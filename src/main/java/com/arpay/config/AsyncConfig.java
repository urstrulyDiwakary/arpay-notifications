package com.arpay.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.Executor;

/**
 * Async configuration for notification processing.
 * Provides separate thread pools for different priority levels.
 */
@Configuration
@EnableAsync
@EnableScheduling
@Slf4j
public class AsyncConfig {
    
    @Value("${notification.worker.critical-threads:5}")
    private int criticalThreads;
    
    @Value("${notification.worker.normal-threads:10}")
    private int normalThreads;
    
    @Value("${notification.worker.low-threads:3}")
    private int lowThreads;
    
    @Value("${notification.worker.max-inflight:200}")
    private int maxInflight;
    
    @Value("${notification.worker.queue-capacity:500}")
    private int queueCapacity;
    
    /**
     * Executor for critical priority notifications
     */
    @Bean(name = "criticalNotificationExecutor")
    public Executor criticalNotificationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(criticalThreads);
        executor.setMaxPoolSize(criticalThreads * 2);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("notification-critical-");
        executor.setRejectedExecutionHandler((r, exec) -> {
            log.warn("Critical notification rejected - queue full. Consider scaling workers.");
        });
        executor.initialize();
        log.info("Initialized critical notification executor: coreThreads={}, maxThreads={}, queueCapacity={}", 
                 criticalThreads, criticalThreads * 2, queueCapacity);
        return executor;
    }
    
    /**
     * Executor for normal priority notifications
     */
    @Bean(name = "normalNotificationExecutor")
    public Executor normalNotificationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(normalThreads);
        executor.setMaxPoolSize(normalThreads * 2);
        executor.setQueueCapacity(queueCapacity * 2);
        executor.setThreadNamePrefix("notification-normal-");
        executor.setRejectedExecutionHandler((r, exec) -> {
            log.warn("Normal notification rejected - queue full.");
        });
        executor.initialize();
        log.info("Initialized normal notification executor: coreThreads={}, maxThreads={}, queueCapacity={}", 
                 normalThreads, normalThreads * 2, queueCapacity * 2);
        return executor;
    }
    
    /**
     * Executor for low priority notifications
     */
    @Bean(name = "lowNotificationExecutor")
    public Executor lowNotificationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(lowThreads);
        executor.setMaxPoolSize(lowThreads * 2);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("notification-low-");
        executor.initialize();
        log.info("Initialized low notification executor: coreThreads={}, maxThreads={}, queueCapacity={}", 
                 lowThreads, lowThreads * 2, queueCapacity);
        return executor;
    }
    
    /**
     * Executor for DLQ retry processing
     */
    @Bean(name = "dlqRetryExecutor")
    public Executor dlqRetryExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(3);
        executor.setMaxPoolSize(5);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("dlq-retry-");
        executor.initialize();
        log.info("Initialized DLQ retry executor");
        return executor;
    }
    
    /**
     * Task scheduler for scheduled jobs (DLQ retry, cleanup, etc.)
     */
    @Bean(name = "notificationTaskScheduler")
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(5);
        scheduler.setThreadNamePrefix("notification-scheduler-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        scheduler.initialize();
        log.info("Initialized notification task scheduler");
        return scheduler;
    }
}
