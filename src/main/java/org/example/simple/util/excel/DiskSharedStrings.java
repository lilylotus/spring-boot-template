package org.example.simple.util.excel;

import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * 将 XLSX 共享字符串存放到磁盘，仅在堆中保留小型热点缓存。
 * <p>
 * 数据文件按“长度 + UTF-8 内容”顺序写入，索引文件按字符串序号保存数据偏移，避免唯一字符串数量决定堆峰值。
 */
final class DiskSharedStrings implements AutoCloseable {

    /** 最近访问共享字符串的最大缓存条目数。 */
    private static final int CACHE_SIZE = 256;

    /** 保存字符串长度和 UTF-8 内容的数据文件。 */
    private final RandomAccessFile data;
    /** 按共享字符串序号保存数据文件偏移的定长索引文件。 */
    private final RandomAccessFile index;
    /** 按访问顺序淘汰的热点字符串缓存。 */
    private final Map<Integer, String> cache = new LinkedHashMap<>(CACHE_SIZE, 0.75F, true) {
        /** 超过固定容量时淘汰最久未访问条目。 */
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, String> eldest) {
            return size() > CACHE_SIZE;
        }
    };
    /** 已写入磁盘索引的共享字符串数量。 */
    private int count;

    /**
     * 打开工作区内的数据文件和索引文件。
     *
     * @param dataPath 字符串数据文件路径
     * @param indexPath 定长偏移索引文件路径
     */
    private DiskSharedStrings(Path dataPath, Path indexPath) throws IOException {
        data = new RandomAccessFile(dataPath.toFile(), "rw");
        index = new RandomAccessFile(indexPath.toFile(), "rw");
    }

    /**
     * 将共享字符串 XML 顺序解析到磁盘结构中；没有共享字符串部件时创建空表。
     *
     * @param input 共享字符串 XML 输入流，允许为 {@code null}
     * @param workspace 调用级临时工作区
     * @param maxCellCharacters 单条共享字符串字符上限
     * @return 可随机读取的磁盘共享字符串表
     */
    static DiskSharedStrings load(
        InputStream input,
        ExcelTempWorkspace workspace,
        int maxCellCharacters) {
        DiskSharedStrings strings = null;
        try {
            strings = new DiskSharedStrings(
                workspace.resolve("shared-strings.data"),
                workspace.resolve("shared-strings.index"));
            if (input != null) {
                XMLReaderHolder.parse(
                    input,
                    new SharedStringHandler(strings, workspace, maxCellCharacters));
            }
            return strings;
        } catch (ExcelProcessingException exception) {
            if (strings != null) {
                strings.close();
            }
            throw exception;
        } catch (Exception exception) {
            if (strings != null) {
                strings.close();
            }
            throw new ExcelProcessingException(ExcelErrorType.FORMAT, "解析 XLSX 共享字符串失败", exception);
        }
    }

    /**
     * 按零基序号读取共享字符串，优先命中热点缓存。
     *
     * @param position 共享字符串零基序号
     * @return 对应字符串
     */
    synchronized String get(int position) {
        if (position < 0 || position >= count) {
            throw new ExcelProcessingException(
                ExcelErrorType.FORMAT,
                "共享字符串索引越界，索引=" + position + "，数量=" + count);
        }
        String cached = cache.get(position);
        if (cached != null) {
            return cached;
        }
        try {
            index.seek((long) position * Long.BYTES);
            data.seek(index.readLong());
            int length = data.readInt();
            byte[] bytes = new byte[length];
            data.readFully(bytes);
            String value = new String(bytes, StandardCharsets.UTF_8);
            cache.put(position, value);
            return value;
        } catch (IOException exception) {
            throw new ExcelProcessingException(ExcelErrorType.IO, "读取共享字符串临时文件失败", exception);
        }
    }

    /**
     * 追加一个字符串及其偏移，并在写入前登记临时字节预算。
     *
     * @param value 待保存字符串
     * @param workspace 负责资源预算的工作区
     */
    private synchronized void append(String value, ExcelTempWorkspace workspace) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        workspace.reserve(Long.BYTES + Integer.BYTES + bytes.length);
        index.writeLong(data.getFilePointer());
        data.writeInt(bytes.length);
        data.write(bytes);
        count++;
    }

    /** 关闭随机访问文件并释放堆内缓存，不负责删除工作区文件。 */
    @Override
    public void close() {
        try {
            data.close();
        } catch (IOException ignored) {
            // 临时工作区关闭时仍会删除文件。
        }
        try {
            index.close();
        } catch (IOException ignored) {
            // 临时工作区关闭时仍会删除文件。
        }
        cache.clear();
    }

    /** 共享字符串 XML 处理器，拼接富文本中的全部普通文本片段。 */
    private static final class SharedStringHandler extends DefaultHandler {

        /** 解析完成后的写入目标。 */
        private final DiskSharedStrings target;
        /** 负责临时字节预算的工作区。 */
        private final ExcelTempWorkspace workspace;
        /** 单条共享字符串最大字符数。 */
        private final int maxCellCharacters;
        /** 当前共享字符串聚合缓冲区，包含多个富文本片段。 */
        private final StringBuilder value = new StringBuilder();
        /** 当前是否位于共享字符串元素 {@code si} 内。 */
        private boolean inString;
        /** 当前是否采集普通文本元素 {@code t}。 */
        private boolean inText;
        /** 当前所在注音元素 {@code rPh} 的嵌套深度，注音文本不计入显示值。 */
        private int phoneticDepth;

        /** 创建共享字符串 SAX 处理器。 */
        private SharedStringHandler(
            DiskSharedStrings target,
            ExcelTempWorkspace workspace,
            int maxCellCharacters) {
            this.target = target;
            this.workspace = workspace;
            this.maxCellCharacters = maxCellCharacters;
        }

        /** 更新共享字符串、普通文本和注音片段的进入状态。 */
        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes) {
            String name = localName.isEmpty() ? qName : localName;
            if ("si".equals(name)) {
                inString = true;
                value.setLength(0);
            } else if ("rPh".equals(name)) {
                phoneticDepth++;
            } else if (inString && phoneticDepth == 0 && "t".equals(name)) {
                inText = true;
            }
        }

        /** 收集普通文本内容，并在聚合阶段实施字符数上限。 */
        @Override
        public void characters(char[] characters, int start, int length) {
            if (inText) {
                if (value.length() + length > maxCellCharacters) {
                    throw new ExcelProcessingException(
                        ExcelErrorType.RESOURCE_LIMIT,
                        "共享字符串字符数超过限制，限制=" + maxCellCharacters
                            + "，实际至少=" + (value.length() + length));
                }
                value.append(characters, start, length);
            }
        }

        /** 结束共享字符串时将聚合结果追加到磁盘结构。 */
        @Override
        public void endElement(String uri, String localName, String qName) throws SAXException {
            String name = localName.isEmpty() ? qName : localName;
            if ("t".equals(name)) {
                inText = false;
            } else if ("rPh".equals(name)) {
                phoneticDepth--;
            } else if ("si".equals(name)) {
                try {
                    target.append(value.toString(), workspace);
                } catch (IOException exception) {
                    throw new SAXException(exception);
                }
                inString = false;
            }
        }
    }

    /** 隔离安全 SAX 初始化细节。 */
    private static final class XMLReaderHolder {

        /** 静态辅助类不允许实例化。 */
        private XMLReaderHolder() {
        }

        /** 使用统一安全配置解析共享字符串 XML。 */
        private static void parse(InputStream input, DefaultHandler handler) throws Exception {
            var reader = ExcelXmlSupport.newReader();
            reader.setContentHandler(handler);
            reader.parse(new InputSource(input));
        }
    }
}
