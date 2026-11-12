package org.example.simple.algorithm;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * 基于动态数组的栈（后进先出，LIFO）。
 * <p>
 * 复杂度：{@code push}、{@code pop}、{@code peek} 均摊 O(1)；扩容或缩容时单次操作 O(n)。空间 O(n)。
 * <p>
 * 扩缩容策略：数组满时容量翻倍；元素个数降到容量的 1/4 且容量大于默认最小容量时容量减半，
 * 使用 1/4 而非 1/2 作为缩容阈值，可避免在临界点交替入栈出栈时反复扩缩容。
 * <p>
 * 适用场景：函数调用模拟、括号匹配、表达式求值、深度优先搜索、撤销操作等需要"最近的先处理"的场景。
 * <p>
 * 约定：不允许 {@code null} 元素（抛出 {@link NullPointerException}）；空栈上 {@code pop}、{@code peek}
 * 抛出 {@link NoSuchElementException}。迭代器按出栈顺序（栈顶到栈底）遍历，fail-fast。非线程安全。
 *
 * @param <E> 元素类型
 */
public class ArrayStack<E> implements Iterable<E> {

    /** 默认初始容量，同时也是缩容的下限。 */
    private static final int DEFAULT_CAPACITY = 8;

    /** 存放元素的数组，下标 0 为栈底，{@code size - 1} 为栈顶。 */
    private Object[] elements;

    /** 元素个数。 */
    private int size;

    /** 结构修改次数，用于迭代器 fail-fast 检测。 */
    private int modCount;

    /** 使用默认初始容量创建空栈。 */
    public ArrayStack() {
        this(DEFAULT_CAPACITY);
    }

    /**
     * 使用指定初始容量创建空栈。
     *
     * @param initialCapacity 初始容量，必须为正数
     * @throws IllegalArgumentException 初始容量不是正数时抛出
     */
    public ArrayStack(int initialCapacity) {
        if (initialCapacity <= 0) {
            throw new IllegalArgumentException("初始容量必须为正数：" + initialCapacity);
        }
        elements = new Object[initialCapacity];
    }

    /**
     * 将元素压入栈顶，均摊 O(1)。
     *
     * @param element 待放入的元素，不允许为 {@code null}
     * @throws NullPointerException 元素为 {@code null} 时抛出
     */
    public void push(E element) {
        Objects.requireNonNull(element, "栈不允许 null 元素");
        if (size == elements.length) {
            resize(elements.length * 2);
        }
        elements[size++] = element;
        modCount++;
    }

    /**
     * 弹出并返回栈顶元素，均摊 O(1)。
     *
     * @return 原栈顶元素
     * @throws NoSuchElementException 栈为空时抛出
     */
    public E pop() {
        E top = peek();
        // 置空槽位，避免已出栈元素仍被数组引用而无法回收
        elements[--size] = null;
        modCount++;
        if (elements.length > DEFAULT_CAPACITY && size <= elements.length / 4) {
            resize(Math.max(DEFAULT_CAPACITY, elements.length / 2));
        }
        return top;
    }

    /**
     * 返回但不移除栈顶元素，O(1)。
     *
     * @return 栈顶元素
     * @throws NoSuchElementException 栈为空时抛出
     */
    @SuppressWarnings("unchecked")
    public E peek() {
        if (size == 0) {
            throw new NoSuchElementException("栈为空");
        }
        return (E) elements[size - 1];
    }

    /**
     * 返回元素个数。
     *
     * @return 元素个数
     */
    public int size() {
        return size;
    }

    /**
     * 判断栈是否为空。
     *
     * @return 为空时返回 {@code true}
     */
    public boolean isEmpty() {
        return size == 0;
    }

    /** 清空栈并恢复默认容量。 */
    public void clear() {
        elements = new Object[DEFAULT_CAPACITY];
        size = 0;
        modCount++;
    }

    /**
     * 按出栈顺序（栈顶到栈底）返回元素快照列表。
     *
     * @return 元素快照列表，修改它不会影响本结构
     */
    public List<E> toList() {
        List<E> result = new ArrayList<>(size);
        for (E element : this) {
            result.add(element);
        }
        return result;
    }

    /** 返回按出栈顺序遍历的 fail-fast 迭代器，不支持 {@code remove}。 */
    @Override
    public Iterator<E> iterator() {
        return new Iterator<>() {

            /** 下一个待返回元素的下标。 */
            private int cursor = size - 1;

            /** 创建迭代器时的结构修改次数。 */
            private final int expectedModCount = modCount;

            @Override
            public boolean hasNext() {
                return cursor >= 0;
            }

            @Override
            @SuppressWarnings("unchecked")
            public E next() {
                if (modCount != expectedModCount) {
                    throw new ConcurrentModificationException("迭代期间栈被结构性修改");
                }
                if (cursor < 0) {
                    throw new NoSuchElementException("迭代已结束");
                }
                return (E) elements[cursor--];
            }
        };
    }

    @Override
    public String toString() {
        return toList().toString();
    }

    /**
     * 校验内部不变量：{@code size} 不超过容量，有效区内无 {@code null}，有效区外槽位全部为 {@code null}。
     *
     * @throws IllegalStateException 不变量被破坏时抛出
     */
    void verifyInvariants() {
        if (size < 0 || size > elements.length) {
            throw new IllegalStateException("size 超出容量范围");
        }
        for (int i = 0; i < elements.length; i++) {
            if ((i < size) == (elements[i] == null)) {
                throw new IllegalStateException("下标 " + i + " 处槽位状态与有效区不一致");
            }
        }
    }

    /** 当前数组容量，仅供同包测试观察扩缩容行为。 */
    int capacity() {
        return elements.length;
    }

    /** 将数组调整为指定容量，保留现有元素。 */
    private void resize(int newCapacity) {
        elements = Arrays.copyOf(elements, newCapacity);
    }
}
