## 背景

项目为 Java 21 单模块 Gradle 工程，通用工具位于 `org.example.simple.util`，复杂工具按能力分子包（`util.codec`、`util.excel`）。既有工具的共同风格：`final` 工具类 + 私有构造器 + 静态方法；不可变选项类用私有构造器 + 静态 `builder()`；失败使用自有运行时异常并附带错误分类枚举（`ExcelProcessingException` / `ExcelErrorType`、`ScriptExecutionException`）；参数非法使用带中文描述的 `IllegalArgumentException`；公开 API 写中文 Javadoc。本设计沿用这套风格，不引入新的架构分层。

## 目标与非目标

目标：提供一个无状态工具类，输入文本得到 PNG 二维码，输出形态覆盖 Base64 字符串、data URI、指定文件和调用方输出流；支持在码图中间叠加提示文本；反向支持把二维码 PNG（Base64/data URI、输入流、文件）解码回原始文本；选项可控但都有可用默认值；失败语义明确。

非目标：QR_CODE 以外的其他码制、图片/Logo 嵌入与其他美化、提示文本自动换行与字体文件加载、PNG 之外的格式、批量与缓存、解码端的多码识别与摄像头场景图像预处理（旋转/透视校正）。详见 `proposal.md` 的非目标小节。

## 技术决策

### 依赖选择：编码解码都只依赖 zxing core

生产代码仅依赖 `implementation 'com.google.zxing:core:3.5.3'`，编码和解码共用这一个依赖，不引入 `com.google.zxing:javase`。

`javase` 模块提供两块便利能力：`MatrixToImageWriter`（`BitMatrix` → `BufferedImage`，编码方向）和 `BufferedImageLuminanceSource`（`BufferedImage` → `LuminanceSource`，解码方向）。两者各自只有几十行逻辑，自行实现即可；而引入该模块会带来 `jai-imageio-core`、`jcommander` 等与本能力无关的传递依赖。核心编解码能力（`Encoder`/`Decoder`、`MultiFormatReader`、`BinaryBitmap`、`HybridBinarizer`、`EncodeHintType`/`DecodeHintType`、`ErrorCorrectionLevel`）全部位于 `core`；PNG 读写使用 JDK 自带的 `ImageIO`。

因此新增的最小 `LuminanceSource` 只需要实现 `getRow`/`getMatrix` 两个抽象方法（按 ITU-R BT.601 的加权系数把 ARGB 转灰度），不需要 `javase` 版本里为了支持相机取景而做的裁剪、旋转能力——本能力的非目标已明确不支持这类图像预处理。此前测试范围引入的 `testImplementation 'com.google.zxing:javase:3.5.3'`（仅用于测试内手写解码校验编码往返）随之移除：测试直接调用新增的公开 `decode` 方法完成往返校验，不再需要单独的测试期解码依赖。

版本 `3.5.3` 是基线值，已在任务 1.2 核验过可从项目仓库解析且在 Java 21 下可用；解码新增的类型（`Decoder`、`MultiFormatReader` 等）与编码使用的是同一 `core` 构件，不需要重新核验版本。

### 包与类型

新增包 `org.example.simple.util.qrcode`：

| 类型 | 说明 |
| --- | --- |
| `QrCodeUtils` | `final` + 私有构造器，仅静态方法，公开入口 |
| `QrCodeOptions` | 不可变，私有构造器 + `defaults()` + `builder()`，Builder 在 `build()` 时统一校验 |
| `QrCodeErrorCorrection` | 自有枚举 `LOW`/`MEDIUM`/`QUARTILE`/`HIGH`，包内映射到 ZXing `ErrorCorrectionLevel` |
| `QrCodeErrorType` | 自有枚举 `ENCODING`/`FONT`/`DECODING`/`IO` |
| `QrCodeException` | `extends RuntimeException`，携带 `QrCodeErrorType`，保留 cause |

`QrCodeErrorCorrection` 用自有枚举而不是直接暴露 `ErrorCorrectionLevel`：调用方的编译期依赖不因此扩散到 ZXing，后续更换编码实现时公开 API 不变。这与 `ExcelErrorType` 等既有自有枚举的取舍一致。

### 公开 API

```java
public static String toBase64(String text);
public static String toBase64(String text, QrCodeOptions options);

public static String toDataUri(String text);
public static String toDataUri(String text, QrCodeOptions options);

public static void writeToFile(String text, Path file);
public static void writeToFile(String text, Path file, QrCodeOptions options);

public static void write(String text, OutputStream output);
public static void write(String text, OutputStream output, QrCodeOptions options);

public static String decode(String base64OrDataUri);
public static String decode(InputStream input);
public static String decode(Path file);
```

无选项重载等价于传入 `QrCodeOptions.defaults()`。四组方法共用同一条内部管线：

```text
校验参数 → Encoder.encode(text, ecLevel, hints) 得到模块矩阵 ByteMatrix
        → 按请求边长与静区模块数计算每模块像素边长，着色渲染为 BufferedImage(TYPE_INT_ARGB)
        → 配置了提示文本时在中间叠加背板与文字
        → ImageIO.write(image, "png", 目标流)
```

实现直接调用 ZXing 更底层的 `com.google.zxing.qrcode.encoder.Encoder`（`QRCodeWriter#encode` 内部同样调用它），而不是 `QRCodeWriter#encode`：后者一步到位返回按请求像素尺寸渲染好的 `BitMatrix`，丢失了“每个模块占多少像素”这一信息；而中间提示文本背板需要按模块网格对齐（见下），必须知道这个每模块像素边长。直接使用 `Encoder` 拿到未经像素渲染的模块矩阵 `ByteMatrix`（宽高是模块数，不是像素数），自行按请求边长计算缩放倍数、静区留白并逐模块着色，这样渲染逻辑本身就掌握了每模块像素边长，无需事后从最终像素图反推。

`toBase64` 先写入 `ByteArrayOutputStream`，再用 `java.util.Base64.getEncoder()` 得到标准 Base64（非 URL 安全变体），与 `util.codec` 下加解密工具的 Base64 约定一致。`toDataUri` 在其结果前拼接固定前缀 `data:image/png;base64,`。

`write(OutputStream)` 是共用原语，**不关闭**调用方传入的流，与 `ExcelUtils` 的流所有权约定一致；写出中途失败时输出流可能已含不完整字节，该约束写入 Javadoc 与使用文档。

### 选项与默认值

`QrCodeOptions` 全部字段与默认值：

| 选项 | 默认值 | 约束 | 说明 |
| --- | --- | --- | --- |
| `size` | `300` | `[21, 4096]` | 图片边长像素，二维码为正方形，宽高相同 |
| `margin` | `1` | `[0, 32]` | 静区宽度，单位是**模块数**（ZXing `EncodeHintType.MARGIN` 语义），不是像素 |
| `errorCorrection` | `MEDIUM` | 非 null | 纠错等级 |
| `charset` | `UTF-8` | 非 null | 文本编码，映射到 `EncodeHintType.CHARACTER_SET` |
| `foregroundColor` | `0xFF000000` | 任意 int | 暗模块颜色，ARGB |
| `backgroundColor` | `0xFFFFFFFF` | 任意 int | 亮模块颜色，ARGB，允许透明 |
| `centerText` | `null` | `null` 或非空白 | 中间提示文本，`null` 表示不叠加 |
| `centerTextFontName` | `Font.SANS_SERIF` | 非空白 | 字体名称，逻辑字体名或已安装的物理字体名 |
| `centerTextFontSize` | `0` | `0` 或 `[6, 512]` | 字号像素，`0` 表示按背板自动适配 |
| `centerTextColor` | `0xFF000000` | 任意 int | 文字颜色，ARGB |
| `centerTextBackgroundColor` | `0xFFFFFFFF` | 任意 int | 背板颜色，ARGB；透明表示不铺背板 |
| `centerTextAreaRatio` | `0.22` | `[0.05, 0.30]` | 背板边长占图片边长的比例 |

`size` 下界取 21：这是版本 1 二维码的模块边长，低于该值无法保证每个模块至少占一个像素。上界 4096 是防御性限制，避免一次调用申请过大的像素缓冲（4096² ARGB ≈ 64 MB）。`size` 不必是模块数的整数倍，ZXing 自行处理缩放，实际输出边长可能因取整略大于请求值，该行为写入文档而不强行裁剪。

`BufferedImage` 使用 `TYPE_INT_ARGB` 而非 `TYPE_INT_RGB`，使背景色支持透明（如 `0x00FFFFFF`）。ARGB 的 int 形式直接由调用方给出，不引入 `java.awt.Color` 依赖到选项类型上。

### 中间提示文本叠加

`centerText` 非空时，在渲染完模块的图片上再叠加一层：先铺一块居中背板，再在背板中央绘制文字。

**先铺背板而不是直接把文字画在模块上**：文字笔画与暗模块混在一起会让解码器把笔画当作模块，识别率明显下降；铺一块纯色背板则等价于让中心区域整块“缺失”，正好落在二维码纠错能力的适用范围内。背板颜色透明时不铺背板，只画文字，该组合仅用于本身留白足够的场景，文档标注为不推荐。

**背板边界对齐到模块网格**：背板理论边长为 `size × centerTextAreaRatio`，实现时按渲染阶段算出的每模块像素边长向外取整到整数个模块，避免中心出现半个模块的残留色块。

**纠错等级约束**：`centerText` 非空且 `errorCorrection` 为 `LOW` 时，在 `build()` 阶段抛出参数异常。QR 的 L 级只能恢复约 7% 的码字，而默认 0.22 的背板已遮挡约 4.8% 的面积（且集中在一处），叠加后可识别性没有保障。不做静默升级——不擅自改写调用方显式设置的等级，而是让它快速失败并在消息中给出建议等级。文档推荐配合 `HIGH`（约 30% 恢复能力）使用。`centerTextAreaRatio` 上界 0.30 同样是这个原因：再大就超出 H 级的恢复余量。

**字号自动适配**：`centerTextFontSize` 为 `0`（默认）时，按 `\n` 切分成行，从背板内边距允许的最大字号开始，用 `FontMetrics` 测量每行宽度和总行高，逐步缩小直到整块文本放进背板；缩到下界 6px 仍放不下时抛出参数异常，提示缩短文本或提高 `centerTextAreaRatio`。显式指定字号时不自动缩放，超出背板同样抛参数异常——宁可失败也不输出文字溢出到模块区域的图片。

**缺字检测**：中文提示文本依赖运行环境安装了 CJK 字体，最小化容器镜像常常没有。绘制前用 `Font#canDisplayUpTo` 检查，存在无法显示的字符时抛出 `QrCodeException(FONT, ...)`，消息给出字体名和首个无法显示字符的位置，而不是输出一串“豆腐块”让调用方到线上才发现。为此 `QrCodeErrorType` 增加 `FONT` 取值。

**无头渲染**：只使用 `BufferedImage` + `Graphics2D` + `Font`，不创建任何 AWT 窗口组件，在 `java.awt.headless=true` 下可正常工作；工具不修改该系统属性。开启抗锯齿（`TEXT_ANTIALIASING`、`ANTIALIASING`）以保证小字号可读。

### 文件写出

`writeToFile` 的行为：

1. 校验 `file` 非 null。
2. 父目录不存在时用 `Files.createDirectories` 创建，符合“写入到指定文件”的直觉。
3. 在目标文件所在目录创建临时文件并写入完整 PNG，然后移动覆盖目标文件：优先 `ATOMIC_MOVE`，文件系统不支持时退回 `REPLACE_EXISTING`。
4. 任何失败路径删除临时文件，不在目标位置留下半个 PNG。

选择“临时文件 + 移动”而不是直写目标文件：直写在编码或 I/O 失败时会留下不可读的残缺 PNG，而二维码文件通常被其他进程直接读取。代价是同目录需要可写权限和一次额外的移动，对本能力的数据量可忽略。

不强制校验文件扩展名，但输出内容始终是 PNG，文档明确建议使用 `.png`。

### 解码：PNG 还原为文本

三个 `decode` 重载共用同一条管线：

```text
取得 PNG 字节（Base64/data URI 解码，或读取输入流/文件）
    → ImageIO.read 得到 BufferedImage
    → 包内最小 LuminanceSource 转灰度
    → BinaryBitmap(HybridBinarizer) → MultiFormatReader.decode(bitmap, hints)
    → Result.getText()
```

**`decode(String)` 同时接受纯 Base64 和 data URI**：先判断入参是否以 `data:` 开头，是则定位第一个 `,` 并只取其后的内容作为 Base64 负载，否则整个入参就是 Base64 负载。这让 `toBase64`/`toDataUri` 的输出都可以原样传回 `decode`，不需要调用方自己剥前缀。Base64 格式本身非法（如包含非法字符）时直接复用 `java.util.Base64.getDecoder().decode(...)` 抛出的 `IllegalArgumentException`，不额外包装。

**只请求 QR_CODE 格式**：`DecodeHintType.POSSIBLE_FORMATS` 固定设为 `EnumSet.of(BarcodeFormat.QR_CODE)`，避免 `MultiFormatReader` 默认尝试全部条码格式带来的无谓开销和误判；同时设置 `DecodeHintType.TRY_HARDER = true` 提高对本工具生成的、可能带有颜色/中间文字叠加的图片的识别成功率。不传 `CHARACTER_SET` 解码提示：生成阶段的 `Encoder` 在字符集非默认（如 UTF-8）时会自动为负载附加 ECI（Extended Channel Interpretation）标记，`MultiFormatReader` 按标记自解译，本工具生成的二维码天然是自描述的；仅当解码第三方生成、未带 ECI 标记的非 ASCII 二维码时可能出现乱码，这种情况超出本工具的目标范围（本工具的生成与解码配套使用）。

**中间提示文本叠加不影响解码**：叠加逻辑已经把背板对齐模块网格并要求非 `LOW` 纠错等级（见上），遮挡部分落在纠错能力范围内，`HybridBinarizer` 按整图自适应阈值处理，不需要解码侧做任何特殊处理；已在测试中对叠加后的图片做解码往返验证。

**失败分类**：

- `ImageIO.read` 返回 `null`（字节不是可识别的图片格式）：抛出 `QrCodeException(DECODING, ...)`。
- `MultiFormatReader.decode` 只声明抛出 `NotFoundException`：具体解码器内部产生的 `FormatException`/`ChecksumException`（找到疑似码但内容不合法或校验失败）已被 `MultiFormatReader` 自身吸收并统一体现为 `NotFoundException`，因此只需捕获这一种异常，抛出 `QrCodeException(DECODING, ...)`，保留原始异常作为 cause，不在消息中嵌入图片数据。
- 读取输入流或文件发生 `IOException`：抛出 `QrCodeException(IO, ...)`，与写出侧的 I/O 失败共用同一错误类别。
- `base64OrDataUri`/`input`/`file` 为 `null`（`decode(String)` 额外拒绝空字符串）：抛出带中文描述的 `IllegalArgumentException`，与生成侧的参数校验风格一致。

**最小 `LuminanceSource` 实现**：包内新增 `QrCodeImageLuminanceSource extends LuminanceSource`，构造时用 `BufferedImage.getRGB(...)` 批量取像素，按 `(299*r + 587*g + 114*b) / 1000` 计算灰度值（`javase` 版本使用的同一组系数），只实现 `getRow`/`getMatrix`；不覆盖 `crop`/`rotateCounterClockwise`，因为基类默认实现（返回不支持）已经满足“不做图像预处理”的非目标。

### 参数校验与异常

- `text` 为 `null` 或空字符串、`file`/`output`/`options` 为 `null`、Builder 选项越界：抛出带中文描述的 `IllegalArgumentException`，与既有工具的参数校验风格一致。纯空白文本是合法的二维码内容，允许通过，文档说明这一点。
- 提示文本相关的配置冲突同样在 `build()` 阶段抛出 `IllegalArgumentException`：`centerText` 为空白字符串、`centerText` 非空时纠错等级为 `LOW`、显式字号或自动适配下界仍放不进背板。
- `decode` 的入参为 `null`（`decode(String)` 额外拒绝空字符串）：抛出带中文描述的 `IllegalArgumentException`；`decode(String)` 的 Base64 格式本身非法时复用 JDK `Base64` 解码器抛出的 `IllegalArgumentException`，不额外包装。
- 文本超出所选纠错等级下版本 40 的容量、字符集无法表示文本等编码类失败：包装为 `QrCodeException(ENCODING, ...)`，保留 ZXing 的 `WriterException` 作为 cause。
- 提示文本存在当前字体无法显示的字符：抛出 `QrCodeException(FONT, ...)`，消息包含字体名与首个无法显示字符的位置，不包含完整提示文本。
- 待解码字节不是可识别的图片，或图片中未找到可识别的二维码、找到但内容非法：包装为 `QrCodeException(DECODING, ...)`，保留 ZXing 异常作为 cause。
- 写出或读取失败（`IOException`、`ImageIO.write` 返回 `false`、临时文件移动失败）：包装为 `QrCodeException(IO, ...)`。
- 异常消息为中文，不记录完整文本内容或图片字节（可能含业务敏感信息），只在必要时给出文本长度等定量信息；工具不打印日志，日志策略由调用方决定。

用 `IllegalArgumentException` 处理参数、用 `QrCodeException` 处理执行失败，是项目既有工具的一致做法（见 `script-execution-utilities` 与 `cryptography-utilities` 的对应契约）。

### 并发与资源

`QrCodeUtils` 无可变静态状态；`QrCodeOptions` 不可变，可安全跨线程共享。每次调用独立执行编码/解码与渲染，不缓存、不共享任何 ZXing 或 AWT 对象（`MultiFormatReader` 实例同样每次新建）。单次调用的内存占用由 `size`（生成）或输入图片尺寸（解码）决定，生成侧受 `size` 上界约束，但并发调用会叠加，文档提示服务层控制并发。

### 示例

```java
// 默认 300px，中等纠错，返回纯 Base64
String base64 = QrCodeUtils.toBase64("https://example.com/order/A-01");

// 前端直接渲染
String dataUri = QrCodeUtils.toDataUri("订单 A-01");

// 自定义选项后落盘
QrCodeOptions options = QrCodeOptions.builder()
    .size(512)
    .margin(2)
    .errorCorrection(QrCodeErrorCorrection.HIGH)
    .foregroundColor(0xFF1F2937)
    .backgroundColor(0x00FFFFFF)
    .build();
QrCodeUtils.writeToFile("https://example.com/order/A-01", Path.of("build/qr/A-01.png"), options);

// Web 下载：写入响应流，工具不关闭该流
QrCodeUtils.write("https://example.com/order/A-01", response.getOutputStream());

// 中间叠加提示文本，配合高纠错等级
QrCodeOptions labeled = QrCodeOptions.builder()
    .size(400)
    .errorCorrection(QrCodeErrorCorrection.HIGH)
    .centerText("扫码\n报修")
    .centerTextAreaRatio(0.24)
    .centerTextColor(0xFFB91C1C)
    .build();
String labeledBase64 = QrCodeUtils.toBase64("https://example.com/repair/A-01", labeled);

// 解码：三种来源殊途同归，都得到同一段原始文本
String fromBase64 = QrCodeUtils.decode(base64);
String fromDataUri = QrCodeUtils.decode(dataUri);          // 自动识别并去除 data URI 前缀
String fromFile = QrCodeUtils.decode(Path.of("build/qr/A-01.png"));
```

## 风险与取舍

- 新增第三方依赖 → 编码解码均只引入 `zxing:core` 一个生产依赖，不为解码额外引入 `javase`；已核验版本可解析与 Java 21 兼容性。
- 自实现模块矩阵 → `BufferedImage` 的着色渲染 → 逻辑简单且有解码往返测试覆盖，换取更干净的依赖树，同时获得中间提示文本对齐模块网格所需的每模块像素边长。
- 自实现最小 `LuminanceSource` 而不是引入 `javase` 的 `BufferedImageLuminanceSource` → 只多几十行灰度转换代码，换取不引入 `jai-imageio-core`/`jcommander`；代价是不支持 `javase` 版本具备的裁剪/旋转能力，与本能力明确的“不做图像预处理”非目标一致。
- `decode` 只识别 QR_CODE、不做多码识别、不做旋转透视校正 → 匹配“生成/解码配套使用、不面向相机取景场景”的目标定位，避免把工具做成通用条码识别 SDK；确有相机场景需求时应作为独立能力单独评估。
- 自有纠错等级与错误分类枚举需要维护映射 → 换取公开 API 不泄漏第三方类型，映射集中在包内一处。
- `size` 上界 4096 可能限制超大打印需求 → 首期取防御性默认，确有需求时按 OpenSpec 流程调整上界，而不是放开为无界。
- 临时文件 + 移动增加一次文件系统操作与同目录可写要求 → 换取目标文件不出现残缺 PNG。
- 中间叠加提示文本必然遮挡模块、降低容错余量 → 用背板对齐模块网格、限制背板占比上界 0.30、拒绝 `LOW` 等级，并在测试中对每种叠加配置做解码往返验证，把“还能扫出来”变成可执行的断言而不是经验判断。
- 拒绝 `LOW` 而不是自动升级等级 → 调用方显式设置被静默改写更难排查；代价是配置组合非法时需要调用方改一行代码。
- 中文提示文本依赖环境字体，最小化镜像可能缺字 → 绘制前做 `canDisplayUpTo` 检测并以 `FONT` 类别快速失败；工具不内嵌字体文件，避免体积与授权问题，需要固定字形的部署自行安装字体或指定已安装字体名。
- 字号自动适配使用 `FontMetrics` 逐步缩小，存在少量测量开销 → 相对编码与 PNG 压缩可忽略；调用方可显式指定字号跳过适配。
- `ImageIO` 是进程级共享设施（缓存目录、provider 注册）→ 本能力只使用标准 PNG writer，不修改 `ImageIO` 全局配置。
