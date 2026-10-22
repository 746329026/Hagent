package com.hagent.retrieval;

import com.hagent.document.AccessLevel;
import com.hagent.document.DocumentMetadata;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 把"调用方身份"翻译成<b>向量库的过滤条件</b> —— 权限过滤的唯一出处。
 *
 * <h2>★ 把越权挡在数据库里，而不是取回来再筛</h2>
 *
 * <p>差别不是"优雅"与否，而是<b>能不能被绕过</b>：取回后筛，机密内容已经离开数据库、进了进程内存；
 * topK 名额被无权内容挤占（匿名用户问"年假"，检索出 3 条机密的差旅规定，筛掉后一条不剩）；
 * 漏筛一次就直接进 prompt 被模型复述给用户。检索前过滤把条件变成 SQL 的 WHERE，三件事都不成立。
 * 所以本类<b>不产生任何"中间结果"</b>，只产出一个 {@link Filter.Expression}，
 * 交给 {@code SearchRequest} 一起发给数据库。
 *
 * <h2>★ 为什么用 {@code IN} 而不是数值比较</h2>
 *
 * <p>{@code doc.level.rank <= user.clearance.rank} 是规则的<b>语义</b>，但 metadata 里的
 * {@code level} 存的是<b>字符串</b>，无法数值比较 —— 检索侧只能是"枚举出允许的取值"。
 * 两个形态的一致性由 {@link AccessLevel#visibleLevels} 保证，<b>本类不自己判断谁能看什么</b>，
 * 否则规则就出现了第二个出处。
 *
 * <p>空可见清单时<b>抛异常</b>而不是产出恒真条件：后者静默等于<b>全量可见</b>，
 * 方向与安全要求恰好相反。注意框架生成的过滤条件走 jsonpath（{@code @@}）——
 * 建 GIN 索引时必须逐字符匹配该表达式，否则索引白建且不报错。
 */
@Component
public class RetrievalFilterBuilder {

    /**
     * 构造"该许可等级可见内容"的过滤条件。
     *
     * @param clearance 调用方许可等级
     * @return 可直接交给 {@code SearchRequest.filterExpression(...)} 的表达式
     * @throws IllegalStateException 可见密级清单为空时（见类注释）
     */
    public Filter.Expression build(AccessLevel clearance) {
        List<AccessLevel> visible = AccessLevel.visibleLevels(clearance);
        if (visible.isEmpty()) {
            // 不返回 null、不返回恒真条件 —— 那两种写法都会把"看不到任何东西"
            // 变成"看到全部"，是安全性上最糟的失败方向。
            throw new IllegalStateException(
                    "可见密级清单为空（clearance=" + clearance + "），拒绝发起检索。"
                            + "若在这里返回空条件或不过滤，就会静默变成【全量可见】。");
        }

        List<String> levelNames = visible.stream().map(Enum::name).toList();

        // 字段名引用 DocumentMetadata 的常量 —— 入库写、检索读只有一个出处；
        // 写成裸字符串 "level" 与入库侧失步时，SQL 不报错、只是永远不命中。
        //
        // ★ 必须显式 toArray() 走【变长参数】重载：直接传 List 会【静默】渲染成数组比较。
        //   重载有两个 —— in(String, Object...) 逐值展开、in(String, List<Object>)；
        //   而 List<String> 不能赋给 List<Object>（泛型不变），于是回退到变长参数重载，
        //   把整个 List 当成【一个值】：($."level" == ["PUBLIC","INTERNAL"]) 恒为 false。
        //   症状只是"检索一条都没命中"（四种写法的渲染对照见 RetrievalFilterSqlTest）。
        return new FilterExpressionBuilder()
                .in(DocumentMetadata.FIELD_LEVEL, levelNames.toArray())
                .build();
    }
}
