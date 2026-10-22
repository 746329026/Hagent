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
 * 权限过滤条件的 SQL 生成实验：确认框架把 {@link Filter.Expression} 翻译成什么 SQL。
 *
 * <p>向量表的 {@code metadata} 列是 {@code json} 而不是 {@code jsonb}，所以 {@code @>}、{@code ?}
 * 这类 jsonb 独有操作符会直接报错，可行的只有 {@code ->>}。本类把生成的 SQL 片段钉成断言：
 * 框架升级改变生成方式时立刻失败，而不是等运行时检索莫名查不到数据。
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
        // and(...) 收的是同一个 builder 产出的 Op（record，build() 才产出 Expression），不是独立 builder 的 Expression
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
     * {@code in(String, Object...)} 与 {@code in(String, List<Object>)} 生成的 SQL 完全不同，
     * 而错的那个不报错、只是永远不命中。
     *
     * <p>陷阱是"泛型不变 + 重载回退"：List 重载的参数是 {@code List<Object>} 而不是 {@code List<?>}，
     * 于是 {@code in("level", List<String>)} 匹配不上它，静默回退到变长参数版、把整个 List 当成一个值
     * → 字符串与数组字面量比较，jsonpath 恒为 false。无编译错误、无警告，查询正常返回、一条都不命中。
     *
     * <p>本用例把四种写法的渲染全部钉住（含错误渲染）：它不是验证正确行为，
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

        // ④ 显式 toArray()：强制走变长参数 → 正确（RetrievalFilterBuilder 用的就是这一种）
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

        // ② 与 ③ 只差一个泛型声明，结果却完全相反
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

        // 写文件而不是 System.out：surefire 重定向 stdout，实测那样读不到内容
        java.nio.file.Files.writeString(
                java.nio.file.Path.of("target", "filter-sql-report.txt"),
                report, java.nio.charset.StandardCharsets.UTF_8);

        assertThat(report).contains("level");
    }
}
