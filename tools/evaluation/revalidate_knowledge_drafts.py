"""Recheck saved drafts offline after validation-rule changes; no regeneration or provider calls."""
import argparse
from datetime import datetime
import json
from pathlib import Path
import shutil

from generate_knowledge_longform import Splitter, assess, dump, unsupported_medical_terms, decode_supplement, accounted_usage
from prepare_knowledge_longform import ROOT, related_topics


def validate(batch):
    batch = batch.resolve()
    if not batch.is_relative_to((ROOT / 'logs').resolve()):
        raise ValueError('Batch must be under logs/')
    state_path = batch / 'generation.json'
    state = json.loads(state_path.read_text(encoding='utf-8'))
    if state['status'] == 'IN_FLIGHT':
        raise ValueError('Stop generation before revalidating its saved state')
    plan = json.loads((batch / 'plan.json').read_text(encoding='utf-8'))
    rows = {row['topicId']: row for row in plan['topics']}
    archive = batch / ('validation-history-' + datetime.now().strftime('%Y%m%d-%H%M%S-%f'))
    archive.mkdir()
    changes = []
    splitter = Splitter(batch)
    try:
        for topic_id in state['drafts']:
            path = batch / ('drafts/' + topic_id + '.json')
            record = json.loads(path.read_text(encoding='utf-8'))
            response_paths = sorted((batch / 'drafts').glob(topic_id + '-call-*.response.json'))
            if record.get('model') and response_paths:
                response = json.loads(response_paths[-1].read_text(encoding='utf-8'))
                if response['choices'][0].get('finish_reason') == 'stop':
                    record['supplement'] = decode_supplement(response['choices'][0]['message']['content'])
            if 'supplement' not in record:
                continue
            row = rows[topic_id]
            article, actual, issues = assess(row, record['supplement'], splitter)
            terms = unsupported_medical_terms(row, record['supplement'], related_topics(row, plan['topics']))
            if terms:
                issues.append('unsupported_medical_terms:' + ','.join(terms))
            status = 'VALID' if not issues else 'VALIDATION_FAILED'
            if record['issues'] != issues or record['status'] != status or record.get('chunkCount') != len(actual['chunks']):
                shutil.copy2(path, archive / path.name)
                changes.append({'topicId': topic_id, 'previousIssues': record['issues'], 'currentIssues': issues})
                record.update(status=status, issues=issues, chunkCount=len(actual['chunks']), estimatedTokens=actual['tokenCount'])
                dump(path, record)
                (batch / ('drafts/' + topic_id + '.md')).write_text('<!-- mealcheck:independent-topics -->\n' + article, encoding='utf-8')
            state['drafts'][topic_id] = {'status': status, 'issues': issues}
        for call in state['calls']:
            if call.get('usage'):
                call['accountedTokens'] = accounted_usage(call['usage'], call['inputAllowance'] + call['maximumOutputTokens'])
        if state['status'] not in {'PROVIDER_ERROR', 'TRANSPORT_ERROR', 'BUDGET_STOP'}:
            state['status'] = 'REVALIDATED_REQUIRES_EDITORIAL_REVIEW'
        dump(state_path, state)
        print(json.dumps({'providerCalls': 0, 'revalidatedDrafts': len(state['drafts']), 'changes': changes}, ensure_ascii=False))
    finally:
        splitter.close()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('batch', type=Path)
    validate(parser.parse_args().batch)
