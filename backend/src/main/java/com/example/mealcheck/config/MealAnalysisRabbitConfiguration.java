package com.example.mealcheck.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "mealcheck.analysis.dispatch", havingValue = "rabbitmq", matchIfMissing = true)
public class MealAnalysisRabbitConfiguration {
    public static final String EXCHANGE = "mealcheck.analysis";
    public static final String QUEUE = "mealcheck.analysis.jobs";
    public static final String ROUTING_KEY = "analyze";
    public static final String DEAD_EXCHANGE = "mealcheck.analysis.dead";
    public static final String DEAD_QUEUE = "mealcheck.analysis.dead.jobs";

    @Bean
    public Declarables mealAnalysisTopology() {
        var exchange = new DirectExchange(EXCHANGE, true, false);
        var deadExchange = new DirectExchange(DEAD_EXCHANGE, true, false);
        var queue = QueueBuilder.durable(QUEUE).quorum()
                .deadLetterExchange(DEAD_EXCHANGE).deadLetterRoutingKey(ROUTING_KEY).build();
        var deadQueue = QueueBuilder.durable(DEAD_QUEUE).quorum().build();
        return new Declarables(exchange, deadExchange, queue, deadQueue,
                BindingBuilder.bind(queue).to(exchange).with(ROUTING_KEY),
                BindingBuilder.bind(deadQueue).to(deadExchange).with(ROUTING_KEY));
    }

    @Bean
    public SimpleRabbitListenerContainerFactory mealAnalysisListenerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer, ConnectionFactory connectionFactory) {
        var factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        // Spring AUTO acknowledges only after the listener returns (broker autoAck is false).
        factory.setAcknowledgeMode(AcknowledgeMode.AUTO);
        factory.setPrefetchCount(1);
        factory.setDefaultRequeueRejected(false);
        // Terminal model/validation failures must reach the DLQ immediately, not be retried
        // into a no-op ACK after their database state has already changed to FAILED.
        factory.setAdviceChain(RetryInterceptorBuilder.stateless()
                .retryPolicy(new SimpleRetryPolicy(3,
                        java.util.Map.of(AmqpRejectAndDontRequeueException.class, false), true, true))
                .backOffOptions(1000, 2, 5000)
                .recoverer(new RejectAndDontRequeueRecoverer()).build());
        return factory;
    }

    @Bean
    public SimpleRabbitListenerContainerFactory deadLetterArchiveListenerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer, ConnectionFactory connectionFactory) {
        var factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setAcknowledgeMode(AcknowledgeMode.AUTO);
        factory.setPrefetchCount(1);
        factory.setDefaultRequeueRejected(true);
        factory.setAdviceChain(RetryInterceptorBuilder.stateless().maxAttempts(3)
                .backOffOptions(1000,2,5000).recoverer((message,cause) -> {
                    throw new org.springframework.amqp.ImmediateRequeueAmqpException("Dead-letter archive unavailable");
                }).build());
        return factory;
    }
}
