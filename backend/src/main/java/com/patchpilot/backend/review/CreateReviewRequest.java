package com.patchpilot.backend.review;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateReviewRequest(
		@NotBlank(message = "title is required") @Size(max = 160, message = "title must be at most 160 characters") String title,
		@NotBlank(message = "diffText is required") String diffText,
		@Size(max = 255, message = "baseRef must be at most 255 characters") String baseRef,
		@Size(max = 255, message = "headRef must be at most 255 characters") String headRef) {
}
