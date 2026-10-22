package com.hagent.memory.longterm;

/**
 * 长期记忆实现需要的配置项 —— <b>由领域声明、由配置类实现</b>（依赖倒置）。
 *
 * <p>理由与两条约束见 {@link com.hagent.memory.record.MessageStoreSettings} 的类注释。
 *
 * <p>这三个上限决定的不是代码结构，而是<b>成本</b>（索引每轮都注入 prompt）与
 * <b>安全性</b>（单条记忆多大），所以放在配置里；但<b>怎么用它们</b>（超限是拒绝写还是截断）
 * 是领域决策，留在 {@link FileLongTermMemoryStore}。
 */
public interface LongTermMemorySettings {

    /** 记忆根目录。实际路径是 {@code {dir}/{userId}/}。 */
    String getDir();

    /** 注入 prompt 的索引块最大字符数。 */
    int getIndexMaxChars();

    /** 单条记忆正文的最大字符数。 */
    int getContentMaxChars();

    /** 记忆标题的最大字符数（标题会进索引，所以必须短）。 */
    int getTitleMaxChars();
}
