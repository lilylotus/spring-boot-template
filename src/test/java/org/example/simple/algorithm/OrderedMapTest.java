package org.example.simple.algorithm;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Random;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * {@link OrderedMap} 五种树实现的契约测试：对外行为与 {@link TreeMap} 一致，且每步修改后内部不变量成立。
 */
class OrderedMapTest {

    /** 被测实现：名称与工厂，B 树、B+ 树覆盖最小与常用的度数、阶数。 */
    static Stream<Arguments> implementations() {
        return Stream.of(
                Arguments.of("BinarySearchTree", (Supplier<OrderedMap<Integer, String>>) BinarySearchTree::new),
                Arguments.of("RedBlackTree", (Supplier<OrderedMap<Integer, String>>) RedBlackTree::new),
                Arguments.of("AvlTree", (Supplier<OrderedMap<Integer, String>>) AvlTree::new),
                Arguments.of("BTree(t=2)", (Supplier<OrderedMap<Integer, String>>) () -> new BTree<>(2)),
                Arguments.of("BTree(t=3)", (Supplier<OrderedMap<Integer, String>>) BTree::new),
                Arguments.of("BTree(t=5)", (Supplier<OrderedMap<Integer, String>>) () -> new BTree<>(5)),
                Arguments.of("BPlusTree(m=3)", (Supplier<OrderedMap<Integer, String>>) () -> new BPlusTree<>(3)),
                Arguments.of("BPlusTree(m=4)", (Supplier<OrderedMap<Integer, String>>) BPlusTree::new),
                Arguments.of("BPlusTree(m=5)", (Supplier<OrderedMap<Integer, String>>) () -> new BPlusTree<>(5)),
                Arguments.of("BPlusTree(m=8)", (Supplier<OrderedMap<Integer, String>>) () -> new BPlusTree<>(8)));
    }

    /** 覆盖写入返回旧值且 size 不变。 */
    @ParameterizedTest(name = "{0}")
    @MethodSource("implementations")
    void putOverwritesAndReturnsOldValue(String name, Supplier<OrderedMap<Integer, String>> factory) {
        OrderedMap<Integer, String> map = factory.get();
        assertNull(map.put(1, "a"));
        assertEquals("a", map.put(1, "b"));
        assertEquals(1, map.size());
        assertEquals("b", map.get(1));
        verify(map);
    }

    /** 不存在的键：get 与 remove 返回 null，containsKey 返回 false。 */
    @ParameterizedTest(name = "{0}")
    @MethodSource("implementations")
    void missingKeyOperations(String name, Supplier<OrderedMap<Integer, String>> factory) {
        OrderedMap<Integer, String> map = factory.get();
        assertNull(map.get(1));
        assertNull(map.remove(1));
        assertFalse(map.containsKey(1));
        map.put(2, "b");
        assertNull(map.remove(1));
        assertEquals(1, map.size());
        verify(map);
    }

    /** 允许 null 值，需用 containsKey 区分。 */
    @ParameterizedTest(name = "{0}")
    @MethodSource("implementations")
    void supportsNullValues(String name, Supplier<OrderedMap<Integer, String>> factory) {
        OrderedMap<Integer, String> map = factory.get();
        map.put(1, null);
        assertTrue(map.containsKey(1));
        assertNull(map.get(1));
        assertEquals(1, map.range(0, 5).size());
        assertNull(map.range(0, 5).get(0).getValue());
        assertNull(map.remove(1));
        assertTrue(map.isEmpty());
    }

    /** null 键在所有接受键的方法中抛出 NullPointerException。 */
    @ParameterizedTest(name = "{0}")
    @MethodSource("implementations")
    void nullKeyThrows(String name, Supplier<OrderedMap<Integer, String>> factory) {
        OrderedMap<Integer, String> map = factory.get();
        map.put(1, "a");
        assertThrows(NullPointerException.class, () -> map.put(null, "x"));
        assertThrows(NullPointerException.class, () -> map.get(null));
        assertThrows(NullPointerException.class, () -> map.containsKey(null));
        assertThrows(NullPointerException.class, () -> map.remove(null));
        assertThrows(NullPointerException.class, () -> map.floorKey(null));
        assertThrows(NullPointerException.class, () -> map.ceilingKey(null));
        assertThrows(NullPointerException.class, () -> map.range(null, 1));
        assertThrows(NullPointerException.class, () -> map.range(1, null));
        assertEquals(List.of(1), map.keys());
    }

    /** 空树最值抛出 NoSuchElementException，导航与范围查询返回空结果。 */
    @ParameterizedTest(name = "{0}")
    @MethodSource("implementations")
    void emptyTreeBehavior(String name, Supplier<OrderedMap<Integer, String>> factory) {
        OrderedMap<Integer, String> map = factory.get();
        assertThrows(NoSuchElementException.class, map::minKey);
        assertThrows(NoSuchElementException.class, map::maxKey);
        assertNull(map.floorKey(1));
        assertNull(map.ceilingKey(1));
        assertTrue(map.range(0, 10).isEmpty());
        assertTrue(map.keys().isEmpty());
        assertEquals(0, map.height());
        assertFalse(map.iterator().hasNext());
        verify(map);
    }

    /** 导航与范围查询与 TreeMap 一致，反向区间返回空列表。 */
    @ParameterizedTest(name = "{0}")
    @MethodSource("implementations")
    void navigationAndRangeMatchTreeMap(String name, Supplier<OrderedMap<Integer, String>> factory) {
        OrderedMap<Integer, String> map = factory.get();
        TreeMap<Integer, String> expected = new TreeMap<>();
        Random random = new Random(3L);
        for (int i = 0; i < 300; i++) {
            int key = random.nextInt(1000) * 2;
            map.put(key, "v" + key);
            expected.put(key, "v" + key);
        }
        assertEquals(expected.firstKey(), map.minKey());
        assertEquals(expected.lastKey(), map.maxKey());
        for (int probe = -5; probe <= 2005; probe++) {
            assertEquals(expected.floorKey(probe), map.floorKey(probe), "floorKey(" + probe + ")");
            assertEquals(expected.ceilingKey(probe), map.ceilingKey(probe), "ceilingKey(" + probe + ")");
        }
        for (int i = 0; i < 200; i++) {
            int from = random.nextInt(2100) - 50;
            int to = from + random.nextInt(400);
            assertEquals(new ArrayList<>(expected.subMap(from, true, to, true).entrySet()), map.range(from, to));
        }
        assertTrue(map.range(10, 5).isEmpty());
        assertEquals(List.copyOf(expected.keySet()), map.keys());
        List<Integer> visited = new ArrayList<>();
        map.forEach((key, value) -> {
            assertEquals("v" + key, value);
            visited.add(key);
        });
        assertEquals(List.copyOf(expected.keySet()), visited);
    }

    /** 迭代期间插入或删除键使迭代器抛出 ConcurrentModificationException，覆盖值不影响迭代。 */
    @ParameterizedTest(name = "{0}")
    @MethodSource("implementations")
    void iteratorIsFailFast(String name, Supplier<OrderedMap<Integer, String>> factory) {
        OrderedMap<Integer, String> map = factory.get();
        for (int i = 0; i < 10; i++) {
            map.put(i, "v");
        }
        Iterator<Map.Entry<Integer, String>> iterator = map.iterator();
        assertEquals(0, iterator.next().getKey());
        map.put(0, "overwritten");
        assertEquals(1, iterator.next().getKey());
        map.put(100, "new");
        assertThrows(ConcurrentModificationException.class, iterator::next);
        Iterator<Map.Entry<Integer, String>> second = map.iterator();
        second.next();
        map.remove(5);
        assertThrows(ConcurrentModificationException.class, second::next);
    }

    /** clear 后结构为空并可继续使用。 */
    @ParameterizedTest(name = "{0}")
    @MethodSource("implementations")
    void clearResetsMap(String name, Supplier<OrderedMap<Integer, String>> factory) {
        OrderedMap<Integer, String> map = factory.get();
        for (int i = 0; i < 50; i++) {
            map.put(i, "v");
        }
        map.clear();
        assertTrue(map.isEmpty());
        assertEquals(0, map.height());
        verify(map);
        map.put(1, "a");
        assertEquals(List.of(1), map.keys());
    }

    /** 固定种子大规模随机增删查与 TreeMap 对照，每步校验内部不变量。 */
    @ParameterizedTest(name = "{0}")
    @MethodSource("implementations")
    void randomOperationsMatchTreeMap(String name, Supplier<OrderedMap<Integer, String>> factory) {
        Random random = new Random(name.hashCode());
        OrderedMap<Integer, String> map = factory.get();
        TreeMap<Integer, String> expected = new TreeMap<>();
        for (int step = 0; step < 20000; step++) {
            // 键空间较小以制造大量覆盖与命中删除；周期性偏向删除以反复触发下溢、合并与降高
            int key = random.nextInt(600);
            int operation = random.nextInt(10);
            boolean deletePhase = step % 5000 >= 3000;
            if (operation < (deletePhase ? 2 : 6)) {
                String value = "v" + step;
                assertEquals(expected.put(key, value), map.put(key, value));
            } else if (operation < 9) {
                assertEquals(expected.remove(key), map.remove(key));
            } else {
                assertEquals(expected.get(key), map.get(key));
                assertEquals(expected.containsKey(key), map.containsKey(key));
            }
            assertEquals(expected.size(), map.size());
            verify(map);
        }
        assertEquals(List.copyOf(expected.keySet()), map.keys());
        for (Integer key : new ArrayList<>(expected.keySet())) {
            assertEquals(expected.remove(key), map.remove(key));
            verify(map);
        }
        assertTrue(map.isEmpty());
        assertEquals(0, map.height());
    }

    /**
     * 顺序插入 2 万个键：普通 BST 退化为链表（高度等于 n），迭代实现不发生栈溢出。
     * 退化后每次插入 O(n)，总耗时 O(n²)，因此规模取 2 万；该深度已远超递归实现的安全栈深度。
     */
    @Test
    void sequentialInsertionDegeneratesBinarySearchTreeWithoutStackOverflow() {
        int n = 20_000;
        BinarySearchTree<Integer, Integer> bst = new BinarySearchTree<>();
        assertDoesNotThrow(() -> {
            for (int i = 0; i < n; i++) {
                bst.put(i, i);
            }
        });
        assertEquals(n, bst.height());
        assertEquals(n - 1, bst.maxKey());
        assertEquals(n / 2, bst.range(n / 4, n / 4 * 3 - 1).size());
        assertDoesNotThrow(bst::verifyInvariants);
        for (int i = 0; i < n; i += 2) {
            bst.remove(i);
        }
        assertEquals(n / 2, bst.size());
        bst.verifyInvariants();
    }

    /** 顺序插入 10 万个键：平衡树高度满足理论上界。 */
    @Test
    void sequentialInsertionKeepsBalancedTreesShallow() {
        int n = 100_000;
        double log2 = Math.log(n + 1) / Math.log(2);
        RedBlackTree<Integer, Integer> redBlack = new RedBlackTree<>();
        AvlTree<Integer, Integer> avl = new AvlTree<>();
        BTree<Integer, Integer> bTree = new BTree<>(3);
        BPlusTree<Integer, Integer> bPlusTree = new BPlusTree<>(4);
        for (int i = 0; i < n; i++) {
            redBlack.put(i, i);
            avl.put(i, i);
            bTree.put(i, i);
            bPlusTree.put(i, i);
        }
        assertTrue(redBlack.height() <= 2 * log2, "红黑树高度 " + redBlack.height());
        assertTrue(avl.height() <= 1.45 * Math.log(n + 2) / Math.log(2), "AVL 树高度 " + avl.height());
        // B 树非根节点至少 t 个子节点，高度不超过 log_t((n+1)/2) + 1
        assertTrue(bTree.height() <= Math.log((n + 1) / 2.0) / Math.log(3) + 1, "B 树高度 " + bTree.height());
        // B+ 树内部节点至少 ⌈m/2⌉ = 2 个子节点，叶子至少 2 个键
        assertTrue(bPlusTree.height() <= Math.log(n / 2.0) / Math.log(2) + 1, "B+ 树高度 " + bPlusTree.height());
        redBlack.verifyInvariants();
        avl.verifyInvariants();
        bTree.verifyInvariants();
        bPlusTree.verifyInvariants();
    }

    /** B 树与 B+ 树的非法构造参数抛出 IllegalArgumentException。 */
    @Test
    void rejectsIllegalDegreeAndOrder() {
        assertThrows(IllegalArgumentException.class, () -> new BTree<Integer, Integer>(1));
        assertThrows(IllegalArgumentException.class, () -> new BPlusTree<Integer, Integer>(2));
        assertEquals(2, new BTree<Integer, Integer>(2).minDegree());
        assertEquals(3, new BPlusTree<Integer, Integer>(3).order());
    }

    /** 根据实现类型调用对应的包级不变量校验方法。 */
    private static void verify(OrderedMap<Integer, String> map) {
        if (map instanceof BinarySearchTree<Integer, String> tree) {
            tree.verifyInvariants();
        } else if (map instanceof RedBlackTree<Integer, String> tree) {
            tree.verifyInvariants();
        } else if (map instanceof AvlTree<Integer, String> tree) {
            tree.verifyInvariants();
        } else if (map instanceof BTree<Integer, String> tree) {
            tree.verifyInvariants();
        } else if (map instanceof BPlusTree<Integer, String> tree) {
            tree.verifyInvariants();
        } else {
            throw new IllegalArgumentException("未知实现：" + map.getClass());
        }
    }
}
