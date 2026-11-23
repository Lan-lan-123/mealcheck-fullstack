"""Assemble strict 200-topic literal prose; never call providers, embed, or reindex."""
import argparse
from collections import Counter, defaultdict
from datetime import datetime
import hashlib
import json
from pathlib import Path
import re
import subprocess

from check_longform_manuscript import BATCH, MANUSCRIPTS, parse_manuscript
from generate_knowledge_longform import Splitter, render, unsupported_medical_terms
from migrate_rag_longform_labels import migrate
from prepare_knowledge_longform import MARKER, ROOT, related_topics
from revise_knowledge_topic_prose import sentence_reuse

VERSION = 'v8-longform-200'
BOUNDS = {'SHORT': (40, 2, 3), 'MEDIUM': (60, 4, 6), 'LONG': (60, 7, 10), 'EXTENDED': (40, 12, 20)}


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write_json(path, value):
    path.write_bytes((json.dumps(value, ensure_ascii=False, indent=2) + '\n').encode('utf-8'))


def validate_plan(rows):
    if len(rows) != 200 or {r['topicId'] for r in rows} != {f'knowledge-{i:03}' for i in range(1, 201)}:
        raise ValueError('Exactly the original 200 topic IDs are required')
    if len({r['title'] for r in rows}) != 200:
        raise ValueError('Topic titles must remain unique')
    if Counter(r['bucket'] for r in rows) != Counter({k: v[0] for k, v in BOUNDS.items()}):
        raise ValueError('Original 40/60/60/40 distribution required')
    for row in rows:
        if (row['minChunks'], row['maxChunks']) != BOUNDS[row['bucket']][1:]:
            raise ValueError('Length bounds cannot be relaxed: ' + row['topicId'])


def validate_counts(rows, chunks):
    counts = Counter(c['title'] for c in chunks)
    if set(counts) != {r['title'] for r in rows}:
        raise ValueError('Whole corpus must contain exactly the original titles')
    for row in rows:
        if not row['minChunks'] <= counts[row['title']] <= row['maxChunks']:
            raise ValueError(f"{row['topicId']}: actual={counts[row['title']]}, required={row['minChunks']}..{row['maxChunks']}")
    if any(not 300 <= c['tokenCount'] <= 500 for c in chunks):
        raise ValueError('Every production chunk must be 300..500 estimated tokens')
    for key in ['contentHash', 'chunkKey']:
        if len({c[key] for c in chunks}) != len(chunks):
            raise ValueError('Duplicate chunk identity: ' + key)
    return counts


def audit_prose(supplements):
    paragraphs, owner = [], {}
    for topic_id, supplement in supplements.items():
        for section in supplement['sections']:
            for paragraph in section['paragraphs']:
                normalized = re.sub(r'\W+', '', paragraph)
                if normalized in owner:
                    raise ValueError('Duplicate paragraph: ' + owner[normalized] + ' / ' + topic_id)
                owner[normalized] = topic_id
                grams = {normalized[i:i + 5] for i in range(max(0, len(normalized) - 4))}
                paragraphs.append((topic_id, normalized, grams))
    # An inverted index avoids comparing every long paragraph pair. This is a
    # lexical diagnostic, not a claim that absence of overlap proves substance.
    inverted, near = defaultdict(list), []
    for index, (topic_id, paragraph, grams) in enumerate(paragraphs):
        overlap = Counter(j for gram in grams for j in inverted[gram])
        for previous, shared in overlap.items():
            other_id, other_text, other_grams = paragraphs[previous]
            if other_id == topic_id or shared < 20:
                continue
            score = shared / len(grams | other_grams)
            if score >= .45:
                near.append({'topics': [other_id, topic_id], 'jaccard5gram': round(score, 4),
                             'paragraphs': [other_text, paragraph]})
        for gram in grams:
            inverted[gram].append(index)
    reuse = sentence_reuse(supplements)
    if reuse['sharedSentenceOccurrences']:
        raise ValueError('Long sentences reused across >=10 topics require rewriting')
    if any(item['jaccard5gram'] >= .65 for item in near):
        raise ValueError('Near-duplicate authored paragraphs require editorial review/rewrite')
    return {'exactDuplicateParagraphs': 0, 'paragraphCount': len(paragraphs), 'sentenceReuse': reuse,
            'nearDuplicatePairsForReview': near,
            'limitation': 'Lexical checks do not establish factual accuracy or semantic completeness.'}


def read_author_review(topic_id, manuscript):
    path = BATCH / 'author-reviews' / (topic_id + '.json')
    if not path.exists():
        raise ValueError('Missing author review: ' + topic_id)
    review = json.loads(path.read_text(encoding='utf-8'))
    if (review.get('topicId') != topic_id or review.get('manuscriptSha256') != digest(manuscript)
            or review.get('editorialStatus') != 'AUTHOR_REVIEWED'
            or not review.get('uniqueKnowledgeUnits')):
        raise ValueError('Missing or stale author review: ' + topic_id)
    urls = review.get('additionalSourceUrls', [])
    if not isinstance(urls, list) or any(not url.startswith('https://') for url in urls):
        raise ValueError('Invalid additional sources: ' + topic_id)
    return review


def article_with_sources(row, supplement, review):
    article = render(row, supplement)
    added = [url for url in review.get('additionalSourceUrls', []) if url not in article]
    if added:
        article += '\n补充资料（新增事实来源；操作情境为应用示例）：' + '、'.join(
            f'[资料{index + 1}]({url})' for index, url in enumerate(added)) + '。\n'
    return article


def build(output):
    output = output.resolve()
    if not output.is_relative_to((ROOT / 'logs').resolve()) or output.exists():
        raise ValueError('Use a fresh evidence directory under logs/')
    plan = json.loads((BATCH / 'plan.json').read_text(encoding='utf-8'))
    rows = plan['topics']
    validate_plan(rows)
    expected = {r['topicId'] for r in rows}
    if {p.stem for p in MANUSCRIPTS.glob('knowledge-*.md')} != expected:
        raise ValueError('All 200 manuscripts are required before assembly')
    supplements, reviews, articles, topic_meta = {}, {}, [], []
    for row in rows:
        topic_id = row['topicId']
        manuscript = MANUSCRIPTS / (topic_id + '.md')
        supplement = parse_manuscript(manuscript)
        review = read_author_review(topic_id, manuscript)
        terms = unsupported_medical_terms(row, supplement, related_topics(row, rows))
        if terms:
            raise ValueError(topic_id + ': unresolved source-scope terms ' + repr(terms))
        supplements[topic_id], reviews[topic_id] = supplement, review
        articles.append(article_with_sources(row, supplement, review))
    audit = audit_prose(supplements)
    output.mkdir(parents=True)
    candidate = output / 'candidate-diet-guides.md'
    candidate.write_bytes((MARKER + '\n# MealCheck 饮食知识库\n\n'
        '版本：' + VERSION + '。200 个独立长文主题；一般校园选餐与饮食记录知识，不是个体营养处方。\n'
        '保留原有知识与引用；新增正文逐主题编写，计算数字为明确说明的假设示例。独立事实与检索相关性复核仍待完成。\n\n'
        + '\n'.join(articles)).encode('utf-8'))
    runtime = json.loads((BATCH / 'runtime.json').read_text(encoding='utf-8'))
    compiled_sources = runtime.get('compiledSources', {})
    if not compiled_sources or any(digest(Path(source)) != identity for source, identity in compiled_sources.items()):
        raise ValueError('Refresh the offline runtime from current production sources before assembly')
    manifest_path = output / 'candidate-chunks.json'
    subprocess.run(['java', '-XX:ErrorFile=' + str(ROOT / 'logs/crash/hs_err_pid%p.log'), '-cp', runtime['classpath'],
                    'KnowledgeCorpusTool', '--manifest', str(candidate), str(manifest_path)], cwd=ROOT, check=True)
    manifest = json.loads(manifest_path.read_text(encoding='utf-8'))
    if (manifest['minTokens'], manifest['maxTokens'], manifest['overlapTokens']) != (300, 500, 50):
        raise ValueError('Production splitter defaults changed')
    counts = validate_counts(rows, manifest['chunks'])
    for row, article in zip(rows, articles):
        topic_id = row['topicId']
        if row['originalBody'] not in article:
            raise ValueError('Original body lost: ' + topic_id)
        topic_meta.append({'topicId': topic_id, 'title': row['title'], 'lengthBucket': row['bucket'],
            'minChunks': row['minChunks'], 'maxChunks': row['maxChunks'], 'actualChunks': counts[row['title']],
            'lengthPlanSatisfied': True, 'sourceUrls': list(dict.fromkeys(row['sourceUrls'] + reviews[topic_id].get('additionalSourceUrls', []))),
            'authorship': 'CODEX_TOPIC_SPECIFIC_LITERAL_PROSE', 'authorReview': 'COMPLETED',
            'manuscriptSha256': reviews[topic_id]['manuscriptSha256'],
            'independentlyReviewed': False, 'originalBodyPreserved': True})
    before_cases = json.loads((BATCH / 'before-rag_eval_cases.json').read_text(encoding='utf-8'))
    migrated = migrate(before_cases, manifest)
    for case in migrated:
        case['annotationRevision'] = 'rag-evidence-v4-longform'
    if len(migrated) != 200 or any(a['id'] != b['id'] or a['question'] != b['question']
            for a, b in zip(before_cases, migrated)):
        raise ValueError('Evaluation questions changed')
    metadata = {'version': VERSION, 'corpusSha256': digest(candidate), 'topicCount': 200,
        'chunkCount': len(manifest['chunks']), 'splitter': plan['splitterDefaults'],
        'independentlyReviewed': False, 'authorship': 'CODEX_TOPIC_SPECIFIC_LITERAL_PROSE',
        'newProviderCalls': 0, 'contentStatus': 'PENDING_INDEPENDENT_CONTENT_AND_RELEVANCE_REVIEW',
        'lengthPolicy': 'CONTENT_QUALITY_AND_ORIGINAL_LENGTH_BOTH_REQUIRED', 'lengthGoalDeviationCount': 0,
        'lengthPlanStatus': 'SATISFIED', 'topics': topic_meta}
    splits = json.loads((BATCH / 'before-rag_eval_splits.json').read_text(encoding='utf-8'))
    splits['datasetVersion'] = 'rag-200-v5'
    write_json(output / 'candidate-diet-guides-metadata.json', metadata)
    write_json(output / 'candidate-rag-eval-cases.json', migrated)
    write_json(output / 'candidate-rag-eval-splits.json', splits)
    write_json(output / 'prose-audit.json', audit)
    write_json(output / 'author-reviews.json', reviews)
    write_json(output / 'assembly-report.json', {'status': 'CANDIDATE_PENDING_FINAL_EDITORIAL_REVIEW',
        'topics': 200, 'chunks': len(manifest['chunks']), 'distribution': dict(Counter(r['bucket'] for r in rows)),
        'lengthGoalDeviations': 0, 'providerCalls': 0, 'databaseReindexed': False,
        'questionsChanged': False, 'corpusSha256': digest(candidate), 'independentlyReviewed': False})
    lines = ['# 200 个长文主题验收索引', '', '实际生产切分：300–500 估算 token，重叠 50。所有数字均为离线切分结果。',
             '作者自检与助手编辑复核不等于独立人工事实或相关性标注。', '',
             '|主题|范围|实际片数|正文|', '|---|---:|---:|---|']
    for topic in topic_meta:
        source = (MANUSCRIPTS / (topic['topicId'] + '.md')).as_posix()
        lines.append(f"|{topic['topicId']} {topic['title']}|{topic['minChunks']}–{topic['maxChunks']}|{topic['actualChunks']}|[新增正文]({source})|")
    (output / 'review-index.md').write_bytes(('\n'.join(lines) + '\n').encode('utf-8'))
    print(json.dumps({'output': str(output), 'topics': 200, 'chunks': len(manifest['chunks']),
                      'lengthGoalDeviations': 0, 'providerCalls': 0}, ensure_ascii=False))
    return metadata


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output-directory', type=Path, default=ROOT / 'logs/backend' /
                        ('knowledge-v8-candidate-' + datetime.now().strftime('%Y%m%d-%H%M%S-%f')))
    build(parser.parse_args().output_directory)
