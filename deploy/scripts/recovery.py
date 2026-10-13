"""Consistent Compose backup and an isolated, verified PostgreSQL + image restore.

No restore command writes to the source stack. Drill containers expose no ports.
Backups contain private data: encrypt before transferring to private off-site storage.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path, PureWindowsPath
import re
import shutil
import subprocess
import time
import uuid


def run(args, *, data=None, output=None):
    result = subprocess.run(args, input=data, stdout=output or subprocess.PIPE,
                            stderr=subprocess.PIPE, check=False)
    if result.returncode:
        # Never print command environment or provider/database credentials.
        raise RuntimeError(f"{args[0]} {args[1]} failed ({result.returncode}): "
                           + result.stderr.decode(errors="replace")[-1500:])
    return result.stdout


def inspect(container):
    return json.loads(run(["docker", "inspect", container]))[0]


def database_env(container):
    env = dict(item.split("=", 1) for item in inspect(container)["Config"]["Env"] if "=" in item)
    return env.get("POSTGRES_USER", "postgres"), env.get("POSTGRES_DB", "postgres")


def query(container, sql):
    user, database = database_env(container)
    return run(["docker", "exec", "-i", container, "psql", "-X", "-v", "ON_ERROR_STOP=1",
                "-U", user, "-d", database, "-At", "-c", sql]).decode().strip()


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def inventory(root):
    result = {}
    for path in sorted(root.rglob("*")):
        if path.is_symlink():
            raise RuntimeError("Upload backups must not contain symlinks")
        if path.is_file():
            result[path.relative_to(root).as_posix()] = digest(path)
    return result


def table_counts(container):
    names = query(container, "SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename").splitlines()
    result = {}
    for name in names:
        if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", name):
            raise RuntimeError("Unsupported table identifier")
        result[name] = int(query(container, f'SELECT count(*) FROM "{name}"'))
    return result


def image_paths(container):
    return json.loads(query(container, "SELECT coalesce(json_agg(stored_image_path ORDER BY id), '[]') "
                            "FROM meal_records WHERE stored_image_path IS NOT NULL"))


def queued_image_checksums(container):
    exists = query(container, "SELECT count(*) FROM information_schema.columns WHERE table_name='meal_analysis_requests' AND column_name='input_image'")
    if exists == '0': return []
    return json.loads(query(container, "SELECT coalesce(json_agg(t ORDER BY user_id,request_key), '[]') FROM "
        "(SELECT user_id,request_key,md5(input_image) AS checksum FROM meal_analysis_requests WHERE input_image IS NOT NULL) t"))


def validate_image_paths(paths, files):
    for name in paths:
        path = Path(name)
        if path.is_absolute() or PureWindowsPath(name).drive or ".." in path.parts or "\\" in name:
            raise RuntimeError("Stored image paths must be relative to the upload root")
        if name not in files:
            raise RuntimeError(f"A database image reference has no matching backup file: {name}")


def capture(postgres, uploads_source, directory, *, copy_container=None):
    # This workflow verifies local files. Never report a complete restore when
    # object-store data lives outside its archive.
    if any(name.startswith("oss://") for name in image_paths(postgres)):
        raise RuntimeError("OSS images require an object-store backup and restore workflow")
    has_refs = query(postgres, "SELECT count(*) FROM information_schema.columns WHERE table_name='meal_analysis_requests' AND column_name='input_image_ref'")
    if has_refs != '0' and query(postgres, "SELECT count(*) FROM meal_analysis_requests WHERE input_image_ref IS NOT NULL") != '0':
        raise RuntimeError("Referenced queued images require a storage-aware backup workflow")
    directory.mkdir(parents=True, exist_ok=False)
    if os.name != "nt":
        directory.chmod(0o700)
    (directory / ".incomplete").touch()
    user, database = database_env(postgres)
    with (directory / "postgres.dump").open("wb") as stream:
        run(["docker", "exec", postgres, "pg_dump", "-U", user, "-d", database, "-Fc"], output=stream)
    if copy_container:
        (directory / "uploads").mkdir()
        run(["docker", "cp", f"{copy_container}:/app/uploads/.", str(directory / "uploads")])
    else:
        inventory(uploads_source)  # Reject symlinks before copying.
        shutil.copytree(uploads_source, directory / "uploads")
    files = inventory(directory / "uploads")
    paths = image_paths(postgres)
    validate_image_paths(paths, files)
    image_id = inspect(postgres)["Image"]
    repo_digests = json.loads(run(["docker", "image", "inspect", image_id]))[0].get("RepoDigests", [])
    manifest = {
        "format": 1, "createdAtUtc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "postgresImage": repo_digests[0] if repo_digests else image_id,
        "databaseSha256": digest(directory / "postgres.dump"), "uploads": files,
        "tableCounts": table_counts(postgres), "imagePaths": paths, "queuedImageChecksums": queued_image_checksums(postgres),
    }
    (directory / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    (directory / ".incomplete").unlink()
    return manifest


def backup(args):
    compose = ["docker", "compose", "--env-file", str(args.env_file)]
    if args.project: compose += ["-p", args.project]
    for path in args.compose or [Path("docker-compose.prod.yml")]: compose += ["-f", str(path)]
    backend = run(compose + ["ps", "-a", "-q", "backend"]).decode().strip()
    postgres = run(compose + ["ps", "-q", "postgres"]).decode().strip()
    if not backend or not postgres:
        raise RuntimeError("Expected exactly one backend and PostgreSQL service; managed databases require a separate adapter")
    was_running = inspect(backend)["State"]["Running"]
    # This deployment model has a single application writer. Operators must also
    # stop external writers before invoking this command, as documented.
    try:
        if was_running:
            run(compose + ["stop", "--timeout", "45", "backend"])
        capture(postgres, None, args.output, copy_container=backend)
    finally:
        if was_running:
            run(compose + ["start", "backend"])
    print(json.dumps({"backup": str(args.output.resolve()), "verifiedManifest": True}))


def start_database(image):
    name = "mealcheck-restore-drill-" + uuid.uuid4().hex[:12]
    run(["docker", "run", "-d", "--name", name, "--label", "mealcheck.recovery-drill=true",
         "--network", "none", "-e", "POSTGRES_DB=mealcheck", "-e", "POSTGRES_USER=postgres",
         "-e", "POSTGRES_PASSWORD=" + uuid.uuid4().hex, image])
    try:
        for _ in range(60):
            try:
                query(name, "SELECT 1")
                return name
            except RuntimeError:
                time.sleep(1)
        raise RuntimeError("Temporary PostgreSQL did not become ready")
    except BaseException:
        run(["docker", "rm", "-f", "-v", name])
        raise


def drill(directory, output):
    started = time.monotonic()
    if (directory / ".incomplete").exists():
        raise RuntimeError("Backup is incomplete")
    manifest = json.loads((directory / "manifest.json").read_text(encoding="utf-8"))
    if manifest["format"] != 1 or digest(directory / "postgres.dump") != manifest["databaseSha256"]:
        raise RuntimeError("Database archive checksum mismatch")
    if inventory(directory / "uploads") != manifest["uploads"]:
        raise RuntimeError("Upload checksum mismatch")
    validate_image_paths(manifest["imagePaths"], manifest["uploads"])
    # Only a fresh directory may be used. Never overwrite an existing upload root.
    output.mkdir(parents=True, exist_ok=False)
    container = start_database(manifest["postgresImage"])
    try:
        run(["docker", "exec", "-i", container, "pg_restore", "--exit-on-error", "--no-owner",
             "-U", "postgres", "-d", "mealcheck"], data=(directory / "postgres.dump").read_bytes())
        shutil.copytree(directory / "uploads", output / "uploads")
        if table_counts(container) != manifest["tableCounts"]:
            raise RuntimeError("Restored table counts differ from the backup")
        if queued_image_checksums(container) != manifest.get("queuedImageChecksums", []):
            raise RuntimeError("Restored queued images differ from the backup")
        paths = image_paths(container)
        if paths != manifest["imagePaths"] or inventory(output / "uploads") != manifest["uploads"]:
            raise RuntimeError("Restored image references or files differ from the backup")
        validate_image_paths(paths, inventory(output / "uploads"))
        report = {"passed": True, "durationSeconds": round(time.monotonic() - started, 2),
                  "tablesVerified": len(manifest["tableCounts"]), "imageReferencesVerified": len(paths),
                  "filesVerified": len(manifest["uploads"]), "queuedImagesVerified": len(manifest.get("queuedImageChecksums", [])), "sourceModified": False}
        (output / "verification.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
        return report
    finally:
        run(["docker", "rm", "-f", "-v", container])


def self_test(args):
    args.output.mkdir(parents=True, exist_ok=False)
    uploads = args.output / "source-uploads"
    (uploads / "user-1").mkdir(parents=True)
    (uploads / "user-1" / "meal.jpg").write_bytes(b"isolated-restore-fixture")
    container = start_database(args.image)
    try:
        if args.migrations:
            migrations = sorted(args.migrations.glob("V*__*.sql"), key=lambda path: int(path.name.split("__")[0][1:]))
            if not migrations: raise RuntimeError("No migration files found")
            for migration in migrations:
                query(container, migration.read_text(encoding="utf-8"))
            query(container, "INSERT INTO users(username,password_hash) VALUES ('restore-fixture','test-only-hash'); "
                             "INSERT INTO meal_records(user_id,score,stored_image_path,detected_foods_json,original_foods_json) "
                             "VALUES (1,90,'user-1/meal.jpg','[]','[]'); "
                             "INSERT INTO meal_corrections(user_id,record_id,revision,before_foods_json,after_foods_json,reason,scoring_version) "
                             "VALUES (1,1,1,'[]','[]','restore fixture','structured-foods-v2')")
            if query(container, "SELECT count(*) FROM information_schema.columns WHERE table_name='meal_analysis_requests' AND column_name='input_image'") != '0':
                query(container, "INSERT INTO meal_analysis_requests(user_id,request_key,payload_hash,effective_goal,status,attempt_token,lease_until,input_image) "
                                 "VALUES (1,'restore-queue-fixture','fixture-hash','balanced','PENDING','fixture-token',CURRENT_TIMESTAMP,decode('abcd','hex'))")
        else:
            query(container, "CREATE TABLE meal_records(id BIGSERIAL PRIMARY KEY, stored_image_path TEXT); "
                             "INSERT INTO meal_records(stored_image_path) VALUES ('user-1/meal.jpg'); "
                             "CREATE TABLE meal_corrections(id BIGSERIAL PRIMARY KEY, record_id BIGINT REFERENCES meal_records(id)); "
                             "INSERT INTO meal_corrections(record_id) VALUES (1)")
        capture(container, uploads, args.output / "backup")
    finally:
        run(["docker", "rm", "-f", "-v", container])
    print(json.dumps(drill(args.output / "backup", args.output / "restored")))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    command = commands.add_parser("backup")
    command.add_argument("--compose", type=Path, action="append")
    command.add_argument("--project")
    command.add_argument("--env-file", type=Path, default=Path(".env.production"))
    command.add_argument("--output", type=Path, required=True)
    command = commands.add_parser("drill")
    command.add_argument("--backup", type=Path, required=True)
    command.add_argument("--output", type=Path, required=True)
    command = commands.add_parser("self-test")
    command.add_argument("--output", type=Path, required=True)
    command.add_argument("--image", default="pgvector/pgvector:pg16")
    command.add_argument("--migrations", type=Path)
    args = parser.parse_args()
    if args.command == "backup": backup(args)
    elif args.command == "drill": print(json.dumps(drill(args.backup, args.output)))
    else: self_test(args)


if __name__ == "__main__":
    main()
