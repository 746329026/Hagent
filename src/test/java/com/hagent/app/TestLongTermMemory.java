package com.hagent.app;

import com.hagent.memory.longterm.LongTermMemoryStore;
import com.hagent.memory.longterm.MemoryEntry;
import com.hagent.security.UserContext;

import java.util.List;
import java.util.Optional;

/**
 * 测试专用的长期记忆桩 —— <b>不落盘</b>，只提供一个可配置的索引块。
 *
 * <p>管线要读"长期记忆索引"才能拼 prompt，而多数管线测试关心的是工具循环/预算/事件，
 * 让它们各自建临时目录写文件再清理只会更慢更脆。
 *
 * <p>只实现"读"：写（save/forget）是<b>工具</b>的职责，管线不该调，所以直接抛 ——
 * 一旦有人在管线里误加写入，测试会立刻炸，而不是悄悄产生副作用。
 */
final class TestLongTermMemory implements LongTermMemoryStore {

    private final String digest;

    private TestLongTermMemory(String digest) {
        this.digest = digest;
    }

    /** 没有任何记忆（绝大多数用例）。 */
    static LongTermMemoryStore empty() {
        return new TestLongTermMemory("");
    }

    /** 带一段索引（用来验证"记忆确实被注入 system"）。 */
    static LongTermMemoryStore withIndex(String digest) {
        return new TestLongTermMemory(digest);
    }

    @Override
    public List<MemoryEntry> list(UserContext user) {
        return List.of();
    }

    @Override
    public Optional<MemoryEntry> find(String title, UserContext user) {
        return Optional.empty();
    }

    @Override
    public String renderIndexFor(UserContext user) {
        return digest;
    }

    @Override
    public SaveResult save(String title, String content, UserContext user) {
        throw new UnsupportedOperationException("测试桩只提供读：写记忆是工具的职责，管线不该调它");
    }

    @Override
    public boolean forget(String title, UserContext user) {
        throw new UnsupportedOperationException("测试桩只提供读：删记忆是工具的职责，管线不该调它");
    }
}
