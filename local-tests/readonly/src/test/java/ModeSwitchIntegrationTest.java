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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;

class ModeSwitchIntegrationTest {
    @ParameterizedTest @ValueSource(booleans = {true, false})
    void protectedToWritableAndBack(boolean mysql) throws SQLException {
        try (var admin = ProtectedReadOnlyTest.raw(mysql); var s = admin.createStatement()) {
            s.execute("DROP TABLE IF EXISTS guard_mode_switch");
            s.execute("CREATE TABLE guard_mode_switch (id INT PRIMARY KEY, value INT)");
            s.execute("INSERT INTO guard_mode_switch VALUES (1, 10)");
            try {
                var guarded = JDBCProtectedReadOnlyConnection.protect(ProtectedReadOnlyTest.raw(mysql));
                try (var blocked = guarded.createStatement()) {
                    assertThrows(SQLException.class, () -> blocked.executeUpdate("UPDATE guard_mode_switch SET value=20 WHERE id=1"));
                }
                guarded.close();
                assertTrue(guarded.isClosed());
                try (var writable = ProtectedReadOnlyTest.raw(mysql); var edit = writable.createStatement()) {
                    assertEquals(1, edit.executeUpdate("UPDATE guard_mode_switch SET value=20 WHERE id=1"));
                    writable.setAutoCommit(false);
                    edit.executeUpdate("UPDATE guard_mode_switch SET value=99 WHERE id=1");
                    writable.rollback();
                }
                try (var protectedAgain = JDBCProtectedReadOnlyConnection.protect(ProtectedReadOnlyTest.raw(mysql));
                     var read = protectedAgain.createStatement()) {
                    try (var rs = read.executeQuery("SELECT value FROM guard_mode_switch WHERE id=1")) {
                        assertTrue(rs.next()); assertEquals(20, rs.getInt(1));
                    }
                    assertThrows(SQLException.class, () -> read.executeUpdate("UPDATE guard_mode_switch SET value=30 WHERE id=1"));
                }
            } finally { s.execute("DROP TABLE guard_mode_switch"); }
        }
    }
}

