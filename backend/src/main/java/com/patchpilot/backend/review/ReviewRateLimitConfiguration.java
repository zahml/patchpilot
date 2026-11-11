package com.patchpilot.backend.review;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "patchpilot.rate-limit.enabled", havingValue = "true", matchIfMissing = true)
public class ReviewRateLimitConfiguration implements WebMvcConfigurer {

	private final ReviewRateLimitInterceptor rateLimitInterceptor;

	public ReviewRateLimitConfiguration(ReviewRateLimitInterceptor rateLimitInterceptor) {
		this.rateLimitInterceptor = rateLimitInterceptor;
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(rateLimitInterceptor).addPathPatterns("/api/v1/reviews");
	}
}
