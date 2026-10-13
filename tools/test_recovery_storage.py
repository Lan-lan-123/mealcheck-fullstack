"""Ensure a local archive cannot claim to contain external image objects."""
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

root = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('recovery', root / 'deploy/scripts/recovery.py')
recovery = importlib.util.module_from_spec(spec)
spec.loader.exec_module(recovery)


class RecoveryStorageTest(unittest.TestCase):
    def test_object_images_fail_before_any_archive_is_created(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / 'backup'
            with patch.object(recovery, 'image_paths', return_value=['oss://private-bucket/mealcheck/user-1/image.jpg']):
                with self.assertRaisesRegex(RuntimeError, 'object-store backup'):
                    recovery.capture('unused', Path(temporary), output)
            self.assertFalse(output.exists())

    def test_referenced_queue_payloads_also_require_storage_aware_backup(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / 'backup'
            with patch.object(recovery, 'image_paths', return_value=[]), patch.object(recovery, 'query', side_effect=['1', '2']):
                with self.assertRaisesRegex(RuntimeError, 'storage-aware backup'):
                    recovery.capture('unused', Path(temporary), output)
            self.assertFalse(output.exists())


if __name__ == '__main__':
    unittest.main()
