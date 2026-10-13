# Backend reliability and resource isolation (2026-10-04)

The [2026-10-10 implementation](BACKEND_IMPROVEMENTS.md) adds bounded cache/admission waits, classified weekly retries, private object storage, batched retention, interactive AI permit reservation, durable active-user dispatch and model-specific accounting. Its documented behavior supersedes the corresponding earlier sections below.

## Scheduling

`taskScheduler` uses three `background-scheduler-*` threads. The independent `assistantTimeoutScheduler` only runs assistant timeout callbacks. Blocking outbox publishing, image cleanup, retention, and weekly enqueueing use a maintenance executor with two workers and a four-task queue. Repeated ticks for a still-running task are coalesced; executor rejection does not run work on the scheduling thread. Knowledge maintenance has its own one-worker, one-task-queue executor.

## Trusted proxy chain

Use the host TLS Nginx → loopback frontend port → backend topology in `PRODUCTION.md`. The outer proxy overwrites client identity and removes untrusted forwarding headers. The inner proxy trusts only loopback and the exact production edge gateway, then forwards a single sanitized client address. `tools/test_proxy_headers.py --nginx /path/to/nginx` runs both repository proxy configurations against an isolated HTTP fixture, rotates forged headers, checks quota exhaustion, and verifies that an untrusted direct inner client cannot inject `X-Real-IP`. CI also runs the release smoke check against the real backend/Redis quota.

## Upload admission and decoding

`MEALCHECK_RATE_LIMIT_UPLOAD_INGRESS_CAPACITY` defaults to 20, refilling one request per second per user. This protects input hashing, including repeated requests. Image hashes use a bounded stream rather than another full input byte array. The existing analysis quota is acquired in the short claim transaction before decoding; successful/pending duplicate requests return after hash verification without decoding or charging analysis quota again. A failed validation marks its claim retryable through the existing FAILED flow. A crash before payload attachment leaves a leased PROCESSING claim that can be resubmitted after expiry.

`MEALCHECK_UPLOAD_MAX_CONCURRENT_DECODES` defaults to two per backend instance. Saturation returns 429 with a one-second retry hint. All image validation entry points share the same semaphore; permits are released after errors as well as successful processing. No database transaction is held during image decoding.

## Weekly dispatch and authentication

Weekly polling caps claims to available executor capacity. A task rejected before dispatch returns to RETRY after five seconds, restores its attempt budget, and rotates its execution token. The update is fenced by task ID, status, attempt and token, so stale workers cannot release newer work. Actual execution failures still consume the configured retry budget.

JWT signatures and expiry are checked before dependencies. Invalid, expired, revoked, or deleted-account tokens produce 401 on protected endpoints. Redis/database authentication failures return JSON 503 (`AUTH_DEPENDENCY_UNAVAILABLE`), `Retry-After: 3`, and increment `mealcheck.auth.dependency.failures`. This preserves the frontend session because only 401 clears it.

## Knowledge schema and vector rebuilds

V20 creates the fixed knowledge schema, extensions, indexes, durable rebuild jobs and staging rows. Existing knowledge content and embedding columns are preserved during migration. There is no bean-startup DDL or synchronous remote indexing. Automatic indexing runs on the dedicated background worker when `mealcheck.knowledge.auto-index` is enabled.

Changing the embedding model, dimension or storage creates a durable rebuild on the worker. Up to five chunks are generated per batch outside DB transactions. Completed chunks are staged with input hashes and reused after worker restart; edited chunks are regenerated. A three-minute lease and execution token fence expired workers. Failed batches retry after 30 seconds, at most five failed attempts; normal batch continuation does not spend failure budget. A terminal FAILED job requires an explicit admin retry.

Admin REST endpoints:

- `POST /api/knowledge/rebuilds`: enqueue/reuse a build for the current server configuration; returns 202 and a Location header.
- `GET /api/knowledge/rebuilds/{id}`: status, target version, attempts, staged count and total chunks.
- `POST /api/knowledge/rebuilds/{id}/retry`: resume a FAILED build with the same configuration, keeping staging progress.

Only one rebuild may be active across instances. Use consistent embedding configuration across replicas; finish an active build before changing its target. The final local database transaction rechecks current content, switches all vectors and signatures together, and rebuilds the vector index. It performs no model calls and rolls back on failure. The switch has a two-second lock wait limit and a 180-second transaction deadline; large indexes may need a maintenance window. Temporary staging requires additional disk space. Before activation, incompatible/provider-stale vectors remain stored and search uses keyword retrieval. Knowledge edits that require the new embedding configuration wait until the build completes. Search cache is cleared after activation.

## Verification

Run `mvn verify -Pintegration` in `backend` with Docker available. Database tests use pgvector PostgreSQL and include resuming staged rebuilds, atomic rollback, edited-content revalidation, stale worker fencing, and repeated weekly dispatch rejection. Ordinary regression tests cover timeout isolation, admission before decode, decoding capacity, and authentication dependency failures. Run the native proxy fixture and `python -m unittest discover -s tools -p test_operations.py -v` from the repository root.

Local verification on 2026-10-04: the full `mvn verify -Pintegration` run passed 202 ordinary backend tests and 44 PostgreSQL integration tests with Docker available, including all Flyway migrations through V21. This supersedes the earlier attempt that could not reach Docker. A final focused rerun also passed the actual trend-query plans and the budget/HTTP regression tests. The two-layer proxy fixture passed with native Nginx 1.28.0, and all seven operations-script tests passed. Image decoding capacity is covered by concurrency tests; throughput and memory improvements have not been measured with a production-sized image load test.

The additional shared AI deadline and durable daily budgets, conversation cursor pagination, and SQL trend aggregation are documented in the [README backend optimization section](../README.md#后端优化2026-10-04), including configuration, API compatibility and estimated-cost limitations. The query-plan artifact compares the previous full-row read with all three actual trend queries using `EXPLAIN (ANALYZE,BUFFERS)`; it does not establish production performance gains.
