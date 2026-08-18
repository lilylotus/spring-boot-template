package com.example.template.util;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Redis 五大数据类型(String/Hash/List/Set/Sorted Set)常用命令的统一封装工具类。
 * 业务代码应通过本类读写 Redis，而不是各处重复编写 redisTemplate.opsForXxx() 调用，
 * 便于集中管理过期时间设置等约定，使调用代码更简洁。
 * <p>
 * 底层直接复用 {@link com.example.template.config.RedisConfig} 中配置好的
 * RedisTemplate(string key + Jackson JSON value，开启了 activateDefaultTyping)，本类不改变、
 * 也不感知具体的序列化方式。正因为 RedisTemplate 反序列化时已经依据 JSON 里内嵌的类型信息还原出
 * 正确的具体类型，本类的 get 类方法都不需要调用方额外传入 Class 参数做二次转换，
 * 直接依赖调用处的泛型推断即可。
 */
@Component
public class RedisUtils {

    /** 已按 RedisConfig 配置好序列化方式的模板，本类只负责封装命令调用。 */
    private final RedisTemplate<String, Object> redisTemplate;

    /**
     * 构造函数，注入统一配置好的 RedisTemplate。
     *
     * @param redisTemplate 字符串 key、JSON 序列化 value 的 RedisTemplate
     */
    public RedisUtils(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    // ==================== String ====================

    /**
     * 写入键值，不设置过期时间(永久有效，直到被显式删除)。
     *
     * @param key   Redis key
     * @param value 待写入的值
     */
    public void set(String key, Object value) {
        redisTemplate.opsForValue().set(key, value);
    }

    /**
     * 写入键值，并设置过期时间。
     *
     * @param key     Redis key
     * @param value   待写入的值
     * @param timeout 过期时间，到期后 key 自动失效
     */
    public void set(
            String key, Object value,
            Duration timeout) {
        redisTemplate.opsForValue().set(key, value, timeout);
    }

    /**
     * 读取键值，返回类型由调用处的泛型赋值目标推断，无需传入 Class 参数——RedisTemplate 反序列化时
     * 已依据 JSON 里内嵌的类型信息还原出正确的具体类型，这里只是做一次无检查的强转。
     * 调用方需自行保证声明的类型与实际存储的对象类型一致，否则会在后续使用该返回值时抛出
     * ClassCastException，这与直接使用 RedisTemplate 原生 API 的行为一致，本类不做额外的
     * 异常吞掉处理。
     *
     * @param key Redis key
     * @param <T> 返回值类型，由调用处推断
     * @return key 对应的值；key 不存在时返回 null
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String key) {
        Object value = redisTemplate.opsForValue().get(key);
        return (T) value;
    }

    /**
     * 删除指定 key。
     *
     * @param key Redis key
     * @return true 表示删除成功；key 本不存在时返回 false
     */
    public boolean delete(String key) {
        Boolean deleted = redisTemplate.delete(key);
        return Boolean.TRUE.equals(deleted);
    }

    /**
     * 判断 key 是否存在。
     *
     * @param key Redis key
     * @return true 表示存在
     */
    public boolean hasKey(String key) {
        Boolean exists = redisTemplate.hasKey(key);
        return Boolean.TRUE.equals(exists);
    }

    /**
     * 为已存在的 key 单独设置/更新过期时间。
     *
     * @param key     Redis key
     * @param timeout 过期时间
     * @return true 表示设置成功
     */
    public boolean expire(String key, Duration timeout) {
        Boolean result = redisTemplate.expire(key, timeout);
        return Boolean.TRUE.equals(result);
    }

    /**
     * 查询 key 的剩余存活时间。
     * 返回值语义与 RedisTemplate 原生行为一致：key 永久有效或不存在时返回负数，本方法不做额外包装。
     *
     * @param key Redis key
     * @return 剩余存活时间
     */
    public Duration getExpire(String key) {
        // RedisTemplate#getExpire 理论上只在连接异常时才返回 null，正常情况下(含永久/不存在)
        // 都会返回一个 long 值，这里兜底转换成 -1 避免拆箱空指针。
        Long millis = redisTemplate.getExpire(key, TimeUnit.MILLISECONDS);
        return Duration.ofMillis(millis == null ? -1L : millis);
    }

    // ==================== Hash ====================

    /**
     * 写入 Hash 中的一个字段。
     *
     * @param key     Redis key
     * @param hashKey Hash 内的字段名
     * @param value   待写入的值
     */
    public void hSet(String key, Object hashKey, Object value) {
        redisTemplate.opsForHash().put(key, hashKey, value);
    }

    /**
     * 读取 Hash 中的一个字段，返回类型由调用处的泛型赋值目标推断，写法与 {@link #get(String)}
     * 一致，同样不需要传入 Class 参数。
     *
     * @param key     Redis key
     * @param hashKey Hash 内的字段名
     * @param <T>     返回值类型，由调用处推断
     * @return 字段对应的值；字段或 key 不存在时返回 null
     */
    @SuppressWarnings("unchecked")
    public <T> T hGet(String key, Object hashKey) {
        Object value = redisTemplate.opsForHash().get(key, hashKey);
        return (T) value;
    }

    /**
     * 读取整个 Hash 的全部字段与值。
     *
     * @param key Redis key
     * @return 该 Hash 的全部字段与值；key 不存在时返回空 Map
     */
    public Map<Object, Object> hGetAll(String key) {
        return redisTemplate.opsForHash().entries(key);
    }

    /**
     * 删除 Hash 中的一个或多个字段。
     *
     * @param key      Redis key
     * @param hashKeys 待删除的字段名，可传多个
     * @return 实际删除的字段数量
     */
    public Long hDelete(String key, Object... hashKeys) {
        return redisTemplate.opsForHash().delete(key, hashKeys);
    }

    /**
     * 判断 Hash 中某个字段是否存在。
     *
     * @param key     Redis key
     * @param hashKey Hash 内的字段名
     * @return true 表示存在
     */
    public boolean hHasKey(String key, Object hashKey) {
        Boolean exists = redisTemplate.opsForHash().hasKey(key, hashKey);
        return Boolean.TRUE.equals(exists);
    }

    // ==================== List ====================

    /**
     * 将元素插入 List 最左端(头部)。
     *
     * @param key   Redis key
     * @param value 待插入的元素
     * @return 插入后 List 的长度
     */
    public Long lLeftPush(String key, Object value) {
        return redisTemplate.opsForList().leftPush(key, value);
    }

    /**
     * 将元素插入 List 最右端(尾部)。
     *
     * @param key   Redis key
     * @param value 待插入的元素
     * @return 插入后 List 的长度
     */
    public Long lRightPush(String key, Object value) {
        return redisTemplate.opsForList().rightPush(key, value);
    }

    /**
     * 弹出并返回 List 最左端(头部)的元素，写法与 {@link #get(String)} 一致，不带 Class 参数、
     * 不支持阻塞等待——List 为空时立即返回 null，而不是像 BLPOP 那样等待超时。
     *
     * @param key Redis key
     * @param <T> 返回值类型，由调用处推断
     * @return 弹出的元素；List 为空或 key 不存在时返回 null
     */
    @SuppressWarnings("unchecked")
    public <T> T lLeftPop(String key) {
        Object value = redisTemplate.opsForList().leftPop(key);
        return (T) value;
    }

    /**
     * 弹出并返回 List 最右端(尾部)的元素，语义同 {@link #lLeftPop(String)}，方向相反。
     *
     * @param key Redis key
     * @param <T> 返回值类型，由调用处推断
     * @return 弹出的元素；List 为空或 key 不存在时返回 null
     */
    @SuppressWarnings("unchecked")
    public <T> T lRightPop(String key) {
        Object value = redisTemplate.opsForList().rightPop(key);
        return (T) value;
    }

    /**
     * 按下标区间读取 List 中的元素，区间语义与 Redis 原生 LRANGE 一致，含两端(闭区间)。
     *
     * @param key   Redis key
     * @param start 起始下标(含)
     * @param end   结束下标(含)
     * @return 区间内的元素列表
     */
    public List<Object> lRange(String key, long start, long end) {
        return redisTemplate.opsForList().range(key, start, end);
    }

    /**
     * 查询 List 的元素个数。
     *
     * @param key Redis key
     * @return 元素个数；key 不存在时返回 0
     */
    public Long lSize(String key) {
        return redisTemplate.opsForList().size(key);
    }

    // ==================== Set ====================

    /**
     * 向 Set 中添加一个或多个成员，已存在的成员不会重复添加。
     *
     * @param key    Redis key
     * @param values 待添加的成员，可传多个
     * @return 实际新增的成员数量
     */
    public Long sAdd(String key, Object... values) {
        return redisTemplate.opsForSet().add(key, values);
    }

    /**
     * 读取 Set 的全部成员。
     *
     * @param key Redis key
     * @return 全部成员；key 不存在时返回空集合
     */
    public Set<Object> sMembers(String key) {
        return redisTemplate.opsForSet().members(key);
    }

    /**
     * 判断某个值是否是 Set 的成员。
     *
     * @param key   Redis key
     * @param value 待判断的值
     * @return true 表示是成员
     */
    public boolean sIsMember(String key, Object value) {
        Boolean isMember = redisTemplate.opsForSet().isMember(key, value);
        return Boolean.TRUE.equals(isMember);
    }

    /**
     * 从 Set 中移除一个或多个成员。
     *
     * @param key    Redis key
     * @param values 待移除的成员，可传多个
     * @return 实际移除的成员数量
     */
    public Long sRemove(String key, Object... values) {
        return redisTemplate.opsForSet().remove(key, values);
    }

    /**
     * 查询 Set 的成员数量。
     *
     * @param key Redis key
     * @return 成员数量；key 不存在时返回 0
     */
    public Long sSize(String key) {
        return redisTemplate.opsForSet().size(key);
    }

    // ==================== Sorted Set / ZSet ====================

    /**
     * 向 Sorted Set 中添加一个成员并指定其 score。若该成员已存在，则更新其 score
     * (与 Redis 原生 ZADD 语义一致，不视为新增失败)。
     *
     * @param key   Redis key
     * @param value 待添加的成员
     * @param score 排序依据的分数
     * @return true 表示成员是新添加的；成员已存在(仅更新了 score)时返回 false
     */
    public Boolean zAdd(String key, Object value, double score) {
        return redisTemplate.opsForZSet().add(key, value, score);
    }

    /**
     * 按排名区间读取 Sorted Set 中的成员，按 score 从小到大排序，区间含两端(闭区间)，
     * 语义与 Redis 原生 ZRANGE 一致。
     *
     * @param key   Redis key
     * @param start 起始排名(含，从 0 开始)
     * @param end   结束排名(含)
     * @return 区间内的成员集合
     */
    public Set<Object> zRange(String key, long start, long end) {
        return redisTemplate.opsForZSet().range(key, start, end);
    }

    /**
     * 查询 Sorted Set 中某个成员的 score。
     *
     * @param key   Redis key
     * @param value 待查询的成员
     * @return 该成员的 score；成员不存在时返回 null
     */
    public Double zScore(String key, Object value) {
        return redisTemplate.opsForZSet().score(key, value);
    }

    /**
     * 从 Sorted Set 中移除一个或多个成员。
     *
     * @param key    Redis key
     * @param values 待移除的成员，可传多个
     * @return 实际移除的成员数量
     */
    public Long zRemove(String key, Object... values) {
        return redisTemplate.opsForZSet().remove(key, values);
    }

    /**
     * 查询 Sorted Set 的成员数量。
     *
     * @param key Redis key
     * @return 成员数量；key 不存在时返回 0
     */
    public Long zSize(String key) {
        return redisTemplate.opsForZSet().size(key);
    }

}
