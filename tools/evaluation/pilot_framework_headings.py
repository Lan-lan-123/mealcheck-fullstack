"""Offline prototype: B heading/body binding; no production changes or API calls."""
import argparse
from datetime import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess

from audit_chunk_boundaries import audit_manifest, identity, sections, STANDALONE_HEADING
from run_chunking_smoke import ROOT, analyze, sha


def detach_heading(text):
    # Only detach standalone paragraph headings, matching the diagnosed failure.
    paragraphs = re.split(r'\r?\n\s*\r?\n', text.strip())
    detached = []
    while paragraphs and STANDALONE_HEADING.fullmatch(paragraphs[-1]):
        detached.insert(0, paragraphs.pop())
    return '\n\n'.join(paragraphs), '\n\n'.join(detached)


def transfer(chunks):
    staged, moved = [], 0
    carry = ''
    for index, chunk in enumerate(chunks):
        text = (carry + '\n\n' if carry else '') + chunk['content']
        carry = ''
        if index + 1 < len(chunks) and chunks[index + 1]['title'] == chunk['title']:
            body, heading = detach_heading(text)
            if heading:
                moved += 1
                text, carry = body, heading
        if text.strip():
            staged.append({'title': chunk['title'], 'content': text.strip()})
    return staged, moved


def run(batch, output):
    output = output.resolve()
    if not output.is_relative_to((ROOT / 'logs').resolve()):
        raise ValueError('Output must stay under project logs/')
    frozen = json.loads((batch / 'results.json').read_text(encoding='utf-8'))
    for filename, expected in {**frozen['compiledSources'], **frozen['inputSha256']}.items():
        if sha(Path(filename)) != expected:
            raise ValueError('Baseline input changed: ' + filename)
    original = json.loads((batch / 'B-chunks.json').read_text(encoding='utf-8'))
    corpus_bytes = (ROOT / 'backend/src/main/resources/knowledge/diet_guides.md').read_bytes()
    corpus = corpus_bytes.decode('utf-8')
    suite = json.loads((ROOT / 'backend/src/main/resources/knowledge/rag_chunking_eval_suite.json').read_text(encoding='utf-8'))
    staged, moved = transfer(original['chunks'])
    output.mkdir(parents=True, exist_ok=False)
    # Reuse the exact frozen Java runtime and token estimator; no Maven/network.
    jars = [name for name in frozen['dependencySha256'] if name.endswith('.jar')]
    for filename in jars:
        if sha(Path(filename)) != frozen['dependencySha256'][filename]:
            raise ValueError('Frozen dependency changed')
    classpath = os.pathsep.join([str(batch.resolve() / 'classes'), str(ROOT / 'backend/target/classes'), *jars])
    messages = ''.join(json.dumps({'markdown': row['content']}, ensure_ascii=False) + '\n' for row in staged)
    with (output / 'java-stderr.log').open('w', encoding='utf-8') as errors:
        response = subprocess.run(['java', '-XX:ErrorFile=' + str(ROOT / 'logs/crash/hs_err_pid%p.log'),
            '-cp', classpath, 'KnowledgeCorpusTool', '--jsonl'], cwd=ROOT, input=messages,
            stdout=subprocess.PIPE, stderr=errors, text=True, encoding='utf-8', check=True, timeout=120)
    answers = [json.loads(line) for line in response.stdout.splitlines()]
    if len(answers) != len(staged) or any('error' in answer for answer in answers):
        raise ValueError('Incomplete Java verification')
    chunks, fallback_count = [], 0
    for row, answer in zip(staged, answers):
        if answer['tokenCount'] <= original['maxTokens']:
            windows = [{'content': row['content'], 'tokenCount': answer['tokenCount']}]
        else:
            fallback_count += 1
            # Use A only for newly oversized heading-bound blocks, not all B blocks.
            windows = answer['chunks']
        for window in windows:
            index = len(chunks)
            chunks.append({'chunkIndex': index, 'chunkKey': 'heading-pilot-' + str(index),
                'title': row['title'], 'content': window['content'], 'tokenCount': window['tokenCount'],
                'contentHash': identity(row['title'], window['content'])})
    enhanced = {**original, 'chunkCount': len(chunks), 'chunks': chunks,
                'experimentalRevision': 'B-heading-binding-with-A-oversize-fallback-pilot-v1'}
    audits = {name: audit_manifest(manifest, sections(corpus), hashlib.sha256(corpus_bytes).hexdigest())
              for name, manifest in [('B', original), ('Bplus', enhanced)]}
    metrics = {name: analyze(manifest, corpus, suite) for name, manifest in [('B', original), ('Bplus', enhanced)]}
    result = {'mode': 'OFFLINE_HEADING_PROTOTYPE', 'providerCalls': 0, 'modelTokens': 0,
              'baseline': str(batch.resolve()), 'movedBoundaryCount': moved, 'oversizeFallbackCount': fallback_count,
              'prototypeSha256': sha(Path(__file__)), 'audits': audits, 'metrics': metrics}
    for filename, value in [('Bplus-chunks.json', enhanced), ('results.json', result)]:
        (output / filename).write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    lines = ['# B 标题绑定增强：离线小规模验证', '',
             '离线原型，保持生产代码、默认策略和模型配置不变。先在 B 的相邻同主题块之间转移孤立尾标题；新增超长块才调用 A 的递归边界处理。',
             f'共移动 {moved} 处边界；{fallback_count} 个块触发 A 超长回退；外部调用 0 次。', '',
             '| 方案 | 块数 | <300 | >500 | 孤立尾标题 | 未证实覆盖字符 | 单块完整证据单元 | 正文字符冗余 |',
             '|---|---:|---:|---:|---:|---:|---:|---:|']
    for name, row in metrics.items():
        ev = row['screeningEvidence']
        lines.append(f"| {name} | {row['chunkCount']} | {row['estimatedTokenSize']['belowMin']} | {row['estimatedTokenSize']['aboveMax']} | {audits[name]['diagnosticCounts'].get('TRAILING_HEADING', 0)} | {row['unprovenSourceCharacters']} | {ev['targetsWithAllQuotesInOneChunk']}/{ev['directTargetCount']} | {row['bodyCharacterRedundancyRatio']:.1%} |")
    lines += ['', '只说明结构、长度和证据碎片化，不能推断真实检索或回答优胜。短于 300 是 B 的允许行为；长度为现有 Java 正则估算。',
              'qwen3.7-text-rerank 为排序模型，不能生成新增 Bplus 文本向量。未调用已耗尽额度的 embedding，未把新模型向量与旧缓存混用。', '']
    (output / 'report.md').write_text('\n'.join(lines), encoding='utf-8')
    print('\n'.join(lines))
    print('Report: ' + str(output / 'report.md'))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--baseline', type=Path, required=True)
    parser.add_argument('--output-directory', type=Path, default=ROOT / 'logs/backend' / ('B-heading-pilot-' + datetime.now().strftime('%Y%m%d-%H%M%S-%f')))
    args = parser.parse_args()
    run(args.baseline, args.output_directory)
