package com.hagent.memory.record;

import com.hagent.common.AtomicWrites;
import com.hagent.common.Fingerprints;
import com.hagent.memory.LayerStatus;
import com.hagent.memory.MessageCodec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 记录层的文件实现 —— 零基础设施、人可读、可审计。
 *
 * <p>它<b>不是</b>缓存，是记录层（对话记录，权威完整）的实现。保留它作为"零基础设施的兜底"：
 * 不起 MySQL 也能跑起来，且文件可以直接打开看。
 *
 * <p><b>存储形态</b>：每个会话一个 JSON 文件，位于 {@code {fileDir}/{conversationId}.json}。
 * 一会话一文件（而不是单文件存所有会话）的三个理由：单文件会让所有会话共享一把写锁（全部串行）；
 * 文件损坏只影响一个会话；删除会话 = 删一个文件，天然满足"合规删除"。
 *
 * <h2>两个关键实现细节</h2>
 *
 * <p><b>1. 原子写入：临时文件 → ATOMIC_MOVE。</b>直接往目标文件写，写到一半被杀/断电就留下
 * <b>半截 JSON</b>，该会话历史永久损坏。文件系统保证"要么旧内容、要么新内容"，不存在中间态。
 *
 * <p><b>2. 锁的粒度按会话，不全局。</b>本实现的"读-改-写"不是原子的：两个请求同时读到同一份历史、
 * 各自追加、后写的覆盖先写的 → <b>丢消息</b>。所以需要锁，但必须按会话 ——
 * 给整个类加 {@code synchronized} 会让<b>所有会话互相阻塞</b>。
 *
 * <h2>与 MySQL 实现的差别（为什么它只能当兜底）</h2>
 *
 * <p>读路径必须<b>把整个文件读进内存</b>再取尾部 N 条（JSON 数组没法只读一段），
 * 会话很长时是线性成本；MySQL 实现走索引反向扫描、只读 N 行。所以生产上的记录层是 MySQL。
 */
@Slf4j
public class FileMessageStore implements MessageStore {

    private static final java.util.regex.Pattern SAFE_SEGMENT =
            java.util.regex.Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    private final Path rootDir;
    private final ObjectMapper objectMapper;

    /** 会话级锁表：只串行<b>同一会话</b>的写入，不同会话完全并发。 */
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    public FileMessageStore(MessageStoreSettings settings, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.rootDir = Path.of(settings.getFileDir()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(rootDir);
            log.info("[记忆-记录-file] 存储目录已就绪：{}", rootDir);
        } catch (IOException e) {
            throw new UncheckedIOException("无法创建记忆存储目录：" + rootDir, e);
        }
    }

    @Override
    public void append(String conversationId, List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        Object lock = locks.computeIfAbsent(conversationId, k -> new Object());
        synchronized (lock) {
            // 读-改-写三步必须在同一把锁内，否则并发追加会互相覆盖
            List<MessageCodec.StoredMessage> all = new ArrayList<>(readStored(conversationId));
            all.addAll(MessageCodec.toStoredList(messages));
            writeAtomically(fileOf(conversationId), all);
        }
    }

    @Override
    public List<Message> readLatest(String conversationId, int maxMessages) {
        if (maxMessages <= 0) {
            return List.of();
        }
        List<MessageCodec.StoredMessage> stored;
        Object lock = locks.computeIfAbsent(conversationId, k -> new Object());
        synchronized (lock) {
            // 读也放锁内：ATOMIC_MOVE 已保证不会读到半截文件，
            // 但锁能让"读到的一定是某次完整写入"这件事在代码上显式可见
            stored = readStored(conversationId);
        }
        if (stored.isEmpty()) {
            return List.of();
        }
        // 本层不做预算裁剪（那是门面的事），只按条数取尾部 N 条
        List<MessageCodec.StoredMessage> tail = stored.size() <= maxMessages
                ? stored
                : stored.subList(stored.size() - maxMessages, stored.size());
        return MessageCodec.fromStoredList(tail);
    }

    @Override
    public List<Message> readAll(String conversationId) {
        List<MessageCodec.StoredMessage> stored;
        Object lock = locks.computeIfAbsent(conversationId, k -> new Object());
        synchronized (lock) {
            stored = readStored(conversationId);
        }
        return MessageCodec.fromStoredList(stored);
    }

    @Override
    public void clear(String conversationId) {
        Object lock = locks.computeIfAbsent(conversationId, k -> new Object());
        synchronized (lock) {
            try {
                Files.deleteIfExists(fileOf(conversationId));
                log.debug("[记忆-记录-file] 已清空会话 {}", conversationId);
            } catch (IOException e) {
                throw new UncheckedIOException("清空会话失败：" + conversationId, e);
            }
        }
    }

    @Override
    public void delete(String conversationId) {
        clear(conversationId);
        locks.remove(conversationId);
    }

    @Override
    public LayerStatus probe() {
        try {
            if (!Files.isDirectory(rootDir)) {
                return LayerStatus.down("record", "file", "存储目录不存在：" + rootDir);
            }
            if (!Files.isWritable(rootDir)) {
                return LayerStatus.down("record", "file", "存储目录不可写：" + rootDir);
            }
            return LayerStatus.up("record", "file", "存储目录可写：" + rootDir);
        } catch (Exception e) {
            // 探测绝不抛异常：失败本身就是一种结果
            return LayerStatus.down("record", "file", "探测失败：" + e.getMessage());
        }
    }

    // ==================== 内部方法 ====================

    /** 会话 ID → 文件路径（安全形态；理由见 safeSegment）。 */
    private Path fileOf(String conversationId) {
        return rootDir.resolve(safeSegment(conversationId) + ".json");
    }

    /** 会话 ID 的安全文件名段：不可信 ID 退化成 c-{指纹}，构造不出越界路径。 */
    private static String safeSegment(String conversationId) {
        String raw = conversationId == null ? "" : conversationId.trim();
        boolean usable = SAFE_SEGMENT.matcher(raw).matches() && !raw.startsWith("_");
        return usable ? raw : "c-" + Fingerprints.identifier(raw);
    }

    /** 读出全部消息。文件不存在或损坏时返回空列表（不抛异常，避免会话彻底不可用）。 */
    private List<MessageCodec.StoredMessage> readStored(String conversationId) {
        Path file = fileOf(conversationId);
        if (Files.notExists(file)) {
            return new ArrayList<>();
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            if (json.isBlank()) {
                return new ArrayList<>();
            }
            MessageCodec.StoredMessage[] arr =
                    objectMapper.readValue(json, MessageCodec.StoredMessage[].class);
            return new ArrayList<>(List.of(arr));
        } catch (Exception e) {
            log.warn("[记忆-记录-file] 读取会话文件失败，按空历史处理：{} 原因={}", file, e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * 原子写入：先写临时文件，再移动覆盖目标。
     *
     * <p>关键点是 {@link StandardCopyOption#ATOMIC_MOVE} —— 由文件系统保证替换是原子的。
     */
    private void writeAtomically(Path target, List<MessageCodec.StoredMessage> messages) {
        String json;
        try { json = objectMapper.writeValueAsString(messages); }
        catch (Exception e) { throw new IllegalStateException("序列化会话消息失败：" + target, e); }
        if (!AtomicWrites.writeString(target, json)) {
            log.warn("[记忆-记录-file] ATOMIC_MOVE 不被支持，已降级为普通替换（原子性无保障）：{}", target);
        }
    }
}
