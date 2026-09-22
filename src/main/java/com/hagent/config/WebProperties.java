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
}
