"""Run a clearly labelled, fixture-free local vector-only component precheck.

No environment credentials, network, database, chat or semantic provider calls.
This route is not production PostgreSQL hybrid retrieval or a semantic quality score.
"""
import argparse
from datetime import datetime
import hashlib
import json
from pathlib import Path
import subprocess

from freeze_chunking_suite import ROOT, KNOWLEDGE, validate_suite


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def validate_inputs(corpus, manifest, dataset, suite, metadata):
    if digest(corpus) != manifest['corpusSha256'] or manifest['corpusSha256'] != metadata['corpusSha256']:
        raise ValueError('Corpus/manifest/metadata fingerprint mismatch')
    if manifest['chunkCount'] != 1351 or metadata['chunkCount'] != 1351:
        raise ValueError('Current 1351-chunk boundary revision required')
    if manifest.get('splitterRevision') != metadata.get('splitterRevision') or metadata.get('splitterRevision') != 'sentence-heading-boundaries-v2':
        raise ValueError('Current splitter revision required')
    if tuple(manifest.get(key) for key in ('minTokens', 'maxTokens', 'overlapTokens')) != (300, 500, 50) or tuple(
            metadata['splitter'].get(key) for key in ('minTokens', 'maxTokens', 'overlapTokens')) != (300, 500, 50):
        raise ValueError('Default token budget drifted')
    return validate_suite(suite, dataset, corpus.read_text(encoding='utf-8'))


def require_output(output):
    output = output.resolve()
    if not output.is_relative_to((ROOT / 'logs').resolve()):
        raise ValueError('Output must remain under logs/')
    if output.exists():
        raise FileExistsError('Use a new output directory; prior evidence is immutable')
    return output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', type=Path, default=ROOT / 'logs/backend/rag-boundary-fix-20261021-r1/candidate-chunks.json')
    parser.add_argument('--corpus', type=Path, default=KNOWLEDGE / 'diet_guides.md')
    parser.add_argument('--dataset', type=Path, default=KNOWLEDGE / 'rag_eval_cases.json')
    parser.add_argument('--suite', type=Path, default=KNOWLEDGE / 'rag_chunking_eval_suite.json')
    parser.add_argument('--metadata', type=Path, default=KNOWLEDGE / 'diet_guides_metadata.json')
    parser.add_argument('--dependency-runtime', type=Path, default=ROOT / 'logs/backend/knowledge-longform-v8-20261020/runtime.json')
    parser.add_argument('--output-directory', type=Path, default=ROOT / 'logs/backend' /
                        ('local-hash-screening-' + datetime.now().strftime('%Y%m%d-%H%M%S-%f')))
    args = parser.parse_args()
    load = lambda p: json.loads(p.read_text(encoding='utf-8'))
    fixed_check = validate_inputs(args.corpus, load(args.manifest), load(args.dataset), load(args.suite), load(args.metadata))
    output = require_output(args.output_directory)
    output.mkdir(parents=True, exist_ok=False)
    classes = output / 'runtime/classes'
    classes.mkdir(parents=True)
    sources = [ROOT / path for path in (
        'backend/src/main/java/com/example/mealcheck/config/AppProperties.java',
        'backend/src/main/java/com/example/mealcheck/service/MarkdownKnowledgeChunker.java',
        'backend/src/main/java/com/example/mealcheck/service/HashEmbeddingService.java',
        'backend/src/main/java/com/example/mealcheck/service/KnowledgeResultPostProcessor.java',
        'backend/src/main/java/com/example/mealcheck/service/KnowledgeCategoryClassifier.java',
        'backend/src/main/java/com/example/mealcheck/service/RagChainAssessment.java',
        'backend/src/main/java/com/example/mealcheck/service/RagEvidenceLabels.java',
        'backend/src/main/java/com/example/mealcheck/service/PgVectorKnowledgeService.java',
        'backend/src/main/java/com/example/mealcheck/dto/KnowledgeSnippet.java',
        'backend/src/main/java/com/example/mealcheck/dto/RagChainReport.java',
        'tools/evaluation/LocalHashScreeningTool.java')]
    inputs = [args.corpus, args.manifest, args.dataset, args.suite, args.metadata]
    hashes = {str(p.resolve()): digest(p) for p in sources + inputs}
    dependency_classpath = load(args.dependency_runtime)['classpath']
    # Only jars/compiled dependency types are reused. Every invoked production
    # component above is freshly compiled and precedes that classpath at runtime.
    classpath = str(classes) + ';' + dependency_classpath
    evidence = {'executionMode': 'LOCAL_HASH_VECTOR_ONLY_PRECHECK', 'qualityMeasurement': False,
                'productionPgVectorUsed': False, 'providerCalls': 0, 'sourceAndInputSha256': hashes,
                'freshCompiledSources': [str(p.relative_to(ROOT)) for p in sources],
                'dependencyRuntimeSha256': digest(args.dependency_runtime), 'classpath': classpath,
                'fixedPilotValidation': fixed_check}
    (output / 'runtime.json').write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    crash = ROOT / 'logs/crash'
    crash.mkdir(parents=True, exist_ok=True)
    subprocess.run(['javac', '-J-XX:ErrorFile=' + str(crash / 'hs_err_pid%p.log'), '-encoding', 'UTF-8',
                    '-cp', dependency_classpath, '-d', str(classes), *map(str, sources)], cwd=ROOT, check=True)
    if hashes != {str(p.resolve()): digest(p) for p in sources + inputs}:
        raise ValueError('Sources changed during compilation')
    subprocess.run(['java', '-XX:ErrorFile=' + str(crash / 'hs_err_pid%p.log'), '-cp', classpath,
                    'com.example.mealcheck.service.LocalHashScreeningTool', str(args.corpus), str(args.manifest),
                    str(args.dataset), str(args.suite), str(output)], cwd=ROOT, check=True)
    if hashes != {str(p.resolve()): digest(p) for p in sources + inputs}:
        raise ValueError('Sources changed during precheck')
    results = load(output / 'results.json')
    assert results['queryHashCalls'] == 28 and results['outsideSkipped'] == 2 and results['providerCalls'] == 0
    evidence['state'] = 'LOCAL_COMPONENT_PRECHECK_COMPLETE'
    evidence['outputSha256'] = {name: digest(output / name) for name in ('results.json', 'unjudged-review-pool.json')}
    (output / 'runtime.json').write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(str(output))


if __name__ == '__main__':
    main()
