"""Read-only deployment preflight. Never prints resolved Compose credentials."""
import argparse
import json
import subprocess
from pathlib import Path


def run(args):
    result = subprocess.run(args, capture_output=True, text=True, encoding="utf-8", errors="replace")
    if result.returncode:
        raise RuntimeError("Capacity check command failed; ensure the selected Compose project is running.")
    return result.stdout


def free_bytes(compose, service, path):
    output = run(compose + ["exec", "-T", service, "df", "-Pk", path])
    return int(output.strip().splitlines()[-1].split()[3]) * 1024


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("-f", "--file", action="append", dest="files")
    parser.add_argument("--env-file")
    parser.add_argument("--project-name")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    compose = ["docker", "compose", "--project-directory", str(root)]
    for file in args.files or ["docker-compose.yml"]:
        compose += ["-f", str(root / file)]
    if args.env_file:
        compose += ["--env-file", args.env_file]
    if args.project_name:
        compose += ["--project-name", args.project_name]
    config = json.loads(run(compose + ["config", "--format", "json"]))
    env = config["services"]["backend"]["environment"]
    count_limit = int(env.get("MEALCHECK_ANALYSIS_MAX_OUTSTANDING", 200))
    payload_limit = int(env.get("MEALCHECK_ANALYSIS_MAX_STORED_IMAGE_BYTES", 536870912))
    temporary_limit = int(env.get("MEALCHECK_UPLOAD_TEMPORARY_MAX_BYTES", 1073741824))
    sql = """SELECT json_build_object(
      'pending',count(*) FILTER (WHERE status='PENDING'),
      'processing',count(*) FILTER (WHERE status='PROCESSING' AND lease_until>CURRENT_TIMESTAMP),
      'expired_with_payload',count(*) FILTER (WHERE status='PROCESSING' AND lease_until<=CURRENT_TIMESTAMP
        AND (input_image IS NOT NULL OR input_image_ref IS NOT NULL)),
      'stored_image_bytes',coalesce(sum(octet_length(input_image)),0),
      'database_bytes',pg_database_size(current_database())) FROM meal_analysis_requests;"""
    # Credentials stay in container environment; no shell interpolation of user data.
    snapshot = json.loads(run(compose + ["exec", "-T", "postgres", "sh", "-c",
        'exec psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -At -c "$1"', "capacity-check", sql]))
    postgres_free = free_bytes(compose, "postgres", "/var/lib/postgresql/data")
    upload_free = free_bytes(compose, "backend", "/app/upload-temp")
    image_free = free_bytes(compose, "backend", "/app/uploads")
    # Payload budget excludes WAL, MVCC, backups and final image files. Require
    # additional disk headroom; this check is not a reservation of that free space.
    checks = {
        "stored_images_within_budget": snapshot["stored_image_bytes"] <= payload_limit,
        "database_disk_headroom": postgres_free >= max(2 * 1024**3, payload_limit * 4),
        "upload_disk_headroom": upload_free >= temporary_limit + 64 * 1024**2,
        "final_image_disk_headroom": image_free >= payload_limit,
    }
    print(json.dumps({"limits": {"unfinished_tasks": count_limit, "stored_image_bytes": payload_limit,
        "upload_temporary_bytes": temporary_limit}, "database": snapshot,
        "free_bytes": {"postgres": postgres_free, "upload_temporary": upload_free, "final_images": image_free},
        "checks": checks, "ok": all(checks.values())}, ensure_ascii=False, indent=2))
    return 0 if all(checks.values()) else 1


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (RuntimeError, ValueError, KeyError, OSError) as error:
        print(json.dumps({"ok": False, "error": str(error)}, ensure_ascii=False))
        raise SystemExit(1)
