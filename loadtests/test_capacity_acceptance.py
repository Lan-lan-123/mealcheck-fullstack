import importlib.util
import unittest
from pathlib import Path
from unittest.mock import patch
from types import SimpleNamespace
import json

spec = importlib.util.spec_from_file_location('acceptance', Path(__file__).with_name('capacity_acceptance.py'))
acceptance = importlib.util.module_from_spec(spec)
spec.loader.exec_module(acceptance)


class AcceptanceSafetyTest(unittest.TestCase):
    def test_non_fixture_stack_is_rejected_before_seeding_or_faults(self):
        container = {'Id': 'fake-backend', 'Config': {'Labels': {'com.docker.compose.project': 'production', 'com.docker.compose.service': 'backend'}, 'Env': []}}
        replies = [SimpleNamespace(stdout='a b', returncode=0), SimpleNamespace(stdout=json.dumps([container]), returncode=0)]
        with patch.object(acceptance, 'command', side_effect=replies):
            with self.assertRaisesRegex(RuntimeError, 'non-acceptance'): acceptance.verify_stack()

    def test_wrong_provider_key_is_rejected_even_with_matching_namespace(self):
        container = {'Id': 'fake-backend', 'Config': {'Labels': {'com.docker.compose.project': acceptance.PROJECT, 'com.docker.compose.service': 'backend'}, 'Env': ['MEALCHECK_AI_API_KEY=real-key']}}
        replies = [SimpleNamespace(stdout='a b', returncode=0), SimpleNamespace(stdout=json.dumps([container]), returncode=0)]
        with patch.object(acceptance, 'command', side_effect=replies):
            with self.assertRaisesRegex(RuntimeError, 'outside the model fixture'): acceptance.verify_stack()

    def test_queue_percentile_uses_phase_deltas_not_old_samples(self):
        before = 'mealcheck_analysis_queue_wait_seconds_bucket{le="1.0"} 100\nmealcheck_analysis_queue_wait_seconds_bucket{le="2.0"} 100\nmealcheck_analysis_queue_wait_seconds_bucket{le="+Inf"} 100'
        after = 'mealcheck_analysis_queue_wait_seconds_bucket{le="1.0"} 100\nmealcheck_analysis_queue_wait_seconds_bucket{le="2.0"} 120\nmealcheck_analysis_queue_wait_seconds_bucket{le="+Inf"} 120'
        self.assertEqual(acceptance.queue_p95(before, after), 2)
        self.assertIsNone(acceptance.queue_p95(before, before))


if __name__ == '__main__': unittest.main()
