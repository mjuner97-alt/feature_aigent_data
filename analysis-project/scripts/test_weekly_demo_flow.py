import json, tempfile, unittest, sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).parent))
from weekly_demo_flow import seed, execute, verify, reset

class WeeklyDemoFlowTest(unittest.TestCase):
    def setUp(self): self.root = Path(tempfile.mkdtemp()) / "weekly-demo"
    def tearDown(self): reset(self.root)
    def test_full_flow(self):
        seed(self.root); result = execute(self.root); self.assertEqual(result["template"], "weekly_demo"); self.assertEqual(verify(self.root)["status"], "verified")
    def test_missing_template(self):
        seed(self.root)
        with self.assertRaisesRegex(RuntimeError, "ReportTemplateNotFoundOrDisabled"): execute(self.root, "report_template:missing")

if __name__ == "__main__": unittest.main()
