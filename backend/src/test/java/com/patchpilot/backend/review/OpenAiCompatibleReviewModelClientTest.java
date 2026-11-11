package com.patchpilot.backend.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.ObjectMapper;

class OpenAiCompatibleReviewModelClientTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	void sendsOpenAiCompatibleChatRequestAndParsesStructuredAnalysis() throws Exception {
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		RestClient restClient = builder.baseUrl("https://model.example/v1")
				.defaultHeader("Authorization", "Bearer test-token")
				.build();
		OpenAiCompatibleReviewModelClient client = new OpenAiCompatibleReviewModelClient(
				restClient, objectMapper, "test-model", 0.0, 1200);
		ReviewModelRequest reviewRequest = new ReviewModelRequest("Guard account names", "main", "feature",
				"diff --git a/Account.java b/Account.java\n--- a/Account.java\n+++ b/Account.java\n@@ -1 +1 @@\n-old\n+new",
				"v2", "Return JSON only.");
		String modelOutput = objectMapper.writeValueAsString(new ReviewAnalysis("One concrete issue.", List.of(
				new ReviewFinding(FindingSeverity.HIGH, "NULL_DEREFERENCE", "src/Account.java", 4, 4,
						"Possible null dereference", "The value can be null here.", "Check it before use."))));
		String completion = objectMapper.writeValueAsString(Map.of("choices", List.of(Map.of("message",
				Map.of("content", modelOutput)))));

		server.expect(requestTo("https://model.example/v1/chat/completions"))
				.andExpect(method(HttpMethod.POST))
				.andExpect(header("Authorization", "Bearer test-token"))
				.andExpect(jsonPath("$.model", is("test-model")))
				.andExpect(jsonPath("$.temperature", is(0.0)))
				.andExpect(jsonPath("$.response_format.type", is("json_object")))
				.andExpect(jsonPath("$.messages[0].content", is("Return JSON only.")))
				.andExpect(jsonPath("$.messages[1].content", containsString("Guard account names")))
				.andExpect(jsonPath("$.messages[1].content", containsString("diff --git")))
				.andRespond(withSuccess(completion, MediaType.APPLICATION_JSON));

		ReviewAnalysis result = client.analyze(reviewRequest);

		assertThat(result.summary()).isEqualTo("One concrete issue.");
		assertThat(result.findings()).hasSize(1);
		assertThat(result.findings().getFirst().ruleId()).isEqualTo("NULL_DEREFERENCE");
		server.verify();
	}

	@Test
	void hidesRawModelResponseWhenStructuredContentIsInvalid() throws Exception {
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		OpenAiCompatibleReviewModelClient client = new OpenAiCompatibleReviewModelClient(
				builder.baseUrl("https://model.example/v1").build(), objectMapper, "test-model", 0.0, 1200);
		String response = objectMapper.writeValueAsString(Map.of("choices", List.of(Map.of("message",
				Map.of("content", "PRIVATE_RAW_PROVIDER_RESPONSE")))));
		server.expect(requestTo("https://model.example/v1/chat/completions"))
				.andRespond(withSuccess(response, MediaType.APPLICATION_JSON));

		assertThatThrownBy(() -> client.analyze(new ReviewModelRequest("Title", null, null, "diff", "v2", "prompt")))
				.isInstanceOf(ReviewModelException.class)
				.hasMessage("Model request or response could not be processed.")
				.hasMessageNotContaining("PRIVATE_RAW_PROVIDER_RESPONSE");
		server.verify();
	}
}
