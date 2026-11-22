"""Validate local authored previews with the production splitter, without model calls."""
import argparse
import hashlib
import json
from pathlib import Path
import re

from generate_knowledge_longform import Splitter, assess
from prepare_knowledge_longform import MARKER


def preview(batch, topic_ids):
    plan = json.loads((batch / 'plan.json').read_text(encoding='utf-8'))
    cases = json.loads((batch / 'before-rag-cases.json').read_text(encoding='utf-8'))
    rows = {row['topicIndex']: row for row in plan['topics']}
    results = []
    splitter = Splitter(batch)
    try:
        for index in topic_ids:
            row = rows[index]
            source = batch / f'preview-{index:03d}-supplement.md'
            text = source.read_text(encoding='utf-8')
            sections = []
            matches = list(re.finditer(r'^\*\*(.+)\*\*$', text, re.M))
            for position, match in enumerate(matches):
                end = matches[position + 1].start() if position + 1 < len(matches) else len(text)
                paragraphs = [paragraph.strip() for paragraph in text[match.end():end].split('\n\n') if paragraph.strip()]
                sections.append({'heading': match.group(1), 'paragraphs': paragraphs})
            article, actual, issues = assess(row, {'sections': sections}, splitter)
            targets = [target for case in cases for target in case['evidenceTargets'] if target['title'] == row['title']]
            for target in targets:
                if not any(all(quote in chunk['content'] for quote in target['evidenceQuotes']) for chunk in actual['chunks']):
                    issues.append('original_evidence_anchors_split_across_chunks')
            corpus = MARKER + '\n' + article
            (batch / f'preview-{index:03d}.md').write_text(corpus, encoding='utf-8')
            record = {'topicId': row['topicId'], 'title': row['title'], 'bucket': row['bucket'],
                      'allowedChunks': [row['minChunks'], row['maxChunks']],
                      'actualChunks': len(actual['chunks']), 'estimatedTokens': actual['tokenCount'],
                      'chunkTokenCounts': [chunk['tokenCount'] for chunk in actual['chunks']],
                      'issues': issues, 'editorialStatus': 'ASSISTANT_AUTHORED_PREVIEW_NOT_INDEPENDENTLY_REVIEWED',
                      'sourceSha256': hashlib.sha256(corpus.encode('utf-8')).hexdigest(), 'chunks': actual['chunks']}
            (batch / f'preview-{index:03d}-chunks.json').write_text(json.dumps(record, ensure_ascii=False, indent=2), encoding='utf-8')
            results.append({key: value for key, value in record.items() if key != 'chunks'})
        (batch / 'preview-verification.json').write_text(json.dumps({'providerCalls': 0, 'cases': results}, ensure_ascii=False, indent=2), encoding='utf-8')
        print(json.dumps(results, ensure_ascii=False))
        if any(row['issues'] for row in results):
            raise ValueError('Some previews require revision; inspect preview-verification.json')
    finally:
        splitter.close()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('batch', type=Path)
    parser.add_argument('--topics', type=int, nargs='+', default=[44, 64])
    args = parser.parse_args()
    preview(args.batch.resolve(), args.topics)
