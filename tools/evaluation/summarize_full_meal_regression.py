"""Offline reporting only: no provider calls and no alteration of provisional labels."""
import argparse
import base64
from collections import Counter, defaultdict
import hashlib
import html
import importlib.util
import io
import json
import math
from pathlib import Path
import re
from statistics import mean, median


def percentile(values, fraction):
    return sorted(values)[math.ceil(len(values) * fraction) - 1] if values else None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run-dir', type=Path, required=True)
    args = parser.parse_args()
    directory = args.run_dir.resolve()
    root = Path(__file__).resolve().parents[2]
    result = json.loads((directory / 'results.json').read_text(encoding='utf-8-sig'))
    spec = importlib.util.spec_from_file_location('vision_evaluation', root / 'tools/vision_evaluation.py')
    evaluation = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(evaluation)
    dataset, fingerprint = evaluation.load_dataset(root / 'tools/datasets/synthetic-meals-100/dataset.json', allow_synthetic=True)
    snapshot = json.loads((directory / 'dataset-snapshot.json').read_text(encoding='utf-8'))
    if dataset != snapshot:
        raise ValueError('Dataset changed since the run')
    calls = result['calls']
    rows = result['cases']
    classified = []
    call_groups = defaultdict(list)
    for call in calls:
        call_groups[call['caseId']].append(call)
    reviews_path = directory / 'advice-review.json'
    reviews = json.loads(reviews_path.read_text(encoding='utf-8')) if reviews_path.exists() else {}
    for row in rows:
        expected = row['expected']
        prediction = {'recognition': row.get('recognition', {}), 'durationMs': row.get('durationMs')}
        if row['status'] != 'COMPLETED':
            prediction['error'] = row.get('errorType', row['status'])
        metric = evaluation.score(expected, prediction)
        metric['scene'] = row['scene']
        metric['status'] = row['status']
        metric['decisionMatchesLabel'] = row.get('foodDecisionMatchesLabel', False)
        actual_categories = {f['category'] for f in row.get('scoredRecognition', {}).get('foods', [])}
        expected_categories = {f['category'] for f in expected['foods']}
        metric['categorySetMatchesLabel'] = actual_categories == expected_categories
        actual_score = row.get('evaluation', {}).get('score')
        label_score = row.get('labelBasedEvaluation', {}).get('score')
        metric['actualScore'] = actual_score
        metric['labelBasedScore'] = label_score
        metric['scoreDelta'] = actual_score - label_score if actual_score is not None and label_score is not None else None
        metric['adviceFromRealAi'] = row.get('adviceFromRealAi', False)
        metric['inputTokens'] = sum(c.get('inputTokens', 0) for c in call_groups[row['id']])
        metric['outputTokens'] = sum(c.get('outputTokens', 0) for c in call_groups[row['id']])
        metric['totalTokens'] = metric['inputTokens'] + metric['outputTokens']
        metric['unknownUsageCalls'] = sum(c.get('httpAttempted', False) and not c.get('usageKnown', False) for c in call_groups[row['id']])
        # Screening is deliberately a review queue, not an automatic quality pass.
        advice = row.get('advice', '')
        metric['adviceScreeningFlags'] = []
        if advice:
            if re.search(r'\d+\s*(?:千卡|大卡|kcal|克蛋白质)', advice, re.I):
                metric['adviceScreeningFlags'].append('quantified-nutrition-needs-review')
            if re.search(r'停药|治愈|保证.*(?:减|瘦)|断食|催吐', advice):
                metric['adviceScreeningFlags'].append('health-wording-needs-review')
            if len(advice) > 500:
                metric['adviceScreeningFlags'].append('long-answer')
        metric['adviceReview'] = reviews.get(row['id'])
        classified.append(metric)
    known_calls = [c for c in calls if c.get('usageKnown')]
    input_tokens = sum(c.get('inputTokens', 0) for c in known_calls)
    output_tokens = sum(c.get('outputTokens', 0) for c in known_calls)
    if input_tokens != result['knownInputTokens'] or output_tokens != result['knownOutputTokens']:
        raise ValueError('Provider-call token ledger does not reconcile')
    scene_metrics = {}
    for scene in sorted({r['scene'] for r in classified}):
        group = [r for r in classified if r['scene'] == scene]
        scene_metrics[scene] = evaluation.summarize(group)
    food_rows = [r for r in classified if next(x for x in rows if x['id'] == r['id'])['expected']['foodImage']]
    score_deltas = [r['scoreDelta'] for r in food_rows if r['scoreDelta'] is not None]
    operation_metrics = {}
    for operation in sorted({c['operation'] for c in calls}):
        group = [c for c in calls if c['operation'] == operation]
        operation_metrics[operation] = {
            'calls': len(group), 'httpAttempts': sum(bool(c.get('httpAttempted')) for c in group),
            'transportSuccesses': sum(bool(c.get('transportSuccess')) for c in group),
            'inputTokens': sum(c.get('inputTokens', 0) for c in group),
            'outputTokens': sum(c.get('outputTokens', 0) for c in group),
            'p95DurationMs': percentile([c['durationMs'] for c in group if 'durationMs' in c], .95),
        }
    source_manifest = json.loads((directory / 'source-manifest.json').read_text(encoding='utf-8-sig'))
    changed_files = [item['Path'] for item in source_manifest if hashlib.sha256(Path(item['Path']).read_bytes()).hexdigest().lower() != item['SHA256'].lower()]
    summary = {
        'state': result['state'], 'scope': result['scope'], 'datasetVersion': dataset['version'],
        'datasetFingerprint': fingerprint, 'qualityScope': 'synthetic-regression-only',
        'independentlyAnnotated': False, 'model': result['model'], 'scoringVersion': result['scoringVersion'],
        'statusCounts': dict(Counter(r['status'] for r in rows)),
        'recognitionAgainstProposedLabels': evaluation.summarize(classified),
        'sceneMetrics': scene_metrics, 'operationMetrics': operation_metrics,
        'foodCategorySetMatches': sum(r['categorySetMatchesLabel'] for r in food_rows),
        'foodSamples': len(food_rows), 'scoreMatchesProposedLabels': sum(d == 0 for d in score_deltas),
        'scoreComparedSamples': len(score_deltas), 'meanAbsoluteScoreDelta': mean(abs(d) for d in score_deltas) if score_deltas else None,
        'realAiAdviceCompleted': sum(r['adviceFromRealAi'] for r in classified),
        'adviceReviewed': sum(bool(r['adviceReview']) for r in classified),
        'adviceReviewIssues': [r['id'] for r in classified if r['adviceReview'] and r['adviceReview'].get('issues')],
        'inputTokens': input_tokens, 'outputTokens': output_tokens, 'totalTokens': input_tokens + output_tokens,
        'tokenBudget': result['tokenBudget'], 'unknownUsageCalls': result['unknownUsageCalls'],
        'unknownUsageAllowance': result['unknownUsageAllowance'], 'inFlightReservedTokens': result['inFlightReservedTokens'],
        'sourceChangedDuringRun': changed_files,
        'durationMedianMs': median([r['durationMs'] for r in rows if 'durationMs' in r]),
        'durationP95Ms': percentile([r['durationMs'] for r in rows if 'durationMs' in r], .95),
        'cases': classified,
    }
    (directory / 'summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding='utf-8')
    (directory / 'review-input.json').write_text(json.dumps([
        {'id': r['id'], 'foods': r.get('scoredRecognition', {}).get('foods', []),
         'evaluation': r.get('evaluation'), 'advice': r.get('advice'), 'status': r['status']}
        for r in rows if r.get('advice')], ensure_ascii=False, indent=2), encoding='utf-8')
    lines = ['# 100 张合成图片全流程回归', '',
             f"状态：{result['state']}；模型：{result['model']}；思考关闭；目标 balanced；评分版本 {result['scoringVersion']}。", '',
             '本报告使用合成图片与未经独立人工确认的初始标签；名称匹配、分类及分数比较均为回归代理指标，不代表真实照片准确率。', '',
             f"状态统计：{summary['statusCounts']}。AI 建议完成 {summary['realAiAdviceCompleted']}；建议文本复核 {summary['adviceReviewed']}。", '',
             f"实际已知 token：输入 {input_tokens:,}，输出 {output_tokens:,}，总计 {input_tokens + output_tokens:,}。未知用量调用 {result['unknownUsageCalls']}，保守预留 {result['unknownUsageAllowance']:,}。", '',
             '| 图片 | 状态 | 食物判断匹配 | 名称匹配/标签数 | 多识别 | 分数/标签分数 | token |',
             '|---|---|---|---|---|---|---|']
    for r in classified:
        lines.append(f"| {r['id']} | {r['status']} | {r['decisionMatchesLabel']} | {r['matched']}/{r['expected']} | {len(r['extra'])} | {r['actualScore']}/{r['labelBasedScore']} | {r['totalTokens']} |")
    (directory / 'report.md').write_text('\n'.join(lines) + '\n', encoding='utf-8')
    make_gallery(directory, root, rows, classified)
    print(json.dumps({key: value for key, value in summary.items() if key not in {'cases', 'sceneMetrics'}}, ensure_ascii=False, indent=2))


def make_gallery(directory, root, rows, metrics):
    from PIL import Image
    cards = []
    for row, metric in zip(rows, metrics):
        source = root / 'tools/datasets/synthetic-meals-100' / row['expected']['image']
        with Image.open(source) as image:
            image.thumbnail((300, 300))
            stream = io.BytesIO()
            image.convert('RGB').save(stream, format='JPEG', quality=75)
        thumbnail = base64.b64encode(stream.getvalue()).decode()
        label = '、'.join(f['name'] for f in row['expected']['foods']) or '非食物'
        detected = '、'.join(f"{f['name']}({f['category']}, {f.get('confidence')})" for f in row.get('recognition', {}).get('foods', [])) or '无'
        details = {'指标': metric, '评分': row.get('evaluation'), '建议复核': metric.get('adviceReview')}
        issue = row['status'] != 'COMPLETED' or not metric['decisionMatchesLabel'] or bool(metric.get('adviceReview', {}) and metric['adviceReview'].get('issues')) or metric.get('scoreDelta') not in (None, 0)
        cards.append(f'''<article data-issue="{str(issue).lower()}"><img src="data:image/jpeg;base64,{thumbnail}" alt="{html.escape(row['id'])}"><div><h2>{row['id']} · {html.escape(row['scene'])} · {row['status']}</h2><p>初始标签：{html.escape(label)}</p><p>识别：{html.escape(detected)}</p><p>分数：{row.get('evaluation', {}).get('score', '—')} · token：{metric['totalTokens']}</p><p class="advice">{html.escape(row.get('advice', '非食物或执行未完成，无建议。'))}</p><details><summary>完整指标与复核</summary><pre>{html.escape(json.dumps(details, ensure_ascii=False, indent=2))}</pre></details></div></article>''')
    document = '''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><title>MealCheck · 100 张全流程回归</title><style>body{font:16px/1.6 system-ui,sans-serif;background:#f4f6f5;color:#20342e;max-width:1150px;margin:30px auto;padding:0 20px}[hidden]{display:none!important}article{background:white;border-radius:12px;padding:20px;margin:20px 0;display:grid;grid-template-columns:230px 1fr;gap:24px}img{width:100%;border-radius:8px}h2{font-size:18px;margin-top:0}pre{white-space:pre-wrap;font-size:13px}.advice{padding:12px;background:#edf6f1;border-radius:8px}button{padding:10px 20px;cursor:pointer}@media(max-width:700px){article{grid-template-columns:1fr}img{max-width:300px}}</style><h1>MealCheck · 100 张全流程回归</h1><p>合成样本；初始标签未经独立人工标注。逐图展示真实模型识别、规则评分、AI 建议和 token。文本复核为本助手复核，不是独立人工验收。</p><button onclick="const show=this.dataset.active!=='true';this.dataset.active=show;document.querySelectorAll('article').forEach(x=>x.hidden=show&&x.dataset.issue!=='true');this.textContent=show?'显示全部':'仅显示需关注项'">仅显示需关注项</button>'''
    (directory / 'gallery.html').write_text(document + ''.join(cards) + '</html>', encoding='utf-8')


if __name__ == '__main__':
    main()
