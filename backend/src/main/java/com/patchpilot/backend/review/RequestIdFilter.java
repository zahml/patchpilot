package com.patchpilot.backend.review;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

	public static final String HEADER = "X-Request-Id";
	public static final String REQUEST_ID_ATTRIBUTE = RequestIdFilter.class.getName() + ".requestId";
	private static final Pattern SAFE_REQUEST_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		String supplied = request.getHeader(HEADER);
		String requestId = supplied != null && SAFE_REQUEST_ID.matcher(supplied).matches()
				? supplied : UUID.randomUUID().toString();
		request.setAttribute(REQUEST_ID_ATTRIBUTE, requestId);
		response.setHeader(HEADER, requestId);
		MDC.put("requestId", requestId);
		try {
			filterChain.doFilter(request, response);
		}
		finally {
			MDC.remove("requestId");
		}
	}
}
