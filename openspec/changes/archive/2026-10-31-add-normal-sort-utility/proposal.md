## Why

项目目前没有统一的排序算法工具。`Arrays.sort`/`Collections.sort` 虽能满足日常排序，但在教学演示、算法对比、特定数据分布（如小范围整数、近乎有序数据）下选择合适算法等场景中，需要一组实现规范、行为一致、并在注释中清楚说明稳定性、特点、复杂度与适用场景的经典排序算法实现。各处临时手写排序容易在边界（空集合、重复元素、负数、`null` 元素）上出错，也缺少选型依据。

## What Changes

- 新增 `org.example.simple.util.NormalSortUtils` 静态工具类（`final` + 私有构造器），仅使用 JDK 内置 API，不引入新依赖。
- 提供 10 种排序算法：冒泡排序、选择排序、插入排序、希尔排序、归并排序、快速排序、堆排序、计数排序、桶排序、基数排序。
- 每种算法均提供两个泛型重载，元素类型约束为 `<T extends Comparable<? super T>>`，统一按自然顺序升序、原地排序：
  - `xxxSort(T[] array)`：直接对数组原地排序。
  - `xxxSort(List<T> list)`：复制为数组排序后通过 `ListIterator.set` 写回，与 `Collections.sort` 行为一致，`ArrayList`/`LinkedList` 均高效。
- 计数排序、桶排序、基数排序是非比较排序，原理上要求整数键。对泛型 `Comparable` 元素采用"离散化"适配：先将元素映射为其在全部去重值中的排名（`0..k-1` 的整数），再对排名执行经典的计数/桶/基数排序流程，并保持稳定性。
- 计数排序、桶排序、基数排序额外提供 `int[]` 重载，实现经典的整数版本（支持负数），保留这些算法 O(n + k) / O(d·n) 的真实复杂度优势，作为整数场景的推荐入口。
- 每个公共排序方法的中文 Javadoc 统一说明：稳定性、算法特点、时间复杂度（最好/平均/最坏）、空间复杂度、推荐使用的数据场景。
- 参数约定：数组或列表本身为 `null`、或包含 `null` 元素时抛出 `NullPointerException`；空集合或单元素集合直接返回。

## Capabilities

### New Capabilities

- `normal-sort-utilities`：定义 10 种经典排序算法对泛型 `Comparable` 数组与列表的升序原地排序行为、稳定性约定、参数校验约定，以及计数/桶/基数排序的 `int[]` 整数版本行为。

### Modified Capabilities

无。

## Impact

- 新增 `src/main/java/org/example/simple/util/NormalSortUtils.java`。
- 新增 `src/test/java/org/example/simple/util/NormalSortUtilsTest.java`。
- 新增 `docs/NormalSortUtils使用说明.md`，并在 `README.md` 增加入口链接。
- 不新增 Gradle 依赖；不修改既有工具类。
