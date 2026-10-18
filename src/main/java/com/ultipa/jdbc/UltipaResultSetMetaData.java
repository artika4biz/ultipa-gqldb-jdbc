package com.ultipa.jdbc;

import com.gqldb.types.PropertyType;

import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.List;

/**
 * Describes the columns of an {@link UltipaResultSet}.
 *
 * <h2>Why types are inferred</h2>
 *
 * <p>GQLDB's wire protocol carries a result's column <em>names</em> only: {@code GqlResponse}
 * has a {@code repeated string columns} field and no type descriptors. Types travel per value,
 * on each cell. That is a consequence of the data model, not an omission — a graph query's
 * column can legitimately hold a different type in each row.
 *
 * <p>This class therefore reports the type of each column as observed <strong>in the first
 * row</strong> of the result. That is the most useful answer available, and it is accurate for
 * the overwhelmingly common case of a homogeneously typed column. Two consequences follow, and
 * neither is hidden:
 *
 * <ul>
 *   <li>If a column's type varies between rows, the reported type describes the first row only.
 *       {@link java.sql.ResultSet#getObject(int)} always returns the actual value's type, so
 *       reading values is unaffected.</li>
 *   <li>For an empty result there is no row to observe, so every column is reported as
 *       {@link java.sql.Types#NULL} with type name {@code "null"} — not as a guess.</li>
 * </ul>
 *
 * <p>Nullability is reported as {@link #columnNullableUnknown} for the same reason: the protocol
 * states no per-column nullability for query results, and a {@code NOT NULL} constraint on a
 * property does not carry through an arbitrary projection.
 */
public final class UltipaResultSetMetaData implements ResultSetMetaData {

    private final List<String> columnLabels;
    private final List<PropertyType> columnTypes;

    /**
     * Creates column metadata.
     *
     * @param columnLabels the column labels, in result order
     * @param columnTypes  the type observed for each column, aligned with {@code columnLabels};
     *                     an entry may be {@link PropertyType#NULL} when no value was available
     */
    UltipaResultSetMetaData(List<String> columnLabels, List<PropertyType> columnTypes) {
        this.columnLabels = List.copyOf(columnLabels);
        this.columnTypes = List.copyOf(columnTypes);
    }

    @Override
    public int getColumnCount() {
        return columnLabels.size();
    }

    private void checkColumn(int column) throws SQLException {
        if (column < 1 || column > columnLabels.size()) {
            throw UltipaExceptionMapper.invalidArgument(
                    "Column index " + column + " is out of range 1.." + columnLabels.size());
        }
    }

    private PropertyType typeOf(int column) throws SQLException {
        checkColumn(column);
        PropertyType type = columnTypes.get(column - 1);
        return type == null ? PropertyType.NULL : type;
    }

    @Override
    public String getColumnLabel(int column) throws SQLException {
        checkColumn(column);
        return columnLabels.get(column - 1);
    }

    /**
     * {@inheritDoc}
     *
     * <p>The same value as {@link #getColumnLabel(int)}. A GQL result column is a projection
     * expression with an alias; the protocol reports one name for it, so there is no separate
     * underlying column name to give.
     */
    @Override
    public String getColumnName(int column) throws SQLException {
        return getColumnLabel(column);
    }

    @Override
    public int getColumnType(int column) throws SQLException {
        return UltipaTypeMapper.toJdbcType(typeOf(column));
    }

    @Override
    public String getColumnTypeName(int column) throws SQLException {
        return UltipaTypeMapper.typeName(typeOf(column));
    }

    @Override
    public String getColumnClassName(int column) throws SQLException {
        return UltipaTypeMapper.javaClassName(typeOf(column));
    }

    @Override
    public int getColumnDisplaySize(int column) throws SQLException {
        return UltipaTypeMapper.displaySize(typeOf(column));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Always {@link #columnNullableUnknown}: query results carry no per-column nullability.
     */
    @Override
    public int isNullable(int column) throws SQLException {
        checkColumn(column);
        return columnNullableUnknown;
    }

    @Override
    public boolean isSigned(int column) throws SQLException {
        return switch (typeOf(column)) {
            case INT32, INT64, FLOAT, DOUBLE, DECIMAL, YEAR_TO_MONTH, DAY_TO_SECOND -> true;
            default -> false;
        };
    }

    /**
     * {@inheritDoc}
     *
     * <p>Precision is reported only where the type fixes it. {@code DECIMAL} has no declared
     * precision in a query result, and variable-width types have none, so both report {@code 0}
     * rather than an invented bound.
     */
    @Override
    public int getPrecision(int column) throws SQLException {
        return switch (typeOf(column)) {
            case BOOL -> 1;
            case INT32 -> 10;
            case UINT32 -> 10;
            case INT64, UINT64 -> 19;
            case FLOAT -> 7;
            case DOUBLE -> 15;
            default -> 0;
        };
    }

    /**
     * {@inheritDoc}
     *
     * <p>Always {@code 0}: GQLDB query results do not declare a scale, not even for
     * {@code DECIMAL}, whose value carries its own scale.
     */
    @Override
    public int getScale(int column) throws SQLException {
        checkColumn(column);
        return 0;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Always {@code true}: every result set this driver produces is read-only.
     */
    @Override
    public boolean isReadOnly(int column) throws SQLException {
        checkColumn(column);
        return true;
    }

    @Override
    public boolean isWritable(int column) throws SQLException {
        checkColumn(column);
        return false;
    }

    @Override
    public boolean isDefinitelyWritable(int column) throws SQLException {
        checkColumn(column);
        return false;
    }

    @Override
    public boolean isAutoIncrement(int column) throws SQLException {
        checkColumn(column);
        return false;
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@code true} for the textual types, whose comparison in GQLDB is case-sensitive, and
     * {@code false} for every other type, for which case has no meaning.
     */
    @Override
    public boolean isCaseSensitive(int column) throws SQLException {
        return switch (typeOf(column)) {
            case STRING, TEXT -> true;
            default -> false;
        };
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@code true} for the scalar types that can appear in a {@code WHERE} predicate, and
     * {@code false} for graph-specific values, which have no general comparison semantics in a
     * search condition.
     */
    @Override
    public boolean isSearchable(int column) throws SQLException {
        PropertyType type = typeOf(column);
        return !UltipaTypeMapper.isGraphSpecific(type) && type != PropertyType.NULL;
    }

    @Override
    public boolean isCurrency(int column) throws SQLException {
        checkColumn(column);
        return false;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Always {@code ""}: a GQL result column is a projection, not necessarily a property of a
     * single label, so no originating table can be named reliably.
     */
    @Override
    public String getTableName(int column) throws SQLException {
        checkColumn(column);
        return "";
    }

    /**
     * {@inheritDoc}
     *
     * <p>Always {@code ""}: GQLDB has no schema level.
     */
    @Override
    public String getSchemaName(int column) throws SQLException {
        checkColumn(column);
        return "";
    }

    /**
     * {@inheritDoc}
     *
     * <p>Always {@code ""}: a projected column cannot be attributed to a specific graph.
     */
    @Override
    public String getCatalogName(int column) throws SQLException {
        checkColumn(column);
        return "";
    }

    @Override
    public <T> T unwrap(Class<T> target) throws SQLException {
        if (target != null && target.isInstance(this)) {
            return target.cast(this);
        }
        throw UltipaExceptionMapper.invalidArgument(
                "UltipaResultSetMetaData cannot be unwrapped to "
                        + (target == null ? "null" : target.getName()));
    }

    @Override
    public boolean isWrapperFor(Class<?> target) {
        return target != null && target.isInstance(this);
    }
}
