package com.agentscopea2a.v2.pptanomaly;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.util.Units;
import org.apache.poi.xwpf.usermodel.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STMerge;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 例会反馈 Word 导出器，将页面预览、富文本说明、表格及附件图片写入文档。 */
@Component
@ConditionalOnProperty(prefix = "spring.datasource.hikari.gauss-customer", name = "enabled", havingValue = "true")
public class PptResultWordWriter {
    private static final String FILE_PREFIX = "ppt-feedback-files:v1\n";
    private static final String HTML_PREFIX = "ppt-feedback-html:v1\n";
    private static final String FEEDBACK_BLUE = "1D4ED8";
    private final PptAnomalyService service;
    private final ObjectMapper mapper;

    /** 装配原页和附件读取服务及反馈 JSON 解析依赖。 */
    public PptResultWordWriter(PptAnomalyService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    /** 将批次反馈汇总导出为包含预览图片、反馈说明及附件的 Word 字节流。 */
    public byte[] write(PptResultService.Report report, String userId) {
        try (var document = new XWPFDocument(); var output = new ByteArrayOutputStream()) {
            document.createStyles();
            var section = document.getDocument().getBody().addNewSectPr();
            var size = section.addNewPgSz();
            size.setW(BigInteger.valueOf(11906));
            size.setH(BigInteger.valueOf(16838));
            var margins = section.addNewPgMar();
            margins.setTop(BigInteger.valueOf(1080));
            margins.setBottom(BigInteger.valueOf(1080));
            margins.setLeft(BigInteger.valueOf(1080));
            margins.setRight(BigInteger.valueOf(1080));
            String documentTitle = report.fileName().replaceFirst("(?i)\\.pptx$", "");
            heading(document, documentTitle, 20).setAlignment(ParagraphAlignment.CENTER);
            var originalTitles = service.originalSlideTitlesByPage(report.versionId(),
                    report.pages().stream().map(PptResultService.Page::pageNo).toList());
            for (var page : report.pages()) {
                var title = document.createParagraph();
                title.setSpacingBefore(240);
                title.setKeepNext(true);
                var run = title.createRun();
                font(run);
                run.setBold(true);
                run.setFontSize(15);
                String originalTitle = label(originalTitles.get(page.pageNo()), label(page.title(), "未命名页面"));
                String titleWithoutNumber = originalTitle.replaceFirst(
                        "^\\s*[0-9]+(?:[.．。\\-－–—][0-9]+)+[.．。、\\s]*", "").strip();
                run.setText("P" + page.pageNo() + " · " + label(titleWithoutNumber, originalTitle));
                var handlerLine = document.createParagraph();
                handlerLine.setSpacingAfter(120);
                handlerLine.setKeepNext(true);
                text(handlerLine, "经办人：" + handlers(page.handlers()));
                try (var input = service.slidePreview(report.versionId(), page.pageNo(), userId, true).getInputStream()) {
                    picture(document.createParagraph(), input.readAllBytes(), "P" + page.pageNo(), 470);
                }
                if (!page.items().isEmpty()) {
                    heading(document, "补充说明：", 12).getRuns().get(0).setColor(FEEDBACK_BLUE);
                }
                for (var item : page.items()) {
                    if (item.judgement() == null) {
                        text(document.createParagraph(), "待反馈");
                    } else {
                        feedback(document, item, userId);
                    }
                }
            }
            if (report.pages().isEmpty()) text(document.createParagraph(), "该版本暂无需反馈的事项");
            document.write(output);
            return output.toByteArray();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "生成 Word 失败，请检查原始 PPT 和反馈图片后重试", e);
        }
    }

    /** 解包反馈说明和附件索引，将富文本及图片写入 Word。 */
    private void feedback(XWPFDocument document, PptResultService.Item item, String userId) throws IOException {
        String value = item.explanation() == null ? "" : item.explanation();
        List<String> files = List.of();
        // 先拆出附件索引，再按说明版本标记处理富文本或历史纯文字。
        if (value.startsWith(FILE_PREFIX)) {
            var content = mapper.readTree(value.substring(FILE_PREFIX.length()));
            value = content.path("explanation").asText();
            files = mapper.convertValue(content.path("files"), new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
        }
        var context = new Images(item.findingId(), userId, files, new HashSet<>());
        if (value.startsWith(HTML_PREFIX)) {
            var body = Jsoup.parseBodyFragment(value.substring(HTML_PREFIX.length())).body();
            body.select("script,style,iframe,object,svg").remove();
            blocks(document, body.childNodes(), context);
        } else {
            text(document.createParagraph(), label(value, "已反馈，未填写说明")).setColor(FEEDBACK_BLUE);
        }
        for (int index = 0; index < files.size(); index++) {
            if (!context.used().contains(index)) attachment(document.createParagraph(), context, index, 470);
        }
    }

    /** 递归将反馈 HTML 的块级内容转换为 Word 段落及表格。 */
    private void blocks(XWPFDocument document, List<Node> nodes, Images images) throws IOException {
        XWPFParagraph inline = null;
        for (var node : nodes) {
            if (node instanceof Element element && element.tagName().equals("table")) {
                table(document, element, images);
                inline = null;
            } else if (node instanceof Element element && Set.of("div", "ul", "ol").contains(element.tagName())) {
                blocks(document, element.childNodes(), images);
                inline = null;
            } else if (node instanceof Element element && Set.of("p", "li").contains(element.tagName())) {
                var paragraph = document.createParagraph();
                if (element.tagName().equals("li")) text(paragraph, "• ").setColor(FEEDBACK_BLUE);
                inline(paragraph, element, images, false, false, false, 470);
                inline = null;
            } else if (!(node instanceof TextNode textNode && textNode.isBlank())) {
                if (inline == null) inline = document.createParagraph();
                inline(inline, node, images, false, false, false, 470);
            }
        }
    }

    /** Word 表格跨行单元格的结束行及列跨度。 */
    private record Span(int lastRow, int columns) {}

    /** 将反馈 HTML 表格转换为 Word 表格并处理跨行跨列单元格。 */
    private void table(XWPFDocument document, Element html, Images images) throws IOException {
        var rows = html.select("tr").stream().filter(row -> row.closest("table") == html).toList();
        if (rows.isEmpty()) return;
        var table = document.createTable();
        table.setWidth("100%");
        table.removeRow(0);
        // 记录跨行单元格占用的列，后续行继续写入对应 Word 合并标记。
        Map<Integer, Span> spans = new HashMap<>();
        int totalColumns = 0;
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            var row = table.createRow();
            while (!row.getTableCells().isEmpty()) row.removeCell(0);
            var cells = rows.get(rowIndex).children().stream()
                    .filter(cell -> Set.of("td", "th").contains(cell.tagName())).toList();
            int column = 0;
            int nextCell = 0;
            while (nextCell < cells.size() || spans.containsKey(column)) {
                var cell = row.createCell();
                var properties = cell.getCTTc().addNewTcPr();
                var span = spans.get(column);
                int width;
                if (span != null) {
                    width = span.columns();
                    properties.addNewVMerge().setVal(STMerge.CONTINUE);
                    if (span.lastRow() == rowIndex) spans.remove(column);
                } else {
                    var source = cells.get(nextCell++);
                    width = span(source, "colspan");
                    int height = span(source, "rowspan");
                    if (height > 1) {
                        spans.put(column, new Span(rowIndex + height - 1, width));
                        properties.addNewVMerge().setVal(STMerge.RESTART);
                    }
                    inline(cell.getParagraphs().get(0), source, images, source.tagName().equals("th"), false, false, 150);
                }
                if (width > 1) properties.addNewGridSpan().setVal(BigInteger.valueOf(width));
                column += width;
            }
            totalColumns = Math.max(totalColumns, column);
        }
        var grid = table.getCTTbl().addNewTblGrid();
        for (int column = 0; column < totalColumns; column++) {
            grid.addNewGridCol().setW(BigInteger.valueOf(9746L / totalColumns));
        }
        document.createParagraph();
    }

    /** 读取单元格合并跨度，异常属性使用一格。 */
    private int span(Element cell, String attribute) {
        String value = cell.attr(attribute);
        return value.matches("[1-9]\\d{0,2}") ? Integer.parseInt(value) : 1;
    }

    /** 递归转换文字样式、换行及图片，保留反馈内容的展示顺序。 */
    private void inline(XWPFParagraph paragraph, Node node, Images images,
                        boolean bold, boolean italic, boolean underline, int imageWidth) throws IOException {
        if (node instanceof TextNode value) {
            var run = text(paragraph, value.getWholeText());
            run.setColor(FEEDBACK_BLUE);
            run.setBold(bold);
            run.setItalic(italic);
            if (underline) run.setUnderline(UnderlinePatterns.SINGLE);
            return;
        }
        if (!(node instanceof Element element)) return;
        String tag = element.tagName();
        if (tag.equals("br")) {
            paragraph.createRun().addBreak();
            return;
        }
        if (tag.equals("img")) {
            // 编辑器图片占位按附件索引读取，附件由业务服务执行归属和权限校验。
            String slot = element.attr("data-feedback-image");
            if (slot.matches("[0-2]")) {
                attachment(paragraph, images, Integer.parseInt(slot), imageWidth);
            } else if (element.attr("src").matches("(?i)data:image/(png|jpeg|gif|webp);base64,[a-z0-9+/=]+")) {
                String source = element.attr("src");
                try {
                    picture(paragraph, Base64.getDecoder().decode(source.substring(source.indexOf(',') + 1)), "反馈图片", imageWidth);
                } catch (IllegalArgumentException e) {
                    throw new IOException("反馈图片数据无效", e);
                }
            }
            return;
        }
        for (var child : element.childNodes()) {
            inline(paragraph, child, images, bold || Set.of("b", "strong").contains(tag),
                    italic || Set.of("i", "em").contains(tag), underline || tag.equals("u"), imageWidth);
        }
        if (Set.of("p", "div", "li", "tr").contains(tag)) paragraph.createRun().addBreak();
    }

    /** Word 导出中单条反馈的附件索引及已嵌入位置。 */
    private record Images(long findingId, String userId, List<String> files, Set<Integer> used) {}

    /** 按附件索引读取授权图片并嵌入 Word，记录已使用的附件位置。 */
    private void attachment(XWPFParagraph paragraph, Images context, int index, int width) throws IOException {
        if (index >= context.files().size()) return;
        String name = context.files().get(index);
        try (var input = service.feedbackAttachment(context.findingId(), name, context.userId(), true).getInputStream()) {
            picture(paragraph, input.readAllBytes(), name, width);
        }
        context.used().add(index);
    }

    /** 等比缩放反馈图片并转换为 PNG 后嵌入 Word。 */
    private void picture(XWPFParagraph paragraph, byte[] bytes, String name, int maxWidth) throws IOException {
        var image = ImageIO.read(new ByteArrayInputStream(bytes));
        if (image == null) throw new IOException("无法读取图片 " + name);
        double scale = Math.min(1, Math.min((double) maxWidth / image.getWidth(), 500d / image.getHeight()));
        var png = new ByteArrayOutputStream();
        ImageIO.write(image, "png", png);
        try {
            paragraph.createRun().addPicture(new ByteArrayInputStream(png.toByteArray()), Document.PICTURE_TYPE_PNG, name,
                    Units.toEMU(image.getWidth() * scale), Units.toEMU(image.getHeight() * scale));
        } catch (org.apache.poi.openxml4j.exceptions.InvalidFormatException e) {
            throw new IOException("无法导出图片 " + name, e);
        }
    }

    /** 创建指定字号的 Word 标题段落。 */
    private XWPFParagraph heading(XWPFDocument document, String value, int size) {
        var paragraph = document.createParagraph();
        paragraph.setSpacingAfter(120);
        paragraph.setKeepNext(true);
        var run = text(paragraph, value);
        run.setBold(true);
        run.setFontSize(size);
        return paragraph;
    }

    /** 创建应用统一字体的 Word 文本片段。 */
    private XWPFRun text(XWPFParagraph paragraph, String value) {
        var run = paragraph.createRun();
        font(run);
        String[] lines = value.split("\\R", -1);
        for (int index = 0; index < lines.length; index++) {
            if (index > 0) run.addBreak();
            run.setText(lines[index]);
        }
        return run;
    }

    /** 设置 Word 文本片段的中文及西文字体。 */
    private void font(XWPFRun run) {
        run.setFontFamily("宋体");
        run.setFontFamily("宋体", XWPFRun.FontCharRange.eastAsia);
        run.setFontSize(11);
    }

    /** 将经办人姓名、认证号及所属部门组合为导出展示文字。 */
    private String handlers(List<PptResultService.Handler> handlers) {
        return handlers.isEmpty() ? "待分配" : String.join("、", handlers.stream().map(handler -> {
            String name = label(handler.name(), handler.userId());
            String identity = name.equals(handler.userId()) ? name : name + "（" + handler.userId() + "）";
            return handler.departments().isEmpty() ? identity : identity + "-" + String.join("、", handler.departments());
        }).toList());
    }

    /** 规范化展示文字，空值使用给定占位文本。 */
    private String label(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
