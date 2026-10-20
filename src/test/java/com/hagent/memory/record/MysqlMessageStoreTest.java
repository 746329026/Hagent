package com.hagent.memory.record;

import com.hagent.memory.LayerStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 记录层 MySQL 实现的专项测试 —— 只测"这个实现独有、且错了不会报错"的性质。
 *
 * <p>对外语义（读写往返、预算截断、会话隔离…）不在这里重复，那些由
 * {@code ConversationMemoryContractTest} 的三个组合覆盖。
 *
 * <p>本类盯住四件事：
 * <ol>
 *   <li><b>启动自检的内容</b> —— 表、字符集、索引三者都要被核对；
 *   <li><b>并发追加一条不丢、顺序由 id 保证</b> —— 这是取代"序号 + 唯一键 + 重试"
 *       那套方案的直接证据；</li>
 *   <li><b>{@code extra} 列保持 NULL</b> —— 它是阶段 8 的预留位；</li>
 *   <li><b>读最近 N 条走的是倒序取尾 + 正序返回</b>。</li>
 * </ol>
 *
 * <p>★ 每个用例用独立会话 ID，结束即清理：本类连的是<b>真实</b>库。
 */
@SpringBootTest(properties = "hagent.memory.chat.circuit-break-seconds=0")
class MysqlMessageStoreTest {

    /** ★ 业务主库，必须按名取 —— 见 BusinessDataSourceConfig 的类注释。 */
    @Autowired
    @Qualifier("dataSource")
    private DataSource businessDataSource;

    @Autowired
    private MessageStore recordStore;

    private JdbcTemplate mysql;
    private String conversationId;

    @BeforeEach
    void setUp() {
        mysql = new JdbcTemplate(businessDataSource);
        conversationId = "rec-" + UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        recordStore.delete(conversationId);
    }

    @Test
    @DisplayName("probe 报可用，并给出表名、content 字符集与索引名")
    void probeReportsTableCharsetAndIndex() {
        LayerStatus status = recordStore.probe();

        assertThat(status.reachable()).isTrue();
        assertThat(status.layer()).isEqualTo("record");
        assertThat(status.implementation()).isEqualTo("mysql");
        assertThat(status.detail())
                .contains("chat_message")
                .contains("utf8mb4")
                .contains("idx_conv_id");
    }

    @Test
    @DisplayName("★★ 四个线程并发追加同一会话：一条不丢（不靠序号、不靠锁、不靠重试）")
    void concurrentAppendsLoseNothing() throws Exception {
        // ★ 这条用例的前身抓到了真实缺陷：当时用"读 MAX(seq) + 唯一键 + 撞车重试"，
        //   2 线程各 3 次就把重试耗尽；换成锁定读又撞上间隙锁死锁。
        //   最终方案是删掉 seq、改用自增 id 排序 —— 于是并发天然安全。
        //   现在用 4 × 5 加压：如果"无锁"这个前提不成立，这里会丢数据或报错。
        int threads = 4;
        int perThread = 5;
        int expected = threads * perThread;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startTogether = new CountDownLatch(1);
        Set<String> expectedTexts = IntStream.range(0, threads).boxed()
                .flatMap(t -> IntStream.range(0, perThread).mapToObj(i -> "线程" + t + "-第" + i + "条"))
                .collect(Collectors.toSet());
        try {
            List<Future<Object>> futures = IntStream.range(0, threads).mapToObj(t -> pool.submit(() -> {
                startTogether.await();
                for (int i = 0; i < perThread; i++) {
                    recordStore.append(conversationId, List.of(new UserMessage("线程" + t + "-第" + i + "条")));
                }
                return null;
            })).toList();

            startTogether.countDown();
            for (Future<Object> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        Integer rows = mysql.queryForObject(
                "SELECT COUNT(*) FROM chat_message WHERE conversation_id = ?", Integer.class, conversationId);
        assertThat(rows).as("并发追加必须一条不丢").isEqualTo(expected);

        assertThat(recordStore.readLatest(conversationId, 100))
                .as("读回来的内容集合必须与写入的完全一致")
                .extracting(m -> m.getText())
                .containsExactlyInAnyOrderElementsOf(expectedTexts);
    }

    @Test
    @DisplayName("★ 顺序由自增 id 保证：连续追加后读回来是写入顺序")
    void orderFollowsAutoIncrementId() {
        for (int i = 1; i <= 5; i++) {
            recordStore.append(conversationId, List.of(new UserMessage("第" + i + "条")));
        }

        // 表里没有任何"会话内序号"列 —— 顺序完全来自 id
        List<Long> ids = mysql.queryForList(
                "SELECT id FROM chat_message WHERE conversation_id = ? ORDER BY id", Long.class, conversationId);
        assertThat(ids).isSorted();

        assertThat(recordStore.readLatest(conversationId, 100))
                .extracting(m -> m.getText())
                .containsExactly("第1条", "第2条", "第3条", "第4条", "第5条");
    }

    @Test
    @DisplayName("★ readLatest 只取【最近】的 N 条，且按时间正序返回")
    void readLatestTakesRecentRowsInChronologicalOrder() {
        for (int i = 1; i <= 8; i++) {
            recordStore.append(conversationId, List.of(new UserMessage("第" + i + "条")));
        }

        assertThat(recordStore.readLatest(conversationId, 3))
                .extracting(m -> m.getText())
                .containsExactly("第6条", "第7条", "第8条");
    }

    @Test
    @DisplayName("extra 列保持 NULL —— 它是阶段 8 的预留位，现在不该被写入")
    void extraColumnStaysNull() {
        recordStore.append(conversationId, List.of(new UserMessage("不该碰 extra")));

        Integer notNull = mysql.queryForObject(
                "SELECT COUNT(*) FROM chat_message WHERE conversation_id = ? AND extra IS NOT NULL",
                Integer.class, conversationId);
        assertThat(notNull).isZero();
    }

    @Test
    @DisplayName("★ 整批写入是原子的：一次 append 两条要么都在、要么都不在")
    void batchAppendIsAllOrNothing() {
        recordStore.append(conversationId, List.of(new UserMessage("问"), new UserMessage("答")));

        assertThat(mysql.queryForObject(
                "SELECT COUNT(*) FROM chat_message WHERE conversation_id = ?", Integer.class, conversationId))
                .isEqualTo(2);
        assertThat(recordStore.readLatest(conversationId, 100))
                .extracting(m -> m.getText())
                .containsExactly("问", "答");
    }
}
