# Tasks

## 1. 内置地区码表

- [x] 1.1 用脚本把 `src/main/resources/area-codes.csv` 按 1000 行切成 3 段，生成 `AREA_CODES_CSV_PART_1` ~ `_PART_3` 三个文本块常量并插入 `RandomUserInfoUtils` 类末尾，常量上方加注释声明"静态快照、勿手工编辑、需更新请从 CSV 重新生成"；验证：`./gradlew classes` 编译通过（证明没有超出 65535 字节的字符串常量）
- [x] 1.2 把 `loadAreaCodes()` 改写为 `parseAreaCodes()`，按行切分三段文本、以第一个逗号拆分 `code`/`region`，跳过空行与无逗号行，返回 `List.copyOf` 不可变列表；删除 `BufferedReader`/`InputStream`/`InputStreamReader`/`IOException`/`UncheckedIOException`/`StandardCharsets` 等不再使用的 import；验证：`./gradlew classes` 编译无告警，且源码中 `getResourceAsStream` 已不存在（`grep` 为空）
- [x] 1.3 新增测试 `builtInAreaCodeTableMatchesCsvSnapshot()`：从 classpath 读 `area-codes.csv` 构造 `code -> region` 映射，断言 `generateRegions(2842)` 返回的地区名称集合与 CSV 的 region 集合完全相同、条数一致；验证：该测试通过
- [x] 1.4 更新 `RandomUserInfoUtils` 类级 Javadoc，说明地区码表已内置于源码、类加载不读取任何 classpath 资源、该类可单文件独立使用；验证：Javadoc 中不再出现 `/area-codes.csv` 资源路径的加载描述

## 2. 扩充取名用字池

- [x] 2.1 在 `GIVEN_NAME_CHARS` 现有 100 字之后追加 100 个常见取名用字（与原有 100 字不重叠），数组总长 200；验证：新增测试 `givenNameCharsHasTwoHundredDistinctChars()` 断言去重后元素数为 200
- [x] 2.2 新增 `COMPOUND_SURNAMES` 2 字复姓池与 `MINORITY_NAMES` 少数民族完整姓名池（约 100 条，每条 2~4 字、仅含汉字、不含 `·`）；验证：新增测试断言两个池内元素各自去重、`MINORITY_NAMES` 每条长度在 2~4 且全为汉字

## 3. 姓名生成策略

- [x] 3.1 把 `buildRandomName()` 改为三分支加权实现：单姓（权重 70，1 字姓 + 1~3 字名）、复姓（权重 10，2 字复姓 + 1~2 字名）、少数民族（权重 20，整条抽取）；验证：`./gradlew classes` 编译通过
- [x] 3.2 为三个分支各抽出一个私有构建方法（`buildHanSingleSurnameName()` / `buildHanCompoundSurnameName()` / `buildMinorityName()`），并补充中文 Javadoc 说明各自的长度区间；验证：代码审查确认 `buildRandomName()` 只负责分支选择
- [x] 3.3 新增测试 `generateNames_allNamesAreTwoToFourChineseChars()`：对 `generateNames(2000)` 的每个姓名断言长度在 2~4 之间且匹配 `^[一-龥]{2,4}$`；验证：该测试通过
- [x] 3.4 新增测试 `generateNames_coversHanAndMinorityNames()`：对 `generateNames(2000)` 断言结果中至少出现一个 `MINORITY_NAMES` 池内姓名、也至少出现一个以 `SURNAMES` 单姓开头的姓名；验证：该测试通过且可重复执行（不依赖随机种子，2000 样本下三分支权重使其稳定成立）
- [x] 3.5 新增测试 `generateUsers_allNamesAreTwoToFourChineseChars()`：对 `generateUsers(500)` 的 `name` 做同样的长度与汉字断言；验证：该测试通过

## 4. 文档与收尾

- [x] 4.1 更新 `docs/RandomUserInfoUtils使用说明.md`：补充"零 classpath 资源依赖、可单文件拷用"与新的姓名规则（2~4 字、三类姓名、200 字取名用字池）；验证：文档中旧的"需要 area-codes.csv 资源"描述已被替换
- [x] 4.2 运行 `gradlew.bat test` 全量测试；验证：`RandomUserInfoUtilsTest` 27/27 全绿，全量 471 个测试 462 通过、9 失败、8 跳过；9 个失败全部落在 `GlobalHttpClientUtilsTest`、`HttpClientsTest`、`ExcelUtilsTest` 三个与本次改动无关的类，原因是本机缺少硬编码的绝对路径文件（`C:\Users\Administrator\Pictures\star-face-img*.png`、`D:\worktemporary\month09\xxx.xlsx`）与未启动的本地服务（`127.0.0.1:54100`、`:18888`），属环境性既有失败，本次改动未新增失败
- [x] 4.3 运行 `openspec validate enhance-random-user-info-generator` 并核对代码、测试与 spec delta 一致；验证：校验通过且三类姓名、零资源依赖两项要求均有对应测试
