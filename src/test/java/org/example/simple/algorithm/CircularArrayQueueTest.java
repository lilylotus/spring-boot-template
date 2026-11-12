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
 * {@link CircularArrayQueue} 测试。
 */
class CircularArrayQueueTest {

    /** 出队顺序为先进先出。 */
    @Test
    void dequeuesInFirstInFirstOutOrder() {
        CircularArrayQueue<Integer> queue = new CircularArrayQueue<>();
        queue.enqueue(1);
        queue.enqueue(2);
        queue.enqueue(3);
        assertEquals(List.of(1, 2, 3), queue.toList());
        assertEquals(1, queue.peek());
        assertEquals(1, queue.dequeue());
        assertEquals(2, queue.dequeue());
        assertEquals(3, queue.dequeue());
        assertTrue(queue.isEmpty());
        queue.verifyInvariants();
    }

    /** 空队列取出或查看抛出 NoSuchElementException。 */
    @Test
    void emptyQueueThrowsNoSuchElement() {
        CircularArrayQueue<Integer> queue = new CircularArrayQueue<>();
        assertThrows(NoSuchElementException.class, queue::dequeue);
        assertThrows(NoSuchElementException.class, queue::peek);
    }

    /** 拒绝 null 元素且队列内容不变。 */
    @Test
    void rejectsNullElement() {
        CircularArrayQueue<Integer> queue = new CircularArrayQueue<>();
        queue.enqueue(1);
        assertThrows(NullPointerException.class, () -> queue.enqueue(null));
        assertEquals(List.of(1), queue.toList());
    }

    /** 非正初始容量抛出 IllegalArgumentException。 */
    @Test
    void rejectsNonPositiveInitialCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new CircularArrayQueue<Integer>(0));
    }

    /** 下标回绕后扩容仍保持逻辑顺序。 */
    @Test
    void keepsOrderWhenGrowingAfterWrapAround() {
        CircularArrayQueue<Integer> queue = new CircularArrayQueue<>(4);
        queue.enqueue(1);
        queue.enqueue(2);
        queue.enqueue(3);
        queue.dequeue();
        queue.dequeue();
        // 此时 head = 2，继续入队会回绕到数组开头，第 5 个元素触发扩容
        queue.enqueue(4);
        queue.enqueue(5);
        queue.enqueue(6);
        queue.verifyInvariants();
        queue.enqueue(7);
        assertEquals(8, queue.capacity());
        assertEquals(List.of(3, 4, 5, 6, 7), queue.toList());
        queue.verifyInvariants();
    }

    /** 迭代期间结构修改使迭代器抛出 ConcurrentModificationException。 */
    @Test
    void iteratorIsFailFast() {
        CircularArrayQueue<Integer> queue = new CircularArrayQueue<>();
        queue.enqueue(1);
        queue.enqueue(2);
        Iterator<Integer> iterator = queue.iterator();
        assertEquals(1, iterator.next());
        queue.dequeue();
        assertThrows(ConcurrentModificationException.class, iterator::next);
    }

    /** 固定种子随机操作与 JDK ArrayDeque 队列语义对照，覆盖扩容、缩容与回绕。 */
    @Test
    void randomOperationsMatchJdkDeque() {
        Random random = new Random(11L);
        CircularArrayQueue<Integer> queue = new CircularArrayQueue<>(2);
        ArrayDeque<Integer> expected = new ArrayDeque<>();
        for (int step = 0; step < 20000; step++) {
            boolean enqueue = random.nextInt(10) < (step % 4000 < 2000 ? 7 : 3);
            if (enqueue || expected.isEmpty()) {
                int value = random.nextInt();
                queue.enqueue(value);
                expected.addLast(value);
            } else {
                assertEquals(expected.peekFirst(), queue.peek());
                assertEquals(expected.removeFirst(), queue.dequeue());
            }
            assertEquals(expected.size(), queue.size());
            queue.verifyInvariants();
        }
        assertEquals(List.copyOf(expected), queue.toList());
        queue.clear();
        assertTrue(queue.isEmpty());
        queue.verifyInvariants();
    }
}
