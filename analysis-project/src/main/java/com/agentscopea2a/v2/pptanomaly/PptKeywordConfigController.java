package com.agentscopea2a.v2.pptanomaly;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import com.agentscopea2a.mapper.gauss.PptAnomalyMapper;
import com.agentscopea2a.v2.auth.controller.AuthController;
import com.agentscopea2a.v2.auth.mapper.DeveloperPlPersonInfoMapper;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.time.LocalDateTime;
import com.agentscopea2a.config.datasource.GaussConfig;
import org.springframework.transaction.annotation.Transactional;
import org.apache.ibatis.annotations.AutomapConstructor;
import java.util.Set;

/** 例会材料配置接口，维护部门关键词、标题板块、解析范围、经办人及明细链接。 */
@RestController
@RequestMapping("/api/ppt-anomaly/keyword-config")
@ConditionalOnProperty(prefix = "spring.datasource.hikari.gauss-customer", name = "enabled", havingValue = "true")
public class PptKeywordConfigController {
    private static final Set<String> KINDS = Set.of("department", "scope", "section", "contact");
    private static final Set<String> MODES = Set.of("FULL", "TEXT_ONLY", "UPPER_TABLE_ONLY", "NO_SCAN");
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

    /** 校验已登录用户的产品部操作权限。 */
    @ModelAttribute
    public void requireProductDepartment(@SessionAttribute(name = AuthController.SESSION_USER, required = false) String userId) {
        PptAnomalyController.requireProductDepartment(userId, people);
    }

    /** 关键词配置及其解析范围参数和维护时间。 */
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
    public record Page(List<Entry> items, long total) {}
    /** 标题及其板块、解析模式和联系人组合信息。 */
    public record TitleRow(String name, Entry section, Entry scope, Entry contact, String ownerName) {}
    /** 标题配置的分页查询结果。 */
    public record TitlePage(List<TitleRow> items, long total) {}
    /** 标题名称、板块、解析规则及联系人维护请求。 */
    public record TitleEdit(String originalName, String name, String section, String scope, String owner,
                            Double upperLimitRatio, Integer firstRow, Integer lastRow, List<String> handlerUserIds) {}
    /** 经办人的部门、姓名及认证号展示数据。 */
    public record Handler(String department, String name, String userId) {}
    /** 一个标题明细分组及其链接集合。 */
    public record Detail(String name, List<DetailLink> links) {}
    /** 标题明细下的一条链接名称及 HTTP 地址。 */
    public record DetailLink(String name, String url) {}

    @GetMapping("/scan-rules")
    public List<PptScanRule> scanRules(@RequestParam String title) {
        requireConfiguredTitle(title);
        return persistence.scanRules().stream().filter(rule -> title.equals(rule.title())).toList();
    }

    @PutMapping("/scan-rules")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional(GaussConfig.TX_NAME)
    public void saveScanRules(@RequestParam String title, @RequestBody List<PptScanRule> rules) {
        requireRow(persistence.lockConfiguredTitle(title).size());
        if (rules == null || rules.size() > 50) throw bad("每个标题最多配置 50 条规则");
        var names = new java.util.HashSet<String>();
        var conditions = new java.util.HashSet<String>();
        persistence.deleteScanRules(title);
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
            persistence.insertScanRule(new PptScanRule(title, name, keywords, rule.mode(), ratio, first, last));
        }
    }

    /** 查询指定标题下配置的明细分组及链接。 */
    @GetMapping("/title-details")
    public List<PptAnomalyMapper.TitleDetailLink> titleDetails(@RequestParam String title) {
        requireConfiguredTitle(title);
        return persistence.titleDetailLinks(title);
    }

    /** 校验明细名称和 HTTP 链接后整体保存标题明细配置。 */
    @PutMapping("/title-details")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional(GaussConfig.TX_NAME)
    public void saveTitleDetails(@RequestParam String title, @RequestBody List<Detail> details) {
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

    /** 整体替换默认标题经办人，仅影响后续导入。 */
    @PutMapping("/title-handlers")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional(GaussConfig.TX_NAME)
    public void saveTitleHandlers(@RequestParam String title, @RequestBody List<String> userIds) {
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

    /** 为默认标题新增经办人，仅影响后续导入。 */
    @PostMapping("/title-handlers")
    @Transactional(GaussConfig.TX_NAME)
    public List<Handler> addTitleHandler(@RequestParam String title, @RequestParam String userId) {
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

    /** 追加复用来源标题的经办人，保留当前标题已有配置。 */
    @PostMapping("/title-handlers/reuse")
    @Transactional(GaussConfig.TX_NAME)
    public List<Handler> reuseTitleHandlers(@RequestParam String title, @RequestBody ReuseHandlers body) {
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

    /** 删除指定标题下的经办人配置。 */
    @DeleteMapping("/title-handlers")
    @Transactional(GaussConfig.TX_NAME)
    public List<Handler> removeTitleHandler(@RequestParam String title, @RequestParam String userId) {
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
    public void saveTitle(@RequestBody TitleEdit body) {
        String name = required(body.name(), 512);
        String section = required(body.section(), 512);
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
            persistence.renameScanRules(original, name);
        }
    }

    /** 删除标题及其解析、经办人和明细链接配置。 */
    @DeleteMapping("/titles")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional(GaussConfig.TX_NAME)
    public void deleteTitle(@RequestParam String name) {
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
        return new Page(persistence.configs(kind, keyword, target, size, ((long) page - 1) * size), total);
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
    public void saveBoard(@RequestBody BoardEdit body) {
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
    public void deleteBoard(@RequestParam String name) {
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

    /** 校验并新增当前类别的配置。 */
    @PostMapping("/{kind}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void create(@PathVariable String kind, @RequestBody Entry body) {
        save(kind, null, body);
    }

    /** 校验并更新指定配置记录。 */
    @PutMapping("/{kind}/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void update(@PathVariable String kind, @PathVariable long id, @RequestBody Entry body) {
        save(kind, id, body);
    }

    /** 校验类别后删除指定配置记录。 */
    @DeleteMapping("/{kind}/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String kind, @PathVariable long id) {
        checkKind(kind);
        if ("contact".equals(kind)) throw bad("板块联系人必须保留，请更换联系人");
        requireRow(persistence.deleteConfig(kind, id));
    }

    /** 统一校验配置名称、目标值和解析范围后写入数据库。 */
    private void save(String kind, Long id, Entry body) {
        checkKind(kind);
        String name = required(body.name(), 512);
        String target = required(body.target(), 2048);
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
            else requireRow(persistence.updateConfig(kind, entry));
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
