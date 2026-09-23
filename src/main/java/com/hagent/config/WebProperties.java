package com.hagent.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Web 层配置项，绑定 {@code hagent.web.*}。
 *
 * <p><b>★ 这里踩过一个坑，值得完整记下来（不是"猜的"，是实测出来的）</b>：
 *
 * <p>最初我用 {@code @Value("${hagent.web.allowed-origins}")} 直接注入
 * {@code List<String>}，结果启动直接失败：
 *
 * <pre>
 *   PlaceholderResolutionException:
 *   Could not resolve placeholder 'hagent.web.allowed-origins' in value "${hagent.web.allowed-origins}"
 * </pre>
 *
 * <p>排查过程（体现了"先排除再定位"的顺序）：
 * <ol>
 *   <li>先怀疑 YAML 缩进／隐藏字符 —— 用码点逐字打印检查，<b>缩进与字符全正常</b>；</li>
 *   <li>再确认 {@code target/classes/application.yml} 副本 —— <b>配置确实被复制了</b>；</li>
 *   <li>于是定位到问题在 {@code @Value} 本身。</li>
 * </ol>
 *
 * <p><b>根因</b>：YAML 里的<b>列表</b>在 Spring 的 Environment 中会被展开成
 * <b>带下标的多条属性</b>：
 *
 * <pre>
 *   hagent.web.allowed-origins[0]=http://localhost:5500
 *   hagent.web.allowed-origins[1]=http://127.0.0.1:5500
 *   hagent.web.allowed-origins[2]=http://localhost:8080
 * </pre>
 *
 * 不存在名为 {@code hagent.web.allowed-origins} 的<b>单个</b>属性值；
 * 而 {@code @Value} 的占位符解析只做"按键取值"，不做"按结构绑定"，所以取不到。
 *
 * <p><b>正确做法</b>：用 {@code @ConfigurationProperties} —— 它是"按结构绑定"，
 * 能理解下标，把这些属性重新组装成一个 List。
 *
 * <p><b>可迁移认知</b>：{@code @Value} 适合取<b>标量</b>（单个字符串/数字/布尔）；
 * 一旦配置是<b>结构化的</b>（列表、嵌套对象、Map），就应该用
 * {@code @ConfigurationProperties}。本项目其它配置类（如 VectorStoreProperties）
 * 也是这个做法，保持一致。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "hagent.web")
public class WebProperties {

    /**
     * CORS 白名单：只有列出的源才允许跨域访问本服务。
     *
     * <p>刻意不给"放开一切"的默认值 —— 若配置缺失，宁可启动失败也不要静默地对全网开放。
     * （对比参考实现的 {@code allowedOriginPatterns("*") + allowCredentials(true)}，
     * 那是把自己接口对任意网站开放的反模式。）
     */
    private List<String> allowedOrigins = List.of();

    /**
     * 预检请求（preflight）结果的浏览器缓存时长，单位秒。
     *
     * <p><b>它到底控制什么</b>：浏览器在正式发起跨域请求前，会先发一个 {@code OPTIONS}
     * 预检请求问"我能不能这么调"。这个值决定"<b>这份答复能在浏览器里缓存多久</b>"。
     * 缓存有效期内，同一个源/方法/头的跨域调用<b>不再重复发预检请求</b>。
     *
     * <p><b>与 Cookie 无关</b>：Cookie 能不能带是 {@code allowCredentials} 管的事，
     * 两者是不同维度（一个是布尔开关，一个是秒数）。
     *
     * <p><b>取值取舍（这是它值得外置的原因：开发和生产要的值相反）</b>：
     * <ul>
     *   <li>值**大**（如 86400 = 1 天）：省掉大量预检往返，性能好；
     *       但<b>改了 CORS 配置后，浏览器要等缓存过期才生效</b>——
     *       表现为"我明明改了白名单，怎么还是不行"，很难排查。</li>
     *   <li>值**小**（如 0 / 300）：每次都要预检（多一次往返），
     *       但<b>改配置立即生效</b>。</li>
     * </ul>
     *
     * <p>所以：<b>开发环境建议 0~300，生产环境可以 3600~86400</b>。
     * 当前默认 3600 是本地开发的折中值。
     */
    private int maxAgeSeconds = 3600;
}
