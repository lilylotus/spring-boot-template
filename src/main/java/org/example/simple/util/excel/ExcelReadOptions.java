package org.example.simple.util.excel;

import java.nio.file.Path;

/**
 * XLSX 读取选项。所有容量均为有限正数，避免不可信上传导致无限制资源占用。
 */
public final class ExcelReadOptions {

    /** 默认压缩输入上限，单位为字节。 */
    public static final long DEFAULT_MAX_INPUT_BYTES = 200L * 1024 * 1024;
    /** 默认内部临时文件总上限，单位为字节。 */
    public static final long DEFAULT_MAX_TEMP_BYTES = 1024L * 1024 * 1024;
    /** 默认列表最大数据行数。 */
    public static final int DEFAULT_MAX_LIST_ROWS = 100_000;
    /** XLSX 单工作表最大列数。 */
    public static final int XLSX_MAX_COLUMNS = 16_384;
    /** XLSX 单元格文本最大字符数。 */
    public static final int XLSX_MAX_CELL_CHARACTERS = 32_767;

    /** 优先选择的工作表名称；为 {@code null} 时使用索引选择。 */
    private final String sheetName;
    /** 未指定工作表名称时使用的零基工作表索引。 */
    private final int sheetIndex;
    /** 标题所在的零基行索引。 */
    private final int headerRowIndex;
    /** 第一条数据所在的零基行索引。 */
    private final int dataStartRowIndex;
    /** 是否忽略所有单元格均为空的数据行。 */
    private final boolean skipBlankRows;
    /** 标题匹配前是否去除两端空白。 */
    private final boolean trimHeaders;
    /** 普通文本值返回前是否去除两端空白。 */
    private final boolean trimCellValues;
    /** 调用方输入流允许复制的最大压缩字节数。 */
    private final long maxInputBytes;
    /** 本次读取创建的全部内部临时文件最大总字节数。 */
    private final long maxTempBytes;
    /** 便捷列表 API 允许在堆中累计的最大数据行数。 */
    private final int maxListRows;
    /** SAX 读取允许发送给处理器的最大数据行数。 */
    private final int maxRows;
    /** 单个工作表允许出现的最大列数。 */
    private final int maxColumns;
    /** 工作簿允许声明的最大工作表数。 */
    private final int maxSheets;
    /** 读取期间允许处理的最大累计非空单元格数。 */
    private final long maxCells;
    /** 单个单元格允许保留的最大字符数。 */
    private final int maxCellCharacters;
    /** 调用级临时目录的可选父目录。 */
    private final Path tempDirectory;

    /**
     * 从已校验构建器复制字段，形成不可变配置快照。
     *
     * @param builder 已完成校验的构建器
     */
    private ExcelReadOptions(Builder builder) {
        this.sheetName = builder.sheetName;
        this.sheetIndex = builder.sheetIndex;
        this.headerRowIndex = builder.headerRowIndex;
        this.dataStartRowIndex = builder.dataStartRowIndex;
        this.skipBlankRows = builder.skipBlankRows;
        this.trimHeaders = builder.trimHeaders;
        this.trimCellValues = builder.trimCellValues;
        this.maxInputBytes = builder.maxInputBytes;
        this.maxTempBytes = builder.maxTempBytes;
        this.maxListRows = builder.maxListRows;
        this.maxRows = builder.maxRows;
        this.maxColumns = builder.maxColumns;
        this.maxSheets = builder.maxSheets;
        this.maxCells = builder.maxCells;
        this.maxCellCharacters = builder.maxCellCharacters;
        this.tempDirectory = builder.tempDirectory;
    }

    /**
     * 创建使用生产安全默认值的读取选项。
     *
     * @return 默认读取选项
     */
    public static ExcelReadOptions defaults() {
        return builder().build();
    }

    /**
     * 创建读取选项构建器。
     *
     * @return 新构建器
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 返回指定工作表名称。
     *
     * @return 指定工作表名称，未指定时为 {@code null}
     */
    public String getSheetName() {
        return sheetName;
    }

    /**
     * 返回零基工作表索引。
     *
     * @return 零基工作表索引，仅在未指定名称时生效
     */
    public int getSheetIndex() {
        return sheetIndex;
    }

    /**
     * 返回零基标题行索引。
     *
     * @return 零基标题行索引
     */
    public int getHeaderRowIndex() {
        return headerRowIndex;
    }

    /**
     * 返回零基数据起始行索引。
     *
     * @return 零基数据起始行索引
     */
    public int getDataStartRowIndex() {
        return dataStartRowIndex;
    }

    /**
     * 返回是否跳过全空数据行。
     *
     * @return 是否跳过全空数据行
     */
    public boolean isSkipBlankRows() {
        return skipBlankRows;
    }

    /**
     * 返回是否修剪标题两端空白。
     *
     * @return 是否修剪标题两端空白
     */
    public boolean isTrimHeaders() {
        return trimHeaders;
    }

    /**
     * 返回是否修剪普通文本单元格两端空白。
     *
     * @return 是否修剪普通文本单元格两端空白
     */
    public boolean isTrimCellValues() {
        return trimCellValues;
    }

    /**
     * 返回压缩输入最大字节数。
     *
     * @return 压缩输入最大字节数
     */
    public long getMaxInputBytes() {
        return maxInputBytes;
    }

    /**
     * 返回本次读取内部临时文件最大总字节数。
     *
     * @return 内部临时文件最大总字节数
     */
    public long getMaxTempBytes() {
        return maxTempBytes;
    }

    /**
     * 返回便捷列表接口最大数据行数。
     *
     * @return 便捷列表接口最大数据行数
     */
    public int getMaxListRows() {
        return maxListRows;
    }

    /**
     * 返回流式读取最大数据行数。
     *
     * @return 流式读取最大数据行数
     */
    public int getMaxRows() {
        return maxRows;
    }

    /**
     * 返回最大列数。
     *
     * @return 最大列数
     */
    public int getMaxColumns() {
        return maxColumns;
    }

    /**
     * 返回工作簿最大工作表数。
     *
     * @return 工作簿最大工作表数
     */
    public int getMaxSheets() {
        return maxSheets;
    }

    /**
     * 返回最大累计非空单元格数。
     *
     * @return 最大累计非空单元格数
     */
    public long getMaxCells() {
        return maxCells;
    }

    /**
     * 返回单元格最大字符数。
     *
     * @return 单元格最大字符数
     */
    public int getMaxCellCharacters() {
        return maxCellCharacters;
    }

    /**
     * 返回内部临时目录的父目录。
     *
     * @return 临时目录父路径，未指定时为 {@code null}
     */
    public Path getTempDirectory() {
        return tempDirectory;
    }

    /**
     * XLSX 读取选项构建器。
     */
    public static final class Builder {

        /** 待构建的工作表名称。 */
        private String sheetName;
        /** 待构建的零基工作表索引。 */
        private int sheetIndex;
        /** 待构建的零基标题行索引。 */
        private int headerRowIndex;
        /** 待构建的零基数据起始行索引，默认紧跟标题行。 */
        private int dataStartRowIndex = 1;
        /** 是否跳过空数据行，默认跳过。 */
        private boolean skipBlankRows = true;
        /** 是否修剪标题，默认修剪。 */
        private boolean trimHeaders = true;
        /** 是否修剪文本值，默认去除前后空白。 */
        private boolean trimCellValues = true;
        /** 压缩输入字节上限。 */
        private long maxInputBytes = DEFAULT_MAX_INPUT_BYTES;
        /** 内部临时文件总字节上限。 */
        private long maxTempBytes = DEFAULT_MAX_TEMP_BYTES;
        /** 便捷列表最大行数。 */
        private int maxListRows = DEFAULT_MAX_LIST_ROWS;
        /** 流式读取最大数据行数。 */
        private int maxRows = 1_048_575;
        /** 单表最大列数。 */
        private int maxColumns = XLSX_MAX_COLUMNS;
        /** 工作簿最大工作表数。 */
        private int maxSheets = 100;
        /** 最大累计非空单元格数。 */
        private long maxCells = 10_000_000L;
        /** 单元格最大字符数。 */
        private int maxCellCharacters = XLSX_MAX_CELL_CHARACTERS;
        /** 临时目录的可选父目录。 */
        private Path tempDirectory;

        /** 仅允许通过 {@link ExcelReadOptions#builder()} 创建构建器。 */
        private Builder() {
        }

        /**
         * 设置工作表名称。
         *
         * @param sheetName 工作表名称，不能空白
         * @return 当前构建器
         */
        public Builder sheetName(String sheetName) {
            this.sheetName = sheetName;
            return this;
        }

        /**
         * 设置零基工作表索引。
         *
         * @param sheetIndex 零基工作表索引
         * @return 当前构建器
         */
        public Builder sheetIndex(int sheetIndex) {
            this.sheetIndex = sheetIndex;
            return this;
        }

        /**
         * 设置零基标题行索引。
         *
         * @param headerRowIndex 零基标题行索引
         * @return 当前构建器
         */
        public Builder headerRowIndex(int headerRowIndex) {
            this.headerRowIndex = headerRowIndex;
            return this;
        }

        /**
         * 设置零基数据起始行索引。
         *
         * @param dataStartRowIndex 零基数据起始行索引
         * @return 当前构建器
         */
        public Builder dataStartRowIndex(int dataStartRowIndex) {
            this.dataStartRowIndex = dataStartRowIndex;
            return this;
        }

        /**
         * 设置是否跳过全空行。
         *
         * @param skipBlankRows 是否跳过全空行
         * @return 当前构建器
         */
        public Builder skipBlankRows(boolean skipBlankRows) {
            this.skipBlankRows = skipBlankRows;
            return this;
        }

        /**
         * 设置是否修剪标题空白。
         *
         * @param trimHeaders 是否修剪标题空白
         * @return 当前构建器
         */
        public Builder trimHeaders(boolean trimHeaders) {
            this.trimHeaders = trimHeaders;
            return this;
        }

        /**
         * 设置是否修剪文本值空白。
         *
         * @param trimCellValues 是否修剪文本值空白
         * @return 当前构建器
         */
        public Builder trimCellValues(boolean trimCellValues) {
            this.trimCellValues = trimCellValues;
            return this;
        }

        /**
         * 设置压缩输入最大字节数。
         *
         * @param maxInputBytes 压缩输入最大字节数
         * @return 当前构建器
         */
        public Builder maxInputBytes(long maxInputBytes) {
            this.maxInputBytes = maxInputBytes;
            return this;
        }

        /**
         * 设置内部临时文件最大总字节数。
         *
         * @param maxTempBytes 内部临时文件最大总字节数
         * @return 当前构建器
         */
        public Builder maxTempBytes(long maxTempBytes) {
            this.maxTempBytes = maxTempBytes;
            return this;
        }

        /**
         * 设置列表接口最大数据行数。
         *
         * @param maxListRows 列表接口最大数据行数
         * @return 当前构建器
         */
        public Builder maxListRows(int maxListRows) {
            this.maxListRows = maxListRows;
            return this;
        }

        /**
         * 设置最大数据行数。
         *
         * @param maxRows 最大数据行数
         * @return 当前构建器
         */
        public Builder maxRows(int maxRows) {
            this.maxRows = maxRows;
            return this;
        }

        /**
         * 设置最大列数。
         *
         * @param maxColumns 最大列数
         * @return 当前构建器
         */
        public Builder maxColumns(int maxColumns) {
            this.maxColumns = maxColumns;
            return this;
        }

        /**
         * 设置工作簿最大工作表数。
         *
         * @param maxSheets 工作簿最大工作表数
         * @return 当前构建器
         */
        public Builder maxSheets(int maxSheets) {
            this.maxSheets = maxSheets;
            return this;
        }

        /**
         * 设置最大累计非空单元格数。
         *
         * @param maxCells 最大累计非空单元格数
         * @return 当前构建器
         */
        public Builder maxCells(long maxCells) {
            this.maxCells = maxCells;
            return this;
        }

        /**
         * 设置单元格最大字符数。
         *
         * @param maxCellCharacters 单元格最大字符数
         * @return 当前构建器
         */
        public Builder maxCellCharacters(int maxCellCharacters) {
            this.maxCellCharacters = maxCellCharacters;
            return this;
        }

        /**
         * 设置临时目录父路径。
         *
         * @param tempDirectory 临时目录父路径
         * @return 当前构建器
         */
        public Builder tempDirectory(Path tempDirectory) {
            this.tempDirectory = tempDirectory;
            return this;
        }

        /**
         * 校验并创建不可变选项。
         *
         * @return 读取选项
         * @throws IllegalArgumentException 任一参数不符合范围时抛出
         */
        public ExcelReadOptions build() {
            if (sheetName != null && sheetName.isBlank()) {
                throw new IllegalArgumentException("工作表名称不能为空白");
            }
            requireNonNegative(sheetIndex, "工作表索引");
            requireNonNegative(headerRowIndex, "标题行索引");
            requireNonNegative(dataStartRowIndex, "数据起始行索引");
            if (dataStartRowIndex <= headerRowIndex) {
                throw new IllegalArgumentException("数据起始行必须位于标题行之后");
            }
            requirePositive(maxInputBytes, "输入字节上限");
            requirePositive(maxTempBytes, "临时文件字节上限");
            requirePositive(maxListRows, "列表行数上限");
            requireRange(maxRows, 1, 1_048_575, "数据行数上限");
            requireRange(maxColumns, 1, XLSX_MAX_COLUMNS, "列数上限");
            requireRange(maxSheets, 1, 10_000, "工作表数量上限");
            requirePositive(maxCells, "单元格数量上限");
            requireRange(maxCellCharacters, 1, XLSX_MAX_CELL_CHARACTERS, "单元格字符上限");
            return new ExcelReadOptions(this);
        }

        /** 校验索引类参数不能为负数。 */
        private static void requireNonNegative(int value, String name) {
            if (value < 0) {
                throw new IllegalArgumentException(name + "不能小于零");
            }
        }

        /** 校验资源上限必须为正数。 */
        private static void requirePositive(long value, String name) {
            if (value <= 0) {
                throw new IllegalArgumentException(name + "必须为正数");
            }
        }

        /** 校验整数参数处于闭区间内。 */
        private static void requireRange(int value, int minimum, int maximum, String name) {
            if (value < minimum || value > maximum) {
                throw new IllegalArgumentException(name + "必须在 " + minimum + " 到 " + maximum + " 之间");
            }
        }
    }
}
