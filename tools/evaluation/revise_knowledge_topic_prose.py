"""Build a topic-specific revision from literal prose, without templates or model calls.

Original length goals are audited, never filled with generated boilerplate. The
production splitter remains unchanged. This command only writes a candidate to logs/.
"""
import argparse
from collections import Counter, defaultdict
from datetime import datetime
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess

from generate_knowledge_longform import Splitter, render
from migrate_rag_longform_labels import migrate
from prepare_knowledge_longform import ROOT, MARKER


def load_prose(path):
    text = path.read_text(encoding='utf-8')
    matches = list(re.finditer(r'^## (knowledge-\d{3})\s*$', text, re.M))
    result = {}
    for index, match in enumerate(matches):
        topic_id = match.group(1)
        if topic_id in result:
            raise ValueError('Duplicate literal topic: ' + topic_id)
        end = matches[index + 1].start() if index + 1 < len(matches) else len(text)
        body = text[match.end():end].strip()
        heading = re.match(r'^\*\*(.+?)\*\*\s*\n', body)
        if not heading:
            raise ValueError('Missing topic-specific subheading: ' + topic_id)
        paragraphs = [item.strip() for item in body[heading.end():].split('\n\n') if item.strip()]
        if not paragraphs or any(len(item) < 50 for item in paragraphs):
            raise ValueError('Empty or insubstantial literal paragraphs: ' + topic_id)
        result[topic_id] = {'sections': [{'heading': heading.group(1), 'paragraphs': paragraphs}]}
    return result


def sentence_reuse(supplements):
    topics_by_sentence = defaultdict(set)
    occurrences = []
    for topic_id, supplement in supplements.items():
        for section in supplement['sections']:
            for paragraph in section['paragraphs']:
                for sentence in re.split('[。！？]', paragraph):
                    sentence = sentence.strip()
                    if len(sentence) >= 20:
                        topics_by_sentence[sentence].add(topic_id)
                        occurrences.append(sentence)
    shared = [sentence for sentence in occurrences if len(topics_by_sentence[sentence]) >= 10]
    return {'minimumSentenceCharacters': 20, 'sharedAcrossAtLeastTopics': 10,
            'sentenceOccurrences': len(occurrences), 'sharedSentenceOccurrences': len(shared),
            'sharedOccurrenceRatio': len(shared) / len(occurrences) if occurrences else 0,
            'frequentSentences': [{'text': sentence, 'distinctTopics': len(topics)}
                                  for sentence, topics in sorted(topics_by_sentence.items(), key=lambda item: len(item[1]), reverse=True)[:20]]}


def write_json(path, value):
    path.write_bytes((json.dumps(value, ensure_ascii=False, indent=2) + '\n').encode('utf-8'))


def build(source, output, prose_path):
    source, output = source.resolve(), output.resolve()
    if not source.is_relative_to((ROOT / 'logs').resolve()) or not output.is_relative_to((ROOT / 'logs').resolve()):
        raise ValueError('Evidence batches must be under logs/')
    if output.exists():
        raise ValueError('Use a new output directory to preserve previous evidence')
    plan = json.loads((source / 'plan.json').read_text(encoding='utf-8'))
    supplements = load_prose(prose_path)
    details = json.loads(Path(__file__).with_name('knowledge_longform_topic_details.json').read_text(encoding='utf-8'))
    for index, detail in details.items():
        topic_id = f'knowledge-{int(index):03d}'
        if topic_id in supplements:
            raise ValueError('A topic must have exactly one authored source: ' + topic_id)
        supplements[topic_id] = {'sections': [detail]}
    expected = {row['topicId'] for row in plan['topics']}
    if set(supplements) != expected:
        raise ValueError('Literal prose coverage differs: ' + repr(sorted(expected - set(supplements))) + '; extra=' + repr(sorted(set(supplements) - expected)))
    output.mkdir(parents=True)
    (output / 'drafts').mkdir()
    resources = ROOT / 'backend/src/main/resources/knowledge'
    for name in ['diet_guides.md', 'rag_eval_cases.json', 'diet_guides_metadata.json', 'rag_eval_splits.json']:
        shutil.copy2(resources / name, output / ('before-' + name))
    shutil.copy2(source / 'runtime.json', output / 'runtime.json')
    shutil.copy2(source / 'plan.json', output / 'original-length-plan.json')
    shutil.copy2(prose_path, output / 'authored-prose.md')
    shutil.copy2(Path(__file__).with_name('knowledge_longform_topic_details.json'), output / 'retained-topic-details.json')
    before_supplements = {file.stem: json.loads(file.read_text(encoding='utf-8'))['supplement']
                          for file in (source / 'drafts').glob('knowledge-*.json')}
    previous_metadata = json.loads((resources / 'diet_guides_metadata.json').read_text(encoding='utf-8'))
    previous_topics = {topic['topicId']: topic for topic in previous_metadata['topics']}
    splitter = Splitter(output)
    articles, topics, paragraphs = [], [], {}
    try:
        for row in plan['topics']:
            supplement = supplements[row['topicId']]
            article = render(row, supplement)
            actual = splitter.split(MARKER + '\n' + article)
            count = len(actual['chunks'])
            if any(chunk['tokenCount'] > 500 for chunk in actual['chunks']):
                raise ValueError(row['topicId'] + ': invalid production chunk length')
            if count > 1 and any(chunk['tokenCount'] < 300 for chunk in actual['chunks']):
                raise ValueError(row['topicId'] + ': split multi-chunk article has an undersized fragment')
            for section in supplement['sections']:
                for paragraph in section['paragraphs']:
                    normalized = re.sub(r'\s+', '', paragraph)
                    if normalized in paragraphs:
                        raise ValueError('Duplicate literal paragraph in ' + row['topicId'] + ' and ' + paragraphs[normalized])
                    paragraphs[normalized] = row['topicId']
            length_ok = row['minChunks'] <= count <= row['maxChunks']
            topics.append({'topicId': row['topicId'], 'title': row['title'], 'lengthBucket': row['bucket'],
                           'minChunks': row['minChunks'], 'maxChunks': row['maxChunks'], 'actualChunks': count,
                           'lengthPlanSatisfied': length_ok,
                           'sourceUrls': previous_topics[row['topicId']]['sourceUrls'],
                           'authorship': 'LOCAL_ASSISTANT_TOPIC_SPECIFIC_LITERAL_PROSE', 'independentlyReviewed': False,
                           'originalBodyPreserved': row['originalBody'] in article})
            write_json(output / 'drafts' / (row['topicId'] + '.json'), {'topicId': row['topicId'], 'supplement': supplement})
            (output / 'drafts' / (row['topicId'] + '.md')).write_bytes((MARKER + '\n' + article).encode('utf-8'))
            articles.append(article)
    finally:
        splitter.close()
    candidate = (MARKER + '\n# MealCheck 饮食知识库\n\n'
                 '版本：v7-topic-specific-200。适用：一般校园选餐与饮食记录，不是诊断、治疗或个体营养处方。\n'
                 '200 个独立主题。保留原文与来源，新增正文逐主题编写，计算与校园情境为假设示例，仍待独立人工复核。\n\n'
                 + '\n'.join(articles))
    candidate_path = output / 'candidate-diet-guides.md'
    candidate_path.write_bytes(candidate.encode('utf-8'))
    runtime = json.loads((output / 'runtime.json').read_text(encoding='utf-8'))
    subprocess.run(['java', '-XX:ErrorFile=' + str(ROOT / 'logs/crash/hs_err_pid%p.log'), '-cp', runtime['classpath'],
                    'KnowledgeCorpusTool', '--manifest', str(candidate_path), str(output / 'candidate-chunks.json')], cwd=ROOT, check=True)
    manifest = json.loads((output / 'candidate-chunks.json').read_text(encoding='utf-8'))
    if len(manifest['chunks']) != sum(topic['actualChunks'] for topic in topics):
        raise ValueError('Whole-corpus and per-topic counts differ')
    old_cases = json.loads((output / 'before-rag_eval_cases.json').read_text(encoding='utf-8'))
    migrated = migrate(old_cases, manifest)
    for case in migrated:
        case['annotationRevision'] = 'rag-evidence-v3-topic-specific'
        case['annotation']['provenance'] = 'ASSISTANT_MIGRATED_PRESERVED_ORIGINAL_ANCHORS'
    write_json(output / 'candidate-rag-eval-cases.json', migrated)
    before_reuse, after_reuse = sentence_reuse(before_supplements), sentence_reuse(supplements)
    if after_reuse['sharedOccurrenceRatio'] >= before_reuse['sharedOccurrenceRatio']:
        raise ValueError('The revision did not reduce shared-sentence occurrences')
    metadata = {'version': 'v7-topic-specific-200', 'corpusSha256': manifest['corpusSha256'], 'topicCount': 200,
                'chunkCount': manifest['chunkCount'], 'splitter': plan['splitterDefaults'],
                'independentlyReviewed': False, 'authorship': 'LOCAL_ASSISTANT_TOPIC_SPECIFIC_LITERAL_PROSE',
                'newProviderCalls': 0, 'contentStatus': 'PENDING_INDEPENDENT_CONTENT_AND_RELEVANCE_REVIEW',
                'compositionMethod': 'Literal topic-specific paragraphs; no generic prose composition or length padding.',
                'lengthPolicy': 'CONTENT_QUALITY_FIRST_USER_SELECTED',
                'lengthGoalDeviationCount': sum(not topic['lengthPlanSatisfied'] for topic in topics),
                'lengthPlanStatus': 'SATISFIED' if all(topic['lengthPlanSatisfied'] for topic in topics) else 'UNMET_AFTER_DEDUPLICATION',
                'topics': topics}
    write_json(output / 'candidate-diet-guides-metadata.json', metadata)
    report = {'status': 'CANDIDATE_ONLY', 'topics': 200, 'chunks': manifest['chunkCount'], 'newProviderCalls': 0,
              'beforeSentenceReuse': before_reuse, 'afterSentenceReuse': after_reuse,
              'beforeSupplementCharacters': sum(len(paragraph) for supplement in before_supplements.values() for section in supplement['sections'] for paragraph in section['paragraphs']),
              'afterSupplementCharacters': sum(len(paragraph) for supplement in supplements.values() for section in supplement['sections'] for paragraph in section['paragraphs']),
              'lengthPlanStatus': metadata['lengthPlanStatus'],
              'lengthGoalDeviations': [topic for topic in topics if not topic['lengthPlanSatisfied']],
              'wholeParagraphDuplicates': 0, 'preservedOriginalTopics': 200,
              'migratedCases': len(migrated), 'questionsChanged': False, 'databaseReindexed': False,
              'independentReviewCompleted': False}
    write_json(output / 'revision-report.json', report)
    print(json.dumps({'topics': 200, 'chunks': manifest['chunkCount'], 'reusedSentenceOccurrenceRatioBefore': before_reuse['sharedOccurrenceRatio'],
                      'reusedSentenceOccurrenceRatioAfter': after_reuse['sharedOccurrenceRatio'],
                      'lengthGoalDeviations': len(report['lengthGoalDeviations']), 'providerCalls': 0}, ensure_ascii=False))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-batch', type=Path, required=True)
    parser.add_argument('--output-directory', type=Path, default=ROOT / 'logs/backend' / ('knowledge-topic-revision-' + datetime.now().strftime('%Y%m%d-%H%M%S-%f')))
    parser.add_argument('--prose', type=Path, default=Path(__file__).with_name('knowledge_topic_revisions.md'))
    args = parser.parse_args()
    build(args.source_batch, args.output_directory, args.prose)
