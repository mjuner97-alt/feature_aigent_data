import json
import subprocess
import sys
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name('parameter_input_demo.py')


class ParameterInputDemoTest(unittest.TestCase):
    def run_script(self, params):
        return subprocess.run([sys.executable, str(SCRIPT)], input=json.dumps(params),
                              text=True, capture_output=True, encoding='utf-8')

    def test_parameters_reach_report(self):
        result = self.run_script({'report_date': '2026-10-14', 'regions': ['华南'], 'quantity': 7})
        self.assertEqual(result.returncode, 0, result.stderr)
        for value in ['2026-10-14', '华南', '<td>7</td>', '模拟数据']:
            self.assertIn(value, result.stdout)
        self.assertNotIn('华东', result.stdout)

    def test_defaults(self):
        result = self.run_script({})
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('华东', result.stdout)
        self.assertIn('<td>10</td>', result.stdout)

    def test_rejects_bad_inputs(self):
        for params in [{'quantity': -1}, {'quantity': True}, {'quantity': 1.5},
                       {'regions': '华东'}, {'report_date': 'invalid'}]:
            self.assertNotEqual(self.run_script(params).returncode, 0, params)

    def test_escapes_region_names(self):
        result = self.run_script({'regions': ['<script>alert(1)</script>']})
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertNotIn('<script>', result.stdout)
        self.assertIn('&lt;script&gt;', result.stdout)


if __name__ == '__main__':
    unittest.main()
