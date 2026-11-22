"""Read generation progress and globally aggregated accounting without sending requests."""
import argparse
import json
from pathlib import Path

from generate_knowledge_longform import accounted_usage


def status(batch):
    state = json.loads((batch / 'generation.json').read_text(encoding='utf-8'))
    states = [(batch, state)]
    allocation = batch / 'parallel-plan.json'
    if allocation.exists() and not state.get('childBatchesMerged'):
        for child in json.loads(allocation.read_text(encoding='utf-8'))['children']:
            path = Path(child['path'])
            if (path / 'generation.json').exists():
                states.append((path, json.loads((path / 'generation.json').read_text(encoding='utf-8'))))
    ids, calls, accounted, reported, invalid = set(), 0, 0, 0, []
    for path, item in states:
        overlap = ids.intersection(item['drafts'])
        if overlap:
            raise ValueError('Overlapping topic scopes in unmerged generation batches')
        ids.update(item['drafts'])
        calls += len(item['calls'])
        for call in item['calls']:
            usage = call.get('usage')
            accounted += accounted_usage(usage, call['inputAllowance'] + call['maximumOutputTokens']) if usage else call['accountedTokens']
            reported += usage.get('total_tokens', 0) if usage else 0
        for topic_id, value in item['drafts'].items():
            saved = path / ('drafts/' + topic_id + '.json')
            latest = json.loads(saved.read_text(encoding='utf-8')) if saved.exists() else value
            if latest['status'] != 'VALID':
                invalid.append({'topicId': topic_id, 'batch': str(path), 'issues': latest['issues']})
    return {'completedDrafts': len(ids), 'targetTopics': 200, 'callsSentIncludingInFlight': calls,
            'providerReportedTokens': reported, 'conservativeTokensIncludingReservations': accounted,
            'withinGlobalCeiling': calls <= 200 and accounted <= 3000000,
            'invalidDrafts': invalid, 'batches': [{'path': str(path), 'status': value['status'],
                                                 'drafts': len(value['drafts'])} for path, value in states]}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('batch', type=Path)
    print(json.dumps(status(parser.parse_args().batch.resolve()), ensure_ascii=False))
