"""The public CI entry fails when required runtime facilities are unavailable."""
import os
from pathlib import Path
import subprocess
import sys
import unittest

class RequiredFacilitiesCliTest(unittest.TestCase):
    def test_missing_docker_is_failure_before_tests(self):
        environment = {key: value for key, value in os.environ.items() if key != "DOCKER_CONTEXT"}
        environment["DOCKER_HOST"] = "tcp://127.0.0.1:1"
        result = subprocess.run([sys.executable, str(Path(__file__).with_name("run.py")), "--preflight"],
                                env=environment, capture_output=True, text=True, timeout=50)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("required Docker daemon unavailable", result.stdout + result.stderr)
        self.assertNotIn("tests passed", result.stdout)

if __name__ == "__main__":
    unittest.main()
