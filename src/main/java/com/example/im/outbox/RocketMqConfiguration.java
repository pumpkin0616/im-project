package com.example.im.outbox;

import com.example.im.config.ImProperties;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.apache.rocketmq.client.apis.consumer.FilterExpression;
import org.apache.rocketmq.client.apis.consumer.FilterExpressionType;
import org.apache.rocketmq.client.apis.consumer.PushConsumer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.Map;

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
                .setTopics(config.messageTopic(), config.membershipTopic())
                .setMaxAttempts(3)
                .build();
    }

    @Bean(destroyMethod = "close")
    PushConsumer realtimeDeliveryConsumer(ClientServiceProvider provider, ImProperties properties,
                                            com.example.im.delivery.RocketMqMessageListener listener) throws ClientException {
        ImProperties.RocketMq config = properties.rocketmq();
        ClientConfiguration clientConfiguration = ClientConfiguration.newBuilder()
                .setEndpoints(config.endpoints())
                .addClientProperty("consumerIdentity", config.consumerGroup())
                .build();
        return provider.newPushConsumerBuilder()
                .setClientConfiguration(clientConfiguration)
                .setConsumerGroup(config.consumerGroup())
                .setSubscriptionExpressions(Map.of(config.messageTopic(),
                        new FilterExpression("MESSAGE_CREATED", FilterExpressionType.TAG)))
                .setMessageListener(listener)
                .setConsumptionThreadCount(config.consumerThreads())
                .setMaxCacheMessageCount(4096)
                .setMaxCacheMessageSizeInBytes(128 * 1024 * 1024)
                .build();
    }

    @Bean(name = "outboxCompletionExecutor", destroyMethod = "close")
    ExecutorService outboxCompletionExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
