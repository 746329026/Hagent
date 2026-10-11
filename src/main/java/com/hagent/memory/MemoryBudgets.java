package com.hagent.memory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * <b>模式 → 预算</b> 的唯一映射表 —— 两个调用点共用同一份。
 *
 * <h2>★ 它解决什么问题（一次真实的重复）</h2>
 *
 * <p>这张表原先被<b>抄了两份</b>：{@code MemoryReadAdvisor}（主链路）一份，
 * {@code ChatMemoryAdapter}（框架边界）一份，形状完全一样：
 *
 * <pre>
 *   private final Map&lt;MemoryMode, TokenBudget&gt; budgets = Map.of(
 *           MemoryMode.CONVERSATION, new TokenBudget(...),
 *           MemoryMode.WORKFLOW,     new TokenBudget(...));
 * </pre>
 *
 * <p>这不只是"看着重复"，它有一个<b>具体的炸法</b>：
 * {@code Map.of(...)} 是<b>手写列举</b>的，<b>没有任何机制</b>保证"每个模式都被覆盖"。
 * 将来加入第三个 {@code MemoryMode}（例如阶段 9 的批量任务），漏改任意一处就会：
 *
 * <pre>
 *   budgets.get(newMode)  →  null  →  下一行 budget.maxTokens()  →  NPE
 * </pre>
 *
 * 也就是说：<b>一个"漏配了一个枚举常量"的错误，会以运行期空指针的形式出现在
 * 预算取值那一行</b> —— 堆栈里看不出"漏配"这个真正的意思。
 *
 * <h2>★ 两道结构性保证（都不是"记得改两处"）</h2>
 *
 * <ol>
 *   <li><b>建表时遍历 {@link MemoryMode#values()}，而不是手写列举</b> ——
 *       理论上不可能漏项：有多少个模式就建多少个条目；</li>
 *   <li><b>取值用穷尽的 {@code switch} 表达式，且不写 {@code default}</b> ——
 *       Java 对枚举 switch 的穷尽性检查会在<b>编译期</b>报错：
 *       新增一个 {@code MemoryMode} 常量而没在这里补分支，<b>直接编译不过</b>。
 *       "漏配模式"因此从<b>运行期 NPE</b> 变成<b>编译期错误</b>。</li>
 * </ol>
 *
 * <p>这正是 {@code WORKING-AGREEMENT} 第 6 条要的东西：
 * 同一个操作性错误重复出现时，把它从"每次口头提醒"升级为<b>机制化解决</b>。
 *
 * <h2>★ 为什么两个调用点必须共用（而不是各建一份）</h2>
 *
 * <p>它们读的是同一份配置（{@code hagent.memory.chat.*}），
 * 表达的是同一条规则（"会话模式 5000/30，工作流模式 50000/200"）。
 * 一旦各自演化，会出现"同一个会话在主链路与框架路径下拿到不同预算"——
 * 而这种不一致<b>不会报错</b>，只会在某条路径上悄悄多带或少带历史。
 *
 * <h2>与 {@link TokenBudget}、{@link MemoryTrimmer} 的分工</h2>
 *
 * <pre>
 *   MemoryProperties  定义【数值】   （配置里写 5000 / 50000）
 *   MemoryBudgets     选择【组合】   （哪个模式用哪组数值）  ← 本类，唯一出处
 *   MemoryTrimmer     消费【预算】   （按预算截断历史的规则）← 唯一出处
 * </pre>
 */
public record MemoryBudgets(Map<MemoryMode, TokenBudget> byMode) {

    /**
     * 紧凑构造器：拒绝空表，并做一份不可变拷贝。
     *
     * <p>拒绝空表不是多余的 —— 空表会让"取预算"退化成"随便给一个"，
     * 而"预算缺失"的正确表现是<b>明确失败</b>，不是放行。
     */
    public MemoryBudgets {
        if (byMode == null || byMode.isEmpty()) {
            throw new IllegalArgumentException("预算表不能为空 —— 空表意味着没有任何模式有预算");
        }
        byMode = Collections.unmodifiableMap(new LinkedHashMap<>(byMode));
    }

    /**
     * 从配置构建<b>全部模式</b>的预算表。
     *
     * <p>★ 遍历 {@link MemoryMode#values()}：有多少模式就建多少条目，
     * 不存在"忘了给某个模式配"的可能。若要新增模式，
     * {@link #budgetOf} 里的 switch 会先让编译失败。
     */
    public static MemoryBudgets from(MemoryProperties properties) {
        Map<MemoryMode, TokenBudget> map = new LinkedHashMap<>();
        // 用 LinkedHashMap 保留枚举声明顺序 —— 启动日志里读起来更顺
        for (MemoryMode mode : MemoryMode.values()) {
            map.put(mode, budgetOf(mode, properties));
        }
        return new MemoryBudgets(map);
    }

    /**
     * ★ <b>穷尽的 {@code switch}，刻意不写 {@code default}</b>。
     *
     * <p>不写 {@code default} 是有意的：写了 {@code default} 就等于对编译器说
     * "任何我没列出的模式都走这里"，于是新增枚举常量时<b>编译器不再检查</b> ——
     * 那正是这道保证失效的方式。
     *
     * <p>两个模式的数值来自配置，各自绑定它在 {@code application.yml} 里的两项，
     * <b>不存在"共用一个大上限"</b>（那是阶段 5 修正过的坑：用数量级掩盖歧义）。
     */
    private static TokenBudget budgetOf(MemoryMode mode, MemoryProperties properties) {
        return switch (mode) {
            case CONVERSATION -> new TokenBudget(
                    properties.getConversationMaxTokens(),
                    properties.getConversationMaxMessages());
            case WORKFLOW -> new TokenBudget(
                    properties.getWorkflowMaxTokens(),
                    properties.getWorkflowMaxMessages());
        };
    }

    /**
     * 取指定模式的预算。
     *
     * <p>理论上不可能取不到（建表时遍历了 {@code values()}），
     * 所以这里的 null 分支是<b>防御性</b>的：一旦发生，说明本类的构造被绕过了
     * （例如有人直接 {@code new MemoryBudgets(空表)} 之外的方式传入了残缺的表）。
     * <b>明确抛异常而不是回退到某个默认预算</b> —— 回退会让"配错了"表现成"历史偏少"。
     */
    public TokenBudget forMode(MemoryMode mode) {
        TokenBudget budget = byMode.get(mode);
        if (budget == null) {
            throw new IllegalStateException(
                    "模式 " + mode + " 没有对应预算。这是一个不该出现的状态："
                            + "MemoryBudgets.from() 遍历了 MemoryMode.values()，理论上不可能漏。"
                            + "请检查是否有代码直接构造了残缺的预算表。");
        }
        return budget;
    }
}
