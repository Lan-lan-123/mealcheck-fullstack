"""Compare current A/B/C production splitters offline; never call model providers."""
import argparse
from bisect import bisect_left
from collections import Counter, defaultdict
from datetime import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import statistics
import subprocess
import time
import xml.etree.ElementTree as ET

import freeze_chunking_suite as fixed
from audit_chunk_boundaries import compact, occurrences


ROOT = fixed.ROOT
STRATEGIES = {"A": "recursive-markdown", "B": "langchain4j-recursive", "C": "structural-markdown"}
SOURCES = ["backend/src/main/java/com/example/mealcheck/config/AppProperties.java",
           "backend/src/main/java/com/example/mealcheck/service/MarkdownKnowledgeChunker.java",
           "backend/src/main/java/com/example/mealcheck/service/KnowledgeCategoryClassifier.java",
           "tools/evaluation/KnowledgeCorpusTool.java"]


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def merge(intervals):
    result = []
    for start, end in sorted(intervals):
        if result and start <= result[-1][1]:
            result[-1] = (result[-1][0], max(end, result[-1][1]))
        else:
            result.append((start, end))
    return result


def covers(intervals, start, end):
    return any(left <= start and right >= end for left, right in merge(intervals))


def topic_sources(corpus):
    headings = list(re.finditer(r"(?m)^#{2,6}\s+(.+?)\s*$", corpus))
    result = {}
    for index, heading in enumerate(headings):
        title = heading.group(1).strip()
        if title in result:
            raise ValueError("Duplicate source topic: " + title)
        start = heading.end()
        end = headings[index + 1].start() if index + 1 < len(headings) else len(corpus)
        body = corpus[start:end]
        result[title] = {"start": start, "body": body, "compact": compact(body),
                         "positions": [i for i, char in enumerate(body) if not char.isspace()]}
    return result


def analyze(manifest, corpus, suite):
    topics = topic_sources(corpus)
    intervals, heading_intervals, mapped_chunks = defaultdict(list), defaultdict(list), defaultdict(list)
    anomalies, ambiguous = [], []
    structural = manifest["splitterStrategy"] == STRATEGIES["C"]
    for chunk in manifest["chunks"]:
        title = chunk["title"]
        root = title if title in topics else next((key for key in sorted(topics, key=len, reverse=True)
                                                  if structural and title.startswith(key + " / ")), None)
        if root is None:
            anomalies.append({"kind": "UNKNOWN_TOPIC", "chunkIndex": chunk["chunkIndex"], "title": title})
            continue
        topic = topics[root]
        matches = occurrences(topic["compact"], compact(chunk["content"]))
        if len(matches) != 1:
            entry = {"chunkIndex": chunk["chunkIndex"], "title": title, "candidateCount": len(matches)}
            (ambiguous if matches else anomalies).append({"kind": "AMBIGUOUS_LOCATION" if matches else "BODY_NOT_CONTIGUOUS_IN_TOPIC", **entry})
            # Do not union alternative offsets and claim they all belong to this chunk.
            continue
        start, end = matches[0]
        intervals[root].append((start, end))
        mapped_chunks[root].append((start, end, chunk["chunkIndex"]))
        if structural and title != root:
            label = title[len(root) + 3:]
            for match in re.finditer(r"(?m)^\*\*([^\r\n]+?)\*\*\s*$", topic["body"]):
                if match.group(1).strip() == label:
                    heading_intervals[root].append((bisect_left(topic["positions"], match.start()),
                                                    bisect_left(topic["positions"], match.end())))
    source_characters = sum(len(topic["compact"]) for topic in topics.values())
    body_covered = sum(sum(end - start for start, end in merge(intervals[title])) for title in topics)
    covered = sum(sum(end - start for start, end in merge(intervals[title] + heading_intervals[title])) for title in topics)
    topic_rows = [{"title": title, "sourceCharacters": len(topic["compact"]),
                   "coveredCharactersIncludingHeadingMetadata": sum(end - start for start, end in merge(intervals[title] + heading_intervals[title]))}
                  for title, topic in topics.items()]
    sizes = sorted(chunk["tokenCount"] for chunk in manifest["chunks"])
    evidence_rows = []
    for case in suite["cases"]:
        if case["set"] != "screening30":
            continue  # Keep answer10 out of the screening experiment.
        for target in case["evidenceTargets"]:
            if target["relevance"] != 2:
                continue
            topic = topics[target["title"]]
            bounds = [(bisect_left(topic["positions"], quote["start"] - topic["start"]),
                       bisect_left(topic["positions"], quote["end"] - topic["start"])) for quote in target["quotes"]]
            candidates = mapped_chunks[target["title"]]
            evidence_rows.append({"caseId": case["caseId"], "sourceRef": target["sourceRef"], "title": target["title"],
                "quoteCount": len(bounds),
                "quotesCoveredAcrossAllChunks": sum(covers(intervals[target["title"]], start, end) for start, end in bounds),
                "quotesIntactInOneChunk": sum(any(left <= start and right >= end for left, right, _ in candidates) for start, end in bounds),
                "allQuotesInOneChunk": any(all(left <= start and right >= end for start, end in bounds) for left, right, _ in candidates),
                "singleChunkIds": [cid for left, right, cid in candidates if all(left <= start and right >= end for start, end in bounds)]})
    chars = sum(len(compact(chunk["content"])) for chunk in manifest["chunks"])
    return {"strategy": manifest["splitterStrategy"], "chunkCount": len(sizes), "sourceTopicCount": len(topics),
            "distinctChunkTitles": len({chunk["title"] for chunk in manifest["chunks"]}),
            "estimatedTokenSize": {"min": min(sizes), "median": statistics.median(sizes),
                "p95": sizes[max(0, (95 * len(sizes) + 99) // 100 - 1)], "max": max(sizes),
                "sum": sum(sizes), "belowMin": sum(size < manifest["minTokens"] for size in sizes),
                "aboveMax": sum(size > manifest["maxTokens"] for size in sizes)},
            "sourceNonWhitespaceCharacters": source_characters, "definitelyCoveredBodyCharacters": body_covered,
            "coveredCharactersIncludingHeadingMetadata": covered,
            "headingMetadataOnlyCharacters": covered - body_covered,
            "unprovenSourceCharacters": source_characters - covered,
            "bodyCharacterRedundancyRatio": round(chars / body_covered - 1, 6) if body_covered else None,
            "duplicateContentHashCount": sum(n - 1 for n in Counter(chunk["contentHash"] for chunk in manifest["chunks"]).values()),
            "unmappedChunks": anomalies, "ambiguousChunks": ambiguous,
            "topicsWithUnprovenCoverage": [row for row in topic_rows if row["sourceCharacters"] != row["coveredCharactersIncludingHeadingMetadata"]],
            "screeningEvidence": {"directTargetCount": len(evidence_rows), "quoteCount": sum(row["quoteCount"] for row in evidence_rows),
                "quotesCoveredAcrossAllChunks": sum(row["quotesCoveredAcrossAllChunks"] for row in evidence_rows),
                "quotesIntactInOneChunk": sum(row["quotesIntactInOneChunk"] for row in evidence_rows),
                "targetsWithAllQuotesInOneChunk": sum(row["allQuotesInOneChunk"] for row in evidence_rows), "targets": evidence_rows}}


def report_text(result):
    lines = ["# A/B/C 切分离线简单验证", "", f"测试集：{result['suiteVersion']}；知识库：{result['corpusVersion']}。",
        "", "仅调用当前 Java 切分代码，使用默认 300/500/50 估算参数；未启动 Spring、数据库，未调用 embedding、重排或回答模型，模型 token 消耗为 0。",
        "仅检查 screening30 的直接证据，answer10 保留。长度是项目正则估算，不是真实 tokenizer 计数。",
        "", "| 方案 | 块数 | 估算长度 min/median/p95/max | <300 | >500 | 正文估算 token 合计 | 未证实覆盖字符 | 正文字符冗余 |",
        "|---|---:|---|---:|---:|---:|---:|---:|"]
    for name, row in result["strategies"].items():
        size = row["estimatedTokenSize"]
        ratio = row['bodyCharacterRedundancyRatio']
        redundancy = f"{ratio:.1%}" if ratio is not None else "未知"
        lines.append(f"| {name} | {row['chunkCount']} | {size['min']}/{size['median']}/{size['p95']}/{size['max']} | {size['belowMin']} | {size['aboveMax']} | {size['sum']} | {row['unprovenSourceCharacters']} | {redundancy} |")
    lines += ["", "| 方案 | 直接证据引句跨块总覆盖 | 单块完整包含引句 | 同一证据单元的全部引句位于单块 | 未定位块 / 歧义块 |",
              "|---|---:|---:|---:|---:|"]
    for name, row in result["strategies"].items():
        ev = row['screeningEvidence']
        lines.append(f"| {name} | {ev['quotesCoveredAcrossAllChunks']}/{ev['quoteCount']} | {ev['quotesIntactInOneChunk']}/{ev['quoteCount']} | {ev['targetsWithAllQuotesInOneChunk']}/{ev['directTargetCount']} | {len(row['unmappedChunks'])} / {len(row['ambiguousChunks'])} |")
    lines += ["", "## 指标解释与限制", "",
        "- 原文覆盖按主题内去空白后的连续子串定位。重复文本有多个候选位置时不计为已证明覆盖；未证实覆盖不等同于确定遗漏，需查看 JSON 诊断。知识库前言和顶层主题标题不计入正文分母。",
        "- C 将粗体小节标题移入块标题；只有实际块标题保留对应小节时才计入元数据覆盖，相关字符单列在 JSON 中。",
        "- 字符冗余 = 全部块正文字符数 / 确定映射覆盖的不同原文字符数 - 1；反映重叠和重复，不代表语义冗余，也不是真实计费量。",
        "- B/C 不保证 300 的下限，短块数量是诊断项，不直接判失败。",
        "- 50 是配置的重叠目标/上限，不代表每一对块实际都有 50 个 token 重叠；实际重叠表现见字符冗余列。",
        "- 引句计数按题目和证据单元统计，不同题共享引句会重复计数。单块包含更多证据仅说明碎片化程度，不能推断检索必然更好。",
        "- 相关性标注仍为 PENDING_HUMAN_REVIEW；没有运行 30 题真实向量检索，不能把上述数值称作召回率、准确率或最终方案排名。",
        "- 下一轮应在相同检索参数和上下文预算下运行 30 题，测实际证据召回，再对两个候选执行相同的 core5 答案验证。", ""]
    return '\n'.join(lines)


def run(output, dependencies):
    output = output.resolve()
    if not output.is_relative_to((ROOT / "logs").resolve()):
        raise ValueError("Output must stay under project logs/")
    corpus = fixed.CORPUS.read_text(encoding="utf-8")
    suite = json.loads(fixed.SUITE.read_text(encoding="utf-8"))
    fixed.validate_suite(suite, json.loads(fixed.DATASET.read_text(encoding="utf-8")), corpus)
    sources = {str(ROOT / name): sha(ROOT / name) for name in SOURCES}
    inputs = {str(path): sha(path) for path in (fixed.CORPUS, fixed.SUITE, fixed.DATASET,
              Path(__file__), Path(__file__).with_name('audit_chunk_boundaries.py'),
              Path(__file__).with_name('freeze_chunking_suite.py'), ROOT / 'backend/pom.xml')}
    version = re.search(r"<langchain4j.version>([^<]+)</langchain4j.version>", (ROOT / "backend/pom.xml").read_text(encoding="utf-8")).group(1)
    framework = Path.home() / ".m2/repository/dev/langchain4j/langchain4j" / version / f"langchain4j-{version}.jar"
    if not framework.is_file() or not dependencies.is_dir():
        raise ValueError("Local dependency jars required; no network/download fallback")
    # The saved corpus runtime predates adding the high-level framework dependency.
    # Resolve OpenNLP from that exact framework POM, not an arbitrary cached version.
    ns = {"m": "http://maven.apache.org/POM/4.0.0"}
    framework_pom = ET.parse(framework.with_suffix('.pom')).getroot()
    opennlp_version = next(dep.find('m:version', ns).text for dep in framework_pom.findall('m:dependencies/m:dependency', ns)
                          if dep.find('m:artifactId', ns).text == 'opennlp-tools')
    opennlp = Path.home() / '.m2/repository/org/apache/opennlp/opennlp-tools' / opennlp_version / f'opennlp-tools-{opennlp_version}.jar'
    if not opennlp.is_file():
        raise ValueError('Matching local OpenNLP dependency required; no download fallback')
    output.mkdir(parents=True, exist_ok=False)
    classes = output / "classes"
    classes.mkdir()
    crash = ROOT / "logs/crash"
    crash.mkdir(parents=True, exist_ok=True)
    classpath = os.pathsep.join(map(str, [ROOT / "backend/target/classes", framework, opennlp, dependencies / "*"]))
    compile_cmd = ["javac", "-J-XX:ErrorFile=" + str(crash / "hs_err_pid%p.log"), "-encoding", "UTF-8", "-cp", classpath, "-d", str(classes), *sources]
    with (output / "compile.log").open("w", encoding="utf-8") as log:
        subprocess.run(compile_cmd, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, check=True, timeout=120)
    result = {"suiteVersion": suite["version"], "corpusVersion": suite["source"]["corpusVersion"],
              "providerCalls": 0, "modelTokens": 0, "mode": "OFFLINE_SPLIT_ONLY", "annotationStatus": suite["annotationStatus"],
              "compiledSources": sources, "inputSha256": inputs, "frameworkJarSha256": sha(framework),
              "dependencySha256": {str(path): sha(path) for path in [framework, opennlp, *sorted(dependencies.glob('*.jar')),
                  ROOT / 'backend/target/classes/com/example/mealcheck/service/PgVectorKnowledgeService$KnowledgeChunk.class']},
              "parameters": {"minEstimatedTokens": 300, "maxEstimatedTokens": 500, "overlapEstimatedTokens": 50},
              "strategies": {}}
    # Retain exact source and fixture snapshots beside the measurements.
    (output / "corpus.md").write_text(corpus, encoding="utf-8")
    (output / "suite.json").write_text(json.dumps(suite, ensure_ascii=False, indent=2), encoding="utf-8")
    for name, strategy in STRATEGIES.items():
        manifest_path = output / f"{name}-chunks.json"
        start = time.perf_counter()
        command = ["java", "-XX:ErrorFile=" + str(crash / "hs_err_pid%p.log"), "-cp", str(classes) + os.pathsep + classpath,
                   "KnowledgeCorpusTool", "--manifest", str(fixed.CORPUS), str(manifest_path), strategy]
        with (output / f"{name}-split.log").open("w", encoding="utf-8") as log:
            subprocess.run(command, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, check=True, timeout=120)
        elapsed = time.perf_counter() - start
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        if (manifest["minTokens"], manifest["maxTokens"], manifest["overlapTokens"]) != (300, 500, 50):
            raise ValueError("Production defaults changed; update experiment explicitly")
        result["strategies"][name] = analyze(manifest, corpus, suite)
        result["strategies"][name]["processWallSecondsIncludingJvmStartup"] = round(elapsed, 3)
        print(f"{name}: {manifest['chunkCount']} chunks", flush=True)
    result['offlineIntegrityPassed'] = all(row['unprovenSourceCharacters'] == 0 and not row['unmappedChunks']
        and not row['ambiguousChunks'] and not row['estimatedTokenSize']['aboveMax'] and not row['duplicateContentHashCount']
        for row in result['strategies'].values())
    for path, fingerprint in {**sources, **inputs}.items():
        if sha(Path(path)) != fingerprint:
            raise ValueError("Input/source changed during the experiment: " + path)
    (output / "results.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding="utf-8")
    (output / "report.md").write_text(report_text(result), encoding="utf-8")
    checksums = {str(path.relative_to(output)): sha(path) for path in sorted(output.rglob('*')) if path.is_file()}
    (output / "checksums.json").write_text(json.dumps(checksums, indent=2) + '\n', encoding="utf-8")
    print("Report: " + str(output / "report.md"))
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output-directory', type=Path, default=ROOT / 'logs/backend' / ('chunking-smoke-' + datetime.now().strftime('%Y%m%d-%H%M%S-%f')))
    parser.add_argument('--dependency-directory', type=Path, default=ROOT / 'logs/backend/knowledge-longform-20261020-distribution-v1/lib')
    args = parser.parse_args()
    run(args.output_directory, args.dependency_directory)


if __name__ == '__main__':
    main()
