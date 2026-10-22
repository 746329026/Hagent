package com.hagent.config;

import com.hagent.memory.longterm.FileLongTermMemoryStore;
import com.hagent.memory.longterm.LongTermMemoryStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 长期记忆的装配。
 *
 * <p>相比会话记忆（{@code MemoryConfig} 里两组条件装配、四种组合），这里<b>只有一个实现</b>：
 * 人可读的 {@code .md} + 索引。
 *
 * <p>★ 刻意<b>不抽第二个实现、也不加"用哪个实现"的开关</b>：那会造出一个永远只有一种取值的
 * 配置。接口保留是为了<b>给管线一个可替换的读入口</b>（测试用假的，不落盘）。
 *
 * <p>★ 归属：装配类（{@code @Configuration}）一律进 {@code com.hagent.config}，
 * 判据是"它是什么种类"而不是"它讲什么业务"。这条由 {@code PackagePlacementTest} 守着。
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
