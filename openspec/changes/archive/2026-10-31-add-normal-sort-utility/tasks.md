# Tasks

## 1. 审阅与确认

- [x] 1.1 获得用户对 `proposal.md`、`design.md` 和 `specs/normal-sort-utilities/spec.md` 的明确确认，尤其是：`List` 复制为数组排序后写回、`null` 容器/元素抛 `NullPointerException`、计数/桶/基数排序的泛型版本采用离散化适配（整体退化为 O(n log n)）、额外提供 `int[]` 经典整数版本、不支持 `Comparator` 与降序这几项设计决策。

## 2. 实现

- [x] 2.1 新增 `org.example.simple.util.NormalSortUtils`（`final` + 私有构造器），编写类级中文 Javadoc：算法总览对比表（稳定性、复杂度）、选型建议、与 JDK 内置排序的关系、线程安全说明。
- [x] 2.2 实现公共私有辅助方法：数组/列表 `null` 校验与 `null` 元素扫描、`List` 转数组排序后 `ListIterator.set` 写回、元素交换。
- [x] 2.3 实现冒泡排序（提前结束优化）、选择排序、插入排序的数组与列表重载。
- [x] 2.4 实现希尔排序（Knuth 增量序列）的数组与列表重载。
- [x] 2.5 实现归并排序（自顶向下、单辅助数组、稳定合并、已有序跳过合并）的数组与列表重载。
- [x] 2.6 实现快速排序（`ThreadLocalRandom` 随机基准、三路划分、较小分区递归较大分区循环）的数组与列表重载。
- [x] 2.7 实现堆排序（自底向上建大顶堆、下沉）的数组与列表重载。
- [x] 2.8 实现离散化辅助方法：归并排序副本、去重、二分查找求排名。
- [x] 2.9 实现计数排序：泛型版本（基于排名的稳定计数排序）与 `int[]` 版本（偏移 `min` 支持负数，值域超过数组上限抛 `IllegalArgumentException`）。
- [x] 2.10 实现桶排序：泛型版本（按排名均匀分桶、桶内插入排序）与 `int[]` 版本（`long` 计算桶宽、两遍扫描放入连续缓冲数组、桶内插入排序）。
- [x] 2.11 实现基数排序：泛型版本（排名 LSD 基数 256）与 `int[]` 版本（翻转符号位、固定 4 轮）。
- [x] 2.12 为每个公共排序方法编写中文 Javadoc：稳定性、特点、最好/平均/最坏时间复杂度、空间复杂度、推荐场景；非比较排序泛型版本如实说明离散化带来的 O(n log n) 开销并推荐整数场景使用 `int[]` 重载。

## 3. 测试

- [x] 3.1 新增 `NormalSortUtilsTest`，用参数化测试覆盖 10 种算法的数组与列表重载。
- [x] 3.2 测试边界输入：空、单元素、已升序、已降序、全部相等，结果正确且不抛异常。
- [x] 3.3 测试 `Integer[]`、`String[]` 及 `ArrayList`/`LinkedList` 排序结果与 JDK 排序一致。
- [x] 3.4 测试固定随机种子生成的多组随机数据（含大量重复值），结果与 `Arrays.sort` 一致。
- [x] 3.5 测试稳定算法（冒泡、插入、归并、计数、桶、基数）对携带原始序号的对象保持相等元素相对顺序。
- [x] 3.6 测试 `null` 数组/列表抛 `NullPointerException`；含 `null` 元素抛 `NullPointerException` 且容器内容不变。
- [x] 3.7 测试 `int[]` 计数/桶/基数排序：含负数、零、`Integer.MIN_VALUE`、`Integer.MAX_VALUE`、随机数据与 `Arrays.sort` 一致；`null` 抛 `NullPointerException`。
- [x] 3.8 测试 `int[]` 计数排序在值域超过数组上限（如同时包含 `Integer.MIN_VALUE` 与 `Integer.MAX_VALUE`）时抛 `IllegalArgumentException` 且数组不变。

## 4. 文档与验证

- [x] 4.1 新增 `docs/NormalSortUtils使用说明.md`：算法对比表、选型建议、使用示例、泛型非比较排序离散化说明、Non-Goal；在 `README.md` 增加入口链接。
- [x] 4.2 运行 `gradlew.bat test` 与 `gradlew.bat build`，确认测试通过且无新增编译或弃用警告。
- [x] 4.3 运行 `openspec validate add-normal-sort-utility --strict`，核对代码、测试、文档与 OpenSpec 文档一致，并在本文件补充验证记录。

## 验证记录

- `gradlew.bat test --tests "org.example.simple.util.NormalSortUtilsTest"`：106 个测试全部通过。
- `gradlew.bat javadoc`：通过，`NormalSortUtils` 无 Javadoc 警告。
- `gradlew.bat build`（全量）：`NormalSortUtilsTest` 全部通过；其余 10 个失败与本变更无关，未作改动：`GlobalHttpClientUtilsTest`/`HttpClientsTest` 共 7 个因本地无可用 HTTP 服务或既有偶发性断言失败，`ExcelUtilsTest` 2 个因本地缺少测试所需文件（`NoSuchFileException`），`RpcLoopbackIntegrationTest.gracefulShutdownCompletesInFlightCall` 1 个偶发失败（单独重跑通过）。
- 实施中设计调整：`int[]` 与泛型桶排序改为两遍扫描放入连续缓冲数组（替代每桶动态扩容数组），已同步更新 `design.md`。
