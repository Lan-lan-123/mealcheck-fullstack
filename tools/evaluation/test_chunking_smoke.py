import unittest

from run_chunking_smoke import analyze, STRATEGIES


def fixture(corpus, quote, contents, strategy="A", titles=None):
    title = "主题"
    start = corpus.index(quote)
    case = {"caseId": "screen", "set": "screening30", "evidenceTargets": [
        {"sourceRef": "unit", "title": title, "relevance": 2,
         "quotes": [{"text": quote, "start": start, "end": start + len(quote)}]}]}
    # Deliberately invalid holdout evidence must never be inspected during screening.
    holdout = {"set": "answer10", "evidenceTargets": [{"title": "不存在"}]}
    chunks = [{"title": titles[i] if titles else title, "content": content,
               "chunkIndex": i, "contentHash": str(i), "tokenCount": len(content)} for i, content in enumerate(contents)]
    return {"splitterStrategy": STRATEGIES[strategy], "minTokens": 300, "maxTokens": 500, "chunks": chunks}, {"cases": [case, holdout]}


class ChunkingSmokeTest(unittest.TestCase):
    def test_fragmented_quote_is_preserved_across_chunks_but_not_intact(self):
        corpus = "## 主题\n甲乙丙丁"
        manifest, suite = fixture(corpus, "乙丙", ["甲乙", "丙丁"])
        result = analyze(manifest, corpus, suite)
        self.assertEqual(result["unprovenSourceCharacters"], 0)
        evidence = result["screeningEvidence"]
        self.assertEqual(evidence["quotesCoveredAcrossAllChunks"], 1)
        self.assertEqual(evidence["quotesIntactInOneChunk"], 0)
        self.assertEqual(evidence["targetsWithAllQuotesInOneChunk"], 0)

    def test_gap_cannot_be_reported_as_complete_quote_coverage(self):
        corpus = "## 主题\n甲乙丙丁"
        manifest, suite = fixture(corpus, "乙丙", ["甲乙", "丁"])
        result = analyze(manifest, corpus, suite)
        self.assertEqual(result["unprovenSourceCharacters"], 1)
        self.assertEqual(result["screeningEvidence"]["quotesCoveredAcrossAllChunks"], 0)

    def test_alternative_duplicate_locations_are_not_unioned_as_proof(self):
        corpus = "## 主题\n甲乙甲乙"
        manifest, suite = fixture(corpus, "甲乙", ["甲乙"])
        result = analyze(manifest, corpus, suite)
        self.assertEqual(len(result["ambiguousChunks"]), 1)
        self.assertEqual(result["definitelyCoveredBodyCharacters"], 0)
        self.assertEqual(result["unprovenSourceCharacters"], 4)
        self.assertEqual(result["screeningEvidence"]["quotesIntactInOneChunk"], 0)

    def test_structural_heading_counts_as_metadata_only_when_title_preserves_it(self):
        corpus = "## 主题\n**小节**\n甲乙"
        manifest, suite = fixture(corpus, "甲乙", ["甲乙"], strategy="C", titles=["主题 / 小节"])
        result = analyze(manifest, corpus, suite)
        self.assertEqual(result["definitelyCoveredBodyCharacters"], 2)
        self.assertEqual(result["headingMetadataOnlyCharacters"], 6)
        self.assertEqual(result["unprovenSourceCharacters"], 0)
        manifest["chunks"][0]["title"] = "主题"
        self.assertEqual(analyze(manifest, corpus, suite)["unprovenSourceCharacters"], 6)

    def test_corrupted_chunk_body_is_not_counted_as_source_coverage(self):
        corpus = "## 主题\n甲乙丙丁"
        manifest, suite = fixture(corpus, "乙丙", ["甲乙错误丙丁"])
        result = analyze(manifest, corpus, suite)
        self.assertEqual(len(result["unmappedChunks"]), 1)
        self.assertEqual(result["unprovenSourceCharacters"], 4)
        self.assertEqual(result["screeningEvidence"]["quotesCoveredAcrossAllChunks"], 0)

    def test_whitespace_reformatting_and_overlap_do_not_count_as_omission(self):
        corpus = "## 主题\n甲乙\n\n丙丁"
        manifest, suite = fixture(corpus, "丙丁", ["甲乙 丙", "丙丁"])
        result = analyze(manifest, corpus, suite)
        self.assertEqual(result["unprovenSourceCharacters"], 0)
        self.assertEqual(result["bodyCharacterRedundancyRatio"], 0.25)
        self.assertEqual(result["screeningEvidence"]["quotesIntactInOneChunk"], 1)


if __name__ == "__main__":
    unittest.main()
