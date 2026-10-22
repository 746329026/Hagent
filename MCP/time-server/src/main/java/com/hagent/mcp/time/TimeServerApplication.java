package com.hagent.mcp.time;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 时间 MCP Server 的入口 —— 一个<b>非 web</b> 的 Spring Boot 应用。
 *
 * <p>它启动后不会监听任何端口：与客户端的通信走 <b>stdio</b>
 * （父进程用管道连上它的 stdin/stdout）。所以：
 *
 * <ul>
 *   <li>没有端口，也就没有"未鉴权的 HTTP 端点"这类风险；</li>
 *   <li><b>stdout 是协议通道</b> —— 任何非 JSON 的打印都会破坏协议
 *       （见 {@code logback-spring.xml}：日志全部改到 stderr）；</li>
 *   <li>进程生命周期由客户端管理：客户端断开/退出，管道关闭，本进程随之结束。</li>
 * </ul>
 */
@SpringBootApplication
public class TimeServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(TimeServerApplication.class, args);
    }
}
