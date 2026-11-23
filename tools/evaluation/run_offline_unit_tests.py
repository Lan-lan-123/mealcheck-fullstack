"""Run evaluation utility tests with temporary files inside the project logs tree."""
import argparse
from datetime import datetime
import tempfile
import unittest

from prepare_knowledge_longform import ROOT


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--pattern', default='test_*.py')
    args = parser.parse_args()
    task_temp_root = ROOT / 'logs/backend' / ('python-test-temp-' + datetime.now().strftime('%Y%m%d-%H%M%S-%f'))
    if not task_temp_root.resolve().is_relative_to((ROOT / 'logs').resolve()):
        raise ValueError('Test temporary files must stay under logs/')
    task_temp_root.mkdir(parents=True, exist_ok=False)
    tempfile.tempdir = str(task_temp_root)
    suite = unittest.defaultTestLoader.discover(str(ROOT / 'tools/evaluation'), pattern=args.pattern)
    result = unittest.TextTestRunner(verbosity=1).run(suite)
    raise SystemExit(0 if result.wasSuccessful() else 1)
