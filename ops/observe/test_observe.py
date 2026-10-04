import unittest
from observe import summarize

class ObservationReportTest(unittest.TestCase):
    def test_business_success_is_not_rejection_throughput(self):
        result = summarize([(302, 10), (429, 1), (503, 2), (302, 30)], 2)
        self.assertEqual(result['statuses'], {'302': 2, '429': 1, '503': 1})
        self.assertEqual(result['successful_redirects_per_second'], 1)
        self.assertEqual(result['latency_ms_by_status']['302']['p50'], 10)
        self.assertEqual(result['latency_ms_by_status']['302']['p95'], 30)
        self.assertNotIn('p99', result['latency_ms_by_status']['302'])
        self.assertEqual(result['latency_ms_by_status']['429']['samples'], 1)

if __name__ == '__main__':
    unittest.main()
