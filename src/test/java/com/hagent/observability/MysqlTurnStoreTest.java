package com.hagent.observability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 阶段 14：回合账的<b>真实落库</b>（真 MySQL）。
 *
 * <h2>★ 为什么它必须是集成测试（而不是假的 JdbcTemplate）</h2>
 *
 * <p>这张表最容易出错的不是 Java 代码，而是<b>Java 与 DDL 的口径是否一致</b>：
 * 列的顺序、可空性、以及"到底哪几个字段被写成 NULL"。这些只有真库能证明 ——
 * 用 Mockito 桩一个 JdbcTemplate 只能证明"我调了 update(…)"，证明不了"写对了列"。
 *
 * <p>同时它也是<b>启动自检</b>的反向证据：本类能加载上下文，就说明
 * {@code MysqlTurnStore} 的构造器在"表 + 三个索引都在"时才放行（决策 8）。
 *
 * <p>⚠️ 前置：先执行 {@code src/main/resources/db/chat_turn.sql}（与另外三张表同一约定）。
 */
@SpringBootTest
class MysqlTurnStoreTest {

    @Autowired
    private TurnStore turnStore;

    @Autowired
    @Qualifier("dataSource")
    private DataSource dataSource;

    @Test
    @DisplayName("★★ 一行账落库后，每个字段都能按原来的口径读回来")
    void recordIsPersistedWithExactSemantics() {
        String turnId = UUID.randomUUID().toString();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        TurnRecord record = new TurnRecord(turnId, "conv-turn-test", "tester", "EMPLOYEE",
                "workflow", TurnStatus.FAILED, "stepFailed",
                1234L, 456L, 7, 3, 89L,
                3000, 700, 3700, 2,
                4, "第 2 步失败：没有产出");
        try {
            turnStore.record(record);

            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT * FROM chat_turn WHERE turn_id = ?", turnId);

            assertThat(row.get("conversation_id")).isEqualTo("conv-turn-test");
            assertThat(row.get("user_id")).as("主体 —— 审计的核心维度").isEqualTo("tester");
            assertThat(row.get("role")).isEqualTo("EMPLOYEE");
            assertThat(row.get("mode")).isEqualTo("workflow");
            assertThat(row.get("status")).isEqualTo("FAILED");
            assertThat(row.get("finish_reason")).isEqualTo("stepFailed");
            assertThat(((Number) row.get("elapsed_ms")).intValue()).isEqualTo(1234);
            assertThat(((Number) row.get("first_token_ms")).intValue()).isEqualTo(456);
            assertThat(((Number) row.get("model_calls")).intValue())
                    .as("★ 整轮 7 次（规划 + 各步），不是最后一次")
                    .isEqualTo(7);
            assertThat(((Number) row.get("tool_calls")).intValue()).isEqualTo(3);
            assertThat(((Number) row.get("tool_ms")).intValue()).isEqualTo(89);
            assertThat(((Number) row.get("total_tokens")).intValue()).isEqualTo(3700);
            assertThat(((Number) row.get("usage_missing_calls")).intValue())
                    .as("★ 与『0 token』区分开的那个计数")
                    .isEqualTo(2);
            assertThat(((Number) row.get("plan_steps")).intValue()).isEqualTo(4);
            assertThat(row.get("error_message")).isEqualTo("第 2 步失败：没有产出");
            assertThat(row.get("created_at")).as("收尾时间由数据库默认值填，Java 不传").isNotNull();
        } finally {
            // 测试数据不留在开发库里（这张表是给真实运行看的）
            jdbc.update("DELETE FROM chat_turn WHERE turn_id = ?", turnId);
        }
    }

    @Test
    @DisplayName("★ chat 的空字段（plan_steps / first_token）允许为 NULL —— 不是 0")
    void nullableFieldsStayNullForChat() {
        String turnId = UUID.randomUUID().toString();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        TurnRecord record = new TurnRecord(turnId, "conv-turn-null", "anonymous", "ANONYMOUS",
                "chat", TurnStatus.CANCELLED, null,
                5L, null, 1, 0, 0L,
                null, null, null, 1,
                null, "客户端断开（或 SSE 超时），本轮未完成");
        try {
            turnStore.record(record);

            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT * FROM chat_turn WHERE turn_id = ?", turnId);

            assertThat(row.get("plan_steps"))
                    .as("chat 没有『计划』这个概念 —— 用 0 会让聚合时看起来像『计划了 0 步』")
                    .isNull();
            assertThat(row.get("first_token_ms"))
                    .as("★ 一个字都没吐出来 ≠ 首字耗时 0ms")
                    .isNull();
            assertThat(row.get("total_tokens"))
                    .as("没拿到 usage ≠ 花了 0 token")
                    .isNull();
            assertThat(row.get("usage_missing_calls")).isEqualTo(1);
        } finally {
            jdbc.update("DELETE FROM chat_turn WHERE turn_id = ?", turnId);
        }
    }
}
