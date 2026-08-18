package com.example.template.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.databind.jsontype.PolymorphicTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class RedisConfig {

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        // key用String序列化(可读性好，方便redis-cli直接查看key)
        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);

        // value用Jackson JSON序列化(可读性好，跨语言兼容，且能处理泛型/多态类型)
        GenericJackson2JsonRedisSerializer jsonSerializer = new GenericJackson2JsonRedisSerializer(buildObjectMapper());
        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);

        template.afterPropertiesSet();
        return template;
    }

    private ObjectMapper buildObjectMapper() {
        ObjectMapper objectMapper = new ObjectMapper();

        // 支持所有字段(包括private)的序列化，不要求必须有getter/setter
        objectMapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);

        // 反序列化时忽略未知字段，避免"实体类改了字段但Redis里还是老结构"导致反序列化直接报错
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        // 支持Java 8时间类型(LocalDateTime/LocalDate等)，不加这个会序列化报错
        objectMapper.registerModule(new JavaTimeModule());

        // 关键: 启用类型信息，让反序列化时能还原出正确的具体类型(而不是变成LinkedHashMap)
        // 这里用PolymorphicTypeValidator限制允许反序列化的包路径，是2.10+版本后的安全推荐写法
        // 避免使用已废弃且有反序列化安全风险的 enableDefaultTyping() 老方法
        PolymorphicTypeValidator typeValidator = BasicPolymorphicTypeValidator.builder()
            .allowIfSubType("com.example")   // 只允许你自己项目包路径下的类参与多态反序列化，收紧安全边界
            .allowIfSubType("java.util")
            .build();

        objectMapper.activateDefaultTyping(typeValidator, ObjectMapper.DefaultTyping.NON_FINAL);

        return objectMapper;
    }
}
