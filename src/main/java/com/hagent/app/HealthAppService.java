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
 * <p><b>★ 依赖探测为什么不放在 {@code /health}</b>：{@code /health} 是<b>存活检查</b>，不碰任何外部
 * 依赖；而 {@code probe()} 会真的去连 MySQL / Redis —— 放进去会让"依赖抖动"变成"进程被判定为死了"，
 * 被编排系统反复重启，<b>把小故障放大成雪崩</b>。{@code /health} 只回 UP，{@code /health/detail}
 * 才给启动探针结论与各依赖此刻是否可达。
 *
 * <p><b>应用只负责"如实报告"，重启交给外部</b>：自动重启容器既是越权（多实例会惊群），也治不了
 * 根因（磁盘满 / OOM 重启后依旧），还会掩盖真正原因；由 docker healthcheck / K8s 决定怎么办。
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
     * <p><b>每次调用都真查</b>（不做缓存）：健康检查的意义就在于"反映此刻"，缓存一个几分钟前的
     * 结论等于自欺。顺序固定为"记录层在前"（它是权威），便于人读日志与 JSON。
     */
    public List<LayerStatus> memoryLayers() {
        return List.of(recordStore.probe(), contextCache.probe());
    }

    /**
     * 就绪判定 —— 给"不解析 JSON 的检查者"一个单一布尔值。
     * 注意 <b>上下文层未启用（none）算就绪</b>：那是配置选择，不是故障。
     */
    public boolean memoryReady() {
        return memoryLayers().stream().allMatch(LayerStatus::reachable);
    }
}
