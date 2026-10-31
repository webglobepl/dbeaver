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

import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.select.Select;
import org.jkiss.code.NotNull;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Deliberately conservative SQL subset, used in addition to server read-only transactions.
 * This is accident prevention, not a sandbox for malicious database objects or extensions.
 */
public final class JDBCReadOnlyPolicy {
    private static final Set<String> FORBIDDEN = Set.of(
        "INSERT", "UPDATE", "DELETE", "MERGE", "REPLACE", "CREATE", "ALTER", "DROP", "TRUNCATE",
        "GRANT", "REVOKE", "CALL", "EXEC", "EXECUTE", "DO", "COPY", "LOAD", "INTO", "OUTFILE", "DUMPFILE",
        "FOR", "LOCK", "UNLOCK", "SET", "RESET", "START", "BEGIN", "COMMIT", "ROLLBACK", "SAVEPOINT",
        "PREPARE", "DEALLOCATE", "KILL", "ANALYZE", "VACUUM", "ATTACH", "DETACH", "PRAGMA", "PROCEDURE"
    );
    private static final Set<String> FUNCTIONS = Set.of(
        "COUNT", "SUM", "AVG", "MIN", "MAX", "ABS", "ROUND", "FLOOR", "CEIL", "CEILING",
        "LOWER", "UPPER", "LENGTH", "CHAR_LENGTH", "CONCAT", "CONCAT_WS", "TRIM", "LTRIM", "RTRIM",
        "SUBSTRING", "SUBSTR", "COALESCE", "NULLIF", "CAST", "EXTRACT", "DATE", "YEAR", "MONTH", "DAY",
        "DATABASE", "SCHEMA", "CURRENT_DATABASE", "CURRENT_SCHEMA", "VERSION", "CONNECTION_ID",
        "NOW", "CURRENT_DATE", "CURRENT_TIME", "CURRENT_TIMESTAMP"
    );
    private static final Set<String> GROUPING_KEYWORDS = Set.of(
        "IN", "EXISTS", "NOT", "AS", "OVER", "FILTER", "FROM", "JOIN", "ON", "WHERE", "AND", "OR", "SELECT"
    );

    private JDBCReadOnlyPolicy() {
    }

    public static void check(@NotNull String sql, boolean mysql) throws SQLException {
        String text = sql.strip();
        if (text.endsWith(";")) {
            text = text.substring(0, text.length() - 1);
        }
        if (mysql && text.matches("(?is)SELECT\\s+@@(?:GLOBAL\\.|SESSION\\.)?[a-z_]+(?:\\s*,\\s*@@(?:GLOBAL\\.|SESSION\\.)?[a-z_]+)*\\s*")) {
            return;
        }
        // Reject dialect-dependent quoting, executable comments and batches rather than guessing their meaning.
        if (text.isBlank() || text.contains(";") || text.contains("\\") || text.contains("$")
            || text.contains("@") || text.contains("/*") || text.contains("--") || text.contains("#")) {
            throw rejected();
        }
        List<String> tokens = tokens(text);
        if (tokens.isEmpty()) {
            throw rejected();
        }
        // These two MySQL metadata operations do not execute user-supplied expressions.
        if (mysql && tokens.getFirst().equals("USE") && tokens.size() == 2
            && text.matches("(?is)USE\\s+(?:[a-zA-Z_][a-zA-Z_0-9]*|`(?:[^`]|``)+`)\\s*")) {
            return;
        }
        if (mysql && text.matches("(?is)SHOW\\s+(?:FULL\\s+|GLOBAL\\s+|SESSION\\s+)?(?:DATABASES|SCHEMAS|TABLES|TABLE\\s+STATUS|COLUMNS|FIELDS|INDEX|INDEXES|KEYS|CREATE\\s+(?:TABLE|VIEW)|VARIABLES|STATUS|CHARSET|COLLATION|ENGINES|PLUGINS|PRIVILEGES)\\b.*")
            && tokens.stream().noneMatch(t -> t.equals("(") || t.equals("INTO"))) {
            return;
        }
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if (FORBIDDEN.contains(token)) {
                throw rejected();
            }
            if (i + 1 < tokens.size() && tokens.get(i + 1).equals("(")
                && (token.equals("QUOTED_IDENTIFIER") || token.matches("[A-Z_][A-Z_0-9]*"))) {
                if (i > 0 && tokens.get(i - 1).equals(".")
                    || !FUNCTIONS.contains(token) && !GROUPING_KEYWORDS.contains(token)) {
                    throw rejected();
                }
            }
        }
        try {
            if (!(CCJSqlParserUtil.parse(text) instanceof Select)) {
                throw rejected();
            }
        } catch (SQLException e) {
            throw e;
        } catch (Exception e) {
            throw new SQLException("Protected read-only: unrecognized SQL is blocked.", e);
        }
    }

    @NotNull
    private static List<String> tokens(@NotNull String sql) throws SQLException {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < sql.length();) {
            char ch = sql.charAt(i);
            if (Character.isWhitespace(ch)) {
                i++;
            } else if (ch == '\'' || ch == '"' || ch == '`') {
                char quote = ch;
                boolean closed = false;
                i++;
                while (i < sql.length()) {
                    if (sql.charAt(i++) == quote) {
                        if (i < sql.length() && sql.charAt(i) == quote) {
                            i++;
                        } else {
                            closed = true;
                            break;
                        }
                    }
                }
                if (!closed) {
                    throw rejected();
                }
                result.add(quote == '\'' ? "STRING_LITERAL" : "QUOTED_IDENTIFIER");
            } else if (Character.isLetter(ch) || ch == '_') {
                int start = i++;
                while (i < sql.length() && (Character.isLetterOrDigit(sql.charAt(i)) || sql.charAt(i) == '_')) {
                    i++;
                }
                result.add(sql.substring(start, i).toUpperCase(Locale.ROOT));
            } else {
                result.add(String.valueOf(ch));
                i++;
            }
        }
        return result;
    }

    @NotNull
    private static SQLException rejected() {
        return new SQLException("Protected read-only: only recognized read queries and approved built-in functions are allowed. "
            + "Commands, locking reads, comments, batches and ambiguous syntax are blocked in this prototype.");
    }
}
