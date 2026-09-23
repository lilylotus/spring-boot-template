package org.example.simple.util.excel;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.apache.poi.util.TempFileCreationStrategy;

/**
 * 当前导出调用专用的 POI 临时文件策略，负责隔离 SXSSF 临时文件、检查预算并清理目录。
 * <p>
 * POI 会通过全局临时文件入口创建文件，因此调用方通过 {@code TempFile.withStrategy} 在当前作用域使用本策略。
 */
final class ExcelWriteTempStrategy implements TempFileCreationStrategy, AutoCloseable {

    /** 本次导出独占的临时目录。 */
    private final Path directory;
    /** SXSSF 临时文件允许占用的最大总字节数。 */
    private final long maxBytes;
    /** 由 POI 通过本策略创建的文件和目录。 */
    private final List<Path> paths = new ArrayList<>();

    /**
     * 创建导出临时目录和预算策略。
     *
     * @param maxBytes 临时文件最大总字节数
     */
    ExcelWriteTempStrategy(long maxBytes) {
        try {
            directory = Files.createTempDirectory("simple-xlsx-write-");
            this.maxBytes = maxBytes;
        } catch (IOException exception) {
            throw new ExcelProcessingException(ExcelErrorType.IO, "创建 XLSX 导出临时目录失败", exception);
        }
    }

    /** {@inheritDoc} */
    @Override
    public File createTempFile(String prefix, String suffix) throws IOException {
        Path path = Files.createTempFile(directory, prefix, suffix);
        paths.add(path);
        return path.toFile();
    }

    /** {@inheritDoc} */
    @Override
    public File createTempDirectory(String prefix) throws IOException {
        Path path = Files.createTempDirectory(directory, prefix);
        paths.add(path);
        return path.toFile();
    }

    /**
     * 扫描已创建文件的当前大小，确认累计磁盘占用未超过配置预算。
     *
     * @throws ExcelProcessingException 读取文件大小失败或总字节数超限时抛出
     */
    void checkLimit() {
        long total = 0;
        try {
            for (Path path : paths) {
                if (Files.isRegularFile(path)) {
                    total += Files.size(path);
                    if (total > maxBytes) {
                        throw new ExcelProcessingException(
                            ExcelErrorType.RESOURCE_LIMIT,
                            "导出临时文件字节数超过限制，限制=" + maxBytes + "，实际至少=" + total);
                    }
                }
            }
        } catch (ExcelProcessingException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new ExcelProcessingException(ExcelErrorType.IO, "检查 XLSX 导出临时文件失败", exception);
        }
    }

    /** 递归删除本次导出创建的全部临时文件和目录。 */
    @Override
    public void close() {
        try (var files = Files.walk(directory)) {
            files.sorted(Comparator.reverseOrder()).forEach(path -> {
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
