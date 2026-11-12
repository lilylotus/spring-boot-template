## Context

用户要求在 `org.example.simple.algorithm` 包中实现常用数据结构：链表、栈、队列、双端队列、二叉搜索树、平衡树（红黑树、AVL 树）、B 树、B+ 树。仓库已有 `NormalSortUtils`，其约定为：元素类型 `Comparable<? super T>`、`null` 抛 `NullPointerException`、中文 Javadoc 写明复杂度与适用场景、仅使用 JDK。本变更沿用这些约定。

## Goals / Non-Goals

**Goals:**
- 9 种数据结构各自提供完整、正确的增删查操作，包括所有树的删除操作。
- 五种树共享 `OrderedMap` 接口，行为可互换，便于对比与统一测试。
- 边界与异常约定明确且一致，并在 Javadoc 与测试中体现。
- 通过 `verifyInvariants()` 可在测试中验证每种结构的内部不变量，而不仅是对外结果。

**Non-Goals:**
- 不实现 `java.util.List`/`Deque`/`NavigableMap` 等 JDK 集合接口（接口方法过多，会冲淡教学重点；需要互操作时由调用方转换）。
- 不支持自定义 `Comparator`，键统一按自然顺序比较（与 `NormalSortUtils` 一致）。
- 不支持 `null` 键；不提供线程安全保证（与 JDK 非并发集合一致）。
- 不提供序列化、持久化存储或磁盘页管理；B/B+ 树为纯内存实现，节点大小仅以键数度量。
- 不追求替代 JDK 集合的生产性能。

## Decisions

### 命名

为避免与 `java.util` 中的 `LinkedList`、`Stack`、`ArrayDeque`、`Queue` 冲突导致导入混淆，类名带实现方式：`DoublyLinkedList`、`ArrayStack`、`CircularArrayQueue`、`CircularArrayDeque`。树类名：`BinarySearchTree`、`RedBlackTree`、`AvlTree`、`BTree`、`BPlusTree`。

### 线性结构

| 结构 | 实现 | 主要操作与复杂度 |
| --- | --- | --- |
| `DoublyLinkedList` | 头尾哨兵引用 + 双向节点 | 头尾增删 O(1)；按下标访问/插入/删除 O(n)，从离下标较近的一端遍历；`reverse()` O(n) |
| `ArrayStack` | 动态数组 | `push`/`pop`/`peek` 均摊 O(1) |
| `CircularArrayQueue` | 循环数组（`head` + `size`） | `enqueue`/`dequeue`/`peek` 均摊 O(1) |
| `CircularArrayDeque` | 循环数组 | `addFirst`/`addLast`/`removeFirst`/`removeLast`/`peekFirst`/`peekLast` 均摊 O(1) |

- `DoublyLinkedList` 方法：`addFirst`、`addLast`、`add(index, e)`、`get`、`set`、`removeAt`、`removeFirst`、`removeLast`、`getFirst`、`getLast`、`remove(Object)`（删除首个相等元素）、`indexOf`、`contains`、`reverse`、`size`、`isEmpty`、`clear`、`toList`。允许 `null` 元素（相等判断用 `Objects.equals`），与 JDK `LinkedList` 一致。删除方法用 `removeAt(int)` 而非重载 `remove(int)`，避免与 `remove(Object)` 在 `Integer` 元素上产生重载歧义。
- 栈/队列/双端队列不允许 `null` 元素（`NullPointerException`），与 JDK `ArrayDeque` 一致；均提供 `size`、`isEmpty`、`clear`、`toList`（栈按出栈顺序、队列按出队顺序）。
- 空结构上执行取出或查看操作抛 `NoSuchElementException`；越界下标抛 `IndexOutOfBoundsException`。不提供返回 `null` 的 `poll` 风格方法，保持单一语义。
- 扩容：容量满时翻倍；缩容：`size` 降到容量的 1/4 且容量大于默认最小容量 8 时减半，避免频繁抖动；`clear` 后回到默认容量。构造器可指定初始容量，非正数抛 `IllegalArgumentException`。出队/出栈后将空出的槽位置 `null`，避免内存泄漏。

### `OrderedMap` 接口

```java
public interface OrderedMap<K extends Comparable<? super K>, V> extends Iterable<Map.Entry<K, V>> {
    V put(K key, V value);          // 返回旧值，不存在返回 null
    V get(K key);                   // 不存在返回 null
    boolean containsKey(K key);
    V remove(K key);                // 返回被删值，不存在返回 null
    int size();
    default boolean isEmpty() { return size() == 0; }
    void clear();
    K minKey();                     // 空时 NoSuchElementException
    K maxKey();
    K floorKey(K key);              // 小于等于 key 的最大键，无则 null
    K ceilingKey(K key);            // 大于等于 key 的最小键，无则 null
    List<K> keys();                 // 升序
    List<Map.Entry<K, V>> range(K fromInclusive, K toInclusive);
    void forEach(BiConsumer<? super K, ? super V> action);
    int height();                   // 空树为 0
}
```

- `null` 键（包括 `range` 边界、`floorKey` 参数）抛 `NullPointerException`；值允许为 `null`，因此需用 `containsKey` 区分"不存在"与"值为 null"。
- `range` 中 `fromInclusive > toInclusive` 返回空列表。
- 返回的 `Map.Entry` 为不可变快照（`Map.entry` 不允许 `null` 值，故使用 `AbstractMap.SimpleImmutableEntry`）。
- 迭代器按键升序，fail-fast：结构修改（增删、覆盖不算）后继续迭代抛 `ConcurrentModificationException`，基于 `modCount` 实现；迭代器不支持 `remove`。

### 各树实现要点

| 树 | 实现要点 | 查找/插入/删除 | 高度 |
| --- | --- | --- | --- |
| `BinarySearchTree` | 全部迭代实现（含删除），删除双子节点时用后继替换 | 平均 O(log n)，最坏 O(n) | 最坏 n |
| `RedBlackTree` | CLRS 版本：父指针、左/右旋、插入修复 3 种情况、删除修复 4 种情况；用 `null` 表示 NIL 并在修复中显式处理 | O(log n) | ≤ 2·log₂(n+1) |
| `AvlTree` | 节点存高度，递归插入/删除后自底向上重算高度并按 LL/RR/LR/RL 旋转 | O(log n) | ≤ 约 1.44·log₂(n+2) |
| `BTree` | CLRS 版本，最小度数 `t ≥ 2`：每个非根节点键数 ∈ [t-1, 2t-1]；插入时自顶向下预分裂满节点；删除时自顶向下保证下降路径节点至少 t 个键（借位/合并），根变空时降低高度 | O(t·log_t n) | O(log_t n) |
| `BPlusTree` | 阶数 `m ≥ 3`（节点最多 m 个子节点）：内部节点只存路由键，叶子存全部键值并以 `next` 指针相连；叶子溢出分裂时把右叶首键复制上移，内部节点分裂时中间键上移；删除下溢时先向兄弟借位，否则合并并递归修复父节点，同时维护路由键 | O(m·log_m n) | O(log_m n) |

- `BinarySearchTree` 采用迭代实现，是因为有序插入会退化为链表，递归在 1 万级元素即可能 `StackOverflowError`；平衡树高度为 O(log n)，`AvlTree` 使用递归更易读。
- `BTree`/`BPlusTree` 节点内用有序 `ArrayList` + 二分查找定位键，避免泛型数组创建；构造参数非法（`t < 2`、`m < 3`）抛 `IllegalArgumentException`。
- `BPlusTree.range` 先定位起始叶子，再沿叶子链表顺序扫描，复杂度 O(log_m n + k)；其他树的 `range` 采用中序遍历剪枝，复杂度 O(h + k)。
- `BPlusTree.minKey` 从最左叶取，`keys`/迭代器直接沿叶子链表遍历。

### 不变量校验

每个结构提供包级可见的 `void verifyInvariants()`，违反时抛 `IllegalStateException`（中文说明违反的性质）：

- 链表：正反向遍历节点数都等于 `size`，`prev`/`next` 相互一致，头尾引用正确。
- 栈/队列/双端队列：`size ≤ capacity`，有效区之外的槽位全部为 `null`。
- 二叉搜索树：中序严格递增，节点数等于 `size`（红黑、AVL 同样检查）。
- 红黑树：根为黑；红节点无红子节点；每条路径黑高相同；父指针一致。
- AVL：每个节点存储的高度正确，平衡因子 ∈ [-1, 1]。
- B 树：非根节点键数 ∈ [t-1, 2t-1]，内部节点子节点数 = 键数 + 1，所有叶子同层，键区间满足搜索树性质。
- B+ 树：除根外节点子节点/键数满足阶数下限，所有叶子同层，路由键正确划分子树，叶子链表按序覆盖全部键且数量等于 `size`。

该方法为包级可见，不属于公共 API，只供同包测试使用。

### 代码组织

每个结构一个顶层类，节点类型为私有静态嵌套类。复用方式：

- `OrderedMaps`（包级 `final` 工具类）：`null` 键校验、不可变 `Entry` 创建、包装 fail-fast 迭代器，供五种树共用。
- `BinaryNode<K, V, N>`（包级抽象节点基类，自限定泛型）：只含 `key`、`value`、`left`、`right` 四个字段；三种二叉树的节点类继承它并各自增加 `parent`/`color`（红黑）或 `height`（AVL）。
- `BinaryTrees`（包级 `final` 工具类）：基于 `BinaryNode` 实现查找、最值、`floorKey`/`ceilingKey`、剪枝 `range`、中序迭代器、高度计算与"中序严格递增且节点数等于 size"校验，全部采用迭代实现，以兼容退化的普通二叉搜索树。

不引入树级别的抽象基类：五种树的插入、删除逻辑差异很大，只复用与平衡策略无关的只读操作。B/B+ 树为多路结构，不使用 `BinaryNode`。

`OrderedMap` 中 `isEmpty`、`keys`、`forEach` 以默认方法基于迭代器实现。

## Risks / Trade-offs

- **红黑树与 B+ 树删除较复杂，容易引入边界错误**：通过 `verifyInvariants()` 配合固定种子的大规模随机增删测试（与 `java.util.TreeMap` 对照）兜底，每次操作后校验不变量。
- **未实现 JDK 集合接口**：无法直接传给要求 `List`/`Map` 的 API；提供 `toList`/`keys`/`range` 作为转换出口，已列为 Non-Goal。
- **`compareTo` 与 `equals` 不一致的键**：树只依据 `compareTo` 判断键相等（与 `TreeMap` 一致），在 Javadoc 中说明。
- **B/B+ 树为内存模拟**：节点使用 `ArrayList`，不体现真实磁盘页 I/O 优势；Javadoc 说明其主要价值在于演示结构与范围查询。
