"""Daily consistent backup, encrypted off-site verification, weekly remote restore.

Requires a preconfigured rclone crypt remote; secrets stay in its private config.
"""
import argparse
import datetime as dt
import json
import os
from pathlib import Path
import re
import subprocess
import time
from urllib.request import Request, urlopen
import uuid
import recovery

NAME = re.compile(r"mealcheck-\d{8}T\d{6}Z-[a-f0-9]{8}")


def command(args):
    result = subprocess.run(args, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if result.returncode: raise RuntimeError(f"{args[0]} operation failed (exit {result.returncode}); inspect private tool logs")
    return result.stdout


def remote_root():
    remote = os.environ.get("MEALCHECK_BACKUP_REMOTE", "").rstrip("/")
    if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_-]+:[A-Za-z0-9_./-]+", remote) or ".." in remote.split("/"):
        raise RuntimeError("Configure MEALCHECK_BACKUP_REMOTE as a dedicated remote:subdirectory")
    config = json.loads(command(["rclone", "config", "dump"]))
    if config.get(remote.split(":")[0], {}).get("type") != "crypt":
        raise RuntimeError("Off-site backups require an rclone crypt remote")
    return remote


def metrics(mode, passed, duration):
    prefix = "mealcheck_" + mode
    lines = [f"{prefix}_success {int(passed)}", f"{prefix}_duration_seconds {duration:.3f}"]
    if passed: lines.append(f"{prefix}_last_success_timestamp_seconds {time.time():.0f}")
    gateway = os.environ.get("MEALCHECK_BACKUP_PUSHGATEWAY", "http://127.0.0.1:19091").rstrip("/")
    # POST merges metrics so failed runs preserve the last successful timestamp.
    with urlopen(Request(gateway + "/metrics/job/mealcheck_" + mode, data=("\n".join(lines) + "\n").encode(), method="POST"), timeout=10): pass


def execute(args):
    remote = remote_root()
    root = args.output.resolve()
    root.mkdir(parents=True, exist_ok=True)
    if os.name != "nt": root.chmod(0o700)
    name = "mealcheck-" + dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:8]
    directory = root / name
    if args.mode == "backup":
        recovery.backup(argparse.Namespace(env_file=args.env_file, project=args.project, compose=args.compose, output=directory))
        command(["rclone", "copy", str(directory), remote + "/" + name])
        # Read/decrypt remote contents and verify against the local source.
        command(["rclone", "check", "--download", str(directory), remote + "/" + name])
        (directory / "offsite-verified.json").write_text(json.dumps({"verified": True, "name": name}), encoding="utf-8")
    else:
        names = command(["rclone", "lsf", "--dirs-only", remote]).decode().splitlines()
        names = sorted(entry.rstrip("/") for entry in names if NAME.fullmatch(entry.rstrip("/")))
        if not names: raise RuntimeError("No remote backup found")
        directory.mkdir(mode=0o700)
        command(["rclone", "copy", remote + "/" + names[-1], str(directory / "downloaded")])
        report = recovery.drill(directory / "downloaded", directory / "restored")
        print(json.dumps(report))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mode", choices=["backup", "restore"], required=True)
    parser.add_argument("--env-file", type=Path, default=Path(".env.production"))
    parser.add_argument("--compose", type=Path, action="append")
    parser.add_argument("--project")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    started, passed = time.monotonic(), False
    try:
        execute(args); passed = True
    finally:
        metrics(args.mode, passed, time.monotonic() - started)


if __name__ == "__main__": main()
