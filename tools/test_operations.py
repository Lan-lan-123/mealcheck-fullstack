import importlib.util
import hashlib
import json
from pathlib import Path
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    loaded = importlib.util.module_from_spec(spec); spec.loader.exec_module(loaded); return loaded


root = Path(__file__).resolve().parents[1]
vision = module('vision', root / 'tools/vision_evaluation.py')
comparison = module('comparison', root / 'tools/compare_vision_evaluations.py')
notification = module('notification', root / 'deploy/monitoring/notification.py')


class EvaluationTest(unittest.TestCase):
    def test_missing_extra_duplicate_and_wrong_category_are_counted(self):
        case = {'id': 'meal1', 'foodImage': True, 'foods': [{'name': '米饭', 'category': 'staple', 'aliases': ['rice']}, {'name': '青菜', 'category': 'vegetable'}]}
        row = vision.score(case, {'recognition': {'foodImage': True, 'foods': [{'name': ' RICE ', 'category': 'protein'}, {'name': 'rice', 'category': 'staple'}]}})
        self.assertEqual(row['matched'], 1); self.assertEqual(row['categoryCorrect'], 0)
        self.assertEqual(row['missing'], ['青菜']); self.assertEqual(row['extra'], ['rice'])
        summary = vision.summarize([row])
        self.assertEqual(summary['recall'], .5); self.assertEqual(summary['precision'], .5)
        self.assertIsNone(summary['estimatedCost']); self.assertEqual(summary['unknownCostCases'], 1)

    def test_failed_predictions_do_not_become_perfect_nonfood_results(self):
        row = vision.score({'id': 'nonfood', 'foodImage': False, 'foods': []}, {'error': 'TimeoutError'})
        self.assertFalse(row['foodImageCorrect']); self.assertEqual(vision.summarize([row])['failures'], 1)

    def test_empty_or_unauthorized_dataset_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'dataset.json'
            path.write_text(json.dumps({'version': 'v1', 'independentlyAnnotated': True, 'authorizedForEvaluation': True, 'cases': []}))
            with self.assertRaises(ValueError): vision.load_dataset(path)

    def test_synthetic_samples_require_explicit_opt_in_and_keep_annotation_status(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'dataset.json'
            image = Path(directory) / 'sample.png'
            image.write_bytes(b'synthetic-fixture')
            dataset = {'version': 'synthetic-v1', 'datasetKind': 'synthetic', 'authorizedForEvaluation': True, 'independentlyAnnotated': False,
                       'cases': [{'id': 'synth1', 'image': 'sample.png', 'sha256': hashlib.sha256(image.read_bytes()).hexdigest(),
                                  'source': 'ai-generated', 'foodImage': False, 'foods': []}]}
            path.write_text(json.dumps(dataset))
            with self.assertRaises(ValueError): vision.load_dataset(path)
            loaded, fingerprint = vision.load_dataset(path, allow_synthetic=True)
            self.assertFalse(loaded['independentlyAnnotated']); self.assertEqual(len(fingerprint), 64)
            dataset['authorizedForEvaluation'] = False; path.write_text(json.dumps(dataset))
            with self.assertRaises(ValueError): vision.load_dataset(path, allow_synthetic=True)

    def test_synthetic_opt_in_never_bypasses_real_labels_or_release_thresholds(self):
        with self.assertRaises(ValueError): vision.validate_quality_targets({'datasetKind': 'synthetic'}, {'recall': .9})
        vision.validate_quality_targets({'datasetKind': 'synthetic'}, {})
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'dataset.json'
            path.write_text(json.dumps({'version': 'real-v1', 'datasetKind': 'real', 'authorizedForEvaluation': True, 'independentlyAnnotated': False, 'cases': []}))
            with self.assertRaises(ValueError): vision.load_dataset(path, allow_synthetic=True)

    def test_real_and_synthetic_reports_are_not_comparable(self):
        report = {'runId': 'base', 'datasetFingerprint': 'same', 'datasetKind': 'real', 'independentlyAnnotated': True, 'cases': [],
                  'metrics': dict.fromkeys(['precision', 'recall', 'categoryAccuracy', 'foodImageAccuracy', 'p95DurationMs', 'estimatedCost'], 1)}
        candidate = {**report, 'runId': 'candidate', 'datasetKind': 'synthetic', 'independentlyAnnotated': False}
        self.assertFalse(comparison.compare(report, candidate)['comparable'])
        self.assertTrue(all(value is None for value in comparison.compare(report, candidate)['metricDelta'].values()))


class NotificationTest(unittest.TestCase):
    def test_real_http_delivery_and_provider_rejection(self):
        received = []
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args): pass
            def do_POST(self):
                received.append(json.loads(self.rfile.read(int(self.headers['Content-Length']))))
                self.send_response(200); self.end_headers()
                self.wfile.write(json.dumps({'errcode': 0 if len(received) == 1 else 1}).encode())
        server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
        event = {'status': 'firing', 'alerts': [{'labels': {'severity': 'critical'}, 'annotations': {'summary': '备份失败'}}], 'private': 'must not be delivered'}
        try:
            target = f'http://127.0.0.1:{server.server_port}'
            notification.deliver(event, target, 'wecom')
            self.assertIn('备份失败', received[0]['text']['content']); self.assertNotIn('private', str(received[0]))
            with self.assertRaises(RuntimeError): notification.deliver(event, target, 'wecom')
        finally: server.shutdown(); server.server_close(); thread.join()


if __name__ == '__main__': unittest.main()
