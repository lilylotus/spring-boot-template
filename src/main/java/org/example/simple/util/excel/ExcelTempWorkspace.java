package org.example.simple.util.excel;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/**
 * XLSX 读取调用级临时工作区，负责输入落盘、共享字符串文件路径和总字节预算。
 * <p>
 * 每次读取创建独立目录；关闭时递归清理，删除失败的路径登记为 JVM 退出时删除。
 */
final class ExcelTempWorkspace implements AutoCloseable {

    /** 本次读取独占的临时目录。 */
    private final Path directory;
    /** 工作区允许写入的最大累计字节数。 */
    private final long maxBytes;
    /** 已向预算登记的累计字节数。 */
    private long usedBytes;

    /**
     * 根据读取选项创建调用级临时目录。
     *
     * @param options 提供父目录和临时字节预算的读取选项
     * @throws ExcelProcessingException 临时目录创建失败时抛出
     */
    ExcelTempWorkspace(ExcelReadOptions options) {
        try {
            Path parent = options.getTempDirectory();
            directory = parent == null
                ? Files.createTempDirectory("simple-xlsx-")
                : Files.createTempDirectory(parent, "simple-xlsx-");
            maxBytes = options.getMaxTempBytes();
        } catch (IOException exception) {
            throw new ExcelProcessingException(ExcelErrorType.IO, "创建 XLSX 临时目录失败", exception);
        }
    }

    /**
     * 将不可回退的调用方输入流限量复制到工作区，供 OPC 包随机访问。
     *
     * @param input 调用方输入流，本方法不关闭
     * @param maxInputBytes 允许复制的最大压缩字节数
     * @return 工作区内的 XLSX 文件路径
     */
    Path copyInput(InputStream input, long maxInputBytes) {
        Path target = resolve("upload.xlsx");
        byte[] buffer = new byte[8192];
        long copied = 0;
        try (OutputStream output = Files.newOutputStream(target)) {
            int count;
            while ((count = input.read(buffer)) != -1) {
                copied += count;
                if (copied > maxInputBytes) {
                    throw limit("压缩输入字节数", maxInputBytes, copied);
                }
                reserve(count);
                output.write(buffer, 0, count);
            }
            return target;
        } catch (ExcelProcessingException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new ExcelProcessingException(ExcelErrorType.IO, "复制 XLSX 上传流失败", exception);
        }
    }

    /**
     * 解析工作区内文件路径。
     *
     * @param name 不含父路径的内部文件名
     * @return 工作区内路径
     */
    Path resolve(String name) {
        return directory.resolve(name);
    }

    /**
     * 原子登记即将写入临时文件的字节数，防止溢出或突破调用预算。
     *
     * @param bytes 本次新增字节数
     */
    void reserve(long bytes) {
        long next = usedBytes + bytes;
        if (bytes < 0 || next < usedBytes || next > maxBytes) {
            throw limit("内部临时文件字节数", maxBytes, next < usedBytes ? Long.MAX_VALUE : next);
        }
        usedBytes = next;
    }

    /** 创建格式统一的资源上限异常。 */
    private static ExcelProcessingException limit(String name, long limit, long actual) {
        return new ExcelProcessingException(
            ExcelErrorType.RESOURCE_LIMIT,
            name + "超过限制，限制=" + limit + "，实际=" + actual);
    }

    /** 递归删除本次读取创建的全部临时文件和目录。 */
    @Override
    public void close() {
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    path.toFile().deleteOnExit();
                }
            });
        } catch (IOException ignored) {
            directory.toFile().deleteOnExit();
        }
    }
}
