package com.hagent.memory;

import com.hagent.config.MemoryProperties;

import com.hagent.memory.context.NoOpContextCache;
import com.hagent.memory.record.FileMessageStore;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;

/**
 * 契约组合①：file 记录层 + none 上下文层 —— 零基础设施组合。
 *
 * <p>不起 Spring、不依赖任何外部服务：契约测试里跑得最快的一个，也是"新环境里能不能跑起来"的保证。
 * 熔断窗口设为 0（关闭），避免"上一个用例的失败"静默掉后面 30 秒的调用、让失败难以定位。
 */
class FileNoOpContractTest extends ConversationMemoryContractTest {

    @TempDir
    Path tempDir;

    @Override
    protected ConversationMemory newMemory() {
        MemoryProperties props = new MemoryProperties();
        props.setFileDir(tempDir.toString());
        return new LayeredConversationMemory(
                new FileMessageStore(props, new ObjectMapper()),
                new NoOpContextCache(),
                0);
    }
}
