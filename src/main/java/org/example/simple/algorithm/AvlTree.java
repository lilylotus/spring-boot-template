package org.example.simple.algorithm;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * AVL 树：任一节点左右子树高度差（平衡因子）的绝对值不超过 1 的严格平衡二叉搜索树。
 * <p>
 * 实现：每个节点保存以自身为根的子树高度；插入与删除采用递归，回溯时自底向上更新高度，
 * 并按失衡形态执行旋转：LL 型右旋、RR 型左旋、LR 型先左旋左子节点再右旋、RL 型先右旋右子节点再左旋。
 * 树高为 O(log n)，递归深度安全。
 * <p>
 * 复杂度：查找、插入、删除最坏 O(log n)；树高不超过约 {@code 1.44·log₂(n+2)}。插入至多 2 次旋转，
 * 删除可能沿路径旋转 O(log n) 次。空间 O(n)，每个节点额外保存高度。
 * <p>
 * 与 {@link RedBlackTree} 相比：AVL 树更矮，查找更快，但插入删除需要更多旋转；适合读多写少的场景。
 * <p>
 * 通用约定见 {@link OrderedMap}。
 *
 * @param <K> 键类型
 * @param <V> 值类型
 */
public class AvlTree<K extends Comparable<? super K>, V> implements OrderedMap<K, V> {

    /** 根节点，空树时为 {@code null}。 */
    private Node<K, V> root;

    /** 键值对个数。 */
    private int size;

    /** 结构修改次数，用于迭代器 fail-fast 检测。 */
    private int modCount;

    /** 递归插入或删除过程中记录的旧值，供公共方法返回。 */
    private V lastValue;

    /** 递归插入或删除过程中记录是否找到了目标键。 */
    private boolean keyFound;

    /** 创建空 AVL 树。 */
    public AvlTree() {
    }

    /** 写入键值对，O(log n)。 */
    @Override
    public V put(K key, V value) {
        OrderedMaps.requireKey(key);
        keyFound = false;
        lastValue = null;
        root = insert(root, key, value);
        V old = lastValue;
        lastValue = null;
        if (!keyFound) {
            size++;
            modCount++;
        }
        return old;
    }

    /** 返回键对应的值，O(log n)。 */
    @Override
    public V get(K key) {
        Node<K, V> node = BinaryTrees.find(root, OrderedMaps.requireKey(key));
        return node == null ? null : node.value;
    }

    /** 判断键是否存在，O(log n)。 */
    @Override
    public boolean containsKey(K key) {
        return BinaryTrees.find(root, OrderedMaps.requireKey(key)) != null;
    }

    /** 删除键并返回其值，O(log n)。 */
    @Override
    public V remove(K key) {
        OrderedMaps.requireKey(key);
        keyFound = false;
        lastValue = null;
        root = delete(root, key);
        V removed = lastValue;
        lastValue = null;
        if (keyFound) {
            size--;
            modCount++;
        }
        return removed;
    }

    /** 返回键值对个数。 */
    @Override
    public int size() {
        return size;
    }

    /** 删除全部键值对。 */
    @Override
    public void clear() {
        root = null;
        size = 0;
        modCount++;
    }

    /** 返回最小键，O(log n)。 */
    @Override
    public K minKey() {
        return BinaryTrees.minKey(root);
    }

    /** 返回最大键，O(log n)。 */
    @Override
    public K maxKey() {
        return BinaryTrees.maxKey(root);
    }

    /** 返回小于等于给定键的最大键，O(log n)。 */
    @Override
    public K floorKey(K key) {
        return BinaryTrees.floorKey(root, OrderedMaps.requireKey(key));
    }

    /** 返回大于等于给定键的最小键，O(log n)。 */
    @Override
    public K ceilingKey(K key) {
        return BinaryTrees.ceilingKey(root, OrderedMaps.requireKey(key));
    }

    /** 返回闭区间内的键值对，O(log n + k)。 */
    @Override
    public List<Map.Entry<K, V>> range(K fromInclusive, K toInclusive) {
        return BinaryTrees.range(root, OrderedMaps.requireKey(fromInclusive), OrderedMaps.requireKey(toInclusive));
    }

    /** 返回树高，直接读取根节点保存的高度，O(1)。 */
    @Override
    public int height() {
        return heightOf(root);
    }

    /** 返回按键升序的 fail-fast 迭代器。 */
    @Override
    public Iterator<Map.Entry<K, V>> iterator() {
        return OrderedMaps.failFast(BinaryTrees.iterator(root), () -> modCount);
    }

    /**
     * 校验 AVL 性质：二叉搜索树性质成立，每个节点保存的高度正确，平衡因子在 [-1, 1] 内。
     *
     * @throws IllegalStateException 性质不成立时抛出
     */
    void verifyInvariants() {
        BinaryTrees.verifySearchTree(root, size);
        checkBalance(root);
    }

    /** 递归计算子树实际高度并校验保存的高度与平衡因子。 */
    private int checkBalance(Node<K, V> node) {
        if (node == null) {
            return 0;
        }
        int leftHeight = checkBalance(node.left);
        int rightHeight = checkBalance(node.right);
        if (Math.abs(leftHeight - rightHeight) > 1) {
            throw new IllegalStateException("节点 " + node.key + " 平衡因子超出 [-1, 1]");
        }
        int actual = Math.max(leftHeight, rightHeight) + 1;
        if (node.height != actual) {
            throw new IllegalStateException("节点 " + node.key + " 保存的高度 " + node.height + " 与实际高度 "
                    + actual + " 不一致");
        }
        return actual;
    }

    /** 在子树中插入键值对，返回重新平衡后的子树根。 */
    private Node<K, V> insert(Node<K, V> node, K key, V value) {
        if (node == null) {
            return new Node<>(key, value);
        }
        int cmp = key.compareTo(node.key);
        if (cmp == 0) {
            keyFound = true;
            lastValue = node.value;
            node.value = value;
            return node;
        }
        if (cmp < 0) {
            node.left = insert(node.left, key, value);
        } else {
            node.right = insert(node.right, key, value);
        }
        return rebalance(node);
    }

    /** 在子树中删除键，返回重新平衡后的子树根。 */
    private Node<K, V> delete(Node<K, V> node, K key) {
        if (node == null) {
            return null;
        }
        int cmp = key.compareTo(node.key);
        if (cmp < 0) {
            node.left = delete(node.left, key);
        } else if (cmp > 0) {
            node.right = delete(node.right, key);
        } else {
            keyFound = true;
            lastValue = node.value;
            if (node.left == null) {
                return node.right;
            }
            if (node.right == null) {
                return node.left;
            }
            // 两个子节点：用右子树最小节点替换当前节点，再从右子树中删除该最小节点
            Node<K, V> successor = BinaryTrees.minNode(node.right);
            node.key = successor.key;
            node.value = successor.value;
            node.right = deleteMin(node.right);
        }
        return rebalance(node);
    }

    /** 删除子树中的最小节点，返回重新平衡后的子树根。 */
    private Node<K, V> deleteMin(Node<K, V> node) {
        if (node.left == null) {
            return node.right;
        }
        node.left = deleteMin(node.left);
        return rebalance(node);
    }

    /** 更新节点高度，并在平衡因子超出 [-1, 1] 时按 LL、LR、RR、RL 四种形态旋转。 */
    private Node<K, V> rebalance(Node<K, V> node) {
        updateHeight(node);
        int balance = balanceFactor(node);
        if (balance > 1) {
            if (balanceFactor(node.left) < 0) {
                // LR 型：先把左子树转为 LL 型
                node.left = rotateLeft(node.left);
            }
            return rotateRight(node);
        }
        if (balance < -1) {
            if (balanceFactor(node.right) > 0) {
                // RL 型：先把右子树转为 RR 型
                node.right = rotateRight(node.right);
            }
            return rotateLeft(node);
        }
        return node;
    }

    /** 右旋：左子节点上升为子树根，返回新根。 */
    private Node<K, V> rotateRight(Node<K, V> node) {
        Node<K, V> pivot = node.left;
        node.left = pivot.right;
        pivot.right = node;
        updateHeight(node);
        updateHeight(pivot);
        return pivot;
    }

    /** 左旋：右子节点上升为子树根，返回新根。 */
    private Node<K, V> rotateLeft(Node<K, V> node) {
        Node<K, V> pivot = node.right;
        node.right = pivot.left;
        pivot.left = node;
        updateHeight(node);
        updateHeight(pivot);
        return pivot;
    }

    /** 根据子节点高度重新计算节点高度。 */
    private static void updateHeight(Node<?, ?> node) {
        node.height = Math.max(heightOf(node.left), heightOf(node.right)) + 1;
    }

    /** 平衡因子：左子树高度减右子树高度。 */
    private static int balanceFactor(Node<?, ?> node) {
        return heightOf(node.left) - heightOf(node.right);
    }

    /** 返回子树高度，空子树为 0。 */
    private static int heightOf(Node<?, ?> node) {
        return node == null ? 0 : node.height;
    }

    /**
     * AVL 树节点，额外保存子树高度。
     *
     * @param <K> 键类型
     * @param <V> 值类型
     */
    private static final class Node<K, V> extends BinaryNode<K, V, Node<K, V>> {

        /** 以本节点为根的子树高度，叶子节点为 1。 */
        private int height = 1;

        /** 使用键值创建节点。 */
        private Node(K key, V value) {
            super(key, value);
        }
    }
}
