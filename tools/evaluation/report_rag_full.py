"""Summarize opt-in RAG full runs without treating model self-review as human accuracy."""

import argparse
from collections import Counter
import csv
import json
from pathlib import Path


def explicit_refusal(row):
    return row.get('actualRefused') is True or row.get('summary', '').startswith((
        '目前知识库证据不足', '知识检索或证据核验暂时不可用'))


def summarize(data):
    rows = data.get('cases', [])
    completed = len(rows)
    selected = data.get('selectedCaseIds', [])
    live = data.get('executionMode') == 'LIVE_PROVIDER' and data.get('qualityMeasurement') is True
    calls = data.get('calls', [])
    actual_calls = [call for call in calls if not call.get('cacheHit')]
    usage = Counter()
    for call in actual_calls:
        for key in ('prompt_tokens', 'completion_tokens', 'total_tokens'):
            usage[key] += (call.get('usage') or {}).get(key, 0)
    tool_passed = sum(row.get('toolPassed') is True and (row.get('functionCallingSuccessful') is True
                      or row.get('generationPath') == 'BACKEND_BOUNDARY') for row in rows)
    quality_passed = sum(row.get('qualityPassed') is True for row in rows)
    end_to_end_passed = sum(row.get('endToEndPassed') is True and row.get('status') == 'COMPLETE' for row in rows)
    answerable = [row for row in rows if not row.get('expectNoAnswer')]
    no_answer = [row for row in rows if row.get('expectNoAnswer')]
    with_refs = [row for row in rows if row.get('references')]
    benchmark_rows = (data.get('retrievalBenchmark') or {}).get('cases', [])
    retrieval_answerable = [row for row in benchmark_rows if not row.get('expectNoAnswer')]
    return {
        'realModelEvidence': live,
        'complete': live and data.get('state') == 'COMPLETE' and not data.get('providerStopped')
                    and completed == len(selected) and set(selected) == {row.get('caseId') for row in rows}
                    and all(row.get('status') == 'COMPLETE' and (row.get('usedRemoteChat') is True
                            or row.get('generationPath') == 'BACKEND_BOUNDARY')
                            and isinstance(row.get('qualityJudgment'), dict) for row in rows),
        'selectedCaseIds': selected, 'sampleSeed': data.get('sampleSeed'),
        'expectedCount': len(selected), 'completedCount': completed,
        'executionErrors': sum(row.get('status') != 'COMPLETE' for row in rows),
        'answerableCount': len(answerable), 'noAnswerCount': len(no_answer),
        'toolPassedCount': tool_passed,
        'toolAccuracy': tool_passed / completed if completed else None,
        'qualityProxyPassedCount': quality_passed,
        'factualVerdicts': dict(Counter((row.get('qualityJudgment') or {}).get('factualVerdict', 'LEGACY_UNSEPARATED') for row in rows)),
        'orchestratedToolCalls': sum(call.get('origin') == 'ORCHESTRATOR' for row in rows for call in row.get('toolTrace', [])),
        'modelRequestedToolCalls': sum(call.get('origin', 'MODEL') != 'ORCHESTRATOR' for row in rows for call in row.get('toolTrace', [])),
        'qualityProxyPassRate': quality_passed / completed if live and completed else None,
        'endToEndPassedCount': end_to_end_passed,
        'endToEndProxyPassRate': end_to_end_passed / completed if live and completed else None,
        'hitAt3Count': sum(row.get('hitAt3') is True for row in retrieval_answerable),
        'hitAt5Count': sum(row.get('hitAt5') is True for row in retrieval_answerable),
        'retrievalAnswerableCount': len(retrieval_answerable),
        'referenceIdentityPassedCount': sum(row.get('referencesExistInKnowledge') is True for row in with_refs),
        'casesWithReferencesCount': len(with_refs),
        'falseRefusalProxyCount': sum((row.get('qualityJudgment') or {}).get('actuallyRefused') is True for row in answerable),
        'explicitKnowledgeRefusalCount': sum(explicit_refusal(row) for row in answerable),
        'partialAnswerCount': sum(row.get('partialAnswer') is True for row in answerable),
        'groundingIssueCodes': dict(Counter(issue.get('code', 'UNKNOWN') for row in rows
            for issue in (row.get('groundingIssues') or []) if isinstance(issue, dict))),
        'noAnswerBoundaryProxyPassedCount': sum((row.get('qualityJudgment') or {}).get('actuallyRefused') is True
                                               and not row.get('references') for row in no_answer),
        'actualProviderCalls': len(actual_calls), 'embeddingCacheHits': len(calls) - len(actual_calls),
        'callsWithoutUsage': sum(not call.get('usage') for call in actual_calls),
        'actualCallsByOperation': dict(Counter(call.get('operation') for call in actual_calls)),
        'providerUsage': dict(usage),
        'limitations': ['工具准确率包含参数主题词规则，词语未命中不一定代表语义错误。',
                        '回答及引用支持度由同一生成模型复核，属于代理评分，不能作为独立人工准确率。',
                        '来源 ID/正文匹配只验证来源身份，不能证明来源支持回答。',
                        '直查命中率与模型自主构造查询后的召回分开测量，不能互相替代。']
    }


def safe(value):
    return str(value).replace('|', '\\|').replace('\n', ' ')


def write_report(data, output):
    summary = summarize(data)
    output.mkdir(parents=True, exist_ok=True)
    (output / 'summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding='utf-8')
    count = summary['completedCount']
    lines = [f"# RAG 随机 {summary['expectedCount']} 题完整评测", '',
             f"执行：{data.get('executionMode')}；状态：{data.get('state')}；完整真实证据：{summary['complete']}。",
             f"随机种子：{summary['sampleSeed']}；题号：{summary['selectedCaseIds']}。",
             f"向量模型：{data.get('embeddingModel')}；生成及复核模型：{data.get('chatModel')}；独立裁判：否。", '',
             '| 指标 | 结果 |', '| --- | --- |',
             f"| 已完成 / 抽取 | {count}/{summary['expectedCount']} |",
             f"| 执行错误 | {summary['executionErrors']} |",
             f"| 工具规则通过 | {summary['toolPassedCount']}/{count} |",
             f"| 回答质量模型复核通过 | {summary['qualityProxyPassedCount']}/{count} |",
             f"| 工具与回答均通过 | {summary['endToEndPassedCount']}/{count} |",
             f"| 明确整题拒答（知识题） | {summary['explicitKnowledgeRefusalCount']}/{summary['answerableCount']} |",
             f"| 保留部分有据内容 | {summary['partialAnswerCount']} |",
             f"| 原问题直查 Hit@3 | {summary['hitAt3Count']}/{summary['retrievalAnswerableCount']} |",
             f"| 原问题直查 Hit@5 | {summary['hitAt5Count']}/{summary['retrievalAnswerableCount']} |", '',
             '回答质量通过规则：四维度各至少 3/4 分、无标记的无依据断言、拒答符合题型、引用来源身份有效；知识题需有引用，库外题不能附引用。', '',
             '## 逐题', '', '| 题号 | 问题 | 工具规则 | 回答复核 | 事实判定 | 四维评分（正确/有据/引用/边界） | 原因 |',
             '| --- | --- | --- | --- | --- | --- | --- |']
    details = ['# 逐题原始回答、引用及工具轨迹', '']
    csv_rows = []
    for row in sorted(data.get('cases', []), key=lambda item: item['caseId']):
        judgment = row.get('qualityJudgment') or {}
        scores = '/'.join(str(judgment.get(key, '-')) for key in ('correctness', 'groundedness', 'citationSupport', 'boundary'))
        reason = '；'.join(row.get('toolFailures', []) + [judgment.get('reason', '')])
        tool = '通过' if row.get('toolPassed') and (row.get('functionCallingSuccessful')
                         or row.get('generationPath') == 'BACKEND_BOUNDARY') else '失败'
        quality = '通过' if row.get('qualityPassed') else '失败/未完成'
        factual = judgment.get('factualVerdict', '旧版未分项')
        lines.append(f"| {row['caseId']} | {safe(row.get('question', ''))} | {tool} | {quality} | {factual} | {scores} | {safe(reason)} |")
        details += [f"## {row['caseId']}：{row.get('question')}", '', row.get('answer') or '未获得回答', '',
                    '展示引用：', '']
        for ref in row.get('references', []):
            details += [f"- [{ref.get('id')}] {ref.get('title')}：{ref.get('content')}"]
        details += ['', '工具轨迹：', '```json', json.dumps(row.get('toolTrace', []), ensure_ascii=False, indent=2), '```', '',
                    '证据校验诊断：', '```json', json.dumps(row.get('groundingIssues', []), ensure_ascii=False, indent=2), '```', '',
                    '规则失败：' + '；'.join(row.get('toolFailures', [])), '', '模型复核：', '```json',
                    json.dumps(judgment, ensure_ascii=False, indent=2), '```', '']
        csv_rows.append([row['caseId'], row.get('question'), tool, quality, factual, scores, reason])
    lines += ['', '## 调用记录与限制', '',
              f"真实接口请求：{summary['actualProviderCalls']}；向量缓存命中：{summary['embeddingCacheHits']}。",
              '实际接口 usage（不含缓存）：' + json.dumps(summary['providerUsage'], ensure_ascii=False) + '。', '']
    lines += [f"缺少 usage 的实际调用：{summary['callsWithoutUsage']}；其费用未包含在上述 token 合计中。", '']
    lines += ['- ' + value for value in summary['limitations']]
    lines += ['', '原始证据见 results.json；全文与轨迹见 answers.md；汇总见 summary.json。', '']
    (output / 'report.md').write_text('\n'.join(lines), encoding='utf-8')
    (output / 'answers.md').write_text('\n'.join(details), encoding='utf-8')
    with (output / 'scores.csv').open('w', encoding='utf-8-sig', newline='') as stream:
        writer = csv.writer(stream)
        writer.writerow(['题号', '问题', '工具规则', '回答复核', '事实判定', '正确/有据/引用/边界', '原因'])
        writer.writerows(csv_rows)
    return summary


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('results', type=Path)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    data = json.loads(args.results.read_text(encoding='utf-8'))
    print(json.dumps(write_report(data, args.output or args.results.parent), ensure_ascii=True, indent=2))
