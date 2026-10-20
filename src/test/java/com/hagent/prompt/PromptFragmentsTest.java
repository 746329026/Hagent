package com.hagent.prompt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>把"提示词与代码的标记对齐"从人工变成机制</b>（阶段 12 第 2 步）。
 *
 * <h2>★ 它防的是什么</h2>
 *
 * <p>{@code prompts/rag-answer-prompt.md} 里写着"优先依据…返回的【知识库片段】回答"、
 * "若某片段的【相关度】为低…"。这些约束<b>靠字面量与代码对齐</b>：
 * 一旦把代码里的标记改名而没改提示词，模型看到的约束就指向一个不存在的标记 ——
 * <b>回答照样通顺、日志全绿、测试全过</b>，只是那条约束悄悄失效。
 * 这是本项目"两个组件靠约定不互相干涉"的典型，所以必须有断言盯着。
 *
 * <p>断言很朴素：<b>每个标记常量都必须真的出现在提示词文件里</b>。
 * 改任何一边，这里都会红。
 */
class PromptFragmentsTest {

    /** surefire 的工作目录是项目根（与 {@code PackagePlacementTest} 同一前提）。 */
    private static final Path PROMPT_FILE = Path.of("src/main/resources/prompts/rag-answer-prompt.md");

    private static String prompt() {
        assertThat(Files.exists(PROMPT_FILE))
                .as("找不到提示词文件 %s —— 本测试依赖 surefire 在项目根运行；"
                        + "若工作目录变了，请修这个测试而不是删掉它", PROMPT_FILE.toAbsolutePath())
                .isTrue();
        try {
            return Files.readString(PROMPT_FILE, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读提示词失败：" + PROMPT_FILE, e);
        }
    }

    @Test
    @DisplayName("★★ 提示词里必须真的出现【知识库片段】标记 —— 否则那条约束指向不存在的标记")
    void promptMentionsTheFragmentHeader() {
        assertThat(prompt())
                .as("代码渲染的片段头是 %s，提示词里必须出现同一个字面量",
                        PromptFragments.KNOWLEDGE_FRAGMENT_HEADER)
                .contains(PromptFragments.KNOWLEDGE_FRAGMENT_HEADER);
    }

    @Test
    @DisplayName("★★ 提示词里必须真的出现【相关度】字段名 —— 它对应工具渲染的「／相关度：」")
    void promptMentionsTheRelevanceLabel() {
        assertThat(prompt())
                .as("代码渲染的字段名是 %s", PromptFragments.RELEVANCE_FIELD_LABEL)
                .contains(PromptFragments.RELEVANCE_FIELD_LABEL);
    }

    @Test
    @DisplayName("标记不是空串 —— 空标记会让上面的断言变成'永远通过'")
    void markersAreNotEmpty() {
        assertThat(PromptFragments.KNOWLEDGE_FRAGMENT_HEADER).isNotBlank();
        assertThat(PromptFragments.RELEVANCE_FIELD_LABEL).isNotBlank();
    }
}
