# Prompt V1/V2 Offline Comparison

## Run

`cd backend && ./mvnw -Pprompt-eval verify`

## Evaluation Record

- Date: 2026-11-11
- Model: `patchpilot-fixture-replay/1` (deterministic offline response fixture; no model endpoint called)
- Parameters: temperature `0.0`, seed `0`, external requests `false`
- Dataset: `eval/cases.json`, 4 synthetic labeled cases
- Prompt files: `prompts/v1.md` and `prompts/v2.md`
- Prompt SHA-256: V1 `5dd4c8c606583882b3bf10d4b2694347bc8f8b72c65a37a5c6091d2cae838c7d`; V2 `d212498f9f6aff981031234496d98b5bcef24a20ee44164b1e279db028765975`
- Scoring: per-case rule ID precision, recall, and F1; severity labels are retained in the fixtures for review

| Version | True positive | False positive | False negative | Precision | Recall | F1 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| V1 | 2 | 2 | 0 | 0.50 | 1.00 | 0.67 |
| V2 | 2 | 0 | 0 | 1.00 | 1.00 | 1.00 |

V2 adds explicit evidence, changed-line, guard-awareness, and duplicate-suppression constraints. The fixture replay captures the intended regression cases for that iteration and verifies that the offline scorer and result contract behave consistently. These numbers do not measure an LLM's semantic response to either prompt; run the same cases against a configured model before drawing model-quality conclusions.

## Cases

The fixed set covers an unguarded nullable value, SQL string concatenation, a guarded nullable value, and a formatting-only change. All diffs are synthetic. Expected rules and V1/V2 fixture predictions are versioned together in `eval/cases.json`.
