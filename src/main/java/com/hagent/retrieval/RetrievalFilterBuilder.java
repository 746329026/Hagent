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
 * <h2>★ 它解决什么问题：把越权挡在数据库里，而不是取回来再筛</h2>
 *
 * <p>两种做法的差别不是"优雅"与否，是<b>能不能被绕过</b>：
 *
 * <table border="1">
 *   <caption>取回后筛选 vs 检索前过滤</caption>
 *   <tr><th></th><th>取回后筛选</th><th>检索前过滤（本项目）</th></tr>
 *   <tr><td>机密内容</td><td>已经离开数据库、进了进程内存</td><td><b>没有离开数据库</b></td></tr>
 *   <tr><td>topK 名额</td><td>被无权内容挤占 —— 匿名用户问"年假"，
 *       检索出 3 条机密的差旅规定，筛掉后<b>一条都不剩</b></td>
 *       <td>名额只发给可见内容</td></tr>
 *   <tr><td>漏筛一次</td><td>越权内容直接进 prompt，被模型复述给用户</td>
 *       <td>不可能发生（条件是 SQL 的 WHERE）</td></tr>
 * </table>
 *
 * <p>这也正是决策 1 的落地："过滤是 SQL 的 WHERE 子句，不是独立步骤" ——
 * 所以本类<b>不产生任何"中间结果"</b>，它产出的就是一个 {@link Filter.Expression}，
 * 交给 {@code SearchRequest} 一起发给数据库。
 *
 * <h2>★ 生成的 SQL 形态（已实测，见 {@code RetrievalFilterSqlTest}）</h2>
 *
 * <pre>
 *   in : metadata::jsonb @@ '($."level" == "PUBLIC" || $."level" == "INTERNAL")'::jsonpath
 * </pre>
 *
 * <p>注意走的是 <b>jsonpath（{@code @@}）</b>而不是 {@code ->>}。这一点重要，
 * 因为向量表的 {@code metadata} 列实测是 <b>{@code json} 而不是 {@code jsonb}</b>，
 * 而 {@code @@} 需要 jsonb —— 框架在这里做了转换（{@code metadata::jsonb}），
 * 所以可用；但也意味着<b>建 GIN 索引时必须逐字符匹配这个表达式</b>，否则索引白建且不报错
 * （见 HANDOFF 3.6，本阶段决定不加索引）。
 *
 * <h2>★ 为什么用 {@code IN} 而不是数值比较</h2>
 *
 * <p>{@code doc.level.rank <= user.clearance.rank} 是这条规则的<b>语义</b>，
 * 但它无法直接写成 SQL —— metadata 里的 {@code level} 存的是<b>字符串</b>（"INTERNAL"），
 * 不是数字。所以在检索这一侧，规则的形态必须是"枚举出允许的取值"。
 *
 * <p>两个形态的<b>一致性由 {@link AccessLevel#visibleLevels} 保证</b>：
 * 它内部用 {@link AccessLevel#visibleTo} 过滤，而我们这里只做"翻译成 Filter 表达式"。
 * <b>本类不自己判断谁能看什么</b> —— 那会让规则出现第二个出处。
 *
 * <h2>★ 空清单：明确拒绝，而不是产出一个"无害的空条件"</h2>
 *
 * <p>如果 {@link AccessLevel#visibleLevels} 返回空清单（许可等级为 null），
 * 有的实现会"顺手"生成一个恒真条件或者干脆不过滤 —— 那等于<b>全量可见</b>，
 * 方向与安全要求恰好相反，而且同样不会报错。
 *
 * <p>所以这里选择<b>抛异常</b>。它在主链路上不可达
 * （{@code UserContext} 保证 role 非空，每个 Role 都带非空 clearance），
 * 属于"如果真发生了，必须让人看见"的防御，而不是常规分支。
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

        // 字段名引用 DocumentMetadata 的常量 —— 入库写、检索读只有一个出处。
        // 若这里写成裸字符串 "level"，与入库侧失步时 SQL 不报错、只是永远不命中。
        //
        // ★★★ 必须显式 toArray() 走【变长参数】重载。
        //      直接传 List 会【静默】渲染成数组比较 —— 不报错、只是永远不命中。
        //
        //   根因是"泛型不变 + 重载回退"，比"两个重载不同"更隐蔽（javap 实证）：
        //     public Op in(String, Object...);        // 逐值展开 → OR 链
        //     public Op in(String, List<Object>);     // 参数是 List<Object>，不是 List<?>
        //
        //   而 List<String> 【不能】赋给 List<Object>（泛型不变）→ List 重载【不适用】
        //   → 静默回退到 in(String, Object...)，把整个 List 当成【一个值】：
        //
        //     (.in("level", levelNames))        → ($."level" == ["PUBLIC","INTERNAL"])   ❌ 恒为 false
        //     (.in("level", levelNames.toArray())) → ($."level" == "PUBLIC" || $."level" == "INTERNAL")  ✅
        //
        //   数组比较在 jsonpath 里是"字符串 vs 数组字面量"，恒为假 ——
        //   检索一条都返回不了，日志里只有一条"没命中"。
        //   ★ 这个坑真的踩到了；四种写法的渲染对照见
        //   RetrievalFilterSqlTest#inOverloadRenderingsArePinned，
        //   定位过程见 docs/PROCEDURES.md 本阶段"踩坑记录 坑 7"。
        return new FilterExpressionBuilder()
                .in(DocumentMetadata.FIELD_LEVEL, levelNames.toArray())
                .build();
    }
}
