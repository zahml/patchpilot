package com.patchpilot.backend.review;

import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

final class ReviewCursor {

	private ReviewCursor() {
	}

	static String encode(Instant createdAt, UUID id) {
		String value = createdAt.toString() + "|" + id;
		return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
	}

	static Position decode(String cursor) {
		try {
			String value = new String(Base64.getUrlDecoder().decode(cursor), java.nio.charset.StandardCharsets.UTF_8);
			String[] parts = value.split("\\|", -1);
			if (parts.length != 2) {
				throw new IllegalArgumentException("Invalid cursor format");
			}
			return new Position(Instant.parse(parts[0]), UUID.fromString(parts[1]));
		}
		catch (IllegalArgumentException | java.time.DateTimeException exception) {
			throw ReviewApiException.badRequest("invalid_cursor", "cursor is invalid");
		}
	}

	record Position(Instant createdAt, UUID id) {
	}
}
