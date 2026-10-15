package com.hagent;

import org.springframework.ai.model.chat.memory.autoconfigure.ChatMemoryAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Hagent 启动类 —— 整个应用的入口。
 *
 * <p><b>@SpringBootApplication 是三个注解的组合</b>，记住这个拆分：
 * 以后排查"Bean 找不到"时，能立刻分清该往哪个方向查。
 *
 * <pre>
 *   @SpringBootConfiguration   声明"我是配置类"（本质是 @Configuration 的特定版本）
 *   @EnableAutoConfiguration   ★ 装配【框架/第三方】的 Bean
 *                                例如：你没写 new Tomcat()，它起来了；
 *                                      你没写 new RedisTemplate()，它能被注入。
 *                                它靠读取各依赖 jar 里的自动装配清单工作。
 *   @ComponentScan             ★ 装配【你自己写的】Bean
 *                                扫描包下的 @Component / @Service / @Repository / @Controller
 * </pre>
 *
 * <p><b>★ 关键规则：@ComponentScan 默认扫描"本类所在包 + 所有子包"。</b>
 * 因此本类必须放在所有业务包的最外层（com.hagent 根下）：
 *
 * <pre>
 *   com.hagent
 *   ├── HagentApplication        &lt;- 启动类（必须在这一层）
 *   ├── app / controller / core / rag / tools / ...   全部是它的子包，都能被扫到
 * </pre>
 *
 * <p>若把它放进子包（如 com.hagent.controller），则 com.hagent.app 成为
 * "平级的兄弟包"而不在扫描范围内，其中的 @Service 全部不会注册。实测验证：
 *
 * <pre>
 *   启动类在 com.hagent              -&gt; com.hagent.app.ScanProbeService : FOUND
 *   启动类在 com.hagent.controller   -&gt; com.hagent.app.ScanProbeService : NOT FOUND
 * </pre>
 *
 * <p>而且这种错误的报错具有迷惑性：它不会说"扫描范围不对"，而是说
 * "required a bean of type '...' that could not be found"，
 * 于是人会先去怀疑"是不是 @Service 忘了写"，白白浪费排查时间。
 *
 * <p>另需注意：@EnableAutoConfiguration 会<b>主动</b>创建 Bean，某些 Bean 创建失败
 * 会直接拖垮整个应用启动（即使你当时还用不到那个能力）。实测例子：
 * 未配置 DeepSeek API Key 时会报
 * "Failed to instantiate [DeepSeekChatModel]: DeepSeek API key must be set"。
 * 这解释了"为什么密钥是启动前置条件，而不是用到时才需要"。
 *
 * <h2>★★ 为什么这里排除了 {@code ChatMemoryAutoConfiguration}（阶段 8）</h2>
 *
 * <p>阶段 8 删除了本项目自己的 {@code ChatMemoryAdapter} —— 它原先是一个
 * <b>占位 Bean</b>，靠"占着 {@code ChatMemory} 这个类型"来阻止框架的默认实现进来。
 * 它一删，Spring AI 的 {@code ChatMemoryAutoConfiguration} 就会补位，装上：
 *
 * <pre>
 *   InMemoryChatMemoryRepository   —— 堆内存，重启即丢
 *   MessageWindowChatMemory        —— 只有条数窗口，【没有任何 token 预算】
 * </pre>
 *
 * <p><b>实测关键事实</b>：该自动配置只带
 * {@code @ConditionalOnClass} 与两个 {@code @ConditionalOnMissingBean}，
 * <b>没有任何 {@code enabled} 开关</b>（javap 核对 2.0.1）——
 * 所以它<b>必然</b>装配，无法靠配置属性关掉，只能在这里排除。
 *
 * <p><b>为什么必须排除而不是"留着它但不用"</b>：它是一个
 * "看起来能用、实际不能用"的 Bean —— 一旦将来有人接回 {@code ChatClient}
 * 或挂上框架自带的记忆切面，会话历史会悄悄进堆内存、
 * <b>重启就丢、且不报错</b>。这正是决策 8（不静默降级）要防的失败模式。
 *
 * <p><b>★ 为什么用 {@code exclude} 而不是 {@code spring.autoconfigure.exclude}</b>：
 * <ul>
 *   <li>这里是<b>编译期检查</b> —— 类名写错或框架改名会直接编译失败，
 *       而配置里的字符串拼错只会<b>静默失效</b>（本项目刚在这上面栽过一次：
 *       YAML 缩进插错位置成了无效键，导致自检失败、65 个集成测试全红）；</li>
 *   <li>排除的原因与代码强相关，写在代码旁边比放在几百行外的 yml 里更不容易被误删。</li>
 * </ul>
 *
 * <p><b>反向确认</b>：{@code StartupProbe} 在构造期检查"框架 {@code ChatMemory}
 * 确实不在容器里"，若将来这个 exclude 被移除，启动期会直接失败并指出这里。
 */
@SpringBootApplication(exclude = ChatMemoryAutoConfiguration.class)
public class HagentApplication {

    public static void main(String[] args) {
        SpringApplication.run(HagentApplication.class, args);
    }
}
