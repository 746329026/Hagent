package com.hagent.controller;

import com.hagent.app.HealthAppService;
import com.hagent.common.BaseResponse;
import com.hagent.observability.StartupProbe;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 健康检查 —— 阶段 0 的验收出口，也是"薄控制器"的样板。
 *
 * <p>刻意保持极薄：控制器只做"收参数、调 app 层、返回结果"三件事，
 * <b>不含任何业务判断</b>。后续所有接口都按这个形态写，
 * 业务逻辑一律下沉到 app 层。
 */
@RestController
@RequestMapping("/health")
@RequiredArgsConstructor
public class HealthController {

    private final StartupProbe startupProbe;
    private final HealthAppService healthAppService;

    /** 基础存活检查：只证明进程活着，不碰任何外部依赖。 */
    @GetMapping
    public BaseResponse<Map<String, Object>> health() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", "UP");
        data.put("application", "hagent");
        data.put("message", healthAppService.describe());
        data.put("time", LocalDateTime.now());
        return BaseResponse.success(data);
    }

    /**
     * 详细检查：把启动探针的真实结论暴露出来。
     *
     * <p>为什么要暴露而不是只看日志：演示与排障时需要一个"可被程序消费"的结论，
     * 而不是让人去日志里翻。
     *
     * @param refresh 是否强制重跑探针。默认 false —— 重跑会真实调用模型，
     *                因此不设为默认行为，避免健康检查被轮询时持续产生费用。
     */
    @GetMapping("/detail")
    public BaseResponse<Map<String, Object>> detail(
            @RequestParam(defaultValue = "false") boolean refresh) {
        if (refresh) {
            startupProbe.rerun();
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("allPassed", startupProbe.allPassed());
        data.put("probe", startupProbe.snapshot());
        return BaseResponse.success(data);
    }
}