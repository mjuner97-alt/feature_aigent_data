import json
import re
import runpy
import sys
import types
import unittest
from pathlib import Path


SCRIPT = (
    Path(__file__).parents[2]
    / "main/resources/workspace/scripts/555153205/q2_1_metrics_by_dept_version.py"
)


class Q21MetricsReportTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        sys.modules.setdefault("pandas", types.ModuleType("pandas"))
        gauss = types.ModuleType("_gauss_jdbc")
        gauss.query_gauss = lambda *args, **kwargs: []
        sys.modules["_gauss_jdbc"] = gauss
        cls.module = runpy.run_path(str(SCRIPT))

    def test_render_report_returns_frontend_renderable_echarts_and_html(self):
        report = self.module["render_report"](
            dept="杭州开发二部",
            version="2026年7月份版本",
            total=296,
            scored=0,
            passed=210,
            scored_pct=0.0,
            passed_pct=70.95,
        )

        self.assertIn("```echarts\n", report)
        self.assertIn("\n```\n\n```html\n", report)
        self.assertIn("杭州开发一部", report)
        self.assertIn("杭州开发二部", report)
        self.assertIn("杭州产品部", report)
        self.assertIn("<td>296</td>", report)
        self.assertIn("<td>70.95%</td>", report)

        option_text = re.search(r"```echarts\n([\s\S]*?)\n```", report).group(1)
        option = json.loads(option_text)
        self.assertEqual([0.0], option["series"][0]["data"])
        self.assertEqual([70.95], option["series"][1]["data"])


if __name__ == "__main__":
    unittest.main()
