package com.patchpilot.backend.review;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class ReviewApiIT {

	private static final String VALID_DIFF = "diff --git a/Account.java b/Account.java\n"
			+ "--- a/Account.java\n+++ b/Account.java\n@@ -1 +1 @@\n-old\n+new";

	@Container
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@DynamicPropertySource
	static void configurePostgres(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
		registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
	}

	@BeforeEach
	void clearReviewData() {
		jdbcTemplate.update("delete from outbox_events");
		jdbcTemplate.update("delete from review_findings");
		jdbcTemplate.update("delete from reviews");
	}

	@Test
	void persistsAcceptedReviewsAndTransactionalOutboxOnPostgres() throws Exception {
		UUID key = UUID.randomUUID();
		String body = """
			{"title":"Postgres review","diffText":"diff --git a/Account.java b/Account.java\\n--- a/Account.java\\n+++ b/Account.java\\n@@ -1 +1 @@\\n-old\\n+new"}
			""";
		String firstId = create(key, body);
		org.assertj.core.api.Assertions.assertThat(create(key, body)).isEqualTo(firstId);
		org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject("select count(*) from reviews", Integer.class))
				.isEqualTo(1);
		org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject("select count(*) from outbox_events", Integer.class))
				.isEqualTo(1);

		mockMvc.perform(get("/api/v1/reviews").param("limit", "1"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].id").value(firstId));
}

	private String create(UUID key, String body) throws Exception {
		var result = mockMvc.perform(post("/api/v1/reviews")
				.header("Idempotency-Key", key.toString())
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
			.andExpect(status().isAccepted())
			.andExpect(jsonPath("$.status").value("QUEUED"))
			.andReturn();
		return new tools.jackson.databind.ObjectMapper()
				.readTree(result.getResponse().getContentAsByteArray()).path("id").asText();
	}
}
