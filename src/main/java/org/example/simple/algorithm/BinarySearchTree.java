package org.example.simple.algorithm;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 普通（不自平衡）二叉搜索树：任一节点左子树的键都小于它、右子树的键都大于它。
 * <p>
 * 复杂度：查找、插入、删除均为 O(h)，h 为树高。随机顺序插入时期望 h = O(log n)；
 * 按有序或近乎有序的顺序插入时退化为链表，h = n，各操作退化为 O(n)。空间 O(n)。
 * <p>
 * 实现：全部操作（含删除与遍历）均为迭代实现，退化为链表时也不会栈溢出。删除有两个子节点的节点时，
 * 用其右子树的最小节点（中序后继）的键值替换它，再删除该后继节点。
 * <p>
 * 适用场景：教学演示、键的插入顺序足够随机且无需最坏情况保证的场景。需要稳定 O(log n) 时请使用
 * {@link RedBlackTree} 或 {@link AvlTree}。
 * <p>
 * 通用约定见 {@link OrderedMap}。
 *
 * @param <K> 键类型
 * @param <V> 值类型
 */
public class BinarySearchTree<K extends Comparable<? super K>, V> implements OrderedMap<K, V> {

    /** 根节点，空树时为 {@code null}。 */
    private Node<K, V> root;

    /** 键值对个数。 */
    private int size;

    /** 结构修改次数，用于迭代器 fail-fast 检测。 */
    private int modCount;

    /** 创建空二叉搜索树。 */
    public BinarySearchTree() {
    }

    /** 写入键值对，O(h)。键已存在时覆盖值并返回旧值。 */
    @Override
    public V put(K key, V value) {
        OrderedMaps.requireKey(key);
        if (root == null) {
            root = new Node<>(key, value);
            size++;
            modCount++;
            return null;
        }
        Node<K, V> node = root;
        while (true) {
            int cmp = key.compareTo(node.key);
            if (cmp == 0) {
                V old = node.value;
                node.value = value;
                return old;
            }
            Node<K, V> child = cmp < 0 ? node.left : node.right;
            if (child == null) {
                if (cmp < 0) {
                    node.left = new Node<>(key, value);
                } else {
                    node.right = new Node<>(key, value);
                }
                size++;
                modCount++;
                return null;
            }
            node = child;
        }
    }

    /** 返回键对应的值，O(h)。 */
    @Override
    public V get(K key) {
        Node<K, V> node = BinaryTrees.find(root, OrderedMaps.requireKey(key));
        return node == null ? null : node.value;
    }

    /** 判断键是否存在，O(h)。 */
    @Override
    public boolean containsKey(K key) {
        return BinaryTrees.find(root, OrderedMaps.requireKey(key)) != null;
    }

    /** 删除键并返回其值，O(h)。 */
    @Override
    public V remove(K key) {
        OrderedMaps.requireKey(key);
        Node<K, V> parent = null;
        Node<K, V> node = root;
        while (node != null) {
            int cmp = key.compareTo(node.key);
            if (cmp == 0) {
                break;
            }
            parent = node;
            node = cmp < 0 ? node.left : node.right;
        }
        if (node == null) {
            return null;
        }
        V removed = node.value;
        if (node.left != null && node.right != null) {
            // 两个子节点：找到中序后继（右子树最小节点），把它的键值搬到当前节点，转为删除后继
            Node<K, V> successorParent = node;
            Node<K, V> successor = node.right;
            while (successor.left != null) {
                successorParent = successor;
                successor = successor.left;
            }
            node.key = successor.key;
            node.value = successor.value;
            parent = successorParent;
            node = successor;
        }
        // 此时 node 至多有一个子节点，用该子节点顶替 node 的位置
        Node<K, V> child = node.left != null ? node.left : node.right;
        if (parent == null) {
            root = child;
        } else if (parent.left == node) {
            parent.left = child;
        } else {
            parent.right = child;
        }
        size--;
        modCount++;
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

    /** 返回最小键，O(h)。 */
    @Override
    public K minKey() {
        return BinaryTrees.minKey(root);
    }

    /** 返回最大键，O(h)。 */
    @Override
    public K maxKey() {
        return BinaryTrees.maxKey(root);
    }

    /** 返回小于等于给定键的最大键，O(h)。 */
    @Override
    public K floorKey(K key) {
        return BinaryTrees.floorKey(root, OrderedMaps.requireKey(key));
    }

    /** 返回大于等于给定键的最小键，O(h)。 */
    @Override
    public K ceilingKey(K key) {
        return BinaryTrees.ceilingKey(root, OrderedMaps.requireKey(key));
    }

    /** 返回闭区间内的键值对，O(h + k)。 */
    @Override
    public List<Map.Entry<K, V>> range(K fromInclusive, K toInclusive) {
        return BinaryTrees.range(root, OrderedMaps.requireKey(fromInclusive), OrderedMaps.requireKey(toInclusive));
    }

    /** 返回树高（根到最深节点路径上的节点数），O(n)。 */
    @Override
    public int height() {
        return BinaryTrees.height(root);
    }

    /** 返回按键升序的 fail-fast 迭代器。 */
    @Override
    public Iterator<Map.Entry<K, V>> iterator() {
        return OrderedMaps.failFast(BinaryTrees.iterator(root), () -> modCount);
    }

    /**
     * 校验二叉搜索树性质：中序遍历键严格递增，节点数等于 {@code size}。
     *
     * @throws IllegalStateException 性质不成立时抛出
     */
    void verifyInvariants() {
        BinaryTrees.verifySearchTree(root, size);
    }

    /**
     * 二叉搜索树节点。
     *
     * @param <K> 键类型
     * @param <V> 值类型
     */
    private static final class Node<K, V> extends BinaryNode<K, V, Node<K, V>> {

        /** 使用键值创建节点。 */
        private Node(K key, V value) {
            super(key, value);
        }
    }
}
