## Why

项目已有排序算法工具 `NormalSortUtils`，但缺少常用数据结构的规范实现。JDK 集合框架虽然覆盖了链表、栈、队列和红黑树（`TreeMap`），却没有对外提供 AVL 树、B 树、B+ 树，也不便于直接观察各结构的内部不变量（平衡因子、红黑性质、节点填充度）。为了教学演示、算法对比和选型参考，需要一组实现规范、接口一致、边界行为明确，并在 Javadoc 中写清复杂度与适用场景的数据结构实现。

## What Changes

- 新增包 `org.example.simple.algorithm`，只使用 JDK 内置 API，不引入新依赖。
- 线性结构：
  - `DoublyLinkedList<E>`：双向链表，支持头尾增删、按下标访问/插入/删除、按值查找/删除、原地反转，允许 `null` 元素。
  - `ArrayStack<E>`：基于动态数组的栈（LIFO）。
  - `CircularArrayQueue<E>`：基于循环数组的队列（FIFO）。
  - `CircularArrayDeque<E>`：基于循环数组的双端队列。
  - 栈、队列、双端队列不允许 `null` 元素；容量自动扩容（翻倍），元素较少时自动缩容。
- 有序键值结构：新增统一接口 `OrderedMap<K extends Comparable<? super K>, V>`，由以下 5 种树实现：
  - `BinarySearchTree<K, V>`：普通（不平衡）二叉搜索树，迭代实现，避免退化时栈溢出。
  - `RedBlackTree<K, V>`：红黑树（经典 CLRS 版本，含插入与删除修复）。
  - `AvlTree<K, V>`：AVL 树（四种旋转，插入与删除后重新平衡）。
  - `BTree<K, V>`：B 树，按最小度数 `t` 构造（默认 `t = 3`），支持插入、删除（借位与合并）。
  - `BPlusTree<K, V>`：B+ 树，按阶数 `m` 构造（默认 `m = 4`），数据只存叶子，叶子以链表相连，支持插入、删除与高效范围查询。
- `OrderedMap` 提供：`put`、`get`、`containsKey`、`remove`、`size`、`isEmpty`、`clear`、`minKey`、`maxKey`、`floorKey`、`ceilingKey`、`keys`（升序）、`range`（闭区间范围查询）、`forEach`（升序遍历）、`height`。
- 所有结构实现 `Iterable`（树按键升序迭代 `Map.Entry`），迭代器为 fail-fast。
- 每个结构提供包级可见的不变量校验方法 `verifyInvariants()`，供测试验证内部结构正确性（如红黑性质、AVL 平衡因子、B/B+ 树节点键数范围与叶子同层）。
- 每个公共类与方法提供中文 Javadoc：结构特点、各操作时间/空间复杂度、适用场景、边界与异常约定。

## Capabilities

### New Capabilities

- `common-data-structures`：定义链表、栈、队列、双端队列的操作语义与异常约定，以及 `OrderedMap` 五种树实现的有序键值操作、范围查询、不变量与参数校验约定。

### Modified Capabilities

无。

## Impact

- 新增 `src/main/java/org/example/simple/algorithm/` 下 13 个源文件（9 个实现类、`OrderedMap` 接口，以及包级可见的 `OrderedMaps`、`BinaryNode`、`BinaryTrees` 复用支撑类）。
- 新增 `src/test/java/org/example/simple/algorithm/` 下对应测试类。
- 新增 `docs/常用数据结构使用说明.md`，并在 `README.md` 增加入口链接。
- 不新增 Gradle 依赖；不修改既有代码。
