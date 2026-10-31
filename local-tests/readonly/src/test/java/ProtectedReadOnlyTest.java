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

import org.jkiss.dbeaver.model.impl.jdbc.JDBCProtectedReadOnlyConnection;
import org.jkiss.dbeaver.model.impl.jdbc.JDBCReadOnlyPolicy;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.sql.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ProtectedReadOnlyTest {
    static Connection raw(boolean mysql) throws SQLException {
        return DriverManager.getConnection(mysql
            ? "jdbc:mysql://127.0.0.1:13316/readonly_test?allowPublicKeyRetrieval=true&useSSL=false&allowMultiQueries=true&readOnlyPropagatesToServer=false"
            : "jdbc:postgresql://127.0.0.1:15436/readonly_test", mysql ? "root" : "postgres", "local_ro_test");
    }
    @BeforeAll static void fixture() throws SQLException {
        for (boolean mysql : List.of(true, false)) {
            try (var c = raw(mysql); var s = c.createStatement()) {
                s.execute("DROP VIEW IF EXISTS guard_view");
                s.execute("DROP FUNCTION IF EXISTS guard_write");
                s.execute("DROP TABLE IF EXISTS guard_items");
                s.execute("CREATE TABLE guard_items (id INT PRIMARY KEY, label VARCHAR(50))");
                s.execute("INSERT INTO guard_items VALUES (1, 'test'), (2, 'other')");
                s.execute(mysql
                    ? "CREATE FUNCTION guard_write() RETURNS INT DETERMINISTIC MODIFIES SQL DATA BEGIN INSERT INTO guard_items VALUES (3, 'bad'); RETURN 3; END"
                    : "CREATE FUNCTION guard_write() RETURNS INT LANGUAGE plpgsql SECURITY DEFINER AS $$ BEGIN INSERT INTO guard_items VALUES (3, 'bad'); RETURN 3; END $$");
                s.execute("CREATE VIEW guard_view AS SELECT guard_write() AS value");
            }
        }
    }
    @ParameterizedTest @ValueSource(strings = {
        "SELECT 1", "SELECT * FROM guard_items", "SELECT id, label FROM guard_items WHERE id = ?",
        "SELECT COUNT(*) FROM guard_items", "SELECT SUM(id) FROM guard_items", "SELECT 'DELETE' AS word",
        "SELECT id FROM guard_items UNION SELECT id FROM guard_items",
        "WITH t AS (SELECT id FROM guard_items) SELECT * FROM t",
        "SELECT id FROM guard_items WHERE id IN (1, 2)", "SELECT CAST(id AS CHAR) FROM guard_items",
        "SELECT id FROM guard_items WHERE EXISTS (SELECT 1 FROM guard_items)", "SELECT 1;"
    }) void reads(String sql) {
        assertDoesNotThrow(() -> JDBCReadOnlyPolicy.check(sql, true));
        assertDoesNotThrow(() -> JDBCReadOnlyPolicy.check(sql, false));
    }
    @ParameterizedTest @ValueSource(strings = {
        "INSERT INTO guard_items VALUES(4, 'x')", "UPDATE guard_items SET label='x'", "DELETE FROM guard_items",
        "DROP TABLE guard_items", "TRUNCATE guard_items", "CREATE TABLE bad(id INT)", "ALTER TABLE guard_items ADD x INT",
        "GRANT ALL ON guard_items TO PUBLIC", "CALL guard_write()", "DO 1", "EXEC guard_write",
        "SELECT 1; DELETE FROM guard_items", "SELECT 1; SELECT 2", "SELECT 1 /*! INTO OUTFILE '/tmp/bad' */",
        "SELECT * FROM guard_items FOR UPDATE", "SELECT * FROM guard_items FOR SHARE",
        "SELECT * FROM guard_items LOCK IN SHARE MODE", "SELECT * INTO bad FROM guard_items",
        "SELECT 1 INTO OUTFILE '/tmp/bad'", "SELECT 1 INTO DUMPFILE '/tmp/bad'", "SELECT @a := 1",
        "SELECT GET_LOCK('bad', 0)", "SELECT SLEEP(1)", "SELECT BENCHMARK(1000000, 1)", "SELECT LOAD_FILE('/etc/passwd')",
        "SELECT guard_write()", "SELECT COUNT(*) FROM guard_items ORDER BY guard_write()",
        "SELECT id FROM guard_items WHERE guard_write() = 1", "SELECT public.count(1)", "SELECT `guard_write`()",
        "SELECT pg_advisory_lock(1)", "SELECT set_config('default_transaction_read_only', 'off', false)",
        "WITH t AS (DELETE FROM guard_items RETURNING *) SELECT * FROM t",
        "SET SESSION TRANSACTION READ WRITE", "START TRANSACTION READ WRITE", "BEGIN", "COMMIT",
        "EXPLAIN ANALYZE DELETE FROM guard_items", "SELECT /*+ MAX_EXECUTION_TIME(0) */ 1", "nonsense", "",
        "SHOW TABLES WHERE SLEEP(1)", "SELECT 'unterminated", "SELECT 1 -- comment"
    }) void rejects(String sql) {
        assertThrows(SQLException.class, () -> JDBCReadOnlyPolicy.check(sql, true), sql);
        assertThrows(SQLException.class, () -> JDBCReadOnlyPolicy.check(sql, false), sql);
    }
    @ParameterizedTest @ValueSource(booleans = {true, false})
    void jdbc(boolean mysql) throws SQLException {
        try (var c = JDBCProtectedReadOnlyConnection.protect(raw(mysql)); var s = c.createStatement()) {
            assertTrue(c.isReadOnly()); assertTrue(c.getAutoCommit());
            try (var rs = s.executeQuery("SELECT COUNT(*) FROM guard_items")) {
                assertTrue(rs.next()); assertEquals(2, rs.getInt(1)); assertSame(s, rs.getStatement());
                assertThrows(SQLException.class, () -> rs.updateInt(1, 9));
            }
            try (var ps = c.prepareStatement("SELECT label FROM guard_items WHERE id = ?")) {
                ps.setInt(1, 1);
                try (var rs = ps.executeQuery()) { assertTrue(rs.next()); assertEquals("test", rs.getString(1)); }
            }
            for (String sql : List.of("DELETE FROM guard_items", "SET SESSION TRANSACTION READ WRITE",
                "SELECT 1; DELETE FROM guard_items", "SELECT guard_write()", "DROP TABLE guard_items")) {
                assertThrows(SQLException.class, () -> s.execute(sql), sql);
                assertThrows(SQLException.class, () -> c.prepareStatement(sql), sql);
            }
            assertThrows(SQLException.class, () -> c.prepareCall("CALL guard_write()"));
            assertThrows(SQLException.class, () -> s.addBatch("DELETE FROM guard_items"));
            assertThrows(SQLException.class, s::executeBatch); assertThrows(SQLException.class, s::executeLargeBatch);
            assertThrows(SQLException.class, () -> c.setReadOnly(false));
            assertThrows(SQLException.class, () -> c.setAutoCommit(false));
            assertThrows(SQLException.class, () -> c.unwrap(Connection.class));
            assertThrows(SQLException.class, () -> s.unwrap(Statement.class));
            assertSame(c, s.getConnection()); assertSame(c, c.getMetaData().getConnection());
            assertThrows(SQLException.class, () -> c.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_UPDATABLE));
            s.setQueryTimeout(0); s.execute("SELECT 1"); assertEquals(30, s.getQueryTimeout());
            s.setQueryTimeout(2); s.execute("SELECT 1"); assertEquals(2, s.getQueryTimeout());
        }
    }
    @ParameterizedTest @ValueSource(booleans = {true, false})
    void hiddenWrite(boolean mysql) throws SQLException {
        try (var c = JDBCProtectedReadOnlyConnection.protect(raw(mysql)); var s = c.createStatement()) {
            SQLException e = assertThrows(SQLException.class, () -> s.executeQuery("SELECT * FROM guard_view"));
            assertFalse(e.getMessage().startsWith("Protected read-only:"), "Must be rejected by server, not parser");
        }
        try (var c = raw(mysql); var s = c.createStatement(); var rs = s.executeQuery("SELECT COUNT(*) FROM guard_items")) {
            assertTrue(rs.next()); assertEquals(2, rs.getInt(1));
        }
    }
    @ParameterizedTest @ValueSource(booleans = {true, false})
    void tamperAndReconnect(boolean mysql) throws SQLException {
        var original = raw(mysql);
        try (var c = JDBCProtectedReadOnlyConnection.protect(original); var s = c.createStatement()) {
            try (var tamper = original.createStatement()) {
                tamper.execute(mysql ? "SET SESSION TRANSACTION READ WRITE" : "SET default_transaction_read_only = off");
            }
            assertThrows(SQLException.class, () -> s.executeQuery("SELECT 1"));
        }
        try (var c = JDBCProtectedReadOnlyConnection.protect(raw(mysql)); var s = c.createStatement()) {
            assertTrue(s.execute("SELECT 1"));
        }
    }
    @ParameterizedTest @ValueSource(booleans = {true, false})
    void tamperTimeout(boolean mysql) throws SQLException {
        var original = raw(mysql);
        try (var c = JDBCProtectedReadOnlyConnection.protect(original); var s = c.createStatement()) {
            try (var tamper = original.createStatement()) {
                tamper.execute(mysql ? "SET SESSION max_execution_time = 0" : "SET statement_timeout = 0");
            }
            assertThrows(SQLException.class, () -> s.executeQuery("SELECT 1"));
        }
    }
    @ParameterizedTest @ValueSource(booleans = {true, false})
    void lockTimeout(boolean mysql) throws SQLException {
        try (var locker = raw(mysql); var lock = locker.createStatement();
             var c = JDBCProtectedReadOnlyConnection.protect(raw(mysql)); var s = c.createStatement()) {
            if (!mysql) locker.setAutoCommit(false);
            lock.execute(mysql ? "LOCK TABLES guard_items WRITE" : "LOCK TABLE guard_items IN ACCESS EXCLUSIVE MODE");
            try {
                long start = System.nanoTime();
                assertThrows(SQLException.class, () -> s.executeQuery("SELECT * FROM guard_items"));
                long seconds = (System.nanoTime() - start) / 1_000_000_000;
                assertTrue(seconds >= 4 && seconds < 15, "Expected 5-second lock timeout, got " + seconds);
            } finally {
                if (mysql) lock.execute("UNLOCK TABLES"); else locker.rollback();
            }
        }
    }
    @Test void mysqlNavigatorMetadata() throws SQLException {
        try (var c = JDBCProtectedReadOnlyConnection.protect(raw(true)); var s = c.createStatement()) {
            try (var rs = s.executeQuery("SHOW TABLE STATUS FROM `readonly_test`")) {
                assertTrue(rs.next());
            }
            try (var rs = s.executeQuery("SELECT @@GLOBAL.character_set_server,@@GLOBAL.collation_server")) {
                assertTrue(rs.next());
            }
            assertThrows(SQLException.class, () -> s.execute("SHOW TABLE STATUS WHERE SLEEP(1)"));
        }
    }
    @Test void unsupported() throws SQLException {
        var c = DriverManager.getConnection("jdbc:h2:mem:unsupported");
        assertThrows(SQLException.class, () -> JDBCProtectedReadOnlyConnection.protect(c));
        assertTrue(c.isClosed());
    }
}

