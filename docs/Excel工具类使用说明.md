# Excel 工具类使用说明

`ExcelUtils` 位于 `org.example.simple.util.excel`，是基于 SAX 读取和 SXSSF 写出的 XLSX 导入导出工具。

- 仅支持 `.xlsx`，`.xls` 会以 `FORMAT` 错误被拒绝。
- 工具**不会关闭**调用方传入的 `InputStream` 和 `OutputStream`，由调用方用 try-with-resources 管理。
- 小数据可用列表方法（`readObjectList`、`readMapList`、`writeMaps`）；大数据应用逐行读取（`readObjects`、`readMaps`）和惰性导出（`writeObjects`、`writeMapsStreaming`）。
- 所有失败都抛出运行时异常 `ExcelProcessingException`，通过 `getErrorType()` 区分错误类别。

## API 速查

| 场景 | 方法 | 数据形态 |
| --- | --- | --- |
| 对象导入（有界列表） | `readObjectList(input, Class<T>[, options])` | `List<T>` |
| 对象导入（逐行） | `readObjects(input, Class<T>[, options], handler)` | 回调 `T` |
| 配置 Map 导入（有界列表） | `readObjectList(input[, options], columns)` | `List<LinkedHashMap<String, Object>>` |
| 配置 Map 导入（逐行） | `readObjects(input[, options], columns, handler)` | 回调 `LinkedHashMap<String, Object>` |
| 通用 Map 导入（有界列表） | `readMapList(input[, options])` | `List<LinkedHashMap<String, String>>` |
| 通用 Map 导入（逐行） | `readMaps(input[, options], handler)` | 回调 `LinkedHashMap<String, String>` |
| 对象导出 | `writeObjects(output, Iterable<T>, Class<T>[, options])` | 惰性迭代 |
| Map 导出（列表） | `writeMaps(output, List<? extends Map<String, Object>>[, columns, options])` | 一次性列表 |
| Map 导出（流式） | `writeMapsStreaming(output, Iterable<? extends Map<String, ?>>[, columns, options])` | 惰性迭代 |

对象导入重载必须传入非空 `Class<T>` 且不接收 `columns`；配置 Map 导入重载只接收 `columns`。两者相互独立，不会互相覆盖或合并。

## 对象导入导出

### 声明映射

用 `@ExcelColumn` 标注实例字段，字段可以是私有的（工具通过反射访问）。`static` 和 `transient` 字段不参与映射。

```java
public class OrderRow {

    @ExcelColumn(value = "订单号", order = 10, required = true)
    private String orderNo;

    @ExcelColumn(value = "客户名称", order = 20)
    private String customerName;

    @ExcelColumn(value = "金额", order = 30)
    private BigDecimal amount;

    @ExcelColumn(value = "状态", order = 40)
    private OrderState state;

    @ExcelColumn(value = "下单时间", order = 50, dateFormat = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;

    @ExcelColumn(value = "发货日期", order = 60, dateFormat = "yyyy-MM-dd")
    private LocalDate shipDate;

    /** 导入要求可访问的无参构造器。 */
    public OrderRow() {
    }

    // getter / setter 省略
}
```

注解属性说明：

| 属性 | 默认值 | 说明 |
| --- | --- | --- |
| `value` | 无（必填） | Excel 标题，导入按标题匹配，导出作为表头文本；同一对象内不能重复 |
| `order` | `Integer.MAX_VALUE` | 列顺序，数值越小越靠前；同一对象内显式值不能重复 |
| `required` | `false` | 导入时该单元格是否必须有值；**不影响标题结构** |
| `dateFormat` | `yyyy-MM-dd HH:mm:ss` | 文本日期的解析格式和导出显示格式 |

列顺序规则：先按 `order` 升序，未显式指定 `order` 的字段排在显式字段之后，按“父类 → 子类”的继承层级和字段声明顺序排列。导入和导出使用同一套顺序。

支持的字段类型：`String`、`boolean`/`Boolean`、`byte`/`Byte`、`short`/`Short`、`int`/`Integer`、`long`/`Long`、`float`/`Float`、`double`/`Double`、`char`/`Character`、`BigDecimal`、`BigInteger`、`LocalDate`、`LocalDateTime`、`java.util.Date`，以及任意枚举（按 `name()` 匹配）。其他类型在首次使用该类型时抛出 `MAPPING` 错误。

### 导入为列表

```java
try (InputStream input = Files.newInputStream(Path.of("orders.xlsx"))) {
    List<OrderRow> rows = ExcelUtils.readObjectList(input, OrderRow.class);
    orderService.saveAll(rows);
}
```

指定工作表和表头位置：

```java
ExcelReadOptions options = ExcelReadOptions.builder()
    .sheetName("订单")        // 不指定则按 sheetIndex 取工作表
    .headerRowIndex(1)        // 表头在第 2 行（零基）
    .dataStartRowIndex(2)     // 数据从第 3 行开始（零基）
    .build();

try (InputStream input = Files.newInputStream(Path.of("orders.xlsx"))) {
    List<OrderRow> rows = ExcelUtils.readObjectList(input, OrderRow.class, options);
}
```

### 逐行导入（大数据）

行处理器返回 `false` 可正常提前停止；回调在解析线程中串行执行，不要长期阻塞。

```java
List<OrderRow> batch = new ArrayList<>(1000);

try (InputStream input = Files.newInputStream(Path.of("orders-large.xlsx"))) {
    ExcelUtils.readObjects(input, OrderRow.class, (row, context) -> {
        batch.add(row);
        if (batch.size() == 1000) {
            orderService.saveAll(batch);   // 分批落库，不把全部行累计在内存
            batch.clear();
        }
        return true;                       // 返回 false 表示正常提前结束
    });
}
if (!batch.isEmpty()) {
    orderService.saveAll(batch);
}
```

`ExcelRowContext` 提供 `sheetName()` 和面向用户的一基 `rowNumber()`，适合记录业务校验失败的行号。

### 导出对象

`writeObjects` 接收惰性 `Iterable`，按需迭代，不在工具内累计全部数据。

```java
ExcelWriteOptions options = ExcelWriteOptions.builder()
    .sheetName("订单")
    .rowWindowSize(200)          // 内存中保留的行数窗口
    .maxRowsPerSheet(500_000)    // 超出后自动新建“订单_2”“订单_3”
    .build();

try (OutputStream output = Files.newOutputStream(Path.of("orders-export.xlsx"))) {
    ExcelUtils.writeObjects(output, () -> orderRepository.iterator(), OrderRow.class, options);
}
```

导出行为：

- 标题行按列顺序写出，`null` 值不写单元格。
- `String`、`Character`、枚举按文本写出；以 `=` 开头的文本仍作为文本单元格写出，不会形成公式。
- 数字、布尔、`Date`、`LocalDate`、`LocalDateTime` 保持对应的单元格类型和日期格式。
- 数据行数达到 `maxRowsPerSheet` 时自动分表，表名为 `<sheetName>`、`<sheetName>_2`……；分表数超过 `maxSheets` 抛出 `RESOURCE_LIMIT`。
- 导出数据行不能为 `null`，否则抛出 `MAPPING`。

## 标题结构校验

对象导入和配置 Map 导入都在处理数据行**之前**做严格的标题结构校验：

1. 标题数量必须与声明列数量相同。少列或多列都抛出 `MAPPING`，消息形如
   `标题列数量与对象声明不一致，工作表=Sheet1，期望列数=6，实际列数=5`。
2. 每个标题必须位于按列顺序规则确定的索引上。标题齐全但顺序不同同样抛出 `MAPPING`，消息形如
   `标题列与对象声明不一致，工作表=Sheet1，列=2，期望标题=客户名称，实际标题=金额`，
   并在异常上带有 `getColumnNumber()`、`getHeader()`、`getFieldName()`。

即 Excel 的列集合和列顺序都必须与声明完全一致。`required = false` 只是放宽单元格值可以为空，不允许缺少该列的标题。`trimHeaders`（默认 `true`）会先修剪标题两端空白再比较。

## 运行时列配置的 Map 导入

列结构在运行时才确定（例如由用户配置模板）时，不定义对象类，直接传入列配置清单。配置键使用 `ExcelColumnConfigKeys` 常量。

| 键 | 类型 | 是否必填 | 说明 |
| --- | --- | --- | --- |
| `field` | 非空 `String` | 必填 | 返回 Map 的键 |
| `value` | 非空 `String` | 可选 | Excel 标题；省略时使用 `field` 的值 |
| `order` | `Integer` | 可选 | 列顺序；省略时保持配置项的出现顺序 |
| `required` | `Boolean` | 可选 | 单元格是否必填，默认 `false` |

```java
List<Map<String, Object>> columns = List.of(
    Map.of(
        ExcelColumnConfigKeys.FIELD, "orderNo",
        ExcelColumnConfigKeys.VALUE, "订单号",
        ExcelColumnConfigKeys.ORDER, 10,
        ExcelColumnConfigKeys.REQUIRED, true),
    Map.of(
        ExcelColumnConfigKeys.FIELD, "customerName",
        ExcelColumnConfigKeys.VALUE, "客户名称",
        ExcelColumnConfigKeys.ORDER, 20));

try (InputStream input = Files.newInputStream(Path.of("orders.xlsx"))) {
    List<LinkedHashMap<String, Object>> rows = ExcelUtils.readObjectList(input, columns);
    // rows.getFirst() 的键迭代顺序为 [orderNo, customerName]
    // 值为单元格文本，空单元格为 null
}
```

逐行版本：

```java
try (InputStream input = Files.newInputStream(Path.of("orders-large.xlsx"))) {
    ExcelUtils.readObjects(input, columns, (row, context) -> {
        importService.handle(row, context.rowNumber());
        return true;
    });
}
```

要点：

- 返回 `LinkedHashMap<String, Object>`，键是配置的 `field`（不是 Excel 标题），键顺序等于生效列顺序。
- 值是单元格文本或 `null`；`trimCellValues` 为 `true`（默认）时保存修剪后的文本，为 `false` 时保存原始文本，但 `required` 判空始终按修剪后的内容判断。
- 配置本身无效时在读取工作簿之前就抛出 `MAPPING`：缺少 `field`、`field`/`value` 不是非空字符串、标题或字段键重复、显式 `order` 重复、`required`/`order` 类型不匹配、包含未知配置键。

## 通用 Map 导入

不需要列声明、直接按 Excel 标题取值时使用 `readMapList` / `readMaps`。要求工作表标题唯一且非空。

```java
try (InputStream input = Files.newInputStream(Path.of("any.xlsx"))) {
    List<LinkedHashMap<String, String>> rows = ExcelUtils.readMapList(input);
    for (LinkedHashMap<String, String> row : rows) {
        String name = row.get("客户名称");   // 键是 Excel 标题，值是文本或 null
    }
}
```

```java
try (InputStream input = Files.newInputStream(Path.of("any-large.xlsx"))) {
    ExcelUtils.readMaps(input, (row, context) -> {
        reportService.accept(row);
        return true;
    });
}
```

## Map 导出

显式列定义使用 `LinkedHashMap<数据键, 显示标题>`，同时决定列顺序和表头文本：

```java
LinkedHashMap<String, String> columns = new LinkedHashMap<>();
columns.put("orderNo", "订单号");
columns.put("customerName", "客户名称");
columns.put("amount", "金额");

List<Map<String, Object>> data = orderService.listAsMaps();   // 允许普通 HashMap

try (OutputStream output = Files.newOutputStream(Path.of("orders.xlsx"))) {
    ExcelUtils.writeMaps(output, data, columns, ExcelWriteOptions.defaults());
}
```

不传列定义时从**首行键的迭代顺序**推断列，此时首行必须是能保证迭代顺序的 `SequencedMap`（如 `LinkedHashMap`）：

```java
LinkedHashMap<String, Object> row = new LinkedHashMap<>();
row.put("orderNo", "A-01");
row.put("amount", new BigDecimal("12.34"));

try (OutputStream output = Files.newOutputStream(Path.of("orders.xlsx"))) {
    ExcelUtils.writeMaps(output, List.of(row));   // 标题即 orderNo、amount
}
```

首行是普通 `HashMap` 且未提供列定义时抛出 `MAPPING`，提示提供显式有序列定义。

大数据用惰性数据源：

```java
try (OutputStream output = Files.newOutputStream(Path.of("orders-large.xlsx"))) {
    ExcelUtils.writeMapsStreaming(output, () -> orderRepository.mapIterator(), columns,
        ExcelWriteOptions.builder().sheetName("订单").build());
}
```

提供显式列定义时，即使数据源为空也会生成只含标题行的有效工作表，适合导出模板。

## 读取选项 ExcelReadOptions

`ExcelReadOptions.defaults()` 或 `ExcelReadOptions.builder()`。行列索引均为**零基**。

| 选项 | 默认值 | 说明 |
| --- | --- | --- |
| `sheetName` | `null` | 按名称定位工作表；为空时使用 `sheetIndex`。名称不存在抛 `CONFIGURATION` |
| `sheetIndex` | `0` | 工作表索引 |
| `headerRowIndex` | `0` | 表头所在行 |
| `dataStartRowIndex` | `1` | 数据起始行，必须大于 `headerRowIndex` |
| `skipBlankRows` | `true` | 跳过全空行 |
| `trimHeaders` | `true` | 修剪标题两端空白 |
| `trimCellValues` | `true` | 修剪文本单元格两端空白 |
| `maxInputBytes` | 200 MB | 输入字节上限 |
| `maxTempBytes` | 1 GB | 解压临时文件字节上限 |
| `maxListRows` | 100 000 | **列表方法**的行数上限，超限提示改用逐行方法 |
| `maxRows` | 1 048 575 | 累计数据行上限 |
| `maxColumns` | 16 384 | 列数上限 |
| `maxSheets` | 100 | 工作表数量上限 |
| `maxCells` | 10 000 000 | 累计单元格数上限 |
| `maxCellCharacters` | 32 767 | 单个单元格文本长度上限 |
| `tempDirectory` | `null` | 解压临时目录，`null` 使用系统临时目录 |

## 写出选项 ExcelWriteOptions

| 选项 | 默认值 | 说明 |
| --- | --- | --- |
| `sheetName` | `数据` | 工作表名，超长会被截断并做安全化处理 |
| `maxRowsPerSheet` | 1 048 575 | 单表数据行上限，超出自动分表 |
| `rowWindowSize` | 100 | SXSSF 内存行窗口，同时是临时文件预算的检查间隔 |
| `maxSheets` | 100 | 分表数量上限 |
| `maxCells` | 10 000 000 | 累计单元格数上限 |
| `maxTempBytes` | 1 GB | SXSSF 临时文件字节上限 |
| `maxCellCharacters` | 32 767 | 单个单元格文本长度上限 |
| `compressTempFiles` | `true` | 压缩临时文件，降低磁盘占用 |

## 异常处理

`ExcelProcessingException` 继承 `RuntimeException`，除 `getErrorType()` 外还可能携带 `getSheetName()`、`getRowNumber()`、`getColumnNumber()`、`getHeader()`、`getFieldName()`。请按 `ExcelErrorType` 分支处理，不要解析异常消息文本。

| `ExcelErrorType` | 含义 | 典型场景 |
| --- | --- | --- |
| `FORMAT` | 不是有效或受支持的 XLSX | 传入 `.xls`、损坏文件 |
| `CONFIGURATION` | 调用方配置无效 | `sheetName` 指定的工作表不存在 |
| `MAPPING` | 不符合映射契约 | 标题数量或顺序不一致、缺少无参构造器、列配置无效、导出行为 `null` |
| `CONVERSION` | 单元格内容无法转换 | 金额列是非数字文本、必填单元格为空 |
| `RESOURCE_LIMIT` | 超过资源上限 | 超过 `maxListRows`、`maxInputBytes`、临时文件预算 |
| `IO` | 读写发生 I/O 错误 | 输出流中断 |
| `CALLBACK` | 逐行处理器执行失败 | 回调内部抛出异常 |

```java
try (InputStream input = Files.newInputStream(path)) {
    List<OrderRow> rows = ExcelUtils.readObjectList(input, OrderRow.class);
} catch (ExcelProcessingException exception) {
    switch (exception.getErrorType()) {
        case MAPPING -> log.warn("模板不匹配：{}", exception.getMessage());
        case CONVERSION -> log.warn("第 {} 行第 {} 列数据无效", exception.getRowNumber(),
            exception.getColumnNumber());
        case RESOURCE_LIMIT -> log.warn("文件过大：{}", exception.getMessage());
        default -> throw exception;
    }
}
```

## Web 上传下载接入要点

- 上传：把 `MultipartFile.getInputStream()` 的结果直接交给读取方法，不要先读成 `byte[]`。工具不关闭该流。
- 下载：把响应输出流交给写出方法，写完后由容器关闭。响应头设置 `Content-Type` 为
  `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`，`Content-Disposition` 中的中文文件名做 URL 编码。
- 输出流写出失败时可能已发送不完整字节；需要原子替换文件时，先写入临时文件再移动。
- 单次调用有独立资源上限，但并发大任务的占用会叠加，服务层仍应限制导入导出的并发数。

下面是 Spring Web 的参考写法（本项目当前未引入 Spring Web 依赖，响应包装类按各自项目调整）：

```java
@PostMapping("/api/v1/orders/import")
public Result<ImportSummary> importOrders(@RequestPart("file") MultipartFile file) throws IOException {
    try (InputStream input = file.getInputStream()) {
        return Result.success(orderImportService.importOrders(input));
    }
}

@GetMapping("/api/v1/orders/export")
public void exportOrders(HttpServletResponse response) throws IOException {
    response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    response.setHeader("Content-Disposition",
        "attachment; filename*=UTF-8''" + URLEncoder.encode("订单.xlsx", StandardCharsets.UTF_8));
    orderExportService.exportOrders(response.getOutputStream());
}
```

业务逻辑、数据查询和 `ExcelUtils` 调用都放在 Service 层，Controller 只做参数接收、调用与响应。
