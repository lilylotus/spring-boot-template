package com.example.template.config;

import com.example.template.util.HttpClientUtils;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

/**
 * 统一 HTTP 客户端相关配置，绑定前缀 {@code rbac.http-client}，供 {@link HttpClientUtils}
 * 构建进程级单例 {@code CloseableHttpClient} 使用（backend-common-utilities change
 * design.md Decision 11）。Bean 初始化完成后立即把自身推送给 {@link HttpClientUtils}，
 * 覆盖其内置的默认值；{@link HttpClientUtils} 本身不依赖 Spring 容器（对齐
 * {@code JacksonUtils} 的既有组织方式），未经过 Spring 初始化时（如纯单元测试）
 * 仍可用内置默认值正常工作。
 */
@Getter
@Setter
@Component
@RefreshScope
@ConfigurationProperties(prefix = "rbac.http-client")
public class HttpClientProperties {

    /** 连接超时（毫秒），不支持按次请求覆盖，默认 3000。 */
    private long connectTimeoutMillis = 3000L;

    /** 响应超时（毫秒），未按次请求指定时使用该全局默认值，默认 5000。 */
    private long responseTimeoutMillis = 5000L;

    /** 连接池最大连接数，默认 200。 */
    private int maxTotal = 200;

    /** 连接池单路由最大连接数，默认 50。 */
    private int maxPerRoute = 50;

    /** 是否跳过 HTTPS 证书校验（仅建议内网自签场景开启），默认 {@code false}。 */
    private boolean skipSslVerify = true;

    /**
     * Bean 初始化完成后把当前配置推送给 {@link HttpClientUtils}，触发其内部单例
     * {@code CloseableHttpClient} 按最新配置重建（懒加载，下次实际调用时才真正构建）。
     */
    @PostConstruct
    public void applyToHttpClientUtils() {
        HttpClientUtils.configure(this);
    }
}
