package com.hagent.common;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * <b>原子写文本文件的唯一实现</b>。
 *
 * <p>直接往目标文件写，写到一半进程被杀 / 断电就留下<b>半截文件</b>：半截 JSON ⇒ 该会话历史
 * 永久读不出来；半截目录 JSON ⇒ 侧栏历史会话全部消失；半截 frontmatter ⇒ 该条长期记忆永久读不出来。
 * 共同点是<b>不报错</b>、只是"以后读不到"，所以必须由<b>结构</b>保证，
 * 而不是靠"记得先写临时文件"。
 *
 * <p>只保留一份实现：三处近乎逐字相同的拷贝会漂移，而漂移不是编译错误 ——
 * 而是"某一层偷偷少了降级、或少了临时文件清理"，只在故障时才表现出来。
 *
 * <p>返回 {@code boolean} 而不是自己降级了事：{@code true} = 真的原子替换了；
 * {@code false} = 文件系统不支持 {@code ATOMIC_MOVE}、已降级为普通替换（<b>原子性无保障</b>）。
 * 降级可接受，但必须让调用方有机会记日志 —— 日志要带业务上下文（"哪一层的哪个文件"），
 * 所以由调用方打。
 *
 * <p>显式 {@code UTF_8}：Windows 上 {@code Files.writeString} 的默认字符集是 <b>GBK</b>，
 * 不显式指定则中文在本机能写能读、换台机器就乱码 —— 而那时数据已经写坏了。
 */
public final class AtomicWrites {

    private AtomicWrites() {
    }

    /**
     * 原子写入：临时文件 → {@code ATOMIC_MOVE}（不支持时降级为普通替换）。
     *
     * @param target  目标文件（父目录必须已存在）
     * @param content 文本内容（以 UTF-8 写入）
     * @return {@code true} = 原子替换成功；{@code false} = 已降级，原子性无保障
     * @throws UncheckedIOException 写入失败（与"降级"不同：这是真失败）
     */
    public static boolean writeString(Path target, String content) {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        boolean atomic = false;
        try {
            Files.writeString(tmp, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            try {
                Files.move(tmp, target,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
                atomic = true;
            } catch (IOException atomicFailed) {
                // 某些文件系统（或容器挂载方式）不支持 ATOMIC_MOVE
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("写入文件失败：" + target, e);
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // 临时文件清理失败不影响主流程：目标文件已经是完整的
            }
        }
        return atomic;
    }
}