"""Adopt a locally edited preview after actual splitting, preserving prior drafts and call accounting."""
import argparse
from datetime import datetime
import json
from pathlib import Path
import re
import shutil

from generate_knowledge_longform import Splitter, assess, dump
from prepare_knowledge_longform import ROOT, MARKER


def adopt(batch, indices, draft_only=False):
    batch = batch.resolve()
    if not batch.is_relative_to((ROOT / 'logs').resolve()):
        raise ValueError('Batch must be under logs/')
    state_path = batch / 'generation.json'
    state = json.loads(state_path.read_text(encoding='utf-8'))
    if state['status'] == 'IN_FLIGHT' and not draft_only:
        raise ValueError('Cannot edit drafts while generation is in flight')
    rows = {row['topicIndex']: row for row in json.loads((batch / 'plan.json').read_text(encoding='utf-8'))['topics']}
    if draft_only:
        selected = {rows[index]['topicId'] for index in indices}
        if not selected.issubset(state['drafts']):
            raise ValueError('Draft-only editing is limited to already completed topic IDs')
        if any(call.get('status') == 'IN_FLIGHT' and call['topicId'] in selected for call in state['calls']):
            raise ValueError('Cannot edit a topic that still has an in-flight request')
    archive = batch / ('editorial-history-' + datetime.now().strftime('%Y%m%d-%H%M%S-%f'))
    archive.mkdir()
    splitter = Splitter(batch)
    try:
        for index in indices:
            row = rows[index]
            text = (batch / f'preview-{index:03d}-supplement.md').read_text(encoding='utf-8')
            matches = list(re.finditer(r'^\*\*(.+)\*\*$', text, re.M))
            sections = []
            for position, match in enumerate(matches):
                end = matches[position + 1].start() if position + 1 < len(matches) else len(text)
                sections.append({'heading': match.group(1), 'paragraphs': [paragraph.strip()
                                for paragraph in text[match.end():end].split('\n\n') if paragraph.strip()]})
            supplement = {'sections': sections}
            article, actual, issues = assess(row, supplement, splitter)
            if issues:
                raise ValueError(row['title'] + ': ' + ','.join(issues))
            for suffix in ['json', 'md']:
                old = batch / ('drafts/' + row['topicId'] + '.' + suffix)
                if old.exists():
                    shutil.copy2(old, archive / old.name)
            record = {'topicId': row['topicId'], 'status': 'VALID', 'issues': [],
                      'independentlyReviewed': False, 'model': None, 'authorship': 'LOCAL_ASSISTANT_AUTHORED_OR_EDITED_PREVIEW',
                      'contentReviewStatus': 'ASSISTANT_REVIEWED_NOT_INDEPENDENTLY_REVIEWED',
                      'supplement': supplement, 'chunkCount': len(actual['chunks']), 'estimatedTokens': actual['tokenCount']}
            dump(batch / ('drafts/' + row['topicId'] + '.json'), record)
            (batch / ('drafts/' + row['topicId'] + '.md')).write_text(MARKER + '\n' + article, encoding='utf-8')
            state['drafts'][row['topicId']] = {'status': 'VALID', 'issues': []}
        adoption = {'topicIndices': indices, 'archive': archive.name, 'draftOnly': draft_only}
        dump(archive / 'adoption.json', adoption)
        if not draft_only:
            if state['status'] not in {'PROVIDER_ERROR', 'TRANSPORT_ERROR', 'BUDGET_STOP'}:
                state['status'] = 'EDITORIAL_UPDATES_READY'
            state.setdefault('localEditorialAdoptions', []).append(adoption)
            dump(state_path, state)
        print(json.dumps({'adoptedTopics': indices, 'sentCallsUnchanged': len(state['calls']),
                          'accountedTokens': sum(call['accountedTokens'] for call in state['calls']),
                          'statusLedgerUnchanged': draft_only}))
    finally:
        splitter.close()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('batch', type=Path)
    parser.add_argument('--topics', type=int, nargs='+', required=True)
    parser.add_argument('--draft-only', action='store_true', help='Edit only completed draft files without touching an active generation ledger')
    args = parser.parse_args()
    adopt(args.batch, args.topics, args.draft_only)
