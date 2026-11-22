"""Assemble locally validated drafts into a candidate and provenance metadata; never publish or call models."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess

from generate_knowledge_longform import Splitter, assess, unsupported_medical_terms
from knowledge_longform_status import status
from prepare_knowledge_longform import ROOT, MARKER, related_topics


def assemble(batch):
    batch = batch.resolve()
    if not batch.is_relative_to((ROOT / 'logs').resolve()):
        raise ValueError('Batch must be under logs/')
    progress = status(batch)
    if not progress['withinGlobalCeiling'] or progress['completedDrafts'] != 200:
        raise ValueError('Global 200-topic coverage and original budget must pass')
    if (batch / 'parallel-plan.json').exists():
        state = json.loads((batch / 'generation.json').read_text(encoding='utf-8'))
        if not state.get('childBatchesMerged'):
            raise ValueError('Merge both completed disjoint batches before assembling')
    plan = json.loads((batch / 'plan.json').read_text(encoding='utf-8'))
    before_cases = json.loads((batch / 'before-rag-cases.json').read_text(encoding='utf-8'))
    quotes = {}
    for case in before_cases:
        for target in case['evidenceTargets']:
            quotes.setdefault(target['title'], []).append(target['evidenceQuotes'])
    articles, metadata, paragraphs = [], [], {}
    splitter = Splitter(batch)
    try:
        for row in plan['topics']:
            record = json.loads((batch / ('drafts/' + row['topicId'] + '.json')).read_text(encoding='utf-8'))
            article, actual, issues = assess(row, record['supplement'], splitter)
            unsupported = unsupported_medical_terms(row, record['supplement'], related_topics(row, plan['topics']))
            if issues or unsupported:
                raise ValueError(row['topicId'] + ': unresolved shape or source-scope issues')
            for anchor_group in quotes.get(row['title'], []):
                if not any(all(anchor in chunk['content'] for anchor in anchor_group) for chunk in actual['chunks']):
                    raise ValueError(row['topicId'] + ': original evidence anchors require manual review')
            for section in record['supplement']['sections']:
                for paragraph in section['paragraphs']:
                    key = re.sub(r'\s+', '', paragraph)
                    if key in paragraphs and paragraphs[key] != row['topicId']:
                        raise ValueError('Repeated authored paragraph across topics: ' + row['topicId'])
                    paragraphs[key] = row['topicId']
            urls = list(row['sourceUrls'])
            if row['topicIndex'] == 177:
                urls.append('https://www.nist.gov/glossary-term/26261')
            background = []
            for related in related_topics(row, plan['topics']):
                background.extend(re.findall(r'https://[^\s)）]+', related['supportedOriginalText']))
            metadata.append({'topicId': row['topicId'], 'title': row['title'], 'lengthBucket': row['bucket'],
                             'minChunks': row['minChunks'], 'maxChunks': row['maxChunks'], 'actualChunks': len(actual['chunks']),
                             'estimatedTokens': actual['tokenCount'], 'sourceUrls': urls,
                             'backgroundSourceUrls': list(dict.fromkeys(background)),
                             'authorship': record.get('authorship', 'MODEL_GENERATED_WITH_SOURCE_SCOPE_AND_LOCAL_CHECKS'),
                             'independentlyReviewed': False})
            articles.append(article)
    finally:
        splitter.close()
    candidate = (MARKER + '\n# MealCheck 饮食知识库\n\n'
                 '版本：v6-longform-200。适用：一般校园选餐与饮食记录，不是诊断、治疗或个体营养处方。\n'
                 '200 个独立主题，按长短分档切分。原有原则与来源保留，新增内容由助手编写，采用分主题说明、条件对照与假设案例结构，仍待独立人工复核；案例不是商品实测或个人营养数据。\n\n'
                 + '\n'.join(articles))
    destination = batch / 'candidate-diet-guides.md'
    if destination.exists() and destination.read_text(encoding='utf-8') != candidate:
        raise ValueError('An earlier different candidate exists; archive it before creating a revision')
    destination.write_bytes(candidate.encode('utf-8'))
    manifest = {'version': 'v6-longform-200', 'corpusSha256': hashlib.sha256(candidate.encode('utf-8')).hexdigest(),
                'topicCount': 200, 'chunkCount': sum(row['actualChunks'] for row in metadata),
                'splitter': plan['splitterDefaults'], 'independentlyReviewed': False,
                'authorship': 'LOCAL_ASSISTANT_AUTHORED_CASE_NOTES_AND_PROSE_COMPOSITION',
                'newProviderCalls': 0, 'contentStatus': 'ASSISTANT_REVIEWED_PENDING_INDEPENDENT_REVIEW',
                'compositionMethod': 'Topic-specific authored cases and details, organized with shared domain teaching stages. Not independently authored or blindly annotated gold data.',
                'topics': metadata}
    (batch / 'candidate-diet-guides-metadata.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding='utf-8')
    runtime = json.loads((batch / 'runtime.json').read_text(encoding='utf-8'))
    output = batch / 'candidate-chunks.json'
    if output.exists():
        raise ValueError('Candidate splitter manifest already exists; preserve historical evidence')
    crash = ROOT / 'logs/crash'
    crash.mkdir(parents=True, exist_ok=True)
    subprocess.run(['java', '-XX:ErrorFile=' + str(crash / 'hs_err_pid%p.log'), '-cp', runtime['classpath'],
                    'KnowledgeCorpusTool', '--manifest', str(destination), str(output)], cwd=ROOT, check=True)
    print(json.dumps({'topics': 200, 'chunks': manifest['chunkCount'], 'providerCalls': 0, 'candidate': str(destination)}))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('batch', type=Path)
    assemble(parser.parse_args().batch)
