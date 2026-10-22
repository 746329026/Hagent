package com.hagent.app;

import com.hagent.observability.TurnRecord;
import com.hagent.observability.TurnStore;

import java.util.ArrayList;
import java.util.List;

/**
 * 回合账的测试替身 —— 与 {@link TestAudit} 同风格。
 *
 * <p>必须有"记录下来的"实现：核心断言是<b>一轮多步对话的 token 是各次调用之和，不是最后一次</b>
 * （旧 {@code Final.usage} 的病），要断言它就得把收尾写入的那条记录取出来看；
 * {@code status} 三值（SUCCESS / FAILED / CANCELLED）同理 —— 若只能测"没抛异常"，
 * "取消被记成失败"会<b>静默</b>把成功率压下去。
 */
final class TestTurn {

    private TestTurn() {
    }

    /** 什么都不做 —— 只关心对话行为、不关心账的用例用它。 */
    static TurnStore noop() {
        return new TurnStore() {
            @Override
            public void record(TurnRecord record) {
            }

            @Override
            public java.util.Map<String, TurnRecord> find(java.util.Collection<String> turnIds) {
                // "没有账"就是空 Map —— 不编造（历史里那些轮次会显示不出数字，这是诚实的）
                return java.util.Map.of();
            }
        };
    }

    /** 把每条记录攒下来供断言。 */
    static Recording recording() {
        return new Recording();
    }

    /** 记录下来的实现。 */
    static final class Recording implements TurnStore {

        private final List<TurnRecord> recorded = new ArrayList<>();

        @Override
        public void record(TurnRecord record) {
            if (record != null) {
                recorded.add(record);
            }
        }

        @Override
        public java.util.Map<String, TurnRecord> find(java.util.Collection<String> turnIds) {
            java.util.Map<String, TurnRecord> found = new java.util.LinkedHashMap<>();
            if (turnIds == null) {
                return found;
            }
            for (TurnRecord record : recorded) {
                if (turnIds.contains(record.turnId())) {
                    found.put(record.turnId(), record);
                }
            }
            return found;
        }

        List<TurnRecord> recorded() {
            return recorded;
        }

        /** 唯一的那一条 —— 一轮只应写一次。多于一条本身就是断言失败。 */
        TurnRecord single() {
            if (recorded.size() != 1) {
                throw new AssertionError("期望恰好一条回合账，实际 " + recorded.size() + " 条");
            }
            return recorded.get(0);
        }
    }
}
