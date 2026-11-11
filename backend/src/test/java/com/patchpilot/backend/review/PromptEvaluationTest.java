package com.patchpilot.backend.review;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.core.io.DefaultResourceLoader;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@EnabledIfSystemProperty(named = "patchpilot.prompt-eval.enabled", matches = "true")
class PromptEvaluationTest {

	private final ObjectMapper objectMapper = new ObjectMapper();
	private final ReviewAnalysisValidator validator = new ReviewAnalysisValidator();

	@Test
	void comparesPromptVersionFixturesAgainstLabeledCases() throws Exception {
		ReviewPromptCatalog promptCatalog = new ReviewPromptCatalog(new DefaultResourceLoader());
		assertThat(promptCatalog.get("v1")).contains("PatchPilot Review Prompt V1");
		assertThat(promptCatalog.get("v2")).contains("PatchPilot Review Prompt V2");

		JsonNode dataset;
		try (InputStream input = getClass().getResourceAsStream("/eval-fixtures/cases.json")) {
			assertThat(input).as("labeled prompt evaluation dataset").isNotNull();
			dataset = objectMapper.readTree(input);
		}
		assertThat(dataset.path("schemaVersion").asInt()).isEqualTo(1);
		assertThat(dataset.path("cases").size()).isGreaterThanOrEqualTo(4);

		Metrics v1 = evaluate(dataset.path("cases"), "v1");
		Metrics v2 = evaluate(dataset.path("cases"), "v2");
		assertThat(v1.truePositives()).isEqualTo(2);
		assertThat(v1.falsePositives()).isEqualTo(2);
		assertThat(v1.falseNegatives()).isZero();
		assertThat(v2.truePositives()).isEqualTo(2);
		assertThat(v2.falsePositives()).isZero();
		assertThat(v2.falseNegatives()).isZero();
		assertThat(v2.precision()).isGreaterThan(v1.precision());
		assertThat(v2.recall()).isEqualTo(v1.recall());
		System.out.printf("PROMPT_EVAL model=patchpilot-fixture-replay/1 temperature=0.0 seed=0 "
				+ "cases=%d v1=TP:%d FP:%d FN:%d P:%.2f R:%.2f F1:%.2f "
				+ "v2=TP:%d FP:%d FN:%d P:%.2f R:%.2f F1:%.2f%n",
				dataset.path("cases").size(), v1.truePositives(), v1.falsePositives(), v1.falseNegatives(),
				v1.precision(), v1.recall(), v1.f1(), v2.truePositives(), v2.falsePositives(),
				v2.falseNegatives(), v2.precision(), v2.recall(), v2.f1());
	}

	private Metrics evaluate(JsonNode cases, String promptVersion) {
		Set<String> expected = new HashSet<>();
		Set<String> predicted = new HashSet<>();
		for (JsonNode testCase : cases) {
			String caseId = testCase.path("id").asText();
			for (JsonNode finding : testCase.path("expected")) {
				expected.add(key(caseId, finding.path("ruleId").asText()));
			}
			ReviewModelClient replayClient = request -> analysis(
					testCase.path("fixturePredictions").path(request.promptVersion()));
			ReviewModelRequest request = new ReviewModelRequest(caseId, null, null,
					testCase.path("diffText").asText(), promptVersion, "fixture replay");
			ReviewAnalysis output = validator.validate(replayClient.analyze(request));
			for (ReviewFinding finding : output.findings()) {
				predicted.add(key(caseId, finding.ruleId()));
			}
		}
		int truePositives = (int) predicted.stream().filter(expected::contains).count();
		int falsePositives = predicted.size() - truePositives;
		int falseNegatives = expected.size() - truePositives;
		return new Metrics(truePositives, falsePositives, falseNegatives);
	}

	private ReviewAnalysis analysis(JsonNode predictions) {
		List<ReviewFinding> findings = new ArrayList<>();
		for (JsonNode prediction : predictions) {
			findings.add(new ReviewFinding(FindingSeverity.valueOf(prediction.path("severity").asText()),
					prediction.path("ruleId").asText(), "src/Sample.java", 1, 1,
					"Fixture prediction", "Deterministic evaluation fixture.", null));
		}
		return new ReviewAnalysis("Deterministic prompt evaluation fixture.", findings);
	}

	private String key(String caseId, String ruleId) {
		return caseId + ":" + ruleId;
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
