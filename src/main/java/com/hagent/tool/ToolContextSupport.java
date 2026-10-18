package com.hagent.tool;

import com.hagent.security.UserContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;

/**
 * 工具上下文的<b>读写约定</b> —— 键名与身份解析的唯一出处。
 *
 * <h2>★ 为什么它从 {@code KnowledgeRetrievalTool} 搬出来（协议第 12、13 条）</h2>
 *
 * <p>这两个键原本定义在 {@code KnowledgeRetrievalTool} 里。阶段 11 加入长期记忆的三个工具后，
 * 它们也要读同一个 {@code hagentToolUser} —— 如果继续写
 * {@code KnowledgeRetrievalTool.TOOL_CONTEXT_USER}，名字就在<b>指向别处</b>：
 * 后来者会以为这个键只有检索工具用，或者去检索工具里找一个通用约定。
 *
 * <p>解析逻辑同理：三条"缺失/类型不对 → 匿名 + WARN"的分支如果各抄一份，
 * 迟早出现"检索按匿名处理、记忆却按缺省权限处理"这种<b>不一致的失败方向</b>。
 *
 * <h2>★ 缺身份时为什么是"匿名"而不是"拒绝"</h2>
 *
 * <p>与 {@code UserContextInterceptor} 的立场一致：<b>匿名 = 最小权限，不是报错</b>。
 * 但<b>必须 WARN</b>：调用方漏传这件事本身要被看见，否则表现为
 * "结果莫名其妙地少"或"记忆写进了别人的目录"。
 */
@Slf4j
public final class ToolContextSupport {

    /** 调用方身份的键 —— 由 {@code AgentPipeline#optionsFor} 放进 ToolContext。 */
    public static final String USER = "hagentToolUser";

    /** 工具发事件（如 {@code Retrieved}）的出口键。 */
    public static final String EVENT_SINK = "hagentToolEventSink";

    private ToolContextSupport() {
    }

    /**
     * 从 {@link ToolContext} 取出调用方身份。
     *
     * <p>三种情况：没有上下文 / 没有这个键 / 类型不对 —— 全部落匿名并 WARN。
     *
     * @param logTag 日志前缀（如 {@code "工具-检索"}），用于区分是哪个工具漏传了
     */
    public static UserContext resolveUser(ToolContext toolContext, String logTag) {
        if (toolContext == null || toolContext.getContext() == null) {
            log.warn("[{}] 没有 ToolContext —— 已按【匿名】处理。"
                    + "正确用法：ToolCallingChatOptions.toolContext({}, userContext)", logTag, USER);
            return UserContext.ANONYMOUS;
        }
        Object value = toolContext.getContext().get(USER);
        if (value instanceof UserContext user) {
            return user;
        }
        if (value == null) {
            log.warn("[{}] ToolContext 里没有 {} —— 已按【匿名】处理。"
                    + "正确用法：ToolCallingChatOptions.toolContext({}, userContext)",
                    logTag, USER, USER);
        } else {
            log.warn("[{}] ToolContext 里的 {} 类型不是 UserContext 而是 {} —— 已按【匿名】处理",
                    logTag, USER, value.getClass().getName());
        }
        return UserContext.ANONYMOUS;
    }
}
