"""Audit same-corpus chunk continuity and boundary diagnostics without provider calls.

Repeated text is handled as ordered candidate occurrences, never by assuming the
first match is the true offset or by unioning all possible occurrences as facts.
"""
import argparse
from collections import Counter, defaultdict
from datetime import datetime
import hashlib
import json
from pathlib import Path
import re


ROOT = Path(__file__).resolve().parents[2]
HEADINGS = re.compile(r'(?m)^#{2,6}\s+(.+?)\s*$')
STANDALONE_HEADING = re.compile(r'(?:#{1,6}\s+[^\r\n]+|\*\*[^*\r\n]+\*\*|__[^_\r\n]+__)')
SENTENCE_END = re.compile(r'[。！？]+[”’」』）)\]]*|[.!?]+[”\'"\)\]]*(?=\s|$)')
PARAGRAPH_BREAK = re.compile(r'\r?\n[ \t]*\r?\n')
TOKENS = re.compile(
    r'[\u3400-\u4dbf\u4e00-\u9fff\U00020000-\U0002fa1f\u3040-\u30ff\u1100-\u11ff\u3130-\u318f\uac00-\ud7af]'
    r'|[^\W_]+(?:[-_\'’][^\W_]+)*|[^\s]', re.UNICODE)


def compact(text):
    return ''.join(character for character in text if not character.isspace())


def identity(title, content):
    def normalized(value):
        return re.sub(r'[ \t\n\r\f\v]+', ' ', value.strip(''.join(chr(i) for i in range(33))))
    return hashlib.sha256((normalized(title) + '\n\n' + normalized(content)).encode('utf-8')).hexdigest()


def sections(markdown):
    matches = list(HEADINGS.finditer(markdown))
    result = {}
    for index, match in enumerate(matches):
        title = match.group(1).strip()
        if title in result:
            raise ValueError('Repeated topic title: ' + title)
        end = matches[index + 1].start() if index + 1 < len(matches) else len(markdown)
        result[title] = markdown[match.end():end].strip()
    if not result:
        raise ValueError('Corpus needs explicit source topics')
    return result


def occurrences(haystack, needle):
    if not needle:
        return []
    result, start = [], 0
    while True:
        start = haystack.find(needle, start)
        if start < 0:
            return result
        result.append((start, start + len(needle)))
        start += 1


def ordered_coverage(candidate_intervals, source_length):
    """Prove existence of an ordered, gap-free assignment of actual chunk occurrences.

    Each state maps the last chosen start to the farthest covered prefix reached
    by a valid assignment. A later contained/duplicate chunk cannot erase prior
    coverage. Ambiguous matches stay alternatives, rather than simultaneous facts.
    """
    states = {0: 0}
    for candidates in candidate_intervals:
        previous = iter(sorted(states.items()))
        current = next(previous, None)
        best_end = -1
        reachable = {}
        for start, end in sorted(candidates):
            while current is not None and current[0] <= start:
                best_end = max(best_end, current[1])
                current = next(previous, None)
            if best_end >= start:
                reachable[start] = max(reachable.get(start, -1), best_end, end)
        states = reachable
        if not states:
            return False
    return bool(states) and max(states.values()) == source_length


def is_sentence_start(body, position):
    if position == 0:
        return True
    prefix = body[:position]
    if PARAGRAPH_BREAK.search(prefix[max(0, len(prefix) - 10):]):
        # The break must end at the current word (apart from whitespace).
        if re.search(r'\r?\n[ \t]*\r?\n\s*$', prefix):
            return True
    stripped = prefix.rstrip()
    if not stripped:
        return True
    if STANDALONE_HEADING.fullmatch(stripped.splitlines()[-1]) and prefix[-1].isspace():
        return True
    without_closer = stripped.rstrip('”’」』）)]\'"')
    if without_closer.endswith(('。', '！', '？', '；', ';')):
        return True
    if without_closer.endswith(('.', '!', '?')) and prefix[-1].isspace():
        return True
    return False


def oversized_sentence_at(body, position, max_tokens):
    boundaries = {0, len(body)}
    boundaries.update(match.end() for match in SENTENCE_END.finditer(body))
    boundaries.update(match.end() for match in PARAGRAPH_BREAK.finditer(body))
    left = max(point for point in boundaries if point <= position)
    right = min((point for point in boundaries if point > position), default=len(body))
    return len(TOKENS.findall(body[left:right])) > max_tokens


def is_sentence_end(body, position):
    if position == len(body):
        return True
    suffix = body[position:]
    if re.match(r'\s*\r?\n[ \t]*\r?\n', suffix):
        return True
    ending = body[:position].rstrip().rstrip('”’」』）)]\'"')
    if ending.endswith(('。', '！', '？', '；', ';')):
        return True
    return ending.endswith(('.', '!', '?')) and bool(suffix) and suffix[0].isspace()


def audit_manifest(manifest, bodies, corpus_sha):
    errors, diagnostics, topic_reports = [], [], []
    if manifest.get('corpusSha256') != corpus_sha:
        raise ValueError('Manifest corpus fingerprint differs from source bytes')
    chunks = manifest['chunks']
    if manifest.get('chunkCount') != len(chunks):
        errors.append({'kind': 'DECLARED_CHUNK_COUNT_MISMATCH'})
    by_topic = defaultdict(list)
    for chunk in chunks:
        by_topic[chunk['title']].append(chunk)
    if set(by_topic) != set(bodies):
        errors.append({'kind': 'TOPIC_SET_MISMATCH', 'missing': sorted(set(bodies) - set(by_topic)),
                       'unexpected': sorted(set(by_topic) - set(bodies))})
    if manifest.get('topicCount') != len(by_topic):
        errors.append({'kind': 'DECLARED_TOPIC_COUNT_MISMATCH'})
    for title, topic_chunks in by_topic.items():
        if title not in bodies:
            continue
        body = bodies[title]
        raw_positions = [index for index, character in enumerate(body) if not character.isspace()]
        source = compact(body)
        intervals, ambiguous = [], 0
        for chunk in topic_chunks:
            cid = chunk['contentHash']
            base = {'knowledgeId': cid, 'title': title, 'chunkIndex': chunk['chunkIndex']}
            if identity(title, chunk['content']) != cid:
                errors.append({**base, 'kind': 'CONTENT_IDENTITY_MISMATCH'})
            if not manifest['minTokens'] <= chunk['tokenCount'] <= manifest['maxTokens']:
                errors.append({**base, 'kind': 'TOKEN_BUDGET_VIOLATION', 'tokenCount': chunk['tokenCount']})
            candidates = occurrences(source, compact(chunk['content']))
            intervals.append(candidates)
            if not candidates:
                errors.append({**base, 'kind': 'NOT_CONTIGUOUS_IN_SAME_TOPIC'})
                continue
            ambiguous += len(candidates) > 1
            raw_starts = [raw_positions[start] for start, _ in candidates]
            clean = [is_sentence_start(body, start) for start in raw_starts]
            if not all(clean):
                definite = not any(clean)
                unavoidable = definite and all(oversized_sentence_at(body, start, manifest['maxTokens']) for start in raw_starts)
                diagnostics.append({**base,
                    'kind': 'OVERSIZED_SENTENCE_FALLBACK' if unavoidable else 'MID_SENTENCE_START' if definite else 'AMBIGUOUS_SENTENCE_START',
                    'fixable': definite and not unavoidable, 'candidateOccurrenceCount': len(candidates),
                    'excerpt': chunk['content'][:120]})
            raw_ends = [raw_positions[end - 1] + 1 for _, end in candidates]
            clean_ends = [is_sentence_end(body, end) for end in raw_ends]
            if not all(clean_ends):
                definite = not any(clean_ends)
                unavoidable = definite and all(oversized_sentence_at(body, max(0, end - 1), manifest['maxTokens']) for end in raw_ends)
                diagnostics.append({**base,
                    'kind': 'OVERSIZED_SENTENCE_END_FALLBACK' if unavoidable else 'MID_SENTENCE_END' if definite else 'AMBIGUOUS_SENTENCE_END',
                    'fixable': definite and not unavoidable, 'candidateOccurrenceCount': len(candidates),
                    'excerpt': chunk['content'][-120:]})
            last_paragraph = re.split(r'\r?\n\s*\r?\n', chunk['content'].strip())[-1]
            if STANDALONE_HEADING.fullmatch(last_paragraph):
                diagnostics.append({**base, 'kind': 'TRAILING_HEADING', 'fixable': True, 'excerpt': last_paragraph})
            if chunk['content'].startswith('*') and not chunk['content'].startswith('**'):
                diagnostics.append({**base, 'kind': 'BROKEN_MARKDOWN_START', 'fixable': True,
                                    'excerpt': chunk['content'][:80]})
        covered = ordered_coverage(intervals, len(source))
        if not covered:
            errors.append({'title': title, 'kind': 'NO_ORDERED_GAP_FREE_SOURCE_COVERAGE'})
        topic_reports.append({'title': title, 'sourceNonWhitespaceCharacters': len(source),
                              'chunkCount': len(topic_chunks), 'ambiguousChunkLocations': ambiguous,
                              'orderedGapFreeCoverageExists': covered})
    return {'chunkCount': len(chunks), 'topicCount': len(by_topic), 'errors': errors,
            'diagnosticCounts': dict(Counter(item['kind'] for item in diagnostics)),
            'fixableDiagnosticCount': sum(item['fixable'] for item in diagnostics),
            'diagnostics': diagnostics, 'topics': topic_reports,
            'continuityAndCoveragePassed': not errors}


def compare(before, after, corpus_bytes):
    sha = hashlib.sha256(corpus_bytes).hexdigest()
    bodies = sections(corpus_bytes.decode('utf-8'))
    result = {'corpusSha256': sha,
              'method': 'Whitespace-insensitive contiguous matches with ordered, gap-free occurrence-assignment proof per topic.',
              'offsetLimitation': 'Repeated text can have several valid offsets. Existence of a coverage assignment is not a claim of unique precise offsets. Whitespace formatting is excluded from coverage.',
              'sentenceBoundaryLimitation': 'Sentence markers are punctuation/paragraph heuristics; this is not a semantic self-containedness or English abbreviation parser.',
              'factReviewLimitation': 'Boundary and source continuity checks do not validate factual correctness or relevance.',
              'before': audit_manifest(before, bodies, sha), 'after': audit_manifest(after, bodies, sha)}
    result['passed'] = result['after']['continuityAndCoveragePassed'] and result['after']['fixableDiagnosticCount'] == 0
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--before', type=Path, required=True)
    parser.add_argument('--after', type=Path, required=True)
    parser.add_argument('--corpus', type=Path, required=True)
    parser.add_argument('--output-directory', type=Path, default=ROOT / 'logs/backend' /
                        ('chunk-boundary-audit-' + datetime.now().strftime('%Y%m%d-%H%M%S-%f')))
    args = parser.parse_args()
    output = args.output_directory.resolve()
    if not output.is_relative_to((ROOT / 'logs').resolve()):
        raise ValueError('Output must stay under project logs/')
    result = compare(json.loads(args.before.read_text(encoding='utf-8')),
                     json.loads(args.after.read_text(encoding='utf-8')), args.corpus.read_bytes())
    output.mkdir(parents=True, exist_ok=False)
    (output / 'audit.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'passed': result['passed'], 'beforeChunks': result['before']['chunkCount'],
                      'afterChunks': result['after']['chunkCount'], 'afterErrors': len(result['after']['errors']),
                      'beforeDiagnostics': result['before']['diagnosticCounts'],
                      'afterDiagnostics': result['after']['diagnosticCounts']}, ensure_ascii=False))
    raise SystemExit(0 if result['passed'] else 1)


if __name__ == '__main__':
    main()
