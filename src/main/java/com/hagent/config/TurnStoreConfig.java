package com.hagent.config;

import com.hagent.observability.MysqlTurnStore;
import com.hagent.observability.TurnStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * 回合账（{@code chat_turn}）的装配。
 *
 * <p>★ 为什么它<b>没有</b> {@code @ConditionalOnProperty}（与工具审计刻意不同）：工具审计那张表的
 * {@code result} 可能是几 KB 的制度全文、是高流量下增长最快的表；本表一行约 100 字节、一轮一条，
 * 量级差两个数量级，真要止血一条 {@code DELETE FROM chat_turn WHERE created_at < ?} 就够。
 *
 * <p>更重要的理由：<b>多一个能关错的键，就多一种"以为在统计其实没有"</b> ——
 * 而"成功率"这个数一旦悄悄不记了，它的缺失本身是看不见的（不像对话会报错）。
 *
 * <p>★ 为什么数据源必须按名字取：本项目的向量库也是一个 {@code DataSource}，而 {@code @Primary}
 * 会让按类型注入<b>静默</b>拿到错的那个（{@code PgVectorStore} 对着 MySQL 执行
 * {@code CREATE EXTENSION vector}，报错读起来却像"PostgreSQL 版本太老"）。
 */
@Configuration
public class TurnStoreConfig {

    @Bean
    public TurnStore turnStore(@Qualifier("dataSource") DataSource dataSource) {
        // 构造器里做启动自检：表或索引缺失 → 启动即失败并打印可执行的修法
        return new MysqlTurnStore(dataSource);
    }
}
