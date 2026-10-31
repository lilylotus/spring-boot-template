# Spec Delta

## Purpose
为 Java 代码提供 10 种经典排序算法的统一静态工具，支持实现 `Comparable` 接口的泛型数组与列表按自然顺序升序原地排序，并为计数、桶、基数排序提供 `int[]` 整数版本。

## ADDED Requirements

### Requirement: 泛型数组与列表升序排序
系统 SHALL 在 `NormalSortUtils` 中为冒泡、选择、插入、希尔、归并、快速、堆、计数、桶、基数排序各提供 `T[]` 与 `List<T>` 两个重载（`T extends Comparable<? super T>`），按自然顺序升序原地排序。

#### Scenario: 数组排序结果正确
- **WHEN** 对包含乱序、重复元素的 `Integer[]` 或 `String[]` 调用任一算法的数组重载
- **THEN** 数组内容与 `Arrays.sort` 对同一输入的结果一致

#### Scenario: 列表排序结果正确
- **WHEN** 对包含乱序、重复元素的 `ArrayList` 或 `LinkedList` 调用任一算法的列表重载
- **THEN** 列表内容与 `Collections.sort` 对同一输入的结果一致

#### Scenario: 边界输入
- **WHEN** 输入为空数组/空列表、单元素、已升序、已降序、全部元素相等
- **THEN** 排序后结果为升序，且不抛出异常

#### Scenario: 随机数据
- **WHEN** 对多组随机生成的数据（含大量重复值）调用任一算法
- **THEN** 结果与 `Arrays.sort` 一致

### Requirement: 稳定性约定
系统 SHALL 保证冒泡、插入、归并、计数、桶、基数排序的泛型版本为稳定排序；选择、希尔、快速、堆排序不承诺稳定。

#### Scenario: 稳定算法保持相等元素原相对顺序
- **WHEN** 对 `compareTo` 仅比较排序键、但携带不同原始序号的对象数组调用稳定算法
- **THEN** 排序键相等的元素保持输入时的相对顺序

### Requirement: 参数校验
系统 SHALL 对 `null` 容器和 `null` 元素抛出 `NullPointerException`，且在发现 `null` 元素时不修改容器内容。

#### Scenario: 容器为 null
- **WHEN** 传入的数组或列表为 `null`
- **THEN** 抛出 `NullPointerException`

#### Scenario: 元素含 null
- **WHEN** 数组或列表中包含 `null` 元素
- **THEN** 抛出 `NullPointerException`，且容器元素顺序保持不变

### Requirement: 非比较排序的整数版本
系统 SHALL 为计数、桶、基数排序提供 `int[]` 重载，支持负数、`Integer.MIN_VALUE` 与 `Integer.MAX_VALUE`。

#### Scenario: 含负数与极值的整数数组
- **WHEN** 对包含负数、零、正数、`Integer.MIN_VALUE`、`Integer.MAX_VALUE` 的 `int[]` 调用基数排序或桶排序
- **THEN** 结果与 `Arrays.sort` 一致

#### Scenario: 计数排序值域过大
- **WHEN** 对 `max - min + 1` 超过 JVM 数组长度上限的 `int[]` 调用计数排序
- **THEN** 抛出 `IllegalArgumentException`，且数组内容保持不变

#### Scenario: 整数 null 数组
- **WHEN** 传入的 `int[]` 为 `null`
- **THEN** 抛出 `NullPointerException`
