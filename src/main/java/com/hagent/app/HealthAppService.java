package com.hagent.app;

import com.hagent.memory.LayerStatus;
import com.hagent.memory.context.ConversationContextCache;
import com.hagent.memory.record.MessageStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * app 层（应用层 / 业务编排层）—— 收集"应用与依赖此刻是否真的可用"。
 *
 * <p><b>app 层的定位</b>：它是 Controller 和下层能力之间唯一的一层，
 * 负责"<b>谁先谁后、失败怎么办</b>"这类编排逻辑。
 *
 * <pre>
 *   controller   收参数 / 返回结果       （不含业务判断，不含循环）
 *       ↓
 *   app          业务编排                ← 这一层（本类所在）
 *       ↓
 *   core / rag / memory / tools          能力层
 * </pre>
 *
 * <h2>★ 阶段 7：从占位字符串变成"如实报告依赖"</h2>
 *
 * <p>本类的早期版本只返回一句静态的 {@code "Hagent application layer is ready"} ——
 * 那句话在依赖全挂的时候<b>依然是它</b>。阶段 7 起它注入两层记忆能力，
 * 用 {@code probe()} 给出<b>此刻的真实状态</b>。
 *
 * <h2>★★ 为什么依赖探测<b>不</b>放在 {@code /health}</h2>
 *
 * <p>{@code /health} 的定位是<b>存活检查</b>：只证明进程活着，
 * <b>不碰任何外部依赖</b>（它的 javadoc 里就是这么写的）。
 * 而 {@code probe()} 会真的去连 MySQL / Redis —— 把它放进存活检查，
 * 会让"依赖抖动"变成"进程被判定为死了"，进而被编排系统反复重启，
 * <b>把小故障放大成雪崩</b>。
 *
 * <p>所以分工是：
 * <ul>
 *   <li>{@code /health} —— 存活：进程在不在（永远只回 UP）；</li>
 *   <li>{@code /health/detail} —— 就绪 + 诊断：启动探针结论 + 各依赖此刻是否可达。</li>
 * </ul>
 *
 * <h2>★ 应用只负责"如实报告"，重启交给外部</h2>
 *
 * <p>本项目<b>刻意不做</b>"检测到 MySQL 挂了就自动重启容器"：
 * 那既是越权（多实例会惊群、且没有权限控制），也治不了根因（磁盘满 / OOM 重启后依旧），
 * 还会掩盖真正的原因。正确的做法是<b>如实暴露状态</b>，
 * 由 docker healthcheck / K8s / 运维来决定怎么办。
 * 详细理由见 {@link LayerStatus} 的类注释。
 */
@Slf4j
@Service
public class HealthAppService {

    private final MessageStore recordStore;
    private final ConversationContextCache contextCache;

    public HealthAppService(MessageStore recordStore,
                            ConversationContextCache contextCache) {
        this.recordStore = recordStore;
        this.contextCache = contextCache;
    }

    /** 应用层自身的说明 —— 与依赖状态无关，因此保持恒定。 */
    public String describe() {
        return "Hagent application layer is ready";
    }

    /**
     * 记忆两层的此刻状态。
     *
     * <p><b>每次调用都真查</b>（不做缓存）：健康检查的意义就在于"反映此刻"，
     * 缓存一个几分钟前的结论等于自欺。
     *
     * <p>顺序固定为"记录层在前"（它是权威），便于人读日志与 JSON。
     */
    public List<LayerStatus> memoryLayers() {
        return List.of(recordStore.probe(), contextCache.probe());
    }

    /**
     * 就绪判定 —— 给"不解析 JSON 的检查者"一个单一布尔值。
     *
     * <p>注意 <b>上下文层未启用（none）算就绪</b>：那是配置选择，不是故障。
     */
    public boolean memoryReady() {
        return memoryLayers().stream().allMatch(LayerStatus::reachable);
    }
}
