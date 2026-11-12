package org.example.simple.algorithm;

import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * 双向链表：每个节点同时持有前驱与后继引用，链表持有头尾节点引用。
 * <p>
 * 复杂度：头尾增删与查看 O(1)；按下标访问、插入、删除 O(n)，实现上从离下标较近的一端开始遍历，
 * 最多遍历 n/2 个节点；按值查找与删除 O(n)；原地反转 O(n)。额外空间为每个元素一个节点（两个引用）。
 * <p>
 * 适用场景：需要频繁在两端或已定位节点处增删、而很少按下标随机访问的序列。随机访问频繁时应使用数组结构。
 * <p>
 * 约定：允许 {@code null} 元素，相等判断使用 {@link Objects#equals(Object, Object)}；
 * 空链表上取出或查看头尾元素抛出 {@link NoSuchElementException}；下标越界抛出 {@link IndexOutOfBoundsException}。
 * 迭代器为 fail-fast：迭代期间发生结构修改（增删、清空、反转）时抛出 {@link ConcurrentModificationException}。
 * 非线程安全。
 *
 * @param <E> 元素类型
 */
public class DoublyLinkedList<E> implements Iterable<E> {

    /** 头节点，空链表时为 {@code null}。 */
    private Node<E> head;

    /** 尾节点，空链表时为 {@code null}。 */
    private Node<E> tail;

    /** 元素个数。 */
    private int size;

    /** 结构修改次数，用于迭代器 fail-fast 检测。 */
    private int modCount;

    /** 创建空链表。 */
    public DoublyLinkedList() {
    }

    /**
     * 在链表头部插入元素，O(1)。
     *
     * @param element 待插入元素，允许为 {@code null}
     */
    public void addFirst(E element) {
        Node<E> node = new Node<>(null, element, head);
        if (head == null) {
            tail = node;
        } else {
            head.prev = node;
        }
        head = node;
        size++;
        modCount++;
    }

    /**
     * 在链表尾部插入元素，O(1)。
     *
     * @param element 待插入元素，允许为 {@code null}
     */
    public void addLast(E element) {
        Node<E> node = new Node<>(tail, element, null);
        if (tail == null) {
            head = node;
        } else {
            tail.next = node;
        }
        tail = node;
        size++;
        modCount++;
    }

    /**
     * 在指定下标处插入元素，原下标及其后的元素后移一位，O(n)。
     *
     * @param index 插入位置，取值范围 {@code [0, size]}
     * @param element 待插入元素
     * @throws IndexOutOfBoundsException 下标越界时抛出
     */
    public void add(int index, E element) {
        Objects.checkIndex(index, size + 1);
        if (index == size) {
            addLast(element);
            return;
        }
        if (index == 0) {
            addFirst(element);
            return;
        }
        Node<E> successor = node(index);
        Node<E> node = new Node<>(successor.prev, element, successor);
        successor.prev.next = node;
        successor.prev = node;
        size++;
        modCount++;
    }

    /**
     * 返回指定下标的元素，O(n)。
     *
     * @param index 元素下标
     * @return 该下标处的元素
     * @throws IndexOutOfBoundsException 下标不在 {@code [0, size)} 内时抛出
     */
    public E get(int index) {
        Objects.checkIndex(index, size);
        return node(index).item;
    }

    /**
     * 替换指定下标的元素并返回旧值，O(n)。替换不属于结构修改。
     *
     * @param index 元素下标
     * @param element 新元素
     * @return 被替换的旧元素
     * @throws IndexOutOfBoundsException 下标不在 {@code [0, size)} 内时抛出
     */
    public E set(int index, E element) {
        Objects.checkIndex(index, size);
        Node<E> node = node(index);
        E old = node.item;
        node.item = element;
        return old;
    }

    /**
     * 删除并返回指定下标的元素，O(n)。
     * <p>
     * 命名为 {@code removeAt} 而非重载 {@code remove(int)}，避免与 {@link #remove(Object)} 在
     * {@code Integer} 元素上产生重载歧义。
     *
     * @param index 待删除元素的下标
     * @return 被删除的元素
     * @throws IndexOutOfBoundsException 下标不在 {@code [0, size)} 内时抛出
     */
    public E removeAt(int index) {
        Objects.checkIndex(index, size);
        return unlink(node(index));
    }

    /**
     * 删除并返回头部元素，O(1)。
     *
     * @return 原头部元素
     * @throws NoSuchElementException 链表为空时抛出
     */
    public E removeFirst() {
        if (head == null) {
            throw new NoSuchElementException("链表为空，无法删除头部元素");
        }
        return unlink(head);
    }

    /**
     * 删除并返回尾部元素，O(1)。
     *
     * @return 原尾部元素
     * @throws NoSuchElementException 链表为空时抛出
     */
    public E removeLast() {
        if (tail == null) {
            throw new NoSuchElementException("链表为空，无法删除尾部元素");
        }
        return unlink(tail);
    }

    /**
     * 返回头部元素，O(1)。
     *
     * @return 头部元素
     * @throws NoSuchElementException 链表为空时抛出
     */
    public E getFirst() {
        if (head == null) {
            throw new NoSuchElementException("链表为空，无法获取头部元素");
        }
        return head.item;
    }

    /**
     * 返回尾部元素，O(1)。
     *
     * @return 尾部元素
     * @throws NoSuchElementException 链表为空时抛出
     */
    public E getLast() {
        if (tail == null) {
            throw new NoSuchElementException("链表为空，无法获取尾部元素");
        }
        return tail.item;
    }

    /**
     * 删除首个与给定对象相等的元素，O(n)。
     *
     * @param element 待删除的元素，允许为 {@code null}
     * @return 找到并删除时返回 {@code true}
     */
    public boolean remove(Object element) {
        for (Node<E> node = head; node != null; node = node.next) {
            if (Objects.equals(node.item, element)) {
                unlink(node);
                return true;
            }
        }
        return false;
    }

    /**
     * 返回首个与给定对象相等的元素下标，O(n)。
     *
     * @param element 待查找的元素，允许为 {@code null}
     * @return 首个相等元素的下标，不存在时返回 -1
     */
    public int indexOf(Object element) {
        int index = 0;
        for (Node<E> node = head; node != null; node = node.next) {
            if (Objects.equals(node.item, element)) {
                return index;
            }
            index++;
        }
        return -1;
    }

    /**
     * 判断链表是否包含与给定对象相等的元素，O(n)。
     *
     * @param element 待查找的元素，允许为 {@code null}
     * @return 包含时返回 {@code true}
     */
    public boolean contains(Object element) {
        return indexOf(element) >= 0;
    }

    /** 原地反转链表：交换每个节点的前驱与后继引用，再交换头尾引用，O(n)，额外空间 O(1)。 */
    public void reverse() {
        Node<E> node = head;
        while (node != null) {
            Node<E> next = node.next;
            node.next = node.prev;
            node.prev = next;
            node = next;
        }
        Node<E> oldHead = head;
        head = tail;
        tail = oldHead;
        modCount++;
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
     * 判断链表是否为空。
     *
     * @return 为空时返回 {@code true}
     */
    public boolean isEmpty() {
        return size == 0;
    }

    /** 清空链表，并断开所有节点引用以便垃圾回收，O(n)。 */
    public void clear() {
        Node<E> node = head;
        while (node != null) {
            Node<E> next = node.next;
            node.prev = null;
            node.next = null;
            node.item = null;
            node = next;
        }
        head = null;
        tail = null;
        size = 0;
        modCount++;
    }

    /**
     * 按从头到尾的顺序返回元素快照列表，O(n)。
     *
     * @return 元素快照列表，修改它不会影响链表
     */
    public List<E> toList() {
        List<E> result = new ArrayList<>(size);
        for (Node<E> node = head; node != null; node = node.next) {
            result.add(node.item);
        }
        return result;
    }

    /** 返回从头到尾的 fail-fast 迭代器，不支持 {@code remove}。 */
    @Override
    public Iterator<E> iterator() {
        return new Iterator<>() {

            /** 下一个待返回的节点。 */
            private Node<E> next = head;

            /** 创建迭代器时的结构修改次数。 */
            private final int expectedModCount = modCount;

            @Override
            public boolean hasNext() {
                return next != null;
            }

            @Override
            public E next() {
                if (modCount != expectedModCount) {
                    throw new ConcurrentModificationException("迭代期间链表被结构性修改");
                }
                if (next == null) {
                    throw new NoSuchElementException("迭代已结束");
                }
                E item = next.item;
                next = next.next;
                return item;
            }
        };
    }

    @Override
    public String toString() {
        return toList().toString();
    }

    /**
     * 校验链表内部不变量：正反向遍历节点数均等于 {@code size}，前驱后继引用相互一致，头尾引用正确。
     *
     * @throws IllegalStateException 不变量被破坏时抛出
     */
    void verifyInvariants() {
        int forward = 0;
        Node<E> prev = null;
        for (Node<E> node = head; node != null; node = node.next) {
            if (node.prev != prev) {
                throw new IllegalStateException("节点的前驱引用与实际前驱不一致");
            }
            prev = node;
            forward++;
            if (forward > size) {
                throw new IllegalStateException("正向遍历节点数超过 size，可能存在环");
            }
        }
        if (prev != tail) {
            throw new IllegalStateException("尾引用不是正向遍历的最后一个节点");
        }
        int backward = 0;
        for (Node<E> node = tail; node != null; node = node.prev) {
            backward++;
            if (backward > size) {
                throw new IllegalStateException("反向遍历节点数超过 size，可能存在环");
            }
        }
        if (forward != size || backward != size) {
            throw new IllegalStateException("遍历节点数与 size 不一致");
        }
    }

    /** 定位指定下标的节点：下标位于前半段时从头遍历，否则从尾遍历。调用方需保证下标合法。 */
    private Node<E> node(int index) {
        if (index < (size >> 1)) {
            Node<E> node = head;
            for (int i = 0; i < index; i++) {
                node = node.next;
            }
            return node;
        }
        Node<E> node = tail;
        for (int i = size - 1; i > index; i--) {
            node = node.prev;
        }
        return node;
    }

    /** 从链表中摘除节点并返回其元素，同时修正相邻节点及头尾引用。 */
    private E unlink(Node<E> node) {
        E item = node.item;
        Node<E> prev = node.prev;
        Node<E> next = node.next;
        if (prev == null) {
            head = next;
        } else {
            prev.next = next;
        }
        if (next == null) {
            tail = prev;
        } else {
            next.prev = prev;
        }
        node.prev = null;
        node.next = null;
        node.item = null;
        size--;
        modCount++;
        return item;
    }

    /**
     * 链表节点。
     *
     * @param <E> 元素类型
     */
    private static final class Node<E> {

        /** 前驱节点。 */
        private Node<E> prev;

        /** 节点元素。 */
        private E item;

        /** 后继节点。 */
        private Node<E> next;

        /** 创建节点。 */
        private Node(Node<E> prev, E item, Node<E> next) {
            this.prev = prev;
            this.item = item;
            this.next = next;
        }
    }
}
