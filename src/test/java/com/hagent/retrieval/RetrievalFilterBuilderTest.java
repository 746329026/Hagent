package com.hagent.retrieval;

import com.hagent.document.AccessLevel;
import com.hagent.document.DocumentMetadata;
import com.hagent.security.Role;
import com.hagent.security.UserContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionConverter;
import org.springframework.ai.vectorstore.pgvector.PgVectorFilterExpressionConverter;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 权限过滤条件的正确性 —— 本项目里"错了最严重"的一处逻辑。
 *
 * <p>过滤条件有两个方向，而且都"能跑通"：方向错了（匿名能搜到 CONFIDENTIAL）不抛异常、没有 WARN，
 * 越权内容照样进 prompt 并被模型复述，唯一发现方式是用低权限身份去检索一条已知机密内容。
 * 所以本类同时验证：① 允许的密级清单；② 生成的 SQL 形态走 jsonpath 而不是 {@code @>}/{@code ?}
 * （向量表 metadata 列是 {@code json}，jsonb 专属操作符会直接报错）。
 */
class RetrievalFilterBuilderTest {

    private final RetrievalFilterBuilder builder = new RetrievalFilterBuilder();

    /** 用真实框架转换器看生成的 SQL —— 与 {@code RetrievalFilterSqlTest} 用的是同一个。 */
    private final FilterExpressionConverter converter = new PgVectorFilterExpressionConverter();

    private String sqlFor(AccessLevel clearance) {
        return converter.convertExpression(builder.build(clearance));
    }

    // 一、允许的密级清单（规则本身）

    @Test
    @DisplayName("★ 匿名（PUBLIC 许可）→ 只能看到 PUBLIC，看不到 INTERNAL/CONFIDENTIAL")
    void anonymousSeesOnlyPublic() {
        String sql = sqlFor(AccessLevel.PUBLIC);

        assertThat(sql).contains("PUBLIC");
        assertThat(sql)
                .as("★ 这是越权防线：匿名绝不能看到 INTERNAL 或 CONFIDENTIAL")
                .doesNotContain("INTERNAL")
                .doesNotContain("CONFIDENTIAL");
    }

    @Test
    @DisplayName("★ 员工（INTERNAL 许可）→ 看到 PUBLIC + INTERNAL，看不到 CONFIDENTIAL")
    void employeeSeesPublicAndInternal() {
        String sql = sqlFor(AccessLevel.INTERNAL);

        assertThat(sql).contains("PUBLIC").contains("INTERNAL");
        assertThat(sql).as("薪酬、人事这类机密内容对普通员工不可见").doesNotContain("CONFIDENTIAL");
    }

    @Test
    @DisplayName("★ 管理员（CONFIDENTIAL 许可）→ 三个密级全部可见")
    void adminSeesAllLevels() {
        String sql = sqlFor(AccessLevel.CONFIDENTIAL);

        assertThat(sql).contains("PUBLIC").contains("INTERNAL").contains("CONFIDENTIAL");
    }

    // 二、角色映射到许可等级（"双字典"陷阱的防线）

    @Test
    @DisplayName("★ 三个角色各自的可见范围（角色→许可的映射在枚举里，不存在第二本字典）")
    void roleMapsToClearanceWithoutSecondDictionary() {
        assertThat(UserContext.ANONYMOUS.clearance()).isEqualTo(AccessLevel.PUBLIC);
        assertThat(new UserContext("zhangsan", Role.EMPLOYEE).clearance()).isEqualTo(AccessLevel.INTERNAL);
        assertThat(new UserContext("admin", Role.ADMIN).clearance()).isEqualTo(AccessLevel.CONFIDENTIAL);

        // 规则本身：doc.level.rank <= user.clearance.rank
        // 用它来交叉验证上面那份"清单"——两条路径必须给出一致的答案。
        assertThat(AccessLevel.visibleLevels(AccessLevel.PUBLIC))
                .containsExactly(AccessLevel.PUBLIC);
        assertThat(AccessLevel.visibleLevels(AccessLevel.INTERNAL))
                .containsExactly(AccessLevel.PUBLIC, AccessLevel.INTERNAL);
        assertThat(AccessLevel.visibleLevels(AccessLevel.CONFIDENTIAL))
                .containsExactly(AccessLevel.PUBLIC, AccessLevel.INTERNAL, AccessLevel.CONFIDENTIAL);
    }

    // 三、生成的 SQL 形态（json 列的兼容性）

    @Test
    @DisplayName("★ 生成的 SQL 不含 jsonb 专属操作符（@> 与 ? 在 json 列上会直接报错）")
    void generatedSqlIsCompatibleWithJsonColumn() {
        for (AccessLevel clearance : AccessLevel.values()) {
            String sql = sqlFor(clearance);
            assertThat(sql)
                    .as("clearance=%s 时生成的 SQL 用到了 jsonb 专属操作符；"
                            + "向量表的 metadata 列实测是 json 而不是 jsonb", clearance)
                    .doesNotContain("@>")
                    .doesNotContain("?");
        }
    }

    @Test
    @DisplayName("★★ in 必须渲染成 OR 链，绝不能渲染成数组比较（后者恒为假、检索永远返回空）")
    void inRendersAsOrChainNotArrayComparison() {
        // in(String, List) 与 in(String, Object...) 渲染不同，而错的那个【不报错】—— 查询正常执行，一条都不命中：
        //   正确： ($."level" == "PUBLIC" || $."level" == "INTERNAL")
        //   错误： ($."level" == ["PUBLIC","INTERNAL"])   ← 字符串比数组字面量，恒 false
        String sql = sqlFor(AccessLevel.INTERNAL);

        assertThat(sql)
                .as("多值过滤必须渲染成 OR 链")
                .contains("||");
        assertThat(sql)
                .as("★ 出现 `== [` 意味着渲染成了数组比较 —— 那样条件恒为假，"
                        + "检索会静默返回空列表（正是本项目在防的那类失败）")
                .doesNotContain("== [");
    }

    @Test
    @DisplayName("★ 字段名取自 DocumentMetadata 常量 —— 与入库侧同源，不会写出 source/sourcePath 这类失步")
    void fieldNameComesFromSharedConstant() {
        String sql = sqlFor(AccessLevel.INTERNAL);
        assertThat(sql).contains(DocumentMetadata.FIELD_LEVEL);
    }

    @Test
    @DisplayName("★ 把三种视角的完整 SQL 形态写文件留档（供人工核对 PostgreSQL 语法）")
    void writeSqlReportForManualReview() throws Exception {
        StringBuilder report = new StringBuilder();
        report.append("=".repeat(78)).append('\n');
        report.append("[权限过滤 SQL 形态汇总] —— 由 RetrievalFilterBuilder 真实生成\n");
        report.append("=".repeat(78)).append('\n');
        for (AccessLevel clearance : AccessLevel.values()) {
            report.append(String.format("%-14s → %s%n", clearance, sqlFor(clearance)));
        }
        report.append("""

                判读要点：
                  1) 全部走 jsonpath（@@ ... ::jsonpath），没有 @> / ?  —— metadata 列是 json
                  2) 匿名那条只含 PUBLIC —— 这就是越权防线
                  3) 注意 metadata::jsonb @@ '$."dept" != "HR"' 这类"排除条件"对字段缺失的行
                     返回 unknown（不匹配），用 dept 做排除会漏记录（HANDOFF 3.6）。
                     本项目 dept 只存不用，因此当前不受影响。
                """);

        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target", "retrieval-filter-report.txt"),
                report.toString(), StandardCharsets.UTF_8);

        assertThat(report.toString()).contains(DocumentMetadata.FIELD_LEVEL);
    }

    // 四、空清单：明确拒绝，不产出"无害的空条件"

    @Test
    @DisplayName("★ 许可等级为 null → 抛异常，而不是生成「不过滤」（那等于全量可见）")
    void nullClearanceIsRejectedNotSilentlyWidened() {
        assertThatThrownBy(() -> builder.build(null))
                .as("空清单必须显式拒绝。若这里改成『返回空条件』或『返回 null』，"
                        + "过滤就会退化成不过滤 —— 方向恰好与安全要求相反，且同样不报错")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("全量可见");
    }

    @Test
    @DisplayName("★ 失败方向必须是「看不到」而不是「看到全部」")
    void emptyVisibleLevelsFailsClosedNotOpen() {
        // 三层都指向同一个方向（closed）：
        //   ① 清单为空；② 连 PUBLIC 都在 null 许可下不可见；③ 构造器拒绝而不是放行。
        assertThat(AccessLevel.visibleLevels(null)).isEmpty();
        assertThat(AccessLevel.PUBLIC.visibleTo(null))
                .as("null 许可下连 PUBLIC 都不可见 —— 不猜、不放行")
                .isFalse();
        assertThatThrownBy(() -> builder.build(null))
                .isInstanceOf(IllegalStateException.class);
    }
}
