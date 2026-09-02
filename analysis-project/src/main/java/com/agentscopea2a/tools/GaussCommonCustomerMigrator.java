package com.agentscopea2a.tools;

import java.sql.*;
import java.util.*;

/** 双 GaussDB 环境迁移工具。参数: commonSource commonTarget customerSource customerTarget。 */
public final class GaussCommonCustomerMigrator {
    private static final List<String> COMMON = List.of("sys_user", "sys_department", "sys_organization", "sys_account");
    private static final List<String> CUSTOMER = List.of("skill", "skill_file", "script_registry", "sql_registry");
    private GaussCommonCustomerMigrator() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException("用法: <源公共库> <目标公共库> <源客户库> <目标客户库>");
        migrate(args[0], args[1], COMMON); migrate(args[2], args[3], CUSTOMER);
    }

    static void migrate(String sourceUrl, String targetUrl, List<String> tables) throws SQLException {
        try (Connection source = DriverManager.getConnection(sourceUrl); Connection target = DriverManager.getConnection(targetUrl)) {
            target.setAutoCommit(false);
            for (String table : tables) {
                long read = 0, written = 0;
                try (Statement s = source.createStatement(); ResultSet rs = s.executeQuery("SELECT * FROM " + table)) {
                    ResultSetMetaData md = rs.getMetaData(); int n = md.getColumnCount();
                    List<String> names = new ArrayList<>();
                    for (int i = 1; i <= n; i++) names.add(md.getColumnName(i));
                    String cols = String.join(",", names);
                    String marks = String.join(",", Collections.nCopies(n, "?"));
                    String sql = "INSERT INTO " + table + " (" + cols + ") VALUES (" + marks + ") ON CONFLICT DO NOTHING";
                    try (PreparedStatement p = target.prepareStatement(sql)) {
                        while (rs.next()) { read++; for (int i=1;i<=n;i++) p.setObject(i, rs.getObject(i)); p.addBatch(); if (++written % 500 == 0) p.executeBatch(); }
                        p.executeBatch();
                    }
                } catch (SQLException e) { target.rollback(); throw new SQLException("表 " + table + " 迁移失败(已读取 " + read + ")", e); }
                System.out.printf("%s: read=%d written=%d%n", table, read, written);
            }
            target.commit();
        }
    }
}
