package com.hagent.memory.catalog;

import com.hagent.memory.MemoryMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 会话目录 MySQL 实现的测试 —— 盯住三条错了也不会报错的性质：
 *
 * <ol>
 *   <li>已存在的会话不覆盖标题与模式：用户改过的名字不能被下一句话刷回去；模式一旦确定就不能被请求改掉
 *       （否则预算能来回跳 10 倍且事后查不到）；</li>
 *   <li>改名成同一个标题仍然算成功：MySQL 的 {@code UPDATE} 影响行数在"新旧值相同"时是 0，
 *       当成"会话不存在"会让前端收到 404；</li>
 *   <li>按活跃时间倒序：侧栏的顺序全靠它。</li>
 * </ol>
 *
 * <p>每个用例用独立会话 ID、结束即清理：本类连的是真实库。
 */
@SpringBootTest(properties = "hagent.memory.chat.circuit-break-seconds=0")
class MysqlSessionStoreTest {

    @Autowired
    private SessionStore catalog;

    private String conversationId;

    @BeforeEach
    void setUp() {
        conversationId = "sess-" + UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        catalog.delete(conversationId);
    }

    @Test
    @DisplayName("★ 懒创建：touch 之后 find 能拿到标题与模式，且模式存的是线上名称 chat/workflow")
    void touchCreatesSession() {
        catalog.touch(conversationId, MemoryMode.CHAT, "公司年假政策怎么规定的");

        assertThat(catalog.find(conversationId)).hasValueSatisfying(session -> {
            assertThat(session.title()).isEqualTo("公司年假政策怎么规定的");
            assertThat(session.mode())
                    .as("★ 存的是线上名称 chat（小写；枚举名是 CHAT）——"
                            + "枚举改名/大小写变化都不会牵动请求体、响应体与数据库")
                    .isEqualTo("chat");
            assertThat(session.createdAt()).isNotNull();
            assertThat(session.updatedAt()).isNotNull();
        });

        catalog.touch(conversationId, MemoryMode.WORKFLOW, "换一种模式");
        assertThat(catalog.find(conversationId)).hasValueSatisfying(session ->
                assertThat(session.mode())
                        .as("★ 已存在的会话，模式【不能被后来的请求改掉】——"
                                + "否则客户端每轮换一次模式，历史预算在 5k/50k 之间来回跳且查不到原因")
                        .isEqualTo("chat"));
    }

    @Test
    @DisplayName("★ 已存在的会话：标题不被下一句话覆盖（用户改过的名字必须留住）")
    void touchDoesNotOverwriteTitle() {
        catalog.touch(conversationId, MemoryMode.CHAT, "第一句话");
        assertThat(catalog.rename(conversationId, "我给它起的名字")).isTrue();

        catalog.touch(conversationId, MemoryMode.CHAT, "第二句话");

        assertThat(catalog.find(conversationId)).hasValueSatisfying(session ->
                assertThat(session.title())
                        .as("★ 若这里变回『第一句话』，用户改的名字就在下一轮消失了 ——"
                                + "而 INSERT ... ON DUPLICATE KEY UPDATE 少写一个条件就是这种效果")
                        .isEqualTo("我给它起的名字"));
    }

    @Test
    @DisplayName("★ 改名成【同一个标题】仍然返回成功（MySQL 影响行数为 0 的坑）")
    void renameToSameTitleStillSucceeds() {
        catalog.touch(conversationId, MemoryMode.CHAT, "原名字");

        assertThat(catalog.rename(conversationId, "原名字"))
                .as("★ UPDATE 的影响行数在『新旧值相同』时是 0。把它当成『会话不存在』，"
                        + "前端在用户原样提交时会收到 404，看起来像会话丢了")
                .isTrue();
        assertThat(catalog.rename("sess-不存在-" + UUID.randomUUID(), "随便"))
                .as("而真的不存在时必须返回 false（由上层翻成 404）")
                .isFalse();
    }

    @Test
    @DisplayName("按活跃时间倒序：刚 touch 过的会话排在前面")
    void listOrdersByUpdatedAtDesc() throws Exception {
        String older = conversationId;
        String newer = "sess-" + UUID.randomUUID();
        try {
            catalog.touch(older, MemoryMode.CHAT, "先创建的");
            Thread.sleep(5); // updated_at 是 DATETIME(3)，同一毫秒内两行无法明确排序
            catalog.touch(newer, MemoryMode.CHAT, "后创建的");

            assertThat(catalog.list(100))
                    .extracting(SessionStore.Session::conversationId)
                    .as("侧栏按『最近活跃』倒序 —— 顺序错了用户会觉得会话在乱跳")
                    .containsSubsequence(newer, older);
        } finally {
            catalog.delete(newer);
        }
    }

    @Test
    @DisplayName("标题超长被截断，而不是让整轮对话写不进去")
    void longTitleIsTruncated() {
        String longSeed = "很".repeat(200);
        catalog.touch(conversationId, MemoryMode.CHAT, longSeed);

        assertThat(catalog.find(conversationId)).hasValueSatisfying(session ->
                assertThat(session.title())
                        .as("标题列是 VARCHAR(128) —— 截断否则 INSERT 直接失败，"
                                + "而那一批写入里还有【整轮对话的消息】")
                        .hasSize(SessionStore.TITLE_MAX_LENGTH));
    }
}
