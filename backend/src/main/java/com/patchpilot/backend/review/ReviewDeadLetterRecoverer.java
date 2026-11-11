package com.patchpilot.backend.review;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

@Component
public class ReviewDeadLetterRecoverer implements MessageRecoverer {

	private static final Logger LOGGER = LoggerFactory.getLogger(ReviewDeadLetterRecoverer.class);
	private final ObjectMapper objectMapper;
	private final ReviewTaskService reviewTaskService;

	public ReviewDeadLetterRecoverer(ObjectMapper objectMapper, ReviewTaskService reviewTaskService) {
		this.objectMapper = objectMapper;
		this.reviewTaskService = reviewTaskService;
	}

	@Override
	public void recover(Message message, Throwable cause) {
		try {
			ReviewMessagePayload payload = objectMapper.readValue(message.getBody(), ReviewMessagePayload.class);
			UUID reviewId = payload.reviewId();
			if (reviewId != null) {
				reviewTaskService.failIfUnfinished(reviewId, "processing_failed");
			}
		}
		catch (RuntimeException exception) {
			LOGGER.warn("Unable to persist failed review task after retry exhaustion");
		}
		throw new AmqpRejectAndDontRequeueException("Review task retry limit reached.", cause);
	}
}
