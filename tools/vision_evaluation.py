"""Evaluate an independently annotated image dataset against the production recognizer.
No model request is issued without --run; predictions can be scored offline.
"""
import argparse
import datetime as dt
import hashlib
import json
import math
import mimetypes
import os
from pathlib import Path
import re
import time
import unicodedata
from urllib.request import Request, urlopen
import uuid

CATEGORIES = {'staple','protein','vegetable','fruit','dairy','soup','drink','dessert','fried','oily','other'}


def normalized(name): return re.sub(r'\s+', '', unicodedata.normalize('NFKC', name).casefold())


def load_dataset(path, allow_synthetic=False):
    dataset = json.loads(path.read_text(encoding='utf-8'))
    kind = dataset.get('datasetKind', 'real')
    if kind not in {'real', 'synthetic'}: raise ValueError('Unknown dataset kind')
    synthetic = kind == 'synthetic'
    if synthetic and not allow_synthetic: raise ValueError('Synthetic samples require --allow-synthetic and are regression-only')
    if (not synthetic and dataset.get('independentlyAnnotated') is not True) or dataset.get('authorizedForEvaluation') is not True:
        raise ValueError('Confirm independent annotation and evaluation authorization')
    if not dataset.get('version') or not dataset.get('cases'): raise ValueError('A version and nonempty labeled dataset are required')
    root = path.parent.resolve(); ids = set(); fixtures = []
    for case in dataset['cases']:
        if case.get('source') == 'ai-generated' and not synthetic: raise ValueError('AI-generated images must be declared as a synthetic dataset')
        if not re.fullmatch(r'[A-Za-z0-9_-]{1,80}', case['id']) or case['id'] in ids: raise ValueError('Invalid/duplicate public case ID')
        ids.add(case['id'])
        image = (root / case['image']).resolve()
        if not image.is_relative_to(root) or not image.is_file(): raise ValueError('Image must exist within the dataset directory')
        digest = hashlib.sha256(image.read_bytes()).hexdigest()
        if digest != case['sha256']: raise ValueError('Image checksum mismatch')
        if not isinstance(case['foodImage'], bool) or case['foodImage'] != bool(case['foods']): raise ValueError('Food images require food labels; non-food images require none')
        for food in case['foods']:
            if not food['name'].strip() or food['category'] not in CATEGORIES: raise ValueError('Invalid food label')
        fixtures.append({key: case[key] for key in ['id','sha256','foodImage','foods']})
    fingerprint = hashlib.sha256(json.dumps(sorted(fixtures,key=lambda item:item['id']), sort_keys=True, ensure_ascii=False, separators=(',', ':')).encode()).hexdigest()
    return dataset, fingerprint


def validate_quality_targets(dataset, targets):
    if dataset.get('datasetKind') == 'synthetic' and targets:
        raise ValueError('Synthetic regression samples cannot be used for release quality thresholds')


def score(case, prediction):
    expected = case['foods']; recognition = prediction.get('recognition', {})
    foods = recognition.get('foods') or []; used = set(); matched = correct = 0; missing = []; category_errors = []
    if not recognition.get('foodImage', True): foods = []
    for food in expected:
        names = {normalized(name) for name in [food['name']] + food.get('aliases', [])}
        index = next((i for i, found in enumerate(foods) if i not in used and normalized(found.get('name','')) in names), None)
        if index is None: missing.append(food['name']); continue
        used.add(index); matched += 1; correct += int(foods[index].get('category') == food['category'])
        if foods[index].get('category') != food['category']:
            category_errors.append({'name': food['name'], 'expected': food['category'], 'actual': foods[index].get('category')})
    return {'id': case['id'], 'expected': len(expected), 'predicted': len(foods), 'matched': matched, 'categoryCorrect': correct,
            'missing': missing, 'extra': [food.get('name','') for i, food in enumerate(foods) if i not in used],
            'categoryErrors': category_errors,
            'foodImageCorrect': 'recognition' in prediction and recognition.get('foodImage', True) == case['foodImage'],
            'durationMs': prediction.get('durationMs'), 'usage': prediction.get('usage'), 'error': prediction.get('error')}


def summarize(cases):
    total = lambda key: sum(case[key] for case in cases)
    expected, predicted, matched = total('expected'), total('predicted'), total('matched')
    times = sorted(row['durationMs'] for row in cases if row['durationMs'] is not None)
    known = [row['usage']['estimatedCost'] for row in cases if row.get('usage') and row['usage'].get('estimatedCost') is not None]
    return {'cases': len(cases), 'failures': sum(bool(row['error']) for row in cases),
            'precision': matched / predicted if predicted else None, 'recall': matched / expected if expected else None,
            'missedFoods': expected - matched, 'extraFoods': predicted - matched,
            'categoryAccuracy': total('categoryCorrect') / matched if matched else None,
            'foodImageAccuracy': total('foodImageCorrect') / len(cases),
            'p95DurationMs': times[math.ceil(len(times) * .95) - 1] if times else None,
            'latencyMeasuredCases': len(times),
            'estimatedCost': sum(known) if len(known) == len(cases) else None, 'knownCostSubtotal': sum(known), 'unknownCostCases': len(cases) - len(known)}


def predict(path, base, token):
    boundary = uuid.uuid4().hex
    content = path.read_bytes(); mime = mimetypes.guess_type(path.name)[0] or 'application/octet-stream'
    data = (f'--{boundary}\r\nContent-Disposition: form-data; name="image"; filename="evaluation-image"\r\nContent-Type: {mime}\r\n\r\n').encode() + content + f'\r\n--{boundary}--\r\n'.encode()
    request = Request(base.rstrip('/') + '/api/admin/vision-evaluation/recognize', data=data,
                      headers={'Authorization': 'Bearer ' + token, 'Content-Type': 'multipart/form-data; boundary=' + boundary})
    with urlopen(request, timeout=180) as response: return json.load(response)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--dataset', type=Path, required=True)
    parser.add_argument('--predictions', type=Path)
    parser.add_argument('--run', action='store_true')
    parser.add_argument('--allow-synthetic', action='store_true', help='Explicitly allow synthetic regression samples; never a real-image release gate')
    parser.add_argument('--base-url')
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--min-recall', type=float)
    parser.add_argument('--min-precision', type=float)
    parser.add_argument('--min-category-accuracy', type=float)
    args = parser.parse_args()
    targets = {'recall': args.min_recall, 'precision': args.min_precision, 'categoryAccuracy': args.min_category_accuracy}
    targets = {key: value for key, value in targets.items() if value is not None}
    if any(not 0 <= value <= 1 for value in targets.values()): raise ValueError('Quality thresholds must be in [0,1]')
    dataset, fingerprint = load_dataset(args.dataset, args.allow_synthetic)
    validate_quality_targets(dataset, targets)
    if args.run == bool(args.predictions): raise ValueError('Choose --run or --predictions')
    predictions = json.loads(args.predictions.read_text(encoding='utf-8')) if args.predictions else {}
    rows, models = [], set()
    for case in dataset['cases']:
        if args.run:
            if not args.base_url or not os.environ.get('MEALCHECK_EVALUATION_TOKEN'): raise ValueError('Set base URL and private admin token environment')
            started=time.monotonic()
            try: prediction = predict((args.dataset.parent / case['image']).resolve(), args.base_url, os.environ['MEALCHECK_EVALUATION_TOKEN'])
            except Exception as error: prediction = {'error': type(error).__name__, 'durationMs': round((time.monotonic()-started)*1000)}
        else: prediction = predictions.get(case['id'], {'error': 'MissingPrediction'})
        models.add(prediction.get('model', 'unknown')); rows.append(score(case, prediction))
    report = {'runId': uuid.uuid4().hex, 'createdAtUtc': dt.datetime.now(dt.timezone.utc).isoformat(), 'datasetVersion': dataset['version'],
              'datasetKind': dataset.get('datasetKind', 'real'), 'independentlyAnnotated': dataset.get('independentlyAnnotated') is True,
              'qualityScope': 'synthetic-regression-only' if dataset.get('datasetKind') == 'synthetic' else 'independently-annotated-real-images',
              'datasetFingerprint': fingerprint, 'models': sorted(models), 'metrics': summarize(rows), 'cases': rows}
    passed = all(report['metrics'][key] is not None and report['metrics'][key] >= value for key, value in targets.items()) if targets else None
    report['qualityGate'] = {'targets': targets, 'passed': passed, 'releaseEligible': dataset.get('datasetKind', 'real') == 'real'}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open('x', encoding='utf-8') as stream: json.dump(report, stream, ensure_ascii=False, indent=2)
    if os.name != 'nt': args.output.chmod(0o600)
    print(json.dumps(report['metrics']))
    if report['metrics']['failures']: raise SystemExit(1)
    if passed is False: raise SystemExit(2)


if __name__ == '__main__': main()
