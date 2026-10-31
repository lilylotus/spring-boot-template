package org.example.simple.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** {@link NormalSortUtils} 10 种排序算法的正确性、稳定性、参数校验与整数版本测试。 */
class NormalSortUtilsTest {

    /** 随机数据测试使用的固定种子，保证测试可重复。 */
    private static final long SEED = 20261031L;

    /** 随机数据测试覆盖的数组长度。 */
    private static final int[] SIZES = {0, 1, 2, 3, 7, 16, 100, 1000};

    /** 对泛型数组排序的算法入口，方法引用可实现泛型函数式方法。 */
    @FunctionalInterface
    interface ArraySorter {

        /** 对数组排序。 */
        <T extends Comparable<? super T>> void sort(T[] array);
    }

    /** 对泛型列表排序的算法入口。 */
    @FunctionalInterface
    interface ListSorter {

        /** 对列表排序。 */
        <T extends Comparable<? super T>> void sort(List<T> list);
    }

    /**
     * 一种排序算法的测试描述。
     *
     * @param name        算法名称，用于参数化测试显示
     * @param arraySorter 数组排序入口
     * @param listSorter  列表排序入口
     * @param stable      该算法是否承诺稳定
     */
    record Algorithm(
            String name, ArraySorter arraySorter,
            ListSorter listSorter, boolean stable) {

        @Override
        public String toString() {
            return name;
        }
    }

    /**
     * 只比较 {@code key}、但携带原始序号 {@code order} 的元素，用于验证稳定性。
     *
     * @param key   排序键
     * @param order 输入时的原始序号
     */
    record Item(int key, int order) implements Comparable<Item> {

        @Override
        public int compareTo(Item other) {
            return Integer.compare(key, other.key);
        }
    }

    /** 全部 10 种算法。 */
    static Stream<Algorithm> algorithms() {
        return Stream.of(
                new Algorithm("冒泡排序", NormalSortUtils::bubbleSort, NormalSortUtils::bubbleSort, true),
                new Algorithm("选择排序", NormalSortUtils::selectionSort, NormalSortUtils::selectionSort, false),
                new Algorithm("插入排序", NormalSortUtils::insertionSort, NormalSortUtils::insertionSort, true),
                new Algorithm("希尔排序", NormalSortUtils::shellSort, NormalSortUtils::shellSort, false),
                new Algorithm("归并排序", NormalSortUtils::mergeSort, NormalSortUtils::mergeSort, true),
                new Algorithm("快速排序", NormalSortUtils::quickSort, NormalSortUtils::quickSort, false),
                new Algorithm("堆排序", NormalSortUtils::heapSort, NormalSortUtils::heapSort, false),
                new Algorithm("计数排序", NormalSortUtils::countingSort, NormalSortUtils::countingSort, true),
                new Algorithm("桶排序", NormalSortUtils::bucketSort, NormalSortUtils::bucketSort, true),
                new Algorithm("基数排序", NormalSortUtils::radixSort, NormalSortUtils::radixSort, true));
    }

    /** 承诺稳定的算法。 */
    static Stream<Algorithm> stableAlgorithms() {
        return algorithms().filter(Algorithm::stable);
    }

    /** 提供 {@code int[]} 重载的 3 种非比较排序。 */
    static Stream<Consumer<int[]>> intSorters() {
        return Stream.of(NormalSortUtils::countingSort, NormalSortUtils::bucketSort, NormalSortUtils::radixSort);
    }

    // ==================== 泛型数组与列表 ====================

    @ParameterizedTest
    @MethodSource("algorithms")
    void sortsRandomIntegerArraysLikeArraysSort(Algorithm algorithm) {
        Random random = new Random(SEED);
        for (int size : SIZES) {
            // 小值域产生大量重复值，大值域覆盖负数与稀疏分布
            for (int bound : new int[] {5, 1_000_000}) {
                Integer[] actual = randomIntegers(random, size, bound);
                Integer[] expected = actual.clone();
                Arrays.sort(expected);
                algorithm.arraySorter().sort(actual);
                assertArrayEquals(expected, actual, algorithm + " size=" + size + " bound=" + bound);
            }
        }
    }

    @ParameterizedTest
    @MethodSource("algorithms")
    void handlesEdgeCaseArrays(Algorithm algorithm) {
        assertSortedLikeJdk(algorithm, new Integer[0]);
        assertSortedLikeJdk(algorithm, new Integer[] {42});
        assertSortedLikeJdk(algorithm, new Integer[] {1, 2, 3, 4, 5, 6, 7, 8, 9});
        assertSortedLikeJdk(algorithm, new Integer[] {9, 8, 7, 6, 5, 4, 3, 2, 1});
        assertSortedLikeJdk(algorithm, new Integer[] {7, 7, 7, 7, 7, 7});
        assertSortedLikeJdk(algorithm, new Integer[] {Integer.MAX_VALUE, 0, Integer.MIN_VALUE, -1, 1});
    }

    @ParameterizedTest
    @MethodSource("algorithms")
    void sortsLargeAscendingAndDescendingArrays(Algorithm algorithm) {
        int size = 2000;
        Integer[] ascending = new Integer[size];
        Integer[] descending = new Integer[size];
        for (int i = 0; i < size; i++) {
            ascending[i] = i;
            descending[i] = size - i;
        }
        assertSortedLikeJdk(algorithm, ascending);
        assertSortedLikeJdk(algorithm, descending);
    }

    @ParameterizedTest
    @MethodSource("algorithms")
    void sortsStringArray(Algorithm algorithm) {
        assertSortedLikeJdk(algorithm, new String[] {"pear", "apple", "banana", "apple", "", "Zebra", "中文", "cherry"});
    }

    @ParameterizedTest
    @MethodSource("algorithms")
    void sortsArrayListAndLinkedListLikeCollectionsSort(Algorithm algorithm) {
        Random random = new Random(SEED);
        for (int size : SIZES) {
            List<Integer> source = Arrays.asList(randomIntegers(random, size, 50));
            List<Integer> expected = new ArrayList<>(source);
            Collections.sort(expected);

            List<Integer> arrayList = new ArrayList<>(source);
            algorithm.listSorter().sort(arrayList);
            assertEquals(expected, arrayList, algorithm + " ArrayList size=" + size);

            List<Integer> linkedList = new LinkedList<>(source);
            algorithm.listSorter().sort(linkedList);
            assertEquals(expected, linkedList, algorithm + " LinkedList size=" + size);
        }
    }

    @ParameterizedTest
    @MethodSource("algorithms")
    void handlesEmptyAndSingletonLists(Algorithm algorithm) {
        List<Integer> empty = new ArrayList<>();
        algorithm.listSorter().sort(empty);
        assertEquals(List.of(), empty);

        // 元素不超过 1 个时直接返回，不可修改列表也不会抛出异常
        assertDoesNotThrow(() -> algorithm.listSorter().sort(List.of(1)));
    }

    @ParameterizedTest
    @MethodSource("algorithms")
    void unmodifiableListThrowsUnsupportedOperationException(Algorithm algorithm) {
        assertThrows(UnsupportedOperationException.class, () -> algorithm.listSorter().sort(List.of(3, 1, 2)));
    }

    // ==================== 稳定性 ====================

    @ParameterizedTest
    @MethodSource("stableAlgorithms")
    void stableAlgorithmsKeepRelativeOrderOfEqualElements(Algorithm algorithm) {
        Random random = new Random(SEED);
        Item[] items = new Item[500];
        for (int i = 0; i < items.length; i++) {
            items[i] = new Item(random.nextInt(10), i);
        }
        // Arrays.sort 对对象数组是稳定的，可作为期望结果
        Item[] expected = items.clone();
        Arrays.sort(expected);

        Item[] actual = items.clone();
        algorithm.arraySorter().sort(actual);
        assertArrayEquals(expected, actual, algorithm + " 数组版本应稳定");

        List<Item> list = new LinkedList<>(Arrays.asList(items));
        algorithm.listSorter().sort(list);
        assertEquals(Arrays.asList(expected), list, algorithm + " 列表版本应稳定");
    }

    // ==================== 参数校验 ====================

    @ParameterizedTest
    @MethodSource("algorithms")
    void nullContainerThrowsNullPointerException(Algorithm algorithm) {
        assertThrows(NullPointerException.class, () -> algorithm.arraySorter().sort((Integer[]) null));
        assertThrows(NullPointerException.class, () -> algorithm.listSorter().sort((List<Integer>) null));
    }

    @ParameterizedTest
    @MethodSource("algorithms")
    void nullElementThrowsNullPointerExceptionAndKeepsContent(Algorithm algorithm) {
        Integer[] array = {3, 1, null, 2};
        Integer[] arraySnapshot = array.clone();
        assertThrows(NullPointerException.class, () -> algorithm.arraySorter().sort(array));
        assertArrayEquals(arraySnapshot, array);

        List<Integer> list = new ArrayList<>(Arrays.asList(3, 1, null, 2));
        List<Integer> listSnapshot = new ArrayList<>(list);
        assertThrows(NullPointerException.class, () -> algorithm.listSorter().sort(list));
        assertEquals(listSnapshot, list);

        assertThrows(NullPointerException.class, () -> algorithm.arraySorter().sort(new Integer[] {null}));
    }

    // ==================== int[] 整数版本 ====================

    @ParameterizedTest
    @MethodSource("intSorters")
    void intSortersSortRandomArraysLikeArraysSort(Consumer<int[]> sorter) {
        Random random = new Random(SEED);
        for (int size : SIZES) {
            for (int bound : new int[] {5, 1_000_000}) {
                int[] actual = new int[size];
                for (int i = 0; i < size; i++) {
                    actual[i] = random.nextInt(bound * 2) - bound;
                }
                int[] expected = actual.clone();
                Arrays.sort(expected);
                sorter.accept(actual);
                assertArrayEquals(expected, actual, "size=" + size + " bound=" + bound);
            }
        }
    }

    @ParameterizedTest
    @MethodSource("intSorters")
    void intSortersHandleEdgeCases(Consumer<int[]> sorter) {
        assertIntSortedLikeJdk(sorter, new int[0]);
        assertIntSortedLikeJdk(sorter, new int[] {-5});
        assertIntSortedLikeJdk(sorter, new int[] {4, 4, 4, 4});
        assertIntSortedLikeJdk(sorter, new int[] {9, -8, 7, 0, -6, 5, 0, -4});
        assertThrows(NullPointerException.class, () -> sorter.accept(null));
    }

    @Test
    void radixAndBucketSortHandleIntegerExtremes() {
        int[] values = {Integer.MAX_VALUE, 0, Integer.MIN_VALUE, -1, 1, Integer.MIN_VALUE + 1, Integer.MAX_VALUE - 1};
        assertIntSortedLikeJdk(NormalSortUtils::radixSort, values);
        assertIntSortedLikeJdk(NormalSortUtils::bucketSort, values);
    }

    @Test
    void radixAndBucketSortHandleFullRangeRandomData() {
        Random random = new Random(SEED);
        int[] values = random.ints(5000).toArray();
        assertIntSortedLikeJdk(NormalSortUtils::radixSort, values);
        assertIntSortedLikeJdk(NormalSortUtils::bucketSort, values);
    }

    @Test
    void countingSortHandlesSmallRangeWithExtremeOffset() {
        int[] nearMax = {Integer.MAX_VALUE, Integer.MAX_VALUE - 3, Integer.MAX_VALUE - 1};
        assertIntSortedLikeJdk(NormalSortUtils::countingSort, nearMax);
        assertIntSortedLikeJdk(NormalSortUtils::countingSort, new int[] {Integer.MIN_VALUE + 2, Integer.MIN_VALUE});
    }

    @Test
    void countingSortRejectsTooLargeRangeAndKeepsContent() {
        int[] values = {Integer.MAX_VALUE, 0, Integer.MIN_VALUE};
        int[] snapshot = values.clone();
        assertThrows(IllegalArgumentException.class, () -> NormalSortUtils.countingSort(values));
        assertArrayEquals(snapshot, values);
    }

    // ==================== 辅助方法 ====================

    /** 生成 {@code [-bound, bound)} 范围内的随机整数数组。 */
    private static Integer[] randomIntegers(
            Random random, int size,
            int bound) {
        Integer[] values = new Integer[size];
        for (int i = 0; i < size; i++) {
            values[i] = random.nextInt(bound * 2) - bound;
        }
        return values;
    }

    /** 断言算法对泛型数组的排序结果与 {@link Arrays#sort(Object[])} 一致。 */
    private static <T extends Comparable<? super T>> void assertSortedLikeJdk(Algorithm algorithm, T[] input) {
        T[] expected = input.clone();
        Arrays.sort(expected);
        T[] actual = input.clone();
        algorithm.arraySorter().sort(actual);
        assertArrayEquals(expected, actual, algorithm + " 输入=" + Arrays.toString(input));
    }

    /** 断言整数排序结果与 {@link Arrays#sort(int[])} 一致。 */
    private static void assertIntSortedLikeJdk(Consumer<int[]> sorter, int[] input) {
        int[] expected = input.clone();
        Arrays.sort(expected);
        int[] actual = input.clone();
        sorter.accept(actual);
        assertArrayEquals(expected, actual, "输入=" + Arrays.toString(input));
    }
}
