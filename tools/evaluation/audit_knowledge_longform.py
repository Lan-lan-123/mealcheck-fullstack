"""Audit actual chunk ranges, duplicates, evidence preservation and bounded call accounting offline."""
import argparse
from collections import Counter, defaultdict
from datetime import datetime
import json
from pathlib import Path
import re

from generate_knowledge_longform import Splitter, assess, accounted_usage, unsupported_medical_terms
from prepare_knowledge_longform import ROOT, related_topics


def audit(batch):
    batch = batch.resolve()
    if not batch.is_relative_to((ROOT / 'logs').resolve()):
        raise ValueError('Batch must be under logs/')
    plan = json.loads((batch / 'plan.json').read_text(encoding='utf-8'))
    state = json.loads((batch / 'generation.json').read_text(encoding='utf-8'))
    before = json.loads((batch / 'before-rag-cases.json').read_text(encoding='utf-8'))
    evidence = defaultdict(list)
    for case in before:
        for target in case['evidenceTargets']:
            evidence[target['title']].append(target['evidenceQuotes'])
    records, missing, duplicate_paragraphs = [], [], defaultdict(list)
    counts = Counter()
    splitter = Splitter(batch)
    try:
        for row in plan['topics']:
            path = batch / ('drafts/' + row['topicId'] + '.json')
            if not path.exists():
                missing.append(row['topicId'])
                continue
            record = json.loads(path.read_text(encoding='utf-8'))
            if 'supplement' not in record:
                records.append({'topicId': row['topicId'], 'title': row['title'], 'issues': record['issues']})
                continue
            try:
                article, actual, issues = assess(row, record['supplement'], splitter)
            except (ValueError, TypeError, KeyError) as error:
                records.append({'topicId': row['topicId'], 'title': row['title'], 'issues': [str(error)]})
                continue
            terms = unsupported_medical_terms(row, record['supplement'], related_topics(row, plan['topics']))
            if terms:
                issues.append('unsupported_medical_terms:' + ','.join(terms))
            for quotations in evidence[row['title']]:
                if not any(all(quote in chunk['content'] for quote in quotations) for chunk in actual['chunks']):
                    issues.append('original_evidence_anchors_do_not_fit_one_chunk')
            warnings = []
            for section in record['supplement']['sections']:
                for paragraph in section['paragraphs']:
                    duplicate_paragraphs[re.sub(r'\s+', '', paragraph)].append(row['topicId'])
                    for pattern, reason in [
                        (r'(?:约为|标准|通常).{0,20}\d+(?:[-–]\d+)?\s*(?:克|g|毫升|ml)', 'possible_invented_standard_quantity'),
                        (r'每天至少|必须每天|每个人都|强制搭配|强制核对|应急记录模式|“修订版”功能', 'possible_universal_rule_or_invented_feature'),
                        (r'不明.{0,20}不计入|未知.{0,20}按零|不确定.{0,20}没有蛋白', 'possible_unknown_as_zero'),
                        (r'每(?:公斤|千克|kg).{0,15}\d+', 'possible_personal_nutrient_prescription'),
                        (r'\d+\s*(?:℃|摄氏度)|常温.{0,20}\d+.{0,5}(?:小时|天)', 'new_food_safety_numeric_condition'),
                    ]:
                        if re.search(pattern, paragraph):
                            warnings.append({'reason': reason, 'heading': section['heading'], 'paragraph': paragraph})
            if not issues:
                counts[row['bucket']] += 1
            records.append({'topicId': row['topicId'], 'title': row['title'], 'bucket': row['bucket'],
                            'actualChunks': len(actual['chunks']), 'allowedChunks': [row['minChunks'], row['maxChunks']],
                            'estimatedTokens': actual['tokenCount'], 'issues': issues, 'editorialWarnings': warnings,
                            'independentlyReviewed': False})
    finally:
        splitter.close()
    duplicates = [{'topics': sorted(set(ids)), 'paragraph': paragraph}
                  for paragraph, ids in duplicate_paragraphs.items() if len(set(ids)) > 1]
    known_usage = sum(call.get('usage', {}).get('total_tokens', 0) for call in state['calls'])
    conservative_usage = sum(accounted_usage(call['usage'], call['inputAllowance'] + call['maximumOutputTokens'])
                             if call.get('usage') else call['accountedTokens'] for call in state['calls'])
    issues = [row for row in records if row['issues']]
    result = {'status': 'OFFLINE_AUDIT', 'sourceTopics': len(plan['topics']), 'recordedDrafts': len(records),
              'missingTopics': missing, 'actualChunks': sum(row.get('actualChunks', 0) for row in records),
              'validBucketCounts': dict(counts), 'issues': issues,
              'duplicateAuthoredParagraphs': duplicates, 'records': records,
              'providerCallsSent': len(state['calls']), 'providerReportedTokens': known_usage,
              'conservativelyAccountedTokens': conservative_usage,
              'unknownOrInterruptedCalls': [call['topicId'] for call in state['calls'] if not call.get('usage')],
              'withinOriginalBudget': len(state['calls']) <= 200 and conservative_usage <= 3000000,
              'structuralAndAnchorChecksPass': len(records) == 200 and not missing and not issues and not duplicates,
              'independentHumanReviewCompleted': False,
              'limitation': 'Structural/lexical checks do not prove factual entailment; known editorial issues must be repaired before publication.'}
    output = batch / 'audits'
    output.mkdir(exist_ok=True)
    path = output / (datetime.now().strftime('%Y%m%d-%H%M%S-%f') + '.json')
    with path.open('x', encoding='utf-8') as stream:
        json.dump(result, stream, ensure_ascii=False, indent=2)
    print(json.dumps({'recordedDrafts': len(records), 'missingTopics': len(missing),
                      'actualChunks': result['actualChunks'], 'invalidDrafts': len(issues),
                      'editorialWarnings': sum(len(row.get('editorialWarnings', [])) for row in records),
                      'providerCallsSent': result['providerCallsSent'],
                      'conservativelyAccountedTokens': conservative_usage, 'report': str(path)}, ensure_ascii=False))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('batch', type=Path)
    audit(parser.parse_args().batch)
