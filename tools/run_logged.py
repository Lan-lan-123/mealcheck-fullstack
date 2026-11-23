"""Run a command with stdout/stderr saved under the repository's logs directory."""

import argparse
from datetime import datetime
from pathlib import Path
import re
import shutil
import subprocess
import sys


ROOT = Path(__file__).resolve().parents[1]
GROUPS = ('root', 'frontend', 'backend', 'deploy', 'loadtests', 'crash')


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--group', choices=GROUPS, default='root')
    parser.add_argument('--name', default='command', help='Short task name, without a path')
    parser.add_argument('--cwd', default='.', help='Working directory relative to the project root')
    parser.add_argument('command', nargs=argparse.REMAINDER, help='Command following --')
    args = parser.parse_args(argv)
    command = args.command
    if command[:1] == ['--']:
        command = command[1:]
    if not command:
        parser.error('A command is required after --')
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]{0,95}', args.name):
        parser.error('--name must contain 1-96 letters, digits, dots, underscores or hyphens')
    cwd = (ROOT / args.cwd).resolve()
    if not cwd.is_relative_to(ROOT) or not cwd.is_dir():
        parser.error('--cwd must be an existing directory inside the project')
    directory = (ROOT / 'logs' / args.group).resolve()
    if not directory.is_relative_to((ROOT / 'logs').resolve()):
        parser.error('Log directory must stay inside logs/')
    directory.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now().strftime('%Y%m%d-%H%M%S-%f')
    log = directory / f'{args.name}-{stamp}.log'
    # Resolve npm.cmd/mvn.cmd on Windows without asking callers to use a shell.
    executable = shutil.which(command[0])
    if executable:
        command[0] = executable
    print(f'Log: {log.relative_to(ROOT).as_posix()}', flush=True)
    try:
        # Exclusive creation preserves existing logs; binary mode preserves child output.
        with log.open('xb') as stream:
            result = subprocess.run(command, cwd=cwd, stdout=stream, stderr=subprocess.STDOUT)
    except OSError as error:
        print(f'Cannot run command: {error}', file=sys.stderr)
        return 127
    print(f'Exit code: {result.returncode}', flush=True)
    return result.returncode if result.returncode >= 0 else 128 - result.returncode


if __name__ == '__main__':
    sys.exit(main())
