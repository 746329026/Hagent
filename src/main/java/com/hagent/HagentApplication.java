package com.hagent;

import org.springframework.ai.model.chat.memory.autoconfigure.ChatMemoryAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Hagent 启动类 —— 整个应用的入口。
 *
 * <p><b>★ 它必须放在 {@code com.hagent} 根下</b>：{@code @ComponentScan} 只扫"本类所在包 +
 * 所有子包"，放进子包（如 {@code com.hagent.controller}）会让 {@code com.hagent.app} 等
 * 兄弟包全部扫不到（实测 {@code ScanProbeService} 从 FOUND 变 NOT FOUND）。
 * 而报错是"required a bean of type '...' that could not be found"，
 * 容易被误判成"@Service 忘了写"。
 *
 * <p>另注意 {@code @EnableAutoConfiguration} 会<b>主动</b>创建 Bean：某些 Bean 创建失败会直接
 * 拖垮启动（即使当时还用不到那个能力）—— 例如未配 DeepSeek API Key 时启动即报
 * "DeepSeek API key must be set"，这就是"密钥是启动前置条件"的原因。
 *
 * <h2>★★ 为什么这里排除了 {@code ChatMemoryAutoConfiguration}</h2>
 *
 * <p>该自动配置会装上 {@code InMemoryChatMemoryRepository}（堆内存，重启即丢）与
 * {@code MessageWindowChatMemory}（只有条数窗口，<b>没有任何 token 预算</b>）。
 * 它只有 {@code @ConditionalOnClass} 与两个 {@code @ConditionalOnMissingBean}，
 * <b>没有任何 {@code enabled} 开关</b>（javap 核对 2.0.1）—— 只能在这里排除。
 * 留着它则是"看起来能用、实际不能用"：一旦有人接回 {@code ChatClient}，
 * 会话历史会悄悄进堆内存、<b>重启就丢、且不报错</b>。
 *
 * <p><b>★ 为什么用 {@code exclude} 而不是 {@code spring.autoconfigure.exclude}</b>：
 * 前者是<b>编译期检查</b>，类名写错直接编译失败；配置里的字符串拼错只会<b>静默失效</b>
 * （本项目曾在 YAML 缩进上栽过：键成了无效键，65 个集成测试全红）。
 * 反向确认：{@code StartupProbe} 在构造期检查框架 {@code ChatMemory} 确实不在容器里。
 */
@SpringBootApplication(exclude = ChatMemoryAutoConfiguration.class)
public class HagentApplication {

    public static void main(String[] args) {
        SpringApplication.run(HagentApplication.class, args);
    }
}
