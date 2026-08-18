## Context

`RedisConfig` 已经配置好唯一的 `RedisTemplate<String, Object>` bean（string key + Jackson JSON
value，`activateDefaultTyping` 限定 `com.example`/`java.util` 包）。当前只有 `UserCacheService`
一处用例，直接注入 `RedisTemplate` 并调用 `opsForValue().set/get`。工具类要解决的是"每次都要写
`redisTemplate.opsForValue()`"这种重复、非静态方法引用不便的问题，而不是重新设计序列化方案。

## Goals / Non-Goals

**Goals:**
- 提供一个 Spring 组件（`@Component`），统一封装 `opsForValue()` 常用的字符串类型命令：
  `set`（不带过期）、`set`（带过期时间，`Duration`）、`get`（返回值按调用处的泛型自动推断）、
  `delete`、`hasKey`、`expire`、`getExpire`。
- 补齐 Redis 另外四大数据类型的常用命令封装，方法签名风格与字符串方法保持一致：
  - Hash（`opsForHash`）：`hSet`、`hGet`（返回值按调用处的泛型自动推断）、`hGetAll`、`hDelete`、
    `hHasKey`
  - List（`opsForList`）：`lLeftPush`、`lRightPush`、`lLeftPop`/`lRightPop`（返回值按调用处的
    泛型自动推断）、`lRange`、`lSize`
  - Set（`opsForSet`）：`sAdd`（可变参数）、`sMembers`、`sIsMember`、`sRemove`（可变参数）、`sSize`
  - Sorted Set/ZSet（`opsForZSet`）：`zAdd`（带 score）、`zRange`（按排名区间）、`zScore`、
    `zRemove`（可变参数）、`zSize`
- 方法签名尽量简洁，调用方不需要感知 `ValueOperations`/`HashOperations`/`ListOperations`/
  `SetOperations`/`ZSetOperations` 这些底层接口。
- 让现有 `UserCacheService` 改用该工具类，验证 API 是否好用。

**Non-Goals:**
- 不封装 Stream 数据结构——当前代码库里没有相关用例，按需再加，避免过度设计。
- 不封装每种数据类型的全部命令（如 Hash 的 `hIncrBy`、List 的按索引 `lIndex`/`lSet`、ZSet 的
  按分数区间查询 `rangeByScore` 等）——只覆盖最常用的增/删/查/判存在，其余命令后续按实际用例
  再补充。
- 不引入分布式锁、限流等更高层的 Redis 能力封装。
- 不改变现有的序列化方式或 `RedisConfig`。

## Decisions

- **注入方式**：使用构造器注入 `RedisTemplate<String, Object>`（而非 `@Autowired` 字段注入），
  与仓库里普遍使用字段注入的风格不同，但构造器注入更利于测试且是 Spring 官方推荐方式；
  由于这是新工具类而非已有代码风格延续，采用更规范的写法。
- **`get` 类方法不需要 `Class<T>` 参数**：`RedisConfig` 里的 `RedisTemplate` 使用
  `GenericJackson2JsonRedisSerializer` 并开启了 `activateDefaultTyping`，序列化时已经把类型信息
  （`@class`）写入 JSON；反序列化时 Jackson 依据这个信息直接还原出正确的具体类型，而不是退化成
  `LinkedHashMap`。也就是说 `redisTemplate.opsForValue().get(key)` 拿到的 `Object` 在运行时已经
  就是正确的具体类型，调用方额外传入 `Class<T>` 只是为了做一次 `type.cast(...)`——这个转换不改变
  任何反序列化结果，纯属多余。因此统一采用 `<T> T get(String key)` 这种依赖调用处赋值目标推断
  泛型的写法（内部做 `@SuppressWarnings("unchecked")` 的强转 `(T) value`），去掉 `Class<T> type`
  参数；`hGet`、`lLeftPop`、`lRightPop` 等"读取单个对象"的方法同理，都不再要求调用方传入
  `Class<T>`。**不再**额外保留一个返回 `Object` 的重载——`<T> T get(String key)` 与
  `Object get(String key)` 参数列表相同、仅返回类型不同，属于非法的重复方法签名（Java 按参数
  和方法名做重载判定，不看返回类型），调用方不关心具体类型时直接写
  `Object value = redisUtils.get(key);` 让泛型推断为 `Object` 即可，无需单独的重载。
- **key 前缀不在工具类里处理**：`UserCacheService` 里 `"user:" + userId` 这种业务 key 拼接逻辑
  仍留在业务层，工具类只负责"给定 key 执行命令"，保持职责单一、通用。
- **异常处理**：不在工具类内吞掉 Redis 连接异常，直接让 `RedisConnectionFailureException` 等
  向上抛出，由调用方或全局异常处理决定如何处理——工具类只做命令封装，不做容错策略。
- **方法命名前缀**：Hash/List/Set/ZSet 的方法分别用 `h`/`l`/`s`/`z` 前缀（如 `hSet`、`lRange`、
  `sAdd`、`zScore`），String 方法维持不带前缀（`set`/`get`）。理由：字符串操作是最基础、最高频的
  用法，保持简短；其余四种类型加前缀是为了在同一个类里避免方法名冲突（例如 `size` 若不加前缀，
  List/Set/ZSet 三种类型都需要"大小"语义，无法共用同一个无前缀方法名）。
- **Hash key/value 类型**：`HashOperations<String, Object, Object>` 对应的 hashKey 和 value 都用
  `Object` 承接（与 `RedisTemplate<String, Object>` 的声明保持一致），`hGet` 与 `get` 一样不带
  `Class<T>` 参数，采用 `<T> T hGet(String key, Object hashKey)` 依赖调用处泛型推断的写法。
- **List 的 pop 方法**：只提供 `lLeftPop`/`lRightPop` 的无阻塞、无超时版本（对应
  `ListOperations#leftPop(key)`/`rightPop(key)`），不封装带超时的阻塞版本
  （`BLPOP`/`BRPOP` 语义）——当前没有需要阻塞等待的用例，避免引入超时参数增加复杂度。
- **可变参数命令**：`sAdd`/`sRemove`/`zRemove` 使用 `Object...` 可变参数，一次调用可添加/删除
  多个成员，直接对应 `SetOperations#add/remove`、`ZSetOperations#remove` 的原生签名，不做拆分。

## Risks / Trade-offs

- [风险] `<T> T get(String key)` / `hGet` / `lLeftPop`/`lRightPop` 内部做的是无检查的泛型强转
  （`@SuppressWarnings("unchecked")` + `(T) value`），如果调用处声明的目标类型与 Redis 中实际
  存储的类型不一致，不会在这一行报错，而是在后续使用该返回值时才抛出 `ClassCastException`
  （Java 泛型擦除的固有限制，并非本工具类引入的新问题）→ 缓解：方法注释明确说明由调用方保证
  声明的类型与实际存储类型一致；这与直接使用 `RedisTemplate` 原生 API 时的行为一致，本类不做
  额外的运行时类型校验或异常吞掉处理。
- [权衡] 每种数据类型只覆盖最常用的增/删/查/判存在命令，不是命令全集，后续如果要用范围查询、
  按索引操作等进阶命令，需要再扩展工具类 → 可接受，遵循"按需添加、不过度设计"的原则。
- [风险] `zAdd` 对同一个 (key, value) 重复添加时会更新 score 而不是报错（ZSet 原生语义）
  → 无需缓解，属于预期行为，方法注释中说明即可，避免调用方误以为是"新增失败"。
