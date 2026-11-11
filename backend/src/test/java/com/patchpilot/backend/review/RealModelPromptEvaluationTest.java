package com.patchpilot.backend.review;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.core.io.DefaultResourceLoader;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@EnabledIfSystemProperty(named = "patchpilot.real-model-eval.enabled", matches = "true")
class RealModelPromptEvaluationTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	void evaluatesBothPromptsWhenCredentialsAreConfigured() throws Exception {
		String apiKey = System.getenv("PATCHPILOT_OPENAI_API_KEY");
		Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(),
				"Set PATCHPILOT_OPENAI_API_KEY to opt into external model evaluation.");

		String baseUrl = env("PATCHPILOT_OPENAI_BASE_URL", "https://api.openai.com/v1");
		String modelName = env("PATCHPILOT_OPENAI_MODEL", "gpt-4o-mini");
		double temperature = Double.parseDouble(env("PATCHPILOT_MODEL_TEMPERATURE", "0.0"));
		int maxTokens = Integer.parseInt(env("PATCHPILOT_MODEL_MAX_OUTPUT_TOKENS", "4000"));
		int connectTimeout = Integer.parseInt(env("PATCHPILOT_MODEL_CONNECT_TIMEOUT_MS", "3000"));
		int readTimeout = Integer.parseInt(env("PATCHPILOT_MODEL_READ_TIMEOUT_MS", "30000"));
		ReviewModelClient modelClient = new OpenAiCompatibleReviewModelClient(objectMapper,
				baseUrl, apiKey, modelName, temperature, maxTokens, connectTimeout, readTimeout);
		ReviewPromptCatalog prompts = new ReviewPromptCatalog(new DefaultResourceLoader());
		ReviewAnalysisValidator validator = new ReviewAnalysisValidator();
		JsonNode dataset;
		try (InputStream input = getClass().getResourceAsStream("/eval-fixtures/cases.json")) {
			assertThat(input).as("labeled prompt evaluation dataset").isNotNull();
			dataset = objectMapper.readTree(input);
		}

		StringBuilder report = new StringBuilder("# Real Model Prompt Evaluation\n\n")
				.append("- Date: ").append(LocalDate.now(ZoneOffset.UTC)).append('\n')
				.append("- Model: `").append(modelName).append("`\n")
				.append("- Parameters: temperature `").append(temperature).append("`, max output tokens `")
				.append(maxTokens).append("`; provider requests are external\n")
				.append("- Dataset: `eval/cases.json`, ").append(dataset.path("cases").size())
				.append(" synthetic labeled cases\n\n")
				.append("| Prompt | TP | FP | FN | Precision | Recall | F1 |\n")
				.append("| --- | ---: | ---: | ---: | ---: | ---: | ---: |\n");
		for (String version : new String[] { "v1", "v2" }) {
			Set<String> expected = new HashSet<>();
			Set<String> predicted = new HashSet<>();
			for (JsonNode testCase : dataset.path("cases")) {
				String caseId = testCase.path("id").asText();
				for (JsonNode finding : testCase.path("expected")) {
					expected.add(key(caseId, finding.path("ruleId").asText()));
				}
				ReviewModelRequest request = new ReviewModelRequest(caseId, null, null,
						testCase.path("diffText").asText(), version, prompts.get(version));
				ReviewAnalysis output = validator.validate(modelClient.analyze(request), request.diffText());
				for (ReviewFinding finding : output.findings()) {
					predicted.add(key(caseId, finding.ruleId()));
				}
			}
			Metrics metrics = metrics(expected, predicted);
			report.append("| ").append(version.toUpperCase()).append(" | ")
					.append(metrics.truePositives()).append(" | ").append(metrics.falsePositives()).append(" | ")
					.append(metrics.falseNegatives()).append(" | ")
					.append(String.format("%.2f", metrics.precision())).append(" | ")
					.append(String.format("%.2f", metrics.recall())).append(" | ")
					.append(String.format("%.2f", metrics.f1())).append(" |\n");
		}
		report.append("\nNo API key or raw model response is written to this report. Repeated runs may vary by provider.");
		Path reportPath = Path.of("target", "prompt-eval", "real-model-report.md");
		Files.createDirectories(reportPath.getParent());
		Files.writeString(reportPath, report);
		System.out.println("Real model evaluation report: " + reportPath.toAbsolutePath());
	}

	private String env(String name, String fallback) {
		String value = System.getenv(name);
		return value == null || value.isBlank() ? fallback : value;
	}

	private String key(String caseId, String ruleId) {
		return caseId + ":" + ruleId;
	}

	private Metrics metrics(Set<String> expected, Set<String> predicted) {
		int truePositives = (int) predicted.stream().filter(expected::contains).count();
		return new Metrics(truePositives, predicted.size() - truePositives, expected.size() - truePositives);
	}

	private record Metrics(int truePositives, int falsePositives, int falseNegatives) {
		double precision() {
			return truePositives + falsePositives == 0 ? 0.0
					: (double) truePositives / (truePositives + falsePositives);
		}

		double recall() {
			return truePositives + falseNegatives == 0 ? 0.0
					: (double) truePositives / (truePositives + falseNegatives);
		}

		double f1() {
			return precision() + recall() == 0 ? 0.0
					: 2 * precision() * recall() / (precision() + recall());
		}
	}
}
