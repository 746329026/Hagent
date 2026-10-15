package com.hagent.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 调用链日志的配置项，绑定 {@code hagent.trace.*}。
 *
 * <h2>★ 它为什么叫这个名字（阶段 8 的一次改名）</h2>
 *
 * <p>本类原名 {@code AdvisorProperties}、前缀 {@code hagent.advisor.trace}。
 * 阶段 8 把 advisor 链换成 pipeline 之后，"advisor"这个词在本项目<b>不再存在</b>，
 * 而配置键指向一个已删除的概念，会让后来者去搜 {@code Advisor} 类、然后搜不到 ——
 * 而他不会怀疑配置键，只会怀疑自己的理解。
 *
 * <p>这正是 {@code WORKING-AGREEMENT} 第 12 条规则 1：
 * <b>名字不得携带历史概念</b>。改的只是外壳，三个字段的语义一个字都没变。
 *
 * <h2>为什么日志参数要做成配置项</h2>
 *
 * <p>理由与项目其它配置一致：<b>能被环境改变的 = 配置项</b>。
 * 这里唯一"环境相关"的参数是日志截断长度 —— 本地开发想看得多，
 * 生产必须打得少（否则日志文件暴涨，而且用户输入与检索出的企业文档
 * 会大量落进日志，构成隐私风险）。
 *
 * <h2>与 {@code PromptLogFormat} 的分工</h2>
 *
 * <pre>
 *   TraceProperties    定义【数值与开关】（打多长、要不要打全文、要不要打）
 *   PromptLogFormat    消费【数值】    （压单行 + 截断的唯一出处）
 * </pre>
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
     * <p><b>取值依据</b>：日志的作用是"看清这次调用大概长什么样"，
     * 而不是"完整备份内容"。排查时通常看开头几十字就能判断 prompt 拼得对不对。
     *
     * <p><b>为什么必须截断</b>（对应参考实现的一个真实坑）：
     * 它把完整 prompt 打进 INFO 日志。一次 RAG 调用可能带几千字检索结果，
     * 结果是 ① 日志文件暴涨 ② 用户输入与内部文档内容全部落进日志，形成隐私风险。
     *
     * <p>500 是折中值：够看清内容轮廓，又不至于让单条日志过长。
     */
    private int maxLogLength = 500;

    /**
     * 是否打印全文（不截断）。
     *
     * <p>默认 false。需要时临时改成 true（或用日志级别控制），
     * 属于"排查具体问题时的临时手段"，不是常态。
     */
    private boolean logFullContent = false;
}
