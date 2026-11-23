"""Legacy v6 template-preview reproduction only; not the current corpus authoring path.

The v7 topic-specific revision uses revise_knowledge_topic_prose.py and literal
paragraphs. Retain this tool solely to reproduce archived v6 previews. No API client.
"""
import argparse
from collections import Counter
from datetime import datetime
import json
from pathlib import Path
import shutil

from generate_knowledge_longform import Splitter, assess, dump, unsupported_medical_terms
from prepare_knowledge_longform import ROOT, MARKER, related_topics


def prepare(source, destination):
    if destination.exists() or not destination.resolve().is_relative_to((ROOT / 'logs').resolve()):
        raise ValueError('Use a new batch directory under logs/')
    destination.mkdir(parents=True)
    for name in ['plan.json', 'before-diet-guides.md', 'before-rag-cases.json', 'runtime.json']:
        shutil.copy2(source / name, destination / name)
    (destination / 'drafts').mkdir()
    state = {'status': 'LOCAL_AUTHORING_NO_PROVIDER_CALLS', 'calls': [], 'drafts': {}, 'importedFrom': []}
    for directory in [source, source / 'part-a', source / 'part-b']:
        original = json.loads((directory / 'generation.json').read_text(encoding='utf-8'))
        for topic in original['drafts']:
            for suffix in ['json', 'md']:
                shutil.copy2(directory / 'drafts' / (topic + '.' + suffix), destination / 'drafts' / (topic + '.' + suffix))
            state['drafts'][topic] = original['drafts'][topic]
        state['calls'].extend(dict(call, sourceBatch=str(directory)) for call in original['calls'])
        state['importedFrom'].append(str(directory))
    state['localAuthoringStarted'] = datetime.now().isoformat()
    state['newProviderCalls'] = 0
    dump(destination / 'generation.json', state)


# Each note is written for one topic. Three contrasting cases provide the facts,
# action and scope used by different parts of the article; they are hypothetical.
def read_notes(path):
    result = {}
    for line in path.read_text(encoding='utf-8').splitlines():
        if not line.strip() or line.startswith('#'):
            continue
        index, *cases = line.split('｜')
        if len(cases) != 3 or any(len(case.split('；')) != 3 for case in cases):
            raise ValueError('Expected index and three situation;action;limit triples: ' + line)
        if int(index) in result:
            raise ValueError('Duplicate authored topic: ' + index)
        result[int(index)] = [case.split('；') for case in cases]
    return result


from knowledge_case_prose import compose


def author(batch, notes_path):
    rows = json.loads((batch / 'plan.json').read_text(encoding='utf-8'))['topics']
    notes = read_notes(notes_path)
    details = json.loads(Path(__file__).with_name('knowledge_longform_topic_details.json').read_text(encoding='utf-8'))
    state = json.loads((batch / 'generation.json').read_text(encoding='utf-8'))
    if state['status'] == 'IN_FLIGHT':
        raise ValueError('A batch with an active request cannot be edited')
    splitter = Splitter(batch)
    report = []
    try:
        for row in rows:
            index = row['topicIndex']
            if index not in notes:
                continue
            count = {'SHORT': 1, 'MEDIUM': 2, 'LONG': 4, 'EXTENDED': 6}[row['bucket']]
            def compose_with_details(size):
                result = compose(row, notes[index], size)
                if str(index) in details:
                    result['sections'].append(details[str(index)])
                return result
            supplement = compose_with_details(count)
            article, actual, issues = assess(row, supplement, splitter)
            # Add or remove whole, separately meaningful sections, never truncate prose.
            while issues and count < 6 and len(actual['chunks']) < row['minChunks']:
                count += 1
                supplement = compose_with_details(count)
                article, actual, issues = assess(row, supplement, splitter)
            while issues and count > 1 and len(actual['chunks']) > row['maxChunks']:
                count -= 1
                supplement = compose_with_details(count)
                article, actual, issues = assess(row, supplement, splitter)
            terms = unsupported_medical_terms(row, supplement, related_topics(row, rows))
            if terms:
                issues.append('unsupported_medical_terms:' + ','.join(terms))
            record = {'topicId': row['topicId'], 'status': 'VALID' if not issues else 'VALIDATION_FAILED',
                      'issues': issues, 'authorship': 'LOCAL_ASSISTANT_AUTHORED_CASE_NOTES_AND_PROSE_COMPOSITION',
                      'model': None, 'independentlyReviewed': False, 'contentReviewStatus': 'PENDING_FULL_CORPUS_REVIEW',
                      'supplement': supplement, 'chunkCount': len(actual['chunks']), 'estimatedTokens': actual['tokenCount'],
                      'caseNotes': notes[index], 'authoringNotesPath': str(notes_path)}
            dump(batch / 'drafts' / (row['topicId'] + '.json'), record)
            (batch / 'drafts' / (row['topicId'] + '.md')).write_text(MARKER + '\n' + article, encoding='utf-8')
            state['drafts'][row['topicId']] = {'status': record['status'], 'issues': issues}
            report.append({'topicId': row['topicId'], 'bucket': row['bucket'], 'chunks': len(actual['chunks']), 'sections': count, 'issues': issues})
    finally:
        splitter.close()
    dump(batch / 'generation.json', state)
    dump(batch / 'local-authoring-report.json', {'providerCalls': 0, 'records': report, 'bucketCounts': dict(Counter(r['bucket'] for r in report))})
    print(json.dumps({'newProviderCalls': 0, 'authored': len(report), 'invalid': [r for r in report if r['issues']], 'totalDrafts': len(state['drafts'])}, ensure_ascii=False))
    if any(record['issues'] for record in report):
        raise ValueError('Some local articles still fail actual splitting or scope checks; see local-authoring-report.json')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('batch', type=Path)
    parser.add_argument('--notes', type=Path)
    parser.add_argument('--import-from', dest='source', type=Path)
    args = parser.parse_args()
    if args.source:
        prepare(args.source.resolve(), args.batch.resolve())
    if args.notes:
        author(args.batch.resolve(), args.notes.resolve())
