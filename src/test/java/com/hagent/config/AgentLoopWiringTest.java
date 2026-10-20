package com.hagent.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code hagent.agent.*} 的<b>绑定</b>测试 —— 真实启动 Spring 上下文，只证明一件事：
 * <b>yml 里的键真的走到了 Bean 上</b>。
 *
 * <h2>★ 为什么这条测试值一个 {@code @SpringBootTest}</h2>
 *
 * <p>本项目踩过两次同一类坑（踩坑 #12 / #39）：
 * <ul>
 *   <li>{@code hagent.memory.long-term.dir} 从阶段 5 就写在 yml 里，却<b>没有任何 Java 字段绑定它</b>
 *       —— 改了它以为换了目录，其实什么都没发生；</li>
 *   <li>{@code hagent.agent.max-steps: 20} 同样配着没人读，而真正生效的是另一个键里的 10。</li>
 * </ul>
 *
 * <p>两者的共同特征是：<b>Spring 默认忽略未知字段</b>，所以"键写了但没绑上"是<b>静默</b>的。
 * 单元测试（{@link AgentLoopPropertiesTest}）只能证明"类里有个默认值"，
 * 证明不了"yml 的值能到达它"。
 *
 * <h2>★ 为什么用 properties 覆盖而不是断言 yml 里的 10 / 20</h2>
 *
 * <p>类内默认值恰好也是 10 / 20。若直接断言 yml 的值，
 * <b>即使绑定完全失效（Bean 用的是默认值）测试也会通过</b> —— 那是一条假防线。
 * 所以这里覆盖成 7 / 9：<b>与默认值不同的数字</b>才能区分"绑上了"和"用了默认值"。
 * （同一手法见 {@code ChatBudgetTest} 的注释："刻意用与默认值不同的数字"。）
 */
@SpringBootTest(properties = {
        // 关掉启动探针的真实模型调用：本测试只关心配置绑定，不该产生费用
        "hagent.probe.live-call=false",
        "hagent.agent.chat-max-steps=7",
        "hagent.agent.workflow-max-steps=9"
})
class AgentLoopWiringTest {

    @Autowired
    private AgentLoopProperties agentLoopProperties;

    @Test
    @DisplayName("★★ hagent.agent.* 真的绑到了 AgentLoopProperties（不是用了类内默认值）")
    void ymlKeysAreActuallyBound() {
        System.out.println("[装配证据] hagent.agent -> chat=" + agentLoopProperties.getChatMaxSteps()
                + " workflow=" + agentLoopProperties.getWorkflowMaxSteps()
                + "（覆盖值 7/9，默认值 10/20 —— 拿到 7/9 才说明绑定生效）");

        assertThat(agentLoopProperties.getChatMaxSteps())
                .as("拿到 10 说明绑定没生效、Bean 用的是类内默认值 —— 那正是踩坑 #39 的形状")
                .isEqualTo(7);
        assertThat(agentLoopProperties.getWorkflowMaxSteps())
                .isEqualTo(9);
    }
}
