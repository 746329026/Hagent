package com.hagent.retrieval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.ai.vectorstore.filter.FilterExpressionConverter;
import org.springframework.ai.vectorstore.pgvector.PgVectorFilterExpressionConverter;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>权限过滤条件的 SQL 生成实验</b> —— 回答一个必须先确认、不能靠猜的问题：
 * "框架把我们写的 {@code Filter.Expression} 翻译成什么 SQL？"
 *
 * <h2>为什么必须做这个实验</h2>
 *
 * <p>阶段 7 要把"用户密级"变成检索时的 SQL 条件（**检索前过滤**，而不是拿到结果再筛）。
 * 而阶段 6 实测发现向量表的 {@code metadata} 列类型是 **{@code json} 而不是 {@code jsonb}** ——
 * 这直接影响可用的操作符：
 *
 * <table border="1">
 *   <caption>json 与 jsonb 的操作符差异</caption>
 *   <tr><th>操作符</th><th>json</th><th>jsonb</th><th>含义</th></tr>
 *   <tr><td>{@code ->}</td><td>✅</td><td>✅</td><td>取 json 值（返回 json）</td></tr>
 *   <tr><td>{@code ->>}</td><td>✅</td><td>✅</td><td>取值为 text</td></tr>
 *   <tr><td>{@code @>}</td><td>❌</td><td>✅</td><td>包含（jsonb 独有）</td></tr>
 *   <tr><td>{@code ?}</td><td>❌</td><td>✅</td><td>键是否存在（jsonb 独有）</td></tr>
 * </table>
 *
 * <p>所以：如果框架生成的条件用到了 {@code @>} 或 {@code ?}，那它在 {@code json} 列上
 * <b>会直接报错</b>；如果只用 {@code ->>}，就能正常工作。
 * <b>这不是推测题，是可验证的事实题。</b>
 *
 * <h2>本实验输出的意义</h2>
 *
 * <p>把生成的 SQL 片段打印出来并存为断言 —— 这样将来框架升级改变了生成方式，
 * 测试会立刻失败并指出差异，而不是等到运行时的检索莫名查不到数据。
 */
class RetrievalFilterSqlTest {

    private final FilterExpressionConverter converter = new PgVectorFilterExpressionConverter();

    private String toSql(Filter.Expression expression) {
        return converter.convertExpression(expression);
    }

    @Test
    @DisplayName("★ 等值条件（eq）生成什么 SQL —— 决定 dept 过滤是否可用")
    void equalityFilterSql() {
        Filter.Expression expression = new FilterExpressionBuilder()
                .eq("dept", "HR")
                .build();

        String sql = toSql(expression);
        System.out.println("[过滤 SQL 实测] eq('dept','HR') → " + sql);

        // 必须走 ->>（json 列也支持），不能出现 @> 或 ?（json 列不支持，会报错）
        assertThat(sql).doesNotContain("@>");
        assertThat(sql).doesNotContain("?");
    }

    @Test
    @DisplayName("★ IN 条件生成什么 SQL —— 这是权限过滤计划使用的形式")
    void inFilterSql() {
        Filter.Expression expression = new FilterExpressionBuilder()
                .in("level", "PUBLIC", "INTERNAL")
                .build();

        String sql = toSql(expression);
        System.out.println("[过滤 SQL 实测] in('level', PUBLIC, INTERNAL) → " + sql);

        assertThat(sql).doesNotContain("@>");
        assertThat(sql).doesNotContain("?");
    }

    @Test
    @DisplayName("★ AND 组合生成什么 SQL —— 权限 + 部门同时过滤时用")
    void andFilterSql() {
        // 注意：and(...) 收的是同一个 builder 产出的 Op，而不是各自独立 builder 的 Expression
        // （这是查过签名才写的：Op 是 record，内部持有 Operand，build() 才产出 Expression）
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        Filter.Expression expression = b.and(
                b.in("level", "PUBLIC"),
                b.eq("dept", "HR")).build();

        String sql = toSql(expression);
        System.out.println("[过滤 SQL 实测] and(in(level), eq(dept)) → " + sql);

        assertThat(sql).isNotBlank();
        assertThat(sql).doesNotContain("@>");
    }

    @Test
    @DisplayName("★★ 多值 IN 的渲染形式 —— 变长参数版渲染成 OR 链（权限过滤必须用这一个）")
    void multiValueInVarargsRendersAsOrChain() {
        Filter.Expression expression = new FilterExpressionBuilder()
                .in("level", "PUBLIC", "INTERNAL", "CONFIDENTIAL")
                .build();

        String sql = toSql(expression);
        System.out.println("[过滤 SQL 实测] in('level', varargs 三个值) → " + sql);

        assertThat(sql).isNotBlank();
        assertThat(sql).doesNotContain("@>");
        // ★ 权限过滤依赖的正是这个形态：逐值 OR。
        assertThat(sql).contains("||");
    }

    /**
     * ★★★ <b>本次开发中真实踩到的坑</b>：
     * {@code in(String, List)} 与 {@code in(String, Object...)} 生成的 SQL <b>完全不同</b>，
     * 而错的那个<b>不报错、只是永远不命中</b>。
     *
     * <pre>
     *   in("level", "PUBLIC", "INTERNAL")          → ($."level" == "PUBLIC" || $."level" == "INTERNAL")   ✅
     *   in("level", List.of("PUBLIC","INTERNAL"))  → ($."level" == ["PUBLIC","INTERNAL"])               ❌
     * </pre>
     *
     * <p>后者是拿<b>字符串</b>去和<b>数组字面量</b>比较，jsonpath 求值恒为 {@code false} ——
     * 查询会正常执行、正常返回，只是<b>一条都不命中</b>。
     * 表现是"检索功能好像没生效"，而日志里只有一条"没命中"。
     *
     * <p>本用例<b>刻意保留这个错误渲染的断言</b>：它的作用不是"验证正确行为"，
     * 而是把"这个重载不能这样用"变成可执行的文档 —— 将来有人想改回 List 版，
     * 这条测试会明确告诉他那是哪个坑。
     */
    /**
     * ★★★ <b>本阶段真实踩到的坑，根因是"泛型不变 + 重载回退"的静默降级</b>。
     *
     * <p>{@code FilterExpressionBuilder} 上有<b>两个</b> {@code in} 重载（javap 实证）：
     *
     * <pre>
     *   public Op in(String, Object...);           // 逐值展开 → OR 链
     *   public Op in(String, List&lt;Object&gt;);        // 注意：是 List&lt;Object&gt;，不是 List&lt;?&gt;
     * </pre>
     *
     * <p>关键在第二个的参数类型：<b>{@code List<Object>} 而不是 {@code List<?>}</b>。
     * 而泛型是不变的 —— <b>{@code List<String>} 不能赋给 {@code List<Object>}</b>。
     * 于是：
     *
     * <pre>
     *   List&lt;String&gt; names = List.of("PUBLIC","INTERNAL");
     *   b.in("level", names)
     *     → List 重载【不适用】（List&lt;String&gt; 不是 List&lt;Object&gt;）
     *     → 静默回退到 in(String, Object...)，把整个 List 当成【一个值】
     *     → ($."level" == ["PUBLIC","INTERNAL"])      ← 字符串比数组字面量，恒为 false
     * </pre>
     *
     * <p><b>没有编译错误、没有警告</b>。查询正常执行、正常返回，只是<b>一条都不命中</b> ——
     * 表现是"检索功能好像没生效"。**这个坑真的踩到了**，定位过程见
     * {@code docs/PROCEDURES.md} 本阶段"踩坑记录 坑 7"。
     *
     * <p>本用例把四种写法的渲染结果<b>全部钉住</b>：它不是"验证正确行为"，
     * 而是把"哪种写法会静默失效"变成可执行的文档。
     */
    @Test
    @DisplayName("★★★ in 的四种写法渲染对照 —— List<String> 会静默回退成数组比较（恒为假）")
    void inOverloadRenderingsArePinned() throws Exception {
        FilterExpressionBuilder b = new FilterExpressionBuilder();

        // ① 变长参数：逐值展开 → 正确
        String varargs = toSql(b.in("level", "PUBLIC", "INTERNAL").build());

        // ② List<String> 变量：匹配不上 List<Object> 重载 → 回退成 varargs 单元素 → 错误
        java.util.List<String> asStringList = java.util.List.of("PUBLIC", "INTERNAL");
        String stringList = toSql(b.in("level", asStringList).build());

        // ③ List<Object>：真正命中 List 重载 → 正确
        java.util.List<Object> asObjectList = java.util.List.of("PUBLIC", "INTERNAL");
        String objectList = toSql(b.in("level", asObjectList).build());

        // ④ 显式 toArray()：强制走变长参数 → 正确（本项目在 RetrievalFilterBuilder 里用的写法）
        String array = toSql(b.in("level", asStringList.toArray()).build());

        String report = """
                =================================================================
                [in 重载渲染对照]  两个值：PUBLIC, INTERNAL
                -----------------------------------------------------------------
                ① in(key, "PUBLIC", "INTERNAL")        → %s
                ② in(key, List<String> 变量)            → %s
                ③ in(key, List<Object> 变量)            → %s
                ④ in(key, stringList.toArray())        → %s
                =================================================================
                判读：② 是坑 —— 字符串与数组字面量比较，jsonpath 恒为 false。
                      权限过滤必须用 ① 或 ④（本项目用 ④，意图最明确）。
                """.formatted(varargs, stringList, objectList, array);

        java.nio.file.Files.createDirectories(java.nio.file.Path.of("target"));
        java.nio.file.Files.writeString(java.nio.file.Path.of("target", "in-overload-report.txt"),
                report, java.nio.charset.StandardCharsets.UTF_8);

        // ① 正确：逐值 OR
        assertThat(varargs).contains("||").doesNotContain("== [");

        // ② 陷阱：数组比较，恒为假 —— 把"错误行为"钉住，谁改回这个写法就会看到它
        assertThat(stringList)
                .as("★ List<String> 变量会静默回退成数组比较 —— 权限过滤绝不能这样写")
                .contains("== [");

        // ③ List<Object> 命中真正的 List 重载 → 正确
        assertThat(objectList).contains("||").doesNotContain("== [");

        // ④ 显式 toArray() → 正确（本项目采用的写法）
        assertThat(array).contains("||").doesNotContain("== [");

        // ② 与 ③ 只差一个泛型声明，结果却完全相反 —— 这正是这个坑的可怕之处
        assertThat(stringList).isNotEqualTo(objectList);
    }

    @Test
    @DisplayName("★ 排除条件（ne/nin）生成什么 SQL —— 备用方案，若 IN 不可用则改用它")
    void exclusionFilterSql() {
        Filter.Expression neExpression = new FilterExpressionBuilder()
                .ne("level", "CONFIDENTIAL")
                .build();
        System.out.println("[过滤 SQL 实测] ne('level','CONFIDENTIAL') → " + toSql(neExpression));

        Filter.Expression ninExpression = new FilterExpressionBuilder()
                .nin("level", "CONFIDENTIAL")
                .build();
        System.out.println("[过滤 SQL 实测] nin('level','CONFIDENTIAL') → " + toSql(ninExpression));

        assertThat(toSql(neExpression)).doesNotContain("@>");
        assertThat(toSql(ninExpression)).doesNotContain("@>");
    }

    @Test
    @DisplayName("★ 同时输出各条件的完整形态，便于人工核对是否符合 PostgreSQL 语法")
    void printAllFormsForManualReview() throws Exception {
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        String report = """
                =========================================================
                [权限过滤 SQL 形态汇总]
                  eq  : %s
                  in  : %s
                  ne  : %s
                  nin : %s
                  and : %s
                =========================================================
                """.formatted(
                toSql(b.eq("level", "INTERNAL").build()),
                toSql(b.in("level", "PUBLIC", "INTERNAL").build()),
                toSql(b.ne("dept", "HR").build()),
                toSql(b.nin("level", "CONFIDENTIAL").build()),
                toSql(b.and(b.in("level", "PUBLIC"), b.eq("dept", "HR")).build()));

        // ★ 写到文件而不是 System.out：surefire 会把测试的 stdout 重定向到报告里，
        //   实测那样读不到内容。写文件让"实验证据"可以被直接查看与留档。
        java.nio.file.Files.writeString(
                java.nio.file.Path.of("target", "filter-sql-report.txt"),
                report, java.nio.charset.StandardCharsets.UTF_8);

        assertThat(report).contains("level");
    }
}
