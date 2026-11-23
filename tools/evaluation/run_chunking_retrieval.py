"""Opt-in paid A/B/C dense retrieval pilot. No chat, reranker or database writes."""
import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime
import hashlib
import json
from pathlib import Path
import threading
import time
import urllib.request
from urllib.parse import urlparse

import numpy as np
import freeze_chunking_suite as fixed
from run_chunking_smoke import analyze, sha
from audit_chunk_boundaries import TOKENS

ROOT = fixed.ROOT


def embedding_text(chunk):
    return chunk['title'].strip() + '\n\n' + chunk['content'].strip()


def dump(path, value):
    pending = path.with_suffix('.tmp.json')
    pending.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    pending.replace(path)


def config():
    values = {}
    for line in (ROOT / '.env').read_text(encoding='utf-8-sig').splitlines():
        if line.lstrip().startswith('#'):
            continue
        key, sep, value = line.partition('=')
        if sep:
            values[key.strip()] = value.strip().strip('\"\'')
    base = values.get('MEALCHECK_KNOWLEDGE_EMBEDDING_BASE_URL') or values.get('MEALCHECK_AI_BASE_URL', '')
    key = values.get('MEALCHECK_KNOWLEDGE_EMBEDDING_API_KEY')
    if not key and not values.get('MEALCHECK_KNOWLEDGE_EMBEDDING_BASE_URL'):
        key = values.get('MEALCHECK_AI_API_KEY')
    endpoint = base.rstrip('/') + '/embeddings'
    if urlparse(endpoint).hostname != 'dashscope.aliyuncs.com' or urlparse(endpoint).scheme != 'https' or not key:
        raise ValueError('Expected configured Aliyun HTTPS embedding endpoint and credential')
    model = values.get('MEALCHECK_KNOWLEDGE_EMBEDDING_MODEL') or 'qwen3.7-text-embedding-flash'
    dimension = int(values.get('MEALCHECK_KNOWLEDGE_EMBEDDING_DIM') or 512)
    return endpoint, key, model, dimension


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        raise ValueError('Embedding endpoint redirect refused')


def cache_key(endpoint, model, dimension, text):
    body = json.dumps({'model': model, 'input': text, 'dimensions': dimension}, ensure_ascii=False, separators=(',', ':'))
    return hashlib.sha256((endpoint + '\n' + body).encode()).hexdigest()


def validate_vector(payload, dimension):
    vector = np.asarray(payload, dtype=np.float32)
    if vector.shape != (dimension,) or not np.isfinite(vector).all() or np.linalg.norm(vector) == 0:
        raise ValueError('Invalid embedding vector')
    return vector


def embed(texts, output, settings, live, plan_only=False):
    endpoint, key, model, dimension = settings
    shared = ROOT / 'logs/backend/chunking-embedding-cache'
    shared.mkdir(parents=True, exist_ok=True)
    caches = [shared, *sorted((ROOT / 'logs/backend').glob('*/embedding-cache'))]
    vectors, missing = {}, []
    for text in dict.fromkeys(texts):
        identity = cache_key(endpoint, model, dimension, text)
        cached = next((folder / (identity + '.json') for folder in caches if (folder / (identity + '.json')).is_file()), None)
        if cached:
            payload = json.loads(cached.read_text(encoding='utf-8'))
            if payload.get('model') != model:
                raise ValueError('Cache model mismatch')
            vectors[text] = validate_vector(payload['data'][0]['embedding'], dimension)
        else:
            missing.append(text)
    ledger = {'state': 'PLANNED', 'model': model, 'dimension': dimension, 'endpoint': endpoint,
              'uniqueInputs': len(vectors) + len(missing), 'cacheHits': len(vectors), 'missingInputs': len(missing),
              'estimatedUncachedInputTokens': sum(len(TOKENS.findall(text)) for text in missing),
              'plannedRequests': (len(missing) + 9) // 10,
              'batchSize': 10, 'workers': 4, 'tokenLimit': 2000000, 'actualInputTokens': 0, 'calls': []}
    dump(output / 'ledger.json', ledger)
    print(f"inputs={ledger['uniqueInputs']} cached={len(vectors)} missing={len(missing)}", flush=True)
    if plan_only:
        return vectors, ledger
    if missing and not live:
        raise ValueError('Uncached vectors require explicit --live')
    lock, stopped = threading.Lock(), threading.Event()

    def batch_call(batch):
        if stopped.is_set():
            return
        with lock:
            if ledger['actualInputTokens'] >= ledger['tokenLimit'] - 20000 or len(ledger['calls']) >= 600:
                stopped.set()
                raise ValueError('Embedding run budget reached')
            call = {'sequence': len(ledger['calls']), 'state': 'STARTED', 'inputCount': len(batch),
                    'inputKeys': [cache_key(endpoint, model, dimension, text) for text in batch]}
            ledger['calls'].append(call)
            ledger['state'] = 'RUNNING'
            dump(output / 'ledger.json', ledger)
        body = json.dumps({'model': model, 'input': batch, 'dimensions': dimension}, ensure_ascii=False).encode()
        request = urllib.request.Request(endpoint, data=body, headers={'Authorization': 'Bearer ' + key, 'Content-Type': 'application/json'})
        started = time.perf_counter()
        try:
            opener = urllib.request.build_opener(NoRedirect())
            with opener.open(request, timeout=90) as response:
                payload = json.loads(response.read())
            entries = sorted(payload['data'], key=lambda row: row['index'])
            if payload.get('model') != model or [e['index'] for e in entries] != list(range(len(batch))):
                raise ValueError('Model or batch index mismatch')
            validated = [validate_vector(entry['embedding'], dimension) for entry in entries]
            usage = payload.get('usage', {})
            tokens = usage.get('prompt_tokens', usage.get('total_tokens'))
            if not isinstance(tokens, int) or tokens < 0:
                raise ValueError('Missing valid reported usage')
            # A batch reports aggregate usage. Never assign it to each individual vector.
            with lock:
                dump(output / f"response-{call['sequence']:04d}.json", payload)
                for text, entry, vector in zip(batch, entries, validated):
                    identity = cache_key(endpoint, model, dimension, text)
                    if not (shared / (identity + '.json')).exists():
                        dump(shared / (identity + '.json'), {'model': model, 'data': [entry], 'usage': None,
                             'batchResponse': str(output / f"response-{call['sequence']:04d}.json")})
                    vectors[text] = vector
                ledger['actualInputTokens'] += tokens
                call.update(state='COMPLETED', usage=usage, seconds=round(time.perf_counter() - started, 3))
                dump(output / 'ledger.json', ledger)
                if sum(c['state'] == 'COMPLETED' for c in ledger['calls']) % 25 == 0:
                    print(f"completed={sum(c['state'] == 'COMPLETED' for c in ledger['calls'])} tokens={ledger['actualInputTokens']}", flush=True)
        except Exception as error:
            stopped.set()
            with lock:
                call.update(state='FAILED', errorType=type(error).__name__)
                ledger['state'] = 'FAILED'
                dump(output / 'ledger.json', ledger)
            raise RuntimeError('Embedding stopped; see ledger. No automatic retry.') from None

    with ThreadPoolExecutor(max_workers=4) as pool:
        list(pool.map(batch_call, [missing[i:i+10] for i in range(0, len(missing), 10)]))
    if stopped.is_set():
        raise RuntimeError('Incomplete embedding run')
    ledger['state'] = 'COMPLETED'
    dump(output / 'ledger.json', ledger)
    return vectors, ledger


def score_case(chunks, corpus, suite, case):
    manifest = {'splitterStrategy': chunks['splitterStrategy'], 'minTokens': 300, 'maxTokens': 500, 'chunks': chunks['chunks']}
    rows = analyze(manifest, corpus, {'cases': [case]})['screeningEvidence']['targets']
    covered = {row['sourceRef'] for row in rows if row['quotesCoveredAcrossAllChunks'] == row['quoteCount']}
    groups = case['requiredEvidenceGroups']
    hits = sum(bool(set(group) & covered) for group in groups)
    return {'groupHits': hits, 'groupCount': len(groups), 'groupRecall': hits / len(groups) if groups else None,
            'allGroupsCovered': hits == len(groups) if groups else None,
            'quoteHits': sum(r['quotesCoveredAcrossAllChunks'] for r in rows), 'quoteCount': sum(r['quoteCount'] for r in rows)}


def run(batch, output, live, plan_only=False):
    if live and plan_only:
        raise ValueError('--plan and --live are mutually exclusive')
    if not output.resolve().is_relative_to((ROOT / 'logs').resolve()):
        raise ValueError('Output must stay under logs/')
    before = json.loads((batch / 'results.json').read_text(encoding='utf-8'))
    if not before['offlineIntegrityPassed']:
        raise ValueError('Offline integrity required first')
    for path, fingerprint in {**before['compiledSources'], **before['inputSha256']}.items():
        if sha(Path(path)) != fingerprint:
            raise ValueError('Offline inputs changed; rerun offline comparison first')
    corpus = fixed.CORPUS.read_text(encoding='utf-8')
    suite = json.loads(fixed.SUITE.read_text(encoding='utf-8'))
    fixed.validate_suite(suite, json.loads(fixed.DATASET.read_text(encoding='utf-8')), corpus)
    manifests = {name: json.loads((batch / f'{name}-chunks.json').read_text(encoding='utf-8')) for name in 'ABC'}
    cases = [case for case in suite['cases'] if case['set'] == 'screening30']
    texts = [embedding_text(chunk) for manifest in manifests.values() for chunk in manifest['chunks']]
    # Match production PgVectorKnowledgeService/KnowledgeMaintenanceService input.
    output.mkdir(parents=True, exist_ok=False)
    dump(output / 'inputs.json', {'suite': suite, 'manifests': manifests, 'sourceBatch': str(batch),
                                  'runnerSha256': sha(Path(__file__)), 'corpusTextSha256': fixed.digest(corpus)})
    vectors, ledger = embed(texts + [case['question'] for case in cases], output, config(), live, plan_only)
    if plan_only:
        lines = ['# A/B/C 真实检索调用计划（尚未调用）', '',
            f"目的地：{ledger['endpoint']}；模型：{ledger['model']}；维度：{ledger['dimension']}。",
            '数据范围：全库 200 个主题的 A/B/C 切块标题和正文，以及 screening30 的 30 道问题。answer10 不发送。',
            f"去重后 {ledger['uniqueInputs']} 条输入；匹配缓存 {ledger['cacheHits']} 条；需发送 {ledger['missingInputs']} 条。",
            f"预计请求 {ledger['plannedRequests']} 次（每批至多 10 条），未缓存输入的正则估算约 {ledger['estimatedUncachedInputTokens']:,} tokens；真实计费用量以接口返回为准。",
            '并发最多 4，实际输入 token 上限 2,000,000，最多 600 次调用；发生错误即停止，不自动重试。',
            '仅计算 embedding 和内存精确余弦检索，不调用聊天模型、付费重排或写入线上索引。',
            '输出：Top5 与估算上下文预算 2000 两种条件的必要证据组召回，以及逐题检索记录。',
            '标注待人工复核，本轮可比较纯向量检索表现，不能直接判定上线端到端效果。',
            '当前仅完成离线计划，无外部请求、无本轮 API 费用。', '']
        (output / 'plan.md').write_text('\n'.join(lines), encoding='utf-8')
        print('Plan: ' + str(output / 'plan.md'))
        return
    result = {'mode': 'LIVE_DENSE_COSINE_PILOT', 'suiteVersion': suite['version'], 'model': ledger['model'],
              'dimension': ledger['dimension'], 'actualInputTokens': ledger['actualInputTokens'], 'providerCalls': len(ledger['calls']),
              'cacheHits': ledger['cacheHits'], 'contextEstimatedTokenBudget': 2000, 'strategies': {},
              'limitations': ['Exact dense cosine in memory; not production hybrid retrieval/postprocessing.',
                  'No chat, reranker or refusal classification. OUT_OF_SCOPE excluded from recall denominators.',
                  'PENDING_HUMAN_REVIEW annotations; 30-question pilot, not independent gold or general accuracy.',
                  'Context budget uses regex estimates including titles; not exact tokenizer counts.']}
    for name, manifest in manifests.items():
        chunks = manifest['chunks']
        matrix = np.stack([vectors[embedding_text(c)] for c in chunks])
        matrix /= np.linalg.norm(matrix, axis=1, keepdims=True)
        rows = []
        for case in cases:
            query = vectors[case['question']]
            similarities = matrix @ (query / np.linalg.norm(query))
            ranking = np.argsort(-similarities, kind='stable').tolist()
            modes = {}
            for mode in ['top5', 'budget2000']:
                selected, cost = [], 0
                for index in ranking:
                    chunk = chunks[index]
                    tokens = chunk['tokenCount'] + len(TOKENS.findall(chunk['title']))
                    if mode == 'top5' and len(selected) == 5 or mode == 'budget2000' and cost + tokens > 2000:
                        break
                    selected.append(index)
                    cost += tokens
                scoring = score_case({**manifest, 'chunks': [chunks[i] for i in selected]}, corpus, suite, case)
                modes[mode] = {**scoring, 'estimatedContextTokens': cost, 'chunkCount': len(selected),
                              'retrieved': [{'chunkIndex': chunks[i]['chunkIndex'], 'title': chunks[i]['title'], 'score': float(similarities[i])} for i in selected]}
            rows.append({'caseId': case['caseId'], 'answerability': case['answerability'], 'question': case['question'], 'modes': modes})
        summary = {}
        for mode in ['top5', 'budget2000']:
            eligible = [row['modes'][mode] for row in rows if row['answerability'] != 'OUT_OF_SCOPE']
            summary[mode] = {'evidenceCaseCount': len(eligible), 'meanGroupRecall': float(np.mean([r['groupRecall'] for r in eligible])),
                             'allGroupsCoveredCases': sum(r['allGroupsCovered'] for r in eligible),
                             'meanContextTokens': float(np.mean([r['estimatedContextTokens'] for r in eligible]))}
        result['strategies'][name] = {'summary': summary, 'cases': rows}
        print(name, summary, flush=True)
    dump(output / 'results.json', result)
    lines = ['# A/B/C 真实向量检索初筛', '', f"模型 {ledger['model']} / {ledger['dimension']} 维；固定 30 题、全库 200 主题。",
             '仅向量检索，不生成答案。28 道有知识证据题参与召回统计，另 2 道范围外题仅保留检索记录。', '',
             '| 方案 | Top5 必要证据组平均召回 | Top5 全组命中题数 | 2000 预算平均召回 | 预算内全组命中题数 |', '|---|---:|---:|---:|---:|']
    for name, row in result['strategies'].items():
        a, b = row['summary']['top5'], row['summary']['budget2000']
        lines.append(f"| {name} | {a['meanGroupRecall']:.1%} | {a['allGroupsCoveredCases']}/28 | {b['meanGroupRecall']:.1%} | {b['allGroupsCoveredCases']}/28 |")
    lines += ['', f"本轮接口调用 {len(ledger['calls'])} 次，缓存命中 {ledger['cacheHits']} 条，实际输入 token：{ledger['actualInputTokens']}。", '',
              '必要证据组按原文引用位置验证，组内来源为替代关系，组间需同时满足。预算按相关度顺序取完整块前缀，不截断、不跳过超预算块。',
              '标注仍待人工复核；本轮为内存精确余弦检索，未包含线上混合检索、过滤、重排、拒答或答案生成。不能据此宣称上线综合效果最优。', '']
    (output / 'report.md').write_text('\n'.join(lines), encoding='utf-8')
    print('Report: ' + str(output / 'report.md'), flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--offline-batch', type=Path, required=True)
    parser.add_argument('--output-directory', type=Path, default=ROOT / 'logs/backend' / ('chunking-retrieval-' + datetime.now().strftime('%Y%m%d-%H%M%S-%f')))
    parser.add_argument('--live', action='store_true')
    parser.add_argument('--plan', action='store_true', help='Compute cache coverage and call plan without network calls')
    args = parser.parse_args()
    run(args.offline_batch, args.output_directory, args.live, args.plan)
