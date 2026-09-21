package com.hagent.app;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * app 层（应用层 / 业务编排层）的第一个类 —— 目前是占位骨架。
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
 * <p><b>为什么 Controller 不能直接调能力层</b>：
 * <ol>
 *   <li>编排逻辑会与 HTTP 绑死 —— 想给它写单元测试就得先起 Tomcat；</li>
 *   <li>将来出现新入口（定时任务、消息消费者）时，同一套编排逻辑要复制一遍；</li>
 *   <li>同步接口与流式接口要走同一套编排，分开写就是两份重复代码。</li>
 * </ol>
 *
 * <p>本类将在"统一契约与健康检查"阶段被真正填充实现，
 * 届时它会同时被普通接口和 SSE 流式接口复用 —— 那时"为什么要有 app 层"
 * 会有一个可直接验证的答案。
 */
@Slf4j
@Service
public class HealthAppService {

    /**
     * 收集应用与依赖的健康状态。
     *
     * <p>目前返回占位字符串；后续会注入启动探针与各存储客户端，
     * 返回结构化的健康数据（模型可用性 / Redis / pgvector / MySQL）。
     */
    public String describe() {
        return "Hagent application layer is ready";
    }
}
