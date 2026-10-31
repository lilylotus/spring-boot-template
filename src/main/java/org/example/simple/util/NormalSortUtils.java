package org.example.simple.util;

import java.util.Arrays;
import java.util.List;
import java.util.ListIterator;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/**
 * 常用排序算法工具类：提供冒泡、选择、插入、希尔、归并、快速、堆、计数、桶、基数 10 种经典排序算法。
 * <p>
 * 每种算法都提供 {@code T[]} 与 {@code List<T>} 两个重载（{@code T extends Comparable<? super T>}），
 * 统一按元素自然顺序升序、原地排序；计数、桶、基数排序额外提供 {@code int[]} 经典整数版本。
 * <p>
 * 算法总览（n 为元素个数，k 为不同值个数或值域大小，d 为数位轮数）：
 * <table class="striped">
 *   <caption>排序算法对比</caption>
 *   <tr><th>算法</th><th>稳定</th><th>最好</th><th>平均</th><th>最坏</th><th>额外空间</th></tr>
 *   <tr><td>冒泡</td><td>是</td><td>O(n)</td><td>O(n²)</td><td>O(n²)</td><td>O(1)</td></tr>
 *   <tr><td>选择</td><td>否</td><td>O(n²)</td><td>O(n²)</td><td>O(n²)</td><td>O(1)</td></tr>
 *   <tr><td>插入</td><td>是</td><td>O(n)</td><td>O(n²)</td><td>O(n²)</td><td>O(1)</td></tr>
 *   <tr><td>希尔</td><td>否</td><td>O(n log n)</td><td>约 O(n^1.3)</td><td>O(n^1.5)</td><td>O(1)</td></tr>
 *   <tr><td>归并</td><td>是</td><td>O(n log n)</td><td>O(n log n)</td><td>O(n log n)</td><td>O(n)</td></tr>
 *   <tr><td>快速</td><td>否</td><td>O(n log n)</td><td>O(n log n)</td><td>O(n²)</td><td>O(log n)</td></tr>
 *   <tr><td>堆</td><td>否</td><td>O(n log n)</td><td>O(n log n)</td><td>O(n log n)</td><td>O(1)</td></tr>
 *   <tr><td>计数（int[]）</td><td>是</td><td>O(n + k)</td><td>O(n + k)</td><td>O(n + k)</td><td>O(n + k)</td></tr>
 *   <tr><td>桶（int[]）</td><td>是</td><td>O(n)</td><td>O(n + k)</td><td>O(n²)</td><td>O(n)</td></tr>
 *   <tr><td>基数（int[]）</td><td>是</td><td>O(d·n)</td><td>O(d·n)</td><td>O(d·n)</td><td>O(n)</td></tr>
 * </table>
 * <p>
 * 选型建议：小规模或近乎有序的数据用插入排序；通用大规模数据用快速排序（不要求稳定）或归并排序（要求稳定）；
 * 内存受限且要求最坏 O(n log n) 用堆排序；值域较小的整数用计数排序；均匀分布的整数用桶排序；
 * 值域很大的整数用基数排序。冒泡与选择排序主要用于教学演示。生产环境中对性能敏感的通用排序仍推荐
 * JDK 内置的 {@link Arrays#sort(Object[])} 与 {@link java.util.Collections#sort(List)}。
 * <p>
 * 计数、桶、基数排序是非比较排序，其泛型版本先将元素"离散化"为其在全部不同值中的排名（{@code 0..k-1}），
 * 再对排名执行经典流程。离散化本身需要 O(n log n) 次比较，因此泛型版本整体复杂度为 O(n log n)，
 * 不再具有线性优势；整数场景请使用 {@code int[]} 重载。
 * <p>
 * 参数约定：数组或列表为 {@code null}、或包含 {@code null} 元素时抛出 {@link NullPointerException}，
 * 且在排序开始前完成校验，不会让容器处于半排序状态；元素个数不超过 1 时直接返回。
 * 所有算法仅依据 {@code compareTo} 判定大小与相等，{@code compareTo} 与 {@code equals} 不一致的类型
 * （如 {@link java.math.BigDecimal}）按 {@code compareTo} 处理。
 * <p>
 * 本工具类无状态，不同线程对不同容器并发排序互不影响；对同一容器的并发访问需由调用方自行同步。
 */
public final class NormalSortUtils {

    /** JVM 可安全分配的数组最大长度，部分虚拟机会在数组头部保留若干字。 */
    private static final int MAX_ARRAY_LENGTH = Integer.MAX_VALUE - 8;

    /** 基数排序每轮使用的基数（按字节分配）。 */
    private static final int RADIX = 256;

    /** 提取单个字节数位的掩码。 */
    private static final int RADIX_MASK = RADIX - 1;

    /** 基数排序每轮处理的位数。 */
    private static final int BITS_PER_PASS = 8;

    /** {@code int} 基数排序的固定轮数（32 位 / 8 位）。 */
    private static final int INT_RADIX_PASSES = Integer.SIZE / BITS_PER_PASS;

    /** {@code int} 符号位掩码：异或后使有符号顺序与无符号字节顺序一致。 */
    private static final int SIGN_BIT = Integer.MIN_VALUE;

    /** 工具类不允许实例化。 */
    private NormalSortUtils() {
    }

    // ==================== 冒泡排序 ====================

    /**
     * 冒泡排序：反复比较相邻元素，逆序则交换，每趟把当前未排序区间的最大值"冒泡"到末尾。
     * <ul>
     *   <li>稳定性：稳定（相邻元素严格大于才交换）。</li>
     *   <li>特点：实现最简单；某一趟没有发生交换即提前结束，因此对已有序数据只需一趟扫描。交换次数多，效率低。</li>
     *   <li>时间复杂度：最好 O(n)（已有序），平均 O(n²)，最坏 O(n²)（逆序）。</li>
     *   <li>空间复杂度：O(1)，原地排序。</li>
     *   <li>推荐场景：教学演示，或几十个元素以内、基本有序的小数据；不推荐用于大规模数据。</li>
     * </ul>
     *
     * @param array 待排序数组，排序后按自然顺序升序
     * @param <T>   元素类型
     * @throws NullPointerException 数组为 {@code null} 或包含 {@code null} 元素
     */
    public static <T extends Comparable<? super T>> void bubbleSort(T[] array) {
        if (prepareArray(array)) {
            doBubbleSort(array);
        }
    }

    /**
     * 冒泡排序列表版本：稳定，最好 O(n)、平均与最坏 O(n²)，推荐场景同 {@link #bubbleSort(Comparable[])}。
     * 列表先复制为数组排序，再通过 {@link ListIterator#set(Object)} 写回，额外占用 O(n) 空间。
     *
     * @param list 待排序列表，必须支持 {@code set} 操作
     * @param <T>  元素类型
     * @throws NullPointerException          列表为 {@code null} 或包含 {@code null} 元素
     * @throws UnsupportedOperationException 列表不可修改且元素个数大于 1
     */
    public static <T extends Comparable<? super T>> void bubbleSort(List<T> list) {
        sortList(list, NormalSortUtils::doBubbleSort);
    }

    /** 冒泡排序核心实现，调用前已完成参数校验。 */
    private static <T extends Comparable<? super T>> void doBubbleSort(T[] a) {
        for (int end = a.length - 1; end > 0; end--) {
            boolean swapped = false;
            for (int i = 0; i < end; i++) {
                if (a[i].compareTo(a[i + 1]) > 0) {
                    swap(a, i, i + 1);
                    swapped = true;
                }
            }
            // 一整趟没有交换说明已经有序，提前结束
            if (!swapped) {
                return;
            }
        }
    }

    // ==================== 选择排序 ====================

    /**
     * 选择排序：每趟从未排序区间选出最小元素，与未排序区间的首个元素交换。
     * <ul>
     *   <li>稳定性：不稳定（长距离交换可能越过相等元素，如 {@code [2a, 2b, 1]} 排序后为 {@code [1, 2b, 2a]}）。</li>
     *   <li>特点：比较次数固定为 n(n-1)/2，与输入是否有序无关；交换次数最多 n-1 次，是交换次数最少的简单排序。</li>
     *   <li>时间复杂度：最好、平均、最坏均为 O(n²)。</li>
     *   <li>空间复杂度：O(1)，原地排序。</li>
     *   <li>推荐场景：数据量小且元素"写入/交换"代价远高于比较代价的场景；一般仅用于教学演示。</li>
     * </ul>
     *
     * @param array 待排序数组，排序后按自然顺序升序
     * @param <T>   元素类型
     * @throws NullPointerException 数组为 {@code null} 或包含 {@code null} 元素
     */
    public static <T extends Comparable<? super T>> void selectionSort(T[] array) {
        if (prepareArray(array)) {
            doSelectionSort(array);
        }
    }

    /**
     * 选择排序列表版本：不稳定，时间复杂度恒为 O(n²)，推荐场景同 {@link #selectionSort(Comparable[])}。
     * 列表先复制为数组排序，再通过 {@link ListIterator#set(Object)} 写回，额外占用 O(n) 空间。
     *
     * @param list 待排序列表，必须支持 {@code set} 操作
     * @param <T>  元素类型
     * @throws NullPointerException          列表为 {@code null} 或包含 {@code null} 元素
     * @throws UnsupportedOperationException 列表不可修改且元素个数大于 1
     */
    public static <T extends Comparable<? super T>> void selectionSort(List<T> list) {
        sortList(list, NormalSortUtils::doSelectionSort);
    }

    /** 选择排序核心实现，调用前已完成参数校验。 */
    private static <T extends Comparable<? super T>> void doSelectionSort(T[] a) {
        int n = a.length;
        for (int i = 0; i < n - 1; i++) {
            int minIndex = i;
            for (int j = i + 1; j < n; j++) {
                if (a[j].compareTo(a[minIndex]) < 0) {
                    minIndex = j;
                }
            }
            if (minIndex != i) {
                swap(a, i, minIndex);
            }
        }
    }

    // ==================== 插入排序 ====================

    /**
     * 插入排序：把每个元素向前插入到已排序区间的正确位置，比它大的元素依次后移一位。
     * <ul>
     *   <li>稳定性：稳定（只有严格大于当前元素的才后移）。</li>
     *   <li>特点：自适应，数据越有序越快，运行时间与逆序对数量成正比；常作为归并、快速排序在小区间上的优化手段，
     *       也是希尔排序与桶排序的子过程。</li>
     *   <li>时间复杂度：最好 O(n)（已有序），平均 O(n²)，最坏 O(n²)（逆序）。</li>
     *   <li>空间复杂度：O(1)，原地排序。</li>
     *   <li>推荐场景：小规模数据（几十到几百个元素）、基本有序的数据，或向有序数据中追加少量新元素后的重排。</li>
     * </ul>
     *
     * @param array 待排序数组，排序后按自然顺序升序
     * @param <T>   元素类型
     * @throws NullPointerException 数组为 {@code null} 或包含 {@code null} 元素
     */
    public static <T extends Comparable<? super T>> void insertionSort(T[] array) {
        if (prepareArray(array)) {
            doInsertionSort(array);
        }
    }

    /**
     * 插入排序列表版本：稳定，最好 O(n)、平均与最坏 O(n²)，推荐场景同 {@link #insertionSort(Comparable[])}。
     * 列表先复制为数组排序，再通过 {@link ListIterator#set(Object)} 写回，额外占用 O(n) 空间。
     *
     * @param list 待排序列表，必须支持 {@code set} 操作
     * @param <T>  元素类型
     * @throws NullPointerException          列表为 {@code null} 或包含 {@code null} 元素
     * @throws UnsupportedOperationException 列表不可修改且元素个数大于 1
     */
    public static <T extends Comparable<? super T>> void insertionSort(List<T> list) {
        sortList(list, NormalSortUtils::doInsertionSort);
    }

    /** 插入排序核心实现，调用前已完成参数校验。 */
    private static <T extends Comparable<? super T>> void doInsertionSort(T[] a) {
        insertionSortRange(a, 0, a.length);
    }

    /** 对数组 {@code [from, to)} 区间做稳定的插入排序。 */
    private static <T extends Comparable<? super T>> void insertionSortRange(T[] a, int from, int to) {
        for (int i = from + 1; i < to; i++) {
            T current = a[i];
            int j = i - 1;
            while (j >= from && a[j].compareTo(current) > 0) {
                a[j + 1] = a[j];
                j--;
            }
            a[j + 1] = current;
        }
    }

    // ==================== 希尔排序 ====================

    /**
     * 希尔排序：按递减的增量把数组分成若干间隔子序列分别做插入排序，最后以增量 1 完成一次普通插入排序。
     * 本实现使用 Knuth 增量序列 {@code 1, 4, 13, 40, 121, ...}（h = 3h + 1）。
     * <ul>
     *   <li>稳定性：不稳定（相等元素可能被分到不同子序列中各自移动）。</li>
     *   <li>特点：插入排序的改进版，大增量阶段让元素快速长距离移动，小增量阶段数据已基本有序；
     *       原地、代码短、无递归，性能明显优于 O(n²) 的简单排序。复杂度依赖增量序列，尚无精确的理论结果。</li>
     *   <li>时间复杂度：最好 O(n log n)，平均约 O(n^1.3)，最坏 O(n^1.5)（Knuth 序列）。</li>
     *   <li>空间复杂度：O(1)，原地排序。</li>
     *   <li>推荐场景：中等规模数据（数千到数万）、不要求稳定、内存或栈空间受限（如嵌入式环境）的场景。</li>
     * </ul>
     *
     * @param array 待排序数组，排序后按自然顺序升序
     * @param <T>   元素类型
     * @throws NullPointerException 数组为 {@code null} 或包含 {@code null} 元素
     */
    public static <T extends Comparable<? super T>> void shellSort(T[] array) {
        if (prepareArray(array)) {
            doShellSort(array);
        }
    }

    /**
     * 希尔排序列表版本：不稳定，平均约 O(n^1.3)、最坏 O(n^1.5)，推荐场景同 {@link #shellSort(Comparable[])}。
     * 列表先复制为数组排序，再通过 {@link ListIterator#set(Object)} 写回，额外占用 O(n) 空间。
     *
     * @param list 待排序列表，必须支持 {@code set} 操作
     * @param <T>  元素类型
     * @throws NullPointerException          列表为 {@code null} 或包含 {@code null} 元素
     * @throws UnsupportedOperationException 列表不可修改且元素个数大于 1
     */
    public static <T extends Comparable<? super T>> void shellSort(List<T> list) {
        sortList(list, NormalSortUtils::doShellSort);
    }

    /** 希尔排序核心实现，调用前已完成参数校验。 */
    private static <T extends Comparable<? super T>> void doShellSort(T[] a) {
        int n = a.length;
        int gap = 1;
        // 生成不超过 n/3 的最大 Knuth 增量
        while (gap < n / 3) {
            gap = gap * 3 + 1;
        }
        while (gap >= 1) {
            for (int i = gap; i < n; i++) {
                T current = a[i];
                int j = i;
                while (j >= gap && a[j - gap].compareTo(current) > 0) {
                    a[j] = a[j - gap];
                    j -= gap;
                }
                a[j] = current;
            }
            gap /= 3;
        }
    }

    // ==================== 归并排序 ====================

    /**
     * 归并排序：分治思想，递归地把数组对半拆分并分别排序，再把两个有序半区合并为一个有序区间。
     * 本实现复用同一个辅助数组；若左半区最大值不大于右半区最小值则跳过合并。
     * <ul>
     *   <li>稳定性：稳定（合并时两侧相等取左侧元素）。</li>
     *   <li>特点：任何输入下都保证 O(n log n)，性能可预测；需要 O(n) 额外空间；适合链表和外部排序（大文件分块归并）。
     *       JDK 对象数组排序使用的 TimSort 即是归并排序的改进版。</li>
     *   <li>时间复杂度：最好 O(n log n)（已有序时因跳过合并只需 O(n) 次比较），平均 O(n log n)，最坏 O(n log n)。</li>
     *   <li>空间复杂度：O(n) 辅助数组 + O(log n) 递归栈。</li>
     *   <li>推荐场景：要求稳定排序的大规模数据（如先按时间再按类别的多关键字排序）、需要最坏情况性能保证的场景。</li>
     * </ul>
     *
     * @param array 待排序数组，排序后按自然顺序升序
     * @param <T>   元素类型
     * @throws NullPointerException 数组为 {@code null} 或包含 {@code null} 元素
     */
    public static <T extends Comparable<? super T>> void mergeSort(T[] array) {
        if (prepareArray(array)) {
            doMergeSort(array);
        }
    }

    /**
     * 归并排序列表版本：稳定，时间复杂度恒为 O(n log n)，推荐场景同 {@link #mergeSort(Comparable[])}。
     * 列表先复制为数组排序，再通过 {@link ListIterator#set(Object)} 写回，额外占用 O(n) 空间。
     *
     * @param list 待排序列表，必须支持 {@code set} 操作
     * @param <T>  元素类型
     * @throws NullPointerException          列表为 {@code null} 或包含 {@code null} 元素
     * @throws UnsupportedOperationException 列表不可修改且元素个数大于 1
     */
    public static <T extends Comparable<? super T>> void mergeSort(List<T> list) {
        sortList(list, NormalSortUtils::doMergeSort);
    }

    /** 归并排序核心实现，调用前已完成参数校验。 */
    private static <T extends Comparable<? super T>> void doMergeSort(T[] a) {
        T[] aux = Arrays.copyOf(a, a.length);
        mergeSortRange(a, aux, 0, a.length - 1);
    }

    /** 递归排序闭区间 {@code [lo, hi]}。 */
    private static <T extends Comparable<? super T>> void mergeSortRange(
            T[] a, T[] aux,
            int lo, int hi) {
        if (lo >= hi) {
            return;
        }
        int mid = (lo + hi) >>> 1;
        mergeSortRange(a, aux, lo, mid);
        mergeSortRange(a, aux, mid + 1, hi);
        // 两个半区已整体有序，无需合并
        if (a[mid].compareTo(a[mid + 1]) <= 0) {
            return;
        }
        merge(a, aux, lo, mid, hi);
    }

    /** 合并有序区间 {@code [lo, mid]} 与 {@code [mid + 1, hi]}。 */
    private static <T extends Comparable<? super T>> void merge(
            T[] a, T[] aux,
            int lo, int mid,
            int hi) {
        System.arraycopy(a, lo, aux, lo, hi - lo + 1);
        int i = lo;
        int j = mid + 1;
        for (int k = lo; k <= hi; k++) {
            if (i > mid) {
                a[k] = aux[j++];
            } else if (j > hi) {
                a[k] = aux[i++];
            } else if (aux[j].compareTo(aux[i]) < 0) {
                // 只有右侧严格更小才取右侧，相等时取左侧以保持稳定
                a[k] = aux[j++];
            } else {
                a[k] = aux[i++];
            }
        }
    }

    // ==================== 快速排序 ====================

    /**
     * 快速排序：分治思想，选取基准元素把数组划分为小于、等于、大于基准的三部分，再递归排序两侧。
     * 本实现使用随机基准与三路划分（Dijkstra 荷兰国旗划分），并对较小分区递归、较大分区循环。
     * <ul>
     *   <li>稳定性：不稳定（划分时的长距离交换会打乱相等元素的相对顺序）。</li>
     *   <li>特点：平均性能最好的比较排序之一，常数因子小、缓存友好、原地排序。随机基准避免了对有序或逆序输入
     *       退化；三路划分让大量重复元素的数据接近 O(n)；先处理较小分区使递归栈深度不超过 O(log n)。</li>
     *   <li>时间复杂度：最好 O(n log n)（全部相等时为 O(n)），平均 O(n log n)，最坏 O(n²)（随机基准下概率极低）。</li>
     *   <li>空间复杂度：O(log n) 递归栈。</li>
     *   <li>推荐场景：不要求稳定的通用大规模数据排序，尤其是随机分布或包含大量重复值的数据。</li>
     * </ul>
     *
     * @param array 待排序数组，排序后按自然顺序升序
     * @param <T>   元素类型
     * @throws NullPointerException 数组为 {@code null} 或包含 {@code null} 元素
     */
    public static <T extends Comparable<? super T>> void quickSort(T[] array) {
        if (prepareArray(array)) {
            doQuickSort(array);
        }
    }

    /**
     * 快速排序列表版本：不稳定，平均 O(n log n)、最坏 O(n²)，推荐场景同 {@link #quickSort(Comparable[])}。
     * 列表先复制为数组排序，再通过 {@link ListIterator#set(Object)} 写回，额外占用 O(n) 空间。
     *
     * @param list 待排序列表，必须支持 {@code set} 操作
     * @param <T>  元素类型
     * @throws NullPointerException          列表为 {@code null} 或包含 {@code null} 元素
     * @throws UnsupportedOperationException 列表不可修改且元素个数大于 1
     */
    public static <T extends Comparable<? super T>> void quickSort(List<T> list) {
        sortList(list, NormalSortUtils::doQuickSort);
    }

    /** 快速排序核心实现，调用前已完成参数校验。 */
    private static <T extends Comparable<? super T>> void doQuickSort(T[] a) {
        quickSortRange(a, 0, a.length - 1);
    }

    /** 对闭区间 {@code [lo, hi]} 做三路快速排序：较小分区递归，较大分区在循环中继续处理。 */
    private static <T extends Comparable<? super T>> void quickSortRange(T[] a, int lo, int hi) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        while (lo < hi) {
            // 随机基准换到区间首位
            swap(a, lo, lo + random.nextInt(hi - lo + 1));
            T pivot = a[lo];
            // 划分后：[lo, lt) < pivot，[lt, gt] == pivot，(gt, hi] > pivot
            int lt = lo;
            int gt = hi;
            int i = lo + 1;
            while (i <= gt) {
                int cmp = a[i].compareTo(pivot);
                if (cmp < 0) {
                    swap(a, lt++, i++);
                } else if (cmp > 0) {
                    swap(a, i, gt--);
                } else {
                    i++;
                }
            }
            if (lt - lo < hi - gt) {
                quickSortRange(a, lo, lt - 1);
                lo = gt + 1;
            } else {
                quickSortRange(a, gt + 1, hi);
                hi = lt - 1;
            }
        }
    }

    // ==================== 堆排序 ====================

    /**
     * 堆排序：先把数组自底向上构建为大顶堆，再反复把堆顶（当前最大值）交换到未排序区间末尾并下沉新堆顶。
     * <ul>
     *   <li>稳定性：不稳定（堆顶与末尾的交换会打乱相等元素的相对顺序）。</li>
     *   <li>特点：原地排序且最坏情况仍为 O(n log n)，不需要递归；但访问模式跳跃、缓存不友好，
     *       实际运行通常比快速排序慢。堆结构本身也适合"取前 K 大/小"的 Top-K 问题。</li>
     *   <li>时间复杂度：建堆 O(n)；最好、平均、最坏均为 O(n log n)。</li>
     *   <li>空间复杂度：O(1)，原地排序。</li>
     *   <li>推荐场景：内存受限、不能接受快速排序最坏 O(n²)、且不要求稳定的场景；对实时性有严格上界要求的系统。</li>
     * </ul>
     *
     * @param array 待排序数组，排序后按自然顺序升序
     * @param <T>   元素类型
     * @throws NullPointerException 数组为 {@code null} 或包含 {@code null} 元素
     */
    public static <T extends Comparable<? super T>> void heapSort(T[] array) {
        if (prepareArray(array)) {
            doHeapSort(array);
        }
    }

    /**
     * 堆排序列表版本：不稳定，时间复杂度恒为 O(n log n)，推荐场景同 {@link #heapSort(Comparable[])}。
     * 列表先复制为数组排序，再通过 {@link ListIterator#set(Object)} 写回，额外占用 O(n) 空间。
     *
     * @param list 待排序列表，必须支持 {@code set} 操作
     * @param <T>  元素类型
     * @throws NullPointerException          列表为 {@code null} 或包含 {@code null} 元素
     * @throws UnsupportedOperationException 列表不可修改且元素个数大于 1
     */
    public static <T extends Comparable<? super T>> void heapSort(List<T> list) {
        sortList(list, NormalSortUtils::doHeapSort);
    }

    /** 堆排序核心实现，调用前已完成参数校验。 */
    private static <T extends Comparable<? super T>> void doHeapSort(T[] a) {
        int n = a.length;
        // 从最后一个非叶子节点开始自底向上建大顶堆
        for (int i = n / 2 - 1; i >= 0; i--) {
            siftDown(a, i, n);
        }
        for (int end = n - 1; end > 0; end--) {
            swap(a, 0, end);
            siftDown(a, 0, end);
        }
    }

    /** 在长度为 {@code size} 的堆中把下标 {@code index} 处的元素下沉到合适位置。 */
    private static <T extends Comparable<? super T>> void siftDown(
            T[] a, int index,
            int size) {
        T value = a[index];
        int half = size >>> 1;
        // index < half 保证存在左子节点，且 2 * index + 1 不会整型溢出
        while (index < half) {
            int child = 2 * index + 1;
            if (child + 1 < size && a[child + 1].compareTo(a[child]) > 0) {
                child++;
            }
            if (a[child].compareTo(value) <= 0) {
                break;
            }
            a[index] = a[child];
            index = child;
        }
        a[index] = value;
    }

    // ==================== 计数排序 ====================

    /**
     * 计数排序（泛型版本）：先把元素离散化为其在全部不同值中的排名（{@code 0..k-1}），统计每个排名的出现次数，
     * 求前缀和得到每个排名的结束位置，再从后向前把原始元素回填到输出位置。
     * <ul>
     *   <li>稳定性：稳定（从后向前回填）。</li>
     *   <li>特点：经典计数排序是非比较排序，要求键为小范围整数。泛型元素只能比较大小，需先离散化，
     *       离散化需要 O(n log n) 比较，因此泛型版本不具备线性优势，主要用于统一接口与演示算法流程。</li>
     *   <li>时间复杂度：最好、平均、最坏均为 O(n log n)（离散化主导，计数阶段为 O(n + k)）。</li>
     *   <li>空间复杂度：O(n + k)，k 为不同值个数。</li>
     *   <li>推荐场景：不同值很少的枚举型数据（如状态、等级）且要求稳定；整数数据请使用 {@link #countingSort(int[])}。</li>
     * </ul>
     *
     * @param array 待排序数组，排序后按自然顺序升序
     * @param <T>   元素类型
     * @throws NullPointerException 数组为 {@code null} 或包含 {@code null} 元素
     */
    public static <T extends Comparable<? super T>> void countingSort(T[] array) {
        if (prepareArray(array)) {
            doCountingSort(array);
        }
    }

    /**
     * 计数排序列表版本：稳定，因离散化整体为 O(n log n)，推荐场景同 {@link #countingSort(Comparable[])}。
     * 列表先复制为数组排序，再通过 {@link ListIterator#set(Object)} 写回，额外占用 O(n) 空间。
     *
     * @param list 待排序列表，必须支持 {@code set} 操作
     * @param <T>  元素类型
     * @throws NullPointerException          列表为 {@code null} 或包含 {@code null} 元素
     * @throws UnsupportedOperationException 列表不可修改且元素个数大于 1
     */
    public static <T extends Comparable<? super T>> void countingSort(List<T> list) {
        sortList(list, NormalSortUtils::doCountingSort);
    }

    /**
     * 计数排序（{@code int[]} 经典版本）：统计每个值的出现次数（计数数组下标偏移最小值以支持负数），
     * 求前缀和后从后向前回填。
     * <ul>
     *   <li>稳定性：稳定（从后向前回填；对 {@code int} 而言稳定性不可观察，但作为基数排序子过程时必须稳定）。</li>
     *   <li>特点：非比较排序，突破比较排序 O(n log n) 下界；速度极快但内存与值域 k 成正比，值域大时不适用。</li>
     *   <li>时间复杂度：最好、平均、最坏均为 O(n + k)，k = max - min + 1。</li>
     *   <li>空间复杂度：O(n + k)。</li>
     *   <li>推荐场景：值域较小（k 与 n 同数量级或更小）的整数，如年龄、考试分数、0-255 像素值；
     *       值域很大时请改用 {@link #radixSort(int[])}。</li>
     * </ul>
     *
     * @param array 待排序数组，排序后升序
     * @throws NullPointerException     数组为 {@code null}
     * @throws IllegalArgumentException 值域 {@code max - min + 1} 超过 JVM 数组长度上限，此时数组内容保持不变
     */
    public static void countingSort(int[] array) {
        Objects.requireNonNull(array, "待排序数组不能为 null");
        int n = array.length;
        if (n <= 1) {
            return;
        }
        int min = array[0];
        int max = array[0];
        for (int value : array) {
            min = Math.min(min, value);
            max = Math.max(max, value);
        }
        long range = (long) max - min + 1;
        if (range > MAX_ARRAY_LENGTH) {
            throw new IllegalArgumentException("计数排序值域过大（" + range + "），超过数组长度上限，请改用基数排序");
        }
        int[] count = new int[(int) range];
        // 值域不超过 int 上限时，int 减法即使中间溢出，结果也等于真实差值
        for (int value : array) {
            count[value - min]++;
        }
        for (int i = 1; i < count.length; i++) {
            count[i] += count[i - 1];
        }
        int[] output = new int[n];
        for (int i = n - 1; i >= 0; i--) {
            output[--count[array[i] - min]] = array[i];
        }
        System.arraycopy(output, 0, array, 0, n);
    }

    /** 泛型计数排序核心实现，调用前已完成参数校验。 */
    private static <T extends Comparable<? super T>> void doCountingSort(T[] a) {
        int n = a.length;
        Ranks ranks = toRanks(a);
        int[] count = new int[ranks.distinctCount()];
        for (int rank : ranks.values()) {
            count[rank]++;
        }
        for (int i = 1; i < count.length; i++) {
            count[i] += count[i - 1];
        }
        T[] output = Arrays.copyOf(a, n);
        for (int i = n - 1; i >= 0; i--) {
            output[--count[ranks.values()[i]]] = a[i];
        }
        System.arraycopy(output, 0, a, 0, n);
    }

    // ==================== 桶排序 ====================

    /**
     * 桶排序（泛型版本）：先把元素离散化为排名（{@code 0..k-1}），按排名均匀映射到若干个桶，
     * 桶内做插入排序，再按桶顺序依次拼接。
     * <ul>
     *   <li>稳定性：稳定（按原顺序分桶，桶内插入排序稳定）。</li>
     *   <li>特点：经典桶排序依赖"数据在值域内均匀分布"的假设。泛型元素离散化后排名天然均匀、不会出现桶失衡，
     *       但离散化本身需要 O(n log n) 比较，因此泛型版本不具备线性优势，主要用于统一接口与演示算法流程。
     *       由于不同值个数 k 不超过 n，桶数取 k，每个桶只含同一排名的元素。</li>
     *   <li>时间复杂度：最好、平均、最坏均为 O(n log n)（离散化主导，分桶与桶内排序为 O(n)）。</li>
     *   <li>空间复杂度：O(n + k)。</li>
     *   <li>推荐场景：需要稳定且希望演示分桶思想的通用数据；整数数据请使用 {@link #bucketSort(int[])}。</li>
     * </ul>
     *
     * @param array 待排序数组，排序后按自然顺序升序
     * @param <T>   元素类型
     * @throws NullPointerException 数组为 {@code null} 或包含 {@code null} 元素
     */
    public static <T extends Comparable<? super T>> void bucketSort(T[] array) {
        if (prepareArray(array)) {
            doBucketSort(array);
        }
    }

    /**
     * 桶排序列表版本：稳定，因离散化整体为 O(n log n)，推荐场景同 {@link #bucketSort(Comparable[])}。
     * 列表先复制为数组排序，再通过 {@link ListIterator#set(Object)} 写回，额外占用 O(n) 空间。
     *
     * @param list 待排序列表，必须支持 {@code set} 操作
     * @param <T>  元素类型
     * @throws NullPointerException          列表为 {@code null} 或包含 {@code null} 元素
     * @throws UnsupportedOperationException 列表不可修改且元素个数大于 1
     */
    public static <T extends Comparable<? super T>> void bucketSort(List<T> list) {
        sortList(list, NormalSortUtils::doBucketSort);
    }

    /**
     * 桶排序（{@code int[]} 经典版本）：桶数取 n，按值域等宽划分桶（桶宽 = 值域 / n + 1），
     * 两遍扫描把元素按原顺序放入连续缓冲数组中的各桶区间，再对每个桶做插入排序。
     * <ul>
     *   <li>稳定性：稳定（按原顺序分桶，桶内插入排序稳定）。</li>
     *   <li>特点：非比较的分配式排序，数据均匀分布时每个桶只有常数个元素，接近线性；
     *       数据高度集中（大部分落入同一个桶）时退化为插入排序。</li>
     *   <li>时间复杂度：最好 O(n)，平均 O(n + k)（数据均匀分布时），最坏 O(n²)（全部落入同一个桶）。</li>
     *   <li>空间复杂度：O(n)（桶计数数组与缓冲数组）。</li>
     *   <li>推荐场景：在值域内大致均匀分布的整数，如随机生成的 ID、均匀采样的测量值；分布严重倾斜时不推荐。</li>
     * </ul>
     *
     * @param array 待排序数组，排序后升序
     * @throws NullPointerException 数组为 {@code null}
     */
    public static void bucketSort(int[] array) {
        Objects.requireNonNull(array, "待排序数组不能为 null");
        int n = array.length;
        if (n <= 1) {
            return;
        }
        int min = array[0];
        int max = array[0];
        for (int value : array) {
            min = Math.min(min, value);
            max = Math.max(max, value);
        }
        if (min == max) {
            return;
        }
        // 用 long 计算值域与桶宽，避免 Integer.MIN_VALUE 与 Integer.MAX_VALUE 同时存在时溢出
        long range = (long) max - min + 1;
        long bucketWidth = range / n + 1;
        // start[b] 先记录桶 b 的元素个数，前缀和后变为桶 b 在缓冲数组中的起始偏移
        int[] start = new int[n + 1];
        for (int value : array) {
            start[bucketIndex(value, min, bucketWidth) + 1]++;
        }
        for (int b = 1; b <= n; b++) {
            start[b] += start[b - 1];
        }
        int[] next = Arrays.copyOf(start, n);
        int[] output = new int[n];
        for (int value : array) {
            output[next[bucketIndex(value, min, bucketWidth)]++] = value;
        }
        for (int b = 0; b < n; b++) {
            insertionSortRange(output, start[b], start[b + 1]);
        }
        System.arraycopy(output, 0, array, 0, n);
    }

    /** 计算整数所属的桶下标。 */
    private static int bucketIndex(
            int value, int min,
            long bucketWidth) {
        return (int) (((long) value - min) / bucketWidth);
    }

    /** 对 {@code int} 数组 {@code [from, to)} 区间做插入排序。 */
    private static void insertionSortRange(int[] a, int from, int to) {
        for (int i = from + 1; i < to; i++) {
            int current = a[i];
            int j = i - 1;
            while (j >= from && a[j] > current) {
                a[j + 1] = a[j];
                j--;
            }
            a[j + 1] = current;
        }
    }

    /** 泛型桶排序核心实现，调用前已完成参数校验。 */
    private static <T extends Comparable<? super T>> void doBucketSort(T[] a) {
        int n = a.length;
        Ranks ranks = toRanks(a);
        int distinctCount = ranks.distinctCount();
        int bucketCount = Math.max(1, Math.min(n, distinctCount));
        int[] bucketOf = new int[n];
        int[] start = new int[bucketCount + 1];
        for (int i = 0; i < n; i++) {
            // 排名在 [0, k) 内均匀分布，按比例映射到桶，long 运算避免乘法溢出
            bucketOf[i] = (int) ((long) ranks.values()[i] * bucketCount / distinctCount);
            start[bucketOf[i] + 1]++;
        }
        for (int b = 1; b <= bucketCount; b++) {
            start[b] += start[b - 1];
        }
        int[] next = Arrays.copyOf(start, bucketCount);
        T[] output = Arrays.copyOf(a, n);
        for (int i = 0; i < n; i++) {
            output[next[bucketOf[i]]++] = a[i];
        }
        for (int b = 0; b < bucketCount; b++) {
            insertionSortRange(output, start[b], start[b + 1]);
        }
        System.arraycopy(output, 0, a, 0, n);
    }

    // ==================== 基数排序 ====================

    /**
     * 基数排序（泛型版本）：先把元素离散化为排名（{@code 0..k-1}），再对排名做 LSD（从低位到高位）基数排序，
     * 基数为 256，每轮按一个字节做稳定的计数排序分配，轮数为排名所需的字节数。
     * <ul>
     *   <li>稳定性：稳定（每轮分配都是稳定的计数排序）。</li>
     *   <li>特点：经典基数排序要求键可按数位拆分（整数、定长字符串）。泛型元素只能比较大小，需先离散化，
     *       离散化需要 O(n log n) 比较，因此泛型版本不具备线性优势，主要用于统一接口与演示算法流程。</li>
     *   <li>时间复杂度：最好、平均、最坏均为 O(n log n)（离散化主导，分配阶段为 O(d·(n + 256))，d ≤ 4）。</li>
     *   <li>空间复杂度：O(n)。</li>
     *   <li>推荐场景：需要稳定且希望演示按位分配思想的通用数据；整数数据请使用 {@link #radixSort(int[])}。</li>
     * </ul>
     *
     * @param array 待排序数组，排序后按自然顺序升序
     * @param <T>   元素类型
     * @throws NullPointerException 数组为 {@code null} 或包含 {@code null} 元素
     */
    public static <T extends Comparable<? super T>> void radixSort(T[] array) {
        if (prepareArray(array)) {
            doRadixSort(array);
        }
    }

    /**
     * 基数排序列表版本：稳定，因离散化整体为 O(n log n)，推荐场景同 {@link #radixSort(Comparable[])}。
     * 列表先复制为数组排序，再通过 {@link ListIterator#set(Object)} 写回，额外占用 O(n) 空间。
     *
     * @param list 待排序列表，必须支持 {@code set} 操作
     * @param <T>  元素类型
     * @throws NullPointerException          列表为 {@code null} 或包含 {@code null} 元素
     * @throws UnsupportedOperationException 列表不可修改且元素个数大于 1
     */
    public static <T extends Comparable<? super T>> void radixSort(List<T> list) {
        sortList(list, NormalSortUtils::doRadixSort);
    }

    /**
     * 基数排序（{@code int[]} 经典版本）：LSD 基数排序，基数 256，固定 4 轮，每轮按一个字节做稳定的计数排序分配。
     * 每个值先与符号位异或，使负数排在正数之前，从而正确处理负数与 {@link Integer#MIN_VALUE}。
     * <ul>
     *   <li>稳定性：稳定（每轮分配都是稳定的计数排序，这是 LSD 基数排序正确性的前提）。</li>
     *   <li>特点：非比较排序，运行时间与值域大小无关，只与数位轮数有关；需要 O(n) 缓冲数组。</li>
     *   <li>时间复杂度：最好、平均、最坏均为 O(d·(n + b))，d = 4，b = 256，即 O(n)。</li>
     *   <li>空间复杂度：O(n + b)。</li>
     *   <li>推荐场景：大规模整数排序，尤其是值域很大、不适合计数排序的数据，如时间戳、哈希值、32 位 ID。</li>
     * </ul>
     *
     * @param array 待排序数组，排序后升序
     * @throws NullPointerException 数组为 {@code null}
     */
    public static void radixSort(int[] array) {
        Objects.requireNonNull(array, "待排序数组不能为 null");
        int n = array.length;
        if (n <= 1) {
            return;
        }
        int[] source = array;
        int[] target = new int[n];
        for (int pass = 0; pass < INT_RADIX_PASSES; pass++) {
            int shift = pass * BITS_PER_PASS;
            // count[d + 1] 统计数位 d 的个数，前缀和后 count[d] 为数位 d 的起始位置
            int[] count = new int[RADIX + 1];
            for (int value : source) {
                count[((value ^ SIGN_BIT) >>> shift & RADIX_MASK) + 1]++;
            }
            for (int d = 1; d <= RADIX; d++) {
                count[d] += count[d - 1];
            }
            for (int value : source) {
                target[count[(value ^ SIGN_BIT) >>> shift & RADIX_MASK]++] = value;
            }
            int[] temp = source;
            source = target;
            target = temp;
        }
        // 轮数为偶数，结果最终回到原数组；此处保留防御性拷贝以便轮数调整时仍然正确
        if (source != array) {
            System.arraycopy(source, 0, array, 0, n);
        }
    }

    /** 泛型基数排序核心实现，调用前已完成参数校验。 */
    private static <T extends Comparable<? super T>> void doRadixSort(T[] a) {
        int n = a.length;
        Ranks ranks = toRanks(a);
        int maxRank = ranks.distinctCount() - 1;
        int passes = 1;
        while (passes < INT_RADIX_PASSES && (maxRank >>> (passes * BITS_PER_PASS)) != 0) {
            passes++;
        }
        T[] sourceItems = Arrays.copyOf(a, n);
        T[] targetItems = Arrays.copyOf(a, n);
        int[] sourceRanks = ranks.values();
        int[] targetRanks = new int[n];
        for (int pass = 0; pass < passes; pass++) {
            int shift = pass * BITS_PER_PASS;
            int[] count = new int[RADIX + 1];
            for (int rank : sourceRanks) {
                count[(rank >>> shift & RADIX_MASK) + 1]++;
            }
            for (int d = 1; d <= RADIX; d++) {
                count[d] += count[d - 1];
            }
            // 元素与其排名同步移动，保证下一轮仍能取到正确的数位
            for (int i = 0; i < n; i++) {
                int position = count[sourceRanks[i] >>> shift & RADIX_MASK]++;
                targetItems[position] = sourceItems[i];
                targetRanks[position] = sourceRanks[i];
            }
            T[] tempItems = sourceItems;
            sourceItems = targetItems;
            targetItems = tempItems;
            int[] tempRanks = sourceRanks;
            sourceRanks = targetRanks;
            targetRanks = tempRanks;
        }
        System.arraycopy(sourceItems, 0, a, 0, n);
    }

    // ==================== 公共辅助方法 ====================

    /**
     * 离散化结果。
     *
     * @param values        与原数组一一对应的排名，取值范围 {@code [0, distinctCount)}
     * @param distinctCount 不同值的个数
     */
    private record Ranks(int[] values, int distinctCount) {
    }

    /** 把元素映射为其在全部不同值（按自然顺序）中的排名，相等元素排名相同。 */
    private static <T extends Comparable<? super T>> Ranks toRanks(T[] a) {
        int n = a.length;
        T[] distinct = Arrays.copyOf(a, n);
        doMergeSort(distinct);
        int distinctCount = 0;
        for (int i = 0; i < n; i++) {
            if (distinctCount == 0 || distinct[distinctCount - 1].compareTo(distinct[i]) != 0) {
                distinct[distinctCount++] = distinct[i];
            }
        }
        int[] values = new int[n];
        for (int i = 0; i < n; i++) {
            values[i] = binarySearch(distinct, distinctCount, a[i]);
        }
        return new Ranks(values, distinctCount);
    }

    /** 在有序去重数组的 {@code [0, length)} 区间中二分查找 {@code key} 的下标，调用方保证一定存在。 */
    private static <T extends Comparable<? super T>> int binarySearch(
            T[] sorted, int length,
            T key) {
        int lo = 0;
        int hi = length - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (sorted[mid].compareTo(key) < 0) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    /** 校验数组非 {@code null} 且不含 {@code null} 元素；返回是否需要排序（元素个数大于 1）。 */
    private static boolean prepareArray(Object[] array) {
        Objects.requireNonNull(array, "待排序数组不能为 null");
        checkNoNullElements(array);
        return array.length > 1;
    }

    /** 排序前扫描 {@code null} 元素，避免排序中途抛异常导致容器处于半排序状态。 */
    private static void checkNoNullElements(Object[] array) {
        for (int i = 0; i < array.length; i++) {
            if (array[i] == null) {
                throw new NullPointerException("下标 " + i + " 处的元素为 null，排序不支持 null 元素");
            }
        }
    }

    /** 列表统一处理：复制为数组、校验、排序后通过 {@link ListIterator#set(Object)} 写回。 */
    private static <T extends Comparable<? super T>> void sortList(List<T> list, Consumer<T[]> arraySorter) {
        Objects.requireNonNull(list, "待排序列表不能为 null");
        if (list.size() <= 1) {
            checkNoNullElements(list.toArray());
            return;
        }
        // 运行时数组类型为 Comparable[]，与 T 的擦除类型一致，强制转换是安全的
        @SuppressWarnings("unchecked")
        T[] array = (T[]) list.toArray(new Comparable<?>[0]);
        checkNoNullElements(array);
        arraySorter.accept(array);
        ListIterator<T> iterator = list.listIterator();
        for (T element : array) {
            iterator.next();
            iterator.set(element);
        }
    }

    /** 交换对象数组中的两个元素。 */
    private static void swap(Object[] a, int i, int j) {
        Object temp = a[i];
        a[i] = a[j];
        a[j] = temp;
    }
}
