"""Export an unfilled review packet without revealing proposed per-question labels."""
import argparse
from datetime import datetime
import hashlib
import json
from pathlib import Path
import re


ROOT = Path(__file__).resolve().parents[2]


def normalized(value):
    return re.sub(r"[ \t\n\r\f\v]+", " ", value.strip(''.join(chr(index) for index in range(33))))


def export(output, chunk_manifest=None):
    output = output.resolve()
    if not output.is_relative_to((ROOT / "logs").resolve()):
        raise ValueError("Review output must stay under the project's logs directory")
    dataset = ROOT / "backend/src/main/resources/knowledge/rag_eval_cases.json"
    corpus = dataset.with_name("diet_guides.md")
    rows = json.loads(dataset.read_text(encoding="utf-8"))
    text = corpus.read_text(encoding="utf-8")
    if not text.lstrip().startswith("<!-- mealcheck:independent-topics -->"):
        raise ValueError("Review export expects independent Markdown topics")
    sources = []
    topic_count = len(re.findall(r'^## ', text, re.M))
    if chunk_manifest:
        actual = json.loads(chunk_manifest.read_text(encoding='utf-8'))
        if actual['corpusSha256'] != hashlib.sha256(corpus.read_bytes()).hexdigest():
            raise ValueError('Chunk manifest and current corpus differ')
        for chunk in actual['chunks']:
            identity = hashlib.sha256((normalized(chunk['title']) + '\n\n' + normalized(chunk['content'])).encode()).hexdigest()
            if identity != chunk['contentHash']:
                raise ValueError('Chunk manifest content identity mismatch')
            sources.append({'knowledgeId': identity, 'title': chunk['title'], 'content': chunk['content'],
                            'chunkKey': chunk['chunkKey'], 'chunkIndex': chunk['chunkIndex']})
    else:
        for section in re.split(r"^## ", text, flags=re.M)[1:]:
            title, _, content = section.partition("\n")
            title, content = title.strip(), content.strip()
            identity = hashlib.sha256((normalized(title) + "\n\n" + normalized(content)).encode()).hexdigest()
            sources.append({"knowledgeId": identity, "title": title, "content": content})
    identities = {source["knowledgeId"] for source in sources}
    if not rows or len({row["id"] for row in rows}) != len(rows):
        raise ValueError("Review questions must be nonempty and unique")
    if any(target["knowledgeId"] not in identities for row in rows for target in row["evidenceTargets"]):
        raise ValueError("Corpus and bound annotations differ; for multi-chunk topics supply --chunk-manifest exported by KnowledgeCorpusTool")
    packet = {
        "status": "UNREVIEWED", "independentlyAnnotated": False,
        "instructions": "请由未参与出题或调试的审核人仅根据知识正文独立标注，不查看现有预期标签。为每题填写 ANSWERABLE / INSUFFICIENT_EVIDENCE / OUT_OF_SCOPE；evidenceJudgments 中填写 knowledgeId、relevance(0无关/1背景/2直接证据)、evidenceQuotes 和理由。缺失信息或不可支持的结论另填 missingFacts。此文件未完成前不构成人工金标准。",
        "knowledge": sources,
        "reviews": [{"caseId": row["id"], "question": row["question"], "answerability": None,
                     "evidenceJudgments": [], "missingFacts": [], "reviewer": "", "notes": ""} for row in rows],
    }
    manifest = {"datasetSha256": hashlib.sha256(dataset.read_bytes()).hexdigest(),
                "corpusSha256": hashlib.sha256(corpus.read_bytes()).hexdigest(),
                "caseCount": len(rows), "topicCount": topic_count, "knowledgeCount": len(sources), "status": "UNREVIEWED",
                "independentlyAnnotated": False, "proposedLabelsIncluded": False,
                "limitation": "An unfilled packet does not establish reviewer independence or blind generalization."}
    output.mkdir(parents=True, exist_ok=False)
    for name, value in [("review.json", packet), ("manifest.json", manifest)]:
        with (output / name).open("x", encoding="utf-8") as stream:
            json.dump(value, stream, ensure_ascii=False, indent=2)
            stream.write("\n")
    print(json.dumps({"caseCount": len(rows), "knowledgeCount": len(sources), "review": str(output / "review.json")}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-directory", type=Path, default=ROOT / "logs/backend" /
                        ("rag-independent-review-" + datetime.now().strftime("%Y%m%d-%H%M%S-%f")))
    parser.add_argument('--chunk-manifest', type=Path, help='Actual production splitter manifest, required for long topics')
    args = parser.parse_args()
    export(args.output_directory, args.chunk_manifest)
