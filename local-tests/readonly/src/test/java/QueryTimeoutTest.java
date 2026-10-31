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
import java.sql.SQLException;
import static org.junit.jupiter.api.Assertions.*;

class QueryTimeoutTest {
    @ParameterizedTest @ValueSource(booleans = {true, false})
    void expensiveReadIsCancelled(boolean mysql) throws SQLException {
        try (var c = JDBCProtectedReadOnlyConnection.protect(ProtectedReadOnlyTest.raw(mysql)); var s = c.createStatement()) {
            String sql = "WITH RECURSIVE n AS (SELECT 1 AS x UNION ALL SELECT x + 1 FROM n WHERE x < 500) "
                + "SELECT SUM(a.x * b.x + d.x) FROM n a CROSS JOIN n b CROSS JOIN n d CROSS JOIN n e";
            long start = System.nanoTime();
            SQLException error = assertThrows(SQLException.class, () -> s.executeQuery(sql));
            long elapsed = (System.nanoTime() - start) / 1_000_000_000;
            assertFalse(error.getMessage().startsWith("Protected read-only:"));
            assertTrue(elapsed >= 25 && elapsed <= 45, "Expected 30-second query timeout, got " + elapsed + ": " + error);
        }
    }
}

