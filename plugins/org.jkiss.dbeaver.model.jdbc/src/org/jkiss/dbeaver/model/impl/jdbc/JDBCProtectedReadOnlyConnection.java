/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jkiss.dbeaver.model.impl.jdbc;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Guard installed before a JDBC connection reaches initialization scripts or plugins.
 * Unknown engines fail closed. Native clients must be blocked separately.
 */
public final class JDBCProtectedReadOnlyConnection {
    public static final int QUERY_TIMEOUT_SECONDS = 30;
    public static final int LOCK_TIMEOUT_SECONDS = 5;
    private final Connection delegate;
    private final boolean mysql;
    private Connection proxy;

    private JDBCProtectedReadOnlyConnection(@NotNull Connection delegate, boolean mysql) {
        this.delegate = delegate;
        this.mysql = mysql;
    }

    @NotNull
    public static Connection protect(@NotNull Connection connection) throws SQLException {
        try {
            String product = connection.getMetaData().getDatabaseProductName();
            boolean mysql = "MySQL".equals(product);
            if (mysql && !connection.getMetaData().getURL().startsWith("jdbc:mysql://")) {
                throw new SQLException("Protected read-only: MySQL replication and load-balancing JDBC URLs are not supported.");
            }
            if (!mysql && !"PostgreSQL".equals(product)) {
                throw new SQLException("Protected read-only has no verified adapter for " + product
                    + ". The connection was blocked; ordinary read-only remains available.");
            }
            JDBCProtectedReadOnlyConnection guard = new JDBCProtectedReadOnlyConnection(connection, mysql);
            connection.setAutoCommit(true);
            try (Statement statement = connection.createStatement()) {
                statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                if (mysql) {
                    statement.execute("SET SESSION TRANSACTION READ ONLY");
                    statement.execute("SET SESSION max_execution_time = " + QUERY_TIMEOUT_SECONDS * 1000);
                    statement.execute("SET SESSION lock_wait_timeout = " + LOCK_TIMEOUT_SECONDS);
                    statement.execute("SET SESSION innodb_lock_wait_timeout = " + LOCK_TIMEOUT_SECONDS);
                } else {
                    statement.execute("SET default_transaction_read_only = on");
                    statement.execute("SET statement_timeout = '" + QUERY_TIMEOUT_SECONDS + "s'");
                    statement.execute("SET lock_timeout = '" + LOCK_TIMEOUT_SECONDS + "s'");
                    statement.execute("SET idle_in_transaction_session_timeout = '" + QUERY_TIMEOUT_SECONDS + "s'");
                }
            }
            guard.verify();
            guard.proxy = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class}, (object, method, args) -> guard.invokeConnection(method, args));
            return guard.proxy;
        } catch (SQLException | RuntimeException e) {
            try {
                connection.close();
            } catch (SQLException closeError) {
                e.addSuppressed(closeError);
            }
            throw e;
        }
    }

    private void verify() throws SQLException {
        if (!delegate.getAutoCommit()) {
            throw blocked("Auto-commit is required to avoid idle read transactions");
        }
        String sql = mysql
            ? "SELECT @@session.transaction_read_only, @@session.max_execution_time, "
                + "@@session.lock_wait_timeout, @@session.innodb_lock_wait_timeout"
            : "SELECT current_setting('default_transaction_read_only') = 'on', "
                + "current_setting('statement_timeout')::interval <= interval '30 seconds' "
                + "AND current_setting('statement_timeout')::interval > interval '0 seconds', "
                + "current_setting('lock_timeout')::interval <= interval '5 seconds' "
                + "AND current_setting('lock_timeout')::interval > interval '0 seconds'";
        try (Statement statement = delegate.createStatement()) {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            try (ResultSet result = statement.executeQuery(sql)) {
                if (!result.next() || !result.getBoolean(1)) {
                    throw blocked("The server did not confirm read-only protection");
                }
                if (mysql) {
                    if (result.getLong(2) <= 0 || result.getLong(2) > QUERY_TIMEOUT_SECONDS * 1000L
                        || result.getLong(3) <= 0 || result.getLong(3) > LOCK_TIMEOUT_SECONDS
                        || result.getLong(4) <= 0 || result.getLong(4) > LOCK_TIMEOUT_SECONDS) {
                        throw blocked("The server did not confirm query and lock timeouts");
                    }
                } else if (!result.getBoolean(2) || !result.getBoolean(3)) {
                    throw blocked("The server did not confirm query and lock timeouts");
                }
            }
        }
    }

    @Nullable
    private Object invokeConnection(@NotNull Method method, @Nullable Object[] args) throws Throwable {
        String name = method.getName();
        rejectUnwrap(name);
        if (name.equals("isWrapperFor")) {
            return false;
        }
        if (name.equals("isReadOnly")) {
            return true;
        }
        if (name.equals("setReadOnly")) {
            if (Boolean.FALSE.equals(args[0])) {
                throw blocked("Disabling read-only requires disconnecting first");
            }
            verify();
            return null;
        }
        if (name.equals("setAutoCommit") && Boolean.FALSE.equals(args[0])) {
            throw blocked("Manual transactions are disabled");
        }
        if (name.equals("prepareCall")) {
            throw blocked("Stored procedures are disabled");
        }
        if (name.equals("createStatement") || name.equals("prepareStatement")) {
            String sql = name.equals("prepareStatement") ? (String) args[0] : null;
            if (sql != null) {
                JDBCReadOnlyPolicy.check(sql, mysql);
            }
            int concurrencyIndex = sql == null ? 1 : 2;
            if (args != null && args.length > concurrencyIndex
                && Integer.valueOf(ResultSet.CONCUR_UPDATABLE).equals(args[concurrencyIndex])) {
                throw blocked("Updatable result sets are disabled");
            }
            Statement statement = (Statement) invoke(delegate, method, args);
            return wrapStatement(statement, sql);
        }
        Object result = invoke(delegate, method, args);
        if (result instanceof DatabaseMetaData metadata) {
            return Proxy.newProxyInstance(DatabaseMetaData.class.getClassLoader(), new Class<?>[] {DatabaseMetaData.class},
                (object, metadataMethod, metadataArgs) -> {
                    rejectUnwrap(metadataMethod.getName());
                    if (metadataMethod.getName().equals("getConnection")) {
                        return proxy;
                    }
                    Object value = invoke(metadata, metadataMethod, metadataArgs);
                    return value instanceof ResultSet rs ? wrapResultSet(rs, null) : value;
                });
        }
        return result;
    }

    @NotNull
    private Statement wrapStatement(@NotNull Statement statement, @Nullable String preparedSql) {
        Class<?> type = statement instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
        return (Statement) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (object, method, args) -> {
            String name = method.getName();
            rejectUnwrap(name);
            if (name.equals("isWrapperFor")) {
                return false;
            }
            if (name.equals("getConnection")) {
                return proxy;
            }
            if (name.equals("addBatch") || name.equals("executeBatch") || name.equals("executeLargeBatch")) {
                throw blocked("Batches are disabled");
            }
            if (name.startsWith("execute")) {
                String sql = args != null && args.length > 0 && args[0] instanceof String text ? text : preparedSql;
                if (sql == null || name.contains("Update")) {
                    throw blocked("Only read queries may be executed");
                }
                JDBCReadOnlyPolicy.check(sql, mysql);
                verify();
                int timeout = statement.getQueryTimeout();
                if (timeout <= 0 || timeout > QUERY_TIMEOUT_SECONDS) {
                    statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                }
            }
            Object result = invoke(statement, method, args);
            return result instanceof ResultSet rs ? wrapResultSet(rs, (Statement) object) : result;
        });
    }

    @NotNull
    private ResultSet wrapResultSet(@NotNull ResultSet result, @Nullable Statement statement) {
        return (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(), new Class<?>[] {ResultSet.class},
            (object, method, args) -> {
                String name = method.getName();
                rejectUnwrap(name);
                if (name.equals("isWrapperFor")) {
                    return false;
                }
                if (name.startsWith("update") || name.equals("insertRow") || name.equals("deleteRow")) {
                    throw blocked("Result-set writes are disabled");
                }
                if (name.equals("getStatement")) {
                    return statement;
                }
                return invoke(result, method, args);
            });
    }

    private static void rejectUnwrap(@NotNull String name) throws SQLException {
        if (name.equals("unwrap")) {
            throw blocked("Direct driver access is disabled");
        }
    }

    @Nullable
    private static Object invoke(@NotNull Object target, @NotNull Method method, @Nullable Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    @NotNull
    private static SQLException blocked(@NotNull String reason) {
        return new SQLException("Protected read-only: " + reason + ".");
    }
}
