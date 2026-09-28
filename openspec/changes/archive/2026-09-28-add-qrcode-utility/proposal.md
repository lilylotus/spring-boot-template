## 背景与原因

项目 `org.example.simple.util` 下已有脚本、雪花 ID、线程池、加解密和 Excel 等通用工具，但缺少二维码生成能力。业务侧常见需求是把一段文本（链接、票据编号、设备标识）生成 PNG 二维码，要么以 Base64 字符串返回给前端直接渲染，要么落盘到指定文件。当前没有统一入口，调用方需自行引入依赖、拼装 `BitMatrix` 和处理 `ImageIO`，容易在字符集、纠错等级、静区和异常语义上各写一套。

## 变更内容

- 新增 `org.example.simple.util.qrcode` 子包，与既有 `util.codec`、`util.excel` 的分包方式保持一致。
- 新增静态工具类 `QrCodeUtils`，以文本和可选选项为输入，输出 PNG 二维码：
  - `String toBase64(String text[, QrCodeOptions options])`：返回标准 Base64 编码的 PNG 字节，不含 data URI 前缀。
  - `String toDataUri(String text[, QrCodeOptions options])`：返回 `data:image/png;base64,...`，供前端 `img` 标签直接使用。
  - `void writeToFile(String text, Path file[, QrCodeOptions options])`：写入指定文件，自动创建父目录，同名文件覆盖。
  - `void write(String text, OutputStream output[, QrCodeOptions options])`：写入调用方输出流，不关闭该流，供 Web 下载等场景复用。
- 新增解码入口，把二维码 PNG 还原为其编码的原始文本：
  - `String decode(String base64OrDataUri)`：接收标准 Base64 文本，或 `toDataUri` 产出的完整 data URI（自动识别并去除前缀）。
  - `String decode(InputStream input)`：从调用方输入流读取 PNG 并解码，不关闭该流。
  - `String decode(Path file)`：从指定文件读取 PNG 并解码。
  - 只识别 QR_CODE 码制；能正确解码本工具生成的任意受支持配置（含中间提示文本叠加、自定义颜色）的二维码。
- 新增不可变选项类 `QrCodeOptions`（Builder 构建），覆盖图片边长、静区模块数、纠错等级、字符集、前景色和背景色。
- 支持在二维码中间叠加提示文本：选项可配置文本内容、字体名称、字号（默认自动适配）、文字颜色、背板颜色和背板占比，支持 `\n` 显式换行。未配置提示文本时输出与不带该能力时一致。
- 新增自有枚举 `QrCodeErrorCorrection`，避免公开 API 暴露第三方库类型。
- 新增运行时异常 `QrCodeException` 和错误分类枚举 `QrCodeErrorType`，区分“文本无法编码”“图片无法识别为二维码”与“读写失败”。
- `build.gradle` 新增二维码编解码依赖：生产仅需 `com.google.zxing:core`（编码与解码共用，均不产生额外传递依赖）；不引入 `com.google.zxing:javase`（见下）。
- 补充 `docs/` 下的中文使用说明，并在 `README.md` 增加入口链接，与脚本、Netty RPC、Excel 文档的组织方式一致。

## 能力

### 新增能力

- `qrcode-generation`：文本与 PNG 二维码互转的契约，包含生成的输出形态（Base64、data URI、文件、输出流）、选项与默认值、中间提示文本叠加、解码的输入来源（Base64/data URI、输入流、文件）、参数校验、异常分类和资源行为。

### 修改能力

无。

## 非目标

- 解码只识别 QR_CODE 码制，不提供条形码、DataMatrix、Aztec 等其他码制的识别或生成。
- 解码不做多码识别：图片中包含多个可识别二维码时只返回其中一个，具体是哪一个不作保证。
- 解码不做摄像头取景、透视校正、旋转校正等针对现实拍摄场景的图像预处理，只处理已经是正向、完整二维码图片的输入。
- 解码只返回二维码中编码的原始文本，不对内容做业务层面的解析或校验（例如不识别其中的 URL、JSON）。
- 不提供 Logo/图片嵌入、圆角模块、渐变着色等美化能力；中间叠加只支持文本。
- 提示文本不做自动换行、不做字体文件加载或内嵌，只使用运行环境已安装的字体并支持 `\n` 显式换行。
- 不提供二维码下方或周边的说明文字排版，只支持居中叠加。
- 不提供 JPEG、SVG、WebP 等 PNG 之外的输出格式。
- 不提供批量生成、缓存、异步或限流能力，调用方自行控制并发。

## 影响范围

- 新增文件集中在 `src/main/java/org/example/simple/util/qrcode/` 与 `src/test/java/org/example/simple/util/qrcode/`，不改动任何既有生产代码。
- `build.gradle` 仅新增依赖声明，不调整既有依赖版本、`test` 配置或 `jcstress` 配置。
- 新增文档 `docs/二维码工具类使用说明.md` 与 `README.md` 的一行链接。
- 依赖版本以 `com.google.zxing:core:3.5.3` 为基线，实施首步须在项目当前仓库（阿里云镜像 + Maven Central）核验可解析并可在 Java 21 运行；若版本不可用或不兼容，先同步本文档与 `design.md` 再继续。
- 提示文本绘制使用 JDK 自带的 `java.awt` 无头渲染，不新增图形依赖；但中文提示文本依赖运行环境已安装 CJK 字体，最小化容器镜像可能缺字，实施时须做缺字检测并快速失败。
- 解码不依赖 `com.google.zxing:javase`（该模块的 `BufferedImageLuminanceSource` 会带入 `jai-imageio-core`、`jcommander` 等传递依赖）：自行实现从 `BufferedImage` 取灰度的最小 `LuminanceSource`，与此前为避免引入该模块而自实现 `BufferedImage` 渲染的取舍一致。因此测试范围此前引入的 `testImplementation 'com.google.zxing:javase:3.5.3'`（仅用于测试中手写解码校验往返）本次移除，测试改为直接调用新增的公开 `decode` 方法完成往返校验。
- 本次是在 `add-qrcode-utility` 已完成实现、测试和文档、尚未归档的情况下追加的能力扩展，直接在同一个未归档变更中补充文档并等待再次确认，不新开变更。
- 工作区当前存在与本变更无关的未提交内容（`build.gradle` 的 jcstress 改动、`runJcstress.bat`、`src/main/java/org/example/simple/jcstress/`、上一次归档产生的 `openspec/` 改动）。本变更不涉及也不代为提交这些内容。
