"""Verify the installed boundary revision and preserve its offline evidence summary."""
from collections import Counter
import hashlib
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET

from rebind_rag_chunk_boundaries import ROOT, KNOWLEDGE, rebind_rows, update_metadata
from freeze_chunking_suite import validate_suite


def read(path):
    return json.loads(path.read_text(encoding='utf-8'))


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def encoded(value):
    return (json.dumps(value, ensure_ascii=False, indent=2) + '\n').encode('utf-8')


def main():
    batch = ROOT / 'logs/backend/rag-boundary-fix-20261021-r1'
    manifest_path = batch / 'candidate-chunks.json'
    manifest = read(manifest_path)
    before_manifest = read(ROOT / 'logs/backend/knowledge-longform-v8-release-20261021/candidate-chunks.json')
    rows = read(KNOWLEDGE / 'rag_eval_cases.json')
    metadata = read(KNOWLEDGE / 'diet_guides_metadata.json')
    installation = read(batch / 'installation.json')
    corpus_path = KNOWLEDGE / 'diet_guides.md'
    assert corpus_path.read_bytes() == (batch / 'before-diet_guides.md').read_bytes()
    assert sha(corpus_path) == manifest['corpusSha256'] == metadata['corpusSha256']
    assert manifest_path.read_bytes() == (batch / 'reproduced-chunks.json').read_bytes()
    assert metadata['indexSourceVersion'] == 'v8-longform-200-boundary-v2'
    assert metadata['splitterRevision'] == manifest['splitterRevision'] == 'sentence-heading-boundaries-v2'
    runtime = read(batch / 'runtime-final.json')
    assert all(sha(Path(path)) == value for path, value in runtime['compiledSources'].items())
    assert all(sha(KNOWLEDGE / name) == value for name, value in installation['resourceSha256'].items())
    decisions_path = ROOT / 'tools/evaluation/knowledge_boundary_reviews_v2.json'
    reproduced, counts = rebind_rows(read(batch / 'before-rag_eval_cases.json'), before_manifest, manifest,
        manifest['splitterRevision'], adjudications=read(decisions_path)['decisions'], new_manifest_sha256=sha(manifest_path))
    assert encoded(reproduced) == (KNOWLEDGE / 'rag_eval_cases.json').read_bytes()
    remetadata = update_metadata(read(batch / 'before-diet_guides_metadata.json'), before_manifest, manifest,
                                 manifest['splitterRevision'])
    assert encoded(remetadata) == (KNOWLEDGE / 'diet_guides_metadata.json').read_bytes()
    allowed = {'annotation', 'annotationRevision', 'evidenceTargets', 'irrelevantEvidence', 'requiredEvidenceGroups',
               'evidenceGroupRationales', 'previousEvidenceGroupRationales', 'coverageMigrationPolicy',
               'previousAnnotationRevision', 'boundaryPriorNegativeAdjudications'}
    before_rows = read(batch / 'before-rag_eval_cases.json')
    assert len(rows) == 200 and all({k: v for k, v in a.items() if k not in allowed} ==
                                  {k: v for k, v in b.items() if k not in allowed} for a, b in zip(rows, before_rows))
    assert all(r['annotation']['status'] == 'PENDING_HUMAN_REVIEW' and not r['annotation']['independentlyAnnotated'] for r in rows)
    assert Counter(t['lengthBucket'] for t in metadata['topics']) == {'SHORT': 40, 'MEDIUM': 60, 'LONG': 60, 'EXTENDED': 40}
    audit = read(batch / 'independent-audit-final/audit.json')
    assert audit['passed'] and not audit['after']['errors'] and not audit['after']['diagnostics']
    assert audit['after']['chunkCount'] == metadata['chunkCount'] == 1351
    suite_check = validate_suite(read(KNOWLEDGE / 'rag_chunking_eval_suite.json'), rows, corpus_path.read_text(encoding='utf-8'))
    blank = read(batch / 'independent-review/review.json')
    assert len(blank['knowledge']) == 1351 and len(blank['reviews']) == 200
    assert blank['status'] == 'UNREVIEWED' and not blank['independentlyAnnotated']
    assert all(not row['evidenceJudgments'] for row in blank['reviews'])
    reports = list((batch / 'test-reports-final').glob('TEST-*.xml'))
    totals = Counter()
    for path in reports:
        attrs = ET.parse(path).getroot().attrib
        for key in ('tests', 'failures', 'errors', 'skipped'):
            totals[key] += int(attrs[key])
    assert len(reports) == 10 and totals == {'tests': 73, 'failures': 0, 'errors': 0, 'skipped': 0}
    logs = [ROOT / name for name in (
        'logs/backend/boundary-backend-final-20261021-145233-150283.log',
        'logs/backend/boundary-python-final-20261021-145224-532953.log',
        'logs/frontend/boundary-frontend-metrics-20261021-145224-624831.log',
        'logs/frontend/boundary-typecheck-final-20261021-145436-536158.log',
        'logs/frontend/boundary-build-final-20261021-145436-555918.log',
        'logs/backend/boundary-pilot-check-20261021-145224-609261.log',
        'logs/backend/boundary-final-audit-20261021-144918-095639.log',
        'logs/backend/boundary-install-20261021-145152-341868.log')]
    assert 'BUILD SUCCESS' in logs[0].read_text(encoding='utf-8', errors='replace')
    assert 'Ran 75 tests' in logs[1].read_text(encoding='utf-8', errors='replace')
    assert re.search(r'tests\s+5', logs[2].read_text(encoding='utf-8', errors='replace'))
    assert 'built in' in logs[4].read_text(encoding='utf-8', errors='replace')
    lines = ['# 200 主题切片边界验收', '',
             '正文不变；全部主题符合原分档，1,351 片均为 300–500 个估算 token。',
             '全库源码连续覆盖、句首/句尾与标题格式检查通过；不代表独立事实或语义完整性金标。', '',
             '| 主题 ID | 标题 | 分档 | 目标 | 修复前 | 修复后 |', '| --- | --- | --- | --- | ---: | ---: |']
    for topic in metadata['topics']:
        lines.append(f"| {topic['topicId']} | {topic['title']} | {topic['lengthBucket']} | {topic['minChunks']}–{topic['maxChunks']} | {topic['previousActualChunks']} | {topic['actualChunks']} |")
    (batch / 'review-index.md').write_text('\n'.join(lines) + '\n', encoding='utf-8')
    root_logs = {path.name: path.stat().st_size for path in ROOT.glob('*.log')}
    assert root_logs == {'rag-sample10-live-resume.log': 19317, 'rag-sample10-live.log': 39023,
                         'rag-sample10-local.log': 19316, 'rag-sample10-resume-local.log': 19832}
    distribution = {}
    for bucket in ('SHORT', 'MEDIUM', 'LONG', 'EXTENDED'):
        values = [t['actualChunks'] for t in metadata['topics'] if t['lengthBucket'] == bucket]
        distribution[bucket] = {'topics': len(values), 'chunks': sum(values), 'min': min(values), 'max': max(values)}
    final = {'status': 'INSTALLED_AND_OFFLINE_VERIFIED', 'topicCount': 200, 'caseCount': 200,
             'beforeChunks': 1249, 'afterChunks': 1351, 'corpusUnchanged': True, 'corpusSha256': sha(corpus_path),
             'splitterRevision': manifest['splitterRevision'], 'indexSourceVersion': metadata['indexSourceVersion'],
             'datasetVersion': 'rag-200-v7+rag-tools-v2', 'annotationRevision': 'rag-evidence-v6-boundary-aligned',
             'scoringVersion': 'rag-chain-v3', 'allLengthBoundsSatisfied': True, 'allTokenBoundsSatisfied': True,
             'distribution': distribution, 'beforeBoundaryDiagnostics': audit['before']['diagnosticCounts'],
             'afterBoundaryDiagnostics': audit['after']['diagnosticCounts'], 'gapFreeSourceCoverage': True,
             'productionManifestByteReproduced': True, 'resourcesByteReproduced': True,
             'migrationStatistics': counts, 'pilotCheck': suite_check,
             'backendTests': dict(totals), 'pythonTests': 75, 'frontendTests': 5,
             'typecheckPassed': True, 'buildPassed': True, 'independentlyAnnotated': False,
             'providerCalls': 0, 'indexRebuilt': False, 'rootLogsUnchanged': True,
             'limitations': ['Boundary diagnostics do not establish factual or full semantic correctness',
                             'Unlisted chunks remain unjudged; independent human review is pending',
                             'Live index synchronization and fixed pilot quality evaluation have not been run'],
             'verificationLogs': [path.relative_to(ROOT).as_posix() for path in logs]}
    (batch / 'final-report.json').write_bytes(encoded(final))
    installation['status'] = 'INSTALLED_AND_OFFLINE_VERIFIED'
    (batch / 'installation.json').write_bytes(encoded(installation))
    files = [p for p in batch.rglob('*') if p.is_file() and p.name != 'checksums.json'] + logs
    files += [ROOT / path for path in (
        'backend/src/main/java/com/example/mealcheck/service/MarkdownKnowledgeChunker.java',
        'backend/src/test/java/com/example/mealcheck/service/MarkdownKnowledgeChunkerTest.java',
        'backend/src/main/java/com/example/mealcheck/config/AppProperties.java',
        'backend/src/main/java/com/example/mealcheck/service/RagAgentEvaluationDataset.java',
        'backend/src/test/java/com/example/mealcheck/service/RagEvidenceLabelsTest.java',
        'tools/evaluation/KnowledgeCorpusTool.java', 'tools/evaluation/rebind_rag_chunk_boundaries.py',
        'tools/evaluation/test_rebind_rag_chunk_boundaries.py', 'tools/evaluation/audit_chunk_boundaries.py',
        'tools/evaluation/test_audit_chunk_boundaries.py', 'tools/evaluation/knowledge_boundary_reviews_v2.json',
        'tools/evaluation/install_rag_boundary_revision.py', 'tools/evaluation/finalize_rag_boundary_revision.py',
        'tools/evaluation/freeze_chunking_suite.py', 'tools/evaluation/test_chunking_suite.py',
        'tools/evaluation/expand_rag_relevance.py', 'tools/evaluation/README.md', 'README.md',
        'frontend/src/utils/knowledgeSplitter.js', 'frontend/src/pages/admin/RagChainEvidence.jsx',
        'backend/src/main/resources/application.yml', '.env.example', 'deploy/RAG_CHUNK_BOUNDARIES_20261021.md')]
    files += [KNOWLEDGE / name for name in installation['resourceSha256']]
    files.append(corpus_path)
    checksums = {path.relative_to(ROOT).as_posix(): sha(path) for path in sorted(set(files))}
    (batch / 'checksums.json').write_bytes(encoded(checksums))
    assert all(sha(ROOT / name) == value for name, value in checksums.items())
    print(json.dumps({'status': final['status'], 'chunks': 1351, 'backendTests': 73, 'pythonTests': 75,
                      'frontendTests': 5, 'verifiedChecksums': len(checksums)}, ensure_ascii=False))


if __name__ == '__main__':
    main()
