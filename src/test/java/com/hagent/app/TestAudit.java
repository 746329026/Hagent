package com.hagent.app;

import com.hagent.tool.audit.ToolInvocationStore;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.List;

/**
 * 测试用的审计存储 + 它的 {@code ObjectProvider}。
 *
 * <h2>★ 为什么需要它（两种测试需求）</h2>
 *
 * <ul>
 *   <li>{@link #disabled()} —— 模拟"审计被关掉"：{@code getIfAvailable()} 返回 null。
 *       ★ 这条路径<b>必须能测</b>：管线里 {@code auditStore == null} 时只记 WARN 不写库，
 *       而那是一个真实的生产状态（{@code hagent.tool.audit.enabled=false}）；</li>
 *   <li>{@link #recording()} —— 记录每次 {@code record(...)} 调用，
 *       让"审计真的写了、写的内容对不对"可以被断言。</li>
 * </ul>
 *
 * <p><b>为什么用一个手写的 {@code ObjectProvider} 而不是 Mockito</b>：
 * {@code ObjectProvider} 是个接口，这里只需要实现一个方法；
 * 手写实现比 mock 更清楚，也不会在"默认返回值"上产生意外
 * （Mockito 对未 stub 的方法返回 null，恰好等于"审计关闭" —— 那种巧合会让测试意图变模糊）。
 */
final class TestAudit {

    private TestAudit() {
    }

    /** 模拟"审计被关掉"：没有任何 ToolInvocationStore Bean。 */
    static ObjectProvider<ToolInvocationStore> disabled() {
        return new ObjectProvider<>() {
            @Override
            public ToolInvocationStore getObject() {
                throw new IllegalStateException("审计已关闭，不应有人取它");
            }

            @Override
            public ToolInvocationStore getIfAvailable() {
                return null;
            }
        };
    }

    /** 可断言的审计存储 + 它的 provider。 */
    static RecordingAudit recording() {
        return new RecordingAudit();
    }

    /** 记录型审计存储。 */
    static final class RecordingAudit implements ObjectProvider<ToolInvocationStore> {
        private final List<ToolInvocationStore.ToolInvocation> recorded = new ArrayList<>();

        private final ToolInvocationStore store = new ToolInvocationStore() {
            @Override
            public void record(ToolInvocation invocation) {
                recorded.add(invocation);
            }

            @Override
            public List<ToolInvocation> latest(String conversationId, int limit) {
                return List.copyOf(recorded);
            }
        };

        List<ToolInvocationStore.ToolInvocation> recorded() {
            return recorded;
        }

        @Override
        public ToolInvocationStore getObject() {
            return store;
        }

        @Override
        public ToolInvocationStore getIfAvailable() {
            return store;
        }
    }
}
