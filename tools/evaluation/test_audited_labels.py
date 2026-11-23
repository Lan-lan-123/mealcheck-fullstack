import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('builder', Path(__file__).with_name('build_audited_labels.py'))
builder = importlib.util.module_from_spec(spec)
spec.loader.exec_module(builder)


class AuditedLabelsTest(unittest.TestCase):
    def fixture(self, folder):
        image = folder / 'sample.png'
        image.write_bytes(b'synthetic-test')
        base = folder / 'dataset.json'
        data = {'version': 'v1', 'datasetKind': 'synthetic', 'independentlyAnnotated': False,
                'cases': [{'id': 'synth-001', 'image': 'sample.png', 'sha256': hashlib.sha256(image.read_bytes()).hexdigest(),
                           'foodImage': True, 'foods': [{'name': '馒头', 'category': 'staple'}]}]}
        base.write_text(json.dumps(data), encoding='utf-8')
        review = {'baseDatasetSha256': hashlib.sha256(base.read_bytes()).hexdigest(), 'baseVersion': 'v1', 'version': 'v2',
                  'reviewer': 'assistant, not human', 'reviewDate': '2026-10-16',
                  'groups': [{'first': 1, 'last': 1, 'observation': 'visible pleats',
                              'updates': [{'originalName': '馒头', 'name': '包子', 'aliases': []}]}]}
        return base, review

    def test_revision_preserves_base_hash_and_never_claims_human_annotation(self):
        with tempfile.TemporaryDirectory() as path:
            base, review = self.fixture(Path(path))
            before = base.read_bytes()
            result, changes = builder.build(base, review)
            self.assertEqual(base.read_bytes(), before)
            self.assertFalse(result['independentlyAnnotated'])
            self.assertEqual(result['cases'][0]['foods'][0]['name'], '包子')
            self.assertNotIn('human-reviewed', result['cases'][0]['annotationStatus'])
            self.assertEqual(changes['changes'][0]['before'][0]['name'], '馒头')
            with self.assertRaises(ValueError):
                builder.save_separate(base, result, base)

    def test_changed_base_or_image_and_partial_duplicate_reviews_are_rejected(self):
        with tempfile.TemporaryDirectory() as path:
            base, review = self.fixture(Path(path))
            (Path(path) / 'sample.png').write_bytes(b'changed')
            with self.assertRaises(ValueError):
                builder.build(base, review)
            with self.assertRaises(ValueError):
                builder.build(base, {**review, 'groups': []}, verify_images=False)
            with self.assertRaises(ValueError):
                builder.build(base, {**review, 'groups': review['groups'] * 2}, verify_images=False)
            with self.assertRaises(ValueError):
                builder.build(base, {**review, 'baseDatasetSha256': 'wrong'}, verify_images=False)

    def test_existing_different_revision_cannot_be_overwritten(self):
        with tempfile.TemporaryDirectory() as path:
            base, review = self.fixture(Path(path))
            result, _ = builder.build(base, review)
            output = Path(path) / 'revision.json'
            builder.save_separate(output, result, base)
            builder.save_separate(output, result, base)
            with self.assertRaises(FileExistsError):
                builder.save_separate(output, {**result, 'version': 'v3'}, base)


if __name__ == '__main__':
    unittest.main()
