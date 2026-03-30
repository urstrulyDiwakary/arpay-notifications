package com.arpay.config;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettucePoolingClientConfiguration;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.util.StringUtils;

import java.time.Duration;

/**
 * Redis configuration with proper Lettuce connection pooling, timeouts, and serialization.
 * <p>
 * Key fixes over previous version:
 * - Connection pooling via {@link LettucePoolingClientConfiguration} (was missing entirely)
 * - Command timeout and socket connect timeout configured
 * - Auto-reconnect enabled with REJECT_COMMANDS on disconnect (fail-fast)
 * - Hash value serializer uses StringRedisSerializer for Redis Streams compatibility
 * - Dedicated StringRedisTemplate bean for simple key-value operations
 */
@Configuration
@Slf4j
public class RedisConfig {

    @Value("${spring.data.redis.host:localhost}")
    private String redisHost;

    @Value("${spring.data.redis.port:6379}")
    private int redisPort;

    @Value("${spring.data.redis.password:}")
    private String redisPassword;

    @Value("${spring.data.redis.lettuce.pool.max-active:20}")
    private int poolMaxActive;

    @Value("${spring.data.redis.lettuce.pool.max-idle:10}")
    private int poolMaxIdle;

    @Value("${spring.data.redis.lettuce.pool.min-idle:4}")
    private int poolMinIdle;

    @Bean
    public RedisConnectionFactory redisConnectionFactory() {
        // Server configuration
        RedisStandaloneConfiguration serverConfig = new RedisStandaloneConfiguration(redisHost, redisPort);
        if (StringUtils.hasText(redisPassword)) {
            serverConfig.setPassword(RedisPassword.of(redisPassword));
        }

        // Connection pool configuration
        GenericObjectPoolConfig<?> poolConfig = new GenericObjectPoolConfig<>();
        poolConfig.setMaxTotal(poolMaxActive);
        poolConfig.setMaxIdle(poolMaxIdle);
        poolConfig.setMinIdle(poolMinIdle);
        poolConfig.setTestOnBorrow(true);
        poolConfig.setTestWhileIdle(true);
        poolConfig.setTimeBetweenEvictionRuns(Duration.ofSeconds(30));

        // Lettuce client configuration with pool, timeouts, and reconnect
        LettucePoolingClientConfiguration clientConfig = LettucePoolingClientConfiguration.builder()
                .poolConfig(poolConfig)
                .commandTimeout(Duration.ofSeconds(10))
                .clientOptions(ClientOptions.builder()
                        .socketOptions(SocketOptions.builder()
                                .connectTimeout(Duration.ofSeconds(3))
                                .keepAlive(true)
                                .build())
                        .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                        .autoReconnect(true)
                        .build())
                .build();

        LettuceConnectionFactory factory = new LettuceConnectionFactory(serverConfig, clientConfig);
        factory.setValidateConnection(true);

        log.info("Redis configured: host={}, port={}, pool=[maxActive={}, maxIdle={}, minIdle={}]",
                redisHost, redisPort, poolMaxActive, poolMaxIdle, poolMinIdle);

        return factory;
    }

    /**
     * Primary RedisTemplate for general use.
     * - Key/HashKey: StringRedisSerializer (readable keys)
     * - Value: GenericJackson2JsonRedisSerializer (complex value objects)
     * - HashValue: StringRedisSerializer (required for Redis Streams compatibility)
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new StringRedisSerializer());
        template.afterPropertiesSet();
        return template;
    }

    /**
     * StringRedisTemplate for simple string key-value operations.
     */
    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }
}
