#!/usr/bin/env sh
set -eu

umask 077
backup_dir="${MEALCHECK_BACKUP_DIR:-/var/backups/mealcheck}"
retention_days="${MEALCHECK_BACKUP_RETENTION_DAYS:-14}"
timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
backup_file="${backup_dir}/mealcheck-${timestamp}.dump"
script_dir="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
repo_root="$(CDPATH= cd -- "${script_dir}/../.." && pwd)"

compose() {
  docker compose --env-file "${repo_root}/.env.production" -f "${repo_root}/docker-compose.prod.yml" "$@"
}

mkdir -p "$backup_dir"
compose exec -T postgres sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc' > "$backup_file"

compose exec -T postgres pg_restore --list < "$backup_file" >/dev/null
find "$backup_dir" -type f -name 'mealcheck-*.dump' -mtime "+$retention_days" -delete
printf '%s\n' "$backup_file"
