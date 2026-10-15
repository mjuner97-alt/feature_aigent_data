import json, shutil, sys
from pathlib import Path
from datetime import date

BASE = Path(__file__).resolve().parents[1] / "tmp" / "weekly-demo"
SCRIPT_ID = "report_template:weekly_demo"

DATA = {
    "period": "2026-W41",
    "project_progress": ["完成报告模板注册流程", "打通 script_exec 演示链路"],
    "metrics": {"完成事项": 12, "按期完成率": "92%", "阻塞事项": 1},
    "risks": ["真实生产模板仍需平台侧独立注册"],
    "next_week": ["接入真实模板注册表", "补充权限与审计校验"],
}

def seed(root=BASE):
    root.mkdir(parents=True, exist_ok=True)
    (root / "registry.json").write_text(json.dumps({"weekly_demo": {"enabled": True, "name": "周报演示模板", "version": 1}}, ensure_ascii=False, indent=2), encoding="utf-8")
    (root / "demo-data.json").write_text(json.dumps(DATA, ensure_ascii=False, indent=2), encoding="utf-8")
    return root

def execute(root=BASE, script_id=SCRIPT_ID):
    registry = root / "registry.json"
    if not registry.exists():
        raise RuntimeError("ReportTemplateNotFoundOrDisabled")
    templates = json.loads(registry.read_text(encoding="utf-8"))
    key = script_id.removeprefix("report_template:")
    if key not in templates or not templates[key].get("enabled"):
        raise RuntimeError("ReportTemplateNotFoundOrDisabled")
    data = json.loads((root / "demo-data.json").read_text(encoding="utf-8"))
    md = "\n".join([
        f"# 周报（{data['period']}）", "",
        "## 项目进展", *[f"- {x}" for x in data["project_progress"]], "",
        "## 指标摘要", *[f"- {k}：{v}" for k, v in data["metrics"].items()], "",
        "## 风险", *[f"- {x}" for x in data["risks"]], "",
        "## 下周计划", *[f"- {x}" for x in data["next_week"]], "",
    ])
    html = "<article><h1>周报（{}</h1><h2>项目进展</h2><ul>{}</ul><h2>指标摘要</h2><ul>{}</ul><h2>风险</h2><ul>{}</ul><h2>下周计划</h2><ul>{}</ul></article>".format(
        data["period"], ''.join(f'<li>{x}</li>' for x in data['project_progress']), ''.join(f'<li>{k}：{v}</li>' for k,v in data['metrics'].items()), ''.join(f'<li>{x}</li>' for x in data['risks']), ''.join(f'<li>{x}</li>' for x in data['next_week']))
    (root / "weekly-report.md").write_text(md, encoding="utf-8")
    (root / "weekly-report.html").write_text(html, encoding="utf-8")
    result = {"status": "success", "scriptId": script_id, "template": key, "outputs": ["weekly-report.md", "weekly-report.html"], "generatedAt": date.today().isoformat()}
    (root / "execution-result.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    return result

def verify(root=BASE):
    result = json.loads((root / "execution-result.json").read_text(encoding="utf-8"))
    report = (root / "weekly-report.md").read_text(encoding="utf-8")
    assert result["status"] == "success" and result["template"] == "weekly_demo"
    for heading in ("项目进展", "指标摘要", "风险", "下周计划"):
        assert f"## {heading}" in report
    return {"status": "verified", "sections": 4}

def reset(root=BASE):
    if root.exists(): shutil.rmtree(root)

def main(argv=None):
    op = (argv or sys.argv[1:] or ["demo"])[0]
    if op == "seed": seed()
    elif op == "exec": print(json.dumps(execute(), ensure_ascii=False, indent=2))
    elif op == "verify": print(json.dumps(verify(), ensure_ascii=False, indent=2))
    elif op == "reset": reset()
    elif op == "demo": seed(); print(json.dumps(execute(), ensure_ascii=False, indent=2)); print(json.dumps(verify(), ensure_ascii=False, indent=2))
    else: raise SystemExit(f"unknown operation: {op}")

if __name__ == "__main__": main()
