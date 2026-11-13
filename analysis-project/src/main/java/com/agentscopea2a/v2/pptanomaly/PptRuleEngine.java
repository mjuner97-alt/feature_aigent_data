package com.agentscopea2a.v2.pptanomaly;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

@Service
public class PptRuleEngine {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final List<String> anomalyKeywords;
    private final List<String> negationKeywords;

    public PptRuleEngine(JdbcTemplate jdbc, ObjectMapper mapper,
                         @Value("${harness.a2a.ppt-anomaly.anomaly-keywords:不达标,超标,逾期,负增长,异常}") String anomalyKeywords,
                         @Value("${harness.a2a.ppt-anomaly.negation-keywords:无,未,没有,已消除,不再}") String negationKeywords) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.anomalyKeywords = split(anomalyKeywords);
        this.negationKeywords = split(negationKeywords);
    }

    public int analyze(long versionId) {
        jdbc.update("DELETE FROM digital_qa_ppt_finding WHERE version_id=?", versionId);
        List<SlideRow> slides = jdbc.query("SELECT id,page_no,title,extracted_json FROM digital_qa_ppt_slide WHERE version_id=? ORDER BY page_no",
                (rs,n)->new SlideRow(rs.getLong(1),rs.getInt(2),rs.getString(3),rs.getString(4)), versionId);
        int sequence = 0;
        for (SlideRow slide : slides) {
            List<String> texts = read(slide.texts());
            for (String text : texts) {
                String keyword = find(text, anomalyKeywords);
                if (keyword == null || negated(text, keyword)) continue;
                sequence++;
                List<String> departments = findDepartments(text);
                String fingerprint = fingerprint(slide.title() + "|" + keyword + "|" + text);
                jdbc.update("INSERT INTO digital_qa_ppt_finding(version_id,slide_id,sequence_no,title,source_text,anomaly_keyword,auto_departments,status,content_fingerprint) VALUES (?,?,?,?,?,?,?,?,?)",
                        versionId, slide.id(), sequence, slide.title(), text, keyword, write(departments), departments.isEmpty()?"UNASSIGNED":"PENDING_FEEDBACK", fingerprint);
            }
        }
        reconcilePrevious(versionId);
        jdbc.update("UPDATE digital_qa_ppt_material_version SET status='ANALYZED' WHERE id=?", versionId);
        return sequence;
    }

    private void reconcilePrevious(long versionId) {
        Long previous = jdbc.query("SELECT previous.id FROM digital_qa_ppt_material_version current JOIN digital_qa_ppt_material_version previous ON previous.batch_id=current.batch_id AND previous.version_no=current.version_no-1 WHERE current.id=?",
                rs -> rs.next() ? rs.getLong(1) : null, versionId);
        if (previous == null) return;
        jdbc.update("UPDATE digital_qa_ppt_finding current SET version_relation=CASE WHEN EXISTS (SELECT 1 FROM digital_qa_ppt_finding previous WHERE previous.version_id=? AND previous.content_fingerprint=current.content_fingerprint) THEN 'CONTINUED' ELSE 'CHANGED' END, status=CASE WHEN EXISTS (SELECT 1 FROM digital_qa_ppt_finding previous WHERE previous.version_id=? AND previous.content_fingerprint=current.content_fingerprint AND previous.status IN ('CONFIRMED','ADJUSTED')) THEN previous.status ELSE current.status END FROM digital_qa_ppt_finding previous WHERE current.version_id=? AND previous.version_id=? AND previous.content_fingerprint=current.content_fingerprint",
                previous, previous, versionId, previous);
    }

    private boolean negated(String text, String keyword) {
        int index=text.indexOf(keyword); if(index<0)return false;
        String prefix=text.substring(Math.max(0,index-8), index);
        return negationKeywords.stream().anyMatch(prefix::contains);
    }
    private List<String> findDepartments(String text) {
        List<String> result=new ArrayList<>();
        for(String department:List.of("一部","二部","三部","四部")) if(text.contains(department)) result.add(department);
        return result;
    }
    private String find(String text,List<String> words){return words.stream().filter(text::contains).sorted((a,b)->Integer.compare(b.length(),a.length())).findFirst().orElse(null);}
    private List<String> read(String json){try{return mapper.readValue(json,new TypeReference<>(){});}catch(Exception e){return List.of();}}
    private String write(Object value){try{return mapper.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}}
    private static List<String> split(String value){return java.util.Arrays.stream(value.split(",")).map(String::trim).filter(s->!s.isBlank()).toList();}
    private static String fingerprint(String value){try{byte[] d=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));StringBuilder b=new StringBuilder();for(byte x:d)b.append(String.format("%02x",x));return b.toString();}catch(Exception e){throw new IllegalStateException(e);}}
    private record SlideRow(long id,int page,String title,String texts){}
}


