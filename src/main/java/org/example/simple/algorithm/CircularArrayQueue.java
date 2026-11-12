package org.example.simple.algorithm;

import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * 基于循环数组的队列（先进先出，FIFO）。
 * <p>
 * 实现：以 {@code head} 记录队首下标、{@code size} 记录元素个数，队尾下标为 {@code (head + size) % capacity}。
 * 出队后队首后移并回绕到数组开头，使已腾出的空间可被复用，避免普通数组队列出队时整体搬移元素。
 * <p>
 * 复杂度：{@code enqueue}、{@code dequeue}、{@code peek} 均摊 O(1)；扩容或缩容时单次操作 O(n)。空间 O(n)。
 * 扩缩容策略与 {@link ArrayStack} 相同：满时翻倍，元素降到容量 1/4 时减半，且不低于默认容量。
 * <p>
 * 适用场景：任务排队、广度优先搜索、生产者与消费者之间的缓冲（单线程）等按到达顺序处理的场景。
 * <p>
 * 约定：不允许 {@code null} 元素（抛出 {@link NullPointerException}）；空队列上 {@code dequeue}、{@code peek}
 * 抛出 {@link NoSuchElementException}。迭代器按出队顺序遍历，fail-fast。非线程安全。
 *
 * @param <E> 元素类型
 */
public class CircularArrayQueue<E> implements Iterable<E> {

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

    /** 使用默认初始容量创建空队列。 */
    public CircularArrayQueue() {
        this(DEFAULT_CAPACITY);
    }

    /**
     * 使用指定初始容量创建空队列。
     *
     * @param initialCapacity 初始容量，必须为正数
     * @throws IllegalArgumentException 初始容量不是正数时抛出
     */
    public CircularArrayQueue(int initialCapacity) {
        if (initialCapacity <= 0) {
            throw new IllegalArgumentException("初始容量必须为正数：" + initialCapacity);
        }
        elements = new Object[initialCapacity];
    }

    /**
     * 将元素加入队尾，均摊 O(1)。
     *
     * @param element 待放入的元素，不允许为 {@code null}
     * @throws NullPointerException 元素为 {@code null} 时抛出
     */
    public void enqueue(E element) {
        Objects.requireNonNull(element, "队列不允许 null 元素");
        if (size == elements.length) {
            resize(elements.length * 2);
        }
        elements[(head + size) % elements.length] = element;
        size++;
        modCount++;
    }

    /**
     * 移除并返回队首元素，均摊 O(1)。
     *
     * @return 原队首元素
     * @throws NoSuchElementException 队列为空时抛出
     */
    public E dequeue() {
        E first = peek();
        // 置空槽位，避免已出队元素仍被数组引用而无法回收
        elements[head] = null;
        head = (head + 1) % elements.length;
        size--;
        modCount++;
        if (elements.length > DEFAULT_CAPACITY && size <= elements.length / 4) {
            resize(Math.max(DEFAULT_CAPACITY, elements.length / 2));
        }
        return first;
    }

    /**
     * 返回但不移除队首元素，O(1)。
     *
     * @return 队首元素
     * @throws NoSuchElementException 队列为空时抛出
     */
    @SuppressWarnings("unchecked")
    public E peek() {
        if (size == 0) {
            throw new NoSuchElementException("队列为空");
        }
        return (E) elements[head];
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
     * 判断队列是否为空。
     *
     * @return 为空时返回 {@code true}
     */
    public boolean isEmpty() {
        return size == 0;
    }

    /** 清空队列并恢复默认容量。 */
    public void clear() {
        elements = new Object[DEFAULT_CAPACITY];
        head = 0;
        size = 0;
        modCount++;
    }

    /**
     * 按出队顺序返回元素快照列表。
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

    /** 返回按出队顺序遍历的 fail-fast 迭代器，不支持 {@code remove}。 */
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
                    throw new ConcurrentModificationException("迭代期间队列被结构性修改");
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
