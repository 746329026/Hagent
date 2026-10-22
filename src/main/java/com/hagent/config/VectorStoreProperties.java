package com.hagent.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * pgvector 向量库自身的配置项，绑定 {@code spring.ai.vectorstore.pgvector.*}。
 *
 * <p><b>为什么要自己写一份</b>：这些配置原本由官方的 PgVectorStoreProperties 读取，而本项目选择
 * <b>显式装配</b> PgVectorStore（原因见 {@link VectorStoreConfig}：自动装配会复用全局 DataSource，
 * 而这里的全局 DataSource 是业务主库 MySQL）。脱离自动装配后官方属性类不会被创建，
 * 配置项就无人读取 —— 结果仍是"配置文件写了，完全不生效"。
 *
 * <p>自己绑定一份交给 builder，是"显式装配"这个决定必须付出的对应代价：
 * 获得可控性，就要自己管配置。
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
     *
     * <p><b>★ 必须为 true —— 这是实测得出的结论，不要改回去。</b> 框架的
     * {@code PgVectorStore.afterPropertiesSet()} 是：{@code schemaValidation} 为真时校验表名/列名，
     * 然后<b>若 {@code initializeSchema} 为假就直接返回，不建表</b>；而 {@code schemaValidation}
     * 默认也是 false，即"表结构校验"同样是空操作。结论：两者都为假时<b>表永远不会被创建</b>，
     * 直到第一次写入才报错。
     *
     * <p><b>为什么交给框架建表，而不是自己写 DDL</b>：DDL 必须与框架的读写逻辑（列名、类型、
     * id 生成方式）严格一致。自己写等于把这份一致性变成"需要人工维护的约定"，
     * 一旦漂移就是"写入报错"或更糟的"写进了结构相似但不被读取的表"。
     */
    private boolean initializeSchema = true;

    /**
     * 是否做"表名合法性"校验（分隔符、非法字符等）。
     *
     * <p>保持 {@code false}，且<b>刻意不把它接到 builder 上</b>：builder 暴露的是另一个名字相近
     * 但语义不同的开关（{@code vectorTableValidationsEnabled}）。把它硬塞给那个方法，会造成
     * "配置名说一件事、实际做另一件事"。保留这个字段是为了与 {@code application.yml} 一一对应；
     * 真正要保证的"表存在且有正确结构"由启动自检用事实核对，比依赖框架的黑盒校验更可靠。
     */
    private boolean schemaValidation = false;

    /**
     * 是否启用框架自身的"向量表校验"。
     *
     * <p>保持 {@code false}：框架的校验条件较严格（要求已有表的 id 列类型与它生成的 DDL 完全一致），
     * 而本项目要的是"<b>建表</b>"而不是"<b>锁死结构</b>"。表结构与存在性由启动自检负责确认。
     */
    private boolean vectorTableValidationsEnabled = false;

    /** 单批最大文档数（入库时批量写入，避免单条循环 insert） */
    private int maxDocumentBatchSize = 100;
}