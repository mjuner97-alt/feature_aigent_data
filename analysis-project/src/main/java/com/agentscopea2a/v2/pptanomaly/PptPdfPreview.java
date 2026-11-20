package com.agentscopea2a.v2.pptanomaly;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/** 配套 PDF 的校验、整页渲染及内容寻址缓存，不承担 PPT 内容识别。调用方控制转换并发及访问权限。 */
final class PptPdfPreview {
    private static final long MAX_BYTES = 50L * 1024 * 1024;
    private static final int MAX_PAGES = 500;
    private static final int MAX_EDGE = 2400;

    byte[] validate(MultipartFile file, int expectedPages) {
        String name = file == null ? null : file.getOriginalFilename();
        if (file == null || file.isEmpty() || name == null || !name.toLowerCase(Locale.ROOT).endsWith(".pdf"))
            throw badRequest("请选择非空的 .pdf 预览文件");
        if (file.getSize() > MAX_BYTES) throw badRequest("预览 PDF 不能超过 50 MB");
        try {
            byte[] bytes = file.getBytes();
            if (bytes.length > MAX_BYTES) throw badRequest("预览 PDF 不能超过 50 MB");
            try (var document = open(bytes)) {
                int pages = document.getNumberOfPages();
                if (pages < 1 || pages > MAX_PAGES) throw badRequest("预览 PDF 页数须为 1 至 500 页");
                if (pages != expectedPages) throw badRequest("PDF 页数（" + pages + "）与 PPT 页数（"
                        + expectedPages + "）不一致，请上传同一份 PPT 导出的完整 PDF");
            }
            return bytes;
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "无法读取预览 PDF，请确认文件完整且未加密", e);
        }
    }

    BufferedImage render(byte[] bytes, int pageNo) throws IOException {
        try (var document = open(bytes)) {
            if (pageNo < 1 || pageNo > document.getNumberOfPages())
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "PDF 页面不存在");
            var box = document.getPage(pageNo - 1).getCropBox();
            float edge = Math.max(box.getWidth(), box.getHeight());
            if (!Float.isFinite(edge) || edge <= 0) throw badRequest("PDF 页面尺寸无效");
            var renderer = new PDFRenderer(document);
            renderer.setSubsamplingAllowed(true);
            return renderer.renderImage(pageNo - 1, MAX_EDGE / edge, ImageType.RGB);
        }
    }

    byte[] renderPng(byte[] bytes, int pageNo) throws IOException {
        var output = new ByteArrayOutputStream();
        if (!ImageIO.write(render(bytes, pageNo), "png", output)) throw new IOException("PNG encoder unavailable");
        return output.toByteArray();
    }

    Path pdfPath(Path originalPpt) {
        // 使用原件的随机文件名关联，避免版本事务回滚后复用版本号时读到旧 PDF。
        return originalPpt.resolveSibling(originalPpt.getFileName() + ".preview.pdf");
    }

    void save(Path originalPpt, byte[] bytes) throws IOException {
        writeAtomic(pdfPath(originalPpt), bytes);
    }

    Path cachedPreview(Path originalPpt, int pageNo) throws IOException {
        Path source = pdfPath(originalPpt);
        if (!Files.isRegularFile(source)) return null;
        byte[] bytes = Files.readAllBytes(source);
        String hash;
        try {
            hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        Path target = originalPpt.getParent().resolve("pdf-previews-v1").resolve(hash).resolve(pageNo + ".png");
        if (!Files.isRegularFile(target)) writeAtomic(target, renderPng(bytes, pageNo));
        return target;
    }

    private static PDDocument open(byte[] bytes) throws IOException {
        PDDocument document = Loader.loadPDF(bytes);
        if (document.isEncrypted()) {
            document.close();
            throw badRequest("不支持加密 PDF，请导出未加密的 PDF 后上传");
        }
        return document;
    }

    private static void writeAtomic(Path target, byte[] bytes) throws IOException {
        Files.createDirectories(target.getParent());
        Path temp = Files.createTempFile(target.getParent(), "pdf-preview-", ".tmp");
        try {
            Files.write(temp, bytes);
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static ResponseStatusException badRequest(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }
}
