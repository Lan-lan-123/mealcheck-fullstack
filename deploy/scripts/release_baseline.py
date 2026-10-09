"""Verify a committed candidate from a clean Git archive and bind its evidence.

No production deployment, registry push, real model calls, or Git mutations.
Only fresh scratch Compose namespaces may be created and removed.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[2]


def now():
    return datetime.now(timezone.utc).isoformat()


def sha256(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def git(*args):
    return subprocess.check_output(['git', '-c', f'safe.directory={ROOT.as_posix()}',
                                    '-C', str(ROOT), *args], text=True).strip()


def executable(name):
    found = shutil.which(name)
    if not found:
        raise RuntimeError(f'Required executable unavailable: {name}')
    return found


def test_summary(directory):
    totals = dict(suites=0, tests=0, failures=0, errors=0, skipped=0)
    for report in directory.glob('TEST-*.xml'):
        suite = ET.parse(report).getroot()
        totals['suites'] += 1
        for key in ('tests', 'failures', 'errors', 'skipped'):
            totals[key] += int(suite.attrib.get(key, 0))
    return totals


class Verification:
    def __init__(self, args):
        if git('status', '--porcelain', '--untracked-files=no'):
            raise RuntimeError('Commit tracked changes before exporting a candidate')
        self.commit = git('rev-parse', 'HEAD')
        self.output = ROOT / 'deploy/release-results' / (
            args.output_name or datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ') + '-' + self.commit[:12])
        self.output = self.output.resolve()
        if not self.output.is_relative_to((ROOT / 'deploy/release-results').resolve()):
            raise RuntimeError('Evidence must stay inside deploy/release-results')
        self.output.mkdir(parents=True, exist_ok=False)
        self.source = self.output / 'source'
        self.env = os.environ.copy()
        # Playwright must launch the archived source, never reuse another dev server.
        self.env['CI'] = 'true'
        # Scratch fixtures have explicit public test configuration. Do not inherit real keys.
        for key in ('MEALCHECK_AI_API_KEY', 'MEALCHECK_ASSISTANT_API_KEY',
                    'MEALCHECK_KNOWLEDGE_EMBEDDING_API_KEY', 'MEALCHECK_KNOWLEDGE_RERANK_API_KEY'):
            self.env[key] = ''
        self.manifest = dict(formatVersion=1, scope='local-engineering-release-candidate',
                             version=args.version, gitCommit=self.commit, gitTree=git('rev-parse', 'HEAD^{tree}'),
                             startedAtUtc=now(), status='RUNNING', checks=[], images={},
                             limitations=['Real model quality, provider quotas and actual billing are not verified.',
                                          'A full one-hour all-feature load test is not part of this baseline.',
                                          'No production deployment or remote CI run is claimed.',
                                          'Rollback compatibility with previous application images is not verified.'])
        if os.environ.get('GITHUB_ACTIONS') == 'true':
            self.manifest['scope'] = 'ci-engineering-release-candidate'
            self.manifest['ciRunUrl'] = (f"{os.environ['GITHUB_SERVER_URL']}/{os.environ['GITHUB_REPOSITORY']}"
                                         f"/actions/runs/{os.environ['GITHUB_RUN_ID']}")
            self.manifest['limitations'].remove('No production deployment or remote CI run is claimed.')
            self.manifest['limitations'].append('No production deployment is claimed.')
        self.save()
        archive = self.output / 'source.zip'
        subprocess.run(['git', '-c', f'safe.directory={ROOT.as_posix()}', '-C', str(ROOT),
                        'archive', '--format=zip', f'--output={archive}', self.commit], check=True)
        with zipfile.ZipFile(archive) as exported:
            for member in exported.namelist():
                if not (self.source / member).resolve().is_relative_to(self.source.resolve()):
                    raise RuntimeError('Invalid archive path')
            exported.extractall(self.source)
        self.manifest['sourceArchiveSha256'] = sha256(archive)
        migrations = self.source / 'backend/src/main/resources/db/migration'
        self.manifest['migrations'] = [dict(file=p.name, sha256=sha256(p)) for p in
                                       sorted(migrations.glob('V*__*.sql'), key=lambda p: int(p.name.split('__')[0][1:]))]
        config_files = ['.env.production.example', 'docker-compose.prod.yml',
                        'deploy/compose.smoke.yml', 'deploy/compose.release-smoke.yml',
                        'deploy/compose.acceptance.yml', 'deploy/compose.release-capacity.yml',
                        'backend/pom.xml', 'frontend/package-lock.json']
        self.manifest['configurationAndDependencyHashes'] = {p: sha256(self.source / p) for p in config_files}
        self.save()

    def save(self):
        path = self.output / 'manifest.json'
        temporary = path.with_suffix('.tmp')
        temporary.write_text(json.dumps(self.manifest, ensure_ascii=False, indent=2), encoding='utf-8')
        temporary.replace(path)

    def run(self, name, command, cwd=None, required=True):
        log = self.output / 'logs' / f'{name}.log'
        log.parent.mkdir(exist_ok=True)
        check = dict(name=name, required=required, startedAtUtc=now(), command=command,
                     cwd=str((cwd or self.source).relative_to(self.output)), status='RUNNING')
        self.manifest['checks'].append(check)
        self.save()
        print(f'START {name}', flush=True)
        started = time.monotonic()
        try:
            with log.open('wb') as stream:
                result = subprocess.run(command, cwd=cwd or self.source, env=self.env,
                                        stdout=stream, stderr=subprocess.STDOUT)
            check.update(exitCode=result.returncode, status='PASSED' if result.returncode == 0 else 'FAILED')
        except Exception as error:
            check.update(exitCode=None, status='FAILED', error=type(error).__name__)
            raise
        finally:
            check.update(finishedAtUtc=now(), durationSeconds=round(time.monotonic() - started, 3),
                         log=str(log.relative_to(self.output)), logSha256=sha256(log) if log.exists() else None)
            self.save()
        print(f'{check["status"]} {name}', flush=True)
        if result.returncode and required:
            raise RuntimeError(f'{name} failed; inspect {log}')

    def collect_backend_reports(self, name):
        for kind in ('surefire', 'failsafe'):
            directory = self.source / f'backend/target/{kind}-reports'
            if directory.exists():
                destination = self.output / 'reports' / name / kind
                destination.parent.mkdir(parents=True, exist_ok=True)
                shutil.copytree(directory, destination)
                self.manifest.setdefault('testSummaries', {})[f'{name}/{kind}'] = test_summary(destination)
        self.save()

    def ensure_fresh_project(self, project):
        ids = subprocess.check_output(['docker', 'ps', '-a', '-q', '--filter',
                                       f'label=com.docker.compose.project={project}'], text=True).strip()
        if ids:
            raise RuntimeError(f'Refusing to modify existing Compose project: {project}')

    def record_dependency_images(self, compose):
        for role in ('postgres', 'redis', 'rabbitmq'):
            container = subprocess.check_output(compose + ['ps', '-q', role], cwd=self.source,
                                                env=self.env, text=True).strip()
            identity = json.loads(subprocess.check_output(['docker', 'inspect', container], text=True))[0]['Image']
            info = json.loads(subprocess.check_output(['docker', 'image', 'inspect', identity], text=True))[0]
            self.manifest.setdefault('dependencyImages', {})[role] = dict(imageId=identity, repoDigests=info.get('RepoDigests', []))
        self.save()

    def containers(self, args):
        short = self.commit[:12]
        for role in ('backend', 'frontend'):
            tag = f'mealcheck-baseline-{role}:{short}'
            # Never overwrite a previously built candidate tag.
            exists = subprocess.run(['docker', 'image', 'inspect', tag], stdout=subprocess.DEVNULL,
                                    stderr=subprocess.DEVNULL).returncode == 0
            if exists:
                raise RuntimeError(f'Candidate tag already exists; retain it and use a fresh candidate: {tag}')
            self.run(f'build-{role}', ['docker', 'build', '--label', f'org.opencontainers.image.revision={self.commit}',
                                      '--label', f'org.opencontainers.image.version={args.version}',
                                      '-t', tag, str(self.source / role)])
            info = json.loads(subprocess.check_output(['docker', 'image', 'inspect', tag], text=True))[0]
            self.manifest['images'][role] = dict(tag=tag, imageId=info['Id'], repoDigests=info.get('RepoDigests', []),
                                                revision=info['Config']['Labels']['org.opencontainers.image.revision'])
            self.env[f'MEALCHECK_RELEASE_{role.upper()}_IMAGE'] = info['Id']
            self.save()
        self.run('retain-images', ['docker', 'image', 'save', '-o', str(self.output / 'images.tar'),
                                  *[image['imageId'] for image in self.manifest['images'].values()]])
        self.manifest['imageArchiveSha256'] = sha256(self.output / 'images.tar')
        self.save()
        capacity_project = 'mealcheck-capacity-acceptance'
        self.ensure_fresh_project(capacity_project)
        capacity = ['docker', 'compose', '-p', capacity_project, '-f', 'deploy/compose.acceptance.yml',
                    '-f', 'deploy/compose.release-capacity.yml']
        try:
            self.run('capacity-start', capacity + ['up', '--no-build', '-d', '--wait', '--wait-timeout', '150'])
            self.verify_running_image(capacity, 'backend')
            self.run('capacity-faults', [sys.executable, 'loadtests/capacity_acceptance.py', '--output',
                                       str(self.output / 'capacity'), '--fault-checks'])
        finally:
            self.run('capacity-service-logs', capacity + ['logs', '--no-color', '--tail', '200'], required=False)
            self.run('capacity-cleanup', capacity + ['down', '--volumes'])
        smoke_project = f'mealcheck-baseline-{short}'
        self.ensure_fresh_project(smoke_project)
        smoke = ['docker', 'compose', '-p', smoke_project, '--env-file', 'deploy/smoke.env',
                 '-f', 'docker-compose.prod.yml', '-f', 'deploy/compose.smoke.yml', '-f', 'deploy/compose.release-smoke.yml']
        self.run('production-compose', smoke + ['config', '--quiet'])
        try:
            self.run('production-start', smoke + ['up', '--no-build', '-d', '--wait', '--wait-timeout', '180'])
            self.record_dependency_images(smoke)
            self.verify_running_image(smoke, 'backend')
            self.verify_running_image(smoke, 'frontend')
            self.run('http-smoke', [sys.executable, 'deploy/scripts/smoke.py', '--base-url', 'http://127.0.0.1:18080'])
            self.run('stack-backup', [sys.executable, 'deploy/scripts/recovery.py', 'backup', '--project', smoke_project,
                                     '--env-file', 'deploy/smoke.env', '--compose', 'docker-compose.prod.yml',
                                     '--compose', 'deploy/compose.smoke.yml', '--compose', 'deploy/compose.release-smoke.yml',
                                     '--output', str(self.output / 'stack-backup')])
            self.run('stack-restore', [sys.executable, 'deploy/scripts/recovery.py', 'drill', '--backup',
                                      str(self.output / 'stack-backup'), '--output', str(self.output / 'stack-restored')])
            self.run('post-backup-ready', smoke + ['up', '--no-build', '-d', '--wait', '--wait-timeout', '180'])
            self.verify_running_image(smoke, 'backend')
            self.run('http-smoke-after-backup', [sys.executable, 'deploy/scripts/smoke.py', '--base-url', 'http://127.0.0.1:18080'])
        finally:
            self.run('production-service-logs', smoke + ['logs', '--no-color', '--tail', '200'], required=False)
            self.run('production-cleanup', smoke + ['down', '--volumes'])
        self.run('full-schema-restore', [sys.executable, 'deploy/scripts/recovery.py', 'self-test', '--migrations',
                                        'backend/src/main/resources/db/migration', '--output', str(self.output / 'full-schema')])
        self.run('alert-rules', ['docker', 'run', '--rm', '--entrypoint', 'promtool', '-v',
                                f'{self.source / "deploy/monitoring"}:/rules:ro', 'prom/prometheus:v3.5.0',
                                'check', 'rules', '/rules/mealcheck-alerts.yml'])

    def verify_running_image(self, compose, role):
        container = subprocess.check_output(compose + ['ps', '-q', role], cwd=self.source,
                                            env=self.env, text=True).strip()
        actual = json.loads(subprocess.check_output(['docker', 'inspect', container], text=True))[0]['Image']
        if actual != self.manifest['images'][role]['imageId']:
            raise RuntimeError(f'{role} acceptance container does not match candidate image')

    def finish(self, status):
        self.manifest.update(status=status, finishedAtUtc=now())
        self.manifest['evidenceHashes'] = {p.relative_to(self.output).as_posix(): sha256(p)
            for folder in ('logs', 'reports') for p in sorted((self.output / folder).rglob('*')) if p.is_file()}
        for relative in ('capacity/acceptance.json', 'stack-restored/verification.json', 'full-schema/restored/verification.json'):
            p = self.output / relative
            if p.exists():
                self.manifest['evidenceHashes'][relative] = sha256(p)
        self.save()
        (self.output / 'manifest.json.sha256').write_text(sha256(self.output / 'manifest.json') + '  manifest.json\n', encoding='ascii')
        print(f'MANIFEST {self.output / "manifest.json"}', flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--version', required=True)
    parser.add_argument('--output-name')
    parser.add_argument('--install-browser', action='store_true')
    parser.add_argument('--require-proxy', action='store_true')
    args = parser.parse_args()
    verification = Verification(args)
    try:
        mvn, npm = executable('mvn'), executable('npm')
        try:
            verification.run('backend-verify', [mvn, '--batch-mode', '--no-transfer-progress', 'verify', '-Pintegration'], verification.source / 'backend')
        finally:
            verification.collect_backend_reports('backend-verify')
        for zone in ('UTC', 'Asia/Shanghai'):
            name = 'timezone-' + zone.replace('/', '-')
            verification.run(name, [mvn, '-o', '-q', '-Dtest=WeeklyReportCoordinationMetricsTest',
                                   f'-DargLine=-Duser.timezone={zone}', 'test'], verification.source / 'backend')
        frontend = verification.source / 'frontend'
        verification.run('frontend-install', [npm, 'ci'], frontend)
        verification.run('frontend-tests', [npm, 'test'], frontend)
        verification.run('frontend-build', [npm, 'run', 'build'], frontend)
        if args.install_browser:
            verification.run('browser-install', [executable('npx'), 'playwright', 'install', '--with-deps', 'chromium'], frontend)
        verification.run('browser-acceptance', [npm, 'run', 'test:e2e'], frontend)
        shutil.copytree(frontend / 'playwright-report', verification.output / 'reports/browser')
        for name, directory, pattern in [('operations-contracts', 'tools', 'test_operations.py'),
                                          ('capacity-contracts', 'loadtests', 'test_capacity_acceptance.py'),
                                          ('model-stub-contracts', 'loadtests', 'test_model_stub.py')]:
            verification.run(name, [sys.executable, '-m', 'unittest', 'discover', '-s', directory, '-p', pattern, '-v'])
        nginx = shutil.which('nginx')
        if nginx:
            verification.run('trusted-proxy', [sys.executable, 'tools/test_proxy_headers.py', '--nginx', nginx])
        elif args.require_proxy:
            raise RuntimeError('Required Nginx executable unavailable')
        else:
            verification.run('trusted-proxy', ['docker', 'run', '--rm', '-v',
                             f'{verification.source}:/workspace', '-w', '/workspace', 'python:3.12-alpine',
                             'sh', '-c', 'apk add --no-cache nginx && python tools/test_proxy_headers.py --nginx /usr/sbin/nginx'])
        verification.containers(args)
        verification.finish('LOCAL_BASELINE_VERIFIED')
    except BaseException as error:
        verification.manifest['failure'] = str(error)
        verification.finish('FAILED')
        raise


if __name__ == '__main__':
    main()
