package com.hagent.app;

import com.hagent.dto.TaskIntent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 任务意图理解 —— 办公 Agent 的入口能力。
 *
 * <p><b>它做什么</b>：把用户的一句自然语言请求，变成结构化的
 * {@link TaskIntent}（意图 / 交付物 / 硬性要求 / 是否需要二次确认）。
 *
 * <p><b>为什么这是 Agent 的第一步</b>：拿到结构化结果，下游才能做<b>确定性的判断</b> ——
 * 该调哪些工具、要不要先问用户确认、要产出什么文件。如果直接让模型自由发挥，
 * 这些判断只能靠解析自然语言文本，脆弱且不可靠。
 *
 * <h2>★ 提示词为什么放在资源文件里，而不是 Java 常量</h2>
 *
 * <p>提示词是<b>业务语义</b>的载体（字段含义、取值字典、判断规则），
 * 它会频繁调整，而调整不应该需要改 Java 代码、重新编译。
 * 放在 {@code prompts/*.md} 里还可以：
 * <ul>
 *   <li>版本化管理（git 能直接 diff 出"提示词改了什么"）；</li>
 *   <li>非 Java 背景的人也能改；</li>
 *   <li>写 Markdown 结构（标题、列表），可读性远好于字符串拼接。</li>
 * </ul>
 *
 * <h2>★ 这里有一道解析后校验（对应"双字典"维护陷阱）</h2>
 *
 * <p>交付物取值定义在两个地方：{@link TaskIntent.Deliverable 枚举}（代码侧）
 * 和提示词里的字典（模型侧）。两者没有任何编译期联系，失步时可能静默失效。
 * 因此本类在拿到结果后<b>显式校验</b>：解析出的交付物必须在枚举内、
 * 且不能为空 —— 把"改了没同步"变成明确失败。
 *
 * <p>虽然框架的 JSON Schema 校验通常已经会拦下非法枚举值，
 * 但校验规则不止"值是否合法"，还包括"业务上是否为空 / 是否违反组合约束"，
 * 这类规则框架管不了，必须自己写。所以这不是多余的防御。
 */
@Slf4j
@Service
public class TaskIntentAppService {

    private final ChatClient chatClient;

    /**
     * 提示词来自 classpath 资源，构造时注入。
     *
     * <p>刻意<b>不用</b> {@code .system(String)} 内联长文本：那样提示词会和业务代码混在一起，
     * 改一句话就要重新编译（详见 {@code AiConfig#taskIntentPrompt()}）。
     *
     * <p><b>为什么手写构造器而不是用 {@code @RequiredArgsConstructor}</b>：
     * 需要给这个参数加 {@code @Qualifier} 按 Bean 名限定。
     * 类型是 {@code Resource}，容器里可能存在多个 Resource 类型的 Bean
     * （Spring Boot 自身也会注册一些），不加限定会有"注入到意料之外的资源"的风险 ——
     * 而这类错误不会在编译期暴露，只会在运行时给出难以定位的行为。
     */
    public TaskIntentAppService(ChatClient chatClient,
                                @Qualifier("taskIntentPrompt") Resource taskIntentPrompt) {
        this.chatClient = chatClient;
        this.taskIntentPrompt = taskIntentPrompt;
    }

    private final Resource taskIntentPrompt;

    /**
     * 分析用户请求，提取任务意图。
     *
     * <p>调用链说明：
     * <pre>
     *   .system(resource)     挂上业务语义提示词（schema 管格式，这段管含义）
     *   .user(message)        用户原始输入
     *   .call().entity(Class) 要求返回强类型对象（而不是一段文本）
     * </pre>
     *
     * @param message 用户原始请求
     * @return 结构化任务意图
     */
    public TaskIntent analyze(String message) {
        TaskIntent intent = chatClient.prompt()
                .system(taskIntentPrompt)
                .user(message)
                .call()
                .entity(TaskIntent.class);

        validate(message, intent);
        log.info("[任务意图] 输入={} → intent={} deliverables={} needConfirmation={} constraints={}",
                message, intent.intent(), intent.deliverables(), intent.needConfirmation(), intent.constraints());
        return intent;
    }

    /**
     * 解析后校验 —— 把"双字典失步"与"模型输出越界"变成明确失败。
     *
     * <p>校验三件事：
     * <ol>
     *   <li>结构非空（模型可能返回 null 字段）；</li>
     *   <li>交付物列表非空，且<b>不含 UNKNOWN 与具体值混用</b>
     *       （例如同时给了 PPT 和 UNKNOWN，说明模型自己也没想清楚，应当拒收）；</li>
     *   <li>约束条目长度符合提示词约定（超长说明模型没遵守格式要求）。</li>
     * </ol>
     *
     * <p>为什么用异常而不是返回错误值：调用方（app 层之上）应当看到"这次理解失败"，
     * 而不是拿到一个看起来正常、实际不可用的对象 —— 后者就是静默失效。
     */
    private void validate(String message, TaskIntent intent) {
        if (intent == null || intent.intent() == null || intent.intent().isBlank()) {
            throw new IllegalStateException("任务意图解析失败：intent 为空。输入=" + message);
        }

        List<TaskIntent.Deliverable> deliverables = intent.deliverables();
        if (deliverables == null || deliverables.isEmpty()) {
            // 提示词已约定：判断不出时填 UNKNOWN，不允许留空。
            // 留空说明模型没遵守约定，属于"约定失步"，必须暴露而不是猜。
            throw new IllegalStateException(
                    "任务意图解析失败：deliverables 为空（提示词约定应填 UNKNOWN）。输入=" + message);
        }
        long unknownCount = deliverables.stream()
                .filter(d -> d == TaskIntent.Deliverable.UNKNOWN)
                .count();
        if (unknownCount > 0 && deliverables.size() > 1) {
            throw new IllegalStateException(
                    "任务意图解析失败：UNKNOWN 不能与具体交付物同时出现。deliverables=" + deliverables
                            + " 输入=" + message);
        }

        if (intent.constraints() != null) {
            for (String c : intent.constraints()) {
                if (c != null && c.length() > 40) {
                    // 提示词要求每条不超过 20 字；放宽一倍算容差，超过说明明显没守约
                    throw new IllegalStateException(
                            "任务意图解析失败：constraint 过长（" + c.length() + " 字）。内容=" + c);
                }
            }
        }
    }
}
