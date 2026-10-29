package com.agentscopea2a.v2.skillManager.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import java.util.regex.Pattern;

/** Versioned calendar window; no expression evaluation or implicit scalar conversion. */
@Component
public class PeriodWindowResolver {
    private static final Set<String> FIELDS = Set.of("v", "kind", "unit", "startOffset", "endOffset", "format", "order", "output");
    private static final Pattern TOKENS = Pattern.compile("\\{(?:year|month|month:02|quarter)\\}");
    private final int maxPeriods;
    public PeriodWindowResolver(@Value("${harness.a2a.script-params.max-periods:120}") int maxPeriods) {
        if (maxPeriods < 1) throw new IllegalArgumentException("max-periods must be positive");
        this.maxPeriods = maxPeriods;
    }
    public int getMaxPeriods() { return maxPeriods; }
    public Object resolve(Object marker, String type, LocalDate date) {
        if (!(marker instanceof Map<?, ?> outer) || outer.size() != 1 || !(outer.get("$dynamic") instanceof Map<?, ?> c))
            throw invalid("invalid $dynamic marker structure");
        if (!c.keySet().equals(FIELDS)) throw invalid("unknown or missing period_window field");
        if (integer(c.get("v")) != 1 || !"period_window".equals(c.get("kind"))) throw invalid("unsupported version or kind");
        String unit = text(c, "unit"), format = normalizeQuarterFormat(text(c, "format")), order = text(c, "order"), output = text(c, "output");
        if (!Set.of("month", "quarter", "year").contains(unit)) throw invalid("unsupported unit");
        if (!Set.of("asc", "desc").contains(order) || !Set.of("list", "single").contains(output)) throw invalid("unsupported order/output");
        int start = integer(c.get("startOffset")), end = integer(c.get("endOffset"));
        long count = (long) end - start + 1;
        if (count < 1 || count > maxPeriods || ("single".equals(output) && count != 1)) throw invalid("invalid window cardinality");
        String remainder = TOKENS.matcher(format).replaceAll("");
        if (format.isBlank() || format.length() > 200 || remainder.contains("{") || remainder.contains("}")) throw invalid("unsupported format placeholder or length (max 200)");
        String normalized = type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
        boolean array = ScriptParamRuleService.isArrayType(normalized);
        String element = normalized;
        if (normalized.endsWith("[]")) element = normalized.substring(0, normalized.length()-2);
        else if (normalized.startsWith("array<") && normalized.endsWith(">")) element = normalized.substring(6, normalized.length()-1).trim();
        else if (normalized.equals("array")) element = "string";
        if (array != "list".equals(output) || !Set.of("string", "date").contains(element)) throw invalid("ParamRuleTypeMismatch: unsupported target type " + type);
        if (date == null) throw invalid("dataDate required");
        List<String> values = new ArrayList<>();
        try {
            YearMonth anchor = YearMonth.from(date);
            if (unit.equals("quarter")) anchor = YearMonth.of(date.getYear(), ((date.getMonthValue()-1)/3)*3+1);
            if (unit.equals("year")) anchor = YearMonth.of(date.getYear(), 1);
            for (long offset = start; offset <= end; offset++) {
                YearMonth p = unit.equals("year") ? anchor.plusYears(offset) : anchor.plusMonths(offset * (unit.equals("quarter") ? 3L : 1L));
                String value = ScriptParamRuleService.formatPeriod(format, p.getYear(), p.getMonthValue());
                if (element.equals("date") && !(unit.equals("month") && format.equals("{year}年{month}月份版本"))) LocalDate.parse(value);
                values.add(value);
            }
        } catch (RuntimeException e) { throw invalid("invalid calendar/date output: " + e.getMessage()); }
        if (order.equals("desc")) Collections.reverse(values);
        return output.equals("single") ? values.get(0) : values;
    }
    private static String text(Map<?, ?> c, String key) {
        if (!(c.get(key) instanceof String s)) throw invalid(key + " must be a string");
        return s;
    }

    /** Accept configurations saved before the quarter label was simplified. */
    private static String normalizeQuarterFormat(String format) {
        return format.replace("{year}年第{quarter}季度", "{year}年{quarter}季度");
    }
    private static int integer(Object value) {
        if (!(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long)) throw invalid("integer required");
        long n = ((Number)value).longValue();
        if (n < Integer.MIN_VALUE || n > Integer.MAX_VALUE) throw invalid("integer out of range");
        return (int)n;
    }
    private static IllegalStateException invalid(String detail) { return new IllegalStateException("ScriptParamsInvalid: " + detail); }
}
