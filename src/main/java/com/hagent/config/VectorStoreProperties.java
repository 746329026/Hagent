package com.hagent.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * pgvector 向量库自身的配置项，绑定 {@code spring.ai.vectorstore.pgvector.*}。
 *
 * <p><b>为什么要自己写一个</b>：Spring AI 自动装配时会用官方那个
 * PgVectorStoreProperties 来读取这些配置。而本项目选择<b>显式装配</b>
 * PgVectorStore（原因见 {@link VectorStoreConfig}：它的自动装配会复用全局
 * DataSource，而本项目全局 DataSource 是业务主库 MySQL）。
 * 一旦脱离自动装配，官方那个属性类就不会被创建，配置项也就无人读取 ——
 * 结果就是"配置文件写了，但完全不生效"。
 *
 * <p>所以这里自己绑定一份，交给 builder 使用。这不是重复造轮子，
 * 而是"显式装配"这个决定必须付出的对应代价 —— 获得可控性，就要自己管配置。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "spring.ai.vectorstore.pgvector")
public class VectorStoreProperties {

    /** 向量表名。用项目专属名，避免与其它项目同名表混淆。 */
    private String tableName = "hagent_knowledge";

    /** 库 schema */
    private String schemaName = "public";

    /**
     * 向量维度。
     * ★ 必须与 embedding 模型的实测维度一致（本项目 mxbai-embed-large = 1024）。
     * 换 embedding 模型即换维度，表必须重建 —— 因为 pgvector 的列类型是 vector(N)，N 固定。
     */
    private int dimensions = 1024;

    /** 索引类型：HNSW（近似最近邻，查询快；建索引较慢、占空间） */
    private PgVectorStore.PgIndexType indexType = PgVectorStore.PgIndexType.HNSW;

    /** 距离度量：余弦距离（文本 embedding 常用） */
    private PgVectorStore.PgDistanceType distanceType = PgVectorStore.PgDistanceType.COSINE_DISTANCE;

    /**
     * 是否自动建表。
     * ★ 本项目设为 false：建表 DDL 由我们显式管理，先看清表结构再动手。
     * 若为 true，PgVectorStore 会自动建表 —— 这也正是"表名配错却没人发现"的温床。
     */
    private boolean initializeSchema = false;

    /** 单批最大文档数（入库时批量写入，避免单条循环 insert） */
    private int maxDocumentBatchSize = 100;
}