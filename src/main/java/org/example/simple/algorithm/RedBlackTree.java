package org.example.simple.algorithm;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 红黑树：通过节点着色与旋转保持近似平衡的二叉搜索树（CLRS 经典版本）。
 * <p>
 * 红黑性质：
 * <ol>
 *   <li>每个节点为红色或黑色；</li>
 *   <li>根节点为黑色；</li>
 *   <li>叶子（空节点 NIL，本实现以 {@code null} 表示）为黑色；</li>
 *   <li>红色节点的子节点都是黑色（不存在相邻红节点）；</li>
 *   <li>从任一节点到其后代叶子的所有路径包含相同数目的黑色节点（黑高相同）。</li>
 * </ol>
 * 由此可证树高不超过 {@code 2·log₂(n+1)}。
 * <p>
 * 复杂度：查找、插入、删除最坏 O(log n)；插入至多 2 次旋转，删除至多 3 次旋转。空间 O(n)，
 * 每个节点额外保存父指针与颜色。
 * <p>
 * 与 {@link AvlTree} 相比：红黑树平衡更宽松、树更高，查找略慢，但插入删除的旋转次数更少，
 * 适合写多读多的通用场景；JDK 的 {@link java.util.TreeMap} 与 {@link java.util.HashMap} 的树化桶均采用红黑树。
 * <p>
 * 通用约定见 {@link OrderedMap}。
 *
 * @param <K> 键类型
 * @param <V> 值类型
 */
public class RedBlackTree<K extends Comparable<? super K>, V> implements OrderedMap<K, V> {

    /** 红色。 */
    private static final boolean RED = true;

    /** 黑色。 */
    private static final boolean BLACK = false;

    /** 根节点，空树时为 {@code null}。 */
    private Node<K, V> root;

    /** 键值对个数。 */
    private int size;

    /** 结构修改次数，用于迭代器 fail-fast 检测。 */
    private int modCount;

    /** 创建空红黑树。 */
    public RedBlackTree() {
    }

    /** 写入键值对，O(log n)。新节点着红色后自底向上修复红黑性质。 */
    @Override
    public V put(K key, V value) {
        OrderedMaps.requireKey(key);
        Node<K, V> parent = null;
        Node<K, V> node = root;
        int cmp = 0;
        while (node != null) {
            parent = node;
            cmp = key.compareTo(node.key);
            if (cmp == 0) {
                V old = node.value;
                node.value = value;
                return old;
            }
            node = cmp < 0 ? node.left : node.right;
        }
        Node<K, V> inserted = new Node<>(key, value, parent);
        if (parent == null) {
            root = inserted;
        } else if (cmp < 0) {
            parent.left = inserted;
        } else {
            parent.right = inserted;
        }
        fixAfterInsertion(inserted);
        size++;
        modCount++;
        return null;
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
        Node<K, V> node = BinaryTrees.find(root, OrderedMaps.requireKey(key));
        if (node == null) {
            return null;
        }
        V removed = node.value;
        deleteNode(node);
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
     * 校验红黑树全部性质：二叉搜索树性质、父指针一致、根为黑色、无相邻红节点、各路径黑高相同。
     *
     * @throws IllegalStateException 性质不成立时抛出
     */
    void verifyInvariants() {
        BinaryTrees.verifySearchTree(root, size);
        if (root == null) {
            return;
        }
        if (root.parent != null) {
            throw new IllegalStateException("根节点的父指针不为 null");
        }
        if (root.color != BLACK) {
            throw new IllegalStateException("根节点不是黑色");
        }
        blackHeight(root);
    }

    /** 递归计算子树黑高并同时校验父指针与红节点约束；红黑树高度为 O(log n)，递归深度安全。 */
    private int blackHeight(Node<K, V> node) {
        if (node == null) {
            return 1;
        }
        if ((node.left != null && node.left.parent != node) || (node.right != null && node.right.parent != node)) {
            throw new IllegalStateException("节点 " + node.key + " 的子节点父指针不一致");
        }
        if (node.color == RED && (colorOf(node.left) == RED || colorOf(node.right) == RED)) {
            throw new IllegalStateException("红色节点 " + node.key + " 存在红色子节点");
        }
        int leftHeight = blackHeight(node.left);
        int rightHeight = blackHeight(node.right);
        if (leftHeight != rightHeight) {
            throw new IllegalStateException("节点 " + node.key + " 左右子树黑高不同");
        }
        return leftHeight + (node.color == BLACK ? 1 : 0);
    }

    /**
     * 删除节点并修复红黑性质。
     * <p>
     * 若节点有两个子节点，先与中序后继交换键值，转为删除后继（后继至多有一个右子节点）。
     * 若被摘除的节点为黑色，会使所在路径黑高减一，需从顶替位置开始执行删除修复。
     * 被删节点没有子节点时，先把它当作"虚拟 NIL"执行修复，再从父节点上摘除，以免修复过程缺少父节点信息。
     */
    private void deleteNode(Node<K, V> target) {
        Node<K, V> node = target;
        if (node.left != null && node.right != null) {
            Node<K, V> successor = BinaryTrees.minNode(node.right);
            node.key = successor.key;
            node.value = successor.value;
            node = successor;
        }
        Node<K, V> replacement = node.left != null ? node.left : node.right;
        if (replacement != null) {
            replacement.parent = node.parent;
            replaceChild(node.parent, node, replacement);
            node.left = null;
            node.right = null;
            node.parent = null;
            if (node.color == BLACK) {
                fixAfterDeletion(replacement);
            }
        } else if (node.parent == null) {
            root = null;
        } else {
            if (node.color == BLACK) {
                fixAfterDeletion(node);
            }
            if (node.parent != null) {
                replaceChild(node.parent, node, null);
                node.parent = null;
            }
        }
    }

    /** 插入修复：处理"叔叔为红"（重新着色并上移）与"叔叔为黑"（旋转）两类情况。 */
    private void fixAfterInsertion(Node<K, V> inserted) {
        Node<K, V> node = inserted;
        node.color = RED;
        while (node != root && colorOf(parentOf(node)) == RED) {
            Node<K, V> parent = parentOf(node);
            Node<K, V> grandparent = parentOf(parent);
            if (parent == leftOf(grandparent)) {
                Node<K, V> uncle = rightOf(grandparent);
                if (colorOf(uncle) == RED) {
                    // 情况 1：叔叔为红，父与叔变黑、祖父变红，问题上移到祖父
                    setColor(parent, BLACK);
                    setColor(uncle, BLACK);
                    setColor(grandparent, RED);
                    node = grandparent;
                } else {
                    if (node == rightOf(parent)) {
                        // 情况 2：内侧子节点，先左旋转为外侧
                        node = parent;
                        rotateLeft(node);
                    }
                    // 情况 3：外侧子节点，父变黑、祖父变红后右旋祖父
                    setColor(parentOf(node), BLACK);
                    setColor(parentOf(parentOf(node)), RED);
                    rotateRight(parentOf(parentOf(node)));
                }
            } else {
                Node<K, V> uncle = leftOf(grandparent);
                if (colorOf(uncle) == RED) {
                    setColor(parent, BLACK);
                    setColor(uncle, BLACK);
                    setColor(grandparent, RED);
                    node = grandparent;
                } else {
                    if (node == leftOf(parent)) {
                        node = parent;
                        rotateRight(node);
                    }
                    setColor(parentOf(node), BLACK);
                    setColor(parentOf(parentOf(node)), RED);
                    rotateLeft(parentOf(parentOf(node)));
                }
            }
        }
        root.color = BLACK;
    }

    /** 删除修复：节点 {@code node} 所在路径缺少一个黑节点，按兄弟节点颜色分 4 种情况处理。 */
    private void fixAfterDeletion(Node<K, V> start) {
        Node<K, V> node = start;
        while (node != root && colorOf(node) == BLACK) {
            if (node == leftOf(parentOf(node))) {
                Node<K, V> sibling = rightOf(parentOf(node));
                if (colorOf(sibling) == RED) {
                    // 情况 1：兄弟为红，旋转使兄弟变为黑色，转为情况 2～4
                    setColor(sibling, BLACK);
                    setColor(parentOf(node), RED);
                    rotateLeft(parentOf(node));
                    sibling = rightOf(parentOf(node));
                }
                if (colorOf(leftOf(sibling)) == BLACK && colorOf(rightOf(sibling)) == BLACK) {
                    // 情况 2：兄弟的两个子节点都为黑，兄弟变红，问题上移到父节点
                    setColor(sibling, RED);
                    node = parentOf(node);
                } else {
                    if (colorOf(rightOf(sibling)) == BLACK) {
                        // 情况 3：兄弟的近侧子节点为红，旋转兄弟转为情况 4
                        setColor(leftOf(sibling), BLACK);
                        setColor(sibling, RED);
                        rotateRight(sibling);
                        sibling = rightOf(parentOf(node));
                    }
                    // 情况 4：兄弟的远侧子节点为红，旋转父节点补足黑高，修复结束
                    setColor(sibling, colorOf(parentOf(node)));
                    setColor(parentOf(node), BLACK);
                    setColor(rightOf(sibling), BLACK);
                    rotateLeft(parentOf(node));
                    node = root;
                }
            } else {
                Node<K, V> sibling = leftOf(parentOf(node));
                if (colorOf(sibling) == RED) {
                    setColor(sibling, BLACK);
                    setColor(parentOf(node), RED);
                    rotateRight(parentOf(node));
                    sibling = leftOf(parentOf(node));
                }
                if (colorOf(rightOf(sibling)) == BLACK && colorOf(leftOf(sibling)) == BLACK) {
                    setColor(sibling, RED);
                    node = parentOf(node);
                } else {
                    if (colorOf(leftOf(sibling)) == BLACK) {
                        setColor(rightOf(sibling), BLACK);
                        setColor(sibling, RED);
                        rotateLeft(sibling);
                        sibling = leftOf(parentOf(node));
                    }
                    setColor(sibling, colorOf(parentOf(node)));
                    setColor(parentOf(node), BLACK);
                    setColor(leftOf(sibling), BLACK);
                    rotateRight(parentOf(node));
                    node = root;
                }
            }
        }
        setColor(node, BLACK);
    }

    /** 以 {@code node} 为支点左旋：其右子节点上升为子树根。 */
    private void rotateLeft(Node<K, V> node) {
        if (node == null) {
            return;
        }
        Node<K, V> pivot = node.right;
        node.right = pivot.left;
        if (pivot.left != null) {
            pivot.left.parent = node;
        }
        pivot.parent = node.parent;
        replaceChild(node.parent, node, pivot);
        pivot.left = node;
        node.parent = pivot;
    }

    /** 以 {@code node} 为支点右旋：其左子节点上升为子树根。 */
    private void rotateRight(Node<K, V> node) {
        if (node == null) {
            return;
        }
        Node<K, V> pivot = node.left;
        node.left = pivot.right;
        if (pivot.right != null) {
            pivot.right.parent = node;
        }
        pivot.parent = node.parent;
        replaceChild(node.parent, node, pivot);
        pivot.right = node;
        node.parent = pivot;
    }

    /** 把父节点指向 {@code oldChild} 的引用改为 {@code newChild}；父节点为 {@code null} 时更新根。 */
    private void replaceChild(Node<K, V> parent, Node<K, V> oldChild, Node<K, V> newChild) {
        if (parent == null) {
            root = newChild;
        } else if (parent.left == oldChild) {
            parent.left = newChild;
        } else {
            parent.right = newChild;
        }
    }

    /** 返回节点颜色，空节点（NIL）视为黑色。 */
    private static boolean colorOf(Node<?, ?> node) {
        return node == null ? BLACK : node.color;
    }

    /** 设置节点颜色，空节点忽略。 */
    private static void setColor(Node<?, ?> node, boolean color) {
        if (node != null) {
            node.color = color;
        }
    }

    /** 返回父节点，空节点返回 {@code null}。 */
    private static <K, V> Node<K, V> parentOf(Node<K, V> node) {
        return node == null ? null : node.parent;
    }

    /** 返回左子节点，空节点返回 {@code null}。 */
    private static <K, V> Node<K, V> leftOf(Node<K, V> node) {
        return node == null ? null : node.left;
    }

    /** 返回右子节点，空节点返回 {@code null}。 */
    private static <K, V> Node<K, V> rightOf(Node<K, V> node) {
        return node == null ? null : node.right;
    }

    /**
     * 红黑树节点，额外保存父指针与颜色。
     *
     * @param <K> 键类型
     * @param <V> 值类型
     */
    private static final class Node<K, V> extends BinaryNode<K, V, Node<K, V>> {

        /** 父节点，根节点为 {@code null}。 */
        private Node<K, V> parent;

        /** 节点颜色，新节点默认为黑色，插入修复时会先置为红色。 */
        private boolean color = BLACK;

        /** 使用键值与父节点创建节点。 */
        private Node(K key, V value, Node<K, V> parent) {
            super(key, value);
            this.parent = parent;
        }
    }
}
