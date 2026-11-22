"""Validate literal assistant reviews and build a source-bound relevance revision offline.

This does not infer relevance from titles, call a provider, or declare human gold.
"""
import argparse
from collections import Counter
from copy import deepcopy
from datetime import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import shutil

from migrate_rag_longform_labels import normalized
from freeze_chunking_suite import build_suite, json_digest

ROOT = Path(__file__).resolve().parents[2]
KNOWLEDGE = ROOT / 'backend/src/main/resources/knowledge'
REVISION = 'rag-evidence-v5-reviewed-chunks'


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def build(rows, manifest, reviews):
    chunks = {chunk['contentHash']: chunk for chunk in manifest['chunks']}
    if len(chunks) != len(manifest['chunks']):
        raise ValueError('Duplicate source identities')
    for identity, chunk in chunks.items():
        actual = hashlib.sha256((normalized(chunk['title']) + '\n\n' + normalized(chunk['content'])).encode()).hexdigest()
        if identity != actual:
            raise ValueError('Chunk identity mismatch')
    by_id = {row['id']: row for row in rows}
    if len(by_id) != len(rows):
        raise ValueError('Duplicate case IDs')
    review_map = {}
    for packet in reviews:
        if packet.get('independentlyAnnotated') is not False or not packet.get('reviewer'):
            raise ValueError('Assistant review must not claim independent annotation')
        for review in packet['cases']:
            case_id = review['caseId']
            if case_id not in by_id or case_id in review_map:
                raise ValueError('Unknown or duplicate case review: ' + case_id)
            review_map[case_id] = review
    if set(review_map) != set(by_id):
        raise ValueError('Every question requires an explicit review record')
    output, decisions = deepcopy(rows), Counter()
    added, inspected_pairs, issues, prior_downgrades = 0, 0, [], 0
    for row in output:
        review = review_map[row['id']]
        inspected = review['inspectedKnowledgeIds']
        if len(set(inspected)) != len(inspected) or not set(inspected) <= set(chunks):
            raise ValueError('Invalid inspected source identities: ' + row['id'])
        if not review.get('notes'):
            raise ValueError('Review scope notes required')
        if row['answerability'] == 'OUT_OF_SCOPE':
            if inspected or review['judgments']:
                raise ValueError('Out-of-scope cases must not gain retrieval evidence')
        elif not inspected or not review['judgments']:
            raise ValueError('Every knowledge question needs a concrete reviewed candidate')
        positives = {t['knowledgeId']: t for t in row['evidenceTargets']}
        negatives = {t['knowledgeId']: t for t in row.get('irrelevantEvidence', [])}
        revised_prior = set()
        for revision in review.get('priorTargetRevisions', []):
            identity = revision['knowledgeId']
            if identity in revised_prior or identity not in positives or identity not in inspected:
                raise ValueError('Prior target adjudication requires a unique read original source')
            original = positives[identity]
            if original['relevance'] != 2 or revision.get('relevance') != 1 or not revision.get('rationale'):
                raise ValueError('Prior target revision only supports explicit direct-to-background downgrade')
            original.update(relevance=1, previousRelevance=2, rationale=revision['rationale'],
                            reviewProvenance='CODEX_EXPLICIT_PRIOR_TARGET_ADJUDICATION')
            revised_prior.add(identity)
            prior_downgrades += 1
        seen = set()
        for item in review['judgments']:
            identity, grade = item['knowledgeId'], item['relevance']
            if identity in seen or identity not in inspected or type(grade) is not int or grade not in (0, 1, 2):
                raise ValueError('Invalid or unread judgment: ' + row['id'])
            seen.add(identity)
            chunk = chunks[identity]
            quotes = item.get('evidenceQuotes', [])
            if not quotes or not item.get('rationale') or any(not isinstance(q, str) or not q.strip()
                    or normalized(q) not in normalized(chunk['content']) for q in quotes):
                raise ValueError('Judgment lacks exact source anchors: ' + row['id'])
            groups = item.get('alternativeForGroups', [])
            if len(set(groups)) != len(groups) or (grade != 2 and groups):
                raise ValueError('Only direct evidence can satisfy coverage groups')
            for index in groups:
                if type(index) is not int or not 0 <= index < len(row['requiredEvidenceGroups']):
                    raise ValueError('Invalid coverage group index')
            old = positives.get(identity) or negatives.get(identity)
            if old and old['relevance'] != grade:
                raise ValueError('Conflicting existing judgment requires explicit adjudication: ' + row['id'])
            target = {'knowledgeId': identity, 'title': chunk['title'], 'chunkKey': chunk['chunkKey'],
                      'relevance': grade, 'evidenceQuotes': quotes, 'rationale': item['rationale'],
                      'reviewProvenance': 'CODEX_ASSISTANT_TEXT_REVIEW_NOT_INDEPENDENT'}
            if old is None:
                (row['evidenceTargets'] if grade else row.setdefault('irrelevantEvidence', [])).append(target)
                added += 1
            for index in groups:
                group = row['requiredEvidenceGroups'][index]
                if identity not in group:
                    group.append(identity)
            decisions[grade] += 1
        for issue in review.get('chunkIssues', []):
            if issue['knowledgeId'] not in inspected or not issue.get('kind') or not issue.get('detail'):
                raise ValueError('Content issue requires a read source and explanation')
            issues.append({'caseId': row['id'], **issue})
        if review.get('coveragePlan'):
            plan = review['coveragePlan']
            groups = plan.get('groups', [])
            direct = {t['knowledgeId'] for t in row['evidenceTargets'] if t['relevance'] == 2}
            if not groups or len(groups) != len(plan.get('rationales', [])) or not all(plan['rationales']):
                raise ValueError('Coverage decomposition requires a rationale for every facet')
            for group in groups:
                if not group or len(set(group)) != len(group) or not set(group) <= direct:
                    raise ValueError('Coverage facets must reference unique direct evidence')
                if not any(set(old_group) <= set(group) for old_group in row['requiredEvidenceGroups']) and not (
                        revised_prior and plan.get('adjudicationReason')):
                    raise ValueError('Coverage decomposition must preserve original complete alternatives')
            row['requiredEvidenceGroups'] = groups
            row['evidenceGroupRationales'] = plan['rationales']
        elif revised_prior:
            raise ValueError('Downgrading prior direct evidence requires an explicit replacement coverage plan')
        inspected_pairs += len(inspected)
        row['annotationRevision'] = REVISION
        row['annotation'].update(status='PENDING_HUMAN_REVIEW', independentlyAnnotated=False,
            provenance='ORIGINAL_ANCHORS_PLUS_CODEX_ASSISTANT_REVIEW',
            coverage='ORIGINAL_ANCHORS_PLUS_SELECTED_CHUNK_REVIEW', additionalChunkRelevanceReview='PARTIAL_ASSISTANT_REVIEW',
            corpusSha256=manifest['corpusSha256'], reviewedCandidateCount=len(inspected),
            explicitlyJudgedCandidateCount=len(review['judgments']),
            reviewScopeNotes=review['notes'], unlistedChunkPolicy='UNJUDGED_NOT_IRRELEVANT')
    summary = {'caseCount': len(rows), 'knowledgeCaseCount': sum(r['answerability'] != 'OUT_OF_SCOPE' for r in rows),
               'addedJudgments': added, 'reviewedCaseChunkPairs': inspected_pairs,
               'priorTargetDowngrades': prior_downgrades,
               'explicitReviewDecisions': dict(decisions), 'chunkIssues': issues,
               'independentlyAnnotated': False, 'coverage': 'SELECTED_CANDIDATES_NOT_EXHAUSTIVE',
               'corpusSha256': manifest['corpusSha256'], 'annotationRevision': REVISION}
    return output, summary


def structural_audit(manifest, corpus=None):
    """Diagnostic flags require reading; they are not automatically content defects."""
    flags = []
    sources = {}
    if corpus:
        for section in re.split(r'^## ', corpus, flags=re.M)[1:]:
            title, _, body = section.partition('\n')
            sources[title.strip()] = normalized(body)
    for chunk in manifest['chunks']:
        paragraphs = chunk['content'].strip().split('\n\n')
        if paragraphs[-1].startswith('**') and paragraphs[-1].endswith('**'):
            flags.append({'knowledgeId': chunk['contentHash'], 'title': chunk['title'],
                          'chunkIndex': chunk['chunkIndex'], 'kind': 'TRAILING_HEADING',
                          'detail': paragraphs[-1], 'status': 'DIAGNOSTIC_ONLY_OVERLAP_MAY_PRESERVE_CONTEXT'})
        if chunk['title'] in sources:
            source = sources[chunk['title']]
            content = normalized(chunk['content'])
            position = source.find(content)
            if position > 0 and source[position - 1] not in ' 。！？；.!?;':
                flags.append({'knowledgeId': chunk['contentHash'], 'title': chunk['title'],
                              'chunkIndex': chunk['chunkIndex'], 'kind': 'MID_SENTENCE_START',
                              'detail': chunk['content'][:100], 'status': 'CONTEXT_DIAGNOSTIC_NOT_FACTUAL_VERDICT'})
    return {'checkedChunks': len(manifest['chunks']), 'flags': flags,
            'flagCounts': dict(Counter(flag['kind'] for flag in flags)),
            'limitation': 'Formatting diagnostics and assistant evidence review do not establish independent factual accuracy.'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', type=Path, required=True)
    parser.add_argument('--dataset', type=Path, default=KNOWLEDGE / 'rag_eval_cases.json',
                        help='For reproduction, an archived before-rag-eval-cases.json may be supplied')
    parser.add_argument('--splits', type=Path, default=KNOWLEDGE / 'rag_eval_splits.json')
    parser.add_argument('--suite', type=Path, default=KNOWLEDGE / 'rag_chunking_eval_suite.json')
    parser.add_argument('--reviews', type=Path, default=ROOT / 'tools/evaluation/relevance_reviews_v1')
    parser.add_argument('--output-directory', type=Path, default=ROOT / 'logs/backend' /
                        ('rag-relevance-v5-' + datetime.now().strftime('%Y%m%d-%H%M%S-%f')))
    parser.add_argument('--install', action='store_true')
    args = parser.parse_args()
    output = args.output_directory.resolve()
    if not output.is_relative_to((ROOT / 'logs').resolve()):
        raise ValueError('Output must stay under logs/')
    path = args.dataset.resolve()
    if args.install and any(actual.resolve() != (KNOWLEDGE / name).resolve() for actual, name in (
            (path, 'rag_eval_cases.json'), (args.splits, 'rag_eval_splits.json'), (args.suite, 'rag_chunking_eval_suite.json'))):
        raise ValueError('Only the canonical dataset may be installed')
    original_bytes = path.read_bytes()
    manifest = json.loads(args.manifest.read_text(encoding='utf-8'))
    if args.install and manifest.get('splitterRevision') != json.loads(
            (KNOWLEDGE / 'diet_guides_metadata.json').read_text(encoding='utf-8')).get('splitterRevision'):
        raise ValueError('Historical chunk manifest differs from the installed splitter revision')
    if manifest['corpusSha256'] != sha(KNOWLEDGE / 'diet_guides.md'):
        raise ValueError('Manifest differs from installed corpus')
    files = sorted(args.reviews.glob('review-*.json'))
    if not files:
        raise ValueError('No literal review packets')
    packets = [json.loads(p.read_text(encoding='utf-8')) for p in files]
    for packet in packets:
        if packet.get('corpusSha256') != manifest['corpusSha256'] or packet.get('baseDatasetSha256') != hashlib.sha256(original_bytes).hexdigest():
            raise ValueError('Review packet source fingerprint differs from declared base')
    revised, summary = build(json.loads(original_bytes), manifest, packets)
    split_path = args.splits.resolve()
    suite_path = args.suite.resolve()
    split_bytes, suite_bytes = split_path.read_bytes(), suite_path.read_bytes()
    splits, previous_suite = json.loads(split_bytes), json.loads(suite_bytes)
    if previous_suite['source']['datasetSha256'] != json_digest(json.loads(original_bytes)):
        raise ValueError('Current frozen suite differs from the review base; resolve concurrent revision first')
    splits['datasetVersion'] = 'rag-200-v6'
    suite = build_suite(revised, (KNOWLEDGE / 'diet_guides.md').read_text(encoding='utf-8'))
    if {key: value['caseIds'] for key, value in previous_suite['sets'].items()} != {
            key: value['caseIds'] for key, value in suite['sets'].items()}:
        raise ValueError('Fixed pilot selection must not change')
    output.mkdir(parents=True, exist_ok=False)
    (output / 'before-rag-eval-cases.json').write_bytes(original_bytes)
    (output / 'before-rag-eval-splits.json').write_bytes(split_bytes)
    (output / 'before-rag-chunking-eval-suite.json').write_bytes(suite_bytes)
    (output / 'candidate-rag-eval-cases.json').write_text(json.dumps(revised, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    for name, value in [('candidate-rag-eval-splits.json', splits), ('candidate-rag-chunking-eval-suite.json', suite)]:
        (output / name).write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    (output / 'chunk-structure-audit.json').write_text(json.dumps(structural_audit(manifest,
        (KNOWLEDGE / 'diet_guides.md').read_text(encoding='utf-8')), ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    summary['reviewFiles'] = {p.name: sha(p) for p in files}
    summary['beforeDatasetSha256'] = hashlib.sha256(original_bytes).hexdigest()
    summary['candidateDatasetSha256'] = sha(output / 'candidate-rag-eval-cases.json')
    summary['installed'] = False
    if args.install:
        if path.read_bytes() != original_bytes or split_path.read_bytes() != split_bytes or suite_path.read_bytes() != suite_bytes:
            raise ValueError('Concurrent dataset change; refusing install')
        if shutil.disk_usage(ROOT).free < 10_000_000:
            raise ValueError('Insufficient disk space for safe staged install')
        updates = [(path, 'candidate-rag-eval-cases.json'), (split_path, 'candidate-rag-eval-splits.json'),
                   (suite_path, 'candidate-rag-chunking-eval-suite.json')]
        staged = []
        for destination, name in updates:
            stage = output / ('install-stage-' + destination.name)
            stage.write_bytes((output / name).read_bytes())
            staged.append((stage, destination))
        for stage, destination in staged:
            # Both absolute paths are within this project; same-volume replace is atomic per file.
            if not stage.resolve().is_relative_to(ROOT) or not destination.resolve().is_relative_to(KNOWLEDGE):
                raise ValueError('Install path escaped project')
            os.replace(stage, destination)
        summary['installed'] = True
    (output / 'summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({k: v for k, v in summary.items() if k not in ('chunkIssues', 'reviewFiles')}, ensure_ascii=False))
    print(str(output))


if __name__ == '__main__':
    main()
