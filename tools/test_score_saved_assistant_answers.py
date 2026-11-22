import unittest
from unittest.mock import patch
import score_saved_assistant_answers as scorer


class SavedAnswerScoreTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.rubric = scorer.read(scorer.ROOT / 'backend/src/main/resources/evaluation/assistant_eval_cases.json')['rubric']

    def grade(self, **scores):
        return {'scores': dict.fromkeys(scorer.DIMENSIONS, 4) | scores,
                'hardGateFailures': [], 'reason': 'Has evidence and covers the core question.'}

    def test_inapplicable_dimensions_are_excluded_and_weights_renormalized(self):
        result = scorer.classify_grade(self.grade(personalization=0, conversationConsistency=0), self.rubric, 'ANSWERABLE')
        self.assertTrue(result['passed'])
        self.assertAlmostEqual(result['weightedScore'], 4)

    def test_high_average_cannot_hide_missing_grounding_or_hard_gate(self):
        self.assertFalse(scorer.classify_grade(self.grade(groundedness=2), self.rubric, 'ANSWERABLE')['passed'])
        grade = self.grade()
        grade['hardGateFailures'] = ['inventedUserData']
        self.assertFalse(scorer.classify_grade(grade, self.rubric, 'ANSWERABLE')['passed'])

    def test_exact_threshold_is_not_rejected_by_binary_float_rounding(self):
        result = scorer.classify_grade(self.grade(correctness=3, groundedness=3, actionability=4), self.rubric, 'ANSWERABLE')
        self.assertEqual(result['weightedScore'], 3.2)
        self.assertTrue(result['passed'])

    def test_information_gap_category_has_its_existing_actionability_minimum(self):
        grade = self.grade(actionability=2)
        self.assertTrue(scorer.classify_grade(grade, self.rubric, 'ANSWERABLE')['passed'])
        self.assertFalse(scorer.classify_grade(grade, self.rubric, 'INSUFFICIENT_EVIDENCE')['passed'])

    def test_non_integer_missing_and_out_of_range_scores_are_rejected(self):
        for value in [True, None, '4', 4.0, 5, -1]:
            with self.subTest(value=value), self.assertRaises(ValueError):
                scorer.classify_grade(self.grade(correctness=value), self.rubric, 'ANSWERABLE')
        grade = self.grade()
        del grade['scores']['correctness']
        with self.assertRaises(ValueError):
            scorer.classify_grade(grade, self.rubric, 'ANSWERABLE')

    def test_unknown_gate_and_empty_reason_are_not_valid_scores(self):
        for patch in [{'hardGateFailures': ['unknown']}, {'hardGateFailures': None}, {'reason': ''}]:
            with self.assertRaises(ValueError):
                scorer.classify_grade(self.grade() | patch, self.rubric, 'ANSWERABLE')

    def test_known_spelling_alias_preserves_scores_and_hard_gate_failures(self):
        raw = self.grade()
        raw['hardGatesFailures'] = ['privacyViolation']
        del raw['hardGateFailures']
        normalized, notes = scorer.normalize_grade(raw)
        self.assertEqual(normalized['scores'], raw['scores'])
        self.assertTrue(notes)
        self.assertNotIn('hardGateFailures', raw)
        self.assertFalse(scorer.classify_grade(normalized, self.rubric, 'ANSWERABLE')['passed'])
        missing = self.grade()
        del missing['hardGateFailures']
        unchanged, notes = scorer.normalize_grade(missing)
        self.assertFalse(notes)
        with self.assertRaises(ValueError):
            scorer.classify_grade(unchanged, self.rubric, 'ANSWERABLE')

    def test_canonical_hard_gate_is_never_overwritten_by_an_alias(self):
        raw = self.grade() | {'hardGateFailures': ['privacyViolation'], 'hardGatesFailures': []}
        normalized, notes = scorer.normalize_grade(raw)
        self.assertFalse(notes)
        self.assertFalse(scorer.classify_grade(normalized, self.rubric, 'ANSWERABLE')['passed'])

    def test_packet_preserves_delivered_answers_and_hides_mode_and_status(self):
        # Archived paid-model runs are deliberately ignored by Git. CI uses synthetic inputs.
        original = {'status': 'COMPLETED', 'cases': [
            {'id': f'case-{n}', 'reviewMode': mode, 'requestId': f'{n}-{mode}',
             'question': f'Question {n}', 'response': {
                 'answer': f'Delivered answer {n} {mode}', 'answerStatus': 'PARTIAL',
                 'references': [{'id': 7, 'title': 'Source', 'content': 'Captured excerpt'}]}}
            for n in range(50) for mode in ('FAST', 'REVIEWED')]}
        selection = {'cases': [{'id': f'case-{n}', 'answerability': 'ANSWERABLE'} for n in range(50)]}
        audits = [{'requestId': '0-FAST', 'candidate': {'claims': [{'evidence': [
            {'sourceId': 7, 'quotes': ['Bound quote', 'Bound quote']},
            {'sourceId': 99, 'quotes': ['Uncited source must not leak']}]}]}}]
        with patch.object(scorer, 'read', side_effect=[original, selection, audits]):
            packet, key = scorer.prepare()
        self.assertEqual(len(packet), 100)
        self.assertEqual(len({x['sample'] for x in packet}), 100)
        self.assertEqual(sum(k['mode'] == 'FAST' for k in key), 50)
        raw = {(r['id'], r['reviewMode']): r for r in original['cases']}
        by_sample = {x['sample']: x for x in packet}
        for k in key:
            item = by_sample[k['sample']]
            self.assertNotIn('mode', item)
            self.assertNotIn('answerStatus', item)
            self.assertNotIn('reasonCodes', item)
            response = raw[k['id'], k['mode']]['response']
            self.assertEqual(item['answer'], response['answer'])
            self.assertEqual([r['id'] for r in item['evidence']], [r['id'] for r in response.get('references', [])])
            if k['id'] == 'case-0' and k['mode'] == 'FAST':
                self.assertEqual(item['evidence'][0]['capturedSourceQuotes'], ['Bound quote'])
            else:
                self.assertEqual(item['evidence'][0]['capturedSourceQuotes'], [])

    def test_errors_are_separate_from_quality_denominator(self):
        rows = [{'sample': 'a', 'validGrade': True, 'assessment': {'passed': True, 'weightedScore': 4}},
                {'sample': 'b', 'validGrade': False}]
        key = [{'sample': 'a', 'mode': 'FAST'}, {'sample': 'b', 'mode': 'FAST'}]
        result = scorer.summarize(rows, key)['FAST']
        self.assertEqual(result['qualityPassRate'], 1)
        self.assertEqual(result['endToEndPassRate'], 1 / 50)
        self.assertEqual(result['gradingErrors'], 1)


if __name__ == '__main__':
    unittest.main()
