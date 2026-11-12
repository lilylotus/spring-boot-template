package org.example.simple.algorithm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * {@link DoublyLinkedList} 测试。
 */
class DoublyLinkedListTest {

    /** 头尾与下标操作的结果应与 JDK 链表一致。 */
    @Test
    void headTailAndIndexOperationsMatchJdkLinkedList() {
        DoublyLinkedList<Integer> list = new DoublyLinkedList<>();
        list.addLast(2);
        list.addFirst(1);
        list.addLast(4);
        list.add(2, 3);
        list.add(0, 0);
        list.add(5, 5);
        assertEquals(List.of(0, 1, 2, 3, 4, 5), list.toList());
        assertEquals(0, list.getFirst());
        assertEquals(5, list.getLast());
        assertEquals(3, list.get(3));
        assertEquals(3, list.set(3, 30));
        assertEquals(30, list.removeAt(3));
        assertEquals(0, list.removeFirst());
        assertEquals(5, list.removeLast());
        assertEquals(List.of(1, 2, 4), list.toList());
        assertEquals(3, list.size());
        list.verifyInvariants();
    }

    /** 越界下标应抛出 IndexOutOfBoundsException 且链表不变。 */
    @Test
    void outOfBoundsIndexThrowsAndLeavesListUnchanged() {
        DoublyLinkedList<String> list = new DoublyLinkedList<>();
        list.addLast("a");
        assertThrows(IndexOutOfBoundsException.class, () -> list.get(1));
        assertThrows(IndexOutOfBoundsException.class, () -> list.get(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> list.set(1, "b"));
        assertThrows(IndexOutOfBoundsException.class, () -> list.removeAt(1));
        assertThrows(IndexOutOfBoundsException.class, () -> list.add(2, "b"));
        assertThrows(IndexOutOfBoundsException.class, () -> list.add(-1, "b"));
        assertEquals(List.of("a"), list.toList());
        list.verifyInvariants();
    }

    /** 空链表上取出或查看头尾元素应抛出 NoSuchElementException。 */
    @Test
    void emptyListAccessThrowsNoSuchElement() {
        DoublyLinkedList<String> list = new DoublyLinkedList<>();
        assertThrows(NoSuchElementException.class, list::removeFirst);
        assertThrows(NoSuchElementException.class, list::removeLast);
        assertThrows(NoSuchElementException.class, list::getFirst);
        assertThrows(NoSuchElementException.class, list::getLast);
        assertTrue(list.isEmpty());
    }

    /** 允许 null 元素，按值查找与删除使用 Objects.equals。 */
    @Test
    void supportsNullElementsAndValueOperations() {
        DoublyLinkedList<String> list = new DoublyLinkedList<>();
        list.addLast("a");
        list.addLast(null);
        list.addLast("b");
        list.addLast(null);
        assertEquals(1, list.indexOf(null));
        assertTrue(list.contains(null));
        assertTrue(list.remove(null));
        assertEquals(List.of("a", "b"), list.toList().subList(0, 2));
        assertEquals(2, list.indexOf(null));
        assertFalse(list.remove("x"));
        assertEquals(-1, list.indexOf("x"));
        list.verifyInvariants();
    }

    /** 原地反转后正反向结构一致。 */
    @Test
    void reverseInvertsOrder() {
        DoublyLinkedList<Integer> list = new DoublyLinkedList<>();
        list.reverse();
        assertTrue(list.isEmpty());
        list.addLast(1);
        list.reverse();
        assertEquals(List.of(1), list.toList());
        list.addLast(2);
        list.addLast(3);
        list.reverse();
        assertEquals(List.of(3, 2, 1), list.toList());
        assertEquals(3, list.getFirst());
        assertEquals(1, list.getLast());
        list.verifyInvariants();
    }

    /** 清空后链表为空且可继续使用。 */
    @Test
    void clearEmptiesList() {
        DoublyLinkedList<Integer> list = new DoublyLinkedList<>();
        list.addLast(1);
        list.addLast(2);
        list.clear();
        assertTrue(list.isEmpty());
        list.verifyInvariants();
        list.addFirst(3);
        assertEquals(List.of(3), list.toList());
    }

    /** 迭代期间结构修改应使迭代器抛出 ConcurrentModificationException。 */
    @Test
    void iteratorIsFailFast() {
        DoublyLinkedList<Integer> list = new DoublyLinkedList<>();
        list.addLast(1);
        list.addLast(2);
        Iterator<Integer> iterator = list.iterator();
        assertEquals(1, iterator.next());
        list.addLast(3);
        assertThrows(ConcurrentModificationException.class, iterator::next);
        List<Integer> collected = new ArrayList<>();
        list.iterator().forEachRemaining(collected::add);
        assertEquals(List.of(1, 2, 3), collected);
    }

    /** 固定种子随机操作与 JDK 链表对照，每步校验不变量。 */
    @Test
    void randomOperationsMatchJdkLinkedList() {
        Random random = new Random(20261112L);
        DoublyLinkedList<Integer> list = new DoublyLinkedList<>();
        LinkedList<Integer> expected = new LinkedList<>();
        for (int step = 0; step < 20000; step++) {
            int operation = random.nextInt(8);
            Integer value = random.nextInt(50);
            switch (operation) {
                case 0 -> {
                    list.addFirst(value);
                    expected.addFirst(value);
                }
                case 1 -> {
                    list.addLast(value);
                    expected.addLast(value);
                }
                case 2 -> {
                    int index = random.nextInt(expected.size() + 1);
                    list.add(index, value);
                    expected.add(index, value);
                }
                case 3 -> {
                    if (!expected.isEmpty()) {
                        int index = random.nextInt(expected.size());
                        assertEquals(expected.remove(index), list.removeAt(index));
                    }
                }
                case 4 -> {
                    if (!expected.isEmpty()) {
                        assertEquals(expected.removeFirst(), list.removeFirst());
                    }
                }
                case 5 -> {
                    if (!expected.isEmpty()) {
                        assertEquals(expected.removeLast(), list.removeLast());
                    }
                }
                case 6 -> assertEquals(expected.remove(value), list.remove(value));
                default -> {
                    if (!expected.isEmpty()) {
                        int index = random.nextInt(expected.size());
                        assertEquals(expected.get(index), list.get(index));
                        assertEquals(expected.indexOf(value), list.indexOf(value));
                    }
                }
            }
            assertEquals(expected.size(), list.size());
            list.verifyInvariants();
        }
        assertEquals(expected, list.toList());
    }

    /** toString 输出与列表格式一致。 */
    @Test
    void toStringUsesListFormat() {
        DoublyLinkedList<Integer> list = new DoublyLinkedList<>();
        list.addLast(1);
        list.addLast(2);
        assertEquals("[1, 2]", list.toString());
        assertFalse(new DoublyLinkedList<Integer>().iterator().hasNext());
    }
}
