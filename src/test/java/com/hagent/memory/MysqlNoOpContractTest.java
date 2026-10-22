package com.hagent.memory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 契约组合②：mysql 记录层 + none 上下文层 —— 降级组合（不起 Redis）。
 *
 * <p>它证明上下文层的"有无"不改变对外语义 —— 这正是"上下文层是可丢的派生层"的直接证据：
 * 如果少了缓存就出现行为差异，那它就不是缓存，而是第二个权威存储。
 */
@SpringBootTest(properties = {
        "hagent.memory.chat.context-cache=none",
        // 测试里关闭熔断：避免"上一个用例的失败"静默掉后面 30 秒的调用，让失败难以定位
        "hagent.memory.chat.circuit-break-seconds=0"
})
class MysqlNoOpContractTest extends ConversationMemoryContractTest {

    @Autowired
    private ConversationMemory memory;

    @Override
    protected ConversationMemory newMemory() {
        return memory;
    }
}
