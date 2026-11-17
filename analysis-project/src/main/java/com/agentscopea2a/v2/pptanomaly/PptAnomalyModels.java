package com.agentscopea2a.v2.pptanomaly;

import java.time.LocalDateTime;
import java.util.List;

/** 例会材料接口数据模型，承载批次、版本、事项、反馈及页面信息。 */
public final class PptAnomalyModels {
    /** 保留接口数据模型容器的私有构造入口。 */
    private PptAnomalyModels() {}

    /** 材料批次及其创建用户和创建时间。 */
    public record Batch(long id, String name, String createdBy, LocalDateTime createdAt) {}
    /** 批次最新版本、文件及识别状态的列表摘要。 */
    public record BatchSummary(long id, String name, Long versionId, Integer versionNo,
                               String fileName, String status, LocalDateTime uploadedAt, String fileNames,
                               String uploadedBy) {}
    /** 同一批次下的一次独立上传版本。 */
    public record Version(long id, long batchId, int versionNo, String versionName, String fileName,
                          String status, LocalDateTime uploadedAt, String uploadedBy) {}
    /** 反馈事项及当前用户是否可编辑的展示数据。 */
    public record Finding(long id, int sequenceNo, int pageNo, String title, String chapter,
                          String sourceText, String anomalyKeyword, List<String> departments,
                          String ownerUserId, String status, String relation, boolean editable) {}
    /** 材料页面及其预览和识别模式信息。 */
    public record Slide(long id, int pageNo, String title, String imageUrl, String renderStatus,
                        String recognitionType) {}
    /** 用户提交的反馈判断及说明内容。 */
    public record FeedbackRequest(String judgement, String explanation, String baseRevision) {
        public FeedbackRequest(String judgement, String explanation) {
            this(judgement, explanation, null);
        }
    }
    /** 材料明细链接及其标题、板块和页码定位信息。 */
    public record DetailLink(long id, String aliasName, String targetUrl, String pptTitle,
                             String chapter, Integer pageNo, boolean enabled) {}
}
