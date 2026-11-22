import unittest
from report_rag_quality import audit


class RagQualityAuditTest(unittest.TestCase):
    def test_current_full_dataset_defaults_to_two_hundred_cases(self):
        rows = [dict(self.row(), caseId=index) for index in range(1, 201)]
        self.assertTrue(audit({"cases": rows})["coverageComplete"])
        self.assertFalse(audit({"cases": rows[:-1]})["coverageComplete"])

    def test_historical_dataset_total_remains_valid_after_expansion(self):
        rows = [dict(self.row(), caseId=index) for index in range(1, 159)]
        result = audit({"datasetTotalCaseCount": 158, "cases": rows})
        self.assertTrue(result["coverageComplete"])
        self.assertTrue(audit({"datasetCaseCount": 158, "cases": rows})["coverageComplete"])

    def test_random_sample_keeps_original_dataset_ids(self):
        row = self.row()
        row["caseId"] = 90
        result = audit({"datasetCaseCount": 1, "selectedCaseIds": [90], "cases": [row]})
        self.assertTrue(result["coverageComplete"])
        self.assertEqual(result["selectedCaseIds"], [90])
    def row(self, **changes):
        return dict(caseId=1, status="COMPLETE", answer="回答", usedRemoteChat=True,
                    expectNoAnswer=False, references=[{"id": 1}], referencesExistInKnowledge=True,
                    citationRelevanceProxy=True, possibleRefusal=False, **changes)

    def test_offline_cannot_be_reported_as_real_quality(self):
        result = audit({"executionMode": "OFFLINE_FIXTURE", "qualityMeasurement": True, "datasetCaseCount": 1, "cases": [self.row()]})
        self.assertTrue(result["coverageComplete"])
        self.assertFalse(result["realModelEvidence"])
        self.assertFalse(result["readyForHumanReview"])

    def test_empty_citation_is_not_a_pass_even_if_exists_is_vacuously_true(self):
        row = self.row()
        row.update(references=[], possibleRefusal=True, usedRemoteChat=False)
        result = audit({"cases": [row]})
        self.assertEqual(result["cases"][0]["flags"], ["no_successful_chat_call", "answerable_without_citation", "possible_false_refusal"])
        self.assertEqual(result["possibleFalseRefusalCount"], 1)

    def test_wrong_source_out_of_scope_errors_and_duplicate_ids_are_visible(self):
        row = self.row()
        row.update(referencesExistInKnowledge=False, citationRelevanceProxy=False)
        other = self.row()
        other.update(expectNoAnswer=True)
        result = audit({"executionMode": "LIVE_PROVIDER", "qualityMeasurement": True, "datasetCaseCount": 3,
                        "cases": [row, other, {"caseId": 3, "status": "ERROR"}]})
        self.assertFalse(result["coverageComplete"])
        self.assertEqual(result["executionErrorCount"], 1)
        self.assertIn("citation_source_mismatch", result["cases"][0]["flags"])
        self.assertIn("out_of_scope_citation", result["cases"][1]["flags"])
        self.assertFalse(result["readyForHumanReview"])


if __name__ == "__main__":
    unittest.main()
