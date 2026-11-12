package org.example.simple.algorithm;

import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * 基于循环数组的双端队列：两端均可高效插入、删除与查看。
 * <p>
 * 实现：以 {@code head} 记录队首下标、{@code size} 记录元素个数；队首插入时 {@code head} 向前回绕，
 * 队尾插入写入 {@code (head + size) % capacity}。
 * <p>
 * 复杂度：{@code addFirst}、{@code addLast}、{@code removeFirst}、{@code removeLast}、{@code peekFirst}、
 * {@code peekLast} 均摊 O(1)；扩容或缩容时单次操作 O(n)。空间 O(n)。
 * 扩缩容策略与 {@link ArrayStack} 相同：满时翻倍，元素降到容量 1/4 时减半，且不低于默认容量。
 * <p>
 * 适用场景：滑动窗口最值（单调队列）、0-1 广度优先搜索、工作窃取、同时需要栈与队列语义的场景。
 * <p>
 * 约定：不允许 {@code null} 元素（抛出 {@link NullPointerException}）；空双端队列上取出或查看操作抛出
 * {@link NoSuchElementException}。迭代器从队首到队尾遍历，fail-fast。非线程安全。
 *
 * @param <E> 元素类型
 */
public class CircularArrayDeque<E> implements Iterable<E> {

    /** 默认初始容量，同时也是缩容的下限。 */
    private static final int DEFAULT_CAPACITY = 8;

    /** 循环数组。 */
    private Object[] elements;

    /** 队首元素下标。 */
    private int head;

    /** 元素个数。 */
    private int size;

    /** 结构修改次数，用于迭代器 fail-fast 检测。 */
    private int modCount;

    /** 使用默认初始容量创建空双端队列。 */
    public CircularArrayDeque() {
        this(DEFAULT_CAPACITY);
    }

    /**
     * 使用指定初始容量创建空双端队列。
     *
     * @param initialCapacity 初始容量，必须为正数
     * @throws IllegalArgumentException 初始容量不是正数时抛出
     */
    public CircularArrayDeque(int initialCapacity) {
        if (initialCapacity <= 0) {
            throw new IllegalArgumentException("初始容量必须为正数：" + initialCapacity);
        }
        elements = new Object[initialCapacity];
    }

    /**
     * 在队首插入元素，均摊 O(1)。
     *
     * @param element 待放入的元素，不允许为 {@code null}
     * @throws NullPointerException 元素为 {@code null} 时抛出
     */
    public void addFirst(E element) {
        Objects.requireNonNull(element, "双端队列不允许 null 元素");
        growIfFull();
        head = decrement(head);
        elements[head] = element;
        size++;
        modCount++;
    }

    /**
     * 在队尾插入元素，均摊 O(1)。
     *
     * @param element 待放入的元素，不允许为 {@code null}
     * @throws NullPointerException 元素为 {@code null} 时抛出
     */
    public void addLast(E element) {
        Objects.requireNonNull(element, "双端队列不允许 null 元素");
        growIfFull();
        elements[(head + size) % elements.length] = element;
        size++;
        modCount++;
    }

    /**
     * 移除并返回队首元素，均摊 O(1)。
     *
     * @return 原队首元素
     * @throws NoSuchElementException 双端队列为空时抛出
     */
    public E removeFirst() {
        E first = peekFirst();
        elements[head] = null;
        head = (head + 1) % elements.length;
        size--;
        modCount++;
        shrinkIfSparse();
        return first;
    }

    /**
     * 移除并返回队尾元素，均摊 O(1)。
     *
     * @return 原队尾元素
     * @throws NoSuchElementException 双端队列为空时抛出
     */
    public E removeLast() {
        E last = peekLast();
        elements[(head + size - 1) % elements.length] = null;
        size--;
        modCount++;
        shrinkIfSparse();
        return last;
    }

    /**
     * 返回但不移除队首元素，O(1)。
     *
     * @return 队首元素
     * @throws NoSuchElementException 双端队列为空时抛出
     */
    @SuppressWarnings("unchecked")
    public E peekFirst() {
        if (size == 0) {
            throw new NoSuchElementException("双端队列为空");
        }
        return (E) elements[head];
    }

    /**
     * 返回但不移除队尾元素，O(1)。
     *
     * @return 队尾元素
     * @throws NoSuchElementException 双端队列为空时抛出
     */
    @SuppressWarnings("unchecked")
    public E peekLast() {
        if (size == 0) {
            throw new NoSuchElementException("双端队列为空");
        }
        return (E) elements[(head + size - 1) % elements.length];
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
     * 判断双端队列是否为空。
     *
     * @return 为空时返回 {@code true}
     */
    public boolean isEmpty() {
        return size == 0;
    }

    /** 清空双端队列并恢复默认容量。 */
    public void clear() {
        elements = new Object[DEFAULT_CAPACITY];
        head = 0;
        size = 0;
        modCount++;
    }

    /**
     * 按从队首到队尾的顺序返回元素快照列表。
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

    /** 返回从队首到队尾遍历的 fail-fast 迭代器，不支持 {@code remove}。 */
    @Override
    public Iterator<E> iterator() {
        return new Iterator<>() {

            /** 已返回的元素个数。 */
            private int offset;

            /** 创建迭代器时的结构修改次数。 */
            private final int expectedModCount = modCount;

            @Override
            public boolean hasNext() {
                return offset < size;
            }

            @Override
            @SuppressWarnings("unchecked")
            public E next() {
                if (modCount != expectedModCount) {
                    throw new ConcurrentModificationException("迭代期间双端队列被结构性修改");
                }
                if (offset >= size) {
                    throw new NoSuchElementException("迭代已结束");
                }
                return (E) elements[(head + offset++) % elements.length];
            }
        };
    }

    @Override
    public String toString() {
        return toList().toString();
    }

    /**
     * 校验内部不变量：{@code size} 不超过容量，{@code head} 在数组范围内，
     * 有效区（含回绕部分）内无 {@code null}，有效区外槽位全部为 {@code null}。
     *
     * @throws IllegalStateException 不变量被破坏时抛出
     */
    void verifyInvariants() {
        int capacity = elements.length;
        if (size < 0 || size > capacity || head < 0 || head >= capacity) {
            throw new IllegalStateException("size 或 head 超出范围");
        }
        for (int i = 0; i < capacity; i++) {
            // 槽位 i 相对队首的逻辑偏移
            int offset = (i - head + capacity) % capacity;
            if ((offset < size) == (elements[i] == null)) {
                throw new IllegalStateException("下标 " + i + " 处槽位状态与有效区不一致");
            }
        }
    }

    /** 当前数组容量，仅供同包测试观察扩缩容行为。 */
    int capacity() {
        return elements.length;
    }

    /** 下标在循环数组中向前移动一位。 */
    private int decrement(int index) {
        return index == 0 ? elements.length - 1 : index - 1;
    }

    /** 数组已满时容量翻倍。 */
    private void growIfFull() {
        if (size == elements.length) {
            resize(elements.length * 2);
        }
    }

    /** 元素个数降到容量 1/4 时容量减半，且不低于默认容量。 */
    private void shrinkIfSparse() {
        if (elements.length > DEFAULT_CAPACITY && size <= elements.length / 4) {
            resize(Math.max(DEFAULT_CAPACITY, elements.length / 2));
        }
    }

    /** 将数组调整为指定容量，并按逻辑顺序把元素搬移到新数组开头，消除回绕。 */
    private void resize(int newCapacity) {
        Object[] newElements = new Object[newCapacity];
        for (int i = 0; i < size; i++) {
            newElements[i] = elements[(head + i) % elements.length];
        }
        elements = newElements;
        head = 0;
    }
}
