import sys
from pathlib import Path
from unittest import TestCase, main
from unittest.mock import patch, MagicMock

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'deploy' / 'scripts'))
import operations_backup


class BackupLifecycleMetricsTest(TestCase):
    def test_success_reports_artifact_size_without_replacing_other_metrics(self):
        with patch.object(operations_backup, 'urlopen', return_value=MagicMock()) as send:
            operations_backup.metrics('backup', True, 1.5, 12345)
        request = send.call_args.args[0]
        self.assertEqual(request.method, 'POST')
        self.assertIn('mealcheck_backup_artifact_bytes 12345', request.data.decode())
        self.assertIn('mealcheck_backup_last_success_timestamp_seconds', request.data.decode())

    def test_failure_does_not_overwrite_last_successful_size_or_timestamp(self):
        with patch.object(operations_backup, 'urlopen', return_value=MagicMock()) as send:
            operations_backup.metrics('backup', False, 2)
        body = send.call_args.args[0].data.decode()
        self.assertIn('mealcheck_backup_success 0', body)
        self.assertNotIn('artifact_bytes', body)
        self.assertNotIn('last_success', body)


if __name__ == '__main__':
    main()
