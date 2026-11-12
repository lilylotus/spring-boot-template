package org.example.simple.algorithm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * B+ 树：数据只存放在叶子、叶子之间以链表相连的多路平衡搜索树，以阶数 {@code m}（节点最多子节点数）描述。
 * <p>
 * 结构约束：
 * <ul>
 *   <li>任一节点至多 m-1 个键；</li>
 *   <li>非根内部节点至少 ⌈m/2⌉ 个子节点（即至少 ⌈m/2⌉-1 个键），根内部节点至少 2 个子节点；</li>
 *   <li>非根叶子至少 ⌊m/2⌋ 个键；</li>
 *   <li>内部节点只存路由键：第 i 个子树中的键 k 满足 {@code 路由键[i-1] <= k < 路由键[i]}；</li>
 *   <li>所有叶子位于同一层，并按键序通过 {@code next} 指针串成单链表。</li>
 * </ul>
 * <p>
 * 实现：插入与删除均为递归自底向上处理。叶子溢出时分裂为两半，右半首键复制到父节点作为路由键；
 * 内部节点溢出时中间键上移（不保留在子节点）。删除后子节点下溢时，先向相邻兄弟借一个键，
 * 兄弟也处于下限时与兄弟合并，并递归修复父节点；根内部节点只剩一个子节点时树高减一。
 * 删除叶子首键不强制更新上层路由键：路由键只需满足区间划分约束，不要求等于右子树最小键。
 * <p>
 * 复杂度：树高 O(log_m n)；查找 O(log n)；插入、删除 O(m·log_m n)；范围查询 O(log n + k)，
 * 定位起始叶子后沿叶子链表顺序扫描。空间 O(n)。
 * <p>
 * 与 {@link BTree} 相比：内部节点不存值、扇出更大、树更矮，且所有查找都落到叶子、耗时稳定；
 * 叶子链表让范围查询和全量顺序扫描无需回溯上层节点。因此关系型数据库索引（如 InnoDB）普遍采用 B+ 树。
 * 本实现为纯内存演示，节点大小以键数度量，不涉及磁盘页管理。
 * <p>
 * 通用约定见 {@link OrderedMap}。
 *
 * @param <K> 键类型
 * @param <V> 值类型
 */
public class BPlusTree<K extends Comparable<? super K>, V> implements OrderedMap<K, V> {

    /** 默认阶数。 */
    public static final int DEFAULT_ORDER = 4;

    /** 阶数 m：节点最多 m 个子节点、m-1 个键。 */
    private final int order;

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

    /** 使用默认阶数 {@value #DEFAULT_ORDER} 创建空 B+ 树。 */
    public BPlusTree() {
        this(DEFAULT_ORDER);
    }

    /**
     * 使用指定阶数创建空 B+ 树。
     *
     * @param order 阶数 m，必须不小于 3
     * @throws IllegalArgumentException 阶数小于 3 时抛出
     */
    public BPlusTree(int order) {
        if (order < 3) {
            throw new IllegalArgumentException("B+ 树阶数必须不小于 3：" + order);
        }
        this.order = order;
    }

    /** 写入键值对，O(m·log_m n)。 */
    @Override
    public V put(K key, V value) {
        OrderedMaps.requireKey(key);
        if (root == null) {
            root = Node.leaf();
            root.keys.add(key);
            root.values.add(value);
            size++;
            modCount++;
            return null;
        }
        keyFound = false;
        lastValue = null;
        Split<K, V> split = insert(root, key, value);
        if (split != null) {
            // 根分裂：新建内部根节点，这是 B+ 树长高的唯一方式
            Node<K, V> newRoot = Node.internal();
            newRoot.keys.add(split.separator);
            newRoot.children.add(root);
            newRoot.children.add(split.right);
            root = newRoot;
        }
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
        OrderedMaps.requireKey(key);
        if (root == null) {
            return null;
        }
        Node<K, V> leaf = findLeaf(key);
        int index = Collections.binarySearch(leaf.keys, key);
        return index >= 0 ? leaf.values.get(index) : null;
    }

    /** 判断键是否存在，O(log n)。 */
    @Override
    public boolean containsKey(K key) {
        OrderedMaps.requireKey(key);
        return root != null && Collections.binarySearch(findLeaf(key).keys, key) >= 0;
    }

    /** 删除键并返回其值，O(m·log_m n)。 */
    @Override
    public V remove(K key) {
        OrderedMaps.requireKey(key);
        if (root == null) {
            return null;
        }
        keyFound = false;
        lastValue = null;
        delete(root, key);
        V removed = lastValue;
        lastValue = null;
        if (!keyFound) {
            return null;
        }
        if (root.leaf && root.keys.isEmpty()) {
            root = null;
        } else if (!root.leaf && root.children.size() == 1) {
            // 根只剩一个子节点：子节点成为新根，树高减一
            root = root.children.get(0);
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

    /** 返回最小键：最左叶子的首键，O(log_m n)。 */
    @Override
    public K minKey() {
        if (root == null) {
            throw new NoSuchElementException("B+ 树为空");
        }
        return firstLeaf().keys.get(0);
    }

    /** 返回最大键：最右叶子的末键，O(log_m n)。 */
    @Override
    public K maxKey() {
        if (root == null) {
            throw new NoSuchElementException("B+ 树为空");
        }
        Node<K, V> node = root;
        while (!node.leaf) {
            node = node.children.getLast();
        }
        return node.keys.getLast();
    }

    /**
     * 返回小于等于给定键的最大键，O(log n)。
     * <p>
     * 叶子链表是单向的，目标叶子中没有候选时无法直接回到前一个叶子，因此递归下降：
     * 若在第 i 个子树中找不到候选，则答案是第 i-1 个子树的最大键。
     */
    @Override
    public K floorKey(K key) {
        OrderedMaps.requireKey(key);
        return root == null ? null : floorIn(root, key);
    }

    /** 返回大于等于给定键的最小键：定位叶子后，叶子内没有候选时取下一个叶子的首键，O(log n)。 */
    @Override
    public K ceilingKey(K key) {
        OrderedMaps.requireKey(key);
        if (root == null) {
            return null;
        }
        Node<K, V> leaf = findLeaf(key);
        int position = lowerBound(leaf.keys, key);
        if (position < leaf.keys.size()) {
            return leaf.keys.get(position);
        }
        return leaf.next == null ? null : leaf.next.keys.get(0);
    }

    /** 返回闭区间内的键值对：定位下界所在叶子后沿叶子链表顺序扫描，O(log n + k)。 */
    @Override
    public List<Map.Entry<K, V>> range(K fromInclusive, K toInclusive) {
        OrderedMaps.requireKey(fromInclusive);
        OrderedMaps.requireKey(toInclusive);
        List<Map.Entry<K, V>> result = new ArrayList<>();
        if (root == null || fromInclusive.compareTo(toInclusive) > 0) {
            return result;
        }
        Node<K, V> leaf = findLeaf(fromInclusive);
        int index = lowerBound(leaf.keys, fromInclusive);
        while (leaf != null) {
            for (; index < leaf.keys.size(); index++) {
                K key = leaf.keys.get(index);
                if (key.compareTo(toInclusive) > 0) {
                    return result;
                }
                result.add(OrderedMaps.entry(key, leaf.values.get(index)));
            }
            leaf = leaf.next;
            index = 0;
        }
        return result;
    }

    /** 返回树的层数，O(log_m n)。 */
    @Override
    public int height() {
        int height = 0;
        for (Node<K, V> node = root; node != null; node = node.leaf ? null : node.children.get(0)) {
            height++;
        }
        return height;
    }

    /**
     * 返回阶数。
     *
     * @return 构造时指定的阶数 m
     */
    public int order() {
        return order;
    }

    /** 返回按键升序的 fail-fast 迭代器：从最左叶子出发沿叶子链表遍历，额外空间 O(1)。 */
    @Override
    public Iterator<Map.Entry<K, V>> iterator() {
        Node<K, V> first = root == null ? null : firstLeaf();
        Iterator<Map.Entry<K, V>> leafIterator = new Iterator<>() {

            /** 当前叶子。 */
            private Node<K, V> leaf = first;

            /** 当前叶子中下一个待输出键的下标。 */
            private int index;

            @Override
            public boolean hasNext() {
                return leaf != null;
            }

            @Override
            public Map.Entry<K, V> next() {
                if (leaf == null) {
                    throw new NoSuchElementException("迭代已结束");
                }
                Map.Entry<K, V> entry = OrderedMaps.entry(leaf.keys.get(index), leaf.values.get(index));
                index++;
                if (index == leaf.keys.size()) {
                    leaf = leaf.next;
                    index = 0;
                }
                return entry;
            }
        };
        return OrderedMaps.failFast(leafIterator, () -> modCount);
    }

    /**
     * 校验 B+ 树性质：节点键数满足阶数约束，内部节点子节点数等于键数加一，路由键正确划分子树区间，
     * 所有叶子同层，叶子链表按序串联全部叶子，键总数等于 {@code size}。
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
        List<Node<K, V>> leaves = new ArrayList<>();
        int[] leafDepth = {-1};
        verifyNode(root, null, null, 0, leaves, leafDepth);
        int count = 0;
        for (int i = 0; i < leaves.size(); i++) {
            Node<K, V> expectedNext = i + 1 < leaves.size() ? leaves.get(i + 1) : null;
            if (leaves.get(i).next != expectedNext) {
                throw new IllegalStateException("叶子链表顺序与树中叶子顺序不一致");
            }
            count += leaves.get(i).keys.size();
        }
        if (count != size) {
            throw new IllegalStateException("键总数 " + count + " 与 size " + size + " 不一致");
        }
    }

    /**
     * 递归校验单个节点及其子树。区间为左闭右开 {@code [low, high)}，{@code null} 表示无界。
     */
    private void verifyNode(
            Node<K, V> node, K low,
            K high, int depth,
            List<Node<K, V>> leaves, int[] leafDepth) {
        int keyCount = node.keys.size();
        boolean isRoot = node == root;
        int minKeys;
        if (node.leaf) {
            minKeys = isRoot ? 1 : minLeafKeys();
        } else {
            minKeys = isRoot ? 1 : minInternalKeys();
        }
        if (keyCount < minKeys || keyCount > order - 1) {
            throw new IllegalStateException("节点键数 " + keyCount + " 超出范围 [" + minKeys + ", " + (order - 1) + "]");
        }
        for (int i = 0; i < keyCount; i++) {
            K key = node.keys.get(i);
            boolean belowLow = low != null && key.compareTo(low) < 0;
            boolean aboveHigh = high != null && key.compareTo(high) >= 0;
            boolean unordered = i > 0 && node.keys.get(i - 1).compareTo(key) >= 0;
            if (belowLow || aboveHigh || unordered) {
                throw new IllegalStateException("键 " + key + " 违反有序或区间约束");
            }
        }
        if (node.leaf) {
            if (node.values.size() != keyCount || !node.children.isEmpty()) {
                throw new IllegalStateException("叶子的值个数与键个数不一致或持有子节点");
            }
            if (leafDepth[0] < 0) {
                leafDepth[0] = depth;
            } else if (leafDepth[0] != depth) {
                throw new IllegalStateException("叶子不在同一层");
            }
            leaves.add(node);
            return;
        }
        if (node.children.size() != keyCount + 1 || !node.values.isEmpty()) {
            throw new IllegalStateException("内部节点子节点数不等于键数加一或存放了值");
        }
        for (int i = 0; i <= keyCount; i++) {
            K childLow = i == 0 ? low : node.keys.get(i - 1);
            K childHigh = i == keyCount ? high : node.keys.get(i);
            verifyNode(node.children.get(i), childLow, childHigh, depth + 1, leaves, leafDepth);
        }
    }

    /** 非根叶子的最少键数 ⌊m/2⌋。 */
    private int minLeafKeys() {
        return order / 2;
    }

    /** 非根内部节点的最少键数 ⌈m/2⌉-1。 */
    private int minInternalKeys() {
        return (order + 1) / 2 - 1;
    }

    /** 从根下降到可能包含给定键的叶子。 */
    private Node<K, V> findLeaf(K key) {
        Node<K, V> node = root;
        while (!node.leaf) {
            node = node.children.get(childIndex(node, key));
        }
        return node;
    }

    /** 返回最左叶子。 */
    private Node<K, V> firstLeaf() {
        Node<K, V> node = root;
        while (!node.leaf) {
            node = node.children.get(0);
        }
        return node;
    }

    /** 递归求子树中小于等于给定键的最大键。 */
    private K floorIn(Node<K, V> node, K key) {
        if (node.leaf) {
            int position = upperBound(node.keys, key);
            return position == 0 ? null : node.keys.get(position - 1);
        }
        int index = childIndex(node, key);
        K result = floorIn(node.children.get(index), key);
        if (result != null || index == 0) {
            return result;
        }
        // 第 index 个子树中所有键都大于给定键，而第 index-1 个子树的键都小于路由键（不大于给定键）
        Node<K, V> left = node.children.get(index - 1);
        while (!left.leaf) {
            left = left.children.getLast();
        }
        return left.keys.getLast();
    }

    /**
     * 在子树中插入键值对。
     *
     * @return 节点溢出分裂时返回需上移到父节点的路由键与新右兄弟，否则返回 {@code null}
     */
    private Split<K, V> insert(Node<K, V> node, K key, V value) {
        if (node.leaf) {
            int index = Collections.binarySearch(node.keys, key);
            if (index >= 0) {
                keyFound = true;
                lastValue = node.values.set(index, value);
                return null;
            }
            int position = -index - 1;
            node.keys.add(position, key);
            node.values.add(position, value);
            return node.keys.size() > order - 1 ? splitLeaf(node) : null;
        }
        int index = childIndex(node, key);
        Split<K, V> split = insert(node.children.get(index), key, value);
        if (split == null) {
            return null;
        }
        node.keys.add(index, split.separator);
        node.children.add(index + 1, split.right);
        return node.keys.size() > order - 1 ? splitInternal(node) : null;
    }

    /** 分裂溢出的叶子：后半部分移入新叶子并接入叶子链表，新叶子首键复制上移作为路由键。 */
    private Split<K, V> splitLeaf(Node<K, V> leaf) {
        int keep = leaf.keys.size() / 2;
        Node<K, V> right = Node.leaf();
        moveTail(leaf.keys, keep, right.keys);
        moveTail(leaf.values, keep, right.values);
        right.next = leaf.next;
        leaf.next = right;
        return new Split<>(right.keys.get(0), right);
    }

    /** 分裂溢出的内部节点：中间键上移（不保留在子节点中），其右侧的键与子节点移入新节点。 */
    private Split<K, V> splitInternal(Node<K, V> node) {
        int middle = node.keys.size() / 2;
        K separator = node.keys.get(middle);
        Node<K, V> right = Node.internal();
        moveTail(node.keys, middle + 1, right.keys);
        moveTail(node.children, middle + 1, right.children);
        node.keys.remove(middle);
        return new Split<>(separator, right);
    }

    /** 在子树中删除键；子节点下溢时由父节点负责借位或合并。 */
    private void delete(Node<K, V> node, K key) {
        if (node.leaf) {
            int index = Collections.binarySearch(node.keys, key);
            if (index >= 0) {
                keyFound = true;
                node.keys.remove(index);
                lastValue = node.values.remove(index);
            }
            return;
        }
        int index = childIndex(node, key);
        Node<K, V> child = node.children.get(index);
        delete(child, key);
        if (!keyFound) {
            return;
        }
        int minKeys = child.leaf ? minLeafKeys() : minInternalKeys();
        if (child.keys.size() < minKeys) {
            fixUnderflow(node, index);
        }
    }

    /** 修复父节点第 {@code index} 个子节点的下溢：优先向左、右兄弟借位，否则与兄弟合并。 */
    private void fixUnderflow(Node<K, V> parent, int index) {
        Node<K, V> child = parent.children.get(index);
        Node<K, V> left = index > 0 ? parent.children.get(index - 1) : null;
        Node<K, V> right = index + 1 < parent.children.size() ? parent.children.get(index + 1) : null;
        int minKeys = child.leaf ? minLeafKeys() : minInternalKeys();
        if (left != null && left.keys.size() > minKeys) {
            borrowFromLeft(parent, index, left, child);
        } else if (right != null && right.keys.size() > minKeys) {
            borrowFromRight(parent, index, child, right);
        } else if (left != null) {
            merge(parent, index - 1);
        } else {
            merge(parent, index);
        }
    }

    /** 从左兄弟借一个键到子节点头部，并更新二者之间的路由键。 */
    private void borrowFromLeft(
            Node<K, V> parent, int index,
            Node<K, V> left, Node<K, V> child) {
        if (child.leaf) {
            child.keys.addFirst(left.keys.removeLast());
            child.values.addFirst(left.values.removeLast());
            // 借来的键成为子节点新的最小键，作为新路由键
            parent.keys.set(index - 1, child.keys.get(0));
        } else {
            // 内部节点旋转：路由键下移到子节点，左兄弟末键上移为新路由键，左兄弟末子节点随之移动
            child.keys.addFirst(parent.keys.get(index - 1));
            parent.keys.set(index - 1, left.keys.removeLast());
            child.children.addFirst(left.children.removeLast());
        }
    }

    /** 从右兄弟借一个键到子节点尾部，并更新二者之间的路由键。 */
    private void borrowFromRight(
            Node<K, V> parent, int index,
            Node<K, V> child, Node<K, V> right) {
        if (child.leaf) {
            child.keys.addLast(right.keys.removeFirst());
            child.values.addLast(right.values.removeFirst());
            // 右兄弟的新最小键作为新路由键
            parent.keys.set(index, right.keys.get(0));
        } else {
            child.keys.addLast(parent.keys.get(index));
            parent.keys.set(index, right.keys.removeFirst());
            child.children.addLast(right.children.removeFirst());
        }
    }

    /**
     * 合并父节点第 {@code index} 与第 {@code index + 1} 个子节点，并删除二者之间的路由键。
     * 叶子合并直接拼接键值并修正链表；内部节点合并需把路由键下移到两部分之间。
     */
    private void merge(Node<K, V> parent, int index) {
        Node<K, V> left = parent.children.get(index);
        Node<K, V> right = parent.children.remove(index + 1);
        K separator = parent.keys.remove(index);
        if (left.leaf) {
            left.keys.addAll(right.keys);
            left.values.addAll(right.values);
            left.next = right.next;
        } else {
            left.keys.add(separator);
            left.keys.addAll(right.keys);
            left.children.addAll(right.children);
        }
    }

    /** 返回给定键应下降的子节点下标：路由键中不大于该键的个数。 */
    private static <K extends Comparable<? super K>> int childIndex(Node<K, ?> node, K key) {
        return upperBound(node.keys, key);
    }

    /** 返回有序列表中首个不小于给定键的位置。 */
    private static <K extends Comparable<? super K>> int lowerBound(List<K> keys, K key) {
        int index = Collections.binarySearch(keys, key);
        return index >= 0 ? index : -index - 1;
    }

    /** 返回有序列表中首个大于给定键的位置（列表键唯一）。 */
    private static <K extends Comparable<? super K>> int upperBound(List<K> keys, K key) {
        int index = Collections.binarySearch(keys, key);
        return index >= 0 ? index + 1 : -index - 1;
    }

    /** 把源列表从 {@code from} 起的尾部元素移动到目标列表末尾。 */
    private static <T> void moveTail(List<T> source, int from, List<T> target) {
        List<T> tail = source.subList(from, source.size());
        target.addAll(tail);
        tail.clear();
    }

    /**
     * 节点分裂结果。
     *
     * @param separator 需上移到父节点的路由键
     * @param right 分裂出的新右兄弟
     * @param <K> 键类型
     * @param <V> 值类型
     */
    private record Split<K, V>(K separator, Node<K, V> right) {
    }

    /**
     * B+ 树节点：叶子存放键值并持有下一个叶子的引用；内部节点只存路由键与子节点。
     *
     * @param <K> 键类型
     * @param <V> 值类型
     */
    private static final class Node<K, V> {

        /** 是否为叶子。 */
        private final boolean leaf;

        /** 有序键列表：叶子中为数据键，内部节点中为路由键。 */
        private final List<K> keys = new ArrayList<>();

        /** 与键一一对应的值列表，仅叶子使用。 */
        private final List<V> values = new ArrayList<>();

        /** 子节点列表，仅内部节点使用，个数等于键数加一。 */
        private final List<Node<K, V>> children = new ArrayList<>();

        /** 下一个叶子，仅叶子使用，最右叶子为 {@code null}。 */
        private Node<K, V> next;

        /** 创建指定类型的节点。 */
        private Node(boolean leaf) {
            this.leaf = leaf;
        }

        /** 创建叶子节点。 */
        private static <K, V> Node<K, V> leaf() {
            return new Node<>(true);
        }

        /** 创建内部节点。 */
        private static <K, V> Node<K, V> internal() {
            return new Node<>(false);
        }
    }
}
