package com.agentscopea2a.v2.presentation;

import com.agentscopea2a.entity.PresentationTemplateEntry;
import com.agentscopea2a.mapper.gauss.PresentationTemplateMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** CRUD service for user-maintained presentation templates. */
@Service
public class PresentationTemplateManageService {
    private static final Pattern TEMPLATE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_./-]{0,159}");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final PresentationTemplateMapper mapper;
    private final RegisteredPresentationTemplateRenderer renderer;

    public PresentationTemplateManageService(PresentationTemplateMapper mapper,
                                             RegisteredPresentationTemplateRenderer renderer) {
        this.mapper = mapper;
        this.renderer = renderer;
    }

    public List<PresentationTemplateEntry> list() {
        return mapper.selectAll();
    }

    public PresentationTemplateEntry get(Long id) {
        return mapper.selectById(id);
    }

    @Transactional("gaussCustomerTransactionManager")
    public PresentationTemplateEntry create(PresentationTemplateEntry entry, String userId) {
        normalizeAndValidate(entry);
        if (mapper.countByTemplateId(entry.getTemplateId()) > 0) {
            throw new IllegalArgumentException("template_id '" + entry.getTemplateId() + "' 已存在");
        }
        entry.setCreatedBy(userId);
        if (entry.getEnabled() == null) entry.setEnabled(1);
        mapper.insert(entry);
        return entry;
    }

    @Transactional("gaussCustomerTransactionManager")
    public PresentationTemplateEntry update(Long id, PresentationTemplateEntry patch) {
        PresentationTemplateEntry existing = mapper.selectById(id);
        if (existing == null) throw new IllegalArgumentException("记录不存在: id=" + id);
        if (patch.getTemplateId() != null && !patch.getTemplateId().equals(existing.getTemplateId())
                && mapper.countByTemplateId(patch.getTemplateId()) > 0) {
            throw new IllegalArgumentException("template_id '" + patch.getTemplateId() + "' 已存在");
        }
        if (patch.getTemplateId() != null) existing.setTemplateId(patch.getTemplateId());
        if (patch.getName() != null) existing.setName(patch.getName());
        if (patch.getDescription() != null) existing.setDescription(patch.getDescription());
        if (patch.getEchartsTemplate() != null) existing.setEchartsTemplate(patch.getEchartsTemplate());
        if (patch.getHtmlTemplate() != null) existing.setHtmlTemplate(patch.getHtmlTemplate());
        if (patch.getVariableSchema() != null) existing.setVariableSchema(patch.getVariableSchema());
        if (patch.getDataProviderType() != null) existing.setDataProviderType(patch.getDataProviderType());
        if (patch.getDataProviderId() != null) existing.setDataProviderId(patch.getDataProviderId());
        if (patch.getDataAdapter() != null) existing.setDataAdapter(patch.getDataAdapter());
        if (patch.getParameterMapping() != null) existing.setParameterMapping(patch.getParameterMapping());
        if (patch.getEnabled() != null) existing.setEnabled(patch.getEnabled());
        normalizeAndValidate(existing);
        mapper.update(existing);
        return existing;
    }

    @Transactional("gaussCustomerTransactionManager")
    public void delete(Long id) {
        if (mapper.selectById(id) == null) throw new IllegalArgumentException("记录不存在: id=" + id);
        mapper.deleteById(id);
    }

    private void normalizeAndValidate(PresentationTemplateEntry entry) {
        if (entry == null) throw new IllegalArgumentException("请求体不能为空");
        if (entry.getTemplateId() != null) entry.setTemplateId(entry.getTemplateId().trim());
        if (entry.getName() != null) entry.setName(entry.getName().trim());
        if (entry.getVariableSchema() == null || entry.getVariableSchema().isBlank()) {
            entry.setVariableSchema("[]");
        }
        if (entry.getDataProviderType() == null || entry.getDataProviderType().isBlank()) {
            entry.setDataProviderType("inline");
        } else {
            entry.setDataProviderType(entry.getDataProviderType().trim().toLowerCase());
        }
        if (!Set.of("inline", "sql").contains(entry.getDataProviderType())) {
            throw new IllegalArgumentException("data_provider_type 仅支持 inline 或 sql");
        }
        if ("sql".equals(entry.getDataProviderType())
                && (entry.getDataProviderId() == null || entry.getDataProviderId().isBlank())) {
            throw new IllegalArgumentException("data_provider_type=sql 时 data_provider_id 必填");
        }
        if (entry.getParameterMapping() == null || entry.getParameterMapping().isBlank()) {
            entry.setParameterMapping("{}");
        }
        try {
            JsonNode mapping = MAPPER.readTree(entry.getParameterMapping());
            if (!mapping.isObject()) throw new IllegalArgumentException("parameter_mapping 必须是 JSON 对象");
            mapping.fields().forEachRemaining(e -> {
                if (!e.getValue().isTextual() || e.getValue().asText().isBlank()) {
                    throw new IllegalArgumentException("parameter_mapping 的值必须是非空参数名");
                }
            });
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("parameter_mapping 不是合法 JSON: " + e.getMessage(), e);
        }
        if (entry.getTemplateId() == null || !TEMPLATE_ID.matcher(entry.getTemplateId()).matches()) {
            throw new IllegalArgumentException("template_id 仅允许字母、数字、_、-、.、/，长度不超过 160");
        }
        if (entry.getEnabled() != null && entry.getEnabled() != 0 && entry.getEnabled() != 1) {
            throw new IllegalArgumentException("enabled 只能是 0 或 1");
        }
        renderer.validateDefinition(entry);
    }
}
