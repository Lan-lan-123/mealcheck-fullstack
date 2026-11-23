import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import freeze_chunking_suite as fixed


class ChunkingSuiteTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.cases = json.loads(fixed.DATASET.read_text(encoding="utf-8"))
        cls.corpus = fixed.CORPUS.read_text(encoding="utf-8")
        cls.suite = json.loads(fixed.SUITE.read_text(encoding="utf-8"))

    def test_frozen_suite_matches_sources_and_is_deterministic(self):
        self.assertTrue(fixed.validate_suite(self.suite, self.cases, self.corpus)["valid"])
        self.assertEqual(fixed.build_suite(self.cases, self.corpus), self.suite)
        self.assertEqual(fixed.build_suite(self.cases, self.corpus.replace("\n", "\r\n")), self.suite)

    def test_question_sets_are_disjoint_with_fixed_distributions_and_core_subset(self):
        first = self.suite["sets"]["screening30"]
        second = self.suite["sets"]["answer10"]
        self.assertEqual(len(set(first["caseIds"])), 30)
        self.assertEqual(len(set(second["caseIds"])), 10)
        self.assertFalse(set(first["caseIds"]) & set(second["caseIds"]))
        self.assertEqual(first["answerabilityCounts"], fixed.EXPECTED["screening30"])
        self.assertEqual(second["answerabilityCounts"], fixed.EXPECTED["answer10"])
        self.assertEqual(second["core5CaseIds"], ["rag-008", "rag-070", "rag-187", "rag-172", "rag-200"])
        self.assertTrue(set(second["core5CaseIds"]) <= set(second["caseIds"]))

    def test_evidence_anchors_bind_original_text_instead_of_strategy_chunk_ids(self):
        count = 0
        for row in self.suite["cases"]:
            direct = {target["sourceRef"] for target in row["evidenceTargets"] if target["relevance"] == 2}
            for group in row["requiredEvidenceGroups"]:
                self.assertTrue(set(group) <= direct)
            for target in row["evidenceTargets"]:
                self.assertNotIn("knowledgeId", target)
                self.assertNotIn("chunkKey", target)
                for quote in target["quotes"]:
                    count += 1
                    self.assertEqual(self.corpus[quote["start"]:quote["end"]], quote["text"])
                    self.assertEqual(fixed.digest(quote["text"]), quote["textSha256"])
        expected_count = sum(len(quotes) for row in self.cases
                             if row['id'] in {c['caseId'] for c in self.suite['cases']}
                             for _, quotes in {(t['title'], tuple(t['evidenceQuotes'])) for t in row['evidenceTargets']})
        self.assertEqual(count, expected_count)
        self.assertGreaterEqual(count, 124)

    def test_different_evidence_units_of_same_title_cannot_collapse(self):
        cases = copy.deepcopy(self.cases)
        row = cases[0]
        first = row['evidenceTargets'][0]
        extra = copy.deepcopy(first)
        extra['knowledgeId'] = 'new-unit'
        extra['evidenceQuotes'] = [first['evidenceQuotes'][0][:20]]
        row['evidenceTargets'].append(extra)
        row['requiredEvidenceGroups'].append(['new-unit'])
        suite = fixed.build_suite(cases, self.corpus)
        new_row = next(c for c in suite['cases'] if c['caseId'] == row['id'])
        refs = [t['sourceRef'] for t in new_row['evidenceTargets'] if t['title'] == first['title']]
        self.assertEqual(len(refs), len(set(refs)))

    def test_identical_quote_units_in_overlapping_chunks_do_not_inflate_pilot_targets(self):
        cases = copy.deepcopy(self.cases)
        first = cases[0]['evidenceTargets'][0]
        duplicate = copy.deepcopy(first)
        duplicate['knowledgeId'] = 'same-anchor-new-overlapping-chunk'
        cases[0]['evidenceTargets'].append(duplicate)
        for group in cases[0]['requiredEvidenceGroups']:
            if first['knowledgeId'] in group:
                group.append(duplicate['knowledgeId'])
        revised = fixed.build_suite(cases, self.corpus)['cases'][0]
        original = self.suite['cases'][0]
        self.assertEqual(revised['evidenceTargets'], original['evidenceTargets'])
        self.assertEqual(revised['requiredEvidenceGroups'], original['requiredEvidenceGroups'])

    def test_source_drift_or_tampered_selection_cannot_silently_pass(self):
        changed = copy.deepcopy(self.cases)
        changed[0]["question"] += "（变更）"
        with self.assertRaisesRegex(ValueError, "fingerprint changed"):
            fixed.validate_suite(self.suite, changed, self.corpus)
        with self.assertRaisesRegex(ValueError, "fingerprint changed"):
            fixed.validate_suite(self.suite, self.cases, self.corpus + "\n新增正文\n")
        changed = copy.deepcopy(self.suite)
        changed["cases"][0]["evidenceTargets"][0]["quotes"][0]["start"] += 1
        with self.assertRaisesRegex(ValueError, "changed"):
            fixed.validate_suite(changed, self.cases, self.corpus)

    def test_duplicate_or_overlapping_selection_is_rejected(self):
        changed = copy.deepcopy(fixed.SELECTIONS)
        changed["screening30"][1] = changed["screening30"][0]
        with self.assertRaisesRegex(ValueError, "duplicate"):
            fixed.build_suite(self.cases, self.corpus, changed)
        changed = copy.deepcopy(fixed.SELECTIONS)
        changed["answer10"][0] = changed["screening30"][0]
        with self.assertRaisesRegex(ValueError, "overlap"):
            fixed.build_suite(self.cases, self.corpus, changed)

    def test_unverified_labels_are_not_promoted_to_gold_or_blind_validation(self):
        self.assertFalse(self.suite["independentlyAnnotated"])
        self.assertFalse(self.suite["blindTest"])
        self.assertEqual(self.suite["annotationStatus"], "PENDING_HUMAN_REVIEW")
        self.assertTrue(all(row["annotationStatus"] == "PENDING_HUMAN_REVIEW" for row in self.suite["cases"]))
        self.assertEqual(self.suite["evaluationPolicy"]["providerCallsDuringFreeze"], 0)

    def test_export_preserves_history_and_produces_blank_review_and_checksums(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(fixed, "ROOT", Path(directory)):
            output = Path(directory) / "logs/backend/suite"
            fixed.export_suite(self.suite, self.corpus, output)
            review = json.loads((output / "review.json").read_text(encoding="utf-8"))
            self.assertEqual(len(review["reviews"]), 40)
            self.assertFalse(review["proposedLabelsIncluded"])
            self.assertTrue(all(row["answerability"] is None and not row["evidenceJudgments"] for row in review["reviews"]))
            manifest = json.loads((output / "manifest.json").read_text(encoding="utf-8"))
            for name, expected in manifest["files"].items():
                self.assertEqual(hashlib.sha256((output / name).read_bytes()).hexdigest(), expected)
            self.assertEqual(len(json.loads((output / "screening30.json").read_text(encoding="utf-8"))["cases"]), 30)
            self.assertEqual(len(json.loads((output / "answer10.json").read_text(encoding="utf-8"))["cases"]), 10)
            with self.assertRaises(FileExistsError):
                fixed.export_suite(self.suite, self.corpus, output)
            with self.assertRaisesRegex(ValueError, "logs"):
                fixed.export_suite(self.suite, self.corpus, Path(directory) / "source-output")


if __name__ == "__main__":
    unittest.main()
