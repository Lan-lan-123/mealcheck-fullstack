"""Generate bounded draft supplements only with --run; never edit the corpus or rebuild vectors."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
from urllib.error import HTTPError
from urllib.parse import urlparse
from urllib.request import Request, urlopen

from prepare_knowledge_longform import CORPUS, MARKER, ROOT, prompt, related_topics


def dump(path, value):
    temporary = path.with_suffix('.tmp.json')
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    temporary.replace(path)


def decode_supplement(raw):
    if not raw.lstrip().startswith('{'):
        text = raw.strip()
        matches = list(re.finditer(r'^(?:\d+[.、]\s*)?\*\*(.+?)\*\*\s*$', text, re.M))
        if not matches:
            raise ValueError('Missing bold Markdown section headings')
        sections = []
        for position, match in enumerate(matches):
            end = matches[position + 1].start() if position + 1 < len(matches) else len(text)
            paragraphs = [paragraph.strip() for paragraph in text[match.end():end].split('\n\n') if paragraph.strip()]
            sections.append({'heading': match.group(1), 'paragraphs': paragraphs})
        return {'sections': sections}
    class Pairs(list):
        pass
    root = json.loads(raw, object_pairs_hook=Pairs)
    if not isinstance(root, Pairs) or len(root) != 1 or root[0][0] != 'sections':
        raise ValueError('Expected one sections field without duplicate root keys')
    entries = root[0][1]
    if not isinstance(entries, list) or isinstance(entries, Pairs):
        raise ValueError('Sections must be an array')
    sections = []
    for entry in entries:
        if not isinstance(entry, Pairs) or len(entry) % 2:
            raise ValueError('Invalid section fields')
        if len(entry) == 2 and {key for key, _ in entry} == {'heading', 'paragraphs'}:
            value = dict(entry)
            sections.append({'heading': value['heading'], 'paragraphs': value['paragraphs']})
            continue
        # Some responses put repeated heading/paragraphs pairs in one object.
        # Preserve each pair as its own section instead of silently keeping only the last.
        for index in range(0, len(entry), 2):
            if entry[index][0] != 'heading' or entry[index + 1][0] != 'paragraphs':
                raise ValueError('Unexpected or ambiguous section keys')
            paragraphs = entry[index + 1][1]
            if not isinstance(paragraphs, list) or isinstance(paragraphs, Pairs):
                raise ValueError('Paragraphs must be an array')
            sections.append({'heading': entry[index][1], 'paragraphs': paragraphs})
    return {'sections': sections}


def accounted_usage(usage, reserved):
    if not isinstance(usage, dict):
        return reserved
    total = usage.get('total_tokens')
    if not isinstance(total, int) or isinstance(total, bool) or total <= 0:
        return reserved
    def nonnegative(value):
        return value if isinstance(value, int) and not isinstance(value, bool) and value >= 0 else 0
    prompt_count = nonnegative(usage.get('prompt_tokens'))
    completion = nonnegative(usage.get('completion_tokens'))
    details = usage.get('completion_tokens_details') or {}
    if not isinstance(details, dict):
        return max(total, prompt_count + completion)
    text_count = details.get('text_tokens')
    reasoning = nonnegative(details.get('reasoning_tokens'))
    candidates = [total, prompt_count + completion]
    if isinstance(text_count, int):
        candidates.append(prompt_count + text_count + reasoning)
    return max(candidates)


def signal_stop(allocation, status, batch, **details):
    if not allocation:
        return
    path = Path(allocation['sharedStopFile'])
    try:
        with path.open('x', encoding='utf-8') as stream:
            json.dump({'status': status, 'batch': str(batch), **details}, stream)
    except FileExistsError:
        pass  # Preserve the first stop event instead of overwriting its evidence.


class Splitter:
    def __init__(self, batch):
        runtime = json.loads((batch / 'runtime.json').read_text(encoding='utf-8'))
        crash = ROOT / 'logs/crash'
        crash.mkdir(parents=True, exist_ok=True)
        self.worker = subprocess.Popen(['java', '-XX:ErrorFile=' + str(crash / 'hs_err_pid%p.log'),
                                       '-cp', runtime['classpath'], 'KnowledgeCorpusTool', '--jsonl'],
                                       cwd=ROOT, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                       stderr=sys.stderr, encoding='utf-8', bufsize=1)

    def split(self, markdown):
        self.worker.stdin.write(json.dumps({'markdown': markdown}, ensure_ascii=False) + '\n')
        self.worker.stdin.flush()
        line = self.worker.stdout.readline()
        if not line:
            raise RuntimeError('Production splitter process exited')
        result = json.loads(line)
        if 'error' in result:
            raise ValueError('Production splitter rejected the article')
        return result

    def close(self):
        self.worker.stdin.close()
        self.worker.wait(timeout=10)


def render(row, supplement):
    sections = supplement.get('sections')
    if not isinstance(sections, list) or not sections:
        raise ValueError('Missing authored sections')
    parts = [f"## {row['title']}", row['originalBody']]
    seen = set()
    for section in sections:
        heading = section.get('heading', '')
        paragraphs = section.get('paragraphs')
        if not isinstance(heading, str) or not heading.strip() or not isinstance(paragraphs, list) or not paragraphs:
            raise ValueError('Missing section heading or paragraphs')
        parts.append('**' + heading.strip() + '**')
        for paragraph in paragraphs:
            if not isinstance(paragraph, str) or len(paragraph.strip()) < 50:
                raise ValueError('Each authored paragraph must contain substantial prose')
            text = paragraph.strip()
            if re.search(r'https?://|^\s*#{1,6}\s|<script|<!--', text, re.M | re.I):
                raise ValueError('New URLs, document headings and executable markup are forbidden')
            normalized = re.sub(r'\s+', '', text)
            if normalized in seen:
                raise ValueError('Repeated paragraph; length cannot be padded by duplication')
            seen.add(normalized)
            parts.append(text)
    return '\n\n'.join(parts) + '\n'


def assess(row, supplement, splitter):
    article = render(row, supplement)
    actual = splitter.split(MARKER + '\n' + article)
    count = len(actual['chunks'])
    issues = []
    if not row['minChunks'] <= count <= row['maxChunks']:
        issues.append(f"chunk_count={count}, required={row['minChunks']}..{row['maxChunks']}")
    if any(not 300 <= chunk['tokenCount'] <= 500 for chunk in actual['chunks']):
        issues.append('chunk_outside_default_token_range')
    if len({chunk['contentHash'] for chunk in actual['chunks']}) != count:
        issues.append('duplicate_chunks')
    return article, actual, issues


def unsupported_medical_terms(row, supplement, supported):
    text = '\n'.join(paragraph for section in supplement.get('sections', []) for paragraph in section.get('paragraphs', []))
    source = row['originalBody'] + '\n' + row['title'] + '\n' + json.dumps(supported, ensure_ascii=False)
    terms = ['胰岛素', '赖氨酸', '蛋氨酸', '肌糖原', '升糖指数', '黏膜', '血糖',
             '抗营养因子', '凝集素', '糖尿病', '胃溃疡', '肠易激', '低血糖',
             '降血压', '降低胆固醇', '促进代谢', '提升专注', '提高专注', '治愈',
             '氨基酸', '葡萄糖', '无热量', '无碳水', '严格限制主食', '固体蛋白',
             '低GI', '高GI', '改善认知', '支持认知', '情绪低落', '胃部黏膜']
    unsupported = []
    for term in terms:
        if term in source:
            continue
        for match in re.finditer(re.escape(term), text):
            prefix = re.split(r'[。！？\n]', text[:match.start()])[-1][-60:]
            suffix = re.split(r'[\n]', text[match.end():], maxsplit=1)[0][:150]
            if term == '糖尿病' and re.search(r'咨询.{0,20}(医生|营养师)|专业医生|专业指导', suffix) and not re.search(r'多吃|少吃|禁食|用药|治愈|治疗|预防|改善|降低', prefix[-12:] + suffix[:35]):
                continue  # A referral to a professional is a scope boundary, not new treatment advice.
            scoped_question = ('询问' in prefix or '如何' in prefix) and re.search(r'超出.{0,30}(范畴|范围|边界)|本手册不提供|不予回答|无法回答', suffix)
            if not scoped_question and not re.search(r'无法|不能|不得|不应|不提供|不预测|不推断|不诊断|不作为|不涉及|不保证|不解释|不回答|不支持|不要引入|不引入|不代表|不意味着', prefix):
                unsupported.append(term)
                break
    return unsupported


def environment():
    # Only select the necessary configured fields; credentials never enter evidence files.
    wanted = {'MEALCHECK_AI_API_KEY', 'MEALCHECK_AI_BASE_URL', 'MEALCHECK_AI_MODEL'}
    values = {}
    for line in (ROOT / '.env').read_text(encoding='utf-8-sig').splitlines():
        key, separator, value = line.partition('=')
        if separator and key.strip() in wanted:
            values[key.strip()] = value.strip().strip('\"\'')
    if not values.get('MEALCHECK_AI_API_KEY'):
        raise ValueError('Configured AI key is unavailable')
    base = values.get('MEALCHECK_AI_BASE_URL', '').rstrip('/')
    if urlparse(base).scheme != 'https' or urlparse(base).hostname != 'dashscope.aliyuncs.com':
        raise ValueError('Only the existing DashScope HTTPS destination is supported')
    model = values.get('MEALCHECK_AI_MODEL', '')
    if model != 'qwen3.7-flash':
        raise ValueError('This generation policy has only been prepared for configured qwen3.7-flash')
    return values, base + '/chat/completions', model


def generate(batch, run, token_budget, max_calls, review_every=5, continue_content_warnings=False):
    batch = batch.resolve()
    if not batch.is_relative_to((ROOT / 'logs').resolve()):
        raise ValueError('Batch must be under logs/')
    plan = json.loads((batch / 'plan.json').read_text(encoding='utf-8'))
    if run and (ROOT / 'logs/backend/knowledge-longform-generation-hold.json').exists():
        raise ValueError('用户已要求暂不继续调用；仅在用户明确恢复后处理暂停记录，当前不发送任何请求')
    allocation_path = batch / 'allocation.json'
    allocation = json.loads(allocation_path.read_text(encoding='utf-8')) if allocation_path.exists() else None
    if run and (batch / 'parallel-plan.json').exists():
        raise ValueError('This parent batch allocated its remaining budget to disjoint children; generate only in the allocated batches')
    if allocation and (token_budget != allocation['tokenBudget'] or max_calls != allocation['maxCalls']):
        raise ValueError('Child budget must exactly match the parent allocation')
    if plan['corpusSha256'] != hashlib.sha256(CORPUS.read_bytes()).hexdigest():
        raise ValueError('Corpus changed after planning; prepare a new plan')
    selected_rows = [row for row in plan['topics'] if not allocation or row['topicId'] in allocation['topicIds']]
    requests = [{'topicId': row['topicId'], 'maximumOutputTokens': row['maxChunks'] * 1000,
                 'messages': prompt(row, related_topics(row, plan['topics']))} for row in selected_rows]
    if not run:
        print(json.dumps({'status': 'DRY_RUN_NO_PROVIDER_CALLS', 'topicCount': len(requests),
                          'tokenBudget': token_budget, 'maximumCalls': max_calls,
                          'minimumChunks': plan['minimumTotalChunks'], 'maximumChunks': plan['maximumTotalChunks']}))
        return
    if not 1 <= token_budget <= 3_000_000 or not 1 <= max_calls <= 240:
        raise ValueError('Maximum authorized preparation ceiling: 3,000,000 tokens and 240 calls')
    config, endpoint, model = environment()
    state_path = batch / 'generation.json'
    if state_path.exists():
        state = json.loads(state_path.read_text(encoding='utf-8'))
        if state['model'] != model or state['corpusSha256'] != plan['corpusSha256']:
            raise ValueError('Resume source/model mismatch')
        if state['status'] in {'PROVIDER_ERROR', 'TRANSPORT_ERROR', 'IN_FLIGHT'}:
            raise ValueError('Prior provider/transport/interrupted call needs review; no automatic retry')
        if token_budget != state['tokenBudget'] or max_calls != state['maxCalls']:
            raise ValueError('Resume cannot change the original admitted budget')
    else:
        state = {'status': 'READY', 'model': model, 'corpusSha256': plan['corpusSha256'],
                 'tokenBudget': token_budget, 'maxCalls': max_calls, 'calls': [], 'drafts': {},
                 'qualityStatus': 'DRAFT_NOT_INDEPENDENTLY_REVIEWED'}
    drafts = batch / 'drafts'
    drafts.mkdir(exist_ok=True)
    request_evidence = batch / 'requests'
    request_evidence.mkdir(exist_ok=True)
    splitter = Splitter(batch)
    generated_this_session = 0
    try:
        for row, specification in zip(selected_rows, requests):
            topic_id = row['topicId']
            if topic_id in state['drafts']:
                continue  # Completed drafts, including failed validation, never silently regenerate.
            if allocation and Path(allocation['sharedStopFile']).exists():
                state['status'] = 'SHARED_STOP'
                dump(state_path, state)
                print('SHARED_STOP; no new provider request sent', flush=True)
                return
            messages = specification['messages']
            maximum = specification['maximumOutputTokens']
            input_allowance = sum(len(message['content'].encode('utf-8')) for message in messages) + 512
            reserved = input_allowance + maximum
            accounted = sum(call['accountedTokens'] for call in state['calls'])
            if len(state['calls']) >= max_calls or accounted + reserved > token_budget:
                state['status'] = 'BUDGET_STOP'
                dump(state_path, state)
                print('BUDGET_STOP; saved drafts retained; no request sent')
                return
            call = {'topicId': topic_id, 'status': 'IN_FLIGHT', 'accountedTokens': reserved,
                    'inputAllowance': input_allowance, 'maximumOutputTokens': maximum,
                    'startedAt': datetime.now(timezone.utc).isoformat()}
            state['calls'].append(call)
            state['status'] = 'IN_FLIGHT'
            dump(state_path, state)
            payload = {'model': model, 'messages': messages, 'enable_thinking': True,
                       'thinking_budget': 1024, 'temperature': 0.2,
                       'max_completion_tokens': maximum}
            attempt = len(state['calls'])
            with (request_evidence / (topic_id + f'-call-{attempt:03d}.json')).open('x', encoding='utf-8') as stream:
                json.dump(payload, stream, ensure_ascii=False, indent=2)
            request = Request(endpoint, data=json.dumps(payload, ensure_ascii=False).encode('utf-8'),
                              headers={'Authorization': 'Bearer ' + config['MEALCHECK_AI_API_KEY'],
                                       'Content-Type': 'application/json'}, method='POST')
            try:
                with urlopen(request, timeout=300) as response:
                    result = json.loads(response.read())
            except HTTPError as error:
                call.update(status='PROVIDER_ERROR', httpStatus=error.code)
                state['status'] = 'PROVIDER_ERROR'
                dump(state_path, state)
                signal_stop(allocation, 'PROVIDER_ERROR', batch, httpStatus=error.code)
                raise RuntimeError(f'Provider HTTP {error.code}; run stopped without retry') from None
            except Exception as error:
                call.update(status='TRANSPORT_ERROR', errorType=type(error).__name__)
                state['status'] = 'TRANSPORT_ERROR'
                dump(state_path, state)
                signal_stop(allocation, 'TRANSPORT_ERROR', batch, errorType=type(error).__name__)
                raise RuntimeError('Transport failure; run stopped without retry') from None
            usage = result.get('usage') or {}
            actual_usage = usage.get('total_tokens')
            if isinstance(actual_usage, int) and actual_usage > 0:
                call['accountedTokens'] = accounted_usage(usage, reserved)
                call['usage'] = usage
                if call['accountedTokens'] != actual_usage:
                    call['accountingNote'] = 'Conservative accounting includes separate text/reasoning details when reported totals disagree.'
            choice = result['choices'][0]
            call.update(status='RETURNED', finishReason=choice.get('finish_reason'))
            raw = choice['message']['content']
            (drafts / (topic_id + f'-call-{attempt:03d}.response.json')).write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
            record = {'topicId': topic_id, 'status': 'VALIDATION_FAILED', 'issues': [],
                      'independentlyReviewed': False, 'model': model,
                      'validationScope': 'STRUCTURAL_AND_SOURCE_TERM_GUARDS_NOT_SEMANTIC_ENTAILMENT',
                      'contentReviewStatus': 'PENDING_EDITORIAL_REVIEW'}
            try:
                if choice.get('finish_reason') != 'stop':
                    raise ValueError('Truncated or unsuccessful generation')
                supplement = decode_supplement(raw)
                record['supplement'] = supplement
                article, actual, issues = assess(row, supplement, splitter)
                medical_terms = unsupported_medical_terms(row, supplement, related_topics(row, plan['topics']))
                if medical_terms:
                    issues.append('unsupported_medical_terms:' + ','.join(medical_terms))
                record.update(supplement=supplement, chunkCount=len(actual['chunks']), issues=issues,
                              estimatedTokens=actual['tokenCount'], status='VALID' if not issues else 'VALIDATION_FAILED')
                (drafts / (topic_id + '.md')).write_text(MARKER + '\n' + article, encoding='utf-8')
            except (ValueError, TypeError, KeyError) as error:
                record['issues'] = [str(error)]
            dump(drafts / (topic_id + '.json'), record)
            state['drafts'][topic_id] = {key: record[key] for key in ['status', 'issues']}
            state['status'] = 'GENERATING'
            dump(state_path, state)
            print(f"{topic_id}: {record['status']}; completed={len(state['drafts'])}/{len(selected_rows)}", flush=True)
            generated_this_session += 1
            if any(issue.startswith('unsupported_medical_terms:') for issue in record['issues']):
                if not continue_content_warnings:
                    state['status'] = 'CONTENT_REVIEW_REQUIRED'
                    dump(state_path, state)
                    print('CONTENT_REVIEW_REQUIRED; no further request sent', flush=True)
                    return
                print('DRAFT_REQUIRES_EDITORIAL_REPAIR; continuing independent topics without publishing', flush=True)
            if review_every and generated_this_session >= review_every:
                state['status'] = 'REVIEW_CHECKPOINT'
                dump(state_path, state)
                print('REVIEW_CHECKPOINT; inspect saved drafts before continuing', flush=True)
                return
        state['status'] = 'GENERATED_REQUIRES_VALIDATION'
        if len(state['drafts']) == 200 and all(draft['status'] == 'VALID' for draft in state['drafts'].values()):
            articles = [(drafts / (row['topicId'] + '.md')).read_text(encoding='utf-8').split('\n', 1)[1]
                        for row in plan['topics']]
            candidate = MARKER + '\n# MealCheck 饮食知识库\n\n版本：v6-longform-200-draft。200 个独立主题；AI 扩写草稿待编辑复核，非个体营养处方。\n\n' + '\n'.join(articles)
            (batch / 'candidate-diet-guides.md').write_text(candidate, encoding='utf-8')
            state['status'] = 'CANDIDATE_READY_REQUIRES_EDITORIAL_REVIEW'
        dump(state_path, state)
    finally:
        splitter.close()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('batch', type=Path)
    parser.add_argument('--run', action='store_true', help='Explicitly allow paid generation')
    parser.add_argument('--token-budget', type=int, default=3_000_000)
    parser.add_argument('--max-calls', type=int, default=200)
    parser.add_argument('--review-every', type=int, default=5, help='Stop after this many new drafts for local review; 0 disables checkpoints')
    parser.add_argument('--continue-on-content-warnings', action='store_true', help='Collect flagged drafts for local repair while generating other independent topics; provider errors still stop globally')
    args = parser.parse_args()
    generate(args.batch, args.run, args.token_budget, args.max_calls, args.review_every, args.continue_on_content_warnings)
