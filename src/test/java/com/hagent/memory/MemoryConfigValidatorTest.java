package com.hagent.memory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 记忆配置的早期校验测试。
 *
 * <p><b>为什么这个类值得单独写</b>：{@link MemoryConfigValidator} 是
 * {@code EnvironmentPostProcessor}，它在<b>任何 Bean 创建之前</b>运行 ——
 * 也就是说，它的失败是"应用根本起不来"，而不是"某个功能不可用"。
 * 一个会阻断启动的组件，必须有直接的测试，而不是靠"端到端跑一遍看起来没事"。
 *
 * <p>阶段 7 把配置从<b>一个键</b>（{@code type}）改成<b>两个键</b>
 * （{@code record-store} / {@code context-cache}）。这里同时钉住"旧键必须明确报错"——
 * 否则旧配置被静默忽略，"改了配置却没生效"这个老问题会立刻回来。
 */
class MemoryConfigValidatorTest {

    private final MemoryConfigValidator validator = new MemoryConfigValidator();

    private static final String RECORD = "hagent.memory.chat.record-store";
    private static final String CONTEXT = "hagent.memory.chat.context-cache";
    private static final String LEGACY = "hagent.memory.chat.type";

    private MockEnvironment env(String... keyValues) {
        MockEnvironment environment = new MockEnvironment();
        for (int i = 0; i < keyValues.length; i += 2) {
            environment.setProperty(keyValues[i], keyValues[i + 1]);
        }
        return environment;
    }

    @Test
    @DisplayName("★ 旧键 type 必须【明确报错】，不能被静默忽略")
    void legacyTypeKeyIsRejectedLoudly() {
        assertThatThrownBy(() -> validator.postProcessEnvironment(
                env(RECORD, "mysql", CONTEXT, "redis", LEGACY, "file"), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(LEGACY)
                .hasMessageContaining("已废弃")
                // 报错必须告诉人"改成什么"，否则只是一个坏消息
                .hasMessageContaining(RECORD)
                .hasMessageContaining(CONTEXT);
    }

    @Test
    @DisplayName("两个键都给合法值时通过")
    void validValuesPass() {
        assertThatCode(() -> validator.postProcessEnvironment(
                env(RECORD, "mysql", CONTEXT, "redis"), null))
                .doesNotThrowAnyException();

        assertThatCode(() -> validator.postProcessEnvironment(
                env(RECORD, "file", CONTEXT, "none"), null))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("★ 记录层取值非法：启动失败，并说清合法取值与不降级的理由")
    void invalidRecordStoreFailsFast() {
        assertThatThrownBy(() -> validator.postProcessEnvironment(
                env(RECORD, "mysq1", CONTEXT, "redis"), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(RECORD)
                .hasMessageContaining("取值非法")
                .hasMessageContaining("而不是降级");
    }

    @Test
    @DisplayName("★ 上下文层取值非法：启动失败")
    void invalidContextCacheFailsFast() {
        assertThatThrownBy(() -> validator.postProcessEnvironment(
                env(RECORD, "mysql", CONTEXT, "redls"), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(CONTEXT)
                .hasMessageContaining("取值非法");
    }

    @Test
    @DisplayName("★ 两个键任一缺失：启动失败（不能靠默认值蒙混过去）")
    void missingKeysFailFast() {
        assertThatThrownBy(() -> validator.postProcessEnvironment(env(CONTEXT, "redis"), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(RECORD)
                .hasMessageContaining("不能为空");

        assertThatThrownBy(() -> validator.postProcessEnvironment(env(RECORD, "mysql"), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(CONTEXT)
                .hasMessageContaining("不能为空");
    }

    @Test
    @DisplayName("大小写与空格容错（配置是给人写的，不该因为 YAML 里多一个空格就起不来）")
    void valuesAreNormalized() {
        assertThatCode(() -> validator.postProcessEnvironment(
                env(RECORD, "  MySQL ", CONTEXT, "REDIS"), null))
                .doesNotThrowAnyException();
    }
}
