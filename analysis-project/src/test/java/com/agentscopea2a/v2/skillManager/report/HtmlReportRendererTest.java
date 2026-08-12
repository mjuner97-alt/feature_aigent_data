package com.agentscopea2a.v2.skillManager.report;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 手动验证 {@link HtmlReportRenderer}：渲染一段含 GFM 表格 + echarts 图表的 Markdown，
 * 输出到 {@code target/skill-report-sample.html}，浏览器打开即可查看样式与图表渲染效果。
 *
 * <p>不依赖 Spring 上下文：直接 new 出 renderer 并手动调 {@link HtmlReportRenderer#init()}
 * 加载 classpath 下的 echarts.min.js。
 *
 * <p>IntelliJ 里右键运行本测试，控制台会打印输出文件的绝对路径。
 */
class HtmlReportRendererTest {

    @Test
    void renderSample() throws Exception {
        HtmlReportRenderer renderer = new HtmlReportRenderer();
        renderer.init(); // 从 classpath report-assets/echarts.min.js 加载

        // 含标题/段落/表格/列表 + 两个 echarts 图表块（柱状图、饼图）的样例 Markdown
        String md = """
                # 2026 Q2 指标分析报告

                本报告由 Skill Job 自动生成，含 **表格** 与 echarts 图表，用于验证 HTML 渲染链路。

                ## 一、部门完成情况

                | 部门 | 计划 | 完成 | 完成率 |
                |------|------|------|--------|
                | 研发 | 150  | 120  | 80%    |
                | 产品 | 100  | 80   | 80%    |
                | 测试 | 70   | 60   | 86%    |
                | 运维 | 50   | 40   | 80%    |

                ```echarts
                {
                  "title": {"text": "各部门Q2完成情况", "left": "center"},
                  "tooltip": {},
                  "xAxis": {"type": "category", "data": ["研发", "产品", "测试", "运维"]},
                  "yAxis": {"type": "value"},
                  "series": [{"name": "完成数", "type": "bar", "data": [120, 80, 60, 40]}]
                }
                ```

                ## 二、任务类型分布

                - 新增技能占比最高
                - 演进与修复各占一部分

                ```echarts
                {
                  "title": {"text": "任务类型分布", "left": "center"},
                  "tooltip": {"trigger": "item"},
                  "legend": {"bottom": 0},
                  "series": [{"name": "类型", "type": "pie", "radius": "60%", "data": [{"value": 50, "name": "新增"}, {"value": 30, "name": "演进"}, {"value": 20, "name": "修复"}]}]
                }
                ```

                ## 三、结论

                整体完成率 *约 80%*，建议下季度提升测试与运维投入。详见 `行内代码` 示例。
                """;

        String html = renderer.render(md, "2026 Q2 指标分析报告");

        Path out = Paths.get("target/skill-report-sample.html");
        Files.createDirectories(out.getParent());
        Files.writeString(out, html);

        System.out.println("====================================================");
        System.out.println("HTML 报告已生成: " + out.toAbsolutePath());
        System.out.println("文件大小: " + html.length() + " bytes");
        System.out.println("浏览器打开上述路径即可查看渲染效果");
        System.out.println("====================================================");

        assertTrue(html.contains("<table"), "应包含表格");
        assertTrue(html.contains("echarts.init"), "应包含 echarts 初始化脚本");
        assertTrue(html.contains("echarts-0") && html.contains("echarts-1"), "应有两个图表占位 div");
        assertTrue(html.contains("setOption"), "应调用 setOption");
    }
}
