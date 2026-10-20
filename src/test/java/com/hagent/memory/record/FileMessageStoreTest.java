package com.hagent.memory.record;

import com.hagent.config.MemoryProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
 * 记录层【文件实现】的集成测试（真实读写文件，不打桩）。
 *
 * <p><b>为什么必须有这类测试</b>：文件实现里有几处"错了不会报错"的细节 ——
 * 原子写入、按会话隔离、清空与删除的语义差异。
 * 尤其"历史能不能跨实例重读"这一条：它正是"换实现只改配置"能否成立的基础，
 * 而它在开发时最容易被"反正内存里还有"掩盖过去。
 *
 * <p>★ <b>本类的测试范围（阶段 7 收窄过）</b>：只测<b>记录层</b>的语义 ——
 * 它只按<b>条数</b>取最近 N 条，<b>不做 token 预算裁剪</b>（那是门面的事）。
 * 预算裁剪、以及"对外语义与实现无关"这两件事，由
 * {@code ConversationMemoryContractTest} 在门面层验证。
 *
 * <p>测试用临时目录，互不干扰；用例结束即清理。
 */
class FileMessageStoreTest {

    private Path tempDir;
    private FileMessageStore store;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("hagent-memory-test-");
        store = new FileMessageStore(props(), new ObjectMapper());
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

    private MemoryProperties props() {
        MemoryProperties p = new MemoryProperties();
        p.setFileDir(tempDir.toString());
        return p;
    }

    @Test
    @DisplayName("追加后能原样读回，且消息类型正确还原")
    void appendThenReadLatestRoundTrip() {
        store.append("c1", List.of(new UserMessage("我叫张三"), new AssistantMessage("你好张三")));

        List<Message> loaded = store.readLatest("c1", 100);

        assertThat(loaded).hasSize(2);
        assertThat(loaded.get(0).getText()).isEqualTo("我叫张三");
        assertThat(loaded.get(1).getText()).isEqualTo("你好张三");
        // 消息类型也必须还原正确 —— 否则拼进 prompt 时角色会错
        assertThat(loaded.get(0).getMessageType().name()).isEqualTo("USER");
        assertThat(loaded.get(1).getMessageType().name()).isEqualTo("ASSISTANT");
    }

    @Test
    @DisplayName("★ 换一个实例（模拟重启）仍能读到 —— 这是「数据真的落盘了」的证据")
    void newInstanceCanReadExistingData() {
        store.append("c1", List.of(new UserMessage("持久化验证")));

        FileMessageStore reopened = new FileMessageStore(props(), new ObjectMapper());

        assertThat(reopened.readLatest("c1", 100)).hasSize(1);
        assertThat(reopened.readLatest("c1", 100).get(0).getText()).isEqualTo("持久化验证");
    }

    @Test
    @DisplayName("会话之间互不干扰")
    void conversationsAreIsolated() {
        store.append("c1", List.of(new UserMessage("会话1的消息")));
        store.append("c2", List.of(new UserMessage("会话2的消息")));

        assertThat(store.readLatest("c1", 100).get(0).getText()).isEqualTo("会话1的消息");
        assertThat(store.readLatest("c2", 100).get(0).getText()).isEqualTo("会话2的消息");
    }

    @Test
    @DisplayName("追加是累加，不是覆盖")
    void appendAccumulatesInsteadOfOverwriting() {
        store.append("c1", List.of(new UserMessage("第一句")));
        store.append("c1", List.of(new UserMessage("第二句")));
        store.append("c1", List.of(new UserMessage("第三句")));

        List<Message> loaded = store.readLatest("c1", 100);
        assertThat(loaded).hasSize(3);
        assertThat(loaded.get(0).getText()).isEqualTo("第一句");
        assertThat(loaded.get(2).getText()).isEqualTo("第三句");
    }

    @Test
    @DisplayName("★ readLatest 只按【条数】取最近 N 条，且按时间正序返回")
    void readLatestTakesTheTailInChronologicalOrder() {
        for (int i = 1; i <= 10; i++) {
            store.append("c1", List.of(new UserMessage("第" + i + "条")));
        }

        List<Message> loaded = store.readLatest("c1", 3);

        assertThat(loaded).hasSize(3);
        // 取的是"最近 3 条"，但顺序必须是早 → 晚（可直接拼进 prompt）
        assertThat(loaded.get(0).getText()).isEqualTo("第8条");
        assertThat(loaded.get(1).getText()).isEqualTo("第9条");
        assertThat(loaded.get(2).getText()).isEqualTo("第10条");
    }

    @Test
    @DisplayName("★ 记录层不做 token 预算裁剪：上限很大时仍返回【全部】消息")
    void recordStoreKeepsEverythingWhenAsked() {
        for (int i = 1; i <= 50; i++) {
            store.append("c1", List.of(new UserMessage("消息" + i)));
        }
        // 记录层的职责是"完整"，它不该因为"预算小"而丢数据 ——
        // 裁剪是门面按预算做的事（ConversationMemoryContractTest 覆盖）
        assertThat(store.readLatest("c1", 1000)).hasSize(50);
    }

    @Test
    @DisplayName("clear 清空内容，但会话仍可继续使用")
    void clearRemovesContentButKeepsWorking() {
        store.append("c1", List.of(new UserMessage("要被清空的")));
        store.clear("c1");

        assertThat(store.readLatest("c1", 100)).isEmpty();

        store.append("c1", List.of(new UserMessage("清空后的新消息")));
        assertThat(store.readLatest("c1", 100)).hasSize(1);
    }

    @Test
    @DisplayName("delete 删除会话")
    void deleteRemovesConversation() {
        store.append("c1", List.of(new UserMessage("要被删除的")));
        store.delete("c1");

        assertThat(store.readLatest("c1", 100)).isEmpty();
    }

    @Test
    @DisplayName("未知会话返回空列表而不是 null（否则上游拼 prompt 时会 NPE）")
    void unknownConversationReturnsEmptyListNotError() {
        assertThat(store.readLatest("不存在的会话", 100)).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("原子写入用的 .tmp 文件必须清理干净，不能残留在存储目录里")
    void noTempFileLeftBehind() throws IOException {
        store.append("c1", List.of(new UserMessage("触发一次写入")));

        try (var files = Files.list(tempDir)) {
            assertThat(files.map(p -> p.getFileName().toString()))
                    .noneMatch(name -> name.endsWith(".tmp"));
        }
    }

    @Test
    @DisplayName("probe 报可用，且给出目录路径（健康检查要如实上报）")
    void probeReportsUp() {
        var status = store.probe();
        assertThat(status.reachable()).isTrue();
        assertThat(status.layer()).isEqualTo("record");
        assertThat(status.implementation()).isEqualTo("file");
        assertThat(status.detail()).contains(tempDir.toString());
    }

    @Test
    @DisplayName("★★ 会话 ID 不得穿越目录，也不得撞会话目录的 _catalog.json")
    void conversationIdCannotEscapeOrCollideWithCatalog() throws IOException {
        store.append("../../evil", List.of(new UserMessage("hi")));
        store.append("_catalog", List.of(new UserMessage("hi")));

        assertThat(Files.exists(tempDir.getParent().resolve("evil.json")))
                .as("../../evil 必须被拦在根目录内 —— 否则 file 模式就是任意路径写入")
                .isFalse();

        try (var files = Files.list(tempDir)) {
            var names = files.map(p -> p.getFileName().toString()).toList();
            assertThat(names)
                    .as("两个危险 ID 都应落成 c- 前缀的安全文件（同一 ID 永远同一文件）")
                    .filteredOn(n -> n.startsWith("c-"))
                    .hasSize(2);
            assertThat(names)
                    .as("_catalog 绝不能覆盖会话目录的 sidecar")
                    .doesNotContain("_catalog.json");
        }
    }
}
