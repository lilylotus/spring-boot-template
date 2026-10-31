# NormalSortUtils 使用说明

`org.example.simple.util.NormalSortUtils` 提供 10 种经典排序算法：冒泡、选择、插入、希尔、归并、快速、堆、计数、桶、基数排序。

## 接口约定

- 每种算法都提供 `xxxSort(T[] array)` 与 `xxxSort(List<T> list)` 两个重载，`T extends Comparable<? super T>`，按自然顺序升序、原地排序。
- 计数、桶、基数排序额外提供 `xxxSort(int[] array)` 经典整数版本，支持负数及 `Integer.MIN_VALUE`/`Integer.MAX_VALUE`。
- `List` 先复制为数组排序，再通过 `ListIterator.set` 写回，`ArrayList`/`LinkedList` 性能一致；不可修改列表（元素多于 1 个时）抛出 `UnsupportedOperationException`。
- 数组或列表为 `null`、或包含 `null` 元素时抛出 `NullPointerException`，校验在排序前完成，容器内容不会被修改。
- 方法名：`bubbleSort`、`selectionSort`、`insertionSort`、`shellSort`、`mergeSort`、`quickSort`、`heapSort`、`countingSort`、`bucketSort`、`radixSort`。

## 使用示例

```java
Integer[] numbers = {5, 3, 8, 1, 3};
NormalSortUtils.quickSort(numbers);          // [1, 3, 3, 5, 8]

List<String> names = new ArrayList<>(List.of("tom", "amy", "bob"));
NormalSortUtils.mergeSort(names);            // [amy, bob, tom]

int[] scores = {88, 92, 75, 92, 60};
NormalSortUtils.countingSort(scores);        // [60, 75, 88, 92, 92]

int[] timestamps = {1730361600, -5, 1700000000};
NormalSortUtils.radixSort(timestamps);       // [-5, 1700000000, 1730361600]
```

## 算法对比

| 算法 | 稳定 | 最好 | 平均 | 最坏 | 额外空间 | 推荐场景 |
| --- | --- | --- | --- | --- | --- | --- |
| 冒泡 | 是 | O(n) | O(n²) | O(n²) | O(1) | 教学演示、极小且基本有序的数据 |
| 选择 | 否 | O(n²) | O(n²) | O(n²) | O(1) | 交换代价远高于比较代价的小数据、教学演示 |
| 插入 | 是 | O(n) | O(n²) | O(n²) | O(1) | 小规模或基本有序的数据 |
| 希尔 | 否 | O(n log n) | 约 O(n^1.3) | O(n^1.5) | O(1) | 中等规模、不要求稳定、内存/栈受限 |
| 归并 | 是 | O(n log n) | O(n log n) | O(n log n) | O(n) | 要求稳定的大规模数据、多关键字排序 |
| 快速 | 否 | O(n log n) | O(n log n) | O(n²)（概率极低） | O(log n) | 不要求稳定的通用大规模数据 |
| 堆 | 否 | O(n log n) | O(n log n) | O(n log n) | O(1) | 内存受限且需要最坏情况保证 |
| 计数（`int[]`） | 是 | O(n + k) | O(n + k) | O(n + k) | O(n + k) | 值域小的整数（分数、年龄、像素值） |
| 桶（`int[]`） | 是 | O(n) | O(n + k) | O(n²) | O(n) | 值域内均匀分布的整数 |
| 基数（`int[]`） | 是 | O(d·n) | O(d·n) | O(d·n) | O(n) | 值域很大的整数（时间戳、哈希值、ID） |

其中 n 为元素个数，k 为值域大小（`max - min + 1`），d 为数位轮数（`int` 固定为 4）。

## 实现要点

- 冒泡排序：某一趟无交换时提前结束。
- 希尔排序：Knuth 增量序列 `1, 4, 13, 40, ...`。
- 归并排序：自顶向下、复用单个辅助数组，左右半区已整体有序时跳过合并。
- 快速排序：`ThreadLocalRandom` 随机基准 + 三路划分，较小分区递归、较大分区循环，栈深度 O(log n)。
- 堆排序：自底向上建大顶堆。
- `int[]` 计数排序：值域 `max - min + 1` 超过 JVM 数组长度上限时抛出 `IllegalArgumentException`，提示改用基数排序。
- `int[]` 桶排序：桶数取 n，按值域等宽分桶，两遍扫描放入连续缓冲数组后桶内插入排序。
- `int[]` 基数排序：LSD，基数 256，异或符号位以正确处理负数。

## 泛型非比较排序的离散化

计数、桶、基数排序原理上要求整数键，泛型 `Comparable` 元素只能比较大小。泛型版本先把每个元素映射为其在全部不同值中的排名（`0..k-1`），再对排名执行经典流程，并按排名移动原始元素对象，保持稳定性。

离散化本身需要 O(n log n) 次比较，因此这三种算法的泛型版本整体复杂度为 O(n log n)，不再具有线性优势，主要用于统一接口与演示算法流程。整数数据请使用 `int[]` 重载。

## 注意事项

- 所有算法只依据 `compareTo` 判定大小与相等。`compareTo` 与 `equals` 不一致的类型（如 `BigDecimal` 的 `1.0` 与 `1.00`）按 `compareTo` 视为相等。
- 冒泡、选择、插入排序为 O(n²)，不建议用于上千个元素以上的数据。
- 生产环境中对性能敏感的通用排序仍推荐 JDK 内置的 `Arrays.sort` 与 `Collections.sort`。
- 工具类无状态，不同线程对不同容器并发排序互不影响；同一容器的并发访问需由调用方同步。

## 不支持的功能

- 自定义 `Comparator` 与降序排序。
- `null` 元素排序。
- `long[]`、`double[]` 等其他基本类型重载。
- 并行排序。
