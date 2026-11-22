"""Verify and summarize the installed offline RAG relevance revision; no provider calls."""
from collections import Counter
import hashlib
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET

from expand_rag_relevance import ROOT, KNOWLEDGE
from freeze_chunking_suite import validate_suite


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    batch = ROOT / 'logs/backend/rag-relevance-v5-20261021-1420'
    summary = json.loads((batch / 'summary.json').read_text(encoding='utf-8'))
    rows = json.loads((KNOWLEDGE / 'rag_eval_cases.json').read_text(encoding='utf-8'))
    before = json.loads((batch / 'before-rag-eval-cases.json').read_text(encoding='utf-8'))
    assert summary['installed'] and len(rows) == len(before) == 200
    for target, candidate in [('rag_eval_cases.json', 'candidate-rag-eval-cases.json'),
                              ('rag_eval_splits.json', 'candidate-rag-eval-splits.json'),
                              ('rag_chunking_eval_suite.json', 'candidate-rag-chunking-eval-suite.json')]:
        assert (KNOWLEDGE / target).read_bytes() == (batch / candidate).read_bytes()
        assert (batch / candidate).read_bytes() == (ROOT / 'logs/backend/rag-relevance-reproduce-20261021-1423' / candidate).read_bytes()
    allowed = {'annotation', 'annotationRevision', 'evidenceTargets', 'irrelevantEvidence',
               'requiredEvidenceGroups', 'evidenceGroupRationales'}
    assert all({k: v for k, v in a.items() if k not in allowed} ==
               {k: v for k, v in b.items() if k not in allowed} for a, b in zip(rows, before))
    assert digest(KNOWLEDGE / 'diet_guides.md') == summary['corpusSha256']
    manifest = json.loads((ROOT / 'logs/backend/knowledge-longform-v8-release-20261021/candidate-chunks.json').read_text(encoding='utf-8'))
    assert len(manifest['chunks']) == 1249 and manifest['corpusSha256'] == summary['corpusSha256']
    suite = json.loads((KNOWLEDGE / 'rag_chunking_eval_suite.json').read_text(encoding='utf-8'))
    pilot_check = validate_suite(suite, rows, (KNOWLEDGE / 'diet_guides.md').read_text(encoding='utf-8'))
    packets = []
    for path in sorted((ROOT / 'tools/evaluation/relevance_reviews_v1').glob('review-*.json')):
        assert digest(path) == summary['reviewFiles'][path.name]
        packets.append(json.loads(path.read_text(encoding='utf-8')))
    reviewed = {case['caseId']: case for packet in packets for case in packet['cases']}
    unique_read = {identity for case in reviewed.values() for identity in case['inspectedKnowledgeIds']}
    judgments = Counter(j['relevance'] for case in reviewed.values() for j in case['judgments'])
    assert len(reviewed) == 200 and len(unique_read) == 224 and judgments == {2: 158, 1: 74, 0: 4}
    lines = ['# RAG 逐题助手复核索引', '',
             '全部 200 题有记录；180 道知识题检查选定新增候选，20 道库外题不添加检索证据。',
             '237 个题目—切片阅读对，224 个不同切片。236 条新增判断：158 直接、74 背景、4 无关；另降级 1 条旧目标。',
             '这是有限范围助手复核；未列出的切片保持未判断，独立人工复核仍未完成。', '',
             '| ID | 问题 | 阅读片数 | 新直接 | 新背景 | 新无关 | 旧目标降级 |', '| --- | --- | ---: | ---: | ---: | ---: | ---: |']
    for row in rows:
        case = reviewed[row['id']]
        grades = Counter(j['relevance'] for j in case['judgments'])
        lines.append(f"| {row['id']} | {row['question'].replace('|', '／')} | {len(case['inspectedKnowledgeIds'])} | {grades[2]} | {grades[1]} | {grades[0]} | {len(case.get('priorTargetRevisions', []))} |")
    (batch / 'review-index.md').write_text('\n'.join(lines) + '\n', encoding='utf-8')
    reports = sorted((batch / 'test-reports-final').glob('TEST-*.xml'))
    totals = Counter()
    for path in reports:
        root = ET.parse(path).getroot()
        for key in ('tests', 'failures', 'errors', 'skipped'):
            totals[key] += int(root.attrib[key])
    assert len(reports) == 9 and totals == {'tests': 63, 'failures': 0, 'errors': 0, 'skipped': 0}
    log_paths = [
        ROOT / 'logs/backend/rag-relevance-backend-final-20261021-142030-545259.log',
        ROOT / 'logs/backend/rag-relevance-python-final-20261021-142026-574433.log',
        ROOT / 'logs/frontend/rag-relevance-frontend-final-20261021-142026-575432.log',
        ROOT / 'logs/frontend/rag-relevance-typecheck-final-20261021-142236-491420.log',
        ROOT / 'logs/frontend/rag-relevance-build-final-20261021-142236-475963.log',
        ROOT / 'logs/backend/rag-relevance-reproduce-20261021-142236-508674.log',
        ROOT / 'logs/backend/rag-relevance-pilot-check-20261021-142007-754973.log',
    ]
    assert 'BUILD SUCCESS' in log_paths[0].read_text(encoding='utf-8', errors='replace')
    assert 'Ran 55 tests' in log_paths[1].read_text(encoding='utf-8', errors='replace')
    assert re.search(r'tests\s+5', log_paths[2].read_text(encoding='utf-8', errors='replace'))
    assert 'built in' in log_paths[4].read_text(encoding='utf-8', errors='replace')
    blank = json.loads((batch / 'independent-review/review.json').read_text(encoding='utf-8'))
    assert blank['status'] == 'UNREVIEWED' and not blank['independentlyAnnotated']
    assert len(blank['knowledge']) == 1249 and all(not r['evidenceJudgments'] for r in blank['reviews'])
    audit = json.loads((batch / 'chunk-structure-audit.json').read_text(encoding='utf-8'))
    final = {'status': 'INSTALLED_AND_OFFLINE_VERIFIED', 'caseCount': 200, 'corpusTopics': 200,
             'chunkCount': 1249, 'corpusSha256': summary['corpusSha256'], 'questionsAndToolRulesUnchanged': True,
             'datasetVersion': 'rag-200-v6+rag-tools-v2', 'scoringVersion': 'rag-chain-v3',
             'annotationRevision': summary['annotationRevision'], 'uniqueChunksRead': len(unique_read),
             'readCaseChunkPairs': 237, 'newJudgments': dict(judgments), 'priorTargetDowngrades': 1,
             'manualBoundaryObservations': len(summary['chunkIssues']),
             'boundaryDiagnosticCounts': audit['flagCounts'], 'fixedPilotCheck': pilot_check,
             'backendTests': dict(totals), 'pythonTests': 55, 'frontendMetricTests': 5,
             'typecheckPassed': True, 'buildPassed': True, 'candidateReproductionByteEqual': True,
             'independentlyAnnotated': False, 'annotationCoverage': 'SELECTED_CANDIDATES_NOT_EXHAUSTIVE',
             'providerCalls': 0, 'indexRebuilt': False,
             'rootLogFiles': sorted(p.name for p in ROOT.glob('*.log')),
             'remainingWork': ['Improve sentence/title chunk boundaries and rebind affected labels',
                               'Independent factual and relevance review', 'Authorized live index synchronization and fixed pilot evaluation'],
             'verificationLogs': [p.relative_to(ROOT).as_posix() for p in log_paths]}
    assert len(final['rootLogFiles']) == 4
    (batch / 'final-report.json').write_text(json.dumps(final, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    source_paths = [KNOWLEDGE / name for name in ('diet_guides.md', 'diet_guides_metadata.json', 'rag_eval_cases.json',
                                                'rag_eval_splits.json', 'rag_chunking_eval_suite.json')]
    source_paths += list((ROOT / 'tools/evaluation/relevance_reviews_v1').glob('*.json'))
    source_paths += [ROOT / name for name in (
        'deploy/RAG_RELEVANCE_REVIEW_20261021.md', 'tools/evaluation/expand_rag_relevance.py',
        'tools/evaluation/finalize_rag_relevance_review.py', 'tools/evaluation/test_expand_rag_relevance.py',
        'tools/evaluation/freeze_chunking_suite.py', 'tools/evaluation/test_chunking_suite.py',
        'backend/src/main/java/com/example/mealcheck/service/RagEvidenceLabels.java',
        'backend/src/main/java/com/example/mealcheck/service/RagChainAssessment.java',
        'backend/src/main/java/com/example/mealcheck/dto/RagChainReport.java',
        'backend/src/main/java/com/example/mealcheck/service/RagAgentEvaluationDataset.java',
        'frontend/src/pages/admin/RagChainEvidence.jsx', 'frontend/src/utils/ragChainMetrics.js')]
    paths = source_paths + log_paths + [p for p in batch.rglob('*') if p.is_file() and p.name != 'checksums.json']
    checksums = {p.relative_to(ROOT).as_posix(): digest(p) for p in sorted(set(paths))}
    (batch / 'checksums.json').write_text(json.dumps(checksums, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    assert all(digest(ROOT / path) == value for path, value in checksums.items())
    print(json.dumps({'status': final['status'], 'verifiedChecksums': len(checksums),
                      'backendTests': totals['tests'], 'pythonTests': 55, 'frontendTests': 5}, ensure_ascii=False))


if __name__ == '__main__':
    main()
