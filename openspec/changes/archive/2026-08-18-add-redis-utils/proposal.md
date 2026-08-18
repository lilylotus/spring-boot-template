## Why

目前业务代码中每次操作 Redis 都要直接注入 `RedisTemplate<String, Object>`，再手写
`redisTemplate.opsForValue().set(...)` / `.get(...)` 这类调用（例如 `UserCacheService`）。
这种写法重复、不美观，key 的构造、过期时间、异常处理等约定也散落在各处，难以统一管理。
需要一个统一的 Redis 工具类，封装常用命令，让业务代码调用更简洁、约定更集中。

## What Changes

- 新增 `RedisUtils` 工具类，封装 `opsForValue()` 对应的常用字符串类型操作：
  `set`（带/不带过期时间）、`get`（依赖 `RedisTemplate` 已配置的 Jackson 类型信息自动还原为
  调用处声明的类型，无需传入 `Class` 参数）、`delete`、`hasKey`、`expire`、`getExpire`。
- 扩展 `RedisUtils`，补齐 Redis 另外四大数据类型的常用命令封装（方法名分别用 `h`/`l`/`s`/`z`
  前缀区分类型，与 `opsForValue()` 对应的字符串方法风格保持一致）：
  - Hash（`opsForHash`）：`hSet`、`hGet`、`hGetAll`、`hDelete`、`hHasKey`
  - List（`opsForList`）：`lLeftPush`、`lRightPush`、`lLeftPop`、`lRightPop`、`lRange`、`lSize`
  - Set（`opsForSet`）：`sAdd`、`sMembers`、`sIsMember`、`sRemove`、`sSize`
  - Sorted Set/ZSet（`opsForZSet`）：`zAdd`、`zRange`、`zScore`、`zRemove`、`zSize`
- 工具类基于现有 `RedisConfig` 中已配置好的 `RedisTemplate<String, Object>` bean，不新增序列化逻辑。
- 现有 `UserCacheService` 改为通过 `RedisUtils` 实现，替换掉直接调用
  `redisTemplate.opsForValue()` 的写法，作为工具类的示例用法。

## Capabilities

### New Capabilities
- `redis-utils`：统一封装 Redis 五大数据类型（String/Hash/List/Set/ZSet）常用命令的工具类，
  供业务代码调用，替代直接操作 `RedisTemplate`。

### Modified Capabilities
（无，`UserCacheService` 属于实现细节调整，不涉及对外行为/需求变化）

## Impact

- 新增：`com.example.template.util.RedisUtils`
- 修改：`com.example.template.redis.UserCacheService`（改为委托给 `RedisUtils`）
- 依赖：无新增第三方依赖，复用现有 `spring-boot-starter-data-redis` 与 `RedisConfig`
