package com.patchpilot.backend.review;

import java.time.Instant;
import java.util.UUID;

public record ReviewDetailResponse(UUID id, String title, ReviewStatus status, String summary,
		Instant createdAt, Instant startedAt, Instant completedAt, String promptVersion, String failureCode) {
}
