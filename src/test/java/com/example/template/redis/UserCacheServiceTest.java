package com.example.template.redis;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.cache.CacheProperties;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class UserCacheServiceTest {

    @Autowired
    private UserCacheService userCacheService;

    private static RedisUser redisUser;

    @BeforeAll
    static void beforeAll() {
        redisUser = new RedisUser();
        redisUser.setId(1L);
        redisUser.setName(UUID.randomUUID().toString());
        redisUser.setCreateTime(LocalDateTime.now());
    }

    @Test
    void cacheUser() {
        userCacheService.cacheUser(Long.toString(redisUser.getId()), redisUser);
    }

    @Test
    void getUser() {
        RedisUser user = userCacheService.getUser(Long.toString(redisUser.getId()));
        Assertions.assertNotNull(user);
        Assertions.assertEquals(redisUser,  user);
    }
}
