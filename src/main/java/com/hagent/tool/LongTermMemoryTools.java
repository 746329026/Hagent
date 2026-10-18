package com.hagent.tool;

import com.hagent.memory.longterm.LongTermMemoryStore;
import com.hagent.memory.longterm.MemoryEntry;
import com.hagent.security.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.Optional;

/**
 * 长期记忆的<b>三个工具</b> —— 模型自己决定记什么、看什么、忘什么。
 *
 * <h2>★ 为什么只有三个，而且没有 {@code updateMemory}</h2>
 *
 * <p>{@code saveMemory} 是 <b>upsert（同名即覆盖）</b>，所以"更新"就是它 ——
 * 再给一个 {@code updateMemory} 只会让模型在两个意思一样的工具之间犹豫，
 * 而 {@code update} 还多一个"必须已存在"的前提要解释。
 *
 * <p>{@code forget + save} 也能达到"更新"，但那是<b>两步非原子操作</b>：
 * 第一步成功、第二步失败就等于<b>把记忆弄丢了</b>。upsert 只有一步、且幂等。
 *
 * <h2>★ 工具描述为什么写"什么时候不要用"</h2>
 *
 * <p>描述是<b>模型唯一能看到的用法说明</b>（工具用法绝不写进 system prompt，见
 * {@code ToolRegistration} 的类注释）。所以除了"做什么"，还要写"别什么时候用" ——
 * 否则模型会把每句闲聊都记成"用户偏好"。
 *
 * <h2>★ 返回值是给模型看的，不是日志</h2>
 *
 * <p>每个分支都写清<b>发生了什么、为什么、下一步能做什么</b>。
 * 例如"索引满了"必须告诉它"先删再存"，否则它只会反复重试同一个保存。
 */
@Slf4j
@RequiredArgsConstructor
public class LongTermMemoryTools {

    private static final String LOG_TAG = "工具-记忆";

    private final LongTermMemoryStore memoryStore;

    @Tool(name = "saveMemory",
            description = """
                    把值得跨会话记住的信息写进长期记忆（用户偏好、身份事实、长期有效的约定）。
                    同名会覆盖旧内容 —— 用户改变主意时直接再写一次同一个标题即可，不需要先删除。
                    不要用它记录：一次性的任务细节、闲聊内容、能从知识库检索到的公司制度。""")
    public String saveMemory(
            @ToolParam(description = "记忆标题，简短且稳定，例如：称呼偏好、常用编程语言、所在城市")
            String title,
            @ToolParam(description = "要记住的内容，一两句话说清，例如：用户希望我称呼他『张工』")
            String content,
            ToolContext toolContext) {

        UserContext user = ToolContextSupport.resolveUser(toolContext, LOG_TAG);
        LongTermMemoryStore.SaveResult result = memoryStore.save(title, content, user);

        return switch (result) {
            case CREATED -> "已记住「" + title + "」。以后相关对话会自动带上这条记忆。";
            case UPDATED -> "已更新记忆「" + title + "」（覆盖了原来的内容）。";
            case INDEX_FULL -> "没有写入：记忆索引已达长度上限。"
                    + "请先用 forgetMemory 删掉不再需要的记忆，或覆盖一条已有记忆腾出位置，然后再保存。";
            case FORBIDDEN -> "没有写入：已存在同名记忆，但它属于更高的密级，当前身份无权修改。"
                    + "请换一个标题。";
        };
    }

    @Tool(name = "readMemory",
            description = """
                    读取某条长期记忆的完整内容。索引里只显示标题与摘要，需要细节时用本工具。
                    标题必须与索引里显示的一致。""")
    public String readMemory(
            @ToolParam(description = "记忆标题，与索引里显示的一致") String title,
            ToolContext toolContext) {

        UserContext user = ToolContextSupport.resolveUser(toolContext, LOG_TAG);
        Optional<MemoryEntry> found = memoryStore.find(title, user);
        if (found.isEmpty()) {
            // 与 forget 一致：不可见与不存在给同一个答复（防标题枚举）
            return "没有找到这条记忆（可能不存在，或当前身份无权查看）。";
        }
        MemoryEntry entry = found.get();
        return "【记忆：" + entry.title() + "／密级：" + entry.level()
                + "／更新于：" + entry.updatedAt() + "】\n" + entry.content();
    }

    @Tool(name = "forgetMemory",
            description = """
                    删除一条长期记忆。当用户明确说"别记这个了"，或某条记忆已经不再成立时使用。
                    标题必须与索引里显示的一致。""")
    public String forgetMemory(
            @ToolParam(description = "要忘记的记忆标题，与索引里显示的一致") String title,
            ToolContext toolContext) {

        UserContext user = ToolContextSupport.resolveUser(toolContext, LOG_TAG);
        boolean removed = memoryStore.forget(title, user);
        return removed
                ? "已忘记「" + title + "」。"
                : "没有找到这条记忆（可能不存在，或当前身份无权删除）。";
    }
}
