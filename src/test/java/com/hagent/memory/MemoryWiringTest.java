package com.hagent.memory;

import com.hagent.memory.record.MessageStore;
import com.hagent.memory.record.MysqlMessageStore;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 记忆两层的<b>装配</b>测试 —— 真实启动 Spring 上下文，只断言"接线对不对"。
 *
 * <h2>★ 为什么需要这个类（它防的是一个真实存在的陷阱）</h2>
 *
 * <p>本项目有两个数据源（业务主库 MySQL、向量库 PostgreSQL）。而 Spring Boot 的
 * {@code DataSourceAutoConfiguration} 带 <b>{@code @ConditionalOnMissingBean(DataSource.class)}</b>，
 * 用户配置里已经声明了 {@code pgVectorDataSource} —— 于是自动配置会<b>退避</b>，
 * <b>业务主库的数据源可能根本不存在</b>。
 *
 * <p>同理 {@code JdbcTemplateAutoConfiguration} 带
 * {@code @ConditionalOnMissingBean(JdbcOperations.class)}，而
 * {@code pgVectorJdbcTemplate} 是用户 Bean —— 于是容器里按类型注入 {@code JdbcTemplate}
 * 会拿到<b>指向 PostgreSQL</b> 的那个。
 *
 * <p>两种情况的共同症状都极具误导性：<b>"表 chat_message 不存在"</b> ——
 * 很容易被误判成"忘了建表"，而真实原因是"你在问另一个数据库"。
 *
 * <p>所以这里把接线<b>断言下来</b>：两个数据源都在、各自指向正确的库、
 * 记录层用的是 MySQL 那个。
 */
@SpringBootTest
class MemoryWiringTest {

    @Autowired
    private ApplicationContext context;

    private static String urlOf(DataSource dataSource) {
        if (dataSource instanceof HikariDataSource hikari) {
            return hikari.getJdbcUrl();
        }
        try (var conn = dataSource.getConnection()) {
            return conn.getMetaData().getURL();
        } catch (Exception e) {
            return "<取不到 URL：" + e.getMessage() + ">";
        }
    }

    @Test
    @DisplayName("★★ 必须同时存在【业务主库 MySQL】与【向量库 PostgreSQL】两个数据源")
    void bothDataSourcesExistAndPointToTheRightDatabase() {
        Map<String, DataSource> beans = new TreeMap<>(context.getBeansOfType(DataSource.class));
        beans.forEach((name, ds) -> System.out.println("[装配证据] DataSource " + name + " -> " + urlOf(ds)));

        assertThat(beans.keySet())
                .as("少了 dataSource 就说明 DataSourceAutoConfiguration 被 pgVectorDataSource 顶掉了")
                .contains("dataSource", "pgVectorDataSource");

        assertThat(urlOf(beans.get("dataSource")))
                .as("业务主库必须是 MySQL —— 记录层按 bean 名取它")
                .startsWith("jdbc:mysql");
        assertThat(urlOf(beans.get("pgVectorDataSource")))
                .as("向量库必须是 PostgreSQL")
                .startsWith("jdbc:postgresql");
    }

    @Test
    @DisplayName("★★ pgvector 的 JdbcTemplate 必须指向 PostgreSQL")
    void pgVectorJdbcTemplatePointsToPostgres() {
        Map<String, JdbcTemplate> beans = new TreeMap<>(context.getBeansOfType(JdbcTemplate.class));
        beans.forEach((name, jt) ->
                System.out.println("[装配证据] JdbcTemplate " + name + " -> " + urlOf(jt.getDataSource())));

        assertThat(urlOf(beans.get("pgVectorJdbcTemplate").getDataSource()))
                .as("按类型注入会拿到 @Primary 的那个（MySQL），于是 PgVectorStore 会对着 MySQL "
                        + "执行 CREATE EXTENSION IF NOT EXISTS vector —— 真实踩过，所以这里钉死")
                .startsWith("jdbc:postgresql");
    }

    @Test
    @DisplayName("★ 默认配置下记录层必须是 MySQL 实现（不是 file，也不是别的）")
    void recordStoreIsMysql() {
        assertThat(context.getBean(MessageStore.class))
                .isInstanceOf(MysqlMessageStore.class);
    }

    @Test
    @DisplayName("门面是 LayeredConversationMemory —— 切面依赖的抽象背后是两层组合")
    void facadeIsLayered() {
        assertThat(context.getBean(ConversationMemory.class))
                .isInstanceOf(LayeredConversationMemory.class);
    }
}
