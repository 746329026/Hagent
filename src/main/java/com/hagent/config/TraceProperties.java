package com.hagent.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 调用链日志的配置项，绑定 {@code hagent.trace.*}。
 *
 * <p>为什么日志参数要做成配置项：这里唯一"环境相关"的是截断长度 —— 本地开发想看得多，
 * 生产必须打得少（否则日志文件暴涨，而且用户输入与检索出的企业文档会大量落进日志，
 * 构成隐私风险）。
 *
 * <p>与 {@code PromptLogFormat} 的分工：本类定义【数值与开关】（打多长、要不要打全文），
 * {@code PromptLogFormat} 消费它们（压单行 + 截断的唯一出处）。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "hagent.trace")
public class TraceProperties {

    /** 是否启用调用链日志。出问题需要"安静环境"时可临时关闭。 */
    private boolean enabled = true;

    /**
     * 单条日志中 prompt / 响应文本的最大打印字符数，超出部分截断。
     *
     * <p><b>为什么必须截断</b>：一次 RAG 调用可能带几千字检索结果 ——
     * ① 日志文件暴涨；② 用户输入与内部文档内容全部落进日志，构成隐私风险。
     *
     * <p>500 是折中值：排查时通常看开头几十字就能判断 prompt 拼得对不对，
     * 够看清轮廓又不至于让单条日志过长。
     */
    private int maxLogLength = 500;

    /**
     * 是否打印全文（不截断）。
     *
     * <p>默认 false。需要时临时改成 true，属于"排查具体问题时的临时手段"，不是常态。
     */
    private boolean logFullContent = false;
}
