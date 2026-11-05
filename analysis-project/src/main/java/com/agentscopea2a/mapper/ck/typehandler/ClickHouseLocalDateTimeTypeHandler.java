package com.agentscopea2a.mapper.ck.typehandler;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneId;

/** Explicitly used by ClickHouse SQL: bind epoch seconds and read local ISO time strings.
 * Avoids JDBC 0.2.4's unsupported getObject(LocalDateTime.class) and fractional DateTime casts.
 */
public class ClickHouseLocalDateTimeTypeHandler extends BaseTypeHandler<LocalDateTime> {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @Override
    public void setNonNullParameter(PreparedStatement statement, int index, LocalDateTime value, JdbcType jdbcType)
            throws SQLException {
        statement.setLong(index, value.atZone(ZONE).toEpochSecond());
    }

    @Override
    public LocalDateTime getNullableResult(ResultSet result, String column) throws SQLException {
        return parse(result.getString(column));
    }

    @Override
    public LocalDateTime getNullableResult(ResultSet result, int column) throws SQLException {
        return parse(result.getString(column));
    }

    @Override
    public LocalDateTime getNullableResult(CallableStatement result, int column) throws SQLException {
        return parse(result.getString(column));
    }

    private static LocalDateTime parse(String value) {
        return value == null || value.isBlank() ? null : LocalDateTime.parse(value.replace(' ', 'T'));
    }
}
