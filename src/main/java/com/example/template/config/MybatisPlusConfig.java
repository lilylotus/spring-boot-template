package com.example.template.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 全局配置。若不注册分页插件，{@code BaseMapper#selectPage} 不会在 SQL 层追加
 * LIMIT/OFFSET，也不会计算 total，只会原样返回未分页的结果——这里统一注册好，各模块的
 * 分页查询无需重复配置。
 */
@Configuration
public class MybatisPlusConfig {

    /**
     * 注册 MySQL 分页插件，使 {@code IPage}/{@code Page} 参数的查询真正生效。
     *
     * @return MyBatis-Plus 拦截器
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }

}
