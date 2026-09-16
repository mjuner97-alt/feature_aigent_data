import contextlib
import io
import json
import runpy
import sys
import types
import unittest
from pathlib import Path


SCRIPTS = Path(__file__).parents[2] / "main/resources/workspace/scripts"
SCRIPT = SCRIPTS / "555153205/q2_1_metrics_by_dept_version.py"


class FakeSeries(list):
    def __eq__(self, other):
        return FakeSeries([1 if v == other else 0 for v in self])

    def sum(self):
        return sum(self)


class FakeDataFrame:
    """最小 DataFrame 桩: len / df[col]==x .sum() / columns / iterrows."""

    def __init__(self, rows):
        self.rows = rows
        self.columns = list(rows[0].keys()) if rows else []

    def __len__(self):
        return len(self.rows)

    def __getitem__(self, col):
        return FakeSeries([r.get(col) for r in self.rows])

    def iterrows(self):
        for r in self.rows:
            yield None, r


class Q21ScriptExecDownloadTest(unittest.TestCase):
    """q2_1 脚本: 注册 SQL 调配 + echarts 可渲染块 + stdout 下载块协议."""

    def setUp(self):
        self._saved = {k: sys.modules.get(k) for k in ("pandas", "_gauss_jdbc", "_sql_registry")}

    def tearDown(self):
        for k, v in self._saved.items():
            if v is None:
                sys.modules.pop(k, None)
            else:
                sys.modules[k] = v

    def _install_stubs(self, registered_rows):
        pandas = types.ModuleType("pandas")
        pandas.DataFrame = FakeDataFrame
        sys.modules["pandas"] = pandas

        gauss = types.ModuleType("_gauss_jdbc")
        gauss.query_gauss = lambda *args, **kwargs: []
        sys.modules["_gauss_jdbc"] = gauss

        sql_registry = types.ModuleType("_sql_registry")
        self.registered_calls = []

        def fake_run_registered_sql(sql_id, params=None):
            self.registered_calls.append((sql_id, params))
            return registered_rows

        sql_registry.run_registered_sql = fake_run_registered_sql
        sys.modules["_sql_registry"] = sql_registry

    def _run_main(self, stdin_params):
        sys.stdin = io.StringIO(json.dumps(stdin_params))
        buf = io.StringIO()
        module = runpy.run_path(str(SCRIPT))
        with contextlib.redirect_stdout(buf):
            module["main"]()
        return module, buf.getvalue()

    def test_main_emits_summary_echarts_and_download_block(self):
        rows = [
            {"项目编号": "P001", "项目名称": "项目一", "Q2_1打分状态": "已打分", "Q2_1是否达标": "达标"},
            {"项目编号": "P002", "项目名称": "项目|二", "Q2_1打分状态": "未打分", "Q2_1是否达标": "未达标"},
        ]
        self._install_stubs(rows)

        module, out = self._run_main({"dept": "杭州开发二部", "version": "2026年7月份版本"})

        # 调配注册 SQL (与 sql_registry_exec 同一 sqlId), 参数已归一为数组
        self.assertEqual(
            [("q2_1_metrics_by_dept_version",
              {"dept": ["杭州开发二部"], "version": ["2026年7月份版本"]})],
            self.registered_calls)

        # 汇总表 + json 行
        self.assertIn("| 2 | 1 | 1 | 50.0% | 50.0% |", out)
        self.assertIn('"total":2,"scored":1,"passed":1', out)
        # echarts 可渲染块 (script_exec 约定, ChatScriptExecResultHook 接管)
        self.assertIn("```echarts", out)
        # 下载块: META 含固化文件名, 明细在 CONTENT/END 之间
        self.assertIn('<<<DOWNLOAD_META>>> {"filename": "q2_1_明细.csv"}', out)
        meta_idx = out.index("<<<DOWNLOAD_META>>>")
        content_idx = out.index("<<<DOWNLOAD_CONTENT>>>")
        end_idx = out.index("<<<DOWNLOAD_END>>>")
        self.assertLess(meta_idx, content_idx, "META 须在 CONTENT 之前")
        self.assertLess(content_idx, end_idx, "CONTENT 须在 END 之前")
        self.assertIn("P001", out)
        # 明细 markdown 表含列头, 转义列分隔符
        self.assertIn("| 项目编号 | 项目名称 |", out)
        self.assertIn("项目\\|二", out)
        # 下载块在 echarts 之后 -> 最终展示"图在上、链接在下"
        self.assertLess(out.index("```echarts"), meta_idx)

    def test_main_skips_download_block_when_no_data(self):
        self._install_stubs([])
        _, out = self._run_main({"dept": "杭州开发二部", "version": "2026年7月份版本"})

        self.assertNotIn("<<<DOWNLOAD_META>>>", out)
        self.assertIn("无数据", out)
        self.assertIn("| 0 | 0 | 0 | 0.0% | 0.0% |", out)


class SqlRegistryHelperTest(unittest.TestCase):
    """_sql_registry.run_registered_sql: 校验规则与 datasource 分派."""

    def setUp(self):
        self._saved = {k: sys.modules.get(k) for k in ("_gauss_jdbc", "_sql_registry")}

    def tearDown(self):
        for k, v in self._saved.items():
            if v is None:
                sys.modules.pop(k, None)
            else:
                sys.modules[k] = v

    def _load(self, registry_rows, captured):
        gauss = types.ModuleType("_gauss_jdbc")

        def query_gauss(sql, params=None):
            if "sql_registry" in sql:
                return registry_rows
            captured.append((sql, params))
            return [{"id": 1}]

        gauss.query_gauss = query_gauss
        sys.modules["_gauss_jdbc"] = gauss
        sys.modules.pop("_sql_registry", None)
        return runpy.run_path(str(SCRIPTS / "_sql_registry.py"))

    REGISTRY = [{
        "sql_template": "SELECT * FROM t WHERE dept IN (:dept)",
        "params_schema": json.dumps([{"name": "dept", "required": True}]),
        "datasource": "gauss",
    }]

    def test_runs_registered_sql_on_gauss_with_limit_fallback(self):
        captured = []
        module = self._load(self.REGISTRY, captured)
        rows = module["run_registered_sql"]("q2_1", {"dept": ["杭州开发二部"]})

        self.assertEqual([{"id": 1}], rows)
        sql, params = captured[0]
        self.assertTrue(sql.strip().endswith("LIMIT 10000"), "无 LIMIT 模板须兜底追加")
        self.assertEqual({"dept": ["杭州开发二部"]}, params)

    def test_rejects_extra_param_not_in_schema(self):
        captured = []
        module = self._load(self.REGISTRY, captured)
        with self.assertRaises(SystemExit):
            module["run_registered_sql"]("q2_1", {"dept": ["d"], "evil": "x"})
        self.assertEqual([], captured)

    def test_rejects_missing_required_param(self):
        captured = []
        module = self._load(self.REGISTRY, captured)
        with self.assertRaises(SystemExit):
            module["run_registered_sql"]("q2_1", {})
        self.assertEqual([], captured)

    def test_fails_clearly_for_unknown_sql_id(self):
        captured = []
        module = self._load([], captured)
        with self.assertRaises(SystemExit):
            module["run_registered_sql"]("ghost", {})
        self.assertEqual([], captured)


if __name__ == "__main__":
    unittest.main()
