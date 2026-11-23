"""Build a narrow production RAG runner; DB/provider access requires explicit modes.

--build-only and --self-test never open a database or network connection. Preflight
is read-only. --execute requires the separately recorded, plan-bound authorization.
"""
import argparse
from datetime import datetime
import hashlib
import json
from pathlib import Path
import subprocess

from freeze_chunking_suite import ROOT, KNOWLEDGE
from prepare_rag_screening_pilot import prepare


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def load(path):
    return json.loads(path.read_text(encoding='utf-8'))


def validate_authorization(auth, plan_sha):
    wanted = {'approved': True, 'planSha256': plan_sha, 'maximumRequests': 1381,
              'maximumAdmissionUnits': 2_000_000, 'maxRetries': 0,
              'stopOnFirstProviderError': True, 'chatCallsAllowed': 0,
              'rerankCallsAllowed': 0, 'supplementalQueryCallsAllowed': 0,
              'preserveAdminEntries': True, 'source': 'diet_guides.md',
              'sourceVersion': 'v8-longform-200-boundary-v2'}
    if any(type(auth.get(k)) is not type(v) or auth.get(k) != v for k, v in wanted.items()):
        raise ValueError('Authorization does not match the exact approved scope and frozen plan')
    if not isinstance(auth.get('authorizationEvidence'), str) or not auth['authorizationEvidence'].strip():
        raise ValueError('Human authorization evidence required')


def validate_packet(prepared, manifest):
    inputs = {'corpus': KNOWLEDGE / 'diet_guides.md', 'dataset': KNOWLEDGE / 'rag_eval_cases.json',
              'metadata': KNOWLEDGE / 'diet_guides_metadata.json', 'suite': KNOWLEDGE / 'rag_chunking_eval_suite.json',
              'manifest': manifest}
    plan = load(prepared / 'plan.json')
    current, selected, expected = prepare(inputs['corpus'].read_bytes(), load(inputs['dataset']),
                                         load(inputs['metadata']), load(inputs['suite']), load(manifest))
    for key, value in current.items():
        if plan.get(key) != value:
            raise ValueError('Prepared plan drift: ' + key)
    if plan.get('inputFileSha256') != {name: digest(path) for name, path in inputs.items()}:
        raise ValueError('Prepared input file hashes changed')
    selected_packet = load(prepared / 'screening30.json')
    expected_packet = load(prepared / 'expected-chunks.json')
    if (selected_packet.get('status') != 'PREPARED_NOT_EXECUTED' or selected_packet.get('cases') != selected
            or expected_packet.get('source') != 'diet_guides.md'
            or expected_packet.get('sourceVersion') != 'v8-longform-200-boundary-v2'
            or expected_packet.get('chunks') != expected):
        raise ValueError('Prepared selected cases or expected index changed')
    return inputs, plan


CORE = ('AppProperties', 'MarkdownKnowledgeChunker', 'HashEmbeddingService', 'KnowledgeResultPostProcessor',
        'KnowledgeCategoryClassifier', 'RagChainAssessment', 'RagEvidenceLabels', 'PgVectorKnowledgeService',
        'KnowledgeSourceSyncStore', 'KnowledgeVectorPersistenceService', 'KnowledgeEmbeddingService',
        'KnowledgeEmbeddingProfileStore', 'KnowledgeEmbeddingSettingsStore', 'VectorCompressionService',
        'ApplicationObservability', 'AiHttpTransport', 'AiChatRequestPolicy', 'KnowledgeJobFailure',
        'KnowledgeQueryFocus', 'AiEndpointResolver')


def sources():
    java = ROOT / 'backend/src/main/java/com/example/mealcheck'
    result = [java / ('config' if name == 'AppProperties' else 'service') / (name + '.java') for name in CORE]
    result += [java / 'dto' / (name + '.java') for name in ('KnowledgeSnippet', 'RagChainReport')]
    result += [java / 'util/AppTime.java', ROOT / 'tools/evaluation/LiveRagScreeningTool.java']
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    modes = parser.add_mutually_exclusive_group(required=True)
    for mode in ('build-only', 'self-test', 'preflight', 'execute'):
        modes.add_argument('--' + mode, action='store_true')
    parser.add_argument('--prepared', type=Path, default=ROOT / 'logs/backend/rag-screening-pilot-prepared-20261021-r1')
    parser.add_argument('--manifest', type=Path, default=ROOT / 'logs/backend/rag-boundary-fix-20261021-r1/candidate-chunks.json')
    parser.add_argument('--authorization', type=Path)
    parser.add_argument('--dependency-runtime', type=Path, default=ROOT / 'logs/backend/knowledge-longform-v8-20261020/runtime.json')
    parser.add_argument('--output-directory', type=Path, default=ROOT / 'logs/backend' /
                        ('live-rag-screening-' + datetime.now().strftime('%Y%m%d-%H%M%S-%f')))
    args = parser.parse_args()
    inputs, plan = validate_packet(args.prepared, args.manifest)
    if args.execute:
        if not args.authorization:
            raise ValueError('--execute requires --authorization')
        validate_authorization(load(args.authorization), digest(args.prepared / 'plan.json'))
    output = args.output_directory.resolve()
    if not output.is_relative_to((ROOT / 'logs').resolve()) or output.exists():
        raise ValueError('A fresh output directory under logs/ is required')
    output.mkdir(parents=True, exist_ok=False)
    classes = output / 'runtime/classes'
    classes.mkdir(parents=True)
    compiled = sources()
    tracked = compiled + list(inputs.values()) + [args.prepared / 'plan.json', args.prepared / 'screening30.json',
                                                 args.prepared / 'expected-chunks.json', ROOT / 'backend/src/main/resources/application.yml']
    if args.execute:
        tracked.append(args.authorization)
    hashes = {str(p.resolve()): digest(p) for p in tracked}
    dependencies = load(args.dependency_runtime)['classpath']
    classpath = str(classes) + ';' + dependencies
    runtime = {'mode': 'BUILD_ONLY' if args.build_only else 'OFFLINE_SELF_TEST' if args.self_test else 'EXECUTE' if args.execute else 'READ_ONLY_PREFLIGHT',
               'classpath': classpath, 'freshCompiledSources': [str(p.relative_to(ROOT)) for p in compiled],
               'sourceAndInputSha256': hashes, 'dependencyRuntimeSha256': digest(args.dependency_runtime),
               'planSha256': digest(args.prepared / 'plan.json'), 'state': 'BUILDING'}
    runtime_path = output / 'runtime.json'
    runtime_path.write_text(json.dumps(runtime, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    crash = ROOT / 'logs/crash'
    crash.mkdir(parents=True, exist_ok=True)
    subprocess.run(['javac', '-J-XX:ErrorFile=' + str(crash / 'hs_err_pid%p.log'), '-encoding', 'UTF-8',
                    '-cp', dependencies, '-d', str(classes), *map(str, compiled)], cwd=ROOT, check=True)
    if hashes != {str(p.resolve()): digest(p) for p in tracked}:
        raise ValueError('Sources or inputs changed during compilation')
    if not args.build_only:
        mode = '--self-test' if args.self_test else '--execute' if args.execute else '--preflight'
        command = ['java', '-XX:ErrorFile=' + str(crash / 'hs_err_pid%p.log'),
                   '-Djdk.httpclient.disableRetryConnect=true', '-Djdk.httpclient.enableAllMethodRetry=false', '-cp', classpath,
                   'com.example.mealcheck.service.LiveRagScreeningTool', mode, str(args.prepared.resolve()),
                   str(args.manifest.resolve()), str(output)]
        if args.execute:
            command.append(str(args.authorization.resolve()))
        result = subprocess.run(command, cwd=ROOT)
        runtime['exitCode'] = result.returncode
    else:
        runtime['exitCode'] = 0
    runtime['state'] = 'COMPLETE' if runtime['exitCode'] == 0 else 'STOPPED'
    runtime['outputSha256'] = {p.name: digest(p) for p in output.glob('*.json') if p != runtime_path}
    runtime_path.write_text(json.dumps(runtime, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(str(output))
    if hashes != {str(p.resolve()): digest(p) for p in tracked}:
        raise ValueError('Sources or inputs changed during run')
    if runtime['exitCode']:
        raise SystemExit(runtime['exitCode'])


if __name__ == '__main__':
    main()
