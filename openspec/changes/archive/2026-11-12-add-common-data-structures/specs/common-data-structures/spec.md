# Spec Delta

## Purpose
在 `org.example.simple.algorithm` 包中提供常用数据结构的规范实现：双向链表、栈、队列、双端队列，以及共享 `OrderedMap` 接口的二叉搜索树、红黑树、AVL 树、B 树、B+ 树。

## ADDED Requirements

### Requirement: 双向链表
系统 SHALL 提供 `DoublyLinkedList<E>`，支持头尾增删、按下标访问/插入/删除、按值查找/删除与原地反转，并允许 `null` 元素。

#### Scenario: 头尾与下标操作
- **WHEN** 依次调用 `addFirst`、`addLast`、`add(index, e)`、`removeAt(index)`
- **THEN** `toList()` 的结果与对 `java.util.LinkedList` 执行同样操作的结果一致

#### Scenario: 下标越界
- **WHEN** 以小于 0 或不小于 `size()` 的下标调用 `get`、`set`、`removeAt`，或以大于 `size()` 的下标调用 `add`
- **THEN** 抛出 `IndexOutOfBoundsException`，链表内容不变

#### Scenario: 空表取出
- **WHEN** 在空链表上调用 `removeFirst`、`removeLast`、`getFirst` 或 `getLast`
- **THEN** 抛出 `NoSuchElementException`

#### Scenario: 原地反转
- **WHEN** 对元素为 `[1, 2, 3]` 的链表调用 `reverse()`
- **THEN** `toList()` 返回 `[3, 2, 1]`，且正反向遍历均一致

### Requirement: 栈、队列与双端队列
系统 SHALL 提供基于数组的 `ArrayStack<E>`（后进先出）、`CircularArrayQueue<E>`（先进先出）与 `CircularArrayDeque<E>`（两端增删），容量自动扩容与缩容，且不允许 `null` 元素。

#### Scenario: 顺序语义
- **WHEN** 依次放入 1、2、3 后全部取出
- **THEN** 栈取出顺序为 3、2、1；队列取出顺序为 1、2、3

#### Scenario: 跨越扩容与循环回绕
- **WHEN** 交替执行大量放入与取出，使数组多次扩容、缩容并发生下标回绕
- **THEN** 取出顺序与 `java.util.ArrayDeque` 执行同样操作的结果一致

#### Scenario: 空结构取出
- **WHEN** 在空结构上执行取出或查看操作
- **THEN** 抛出 `NoSuchElementException`

#### Scenario: 拒绝 null 元素
- **WHEN** 放入 `null`
- **THEN** 抛出 `NullPointerException`，结构内容不变

### Requirement: 有序键值接口
系统 SHALL 提供 `OrderedMap<K extends Comparable<? super K>, V>` 接口，并由 `BinarySearchTree`、`RedBlackTree`、`AvlTree`、`BTree`、`BPlusTree` 实现，各实现对外行为一致。

#### Scenario: 增删查与 TreeMap 一致
- **WHEN** 对任一实现以固定随机种子执行大量 `put`、`remove`、`get`、`containsKey`
- **THEN** 每次返回值、`size()`、`keys()` 与 `java.util.TreeMap` 执行同样操作的结果一致

#### Scenario: 覆盖写入
- **WHEN** 对已存在的键再次调用 `put`
- **THEN** 返回旧值，`size()` 不变

#### Scenario: 导航与范围查询
- **WHEN** 调用 `minKey`、`maxKey`、`floorKey`、`ceilingKey` 与 `range(from, to)`
- **THEN** 结果与 `TreeMap` 的 `firstKey`、`lastKey`、`floorKey`、`ceilingKey`、`subMap(from, true, to, true)` 一致；`from > to` 时 `range` 返回空列表

#### Scenario: 空树最值
- **WHEN** 在空树上调用 `minKey` 或 `maxKey`
- **THEN** 抛出 `NoSuchElementException`

#### Scenario: null 键
- **WHEN** 以 `null` 作为键调用任一接受键的方法
- **THEN** 抛出 `NullPointerException`，树内容不变

#### Scenario: 迭代器 fail-fast
- **WHEN** 迭代过程中插入新键或删除键
- **THEN** 迭代器的下一次 `next` 或 `hasNext` 之后的 `next` 抛出 `ConcurrentModificationException`

### Requirement: 平衡与结构不变量
系统 SHALL 在每次修改操作后保持各树的结构不变量：红黑树满足红黑性质，AVL 树平衡因子在 [-1, 1] 内，B 树与 B+ 树节点键数满足度数/阶数约束且所有叶子同层。

#### Scenario: 随机操作后不变量成立
- **WHEN** 以固定随机种子执行大量插入与删除，并在每步之后调用 `verifyInvariants()`
- **THEN** 不抛出 `IllegalStateException`

#### Scenario: 顺序插入的高度
- **WHEN** 按升序向平衡树插入 100000 个键
- **THEN** `RedBlackTree` 高度不超过 `2·log₂(n+1)`，`AvlTree` 高度不超过 `1.45·log₂(n+2)`，B 树与 B+ 树高度为 O(log n)

#### Scenario: 普通二叉搜索树退化
- **WHEN** 按升序向 `BinarySearchTree` 插入 20000 个键（退化为链表，总耗时 O(n²)，故规模小于平衡树）
- **THEN** 高度等于键数，插入、查询、删除与校验均不发生 `StackOverflowError`

#### Scenario: 非法构造参数
- **WHEN** 以 `t < 2` 构造 `BTree` 或以 `m < 3` 构造 `BPlusTree`
- **THEN** 抛出 `IllegalArgumentException`
