package com.agentscopea2a.v2.pptanomaly;

import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.agentscopea2a.mapper.gauss.PptAnomalyMapper;
import com.agentscopea2a.v2.auth.controller.AuthController;
import com.agentscopea2a.v2.auth.mapper.DeveloperPlPersonInfoMapper;
import org.springframework.dao.DuplicateKeyException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Comparator;
import java.time.LocalDateTime;
import com.agentscopea2a.config.datasource.GaussConfig;
import org.springframework.transaction.annotation.Transactional;
import org.apache.ibatis.annotations.AutomapConstructor;
import java.util.Set;

/** 例会材料配置接口，提供材料类型、板块、标题和部门关键字的查询及维护。查询要求登录，写入按配置类别校验。 */
@RestController
@RequestMapping("/api/ppt-anomaly/keyword-config")
@ConditionalOnProperty(prefix = "spring.datasource.hikari.gauss-customer", name = "enabled", havingValue = "true")
public class PptKeywordConfigController {
    private static final Set<String> KINDS = Set.of("material-type", "department", "scope", "section", "contact");
    private static final Set<String> MODES = PptAnomalyService.CONFIGURABLE_PAGE_MODES;
    private static final String KEYWORD_HEADER = "匹配关键词";
    private static final String DEPARTMENT_HEADER = "部门";
    private static final int CONFIG_NAME_LIMIT = 512;
    private static final int CONFIG_TARGET_LIMIT = 2048;
    private final PptAnomalyMapper persistence;
    private final DeveloperPlPersonInfoMapper people;
    private final PptFeedbackNotifier notifier;
    private final PptAnomalyService service;

    /** 装配标题配置、部门权限及通知补发依赖。 */
    public PptKeywordConfigController(PptAnomalyMapper persistence, DeveloperPlPersonInfoMapper people,
                                      PptFeedbackNotifier notifier, PptAnomalyService service) {
        this.persistence = persistence;
        this.people = people;
        this.notifier = notifier;
        this.service = service;
    }

    /** 配置读取对所有登录用户开放。 */
    @ModelAttribute
    public void requireUser(@SessionAttribute(name = AuthController.SESSION_USER, required = false) String userId) {
        PptAnomalyController.requireUser(userId);
    }

    /** 配置记录及其识别范围参数和维护时间。 */
    public record Entry(Long id, String name, String target, Double upperLimitRatio, Integer firstRow, Integer lastRow,
                        LocalDateTime createdAt, LocalDateTime updatedAt) {
        /** 构造配置记录，兼容包含或省略维护时间的输入。 */
        @AutomapConstructor public Entry {}
        /** 构造配置记录，兼容包含或省略维护时间的输入。 */
        public Entry(Long id, String name, String target, Double upperLimitRatio, Integer firstRow, Integer lastRow) {
            this(id, name, target, upperLimitRatio, firstRow, lastRow, null, null);
        }
    }
    /** 配置记录的分页结果。 */
    public record Page(List<Entry> items, long total, List<PptAnomalyMapper.MaterialReminderSettings> reminderSettings,
                       Map<Long, List<PptAnomalyMapper.ReminderWindow>> reminderWindows) {}
    /** 标题及其板块、解析模式和联系人组合信息。 */
    public record TitleRow(String name, Entry section, Entry scope, Entry contact, String ownerName) {}
    /** 标题配置的分页查询结果。 */
    public record TitlePage(List<TitleRow> items, long total) {}

    /** 返回标题关联配置的最近更新时间，用于列表倒序展示。 */
    private static LocalDateTime titleUpdatedAt(TitleRow row) {
        var sectionTime = row.section() == null ? null : row.section().updatedAt();
        var scopeTime = row.scope() == null ? null : row.scope().updatedAt();
        if (sectionTime == null) return scopeTime;
        if (scopeTime == null) return sectionTime;
        return sectionTime.isAfter(scopeTime) ? sectionTime : scopeTime;
    }
    /** 标题名称、板块、解析规则及联系人维护请求。 */
    public record TitleEdit(String originalName, String name, String section, String scope, String owner,
                            Double upperLimitRatio, Integer firstRow, Integer lastRow, List<String> handlerUserIds,
                            List<PptScanRule> scanRules) {}
    public record RecognitionSettings(String scope, Double upperLimitRatio, Integer firstRow, Integer lastRow,
                                      List<PptScanRule> scanRules) {}
    /** 经办人的部门、姓名及认证号展示数据。 */
    public record Handler(String department, String name, String userId) {}
    /** 一个标题明细分组及其链接集合。 */
    public record Detail(String name, List<DetailLink> links) {}
    /** 标题明细下的一条链接名称及 HTTP 地址。 */
    public record DetailLink(String name, String url) {}

    /** 读取已配置标题的扫描规则，供查看和编辑页面使用。 */
    @GetMapping("/scan-rules")
    public List<PptScanRule> scanRules(@RequestParam String title) {
        requireConfiguredTitle(title);
        return rulesForTitle(title);
    }

    public record ReminderSettings(boolean enabled, String cutoff, int intervalMinutes, Integer weeklyStartMinute,
                                   Integer weeklyEndMinute, Boolean notificationEnabled,
                                   List<PptAnomalyMapper.ReminderWindow> windows) {}

    public record MaterialTypeEdit(String name, ReminderSettings reminder, List<PptAnomalyMapper.MaterialIgnoreRule> ignoreRules) {}
    public record NotificationSwitches(Boolean enabled, Boolean notificationEnabled) {}

    @GetMapping("/material-types/{id}/feedback-reminder-settings")
    public ReminderSettings feedbackReminderSettings(@PathVariable long id) {
        var settings = persistence.feedbackReminderSettings(id);
        if (settings == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "材料类型不存在");
        return new ReminderSettings(settings.enabled(), settings.cutoff(), settings.intervalMinutes(),
                settings.weeklyStartMinute(), settings.weeklyEndMinute(), settings.notificationEnabled(),
                reminderWindows(List.of(id)).getOrDefault(id, List.of()));
    }

    @PutMapping("/material-types/{id}/feedback-reminder-settings")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional(GaussConfig.TX_NAME)
    public void saveFeedbackReminderSettings(@PathVariable long id,
            @RequestBody ReminderSettings settings,
            @SessionAttribute(name = AuthController.SESSION_USER, required = false) String editor) {
        requireConfigWrite("material-type", id, null, editor);
        saveReminderSettings(id, settings);
    }

    @PatchMapping("/material-types/{id}/feedback-reminder-settings")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updateNotificationSwitches(@PathVariable long id, @RequestBody NotificationSwitches switches,
            @SessionAttribute(name = AuthController.SESSION_USER, required = false) String editor) {
        requireConfigWrite("material-type", id, null, editor);
        if (switches.enabled() == null && switches.notificationEnabled() == null) throw bad("请选择要修改的通知开关");
        requireRow(persistence.updateNotificationSwitches(id, switches.enabled(), switches.notificationEnabled()));
    }

    @PostMapping("/material-types")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional(GaussConfig.TX_NAME)
    public void createMaterialType(@RequestBody MaterialTypeEdit body,
            @SessionAttribute(name = AuthController.SESSION_USER, required = false) String editor) {
        saveMaterialType(null, body, editor);
    }

    @PutMapping("/material-types/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional(GaussConfig.TX_NAME)
    public void updateMaterialType(@PathVariable long id, @RequestBody MaterialTypeEdit body,
            @SessionAttribute(name = AuthController.SESSION_USER, required = false) String editor) {
        saveMaterialType(id, body, editor);
    }

    private void saveMaterialType(Long id, MaterialTypeEdit body, String editor) {
        requireConfigWrite("material-type", id, null, editor);
        String name = required(body.name(), CONFIG_NAME_LIMIT);
        save("material-type", id, new Entry(id, name, name, null, null, null));
        saveReminderSettings(id == null ? persistence.materialTypeConfigId(name) : id, body.reminder());
        if (body.ignoreRules() != null) service.replaceMaterialIgnoreRules(name, body.ignoreRules());
    }

    private Map<Long, List<PptAnomalyMapper.ReminderWindow>> reminderWindows(List<Long> ids) {
        if (ids.isEmpty()) return Map.of();
        Map<Long, List<PptAnomalyMapper.ReminderWindow>> result = new HashMap<>();
        for (var row : persistence.materialReminderWindows(ids)) {
            result.computeIfAbsent(row.configId(), key -> new ArrayList<>()).add(row.window());
        }
        return result;
    }

    private void saveReminderSettings(long id, ReminderSettings settings) {
        if (settings == null) throw bad("请填写提醒设置");
        if (settings.cutoff() == null || !settings.cutoff().matches("([01][0-9]|2[0-3]):[0-5][0-9]")
                || !java.time.LocalTime.parse(settings.cutoff()).isAfter(java.time.LocalTime.of(10, 0))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "提醒截止时间须为 10:01 至 23:59");
        }
        if (settings.intervalMinutes() < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "提醒间隔须为正整数分钟");
        }
        Integer start = settings.weeklyStartMinute();
        Integer end = settings.weeklyEndMinute();
        if ((start != null || end != null)
                && (start == null || end == null || start < 0 || start >= 10080
                    || end < 0 || end >= 10080)) {
            throw bad("请选择有效的通知开始和结束时间");
        }
        if (start != null && start >= end) throw bad("开始时间必须早于结束时间");
        var windows = settings.windows();
        if (windows != null) validateReminderWindows(windows);
        requireRow(persistence.updateFeedbackReminderSettings(id, new PptAnomalyMapper.FeedbackReminderSettings(
                settings.enabled(), settings.cutoff(), settings.intervalMinutes(), start, end, settings.notificationEnabled())));
        if (windows != null) {
            persistence.deleteReminderWindows(id);
            if (!windows.isEmpty()) persistence.insertReminderWindows(id, windows);
        }
    }

    private static void validateReminderWindows(List<PptAnomalyMapper.ReminderWindow> windows) {
        for (var window : windows) {
            if (window == null || window.startMinute() == null || window.endMinute() == null) {
                throw bad("请填写完整通知时段");
            }
            int start = window.startMinute();
            int end = window.endMinute();
            int limit = window.daily() ? 1440 : 10080;
            if (start < 0 || start >= limit || end < 0 || end >= limit) {
                throw bad("请选择有效的通知开始和结束时间");
            }
            if (start >= end) throw bad("开始时间必须早于结束时间");
            if (window.daily() != windows.get(0).daily()) throw bad("请选择统一的通知周期");
        }
        var sorted = windows.stream().sorted(Comparator.comparingInt(PptAnomalyMapper.ReminderWindow::startMinute)).toList();
        for (int i = 1; i < sorted.size(); i++) {
            if (sorted.get(i).startMinute() < sorted.get(i - 1).endMinute()) throw bad("通知时段不能重叠，请调整后再保存");
        }
    }

    @GetMapping("/material-types/{materialType}/ignore-rules")
    public List<PptAnomalyMapper.MaterialIgnoreRule> materialIgnoreRules(@PathVariable String materialType) {
        return service.materialIgnoreRules(materialType);
    }

    @PutMapping("/material-types/{materialType}/ignore-rules")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void saveMaterialIgnoreRules(@PathVariable String materialType,
            @RequestBody List<PptAnomalyMapper.MaterialIgnoreRule> rules,
            @SessionAttribute(name = AuthController.SESSION_USER, required = false) String editor) {
        if (!service.canManageAllConfigurations(editor)) PptAnomalyController.requireProductDepartment(editor, people);
        service.replaceMaterialIgnoreRules(materialType, rules);
    }

    /** 读取核对页标题的识别设置，未配置的标题从整页识别开始。 */
    @GetMapping("/recognition-settings")
    public RecognitionSettings recognitionSettings(@RequestParam String title) {
        String name = required(title, CONFIG_NAME_LIMIT);
        Entry scope = entriesByName("scope").get(name);
        var rules = rulesForTitle(name);
        return scope == null || !MODES.contains(scope.target()) ? new RecognitionSettings("FULL", null, null, null, rules)
                : new RecognitionSettings(scope.target(), scope.upperLimitRatio(), scope.firstRow(), scope.lastRow(), rules);
    }

    /** 保存标题的识别设置，板块及经办人仍由各自的维护入口处理。 */
    @PutMapping("/recognition-settings")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional(GaussConfig.TX_NAME)
    public void saveRecognitionSettings(@RequestParam String title, @RequestBody RecognitionSettings body,
            @SessionAttribute(name = AuthController.SESSION_USER, required = false) String editor) {
        service.requireBoardAndTitleMaintenance(editor);
        String name = required(title, CONFIG_NAME_LIMIT);
        var rules = validateScanRules(name, body.scanRules());
        persistence.lockConfiguredTitle(name);
        Entry scope = entriesByName("scope").get(name);
        save("scope", scope == null ? null : scope.id(), new Entry(null, name, body.scope(),
                body.upperLimitRatio(), body.firstRow(), body.lastRow()));
        replaceScanRules(name, rules);
    }

    private List<PptScanRule> rulesForTitle(String title) {
        return PptScanRule.forTitle(persistence.scanRules(), title, true);
    }

    /** 校验维护权限并整体替换标题的扫描规则。 */
    @PutMapping("/scan-rules")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional(GaussConfig.TX_NAME)
    public void saveScanRules(@RequestParam String title, @RequestBody List<PptScanRule> rules,
            @SessionAttribute(name = AuthController.SESSION_USER, required = false) String editor) {
        service.requireBoardAndTitleMaintenance(editor);
        // 锁住标题后再替换规则，使校验和保存对应同一条标题配置。
        requireRow(persistence.lockConfiguredTitle(title).size());
        replaceScanRules(title, validateScanRules(title, rules));
    }

    private void replaceScanRules(String title, List<PptScanRule> rules) {
        persistence.deleteScanRules(title);
        rules.forEach(persistence::insertScanRule);
    }

    private static List<PptScanRule> validateScanRules(String title, List<PptScanRule> rules) {
        if (rules == null || rules.size() > 50) throw bad("每个标题最多配置 50 条规则");
        var names = new java.util.HashSet<String>();
        var conditions = new java.util.HashSet<String>();
        var validated = new java.util.ArrayList<PptScanRule>();
        for (var rule : rules) {
            if (rule == null || rule.mode() == null || !MODES.contains(rule.mode())) throw bad("扫描方式无效");
            String name = required(rule.name(), 255);
            String keywords = required(rule.keywords(), 8192);
            if (!names.add(name)) throw bad("规则名称不能重复");
            String key = keywords.lines().map(String::strip).filter(v -> !v.isEmpty())
                    .map(v -> v.replaceAll("[\\p{javaWhitespace}\\p{Zs}]+", "").toLowerCase(Locale.ROOT))
                    .distinct().sorted().collect(java.util.stream.Collectors.joining("\n"));
            if (key.isEmpty() || !conditions.add(key)) throw bad("关键词条件为空或重复");
            boolean upper = "UPPER_TABLE_ONLY".equals(rule.mode());
            Double ratio = upper ? rule.upperLimitRatio() : null;
            Integer first = upper ? rule.firstRow() : null;
            Integer last = upper ? rule.lastRow() : null;
            if (ratio != null && (!Double.isFinite(ratio) || ratio <= 0 || ratio > 1)
                    || first != null && first < 1 || last != null && last < (first == null ? 1 : first)) {
                throw bad("表格范围无效");
            }
            validated.add(new PptScanRule(title, name, keywords, rule.mode(), ratio, first, last));
        }
        return validated;
    }

    /** 查询指定标题下配置的明细分组及链接。 */
    @GetMapping("/title-details")
    public List<PptAnomalyMapper.TitleDetailLink> titleDetails(@RequestParam String title) {
        requireConfiguredTitle(title);
        return persistence.titleDetailLinks(title);
    }

    /** 校验维护权限、明细名称和 HTTP 链接后整体保存标题明细配置。 */
    @PutMapping("/title-details")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional(GaussConfig.TX_NAME)
    public void saveTitleDetails(@RequestParam String title, @RequestBody List<Detail> details,
            @SessionAttribute(name = AuthController.SESSION_USER, required = false) String editor) {
        service.requireBoardAndTitleMaintenance(editor);
        requireRow(persistence.lockConfiguredTitle(title).size());
        persistence.deleteTitleDetailLinks(title);
        for (int detailNo = 0; detailNo < details.size(); detailNo++) {
            var detail = details.get(detailNo);
            if (detail == null || detail.links() == null || detail.links().isEmpty()) {
                throw bad("每组明细至少需要一个链接");
            }
            String detailName = required(detail.name(), 255);
            for (int linkNo = 0; linkNo < detail.links().size(); linkNo++) {
                var link = detail.links().get(linkNo);
                if (link == null) throw bad("请填写链接名称和地址");
                String url = required(link.url(), 2048);
                java.net.URI uri;
                try {
                    uri = java.net.URI.create(url);
                } catch (IllegalArgumentException e) {
                    throw bad("请填写有效的 HTTP 或 HTTPS 地址");
                }
                if (uri.getHost() == null || uri.getRawUserInfo() != null
                        || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
                    throw bad("请填写有效的 HTTP 或 HTTPS 地址");
                }
                persistence.insertTitleDetailLink(title, linkNo, new PptAnomalyMapper.TitleDetailLink(
                        detailNo, detailName, required(link.name(), 255), url));
            }
        }
    }

    /** 读取标题、部门与经办人的配置关系。 */
    @GetMapping("/title-handlers")
    public List<Handler> titleHandlers(@RequestParam String title) {
        requireConfiguredTitle(title);
        return readTitleHandlers(title);
    }

    /** 读取标题经办配置并补充当前人员姓名。 */
    private List<Handler> readTitleHandlers(String title) {
        return persistence.titleHandlers().stream().filter(handler -> title.equals(handler.title()))
                .map(handler -> {
                    var person = service.ledgerPerson(handler.userId());
                    return new Handler(handler.department(), person == null ? handler.userId() : person.name(), handler.userId());
                }).toList();
    }

    /** 整体替换默认标题经办人，新名单用于后续导入。 */
    @PutMapping("/title-handlers")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional(GaussConfig.TX_NAME)
    public void saveTitleHandlers(@RequestParam String title, @RequestBody List<String> userIds,
            @SessionAttribute(name = AuthController.SESSION_USER, required = false) String editor) {
        service.requireBoardAndTitleMaintenance(editor);
        requireRow(persistence.lockConfiguredTitle(title).size());
        replaceTitleHandlers(title, userIds);
    }

    private void replaceTitleHandlers(String title, List<String> userIds) {
        var people = userIds.stream().distinct().map(service::ledgerPerson).toList();
        if (people.stream().anyMatch(person -> person == null || person.depts().isEmpty())) {
            throw bad("请选择带有部门的经办人");
        }
        // 默认配置与版本快照隔离，历史事项只能通过版本分派入口调整。
        persistence.deleteTitleHandlers(title);
        for (var person : people) {
            for (String department : person.depts()) {
                persistence.insertTitleHandler(title, department, person.userId());
            }
        }
    }

    /** 为标题补充默认经办人，新名单用于后续导入。 */
    @PostMapping("/title-handlers")
    @Transactional(GaussConfig.TX_NAME)
    public List<Handler> addTitleHandler(@RequestParam String title, @RequestParam String userId,
            @SessionAttribute(name = AuthController.SESSION_USER, required = false) String editor) {
        service.requireBoardAndTitleMaintenance(editor);
        requireRow(persistence.lockConfiguredTitle(title).size());
        var person = service.ledgerPerson(userId);
        if (person == null || person.depts().isEmpty()) throw bad("请选择带有部门的经办人");
        persistence.deleteTitleHandler(title, person.userId());
        for (String department : person.depts()) {
            persistence.insertTitleHandler(title, department, person.userId());
        }
        return readTitleHandlers(title);
    }

    @GetMapping("/title-handler-sources")
    public List<String> titleHandlerSources() {
        var titles = new java.util.HashSet<>(entriesByName("section").keySet());
        titles.addAll(entriesByName("scope").keySet());
        return persistence.titleHandlers().stream().map(PptAnomalyMapper.TitleHandler::title)
                .filter(titles::contains).distinct().sorted().toList();
    }

    public record ReuseHandlers(String sourceTitle) {}

    /** 将来源标题的默认经办人追加到当前标题。 */
    @PostMapping("/title-handlers/reuse")
    @Transactional(GaussConfig.TX_NAME)
    public List<Handler> reuseTitleHandlers(@RequestParam String title, @RequestBody ReuseHandlers body,
            @SessionAttribute(name = AuthController.SESSION_USER, required = false) String editor) {
        service.requireBoardAndTitleMaintenance(editor);
        String source = required(body.sourceTitle(), 512);
        if (title.equals(source)) throw bad("请选择其他标题");
        java.util.stream.Stream.of(title, source).sorted().forEach(name -> requireRow(persistence.lockConfiguredTitle(name).size()));
        var ids = persistence.titleHandlers().stream().filter(handler -> source.equals(handler.title()))
                .map(PptAnomalyMapper.TitleHandler::userId).distinct().toList();
        if (ids.isEmpty()) throw bad("来源标题尚未配置经办人");
        for (String id : ids) {
            var person = service.ledgerPerson(id);
            if (person == null || person.depts().isEmpty()) throw bad("来源标题包含无效经办人，请先更新来源配置");
            for (String department : person.depts()) persistence.insertTitleHandler(title, department, person.userId());
        }
        return readTitleHandlers(title);
    }

    /** 移除指定标题下的默认经办人关系。 */
    @DeleteMapping("/title-handlers")
    @Transactional(GaussConfig.TX_NAME)
    public List<Handler> removeTitleHandler(@RequestParam String title, @RequestParam String userId,
            @SessionAttribute(name = AuthController.SESSION_USER, required = false) String editor) {
        service.requireBoardAndTitleMaintenance(editor);
        requireRow(persistence.lockConfiguredTitle(title).size());
        persistence.deleteTitleHandler(title, required(userId, 255));
        return readTitleHandlers(title);
    }

    /** 校验标题存在于标题归属或解析范围配置中。 */
    private void requireConfiguredTitle(String title) {
        if (!entriesByName("section").containsKey(title) && !entriesByName("scope").containsKey(title)) requireRow(0);
    }

    /** 整体维护标题名称、板块、解析范围及关联配置。 */
    @PostMapping("/titles")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional(GaussConfig.TX_NAME)
    public void saveTitle(@RequestBody TitleEdit body,
            @SessionAttribute(name = AuthController.SESSION_USER, required = false) String editor) {
        service.requireBoardAndTitleMaintenance(editor);
        String name = required(body.name(), 512);
        String section = required(body.section(), 512);
        var rules = body.scanRules() == null ? null : validateScanRules(name, body.scanRules());
        if (!sections().contains(section)) throw bad("请选择已配置的板块");
        String original = body.originalName();
        if (original != null) requireRow(persistence.lockConfiguredTitle(original).size());
        Entry oldSection = entriesByName("section").get(original == null ? name : original);
        Entry oldScope = entriesByName("scope").get(original == null ? name : original);
        if (!name.equals(original) && (entriesByName("section").containsKey(name) || entriesByName("scope").containsKey(name))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "已有相同的页面标题");
        }
        if (original != null && oldSection == null && oldScope == null) requireRow(0);
        save("section", oldSection == null ? null : oldSection.id(), new Entry(null, name, section, null, null, null));
        save("scope", oldScope == null ? null : oldScope.id(),
                new Entry(null, name, body.scope(), body.upperLimitRatio(), body.firstRow(), body.lastRow()));
        if (original == null) {
            replaceTitleHandlers(name, body.handlerUserIds() == null ? List.of() : body.handlerUserIds());
        } else if (!name.equals(original)) {
            persistence.renameTitleHandlers(original, name);
            persistence.renameTitleDetailLinks(original, name);
            if (rules == null) persistence.renameScanRules(original, name);
            else persistence.deleteScanRules(original);
        }
        if (rules != null) replaceScanRules(name, rules);
    }

    /** 删除标题及其解析、经办人和明细链接配置。 */
    @DeleteMapping("/titles")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional(GaussConfig.TX_NAME)
    public void deleteTitle(@RequestParam String name,
            @SessionAttribute(name = AuthController.SESSION_USER, required = false) String editor) {
        service.requireBoardAndTitleMaintenance(editor);
        requireRow(persistence.lockConfiguredTitle(name).size());
        Entry section = entriesByName("section").get(name);
        Entry scope = entriesByName("scope").get(name);
        if (section == null && scope == null) requireRow(0);
        if (section != null) requireRow(persistence.deleteConfig("section", section.id()));
        if (scope != null) requireRow(persistence.deleteConfig("scope", scope.id()));
        persistence.deleteTitleHandlers(name);
        persistence.deleteTitleDetailLinks(name);
        persistence.deleteScanRules(name);
    }

    /** 分页组合标题归属、解析规则及联系人信息。 */
    @GetMapping("/titles")
    public TitlePage titles(@RequestParam(defaultValue = "") String keyword,
                            @RequestParam(defaultValue = "") String target,
                            @RequestParam(defaultValue = "1") int page,
                            @RequestParam(defaultValue = "20") int size) {
        if (page < 1 || size < 1 || size > 100) throw bad("分页参数无效");
        var sections = entriesByName("section");
        var scopes = entriesByName("scope");
        var contacts = entriesByName("contact");
        var names = new java.util.LinkedHashSet<>(sections.keySet());
        names.addAll(scopes.keySet());
        String term = keyword.trim().toLowerCase(Locale.ROOT);
        var matches = names.stream().map(name -> {
            Entry section = sections.get(name);
            Entry contact = section == null ? null : contacts.get(section.target());
            var owner = contact == null ? null : service.contactGroup(contact.target());
            return new TitleRow(name, section, scopes.get(name), contact, owner == null ? null : owner.name());
        }).filter(row -> row.name().toLowerCase(Locale.ROOT).contains(term)
                || row.section() != null && row.section().target().toLowerCase(Locale.ROOT).contains(term))
          .filter(row -> target.isEmpty() || target.equals(row.scope() == null || !MODES.contains(row.scope().target())
                  ? "FULL" : row.scope().target()))
          .toList();
        matches = matches.stream()
                .sorted(Comparator.comparing(PptKeywordConfigController::titleUpdatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(TitleRow::name))
                .toList();
        return new TitlePage(matches.stream().skip(((long) page - 1) * size).limit(size).toList(), matches.size());
    }

    /** 按名称索引指定类别的配置记录。 */
    private Map<String, Entry> entriesByName(String kind) {
        Map<String, Entry> entries = new LinkedHashMap<>();
        persistence.configs(kind, "", "", Integer.MAX_VALUE, 0).forEach(entry -> entries.put(entry.name(), entry));
        return entries;
    }

    /** 分页读取当前配置类别的数据。 */
    @GetMapping("/{kind}")
    public Page list(@PathVariable String kind, @RequestParam(defaultValue = "") String keyword,
                     @RequestParam(defaultValue = "") String target, @RequestParam(defaultValue = "1") int page,
                     @RequestParam(defaultValue = "20") int size) {
        checkKind(kind);
        if (page < 1 || size < 1 || size > 100) throw bad("分页参数无效");
        long total = persistence.configCount(kind, keyword, target);
        var items = persistence.configs(kind, keyword, target, size, ((long) page - 1) * size);
        var reminderSettings = "material-type".equals(kind) && !items.isEmpty()
                ? persistence.materialReminderSettings(items.stream().map(Entry::id).toList())
                : List.<PptAnomalyMapper.MaterialReminderSettings>of();
        return new Page(items, total, reminderSettings,
                reminderWindows(reminderSettings.stream().map(PptAnomalyMapper.MaterialReminderSettings::id).toList()));
    }

    /** 查询可选择的正式板块名称。 */
    @GetMapping("/sections")
    public List<String> sections() {
        return persistence.sections();
    }

    /** 可选板块、默认联系人及关联标题数量。 */
    public record SectionOption(String name, String owner, String ownerName, long titleCount) {}
    /** 板块名称、联系人及新增标记。 */
    public record BoardEdit(String name, String owner, boolean create, String originalName) {}

    /** 汇总板块联系人及关联标题数量，供板块配置页面使用。 */
    @GetMapping("/section-options")
    public List<SectionOption> sectionOptions() {
        var contacts = entriesByName("contact");
        var titleCounts = entriesByName("section").values().stream().collect(
                java.util.stream.Collectors.groupingBy(Entry::target, java.util.stream.Collectors.counting()));
        return sections().stream().map(name -> {
            var contact = contacts.get(name);
            String owner = contact == null ? null : contact.target();
            var person = owner == null ? null : service.contactGroup(owner);
            return new SectionOption(name, owner, person == null ? null : person.name(), titleCounts.getOrDefault(name, 0L));
        }).toList();
    }

    /** 创建或更新正式板块及其默认联系人。 */
    @PostMapping("/boards")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional(GaussConfig.TX_NAME)
    public void saveBoard(@RequestBody BoardEdit body,
            @SessionAttribute(name = AuthController.SESSION_USER, required = false) String editor) {
        service.requireBoardAndTitleMaintenance(editor);
        String name = required(body.name(), 512);
        if (body.owner() == null || body.owner().isBlank()) throw bad("请选择板块联系人");
        String original = body.create() || body.originalName() == null ? name : required(body.originalName(), 512);
        var names = sections();
        if ((body.create() || !original.equals(name)) && names.contains(name)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "板块已存在");
        }
        if (!body.create() && !names.contains(original)) requireRow(0);
        String owners = service.requireContactGroup(body.owner());
        var contact = entriesByName("contact").get(original);
        if (body.create()) {
            try {
                persistence.createConfig("board", new Entry(null, name, name, null, null, null));
            } catch (DuplicateKeyException e) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "板块已存在");
            }
        } else {
            persistence.ensureBoard(original);
            if (!original.equals(name)) {
                var board = entriesByName("board").get(original);
                requireRow(persistence.updateConfig("board", new Entry(board.id(), name, name, null, null, null)));
                for (var entry : entriesByName("section").values()) {
                    if (original.equals(entry.target())) {
                        requireRow(persistence.updateConfig("section", new Entry(entry.id(), entry.name(), name,
                                entry.upperLimitRatio(), entry.firstRow(), entry.lastRow())));
                    }
                }
                if (contact != null) {
                    requireRow(persistence.updateConfig("contact", new Entry(contact.id(), name, owners, null, null, null)));
                }
            }
        }
        save("contact", contact == null ? null : contact.id(), new Entry(null, name, owners, null, null, null));
    }

    /** 删除未关联标题的板块及其默认联系人配置。 */
    @DeleteMapping("/boards")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional(GaussConfig.TX_NAME)
    public void deleteBoard(@RequestParam String name,
            @SessionAttribute(name = AuthController.SESSION_USER, required = false) String editor) {
        service.requireBoardAndTitleMaintenance(editor);
        String boardName = required(name, 512);
        if (!sections().contains(boardName)) requireRow(0);
        if (entriesByName("section").values().stream().anyMatch(entry -> boardName.equals(entry.target()))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该板块仍有关联标题，请先调整标题所属板块后再删除");
        }
        var board = entriesByName("board").get(boardName);
        var contact = entriesByName("contact").get(boardName);
        if (board != null) requireRow(persistence.deleteConfig("board", board.id()));
        if (contact != null) requireRow(persistence.deleteConfig("contact", contact.id()));
    }

    /** 返回人员台账中的默认候选项。 */
    @GetMapping("/people")
    public List<Map<String, String>> people() {
        return searchPeople("", "");
    }

    /** 从当前人员台账按姓名、认证号和可选部门查询联系人。 */
    @GetMapping("/people/search")
    public List<Map<String, String>> searchPeople(@RequestParam(defaultValue = "") String keyword,
                                                  @RequestParam(defaultValue = "") String department) {
        return people.searchPptPeople(keyword.strip(), department.strip()).stream().map(person -> Map.of(
                "userId", person.getUserId(),
                "name", person.getName() == null || person.getName().isBlank() ? person.getUserId() : person.getName(),
                "department", person.getDepartment() == null ? "" : person.getDepartment())).toList();
    }

    @GetMapping("/department/template")
    public ResponseEntity<byte[]> departmentTemplate() throws IOException {
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("部门关键词");
            var textStyle = workbook.createCellStyle();
            textStyle.setDataFormat(workbook.createDataFormat().getFormat("@"));
            for (int column = 0; column < 2; column++) {
                sheet.setDefaultColumnStyle(column, textStyle);
                sheet.setColumnWidth(column, 28 * 256);
            }
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue(KEYWORD_HEADER);
            header.createCell(1).setCellValue(DEPARTMENT_HEADER);
            workbook.write(output);
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                            .filename("部门关键词导入模板.xlsx", StandardCharsets.UTF_8).build().toString())
                    .body(output.toByteArray());
        }
    }

    /** 整表校验关键字及各行部门权限后批量新增，关键词冲突时回滚本次导入。 */
    @PostMapping(value = "/department/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Transactional(GaussConfig.TX_NAME)
    public Map<String, Integer> importDepartmentKeywords(@RequestParam(value = "file", required = false) MultipartFile file,
            @SessionAttribute(name = AuthController.SESSION_USER, required = false) String editor) {
        Map<Integer, Entry> entries = readDepartmentKeywords(file);
        var access = service.configDepartmentAccess(editor);
        // 任一行越权时拒绝整批导入，确保后续插入只涉及授权部门。
        entries.values().forEach(entry -> access.requireMaintenance(entry.target()));
        for (var row : entries.entrySet()) {
            try {
                persistence.createConfig("department", row.getValue());
            } catch (DuplicateKeyException e) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "第 " + row.getKey() + " 行：匹配关键词已存在");
            }
        }
        return Map.of("imported", entries.size());
    }

    private static Map<Integer, Entry> readDepartmentKeywords(MultipartFile file) {
        if (file == null || file.isEmpty()) throw bad("请选择包含部门关键词的 Excel 文件");
        String filename = file.getOriginalFilename();
        if (filename == null || !filename.toLowerCase(Locale.ROOT).matches(".*\\.xlsx?")) {
            throw bad("请选择 .xlsx 或 .xls 文件");
        }
        Map<Integer, Entry> entries = new LinkedHashMap<>();
        Map<String, Integer> keywordRows = new HashMap<>();
        var formatter = new DataFormatter(Locale.ROOT);
        try (var input = file.getInputStream(); var workbook = WorkbookFactory.create(input)) {
            if (workbook.getNumberOfSheets() == 0) throw bad("Excel 中没有工作表");
            var sheet = workbook.getSheetAt(0);
            var header = sheet.getRow(0);
            if (header == null || !KEYWORD_HEADER.equals(importCell(header, 0, formatter))
                    || !DEPARTMENT_HEADER.equals(importCell(header, 1, formatter))) {
                throw bad("第 1 行：请使用模板表头「匹配关键词」「部门」");
            }
            for (Row row : sheet) {
                int rowNumber = row.getRowNum() + 1;
                if (rowNumber == 1) continue;
                String keyword = importCell(row, 0, formatter);
                String department = importCell(row, 1, formatter);
                if (keyword.isBlank() && department.isBlank()) continue;
                requireImportValue(keyword, KEYWORD_HEADER, CONFIG_NAME_LIMIT, rowNumber);
                requireImportValue(department, DEPARTMENT_HEADER, CONFIG_TARGET_LIMIT, rowNumber);
                Integer firstRow = keywordRows.putIfAbsent(keyword, rowNumber);
                if (firstRow != null) throw bad("第 " + rowNumber + " 行：匹配关键词与第 " + firstRow + " 行重复");
                entries.put(rowNumber, new Entry(null, keyword, department, null, null, null));
            }
        } catch (ResponseStatusException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw bad("无法读取 Excel，请检查文件格式、文件是否损坏或加密");
        }
        if (entries.isEmpty()) throw bad("Excel 中没有可导入的部门关键词");
        return entries;
    }

    private static String importCell(Row row, int column, DataFormatter formatter) {
        var cell = row.getCell(column);
        if (cell != null && (cell.getCellType() == CellType.FORMULA || cell.getCellType() == CellType.ERROR)) {
            String label = column == 0 ? KEYWORD_HEADER : DEPARTMENT_HEADER;
            throw bad("第 " + (row.getRowNum() + 1) + " 行：" + label + "请填写值，单元格中有公式或错误");
        }
        return formatter.formatCellValue(cell).trim();
    }

    private static void requireImportValue(String value, String label, int limit, int row) {
        if (value.isBlank()) throw bad("第 " + row + " 行：请填写" + label);
        if (value.length() > limit) throw bad("第 " + row + " 行：" + label + "不能超过 " + limit + " 个字符");
    }

    /** 按目标部门或配置类别校验权限并新增记录。 */
    @PostMapping("/{kind}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void create(@PathVariable String kind, @RequestBody Entry body,
            @SessionAttribute(name = AuthController.SESSION_USER, required = false) String editor) {
        requireConfigWrite(kind, null, body, editor);
        save(kind, null, body);
    }

    /** 校验写入权限并更新记录，部门关键字换部门时校验新旧归属。 */
    @PutMapping("/{kind}/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional(GaussConfig.TX_NAME)
    public void update(@PathVariable String kind, @PathVariable long id, @RequestBody Entry body,
            @SessionAttribute(name = AuthController.SESSION_USER, required = false) String editor) {
        requireConfigWrite(kind, id, body, editor);
        save(kind, id, body);
    }

    /** 按已有记录的归属或类别校验权限后删除配置。 */
    @DeleteMapping("/{kind}/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional(GaussConfig.TX_NAME)
    public void delete(@PathVariable String kind, @PathVariable long id,
            @SessionAttribute(name = AuthController.SESSION_USER, required = false) String editor) {
        requireConfigWrite(kind, id, null, editor);
        if ("contact".equals(kind)) throw bad("板块联系人必须保留，请更换联系人");
        requireRow(persistence.deleteConfig(kind, id));
    }

    /** 集中校验通用新增、更新和删除入口的权限。 */
    private void requireConfigWrite(String kind, Long id, Entry body, String editor) {
        checkKind(kind);
        switch (kind) {
            case "material-type" -> {
                // 材料类型是共享配置，系统管理员也有全量维护权限。
                if (!service.canManageAllConfigurations(editor)) PptAnomalyController.requireProductDepartment(editor, people);
            }
            case "department" -> {
                var access = service.configDepartmentAccess(editor);
                if (id != null) {
                    // 更新和删除均以数据库归属为准，并在写入事务中锁定该记录。
                    String currentDepartment = persistence.lockDepartmentKeywordTarget(id);
                    requireRow(currentDepartment == null ? 0 : 1);
                    access.requireMaintenance(currentDepartment);
                }
                // 新增及更新均校验提交的目标部门，覆盖跨部门调整归属的场景。
                if (body != null) access.requireMaintenance(required(body.target(), CONFIG_TARGET_LIMIT));
            }
            default -> service.requireBoardAndTitleMaintenance(editor);
        }
    }

    /** 统一校验配置名称、目标值和解析范围后写入数据库。 */
    private void save(String kind, Long id, Entry body) {
        checkKind(kind);
        String name = required(body.name(), CONFIG_NAME_LIMIT);
        // 材料类型只维护名称，复用配置表的必填目标字段保存同一名称。
        String target = "material-type".equals(kind) ? name : required(body.target(), CONFIG_TARGET_LIMIT);
        if ("scope".equals(kind) && !MODES.contains(target)) throw bad("识别范围无效");
        if ("contact".equals(kind)) {
            if (!sections().contains(name)) throw bad("请选择已有板块");
            if (id != null) {
                var contact = entriesByName(kind).get(name);
                if (contact == null || !id.equals(contact.id())) throw bad("联系人只能在原板块内更换");
            }
            target = service.requireContactGroup(target);
        }
        boolean upper = "scope".equals(kind) && "UPPER_TABLE_ONLY".equals(target);
        Double ratio = upper ? body.upperLimitRatio() : null;
        Integer first = upper ? body.firstRow() : null;
        Integer last = upper ? body.lastRow() : null;
        if ((ratio != null && (!Double.isFinite(ratio) || ratio <= 0 || ratio > 1))
                || (first != null && first < 1) || (last != null && last < (first == null ? 1 : first))) {
            throw bad("表格范围无效");
        }
        try {
            Entry entry = new Entry(id, name, target, ratio, first, last);
            if (id == null) persistence.createConfig(kind, entry);
            else {
                String previousType = "material-type".equals(kind) ? persistence.lockMaterialTypeName(id) : null;
                requireRow(persistence.updateConfig(kind, entry));
                if (previousType != null && !previousType.equals(name)) persistence.renameBatchMaterialType(previousType, name);
            }
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "已有相同的配置键");
        }
    }

    /** 校验配置类别处于当前支持范围。 */
    private static void checkKind(String kind) {
        if (!KINDS.contains(kind)) throw bad("配置类型无效");
    }

    /** 规范化必填字符串并校验长度。 */
    private static String required(String value, int length) {
        if (value == null || value.isBlank() || value.trim().length() > length) throw bad("配置内容为空或超出长度限制");
        return value.trim();
    }

    /** 确认目标记录存在或更新成功。 */
    private static void requireRow(int count) {
        if (count == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "配置不存在");
    }

    /** 构造参数校验失败的 HTTP 400 异常。 */
    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
