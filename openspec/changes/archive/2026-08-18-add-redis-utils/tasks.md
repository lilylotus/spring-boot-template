## 1. 新增 RedisUtils 工具类（String）

- [x] 1.1 在 `com.example.template.util` 包下新增 `RedisUtils` 类，`@Component`，
      通过构造器注入 `RedisTemplate<String, Object>`
- [x] 1.2 实现 `void set(String key, Object value)`（不带过期时间）
- [x] 1.3 实现 `void set(String key, Object value, Duration timeout)`（带过期时间）
- [x] 1.4 【返工】现有实现是 `<T> T get(String key, Class<T> type)`（用 `type.cast(...)`）+ 一个
      不带类型参数、返回 `Object` 的重载。改为 `<T> T get(String key)` 单一方法：依赖调用处泛型
      推断，内部 `@SuppressWarnings("unchecked")` 做 `(T) value` 强转，不再需要 `Class<T>`
      参数（`RedisConfig` 的 `GenericJackson2JsonRedisSerializer` + `activateDefaultTyping` 已经
      能在反序列化阶段还原出正确的具体类型，见 design.md）；同时删除那个 `Object get(String key)`
      重载（与新 `get` 参数擦除后签名相同，二者不能共存）
- [x] 1.5 实现 `boolean delete(String key)`
- [x] 1.6 实现 `boolean hasKey(String key)`
- [x] 1.7 实现 `boolean expire(String key, Duration timeout)`
- [x] 1.8 实现 `Duration getExpire(String key)`
- [x] 1.9 为类和每个方法按仓库注释规范添加中文注释（说明用途、参数、返回值及非显而易见的行为，
      如 key 不存在时的返回值语义）

## 2. 扩展 RedisUtils：Hash

- [x] 2.1 实现 `void hSet(String key, Object hashKey, Object value)`
- [x] 2.2 实现 `<T> T hGet(String key, Object hashKey)`（不带 `Class<T>` 参数，与 1.4 的
      `get(String key)` 采用同样的泛型推断 + 无检查强转写法）
- [x] 2.3 实现 `Map<Object, Object> hGetAll(String key)`
- [x] 2.4 实现 `Long hDelete(String key, Object... hashKeys)`
- [x] 2.5 实现 `boolean hHasKey(String key, Object hashKey)`
- [x] 2.6 为以上方法添加中文注释（说明字段不存在/key 不存在时的返回值语义）

## 3. 扩展 RedisUtils：List

- [x] 3.1 实现 `Long lLeftPush(String key, Object value)`
- [x] 3.2 实现 `Long lRightPush(String key, Object value)`
- [x] 3.3 实现 `<T> T lLeftPop(String key)`（不带 `Class<T>` 参数，写法同 1.4）
- [x] 3.4 实现 `<T> T lRightPop(String key)`（不带 `Class<T>` 参数，写法同 1.4）
- [x] 3.5 实现 `List<Object> lRange(String key, long start, long end)`
- [x] 3.6 实现 `Long lSize(String key)`
- [x] 3.7 为以上方法添加中文注释（说明 List 为空/key 不存在时的返回值语义，以及
      `lRange` 区间含两端的语义）

## 4. 扩展 RedisUtils：Set

- [x] 4.1 实现 `Long sAdd(String key, Object... values)`
- [x] 4.2 实现 `Set<Object> sMembers(String key)`
- [x] 4.3 实现 `boolean sIsMember(String key, Object value)`
- [x] 4.4 实现 `Long sRemove(String key, Object... values)`
- [x] 4.5 实现 `Long sSize(String key)`
- [x] 4.6 为以上方法添加中文注释（说明 key 不存在时的返回值语义）

## 5. 扩展 RedisUtils：Sorted Set/ZSet

- [x] 5.1 实现 `Boolean zAdd(String key, Object value, double score)`
- [x] 5.2 实现 `Set<Object> zRange(String key, long start, long end)`
- [x] 5.3 实现 `Double zScore(String key, Object value)`
- [x] 5.4 实现 `Long zRemove(String key, Object... values)`
- [x] 5.5 实现 `Long zSize(String key)`
- [x] 5.6 为以上方法添加中文注释（说明 `zAdd` 对已存在成员是更新 score 而非新增失败的语义，
      以及成员不存在时 `zScore` 的返回值语义）

## 6. 接入现有代码

- [x] 6.1 将 `UserCacheService` 改为注入 `RedisUtils`，用其替换直接调用
      `redisTemplate.opsForValue().set/get`
- [x] 6.2 【返工】`UserCacheService.getUser()` 目前调用的是旧签名
      `redisUtils.get("user:" + userId, RedisUser.class)`，1.4 改完之后要同步改成
      `redisUtils.get("user:" + userId)`（返回类型 `RedisUser` 由方法声明的返回值推断出泛型）
- [x] 6.3 确认 `UserCacheServiceTest` 中 `cacheUser`/`getUser` 用例语义不变——已在本地 Redis
      环境下执行 `./gradlew test --tests "*UserCacheServiceTest"`，2 个用例全部通过

## 7. 收尾

- [x] 7.1 执行 `./gradlew compileJava` 确认编译通过（String 部分，已完成）
- [x] 7.2 补充 Hash/List/Set/ZSet 后重新执行 `./gradlew compileJava` 确认编译通过
- [x] 7.3 走查 `RedisUtils` 是否覆盖 proposal/design/spec 中列出的全部命令与场景（五大类型）
