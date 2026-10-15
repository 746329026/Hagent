package com.hagent.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * RAG（知识库检索增强）的装配 —— 与 {@code AiConfig} / {@code WebConfig} /
 * {@code VectorStoreConfig} 同一层级：<b>一个能力域一个配置类</b>。
 *
 * <h2>为什么单独建一个类，而不是塞进 VectorStoreConfig</h2>
 *
 * <p>两者的职责不同：
 * <ul>
 *   <li>{@code VectorStoreConfig} 管的是"<b>向量库这个基础设施怎么连</b>" ——
 *       数据源、表名、维度、索引类型。它对应的是"存储层"；</li>
 *   <li>本类管的是"<b>知识怎么被切分、入库、检索</b>" —— 块大小、重叠、
 *       默认种类、检索参数、回答约束。它对应的是"业务策略层"。</li>
 * </ul>
 *
 * <p>把业务策略塞进基础设施配置类，会让"改一个切分参数要打开一个讲数据源的文件" ——
 * 而本项目的配置类是按能力域划分的，保持这个一致性比省一个文件更重要。
 *
 * <h2>★ 阶段 6 后半新增：回答约束资源 Bean</h2>
 *
 * <p>检索链路的组件（{@code KnowledgeRetrievalService}、{@code RetrievalFilterBuilder}、
 * {@code AgentPipeline}）都是 {@code @Component} / {@code @Service}，由组件扫描发现。
 * 唯一需要在这里显式声明的是<b>回答约束提示词</b>——因为它来自类路径资源，
 * 需要一个"存在性检查"的落点。
 */
@Slf4j
@Configuration
@EnableConfigurationProperties({
        ChunkingProperties.class,
        IngestProperties.class,
        RetrievalProperties.class,
        PromptProperties.class})
public class RagConfig {

    /**
     * 回答约束提示词的资源 Bean。
     *
     * <h3>★ 为什么在这里做"存在性 + 可读性"检查，而不是等到用时</h3>
     *
     * <p>提示词读不到时，若静默用一个空字符串，模型会失去
     * "知识库中没有相关内容时明确说明，不要凭常识编造"与
     * "不要自己创造文件名"这两条约束。<b>而它的表现是回答依然通顺</b>，
     * 只是偶尔编一个看起来很像真的来源 ——
     * 这种表现没人会往"提示词没加载"上想。
     *
     * <p>所以按决策 8（不静默降级）在这里直接失败：应用起不来，
     * 报出资源路径与修法。这与 {@code MemoryConfigValidator}、
     * {@code vectorTableSelfCheck} 是同一个立场。
     *
     * <h3>★ 为什么读一次就够（内容在这里读进来之后就不再读盘）</h3>
     *
     * <p>本 Bean 只做"存在性 + UTF-8 可读性"的验证，并把<b>校验过的路径</b>
     * 对应的 {@link Resource} 交给 {@code PromptBuilder}，由<b>它</b>在构造时读入正文
     * （接线方式：{@code AgentPipeline} 在自己的构造器里 new 出 {@code PromptBuilder}，
     * 本 Bean 就是这个构造参数）。这里不缓存正文，避免"两份正文" ——
     * 这个理由现在依然成立：一旦这里也留一份，"资源 Bean 里那份"与
     * "PromptBuilder 里那份"就会各自演化，而<b>哪一份才是模型真正收到的</b>
     * 将无法从代码上看出来。
     *
     * <h3>★ 为什么用注入的 {@link ResourceLoader} 而不是 {@code new DefaultResourceLoader()}</h3>
     *
     * <p>{@code ApplicationContext} 本身就是一个 {@code ResourceLoader}，
     * 注入它可以让解析规则（类路径前缀、相对路径基准）与应用其余部分完全一致。
     * 自己 new 一个虽然当前结果相同，但那是"两套解析规则"，将来打包方式一变
     * （如换成 fat jar 里的嵌套路径）就会分叉。
     */
    @Bean
    public Resource ragAnswerPromptResource(PromptProperties properties, ResourceLoader resourceLoader) {
        String location = properties.getConstraintResource();
        Resource resource = resourceLoader.getResource(location);

        if (!resource.exists()) {
            throw new IllegalStateException(String.format(
                    "回答约束提示词不存在：%s（来自 hagent.rag.prompt.constraint-resource）。"
                            + "【为什么必须启动失败】缺失时模型会照常给出通顺的回答，"
                            + "只是偶尔编造来源 —— 这种表现不会被联想到「提示词没加载」。"
                            + "【怎么修】确认资源位于 src/main/resources/prompts/ 下，"
                            + "或把约束内容整段摘掉以确认这就是问题所在："
                            + "hagent.rag.prompt.include-constraint=false",
                    location));
        }

        try {
            String content = resource.getContentAsString(StandardCharsets.UTF_8);
            if (content.isBlank()) {
                throw new IllegalStateException(
                        "回答约束提示词是空文件：" + location
                                + "。空约束等同于没有约束，按「不静默降级」处理。");
            }
            log.info("[RAG] 回答约束已就位：{}（{} 字符，UTF-8 校验通过）", location, content.trim().length());
        } catch (IOException e) {
            throw new IllegalStateException(
                    "回答约束提示词读取失败：" + location + "。【排查方向】文件是否为 UTF-8、是否可读。", e);
        }

        return resource;
    }
}
