package com.example.template.redis;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;

@Getter
@Setter
@ToString
@EqualsAndHashCode
public class RedisUser {

    private Long id;

    private String name;

    private LocalDateTime createTime;

}
