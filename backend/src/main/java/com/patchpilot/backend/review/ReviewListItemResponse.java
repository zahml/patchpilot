package com.patchpilot.backend.review;

import java.time.Instant;
import java.util.UUID;

public record ReviewListItemResponse(UUID id, String title, ReviewStatus status, Instant createdAt) {
}
