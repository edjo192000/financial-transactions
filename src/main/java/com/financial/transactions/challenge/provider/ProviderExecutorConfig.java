package com.financial.transactions.challenge.provider;

import jakarta.annotation.PreDestroy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ProviderProperties.class)
public class ProviderExecutorConfig {

    private ThreadPoolExecutor providerExecutor;

    @Bean
    public ThreadPoolExecutor providerExecutor(ProviderProperties properties) {
        ProviderProperties.ProviderExecutor config = properties.providerExecutor();

        AtomicInteger threadCount = new AtomicInteger(1);
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "provider-worker-" + threadCount.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };

        providerExecutor = new ThreadPoolExecutor(
                config.corePoolSize(),
                config.maxPoolSize(),
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(config.queueCapacity()),
                threadFactory,
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
        return providerExecutor;
    }

    @PreDestroy
    public void shutdown() {
        if (providerExecutor == null) {
            return;
        }
        providerExecutor.shutdown();
        try {
            if (!providerExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                providerExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            providerExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
