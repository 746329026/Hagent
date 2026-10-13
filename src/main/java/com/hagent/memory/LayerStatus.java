package com.hagent.memory;

/**
 * 某一层的健康状态 —— 供 {@code /api/health} 如实报告依赖可用性。
 *
 * <p><b>为什么需要它</b>：应用只负责"<b>如实报告</b>"，重启交给外部
 * （docker healthcheck / K8s liveness / 运维）。而"如实报告"的前提是有结构化的状态，
 * 不是一个永远返回 UP 的静态字符串。
 *
 * <h2>★ 为什么不让应用自己去重启容器</h2>
 *
 * <p>这是刻意的边界，三条理由：
 * <ol>
 *   <li><b>越权</b>：改基础设施是运维职责。多实例部署时 N 个实例会<b>同时</b>尝试重启
 *       同一个容器（惊群），且没有任何权限控制；</li>
 *   <li><b>治不了根因</b>：MySQL 挂掉的原因可能是磁盘满 / OOM / 配置错 ——
 *       重启只让症状消失一会儿，<b>真正的原因被掩盖</b>；</li>
 *   <li><b>技术上也不成立</b>：应用容器内通常没有 docker CLI，要挂 {@code docker.sock}
 *       才能操作容器 —— 那等于把宿主机权限交给应用。</li>
 * </ol>
 *
 * <p>所以本项目的做法是：<b>熔断（保住可用性）+ 如实上报（让外部能发现）</b>。
 *
 * @param layer          层名（{@code record} / {@code context}），用于日志与 JSON 字段
 * @param implementation 实际生效的实现名（{@code mysql} / {@code file} / {@code redis} / {@code none}）
 * @param reachable      这一层此刻是否可用
 * @param detail         人类可读的细节（失败原因 / 表名 / TTL 等）—— <b>不要放敏感信息</b>
 */
public record LayerStatus(String layer, String implementation, boolean reachable, String detail) {

    /** 便捷构造：可用。 */
    public static LayerStatus up(String layer, String implementation, String detail) {
        return new LayerStatus(layer, implementation, true, detail);
    }

    /** 便捷构造：不可用。 */
    public static LayerStatus down(String layer, String implementation, String detail) {
        return new LayerStatus(layer, implementation, false, detail);
    }
}
