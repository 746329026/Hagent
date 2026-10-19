package com.hagent.memory.longterm;

import com.hagent.config.LongTermMemoryProperties;
import com.hagent.document.AccessLevel;
import com.hagent.security.Role;
import com.hagent.security.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 长期记忆文件实现的测试 —— 盯住"写错了不会报错"的性质。
 *
 * <p>不测的：接口语义已经写在 {@link LongTermMemoryStore} 的注释里，
 * 这里只测<b>这个实现独有</b>且容易错的地方：upsert、密级过滤、索引上限、
 * 路径安全、以及"索引文件是物化产物"。
 */
class FileLongTermMemoryStoreTest {

    private Path tempDir;
    private FileLongTermMemoryStore store;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("hagent-ltm-test-");
        store = new FileLongTermMemoryStore(properties(1000));
    }

    @AfterEach
    void tearDown() throws IOException {
        if (tempDir != null && Files.exists(tempDir)) {
            try (Stream<Path> walk = Files.walk(tempDir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                        // 清理失败不影响结论
                    }
                });
            }
        }
    }

    private LongTermMemoryProperties properties(int indexMaxChars) {
        LongTermMemoryProperties props = new LongTermMemoryProperties();
        props.setDir(tempDir.toString());
        props.setIndexMaxChars(indexMaxChars);
        props.afterPropertiesSet();
        return props;
    }

    /** ★ 同一个 userId、不同角色 —— 这样才测得出密级过滤（目录是按 userId 分的）。 */
    private static UserContext as(String userId, Role role) {
        return new UserContext(userId, role);
    }

    // ========================================================================
    // 一、upsert：同名即覆盖
    // ========================================================================

    @Test
    @DisplayName("★ 保存后能读出来，密级取写入者角色的密级（复用 AccessLevel）")
    void saveThenList() {
        LongTermMemoryStore.SaveResult result =
                store.save("称呼偏好", "用户希望我称呼他张工", as("zhangsan", Role.EMPLOYEE));

        assertThat(result).isEqualTo(LongTermMemoryStore.SaveResult.CREATED);
        assertThat(store.list(as("zhangsan", Role.EMPLOYEE)))
                .singleElement()
                .satisfies(entry -> {
                    assertThat(entry.title()).isEqualTo("称呼偏好");
                    assertThat(entry.content()).contains("张工");
                    assertThat(entry.level())
                            .as("★ 密级直接复用 AccessLevel（EMPLOYEE 的 clearance 是 INTERNAL），"
                                    + "不引入新概念")
                            .isEqualTo(AccessLevel.INTERNAL);
                });
    }

    @Test
    @DisplayName("★★ 同名 → 覆盖，不是新增（upsert）。这样'更新'不需要 update 工具，也不用先删")
    void sameTitleOverwrites() {
        UserContext user = as("zhangsan", Role.EMPLOYEE);
        store.save("回答风格", "喜欢简洁的回答", user);

        LongTermMemoryStore.SaveResult second = store.save("回答风格", "喜欢详细的回答", user);

        assertThat(second).isEqualTo(LongTermMemoryStore.SaveResult.UPDATED);
        assertThat(store.list(user))
                .as("必须还是一条：若变成两条，模型会同时看到'简洁'和'详细'两个矛盾的偏好")
                .hasSize(1)
                .singleElement()
                .satisfies(entry -> assertThat(entry.content()).contains("详细"));
    }

    @Test
    @DisplayName("★ 删除：删掉后列表为空")
    void forgetRemoves() {
        UserContext user = as("zhangsan", Role.EMPLOYEE);
        store.save("所在城市", "用户在杭州", user);

        assertThat(store.forget("所在城市", user)).isTrue();
        assertThat(store.list(user)).isEmpty();
        assertThat(store.forget("不存在的标题", user)).isFalse();
    }

    // ========================================================================
    // 二、密级：与检索同一条规则（AccessLevel.visibleTo）
    // ========================================================================

    @Test
    @DisplayName("★★ 高密级的记忆对低权限身份不可见，也不能被它覆盖或删除")
    void higherLevelEntryIsInvisibleAndImmutable() {
        UserContext boss = as("zhangsan", Role.ADMIN);      // 同一 userId，更高角色
        UserContext staff = as("zhangsan", Role.EMPLOYEE);

        store.save("薪资结构", "这个人的薪资档位是 P7", boss);

        assertThat(store.list(staff))
                .as("ADMIN 写的记忆密级是 CONFIDENTIAL，EMPLOYEE 看不到 —— "
                        + "记忆不能比检索更宽松（检索里这条内容也是查不到的）")
                .isEmpty();
        assertThat(store.find("薪资结构", staff)).isEmpty();

        assertThat(store.save("薪资结构", "我改一下", staff))
                .as("不可见就不可改：否则低权限身份能把别人的记忆冲掉，而且毫无察觉")
                .isEqualTo(LongTermMemoryStore.SaveResult.FORBIDDEN);
        assertThat(store.forget("薪资结构", staff))
                .as("删除同理：不可见就不可删")
                .isFalse();

        assertThat(store.list(boss))
                .as("原条目必须完好")
                .singleElement()
                .satisfies(entry -> assertThat(entry.content()).contains("P7"));
    }

    // ========================================================================
    // 三、索引：上限、渲染、物化
    // ========================================================================

    @Test
    @DisplayName("★ 注入的索引里能看到标题与摘要，且带上'冲突时听用户的'使用规则")
    void indexCarriesTitlesAndRules() {
        UserContext user = as("zhangsan", Role.EMPLOYEE);
        store.save("称呼偏好", "用户希望我称呼他张工", user);

        String digest = store.renderIndexFor(user);

        assertThat(digest)
                .contains("长期记忆")
                .contains("称呼偏好")
                .contains("张工")
                .as("没有'冲突时以用户当前说法为准'这条，模型会拿旧偏好压过用户刚说的话")
                .contains("以当前说法为准");
    }

    @Test
    @DisplayName("★ 没有任何记忆时不注入（返回空串，而不是一个空标题）")
    void emptyIndexIsBlank() {
        assertThat(store.renderIndexFor(as("nobody", Role.EMPLOYEE))).isBlank();
    }

    @Test
    @DisplayName("★★ 索引超出上限 → 拒绝写入，而不是静默淘汰旧记忆")
    void indexFullIsRefused() {
        UserContext user = as("zhangsan", Role.EMPLOYEE);
        String longContent = "很".repeat(80);

        // ★ 不写死数字：先量出"一条记忆的索引有多长"，再把上限卡在它 +1。
        //   写死数字的测试会在文案改动时莫名其妙地变红，而红的原因看起来与"上限"无关。
        FileLongTermMemoryStore roomy = new FileLongTermMemoryStore(properties(100_000));
        assertThat(roomy.save("第一条", longContent, user))
                .isEqualTo(LongTermMemoryStore.SaveResult.CREATED);
        int oneEntryIndexLength = roomy.renderIndexFor(user).length();

        FileLongTermMemoryStore tight =
                new FileLongTermMemoryStore(properties(oneEntryIndexLength + 1));
        LongTermMemoryStore.SaveResult second = tight.save("第二条", longContent, user);

        assertThat(second)
                .as("★ 静默淘汰最旧的条目 = 悄悄丢用户数据。宁可让模型自己去整理（forget 再存）")
                .isEqualTo(LongTermMemoryStore.SaveResult.INDEX_FULL);
        assertThat(tight.list(user))
                .as("被拒绝的那条不能落盘 —— 否则会出现'写进去了但索引里没有'的孤儿条目")
                .extracting(MemoryEntry::title)
                .containsExactly("第一条");
    }

    @Test
    @DisplayName("★ MEMORY.md 是物化产物：每次写入后重新生成，含标题与密级")
    void indexFileIsMaterialized() throws IOException {
        UserContext user = as("zhangsan", Role.EMPLOYEE);
        store.save("常用语言", "用户主要用 Java", user);

        Path indexFile = tempDir.resolve("zhangsan").resolve("MEMORY.md");
        assertThat(indexFile).exists();
        assertThat(Files.readString(indexFile))
                .contains("常用语言")
                .contains("INTERNAL");
    }

    // ========================================================================
    // 四、安全：标题来自模型，不能变成路径
    // ========================================================================

    @Test
    @DisplayName("★★ 标题里带目录穿越 → 落成 mem-*.md，根目录外一个字节都没写")
    void traversalTitleCannotEscapeRoot() throws IOException {
        UserContext user = as("zhangsan", Role.EMPLOYEE);

        store.save("../../application.yml", "恶意内容", user);

        try (Stream<Path> walk = Files.walk(tempDir)) {
            assertThat(walk.filter(path -> !path.equals(tempDir))
                    .map(Path::getFileName).map(Path::toString).toList())
                    .as("文件名只能是 mem-{8位十六进制}.md —— 不是被校验挡住，而是根本构造不出来")
                    .allMatch(name -> name.equals("zhangsan") || name.equals("MEMORY.md")
                            || name.matches("mem-[0-9a-f]{8}\\.md"));
        }
        assertThat(Files.exists(Path.of("application.yml"))).isFalse();
    }

    @Test
    @DisplayName("★ 奇怪的 userId 不会跑出根目录（退化成哈希目录）")
    void weirdUserIdStaysInsideRoot() throws IOException {
        store.save("随便", "内容", as("../../etc", Role.ADMIN));

        try (Stream<Path> walk = Files.walk(tempDir)) {
            assertThat(walk.allMatch(path -> path.normalize().startsWith(tempDir)))
                    .as("所有落盘路径都必须在根目录之内")
                    .isTrue();
        }
        assertThat(store.list(as("../../etc", Role.ADMIN)))
                .as("同一个 userId 永远落到同一个目录（哈希是确定的）")
                .hasSize(1);
    }

    // ========================================================================
    // 五、持久化
    // ========================================================================

    @Test
    @DisplayName("重新打开（新实例）能读到之前写下的记忆 —— 它不是内存里的东西")
    void reopenedInstanceSeesEntries() {
        store.save("所在城市", "用户在杭州", as("zhangsan", Role.EMPLOYEE));

        FileLongTermMemoryStore reopened = new FileLongTermMemoryStore(properties(1000));

        assertThat(reopened.list(as("zhangsan", Role.EMPLOYEE)))
                .singleElement()
                .satisfies(entry -> assertThat(entry.content()).contains("杭州"));
    }

    @Test
    @DisplayName("不同用户的记忆互相看不到")
    void memoriesAreIsolatedPerUser() {
        store.save("张三的偏好", "简洁", as("zhangsan", Role.EMPLOYEE));
        store.save("李四的偏好", "详细", as("lisi", Role.EMPLOYEE));

        assertThat(store.list(as("zhangsan", Role.EMPLOYEE)))
                .extracting(MemoryEntry::title).containsExactly("张三的偏好");
        assertThat(store.list(as("lisi", Role.EMPLOYEE)))
                .extracting(MemoryEntry::title).containsExactly("李四的偏好");
    }

    @Test
    @DisplayName("空标题 / 纯空白 → 不落盘（否则所有空标题会抢同一个文件）")
    void blankTitleIsRejected() {
        UserContext user = as("zhangsan", Role.EMPLOYEE);
        assertThat(store.save("   ", "内容", user))
                .isEqualTo(LongTermMemoryStore.SaveResult.FORBIDDEN);
        assertThat(store.list(user)).isEmpty();
    }

    @Test
    @DisplayName("正文超长被截断而不是拒绝（宁可记住大部分，也不要一个字都不记）")
    void longContentIsTruncated() {
        UserContext user = as("zhangsan", Role.EMPLOYEE);
        FileLongTermMemoryStore small = new FileLongTermMemoryStore(properties(1000));
        small.save("长内容", "很".repeat(3000), user);

        List<MemoryEntry> entries = small.list(user);
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).content().length())
                .as("上限是 2000 字（配置项 content-max-chars）")
                .isLessThanOrEqualTo(2001);
    }
}
