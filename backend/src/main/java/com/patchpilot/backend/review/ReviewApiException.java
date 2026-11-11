package com.patchpilot.backend.review;

import org.springframework.http.HttpStatus;

public class ReviewApiException extends RuntimeException {

	private final HttpStatus status;
	private final String code;
	private final Long retryAfterSeconds;

	private ReviewApiException(HttpStatus status, String code, String detail) {
		this(status, code, detail, null);
	}

	private ReviewApiException(HttpStatus status, String code, String detail, Long retryAfterSeconds) {
		super(detail);
		this.status = status;
		this.code = code;
		this.retryAfterSeconds = retryAfterSeconds;
	}

	public HttpStatus getStatus() {
		return status;
	}

	public String getCode() {
		return code;
	}

	public Long getRetryAfterSeconds() {
		return retryAfterSeconds;
	}

	public static ReviewApiException badRequest(String code, String detail) {
		return new ReviewApiException(HttpStatus.BAD_REQUEST, code, detail);
	}

	public static ReviewApiException conflict(String code, String detail) {
		return new ReviewApiException(HttpStatus.CONFLICT, code, detail);
	}

	public static ReviewApiException notFound(String code, String detail) {
		return new ReviewApiException(HttpStatus.NOT_FOUND, code, detail);
	}

	public static ReviewApiException tooLarge(String detail) {
		return new ReviewApiException(HttpStatus.PAYLOAD_TOO_LARGE, "diff_too_large", detail);
	}

	public static ReviewApiException rateLimited(long retryAfterSeconds) {
		return new ReviewApiException(HttpStatus.TOO_MANY_REQUESTS, "rate_limit_exceeded",
				"Review submission rate limit exceeded.", retryAfterSeconds);
	}

	public static ReviewApiException serviceUnavailable(String code, String detail) {
		return new ReviewApiException(HttpStatus.SERVICE_UNAVAILABLE, code, detail);
	}
}
