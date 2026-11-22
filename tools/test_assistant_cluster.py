import importlib.util
from pathlib import Path
from types import SimpleNamespace
from unittest import TestCase, main
from unittest.mock import Mock

spec=importlib.util.spec_from_file_location('assistant_cluster',Path(__file__).resolve().parents[1]/'deploy/scripts/verify_assistant_cluster.py')
cluster=importlib.util.module_from_spec(spec);spec.loader.exec_module(cluster)


class ClusterSafetyTest(TestCase):
    def test_existing_namespace_is_rejected_before_any_mutation(self):
        run=object.__new__(cluster.Acceptance)
        run.project='mealcheck-assistant-cluster-test'
        run.created=False
        run.command=Mock(return_value=SimpleNamespace(stdout='existing-container'))
        with self.assertRaises(RuntimeError):run.start()
        self.assertFalse(run.created)
        self.assertEqual(run.command.call_count,1)
        self.assertIn('ps',run.command.call_args.args[0])


if __name__=='__main__':main()
