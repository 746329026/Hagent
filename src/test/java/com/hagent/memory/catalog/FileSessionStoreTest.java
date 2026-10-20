package com.hagent.memory.catalog;

import com.hagent.memory.MemoryMode;
import com.hagent.config.MemoryProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 会话目录 file 实现的专项测试 —— 只测"这个实现独有、且错了不会报错"的性质。
 *
 * <p>对外语义（懒创建、不覆盖标题与模式、按活跃时间倒序）不在这里重复：
 * 那些由 {@link SessionStoreContractTest} 对两个实现各跑一遍。
 */
class FileSessionStoreTest {

    private Path tempDir;
    private FileSessionStore catalog;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("hagent-catalog-test-");
        catalog = new FileSessionStore(properties(), new ObjectMapper());
    }

    @AfterEach
    void tearDown() throws IOException {
        if (tempDir != null && Files.exists(tempDir)) {
            try (var walk = Files.walk(tempDir)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                        // 测试清理失败不影响结论
                    }
                });
            }
        }
    }

    private MemoryProperties properties() {
        MemoryProperties p = new MemoryProperties();
        p.setFileDir(tempDir.toString());
        return p;
    }

    @Test
    @DisplayName("★ 目录落在 sidecar 文件里（不与会话的消息文件混在一起）")
    void catalogLivesInSidecarFile() throws IOException {
        catalog.touch("conv-a", MemoryMode.CHAT, "第一句话");

        assertThat(tempDir.resolve("_catalog.json"))
                .as("目录必须是【一个】文件：侧栏要列出全部会话，"
                        + "若一会话一文件，每次打开侧栏都要遍历整个目录再排序")
                .exists();
        assertThat(tempDir.resolve("conv-a.json"))
                .as("目录不该顺便创建消息文件 —— 消息是记录层的职责")
                .doesNotExist();
    }

    @Test
    @DisplayName("★ 重新打开（新实例）能读到之前写下的目录 —— 它不是内存里的东西")
    void reopenedInstanceSeesExistingCatalog() {
        catalog.touch("conv-a", MemoryMode.WORKFLOW, "跨实例");

        FileSessionStore reopened = new FileSessionStore(properties(), new ObjectMapper());

        assertThat(reopened.find("conv-a")).hasValueSatisfying(session -> {
            assertThat(session.title()).isEqualTo("跨实例");
            assertThat(session.mode()).isEqualTo("workflow");
        });
    }

    @Test
    @DisplayName("损坏的目录文件按空目录处理，不抛异常")
    void corruptedCatalogIsTreatedAsEmpty() throws IOException {
        Files.writeString(tempDir.resolve("_catalog.json"), "{ 这不是 JSON", java.nio.charset.StandardCharsets.UTF_8);

        assertThat(catalog.list(10)).isEmpty();
        // 而且还能继续写（不能被一个坏文件永久锁死）
        catalog.touch("conv-b", MemoryMode.CHAT, "新会话");
        assertThat(catalog.find("conv-b")).isPresent();
    }
}
