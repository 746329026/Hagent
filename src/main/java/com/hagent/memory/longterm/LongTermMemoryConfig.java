package com.hagent.memory.longterm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 长期记忆的装配。
 *
 * <p>相比会话记忆（{@code MemoryConfig} 里有两组条件装配、四种组合），这里<b>只有一个实现</b>：
 * 用户选定的形态就是"人可读的 {@code .md} + 索引"，没有第二个候选。
 *
 * <p>★ 所以<b>刻意不抽 {@code LongTermMemoryStore} 的第二个实现、也不加"用哪个实现"的开关</b>：
 * 那会造出一个永远只有一种取值的配置（本项目清了两次这类债）。
 * 接口本身留着是为了<b>给管线一个可替换的读入口</b>（测试用假的，不落盘），不是为了选型。
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(LongTermMemoryProperties.class)
public class LongTermMemoryConfig {

    @Bean
    public LongTermMemoryStore longTermMemoryStore(LongTermMemoryProperties properties) {
        return new FileLongTermMemoryStore(properties);
    }
}
