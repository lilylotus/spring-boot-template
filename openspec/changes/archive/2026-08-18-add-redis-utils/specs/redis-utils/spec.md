## ADDED Requirements

### Requirement: 统一封装 Redis 字符串命令
系统 SHALL 提供一个 `RedisUtils` 组件，封装 `RedisTemplate` 的 `opsForValue()` 常用字符串命令
（`set`、带过期时间的 `set`、`get`、`delete`、`hasKey`、`expire`、`getExpire`），业务代码 SHALL
通过该组件读写 Redis 字符串类型数据，而不是直接调用 `redisTemplate.opsForValue()`。

#### Scenario: 写入不带过期时间的键值
- **WHEN** 调用方执行 `redisUtils.set(key, value)`
- **THEN** 该 key 在 Redis 中被写入 value，且不设置过期时间（永久有效，直到被显式删除）

#### Scenario: 写入带过期时间的键值
- **WHEN** 调用方执行 `redisUtils.set(key, value, duration)`
- **THEN** 该 key 在 Redis 中被写入 value，并设置为 `duration` 后自动过期

#### Scenario: 按调用处声明的类型读取键值
- **WHEN** 调用方执行 `SomeType value = redisUtils.get(key);`（不传 `Class` 参数），且该 key 存在
  且 Redis 中存储的对象类型与调用处声明的 `SomeType` 一致
- **THEN** 返回反序列化后的对象，运行时类型与调用处声明的 `SomeType` 一致（由 `RedisTemplate`
  的 Jackson 类型信息在反序列化阶段自动还原，`RedisUtils` 不做二次类型转换）

#### Scenario: 读取不存在的键
- **WHEN** 调用方执行 `redisUtils.get(key)`，且该 key 不存在
- **THEN** 返回 `null`

#### Scenario: 删除键
- **WHEN** 调用方执行 `redisUtils.delete(key)`，且该 key 存在
- **THEN** 该 key 被从 Redis 中删除，方法返回 `true`；若 key 本不存在则返回 `false`

#### Scenario: 判断键是否存在
- **WHEN** 调用方执行 `redisUtils.hasKey(key)`
- **THEN** 返回该 key 在 Redis 中是否存在的布尔结果

#### Scenario: 单独设置过期时间
- **WHEN** 调用方对一个已存在的 key 执行 `redisUtils.expire(key, duration)`
- **THEN** 该 key 的过期时间被更新为 `duration` 后过期，方法返回是否设置成功

#### Scenario: 查询剩余过期时间
- **WHEN** 调用方执行 `redisUtils.getExpire(key)`
- **THEN** 返回该 key 的剩余存活时间（`Duration`）；若 key 永久有效返回负值语义与
  `RedisTemplate` 原生行为一致（不做额外包装）

### Requirement: 统一封装 Redis Hash 命令
系统 SHALL 在 `RedisUtils` 中封装 `RedisTemplate` 的 `opsForHash()` 常用命令
（`hSet`、`hGet`、`hGetAll`、`hDelete`、`hHasKey`），业务代码 SHALL 通过该组件读写 Redis Hash
类型数据，而不是直接调用 `redisTemplate.opsForHash()`。

#### Scenario: 写入 Hash 字段
- **WHEN** 调用方执行 `redisUtils.hSet(key, hashKey, value)`
- **THEN** 该 key 对应的 Hash 中 `hashKey` 字段被写入/覆盖为 value

#### Scenario: 按调用处声明的类型读取 Hash 字段
- **WHEN** 调用方执行 `SomeType value = redisUtils.hGet(key, hashKey);`（不传 `Class` 参数），
  且该字段存在且存储的对象类型与调用处声明的 `SomeType` 一致
- **THEN** 返回反序列化后的对象，运行时类型与调用处声明的 `SomeType` 一致

#### Scenario: 读取不存在的 Hash 字段
- **WHEN** 调用方执行 `redisUtils.hGet(key, hashKey)`，且该字段不存在
- **THEN** 返回 `null`

#### Scenario: 读取整个 Hash
- **WHEN** 调用方执行 `redisUtils.hGetAll(key)`
- **THEN** 返回该 key 对应 Hash 的全部字段与值（`Map<Object, Object>`）；key 不存在时返回空 Map

#### Scenario: 删除 Hash 字段
- **WHEN** 调用方执行 `redisUtils.hDelete(key, hashKey)`，且该字段存在
- **THEN** 该字段被从 Hash 中删除，方法返回实际删除的字段数量

#### Scenario: 判断 Hash 字段是否存在
- **WHEN** 调用方执行 `redisUtils.hHasKey(key, hashKey)`
- **THEN** 返回该字段在 Hash 中是否存在的布尔结果

### Requirement: 统一封装 Redis List 命令
系统 SHALL 在 `RedisUtils` 中封装 `RedisTemplate` 的 `opsForList()` 常用命令
（`lLeftPush`、`lRightPush`、`lLeftPop`、`lRightPop`、`lRange`、`lSize`），业务代码 SHALL 通过该
组件读写 Redis List 类型数据，而不是直接调用 `redisTemplate.opsForList()`。

#### Scenario: 从左侧入队
- **WHEN** 调用方执行 `redisUtils.lLeftPush(key, value)`
- **THEN** value 被插入到该 List 的最左端，方法返回插入后 List 的长度

#### Scenario: 从右侧入队
- **WHEN** 调用方执行 `redisUtils.lRightPush(key, value)`
- **THEN** value 被插入到该 List 的最右端，方法返回插入后 List 的长度

#### Scenario: 从左侧出队
- **WHEN** 调用方执行 `SomeType value = redisUtils.lLeftPop(key);`（不传 `Class` 参数），
  且该 List 非空
- **THEN** 弹出并返回该 List 最左端的元素，运行时类型与调用处声明的 `SomeType` 一致；List 为空
  或 key 不存在时返回 `null`

#### Scenario: 从右侧出队
- **WHEN** 调用方执行 `SomeType value = redisUtils.lRightPop(key);`（不传 `Class` 参数），
  且该 List 非空
- **THEN** 弹出并返回该 List 最右端的元素，运行时类型与调用处声明的 `SomeType` 一致；List 为空
  或 key 不存在时返回 `null`

#### Scenario: 按区间读取
- **WHEN** 调用方执行 `redisUtils.lRange(key, start, end)`
- **THEN** 返回该 List 中下标区间 `[start, end]`（含两端，语义与 `LRANGE` 一致）内的元素列表

#### Scenario: 查询长度
- **WHEN** 调用方执行 `redisUtils.lSize(key)`
- **THEN** 返回该 List 的元素个数；key 不存在时返回 0

### Requirement: 统一封装 Redis Set 命令
系统 SHALL 在 `RedisUtils` 中封装 `RedisTemplate` 的 `opsForSet()` 常用命令
（`sAdd`、`sMembers`、`sIsMember`、`sRemove`、`sSize`），业务代码 SHALL 通过该组件读写 Redis Set
类型数据，而不是直接调用 `redisTemplate.opsForSet()`。

#### Scenario: 添加成员
- **WHEN** 调用方执行 `redisUtils.sAdd(key, value1, value2)`
- **THEN** value1、value2 被加入该 Set（已存在的成员不重复添加），方法返回实际新增的成员数量

#### Scenario: 读取全部成员
- **WHEN** 调用方执行 `redisUtils.sMembers(key)`
- **THEN** 返回该 Set 的全部成员；key 不存在时返回空集合

#### Scenario: 判断成员是否存在
- **WHEN** 调用方执行 `redisUtils.sIsMember(key, value)`
- **THEN** 返回 value 是否属于该 Set 的布尔结果

#### Scenario: 移除成员
- **WHEN** 调用方执行 `redisUtils.sRemove(key, value1, value2)`
- **THEN** value1、value2 被从该 Set 中移除，方法返回实际移除的成员数量

#### Scenario: 查询成员数量
- **WHEN** 调用方执行 `redisUtils.sSize(key)`
- **THEN** 返回该 Set 的成员个数；key 不存在时返回 0

### Requirement: 统一封装 Redis Sorted Set/ZSet 命令
系统 SHALL 在 `RedisUtils` 中封装 `RedisTemplate` 的 `opsForZSet()` 常用命令
（`zAdd`、`zRange`、`zScore`、`zRemove`、`zSize`），业务代码 SHALL 通过该组件读写 Redis
Sorted Set 类型数据，而不是直接调用 `redisTemplate.opsForZSet()`。

#### Scenario: 添加成员并指定分数
- **WHEN** 调用方执行 `redisUtils.zAdd(key, value, score)`
- **THEN** value 以 score 为排序依据加入该 Sorted Set；若 value 已存在，则更新其 score
  （与 `ZADD` 原生语义一致，不视为新增失败）

#### Scenario: 按排名区间读取
- **WHEN** 调用方执行 `redisUtils.zRange(key, start, end)`
- **THEN** 返回该 Sorted Set 中按 score 从小到大排序、排名区间 `[start, end]`（含两端）内的成员

#### Scenario: 查询成员分数
- **WHEN** 调用方执行 `redisUtils.zScore(key, value)`，且 value 是该 Sorted Set 的成员
- **THEN** 返回该成员的 score；value 不存在时返回 `null`

#### Scenario: 移除成员
- **WHEN** 调用方执行 `redisUtils.zRemove(key, value1, value2)`
- **THEN** value1、value2 被从该 Sorted Set 中移除，方法返回实际移除的成员数量

#### Scenario: 查询成员数量
- **WHEN** 调用方执行 `redisUtils.zSize(key)`
- **THEN** 返回该 Sorted Set 的成员个数；key 不存在时返回 0
