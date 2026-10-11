package com.hagent.security;

import com.hagent.document.AccessLevel;
import org.springframework.ai.chat.memory.ChatMemory;

/**
 * 本次调用的<b>调用方身份</b> —— "谁在问"。
 *
 * <h2>★ 它的产出谁消费（协议第 7 条要求的答案）</h2>
 *
 * <p>唯一的消费者是 {@code RagRetrievalAdvisor}：它把
 * {@link #clearance()} 翻成检索的 SQL 条件 {@code level IN (...)}，
 * 让无权的内容<b>在数据库里就被挡住</b>，而不是取回来再筛掉。
 *
 * <p>若不做这一步会怎样：匿名用户问"我们的薪酬体系"，
 * 向量库会把 {@code CONFIDENTIAL} 的薪酬文档原样返回、拼进 prompt、
 * 由模型复述给用户 —— <b>越权且不留痕迹</b>（日志里只有一次正常的检索）。
 *
 * <h2>★ 为什么"传身份"和"发凭证"是两件事</h2>
 *
 * <p>本项目走自定义 token（见 {@code WORKING-AGREEMENT} 决策 20），
 * 只实现"身份传递链路"：从请求头解析 → 放进请求作用域 → 交给检索切面。
 * <b>签发、校验签名、过期、吊销</b>属于认证服务，本项目不做 ——
 * 所以 {@link UserTokenResolver} 解析的是<b>明文 token</b>，
 * 任何人都能伪造。<b>这是刻意的范围限制，不是疏漏</b>，已在那个类里显式说明。
 *
 * <h2>★ 为什么身份走 context 而不是 prompt（与 conversationId 同理）</h2>
 *
 * <p>切面只能通过 {@code ChatClientRequest.context()} 拿到"请求级"的数据。
 * 而身份<b>绝不能进 prompt</b>：
 * <ul>
 *   <li>prompt 文本会发给模型提供方，context 不会离开我们的进程；</li>
 *   <li>让模型看到"你的身份是 ADMIN"会诱导它据此作答（提示注入的一个入口）。</li>
 * </ul>
 *
 * <h2>★ context key 的命名（与 {@link ChatMemory#CONVERSATION_ID} 同风格）</h2>
 *
 * <p>全小写 + 下划线 + {@code hagent_} 前缀。加前缀是为了避免与框架将来新增的 key
 * 撞名 —— context 是扁平的 {@code Map<String,Object>}，撞名会<b>静默覆盖</b>。
 * 与 {@code MemoryMode.CONTEXT_KEY} 保持同一风格。
 *
 * @param userId 调用方标识。匿名时为 {@code "anonymous"}
 * @param role   角色 —— 决定许可等级
 */
public record UserContext(String userId, Role role) {

    /** 放进 {@code ChatClientRequest.context()} 的 key。 */
    public static final String CONTEXT_KEY = "hagent_user_context";

    /**
     * 放进 {@code HttpServletRequest} 的 attribute 名 ——
     * 拦截器写入、Controller 用 {@code @RequestAttribute} 取出。
     *
     * <p>取值与 {@link #CONTEXT_KEY} 相同是刻意的：它们是<b>同一个概念</b>
     * 在两层里的名字。若将来需要区分，改这里一处即可。
     */
    public static final String REQUEST_ATTRIBUTE = "hagent_user_context";

    /** 匿名身份的 userId 取值。集中定义，避免裸字符串散落。 */
    public static final String ANONYMOUS_USER_ID = "anonymous";

    /**
     * 匿名身份 —— <b>未携带任何凭证时的默认值</b>。
     *
     * <p><b>为什么是"最小权限"而不是"拒绝"</b>（决策 21）：
     * <ul>
     *   <li>与"默认密级 INTERNAL"同源 —— 忘记带凭证时，宁可少看到，不可多看到；</li>
     *   <li>且不打断已有的验证流程：本项目当前的接口验证（curl / 前端演示）
     *       都没有带 token，若一律 401，这些流程全部失效，反而没人愿意接鉴权。</li>
     * </ul>
     * 即"默认偏保守"与"可用性"的交点：<b>能看到 PUBLIC，永远看不到 INTERNAL 以上</b>。
     */
    public static final UserContext ANONYMOUS =
            new UserContext(ANONYMOUS_USER_ID, Role.ANONYMOUS);

    /**
     * 紧凑构造器：把"必填"约束变成对象创建时就失败。
     *
     * <p>为什么不靠调用方自觉：{@code role} 缺失会导致
     * {@link #clearance()} 抛 NPE —— 而那发生在<b>检索切面深处</b>，
     * 堆栈里看不出是"谁忘了传身份"。在这里拦住，错误信息直指根因。
     */
    public UserContext {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId 不能为空 —— 它是审计与排障的定位依据");
        }
        if (role == null) {
            throw new IllegalArgumentException(
                    "role 不能为空 —— 缺了它就无法决定检索可见范围。"
                            + "未认证的调用方请显式使用 UserContext.ANONYMOUS");
        }
    }

    /** 本次调用能看到的密级范围 —— 由角色派生，供检索过滤使用。 */
    public AccessLevel clearance() {
        return role.clearance();
    }

    /** 是否匿名。日志与自检用。 */
    public boolean anonymous() {
        return role == Role.ANONYMOUS;
    }
}
