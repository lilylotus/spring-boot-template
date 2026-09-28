# Tasks

## 1. 审阅与依赖确认

- [x] 1.1 获得用户对 `proposal.md`、`design.md` 和 `specs/qrcode-generation/spec.md` 的明确确认（首版：仅生成能力）。
- [x] 1.2 在 `build.gradle` 新增 `implementation 'com.google.zxing:core:3.5.3'` 与 `testImplementation 'com.google.zxing:javase:3.5.3'`，运行 `gradlew.bat dependencies --configuration runtimeClasspath` 核验版本可从项目仓库解析、在 Java 21 下可用且未引入预期外的传递依赖。
- [x] 1.3 获得用户对新增“解码”能力的 `proposal.md`、`design.md` 和 `specs/qrcode-generation/spec.md` 修订的明确确认。
- [x] 1.4 从 `build.gradle` 移除 `testImplementation 'com.google.zxing:javase:3.5.3'`（解码改为自实现最小 `LuminanceSource`，测试直接调用新增的公开 `decode` 方法，不再需要该依赖）；重新运行 `gradlew.bat dependencies --configuration testRuntimeClasspath` 确认已移除且不遗留 `jcommander`/`jai-imageio-core`。

## 2. 类型与选项

- [x] 2.1 新增 `QrCodeErrorType`（`ENCODING`、`FONT`、`IO`）与 `QrCodeException extends RuntimeException`，携带错误类别、中文消息和 cause，并提供读取错误类别的访问方法。
- [x] 2.2 新增 `QrCodeErrorCorrection`（`LOW`、`MEDIUM`、`QUARTILE`、`HIGH`）及其到 ZXing `ErrorCorrectionLevel` 的包内映射，公开 API 不暴露第三方类型。
- [x] 2.3 新增不可变 `QrCodeOptions`，提供 `defaults()`、`builder()` 与全部只读访问方法，基础字段和默认值为：`size=300`、`margin=1`、`errorCorrection=MEDIUM`、`charset=UTF-8`、`foregroundColor=0xFF000000`、`backgroundColor=0xFFFFFFFF`。
- [x] 2.4 在 `QrCodeOptions` 增加提示文本字段和默认值：`centerText=null`、`centerTextFontName=Font.SANS_SERIF`、`centerTextFontSize=0`（自动适配）、`centerTextColor=0xFF000000`、`centerTextBackgroundColor=0xFFFFFFFF`、`centerTextAreaRatio=0.22`。
- [x] 2.5 在 `QrCodeOptions.Builder#build` 中集中校验：`size` 属于 `[21, 4096]`、`margin` 属于 `[0, 32]`、`errorCorrection` 与 `charset` 非 null、`centerText` 不为空白字符串、`centerTextFontName` 非空白、`centerTextFontSize` 为 `0` 或属于 `[6, 512]`、`centerTextAreaRatio` 属于 `[0.05, 0.30]`，以及 `centerText` 非空时 `errorCorrection` 不得为 `LOW`（消息给出建议等级），违反时抛出带中文描述的 `IllegalArgumentException`。
- [x] 2.6 在 `QrCodeErrorType` 增加 `DECODING` 取值，用于区分“图片无法识别为二维码”与既有的 `ENCODING`/`FONT`/`IO`。

## 3. 生成实现

- [x] 3.1 实现包内渲染原语：调用 ZXing `Encoder.encode` 得到模块矩阵 `ByteMatrix`（而不是更高层的 `QRCodeWriter#encode`，以便获得每模块像素边长供中间提示文本对齐背板使用，详见 `design.md`），按请求边长和静区模块数计算缩放倍数，渲染为 `TYPE_INT_ARGB` 的 `BufferedImage` 并按前景色、背景色着色。
- [x] 3.2 实现提示文本叠加：按 `centerTextAreaRatio` 计算背板边长并向外取整对齐到模块边界，背板色不透明时先铺背板，再用 `Graphics2D` 开启文本与图形抗锯齿、居中绘制按 `\n` 分行的文本；绘制前用 `Font#canDisplayUpTo` 检测缺字并抛 `QrCodeException(FONT, ...)`，消息含字体名与首个缺字位置。
- [x] 3.3 实现字号适配：`centerTextFontSize` 为 `0` 时用 `FontMetrics` 从背板可用内边距允许的最大字号逐步缩小到能容纳全部行，缩至下界 6px 仍放不下时抛带中文描述的 `IllegalArgumentException`；显式字号不缩放，超出背板同样抛参数异常。
- [x] 3.4 实现 `QrCodeUtils.write(String, OutputStream[, QrCodeOptions])`：用 `ImageIO.write(image, "png", output)` 写出，不关闭调用方流；`ImageIO.write` 返回 `false` 或抛 `IOException` 时抛出 `QrCodeException(IO, ...)`。
- [x] 3.5 实现 `QrCodeUtils.toBase64(String[, QrCodeOptions])`：复用渲染原语写入 `ByteArrayOutputStream`，用标准 Base64 编码器返回纯 Base64 文本。
- [x] 3.6 实现 `QrCodeUtils.toDataUri(String[, QrCodeOptions])`：在 Base64 结果前拼接 `data:image/png;base64,`。
- [x] 3.7 实现 `QrCodeUtils.writeToFile(String, Path[, QrCodeOptions])`：必要时创建父目录，先写同目录临时文件再移动覆盖（优先 `ATOMIC_MOVE`，不支持时退回 `REPLACE_EXISTING`），任何失败路径清理临时文件。
- [x] 3.8 统一入参校验与异常包装：`null`/空文本与 `null` 的 `file`/`output`/`options` 抛中文 `IllegalArgumentException`，纯空白文本放行；ZXing `WriterException` 包装为 `QrCodeException(ENCODING, ...)`，消息不含完整文本。
- [x] 3.9 为 `QrCodeUtils`、`QrCodeOptions`、`QrCodeErrorCorrection`、`QrCodeErrorType`、`QrCodeException` 的公开 API 补充中文 Javadoc，说明默认值、静区单位为模块数、提示文本的纠错等级要求与字体依赖、流不被关闭、失败语义与并发约定。

## 4. 解码实现

- [x] 4.1 新增包内 `QrCodeImageLuminanceSource extends com.google.zxing.LuminanceSource`，基于 `BufferedImage.getRGB(...)` 按 `(299*r + 587*g + 114*b) / 1000` 计算灰度，实现 `getRow`/`getMatrix`，不覆盖 `crop`/`rotateCounterClockwise`（使用基类默认的“不支持”实现，与“不做图像预处理”的非目标一致）。
- [x] 4.2 实现包内解码原语：`ImageIO.read` 读取字节为 `BufferedImage`（返回 `null` 时抛 `QrCodeException(DECODING, ...)`），构造 `BinaryBitmap(new HybridBinarizer(new QrCodeImageLuminanceSource(image)))`，用固定 `hints`（`DecodeHintType.POSSIBLE_FORMATS = EnumSet.of(BarcodeFormat.QR_CODE)`、`DecodeHintType.TRY_HARDER = true`）调用 `new MultiFormatReader().decode(bitmap, hints)`；`MultiFormatReader.decode` 只声明抛出 `NotFoundException`（内部已吸收具体解码器的 `FormatException`/`ChecksumException`），捕获该异常包装为 `QrCodeException(DECODING, ...)`，返回 `Result.getText()`。
- [x] 4.3 实现 `QrCodeUtils.decode(String base64OrDataUri)`：非空校验（`null`/空字符串抛 `IllegalArgumentException`）；若以 `data:` 开头，定位首个 `,` 后的子串作为 Base64 负载，否则整个入参即负载；用 `Base64.getDecoder().decode(...)` 得到字节（格式非法时让其原生抛出的 `IllegalArgumentException` 直接传播，不额外包装）；交给解码原语。
- [x] 4.4 实现 `QrCodeUtils.decode(InputStream input)`：非空校验；用 `input.readAllBytes()` 读取全部字节交给解码原语，`IOException` 包装为 `QrCodeException(IO, ...)`；方法不关闭该输入流。
- [x] 4.5 实现 `QrCodeUtils.decode(Path file)`：非空校验；用 `Files.readAllBytes(file)` 读取，`IOException` 包装为 `QrCodeException(IO, ...)`；交给解码原语。
- [x] 4.6 为三个 `decode` 重载补充中文 Javadoc：说明 `decode(String)` 同时接受纯 Base64 和 data URI、只识别 QR_CODE、输入流不被关闭、失败分类（`IllegalArgumentException` vs `QrCodeException` 的 `DECODING`/`IO`）。

## 5. 测试

- [x] 5.1 新增 `QrCodeUtilsTest`，验证 Base64、data URI、文件、输出流四种形态均可解码回原文本（改用本工具新增的 `QrCodeUtils.decode` 而不是测试专用的 ZXing `javase` 解码器），且三种字节输出内容一致。
- [x] 5.2 测试文本场景：ASCII 链接、中文文本、纯空白文本可正常生成；`null` 与空字符串抛 `IllegalArgumentException`；超长文本抛 `QrCodeException` 且错误类别为 `ENCODING`。
- [x] 5.3 新增 `QrCodeOptionsTest`，验证默认值、自定义边长使输出宽高相等且不小于请求值、透明背景保留 alpha 通道，以及 `size`/`margin` 越界与 `null` 选项字段抛 `IllegalArgumentException`。
- [x] 5.4 测试文件写出：父目录自动创建、同名文件被覆盖后可解码为新文本、写出失败后目标路径无残缺文件且无遗留临时文件。
- [x] 5.5 测试流所有权与并发：写出后调用方流未被关闭且可继续写入；多线程共享同一 `QrCodeOptions` 并发生成不同文本，结果互不干扰。
- [x] 5.6 新增提示文本叠加测试：默认关闭时字节与不叠加一致；配置提示文本后叠加图片仍可解码为原始文本；多行文本按 `\n` 分行且整体居中；自动字号适配对长文本选出能容纳的最大字号。
- [x] 5.7 测试提示文本失败路径：显式字号超出背板、自动适配缩到下界仍放不下均抛 `IllegalArgumentException`；`centerText` 非空白但配合 `LOW` 纠错等级抛 `IllegalArgumentException` 且消息给出建议等级；不可显示字符抛 `QrCodeException` 且错误类别为 `FONT`。
- [x] 5.8 将 `QrCodeUtilsTest` 中原本手写调用 ZXing `javase`（`BufferedImageLuminanceSource` 等）的解码校验辅助方法，替换为直接调用 `QrCodeUtils.decode(...)`；确认替换后仍覆盖“生成的图片能正确还原原文本”的全部既有场景。
- [x] 5.9 新增解码测试（新建 `QrCodeDecodeTest`）：从 `toBase64` 结果解码、从 `toDataUri` 结果解码（含前缀自动识别）、从 `write`/`writeToFile` 产出的流与文件解码，四者对同一文本和选项解码结果一致；解码带中间提示文本叠加、自定义前景背景色的图片仍得到原始文本；`decode` 系列的入参为 `null` 抛 `IllegalArgumentException`，`decode(String)` 传入空字符串或非法 Base64 抛 `IllegalArgumentException`；传入非图片字节或不含二维码的图片抛 `QrCodeException` 且错误类别为 `DECODING`；验证 `decode(InputStream)` 不关闭调用方传入的流。

## 6. 文档与验证

- [x] 6.1 新增 `docs/二维码工具类使用说明.md`，覆盖四种输出形态示例、选项默认值表（含提示文本相关选项）、提示文本叠加示例与限制、异常分类表与 Web 下载接入要点；在 `README.md` 增加一行入口链接（首版：仅生成能力）。
- [x] 6.2 更新 `docs/二维码工具类使用说明.md`，补充解码用法：三个 `decode` 重载的示例（Base64、data URI、文件/流）、`decode(String)` 对 data URI 前缀的自动识别、异常分类表补充 `DECODING`。
- [x] 6.3 运行 `gradlew.bat test` 与 `gradlew.bat build`，确认全部测试通过且无新增编译或弃用警告（首版：仅生成能力）。
- [x] 6.4 补充解码实现后重新运行 `gradlew.bat test` 与 `gradlew.bat build`，确认全部测试通过（含新增解码测试）且无新增编译或弃用警告。
- [x] 6.5 运行 `openspec validate add-qrcode-utility --strict`，核对代码、测试、文档与 OpenSpec 文档一致（首版：仅生成能力）。
- [x] 6.6 补充解码实现后重新运行 `openspec validate add-qrcode-utility --strict`，并在本文件补充第二轮验证记录。

## 验证记录（第一轮：仅生成能力）

- 依赖核验：`com.google.zxing:core:3.5.3` 在 `runtimeClasspath` 下零传递依赖；`com.google.zxing:javase:3.5.3` 仅出现在 `testRuntimeClasspath`（附带 `jcommander`、`jai-imageio-core`），符合设计预期，Java 21 下编译运行正常。
- 新增文件：`src/main/java/org/example/simple/util/qrcode/`（`QrCodeUtils`、`QrCodeOptions`、`QrCodeErrorCorrection`、`QrCodeErrorType`、`QrCodeException`、`QrCodeRenderer`、`QrCodeCenterTextPainter`）与 `src/test/java/org/example/simple/util/qrcode/`（`QrCodeUtilsTest` 12 项、`QrCodeOptionsTest` 9 项、`QrCodeCenterTextTest` 8 项），共 29 项新增测试全部通过。
- 实现与 `design.md` 的一处技术细节修正：渲染改为直接调用 ZXing 更底层的 `Encoder.encode` 获取模块矩阵 `ByteMatrix`，而不是文档最初写的 `QRCodeWriter#encode`，原因是后者返回的是已按像素渲染好的 `BitMatrix`，无法反推出每模块像素边长，而中间提示文本背板对齐模块网格必须依赖这个值。该修正已同步回 `design.md`，不影响任何已确认的对外行为、默认值或需求场景。
- `gradlew.bat clean compileJava compileTestJava`：无编译错误，无新增警告。
- `gradlew.bat test build`（连续两次全量运行）：272 项测试全部通过，8 项按既有设计跳过（压力/故障专项，通过 `-Drpc.stress=true` 才启用，与本变更无关）；`xlsxMemoryTest`、`check`、`build` 均成功。首次全量运行中 `RpcLoopbackIntegrationTest.gracefulShutdownCompletesInFlightCall` 出现一次超时失败，单独重跑及第二次全量重跑均通过，确认是既有 Netty RPC 优雅停机测试的时序性偶发失败，与本变更代码（未触碰 `rpc` 包）无关。
- `openspec validate add-qrcode-utility --strict`：通过。

## 验证记录（第二轮：解码能力）

- 用户已确认新增解码能力的 `proposal.md`/`design.md`/`specs/qrcode-generation/spec.md` 修订（任务 1.3）。
- 依赖调整：移除 `testImplementation 'com.google.zxing:javase:3.5.3'`；`gradlew.bat dependencies --configuration testRuntimeClasspath` 确认 `jcommander`、`jai-imageio-core` 已随之消失，测试类路径只剩 `com.google.zxing:core:3.5.3`。生产依赖未新增，编码解码共用同一个 `core`。
- 新增文件：`QrCodeImageLuminanceSource`（最小 `LuminanceSource` 实现）、`QrCodeDecoder`（包内解码原语）；`QrCodeUtils` 新增 `decode(String)`/`decode(InputStream)`/`decode(Path)` 三个重载；`QrCodeErrorType` 新增 `DECODING`。
- 与 `design.md` 的一处技术细节修正：`MultiFormatReader.decode` 的方法签名只声明抛出 `NotFoundException`（具体解码器的 `FormatException`/`ChecksumException` 已被其内部吸收并统一体现为 `NotFoundException`），因此 `QrCodeDecoder` 只捕获这一种异常，而不是文档最初设想的三种。该修正已同步回 `design.md`，不影响任何已确认的对外行为或需求场景（解码失败仍统一归为 `QrCodeErrorType.DECODING`）。
- 测试变化：`QrCodeUtilsTest` 的解码校验辅助方法改为直接调用 `QrCodeUtils.decode(InputStream)`，不再手写 ZXing 调用；新增 `QrCodeDecodeTest`（10 项），覆盖三种解码入口、四种来源互相一致、叠加提示文本/自定义颜色后仍可解码、`null`/空/非法 Base64 入参、非图片字节、无二维码图片、输入流不被关闭。全部新增测试连同既有测试共 39 项二维码专项测试通过。
- `gradlew.bat clean compileJava compileTestJava`：无编译错误，无新增警告。
- `gradlew.bat test build`：282 项测试全部通过，8 项按既有设计跳过（与本变更无关）；`xlsxMemoryTest`、`check`、`build` 均成功，未复现此前偶发的 `RpcLoopbackIntegrationTest` 超时。
- `openspec validate add-qrcode-utility --strict`：通过。
- `docs/二维码工具类使用说明.md` 已补充解码用法、异常分类表 `DECODING` 行和 Web 上传解码接入示例；`README.md` 入口链接无需改动（首版已添加）。
