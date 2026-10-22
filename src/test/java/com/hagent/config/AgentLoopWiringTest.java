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
 * <p>这条测试值一个 {@code @SpringBootTest}，因为同一类坑踩过两次：
 * {@code hagent.memory.long-term.dir} 从写在 yml 里起就<b>没有任何 Java 字段绑定它</b>
 * （改了它以为换了目录，其实什么都没发生）；{@code hagent.agent.max-steps: 20} 同样配着没人读，
 * 真正生效的是另一个键里的 10。<b>Spring 默认忽略未知字段</b>，所以"键写了但没绑上"是<b>静默</b>的，
 * 而单元测试（{@link AgentLoopPropertiesTest}）只能证明"类里有个默认值"。
 *
 * <p>为什么用 properties 覆盖而不是断言 yml 里的 10 / 20：类内默认值恰好也是 10 / 20，
 * 直接断言 yml 的值时<b>即使绑定完全失效（Bean 用的是默认值）测试也会通过</b> —— 那是假防线。
 * 覆盖成 7 / 9 才能区分"绑上了"和"用了默认值"。
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
