package com.hagent.tool;

import com.hagent.memory.longterm.LongTermMemoryStore;
import com.hagent.memory.longterm.MemoryEntry;
import com.hagent.security.Role;
import com.hagent.security.UserContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 长期记忆三个工具的测试 —— 只测"给模型看的那句话"与"身份透传"。
 *
 * <p>为什么值得单测文案：<b>模型看到的只有这些字符串</b>。
 * 文案若把"索引满了"说成"已保存"，模型会以为记住了，而实际上什么都没写 ——
 * 用户下次发现它"忘了"，而日志里一切正常。
 */
class LongTermMemoryToolsTest {

    private static final UserContext EMPLOYEE = new UserContext("zhangsan", Role.EMPLOYEE);

    private static ToolContext contextWith(UserContext user) {
        return new ToolContext(Map.of(ToolContextSupport.USER, user));
    }

    @Test
    @DisplayName("★ saveMemory：新增与覆盖给出【不同】的话 —— 模型据此知道自己刚做了什么")
    void saveTellsCreatedVersusUpdated() {
        LongTermMemoryStore store = mock(LongTermMemoryStore.class);
        when(store.save(eq("称呼偏好"), eq("叫他张工"), any()))
                .thenReturn(LongTermMemoryStore.SaveResult.CREATED)
                .thenReturn(LongTermMemoryStore.SaveResult.UPDATED);
        LongTermMemoryTools tools = new LongTermMemoryTools(store);

        String created = tools.saveMemory("称呼偏好", "叫他张工", contextWith(EMPLOYEE));
        String updated = tools.saveMemory("称呼偏好", "叫他张工", contextWith(EMPLOYEE));

        assertThat(created).contains("已记住");
        assertThat(updated).contains("已更新");
        verify(store, org.mockito.Mockito.times(2)).save(eq("称呼偏好"), eq("叫他张工"), eq(EMPLOYEE));
    }

    @Test
    @DisplayName("★★ 索引满时不能说'已保存'，而且必须告诉它下一步能做什么")
    void indexFullSaysNotSavedWithNextStep() {
        LongTermMemoryStore store = mock(LongTermMemoryStore.class);
        when(store.save(any(), any(), any())).thenReturn(LongTermMemoryStore.SaveResult.INDEX_FULL);
        LongTermMemoryTools tools = new LongTermMemoryTools(store);

        String result = tools.saveMemory("新偏好", "内容", contextWith(EMPLOYEE));

        assertThat(result)
                .as("说成'已保存'就等于骗模型：它会以为记住了，下次却说不出 —— 而日志里一切正常")
                .contains("没有写入")
                .contains("forgetMemory");
    }

    @Test
    @DisplayName("★ 密级不允许覆盖时，告诉模型换标题，而不是让它反复重试")
    void forbiddenSuggestsAnotherTitle() {
        LongTermMemoryStore store = mock(LongTermMemoryStore.class);
        when(store.save(any(), any(), any())).thenReturn(LongTermMemoryStore.SaveResult.FORBIDDEN);
        LongTermMemoryTools tools = new LongTermMemoryTools(store);

        assertThat(tools.saveMemory("薪资结构", "改一下", contextWith(EMPLOYEE)))
                .contains("无权")
                .contains("换一个标题");
    }

    @Test
    @DisplayName("★ readMemory：命中时带标题/密级/时间；未命中与无权给【同一句】答复（防标题枚举）")
    void readMemoryDistinguishesFoundFromNotFound() {
        LongTermMemoryStore store = mock(LongTermMemoryStore.class);
        when(store.find(eq("所在城市"), any())).thenReturn(Optional.of(
                new MemoryEntry("所在城市", "用户在杭州",
                        com.hagent.document.AccessLevel.INTERNAL,
                        java.time.Instant.parse("2026-10-18T00:00:00Z"),
                        java.time.Instant.parse("2026-10-18T00:00:00Z"))));
        // 其它标题一律"查不到" —— 站在工具这一层，"不存在"和"无权看"本来就是同一个返回值
        when(store.find(eq("别人的记忆"), any())).thenReturn(Optional.empty());
        when(store.find(eq("根本不存在的"), any())).thenReturn(Optional.empty());
        LongTermMemoryTools tools = new LongTermMemoryTools(store);

        assertThat(tools.readMemory("所在城市", contextWith(EMPLOYEE)))
                .contains("杭州")
                .contains("INTERNAL");

        assertThat(tools.readMemory("别人的记忆", contextWith(EMPLOYEE)))
                .as("★ '存在但你看不到'与'根本不存在'必须给出【逐字相同】的答复 —— "
                        + "否则攻击者可以靠两者的差异枚举出别人有哪些记忆标题")
                .isEqualTo(tools.readMemory("根本不存在的", contextWith(EMPLOYEE)));
    }

    @Test
    @DisplayName("★ forgetMemory：删成功说'已忘记'，删不到说'没有找到'")
    void forgetReportsOutcome() {
        LongTermMemoryStore store = mock(LongTermMemoryStore.class);
        when(store.forget(eq("所在城市"), any())).thenReturn(true);
        when(store.forget(eq("不存在的"), any())).thenReturn(false);
        LongTermMemoryTools tools = new LongTermMemoryTools(store);

        assertThat(tools.forgetMemory("所在城市", contextWith(EMPLOYEE))).contains("已忘记");
        assertThat(tools.forgetMemory("不存在的", contextWith(EMPLOYEE))).contains("没有找到");
    }

    @Test
    @DisplayName("★ 缺身份 → 落匿名（最小权限），不抛异常")
    void missingIdentityFallsBackToAnonymous() {
        LongTermMemoryStore store = mock(LongTermMemoryStore.class);
        when(store.save(any(), any(), any())).thenReturn(LongTermMemoryStore.SaveResult.CREATED);
        LongTermMemoryTools tools = new LongTermMemoryTools(store);

        tools.saveMemory("随便", "内容", null);

        verify(store).save(eq("随便"), eq("内容"), eq(UserContext.ANONYMOUS));
    }
}
