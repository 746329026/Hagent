package com.hagent.memory;

import com.hagent.config.MemoryProperties;

import com.hagent.memory.context.NoOpContextCache;
import com.hagent.memory.record.FileConversationRecordStore;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;

/**
 * 契约组合①：<b>file 记录层 + none 上下文层</b> —— 零基础设施组合。
 *
 * <p>它不起 Spring、不依赖任何外部服务，因此是契约测试里跑得最快的一个，
 * 也是"新环境/排查问题时能不能跑起来"的保证。
 *
 * <p>★ 熔断窗口设为 0（关闭）：测试里不希望"上一个用例的失败"静默掉后面 30 秒的调用，
 * 那会让失败定位变得极其困难。
 */
class FileNoOpContractTest extends ConversationMemoryContractTest {

    @TempDir
    Path tempDir;

    @Override
    protected ConversationMemory newMemory() {
        MemoryProperties props = new MemoryProperties();
        props.setFileDir(tempDir.toString());
        return new LayeredConversationMemory(
                new FileConversationRecordStore(props, new ObjectMapper()),
                new NoOpContextCache(),
                0);
    }
}
