-- User-configured ECharts/HTML templates resolved by presentation_render.
CREATE TABLE IF NOT EXISTS presentation_template_registry (
    id                BIGSERIAL    PRIMARY KEY,
    template_id       VARCHAR(160) NOT NULL,
    name              VARCHAR(256) NOT NULL,
    description       TEXT,
    echarts_template  TEXT,
    html_template     TEXT,
    variable_schema   TEXT         NOT NULL DEFAULT '[]',
    enabled           SMALLINT     NOT NULL DEFAULT 1,
    created_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by        VARCHAR(64),
    CONSTRAINT uk_presentation_template_id UNIQUE (template_id),
    CONSTRAINT ck_presentation_template_content
        CHECK (echarts_template IS NOT NULL OR html_template IS NOT NULL)
);

CREATE INDEX IF NOT EXISTS idx_presentation_template_enabled
    ON presentation_template_registry (enabled);

CREATE OR REPLACE FUNCTION fn_presentation_template_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_presentation_template_updated_at
    ON presentation_template_registry;

CREATE TRIGGER trg_presentation_template_updated_at
    BEFORE UPDATE ON presentation_template_registry
    FOR EACH ROW
    EXECUTE PROCEDURE fn_presentation_template_updated_at();

INSERT INTO presentation_template_registry
    (template_id, name, description, echarts_template, html_template,
     variable_schema, created_by)
VALUES
(
  'q2_1_by_dept_version_metrics/report-v1',
  'Q2-1 部门版本指标报告',
  '蓝绿双折线趋势图和红色多级表头部门明细。模板内容由注册表维护，Java 只负责变量绑定和安全渲染。',
  $echarts$
  {
    "title":{"text":"{{title}}","left":"center","textStyle":{"fontSize":20}},
    "tooltip":{"trigger":"axis"},
    "legend":{"data":["打分率","达标率"],"bottom":0,"textStyle":{"fontSize":14,"color":"#000000"}},
    "grid":{"left":56,"right":24,"top":64,"bottom":64,"containLabel":true},
    "xAxis":{"type":"category","name":"版本","data":"{{versions}}","axisLine":{"lineStyle":{"color":"#000000"}},"axisLabel":{"fontSize":14,"color":"#000000"},"nameTextStyle":{"fontSize":14,"color":"#000000"}},
    "yAxis":{"type":"value","name":"比例","min":0,"max":100,"axisLine":{"lineStyle":{"color":"#000000"}},"axisLabel":{"fontSize":14,"color":"#000000","formatter":"{value}%"},"nameTextStyle":{"fontSize":14,"color":"#000000"},"splitLine":{"lineStyle":{"color":"#E0E0E0"}}},
    "series":[
      {"name":"打分率","type":"line","color":"#2563EB","symbol":"circle","symbolSize":10,"lineStyle":{"color":"#2563EB","width":3},"itemStyle":{"color":"#2563EB","borderColor":"#FFFFFF","borderWidth":2},"label":{"show":true,"formatter":"{c}%","color":"#2563EB","fontWeight":"bold"},"data":"{{scoredRates}}"},
      {"name":"达标率","type":"line","color":"#16803A","symbol":"circle","symbolSize":10,"lineStyle":{"color":"#16803A","width":3,"type":"dashed"},"itemStyle":{"color":"#16803A","borderColor":"#FFFFFF","borderWidth":2},"label":{"show":true,"formatter":"{c}%","color":"#16803A","fontWeight":"bold"},"data":"{{passedRates}}"}
    ]
  }
  $echarts$,
  $html$
  <!DOCTYPE html><html lang="zh-CN"><head><meta charset="utf-8">
  <meta name="viewport" content="width=device-width,initial-scale=1">
  <style>
  .q21-section{margin:0 0 34px}
  .q21-section h2{margin:0 0 18px;padding-bottom:10px;border-bottom:2px solid #d9272e;color:#172033;font-size:22px}
  .q21-table-wrap{width:100%;overflow-x:auto}
  table.q21-table{min-width:880px;margin:0;border-collapse:collapse;font-family:Arial,"Microsoft YaHei",sans-serif;font-size:14px}
  table.q21-table th,table.q21-table td{border:1px solid #d7dce3;padding:10px 12px;text-align:center;vertical-align:middle;white-space:nowrap}
  table.q21-table thead th{background:#c00000;color:#fff;font-weight:700;text-align:center}
  table.q21-table tbody tr:nth-child(even){background:#fff7f7}
  table.q21-table tbody tr:hover{background:#fff0f0}
  table.q21-table td:first-child{text-align:left}
  table.q21-table .q21-total td{background:#f3f4f6;color:#172033;font-weight:700}
  .q21-source{margin-top:18px;color:#5b6472;font-size:13px}
  .echarts-chart{height:440px}
  </style></head><body>
  <section class="q21-section"><h2>ECharts 图表</h2>{{@echarts}}</section>
  <section class="q21-section"><h2>HTML 明细表</h2>
  <div class="q21-table-wrap"><table class="q21-table">
  <thead><tr><th rowspan="2">开发部门</th><th rowspan="2">版本</th><th colspan="3">Q2-1 统计</th><th colspan="2">比例</th></tr>
  <tr><th>总数</th><th>已打分</th><th>达标数</th><th>打分率</th><th>达标率</th></tr></thead>
  <tbody>{{#records}}<tr><td>{{department}}</td><td>{{version}}</td><td>{{total}}</td><td>{{scored}}</td><td>{{passed}}</td><td>{{scoredPctText}}</td><td>{{passedPctText}}</td></tr>{{/records}}
  {{#summary}}<tr class="q21-total"><td>{{department}}</td><td>{{version}}</td><td>{{total}}</td><td>{{scored}}</td><td>{{passed}}</td><td>{{scoredPctText}}</td><td>{{passedPctText}}</td></tr>{{/summary}}
  </tbody></table></div>
  <div class="q21-source">数据来源：{{dataSource}}。{{#dataDate}}统计日期：{{dataDate}}。{{/dataDate}}</div>
  </section></body></html>
  $html$,
  $schema$
  [
    {"name":"title","type":"string","required":true},
    {"name":"versions","type":"array","required":true},
    {"name":"scoredRates","type":"array","required":true},
    {"name":"passedRates","type":"array","required":true},
    {"name":"records","type":"array","required":true},
    {"name":"summary","type":"object","required":false},
    {"name":"dataSource","type":"string","required":true},
    {"name":"dataDate","type":"string","required":false}
  ]
  $schema$,
  'flyway'
)
ON DUPLICATE KEY UPDATE
    name = VALUES(name),
    description = VALUES(description),
    echarts_template = VALUES(echarts_template),
    html_template = VALUES(html_template),
    variable_schema = VALUES(variable_schema);
