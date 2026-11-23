"""Guard the offline review boundary; synthetic captures never reach live outputs."""
import copy
import json
from pathlib import Path
import tempfile
import unittest

from export_live_retrieval_review import (MODE, build_review, content_id, digest,
                                        verify_completion, verify_files)


def fixture():
    chunks, snapshot, hits = [], [], []
    for n in range(1, 4):
        title, content = 'topic ' + str(n), 'source paragraph ' + str(n)
        key = content_id(title, content)
        chunks.append({'contentHash': key, 'title': title, 'content': content, 'chunkKey': str(n)})
        snapshot.append({'id': n, 'source': 'diet_guides.md', 'title': title, 'content': content,
                         'contentHash': key, 'chunkKey': str(n), 'hasEmbedding': True,
                         'sourceVersion': 'v8-longform-200-boundary-v2'})
        hits.append({'id': n, 'knowledgeId': key, 'title': title, 'content': content, 'score': .8})
    cases, rows = [], []
    for n in range(30):
        case_id = 'case-' + str(n)
        kind = 'ANSWERABLE' if n < 24 else 'INSUFFICIENT_EVIDENCE' if n < 28 else 'OUT_OF_SCOPE'
        case = {'id': case_id, 'question': 'original query ' + str(n), 'answerability': kind,
                'evidenceTargets': [{'knowledgeId': hits[1]['knowledgeId'], 'title': hits[1]['title'],
                                     'relevance': 2, 'evidenceQuotes': [hits[1]['content']]}]}
        cases.append(case)
        row = {'caseId': case_id, 'question': case['question'], 'answerability': kind}
        if n >= 28:
            row.update(state='SKIPPED_OUT_OF_SCOPE', queryEmbeddingCalls=0, rawCandidates=[],
                       assessment={'retrievals': []})
        else:
            raw = [{'rank': i, 'hit': hit, 'judgment': 'DIRECT' if i == 2 else 'UNJUDGED',
                    'disposition': 'THRESHOLD_FILTERED' if i == 1 else 'RETAINED_TOP5'}
                   for i, hit in enumerate(hits, 1)]
            row.update(state='RETRIEVED_PRODUCTION_PGVECTOR_NO_RERANK', queryEmbeddingCalls=1,
                       rawCandidates=raw, postProcessor={'snippets': hits[1:]},
                       capturedRetrieval={'query': case['question'], 'requestedLimit': 5,
                                          'status': 'SUCCESS', 'hits': hits[1:]})
        rows.append(row)
    result = {'executionMode': MODE, 'caseCount': 30, 'queryEmbeddingCalls': 28, 'outsideSkipped': 2,
              'chatCalls': 0, 'rerankCalls': 0, 'maxRetries': 0, 'results': rows,
              'independentlyAnnotated': False, 'routingOrToolSelectionMeasured': False,
              'answerGenerationMeasured': False}
    return result, cases, [c['id'] for c in cases], {'chunks': chunks}, snapshot


class ExportGuards(unittest.TestCase):
    def test_only_actual_retained_unknown_pairs_enter_primary_and_blank_template(self):
        primary, secondary = build_review(*fixture())
        self.assertEqual((len(primary), len(secondary)), (28, 56))
        self.assertEqual({p['rawRank'] for p in primary}, {3})
        self.assertEqual({p['retainedRank'] for p in primary}, {2})
        for pair in primary:
            self.assertIsNone(pair['relevance'])
            self.assertEqual(pair['rationale'], '')
            self.assertEqual(pair['evidenceQuotes'], [])
            self.assertEqual(pair['alternativeForGroups'], [])
            self.assertEqual(pair['content'], 'source paragraph 3')

    def test_refuses_changed_question_and_outside_retrieval(self):
        for mutate in (lambda r: r['results'][0].update(question='changed query'),
                       lambda r: r['results'][-1].update(queryEmbeddingCalls=1)):
            args = fixture()
            mutate(args[0])
            with self.assertRaises(ValueError):
                build_review(*args)

    def test_refuses_injected_retained_hit_or_forged_source(self):
        args = copy.deepcopy(fixture())
        args[0]['results'][0]['capturedRetrieval']['hits'][0] = {
            **args[0]['results'][0]['capturedRetrieval']['hits'][0], 'id': 999}
        with self.assertRaises(ValueError):
            build_review(*args)
        args = fixture()
        args[-1][0]['source'] = 'unrelated-admin-source'
        with self.assertRaises(ValueError):
            build_review(*args)

    def test_refuses_captured_body_or_judgment_tampering(self):
        args = fixture()
        args[0]['results'][0]['rawCandidates'][0]['hit']['content'] = 'different body'
        with self.assertRaises(ValueError):
            build_review(*args)
        args = fixture()
        args[0]['results'][0]['rawCandidates'][1]['judgment'] = 'UNJUDGED'
        with self.assertRaises(ValueError):
            build_review(*args)

    def test_incomplete_runtime_never_treated_as_complete(self):
        for runtime in ({'mode': 'EXECUTE', 'state': 'BUILDING'},
                        {'mode': 'EXECUTE', 'state': 'STOPPED', 'exitCode': 1},
                        {'mode': 'BUILD_ONLY', 'state': 'COMPLETE', 'exitCode': 0}):
            with self.assertRaises(ValueError):
                verify_completion(runtime)

    def test_file_proofs_reject_input_and_sealed_output_drift(self):
        with tempfile.TemporaryDirectory() as folder:
            base = Path(folder)
            names = ['corpus.json', 'plan.json', 'screening30.json', 'expected-chunks.json',
                     'results.json', 'after-screening-index.json', 'budget-summary.json']
            for name in names:
                (base / name).write_text(json.dumps({'status': 'COMPLETE'}), encoding='utf-8')
            tracked = names[:4]
            runtime = {'mode': 'EXECUTE', 'state': 'COMPLETE', 'exitCode': 0,
                       'sourceAndInputSha256': {str((base / n).resolve()): digest(base / n) for n in tracked},
                       'planSha256': digest(base / 'plan.json'),
                       'outputSha256': {n: digest(base / n) for n in names[4:]}}
            inputs = {'corpus': base / 'corpus.json'}
            verify_files(runtime, base, base, inputs)
            (base / 'results.json').write_text('tampered', encoding='utf-8')
            with self.assertRaisesRegex(ValueError, 'Live output drift'):
                verify_files(runtime, base, base, inputs)
            (base / 'corpus.json').write_text('tampered', encoding='utf-8')
            with self.assertRaisesRegex(ValueError, 'Frozen source/input drift'):
                verify_files(runtime, base, base, inputs)


if __name__ == '__main__':
    unittest.main()
