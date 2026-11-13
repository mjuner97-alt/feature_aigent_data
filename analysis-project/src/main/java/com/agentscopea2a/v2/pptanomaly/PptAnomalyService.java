package com.agentscopea2a.v2.pptanomaly;

import com.agentscopea2a.v2.pptanomaly.PptAnomalyModels.Batch;
import com.agentscopea2a.v2.pptanomaly.PptAnomalyModels.FeedbackRequest;
import com.agentscopea2a.v2.pptanomaly.PptAnomalyModels.Finding;
import com.agentscopea2a.v2.pptanomaly.PptAnomalyModels.Slide;
import com.agentscopea2a.v2.pptanomaly.PptAnomalyModels.Version;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTable;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class PptAnomalyService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Path storageDir;

    public PptAnomalyService(JdbcTemplate jdbc, ObjectMapper mapper,
                             @Value("${harness.a2a.ppt-anomaly.storage-dir:./data/ppt-anomaly}") String storageDir) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.storageDir = Path.of(storageDir).toAbsolutePath().normalize();
    }

    @Transactional
    public Batch createBatch(String name, String userId) {
        long id = insertKey("INSERT INTO digital_qa_ppt_material_batch(name, created_by) VALUES (?, ?)", name, userId);
        return new Batch(id, name, userId, LocalDateTime.now());
    }

    @Transactional
    public Version uploadVersion(long batchId, MultipartFile file, String versionName, String userId) {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("PPT 文件不能为空");
        try {
            byte[] bytes = file.getBytes();
            String hash = sha256(bytes);
            Integer duplicate = jdbc.query("SELECT version_no FROM digital_qa_ppt_material_version WHERE batch_id=? AND file_sha256=?",
                    rs -> rs.next() ? rs.getInt(1) : null, batchId, hash);
            if (duplicate != null) throw new IllegalArgumentException("同一材料批次已上传相同 PPT");
            Integer max = jdbc.query("SELECT COALESCE(MAX(version_no),0) FROM digital_qa_ppt_material_version WHERE batch_id=?",
                    rs -> rs.next() ? rs.getInt(1) : 0, batchId);
            int versionNo = max + 1;
            String resolvedName = versionName == null || versionName.isBlank()
                    ? (versionNo == 1 ? "初稿" : "第 " + versionNo + " 版") : versionName.trim();
            Path dir = storageDir.resolve("batch-" + batchId).resolve("version-" + versionNo);
            Files.createDirectories(dir);
            Path pptx = dir.resolve(safeName(file.getOriginalFilename(), "input.pptx"));
            Files.write(pptx, bytes);
            long versionId = insertKey("INSERT INTO digital_qa_ppt_material_version(batch_id,version_no,version_name,file_name,file_path,file_sha256,uploaded_by) VALUES (?,?,?,?,?,?,?)",
                    batchId, versionNo, resolvedName, file.getOriginalFilename(), pptx.toString(), hash, userId);
            parseSlides(versionId, pptx);
            return new Version(versionId, batchId, versionNo, resolvedName, file.getOriginalFilename(), "UPLOADED", LocalDateTime.now());
        } catch (IOException e) {
            throw new IllegalStateException("保存或解析 PPT 失败", e);
        }
    }

    public List<Version> listVersions(long batchId) {
        return jdbc.query("SELECT id,batch_id,version_no,version_name,file_name,status,uploaded_at FROM digital_qa_ppt_material_version WHERE batch_id=? ORDER BY version_no",
                (rs, n) -> new Version(rs.getLong(1), rs.getLong(2), rs.getInt(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getTimestamp(7).toLocalDateTime()), batchId);
    }

    public List<Finding> overview(long versionId, String department, String userId) {
        String sql = "SELECT id,sequence_no,(SELECT page_no FROM digital_qa_ppt_slide s WHERE s.id=f.slide_id),title,chapter,source_text,anomaly_keyword,auto_departments,owner_user_id,status,version_relation FROM digital_qa_ppt_finding f WHERE version_id=? ORDER BY sequence_no";
        List<Finding> all = jdbc.query(sql, (rs, n) -> finding(rs, department, userId), versionId);
        if (department == null || department.isBlank()) return all;
        return all.stream().filter(f -> f.departments().contains(department)).toList();
    }

    public Slide slide(long versionId, int pageNo) {
        return jdbc.queryForObject("SELECT id,page_no,title,image_path,render_status,recognition_type FROM digital_qa_ppt_slide WHERE version_id=? AND page_no=?",
                (rs, n) -> new Slide(rs.getLong(1), rs.getInt(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6)), versionId, pageNo);
    }

    @Transactional
    public void saveFeedback(long findingId, FeedbackRequest request, String userId) {
        String owner = jdbc.queryForObject("SELECT owner_user_id FROM digital_qa_ppt_finding WHERE id=?", String.class, findingId);
        if (owner != null && !owner.isBlank() && !owner.equals(userId)) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN, "当前用户无权反馈该异常板块");
        List<String> departments = request.adjustedDepartments() == null ? List.of() : request.adjustedDepartments();
        String json = write(departments);
        jdbc.update("INSERT INTO digital_qa_ppt_finding_feedback(finding_id,judgement,adjusted_departments,explanation,user_id) VALUES (?,?,?,?,?)",
                findingId, request.judgement(), json, request.explanation(), userId);
        jdbc.update("UPDATE digital_qa_ppt_finding SET status=? WHERE id=?", "CONFIRMED".equalsIgnoreCase(request.judgement()) ? "CONFIRMED" : "ADJUSTED", findingId);
    }

    private Finding finding(java.sql.ResultSet rs, String department, String userId) throws java.sql.SQLException {
        List<String> deps = readList(rs.getString(8));
        return new Finding(rs.getLong(1), rs.getInt(2), rs.getInt(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), deps,
                rs.getString(9), rs.getString(10), rs.getString(11), rs.getString(9) == null || rs.getString(9).isBlank() || Objects.equals(rs.getString(9), userId));
    }

    private void parseSlides(long versionId, Path pptx) throws IOException {
        try (InputStream input = Files.newInputStream(pptx); XMLSlideShow show = new XMLSlideShow(input)) {
            int page = 0;
            for (XSLFSlide slide : show.getSlides()) {
                page++;
                List<String> texts = new ArrayList<>();
                for (XSLFShape shape : slide.getShapes()) {
                    if (shape instanceof XSLFTextShape textShape && textShape.getText() != null && !textShape.getText().isBlank()) texts.add(textShape.getText().trim());
                    if (shape instanceof XSLFTable table) for (var row : table.getRows()) texts.add(row.getCells().stream().map(cell -> cell.getText() == null ? "" : cell.getText().trim()).reduce((a,b) -> a + " | " + b).orElse(""));
                }
                String title = texts.isEmpty() ? "" : texts.get(0);
                String json = write(texts);
                jdbc.update("INSERT INTO digital_qa_ppt_slide(version_id,page_no,title,extracted_json,render_status,recognition_type) VALUES (?,?,?,?,?,?)",
                        versionId, page, title, json, "UNAVAILABLE", "PENDING");
            }
        }
    }

    private long insertKey(String sql, Object... args) {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            return ps;
        }, holder);
        Number key = holder.getKey();
        if (key == null) throw new IllegalStateException("数据库未返回主键");
        return key.longValue();
    }

    private String write(Object value) { try { return mapper.writeValueAsString(value); } catch (Exception e) { throw new IllegalStateException(e); } }
    private List<String> readList(String value) { try { return value == null ? List.of() : mapper.readValue(value, new TypeReference<>() {}); } catch (Exception e) { return List.of(); } }
    private static String sha256(byte[] bytes) { try { byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes); StringBuilder b = new StringBuilder(); for (byte x : digest) b.append(String.format("%02x", x)); return b.toString(); } catch (Exception e) { throw new IllegalStateException(e); } }
    private static String safeName(String value, String fallback) { String name = value == null || value.isBlank() ? fallback : Path.of(value).getFileName().toString(); return name.replaceAll("[^\\p{L}\\p{N}._-]", "_"); }
}


