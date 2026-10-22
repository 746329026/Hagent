package com.hagent.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Web 层配置项，绑定 {@code hagent.web.*}。
 *
 * <p>★ 为什么用 {@code @ConfigurationProperties} 而不是 {@code @Value}：YAML 里的<b>列表</b>在
 * Environment 中会被展开成<b>带下标的多条属性</b>（{@code hagent.web.allowed-origins[0]=...}），
 * 不存在名为 {@code hagent.web.allowed-origins} 的<b>单个</b>属性值。{@code @Value} 的占位符解析
 * 只做"按键取值"，取不到 → 启动直接失败（{@code PlaceholderResolutionException}）；
 * {@code @ConfigurationProperties} 是"按结构绑定"，能理解下标并重新组装成 List。
 *
 * <p>可迁移认知：{@code @Value} 适合取<b>标量</b>（字符串/数字/布尔）；一旦配置是<b>结构化的</b>
 * （列表、嵌套对象、Map），就应该用 {@code @ConfigurationProperties}。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "hagent.web")
public class WebProperties {

    /**
     * CORS 白名单：只有列出的源才允许跨域访问本服务。
     *
     * <p>刻意不给"放开一切"的默认值 —— 若配置缺失，宁可启动失败也不要静默地对全网开放。
     */
    private List<String> allowedOrigins = List.of();

    /**
     * 预检请求（preflight）结果的浏览器缓存时长，单位秒。
     *
     * <p><b>它控制什么</b>：浏览器在正式发起跨域请求前会先发一个 {@code OPTIONS} 预检问
     * "我能不能这么调"；这个值决定<b>这份答复能在浏览器里缓存多久</b>，缓存有效期内同一源/方法/头
     * 不再重复发预检。<b>与 Cookie 无关</b>（那是 {@code allowCredentials} 管的）。
     *
     * <p><b>取值取舍（这是它值得外置的原因：开发和生产要的值相反）</b>：
     * 值**大**（如 86400）省掉大量预检往返，但<b>改了 CORS 配置要等缓存过期才生效</b>，
     * 表现为"我明明改了白名单，怎么还是不行"；值**小**（0 / 300）每次都要预检，
     * 但改配置立即生效。所以：<b>开发 0~300，生产 3600~86400</b>；默认 3600 是本地开发的折中值。
     */
    private int maxAgeSeconds = 3600;
}
