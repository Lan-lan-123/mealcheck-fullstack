"""Locations supplied by CI or release_baseline after regenerating offline fixtures."""
import os
from pathlib import Path


def fixture_path(variable):
    value = os.environ.get(variable)
    if not value or not Path(value).exists():
        raise ValueError(f'{variable} is required; run tools/evaluation/prepare_ci_fixtures.py after compiling the backend')
    return Path(value)
