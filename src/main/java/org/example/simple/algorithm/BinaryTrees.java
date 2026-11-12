package org.example.simple.algorithm;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * 二叉搜索树通用只读算法：查找、最值、前驱后继导航、范围查询、中序迭代、高度与二叉搜索树性质校验。
 * <p>
 * 全部采用迭代实现而非递归，使退化为链表的普通二叉搜索树（高度可达 n）也不会发生栈溢出。
 */
final class BinaryTrees {

    /** 工具类不允许实例化。 */
    private BinaryTrees() {
    }

    /** 查找键对应的节点，不存在时返回 {@code null}，O(h)。 */
    static <K extends Comparable<? super K>, N extends BinaryNode<K, ?, N>> N find(N root, K key) {
        N node = root;
        while (node != null) {
            int cmp = key.compareTo(node.key);
            if (cmp == 0) {
                return node;
            }
            node = cmp < 0 ? node.left : node.right;
        }
        return null;
    }

    /** 返回子树中键最小的节点（最左节点），子树为空时返回 {@code null}，O(h)。 */
    static <N extends BinaryNode<?, ?, N>> N minNode(N root) {
        N node = root;
        if (node == null) {
            return null;
        }
        while (node.left != null) {
            node = node.left;
        }
        return node;
    }

    /** 返回子树中键最大的节点（最右节点），子树为空时返回 {@code null}，O(h)。 */
    static <N extends BinaryNode<?, ?, N>> N maxNode(N root) {
        N node = root;
        if (node == null) {
            return null;
        }
        while (node.right != null) {
            node = node.right;
        }
        return node;
    }

    /**
     * 返回最小键，O(h)。
     *
     * @throws NoSuchElementException 树为空时抛出
     */
    static <K, N extends BinaryNode<K, ?, N>> K minKey(N root) {
        if (root == null) {
            throw new NoSuchElementException("树为空");
        }
        return minNode(root).key;
    }

    /**
     * 返回最大键，O(h)。
     *
     * @throws NoSuchElementException 树为空时抛出
     */
    static <K, N extends BinaryNode<K, ?, N>> K maxKey(N root) {
        if (root == null) {
            throw new NoSuchElementException("树为空");
        }
        return maxNode(root).key;
    }

    /** 返回小于等于给定键的最大键：向右走时记录候选，O(h)。 */
    static <K extends Comparable<? super K>, N extends BinaryNode<K, ?, N>> K floorKey(N root, K key) {
        K candidate = null;
        N node = root;
        while (node != null) {
            int cmp = key.compareTo(node.key);
            if (cmp == 0) {
                return node.key;
            }
            if (cmp > 0) {
                candidate = node.key;
                node = node.right;
            } else {
                node = node.left;
            }
        }
        return candidate;
    }

    /** 返回大于等于给定键的最小键：向左走时记录候选，O(h)。 */
    static <K extends Comparable<? super K>, N extends BinaryNode<K, ?, N>> K ceilingKey(N root, K key) {
        K candidate = null;
        N node = root;
        while (node != null) {
            int cmp = key.compareTo(node.key);
            if (cmp == 0) {
                return node.key;
            }
            if (cmp < 0) {
                candidate = node.key;
                node = node.left;
            } else {
                node = node.right;
            }
        }
        return candidate;
    }

    /**
     * 返回闭区间 {@code [from, to]} 内的键值对。
     * <p>
     * 中序遍历时剪枝：键小于 {@code from} 的节点直接转向右子树（其左子树必然都小于 {@code from}），
     * 遇到第一个大于 {@code to} 的键即停止。复杂度 O(h + k)，k 为结果个数。
     */
    static <K extends Comparable<? super K>, V, N extends BinaryNode<K, V, N>> List<Map.Entry<K, V>> range(
            N root, K from, K to) {
        List<Map.Entry<K, V>> result = new ArrayList<>();
        if (from.compareTo(to) > 0) {
            return result;
        }
        Deque<N> stack = new ArrayDeque<>();
        N node = root;
        while (node != null || !stack.isEmpty()) {
            while (node != null) {
                if (node.key.compareTo(from) < 0) {
                    node = node.right;
                } else {
                    stack.push(node);
                    node = node.left;
                }
            }
            if (stack.isEmpty()) {
                break;
            }
            N current = stack.pop();
            if (current.key.compareTo(to) > 0) {
                break;
            }
            result.add(OrderedMaps.entry(current.key, current.value));
            node = current.right;
        }
        return result;
    }

    /** 返回按键升序的中序迭代器（基于显式栈，额外空间 O(h)），不含 fail-fast 检查。 */
    static <K, V, N extends BinaryNode<K, V, N>> Iterator<Map.Entry<K, V>> iterator(N root) {
        return new Iterator<>() {

            /** 待访问节点栈，栈顶为下一个最小节点。 */
            private final Deque<N> stack = new ArrayDeque<>();

            {
                pushLeftPath(root);
            }

            @Override
            public boolean hasNext() {
                return !stack.isEmpty();
            }

            @Override
            public Map.Entry<K, V> next() {
                if (stack.isEmpty()) {
                    throw new NoSuchElementException("迭代已结束");
                }
                N node = stack.pop();
                pushLeftPath(node.right);
                return OrderedMaps.entry(node.key, node.value);
            }

            /** 将从给定节点出发的整条左链压栈。 */
            private void pushLeftPath(N start) {
                for (N node = start; node != null; node = node.left) {
                    stack.push(node);
                }
            }
        };
    }

    /** 计算树高（根到最深节点路径上的节点数），按层遍历，O(n)。 */
    static <N extends BinaryNode<?, ?, N>> int height(N root) {
        if (root == null) {
            return 0;
        }
        int height = 0;
        List<N> level = new ArrayList<>();
        level.add(root);
        while (!level.isEmpty()) {
            height++;
            List<N> nextLevel = new ArrayList<>();
            for (N node : level) {
                if (node.left != null) {
                    nextLevel.add(node.left);
                }
                if (node.right != null) {
                    nextLevel.add(node.right);
                }
            }
            level = nextLevel;
        }
        return height;
    }

    /**
     * 校验二叉搜索树性质：中序遍历的键严格递增，且节点总数等于 {@code size}。
     *
     * @throws IllegalStateException 性质不成立时抛出
     */
    static <K extends Comparable<? super K>, N extends BinaryNode<K, ?, N>> void verifySearchTree(
            N root, int size) {
        Deque<N> stack = new ArrayDeque<>();
        N node = root;
        K previous = null;
        int count = 0;
        while (node != null || !stack.isEmpty()) {
            while (node != null) {
                stack.push(node);
                node = node.left;
            }
            N current = stack.pop();
            if (current.key == null) {
                throw new IllegalStateException("节点键为 null");
            }
            if (previous != null && previous.compareTo(current.key) >= 0) {
                throw new IllegalStateException("中序遍历键未严格递增：" + previous + " 之后为 " + current.key);
            }
            previous = current.key;
            count++;
            node = current.right;
        }
        if (count != size) {
            throw new IllegalStateException("节点数 " + count + " 与 size " + size + " 不一致");
        }
    }
}
