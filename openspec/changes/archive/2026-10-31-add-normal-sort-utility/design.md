## Context

用户要求在 `org.example.simple.util.NormalSortUtils` 中实现 10 种常用排序算法，支持实现了 `Comparable` 接口的泛型 `List` 与数组，并在每个算法方法上说明稳定性、特点、复杂度与推荐场景。

其中前 7 种（冒泡、选择、插入、希尔、归并、快速、堆）是比较排序，可直接基于 `compareTo` 实现；后 3 种（计数、桶、基数）是非比较排序，经典定义依赖整数键（计数排序以值为下标、桶排序按值域划分、基数排序按数位分配），无法直接作用于只能比较大小的 `Comparable` 对象，需要设计适配方案。

## Goals / Non-Goals

**Goals:**
- 10 种算法均支持 `T[]` 与 `List<T>`（`T extends Comparable<? super T>`），按自然顺序升序原地排序。
- 各算法的稳定性与其经典定义一致，并在 Javadoc 与测试中体现。
- 计数/桶/基数排序对泛型元素给出可用且稳定的实现，同时提供 `int[]` 经典整数版本。
- 每个公共方法提供中文 Javadoc：稳定性、特点、时间复杂度（最好/平均/最坏）、空间复杂度、推荐场景。
- 无状态、线程安全（不同线程排序不同容器时互不影响）。

**Non-Goals:**
- 不支持自定义 `Comparator` 与降序参数（调用方可通过包装类型或排序后反转实现；如需要另行提出变更）。
- 不支持 `null` 元素排序（不提供 nullsFirst/nullsLast 语义）。
- 不提供 `long[]`、`double[]`、`String[]` 等其他基本类型或专用重载；整数版本仅 `int[]`。
- 不追求替代 `Arrays.sort`/`Collections.sort` 的生产性能；生产场景仍推荐 JDK 内置排序，本工具定位为规范实现与选型参考。
- 不提供并行排序。

## Decisions

### 公共 API 形态

每种算法提供 `public static <T extends Comparable<? super T>> void xxxSort(T[] array)` 与 `void xxxSort(List<T> list)`；计数、桶、基数排序额外提供 `void xxxSort(int[] array)`。方法名：`bubbleSort`、`selectionSort`、`insertionSort`、`shellSort`、`mergeSort`、`quickSort`、`heapSort`、`countingSort`、`bucketSort`、`radixSort`。

- 采用 `Comparable<? super T>` 而非 `Comparable<T>`，使父类实现 `Comparable` 的子类类型（如 `java.sql.Timestamp` 之于 `Date`）也能使用，与 JDK `Collections.sort` 签名一致。
- 返回 `void` 原地排序，与 `Arrays.sort`/`Collections.sort` 习惯一致。

### `List` 的统一处理

`List` 重载统一执行：`toArray()` → 调用对应数组算法 → 用 `ListIterator.set` 逐个写回。理由：`LinkedList` 按下标随机访问是 O(n)，直接在 `List` 上实现冒泡/堆等算法会使复杂度恶化；复制为数组后各算法复杂度与数组版本一致，额外 O(n) 空间与 `Collections.sort` 相同。不可修改列表在写回时由列表自身抛出 `UnsupportedOperationException`（元素数 ≤ 1 时直接返回不会抛出）。

### 参数校验

- 数组/列表为 `null`：抛出 `NullPointerException`（`Objects.requireNonNull`，中文消息）。
- 元素含 `null`：排序前 O(n) 扫描，发现即抛出 `NullPointerException` 并指明下标，避免排序进行到一半才因 `compareTo` 抛出导致容器处于半排序状态。
- 长度 ≤ 1：直接返回。

### 比较排序的实现选择

| 算法 | 实现要点 | 稳定 | 时间（最好/平均/最坏） | 空间 |
| --- | --- | --- | --- | --- |
| 冒泡 | 相邻交换，单趟无交换时提前结束 | 是 | O(n) / O(n²) / O(n²) | O(1) |
| 选择 | 每趟选最小值与首位交换 | 否 | O(n²) / O(n²) / O(n²) | O(1) |
| 插入 | 元素后移腾位，严格大于才后移以保持稳定 | 是 | O(n) / O(n²) / O(n²) | O(1) |
| 希尔 | Knuth 增量序列 `1, 4, 13, 40, ...` 分组插入排序 | 否 | O(n log n) / 约 O(n^1.3) / O(n^1.5) | O(1) |
| 归并 | 自顶向下递归，复用单个辅助数组；左半等于右半时取左以保持稳定；左右已有序时跳过合并 | 是 | O(n log n) / O(n log n) / O(n log n) | O(n) |
| 快速 | 随机基准 + 三路划分（处理大量重复元素）；递归较小分区、循环处理较大分区，栈深度 O(log n) | 否 | O(n log n) / O(n log n) / O(n²)（随机基准使其概率极低） | O(log n) |
| 堆 | 自底向上建大顶堆，依次将堆顶交换到末尾并下沉 | 否 | O(n log n) / O(n log n) / O(n log n) | O(1) |

- 快速排序随机基准使用 `ThreadLocalRandom`，避免对有序/逆序输入退化为 O(n²)，且线程安全无竞争。
- 泛型辅助数组通过 `Arrays.copyOf(array, n)` 创建，规避泛型数组创建限制。

### 非比较排序的泛型适配：离散化

对泛型 `T[]`，计数/桶/基数排序统一先执行离散化：
1. 复制数组并用归并排序得到有序副本，去重得到 `k` 个不同值的有序数组 `distinct`；
2. 对原数组每个元素用二分查找在 `distinct` 中定位排名 `rank ∈ [0, k)`（比较一致性依赖 `compareTo`，相等元素排名相同）；
3. 基于排名执行对应算法的经典流程，按排名把原始元素对象（而不是去重值）放回，保证相等元素保持原相对顺序（稳定）。

各算法在排名上的经典流程：
- **计数排序**：统计每个排名的出现次数 → 前缀和 → 从后向前回填，稳定。
- **桶排序**：桶数取 `max(1, min(n, k))`，按 `rank * bucketCount / k` 均匀分桶（排名天然均匀分布，避免值域倾斜导致桶失衡），与 `int[]` 版本同样采用两遍扫描放入连续缓冲数组，桶内用插入排序（稳定），按桶顺序拼接。由于 `k ≤ n`，桶数实际等于 `k`，每个桶只包含同一排名的元素，桶内插入排序退化为 O(桶大小) 的顺序扫描。
- **基数排序**：对排名做 LSD 基数排序，基数 256，每轮以稳定计数排序分配，轮数为排名所需字节数（`k ≤ 256` 时 1 轮）。

取舍：离散化本身需要 O(n log n) 比较，因此泛型版本整体复杂度为 O(n log n)，不再具有非比较排序的线性优势，仅用于保持接口一致与演示算法流程。Javadoc 必须如实说明这一点，并推荐整数场景使用 `int[]` 重载。

选择离散化而非"要求调用方传入整数键提取函数"的理由：用户要求的是基于 `Comparable` 的泛型接口，离散化只依赖 `compareTo`，无需额外参数，对任意 `Comparable` 类型都可用。

### 非比较排序的 `int[]` 经典版本

- **计数排序**：值域 `range = max - min + 1`（用 `long` 计算避免溢出），计数数组下标偏移 `min` 以支持负数；当 `range` 超过 `Integer.MAX_VALUE - 8`（JVM 数组长度上限）时抛出 `IllegalArgumentException`，提示值域过大应改用基数排序。复杂度 O(n + k)，空间 O(n + k)，稳定（对 `int` 稳定性不可观察，但实现保持稳定回填写法）。
- **桶排序**：桶数取 `n`，每桶宽度 `bucketWidth = range / n + 1`（`long` 计算），桶索引 `(value - min) / bucketWidth`；采用两遍扫描分桶：先统计每个桶的元素个数并求前缀和得到各桶在连续缓冲数组中的起始偏移，再按原顺序把元素放入所属桶区间（稳定），避免为每个桶单独分配并扩容数组以及 `Integer` 装箱；随后对每个桶区间做插入排序。平均 O(n + k)（数据均匀分布时），最坏 O(n²)（全部落入同一桶），空间 O(n + k)。
- **基数排序**：LSD，基数 256，固定 4 轮；每个值先与 `0x80000000` 异或翻转符号位，使有符号顺序与无符号字节顺序一致，从而正确处理负数与 `Integer.MIN_VALUE`。复杂度 O(d·(n + b))，d = 4、b = 256，空间 O(n + b)。

### 线程安全

工具类不持有可变静态状态；各方法仅操作入参与局部变量。并发对同一容器排序属于调用方的数据竞争问题，不在保证范围内。

## Risks / Trade-offs

- **`compareTo` 与 `equals` 不一致的类型（如 `BigDecimal` 的 `1.0` 与 `1.00`）**：所有算法仅依据 `compareTo` 判断相等，离散化后两者排名相同、按稳定性保持原顺序；行为与 `Arrays.sort` 一致，在文档中说明。
- **O(n²) 算法用于大数据量**：冒泡/选择/插入在大数组上很慢；不做长度限制，仅在 Javadoc 中给出推荐规模（约几十到上千个元素以内）。
- **`int[]` 计数排序值域过大内存占用**：值域接近 `int` 全范围时计数数组可达数 GB；超过数组上限直接抛异常，未超过但较大的场景由 Javadoc 提示改用基数排序。
- **泛型非比较排序无线性优势**：已在设计与 Javadoc 中如实说明，避免误导选型。
