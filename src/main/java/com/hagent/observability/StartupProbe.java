package com.hagent.observability;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 启动探针 —— 阶段 0 的核心验收手段。
 *
 * <p><b>为什么需要它</b>：验证类代码最常见的失败方式是"静默不执行"——
 * 程序看起来一切正常，其实什么都没检查。所以本类的设计原则是：
 * <ol>
 *   <li>整个探测过程包在 try-catch(Throwable) 里，任何异常都写入结果并 log.error，
 *       绝不允许"探针失败 = 静默无输出"；</li>
 *   <li>区分两类证据：<b>Bean 归属</b>证明"Spring 装配了谁"，
 *       <b>真实调用</b>证明"它真的能用" —— 两者层次不同，缺一不可；</li>
 *   <li>幂等 + 可手动重跑（供 /health/detail?refresh=true 使用）。</li>
 * </ol>
 *
 * <p><b>本项目已由探针抓到的真实问题</b>：
 * <ul>
 *   <li>chat 模型名写错（deepseek-chat 不存在）时，API 会<b>静默降级</b>到
 *       deepseek-flash 且不报错 —— 所以探针必须核对"实际返回的模型名"，
 *       而不只是看"我配置了什么"；</li>
 *   <li>embedding 维度是 pgvector 建表 vector(N) 的依据，必须实测而非照抄。</li>
 * </ul>
 */
@Slf4j
@Component
public class StartupProbe {

    private final ChatModel chatModel;
    private final EmbeddingModel embeddingModel;
    private final StringRedisTemplate redisTemplate;
    private final ApplicationContext applicationContext;

    @Value("${spring.ai.deepseek.api-key:}")
    private String deepseekApiKey;

    @Value("${spring.ai.deepseek.chat.options.model:unknown}")
    private String configuredChatModel;

    @Value("${hagent.probe.live-call:true}")
    private boolean liveCall;

    /** 探针结论快照，供 /health/detail 暴露，把"我验证过"变成可复现的验证。 */
    private final Map<String, String> snapshot = Collections.synchronizedMap(new LinkedHashMap<>());
    private final AtomicBoolean executed = new AtomicBoolean(false);
    private volatile boolean allPassed;

    public StartupProbe(ChatModel chatModel,
                        EmbeddingModel embeddingModel,
                        StringRedisTemplate redisTemplate,
                        ApplicationContext applicationContext) {
        this.chatModel = chatModel;
        this.embeddingModel = embeddingModel;
        this.redisTemplate = redisTemplate;
        this.applicationContext = applicationContext;
    }

    /** 应用就绪后自动执行一次（此时所有 Bean 已完成注入，是探测的最佳时机）。 */
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        log.debug("[探针] 收到 ApplicationReadyEvent，开始探测（liveCall={}）", liveCall);
        runProbe();
    }

    /**
     * 执行探测（幂等，已执行过则直接返回）。
     *
     * <p>这里用 synchronized 是合理场景：保护的是确实被多线程共享且非线程安全的
     * LinkedHashMap，且只串行化"探测"这个低频动作，不影响业务吞吐。
     */
    public synchronized void runProbe() {
        if (!executed.compareAndSet(false, true)) {
            log.debug("[探针] 已执行过，跳过重复执行");
            return;
        }
        try {
            doProbe();
        } catch (Throwable t) {
            log.error("[探针] 执行中抛出未预期异常（这本身就是一个需要修的缺陷）", t);
            snapshot.put("探针自身", "[!!] 执行异常: " + t.getClass().getSimpleName());
            allPassed = false;
        }
    }

    /** 强制重跑（会真实调用模型，因此不设为健康检查的默认行为）。 */
    public synchronized void rerun() {
        executed.set(false);
        runProbe();
    }

    private void doProbe() {
        List<ProbeItem> items = new ArrayList<>();

        // ---- 1. Bean 归属：证明"Spring 装配了什么" ----
        String chatImpl = chatModel.getClass().getName();
        String embedImpl = embeddingModel.getClass().getName();
        boolean keyPresent = isKeyPresent();

        items.add(new ProbeItem("ChatModel 实现", chatImpl, chatImpl.toLowerCase().contains("deepseek")));
        items.add(new ProbeItem("EmbeddingModel 实现", embedImpl, embedImpl.toLowerCase().contains("ollama")));
        items.add(new ProbeItem("配置的 chat 模型", configuredChatModel, true));
        items.add(new ProbeItem("DeepSeek API Key",
                keyPresent ? "已配置(长度=" + deepseekApiKey.length() + ")" : "未配置", keyPresent));

        // ---- 2. 真实调用：证明"它真的能用" ----
        if (liveCall) {
            items.add(probeChat());
            items.add(probeEmbedding());
        } else {
            items.add(new ProbeItem("ChatModel 实调", "已跳过(live-call=false)", true));
            items.add(new ProbeItem("Embedding 实测维度", "已跳过(live-call=false)", true));
        }

        // ---- 3. 依赖服务 ----
        items.add(probeRedis());
        items.add(probeVectorStore());

        publish(items);
    }

    /**
     * 真调云端 chat，并<b>核对实际返回的模型名</b>。
     *
     * <p>为什么必须核对：请求一个不存在的模型名时，API 会静默降级并正常返回，
     * 只会把响应里的 model 字段换成实际使用的模型。不核对就发现不了 ——
     * 你会以为在用一个强模型，实际在用另一个。实测已验证该行为。
     */
    private ProbeItem probeChat() {
        if (!isKeyPresent()) {
            return new ProbeItem("ChatModel 实调", "跳过（无 API Key）", false);
        }
        long start = System.currentTimeMillis();
        try {
            ChatResponse response = chatModel.call(new Prompt("ping"));
            long cost = System.currentTimeMillis() - start;

            String returnedModel = "未知";
            ChatResponseMetadata meta = response.getMetadata();
            if (meta != null && meta.getModel() != null) {
                returnedModel = meta.getModel();
            }

            boolean modelMatched = configuredChatModel.equalsIgnoreCase(returnedModel);
            String detail = "OK(" + cost + "ms) 返回模型=" + returnedModel
                    + (modelMatched ? " [与配置一致]" : " [!! 与配置不一致，可能发生了静默降级]");
            return new ProbeItem("ChatModel 实调", detail, modelMatched);
        } catch (Exception e) {
            return new ProbeItem("ChatModel 实调",
                    "失败 " + e.getClass().getSimpleName() + ": " + brief(e.getMessage()), false);
        }
    }

    /**
     * 真调本地 embedding，<b>维度是本次探测最重要的产出</b>：
     * 它直接决定 pgvector 建表时 vector(N) 里的 N，写错则入库报维度不匹配。
     */
    private ProbeItem probeEmbedding() {
        long start = System.currentTimeMillis();
        try {
            float[] vector = embeddingModel.embed("Hagent 维度探测");
            return new ProbeItem("Embedding 实测维度",
                    vector.length + " 维 (" + (System.currentTimeMillis() - start) + "ms)", vector.length > 0);
        } catch (Exception e) {
            return new ProbeItem("Embedding 实测维度",
                    "失败 " + e.getClass().getSimpleName() + ": " + brief(e.getMessage()), false);
        }
    }

    private ProbeItem probeRedis() {
        try {
            if (redisTemplate.getConnectionFactory() == null) {
                return new ProbeItem("Redis 连通性", "无连接工厂", false);
            }
            String pong = redisTemplate.getConnectionFactory().getConnection().ping();
            return new ProbeItem("Redis 连通性", "PONG=" + pong, "PONG".equalsIgnoreCase(pong));
        } catch (Exception e) {
            return new ProbeItem("Redis 连通性", "不可用 " + e.getClass().getSimpleName(), false);
        }
    }

    /**
     * 向量库探测：直接问 PostgreSQL"这个库装没装 vector 扩展"。
     * 比"能否建立连接"更强的证据 —— 它同时证明扩展已安装（建表的前提）。
     */
    private ProbeItem probeVectorStore() {
        try {
            DataSource ds = applicationContext.getBean(
                    com.hagent.config.VectorStoreConfig.PG_VECTOR_DATASOURCE_BEAN, DataSource.class);
            try (Connection conn = ds.getConnection();
                 Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT extversion FROM pg_extension WHERE extname = 'vector'")) {
                String url = conn.getMetaData().getURL();
                if (rs.next()) {
                    return new ProbeItem("pgvector 连通/扩展", "vector " + rs.getString(1) + " @ " + url, true);
                }
                return new ProbeItem("pgvector 连通/扩展", "已连接但未安装 vector 扩展 @ " + url, false);
            }
        } catch (Exception e) {
            return new ProbeItem("pgvector 连通/扩展",
                    "不可用 " + e.getClass().getSimpleName() + ": " + brief(e.getMessage()), false);
        }
    }

    private void publish(List<ProbeItem> items) {
        snapshot.clear();
        items.forEach(i -> snapshot.put(i.name(), (i.ok() ? "[OK] " : "[!!] ") + i.value()));
        long failed = items.stream().filter(i -> !i.ok()).count();
        this.allPassed = failed == 0;

        StringBuilder sb = new StringBuilder();
        snapshot.forEach((k, v) -> sb.append(String.format("  %-24s : %s%n", k, v)));
        sb.append(allPassed
                ? "  >>> 全部通过：模型 / 密钥 / 存储装配符合预期 <<<"
                : "  >>> " + failed + " 项未通过，请逐项检查 <<<");

        log.info("""

                ======================== Hagent 启动探针 ========================
                {}
                ================================================================""", sb);
    }

    /** 对外暴露的探针快照（不可变视图）。 */
    public Map<String, String> snapshot() {
        synchronized (snapshot) {
            return Map.copyOf(snapshot);
        }
    }

    public boolean allPassed() {
        return allPassed;
    }

    private boolean isKeyPresent() {
        return deepseekApiKey != null && !deepseekApiKey.isBlank();
    }

    private String brief(String s) {
        if (s == null) {
            return "无消息";
        }
        String one = s.strip().replaceAll("\\s+", " ");
        return one.length() > 90 ? one.substring(0, 90) + "..." : one;
    }

    /** 一条探测结果：只描述"实际是什么"，不描述"我期望什么"。 */
    private record ProbeItem(String name, String value, boolean ok) {
    }
}