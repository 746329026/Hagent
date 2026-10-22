package com.hagent.config;

import com.hagent.retrieval.KnowledgeSearch;

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
 * <p>为什么与 {@code VectorStoreConfig} 分开：后者管"<b>向量库这个基础设施怎么连</b>"
 * （数据源、表名、维度、索引类型，属存储层），本类管"<b>知识怎么被切分、入库、检索</b>"
 * （块大小、重叠、默认种类、检索参数、回答约束，属业务策略层）。把业务策略塞进基础设施
 * 配置类，会让"改一个切分参数要打开一个讲数据源的文件"。
 *
 * <p>检索链路的组件（{@code KnowledgeSearch}、{@code RetrievalFilterBuilder}、
 * {@code AgentPipeline}）都是组件扫描发现的；唯一需要显式声明的是<b>回答约束提示词</b> ——
 * 它来自类路径资源，需要一个"存在性检查"的落点。
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
     * <p>★ 为什么在这里做"存在性 + 可读性"检查，而不是等到用时：提示词读不到时若静默用空字符串，
     * 模型会失去"知识库无相关内容时明确说明、不要凭常识编造"与"不要自创文件名"这两条约束。
     * <b>而它的表现是回答依然通顺</b>，只是偶尔编一个很像真的来源 ——
     * 这种表现没人会往"提示词没加载"上想。所以按"不静默降级"直接失败，报出资源路径与修法。
     *
     * <p>★ 为什么读一次就够：本 Bean 只验证"存在 + UTF-8 可读"，把校验过的 {@link Resource}
     * 交给 {@code PromptBuilder}，由<b>它</b>在构造时读入正文。这里<b>不缓存正文</b>，避免
     * "资源 Bean 里那份"与"PromptBuilder 里那份"各自演化 —— 否则哪一份才是模型真正收到的，
     * 将无法从代码上看出来。
     *
     * <p>★ 为什么用注入的 {@link ResourceLoader} 而不是 {@code new DefaultResourceLoader()}：
     * {@code ApplicationContext} 本身就是 ResourceLoader，注入它让解析规则（类路径前缀、
     * 相对路径基准）与应用其余部分完全一致；自己 new 就是两套规则，打包方式一变
     * （如 fat jar 里的嵌套路径）就会分叉。
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

    /**
     * 把 {@code RetrievalProperties} 映射成领域值对象 —— <b>映射只发生在装配层</b>。
     *
     * <p>领域包（{@code retrieval}）不应该认识 {@code config}，否则就是"领域依赖装配"，
     * 并会形成结构环。
     */
    @Bean
    public com.hagent.retrieval.RetrievalSettings retrievalSettings(RetrievalProperties properties) {
        return new com.hagent.retrieval.RetrievalSettings(
                properties.isEnabled(),
                properties.getTopK(),
                properties.similarityThreshold(),
                properties.getMaxDistance(),
                properties.getHighMaxDistance(),
                properties.getMediumMaxDistance());
    }
}
