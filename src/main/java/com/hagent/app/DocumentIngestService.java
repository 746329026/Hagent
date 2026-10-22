package com.hagent.app;

import com.hagent.common.Fingerprints;

import com.hagent.config.ChunkingProperties;
import com.hagent.config.IngestProperties;
import com.hagent.config.VectorStoreProperties;
import com.hagent.document.AccessLevel;
import com.hagent.document.Chunk;
import com.hagent.document.DocumentKind;
import com.hagent.document.DocumentMetadata;
import com.hagent.document.IngestOutcome;
import com.hagent.document.IngestResult;
import com.hagent.document.LoadedDocument;
import com.hagent.document.SourceLoader;
import com.hagent.document.chunking.ChunkingDispatch;
import com.hagent.exception.AgentException;
import com.hagent.common.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 文档入库服务 —— <b>知识库写入链路的编排者</b>。
 *
 * <pre>
 *   读取文件 → 解析 frontmatter → 算文档哈希
 *        ↓
 *   查库：这个来源已存在吗？哈希变了吗？
 *        ├─ 不存在          → 切分 → 打标签 → embedding → 写入   （ADDED）
 *        ├─ 存在且哈希未变  → 跳过，连切分都不做                   （SKIPPED_UNCHANGED）
 *        └─ 存在但哈希已变  → 删旧块 → 切分 → 打标签 → 写入         （UPDATED）
 * </pre>
 *
 * <h2>幂等判据：{@code source}（文档身份）+ {@code docHash}（内容指纹）</h2>
 *
 * <p>库中没有该 source → 写入；有且 docHash 相同 → <b>跳过</b>（重新切分与 embedding 是纯浪费）；
 * 有但 docHash 变了 → <b>先删后写</b>。<b>删除是必需的步骤</b>：不删旧块就直接写新块，"旧版本 +
 * 新版本"会同时存在于库中，检索时<b>旧内容照样会被命中</b> —— 用户拿到一份已经被废止的规定，
 * 而系统毫无异常。
 *
 * <h2>关于"块 ID"的刻意选择</h2>
 *
 * <p>本项目<b>不自己指定块 ID</b>，交给框架生成随机 UUID：pgvector 表的 id 是 {@code uuid} 类型，
 * 自己指定就得保证生成的字符串是合法 UUID；更重要的是<b>幂等已由 docHash 判定负责</b>，再引入
 * "确定性 ID"就是第二套幂等机制 —— 两套并存时"谁说了算"会变得难以推理。一套机制做一件事。
 *
 * <h2>为什么直查 SQL 而不是只用 VectorStore</h2>
 *
 * <p>{@code VectorStore} 没有"按元数据条件查询"的方法（{@code delete(Filter.Expression)} 只删不查），
 * 而幂等判定恰恰需要"查这个 source 现在是什么哈希"。所以写走 {@code VectorStore}（由它保证与建表
 * DDL 一致），查走 {@code JdbcTemplate} 原生 SQL —— 两条路径访问同一张表，表名与 schema 同源配置。
 */
@Slf4j
@Service
public class DocumentIngestService {

    private final SourceLoader sourceLoader;
    private final ChunkingDispatch chunkingDispatch;
    private final VectorStore vectorStore;
    private final JdbcTemplate jdbcTemplate;
    private final IngestProperties ingestProperties;
    private final ChunkingProperties chunkingProperties;
    private final VectorStoreProperties vectorStoreProperties;

    public DocumentIngestService(SourceLoader sourceLoader,
                                 ChunkingDispatch chunkingDispatch,
                                 VectorStore vectorStore,
                                 // ★ 必须按名取：容器里有两个 JdbcTemplate（本项目的 MySQL 与 pgvector），
                                 //   按类型注入会拿到 @Primary 的那个（MySQL）——见 VectorStoreConfig
                                 //   的 PG_VECTOR_JDBC_TEMPLATE_BEAN 注释里那段实测踩坑
                                 @Qualifier("pgVectorJdbcTemplate") JdbcTemplate pgVectorJdbcTemplate,
                                 IngestProperties ingestProperties,
                                 ChunkingProperties chunkingProperties,
                                 VectorStoreProperties vectorStoreProperties) {
        this.sourceLoader = sourceLoader;
        this.chunkingDispatch = chunkingDispatch;
        this.vectorStore = vectorStore;
        this.jdbcTemplate = pgVectorJdbcTemplate;
        this.ingestProperties = ingestProperties;
        this.chunkingProperties = chunkingProperties;
        this.vectorStoreProperties = vectorStoreProperties;
    }

    // ---- 对外入口 ----

    /**
     * 入库一份文档（幂等）。
     *
     * @param source 相对知识库根目录的来源路径
     * @return 处理结论 —— <b>调用方必须区分 ADDED / UPDATED / SKIPPED</b>，
     *         因为"跳过"和"写入"是两种不同的正常结果，混在一起会掩盖"同步没生效"
     */
    public IngestResult ingest(String source) {
        Path root = Path.of(ingestProperties.getRootDir());

        LoadedDocument loaded;
        try {
            loaded = sourceLoader.load(root, source, ingestProperties.getDefaultKind(), ingestProperties.getExtensions());
        } catch (IOException e) {
            throw new AgentException(ErrorCode.STORAGE_UNAVAILABLE,
                    "读取知识库文档失败：" + source + "（" + e.getMessage() + "）", e);
        }

        String docHash = computeDocHash(loaded);
        String existingHash = findExistingDocHash(loaded.source());

        if (docHash.equals(existingHash)) {
            log.info("[知识入库] 跳过（内容未变）：来源={} 哈希={}", loaded.source(), shortHash(docHash));
            return IngestResult.skipped(loaded.source(), docHash);
        }

        boolean isUpdate = existingHash != null;
        if (isUpdate) {
            // 先删旧块：不删会导致旧版本内容继续被检索命中（见类注释）
            deleteBySource(loaded.source());
            log.info("[知识入库] 检测到内容变更，已删除旧块：来源={} 旧哈希={} 新哈希={}",
                    loaded.source(), shortHash(existingHash), shortHash(docHash));
        }

        List<Chunk> chunks = chunkingDispatch.chunk(
                loaded.body(),
                loaded.kind().chunkingStrategy(),
                chunkingProperties.getMaxSize(),
                chunkingProperties.getOverlap(),
                loaded.source());

        if (chunks.isEmpty()) {
            // 正文为空（如只有 frontmatter 的占位文件）。这不是错误，但必须可见 ——
            // 静默成功会让作者以为"文档已经进知识库了"
            log.warn("[知识入库] 文档正文为空，未写入任何块：来源={}", loaded.source());
            return new IngestResult(loaded.source(),
                    isUpdate ? IngestOutcome.UPDATED : IngestOutcome.ADDED, 0, docHash);
        }

        List<Document> documents = toDocuments(loaded, chunks, docHash);
        vectorStore.add(documents);

        log.info("[知识入库] {}：来源={} 种类={} 密级={} 块数={} 策略={}",
                isUpdate ? "更新" : "新增", loaded.source(), loaded.kind(), loaded.level(),
                documents.size(), loaded.kind().chunkingStrategy());

        return new IngestResult(loaded.source(),
                isUpdate ? IngestOutcome.UPDATED : IngestOutcome.ADDED, documents.size(), docHash);
    }

    /**
     * 批量入库整个知识库目录（递归扫描）。
     *
     * <p><b>单份失败不中止整批</b>，而是记录后继续 —— 一份格式不对就让整批不执行，会把"98 份成功"
     * 变成"0 份成功"。但失败必须被<b>报告出来</b>（见 {@link BatchIngestReport}），绝不能静默吞掉：
     * "以为同步了 99 份，实际只有 98 份"是最难发现的一类问题。
     */
    public BatchIngestReport ingestAll() {
        Path root = Path.of(ingestProperties.getRootDir());
        if (!Files.isDirectory(root)) {
            throw new AgentException(ErrorCode.INVALID_PARAM,
                    "知识库根目录不存在：" + root.toAbsolutePath());
        }

        List<String> sources = listCandidateSources(root);
        List<IngestResult> results = new ArrayList<>(sources.size());
        List<BatchIngestReport.Failure> failures = new ArrayList<>();

        for (String source : sources) {
            try {
                results.add(ingest(source));
            } catch (RuntimeException e) {
                // 单份失败不影响整批，但必须被记录（见方法注释）
                log.error("[知识入库] 单份文档处理失败，已跳过继续：来源={}", source, e);
                failures.add(new BatchIngestReport.Failure(source, e.getMessage()));
            }
        }

        BatchIngestReport report = new BatchIngestReport(results, failures);
        log.info("[知识入库] 批量完成：扫描={} 新增={} 更新={} 跳过={} 失败={}",
                sources.size(), report.addedCount(), report.updatedCount(),
                report.skippedCount(), failures.size());
        if (!failures.isEmpty()) {
            log.warn("[知识入库] 有 {} 份文档未能入库，详情见上方 ERROR 日志或报告的 failures 字段",
                    failures.size());
        }
        return report;
    }

    // ---- 幂等判定：直查向量表的 metadata ----

    /**
     * 查询某个来源当前在库里的文档哈希；该来源不存在时返回 {@code null}。
     *
     * <p><b>SQL 的两个安全要点</b>：<b>表名只能用字符串拼接</b>（SQL 不支持表名占位符；它来自我们
     * 自己的配置，不是外部输入），额外加双引号以免表名恰好是 SQL 关键字；<b>{@code source} 必须用
     * 占位符</b> —— 它最终来自文件路径（外部输入），拼进 SQL 就是注入漏洞。
     *
     * <p>用 {@code LIMIT 1}：一篇文档的所有块携带<b>同一个</b> docHash，取任意一条即可，顺便避免
     * "某篇文档块特别多"时扫全表。{@code metadata ->> 'docHash'} 的 {@code ->>} 是把 json 取成 text。
     */
    private String findExistingDocHash(String source) {
        String sql = String.format(
                "SELECT metadata ->> '%s' AS doc_hash FROM %s WHERE metadata ->> '%s' = ? LIMIT 1",
                DocumentMetadata.FIELD_DOC_HASH, qualifiedTableName(), DocumentMetadata.FIELD_SOURCE);
        List<String> rows = jdbcTemplate.query(
                sql, (rs, rowNum) -> rs.getString("doc_hash"), source);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 按来源删除该文档的全部块。 */
    private void deleteBySource(String source) {
        Filter.Expression filter = new FilterExpressionBuilder()
                .eq(DocumentMetadata.FIELD_SOURCE, source)
                .build();
        vectorStore.delete(filter);
    }

    private String qualifiedTableName() {
        return String.format("\"%s\".\"%s\"",
                vectorStoreProperties.getSchemaName(), vectorStoreProperties.getTableName());
    }

    // ---- 切分结果 → 向量库文档 ----

    /**
     * 把切好的块包装成 {@link Document}（带上完整元数据）。
     *
     * <p><b>这里只做"包装"，不做任何判断</b> —— 密级、种类、有效期都由 {@link LoadedDocument} 决定。
     * 这样"入库写什么标签"只有一个决策点，而不是两处都可能改。
     */
    private List<Document> toDocuments(LoadedDocument loaded, List<Chunk> chunks, String docHash) {
        List<Document> documents = new ArrayList<>(chunks.size());
        for (Chunk chunk : chunks) {
            String contentHash = Fingerprints.content(chunk.text());
            DocumentMetadata metadata = new DocumentMetadata(
                    loaded.source(),
                    loaded.title(),
                    chunk.section(),
                    chunk.index(),
                    chunk.total(),
                    contentHash,
                    docHash,
                    loaded.kind(),
                    loaded.level(),
                    loaded.dept(),
                    loaded.validFrom(),
                    loaded.validTo());
            documents.add(new Document(chunk.text(), metadata.toMap()));
        }
        return documents;
    }

    /**
     * 计算文档指纹。
     *
     * <p>包含<b>正文 + 关键 frontmatter 字段</b>，而不是只哈希正文：<b>只改了密级（或种类、有效期）
     * 时正文没变，但入库结果必须改变</b>。若指纹只算正文，"把某文档从 INTERNAL 改成 PUBLIC"会被
     * 判定为"内容未变"而跳过 —— 权限调整静默失效，这是安全问题而非功能问题。
     *
     * <p>刻意<b>不</b>把 {@code dept} 参与哈希：它来自目录级配置，改了配置会让全库重新入库，代价
     * 大于收益。这是明确的取舍，所以写明 —— 将来若要"按部门改密级"，需重新评估这一条。
     */
    private String computeDocHash(LoadedDocument loaded) {
        StringBuilder sb = new StringBuilder();
        sb.append("body:").append(loaded.body()).append('\n');
        sb.append("kind:").append(loaded.kind().name()).append('\n');
        sb.append("level:").append(loaded.level().name()).append('\n');
        sb.append("title:").append(loaded.title()).append('\n');
        if (loaded.validFrom() != null) {
            sb.append("validFrom:").append(loaded.validFrom()).append('\n');
        }
        if (loaded.validTo() != null) {
            sb.append("validTo:").append(loaded.validTo()).append('\n');
        }
        return Fingerprints.content(sb.toString());
    }

    // ---- 目录扫描 ----

    /**
     * 递归列出知识库目录下的候选文档。
     *
     * <p>返回<b>相对路径且统一用 {@code /}</b> —— 与 {@code LoadedDocument#source()} 的约定一致，
     * 否则同一份文档会因分隔符不同而被当成两份。<b>排序是刻意的</b>：批量入库顺序稳定（按路径字典序），
     * 日志与报告的先后顺序才能在多次运行之间比较。
     */
    private List<String> listCandidateSources(Path root) {
        List<String> extensions = ingestProperties.getExtensions();
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> matchesExtension(p, extensions))
                    // 跳过隐藏文件/目录（如 .git 内的文件、编辑器临时文件）
                    .filter(p -> !isHidden(root.relativize(p)))
                    .map(p -> root.relativize(p).toString().replace('\\', '/'))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new AgentException(ErrorCode.STORAGE_UNAVAILABLE,
                    "扫描知识库目录失败：" + root.toAbsolutePath(), e);
        }
    }

    private boolean matchesExtension(Path path, List<String> extensions) {
        String name = path.getFileName().toString().toLowerCase();
        return extensions.stream().anyMatch(ext -> name.endsWith(ext.toLowerCase()));
    }

    private boolean isHidden(Path relative) {
        for (Path part : relative) {
            if (part.toString().startsWith(".")) {
                return true;
            }
        }
        return false;
    }

    private static String shortHash(String hash) {
        if (hash == null) {
            return "null";
        }
        return hash.length() <= 12 ? hash : hash.substring(0, 12);
    }
}
