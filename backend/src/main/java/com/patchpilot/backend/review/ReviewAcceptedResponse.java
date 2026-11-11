package com.patchpilot.backend.review;

import java.time.Instant;
import java.util.UUID;

public record ReviewAcceptedResponse(UUID id, String title, ReviewStatus status, Instant createdAt) {
}
