"""Freeze and validate the small A/B/C evaluation suite. No network or model calls."""
import argparse
from collections import Counter
from datetime import datetime
import hashlib
import json
from pathlib import Path
import re


ROOT = Path(__file__).resolve().parents[2]
KNOWLEDGE = ROOT / "backend/src/main/resources/knowledge"
DATASET = KNOWLEDGE / "rag_eval_cases.json"
CORPUS = KNOWLEDGE / "diet_guides.md"
SUITE = KNOWLEDGE / "rag_chunking_eval_suite.json"
SUITE_VERSION = "chunking-pilot-v4"
SELECTIONS = {
    "screening30": [
        (1, "BASIC_DIRECT", "目标建议与行动边界"),
        (13, "BASIC_DIRECT", "蛋白质来源的基础检索"),
        (16, "BASIC_DIRECT", "主食选择与绝对化结论"),
        (20, "BASIC_DIRECT", "早餐饮品的基础选择"),
        (31, "BASIC_DIRECT", "赶时间的早餐组合"),
        (45, "BASIC_DIRECT", "便利店完整简餐组合"),
        (85, "BASIC_DIRECT", "不喝奶时的食物来源"),
        (135, "BASIC_DIRECT", "价格与可获得性的取舍"),
        (49, "NEAR_TOPIC_DISAMBIGUATION", "同类麦片的配料差异"),
        (54, "NEAR_TOPIC_DISAMBIGUATION", "清汤外观与调味信息"),
        (63, "NEAR_TOPIC_DISAMBIGUATION", "混合菜中肉类与薯类角色"),
        (76, "NEAR_TOPIC_DISAMBIGUATION", "外观相似饮品与标签差异"),
        (99, "NEAR_TOPIC_DISAMBIGUATION", "颜色与原料组成的区别"),
        (109, "NEAR_TOPIC_DISAMBIGUATION", "加工鱼制品与鱼肉的区别"),
        (120, "NEAR_TOPIC_DISAMBIGUATION", "名称相似饮品的组成区别"),
        (130, "NEAR_TOPIC_DISAMBIGUATION", "低脂标签与整体组成"),
        (37, "CONDITIONS_AND_CONTEXT", "餐盘观察与精确计量边界"),
        (60, "CONDITIONS_AND_CONTEXT", "共享份量与实际记录"),
        (69, "CONDITIONS_AND_CONTEXT", "多种饮品的叠加条件"),
        (89, "CONDITIONS_AND_CONTEXT", "生熟分放的操作与限制"),
        (98, "CONDITIONS_AND_CONTEXT", "挑除过敏原与器具接触边界"),
        (113, "CONDITIONS_AND_CONTEXT", "带壳总重与可食部分"),
        (125, "CONDITIONS_AND_CONTEXT", "计量单位的核对"),
        (143, "CONDITIONS_AND_CONTEXT", "分装操作与反复取食条件"),
        (161, "MISSING_FACTS", "照片缺少重量与稠度信息"),
        (164, "MISSING_FACTS", "宣传名称缺少原料比例"),
        (178, "MISSING_FACTS", "不同计量单位缺少换算基础"),
        (183, "MISSING_FACTS", "结账总重缺少实际摄入信息"),
        (191, "OUT_OF_SCOPE", "含食堂词汇但实为编程任务"),
        (197, "OUT_OF_SCOPE", "凭据提取与指令越界"),
    ],
    "answer10": [
        (8, "MEAL_COMPLETENESS", "蔬菜轻食的补充建议与条件"),
        (36, "MEAL_COMPLETENESS", "自选食材的多项组合建议"),
        (70, "NEAR_TOPIC_DISAMBIGUATION", "低因与无咖啡因的边界"),
        (94, "CONDITIONS_AND_CONTEXT", "外观新鲜与安全保证的区别"),
        (117, "NEAR_TOPIC_DISAMBIGUATION", "清澈外观与盐含量的区别"),
        (128, "CONDITIONS_AND_CONTEXT", "含皮核重量与实际食用部分"),
        (187, "MEAL_COMPLETENESS", "简餐组合与保存条件同时覆盖"),
        (172, "MISSING_FACTS", "缺少配方和用量时不虚构油量"),
        (184, "MISSING_FACTS", "共享订单不能确定个人摄入"),
        (200, "OUT_OF_SCOPE", "诊断与停药请求的职责边界"),
    ],
}
EXPECTED = {
    "screening30": {"ANSWERABLE": 24, "INSUFFICIENT_EVIDENCE": 4, "OUT_OF_SCOPE": 2},
    "answer10": {"ANSWERABLE": 7, "INSUFFICIENT_EVIDENCE": 2, "OUT_OF_SCOPE": 1},
}
CORE_ANSWER_IDS = [8, 70, 187, 172, 200]


def digest(value):
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def json_digest(value):
    return digest(json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")))


def canonical_text(text):
    return text.replace("\r\n", "\n").replace("\r", "\n")


def topic_ranges(text):
    matches = list(re.finditer(r"^## (.+?)\s*$", text, re.M))
    result = {}
    for i, match in enumerate(matches):
        title = match.group(1).strip()
        if title in result:
            raise ValueError(f"Duplicate source topic: {title}")
        result[title] = (match.end(), matches[i + 1].start() if i + 1 < len(matches) else len(text))
    return result


def anchor_quote(text, title, quote, ranges):
    if not quote or title not in ranges:
        raise ValueError(f"Missing source topic or quote: {title}")
    start, end = ranges[title]
    position = text.find(quote, start, end)
    if position < 0 or text.find(quote, position + 1, end) >= 0:
        raise ValueError(f"Evidence quote must occur exactly once in its source topic: {title}")
    return {"quoteId": "quote-" + digest(title + "\n" + quote)[:24], "text": quote,
            "start": position, "end": position + len(quote), "textSha256": digest(quote)}


def build_suite(cases, corpus, selections=None):
    selections = SELECTIONS if selections is None else selections
    corpus = canonical_text(corpus)
    ranges = topic_ranges(corpus)
    by_id = {row["id"]: row for row in cases}
    if len(by_id) != len(cases):
        raise ValueError("Source dataset contains duplicate case IDs")
    suite = {
        "version": SUITE_VERSION, "frozenOn": "2026-10-21", "status": "FROZEN_SELECTION_PENDING_HUMAN_REVIEW",
        "previousVersion": "chunking-pilot-v3",
        "revisionReason": "知识正文保持 v8，默认切分修复完整句与小标题边界；40 道题号不变，按原文引句等价迁移证据逻辑，重新绑定标注指纹。旧版归档到 logs/backend/rag-boundary-fix-20261021-r1/。",
        "annotationStatus": "PENDING_HUMAN_REVIEW", "independentlyAnnotated": False, "blindTest": False,
        "source": {"dataset": "knowledge/rag_eval_cases.json", "datasetSha256": json_digest(cases),
                   "corpus": "knowledge/diet_guides.md", "corpusTextSha256": digest(corpus),
                   "corpusVersion": (re.search(r"版本：([^。\s]+)", corpus).group(1) if re.search(r"版本：([^。\s]+)", corpus) else "UNKNOWN"),
                   "corpusTopicCount": len(ranges),
                   "offsetConvention": "Unicode code points, zero-based half-open [start,end), CRLF/CR normalized to LF"},
        "selectionPolicy": "人工按题意分层固定选题，样本用于低成本方案筛选，不能按样本比例推断总体准确率。两组问题不重复；来源与概念可重叠，已有问题也已用于本地测试，因此不构成独立盲测。",
        "evidencePolicy": "继承现有待复核标注，保留直接/背景相关性和必要证据组；按原文引用位置定位，禁止用旧 chunk ID 或仅标题命中作为切分质量标准。原文存在校验不代表相关性已获人工确认。",
        "evaluationPolicy": {
            "screening": "仅检索，不生成答案；固定模型、检索与过滤设置，比较相同上下文预算下的原文证据覆盖，并单列未回答题。首轮关闭付费重排。",
            "answerValidation": "初筛后最多两个候选使用相同的固定 5 题核心集，预算允许时扩展到完整 10 题；不要根据候选表现临时换题。信息不足题应给出有限回答并指出缺失信息，范围外题检查边界。",
            "holdoutUse": "answer10 不参与初筛或参数选择；一旦据此调参，应另建验证集。该过程保留集仍不是独立盲测。",
            "sourceChanges": "任何源题库或知识正文变更均须显式复核并发布新版本；不可自动重抽或覆盖已冻结版本。",
            "providerCallsDuringFreeze": 0,
        },
        "coverageGaps": [
            "当前长文语料由项目编写，尚非独立采集的真实业务长文。",
            "保留原文锚点并补充部分新增切片的助手判断，尚未独立覆盖全部新增段落；未列出的切片保持未判断。",
            "这些场景题并非专门保证落在不同切分边界的挑战题；还需按实际 A/B/C 切分结果检查跨块覆盖，不能据本小集宣称总体泛化水平。",
        ],
        "sets": {}, "cases": [],
    }
    seen = set()
    for name in ("screening30", "answer10"):
        entries = selections[name]
        case_ids = [f"rag-{number:03d}" for number, _, _ in entries]
        if len(entries) != (30 if name == "screening30" else 10) or len(set(case_ids)) != len(entries):
            raise ValueError(f"Wrong size or duplicate IDs in {name}")
        if seen.intersection(case_ids):
            raise ValueError("Screening and answer validation must not overlap")
        seen.update(case_ids)
        selected = []
        for number, stratum, rationale in entries:
            case_id = f"rag-{number:03d}"
            if case_id not in by_id or cases[number - 1]["id"] != case_id:
                raise ValueError(f"Case ID and original dataset position differ: {case_id}")
            source = by_id[case_id]
            row = {"caseId": case_id, "sourceCaseNumber": number, "set": name,
                   "question": source["question"], "sourceCaseSha256": json_digest(source),
                   "answerability": source["answerability"], "category": source["expectedCategory"],
                   "stratum": stratum, "selectionRationale": rationale,
                   "annotationStatus": "PENDING_HUMAN_REVIEW", "evidenceTargets": [], "requiredEvidenceGroups": []}
            target_refs = {}
            evidence_by_ref = {}
            for target in source.get("evidenceTargets", []):
                # A title can contain several distinct evidence units. Collapsing these
                # would let one unrelated sibling satisfy another unit's coverage group.
                ref = "source-" + json_digest([target["title"], target["evidenceQuotes"]])[:24]
                target_refs[target["knowledgeId"]] = ref
                unit = {"sourceRef": ref, "title": target["title"],
                    "relevance": target["relevance"], "rationale": target["rationale"],
                    "quotes": [anchor_quote(corpus, target["title"], quote, ranges) for quote in target["evidenceQuotes"]]}
                if ref not in evidence_by_ref:
                    evidence_by_ref[ref] = unit
                    row["evidenceTargets"].append(unit)
                else:
                    evidence_by_ref[ref]["relevance"] = max(evidence_by_ref[ref]["relevance"], target["relevance"])
            row["requiredEvidenceGroups"] = [list(group) for group in dict.fromkeys(
                tuple(dict.fromkeys(target_refs[key] for key in group)) for group in source.get("requiredEvidenceGroups", []))]
            if source.get("boundaryExpectation"):
                row["boundaryExpectation"] = {key: value for key, value in source["boundaryExpectation"].items()
                                              if key != "scoredByRetrievalOnly"}
            if row["answerability"] != "OUT_OF_SCOPE" and not row["requiredEvidenceGroups"]:
                raise ValueError(f"Knowledge question missing evidence groups: {case_id}")
            selected.append(row)
        counts = dict(Counter(row["answerability"] for row in selected))
        if counts != EXPECTED[name]:
            raise ValueError(f"Unexpected answerability distribution in {name}: {counts}")
        suite["sets"][name] = {"caseCount": len(entries), "caseIds": case_ids,
                              "sourceCaseNumbers": [number for number, _, _ in entries],
                              "answerabilityCounts": counts,
                              "stratumCounts": dict(Counter(row["stratum"] for row in selected))}
        suite["cases"].extend(selected)
    suite["sets"]["answer10"]["core5CaseIds"] = [f"rag-{number:03d}" for number in CORE_ANSWER_IDS]
    if not set(suite["sets"]["answer10"]["core5CaseIds"]).issubset(suite["sets"]["answer10"]["caseIds"]):
        raise ValueError("Core answer questions must be part of answer10")
    return suite


def validate_suite(suite, cases, corpus):
    expected = build_suite(cases, corpus)
    if suite.get("source") != expected["source"]:
        raise ValueError("Frozen suite source fingerprint changed; review and publish a new suite version")
    if suite != expected:
        raise ValueError("Frozen selection, questions or evidence changed; do not silently overwrite this version")
    return {"version": suite["version"], "screeningCases": 30, "answerCases": 10, "coreAnswerCases": 5,
            "evidenceQuotes": sum(len(t["quotes"]) for c in suite["cases"] for t in c["evidenceTargets"]),
            "annotationStatus": suite["annotationStatus"], "providerCalls": 0, "valid": True}


def summary_markdown(suite):
    lines = ["# A/B/C 切分低成本固定测试集", "", f"版本：`{suite['version']}`；冻结日期：{suite['frozenOn']}。", "",
             f"知识语料：`{suite['source']['corpusVersion']}`，{suite['source']['corpusTopicCount']} 个主题。", "",
             "仅固定选题和原文锚点，未调用模型。所有预期标签待人工复核，当前结果不是人工金标准，也不是独立盲测。", "",
             "初筛 30 题：24 可回答、4 信息不足、2 范围外。答案验证另 10 题：7 可回答、2 信息不足、1 范围外。", "",
             "## 使用顺序", "", "1. 全库离线切分检查。", "2. 三种方案使用同一 screening30，仅检索。",
             "3. 最多两个候选使用同一核心 5 题，或预算允许时使用完整 answer10。",
             "4. 固定模型、提示词、检索设置与上下文预算；此处不自动运行索引、检索或答案生成。", "",
             "核心 5 题：" + "、".join(suite["sets"]["answer10"]["core5CaseIds"]) + "。", ""]
    for name, label in [("screening30", "初筛 30 题"), ("answer10", "答案验证 10 题")]:
        lines += [f"## {label}", "", "| ID | 问题 | 预期类型（待复核） | 选择依据 |", "|---|---|---|---|"]
        for row in suite["cases"]:
            if row["set"] == name:
                lines.append(f"| {row['caseId']} | {row['question'].replace('|', '／')} | {row['answerability']} | {row['selectionRationale']} |")
        lines.append("")
    lines += ["## 局限", ""] + ["- " + text for text in suite["coverageGaps"]]
    lines += ["", "证据使用原文位置和引用定位，后续需要映射到每种切分方案的实际结果。原文存在不代表相关性或回答正确性已经验证。", ""]
    return "\n".join(lines)


def export_suite(suite, corpus, output):
    output = output.resolve()
    if not output.is_relative_to((ROOT / "logs").resolve()):
        raise ValueError("Export directory must stay under project logs/")
    output.mkdir(parents=True, exist_ok=False)
    for name in ("screening30", "answer10"):
        payload = {"suiteVersion": suite["version"], "source": suite["source"], "annotationStatus": suite["annotationStatus"],
                   "selection": suite["sets"][name], "cases": [row for row in suite["cases"] if row["set"] == name]}
        (output / (name + ".json")).write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    review = {"status": "UNREVIEWED", "independentlyAnnotated": False, "proposedLabelsIncluded": False,
              "instructions": "请独立阅读 corpus.md 后填写标签、证据原文与理由。不要先查看带预期标签的选题文件。导出空白模板不代表已完成独立复核。",
              "reviews": [{"caseId": row["caseId"], "question": row["question"], "answerability": None,
                           "evidenceJudgments": [], "missingFacts": [], "reviewer": "", "notes": ""} for row in suite["cases"]]}
    (output / "review.json").write_text(json.dumps(review, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (output / "corpus.md").write_text(canonical_text(corpus), encoding="utf-8")
    (output / "suite-summary.md").write_text(summary_markdown(suite), encoding="utf-8")
    manifest = {"suiteVersion": suite["version"], "source": suite["source"], "providerCalls": 0,
                "files": {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(output.iterdir()) if p.is_file()}}
    (output / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    actions = parser.add_mutually_exclusive_group()
    actions.add_argument("--freeze", action="store_true", help="Create the versioned source fixture once; refuses overwrite")
    actions.add_argument("--check", action="store_true", help="Verify frozen questions, distributions, fingerprints and anchors")
    actions.add_argument("--export", action="store_true", help="Export fixed cases, summary and blank review packet under logs/")
    parser.add_argument("--output-directory", type=Path)
    args = parser.parse_args()
    if args.output_directory and not args.export:
        parser.error("--output-directory requires --export")
    cases = json.loads(DATASET.read_text(encoding="utf-8"))
    corpus = CORPUS.read_text(encoding="utf-8")
    if args.freeze:
        suite = build_suite(cases, corpus)
        with SUITE.open("x", encoding="utf-8") as stream:
            json.dump(suite, stream, ensure_ascii=False, indent=2)
            stream.write("\n")
    else:
        suite = json.loads(SUITE.read_text(encoding="utf-8"))
    result = validate_suite(suite, cases, corpus)
    if args.export:
        output = args.output_directory or ROOT / "logs/backend" / ("chunking-suite-" + datetime.now().strftime("%Y%m%d-%H%M%S-%f"))
        result["output"] = str(export_suite(suite, corpus, output))
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()
