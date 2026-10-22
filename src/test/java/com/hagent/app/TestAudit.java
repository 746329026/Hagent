package com.hagent.app;

import com.hagent.tool.audit.ToolInvocationStore;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.List;

/**
 * 测试用的审计存储 + 它的 {@code ObjectProvider}。
 *
 * <p>{@link #disabled()} 模拟审计关闭（{@code getIfAvailable()} 返回 null）：管线在
 * {@code auditStore == null} 时只记 WARN 不写库，而这是真实的生产状态
 * （{@code hagent.tool.audit.enabled=false}），必须能测。
 * {@link #recording()} 记录每次 {@code record(...)}，让"写了、写得对不对"可断言。
 *
 * <p>手写而非 Mockito：这里只需实现一个方法；且 Mockito 对未 stub 的方法返回 null，
 * 恰好等于"审计关闭"，那种巧合会让测试意图变模糊。
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
