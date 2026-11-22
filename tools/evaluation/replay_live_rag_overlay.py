"""Apply an explicit retained-top5 review to copies and replay saved evidence offline.

No database, environment credentials, provider or retrieval calls are used. The
original run and canonical resources are never changed.
"""
import argparse
from copy import deepcopy
from datetime import datetime
import hashlib
import json
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[2]
SCHEMA = 'rag-live-review-overlay-v1'
SCOPE = 'ACTUAL_RETAINED_TOP5_UNJUDGED_ONLY'
REVISION = 'rag-evidence-v6-live-screening-review-overlay-v1'


def read(path):
    return json.loads(path.read_text(encoding='utf-8'))


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def normalize(value):
    return re.sub(r'\s+', ' ', value.strip())


def content_id(title, content):
    return hashlib.sha256((normalize(title) + '\n\n' + normalize(content)).encode('utf-8')).hexdigest()


def targets(baseline, rows, manifest):
    by_case = {r['id']: r for r in rows}
    chunks = {c['contentHash']: c for c in manifest['chunks']}
    if len(by_case) != len(rows) or len(chunks) != len(manifest['chunks']):
        raise ValueError('Duplicate dataset or manifest identities')
    result, seen_cases = {}, set()
    for case in baseline['results']:
        case_id = case['caseId']
        if case_id in seen_cases or case_id not in by_case:
            raise ValueError('Unknown or duplicate captured case')
        seen_cases.add(case_id)
        expected = by_case[case_id]
        if case['question'] != expected['question']:
            raise ValueError('Captured question differs from frozen dataset')
        retrieval = case.get('capturedRetrieval')
        if expected['answerability'] == 'OUT_OF_SCOPE':
            if retrieval or case.get('state') != 'SKIPPED_OUT_OF_SCOPE':
                raise ValueError('Out-of-scope case unexpectedly retrieved')
            continue
        if not retrieval or retrieval['status'] != 'SUCCESS' or retrieval['query'] != expected['question']:
            raise ValueError('Missing successful captured retrieval')
        hits = retrieval['hits']
        if len(hits) > 5 or len({h['knowledgeId'] for h in hits}) != len(hits):
            raise ValueError('Invalid retained top5')
        known = {t['knowledgeId'] for field in ('evidenceTargets', 'irrelevantEvidence') for t in expected.get(field, [])}
        for hit in hits:
            identity = hit['knowledgeId']
            if content_id(hit['title'], hit['content']) != identity:
                raise ValueError('Captured hit content identity mismatch')
            chunk = chunks.get(identity)
            if not chunk or chunk['title'] != hit['title'] or chunk['content'] != hit['content']:
                raise ValueError('Captured hit differs from frozen manifest')
            if identity not in known:
                result[(case_id, identity)] = {'caseId': case_id, 'knowledgeId': identity,
                    'question': expected['question'], 'title': hit['title'], 'content': hit['content'],
                    'requiredEvidenceGroups': deepcopy(expected['requiredEvidenceGroups']),
                    'evidenceGroupRationales': deepcopy(expected.get('evidenceGroupRationales', [])),
                    'existingEvidenceTargets': deepcopy(expected['evidenceTargets'])}
    return result


def apply_overlay(baseline, rows, manifest, overlay, fingerprints):
    if overlay.get('schemaVersion') != SCHEMA or overlay.get('reviewScope') != SCOPE:
        raise ValueError('Unexpected overlay schema or scope')
    if overlay.get('independentlyAnnotated') is not False or not str(overlay.get('reviewer', '')).strip():
        raise ValueError('Codex review must declare false independence and reviewer')
    if overlay.get('source') != fingerprints:
        raise ValueError('Overlay source fingerprints changed')
    pool = targets(baseline, rows, manifest)
    judgments = overlay.get('reviewedPairs', [])
    keys = [(j.get('caseId'), j.get('knowledgeId')) for j in judgments]
    if len(set(keys)) != len(keys) or set(keys) != set(pool):
        raise ValueError('Review must exactly cover retained-top5 UNJUDGED pairs; partial or raw-only pool rejected')
    revised = deepcopy(rows)
    by_id = {r['id']: r for r in revised}
    chunks = {c['contentHash']: c for c in manifest['chunks']}
    for judgment in judgments:
        key = (judgment['caseId'], judgment['knowledgeId'])
        grade = judgment.get('relevance')
        if type(grade) is not int or grade not in (0, 1, 2):
            raise ValueError('Relevance must be integer 0, 1 or 2; uncertainty cannot become irrelevant')
        quotes = judgment.get('evidenceQuotes')
        if not isinstance(quotes, list) or not quotes or any(not isinstance(q, str) or not q.strip()
                or normalize(q) not in normalize(pool[key]['content']) for q in quotes):
            raise ValueError('Review requires exact source quotes')
        if not isinstance(judgment.get('rationale'), str) or not judgment['rationale'].strip():
            raise ValueError('Review rationale required')
        groups = judgment.get('alternativeForGroups')
        row = by_id[key[0]]
        if not isinstance(groups, list) or any(type(g) is not int or not 0 <= g < len(row['requiredEvidenceGroups']) for g in groups):
            raise ValueError('Invalid alternative group index')
        if len(groups) != len(set(groups)) or (groups and grade != 2):
            raise ValueError('Only direct evidence can be a group alternative')
        chunk = chunks[key[1]]
        label = {'knowledgeId': key[1], 'title': chunk['title'], 'chunkKey': chunk['chunkKey'],
                 'relevance': grade, 'evidenceQuotes': quotes, 'rationale': judgment['rationale'],
                 'reviewProvenance': 'CODEX_CAPTURED_TOP5_TEXT_REVIEW_NOT_INDEPENDENT'}
        row.setdefault('irrelevantEvidence' if grade == 0 else 'evidenceTargets', []).append(label)
        for group in groups:
            row['requiredEvidenceGroups'][group].append(key[1])
        row['annotationRevision'] = REVISION
        row['annotation'] = {**row['annotation'], 'independentlyAnnotated': False,
            'status': 'PENDING_HUMAN_REVIEW', 'liveTop5ReviewScope': SCOPE,
            'liveTop5ReviewSource': fingerprints['resultsSha256'], 'unlistedChunkPolicy': 'UNJUDGED_NOT_IRRELEVANT'}
    return revised, pool


def require_completed_run(results_path, dataset_path, manifest_path, runtime):
    if runtime.get('mode') != 'EXECUTE' or runtime.get('state') != 'COMPLETE' or runtime.get('exitCode') != 0:
        raise ValueError('Only a completed successful live execution may be replayed')
    if runtime.get('outputSha256', {}).get(results_path.name) != sha(results_path):
        raise ValueError('Captured results checksum mismatch')
    hashes = runtime.get('sourceAndInputSha256', {})
    for path in (dataset_path, manifest_path):
        if hashes.get(str(path.resolve())) != sha(path):
            raise ValueError('Live run source binding changed: ' + path.name)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--results', type=Path, required=True)
    parser.add_argument('--dataset', type=Path, default=ROOT / 'backend/src/main/resources/knowledge/rag_eval_cases.json')
    parser.add_argument('--manifest', type=Path, default=ROOT / 'logs/backend/rag-boundary-fix-20261021-r1/candidate-chunks.json')
    parser.add_argument('--overlay', type=Path)
    parser.add_argument('--export-template', action='store_true')
    parser.add_argument('--output-directory', type=Path, default=ROOT / 'logs/backend' /
                        ('rag-live-overlay-' + datetime.now().strftime('%Y%m%d-%H%M%S-%f')))
    args = parser.parse_args()
    if bool(args.overlay) == args.export_template:
        parser.error('Choose exactly one of --overlay or --export-template')
    runtime_path = args.results.parent / 'runtime.json'
    runtime = read(runtime_path)
    require_completed_run(args.results, args.dataset, args.manifest, runtime)
    baseline, rows, manifest = read(args.results), read(args.dataset), read(args.manifest)
    if baseline.get('executionMode') != 'LIVE_PRODUCTION_PGVECTOR_RETRIEVAL_SCREENING' or len(baseline['results']) != 30:
        raise ValueError('Expected completed fixed screening30 live capture')
    fingerprints = {'resultsSha256': sha(args.results), 'datasetSha256': sha(args.dataset),
                    'manifestSha256': sha(args.manifest), 'corpusSha256': manifest['corpusSha256']}
    pool = targets(baseline, rows, manifest)
    output = args.output_directory.resolve()
    if not output.is_relative_to((ROOT / 'logs').resolve()) or output.exists():
        raise ValueError('Use a fresh output directory under logs/')
    output.mkdir(parents=True, exist_ok=False)
    def save(name, value):
        (output / name).write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    if args.export_template:
        save('review-overlay-template.json', {'schemaVersion': SCHEMA, 'reviewScope': SCOPE,
            'independentlyAnnotated': False, 'reviewer': 'Codex', 'source': fingerprints,
            'reviewedPairs': [{'caseId': c, 'knowledgeId': k, 'relevance': None, 'evidenceQuotes': [],
                'rationale': '', 'alternativeForGroups': []} for c, k in pool]})
        save('review-targets.json', list(pool.values()))
        print(json.dumps({'status': 'UNREVIEWED_TEMPLATE', 'reviewedPairTargetCount': len(pool), 'providerCalls': 0}))
        return
    overlay = read(args.overlay)
    revised, pool = apply_overlay(baseline, rows, manifest, overlay, fingerprints)
    (output / 'baseline-results.json').write_bytes(args.results.read_bytes())
    (output / 'baseline-dataset.json').write_bytes(args.dataset.read_bytes())
    (output / 'review-overlay.json').write_bytes(args.overlay.read_bytes())
    save('overlay-dataset.json', revised)
    source = ROOT / 'tools/evaluation/ReplayLiveRagOverlay.java'
    java_sources = [source] + [ROOT / 'backend/src/main/java/com/example/mealcheck' / p for p in (
        'service/RagChainAssessment.java', 'service/RagEvidenceLabels.java', 'dto/RagChainReport.java')]
    tracked = [args.results, args.dataset, args.manifest, args.overlay, runtime_path, *java_sources]
    before = {str(p.resolve()): sha(p) for p in tracked}
    for path in java_sources[1:]:
        if runtime['sourceAndInputSha256'].get(str(path.resolve())) != sha(path):
            raise ValueError('Production assessor changed since live capture')
    classes = output / 'classes'
    classes.mkdir()
    # The assessor uses captured DTOs only. Drop the unused external language
    # framework JAR from the original runtime; bundled libraries remain local.
    classpath = ';'.join(p for p in runtime['classpath'].split(';') if 'langchain4j' not in Path(p).name.lower())
    crash = ROOT / 'logs/crash'
    crash.mkdir(exist_ok=True)
    crash_flag = '-XX:ErrorFile=' + str(crash / 'hs_err_pid%p.log')
    subprocess.run(['javac', '-J' + crash_flag, '-encoding', 'UTF-8', '-cp', classpath,
                    '-d', str(classes), *map(str, java_sources)], cwd=ROOT, check=True)
    subprocess.run(['java', crash_flag, '-cp', str(classes) + ';' + classpath,
        'com.example.mealcheck.service.ReplayLiveRagOverlay', str(output / 'baseline-results.json'),
        str(output / 'baseline-dataset.json'), str(output / 'overlay-dataset.json'), str(args.manifest.resolve()),
        str(output / 'replayed-assessments.json')], cwd=ROOT, check=True)
    if before != {str(p.resolve()): sha(p) for p in tracked}:
        raise ValueError('Inputs or assessor changed during replay')
    save('summary.json', {'status': 'OFFLINE_LABEL_OVERLAY_REPLAY_COMPLETE', 'source': fingerprints,
        'reviewedPairs': len(pool), 'independentlyAnnotated': False, 'formalResourcesChanged': False,
        'providerCalls': 0, 'retrievalRepeated': False,
        'limitations': ['Selected retained-top5 review only; raw-only and unreturned chunks remain UNJUDGED.',
            'Score differences arise from relevance labels, not a new retrieval or system improvement.',
            'Codex review is not independent human factual validation.'], 'inputSha256': before})
    print(json.dumps({'status': 'OFFLINE_LABEL_OVERLAY_REPLAY_COMPLETE', 'reviewedPairs': len(pool), 'providerCalls': 0}))


if __name__ == '__main__':
    main()
