"""Allocate two disjoint generation batches within the originally approved GLOBAL budget."""
import argparse
from datetime import datetime
import hashlib
import json
from pathlib import Path
import shutil

from generate_knowledge_longform import accounted_usage, dump
from prepare_knowledge_longform import ROOT


def prepare(parent):
    parent = parent.resolve()
    if not parent.is_relative_to((ROOT / 'logs').resolve()):
        raise ValueError('Parent batch must be under logs/')
    state = json.loads((parent / 'generation.json').read_text(encoding='utf-8'))
    if state['status'] not in {'REVIEW_CHECKPOINT', 'CONTENT_REVIEW_REQUIRED', 'EDITORIAL_UPDATES_READY',
                               'REVALIDATED_REQUIRES_EDITORIAL_REVIEW', 'GENERATED_REQUIRES_VALIDATION'}:
        raise ValueError('Parent must be stopped at a reviewed checkpoint, without provider or budget failure')
    manifest_path = parent / 'parallel-plan.json'
    if manifest_path.exists():
        raise ValueError('An allocation already exists; do not allocate a second budget')
    plan = json.loads((parent / 'plan.json').read_text(encoding='utf-8'))
    remaining = [row for row in plan['topics'] if row['topicId'] not in state['drafts']]
    if len(remaining) < 2:
        raise ValueError('Fewer than two topics remain; use the sequential batch')
    already = len(state['calls'])
    spent = sum(accounted_usage(call['usage'], call['inputAllowance'] + call['maximumOutputTokens'])
                if call.get('usage') else call['accountedTokens'] for call in state['calls'])
    if already + len(remaining) > 200 or spent >= 3000000:
        raise ValueError('Remaining topics cannot fit the original global call/token allowance')
    groups, weights = [[], []], [0, 0]
    for row in sorted(remaining, key=lambda row: -row['targetChunks']):
        target = min(range(2), key=lambda index: (weights[index], len(groups[index])))
        groups[target].append(row)
        weights[target] += row['targetChunks']
    available = 3000000 - spent
    budgets = [available // 2, available - available // 2]
    manifest = {'createdAt': datetime.now().isoformat(), 'globalMaxCalls': 200, 'globalTokenBudget': 3000000,
                'parentCallsAlreadySent': already, 'parentConservativeTokens': spent,
                'sharedStopFile': str(parent / 'parallel-stop.json'), 'children': []}
    for index, group in enumerate(groups):
        child = parent / ('part-' + ('a' if index == 0 else 'b'))
        child.mkdir(exist_ok=False)
        for filename in ['plan.json', 'before-diet-guides.md', 'before-rag-cases.json', 'runtime.json']:
            shutil.copy2(parent / filename, child / filename)
        allocation = {'parentBatch': str(parent), 'sourceSha256': plan['corpusSha256'],
                      'topicIds': sorted(row['topicId'] for row in group),
                      'maxCalls': len(group), 'tokenBudget': budgets[index],
                      'sharedStopFile': manifest['sharedStopFile']}
        (child / 'allocation.json').write_text(json.dumps(allocation, ensure_ascii=False, indent=2), encoding='utf-8')
        manifest['children'].append({'path': str(child), 'maxCalls': len(group), 'tokenBudget': budgets[index],
                                     'topicCount': len(group), 'targetChunkWeight': weights[index]})
    assert already + sum(child['maxCalls'] for child in manifest['children']) <= 200
    assert spent + sum(child['tokenBudget'] for child in manifest['children']) == 3000000
    with manifest_path.open('x', encoding='utf-8') as stream:
        json.dump(manifest, stream, ensure_ascii=False, indent=2)
    print(json.dumps(manifest, ensure_ascii=False))


def merge(parent):
    parent = parent.resolve()
    manifest = json.loads((parent / 'parallel-plan.json').read_text(encoding='utf-8'))
    state_path = parent / 'generation.json'
    original = state_path.read_bytes()
    state = json.loads(original)
    if state.get('childBatchesMerged'):
        raise ValueError('Already merged; avoid counting calls twice')
    all_ids = set(state['drafts'])
    children = []
    for child in manifest['children']:
        path = Path(child['path'])
        allocation = json.loads((path / 'allocation.json').read_text(encoding='utf-8'))
        data = json.loads((path / 'generation.json').read_text(encoding='utf-8'))
        if data['status'] == 'IN_FLIGHT' or set(data['drafts']) != set(allocation['topicIds']):
            raise ValueError('Each child must have finished its entire disjoint scope before merging')
        if all_ids.intersection(data['drafts']):
            raise ValueError('Overlapping draft IDs')
        all_ids.update(data['drafts'])
        children.append((path, data))
    total_calls = len(state['calls']) + sum(len(data['calls']) for _, data in children)
    total_tokens = manifest['parentConservativeTokens'] + sum(
        sum(accounted_usage(call['usage'], call['inputAllowance'] + call['maximumOutputTokens'])
            if call.get('usage') else call['accountedTokens'] for call in data['calls']) for _, data in children)
    if total_calls > 200 or total_tokens > 3000000 or len(all_ids) != 200:
        raise ValueError('Global budget or 200-topic coverage failed')
    backup = parent / ('pre-merge-generation-' + datetime.now().strftime('%Y%m%d-%H%M%S-%f') + '.json')
    backup.write_bytes(original)
    for path, data in children:
        for directory in ['drafts', 'requests']:
            for source in (path / directory).glob('*'):
                destination = parent / directory / source.name
                if destination.exists():
                    if destination.read_bytes() == source.read_bytes():
                        continue
                    raise ValueError('Evidence collision: ' + source.name)
                shutil.copy2(source, destination)
        state['drafts'].update(data['drafts'])
        state['calls'].extend([{**call, 'sourceBatch': str(path)} for call in data['calls']])
    state['status'] = 'MERGED_REQUIRES_OFFLINE_AUDIT'
    state['childBatchesMerged'] = [str(path) for path, _ in children]
    dump(state_path, state)
    print(json.dumps({'drafts': len(state['drafts']), 'calls': total_calls, 'conservativeTokens': total_tokens}))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('parent', type=Path)
    parser.add_argument('--merge', action='store_true')
    args = parser.parse_args()
    (merge if args.merge else prepare)(args.parent)
