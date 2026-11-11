package com.patchpilot.backend.review;

import java.io.IOException;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(name = "patchpilot.messaging.enabled", havingValue = "true", matchIfMissing = true)
public class ReviewQueueListener {

	private final ObjectMapper objectMapper;
	private final ReviewTaskService reviewTaskService;

	public ReviewQueueListener(ObjectMapper objectMapper, ReviewTaskService reviewTaskService) {
		this.objectMapper = objectMapper;
		this.reviewTaskService = reviewTaskService;
	}

	@RabbitListener(id = "reviewQueueListener", queues = ReviewMessagingConfiguration.REVIEW_QUEUE,
			containerFactory = "rabbitListenerContainerFactory")
	public void receive(Message message) throws IOException {
		ReviewMessagePayload payload = objectMapper.readValue(message.getBody(), ReviewMessagePayload.class);
		if (payload.reviewId() == null) {
			throw new IllegalArgumentException("Review task message has no review ID.");
		}
		reviewTaskService.process(payload.reviewId(), message.getMessageProperties().isRedelivered());
	}
}
