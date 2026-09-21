package com.hagent;

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
 */
@SpringBootApplication
public class HagentApplication {

    public static void main(String[] args) {
        SpringApplication.run(HagentApplication.class, args);
    }
}
