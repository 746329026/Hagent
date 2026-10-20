package com.hagent.document;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link AccessLevel} 的解析规则。
 *
 * <h2>★ 为什么这个类现在才出现（阶段 12 第 2 步）</h2>
 *
 * <p>在此之前，"密级字符串 → 枚举"的规则被<b>抄了三遍</b>：
 * {@code AccessLevel.parse}（入库，抛异常）、{@code RetrievedChunk.level}（检索，静默回退）、
 * {@code FileLongTermMemoryStore.parseLevelOrWarn}（长期记忆，WARN + 回退）。
 * 三处<b>规则</b>相同、<b>策略</b>不同 —— 而没有任何测试盯着"它们说的是同一条规则"。
 *
 * <p>现在规则只有一处（{@code parse} 与 {@code tryParse} 共用匹配逻辑），
 * 于是测试可以只钉两件事：
 * <ol>
 *   <li><b>严格</b>策略：非法值必须<b>抛</b>，且消息里点名"允许的取值"（作者要能改对）；</li>
 *   <li><b>宽松</b>策略：非法值返回<b>空</b>（由调用点决定 WARN / 默认），空白输入给默认值。</li>
 * </ol>
 */
class AccessLevelTest {

    @Test
    @DisplayName("严格解析：未知取值必须抛，且消息里列出允许的取值")
    void strictParseRejectsUnknownValue() {
        assertThatThrownBy(() -> AccessLevel.parse("绝密"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知的密级取值")
                .hasMessageContaining("PUBLIC")
                .as("必须列出合法取值 —— 否则作者只知道错了、不知道改成什么")
                ;
    }

    @Test
    @DisplayName("严格解析：大小写与首尾空白不敏感；空白视为默认值")
    void strictParseIsCaseAndWhitespaceInsensitive() {
        assertThat(AccessLevel.parse("  internal ")).isEqualTo(AccessLevel.INTERNAL);
        assertThat(AccessLevel.parse("Confidential")).isEqualTo(AccessLevel.CONFIDENTIAL);
        assertThat(AccessLevel.parse("")).isEqualTo(AccessLevel.DEFAULT);
        assertThat(AccessLevel.parse(null)).isEqualTo(AccessLevel.DEFAULT);
    }

    @Test
    @DisplayName("★ 宽松解析：非法值返回空 —— 让调用点自己决定后果，而不是在这里静默吞掉")
    void lenientParseReturnsEmptyForUnknownValue() {
        assertThat(AccessLevel.tryParse("绝密")).isEmpty();
        assertThat(AccessLevel.tryParse("PUBLIC")).contains(AccessLevel.PUBLIC);
        assertThat(AccessLevel.tryParse("  ")).contains(AccessLevel.DEFAULT);
    }

    @Test
    @DisplayName("★★ 两条策略必须对合法值给出同一个答案（规则只有一个出处）")
    void bothPoliciesAgreeOnEveryValidValue() {
        for (AccessLevel level : AccessLevel.values()) {
            assertThat(AccessLevel.tryParse(level.name())).contains(level);
            assertThat(AccessLevel.parse(level.name())).isEqualTo(level);
        }
    }

    @Test
    @DisplayName("默认密级是 INTERNAL（偏保守：宁可少看到，不可多看到）")
    void defaultIsInternal() {
        assertThat(AccessLevel.DEFAULT).isEqualTo(AccessLevel.INTERNAL);
        assertThat(AccessLevel.PUBLIC.visibleTo(AccessLevel.PUBLIC)).isTrue();
        assertThat(AccessLevel.CONFIDENTIAL.visibleTo(AccessLevel.PUBLIC)).isFalse();
    }
}
