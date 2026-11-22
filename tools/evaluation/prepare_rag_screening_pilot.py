"""Prepare and verify the fixed retrieval pilot offline; never connects or calls models.

An exported plan is not permission to spend, an index synchronization, or a quality
measurement. Optional snapshots are previously exported, local JSON files only.
"""
import argparse
from collections import Counter
from datetime import datetime
import hashlib
import json
from pathlib import Path

from freeze_chunking_suite import ROOT, KNOWLEDGE, SELECTIONS, validate_suite
from migrate_rag_longform_labels import normalized
from rebind_rag_chunk_boundaries import validate_manifest, update_metadata
from audit_chunk_boundaries import audit_manifest, sections

SOURCE = 'diet_guides.md'
SOURCE_VERSION = 'v8-longform-200-boundary-v2'
SPLITTER_REVISION = 'sentence-heading-boundaries-v2'
REQUEST_CAP = 1381
ADMISSION_CAP = 2_000_000
REQUEST_ALLOWANCE = 128
TOOLS = {'search_diet_knowledge', 'get_my_recent_meals', 'get_my_weekly_report', 'get_my_current_goal'}

SNAPSHOT_SQL = """-- Run explicitly with the application's read-only DB access; this file does not execute itself.
-- No vectors, keys, provider URLs or administrator bodies are exported.
BEGIN TRANSACTION READ ONLY;
SELECT COALESCE(json_agg(json_build_object(
    'id', id, 'source', source, 'title', title, 'contentHash', content_hash,
    'chunkKey', chunk_key, 'sourceVersion', source_version,
    'signature', embedding_signature, 'hasEmbedding', embedding IS NOT NULL,
    'content', CASE WHEN source = 'diet_guides.md' THEN content ELSE NULL END
) ORDER BY source, chunk_key, id), '[]'::json)
FROM knowledge_chunks;
COMMIT;
"""


def digest(data):
    return hashlib.sha256(data).hexdigest()


def content_id(title, content):
    return digest((normalized(title) + '\n\n' + normalized(content)).encode('utf-8'))


def validate_tool_rules(row):
    rules = row['agentEvaluation']
    outside = row['answerability'] == 'OUT_OF_SCOPE'
    if row.get('expectNoAnswer', False) != outside:
        raise ValueError('Answerability and scope disagree: ' + row['id'])
    expected = set() if outside else {'search_diet_knowledge'}
    if (rules.get('ruleVersion') != 'rag-tools-v2' or set(rules.get('expectedTools', [])) != expected
            or set(rules.get('forbiddenTools', [])) != TOOLS - expected
            or rules.get('maxToolCalls') != (0 if outside else 2)
            or rules.get('limitMin') != 1 or rules.get('limitMax') != 5):
        raise ValueError('Tool rules differ from the fixed retrieval contract: ' + row['id'])
    if len(rules['expectedTools']) != len(expected) or len(rules['forbiddenTools']) != len(TOOLS - expected):
        raise ValueError('Tool rule duplicates: ' + row['id'])
    if rules.get('expectedToolSequence') != ([] if outside else ['search_diet_knowledge']):
        raise ValueError('Tool sequence differs: ' + row['id'])
    if rules.get('expectedToolStatuses') != ({} if outside else {'search_diet_knowledge': ['SUCCESS']}):
        raise ValueError('Tool statuses differ: ' + row['id'])
    groups = rules.get('queryTopicGroups')
    if not isinstance(groups, list) or (outside and groups) or (not outside and not groups):
        raise ValueError('Query topic groups differ: ' + row['id'])
    for group in groups:
        if not isinstance(group, list) or not group or any(not isinstance(t, str) or not t.strip() for t in group) or len(set(group)) != len(group):
            raise ValueError('Invalid query topic group: ' + row['id'])


def prepare(corpus_bytes, rows, metadata, suite, manifest):
    corpus_sha = digest(corpus_bytes)
    validate_manifest(manifest)
    if (manifest['corpusSha256'] != corpus_sha or metadata['corpusSha256'] != corpus_sha
            or manifest['topicCount'] != 200 or manifest['chunkCount'] != 1351
            or metadata.get('topicCount') != 200 or metadata.get('chunkCount') != 1351):
        raise ValueError('Current corpus, metadata and production manifest must agree')
    if (manifest.get('splitterRevision') != SPLITTER_REVISION or metadata.get('splitterRevision') != SPLITTER_REVISION
            or metadata.get('indexSourceVersion') != SOURCE_VERSION
            or manifest.get('splitterStrategy') != 'recursive-markdown'
            or tuple(manifest.get(k) for k in ('minTokens', 'maxTokens', 'overlapTokens')) != (300, 500, 50)):
        raise ValueError('Default splitter or index source revision differs')
    if tuple(metadata.get('splitter', {}).get(k) for k in ('minTokens', 'maxTokens', 'overlapTokens')) != (300, 500, 50):
        raise ValueError('Metadata token settings differ')
    update_metadata(metadata, manifest, manifest, SPLITTER_REVISION)
    audit = audit_manifest(manifest, sections(corpus_bytes.decode('utf-8')), corpus_sha)
    if not audit['continuityAndCoveragePassed'] or audit['fixableDiagnosticCount']:
        raise ValueError('Current production chunks do not pass source continuity/boundary validation')
    expected_ids = [f'rag-{number:03d}' for number, _, _ in SELECTIONS['screening30']]
    if [row.get('id') for row in rows] != [f'rag-{number:03d}' for number in range(1, 201)]:
        raise ValueError('Need all 200 uniquely identified source questions')
    if suite['sets']['screening30']['caseIds'] != expected_ids:
        raise ValueError('screening30 must retain the exact fixed IDs and order')
    validate_suite(suite, rows, corpus_bytes.decode('utf-8'))
    by_identity = {c['contentHash']: c for c in manifest['chunks']}
    for row in rows:
        if row['answerability'] not in {'ANSWERABLE', 'INSUFFICIENT_EVIDENCE', 'OUT_OF_SCOPE'}:
            raise ValueError('Unknown answerability')
        validate_tool_rules(row)
        if row.get('annotationRevision') != 'rag-evidence-v6-boundary-aligned' or row['annotation'].get('splitterRevision') != SPLITTER_REVISION:
            raise ValueError('Question annotations are not bound to the current splitter')
        targets = row.get('evidenceTargets')
        groups = row.get('requiredEvidenceGroups')
        if not isinstance(targets, list) or not isinstance(groups, list):
            raise ValueError('Missing evidence targets/groups')
        if row['answerability'] == 'OUT_OF_SCOPE':
            if targets or groups or row.get('irrelevantEvidence'):
                raise ValueError('Out-of-scope question must not gain evidence')
            continue
        seen, direct = set(), set()
        for field, grades in (('evidenceTargets', {1, 2}), ('irrelevantEvidence', {0})):
            for target in row.get(field, []):
                identity = target.get('knowledgeId')
                chunk = by_identity.get(identity)
                quotes = target.get('evidenceQuotes')
                if (identity in seen or chunk is None or type(target.get('relevance')) is not int or target.get('relevance') not in grades
                        or target.get('title') != chunk['title'] or not quotes or not target.get('rationale')
                        or any(not isinstance(q, str) or not q.strip() or normalized(q) not in normalized(chunk['content']) for q in quotes)):
                    raise ValueError('Evidence source, grade or quotes differ: ' + row['id'])
                seen.add(identity)
                if target['relevance'] == 2:
                    direct.add(identity)
        if not groups or any(not group or len(set(group)) != len(group) or not set(group) <= direct for group in groups):
            raise ValueError('Knowledge question lacks valid direct-evidence coverage groups')
        if row['answerability'] == 'INSUFFICIENT_EVIDENCE' and (not row.get('boundaryExpectation', {}).get('missingFacts')
                or not row['boundaryExpectation'].get('requiredBehavior')):
            raise ValueError('Insufficient-evidence case lacks the missing-fact boundary')
    by_id = {row['id']: row for row in rows}
    selected = [by_id[case_id] for case_id in expected_ids]
    queries = [r['question'] for r in selected if r['answerability'] != 'OUT_OF_SCOPE']
    if len(queries) != 28:
        raise ValueError('Fixed screening must have 28 retrieval and 2 no-retrieval cases')
    expected = [{'source': SOURCE, 'title': c['title'], 'contentHash': c['contentHash'],
                 'chunkKey': c['chunkKey'], 'sourceVersion': SOURCE_VERSION} for c in manifest['chunks']]
    index_bytes = sum(len((c['title'].strip() + '\n\n' + c['content'].strip()).encode('utf-8')) for c in manifest['chunks'])
    query_bytes = sum(len(q.encode('utf-8')) for q in queries)
    planned_requests = len(expected) + len(queries)
    admission_units = index_bytes + query_bytes + planned_requests * REQUEST_ALLOWANCE
    if planned_requests > REQUEST_CAP or admission_units > ADMISSION_CAP:
        raise ValueError('Declared maximum request or conservative input admission budget would be exceeded')
    plan = {'status': 'PREPARED_NOT_EXECUTED', 'source': SOURCE, 'sourceVersion': SOURCE_VERSION,
            'corpusSha256': corpus_sha, 'splitterRevision': SPLITTER_REVISION,
            'chunkCount': len(expected), 'topicCount': 200, 'suiteVersion': suite['version'],
            'selectionPolicy': 'FIXED_SCREENING30_EXACT_IDS_NO_RANDOM_SAMPLING', 'selectedCaseIds': expected_ids,
            'caseCount': len(selected), 'answerabilityCounts': dict(Counter(r['answerability'] for r in selected)),
            'plannedQueryEmbeddingCount': len(queries), 'plannedMaximumNewVectorRequests': planned_requests,
            'maximumAuthorizedRequestCeiling': REQUEST_CAP, 'conservativeInputBudgetCeiling': ADMISSION_CAP,
            'embeddingInputUtf8Bytes': index_bytes, 'queryInputUtf8Bytes': query_bytes,
            'perRequestAdmissionAllowance': REQUEST_ALLOWANCE, 'conservativeInputAdmissionUnits': admission_units,
            'budgetMethod': 'UTF-8 bytes of production embeddingText(title + two newlines + content) or query, plus 128 admission units/request; local conservative admission, not a supplier billing guarantee.',
            'maxRetries': 0, 'stopOnFirstProviderError': True, 'chatCallsAllowed': 0, 'rerankCallsAllowed': 0,
            'retrievalLimit': 5, 'supplementalQueryCallsAllowed': 0,
            'providerCallsMade': 0, 'indexRebuilt': False, 'retrievalExecuted': False,
            'independentlyAnnotated': False, 'preserveAdminEntries': True,
            'readinessRequirements': ['Verify actual index content/version/signature before retrieval',
                'Do not switch provider or rebuild admin vectors within this budget',
                'Treat OUT_OF_SCOPE questions as no retrieval, not 2 additional embedding calls',
                'Keep unjudged returned chunks separate from explicit irrelevance',
                'Hash or lexical diagnostics are not semantic-model retrieval quality'],
            'remainingAuthorization': 'Provider calls require the human decision recorded in the main task; this offline plan does not grant permission.'}
    return plan, selected, expected


def compare_index(snapshot, expected, before_snapshot=None):
    rows = snapshot['rows'] if isinstance(snapshot, dict) else snapshot
    if not isinstance(rows, list) or not all(isinstance(row, dict) for row in rows):
        raise ValueError('Index snapshot must be a JSON array or {rows:[...]}')
    system = [row for row in rows if row.get('source') == SOURCE]
    admins = [row for row in rows if row.get('source') == 'admin']
    wanted = {row['chunkKey']: row for row in expected}
    if len(wanted) != len(expected):
        raise ValueError('Duplicate expected chunk keys')
    by_key, errors, warnings = {}, [], []
    for row in system:
        key = row.get('chunkKey')
        if key in by_key:
            errors.append({'kind': 'DUPLICATE_SYSTEM_CHUNK_KEY', 'chunkKey': key})
        by_key[key] = row
    missing = sorted(set(wanted) - set(by_key), key=str)
    extra = sorted(set(by_key) - set(wanted), key=str)
    if missing or extra:
        errors.append({'kind': 'CHUNK_SET_DIFFERS', 'missingChunkKeys': missing, 'unexpectedChunkKeys': extra})
    verified = 0
    for key in sorted(set(wanted) & set(by_key)):
        row, target = by_key[key], wanted[key]
        for field in ('title', 'contentHash', 'sourceVersion'):
            if row.get(field) != target[field]:
                errors.append({'kind': 'INDEX_FIELD_DIFFERS', 'chunkKey': key, 'field': field})
        if isinstance(row.get('content'), str):
            actual_hash = content_id(row.get('title', ''), row['content'])
        else:
            actual_hash = None
            warnings.append({'kind': 'ACTUAL_BODY_HASH_UNVERIFIED', 'chunkKey': key})
        if actual_hash is not None:
            if actual_hash != row.get('contentHash') or actual_hash != target['contentHash']:
                errors.append({'kind': 'ACTUAL_CONTENT_HASH_DIFFERS', 'chunkKey': key})
            else:
                verified += 1
        if row.get('hasEmbedding') is not True:
            errors.append({'kind': 'VECTOR_MISSING_OR_UNVERIFIED', 'chunkKey': key})
    signatures = Counter(row.get('signature') for row in system)
    nonempty = {s for s in signatures if isinstance(s, str) and s.strip()}
    if len(nonempty) > 1:
        errors.append({'kind': 'MIXED_EMBEDDING_SIGNATURES'})
    if len(nonempty) != 1 or any(not isinstance(s, str) or not s.strip() for s in signatures):
        errors.append({'kind': 'MISSING_EMBEDDING_SIGNATURE'})
    local_vectors = any(s.startswith('hash-v1:') for s in nonempty)
    semantic_vectors = len(nonempty) == 1 and next(iter(nonempty)).startswith('semantic-v1:')
    if local_vectors:
        warnings.append({'kind': 'LOCAL_HASH_VECTORS_NOT_SEMANTIC_QUALITY'})
    elif nonempty and not semantic_vectors:
        warnings.append({'kind': 'UNRECOGNIZED_SIGNATURE_NOT_SEMANTIC_QUALITY'})
    admin_preserved = None
    if before_snapshot is not None:
        old_rows = before_snapshot['rows'] if isinstance(before_snapshot, dict) else before_snapshot
        def admin_inventory(items):
            return Counter(json.dumps({key: row.get(key) for key in ('id', 'source', 'title', 'contentHash', 'chunkKey', 'sourceVersion', 'signature')},
                                      ensure_ascii=False, sort_keys=True) for row in items if row.get('source') == 'admin')
        admin_preserved = admin_inventory(old_rows) == admin_inventory(rows)
        if not admin_preserved:
            errors.append({'kind': 'ADMIN_INVENTORY_CHANGED_REQUIRES_REVIEW'})
    else:
        warnings.append({'kind': 'ADMIN_PRESERVATION_NOT_MEASURED_WITHOUT_BEFORE_SNAPSHOT'})
    identity_complete = len(system) == len(expected) and verified == len(expected)
    ready = not errors and identity_complete
    return {'status': 'SNAPSHOT_CONSISTENT' if ready else 'SNAPSHOT_NOT_READY',
            'systemChunkCount': len(system), 'expectedSystemChunkCount': len(expected), 'adminEntryCount': len(admins),
            'otherSourceEntryCount': len(rows) - len(system) - len(admins),
            'actualBodyHashesVerified': verified, 'contentIdentityFullyVerified': identity_complete,
            'signatureCounts': {str(key): value for key, value in signatures.items()},
            'indexConsistencyReady': ready, 'semanticModelSignaturePresent': semantic_vectors,
            'semanticIndexSnapshotConsistent': ready and semantic_vectors and admin_preserved is not False,
            'adminInventoryPreserved': admin_preserved, 'providerCallsMade': 0,
            'retrievalQualityMeasured': False, 'errors': errors, 'warnings': warnings,
            'limitation': 'Snapshot checks do not verify vector numerical values/dimensions, provider authorization or semantic retrieval quality. Active provider profile and vector signature must still be checked by the executing service.'}


def export(output, plan, selected, expected, snapshot=None, before_snapshot=None):
    output = output.resolve()
    if not output.is_relative_to((ROOT / 'logs').resolve()):
        raise ValueError('Pilot output must stay under project logs/')
    output.mkdir(parents=True, exist_ok=False)
    payloads = {'plan.json': plan, 'screening30.json': {'status': 'PREPARED_NOT_EXECUTED', 'cases': selected},
                'expected-chunks.json': {'source': SOURCE, 'sourceVersion': SOURCE_VERSION, 'chunks': expected}}
    if snapshot is not None:
        payloads['index-snapshot-comparison.json'] = compare_index(snapshot, expected, before_snapshot)
    for name, payload in payloads.items():
        (output / name).write_bytes((json.dumps(payload, ensure_ascii=False, indent=2) + '\n').encode('utf-8'))
    (output / 'read-only-index-snapshot.sql').write_bytes(SNAPSHOT_SQL.encode('utf-8'))
    hashes = {path.name: digest(path.read_bytes()) for path in output.iterdir() if path.is_file()}
    (output / 'checksums.json').write_bytes((json.dumps(hashes, indent=2) + '\n').encode('utf-8'))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', type=Path, default=ROOT / 'logs/backend/rag-boundary-fix-20261021-r1/candidate-chunks.json')
    parser.add_argument('--snapshot', type=Path, help='Existing sanitized local JSON snapshot; no connection is opened')
    parser.add_argument('--before-snapshot', type=Path, help='Optional earlier local snapshot for admin preservation comparison')
    parser.add_argument('--output-directory', type=Path, default=ROOT / 'logs/backend' /
                        ('rag-screening-pilot-prepared-' + datetime.now().strftime('%Y%m%d-%H%M%S-%f')))
    args = parser.parse_args()
    if args.before_snapshot and not args.snapshot:
        parser.error('--before-snapshot requires --snapshot')
    paths = {'corpus': KNOWLEDGE / 'diet_guides.md', 'dataset': KNOWLEDGE / 'rag_eval_cases.json',
             'metadata': KNOWLEDGE / 'diet_guides_metadata.json', 'suite': KNOWLEDGE / 'rag_chunking_eval_suite.json',
             'manifest': args.manifest}
    raw = {key: path.read_bytes() for key, path in paths.items()}
    plan, selected, expected = prepare(raw['corpus'], *[json.loads(raw[key]) for key in ('dataset', 'metadata', 'suite', 'manifest')])
    plan['inputFileSha256'] = {key: digest(value) for key, value in raw.items()}
    snapshot = json.loads(args.snapshot.read_text(encoding='utf-8')) if args.snapshot else None
    before_snapshot = json.loads(args.before_snapshot.read_text(encoding='utf-8')) if args.before_snapshot else None
    export(args.output_directory, plan, selected, expected, snapshot, before_snapshot)
    print(json.dumps({'status': plan['status'], 'caseCount': 30, 'retrievalQueryCount': 28, 'chunkCount': 1351,
                      'plannedMaximumNewVectorRequests': plan['plannedMaximumNewVectorRequests'],
                      'conservativeInputAdmissionUnits': plan['conservativeInputAdmissionUnits'],
                      'providerCallsMade': 0, 'output': str(args.output_directory)}, ensure_ascii=False))


if __name__ == '__main__':
    main()
