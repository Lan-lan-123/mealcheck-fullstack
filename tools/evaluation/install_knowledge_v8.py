"""Install a complete, reviewed offline candidate; never touch a running index."""
import argparse
import json
from pathlib import Path

from assemble_knowledge_v8 import BATCH, MANUSCRIPTS, ROOT, VERSION, digest, validate_counts, validate_plan, write_json

FILES = {'diet_guides.md': 'candidate-diet-guides.md',
         'diet_guides_metadata.json': 'candidate-diet-guides-metadata.json',
         'rag_eval_cases.json': 'candidate-rag-eval-cases.json',
         'rag_eval_splits.json': 'candidate-rag-eval-splits.json'}


def verify_editorial(candidate, review):
    expected = {f'knowledge-{index:03}' for index in range(1, 201)}
    if review.get('corpusSha256') != digest(candidate / 'candidate-diet-guides.md'):
        raise ValueError('Editorial review describes a different corpus')
    if review.get('decision') != 'APPROVED_WITH_INDEPENDENT_REVIEW_PENDING':
        raise ValueError('Final assistant editorial review is required')
    rows = review.get('topics', [])
    if len(rows) != 200 or {row['topicId'] for row in rows} != expected:
        raise ValueError('Final editorial review must cover all 200 topics')
    for row in rows:
        if (row.get('decision') != 'ACCEPT' or not row.get('reviewedKnowledgeUnits')
                or row.get('manuscriptSha256') != digest(MANUSCRIPTS / (row['topicId'] + '.md'))):
            raise ValueError('Incomplete or stale topic editorial review: ' + row['topicId'])
    if review.get('independentlyReviewed') is not False:
        raise ValueError('Assistant editorial review cannot assert independent human validation')


def install(candidate, review_file):
    candidate = candidate.resolve()
    if not candidate.is_relative_to((ROOT / 'logs').resolve()):
        raise ValueError('Candidate must be an evidence batch under logs/')
    resources = ROOT / 'backend/src/main/resources/knowledge'
    # All preconditions are evaluated before the first resource is written.
    for name in FILES:
        if (resources / name).read_bytes() != (BATCH / ('before-' + name)).read_bytes():
            raise ValueError('Resource changed since frozen baseline; reconcile explicitly: ' + name)
    plan = json.loads((BATCH / 'plan.json').read_text(encoding='utf-8'))['topics']
    validate_plan(plan)
    manifest = json.loads((candidate / 'candidate-chunks.json').read_text(encoding='utf-8'))
    metadata = json.loads((candidate / FILES['diet_guides_metadata.json']).read_text(encoding='utf-8'))
    if (metadata['version'] != VERSION or metadata['lengthGoalDeviationCount'] != 0
            or metadata['lengthPlanStatus'] != 'SATISFIED'
            or manifest['corpusSha256'] != digest(candidate / FILES['diet_guides.md'])
            or metadata['corpusSha256'] != manifest['corpusSha256']):
        raise ValueError('Candidate metadata does not match a strict complete corpus')
    counts = validate_counts(plan, manifest['chunks'])
    topics = metadata.get('topics', [])
    if (metadata.get('topicCount') != 200 or metadata.get('chunkCount') != len(manifest['chunks'])
            or len(topics) != 200 or {t['topicId'] for t in topics} != {t['topicId'] for t in plan}
            or metadata.get('lengthPolicy') != 'CONTENT_QUALITY_AND_ORIGINAL_LENGTH_BOTH_REQUIRED'):
        raise ValueError('Candidate topic metadata is incomplete or uses a relaxed policy')
    for topic in topics:
        if topic.get('actualChunks') != counts[topic['title']] or topic.get('lengthPlanSatisfied') is not True:
            raise ValueError('Candidate topic counts do not match production chunks: ' + topic['topicId'])
    review = json.loads(review_file.read_text(encoding='utf-8'))
    verify_editorial(candidate, review)
    authors = json.loads((candidate / 'author-reviews.json').read_text(encoding='utf-8'))
    if set(authors) != {t['topicId'] for t in plan}:
        raise ValueError('Author review coverage is incomplete')
    for topic_id, author in authors.items():
        if digest(MANUSCRIPTS / (topic_id + '.md')) != author['manuscriptSha256']:
            raise ValueError('Manuscript changed after assembly: ' + topic_id)
    for name, staged in FILES.items():
        (resources / name).write_bytes((candidate / staged).read_bytes())
    write_json(candidate / 'installation.json', {'version': VERSION, 'corpusSha256': manifest['corpusSha256'],
        'topics': 200, 'chunks': len(manifest['chunks']), 'databaseReindexed': False, 'providerCalls': 0,
        'independentlyReviewed': False, 'editorialReview': str(review_file.resolve())})
    print(json.dumps({'installedVersion': VERSION, 'topics': 200, 'chunks': len(manifest['chunks']),
                      'databaseReindexed': False, 'providerCalls': 0}))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('candidate', type=Path)
    parser.add_argument('--editorial-review', type=Path, required=True)
    args = parser.parse_args()
    install(args.candidate, args.editorial_review)
