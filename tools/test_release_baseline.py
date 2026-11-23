"""Release export safety contracts; no Docker or model service is required."""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import zipfile

PROJECT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('release_baseline', PROJECT / 'deploy/scripts/release_baseline.py')
baseline = importlib.util.module_from_spec(spec)
spec.loader.exec_module(baseline)


class ReleaseExportTest(unittest.TestCase):
    def setUp(self):
        directory = PROJECT / 'logs/deploy/release-contract-fixtures'
        directory.mkdir(parents=True, exist_ok=True)
        self.temporary = tempfile.TemporaryDirectory(dir=directory)
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.git('init', '-q')
        self.write('.gitignore', 'logs/\n.env\n')
        self.write('backend/pom.xml', '<project xmlns="http://maven.apache.org/POM/4.0.0"><version>2.0.0</version></project>')
        self.write('frontend/package.json', json.dumps({'version': '2.0.0'}))
        self.write('frontend/package-lock.json', json.dumps({'version': '2.0.0', 'packages': {'': {'version': '2.0.0'}}}))
        for name in ('.env.production.example', 'docker-compose.prod.yml', 'deploy/compose.smoke.yml',
                     'deploy/compose.release-smoke.yml', 'deploy/compose.acceptance.yml', 'deploy/compose.release-capacity.yml'):
            self.write(name, '# public fixture\n')
        self.write('backend/src/main/resources/db/migration/V1__initial.sql', 'select 1;\n')
        self.git('add', '.')
        self.git('-c', 'user.name=Release contract', '-c', 'user.email=fixture@example.invalid',
                 '-c', 'commit.gpgsign=false', 'commit', '-qm', 'fixture')
        self.args = SimpleNamespace(version='2.0.0', output_name='fixture')

    def git(self, *args):
        return subprocess.check_output(['git', '-c', f'safe.directory={self.root.as_posix()}',
                                        '-C', str(self.root), *args], stderr=subprocess.STDOUT)

    def write(self, relative, content):
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding='utf-8')

    def export(self):
        with patch.object(baseline, 'ROOT', self.root):
            return baseline.Verification(self.args)

    def test_export_binds_the_committed_source_and_ignores_local_credentials(self):
        self.write('.env', 'PRIVATE_VALUE=local-only-fixture\n')
        exported = self.export()
        self.assertTrue(exported.output.is_relative_to(self.root / 'logs'))
        self.assertEqual(set(exported.manifest['componentVersions'].values()), {'2.0.0'})
        self.assertEqual(exported.manifest['gitCommit'], self.git('rev-parse', 'HEAD').decode().strip())
        with zipfile.ZipFile(exported.output / 'source.zip') as archive:
            self.assertIn('frontend/package.json', archive.namelist())
            self.assertNotIn('.env', archive.namelist())
        self.assertEqual(exported.manifest['sourceArchiveSha256'], baseline.sha256(exported.output / 'source.zip'))

    def test_modified_tracked_source_cannot_be_misrepresented_by_old_head(self):
        self.write('frontend/package.json', '{"version":"2.1.0"}')
        with self.assertRaisesRegex(RuntimeError, 'commit'):
            self.export()

    def test_required_untracked_source_cannot_be_silently_omitted(self):
        self.write('backend/src/main/java/NewService.java', 'class NewService {}')
        with self.assertRaisesRegex(RuntimeError, 'untracked'):
            self.export()

    def test_requested_version_must_match_all_components(self):
        self.args.version = '9.0.0'
        with self.assertRaisesRegex(RuntimeError, 'version must match'):
            self.export()
        self.assertFalse((self.root / baseline.EVIDENCE_DIRECTORY).exists())

    def test_lockfile_version_drift_is_rejected(self):
        self.write('frontend/package-lock.json', '{"version":"2.0.0","packages":{"":{"version":"1.0.0"}}}')
        self.git('add', '.')
        self.git('-c', 'user.name=Release contract', '-c', 'user.email=fixture@example.invalid',
                 '-c', 'commit.gpgsign=false', 'commit', '-qm', 'drift')
        with self.assertRaisesRegex(RuntimeError, 'version must match'):
            self.export()

    def test_path_traversal_cannot_write_evidence_outside_logs(self):
        self.args.output_name = '../../../outside'
        with self.assertRaisesRegex(RuntimeError, 'Evidence must stay'):
            self.export()
        self.assertFalse((self.root / 'outside').exists())

    def test_archived_acceptance_does_not_inherit_judge_or_future_provider_keys(self):
        keys = {'MEALCHECK_ASSISTANT_EVALUATION_JUDGE_API_KEY': 'judge-fixture-only',
                'MEALCHECK_FUTURE_PROVIDER_API_KEY': 'future-fixture-only'}
        with patch.dict(os.environ, keys):
            exported = self.export()
            for key, value in keys.items():
                self.assertEqual(exported.env[key], '')
                self.assertEqual(os.environ[key], value)

    def test_existing_evidence_is_never_overwritten(self):
        exported = self.export()
        before = (exported.output / 'manifest.json').read_bytes()
        with self.assertRaises(FileExistsError):
            self.export()
        self.assertEqual(before, (exported.output / 'manifest.json').read_bytes())

    def test_both_cluster_checks_must_verify_the_same_candidate_jar(self):
        exported = self.export()
        jar = exported.source / 'backend/target/mealcheck-backend.jar'
        jar.parent.mkdir(parents=True)
        jar.write_bytes(b'fixed-test-artifact')
        def fake_run(name, command):
            self.assertEqual(command[command.index('--jar') + 1], str(jar))
            output = Path(command[command.index('--output') + 1]); output.mkdir()
            (output / 'verification.json').write_text(json.dumps({'status': 'PASSED', 'jarSha256': baseline.sha256(jar)}))
        with patch.object(exported, 'run', side_effect=fake_run) as run:
            baseline.verify_clusters(exported)
        self.assertEqual([call.args[0] for call in run.call_args_list], ['assistant-cluster', 'knowledge-cluster'])
        self.assertEqual(exported.manifest['verifiedBackendJarSha256'], baseline.sha256(jar))

    def test_passing_cluster_report_for_a_different_jar_is_rejected(self):
        exported = self.export()
        jar = exported.source / 'backend/target/mealcheck-backend.jar'
        jar.parent.mkdir(parents=True); jar.write_bytes(b'candidate')
        def fake_run(name, command):
            output = Path(command[command.index('--output') + 1]); output.mkdir()
            (output / 'verification.json').write_text(json.dumps({'status': 'PASSED', 'jarSha256': 'different-artifact'}))
        with patch.object(exported, 'run', side_effect=fake_run) as run:
            with self.assertRaisesRegex(RuntimeError, 'fixed candidate JAR'):
                baseline.verify_clusters(exported)
        self.assertEqual(run.call_count, 1)

    def test_missing_or_fully_skipped_database_tests_are_not_acceptance(self):
        with self.assertRaisesRegex(RuntimeError, 'No database integration'):
            baseline.require_database_tests(self.root)
        self.write('backend/target/failsafe-reports/TEST-ExampleDatabaseIntegrationTest.xml',
                   '<testsuite tests="5" skipped="5" failures="0" errors="0"/>')
        with self.assertRaisesRegex(RuntimeError, 'No database integration'):
            baseline.require_database_tests(self.root)

    def test_executed_database_tests_and_errors_are_read_from_actual_reports(self):
        report = 'backend/target/surefire-reports/TEST-ExampleDatabaseIntegrationTest.xml'
        self.write(report, '<testsuite tests="5" skipped="1" failures="0" errors="0"/>')
        self.assertEqual(baseline.require_database_tests(self.root)['executedDatabaseTests'], 4)
        self.write(report, '<testsuite tests="5" skipped="1" failures="0" errors="1"/>')
        with self.assertRaisesRegex(RuntimeError, 'failures or errors'):
            baseline.require_database_tests(self.root)


if __name__ == '__main__':
    unittest.main()
