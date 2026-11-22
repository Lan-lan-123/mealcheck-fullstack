"""Export unreviewed candidate pairs from a completed, fingerprint-bound live run.

Offline only. This exports captured text for a non-independent development review;
it does not judge relevance, run retrieval, or alter baseline evidence or labels.
"""
import argparse
from collections import Counter
from datetime import datetime
import json
from pathlib import Path

from freeze_chunking_suite import ROOT, KNOWLEDGE, json_digest
from prepare_rag_screening_pilot import content_id
from migrate_rag_longform_labels import normalized
from run_live_rag_screening import digest, load, validate_packet


MODE = 'LIVE_PRODUCTION_PGVECTOR_RETRIEVAL_SCREENING'
SOURCE = 'diet_guides.md'
REVIEW_STATUS = 'UNREVIEWED_DEVELOPMENT_REVIEW_NOT_INDEPENDENT'


def require(condition, message):
    if not condition:
        raise ValueError(message)


def verify_completion(runtime):
    require(runtime.get('mode') == 'EXECUTE' and runtime.get('state') == 'COMPLETE'
            and type(runtime.get('exitCode')) is int and runtime['exitCode'] == 0,
            'Live run is incomplete or unsuccessful; no complete review export allowed')


def verify_files(runtime, execution, prepared, inputs):
    """Require all frozen source/input files and all sealed JSON outputs unchanged."""
    verify_completion(runtime)
    hashes = runtime.get('sourceAndInputSha256', {})
    require(bool(hashes), 'Missing frozen input/source fingerprints')
    for name, expected in hashes.items():
        path = Path(name)
        require(path.is_file() and digest(path) == expected, 'Frozen source/input drift: ' + name)
    for path in [*inputs.values(), prepared / 'plan.json', prepared / 'screening30.json',
                 prepared / 'expected-chunks.json']:
        require(hashes.get(str(path.resolve())) == digest(path), 'Unbound required input: ' + str(path))
    require(runtime.get('planSha256') == digest(prepared / 'plan.json'), 'Plan fingerprint mismatch')
    outputs = runtime.get('outputSha256', {})
    for name in ('results.json', 'after-screening-index.json', 'budget-summary.json'):
        require(name in outputs, 'Missing sealed output: ' + name)
    for name, expected in outputs.items():
        require(Path(name).name == name, 'Output fingerprint is not a local filename')
        path = execution / name
        require(path.is_file() and digest(path) == expected, 'Live output drift: ' + name)
    require(load(execution / 'budget-summary.json').get('status') == 'COMPLETE',
            'Provider budget ledger did not complete')


def judgment(hit, expected):
    """Recheck existing explicit labels; never infer relevance from title or rank."""
    for target in expected.get('evidenceTargets', []):
        if target['knowledgeId'] != hit['knowledgeId']:
            continue
        require(target['title'] == hit['title'] and target.get('relevance') in (1, 2)
                and target.get('evidenceQuotes')
                and all(normalized(q) in normalized(hit['content']) for q in target['evidenceQuotes']),
                'Existing label cannot be anchored in captured content')
        return 'DIRECT' if target['relevance'] == 2 else 'BACKGROUND'
    if any(t['knowledgeId'] == hit['knowledgeId'] for t in expected.get('irrelevantEvidence', [])):
        return 'IRRELEVANT'
    return 'UNJUDGED'


def validate_hit(hit, chunks, index):
    key = hit.get('knowledgeId')
    require(key in chunks and type(hit.get('id')) is int, 'Hit has no manifest/DB identity')
    chunk = chunks[key]
    require(hit.get('title') == chunk['title'] and hit.get('content') == chunk['content']
            and content_id(hit['title'], hit['content']) == key, 'Captured hit body/hash mismatch')
    db = index.get(hit['id'])
    require(db is not None and db.get('source') == SOURCE and db.get('contentHash') == key
            and db.get('chunkKey') == chunk['chunkKey'] and db.get('title') == hit['title']
            and db.get('content') == hit['content'] and db.get('hasEmbedding') is True
            and db.get('sourceVersion') == 'v8-longform-200-boundary-v2',
            'Captured hit does not match the published system-source snapshot')


def review_item(row, candidate, chunk, retained_rank):
    hit = candidate['hit']
    return {'caseId': row['caseId'], 'question': row['question'], 'knowledgeId': hit['knowledgeId'],
            'title': hit['title'], 'content': hit['content'], 'source': SOURCE,
            'chunkKey': chunk['chunkKey'], 'capturedDbId': hit['id'], 'rawRank': candidate['rank'],
            'retainedRank': retained_rank, 'score': hit['score'], 'disposition': candidate['disposition'],
            'relevance': None, 'evidenceQuotes': [], 'rationale': '', 'alternativeForGroups': [],
            'reviewer': '', 'notes': ''}


def build_review(results, cases, selected_ids, manifest, snapshot):
    """Validate the captured chain, then select only currently unjudged actual hits."""
    require(results.get('executionMode') == MODE, 'Results are not live production PGVector retrieval')
    for name, value in {'caseCount': 30, 'queryEmbeddingCalls': 28, 'outsideSkipped': 2,
                        'chatCalls': 0, 'rerankCalls': 0, 'maxRetries': 0}.items():
        require(type(results.get(name)) is int and results[name] == value, 'Wrong run contract: ' + name)
    for name in ('independentlyAnnotated', 'routingOrToolSelectionMeasured', 'answerGenerationMeasured'):
        require(results.get(name) is False, 'Unexpected measurement claim: ' + name)
    rows = results.get('results', [])
    require(len(selected_ids) == 30 and len(set(selected_ids)) == 30
            and [r.get('caseId') for r in rows] == selected_ids, 'Not the exact ordered fixed screening30')
    by_case = {c['id']: c for c in cases}
    require(len(by_case) == len(cases) and set(selected_ids) <= by_case.keys(), 'Dataset case identities invalid')
    require(Counter(by_case[i]['answerability'] for i in selected_ids)
            == Counter(ANSWERABLE=24, INSUFFICIENT_EVIDENCE=4, OUT_OF_SCOPE=2), 'Not fixed 28+2')
    chunks = {c['contentHash']: c for c in manifest['chunks']}
    require(len(chunks) == len(manifest['chunks']), 'Duplicate manifest content identities')
    for key, chunk in chunks.items():
        require(content_id(chunk['title'], chunk['content']) == key, 'Manifest body hash mismatch')
    index = {r['id']: r for r in snapshot}
    require(len(index) == len(snapshot), 'Duplicate snapshot DB identity')
    system_rows = [r for r in snapshot if r.get('source') == SOURCE]
    require(len(system_rows) == len(chunks)
            and {r.get('contentHash') for r in system_rows} == set(chunks),
            'Published system source does not contain exactly the manifest chunk identities')
    for db in system_rows:
        validate_hit({'id': db['id'], 'knowledgeId': db['contentHash'],
                      'title': db['title'], 'content': db['content']}, chunks, index)
    primary, secondary = [], []
    for row in rows:
        expected = by_case[row['caseId']]
        require(row.get('question') == expected['question'] and row.get('answerability') == expected['answerability'],
                'Case query or scope changed: ' + row['caseId'])
        raw = row.get('rawCandidates', [])
        if expected['answerability'] == 'OUT_OF_SCOPE':
            require(row.get('state') == 'SKIPPED_OUT_OF_SCOPE' and row.get('queryEmbeddingCalls') == 0
                    and not raw and not row.get('capturedRetrieval')
                    and not row.get('assessment', {}).get('retrievals'), 'Outside case performed retrieval')
            continue
        capture = row.get('capturedRetrieval', {})
        hits = capture.get('hits', [])
        require(row.get('state') == 'RETRIEVED_PRODUCTION_PGVECTOR_NO_RERANK'
                and row.get('queryEmbeddingCalls') == 1 and capture.get('query') == expected['question']
                and capture.get('requestedLimit') == 5 and capture.get('status') == 'SUCCESS'
                and not capture.get('errorType') and len(hits) <= 5 and len(raw) <= 20,
                'Invalid production retrieval capture')
        require([c.get('rank') for c in raw] == list(range(1, len(raw) + 1)), 'Raw ranks are not contiguous')
        raw_by_id = {c['hit']['id']: c for c in raw}
        require(len(raw_by_id) == len(raw), 'Duplicate raw hit DB identity')
        retained = {h['id']: rank for rank, h in enumerate(hits, 1)}
        require(len(retained) == len(hits), 'Duplicate retained hit DB identity')
        require([s['id'] for s in row.get('postProcessor', {}).get('snippets', [])] == [h['id'] for h in hits],
                'Postprocessor snippets and captured top5 differ')
        for hit in hits:
            require(hit['id'] in raw_by_id and raw_by_id[hit['id']]['hit'] == hit,
                    'Retained hit was not captured in actual raw candidates')
        for candidate in raw:
            hit = candidate['hit']
            validate_hit(hit, chunks, index)
            is_retained = hit['id'] in retained
            require((candidate.get('disposition') == 'RETAINED_TOP5') == is_retained,
                    'Raw retained disposition disagrees with actual capture')
            actual = judgment(hit, expected)
            require(candidate.get('judgment') == actual, 'Captured judgment disagrees with current source-bound labels')
            if actual == 'UNJUDGED':
                item = review_item(row, candidate, chunks[hit['knowledgeId']], retained.get(hit['id']))
                secondary.append(item)
                if is_retained:
                    primary.append(item)
    return primary, secondary


def export(args):
    execution, prepared = args.execution.resolve(), args.prepared.resolve()
    runtime = load(execution / 'runtime.json')
    verify_completion(runtime)  # Reject an unfinished run before reading partial results.
    inputs, plan = validate_packet(prepared, args.manifest)
    verify_files(runtime, execution, prepared, inputs)
    manifest, cases, suite = load(inputs['manifest']), load(inputs['dataset']), load(inputs['suite'])
    require(manifest['chunkCount'] == 1351 and manifest['topicCount'] == 200
            and manifest['splitterRevision'] == 'sentence-heading-boundaries-v2', 'Not current 1351 corpus')
    primary, secondary = build_review(load(execution / 'results.json'), cases,
                                      suite['sets']['screening30']['caseIds'], manifest,
                                      load(execution / 'after-screening-index.json'))
    source = {'resultsSha256': digest(execution / 'results.json'), 'datasetSha256': digest(inputs['dataset']),
              'manifestSha256': digest(inputs['manifest']), 'corpusSha256': digest(inputs['corpus'])}
    common = {'status': REVIEW_STATUS, 'independentlyAnnotated': False, 'isIndependentBlindReview': False,
              'reviewPurpose': 'DEVELOPMENT_RELEVANCE_REVIEW', 'source': source,
              'suiteJsonDigest': json_digest(suite), 'planSha256': digest(prepared / 'plan.json'),
              'runtimeSha256': digest(execution / 'runtime.json'), 'productionChunkCount': plan['chunkCount'],
              'limitations': ['No relevance judgments have been made by this exporter.',
                              'The development team has seen the corpus and existing labels; this is not an independent blind test.',
                              'Raw20-only candidates are secondary and are excluded from the retained-top5 overlay.']}
    overlay = {'schemaVersion': 'rag-live-review-overlay-v1', 'independentlyAnnotated': False,
               'reviewer': 'Codex', 'reviewScope': 'ACTUAL_RETAINED_TOP5_UNJUDGED_ONLY', 'source': source,
               'reviewedPairs': [{key: item[key] for key in ('caseId', 'knowledgeId', 'relevance', 'evidenceQuotes',
                                                              'rationale', 'alternativeForGroups')} for item in primary]}
    output = args.output_directory.resolve()
    require(output.is_relative_to((ROOT / 'logs').resolve()) and not output.exists(),
            'A fresh output directory under logs/ is required')
    output.mkdir(parents=True)
    packets = {'retained-top5-review.json': {**common, 'scope': 'ACTUAL_RETAINED_TOP5_UNJUDGED_ONLY', 'pairs': primary},
               'raw20-review.json': {**common, 'scope': 'ACTUAL_RAW20_UNJUDGED_SECONDARY_POOL', 'pairs': secondary},
               'overlay-template.json': overlay,
               'export-summary.json': {**common, 'retainedTop5UnjudgedPairs': len(primary),
                                       'raw20UnjudgedPairs': len(secondary),
                                       'retainedCasesNeedingReview': len({p['caseId'] for p in primary})}}
    for name, packet in packets.items():
        (output / name).write_text(json.dumps(packet, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'outputDirectory': str(output), 'retainedTop5UnjudgedPairs': len(primary),
                      'raw20UnjudgedPairs': len(secondary), 'status': REVIEW_STATUS}, ensure_ascii=False))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--execution', type=Path, default=ROOT / 'logs/backend/rag-screening-live-execute-20261021-r1')
    parser.add_argument('--prepared', type=Path, default=ROOT / 'logs/backend/rag-screening-pilot-prepared-20261021-r1')
    parser.add_argument('--manifest', type=Path, default=ROOT / 'logs/backend/rag-boundary-fix-20261021-r1/candidate-chunks.json')
    parser.add_argument('--output-directory', type=Path, default=ROOT / 'logs/backend' /
                        ('live-retrieval-review-' + datetime.now().strftime('%Y%m%d-%H%M%S-%f')))
    export(parser.parse_args())


if __name__ == '__main__':
    main()
