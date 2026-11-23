"""Audit saved RAG evidence locally. No credentials, network or model calls."""
import argparse
import csv
import json
from pathlib import Path


def audit(data):
    rows = data.get("cases", [])
    expected = data.get("datasetCaseCount", data.get("datasetTotalCaseCount", 200))
    ids = [row.get("caseId") for row in rows]
    complete = [row for row in rows if row.get("status") == "COMPLETE"]
    checks = []
    for row in rows:
        flags = []
        if row.get("status") != "COMPLETE":
            flags.append("execution_error")
        else:
            if not row.get("usedRemoteChat"):
                flags.append("no_successful_chat_call")
            if not str(row.get("answer") or "").strip():
                flags.append("empty_answer")
            refs = row.get("references") or []
            if refs and row.get("referencesExistInKnowledge") is not True:
                flags.append("citation_source_mismatch")
            if not row.get("expectNoAnswer"):
                if not refs:
                    flags.append("answerable_without_citation")
                elif not row.get("citationRelevanceProxy"):
                    flags.append("citation_title_proxy_miss")
                if row.get("possibleRefusal"):
                    flags.append("possible_false_refusal")
            elif refs:
                flags.append("out_of_scope_citation")
        checks.append({"caseId": row.get("caseId"), "question": row.get("question", ""),
                       "flags": flags, "humanReview": "PENDING"})
    live = data.get("executionMode") == "LIVE_PROVIDER" and data.get("qualityMeasurement") is True
    selected = data.get("selectedCaseIds", list(range(1, expected + 1)))
    coverage = (len(rows) == expected and len(set(ids)) == expected
                and len(selected) == expected and len(set(selected)) == expected
                and set(ids) == set(selected))
    answerable = [row for row in complete if not row.get("expectNoAnswer")]
    return {"executionMode": data.get("executionMode", "UNKNOWN"),
            "realModelEvidence": live,
            "coverageComplete": coverage,
            "selectedCaseIds": selected, "sampleSeed": data.get("sampleSeed"),
            "expectedCases": expected, "recordedCases": len(rows), "completedCases": len(complete),
            "executionErrorCount": len(rows) - len(complete),
            "successfulChatCases": sum(bool(row.get("usedRemoteChat")) for row in complete),
            "answerableCompletedCases": len(answerable),
            "possibleFalseRefusalCount": sum(bool(row.get("possibleRefusal")) for row in answerable),
            "flaggedCases": sum(bool(row["flags"]) for row in checks),
            "readyForHumanReview": live and coverage and len(complete) == expected and not data.get("providerStopped"),
            "limitations": ["离线固定回答不能衡量语义检索或回答质量。", "标题相关性和拒答关键词仅用于筛选疑点，不能判定引用蕴含或实际误拒答。",
                            "引用正文匹配仅证明来源存在；空引用不会被判为引用通过。", "最终回答及其引用是否支持结论需要逐题人工复核。"],
            "cases": checks}


def write_report(data, output):
    result = audit(data)
    output.mkdir(parents=True, exist_ok=True)
    (output / "audit.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    with (output / "review.csv").open("w", encoding="utf-8-sig", newline="") as target:
        writer = csv.writer(target)
        writer.writerow(["caseId", "question", "flags", "humanReview", "citationSupportsAnswer", "actuallyRefused", "notes"])
        for row in result["cases"]:
            writer.writerow([row["caseId"], row["question"], ";".join(row["flags"]), "PENDING", "", "", ""])
    retrieval = data.get("retrievalBenchmark") or {}
    lines = ["# RAG 评测证据审查", "", f"执行模式：{result['executionMode']}",
             f"真实模型证据：{'是' if result['realModelEvidence'] else '否；仅本地链路验证'}", "",
             f"覆盖：{result['recordedCases']} / {result['expectedCases']}；执行完成：{result['completedCases']}；执行错误：{result['executionErrorCount']}。",
             f"疑点题数：{result['flaggedCases']}；有答案题中的疑似拒答：{result['possibleFalseRefusalCount']} / {result['answerableCompletedCases']}。", ""]
    if result["realModelEvidence"]:
        lines += [f"检索 Hit@3：{retrieval.get('hitAt3', '未记录')}；MRR：{retrieval.get('mrr', '未记录')}。", ""]
    lines += ["以上疑点数量不等于真实错误率。人工复核模板见 review.csv，原始回答与引用见 results.json。", ""]
    lines += ["- " + limit for limit in result["limitations"]]
    (output / "report.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("results", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    source = json.loads(args.results.read_text(encoding="utf-8"))
    summary = write_report(source, args.output or args.results.parent)
    print(json.dumps({key: value for key, value in summary.items() if key not in ("cases", "limitations")}, ensure_ascii=False))
