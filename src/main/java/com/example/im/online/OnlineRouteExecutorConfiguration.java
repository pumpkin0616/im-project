package com.example.im.online;

import com.example.im.config.ImProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Configuration(proxyBeanMethods = false)
class OnlineRouteExecutorConfiguration {
    @Bean(name = "onlineRouteExecutor", destroyMethod = "shutdownNow")
    ExecutorService onlineRouteExecutor(ImProperties properties) {
        ImProperties.Online config = properties.online();
        return new ThreadPoolExecutor(config.routeThreads(), config.routeThreads(), 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(config.routeQueueCapacity()),
                Thread.ofPlatform().name("im-online-route-", 0).factory(),
                new ThreadPoolExecutor.DiscardPolicy());
    }
}
