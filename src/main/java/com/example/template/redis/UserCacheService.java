package com.example.template.redis;

import com.example.template.util.RedisUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 用户信息的 Redis 缓存服务，统一管理 "user:{id}" 这个 key 的读写约定。
 */
@Service
public class UserCacheService {

    @Autowired
    private RedisUtils redisUtils;

    /** 缓存用户信息，30 分钟后自动过期。 */
    public void cacheUser(String userId, RedisUser user) {
        redisUtils.set("user:" + userId, user, Duration.ofMinutes(30));
    }

    /** 读取缓存的用户信息；未命中(不存在或已过期)时返回 null。 */
    public RedisUser getUser(String userId) {
        return redisUtils.get("user:" + userId);
    }

}
