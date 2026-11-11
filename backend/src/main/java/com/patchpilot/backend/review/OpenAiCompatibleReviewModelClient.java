package com.patchpilot.backend.review;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(name = "patchpilot.model.provider", havingValue = "openai")
public class OpenAiCompatibleReviewModelClient implements ReviewModelClient {

	private final RestClient restClient;
	private final ObjectMapper objectMapper;
	private final String model;
	private final double temperature;
	private final int maxOutputTokens;

	@Autowired
	public OpenAiCompatibleReviewModelClient(ObjectMapper objectMapper,
			@Value("${patchpilot.model.base-url}") String baseUrl,
			@Value("${patchpilot.model.api-key}") String apiKey,
			@Value("${patchpilot.model.name}") String model,
			@Value("${patchpilot.model.temperature:0.0}") double temperature,
			@Value("${patchpilot.model.max-output-tokens:4000}") int maxOutputTokens,
			@Value("${patchpilot.model.connect-timeout-ms:3000}") int connectTimeoutMillis,
			@Value("${patchpilot.model.read-timeout-ms:30000}") int readTimeoutMillis) {
		this(buildRestClient(baseUrl, apiKey, connectTimeoutMillis, readTimeoutMillis),
				objectMapper, model, temperature, maxOutputTokens);
	}

	OpenAiCompatibleReviewModelClient(RestClient restClient, ObjectMapper objectMapper, String model,
			double temperature, int maxOutputTokens) {
		this.restClient = restClient;
		this.objectMapper = objectMapper;
		this.model = model;
		this.temperature = temperature;
		this.maxOutputTokens = maxOutputTokens;
	}

	private static RestClient buildRestClient(String baseUrl, String apiKey,
			int connectTimeoutMillis, int readTimeoutMillis) {
		if (apiKey == null || apiKey.isBlank()) {
			throw new IllegalStateException("PATCHPILOT_OPENAI_API_KEY is required when the openai provider is enabled.");
		}
		SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
		requestFactory.setConnectTimeout(connectTimeoutMillis);
		requestFactory.setReadTimeout(readTimeoutMillis);
		return RestClient.builder()
				.baseUrl(baseUrl)
				.requestFactory(requestFactory)
				.defaultHeader("Authorization", "Bearer " + apiKey)
				.build();
	}

	@Override
	public ReviewAnalysis analyze(ReviewModelRequest request) {
		try {
			String completion = restClient.post()
					.uri("/chat/completions")
					.contentType(MediaType.APPLICATION_JSON)
					.body(requestBody(request))
					.retrieve()
					.body(String.class);
			if (completion == null) {
				throw new ReviewModelException("Model provider returned an empty response.");
			}
			JsonNode root = objectMapper.readTree(completion);
			JsonNode content = root.at("/choices/0/message/content");
			if (!content.isString() || content.asString().isBlank()) {
				throw new ReviewModelException("Model provider returned no structured review content.");
			}
			return objectMapper.readValue(content.asString(), ReviewAnalysis.class);
		}
		catch (ReviewModelException exception) {
			throw exception;
		}
		catch (RestClientException | JacksonException exception) {
			throw new ReviewModelException("Model request or response could not be processed.");
		}
	}

	private Map<String, Object> requestBody(ReviewModelRequest request) {
		return Map.of(
				"model", model,
				"temperature", temperature,
				"max_tokens", maxOutputTokens,
				"response_format", Map.of("type", "json_object"),
				"messages", List.of(
						Map.of("role", "system", "content", request.systemPrompt()),
						Map.of("role", "user", "content", userContent(request))));
	}

	private String userContent(ReviewModelRequest request) {
		return "Review title: " + request.title() + "\n"
				+ "Base ref: " + (request.baseRef() == null ? "(not supplied)" : request.baseRef()) + "\n"
				+ "Head ref: " + (request.headRef() == null ? "(not supplied)" : request.headRef()) + "\n"
				+ "Prompt version: " + request.promptVersion() + "\n\n"
				+ "Unified diff:\n" + request.diffText();
	}
}
