package com.patchpilot.backend.review;

import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Value;

@Component
public class ReviewOutboxPublisher {

	private static final Logger LOGGER = LoggerFactory.getLogger(ReviewOutboxPublisher.class);
	private final OutboxEventRepository outboxEventRepository;
	private final RabbitTemplate rabbitTemplate;
	private final int batchSize;
	private final long confirmTimeoutMillis;

	public ReviewOutboxPublisher(OutboxEventRepository outboxEventRepository, RabbitTemplate rabbitTemplate,
			@Value("${patchpilot.outbox.batch-size:20}") int batchSize,
			@Value("${patchpilot.outbox.confirm-timeout-ms:3000}") long confirmTimeoutMillis) {
		this.outboxEventRepository = outboxEventRepository;
		this.rabbitTemplate = rabbitTemplate;
		this.batchSize = batchSize;
		this.confirmTimeoutMillis = confirmTimeoutMillis;
	}

	@Scheduled(fixedDelayString = "${patchpilot.outbox.poll-interval-ms:1000}")
	@Transactional
	public void publishPending() {
		var events = outboxEventRepository.findByPublishedAtIsNullOrderByCreatedAtAsc(PageRequest.of(0, batchSize));
		for (OutboxEventEntity event : events) {
			event.recordAttempt();
			try {
				publish(event);
				event.markPublished(java.time.Instant.now());
			}
			catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				LOGGER.warn("Outbox publish interrupted for eventId={} attempts={}", event.getId(), event.getAttempts());
				break;
			}
			catch (Exception exception) {
				LOGGER.warn("Outbox publish failed for eventId={} attempts={}", event.getId(), event.getAttempts());
			}
		}
	}

	private void publish(OutboxEventEntity event) throws Exception {
		CorrelationData correlation = new CorrelationData(event.getId().toString());
		rabbitTemplate.convertAndSend(ReviewMessagingConfiguration.REVIEW_EXCHANGE,
				ReviewMessagingConfiguration.REVIEW_ROUTING_KEY, event.getPayload(), message -> {
					MessageProperties properties = message.getMessageProperties();
					properties.setMessageId(event.getId().toString());
					properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
					properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
					return message;
				}, correlation);
		CorrelationData.Confirm confirm = correlation.getFuture().get(confirmTimeoutMillis, TimeUnit.MILLISECONDS);
		if (!confirm.ack() || correlation.getReturned() != null) {
			throw new IllegalStateException("RabbitMQ did not confirm the review event.");
		}
	}
}
