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
 * {@link ArrayStack} 测试。
 */
class ArrayStackTest {

    /** 出栈顺序为后进先出。 */
    @Test
    void popsInLastInFirstOutOrder() {
        ArrayStack<Integer> stack = new ArrayStack<>();
        stack.push(1);
        stack.push(2);
        stack.push(3);
        assertEquals(List.of(3, 2, 1), stack.toList());
        assertEquals(3, stack.peek());
        assertEquals(3, stack.pop());
        assertEquals(2, stack.pop());
        assertEquals(1, stack.pop());
        assertTrue(stack.isEmpty());
        stack.verifyInvariants();
    }

    /** 空栈取出或查看抛出 NoSuchElementException。 */
    @Test
    void emptyStackThrowsNoSuchElement() {
        ArrayStack<Integer> stack = new ArrayStack<>();
        assertThrows(NoSuchElementException.class, stack::pop);
        assertThrows(NoSuchElementException.class, stack::peek);
    }

    /** 拒绝 null 元素且栈内容不变。 */
    @Test
    void rejectsNullElement() {
        ArrayStack<Integer> stack = new ArrayStack<>();
        stack.push(1);
        assertThrows(NullPointerException.class, () -> stack.push(null));
        assertEquals(List.of(1), stack.toList());
    }

    /** 非正初始容量抛出 IllegalArgumentException。 */
    @Test
    void rejectsNonPositiveInitialCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new ArrayStack<Integer>(0));
        assertThrows(IllegalArgumentException.class, () -> new ArrayStack<Integer>(-1));
    }

    /** 扩容后翻倍，大量出栈后缩容且不低于默认容量。 */
    @Test
    void growsAndShrinksCapacity() {
        ArrayStack<Integer> stack = new ArrayStack<>(1);
        for (int i = 0; i < 100; i++) {
            stack.push(i);
        }
        assertEquals(128, stack.capacity());
        for (int i = 99; i >= 0; i--) {
            assertEquals(i, stack.pop());
            stack.verifyInvariants();
        }
        assertEquals(8, stack.capacity());
        stack.push(7);
        stack.clear();
        assertTrue(stack.isEmpty());
        assertEquals(8, stack.capacity());
    }

    /** 迭代期间结构修改使迭代器抛出 ConcurrentModificationException。 */
    @Test
    void iteratorIsFailFast() {
        ArrayStack<Integer> stack = new ArrayStack<>();
        stack.push(1);
        stack.push(2);
        Iterator<Integer> iterator = stack.iterator();
        assertEquals(2, iterator.next());
        stack.push(3);
        assertThrows(ConcurrentModificationException.class, iterator::next);
    }

    /** 固定种子随机操作与 JDK ArrayDeque 栈语义对照。 */
    @Test
    void randomOperationsMatchJdkDeque() {
        Random random = new Random(7L);
        ArrayStack<Integer> stack = new ArrayStack<>();
        ArrayDeque<Integer> expected = new ArrayDeque<>();
        for (int step = 0; step < 20000; step++) {
            // 前半段偏向入栈、后半段偏向出栈，覆盖扩容与缩容
            boolean push = random.nextInt(10) < (step < 10000 ? 7 : 3);
            if (push || expected.isEmpty()) {
                int value = random.nextInt();
                stack.push(value);
                expected.push(value);
            } else {
                assertEquals(expected.peek(), stack.peek());
                assertEquals(expected.pop(), stack.pop());
            }
            assertEquals(expected.size(), stack.size());
            stack.verifyInvariants();
        }
        assertEquals(List.copyOf(expected), stack.toList());
    }
}
