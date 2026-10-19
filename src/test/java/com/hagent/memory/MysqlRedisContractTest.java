package com.hagent.memory;

import com.hagent.config.MemoryProperties;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 契约组合③：<b>mysql 记录层 + redis 上下文层</b> —— 生产组合。
 *
 * <p>除了继承来的契约断言，这里额外验一条<b>只有这个组合才能验</b>的性质：
 * 缓存被清掉之后，仍能从记录层重建 —— 也就是阶段 7 的核心承诺
 * "<b>Redis 是模型上下文，不是对话记录的存放处</b>"。
 */
@SpringBootTest(properties = {
        "hagent.memory.chat.circuit-break-seconds=0"
})
class MysqlRedisContractTest extends ConversationMemoryContractTest {

    @Autowired
    private ConversationMemory memory;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private MemoryProperties properties;

    @Override
    protected ConversationMemory newMemory() {
        return memory;
    }

    @Test
    @DisplayName("★★ 把 Redis 里这个会话的缓存整个删掉，历史仍读得到 —— 权威在 MySQL，不在 Redis")
    void historySurvivesCacheWipe() {
        String conversationId = "contract-wipe-" + UUID.randomUUID();
        try {
            memory.append(conversationId, List.of(
                    new UserMessage("缓存被清也要记得我"),
                    new UserMessage("第二句")));

            // 先确认缓存里确实有东西 —— 否则这条测试会"因为缓存根本没生效"而假通过
            String key = properties.getRedisKeyPrefix() + conversationId;
            assertThat(redis.opsForList().size(key))
                    .as("前置条件：缓存里应当有内容，否则本用例证明不了任何事")
                    .isEqualTo(2);

            // 模拟"缓存丢了"：TTL 到期、换实例、Redis 被清空 —— 都是同一种情形
            redis.delete(key);
            assertThat(redis.opsForList().size(key)).isZero();

            // ★ 关键断言：历史必须还在
            assertThat(memory.load(conversationId, BIG))
                    .extracting(Message::getText)
                    .containsExactly("缓存被清也要记得我", "第二句");

            // 而且它应当顺手把缓存回填了（下次读又是命中路径）
            assertThat(redis.opsForList().size(key))
                    .as("重建后应当回填缓存")
                    .isEqualTo(2);
        } finally {
            memory.delete(conversationId);
        }
    }

    @Test
    @DisplayName("★ 缓存命中路径：同一会话第二次读取走的是 Redis，内容与第一次一致")
    void secondReadComesFromCache() {
        String conversationId = "contract-hit-" + UUID.randomUUID();
        try {
            memory.append(conversationId, List.of(new UserMessage("命中路径验证")));

            List<Message> first = memory.load(conversationId, BIG);
            List<Message> second = memory.load(conversationId, BIG);

            assertThat(second).extracting(Message::getText)
                    .isEqualTo(first.stream().map(Message::getText).toList());
            assertThat(redis.opsForList().size(properties.getRedisKeyPrefix() + conversationId))
                    .isEqualTo(1);
        } finally {
            memory.delete(conversationId);
        }
    }
}
