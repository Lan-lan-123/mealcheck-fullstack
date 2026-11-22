"""Bind existing evidence quotations to actual longform chunks; do not infer new gold relevance."""
import argparse
from collections import Counter
import copy
import hashlib
import json
from pathlib import Path
import re

from prepare_knowledge_longform import ROOT


def normalized(value):
    return re.sub(r'[ \t\n\r\f\v]+', ' ', value.strip(''.join(chr(index) for index in range(33))))


def migrate(rows, manifest):
    chunks = manifest['chunks']
    result = copy.deepcopy(rows)
    for row in result:
        mapping = {}
        for target in row['evidenceTargets']:
            candidates = [chunk for chunk in chunks if chunk['title'] == target['title']
                          and all(normalized(quote) in normalized(chunk['content']) for quote in target['evidenceQuotes'])]
            if not candidates:
                raise ValueError(row['id'] + ': original evidence anchors do not fit an actual chunk; manual review required')
            selected = candidates[0]
            expected_hash = hashlib.sha256((normalized(selected['title']) + '\n\n' + normalized(selected['content'])).encode()).hexdigest()
            if selected['contentHash'] != expected_hash:
                raise ValueError('Chunk identity mismatch')
            mapping[target['knowledgeId']] = selected['contentHash']
            target['previousKnowledgeId'] = target['knowledgeId']
            target['knowledgeId'] = selected['contentHash']
            target['chunkKey'] = selected['chunkKey']
        for group in row['requiredEvidenceGroups']:
            group[:] = list(dict.fromkeys(mapping[identity] for identity in group))
        if len({target['knowledgeId'] for target in row['evidenceTargets']}) != len(row['evidenceTargets']):
            raise ValueError('Different previous targets merged; explicit annotation review required')
        row['annotationRevision'] = 'rag-evidence-v2-longform'
        row['annotation'].update(status='PENDING_HUMAN_REVIEW', independentlyAnnotated=False,
                                 provenance='ASSISTANT_MIGRATED_ORIGINAL_ANCHORS',
                                 coverage='PRESERVED_ORIGINAL_ANCHORS_ONLY',
                                 additionalChunkRelevanceReview='PENDING',
                                 corpusSha256=manifest['corpusSha256'])
    return result


def main(batch, manifest_path):
    batch = batch.resolve()
    if not batch.is_relative_to((ROOT / 'logs').resolve()):
        raise ValueError('Output must remain under logs/')
    plan = json.loads((batch / 'plan.json').read_text(encoding='utf-8'))
    candidate = batch / 'candidate-diet-guides.md'
    manifest = json.loads(manifest_path.read_text(encoding='utf-8'))
    if manifest['corpusSha256'] != hashlib.sha256(candidate.read_bytes()).hexdigest():
        raise ValueError('Manifest does not describe the candidate corpus')
    if (manifest['minTokens'], manifest['maxTokens'], manifest['overlapTokens']) != (300, 500, 50):
        raise ValueError('This length plan requires default production split sizes')
    counts = Counter(chunk['title'] for chunk in manifest['chunks'])
    if set(counts) != {topic['title'] for topic in plan['topics']}:
        raise ValueError('Candidate must contain all 200 original topics')
    distribution = Counter()
    for topic in plan['topics']:
        if not topic['minChunks'] <= counts[topic['title']] <= topic['maxChunks']:
            raise ValueError(topic['title'] + ': length plan not satisfied')
        distribution[topic['bucket']] += 1
    before = json.loads((batch / 'before-rag-cases.json').read_text(encoding='utf-8'))
    after = migrate(before, manifest)
    destination = batch / 'candidate-rag-eval-cases.json'
    with destination.open('x', encoding='utf-8') as stream:
        json.dump(after, stream, ensure_ascii=False, indent=2)
        stream.write('\n')
    result = {'topicCount': len(counts), 'chunkCount': len(manifest['chunks']),
              'distribution': dict(distribution), 'migratedCases': len(after),
              'sourceBoundKnowledgeCases': sum(bool(row['evidenceTargets']) for row in after),
              'independentlyReviewed': False,
              'limitation': 'Preserved original anchors only; newly authored chunks require additional relevance judgments.'}
    (batch / 'migration.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(result, ensure_ascii=False))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('batch', type=Path)
    parser.add_argument('chunk_manifest', type=Path)
    args = parser.parse_args()
    main(args.batch, args.chunk_manifest)
