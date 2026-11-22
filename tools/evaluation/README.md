# 100-image recognition, scoring and advice regression

## Offline contracts in a fresh checkout

After compiling the current backend, regenerate test inputs with the production
splitter. Historical local evidence is not checked into Git and is not a CI input.
The generated directory must be new and under `logs/`:

```powershell
python -m pip install -r tools/requirements-ci.txt
python tools/evaluation/prepare_ci_fixtures.py --output logs/backend/ci-fixtures-NEW-TIMESTAMP
$env:MEALCHECK_TEST_CHUNK_MANIFEST = (Resolve-Path logs/backend/ci-fixtures-NEW-TIMESTAMP/chunks.json).Path
$env:MEALCHECK_TEST_SCREENING_PACKET = (Resolve-Path logs/backend/ci-fixtures-NEW-TIMESTAMP/screening-packet).Path
python tools/run_logged.py --group backend --name evaluation-contracts -- python tools/evaluation/run_offline_unit_tests.py
```

CI and `release_baseline.py` prepare these paths automatically. These tests make
no real model calls; executing a historical live-run tool remains a separate task.

## RAG full sample evaluation

`RagLiveQualityDatabaseIntegrationTest` also supports opt-in full RAG sampling.
Use `MEALCHECK_RAG_FULL_EVALUATION=true`, `MEALCHECK_RAG_CASE_LIMIT=20` and a fixed
`MEALCHECK_RAG_SAMPLE_SEED`. Full mode explicitly enables production Function
Calling, live pgvector retrieval and a same-model quality review. It records
tool traces, citations, four review scores and strict evidence pass flags.
These scores are model-review proxies, not independent human accuracy.

Use `MEALCHECK_RUN_OFFLINE_RAG=true` for local fixture validation. Real provider
runs require explicit user authorization and `MEALCHECK_RUN_LIVE_RAG=true`.
Set `MEALCHECK_RAG_OUTPUT` to a fresh batch under `logs/backend/`; defaults also
use `logs/`. Pass `-Dmealcheck.testReportsDirectory=...` under the same batch to
place Surefire/Failsafe reports there. The runner does not retry provider errors.

After investigating an interrupted run, `MEALCHECK_RAG_RESUME` can point to its
saved `results.json`: the sample IDs, source fingerprint and models must match.
Previously successful Function Calling answers are reused, preserving their
tool failures; their missing quality review is completed without regenerating
the answer. Prior call accounting is retained. An embedding cache may be copied
to the new batch and selected with `MEALCHECK_RAG_EMBEDDING_CACHE`.

After completion, generate readable answers, scores and accounting offline:

```powershell
python tools/evaluation/report_rag_full.py logs/backend/NEW-RUN/evidence/results.json
```

The full RAG quality review uses the assistant thinking policy (`off` in this
harness), so hidden reasoning does not consume the capped answer budget. API
errors, truncation and absent usage are retained as limitations in the evidence.

## Image regression runner

This opt-in runner compiles the **current workspace Java sources**, then calls the
configured real provider through `VisionFoodRecognitionService`,
`MealScoringService` and `AdviceGenerationService`. It normalizes each original
image with the production validator, checks every fixture SHA-256, uses the
`balanced` goal, disables thinking, caps output at 2,048 tokens and disables
automatic retries. Non-food images stop after recognition. A local rule fallback
is retained in evidence and counted as an incomplete AI-advice stage.

The 100 synthetic images and proposed labels are preserved. This is a component
quality regression: database, Redis, RabbitMQ, browser flow, live vector retrieval
and embedding/reranking are outside its scope. Advice uses three fixed excerpts
from the repository knowledge guide, matching the earlier eight-image comparison;
the excerpts are saved with the run. No user records or production configuration
are changed.

## Running

Prepare a test-scope dependency classpath with Maven (or use an existing compatible
classpath file). `spring-boot-starter-test` is needed for the isolated harness.

```powershell
# In backend/; downloading dependencies may require network access.
mvn dependency:build-classpath -DincludeScope=test -Dmdep.outputFile=target/evaluation-classpath.txt

# In the project root: compile and check all fixtures, without provider calls.
./tools/evaluation/run-full-meal-regression.ps1 `
  -ClasspathFile ./backend/target/evaluation-classpath.txt `
  -OutputDirectory ./loadtests/results/full100-NEW-RUN

# Add -Run only when real provider calls and their cost are authorized.
./tools/evaluation/run-full-meal-regression.ps1 `
  -ClasspathFile ./backend/target/evaluation-classpath.txt `
  -OutputDirectory ./loadtests/results/full100-NEW-RUN `
  -TokenBudget 350000 -Workers 2 -Run

python tools/evaluation/summarize_full_meal_regression.py `
  --run-dir ./loadtests/results/full100-NEW-RUN

# After the advice reviews are recorded, audit the completed evidence offline.
python tools/evaluation/audit_full_meal_regression.py `
  --run-dir ./loadtests/results/full100-NEW-RUN
```

The runner reads only the AI key, base URL and model from the local `.env`.
Credentials and image Base64 are excluded from saved evidence. Only original
synthetic images are sent to the configured model endpoint. The concurrency and
budget persistence services are isolated doubles; an independent in-process token
ledger controls this run. At most two calls execute concurrently, with starts
spaced by at least 800 ms.

## Budget and evidence

Before each request, admission reserves a conservative input estimate plus the
maximum output allowance. Provider usage replaces this reservation when known;
calls without usage retain a conservative allowance. Admission stops when known
usage, unknown allowances and in-flight reservations would exceed the chosen
budget. This controls local admission, not a guaranteed supplier billing cap.
Budgets above 350,000 tokens are refused by this runner.

For a budget-limited targeted retest, pass `-Workers 1 -TokenBudget 38000`
and `-Cases 'synth-021,synth-026,synth-033,synth-056,synth-051,synth-076'`.
The explicit order is preserved. The first rejected token reservation ends
admission; remaining cases are recorded as `NOT_RUN_BUDGET`, not model failures.
Do not start another invocation to bypass that stop. The original full dataset
and all image hashes are still checked, while only selected cases are submitted.
For these partial v5 runs, use `report_priority_retest.py --run-dir ...` after
recording per-case advice reviews. It separates completed cases from untested
cases and compares saved predictions under the same reviewed labels. Legacy
label scores are proxy values, not independent expected scores for v5.

When the user explicitly authorizes running until the configured provider stops,
add `-StopOnProviderError -Workers 1` and an ordered case list. The first non-2xx
response ends admission, including advice after a recognition rejection. Its HTTP
status and sanitized provider error code are saved; subsequent cases are marked
`NOT_RUN_PROVIDER_STOP`. Authentication errors and rate limits also stop the run:
only an explicit quota error supports saying the model allowance was exhausted.
No provider/account limit is changed and no retry or fallback is counted as a
successful AI result. The existing 350,000-token local ceiling still applies.
`ProviderStopSelfTest.java` verifies the stop boundary using a loopback HTTP 403,
without an API key. Use `summarize_provider_stop_run.py --run-dir ...` after the
run to reconcile actual known usage, conservative unknown allowances, score
breakdowns, completed stages and per-case advice reviews.

`-Model qwen3.7-flash` overrides the model for that invocation only, using the
existing configured endpoint/key without editing `.env` or the business default.
Qwen3.7 Flash (including its snapshot aliases) shares the verified JSON output,
thinking-off and total-completion controls and 32×32 image-token bounds. Other
unverified model families remain refused by this runner. Compare a completed
same-image subset with `compare_model_image_runs.py --reference ... --candidate
...`; it checks source/prompt components, normalized image hashes and knowledge
excerpts before presenting paired metrics. Exact aliases and selected synthetic
samples do not establish general recognition accuracy.

`results.json` is checkpointed before calls, after calls and after each image.
It retains per-stage usage, final answers, request fingerprints, outcomes, model
and output policies. Existing results cannot be overwritten or silently retried.
A new invocation is a new paid run; review existing evidence first.

The offline reporter produces `summary.json`, `report.md`, `review-input.json`
and a self-contained thumbnail `gallery.html`. Optional `advice-review.json` maps
case IDs to explicit reviewer records. A missing review stays unreviewed; keyword
screening does not imply an automatic quality pass. The reporter also checks the
token ledger and verifies that recorded source files still match their hashes.

Names are compared using the existing exact-name/declared-alias one-to-one
matcher. Semantically equivalent names can count as missing/extra. Category and
score comparisons use the original **proposed** labels; label-based scores use
the same scoring implementation and are not an independent nutrition reference.
Do not interpret these metrics as real-photo accuracy or a release quality gate.

## First-round offline repair verification

`build_audited_labels.py` builds a separate, versioned synthetic label revision
from the explicit visual-review record. It verifies the base and image hashes,
requires complete non-overlapping review coverage, preserves the base, and never
sets independent-human-annotation flags. Its regression tests run in CI without
the ignored image files or any provider configuration.

`OfflineMealReplay.java` loads the saved full-run recognition results, runs the
current production scorer, and captures 80 advice requests using a mocked client.
It never creates an HTTP transport or reads `.env`, and refuses to overwrite an
existing replay. The updated prompt is tested as a request, not as a new AI answer.

Example after a normal backend build, using a test-scope dependency classpath:

```powershell
$cp = 'backend/target/classes;' + (Get-Content backend/target/evaluation-classpath.txt -Raw).Trim()
javac -encoding UTF-8 -cp $cp -d loadtests/results/replay-NEW/classes tools/evaluation/OfflineMealReplay.java
java -cp ('loadtests/results/replay-NEW/classes;' + $cp) com.example.mealcheck.service.OfflineMealReplay `
  loadtests/results/full100-20261016/results.json `
  loadtests/results/replay-NEW/offline-replay.json `
  tools/datasets/synthetic-meals-100/dataset.assistant-reviewed-v2.json
python tools/evaluation/report_round1_replay.py --replay loadtests/results/replay-NEW/offline-replay.json
```

The reporter measures the same saved predictions under the original and revised
labels, verifies input preservation, and explicitly marks cross-label model
quality comparisons as invalid. Label-score comparisons remain proxy checks;
unresolved preparation/category conflicts remain visible.

## Offline semantic-conflict replay

`OfflineMealSemanticReplay.java` replays a completed run's saved recognition and
advice through the current local semantic validator, scorer and advice checker.
Compile it in package `com.example.mealcheck.service` against the backend classes
and dependencies. Its two arguments are the saved `results.json` and a new output
JSON path. Existing outputs cannot be overwritten. It makes no provider calls and
does not read credentials. Same-input score differences are checked before any
semantic changes; separately reported reviewed-input scores and old-advice
rejections are not measurements of newly generated model quality.

See `deploy/MEAL_SEMANTIC_FIXES_20261017.md` for the 2026-10-17 replay, checks and
remaining database/model verification limits.

`OfflineAdviceRepairReplay.java` reuses recorded `calls[].answer` (or a saved advice
field for older runs) through a mocked `AiChatClient` and the compiled production
advice service. Its arguments are the source `results.json` and a new output JSON
path. It includes saved fallback cases, reports retained/filtered/rule-fallback
answers separately, and checks same-input scores. It does not construct an HTTP
transport or read credentials. Run with a pre-fix or post-fix backend classpath to
compare local handling of the same answers; this does not measure fresh model
generation. See `deploy/ADVICE_REVIEW_FIX_20261018.md`.

## RAG retrieval and final-answer evidence

`RagLiveQualityDatabaseIntegrationTest` is opt-in. Set only
`MEALCHECK_RUN_OFFLINE_RAG=true` for a full local 200-source / 200-question pipeline
check with in-process hash embeddings and fixed answers. It does not read `.env`
or call provider endpoints. Ordinary tests skip it. Live mode requires separate
authorization and is not enabled by the offline command.

`report_rag_quality.py <results.json>` audits saved results without network calls
and writes `audit.json`, `report.md`, and an unfilled `review.csv` for manual
citation-support and refusal review. Its tests run with
`python -m unittest discover -s tools/evaluation -p test_rag_quality.py`.
Offline results cannot be reported as real semantic or answer quality.
See `deploy/KNOWLEDGE_JOB_RECOVERY_20261018.md` for commands, scope, and limitations.

For a reproducible random subset, set `MEALCHECK_RAG_CASE_LIMIT=10` and
`MEALCHECK_RAG_SAMPLE_SEED=20261018`. Both retrieval and final-answer evaluation
are limited to those cases. Reports retain the original dataset IDs and seed;
the complete 200-source corpus is still needed for the isolated retrieval index.
The default without a limit remains the full dataset. Live provider mode must
only be used within the user's authorized data, destination, and question count.

## Fixed low-cost chunking comparison suite

`backend/src/main/resources/knowledge/rag_chunking_eval_suite.json` freezes
`chunking-pilot-v4`: 30 screening questions (24 answerable, 4 insufficient-evidence,
2 out-of-scope) and 10 separate answer-validation questions (7/2/1 respectively).
The two question sets are disjoint. `answer10.core5CaseIds` fixes the same five
questions for both finalists when using the smaller answer budget; use all ten
for both when expanding. Do not choose different questions per strategy.

Each question records its original dataset ID/position, stratum, selection
rationale and source-case fingerprint. The suite also freezes the full dataset
fingerprint and canonical corpus fingerprint. Proposed evidence retains its
direct/background relevance and alternative required-source groups, with exact
quotes and Unicode code-point offsets in the LF-normalized original Markdown.
These anchors must be mapped to each strategy's actual chunks; old chunk IDs
and title-only hits are not evidence coverage. Question sets may share sources
and concepts, so this is a process holdout, not independent blind validation.
All labels remain `PENDING_HUMAN_REVIEW`.

The corpus changed from v7 to v8 while this selection was first being frozen.
The fingerprint guard rejected the mismatch; v2 retained all 40 IDs and rebound
the evidence to v8. The initial v1 snapshot and revision receipt are preserved
under `logs/backend/chunking-suite-revisions-*`. Future source changes must be
reviewed and versioned rather than silently regenerating the frozen fixture.
The v3 revision preserves all 40 IDs while incorporating selected assistant chunk
judgments. Distinct evidence units within one title have distinct reference IDs;
the previous v2 snapshot is archived under `logs/backend/rag-relevance-v5-*`.
The v4 boundary revision preserves those IDs and original quote positions,
rebinds splitter-dependent judgments, and folds duplicate quote units from
overlapping chunks. The previous v3 fixture is archived in
`logs/backend/rag-boundary-fix-20261021-r1/before-rag_chunking_eval_suite.json`.

Validate or export without a database, network, embedding or chat calls:

```powershell
python tools/run_logged.py --group backend --name chunking-suite-check -- python -X utf8 tools/evaluation/freeze_chunking_suite.py --check
python tools/run_logged.py --group backend --name chunking-suite-export -- python -X utf8 tools/evaluation/freeze_chunking_suite.py --export
python tools/run_logged.py --group backend --name chunking-suite-tests -- python -X utf8 -m unittest discover -s tools/evaluation -p test_chunking_suite.py
```

Export creates a fresh `logs/backend/chunking-suite-TIMESTAMP/` directory with
`screening30.json`, `answer10.json`, a readable `suite-summary.md`, the source
corpus, a blank `review.json` without proposed labels, and file checksums.
Existing directories are never overwritten. `--freeze` is for first creation
only and refuses to overwrite the source fixture. This tool fixes and validates
the datasets; it does not create three indexes or run retrieval/generation.
The admin evaluation and existing random-sample runner are not automatically
changed to these question counts. Blank review export and passing fixture
tests establish neither completed independent review nor live model quality.

## Shared 200-question RAG dataset

### A/B/C offline smoke comparison

For the next dense retrieval pilot, `run_chunking_retrieval.py --offline-batch
logs/backend/chunking-smoke-TIMESTAMP --plan` writes an offline cache/call plan.
It never constructs an HTTP client in plan mode. After explicit approval to send
the full corpus and screening30 to the configured Aliyun embedding endpoint,
use `--live` instead. This uses an isolated in-memory cosine index, not the
production hybrid retrieval chain. It compares Top5 and a 2000 estimated-token
context budget, scores original evidence groups, and excludes two out-of-scope
questions from evidence recall. No answer10/chat/reranker calls or database writes.
Provider errors stop the run without automatic retry; usage and response batches
are checkpointed under the new output directory. Batch usage is aggregate and is
never attributed in full to each cached vector. Matching single-input cache keys
bind endpoint, model, dimensions and exact text (production title + two newlines
+ body). Defaults are 10 inputs/batch, 4 workers, 600 requests maximum and an
actual input-token stop threshold below 2,000,000 tokens.

`run_chunking_smoke.py` compiles the current production splitter and runs all
three strategies with the same default 300/500/50 estimated-token parameters.
It validates the frozen suite, checks full-corpus source coverage, and measures
fragmentation of direct evidence in `screening30`. It does not inspect `answer10`,
start Spring/a database, read `.env`, download dependencies, or call providers.
It needs a JDK, existing `backend/target/classes`, the saved dependency jars in
`logs/backend/knowledge-longform-20261020-distribution-v1/lib` (override with
`--dependency-directory`), and matching LangChain4j/OpenNLP jars in local Maven
cache. The helper's optional manifest strategy argument preserves existing calls.

```powershell
python tools/run_logged.py --group backend --name chunking-abc-simple -- python -X utf8 tools/evaluation/run_chunking_smoke.py
python tools/run_logged.py --group backend --name chunking-smoke-tests -- python -X utf8 -m unittest discover -s tools/evaluation -p test_chunking_smoke.py
```

A fresh `logs/backend/chunking-smoke-TIMESTAMP/` contains A/B/C chunk manifests,
the corpus/suite snapshots, source/dependency hashes, logs, `results.json`,
`report.md` and checksums. An existing output directory is never overwritten.
Coverage ignores whitespace; ambiguous repeated occurrences remain unproven.
Structural subsection labels carried in chunk titles count separately as heading
metadata. Short B/C chunks are diagnostics, not failures. Token sums exclude
titles and describe the regex estimate, not provider billing. These are offline
split diagnostics, not retrieval recall, generated-answer quality or a ranking.

The default admin workflow is now **RAG chain evaluation** (`RAG_CHAIN`):
one case runs tool orchestration, actual retrieval, and linked stage assessment.
It scores only the results returned by that case's real tool calls. Controlled
`RAG_TOOLS` remains compatible for historical/API use, and standalone retrieval
is an optional diagnostic. See [chain workflow and metric definitions](../../deploy/RAG_CHAIN_EVALUATION_20261019.md).

The current `backend/src/main/resources/knowledge/rag_eval_cases.json` contains
**200 questions: 168 answerable, 12 in-domain insufficient-evidence and 20 out of scope**.
The original `rag-001` through `rag-158` retain their order and questions, with
new source-bound evidence annotations. `rag-159` through `rag-190` cover staple combinations, protein food
choices, vegetables, fruit, drink labels, takeaway and campus meal planning.
`rag-191` through `rag-200` add 10 no-answer questions, including unrelated tasks,
privacy/instruction extraction, diagnosis and medication requests. They are authored
regression samples, not independently collected real user questions. Twelve
questions in `rag-159` through `rag-190` now ask for facts that the corpus alone
cannot establish, such as actual food weights, ingredients or absent labels.
They still require knowledge retrieval; retrieval success alone does not prove
that the final answer correctly acknowledges missing information.

Every added answerable question has an existing target knowledge title and
`agentEvaluation` tool/argument rules. Both the retrieval benchmark and the
`RAG_TOOLS` mode use this same file. The adapted suite version is
`rag-200-v7+rag-tools-v2`; the tool rules themselves remain v2. Dataset content
and corpus fingerprints distinguish the new suite from historical runs.

`rag_eval_splits.json` v3 keeps the original 20 fixed regression IDs and extends
the broader evaluation pool to 180 questions. This pool is not a strict blind
test: local routing tests inspect all known questions. The opt-in database
harness derives the default question count from the selected full dataset or
split, and records the actual full dataset size in its evidence. A fixed seed
can select different questions after a population change; compare saved IDs and
fingerprints as well as the seed. Historical results retain their original
question counts. The offline reporter accepts their recorded counts and uses
200 only when no count metadata exists.

The `rag-chain-v3` assessor binds all 180 knowledge questions to content hashes,
original quotations and graded relevance (1 background, 2 direct evidence).
Required evidence groups explicitly list their direct alternatives. Final coverage uses the
union of actual tool results across calls; first-query ranking remains separate.
Background sources cannot satisfy direct evidence groups. Graded nDCG@5 counts
each source once. Historical rows retain their old scores; old queued scoring
versions must be resubmitted. Standalone retrieval remains a title-based diagnostic.
The v3 scoring policy distinguishes unjudged hits from explicit irrelevance and
invalid content identities. Missing coverage with unjudged results has status
`UNJUDGED`, not a confirmed relevance miss. The case does not automatically pass.
Ranking aggregates omit queries containing unjudged primary hits and report their
count separately; these aggregates describe the judgeable subset, not the whole
population. Empty searches remain misses, errors remain errors, and a complete
known evidence group can still confirm coverage alongside unknown hits.

All annotations are explicitly `PENDING_HUMAN_REVIEW`, not independently verified
gold labels. Source matching verifies provenance, not semantic correctness.
Export an unfilled review packet without proposed per-question labels using:

```powershell
python tools/run_logged.py --group backend --name rag-independent-review -- python tools/evaluation/export_rag_review.py --chunk-manifest logs/backend/knowledge-longform-v8-release-20261021/candidate-chunks.json
```

The new `logs/backend/rag-independent-review-TIMESTAMP/` directory contains the
corpus, blank question reviews and source hashes. A reviewer uninvolved in
authoring/debugging must complete it independently. Exporting a blank packet
does not establish completed human review or external blind generalization.

To run dataset, retrieval, routing and tool-rule tests without a model call:

```powershell
$reportDir = Join-Path (Get-Location).Path ('logs/backend/rag200-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
New-Item -ItemType Directory -Path $reportDir | Out-Null
python tools/run_logged.py --group backend --name rag200 --cwd backend -- mvn test `
  '-Dtest=RagEvidenceLabelsTest,RagChainAssessmentTest,RagChainExecutionTest,RagBenchmarkServiceTest,RagToolEvaluationRulesTest,AssistantBenchmarkServiceTest,AssistantRagSafetyTest,RagEvaluationSampleTest,AssistantEvaluationDatasetTest,AssistantEvaluationToolRuntimeFactoryTest,AssistantEvaluationRunnerTest' `
  "-Dmealcheck.testReportsDirectory=$reportDir"
```

These tests validate the fixtures and local behavior with controlled replacements;
they do not establish real embedding, ranking or generated-answer quality.

The current `v8-longform-200` corpus preserves all 200 original topics and
quotations and adds 3,025 literal, topic-specific paragraphs. With the corrected
boundaries it yields 1,351 default chunks: 40 short topics contribute 94 chunks,
60 medium topics 248, 60 long topics 462 and 40 extended topics 547. Every topic meets its original
2–3/4–6/7–10/12–20 range. The Java regression test requires these bounds directly;
one-chunk entries and relaxed metadata cannot satisfy the release gate.
RAG v6 adds 236 explicit assistant judgments (158 direct, 74 background, 4 irrelevant)
over 237 inspected case/chunk pairs, including one original target downgraded to
background. Questions, tool rules, answerability and boundary requirements are
unchanged. Three compound questions require two distinct evidence facets.
These are selected candidate reviews, not exhaustive relevance labels or independent
human gold. The subsequent v7 boundary revision keeps the corpus bytes unchanged
and rebinds these judgments to 1,351 actual chunks. Of three expanded negative
contexts, one was explicitly reread as background and two remain irrelevant.

Literal review decisions are in `tools/evaluation/relevance_reviews_v1/`.
`expand_rag_relevance.py` validates source fingerprints, exact quotes, review scope,
negative labels, direct-evidence alternatives and explicit prior-target revisions.
It produces candidates under `logs/`; `--install` stages and replaces the three
evaluation resources only. No model, database or embedding calls are implemented.
The following is the historical v6 candidate workflow; current boundary-revision
reproduction is documented immediately below. Historical installation is refused
when its manifest revision differs from the current splitter metadata.

```powershell
python tools/run_logged.py --group backend --name relevance-reproduce -- python -X utf8 tools/evaluation/expand_rag_relevance.py `
  --manifest logs/backend/knowledge-longform-v8-release-20261021/candidate-chunks.json `
  --dataset logs/backend/rag-relevance-v5-20261021-1420/before-rag-eval-cases.json `
  --splits logs/backend/rag-relevance-v5-20261021-1420/before-rag-eval-splits.json `
  --suite logs/backend/rag-relevance-v5-20261021-1420/before-rag-chunking-eval-suite.json
```

See `deploy/RAG_RELEVANCE_REVIEW_20261021.md` for inspection scope and remaining
boundary diagnostics. Independent content and relevance review remains pending.

## Current sentence/heading boundary revision

`sentence-heading-boundaries-v2` keeps the source corpus and 300–500 estimated-token
limits unchanged. Overlap starts at a complete sentence/paragraph near the configured
50-token target; it can be shorter or longer to satisfy final-chunk constraints.
Standalone headings stay with following prose. Oversized unpunctuated units retain
a bounded token fallback rather than silently exceeding the maximum.

`audit_chunk_boundaries.py` verifies same-topic contiguous matches and an ordered,
gap-free assignment covering the full source, without inventing unique offsets for
repeated text. `rebind_rag_chunk_boundaries.py` preserves exact quote facets and their
AND/OR coverage formula. Missing quotes, positive/negative collisions, and expanded
negative contexts block automatic migration. Explicit decisions require new-manifest
and corpus hashes, actual quotes, reviewer and rationale. They do not become human gold.

```powershell
python tools/run_logged.py --group backend --name boundary-rebind-reproduce -- python -X utf8 tools/evaluation/rebind_rag_chunk_boundaries.py `
  --before-manifest logs/backend/knowledge-longform-v8-release-20261021/candidate-chunks.json `
  --manifest logs/backend/rag-boundary-fix-20261021-r1/candidate-chunks.json `
  --dataset logs/backend/rag-boundary-fix-20261021-r1/before-rag_eval_cases.json `
  --metadata logs/backend/rag-boundary-fix-20261021-r1/before-diet_guides_metadata.json `
  --adjudications tools/evaluation/knowledge_boundary_reviews_v2.json `
  --splitter-revision sentence-heading-boundaries-v2
```

The installer validates the archived base, fresh compiled-source hashes, byte-equal
production manifests, independent boundary audit, original length bounds and equivalent
candidate regeneration before replacing four evaluation/metadata resources. It does
not invoke indexing or providers. Current index source version is
`v8-longform-200-boundary-v2`; local `.env` overrides require corresponding deployment
configuration. See `deploy/RAG_CHUNK_BOUNDARIES_20261021.md` for final results.

The historical v7 `knowledge_topic_revisions.md` contains 178 literal revisions, and the retained
`knowledge_longform_topic_details.json` provides 22 separately authored details.
`revise_knowledge_topic_prose.py` reproduces that concise intermediate phase;
it is not the current longform release builder. Current literal additions are in
`knowledge_longform_v8/knowledge-NNN.md`. The local staging workflow uses
`refresh_knowledge_splitter_runtime.py`, `check_longform_manuscript.py`,
`assemble_knowledge_v8.py` and `install_knowledge_v8.py`. It requires the frozen
baseline, all 200 manuscripts, matching author hashes, full length checks, prose
diagnostics and a final assistant editorial review before resource installation.
It never calls a provider or rebuilds a database index. Evidence and temporary
files remain under `logs/`. Use `run_offline_unit_tests.py` via `run_logged.py`
to keep utility-test temporary files inside the project logs tree.

The old `knowledge_case_prose.py` / `author_knowledge_longform.py` workflow is
retained only for historical v6 preview reproduction. Provider generation remains
paused. See `deploy/KNOWLEDGE_LONGFORM_V8_20261021.md` for current results;
`deploy/KNOWLEDGE_TOPIC_REWRITE_20261020.md` and
`deploy/KNOWLEDGE_LONGFORM_20261020.md` describe the v7 and v6 intermediate phases.
