package com.hagent.memory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link MemoryMode} 的线上名称映射。
 *
 * <p>值得单独测：它是请求体、响应体、数据库三处共用的一层翻译，写错的表现极其安静 ——
 * "模式存进库变成了 null"或"客户端传 workflow 却按 chat 的 1/10 预算在跑"，两种都不报错。
 */
class MemoryModeTest {

    @Test
    @DisplayName("线上名称是 chat / workflow，不是枚举名 CHAT / WORKFLOW")
    void wireNames() {
        assertThat(MemoryMode.CHAT.wireName()).isEqualTo("chat");
        assertThat(MemoryMode.WORKFLOW.wireName()).isEqualTo("workflow");
    }

    @Test
    @DisplayName("大小写不敏感，枚举名也认（人输入的参数不该因大小写失败）")
    void fromWireIsLenientAboutCase() {
        assertThat(MemoryMode.fromWire("chat")).isEqualTo(MemoryMode.CHAT);
        assertThat(MemoryMode.fromWire("CHAT")).isEqualTo(MemoryMode.CHAT);
        assertThat(MemoryMode.fromWire(" Workflow ")).isEqualTo(MemoryMode.WORKFLOW);
        assertThat(MemoryMode.fromWire("WORKFLOW")).isEqualTo(MemoryMode.WORKFLOW);
    }

    @Test
    @DisplayName("不传 → 默认会话模式（老客户端与手工 curl 是合法状态）")
    void blankFallsBackToDefault() {
        assertThat(MemoryMode.fromWire(null)).isEqualTo(MemoryMode.DEFAULT);
        assertThat(MemoryMode.fromWire("")).isEqualTo(MemoryMode.DEFAULT);
        assertThat(MemoryMode.fromWire("   ")).isEqualTo(MemoryMode.DEFAULT);
    }

    @Test
    @DisplayName("★ 打错字 → 抛异常，而不是静默退回默认值")
    void unknownValueThrows() {
        assertThatThrownBy(() -> MemoryMode.fromWire("wrokflow"))
                .as("静默退回默认值会让『打错一个字母』表现为『模型忽然开始忘事』"
                        + "（预算少了 10 倍），且没有人会发现")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("wrokflow");
    }

    @Test
    @DisplayName("★ 每个模式都必须能翻译成线上名称（新增模式时会编译失败，不是运行期漏掉）")
    void everyModeHasWireName() {
        for (MemoryMode mode : MemoryMode.values()) {
            assertThat(mode.wireName()).isNotBlank();
            assertThat(MemoryMode.fromWire(mode.wireName()))
                    .as("往返必须恒等，否则数据库里存的值读回来会变成另一个模式")
                    .isEqualTo(mode);
        }
    }
}
