package org.example.simple.util.excel;

/**
 * XLSX 流式导出选项。
 */
public final class ExcelWriteOptions {

    /** 默认 SXSSF 内存行窗口。 */
    public static final int DEFAULT_ROW_WINDOW_SIZE = 100;
    /** 默认工作表名称。 */
    public static final String DEFAULT_SHEET_NAME = "数据";

    /** 首个工作表的基础名称，自动分表时追加序号。 */
    private final String sheetName;
    /** 每个工作表允许写出的最大数据行数，不包含标题行。 */
    private final int maxRowsPerSheet;
    /** SXSSF 在堆内保留的最近行数。 */
    private final int rowWindowSize;
    /** 自动分表允许创建的最大工作表数。 */
    private final int maxSheets;
    /** 整个导出允许写出的最大累计数据单元格数。 */
    private final long maxCells;
    /** SXSSF 临时文件允许占用的最大总字节数。 */
    private final long maxTempBytes;
    /** 单个文本单元格允许写出的最大字符数。 */
    private final int maxCellCharacters;
    /** 是否压缩 SXSSF 生成的临时 XML 文件。 */
    private final boolean compressTempFiles;

    /**
     * 从已校验构建器复制字段，形成不可变配置快照。
     *
     * @param builder 已完成校验的构建器
     */
    private ExcelWriteOptions(Builder builder) {
        this.sheetName = builder.sheetName;
        this.maxRowsPerSheet = builder.maxRowsPerSheet;
        this.rowWindowSize = builder.rowWindowSize;
        this.maxSheets = builder.maxSheets;
        this.maxCells = builder.maxCells;
        this.maxTempBytes = builder.maxTempBytes;
        this.maxCellCharacters = builder.maxCellCharacters;
        this.compressTempFiles = builder.compressTempFiles;
    }

    /**
     * 创建使用生产安全默认值的导出选项。
     *
     * @return 默认导出选项
     */
    public static ExcelWriteOptions defaults() {
        return builder().build();
    }

    /**
     * 创建导出选项构建器。
     *
     * @return 新导出选项构建器
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 返回基础工作表名称。
     *
     * @return 基础工作表名称
     */
    public String getSheetName() {
        return sheetName;
    }

    /**
     * 返回每个工作表最大数据行数。
     *
     * @return 每个工作表最大数据行数，不含标题
     */
    public int getMaxRowsPerSheet() {
        return maxRowsPerSheet;
    }

    /**
     * 返回 SXSSF 在堆内保留的最近行数。
     *
     * @return 堆内行窗口大小
     */
    public int getRowWindowSize() {
        return rowWindowSize;
    }

    /**
     * 返回最大工作表数。
     *
     * @return 最大工作表数
     */
    public int getMaxSheets() {
        return maxSheets;
    }

    /**
     * 返回最大累计数据单元格数。
     *
     * @return 最大累计数据单元格数
     */
    public long getMaxCells() {
        return maxCells;
    }

    /**
     * 返回 SXSSF 临时文件最大总字节数。
     *
     * @return 临时文件最大总字节数
     */
    public long getMaxTempBytes() {
        return maxTempBytes;
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
     * 返回是否压缩 SXSSF 临时 XML。
     *
     * @return 是否压缩临时 XML
     */
    public boolean isCompressTempFiles() {
        return compressTempFiles;
    }

    /**
     * XLSX 导出选项构建器。
     */
    public static final class Builder {

        /** 基础工作表名称。 */
        private String sheetName = DEFAULT_SHEET_NAME;
        /** 每个工作表最大数据行数。 */
        private int maxRowsPerSheet = 1_048_575;
        /** SXSSF 堆内行窗口。 */
        private int rowWindowSize = DEFAULT_ROW_WINDOW_SIZE;
        /** 自动分表最大数量。 */
        private int maxSheets = 100;
        /** 最大累计数据单元格数。 */
        private long maxCells = 10_000_000L;
        /** 导出临时文件总字节上限。 */
        private long maxTempBytes = ExcelReadOptions.DEFAULT_MAX_TEMP_BYTES;
        /** 单元格文本字符上限。 */
        private int maxCellCharacters = ExcelReadOptions.XLSX_MAX_CELL_CHARACTERS;
        /** 是否压缩临时 XML，默认开启以降低磁盘占用。 */
        private boolean compressTempFiles = true;

        /** 仅允许通过 {@link ExcelWriteOptions#builder()} 创建构建器。 */
        private Builder() {
        }

        /**
         * 设置基础工作表名称。
         *
         * @param sheetName 基础工作表名称
         * @return 当前构建器
         */
        public Builder sheetName(String sheetName) {
            this.sheetName = sheetName;
            return this;
        }

        /**
         * 设置每表最大数据行数。
         *
         * @param maxRowsPerSheet 每表最大数据行数
         * @return 当前构建器
         */
        public Builder maxRowsPerSheet(int maxRowsPerSheet) {
            this.maxRowsPerSheet = maxRowsPerSheet;
            return this;
        }

        /**
         * 设置堆内行窗口大小。
         *
         * @param rowWindowSize 堆内行窗口大小
         * @return 当前构建器
         */
        public Builder rowWindowSize(int rowWindowSize) {
            this.rowWindowSize = rowWindowSize;
            return this;
        }

        /**
         * 设置最大工作表数。
         *
         * @param maxSheets 最大工作表数
         * @return 当前构建器
         */
        public Builder maxSheets(int maxSheets) {
            this.maxSheets = maxSheets;
            return this;
        }

        /**
         * 设置最大累计数据单元格数。
         *
         * @param maxCells 最大累计数据单元格数
         * @return 当前构建器
         */
        public Builder maxCells(long maxCells) {
            this.maxCells = maxCells;
            return this;
        }

        /**
         * 设置 SXSSF 临时文件最大总字节数。
         *
         * @param maxTempBytes 临时文件最大总字节数
         * @return 当前构建器
         */
        public Builder maxTempBytes(long maxTempBytes) {
            this.maxTempBytes = maxTempBytes;
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
         * 设置是否压缩 SXSSF 临时文件。
         *
         * @param compressTempFiles 是否压缩 SXSSF 临时文件
         * @return 当前构建器
         */
        public Builder compressTempFiles(boolean compressTempFiles) {
            this.compressTempFiles = compressTempFiles;
            return this;
        }

        /**
         * 校验并创建不可变选项。
         *
         * @return 导出选项
         * @throws IllegalArgumentException 任一参数无效时抛出
         */
        public ExcelWriteOptions build() {
            if (sheetName == null || sheetName.isBlank()) {
                throw new IllegalArgumentException("工作表名称不能为空白");
            }
            requireRange(maxRowsPerSheet, 1, 1_048_575, "每表数据行数上限");
            requireRange(rowWindowSize, 1, 100_000, "行窗口大小");
            requireRange(maxSheets, 1, 10_000, "工作表数量上限");
            if (maxCells <= 0) {
                throw new IllegalArgumentException("单元格数量上限必须为正数");
            }
            if (maxTempBytes <= 0) {
                throw new IllegalArgumentException("临时文件字节上限必须为正数");
            }
            requireRange(maxCellCharacters, 1, ExcelReadOptions.XLSX_MAX_CELL_CHARACTERS, "单元格字符上限");
            return new ExcelWriteOptions(this);
        }

        /** 校验整数参数处于闭区间内。 */
        private static void requireRange(int value, int minimum, int maximum, String name) {
            if (value < minimum || value > maximum) {
                throw new IllegalArgumentException(name + "必须在 " + minimum + " 到 " + maximum + " 之间");
            }
        }
    }
}
