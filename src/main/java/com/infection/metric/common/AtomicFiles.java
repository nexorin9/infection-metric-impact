package com.infection.metric.common;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * 原子写协议：先写 .tmp → force(true) 落盘 → ATOMIC_MOVE 覆盖目标。
 * 写入中途失败绝不留下半截 JSON；注入点 failAfterTemp 供单测模拟中途失败。
 */
public final class AtomicFiles {

    /** 测试注入：写完 .tmp 之后、rename 之前触发（抛异常即模拟写入中途失败）。 */
    public static volatile Runnable failAfterTemp = null;

    private AtomicFiles() {}

    public static void writeString(Path target, String content) {
        writeBytes(target, content.getBytes(StandardCharsets.UTF_8));
    }

    public static void writeBytes(Path target, byte[] content) {
        try {
            Path parent = target.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            try (FileChannel ch = FileChannel.open(tmp,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE)) {
                ch.write(java.nio.ByteBuffer.wrap(content));
                ch.force(true);
            }
            Runnable hook = failAfterTemp;
            if (hook != null) hook.run();
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new AppException("E_STORE_002", "原子写失败：" + target + "：" + e.getMessage(), e);
        }
    }

    public static String readString(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AppException("E_STORE_001", "读取文件失败：" + path + "：" + e.getMessage(), e);
        }
    }

    public static boolean exists(Path path) { return Files.exists(path); }

    public static void ensureDir(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new AppException("E_STORE_002", "创建目录失败：" + dir, e);
        }
    }
}
