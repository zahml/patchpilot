package com.patchpilot.backend.review;

import java.net.URI;
import java.util.UUID;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/v1/reviews")
public class ReviewController {

	private final ReviewApplicationService reviewService;

	public ReviewController(ReviewApplicationService reviewService) {
		this.reviewService = reviewService;
	}

	@PostMapping
	public ResponseEntity<ReviewAcceptedResponse> createReview(
			@RequestHeader("Idempotency-Key") UUID idempotencyKey,
			@Valid @RequestBody CreateReviewRequest request) {
		ReviewAcceptedResponse response = reviewService.createReview(request, idempotencyKey);
		URI location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}")
				.buildAndExpand(response.id()).toUri();
		return ResponseEntity.accepted().location(location).body(response);
	}

	@GetMapping
	public CursorPageResponse<ReviewListItemResponse> listReviews(
			@RequestParam(required = false) String cursor,
			@RequestParam(defaultValue = "20") int limit) {
		return reviewService.listReviews(cursor, limit);
	}

	@GetMapping("/{reviewId}")
	public ReviewDetailResponse getReview(@PathVariable UUID reviewId) {
		return reviewService.getReview(reviewId);
	}

	@GetMapping("/{reviewId}/findings")
	public CursorPageResponse<ReviewFindingResponse> listFindings(
			@PathVariable UUID reviewId,
			@RequestParam(required = false) String cursor,
			@RequestParam(defaultValue = "20") int limit) {
		return reviewService.listFindings(reviewId, cursor, limit);
	}
}
