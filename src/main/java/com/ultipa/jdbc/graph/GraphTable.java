package com.ultipa.jdbc.graph;

import java.util.List;
import java.util.Objects;

/**
 * An immutable nested table: GQLDB's {@code TABLE} value type, which appears as a single column
 * value rather than as a result set of its own.
 *
 * <p>It is exposed as a value object rather than as a nested {@link java.sql.ResultSet} because
 * JDBC has no concept of a result set embedded in a column, and converting one would invent
 * cursor semantics the protocol does not define.
 */
public final class GraphTable {

    private final List<String> columns;
    private final List<List<Object>> rows;

    /**
     * Creates a nested table.
     *
     * @param columns the column names; never {@code null}
     * @param rows    the rows, each a list of already-converted values; never {@code null}
     */
    public GraphTable(List<String> columns, List<List<Object>> rows) {
        this.columns = columns == null ? List.of() : List.copyOf(columns);
        if (rows == null) {
            this.rows = List.of();
        } else {
            this.rows = rows.stream()
                    .map(row -> row == null ? List.<Object>of() : List.copyOf(row))
                    .toList();
        }
    }

    /** Returns the table's column names. */
    public List<String> getColumns() {
        return columns;
    }

    /** Returns the table's rows. */
    public List<List<Object>> getRows() {
        return rows;
    }

    /** Returns the number of columns. */
    public int getColumnCount() {
        return columns.size();
    }

    /** Returns the number of rows. */
    public int getRowCount() {
        return rows.size();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof GraphTable other)) {
            return false;
        }
        return columns.equals(other.columns) && rows.equals(other.rows);
    }

    @Override
    public int hashCode() {
        return Objects.hash(columns, rows);
    }

    @Override
    public String toString() {
        return "GraphTable{columns=" + columns + ", rowCount=" + rows.size() + '}';
    }
}
