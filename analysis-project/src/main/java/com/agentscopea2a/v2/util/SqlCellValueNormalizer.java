package com.agentscopea2a.v2.util;

import java.nio.charset.StandardCharsets;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.SQLException;

/**
 * JDBC 单元格取值归一化.
 *
 * <p>{@link java.sql.ResultSet#getObject} 对大对象列会返回驱动私有对象
 * (Clob/Blob/PGobject 等), 直接进 JSON 序列化会抛
 * "No serializer found" 导致接口 500, 进 markdown 渲染则变成 "xxx@1a2b3c" 垃圾串.
 * 这里统一转成普通 String / 原生类型.
 */
public final class SqlCellValueNormalizer {

    /** 单元格最大字符数, 超出截断 (预览/回显用不到全文) */
    private static final int MAX_CELL_CHARS = 20_000;

    private SqlCellValueNormalizer() {
    }

    public static Object normalize(Object value) {
        if (value == null) {
            return null;
        }
        try {
            if (value instanceof Clob clob) {
                return cap(clob.getSubString(1, (int) Math.min(clob.length(), MAX_CELL_CHARS)));
            }
            if (value instanceof Blob blob) {
                return cap(new String(blob.getBytes(1, (int) Math.min(blob.length(), (long) MAX_CELL_CHARS)),
                        StandardCharsets.UTF_8));
            }
            if (value instanceof byte[] bytes) {
                return cap(new String(bytes, StandardCharsets.UTF_8));
            }
        } catch (SQLException e) {
            // 读取失败仍返回可序列化的占位, 不让整次查询报废
            return "<unreadable " + value.getClass().getSimpleName() + ": " + e.getMessage() + ">";
        }
        if (value instanceof String s) {
            return cap(s);
        }
        // PGobject 等驱动类型的 toString() 已返回可读值
        return value;
    }

    private static String cap(String s) {
        if (s == null || s.length() <= MAX_CELL_CHARS) {
            return s;
        }
        return s.substring(0, MAX_CELL_CHARS) + "...(truncated)";
    }
}
