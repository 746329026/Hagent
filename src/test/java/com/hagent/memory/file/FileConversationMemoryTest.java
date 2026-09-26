package com.hagent.memory.file;

import com.hagent.memory.MemoryProperties;
import com.hagent.memory.TokenBudget;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文件记忆实现的集成测试（真实读写文件，不打桩）。
 *
 * <p><b>为什么必须有这类测试</b>：文件实现里有几处"错了不会报错"的细节 ——
 * 原子写入、按会话隔离、清空与删除的语义差异。
 * 尤其"历史能不能跨实例重读"这一条：它正是"换实现只改配置"能否成立的基础，
 * 而它在开发时最容易被"反正内存里还有"掩盖过去。
 *
 * <p>测试用临时目录，互不干扰；用例结束即清理。
 */
class FileConversationMemoryTest {

    private Path tempDir;
    private FileConversationMemory memory;

    private static final TokenBudget BIG = new TokenBudget(1_000_000, 1000);

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("hagent-memory-test-");
        MemoryProperties props = new MemoryProperties();
        props.setFileDir(tempDir.toString());
        memory = new FileConversationMemory(props, new ObjectMapper());
    }

    @AfterEach
    void tearDown() throws IOException {
        if (tempDir != null && Files.exists(tempDir)) {
            try (var walk = Files.walk(tempDir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                        // 清理失败不影响测试结论
                    }
                });
            }
        }
    }

    @Test
    void appendThenLoadRoundTrip() {
        memory.append("c1", List.of(new UserMessage("我叫张三"), new AssistantMessage("你好张三")));

        List<Message> loaded = memory.load("c1", BIG);

        assertThat(loaded).hasSize(2);
        assertThat(loaded.get(0).getText()).isEqualTo("我叫张三");
        assertThat(loaded.get(1).getText()).isEqualTo("你好张三");
        // 消息类型也必须还原正确 —— 否则拼进 prompt 时角色会错
        assertThat(loaded.get(0).getMessageType().name()).isEqualTo("USER");
        assertThat(loaded.get(1).getMessageType().name()).isEqualTo("ASSISTANT");
    }

    @Test
    void newInstanceCanReadExistingData() {
        // ★ 这条测试是"换实现只改配置"能成立的基础：
        //   数据写在磁盘上，换一个实例（模拟重启）仍能读到。
        //   如果用内存实现，这里会失败 —— 这正是两者的本质差别。
        memory.append("c1", List.of(new UserMessage("持久化验证")));

        MemoryProperties props = new MemoryProperties();
        props.setFileDir(tempDir.toString());
        FileConversationMemory reopened = new FileConversationMemory(props, new ObjectMapper());

        assertThat(reopened.load("c1", BIG)).hasSize(1);
        assertThat(reopened.load("c1", BIG).get(0).getText()).isEqualTo("持久化验证");
    }

    @Test
    void conversationsAreIsolated() {
        memory.append("c1", List.of(new UserMessage("会话1的消息")));
        memory.append("c2", List.of(new UserMessage("会话2的消息")));

        assertThat(memory.load("c1", BIG)).hasSize(1);
        assertThat(memory.load("c1", BIG).get(0).getText()).isEqualTo("会话1的消息");
        assertThat(memory.load("c2", BIG).get(0).getText()).isEqualTo("会话2的消息");
    }

    @Test
    void appendAccumulatesInsteadOfOverwriting() {
        memory.append("c1", List.of(new UserMessage("第一句")));
        memory.append("c1", List.of(new UserMessage("第二句")));
        memory.append("c1", List.of(new UserMessage("第三句")));

        List<Message> loaded = memory.load("c1", BIG);
        assertThat(loaded).hasSize(3);
        assertThat(loaded.get(0).getText()).isEqualTo("第一句");
        assertThat(loaded.get(2).getText()).isEqualTo("第三句");
    }

    @Test
    void loadRespectsMessageBudget() {
        for (int i = 1; i <= 10; i++) {
            memory.append("c1", List.of(new UserMessage("第" + i + "条")));
        }
        // 条数上限 3：只应拿到最后 3 条
        List<Message> loaded = memory.load("c1", new TokenBudget(1_000_000, 3));
        assertThat(loaded).hasSize(3);
        assertThat(loaded.get(0).getText()).isEqualTo("第8条");
        assertThat(loaded.get(2).getText()).isEqualTo("第10条");
    }

    @Test
    void clearRemovesContentButKeepsWorking() {
        memory.append("c1", List.of(new UserMessage("要被清空的")));
        memory.clear("c1");

        assertThat(memory.load("c1", BIG)).isEmpty();

        // 清空后仍可继续用（这是 clear 与 delete 的语义差别之一）
        memory.append("c1", List.of(new UserMessage("清空后的新消息")));
        assertThat(memory.load("c1", BIG)).hasSize(1);
    }

    @Test
    void deleteRemovesConversation() {
        memory.append("c1", List.of(new UserMessage("要被删除的")));
        memory.delete("c1");

        assertThat(memory.load("c1", BIG)).isEmpty();
    }

    @Test
    void unknownConversationReturnsEmptyListNotError() {
        // 新会话必须返回空列表而不是 null —— 否则上游拼 prompt 时会 NPE
        assertThat(memory.load("不存在的会话", BIG)).isNotNull().isEmpty();
    }

    @Test
    void noTempFileLeftBehind() throws IOException {
        // 原子写入用的 .tmp 文件必须在写完后清理干净，不能残留在存储目录里
        memory.append("c1", List.of(new UserMessage("触发一次写入")));

        try (var files = Files.list(tempDir)) {
            assertThat(files.map(p -> p.getFileName().toString()))
                    .noneMatch(name -> name.endsWith(".tmp"));
        }
    }
}
