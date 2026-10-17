package com.example.im.outbox;

import com.example.im.config.ImProperties;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "im.rocketmq", name = "enabled", havingValue = "true")
class RocketMqConfiguration {
    @Bean
    ClientServiceProvider rocketMqClientServiceProvider() {
        return ClientServiceProvider.loadService();
    }

    @Bean(destroyMethod = "close")
    Producer rocketMqProducer(ClientServiceProvider provider, ImProperties properties) throws ClientException {
        ImProperties.RocketMq config = properties.rocketmq();
        ClientConfiguration clientConfiguration = ClientConfiguration.newBuilder()
                .setEndpoints(config.endpoints())
                .addClientProperty("producerIdentity", config.producerIdentity())
                .build();
        return provider.newProducerBuilder()
                .setClientConfiguration(clientConfiguration)
                .setTopics(config.messageTopic())
                .setMaxAttempts(3)
                .build();
    }

    @Bean(name = "outboxCompletionExecutor", destroyMethod = "close")
    ExecutorService outboxCompletionExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
