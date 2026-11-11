package com.patchpilot.backend.review;

import java.net.URI;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ReviewApiExceptionHandler {

	@ExceptionHandler(ReviewApiException.class)
	public ResponseEntity<ApiErrorResponse> handleReviewApiException(ReviewApiException exception,
			HttpServletRequest request) {
		return response(exception.getStatus(), exception.getCode(), exception.getMessage(), request);
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException exception,
			HttpServletRequest request) {
		return response(HttpStatus.BAD_REQUEST, "validation_failed", "Request validation failed.", request);
	}

	@ExceptionHandler(MissingRequestHeaderException.class)
	public ResponseEntity<ApiErrorResponse> handleMissingHeader(MissingRequestHeaderException exception,
			HttpServletRequest request) {
		String code = exception.getHeaderName().equalsIgnoreCase("Idempotency-Key")
				? "missing_idempotency_key" : "missing_request_header";
		String detail = code.equals("missing_idempotency_key")
				? "Idempotency-Key header is required." : "A required request header is missing.";
		return response(HttpStatus.BAD_REQUEST, code, detail, request);
	}

	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	public ResponseEntity<ApiErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException exception,
			HttpServletRequest request) {
		String parameterName = exception.getName();
		boolean idempotencyKey = parameterName.equalsIgnoreCase("idempotencyKey")
				|| parameterName.equalsIgnoreCase("Idempotency-Key");
		String code = idempotencyKey
				? "invalid_idempotency_key" : "invalid_request_parameter";
		String detail = code.equals("invalid_idempotency_key")
				? "Idempotency-Key must be a UUID." : "A request parameter has an invalid value.";
		return response(HttpStatus.BAD_REQUEST, code, detail, request);
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<ApiErrorResponse> handleUnreadableBody(HttpMessageNotReadableException exception,
			HttpServletRequest request) {
		return response(HttpStatus.BAD_REQUEST, "invalid_request_body", "Request body must be valid JSON.", request);
	}

	private ResponseEntity<ApiErrorResponse> response(HttpStatus status, String code, String detail,
			HttpServletRequest request) {
		Object requestId = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
		String traceId = requestId instanceof String value ? value : UUID.randomUUID().toString();
		ApiErrorResponse error = new ApiErrorResponse(URI.create("about:blank"), status.getReasonPhrase(),
				status.value(), detail, code, traceId);
		ResponseEntity.BodyBuilder response = ResponseEntity.status(status);
		if (status == HttpStatus.TOO_MANY_REQUESTS && request.getAttribute("rateLimitRetryAfter") instanceof Long retryAfter) {
			response.header("Retry-After", retryAfter.toString());
		}
		return response.body(error);
	}
}
