package com.patchpilot.backend.review;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
class ReviewControllerTest {

	private static final String VALID_DIFF = "diff --git a/Account.java b/Account.java\n"
			+ "--- a/Account.java\n+++ b/Account.java\n@@ -1 +1 @@\n-old\n+new";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private ReviewFindingRepository findingRepository;

	@Autowired
	private ReviewTaskService reviewTaskService;

	@MockitoSpyBean
	private ReviewModelClient reviewModelClient;

	@BeforeEach
	void clearReviewData() {
		jdbcTemplate.update("delete from outbox_events");
		jdbcTemplate.update("delete from review_findings");
		jdbcTemplate.update("delete from reviews");
	}

	@Test
	void acceptsValidDiffAndWritesReviewAndOutboxAtomically() throws Exception {
		UUID key = UUID.randomUUID();
		MvcResult result = submit(key, "Guard empty account names", VALID_DIFF)
				.andExpect(status().isAccepted())
				.andExpect(header().exists("Location"))
				.andExpect(jsonPath("$.id").isNotEmpty())
				.andExpect(jsonPath("$.title").value("Guard empty account names"))
				.andExpect(jsonPath("$.status").value("QUEUED"))
				.andExpect(jsonPath("$.diffText").doesNotExist())
				.andReturn();

		UUID reviewId = UUID.fromString(read(result).path("id").asText());
		org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
				"select count(*) from reviews where id = ?", Integer.class, reviewId)).isEqualTo(1);
		org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
				"select count(*) from outbox_events where aggregate_id = ?", Integer.class, reviewId)).isEqualTo(1);
	}

	@Test
	void replaysSameRequestAndRejectsDifferentContentForSameKey() throws Exception {
		UUID key = UUID.randomUUID();
		String firstId = read(submit(key, "First title", VALID_DIFF)
				.andExpect(status().isAccepted()).andReturn()).path("id").asText();

		submit(key, "First title", VALID_DIFF)
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.id").value(firstId));

		submit(key, "Different title", VALID_DIFF)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("idempotency_key_conflict"))
				.andExpect(jsonPath("$.traceId").isNotEmpty());

		org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject("select count(*) from reviews", Integer.class))
				.isEqualTo(1);
		org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject("select count(*) from outbox_events", Integer.class))
				.isEqualTo(1);
	}

	@Test
	void requiresAUuidIdempotencyKey() throws Exception {
		String body = body("Title", VALID_DIFF);
		mockMvc.perform(post("/api/v1/reviews").contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("missing_idempotency_key"))
				.andExpect(jsonPath("$.traceId").isNotEmpty());

		mockMvc.perform(post("/api/v1/reviews").header("Idempotency-Key", "not-a-uuid")
				.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("invalid_idempotency_key"));
	}

	@Test
	void validatesDiffAndRejectsOversizedPayloads() throws Exception {
		submit(UUID.randomUUID(), "Title", "not a diff")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("invalid_diff"));

		submit(UUID.randomUUID(), "Title", VALID_DIFF + "+".repeat(200 * 1024))
				.andExpect(status().isPayloadTooLarge())
				.andExpect(jsonPath("$.code").value("diff_too_large"));
		org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject("select count(*) from reviews", Integer.class))
				.isZero();
	}

	@Test
	void listsReviewsWithStableCursorAndDoesNotExposeDiff() throws Exception {
		for (int index = 0; index < 3; index++) {
			submit(UUID.randomUUID(), "Review " + index, VALID_DIFF).andExpect(status().isAccepted());
		}

		MvcResult firstPage = mockMvc.perform(get("/api/v1/reviews").param("limit", "2"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(2))
				.andExpect(jsonPath("$.nextCursor").isNotEmpty())
				.andExpect(jsonPath("$.items[0].diffText").doesNotExist())
				.andReturn();
		String nextCursor = read(firstPage).path("nextCursor").asText();

		mockMvc.perform(get("/api/v1/reviews").param("limit", "2").param("cursor", nextCursor))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.nextCursor").value(org.hamcrest.Matchers.nullValue()));
	}

	@Test
	void returnsReviewDetailsAndPaginatedFindings() throws Exception {
		UUID reviewId = UUID.fromString(read(submit(UUID.randomUUID(), "Review", VALID_DIFF)
				.andExpect(status().isAccepted()).andReturn()).path("id").asText());
		findingRepository.save(new ReviewFindingEntity(UUID.randomUUID(), reviewId, FindingSeverity.HIGH,
				"NULL_DEREFERENCE", "src/Account.java", 12, 13, "Possible null dereference",
				"The account may be null on this path.", "Check the value before use.", Instant.now()));

		mockMvc.perform(get("/api/v1/reviews/{reviewId}", reviewId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("QUEUED"))
				.andExpect(jsonPath("$.diffText").doesNotExist());

		mockMvc.perform(get("/api/v1/reviews/{reviewId}/findings", reviewId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].severity").value("HIGH"))
				.andExpect(jsonPath("$.items[0].lineStart").value(12));
}

	@Test
	void rejectsUnknownReviewsInvalidCursorAndInvalidPageSize() throws Exception {
		UUID missingId = UUID.randomUUID();
		mockMvc.perform(get("/api/v1/reviews/{reviewId}", missingId))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("review_not_found"));
		mockMvc.perform(get("/api/v1/reviews/{reviewId}/findings", missingId))
				.andExpect(status().isNotFound());
		mockMvc.perform(get("/api/v1/reviews").param("cursor", "bad-cursor"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("invalid_cursor"));
		mockMvc.perform(get("/api/v1/reviews").param("limit", "101"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("invalid_limit"));
	}

	@Test
	void persistsValidatedModelAnalysisAndDoesNotProcessCompletedDuplicates() throws Exception {
		UUID reviewId = createReview("Persist model output");
		ReviewAnalysis analysis = new ReviewAnalysis("A nullable value is dereferenced.", java.util.List.of(
				new ReviewFinding(FindingSeverity.HIGH, "NULL_DEREFERENCE", "Account.java", 1, 1,
						"Possible null dereference", "The value can be null on this path.", "Check it before use.")));
		doReturn(analysis).when(reviewModelClient).analyze(any(ReviewModelRequest.class));

		reviewTaskService.process(reviewId, false);

		org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
				"select status from reviews where id = ?", String.class, reviewId)).isEqualTo("COMPLETED");
		org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
				"select summary from reviews where id = ?", String.class, reviewId))
				.isEqualTo("A nullable value is dereferenced.");
		org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
				"select prompt_version from reviews where id = ?", String.class, reviewId)).isEqualTo("v2");
		org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
				"select count(*) from review_findings where review_id = ?", Integer.class, reviewId)).isEqualTo(1);

		reviewTaskService.process(reviewId, false);

		org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
				"select count(*) from review_findings where review_id = ?", Integer.class, reviewId)).isEqualTo(1);
		verify(reviewModelClient, times(1)).analyze(any(ReviewModelRequest.class));
	}

	@Test
	void rejectsInvalidModelFindingsAndReleasesReviewForRetry() throws Exception {
		UUID reviewId = createReview("Reject unsafe finding");
		doReturn(new ReviewAnalysis("Invalid finding.", java.util.List.of(
				new ReviewFinding(FindingSeverity.HIGH, "PATH_TRAVERSAL", "../secrets.txt", 1, 1,
						"Invalid path", "Invalid path from model output.", null))))
				.when(reviewModelClient).analyze(any(ReviewModelRequest.class));

		assertThatThrownBy(() -> reviewTaskService.process(reviewId, false))
				.isInstanceOf(ReviewModelException.class)
				.hasMessage("Model response failed validation.");
		org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
				"select status from reviews where id = ?", String.class, reviewId)).isEqualTo("QUEUED");
		org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
				"select count(*) from review_findings where review_id = ?", Integer.class, reviewId)).isZero();
	}

	@Test
	void rejectsFindingsOutsideAddedDiffLines() throws Exception {
		UUID reviewId = createReview("Reject invented line");
		doReturn(new ReviewAnalysis("Invalid line.", java.util.List.of(
				new ReviewFinding(FindingSeverity.HIGH, "NULL_DEREFERENCE", "Account.java", 8, 8,
						"Invented line", "The reported line is not part of this diff.", null))))
				.when(reviewModelClient).analyze(any(ReviewModelRequest.class));

		assertThatThrownBy(() -> reviewTaskService.process(reviewId, false))
				.isInstanceOf(ReviewModelException.class)
				.hasMessage("Model response failed validation.");
		org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
				"select status from reviews where id = ?", String.class, reviewId)).isEqualTo("QUEUED");
		org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
				"select count(*) from review_findings where review_id = ?", Integer.class, reviewId)).isZero();
	}

	private UUID createReview(String title) throws Exception {
		MvcResult result = submit(UUID.randomUUID(), title, VALID_DIFF)
				.andExpect(status().isAccepted())
				.andReturn();
		return UUID.fromString(read(result).path("id").asText());
	}

	private org.springframework.test.web.servlet.ResultActions submit(UUID key, String title, String diff)
			throws Exception {
		return mockMvc.perform(post("/api/v1/reviews")
				.header("Idempotency-Key", key.toString())
				.contentType(MediaType.APPLICATION_JSON)
				.content(body(title, diff)));
	}

	private String body(String title, String diff) throws Exception {
		return objectMapper.writeValueAsString(new CreateReviewRequest(title, diff, null, null));
	}

	private JsonNode read(MvcResult result) throws Exception {
		return objectMapper.readTree(result.getResponse().getContentAsByteArray());
	}
}
