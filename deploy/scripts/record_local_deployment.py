"""Record local runtime identities and passed evidence without reading secrets."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import subprocess
import recovery


def record(output, verification, restore):
    root = Path(__file__).resolve().parents[2]
    output.mkdir(parents=True, exist_ok=True)
    proof = json.loads(verification.read_text(encoding='utf-8'))
    restored = json.loads(restore.read_text(encoding='utf-8'))
    if proof.get('passed') is not True or restored.get('passed') is not True:
        raise RuntimeError('Deployment and restore evidence must both pass')
    names = ['mealcheck-backend', 'mealcheck-frontend', 'mealcheck-postgres',
             'mealcheck-redis', 'mealcheck-fullstack-rabbitmq-1']
    containers = {}
    for name in names:
        state = recovery.inspect(name)
        if not state['State']['Running'] or state['State'].get('Health', {}).get('Status') != 'healthy':
            raise RuntimeError('Container is not healthy: ' + name)
        containers[name] = {'imageTag': state['Config']['Image'], 'imageId': state['Image'],
                            'health': state['State']['Health']['Status'],
                            'ports': state['NetworkSettings']['Ports']}
    files = []
    for directory in ['backend/src', 'frontend/src', 'frontend/e2e', 'frontend/test', 'deploy/scripts']:
        files.extend(path for path in (root / directory).rglob('*')
                     if path.is_file() and '__pycache__' not in path.parts)
    files.extend(root / name for name in ['backend/pom.xml', 'backend/Dockerfile', 'frontend/package.json',
                  'frontend/package-lock.json', 'frontend/Dockerfile', 'frontend/nginx.conf',
                  'docker-compose.yml', 'deploy/compose.local.yml', 'README.md', 'deploy/OPTIONAL_NUTRITION.md', 'deploy/MEAL_JOURNAL.md', 'deploy/CODE_OPTIMIZATIONS.md'])
    hashes = {path.relative_to(root).as_posix(): recovery.digest(path) for path in sorted(set(files))}
    # Check that the actual running backend contains the checked migration scripts.
    packaged = output / 'backend-app.jar'
    recovery.run(['docker', 'cp', 'mealcheck-backend:/app/app.jar', str(packaged)])
    import zipfile
    with zipfile.ZipFile(packaged) as jar:
        for migration in (root / 'backend/src/main/resources/db/migration').glob('*.sql*'):
            content = jar.read('BOOT-INF/classes/db/migration/' + migration.name)
            if hashlib.sha256(content).hexdigest() != recovery.digest(migration):
                raise RuntimeError('Running migration differs from source: ' + migration.name)
    git = subprocess.run(['git', '-c', 'safe.directory=' + root.as_posix(), 'rev-parse', 'HEAD'],
                         cwd=root, capture_output=True, check=True, text=True).stdout.strip()
    result = {'finishedAtUtc': datetime.now(timezone.utc).isoformat(), 'scope': 'local-docker-working-tree',
              'baseCommit': git, 'sourceKind': 'uncommitted working tree, not a formal release baseline',
              'sourceFileSha256': hashes, 'containers': containers, 'packagedMigrationsMatchSource': True,
              'backendJarSha256': recovery.digest(packaged),
              'databaseVersion': recovery.query('mealcheck-postgres', 'SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1'),
              'users': int(recovery.query('mealcheck-postgres', 'SELECT count(*) FROM users')),
              'meals': int(recovery.query('mealcheck-postgres', 'SELECT count(*) FROM meal_records')),
              'deploymentVerification': {'path': str(verification), 'sha256': recovery.digest(verification), 'passed': True},
              'backupRestoreVerification': {'path': str(restore), 'sha256': recovery.digest(restore), 'passed': True},
              'limitations': ['Local deployment only. Real model quality and billing are not verified.',
                              'Registry publication, HTTPS and full release-baseline acceptance were not performed.']}
    target = output / 'manifest.json'
    if target.exists():
        raise RuntimeError('Refusing to overwrite an existing deployment manifest')
    target.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    (output / 'manifest.sha256').write_text(recovery.digest(target) + '  manifest.json\n', encoding='ascii')
    print(json.dumps({'manifest': str(target), 'databaseVersion': result['databaseVersion'],
                      'users': result['users'], 'meals': result['meals'], 'healthyContainers': len(containers)}))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--verification', type=Path, required=True)
    parser.add_argument('--restore', type=Path, required=True)
    args = parser.parse_args()
    record(args.output, args.verification, args.restore)
