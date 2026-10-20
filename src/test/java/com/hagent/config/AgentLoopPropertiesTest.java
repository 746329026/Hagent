package com.hagent.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link AgentLoopProperties} 的<b>默认值</b>与<b>启动期校验</b>。
 *
 * <h2>★ 为什么这两个值必须断言"不相等"</h2>
 *
 * <p>本项目曾经有过一个真实故障：{@code hagent.agent.max-steps: 20} 配在 yml 里、
 * <b>没有任何 Java 字段绑定它</b>，而真正生效的是另一个键里的 10。
 * 如果两档默认值恰好相同，那么"绑没绑上""取的是哪一档"这类错误<b>都不会被任何断言发现</b>
 * —— 它们会安静地通过。
 *
 * <p>所以这里的默认值刻意<b>不同</b>（10 / 20），并且下面逐条钉住：
 * 上限配 0 或负数时必须<b>启动失败且报出键名</b>，而不是等运行期出现
 * "模型明明要调工具却收到空回答"这种没人会联想到配置的症状。
 */
class AgentLoopPropertiesTest {

    @Test
    @DisplayName("默认值：chat 10 / workflow 20 —— 两档刻意不同")
    void defaultsArePerModeAndDifferent() {
        AgentLoopProperties props = new AgentLoopProperties();
        props.afterPropertiesSet();

        assertThat(props.getChatMaxSteps()).isEqualTo(10);
        assertThat(props.getWorkflowMaxSteps()).isEqualTo(20);
        assertThat(props.getChatMaxSteps())
                .as("两档相同的话，'取错了哪一档'就无法被测试发现")
                .isNotEqualTo(props.getWorkflowMaxSteps());
    }

    @Test
    @DisplayName("★ chat 配 0 → 启动失败，且报错点名那个键（决策 8：不静默降级）")
    void zeroChatStepsFailsFastNamingTheKey() {
        AgentLoopProperties props = new AgentLoopProperties();
        props.setChatMaxSteps(0);

        assertThatThrownBy(props::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("hagent.agent.chat-max-steps");
    }

    @Test
    @DisplayName("★ workflow 配负数 → 同样启动失败，且报的是它自己的键（不串台）")
    void negativeWorkflowStepsFailsFastNamingItsOwnKey() {
        AgentLoopProperties props = new AgentLoopProperties();
        props.setWorkflowMaxSteps(-1);

        assertThatThrownBy(props::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("hagent.agent.workflow-max-steps")
                .hasMessageNotContaining("chat-max-steps");
    }
}
