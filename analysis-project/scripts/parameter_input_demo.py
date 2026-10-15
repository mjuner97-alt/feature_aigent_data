"""Read script_exec JSON from stdin and render deterministic mock data; no database needed."""
import json
import sys
from datetime import date
from html import escape


def render(params):
    report_date = params.get('report_date', date.today().isoformat())
    date.fromisoformat(report_date)
    regions = params.get('regions', ['华东', '华南'])
    quantity = params.get('quantity', 10)
    if not isinstance(regions, list) or not regions or any(
            not isinstance(region, str) or not region.strip() for region in regions):
        raise ValueError('regions 必须是非空字符串数组')
    if type(quantity) is not int or not 1 <= quantity <= 10000:
        raise ValueError('quantity 必须是 1 到 10000 的整数')
    rows = ''.join(f'<tr><td>{escape(region)}</td><td>{quantity}</td>'
                   f'<td>{quantity * (100 + index * 20)}</td></tr>'
                   for index, region in enumerate(regions))
    return ('<!DOCTYPE html><html><head><meta charset="utf-8"><title>脚本参数演示</title></head>'
            f'<body><h2>脚本参数演示 — {escape(report_date)}</h2>'
            '<p>以下为模拟数据，仅用于验证流程参数输入。</p>'
            '<table><thead><tr><th>地区</th><th>数量</th><th>模拟金额</th></tr></thead>'
            f'<tbody>{rows}</tbody></table></body></html>')


def main():
    sys.stdin.reconfigure(encoding='utf-8')
    sys.stdout.reconfigure(encoding='utf-8')
    sys.stderr.reconfigure(encoding='utf-8')
    try:
        params = json.loads(sys.stdin.read() or '{}')
        if not isinstance(params, dict):
            raise ValueError('参数必须是 JSON 对象')
        print(render(params))
    except (ValueError, TypeError) as error:
        print(f'参数错误：{error}', file=sys.stderr)
        raise SystemExit(1)


if __name__ == '__main__':
    main()
