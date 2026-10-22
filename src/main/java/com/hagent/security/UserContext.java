package com.hagent.security;

import com.hagent.retrieval.KnowledgeSearch;

import com.hagent.document.AccessLevel;

/**
 * 本次调用的<b>调用方身份</b> —— "谁在问"。
 *
 * <p>消费者是检索：{@link #clearance()} 交给 {@code KnowledgeSearch} 翻成 SQL 条件
 * {@code level IN (...)}，让无权的内容<b>在数据库里就被挡住</b>，而不是取回来再筛掉。
 * 若不做这一步：匿名用户问"我们的薪酬体系"，向量库会把 {@code CONFIDENTIAL} 的薪酬文档
 * 原样返回、拼进 prompt、由模型复述给用户 —— <b>越权且不留痕迹</b>（日志里只有一次正常检索）。
 *
 * <p>身份传递链路：拦截器 → request attribute → Controller 的 {@code @RequestAttribute}
 * → <b>方法参数</b> → {@code AgentPipeline.run(..., userContext)} → 检索。
 * 全程都是编译期检查，没有任何字符串 key，身份一个字节都不进 prompt。
 *
 * <p>为什么身份绝不能进 prompt：prompt 文本会发给模型提供方，而身份不该离开我们的进程；
 * 且让模型看到"你的身份是 ADMIN"会诱导它据此作答（提示注入的一个入口）。
 *
 * @param userId 调用方标识。匿名时为 {@code "anonymous"}
 * @param role   角色 —— 决定许可等级
 */
public record UserContext(String userId, Role role) {

    /**
     * 放进 {@code HttpServletRequest} 的 attribute 名 ——
     * 拦截器写入、Controller 用 {@code @RequestAttribute} 取出。
     *
     * <p>它是身份传递在 HTTP 层的唯一名字（引用的是编译期常量，拼错编译不过）；
     * 原先还有一个等值的 {@code CONTEXT_KEY} 需要人工同步，那个负担已结构性消失。
     */
    public static final String REQUEST_ATTRIBUTE = "hagent_user_context";

    /** 匿名身份的 userId 取值。集中定义，避免裸字符串散落。 */
    public static final String ANONYMOUS_USER_ID = "anonymous";

    /**
     * 匿名身份 —— <b>未携带任何凭证时的默认值</b>。
     *
     * <p>为什么是"最小权限"而不是"拒绝"：与"默认密级 INTERNAL"同源 —— 忘记带凭证时，
     * 宁可少看到、不可多看到；且本项目当前的验证流程（curl / 前端演示）都没有带 token，
     * 若一律 401 这些流程会全部失效，反而没人愿意接鉴权。
     * 即"默认偏保守"与"可用性"的交点：<b>能看到 PUBLIC，永远看不到 INTERNAL 以上</b>。
     */
    public static final UserContext ANONYMOUS =
            new UserContext(ANONYMOUS_USER_ID, Role.ANONYMOUS);

    /**
     * 紧凑构造器：把"必填"约束变成对象创建时就失败。
     *
     * <p>不靠调用方自觉：{@code role} 缺失会让 {@link #clearance()} 抛 NPE ——
     * 而那发生在<b>检索链路深处</b>，堆栈里看不出是"谁忘了传身份"。
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
