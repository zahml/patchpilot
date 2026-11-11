package com.patchpilot.backend.review;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class ReviewRateLimitInterceptor implements HandlerInterceptor {

	private static final String RATE_LIMIT_KEY_PREFIX = "patchpilot:review-submit:";
	private static final RedisScript<List> RATE_LIMIT_SCRIPT = new DefaultRedisScript<>("""
			local count = redis.call('INCR', KEYS[1])
			if count == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end
			return {count, redis.call('PTTL', KEYS[1])}
			""", List.class);

	private final StringRedisTemplate redisTemplate;
	private final int requestLimit;
	private final long windowMillis;

	public ReviewRateLimitInterceptor(StringRedisTemplate redisTemplate,
			@Value("${patchpilot.rate-limit.requests:10}") int requestLimit,
			@Value("${patchpilot.rate-limit.window-ms:60000}") long windowMillis) {
		this.redisTemplate = redisTemplate;
		this.requestLimit = requestLimit;
		this.windowMillis = windowMillis;
	}

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		if (!"POST".equalsIgnoreCase(request.getMethod())) {
			return true;
		}
		try {
			List<?> result = redisTemplate.execute(RATE_LIMIT_SCRIPT, List.of(keyFor(request.getRemoteAddr())),
					Long.toString(windowMillis));
			if (result == null || result.size() != 2) {
				throw ReviewApiException.serviceUnavailable("rate_limiter_unavailable",
						"Rate limiter is temporarily unavailable.");
			}
			long count = ((Number) result.get(0)).longValue();
			long ttl = Math.max(1, ((Number) result.get(1)).longValue());
			if (count > requestLimit) {
				long retryAfter = Math.max(1, (ttl + 999) / 1000);
				request.setAttribute("rateLimitRetryAfter", retryAfter);
				throw ReviewApiException.rateLimited(retryAfter);
			}
			return true;
		}
		catch (ReviewApiException exception) {
			throw exception;
		}
		catch (DataAccessException exception) {
			throw ReviewApiException.serviceUnavailable("rate_limiter_unavailable",
					"Rate limiter is temporarily unavailable.");
		}
	}

	private String keyFor(String remoteAddress) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
					.digest(String.valueOf(remoteAddress).getBytes(StandardCharsets.UTF_8));
			return RATE_LIMIT_KEY_PREFIX + HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is not available", exception);
		}
	}
}
