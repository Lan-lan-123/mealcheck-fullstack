"""Install a verified splitter-only revision atomically per resource, without indexing."""
import hashlib
import json
import os
from pathlib import Path
import shutil

from rebind_rag_chunk_boundaries import ROOT, KNOWLEDGE, rebind_rows, update_metadata
from freeze_chunking_suite import build_suite


def read(path):
    return json.loads(path.read_text(encoding='utf-8'))


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    batch = ROOT / 'logs/backend/rag-boundary-fix-20261021-r1'
    baseline = read(batch / 'before-hashes.json')
    manifest_path = batch / 'candidate-chunks.json'
    manifest = read(manifest_path)
    old = read(ROOT / 'logs/backend/knowledge-longform-v8-release-20261021/candidate-chunks.json')
    decisions_path = ROOT / 'tools/evaluation/knowledge_boundary_reviews_v2.json'
    revision = manifest['splitterRevision']
    runtime = read(ROOT / 'logs/backend/knowledge-longform-v8-20261020/runtime.json')
    for name in ('AppProperties.java', 'MarkdownKnowledgeChunker.java', 'KnowledgeCorpusTool.java'):
        matches = [(Path(path), digest) for path, digest in runtime['compiledSources'].items() if Path(path).name == name]
        if len(matches) != 1 or sha(matches[0][0]) != matches[0][1]:
            raise ValueError('Production splitter runtime is stale: ' + name)
    if manifest_path.read_bytes() != (batch / 'reproduced-chunks.json').read_bytes():
        raise ValueError('Current compiled production splitter does not reproduce the candidate')
    if not read(batch / 'independent-audit-final/audit.json')['passed']:
        raise ValueError('Independent boundary/source coverage audit must pass')
    if sha(KNOWLEDGE / 'diet_guides.md') != manifest['corpusSha256']:
        raise ValueError('Corpus changed')
    for name, expected in baseline.items():
        if name.startswith('backend/src/main/resources/knowledge/') and sha(ROOT / name) != expected:
            raise ValueError('Canonical knowledge resources changed since review: ' + name)
    previous_rows = read(batch / 'before-rag_eval_cases.json')
    revised_rows, counts = rebind_rows(previous_rows, old, manifest, revision,
        adjudications=read(decisions_path)['decisions'], new_manifest_sha256=sha(manifest_path))
    expected_metadata = update_metadata(read(batch / 'before-diet_guides_metadata.json'), old, manifest, revision)
    if revised_rows != read(batch / 'rebound-final/candidate-rag-eval-cases.json') or expected_metadata != read(batch / 'rebound-final/candidate-metadata.json'):
        raise ValueError('Rebinding candidates differ from deterministic source-bound reproduction')
    splits = read(batch / 'before-rag_eval_splits.json')
    splits['datasetVersion'] = 'rag-200-v7'
    suite = build_suite(revised_rows, (KNOWLEDGE / 'diet_guides.md').read_text(encoding='utf-8'))
    old_suite = read(batch / 'before-rag_chunking_eval_suite.json')
    if {k: v['caseIds'] for k, v in old_suite['sets'].items()} != {k: v['caseIds'] for k, v in suite['sets'].items()}:
        raise ValueError('Fixed pilot selection changed')
    updates = {'rag_eval_cases.json': revised_rows, 'diet_guides_metadata.json': expected_metadata,
               'rag_eval_splits.json': splits, 'rag_chunking_eval_suite.json': suite}
    if shutil.disk_usage(ROOT).free < 50_000_000:
        raise ValueError('Insufficient disk space for staged resource install')
    stages = []
    for name, payload in updates.items():
        data = (json.dumps(payload, ensure_ascii=False, indent=2) + '\n').encode('utf-8')
        candidate = batch / ('installed-candidate-' + name)
        with candidate.open('xb') as stream:
            stream.write(data)
        stage = batch / ('install-stage-' + name)
        with stage.open('xb') as stream:
            stream.write(data)
        stages.append((stage, KNOWLEDGE / name))
    # Compare once more after staging. All replace targets are fixed files in this project.
    for name, expected in baseline.items():
        if name.startswith('backend/src/main/resources/knowledge/') and sha(ROOT / name) != expected:
            raise ValueError('Concurrent resource change; refusing install')
    for stage, destination in stages:
        if not stage.resolve().is_relative_to(batch.resolve()) or not destination.resolve().is_relative_to(KNOWLEDGE.resolve()):
            raise ValueError('Install path escaped project')
        os.replace(stage, destination)
    result = {'status': 'INSTALLED_PENDING_FINAL_REGRESSION', 'corpusSha256': manifest['corpusSha256'],
              'splitterRevision': revision, 'datasetVersion': 'rag-200-v7+rag-tools-v2',
              'chunkCount': manifest['chunkCount'], 'caseCount': len(revised_rows), 'migrationStatistics': counts,
              'independentlyAnnotated': False, 'providerCalls': 0, 'indexRebuilt': False,
              'resourceSha256': {name: sha(KNOWLEDGE / name) for name in updates}}
    (batch / 'installation.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    (batch / 'runtime-final.json').write_bytes((ROOT / 'logs/backend/knowledge-longform-v8-20261020/runtime.json').read_bytes())
    print(json.dumps(result, ensure_ascii=False))


if __name__ == '__main__':
    main()
