package com.agentscopea2a.mapper.ck.typehandler;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.sql.*;

/** Bind trace timestamps as epoch seconds, including multi-row inserts on older ClickHouse servers. */
public class ClickHouseTimestampTypeHandler extends BaseTypeHandler<Timestamp> {
    @Override
    public void setNonNullParameter(PreparedStatement statement, int index, Timestamp value, JdbcType jdbcType)
            throws SQLException {
        statement.setLong(index, value.toInstant().getEpochSecond());
    }

    @Override
    public Timestamp getNullableResult(ResultSet result, String column) throws SQLException {
        return result.getTimestamp(column);
    }

    @Override
    public Timestamp getNullableResult(ResultSet result, int column) throws SQLException {
        return result.getTimestamp(column);
    }

    @Override
    public Timestamp getNullableResult(CallableStatement result, int column) throws SQLException {
        return result.getTimestamp(column);
    }
}
