import unittest
import subprocess
import sys
from pathlib import Path
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

    def test_invalid_cli_samples_fail_before_starting_facilities(self):
        reply = subprocess.run([sys.executable, str(Path(__file__).with_name('observe.py')), '--samples', '0'],
                               capture_output=True, text=True)
        self.assertEqual(reply.returncode, 2)
        self.assertIn('samples must be 1..1000', reply.stderr)
        self.assertNotIn('PASS', reply.stdout)
if __name__ == '__main__':
    unittest.main()
