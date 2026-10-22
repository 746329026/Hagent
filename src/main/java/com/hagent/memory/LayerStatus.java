package com.hagent.memory;

/**
 * 某一层的健康状态 —— 供 {@code /api/health} 如实报告依赖可用性。
 *
 * <p>应用只负责<b>如实报告</b>，重启交给外部（docker healthcheck / K8s liveness / 运维）。
 * "如实报告"的前提是有结构化的状态，而不是一个永远返回 UP 的静态字符串。
 *
 * <p><b>为什么应用不自己重启容器</b>：越权（改基础设施是运维职责，多实例会惊群）、
 * 治不了根因（MySQL 挂掉可能是磁盘满/OOM，重启只让症状消失一会儿）、
 * 技术上也不成立（容器内通常没有 docker CLI，挂 {@code docker.sock} 等于把宿主机权限交给应用）。
 * 所以本项目的做法是：<b>熔断（保住可用性）+ 如实上报（让外部能发现）</b>。
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
