"""Check v8 literal manuscripts against the original length plan; no model or DB calls."""
import argparse
from collections import Counter
from datetime import datetime
import json
from pathlib import Path
import re
import shutil

from generate_knowledge_longform import Splitter, render, unsupported_medical_terms
from prepare_knowledge_longform import ROOT, related_topics, topics

BATCH = ROOT / 'logs/backend/knowledge-longform-v8-20261020'
MANUSCRIPTS = ROOT / 'tools/evaluation/knowledge_longform_v8'


def prepare():
    if BATCH.exists():
        return
    resources = ROOT / 'backend/src/main/resources/knowledge'
    original = ROOT / 'logs/backend/knowledge-longform-20261020-distribution-v1'
    plan = json.loads((original / 'plan.json').read_text(encoding='utf-8'))
    current = {row['topicId']: row for row in topics((resources / 'diet_guides.md').read_text(encoding='utf-8'))}
    metadata = json.loads((resources / 'diet_guides_metadata.json').read_text(encoding='utf-8'))
    source_urls = {row['topicId']: row['sourceUrls'] for row in metadata['topics']}
    BATCH.mkdir(parents=True)
    (BATCH / 'checks').mkdir()
    (BATCH / 'author-reviews').mkdir()
    MANUSCRIPTS.mkdir(parents=True, exist_ok=True)
    for row in plan['topics']:
        row['preservedOriginalBody'] = row['originalBody']
        row['originalBody'] = current[row['topicId']]['originalBody']
        row['sourceUrls'] = source_urls[row['topicId']]
    plan['status'] = 'STRICT_LENGTH_AND_CONTENT_REQUIRED'
    plan['baselineVersion'] = metadata['version']
    (BATCH / 'plan.json').write_bytes((json.dumps(plan, ensure_ascii=False, indent=2) + '\n').encode('utf-8'))
    shutil.copy2(original / 'runtime.json', BATCH / 'runtime.json')
    for name in ['diet_guides.md', 'diet_guides_metadata.json', 'rag_eval_cases.json', 'rag_eval_splits.json']:
        shutil.copy2(resources / name, BATCH / ('before-' + name))


def parse_manuscript(path):
    text = path.read_text(encoding='utf-8').strip()
    if re.search(r'^\s*#{1,6}\s|```|https?://|<!--|<script', text, re.M | re.I):
        raise ValueError('Use literal paragraphs and bold section headings, without # headings, URLs or code')
    matches = list(re.finditer(r'^\*\*(.+?)\*\*\s*$', text, re.M))
    if not matches or text[:matches[0].start()].strip():
        raise ValueError('Start with a bold topic-specific section heading')
    sections = []
    for index, match in enumerate(matches):
        end = matches[index + 1].start() if index + 1 < len(matches) else len(text)
        paragraphs = [item.strip() for item in text[match.end():end].split('\n\n') if item.strip()]
        if not paragraphs or any(len(paragraph) < 50 for paragraph in paragraphs):
            raise ValueError('Every section needs substantial paragraphs of at least 50 characters: ' + match.group(1))
        sections.append({'heading': match.group(1), 'paragraphs': paragraphs})
    return {'sections': sections}


def check(indices, strict=True):
    prepare()
    rows = json.loads((BATCH / 'plan.json').read_text(encoding='utf-8'))['topics']
    selected = [row for row in rows if row['topicIndex'] in indices]
    if len(selected) != len(set(indices)):
        raise ValueError('Unknown topic indices')
    results = []
    splitter = Splitter(BATCH)
    try:
        for row in selected:
            path = MANUSCRIPTS / (row['topicId'] + '.md')
            if not path.exists():
                results.append({'topicId': row['topicId'], 'title': row['title'], 'status': 'MISSING'})
                continue
            try:
                supplement = parse_manuscript(path)
                article = render(row, supplement)
                actual = splitter.split('<!-- mealcheck:independent-topics -->\n' + article)
                count = len(actual['chunks'])
                issues = []
                if not row['minChunks'] <= count <= row['maxChunks']:
                    issues.append(f"chunk_count={count}, required={row['minChunks']}..{row['maxChunks']}")
                if any(not 300 <= part['tokenCount'] <= 500 for part in actual['chunks']):
                    issues.append('chunk_outside_300_500')
                terms = unsupported_medical_terms(row, supplement, related_topics(row, rows))
                if terms:
                    issues.append('source_scope_terms:' + ','.join(terms))
                sentences = [sentence.strip() for section in supplement['sections'] for paragraph in section['paragraphs']
                             for sentence in re.split('[。！？]', paragraph) if len(sentence.strip()) >= 20]
                repeated = [{'sentence': sentence, 'occurrences': frequency} for sentence, frequency in Counter(sentences).items() if frequency >= 3]
                result = {'topicId': row['topicId'], 'title': row['title'], 'status': 'SHAPE_PASS_PENDING_EDITORIAL_REVIEW' if not issues else 'FAILED',
                          'actualChunks': count, 'allowedChunks': [row['minChunks'], row['maxChunks']],
                          'addedCharacters': sum(len(paragraph) for section in supplement['sections'] for paragraph in section['paragraphs']),
                          'sectionHeadings': [section['heading'] for section in supplement['sections']],
                          'issues': issues, 'withinArticleRepeatedSentences': repeated,
                          'independentlyReviewed': False, 'providerCalls': 0}
            except (ValueError, TypeError, KeyError) as error:
                result = {'topicId': row['topicId'], 'title': row['title'], 'status': 'FAILED', 'issues': [str(error)]}
            results.append(result)
    finally:
        splitter.close()
    output = BATCH / 'checks' / (datetime.now().strftime('%Y%m%d-%H%M%S-%f') + '.json')
    output.write_bytes((json.dumps({'providerCalls': 0, 'records': results}, ensure_ascii=False, indent=2) + '\n').encode('utf-8'))
    for result in results:
        print(json.dumps(result, ensure_ascii=False))
    if strict and any(result['status'] in {'FAILED', 'MISSING'} for result in results):
        raise ValueError('Some manuscripts are missing or do not satisfy the original length/scope gates')
    return results


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--topics', type=int, nargs='+')
    parser.add_argument('--prepare-only', action='store_true')
    parser.add_argument('--all-existing', action='store_true')
    parser.add_argument('--report-only', action='store_true')
    args = parser.parse_args()
    prepare()
    indices = args.topics or ([int(path.stem.split('-')[1]) for path in MANUSCRIPTS.glob('knowledge-*.md')] if args.all_existing else [])
    if not args.prepare_only:
        if not indices:
            parser.error('Select --topics or --all-existing')
        check(indices, not args.report_only)
