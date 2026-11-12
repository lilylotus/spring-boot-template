# Tasks

## 1. 审阅与确认

- [x] 1.1 获得用户对 `proposal.md`、`design.md` 和 `specs/common-data-structures/spec.md` 的明确确认，尤其是：类命名（避免与 `java.util` 冲突）、不实现 JDK 集合接口、不支持 `Comparator` 与 `null` 键、栈/队列/双端队列禁止 `null` 元素且空时抛 `NoSuchElementException`、五种树共享 `OrderedMap` 接口、`BTree` 默认 `t = 3` 与 `BPlusTree` 默认 `m = 4`、包级 `verifyInvariants()` 校验方法这几项设计决策。

## 2. 线性结构实现

- [x] 2.1 实现 `DoublyLinkedList`：头尾增删、按下标访问/插入/删除（就近端遍历）、按值查找/删除、`reverse`、`toList`、fail-fast 迭代器、`verifyInvariants`。
- [x] 2.2 实现 `ArrayStack`：`push`/`pop`/`peek`、扩容与缩容、槽位清空、`toList`、迭代器、`verifyInvariants`。
- [x] 2.3 实现 `CircularArrayQueue`：`enqueue`/`dequeue`/`peek`、循环下标、扩容与缩容时按逻辑顺序搬移、迭代器、`verifyInvariants`。
- [x] 2.4 实现 `CircularArrayDeque`：两端增删与查看、循环下标、扩容与缩容、迭代器、`verifyInvariants`。

## 3. 有序键值结构实现

- [x] 3.1 新增 `OrderedMap` 接口与包级工具类 `OrderedMaps`（`null` 键校验、不可变 `Entry` 创建、fail-fast 迭代器包装），以及二叉树复用支撑 `BinaryNode`、`BinaryTrees`（迭代实现的查找、导航、范围查询、中序迭代、高度、BST 性质校验）。
- [x] 3.2 实现 `BinarySearchTree`（全迭代实现，含后继替换删除、`floorKey`/`ceilingKey`、剪枝 `range`）。
- [x] 3.3 实现 `RedBlackTree`（左/右旋、插入修复、删除修复、`verifyInvariants` 校验红黑性质与黑高）。
- [x] 3.4 实现 `AvlTree`（高度维护、LL/RR/LR/RL 旋转、插入与删除后重平衡、`verifyInvariants`）。
- [x] 3.5 实现 `BTree`（最小度数参数校验、预分裂插入、借位/合并删除、根收缩、`verifyInvariants`）。
- [x] 3.6 实现 `BPlusTree`（阶数参数校验、叶子链表、叶子/内部节点分裂、借位/合并删除与路由键维护、基于叶子链表的 `range` 与迭代、`verifyInvariants`）。
- [x] 3.7 为所有公共类与方法编写中文 Javadoc：结构特点、复杂度、适用场景、异常约定。

## 4. 测试

- [x] 4.1 `DoublyLinkedListTest`：头尾操作、下标操作与越界、`null` 元素、按值删除、`reverse`、空表异常、迭代器 fail-fast。
- [x] 4.2 `ArrayStackTest`、`CircularArrayQueueTest`、`CircularArrayDequeTest`：顺序语义、空结构异常、`null` 元素拒绝、跨越扩容/缩容与循环回绕后的顺序正确性、非法初始容量。
- [x] 4.3 线性结构随机操作测试：固定种子随机执行操作，与 `java.util.ArrayDeque`/`java.util.LinkedList` 对照，每步后调用 `verifyInvariants()`。
- [x] 4.4 `OrderedMap` 参数化契约测试，覆盖五种树：`put` 覆盖返回旧值、`get`/`containsKey`/`remove` 不存在键、`null` 值、`null` 键异常、空树 `minKey`/`maxKey` 异常、`floorKey`/`ceilingKey`、`range`（含反向区间）、`keys` 升序、迭代器 fail-fast。
- [x] 4.5 五种树随机增删查测试：固定种子大规模随机操作与 `java.util.TreeMap` 对照，每步后 `verifyInvariants()`，B/B+ 树覆盖多种 `t`/`m`（含最小值）。
- [x] 4.6 平衡性测试：顺序插入 10 万个键后，`RedBlackTree`/`AvlTree` 高度满足理论上界，B/B+ 树高度符合 O(log n)；`BinarySearchTree` 顺序插入 2 万个键（退化为 O(n²)，规模单独缩小）不发生栈溢出。
- [x] 4.7 `BTree`/`BPlusTree` 非法构造参数抛 `IllegalArgumentException`。

## 5. 文档与验证

- [x] 5.1 新增 `docs/常用数据结构使用说明.md`：结构对比表、选型建议、使用示例、Non-Goal；在 `README.md` 增加入口链接。
- [x] 5.2 运行 `gradlew.bat test` 与 `gradlew.bat build`，确认新增测试通过且无新增编译或弃用警告。
- [x] 5.3 运行 `openspec validate add-common-data-structures --strict`，核对代码、测试、文档与 OpenSpec 文档一致，并在本文件补充验证记录。

## 验证记录

- `gradlew.bat test --tests "org.example.simple.algorithm.*"`：5 个测试类共 123 个用例（含参数化展开）全部通过，耗时约 6 秒。
- 对 `algorithm` 包单独执行 `javadoc -Xmaxwarns 1000`：0 个警告；编译无新增警告；源码与测试均无超过 120 字符的行、无 tab、无 CRLF。
- 变异检查：删除 `RedBlackTree` 删除修复情况 4 中的一次着色后，`randomOperationsMatchTreeMap` 的红黑树用例立即失败，确认 `verifyInvariants()` 能发现结构破坏；检查后已恢复源码。
- `gradlew.bat build`（全量）：本变更测试全部通过；其余 9 个失败与本变更无关，未作改动：`GlobalHttpClientUtilsTest` 5 个与 `HttpClientsTest` 2 个依赖本地 HTTP 服务，`ExcelUtilsTest` 2 个缺少本地测试文件。
- 实施中设计调整（均已先同步 `design.md`/`proposal.md`/`spec.md`）：
  - 新增包级支撑类 `BinaryNode`、`BinaryTrees`，三种二叉树复用迭代实现的只读算法；`OrderedMap` 的 `isEmpty`、`keys`、`forEach` 改为默认方法。
  - 普通二叉搜索树退化测试规模由 10 万降为 2 万：退化后插入总耗时 O(n²)，10 万规模单测需约 150 秒；2 万深度已远超递归实现的安全栈深度，平衡树仍以 10 万规模测试。
