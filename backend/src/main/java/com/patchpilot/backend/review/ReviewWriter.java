package com.patchpilot.backend.review;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReviewWriter {

	private final ReviewRepository reviewRepository;
	private final OutboxEventRepository outboxEventRepository;

	public ReviewWriter(ReviewRepository reviewRepository, OutboxEventRepository outboxEventRepository) {
		this.reviewRepository = reviewRepository;
		this.outboxEventRepository = outboxEventRepository;
	}

	@Transactional
	public ReviewEntity create(CreateReviewRequest request, UUID idempotencyKey, String requestDigest) {
		Instant now = Instant.now();
		ReviewEntity review = new ReviewEntity(UUID.randomUUID(), request.title(), request.diffText(),
				request.baseRef(), request.headRef(), idempotencyKey.toString(), requestDigest, now);
		reviewRepository.saveAndFlush(review);
		outboxEventRepository.save(new OutboxEventEntity(UUID.randomUUID(), review.getId(),
				"ReviewRequested", "{\"reviewId\":\"" + review.getId() + "\"}", now));
		return review;
	}
}
