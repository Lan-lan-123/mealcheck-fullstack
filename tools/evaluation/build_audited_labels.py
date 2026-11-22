"""Build an explicitly non-independent synthetic label revision; never changes the base dataset."""
import argparse
from copy import deepcopy
import hashlib
import json
from pathlib import Path


def build(base, review, verify_images=True):
    raw = base.read_bytes()
    if hashlib.sha256(raw).hexdigest() != review['baseDatasetSha256']:
        raise ValueError('Base dataset hash changed; review revisions against the new base first')
    original = json.loads(raw)
    if original['version'] != review['baseVersion'] or original.get('datasetKind') != 'synthetic':
        raise ValueError('Review must target its declared synthetic base version')
    result = deepcopy(original)
    by_id = {case['id']: case for case in result['cases']}
    if len(by_id) != len(result['cases']):
        raise ValueError('Duplicate case IDs')
    seen, changes = set(), []
    for group in review['groups']:
        for index in range(group['first'], group['last'] + 1):
            case_id = f'synth-{index:03}'
            if case_id in seen or case_id not in by_id:
                raise ValueError('Each existing case must have exactly one review')
            seen.add(case_id)
            case = by_id[case_id]
            if verify_images:
                image = (base.parent / case['image']).resolve()
                if not image.is_relative_to(base.parent.resolve()):
                    raise ValueError('Image path escapes dataset directory')
                if hashlib.sha256(image.read_bytes()).hexdigest() != case['sha256']:
                    raise ValueError('Reviewed image hash changed: ' + case_id)
            before = deepcopy(case['foods'])
            for update in group.get('updates', []):
                matching = [f for f in case['foods'] if f['name'] == update['originalName']]
                if len(matching) != 1:
                    raise ValueError('Revision must match exactly one original food: ' + case_id)
                food = matching[0]
                food['name'] = update.get('name', food['name'])
                aliases = update.get('aliases', food.get('aliases', [])) + update.get('aliasesAdd', [])
                if any(not isinstance(a, str) or not a.strip() for a in aliases):
                    raise ValueError('Invalid alias')
                aliases = list(dict.fromkeys(a for a in aliases if a != food['name']))
                if aliases:
                    food['aliases'] = aliases
                else:
                    food.pop('aliases', None)
            case['annotationStatus'] = 'assistant-visual-audited-not-independent'
            case['labelReview'] = {'reviewer': review['reviewer'], 'reviewDate': review['reviewDate'],
                                  'observation': group['observation'], 'uncertainties': group.get('uncertainties', [])}
            if before != case['foods']:
                changes.append({'id': case_id, 'before': before, 'after': deepcopy(case['foods']),
                                'reason': group['observation']})
    if seen != set(by_id):
        raise ValueError('Every case requires a review; partial review cannot be exported as complete')
    result.update(version=review['version'], datasetKind='synthetic', independentlyAnnotated=False,
                  reviewMethod='assistant visual audit; not independent human annotation',
                  baseVersion=original['version'], baseDatasetSha256=review['baseDatasetSha256'])
    change_log = {'version': review['version'], 'baseVersion': original['version'],
                  'baseDatasetSha256': review['baseDatasetSha256'], 'reviewedCases': len(seen),
                  'changedCases': len(changes), 'independentlyAnnotated': False, 'changes': changes}
    return result, change_log


def save_separate(path, value, base):
    if path.resolve() == base.resolve() or path.parent.resolve() != base.parent.resolve():
        raise ValueError('Save a separate revision beside the base to preserve relative image paths')
    serialized = json.dumps(value, ensure_ascii=False, indent=2) + '\n'
    if path.exists() and path.read_text(encoding='utf-8') != serialized:
        raise FileExistsError('Existing revision differs; use a new version/file instead of overwriting')
    path.write_text(serialized, encoding='utf-8')


def main():
    root = Path(__file__).resolve().parents[2]
    folder = root / 'tools/datasets/synthetic-meals-100'
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base', type=Path, default=folder / 'dataset.json')
    parser.add_argument('--review', type=Path, default=folder / 'label-audit-v2.json')
    parser.add_argument('--output', type=Path, default=folder / 'dataset.assistant-reviewed-v2.json')
    parser.add_argument('--changes', type=Path, default=folder / 'label-changes-v2.json')
    args = parser.parse_args()
    result, changes = build(args.base, json.loads(args.review.read_text(encoding='utf-8')))
    save_separate(args.output, result, args.base)
    save_separate(args.changes, changes, args.base)
    print(json.dumps({'reviewedCases': changes['reviewedCases'], 'changedCases': changes['changedCases'],
                      'version': result['version'], 'independentlyAnnotated': result['independentlyAnnotated']}))


if __name__ == '__main__':
    main()
