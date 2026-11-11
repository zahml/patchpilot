package com.patchpilot.backend.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.containers.wait.strategy.Wait;

import tools.jackson.databind.ObjectMapper;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ReviewWorkflowIT {

	private static final String REVIEW_QUEUE = "patchpilot.review.queue";
	private static final String DEAD_LETTER_QUEUE = "patchpilot.review.dead";
	private static final String REVIEW_EXCHANGE = "patchpilot.review.exchange";
	private static final String REVIEW_ROUTING_KEY = "review.requested";
	private static final String VALID_DIFF = "diff --git a/Account.java b/Account.java\n"
			+ "--- a/Account.java\n+++ b/Account.java\n@@ -1 +1 @@\n-old\n+new";

	@Container
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

	@Container
	static final GenericContainer<?> RABBITMQ = new GenericContainer<>(DockerImageName.parse("rabbitmq:4-management-alpine"))
			.withEnv("RABBITMQ_DEFAULT_USER", "patchpilot")
			.withEnv("RABBITMQ_DEFAULT_PASS", "patchpilot-test")
			.withExposedPorts(5672)
			.waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(2)));

	@Container
	static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:8-alpine"))
			.withExposedPorts(6379)
			.waitingFor(Wait.forListeningPort());

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private StringRedisTemplate redisTemplate;

	@Autowired
	private RabbitTemplate rabbitTemplate;

	@Autowired
	private RabbitAdmin rabbitAdmin;

	@MockitoSpyBean
	private ReviewModelClient reviewModelClient;

	@DynamicPropertySource
	static void configureServices(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
		registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
		registry.add("spring.rabbitmq.host", RABBITMQ::getHost);
		registry.add("spring.rabbitmq.port", () -> RABBITMQ.getMappedPort(5672));
		registry.add("spring.rabbitmq.username", () -> "patchpilot");
		registry.add("spring.rabbitmq.password", () -> "patchpilot-test");
		registry.add("spring.rabbitmq.publisher-confirm-type", () -> "correlated");
		registry.add("spring.rabbitmq.publisher-returns", () -> "true");
		registry.add("spring.data.redis.host", REDIS::getHost);
		registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
		registry.add("patchpilot.messaging.enabled", () -> "true");
		registry.add("patchpilot.rate-limit.enabled", () -> "true");
		registry.add("patchpilot.rate-limit.requests", () -> "5");
		registry.add("patchpilot.rate-limit.window-ms", () -> "60000");
		registry.add("patchpilot.outbox.poll-interval-ms", () -> "200");
		registry.add("patchpilot.review.listener.concurrency", () -> "2");
		registry.add("patchpilot.review.listener.prefetch", () -> "1");
		registry.add("patchpilot.review.retry.max-attempts", () -> "3");
		registry.add("patchpilot.review.retry.initial-backoff-ms", () -> "50");
		registry.add("patchpilot.review.retry.max-backoff-ms", () -> "100");
	}

	@BeforeEach
	void clearRateLimitCounters() {
		redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
	}

	@Test
	void preservesRequestIdInResponseAndProblemDetails() throws Exception {
		String requestId = "review-trace-123";
		mockMvc.perform(get("/api/v1/reviews/{reviewId}", UUID.randomUUID())
				.header("X-Request-Id", requestId))
			.andExpect(status().isNotFound())
			.andExpect(header().string("X-Request-Id", requestId))
			.andExpect(jsonPath("$.traceId").value(requestId));
	}

	@Test
	void generatesRequestIdWhenClientOmitsOne() throws Exception {
		var result = mockMvc.perform(get("/api/v1/reviews/{reviewId}", UUID.randomUUID()))
				.andExpect(status().isNotFound())
				.andExpect(header().exists("X-Request-Id"))
				.andReturn();
		String requestId = result.getResponse().getHeader("X-Request-Id");
		assertThat(objectMapper.readTree(result.getResponse().getContentAsByteArray()).path("traceId").asText())
				.isEqualTo(requestId);
	}

	@Test
	void rejectsRequestsOverRedisWindowLimitWithoutCreatingReviews() throws Exception {
		int before = jdbcTemplate.queryForObject("select count(*) from reviews", Integer.class);
		for (int i = 0; i < 5; i++) {
			submit(UUID.randomUUID(), "Within rate limit");
		}
		mockMvc.perform(get("/api/v1/reviews"))
				.andExpect(status().isOk());

		submitRequest(UUID.randomUUID(), "Over rate limit")
				.andExpect(status().isTooManyRequests())
				.andExpect(header().exists("Retry-After"))
				.andExpect(header().exists("X-Request-Id"))
				.andExpect(jsonPath("$.code").value("rate_limit_exceeded"))
				.andExpect(jsonPath("$.traceId").isNotEmpty());
		assertThat(jdbcTemplate.queryForObject("select count(*) from reviews", Integer.class)).isEqualTo(before + 5);
	}

	@Test
	void retriesOutboxDeliveryAfterRabbitMqRecovers() throws Exception {
		RABBITMQ.execInContainer("rabbitmqctl", "stop_app");
		UUID reviewId;
		try {
			reviewId = submit(UUID.randomUUID(), "Accepted while broker is down");
			waitFor(() -> jdbcTemplate.queryForObject(
					"select attempts > 0 from outbox_events where aggregate_id = ?", Boolean.class, reviewId));
		}
		finally {
			RABBITMQ.execInContainer("rabbitmqctl", "start_app");
		}
		waitFor(() -> Boolean.TRUE.equals(jdbcTemplate.queryForObject(
				"select published_at is not null from outbox_events where aggregate_id = ?", Boolean.class, reviewId)));
		waitFor(() -> "COMPLETED".equals(jdbcTemplate.queryForObject(
				"select status from reviews where id = ?", String.class, reviewId)));
	}

	@Test
	void duplicateDeliveryDoesNotClaimOrMutateReviewTwice() throws Exception {
		UUID reviewId = submit(UUID.randomUUID(), "Duplicate delivery");
		waitFor(() -> "COMPLETED".equals(jdbcTemplate.queryForObject(
				"select status from reviews where id = ?", String.class, reviewId)));
		var before = jdbcTemplate.queryForObject("select completed_at from reviews where id = ?", Object.class, reviewId);
		var findingsBefore = jdbcTemplate.queryForObject(
				"select count(*) from review_findings where review_id = ?", Integer.class, reviewId);
		rabbitTemplate.convertAndSend(REVIEW_EXCHANGE, REVIEW_ROUTING_KEY,
				"{\"reviewId\":\"" + reviewId + "\"}");
		waitFor(() -> rabbitAdmin.getQueueInfo(REVIEW_QUEUE).getMessageCount() == 0);
		var after = jdbcTemplate.queryForObject("select completed_at from reviews where id = ?", Object.class, reviewId);
		assertThat(after).isEqualTo(before);
		assertThat(jdbcTemplate.queryForObject(
				"select count(*) from review_findings where review_id = ?", Integer.class, reviewId)).isEqualTo(findingsBefore);
		verify(reviewModelClient, times(1)).analyze(argThat(request -> "Duplicate delivery".equals(request.title())));
	}

	@Test
	void listenerUsesConfiguredConcurrencyAndPrefetchBounds() throws Exception {
		var queueInfo = rabbitAdmin.getQueueInfo(REVIEW_QUEUE);
		assertThat(queueInfo.getConsumerCount()).isEqualTo(2);
		String consumers = RABBITMQ.execInContainer("rabbitmqctl", "-q", "list_consumers",
				"queue_name", "prefetch_count").getStdout();
		var listenerConsumers = consumers.lines()
				.map(String::trim)
				.filter(line -> line.startsWith(REVIEW_QUEUE + " ") || line.startsWith(REVIEW_QUEUE + "\t"))
				.toList();
		assertThat(listenerConsumers).hasSize(2);
		assertThat(listenerConsumers).allSatisfy(line -> assertThat(line.split("\\s+")[1]).isEqualTo("1"));
	}

	@Test
	void sendsMessagesWithExhaustedRetriesToDeadLetterQueue() {
		long deadLettersBefore = rabbitAdmin.getQueueInfo(DEAD_LETTER_QUEUE).getMessageCount();
		String missingReview = UUID.randomUUID().toString();
		rabbitTemplate.convertAndSend(REVIEW_EXCHANGE, REVIEW_ROUTING_KEY,
				"{\"reviewId\":\"" + missingReview + "\"}");
		waitFor(() -> rabbitAdmin.getQueueInfo(DEAD_LETTER_QUEUE).getMessageCount() == deadLettersBefore + 1);
	}

	@Test
	void marksReviewFailedWithSafeCodeAfterRetriesAreExhausted() throws Exception {
		long deadLettersBefore = rabbitAdmin.getQueueInfo(DEAD_LETTER_QUEUE).getMessageCount();
		doThrow(new IllegalStateException("sensitive processing detail"))
				.when(reviewModelClient).analyze(argThat(request -> "Fails processing".equals(request.title())));
		UUID reviewId = submit(UUID.randomUUID(), "Fails processing");

		waitFor(() -> "FAILED".equals(jdbcTemplate.queryForObject(
				"select status from reviews where id = ?", String.class, reviewId)));
		waitFor(() -> rabbitAdmin.getQueueInfo(DEAD_LETTER_QUEUE).getMessageCount() == deadLettersBefore + 1);
		verify(reviewModelClient, times(3)).analyze(argThat(request -> "Fails processing".equals(request.title())));
		assertThat(jdbcTemplate.queryForObject(
				"select failure_code from reviews where id = ?", String.class, reviewId))
				.isEqualTo("processing_failed");
	}

	private UUID submit(UUID key, String title) throws Exception {
		var result = submitRequest(key, title)
				.andExpect(status().isAccepted())
				.andReturn();
		return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsByteArray()).path("id").asText());
	}

	private org.springframework.test.web.servlet.ResultActions submitRequest(UUID key, String title) throws Exception {
		String body = objectMapper.writeValueAsString(new CreateReviewRequest(title, VALID_DIFF, null, null));
		return mockMvc.perform(post("/api/v1/reviews")
				.header("Idempotency-Key", key.toString())
				.contentType("application/json")
				.content(body));
	}

	private void waitFor(BooleanSupplier condition) {
		long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
		while (System.nanoTime() < deadline) {
			if (condition.getAsBoolean()) {
				return;
			}
			try {
				Thread.sleep(50);
			}
			catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new AssertionError("Interrupted while waiting for asynchronous work", exception);
			}
		}
		assertThat(condition.getAsBoolean()).isTrue();
	}
}
