package org.example.simple.algorithm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * {@link CircularArrayDeque} 测试。
 */
class CircularArrayDequeTest {

    /** 两端插入与删除符合双端队列语义。 */
    @Test
    void supportsOperationsAtBothEnds() {
        CircularArrayDeque<Integer> deque = new CircularArrayDeque<>();
        deque.addLast(2);
        deque.addFirst(1);
        deque.addLast(3);
        assertEquals(List.of(1, 2, 3), deque.toList());
        assertEquals(1, deque.peekFirst());
        assertEquals(3, deque.peekLast());
        assertEquals(3, deque.removeLast());
        assertEquals(1, deque.removeFirst());
        assertEquals(2, deque.removeFirst());
        assertTrue(deque.isEmpty());
        deque.verifyInvariants();
    }

    /** 空双端队列取出或查看抛出 NoSuchElementException。 */
    @Test
    void emptyDequeThrowsNoSuchElement() {
        CircularArrayDeque<Integer> deque = new CircularArrayDeque<>();
        assertThrows(NoSuchElementException.class, deque::removeFirst);
        assertThrows(NoSuchElementException.class, deque::removeLast);
        assertThrows(NoSuchElementException.class, deque::peekFirst);
        assertThrows(NoSuchElementException.class, deque::peekLast);
    }

    /** 拒绝 null 元素且内容不变。 */
    @Test
    void rejectsNullElement() {
        CircularArrayDeque<Integer> deque = new CircularArrayDeque<>();
        deque.addLast(1);
        assertThrows(NullPointerException.class, () -> deque.addFirst(null));
        assertThrows(NullPointerException.class, () -> deque.addLast(null));
        assertEquals(List.of(1), deque.toList());
    }

    /** 非正初始容量抛出 IllegalArgumentException。 */
    @Test
    void rejectsNonPositiveInitialCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new CircularArrayDeque<Integer>(0));
    }

    /** 队首插入时 head 回绕到数组末尾，扩容后顺序保持不变。 */
    @Test
    void keepsOrderWhenAddingFirstWrapsAround() {
        CircularArrayDeque<Integer> deque = new CircularArrayDeque<>(2);
        deque.addFirst(2);
        deque.addFirst(1);
        deque.addFirst(0);
        deque.addLast(3);
        assertEquals(List.of(0, 1, 2, 3), deque.toList());
        assertEquals(4, deque.capacity());
        deque.verifyInvariants();
    }

    /** 迭代期间结构修改使迭代器抛出 ConcurrentModificationException。 */
    @Test
    void iteratorIsFailFast() {
        CircularArrayDeque<Integer> deque = new CircularArrayDeque<>();
        deque.addLast(1);
        deque.addLast(2);
        Iterator<Integer> iterator = deque.iterator();
        assertEquals(1, iterator.next());
        deque.addFirst(0);
        assertThrows(ConcurrentModificationException.class, iterator::next);
    }

    /** 固定种子随机操作与 JDK ArrayDeque 对照，覆盖扩容、缩容与双向回绕。 */
    @Test
    void randomOperationsMatchJdkDeque() {
        Random random = new Random(13L);
        CircularArrayDeque<Integer> deque = new CircularArrayDeque<>(1);
        ArrayDeque<Integer> expected = new ArrayDeque<>();
        for (int step = 0; step < 30000; step++) {
            boolean add = random.nextInt(10) < (step % 6000 < 3000 ? 7 : 3);
            boolean front = random.nextBoolean();
            if (add || expected.isEmpty()) {
                int value = random.nextInt();
                if (front) {
                    deque.addFirst(value);
                    expected.addFirst(value);
                } else {
                    deque.addLast(value);
                    expected.addLast(value);
                }
            } else if (front) {
                assertEquals(expected.peekFirst(), deque.peekFirst());
                assertEquals(expected.removeFirst(), deque.removeFirst());
            } else {
                assertEquals(expected.peekLast(), deque.peekLast());
                assertEquals(expected.removeLast(), deque.removeLast());
            }
            assertEquals(expected.size(), deque.size());
            deque.verifyInvariants();
        }
        assertEquals(List.copyOf(expected), deque.toList());
    }
}
