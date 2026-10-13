# MealCheck production deployment

## Before deployment

1. Use an Alibaba Cloud VPC. Expose only ports 80 and 443 publicly; restrict SSH to trusted source IPs.
2. Prefer RDS PostgreSQL with pgvector, managed Redis, and a private OSS bucket. The bundled PostgreSQL and Redis services are intended for a small single-ECS launch only.
3. Copy `.env.production.example` to `.env.production`, replace every placeholder, and set file mode `600`.
4. Point the production domain to the ECS public IP and finish ICP filing before serving traffic from a mainland China region.

## Start the application

Image analysis now requires RabbitMQ credentials (`SPRING_RABBITMQ_PASSWORD`). The production Compose file includes a persistent, internal-only RabbitMQ service; see [RabbitMQ operations](RABBITMQ.md) for V18 migration, consumer concurrency, outbox recovery and dead-letter handling.

The default capacity profile is eight image consumers per backend, 32 outstanding tasks across replicas, two outstanding tasks per user, and ten shared AI call permits. Backend containers default to 1.5 CPU / 1 GiB with a 45% JVM heap. Existing explicit environment values override these defaults: update old `MEALCHECK_ANALYSIS_CONCURRENCY=2` and `MEALCHECK_ANALYSIS_MAX_OUTSTANDING=8` entries to 8 and 32, then recreate the backend with Compose. Stub model credentials and relaxed test quotas belong only to the isolated capacity environment. See [capacity guardrails](CAPACITY-GUARDRAILS.md).

```bash
docker compose --env-file .env.production -f docker-compose.prod.yml build
docker compose --env-file .env.production -f docker-compose.prod.yml up -d
docker compose --env-file .env.production -f docker-compose.prod.yml ps
```

The application binds only to `127.0.0.1:8080`. PostgreSQL, Redis, backend port 8080, and the frontend container port are not exposed to the public network.

## HTTPS edge

Image analysis and AI weekly reports use asynchronous jobs by default; synchronous compatibility remains available. The default timeout chain is backend AI operation 120s, container Nginx 150s, host Nginx 165s, and browser long requests 180s. Rebuild/recreate the frontend container and update the host Nginx configuration, then run `nginx -t` before reload. Raising the backend operation timeout also requires changing the proxy/browser values and checking any additional ALB/CDN gateway. See [timeout policy](TIMEOUTS.md).

Install Nginx on the ECS host, copy `deploy/nginx/mealcheck.conf.example`, replace the example domain and certificate paths, then validate and reload Nginx. The edge redirects HTTP to HTTPS and proxies to `127.0.0.1:8080`.

The host proxy is the trust boundary: it removes incoming `Forwarded` headers and rebuilds client address/protocol headers. The frontend trusts `X-Real-IP` only from loopback and the dedicated production edge bridge gateway `10.203.0.1` (`10.203.0.0/24` in Compose). Keep the published frontend port bound to host loopback. If that subnet conflicts with an existing network, change the Compose subnet/gateway and the exact trusted address in `frontend/nginx.conf` together. Do not trust all private address ranges or preserve an external `X-Forwarded-For` chain.

If TLS terminates at an Alibaba Cloud ALB instead, forward requests to the loopback-bound frontend through an appropriate private listener or change the frontend bind address to the ECS private IP. Never expose database or Redis ports.

For an ALB, explicitly allow only its private source addresses at the edge and normalize its client-IP header there. Update the frontend's exact trusted proxy addresses to match that topology before changing the bind address.

V20 moves knowledge schema ownership into Flyway and adds resumable vector rebuild jobs. PostgreSQL must provide pgvector with `halfvec` support (the bundled `pgvector/pgvector:pg16` image does). See [backend optimizations](BACKEND_OPTIMIZATIONS.md) for rebuild progress, retry, and verification.

## Backup and restore readiness

V25 adds image object references and durable weekly dispatch. Storage remains local by default; OSS requires explicit credentials and a storage-aware backup/restore workflow. The local `recovery.py` refuses external image references to avoid claiming an incomplete backup is recoverable. See [current backend improvements and configuration](BACKEND_IMPROVEMENTS.md).

For self-hosted PostgreSQL, schedule `deploy/scripts/backup-postgres.sh`, upload encrypted backup files to a private OSS bucket, and keep a second-region copy when required. The script verifies the archive structure but a staging restore drill is still required before launch.

For a consistent single-instance backup that includes uploaded images, prefer `deploy/scripts/recovery.py backup` and validate it with `recovery.py drill`. Follow [`RELIABILITY.md`](RELIABILITY.md) for writer quiescence, manifest verification, isolated restore testing, and V16 rollback requirements. The older shell script remains a database-only backup.

For RDS, enable automatic backups and log backups, define the recovery-point objective, and perform a point-in-time restore drill. Uploaded images must also have an OSS lifecycle and recovery policy.

## Monitoring and release gate

- Load `deploy/monitoring/mealcheck-alerts.yml` into Prometheus and route warning/critical alerts through Alertmanager.
- Collect container, Nginx, and application logs in SLS with secrets and authorization headers redacted.
- Alert on health check failure, 5xx rate, p95 latency, disk usage, container restarts, PostgreSQL connections, Redis availability, and AI failure/cost rate.
- Run `mvn test`, `npm ci && npm run build`, vulnerability scanning, smoke tests, and a restore drill before promoting an immutable image tag.
- Keep the previous image tag and database migration compatibility for rollback.
