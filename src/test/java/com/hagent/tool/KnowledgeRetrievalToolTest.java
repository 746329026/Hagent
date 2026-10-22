package com.hagent.tool;

import com.hagent.agent.PipelineEvent;
import com.hagent.agent.ToolEventSink;

import com.hagent.config.ToolRegistration;

import com.hagent.retrieval.KnowledgeSearch;
import com.hagent.config.RetrievalProperties;
import com.hagent.document.AccessLevel;
import com.hagent.retrieval.RelevanceBand;
import com.hagent.retrieval.RetrievedChunk;
import com.hagent.security.Role;
import com.hagent.security.UserContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link KnowledgeRetrievalTool} 的行为契约。
 *
 * <p>守着一个容易被忽略的越权风险：检索搬到工具内部后，{@code UserContext} 必须经
 * {@code ToolContext} 传进工具、再交给 Service。这条链路断了不会报错 ——
 * 身份没传则落匿名（表现为"知识库好像没内容"），若有人"修"成"取不到就放行"，
 * 匿名就能看到 CONFIDENTIAL（越权且不留痕迹）。所以本类既断言身份被正确传下去，
 * 也断言缺失时落匿名而不是落成放行。
 */
class KnowledgeRetrievalToolTest {

    private RetrievedChunk chunk(String content, String source, String section, RelevanceBand band) {
        return new RetrievedChunk(content, source, source, section, 0,
                AccessLevel.INTERNAL, 0.8, band);
    }

    private KnowledgeRetrievalTool toolReturning(List<RetrievedChunk> chunks) {
        KnowledgeSearch service = mock(KnowledgeSearch.class);
        when(service.retrieve(any(), any())).thenReturn(chunks);
        return new KnowledgeRetrievalTool(service);
    }

    private ToolContext contextWith(UserContext user) {
        return new ToolContext(Map.of(ToolContextSupport.USER, user));
    }

    // 一、权限：身份必须传到 Service（这条是越权防线）

    @Test
    @DisplayName("★★ 身份必须从 ToolContext 传进检索 Service —— 越权防线不能因为搬家而断掉")
    void identityIsPassedThroughToRetrievalService() {
        KnowledgeSearch service = mock(KnowledgeSearch.class);
        when(service.retrieve(any(), any())).thenReturn(List.of());
        KnowledgeRetrievalTool tool = new KnowledgeRetrievalTool(service);

        UserContext employee = new UserContext("zhangsan", Role.EMPLOYEE);
        tool.retrieveKnowledge("年假几天", contextWith(employee));

        verify(service).retrieve(eq("年假几天"), eq(employee));
    }

    @Test
    @DisplayName("★★ 缺失身份 → 落到【匿名】而不是放行（失败方向必须偏保守）")
    void missingIdentityFallsBackToAnonymousNotToEverything() {
        KnowledgeSearch service = mock(KnowledgeSearch.class);
        when(service.retrieve(any(), any())).thenReturn(List.of());
        KnowledgeRetrievalTool tool = new KnowledgeRetrievalTool(service);

        // 三种"缺失"形态都要落到匿名：没有 ToolContext、context 为空 Map、键不存在
        tool.retrieveKnowledge("年假几天", null);
        tool.retrieveKnowledge("年假几天", new ToolContext(Map.of()));
        tool.retrieveKnowledge("年假几天", new ToolContext(Map.of("别的键", "别的值")));

        verify(service, org.mockito.Mockito.times(3))
                .retrieve(any(), eq(UserContext.ANONYMOUS));
    }

    @Test
    @DisplayName("★ 身份类型不对 → 同样落匿名（不静默当成缺省的写权限）")
    void wrongIdentityTypeFallsBackToAnonymous() {
        KnowledgeSearch service = mock(KnowledgeSearch.class);
        when(service.retrieve(any(), any())).thenReturn(List.of());
        KnowledgeRetrievalTool tool = new KnowledgeRetrievalTool(service);

        tool.retrieveKnowledge("年假几天",
                new ToolContext(Map.of(ToolContextSupport.USER, "我是个字符串")));

        verify(service).retrieve(any(), eq(UserContext.ANONYMOUS));
    }

    // 二、返回值是"给模型看的文本"（模型只能看到字符串）

    @Test
    @DisplayName("★ 命中时返回值必须带【来源】与【相关度】—— 否则模型无法『说明出处』")
    void renderedResultCarriesSourceAndBand() {
        KnowledgeRetrievalTool tool = toolReturning(List.of(
                chunk("员工每年享有五天带薪年假", "员工手册", "假期管理", RelevanceBand.HIGH)));

        String result = tool.retrieveKnowledge("年假几天", contextWith(UserContext.ANONYMOUS));

        assertThat(result)
                .contains("【知识库片段】")
                .contains("来源：员工手册")
                .contains("相关度：高")
                .contains("员工每年享有五天带薪年假")
                .as("★ 标记字样必须与 prompts/rag-answer-prompt.md 里的约束一致，"
                        + "否则约束指向一个不存在的标记且不报错")
                .contains("【知识库片段 1");
    }

    @Test
    @DisplayName("★ 多条命中时逐条编号 —— 模型引用时才有可指的编号")
    void multipleHitsAreNumbered() {
        KnowledgeRetrievalTool tool = toolReturning(List.of(
                chunk("内容一", "手册A", "章节一", RelevanceBand.HIGH),
                chunk("内容二", "手册B", "章节二", RelevanceBand.MEDIUM)));

        String result = tool.retrieveKnowledge("问题", contextWith(UserContext.ANONYMOUS));

        assertThat(result).contains("【知识库片段 1").contains("【知识库片段 2")
                .contains("内容一").contains("内容二");
    }

    @Test
    @DisplayName("★ 没命中 → 返回明确说明（而不是空串），让模型知道『查过了但没有』")
    void noHitReturnsExplicitSentence() {
        KnowledgeRetrievalTool tool = toolReturning(List.of());

        String result = tool.retrieveKnowledge("不存在的问题", contextWith(UserContext.ANONYMOUS));

        assertThat(result)
                .as("返回空串会让模型以为『工具坏了』或『什么也没发生』；"
                        + "明确说明才能配合提示词约束，让模型回答『未找到相关内容』而非编造")
                .isNotBlank()
                .contains("没有找到");
    }

    @Test
    @DisplayName("★★ 检索【失败】必须与『没命中』给出完全不同的说明 —— 否则系统在诚实地撒谎")
    void retrievalFailureIsNotRenderedAsNoHit() {
        KnowledgeSearch service = mock(KnowledgeSearch.class);
        when(service.retrieve(any(), any())).thenThrow(
                new com.hagent.exception.RetrievalUnavailableException(
                        "知识库检索失败（查询=年假几天）", new RuntimeException("pgvector 不可达")));
        KnowledgeRetrievalTool tool = new KnowledgeRetrievalTool(service);

        String result = tool.retrieveKnowledge("年假几天", contextWith(UserContext.ANONYMOUS));

        assertThat(result)
                .as("★ 实测事故：检索每次抛异常都被降级成『没命中』，"
                        + "模型于是回答『知识库中未找到』—— 而用户问的年假就在库里。"
                        + "失败必须让模型知道『没查成』，而不是『没有』")
                .contains("检索不可用")
                .contains("不是")
                .doesNotContain("没有找到");
    }

    @Test
    @DisplayName("★ 检索失败时不发 Retrieved 事件 —— 发了就等于告诉前端『检索成功、命中 0 条』")
    void failureDoesNotEmitRetrievedEvent() {
        KnowledgeSearch service = mock(KnowledgeSearch.class);
        when(service.retrieve(any(), any())).thenThrow(
                new com.hagent.exception.RetrievalUnavailableException(
                        "失败", new RuntimeException("pgvector 不可达")));
        KnowledgeRetrievalTool tool = new KnowledgeRetrievalTool(service);

        java.util.List<com.hagent.agent.PipelineEvent> emitted = new java.util.ArrayList<>();
        com.hagent.agent.ToolEventSink sink = emitted::add;
        UserContext employee = new UserContext("zhangsan", Role.EMPLOYEE);

        tool.retrieveKnowledge("年假几天", new ToolContext(Map.of(
                ToolContextSupport.USER, employee,
                ToolContextSupport.EVENT_SINK, sink)));

        assertThat(emitted)
                .as("Retrieved 的含义是『检索跑完了，结果是这几条』。"
                        + "失败时发一个空列表的 Retrieved，前端会显示『已返回』—— 又是一次同形")
                .isEmpty();
    }

    // 三、工具注册（@Tool 内省是否真的产出了可用的 ToolCallback）

    @Test
    @DisplayName("★★ @Tool 内省产出的定义必须带名字、描述与输入 schema")
    void toolIntrospectionProducesUsableDefinition() {
        KnowledgeRetrievalTool tool = toolReturning(List.of());

        ToolCallback[] callbacks = MethodToolCallbackProvider.builder()
                .toolObjects(tool)
                .build()
                .getToolCallbacks();

        assertThat(callbacks).hasSize(1);
        var definition = callbacks[0].getToolDefinition();

        assertThat(definition.name()).isEqualTo("retrieveKnowledge");
        assertThat(definition.description())
                .as("★ 描述是【写给模型看的】：它决定模型什么时候该调用这个工具。"
                        + "空描述会让模型无法判断时机")
                .isNotBlank()
                .contains("知识库");
        assertThat(definition.inputSchema())
                .as("★ 框架会把输入 schema 一起发给模型 —— 这就是『工具使用方法』的载体，"
                        + "所以不需要另外写进 system prompt")
                .contains("query");
        assertThat(definition.inputSchema())
                .as("ToolContext 是【框架注入】的上下文参数，不能出现在给模型的输入 schema 里")
                .doesNotContain("toolContext");
    }

    @Test
    @DisplayName("★ 通过 ToolCallback 真实调用一次 —— 参数能正确解析并命中工具方法")
    void callbackActuallyInvokesTheToolMethod() {
        KnowledgeSearch service = mock(KnowledgeSearch.class);
        when(service.retrieve(any(), any())).thenReturn(List.of(
                chunk("员工每年享有五天带薪年假", "员工手册", "假期管理", RelevanceBand.HIGH)));
        ToolCallback[] callbacks = MethodToolCallbackProvider.builder()
                .toolObjects(new KnowledgeRetrievalTool(service))
                .build()
                .getToolCallbacks();

        // 必须走双参重载：本工具声明了 ToolContext 参数，框架会强制要求它
        // （只传 JSON 会抛 "ToolContext is required by the method as an argument"）。
        // 这条约束是好事 —— "忘了传身份"在框架层就被拒绝，而不是静默降级成匿名。
        String result = callbacks[0].call("{\"query\":\"年假几天\"}",
                new ToolContext(Map.of(ToolContextSupport.USER,
                        new UserContext("zhangsan", Role.EMPLOYEE))));

        assertThat(result)
                .as("走框架的真实调用路径（JSON 参数解析 → 方法反射 → 结果转换）"
                        + "必须能拿到渲染好的文本")
                .contains("员工每年享有五天带薪年假");

        verify(service).retrieve(eq("年假几天"), eq(new UserContext("zhangsan", Role.EMPLOYEE)));
    }

    // 四、可插拔：检索开关关掉时不注册任何工具

    @Test
    @DisplayName("★ retrieval.enabled=false → 没有 retrieveKnowledge，但长期记忆工具照常在")
    void retrievalDisabledRemovesOnlyTheRetrievalTool() {
        RetrievalProperties off = new RetrievalProperties();
        off.setEnabled(false);

        ToolCallback[] tools = new ToolRegistration()
                .allTools(mock(KnowledgeSearch.class), off,
                        mock(com.hagent.memory.longterm.LongTermMemoryStore.class));

        assertThat(tools)
                .as("关掉检索 = 模型看不到 retrieveKnowledge = 不会发起检索（阶段 9 的语义）")
                .noneMatch(tool -> "retrieveKnowledge".equals(tool.getToolDefinition().name()));
        assertThat(tools)
                .as("★ 阶段 11 起：记忆工具与检索开关【无关】—— 记忆是跨会话的事实，"
                        + "不是知识库的一部分。关掉检索不该顺带让人失忆")
                .extracting(tool -> tool.getToolDefinition().name())
                .containsExactlyInAnyOrder("saveMemory", "readMemory", "forgetMemory");
    }

    @Test
    @DisplayName("★ retrieval.enabled=true → 4 个工具（检索 1 + 长期记忆 3）")
    void retrievalEnabledRegistersAllTools() {
        RetrievalProperties on = new RetrievalProperties();
        on.setEnabled(true);

        ToolCallback[] tools = new ToolRegistration()
                .allTools(mock(KnowledgeSearch.class), on,
                        mock(com.hagent.memory.longterm.LongTermMemoryStore.class));

        assertThat(tools)
                .extracting(tool -> tool.getToolDefinition().name())
                .containsExactlyInAnyOrder("retrieveKnowledge", "saveMemory", "readMemory", "forgetMemory");
    }
}
