package org.example.simple.algorithm;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * B 树：所有叶子位于同一层的多路平衡搜索树（CLRS 经典版本，以最小度数 {@code t} 描述）。
 * <p>
 * 结构约束：
 * <ul>
 *   <li>每个节点的键有序，非根节点键数在 {@code [t-1, 2t-1]} 内，根节点在 {@code [1, 2t-1]} 内；</li>
 *   <li>内部节点的子节点数等于键数加一，第 i 个子树中的键介于第 i-1 与第 i 个键之间；</li>
 *   <li>键和值同时存放在内部节点与叶子中；所有叶子深度相同。</li>
 * </ul>
 * <p>
 * 实现：插入自顶向下，沿途预先分裂已满（2t-1 个键）的节点，保证插入到叶子时不会再向上传播分裂；
 * 删除同样自顶向下，下降前保证目标子节点至少有 t 个键（向兄弟借位或与兄弟合并），删除后若根变空则树高减一。
 * 节点内使用有序 {@link ArrayList} 存放键，并以二分查找定位。
 * <p>
 * 复杂度：树高 O(log_t n)；查找 O(log n)（每层二分 O(log t)）；插入、删除 O(t·log_t n)（节点内移动元素）。
 * 空间 O(n)。
 * <p>
 * 适用场景：数据库与文件系统的索引结构，节点对应磁盘页，矮胖的树形使一次查找只需少量磁盘读取。
 * 本实现为纯内存演示，节点大小以键数度量，不涉及磁盘页管理。
 * <p>
 * 通用约定见 {@link OrderedMap}。
 *
 * @param <K> 键类型
 * @param <V> 值类型
 */
public class BTree<K extends Comparable<? super K>, V> implements OrderedMap<K, V> {

    /** 默认最小度数。 */
    public static final int DEFAULT_MIN_DEGREE = 3;

    /** 最小度数 t：非根节点至少 t-1 个键，任一节点至多 2t-1 个键。 */
    private final int minDegree;

    /** 根节点，空树时为 {@code null}。 */
    private Node<K, V> root;

    /** 键值对个数。 */
    private int size;

    /** 结构修改次数，用于迭代器 fail-fast 检测。 */
    private int modCount;

    /** 使用默认最小度数 {@value #DEFAULT_MIN_DEGREE} 创建空 B 树。 */
    public BTree() {
        this(DEFAULT_MIN_DEGREE);
    }

    /**
     * 使用指定最小度数创建空 B 树。
     *
     * @param minDegree 最小度数 t，必须不小于 2（t = 2 即 2-3-4 树）
     * @throws IllegalArgumentException 最小度数小于 2 时抛出
     */
    public BTree(int minDegree) {
        if (minDegree < 2) {
            throw new IllegalArgumentException("B 树最小度数必须不小于 2：" + minDegree);
        }
        this.minDegree = minDegree;
    }

    /** 写入键值对，O(t·log_t n)。 */
    @Override
    public V put(K key, V value) {
        OrderedMaps.requireKey(key);
        if (root == null) {
            root = new Node<>();
            root.keys.add(key);
            root.values.add(value);
            size++;
            modCount++;
            return null;
        }
        // 键已存在时只覆盖值，避免为一次覆盖而执行不必要的预分裂
        Node<K, V> node = root;
        while (true) {
            int index = Collections.binarySearch(node.keys, key);
            if (index >= 0) {
                return node.values.set(index, value);
            }
            if (node.isLeaf()) {
                break;
            }
            node = node.children.get(-index - 1);
        }
        if (root.keys.size() == maxKeys()) {
            // 根已满：新建根并分裂旧根，这是 B 树长高的唯一方式
            Node<K, V> newRoot = new Node<>();
            newRoot.children.add(root);
            splitChild(newRoot, 0);
            root = newRoot;
        }
        insertNonFull(root, key, value);
        size++;
        modCount++;
        return null;
    }

    /** 返回键对应的值，O(log n)。 */
    @Override
    public V get(K key) {
        OrderedMaps.requireKey(key);
        Node<K, V> node = root;
        while (node != null) {
            int index = Collections.binarySearch(node.keys, key);
            if (index >= 0) {
                return node.values.get(index);
            }
            node = node.isLeaf() ? null : node.children.get(-index - 1);
        }
        return null;
    }

    /** 判断键是否存在，O(log n)。 */
    @Override
    public boolean containsKey(K key) {
        OrderedMaps.requireKey(key);
        Node<K, V> node = root;
        while (node != null) {
            int index = Collections.binarySearch(node.keys, key);
            if (index >= 0) {
                return true;
            }
            node = node.isLeaf() ? null : node.children.get(-index - 1);
        }
        return false;
    }

    /** 删除键并返回其值，O(t·log_t n)。键不存在时不调整树结构。 */
    @Override
    public V remove(K key) {
        if (!containsKey(key)) {
            return null;
        }
        V removed = delete(root, key);
        if (root.keys.isEmpty()) {
            // 根的键被合并下移后变空：以唯一子节点为新根，树高减一；根本身是叶子时树变空
            root = root.isLeaf() ? null : root.children.get(0);
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

    /** 返回最小键：沿最左子节点下降到叶子，O(log_t n)。 */
    @Override
    public K minKey() {
        if (root == null) {
            throw new NoSuchElementException("B 树为空");
        }
        Node<K, V> node = root;
        while (!node.isLeaf()) {
            node = node.children.get(0);
        }
        return node.keys.get(0);
    }

    /** 返回最大键：沿最右子节点下降到叶子，O(log_t n)。 */
    @Override
    public K maxKey() {
        if (root == null) {
            throw new NoSuchElementException("B 树为空");
        }
        return lastLeaf(root).keys.getLast();
    }

    /** 返回小于等于给定键的最大键：下降时记录插入位置左侧的键作为候选，O(log n)。 */
    @Override
    public K floorKey(K key) {
        OrderedMaps.requireKey(key);
        K candidate = null;
        Node<K, V> node = root;
        while (node != null) {
            int index = Collections.binarySearch(node.keys, key);
            if (index >= 0) {
                return node.keys.get(index);
            }
            int position = -index - 1;
            if (position > 0) {
                candidate = node.keys.get(position - 1);
            }
            node = node.isLeaf() ? null : node.children.get(position);
        }
        return candidate;
    }

    /** 返回大于等于给定键的最小键：下降时记录插入位置处的键作为候选，O(log n)。 */
    @Override
    public K ceilingKey(K key) {
        OrderedMaps.requireKey(key);
        K candidate = null;
        Node<K, V> node = root;
        while (node != null) {
            int index = Collections.binarySearch(node.keys, key);
            if (index >= 0) {
                return node.keys.get(index);
            }
            int position = -index - 1;
            if (position < node.keys.size()) {
                candidate = node.keys.get(position);
            }
            node = node.isLeaf() ? null : node.children.get(position);
        }
        return candidate;
    }

    /** 返回闭区间内的键值对：中序遍历并跳过整体小于下界的子树，遇到大于上界的键即停止，O(log n + k)。 */
    @Override
    public List<Map.Entry<K, V>> range(K fromInclusive, K toInclusive) {
        OrderedMaps.requireKey(fromInclusive);
        OrderedMaps.requireKey(toInclusive);
        List<Map.Entry<K, V>> result = new ArrayList<>();
        if (root != null && fromInclusive.compareTo(toInclusive) <= 0) {
            collectRange(root, fromInclusive, toInclusive, result);
        }
        return result;
    }

    /** 返回树的层数，O(log_t n)。 */
    @Override
    public int height() {
        int height = 0;
        for (Node<K, V> node = root; node != null; node = node.isLeaf() ? null : node.children.get(0)) {
            height++;
        }
        return height;
    }

    /**
     * 返回最小度数。
     *
     * @return 构造时指定的最小度数 t
     */
    public int minDegree() {
        return minDegree;
    }

    /** 返回按键升序的 fail-fast 迭代器（基于显式栈的中序遍历，额外空间 O(log_t n)）。 */
    @Override
    public Iterator<Map.Entry<K, V>> iterator() {
        return OrderedMaps.failFast(new InOrderIterator(), () -> modCount);
    }

    /**
     * 校验 B 树性质：键严格有序且满足搜索树区间约束，节点键数满足度数约束，内部节点子节点数等于键数加一，
     * 所有叶子同层，键总数等于 {@code size}。
     *
     * @throws IllegalStateException 性质不成立时抛出
     */
    void verifyInvariants() {
        if (root == null) {
            if (size != 0) {
                throw new IllegalStateException("空树的 size 不为 0");
            }
            return;
        }
        int[] counter = new int[1];
        int[] leafDepth = {-1};
        verifyNode(root, null, null, 0, counter, leafDepth);
        if (counter[0] != size) {
            throw new IllegalStateException("键总数 " + counter[0] + " 与 size " + size + " 不一致");
        }
    }

    /** 递归校验单个节点及其子树；{@code low}、{@code high} 为开区间边界，{@code null} 表示无界。 */
    private void verifyNode(
            Node<K, V> node, K low,
            K high, int depth,
            int[] counter, int[] leafDepth) {
        int keyCount = node.keys.size();
        int minKeys = node == root ? 1 : minDegree - 1;
        if (keyCount < minKeys || keyCount > maxKeys()) {
            throw new IllegalStateException("节点键数 " + keyCount + " 超出范围 [" + minKeys + ", " + maxKeys() + "]");
        }
        if (node.values.size() != keyCount) {
            throw new IllegalStateException("节点值个数与键个数不一致");
        }
        for (int i = 0; i < keyCount; i++) {
            K key = node.keys.get(i);
            boolean belowLow = low != null && key.compareTo(low) <= 0;
            boolean aboveHigh = high != null && key.compareTo(high) >= 0;
            boolean unordered = i > 0 && node.keys.get(i - 1).compareTo(key) >= 0;
            if (belowLow || aboveHigh || unordered) {
                throw new IllegalStateException("键 " + key + " 违反有序或区间约束");
            }
        }
        counter[0] += keyCount;
        if (node.isLeaf()) {
            if (leafDepth[0] < 0) {
                leafDepth[0] = depth;
            } else if (leafDepth[0] != depth) {
                throw new IllegalStateException("叶子不在同一层");
            }
            return;
        }
        if (node.children.size() != keyCount + 1) {
            throw new IllegalStateException("内部节点子节点数不等于键数加一");
        }
        for (int i = 0; i <= keyCount; i++) {
            K childLow = i == 0 ? low : node.keys.get(i - 1);
            K childHigh = i == keyCount ? high : node.keys.get(i);
            verifyNode(node.children.get(i), childLow, childHigh, depth + 1, counter, leafDepth);
        }
    }

    /** 单个节点允许的最大键数 2t-1。 */
    private int maxKeys() {
        return 2 * minDegree - 1;
    }

    /**
     * 分裂父节点的第 {@code index} 个子节点（该子节点已满，含 2t-1 个键）：
     * 前 t-1 个键留在原节点，第 t 个键（中位键）上移到父节点，后 t-1 个键与对应子节点移入新右兄弟。
     */
    private void splitChild(Node<K, V> parent, int index) {
        Node<K, V> full = parent.children.get(index);
        Node<K, V> right = new Node<>();
        int middle = minDegree - 1;
        right.keys.addAll(full.keys.subList(middle + 1, full.keys.size()));
        right.values.addAll(full.values.subList(middle + 1, full.values.size()));
        if (!full.isLeaf()) {
            List<Node<K, V>> movedChildren = full.children.subList(middle + 1, full.children.size());
            right.children.addAll(movedChildren);
            movedChildren.clear();
        }
        parent.keys.add(index, full.keys.get(middle));
        parent.values.add(index, full.values.get(middle));
        parent.children.add(index + 1, right);
        full.keys.subList(middle, full.keys.size()).clear();
        full.values.subList(middle, full.values.size()).clear();
    }

    /** 向未满节点插入新键（调用方保证键不存在）；下降前预先分裂已满的子节点。 */
    private void insertNonFull(Node<K, V> start, K key, V value) {
        Node<K, V> node = start;
        while (true) {
            int position = -Collections.binarySearch(node.keys, key) - 1;
            if (node.isLeaf()) {
                node.keys.add(position, key);
                node.values.add(position, value);
                return;
            }
            if (node.children.get(position).keys.size() == maxKeys()) {
                splitChild(node, position);
                // 分裂后中位键上移到 position 处，新键大于它时应进入右半部分
                if (key.compareTo(node.keys.get(position)) > 0) {
                    position++;
                }
            }
            node = node.children.get(position);
        }
    }

    /**
     * 从以 {@code node} 为根的子树中删除键（调用方保证键存在，且 {@code node} 为根或至少有 t 个键），返回被删值。
     * <p>
     * 情况 1：键在叶子中，直接删除。
     * 情况 2：键在内部节点中——左子节点至少 t 个键时用前驱替换；否则右子节点至少 t 个键时用后继替换；
     * 否则把键与右子节点合并进左子节点，再在合并后的节点中删除。
     * 情况 3：键不在当前节点——若将要下降的子节点只有 t-1 个键，先向兄弟借位或与兄弟合并，再下降。
     */
    private V delete(Node<K, V> node, K key) {
        int index = Collections.binarySearch(node.keys, key);
        if (index >= 0) {
            if (node.isLeaf()) {
                node.keys.remove(index);
                return node.values.remove(index);
            }
            V removed = node.values.get(index);
            Node<K, V> left = node.children.get(index);
            Node<K, V> right = node.children.get(index + 1);
            if (left.keys.size() >= minDegree) {
                Node<K, V> leaf = lastLeaf(left);
                K predecessorKey = leaf.keys.getLast();
                V predecessorValue = leaf.values.getLast();
                delete(left, predecessorKey);
                node.keys.set(index, predecessorKey);
                node.values.set(index, predecessorValue);
            } else if (right.keys.size() >= minDegree) {
                Node<K, V> leaf = right;
                while (!leaf.isLeaf()) {
                    leaf = leaf.children.get(0);
                }
                K successorKey = leaf.keys.get(0);
                V successorValue = leaf.values.get(0);
                delete(right, successorKey);
                node.keys.set(index, successorKey);
                node.values.set(index, successorValue);
            } else {
                merge(node, index);
                delete(left, key);
            }
            return removed;
        }
        int position = -index - 1;
        Node<K, V> child = node.children.get(position);
        if (child.keys.size() == minDegree - 1) {
            position = fillChild(node, position);
            child = node.children.get(position);
        }
        return delete(child, key);
    }

    /**
     * 保证父节点第 {@code position} 个子节点至少有 t 个键：优先向左兄弟借位，其次向右兄弟借位，否则与兄弟合并。
     *
     * @return 处理后目标键所在子节点的下标（与左兄弟合并时下标减一）
     */
    private int fillChild(Node<K, V> parent, int position) {
        Node<K, V> child = parent.children.get(position);
        Node<K, V> leftSibling = position > 0 ? parent.children.get(position - 1) : null;
        Node<K, V> rightSibling = position < parent.keys.size() ? parent.children.get(position + 1) : null;
        if (leftSibling != null && leftSibling.keys.size() >= minDegree) {
            // 父节点分隔键下移到子节点头部，左兄弟最大键上移为新分隔键
            child.keys.addFirst(parent.keys.get(position - 1));
            child.values.addFirst(parent.values.get(position - 1));
            parent.keys.set(position - 1, leftSibling.keys.removeLast());
            parent.values.set(position - 1, leftSibling.values.removeLast());
            if (!leftSibling.isLeaf()) {
                child.children.addFirst(leftSibling.children.removeLast());
            }
            return position;
        }
        if (rightSibling != null && rightSibling.keys.size() >= minDegree) {
            // 父节点分隔键下移到子节点尾部，右兄弟最小键上移为新分隔键
            child.keys.addLast(parent.keys.get(position));
            child.values.addLast(parent.values.get(position));
            parent.keys.set(position, rightSibling.keys.removeFirst());
            parent.values.set(position, rightSibling.values.removeFirst());
            if (!rightSibling.isLeaf()) {
                child.children.addLast(rightSibling.children.removeFirst());
            }
            return position;
        }
        if (rightSibling != null) {
            merge(parent, position);
            return position;
        }
        merge(parent, position - 1);
        return position - 1;
    }

    /** 合并父节点第 {@code index} 与第 {@code index + 1} 个子节点：分隔键下移，右子节点内容并入左子节点。 */
    private void merge(Node<K, V> parent, int index) {
        Node<K, V> left = parent.children.get(index);
        Node<K, V> right = parent.children.remove(index + 1);
        left.keys.add(parent.keys.remove(index));
        left.values.add(parent.values.remove(index));
        left.keys.addAll(right.keys);
        left.values.addAll(right.values);
        left.children.addAll(right.children);
    }

    /** 中序收集区间内的键值对，返回 {@code true} 表示已越过上界、应停止遍历。 */
    private boolean collectRange(Node<K, V> node, K from, K to, List<Map.Entry<K, V>> result) {
        int index = Collections.binarySearch(node.keys, from);
        // 从首个不小于下界的键开始；其左侧子树整体小于该键，但可能包含不小于下界的键，因此一并遍历
        int start = index >= 0 ? index : -index - 1;
        for (int i = start; i <= node.keys.size(); i++) {
            if (!node.isLeaf() && collectRange(node.children.get(i), from, to, result)) {
                return true;
            }
            if (i == node.keys.size()) {
                break;
            }
            K key = node.keys.get(i);
            if (key.compareTo(to) > 0) {
                return true;
            }
            result.add(OrderedMaps.entry(key, node.values.get(i)));
        }
        return false;
    }

    /** 返回子树中最右侧的叶子。 */
    private static <K, V> Node<K, V> lastLeaf(Node<K, V> start) {
        Node<K, V> node = start;
        while (!node.isLeaf()) {
            node = node.children.getLast();
        }
        return node;
    }

    /**
     * 中序迭代器：栈中每一帧记录节点与下一个待输出键的下标。
     */
    private final class InOrderIterator implements Iterator<Map.Entry<K, V>> {

        /** 遍历帧栈，栈顶为下一个待输出键所在的节点。 */
        private final Deque<Frame<K, V>> stack = new ArrayDeque<>();

        /** 创建迭代器并定位到最小键。 */
        private InOrderIterator() {
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
            Frame<K, V> frame = stack.peek();
            Node<K, V> node = frame.node;
            int index = frame.index++;
            Map.Entry<K, V> entry = OrderedMaps.entry(node.keys.get(index), node.values.get(index));
            if (frame.index == node.keys.size()) {
                stack.pop();
            }
            // 输出第 index 个键后，接着遍历其右侧（第 index + 1 个）子树
            if (!node.isLeaf()) {
                pushLeftPath(node.children.get(index + 1));
            }
            return entry;
        }

        /** 从给定节点沿最左子节点下降，逐层压入遍历帧。 */
        private void pushLeftPath(Node<K, V> start) {
            for (Node<K, V> node = start; node != null; node = node.isLeaf() ? null : node.children.get(0)) {
                stack.push(new Frame<>(node));
            }
        }
    }

    /**
     * 中序遍历帧。
     *
     * @param <K> 键类型
     * @param <V> 值类型
     */
    private static final class Frame<K, V> {

        /** 当前节点。 */
        private final Node<K, V> node;

        /** 下一个待输出键的下标。 */
        private int index;

        /** 创建指向节点首个键的遍历帧。 */
        private Frame(Node<K, V> node) {
            this.node = node;
        }
    }

    /**
     * B 树节点：键、值一一对应，内部节点额外持有键数加一个子节点，叶子的子节点列表为空。
     *
     * @param <K> 键类型
     * @param <V> 值类型
     */
    private static final class Node<K, V> {

        /** 有序键列表。 */
        private final List<K> keys = new ArrayList<>();

        /** 与键一一对应的值列表。 */
        private final List<V> values = new ArrayList<>();

        /** 子节点列表，叶子为空。 */
        private final List<Node<K, V>> children = new ArrayList<>();

        /** 判断是否为叶子。 */
        private boolean isLeaf() {
            return children.isEmpty();
        }
    }
}
