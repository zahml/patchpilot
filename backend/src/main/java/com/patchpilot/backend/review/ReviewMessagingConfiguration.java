package com.patchpilot.backend.review;

import org.aopalliance.aop.Advice;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.boot.amqp.autoconfigure.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "patchpilot.messaging.enabled", havingValue = "true", matchIfMissing = true)
@EnableRabbit
@EnableScheduling
public class ReviewMessagingConfiguration {

	public static final String REVIEW_EXCHANGE = "patchpilot.review.exchange";
	public static final String REVIEW_QUEUE = "patchpilot.review.queue";
	public static final String REVIEW_ROUTING_KEY = "review.requested";
	public static final String DEAD_LETTER_EXCHANGE = "patchpilot.review.dead.exchange";
	public static final String DEAD_LETTER_QUEUE = "patchpilot.review.dead";
	public static final String DEAD_LETTER_ROUTING_KEY = "review.dead";

	@Bean
	Declarables reviewQueueTopology() {
		DirectExchange exchange = new DirectExchange(REVIEW_EXCHANGE, true, false);
		DirectExchange deadLetterExchange = new DirectExchange(DEAD_LETTER_EXCHANGE, true, false);
		Queue reviewQueue = QueueBuilder.durable(REVIEW_QUEUE)
				.deadLetterExchange(DEAD_LETTER_EXCHANGE)
				.deadLetterRoutingKey(DEAD_LETTER_ROUTING_KEY)
				.build();
		Queue deadLetterQueue = QueueBuilder.durable(DEAD_LETTER_QUEUE).build();
		Binding reviewBinding = BindingBuilder.bind(reviewQueue).to(exchange).with(REVIEW_ROUTING_KEY);
		Binding deadLetterBinding = BindingBuilder.bind(deadLetterQueue).to(deadLetterExchange)
				.with(DEAD_LETTER_ROUTING_KEY);
		return new Declarables(exchange, deadLetterExchange, reviewQueue, deadLetterQueue,
				reviewBinding, deadLetterBinding);
	}

	@Bean
	Advice reviewListenerRetryAdvice(MessageRecoverer reviewDeadLetterRecoverer,
			@Value("${patchpilot.review.retry.max-attempts:3}") int maxAttempts,
			@Value("${patchpilot.review.retry.initial-backoff-ms:250}") long initialBackoff,
			@Value("${patchpilot.review.retry.max-backoff-ms:2000}") long maxBackoff) {
		return RetryInterceptorBuilder.stateless()
				.maxRetries(Math.max(0, maxAttempts - 1))
				.backOffOptions(initialBackoff, 2.0, maxBackoff)
				.recoverer(reviewDeadLetterRecoverer)
				.build();
	}

	@Bean(name = "rabbitListenerContainerFactory")
	SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
			SimpleRabbitListenerContainerFactoryConfigurer configurer,
			ConnectionFactory connectionFactory,
			Advice reviewListenerRetryAdvice,
			@Value("${patchpilot.review.listener.concurrency:2}") int concurrency,
			@Value("${patchpilot.review.listener.prefetch:1}") int prefetch) {
		SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
		configurer.configure(factory, connectionFactory);
		factory.setConcurrentConsumers(concurrency);
		factory.setMaxConcurrentConsumers(concurrency);
		factory.setPrefetchCount(prefetch);
		factory.setDefaultRequeueRejected(false);
		factory.setAdviceChain(reviewListenerRetryAdvice);
		return factory;
	}
}
