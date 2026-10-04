"""Exercise the public artifact collection CLI, including interrupted smoke secrets."""
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

CLI = Path(__file__).with_name("reports.py")

class SafeReportsCliTest(unittest.TestCase):
    def test_only_allowlisted_reports_leave_the_run_and_secrets_are_redacted(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            source = root / "source"
            source.mkdir()
            (source / ".env.local").write_text("DB_PASSWORD=artifact-secret-canary\n")
            (source / "summary.json").write_text(json.dumps({"failure": "artifact-secret-canary"}))
            (source / "build.log").write_text("artifact-secret-canary")
            (source / "inspect.log").write_text("private-inspect-canary")
            (source / "config.log").write_text("expanded-config-canary")
            (source / "ports.yml").write_text("private-file-canary")
            (source / "link.jar").write_bytes(b"private-image-canary")
            destination = root / "artifact"
            result = subprocess.run([sys.executable, str(CLI), str(source), str(destination)], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            names = {path.name for path in destination.rglob("*") if path.is_file()}
            self.assertEqual(names, {"summary.json", "build.log"})
            output = "".join(path.read_text() for path in destination.rglob("*") if path.is_file())
            self.assertNotIn("canary", output)
            self.assertIn("[REDACTED]", output)

if __name__ == "__main__":
    unittest.main()
