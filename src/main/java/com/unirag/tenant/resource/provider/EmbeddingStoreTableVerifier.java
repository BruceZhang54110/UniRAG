package com.unirag.tenant.resource.provider;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * EMBEDDING_STORE 向量表加载期元数据校验（research.md R2/R3）：表名白名单、
 * 表存在性、{@code embedding} 列维度与配置一致。每次校验使用一次性短连接
 * （try-with-resources），校验通过静默返回。
 *
 * <p>异常信息只含表名/维度等定位信息，绝不回显密码等配置内容（FR-008）。
 */
public final class EmbeddingStoreTableVerifier {

    private static final Pattern TABLE_NAME_PATTERN =
            Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*(\\.[a-zA-Z_][a-zA-Z0-9_]*)?$");

    private static final String DIMENSION_SQL = """
            SELECT a.atttypmod
            FROM pg_attribute a
            JOIN pg_class c ON a.attrelid = c.oid
            JOIN pg_namespace n ON c.relnamespace = n.oid
            WHERE n.nspname = ? AND c.relname = ?
              AND a.attname = 'embedding' AND a.attnum > 0 AND NOT a.attisdropped""";

    private EmbeddingStoreTableVerifier() {
    }

    /**
     * 表名白名单校验（research.md R3）：不匹配抛 {@link IllegalArgumentException}（信息含表名）。
     * store 构建前须先调用——store 将表名原样拼入 DDL，非法表名会在其内部以不可读 SQL 错误失败。
     */
    public static void verifyTableName(String table) {
        if (table == null || !TABLE_NAME_PATTERN.matcher(table).matches()) {
            throw new IllegalArgumentException("非法向量表名: " + table
                    + "（须匹配 " + TABLE_NAME_PATTERN.pattern() + "）");
        }
    }

    /**
     * 校验向量表：白名单 → 连接 → 存在性/维度。表不存在且 {@code createTable=true} 时
     * 静默通过（store 构建期已按配置维度建表）；{@code createTable=false} 时表必须已存在。
     *
     * @throws IllegalArgumentException 表名非法（信息含表名）
     * @throws IllegalStateException    表不存在（禁建时）/维度不匹配/连接或查询失败
     */
    public static void verify(String host, int port, String database, String user, String password,
                              String table, int dimension, boolean createTable) {
        verifyTableName(table);
        String schema = "public";
        String tableName = table;
        int dot = table.indexOf('.');
        if (dot >= 0) {
            schema = table.substring(0, dot);
            tableName = table.substring(dot + 1);
        }
        // store 以未加引号标识符执行 DDL，PG 会折叠为小写——目录查询用折叠后的实际表名
        String catalogSchema = schema.toLowerCase(Locale.ROOT);
        String catalogTable = tableName.toLowerCase(Locale.ROOT);
        String url = "jdbc:postgresql://" + host + ":" + port + "/" + database;
        try (Connection connection = DriverManager.getConnection(url, user, password);
                PreparedStatement statement = connection.prepareStatement(DIMENSION_SQL)) {
            statement.setString(1, catalogSchema);
            statement.setString(2, catalogTable);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    if (!createTable) {
                        throw new IllegalStateException(
                                "向量表不存在: " + table + "（create-table=false 要求表已存在）");
                    }
                    return;
                }
                int actualDimension = resultSet.getInt(1);
                if (actualDimension != dimension) {
                    throw new IllegalStateException("向量表 " + table + " 维度不匹配: 实际维度 "
                            + actualDimension + "，配置维度 " + dimension);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("向量表校验失败: " + table + " (" + e.getMessage() + ")", e);
        }
    }
}
