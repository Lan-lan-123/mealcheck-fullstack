"""Rebind exact RAG anchors after a splitter-only change, without inferring relevance.

Targets containing several quotes mean AND; alternatives inside a required group
mean OR. Distributing these expressions preserves coverage when quotes now live
in different chunks. Missing quotes and expanded negative contexts require an
explicit follow-up review. This tool never installs resources or calls APIs.
"""
import argparse
from collections import Counter, defaultdict
from copy import deepcopy
from datetime import datetime
import hashlib
import json
from pathlib import Path
import re

from migrate_rag_longform_labels import normalized

ROOT = Path(__file__).resolve().parents[2]
KNOWLEDGE = ROOT / 'backend/src/main/resources/knowledge'
REVISION = 'rag-evidence-v6-boundary-aligned'
BOUNDS = {'SHORT': (2, 3), 'MEDIUM': (4, 6), 'LONG': (7, 10), 'EXTENDED': (12, 20)}


class MigrationBlocked(ValueError):
    def __init__(self, issues):
        self.issues = issues
        super().__init__(f'Boundary migration blocked by {len(issues)} issues requiring review')


def validate_manifest(manifest):
    chunks = manifest['chunks']
    by_id = {}
    keys = set()
    for index, chunk in enumerate(chunks):
        identity = hashlib.sha256((normalized(chunk['title']) + '\n\n' + normalized(chunk['content'])).encode()).hexdigest()
        if identity != chunk['contentHash'] or identity in by_id or chunk['chunkKey'] in keys:
            raise ValueError('Chunk identity or unique key validation failed')
        if chunk['chunkIndex'] != index:
            raise ValueError('Chunk indices must describe the actual ordered manifest')
        by_id[identity] = chunk
        keys.add(chunk['chunkKey'])
    if manifest['chunkCount'] != len(chunks) or manifest['topicCount'] != len({c['title'] for c in chunks}):
        raise ValueError('Manifest summary differs from actual chunks')
    return by_id


def simplify_cnf(clauses):
    """Deduplicate and remove supersets: A AND (A OR B) is A."""
    unique = sorted({frozenset(c) for c in clauses}, key=lambda c: (len(c), sorted(c)))
    result = []
    for clause in unique:
        if not clause or not any(existing <= clause for existing in result):
            result.append(clause)
    return result


def disjoin_cnf(formulas, limit=4096):
    result = [frozenset()]
    for formula in formulas:
        if len(result) * len(formula) > limit:
            raise ValueError('Coverage CNF expansion exceeds the explicit review limit')
        result = simplify_cnf(a | b for a in result for b in formula)
    return result


def sentence_suggestions(quote, chunks):
    """Hints only. Main migration never automatically replaces an original quote."""
    segments = re.findall(r'.+?(?:[。！？]+[”’」』）)]*|$)', quote, re.S)
    return [{'quote': segment, 'candidateKnowledgeIds': [c['contentHash'] for c in chunks
             if normalized(segment) and normalized(segment) in normalized(c['content'])]}
            for segment in segments if segment.strip()]


def rebind_rows(rows, before_manifest, manifest, splitter_revision='recursive-boundary-v2', max_groups=4096,
                adjudications=None, new_manifest_sha256=None):
    before = validate_manifest(before_manifest)
    after = validate_manifest(manifest)
    if before_manifest['corpusSha256'] != manifest['corpusSha256']:
        raise ValueError('Splitter-only migration requires an unchanged corpus hash')
    decisions, used_decisions = {}, set()
    originals = {row['id']: row for row in rows}
    for decision in adjudications or []:
        key = (decision['caseId'], decision['previousKnowledgeId'], decision['newKnowledgeId'])
        grade = decision['relevance']
        if key in decisions or key[0] not in originals or key[1] not in before or key[2] not in after:
            raise ValueError('Adjudication requires unique existing case and source identities')
        if not any(t['knowledgeId'] == key[1] and t['relevance'] == 0
                   for t in originals[key[0]].get('irrelevantEvidence', [])):
            raise ValueError('Explicit boundary adjudication must identify a previous negative target')
        if (grade != 'UNJUDGED' and (type(grade) is not int or grade not in (0, 1, 2))):
            raise ValueError('Adjudication relevance must be 0, 1, 2 or UNJUDGED')
        if not decision.get('reviewer') or not decision.get('rationale') or not new_manifest_sha256:
            raise ValueError('Explicit adjudication requires reviewer, rationale and manifest fingerprint')
        if decision.get('corpusSha256') != manifest['corpusSha256'] or decision.get('newManifestSha256') != new_manifest_sha256:
            raise ValueError('Adjudication source fingerprint differs from the new manifest')
        if before[key[1]]['title'] != after[key[2]]['title'] or not decision.get('quotes') or any(
                not isinstance(q, str) or not q.strip() or normalized(q) not in normalized(after[key[2]]['content'])
                for q in decision['quotes']):
            raise ValueError('Adjudication quotes or title do not match the new source')
        decisions[key] = decision
    by_title = defaultdict(list)
    for chunk in manifest['chunks']:
        by_title[chunk['title']].append(chunk)
    result, issues, statistics = [], [], Counter()
    for original in rows:
        row = deepcopy(original)
        case_id = row['id']
        formulas, new_targets, old_ids = {}, {}, set()
        case_issues = []
        for field in ('evidenceTargets', 'irrelevantEvidence'):
            for target in row.get(field, []):
                old_id, grade = target['knowledgeId'], target['relevance']
                if old_id in old_ids or old_id not in before or grade not in (0, 1, 2):
                    raise ValueError(case_id + ': duplicate, missing or invalid prior target')
                old_ids.add(old_id)
                source = before[old_id]
                if source['title'] != target['title'] or not target.get('evidenceQuotes') or any(
                        not isinstance(q, str) or not q.strip() or normalized(q) not in normalized(source['content'])
                        for q in target['evidenceQuotes']):
                    raise ValueError(case_id + ': prior source or quote does not match the before manifest')
                quote_clauses = []
                for quote in target['evidenceQuotes']:
                    candidates = [chunk for chunk in by_title[target['title']]
                                  if normalized(quote) in normalized(chunk['content'])]
                    if not candidates:
                        case_issues.append({'caseId': case_id, 'kind': 'ANCHOR_SPLIT_OR_MISSING',
                            'oldKnowledgeId': old_id, 'title': target['title'], 'relevance': grade,
                            'quote': quote, 'sentenceSuggestions': sentence_suggestions(quote, by_title[target['title']])})
                        continue
                    quote_clauses.append(frozenset(c['contentHash'] for c in candidates))
                    for chunk in candidates:
                        identity = chunk['contentHash']
                        key = (case_id, old_id, identity)
                        decision = decisions.get(key)
                        actual_grade = decision['relevance'] if decision else grade
                        if decision and key not in used_decisions:
                            row.setdefault('boundaryPriorNegativeAdjudications', []).append({
                                'previousTarget': deepcopy(target), 'decision': deepcopy(decision),
                                'independentlyAnnotated': False})
                            used_decisions.add(key)
                            statistics['explicitNegativeContextAdjudications'] += 1
                            if actual_grade == 'UNJUDGED':
                                statistics['negativeTargetsChangedToUnjudged'] += 1
                        if grade == 0 and not decision and normalized(chunk['content']) not in normalized(source['content']):
                            case_issues.append({'caseId': case_id, 'kind': 'NEGATIVE_CONTEXT_EXPANDED',
                                'oldKnowledgeId': old_id, 'newKnowledgeId': identity, 'title': target['title'],
                                'detail': 'A former irrelevant quote cannot label newly included surrounding content irrelevant without review.'})
                        if actual_grade == 'UNJUDGED':
                            continue
                        if identity not in new_targets:
                            item = deepcopy(target)
                            for obsolete in ('previousKnowledgeId', 'previousKnowledgeIds', 'previousRelevance'):
                                item.pop(obsolete, None)
                            item.update(knowledgeId=identity, title=chunk['title'], chunkKey=chunk['chunkKey'], relevance=actual_grade,
                                        evidenceQuotes=[], boundaryMigrationSources=[],
                                        reviewProvenance='EXACT_ANCHOR_BOUNDARY_MIGRATION_NOT_NEW_SEMANTIC_REVIEW')
                            new_targets[identity] = item
                        item = new_targets[identity]
                        if (item['relevance'] == 0) != (actual_grade == 0):
                            case_issues.append({'caseId': case_id, 'kind': 'POSITIVE_NEGATIVE_COLLISION',
                                'newKnowledgeId': identity, 'oldKnowledgeId': old_id, 'title': target['title']})
                        item['relevance'] = max(item['relevance'], actual_grade)
                        for current_quote in decision['quotes'] if decision else [quote]:
                            if current_quote not in item['evidenceQuotes']:
                                item['evidenceQuotes'].append(current_quote)
                        if decision:
                            item['boundaryContextAdjudication'] = deepcopy(decision)
                            item['rationale'] = decision['rationale']
                            item['reviewProvenance'] = 'CODEX_EXPLICIT_NEW_BOUNDARY_CONTEXT_REVIEW'
                        if not any(t['knowledgeId'] == old_id for t in item['boundaryMigrationSources']):
                            item['boundaryMigrationSources'].append(deepcopy(target))
                if len(quote_clauses) == len(target['evidenceQuotes']):
                    formulas[old_id] = simplify_cnf(quote_clauses)
        if case_issues:
            issues.extend(case_issues)
            continue
        groups = []
        for old_group in row['requiredEvidenceGroups']:
            if not old_group or any(identity not in formulas for identity in old_group):
                raise ValueError(case_id + ': coverage group does not reference valid prior evidence')
            if any(before_target['relevance'] != 2 for before_target in row['evidenceTargets']
                   if before_target['knowledgeId'] in old_group):
                raise ValueError(case_id + ': required group contains background evidence')
            try:
                groups.extend(disjoin_cnf([formulas[identity] for identity in old_group], max_groups))
            except ValueError as error:
                issues.append({'caseId': case_id, 'kind': 'COVERAGE_COMPLEXITY', 'detail': str(error)})
                break
        else:
            for identity, item in new_targets.items():
                body = normalized(after[identity]['content'])
                complete, partial = [], []
                for source_target in item['boundaryMigrationSources']:
                    is_complete = all(normalized(q) in body for q in source_target['evidenceQuotes'])
                    (complete if is_complete else partial).append(source_target['knowledgeId'])
                item['boundaryQuoteFacets'] = {'completePreviousTargets': complete, 'partialPreviousTargets': partial}
                if len(item['boundaryMigrationSources']) > 1:
                    reasons = list(dict.fromkeys(f"原等级 {source['relevance']}：{source['rationale']}"
                                                  for source in item['boundaryMigrationSources']))
                    item['rationale'] = ('边界调整合并原文锚点，保留各原判断并取有依据的最高相关等级；'
                                         '完整覆盖仍按必要引句面检查。' + ' '.join(reasons))
                    if item.get('boundaryContextAdjudication'):
                        item['rationale'] += ' 新上下文显式裁决：' + item['boundaryContextAdjudication']['rationale']
            row['evidenceTargets'] = [item for item in new_targets.values() if item['relevance'] > 0]
            negatives = [item for item in new_targets.values() if item['relevance'] == 0]
            if negatives or 'irrelevantEvidence' in row:
                row['irrelevantEvidence'] = negatives
            row['requiredEvidenceGroups'] = [sorted(group) for group in simplify_cnf(groups)]
            if 'evidenceGroupRationales' in row:
                row['previousEvidenceGroupRationales'] = row.pop('evidenceGroupRationales')
            row['coverageMigrationPolicy'] = 'AND_OF_EXACT_QUOTE_FACETS_WITH_OR_ALTERNATIVES_CNF'
            row['previousAnnotationRevision'] = row['annotationRevision']
            row['annotationRevision'] = REVISION
            row['annotation'].update(status='PENDING_HUMAN_REVIEW', independentlyAnnotated=False,
                corpusSha256=manifest['corpusSha256'], splitterRevision=splitter_revision,
                boundaryBindingReview='EXACT_ANCHOR_MIGRATION_PENDING_CONTEXT_REVIEW',
                originalReviewScopePreserved=True, newBoundaryContextsIndependentlyReviewed=False,
                unlistedChunkPolicy='UNJUDGED_NOT_IRRELEVANT')
            statistics['reboundCases'] += 1
            statistics['beforeTargets'] += len(original['evidenceTargets']) + len(original.get('irrelevantEvidence', []))
            statistics['afterTargets'] += len(new_targets)
            statistics['beforeCoverageGroups'] += len(original['requiredEvidenceGroups'])
            statistics['afterCoverageGroups'] += len(row['requiredEvidenceGroups'])
            result.append(row)
    if set(decisions) != used_decisions:
        raise ValueError('Adjudication includes a source outside the actual anchor migration candidates')
    if issues:
        raise MigrationBlocked(issues)
    return result, dict(statistics)


def update_metadata(metadata, before_manifest, manifest, splitter_revision='recursive-boundary-v2'):
    validate_manifest(manifest)
    if metadata['corpusSha256'] != manifest['corpusSha256'] or before_manifest['corpusSha256'] != manifest['corpusSha256']:
        raise ValueError('Corpus bytes changed; this is not a boundary-only migration')
    if (manifest['minTokens'], manifest['maxTokens'], manifest['overlapTokens']) != (300, 500, 50):
        raise ValueError('Original plan requires unchanged default token settings')
    counts = Counter(c['title'] for c in manifest['chunks'])
    topics = metadata['topics']
    if len(topics) != 200 or set(counts) != {t['title'] for t in topics}:
        raise ValueError('All original 200 topics are required')
    if Counter(t['lengthBucket'] for t in topics) != {'SHORT': 40, 'MEDIUM': 60, 'LONG': 60, 'EXTENDED': 40}:
        raise ValueError('Original 40/60/60/40 distribution changed')
    for chunk in manifest['chunks']:
        if not 300 <= chunk['tokenCount'] <= 500:
            raise ValueError('Actual chunk exceeds original token limits: ' + chunk['chunkKey'])
    result = deepcopy(metadata)
    for topic in result['topics']:
        bounds = BOUNDS[topic['lengthBucket']]
        if (topic['minChunks'], topic['maxChunks']) != bounds or not bounds[0] <= counts[topic['title']] <= bounds[1]:
            raise ValueError('Original per-topic length plan failed: ' + topic['title'])
        topic.update(previousActualChunks=topic['actualChunks'], actualChunks=counts[topic['title']], lengthPlanSatisfied=True)
    result.update(previousChunkCount=metadata['chunkCount'], chunkCount=manifest['chunkCount'],
                  splitterRevision=splitter_revision, independentlyReviewed=False,
                  indexSourceVersion='v8-longform-200-boundary-v2',
                  lengthGoalDeviationCount=0, lengthPlanStatus='SATISFIED')
    result['splitter']['revision'] = splitter_revision
    result['splitter']['overlapPolicy'] = 'SENTENCE_ALIGNED_BEST_EFFORT_CONFIGURED_BUDGET'
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--before-manifest', type=Path, required=True)
    parser.add_argument('--manifest', type=Path, required=True)
    parser.add_argument('--dataset', type=Path, default=KNOWLEDGE / 'rag_eval_cases.json')
    parser.add_argument('--metadata', type=Path, default=KNOWLEDGE / 'diet_guides_metadata.json')
    parser.add_argument('--splitter-revision', default='recursive-boundary-v2')
    parser.add_argument('--adjudications', type=Path, help='Explicit reviewed negative-context decisions, never inferred')
    parser.add_argument('--output-directory', type=Path, default=ROOT / 'logs/backend' /
                        ('rag-boundary-rebind-' + datetime.now().strftime('%Y%m%d-%H%M%S-%f')))
    args = parser.parse_args()
    output = args.output_directory.resolve()
    if not output.is_relative_to((ROOT / 'logs').resolve()):
        raise ValueError('Output must remain under project logs/')
    previous = json.loads(args.before_manifest.read_text(encoding='utf-8'))
    actual = json.loads(args.manifest.read_text(encoding='utf-8'))
    rows = json.loads(args.dataset.read_text(encoding='utf-8'))
    metadata = json.loads(args.metadata.read_text(encoding='utf-8'))
    if hashlib.sha256((KNOWLEDGE / 'diet_guides.md').read_bytes()).hexdigest() != actual['corpusSha256']:
        raise ValueError('Manifest does not match the current unchanged corpus')
    revised_metadata = update_metadata(metadata, previous, actual, args.splitter_revision)
    output.mkdir(parents=True, exist_ok=False)
    report = {'corpusSha256': actual['corpusSha256'], 'beforeChunks': previous['chunkCount'],
              'afterChunks': actual['chunkCount'], 'caseCount': len(rows), 'providerCalls': 0,
              'installed': False, 'independentlyAnnotated': False,
              'splitterRevision': args.splitter_revision}
    adjudications = []
    if args.adjudications:
        adjudications = json.loads(args.adjudications.read_text(encoding='utf-8'))['decisions']
        report['adjudicationsSha256'] = hashlib.sha256(args.adjudications.read_bytes()).hexdigest()
    try:
        revised_rows, counts = rebind_rows(rows, previous, actual, args.splitter_revision,
            adjudications=adjudications, new_manifest_sha256=hashlib.sha256(args.manifest.read_bytes()).hexdigest())
    except MigrationBlocked as blocked:
        report.update(status='BLOCKED_EXPLICIT_REVIEW_REQUIRED', issues=blocked.issues,
                      issueCounts=dict(Counter(issue['kind'] for issue in blocked.issues)))
        (output / 'migration-report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        print(json.dumps({key: value for key, value in report.items() if key != 'issues'}, ensure_ascii=False))
        print(str(output / 'migration-report.json'))
        raise SystemExit(2)
    report.update(status='CANDIDATES_READY_FOR_REVIEW', statistics=counts, issues=[])
    for name, value in [('candidate-rag-eval-cases.json', revised_rows), ('candidate-metadata.json', revised_metadata),
                        ('migration-report.json', report)]:
        (output / name).write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(report, ensure_ascii=False))


if __name__ == '__main__':
    main()
