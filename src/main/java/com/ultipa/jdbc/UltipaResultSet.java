package com.ultipa.jdbc;

import com.gqldb.Response;
import com.gqldb.Row;
import com.gqldb.types.PropertyType;
import com.gqldb.types.TypedValue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A read-only, forward-only {@link ResultSet} over one GQLDB response.
 *
 * <h2>Cursor and buffering</h2>
 *
 * <p>The SDK returns a response with its rows already materialised, so this class wraps a list
 * rather than streaming. The cursor still behaves as JDBC requires: it starts before the first
 * row, {@link #next()} advances it, and reading a value before the first {@code next()} or after
 * the last row is an error rather than a silent {@code null}.
 *
 * <p>Because every row is already in memory, {@link #setFetchSize(int)} has nothing to tune and
 * is accepted and ignored. {@link java.sql.Statement#setMaxRows(int)} is honoured: rows beyond
 * the limit are not exposed.
 *
 * <h2>Values</h2>
 *
 * <p>{@link #getObject(int)} returns the mapping described in {@link UltipaTypeMapper}: standard
 * Java types for scalars and temporals, and the value types in {@link com.ultipa.jdbc.graph} for
 * nodes, edges, paths, points, nested tables and error values. Graph structure is preserved, not
 * flattened.
 *
 * <p>The typed getters convert where a conversion is well defined — a numeric string to a
 * number, a number to a wider number, a temporal to its {@code java.sql} counterpart — and raise
 * a {@link SQLException} naming both types where it is not, instead of returning a zero or a
 * truncated value. Numeric conversions are range-checked, so reading a value too large for the
 * requested type fails rather than wrapping around.
 *
 * <p>{@link #wasNull()} reflects the last column read through any getter, as JDBC specifies.
 */
public final class UltipaResultSet implements ResultSet {

    private final UltipaStatement owner;
    private final List<String> columnLabels;
    private final Map<String, Integer> columnIndexByLabel;
    private final Map<String, Integer> columnIndexByLowerLabel;
    private final List<Row> rows;

    private int cursor = -1;
    private boolean wasNull;
    private boolean closed;
    private SQLWarning warnings;

    /**
     * Wraps a response.
     *
     * @param owner    the statement that produced this result set, or {@code null} for a result
     *                 set the driver built itself, such as one from {@code DatabaseMetaData}
     * @param response the server response to expose
     * @param maxRows  the maximum number of rows to expose, or {@code 0} for all of them
     */
    UltipaResultSet(UltipaStatement owner, Response response, int maxRows) {
        this(owner, response.getColumns(), limit(response.getRows(), maxRows));
    }

    /**
     * Wraps rows assembled by the driver, used for {@code DatabaseMetaData} results.
     *
     * @param owner        the owning statement, may be {@code null}
     * @param columnLabels the column labels
     * @param rows         the rows
     */
    UltipaResultSet(UltipaStatement owner, List<String> columnLabels, List<Row> rows) {
        this.owner = owner;
        this.columnLabels = columnLabels == null ? List.of() : List.copyOf(columnLabels);
        this.rows = rows == null ? List.of() : List.copyOf(rows);

        Map<String, Integer> byLabel = new LinkedHashMap<>();
        Map<String, Integer> byLowerLabel = new LinkedHashMap<>();
        for (int i = 0; i < this.columnLabels.size(); i++) {
            String label = this.columnLabels.get(i);
            if (label == null) {
                continue;
            }
            // First occurrence wins, as JDBC specifies for duplicate labels.
            byLabel.putIfAbsent(label, i + 1);
            byLowerLabel.putIfAbsent(label.toLowerCase(Locale.ROOT), i + 1);
        }
        this.columnIndexByLabel = byLabel;
        this.columnIndexByLowerLabel = byLowerLabel;
    }

    private static List<Row> limit(List<Row> source, int maxRows) {
        if (source == null) {
            return List.of();
        }
        if (maxRows <= 0 || source.size() <= maxRows) {
            return source;
        }
        return source.subList(0, maxRows);
    }

    // ---------------------------------------------------------------------
    // Cursor
    // ---------------------------------------------------------------------

    @Override
    public boolean next() throws SQLException {
        checkOpen();
        if (cursor >= rows.size()) {
            return false;
        }
        cursor++;
        wasNull = false;
        return cursor < rows.size();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        cursor = rows.size();
        if (owner != null) {
            owner.resultSetClosed(this);
        }
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public boolean wasNull() throws SQLException {
        checkOpen();
        return wasNull;
    }

    @Override
    public int getRow() throws SQLException {
        checkOpen();
        return isOnRow() ? cursor + 1 : 0;
    }

    @Override
    public boolean isBeforeFirst() throws SQLException {
        checkOpen();
        return cursor < 0 && !rows.isEmpty();
    }

    @Override
    public boolean isAfterLast() throws SQLException {
        checkOpen();
        return cursor >= rows.size() && !rows.isEmpty();
    }

    @Override
    public boolean isFirst() throws SQLException {
        checkOpen();
        return cursor == 0 && !rows.isEmpty();
    }

    @Override
    public boolean isLast() throws SQLException {
        checkOpen();
        return !rows.isEmpty() && cursor == rows.size() - 1;
    }

    @Override
    public int getType() throws SQLException {
        checkOpen();
        return TYPE_FORWARD_ONLY;
    }

    @Override
    public int getConcurrency() throws SQLException {
        checkOpen();
        return CONCUR_READ_ONLY;
    }

    @Override
    public int getHoldability() throws SQLException {
        checkOpen();
        return HOLD_CURSORS_OVER_COMMIT;
    }

    @Override
    public void setFetchDirection(int direction) throws SQLException {
        checkOpen();
        if (direction != FETCH_FORWARD) {
            throw UltipaExceptionMapper.unsupported(
                    "Fetch direction other than FETCH_FORWARD on a forward-only result set");
        }
    }

    @Override
    public int getFetchDirection() throws SQLException {
        checkOpen();
        return FETCH_FORWARD;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Accepted and ignored: the rows of a response are already in memory, so there is no
     * fetch size to tune. A negative value is rejected, as the contract requires.
     */
    @Override
    public void setFetchSize(int size) throws SQLException {
        checkOpen();
        if (size < 0) {
            throw UltipaExceptionMapper.invalidArgument("Fetch size must not be negative");
        }
    }

    @Override
    public int getFetchSize() throws SQLException {
        checkOpen();
        return 0;
    }

    @Override
    public Statement getStatement() throws SQLException {
        checkOpen();
        return owner;
    }

    @Override
    public SQLWarning getWarnings() throws SQLException {
        checkOpen();
        return warnings;
    }

    @Override
    public void clearWarnings() throws SQLException {
        checkOpen();
        warnings = null;
    }

    @Override
    public ResultSetMetaData getMetaData() throws SQLException {
        checkOpen();
        List<PropertyType> types = new ArrayList<>(columnLabels.size());
        Row firstRow = rows.isEmpty() ? null : rows.get(0);
        for (int i = 0; i < columnLabels.size(); i++) {
            TypedValue value = firstRow == null || i >= firstRow.size()
                    ? null
                    : firstRow.getTypedValue(i);
            types.add(value == null ? PropertyType.NULL : value.getType());
        }
        return new UltipaResultSetMetaData(columnLabels, types);
    }

    @Override
    public int findColumn(String columnLabel) throws SQLException {
        checkOpen();
        if (columnLabel == null) {
            throw UltipaExceptionMapper.invalidArgument("Column label must not be null");
        }
        Integer index = columnIndexByLabel.get(columnLabel);
        if (index == null) {
            index = columnIndexByLowerLabel.get(columnLabel.toLowerCase(Locale.ROOT));
        }
        if (index == null) {
            throw UltipaExceptionMapper.invalidArgument(
                    "No column labelled \"" + columnLabel + "\"; available columns: " + columnLabels);
        }
        return index;
    }

    // ---------------------------------------------------------------------
    // Value access
    // ---------------------------------------------------------------------

    private void checkOpen() throws SQLException {
        if (closed) {
            throw UltipaExceptionMapper.closed("ResultSet");
        }
    }

    private boolean isOnRow() {
        return cursor >= 0 && cursor < rows.size();
    }

    private TypedValue typedValueAt(int columnIndex) throws SQLException {
        checkOpen();
        if (!isOnRow()) {
            throw UltipaExceptionMapper.invalidCursorState(
                    cursor < 0
                            ? "The cursor is before the first row; call next() first"
                            : "The cursor is after the last row");
        }
        Row row = rows.get(cursor);
        if (columnIndex < 1 || columnIndex > columnLabels.size()) {
            throw UltipaExceptionMapper.invalidArgument(
                    "Column index " + columnIndex + " is out of range 1.." + columnLabels.size());
        }
        if (columnIndex > row.size()) {
            // A short row is a protocol-level inconsistency; report it rather than invent a null.
            throw UltipaExceptionMapper.invalidArgument(
                    "Row " + (cursor + 1) + " has only " + row.size() + " values but column "
                            + columnIndex + " was requested");
        }
        return row.getTypedValue(columnIndex - 1);
    }

    /** Reads a column and records whether it was null, as {@link #wasNull()} reports. */
    private Object valueAt(int columnIndex) throws SQLException {
        Object converted = UltipaTypeMapper.toJavaObject(typedValueAt(columnIndex));
        wasNull = converted == null;
        return converted;
    }

    private PropertyType typeAt(int columnIndex) throws SQLException {
        TypedValue value = typedValueAt(columnIndex);
        return value == null ? PropertyType.NULL : value.getType();
    }

    private SQLException cannotConvert(int columnIndex, Object value, String target) throws SQLException {
        String from = value == null ? "null" : value.getClass().getSimpleName();
        return UltipaExceptionMapper.invalidArgument(
                "Column " + columnIndex + " (" + UltipaTypeMapper.typeName(typeAt(columnIndex))
                        + ", read as " + from + ") cannot be converted to " + target);
    }

    @Override
    public Object getObject(int columnIndex) throws SQLException {
        return valueAt(columnIndex);
    }

    @Override
    public Object getObject(String columnLabel) throws SQLException {
        return getObject(findColumn(columnLabel));
    }

    @Override
    public String getString(int columnIndex) throws SQLException {
        return UltipaTypeMapper.renderAsString(valueAt(columnIndex));
    }

    @Override
    public String getString(String columnLabel) throws SQLException {
        return getString(findColumn(columnLabel));
    }

    @Override
    public String getNString(int columnIndex) throws SQLException {
        return getString(columnIndex);
    }

    @Override
    public String getNString(String columnLabel) throws SQLException {
        return getString(columnLabel);
    }

    @Override
    public boolean getBoolean(int columnIndex) throws SQLException {
        Object value = valueAt(columnIndex);
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean flag) {
            return flag;
        }
        if (value instanceof Number number) {
            return number.doubleValue() != 0.0d;
        }
        if (value instanceof String text) {
            String normalised = text.trim().toLowerCase(Locale.ROOT);
            switch (normalised) {
                case "true", "t", "yes", "y", "on", "1":
                    return true;
                case "false", "f", "no", "n", "off", "0", "":
                    return false;
                default:
                    break;
            }
            try {
                return new BigDecimal(normalised).signum() != 0;
            } catch (NumberFormatException e) {
                throw cannotConvert(columnIndex, value, "boolean");
            }
        }
        throw cannotConvert(columnIndex, value, "boolean");
    }

    @Override
    public boolean getBoolean(String columnLabel) throws SQLException {
        return getBoolean(findColumn(columnLabel));
    }

    private BigDecimal toBigDecimal(int columnIndex, Object value, String target) throws SQLException {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Integer || value instanceof Long || value instanceof Short
                || value instanceof Byte) {
            return BigDecimal.valueOf(((Number) value).longValue());
        }
        if (value instanceof Double || value instanceof Float) {
            double raw = ((Number) value).doubleValue();
            if (Double.isNaN(raw) || Double.isInfinite(raw)) {
                throw UltipaExceptionMapper.invalidArgument(
                        "Column " + columnIndex + " holds " + raw + ", which has no " + target
                                + " representation");
            }
            return BigDecimal.valueOf(raw);
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        if (value instanceof Boolean flag) {
            return flag ? BigDecimal.ONE : BigDecimal.ZERO;
        }
        if (value instanceof String text) {
            try {
                return new BigDecimal(text.trim());
            } catch (NumberFormatException e) {
                throw cannotConvert(columnIndex, value, target);
            }
        }
        throw cannotConvert(columnIndex, value, target);
    }

    private long getExactLong(int columnIndex, long minimum, long maximum, String target)
            throws SQLException {
        Object value = valueAt(columnIndex);
        if (value == null) {
            return 0L;
        }
        BigDecimal decimal = toBigDecimal(columnIndex, value, target);
        long result;
        try {
            result = decimal.setScale(0, RoundingMode.DOWN).longValueExact();
        } catch (ArithmeticException e) {
            throw UltipaExceptionMapper.invalidArgument(
                    "Column " + columnIndex + " holds " + decimal + ", which is out of range for "
                            + target);
        }
        if (result < minimum || result > maximum) {
            throw UltipaExceptionMapper.invalidArgument(
                    "Column " + columnIndex + " holds " + decimal + ", which is out of range for "
                            + target);
        }
        return result;
    }

    @Override
    public byte getByte(int columnIndex) throws SQLException {
        return (byte) getExactLong(columnIndex, Byte.MIN_VALUE, Byte.MAX_VALUE, "byte");
    }

    @Override
    public byte getByte(String columnLabel) throws SQLException {
        return getByte(findColumn(columnLabel));
    }

    @Override
    public short getShort(int columnIndex) throws SQLException {
        return (short) getExactLong(columnIndex, Short.MIN_VALUE, Short.MAX_VALUE, "short");
    }

    @Override
    public short getShort(String columnLabel) throws SQLException {
        return getShort(findColumn(columnLabel));
    }

    @Override
    public int getInt(int columnIndex) throws SQLException {
        return (int) getExactLong(columnIndex, Integer.MIN_VALUE, Integer.MAX_VALUE, "int");
    }

    @Override
    public int getInt(String columnLabel) throws SQLException {
        return getInt(findColumn(columnLabel));
    }

    @Override
    public long getLong(int columnIndex) throws SQLException {
        return getExactLong(columnIndex, Long.MIN_VALUE, Long.MAX_VALUE, "long");
    }

    @Override
    public long getLong(String columnLabel) throws SQLException {
        return getLong(findColumn(columnLabel));
    }

    @Override
    public float getFloat(int columnIndex) throws SQLException {
        return (float) getDouble(columnIndex);
    }

    @Override
    public float getFloat(String columnLabel) throws SQLException {
        return getFloat(findColumn(columnLabel));
    }

    @Override
    public double getDouble(int columnIndex) throws SQLException {
        Object value = valueAt(columnIndex);
        if (value == null) {
            return 0.0d;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof Boolean flag) {
            return flag ? 1.0d : 0.0d;
        }
        if (value instanceof String text) {
            try {
                return Double.parseDouble(text.trim());
            } catch (NumberFormatException e) {
                throw cannotConvert(columnIndex, value, "double");
            }
        }
        throw cannotConvert(columnIndex, value, "double");
    }

    @Override
    public double getDouble(String columnLabel) throws SQLException {
        return getDouble(findColumn(columnLabel));
    }

    @Override
    public BigDecimal getBigDecimal(int columnIndex) throws SQLException {
        Object value = valueAt(columnIndex);
        if (value == null) {
            return null;
        }
        return toBigDecimal(columnIndex, value, "BigDecimal");
    }

    @Override
    public BigDecimal getBigDecimal(String columnLabel) throws SQLException {
        return getBigDecimal(findColumn(columnLabel));
    }

    /**
     * {@inheritDoc}
     *
     * @deprecated as in {@link ResultSet}; use {@link #getBigDecimal(int)} and set the scale on
     *         the returned value.
     */
    @Deprecated
    @Override
    public BigDecimal getBigDecimal(int columnIndex, int scale) throws SQLException {
        BigDecimal value = getBigDecimal(columnIndex);
        return value == null ? null : value.setScale(scale, RoundingMode.HALF_UP);
    }

    /**
     * {@inheritDoc}
     *
     * @deprecated as in {@link ResultSet}; use {@link #getBigDecimal(String)}.
     */
    @Deprecated
    @Override
    public BigDecimal getBigDecimal(String columnLabel, int scale) throws SQLException {
        return getBigDecimal(findColumn(columnLabel), scale);
    }

    @Override
    public byte[] getBytes(int columnIndex) throws SQLException {
        Object value = valueAt(columnIndex);
        if (value == null) {
            return null;
        }
        if (value instanceof byte[] bytes) {
            return bytes.clone();
        }
        if (value instanceof String text) {
            return text.getBytes(StandardCharsets.UTF_8);
        }
        throw cannotConvert(columnIndex, value, "byte[]");
    }

    @Override
    public byte[] getBytes(String columnLabel) throws SQLException {
        return getBytes(findColumn(columnLabel));
    }

    // ---------------------------------------------------------------------
    // Temporal values
    // ---------------------------------------------------------------------

    private ZoneId zoneOf(Calendar calendar) {
        return calendar == null ? ZoneId.systemDefault() : calendar.getTimeZone().toZoneId();
    }

    private LocalDate toLocalDate(int columnIndex, Object value, ZoneId zone) throws SQLException {
        if (value instanceof LocalDate date) {
            return date;
        }
        if (value instanceof LocalDateTime dateTime) {
            return dateTime.toLocalDate();
        }
        if (value instanceof OffsetDateTime dateTime) {
            return dateTime.atZoneSameInstant(zone).toLocalDate();
        }
        if (value instanceof Instant instant) {
            return instant.atZone(zone).toLocalDate();
        }
        if (value instanceof String text) {
            try {
                return LocalDate.parse(text.trim());
            } catch (RuntimeException e) {
                throw cannotConvert(columnIndex, value, "java.sql.Date");
            }
        }
        throw cannotConvert(columnIndex, value, "java.sql.Date");
    }

    private LocalTime toLocalTime(int columnIndex, Object value, ZoneId zone) throws SQLException {
        if (value instanceof LocalTime time) {
            return time;
        }
        if (value instanceof OffsetTime time) {
            return time.withOffsetSameInstant(
                    zone.getRules().getOffset(Instant.now())).toLocalTime();
        }
        if (value instanceof LocalDateTime dateTime) {
            return dateTime.toLocalTime();
        }
        if (value instanceof OffsetDateTime dateTime) {
            return dateTime.atZoneSameInstant(zone).toLocalTime();
        }
        if (value instanceof Instant instant) {
            return instant.atZone(zone).toLocalTime();
        }
        if (value instanceof String text) {
            try {
                return LocalTime.parse(text.trim());
            } catch (RuntimeException e) {
                throw cannotConvert(columnIndex, value, "java.sql.Time");
            }
        }
        throw cannotConvert(columnIndex, value, "java.sql.Time");
    }

    private LocalDateTime toLocalDateTime(int columnIndex, Object value, ZoneId zone)
            throws SQLException {
        if (value instanceof LocalDateTime dateTime) {
            return dateTime;
        }
        if (value instanceof OffsetDateTime dateTime) {
            return dateTime.atZoneSameInstant(zone).toLocalDateTime();
        }
        if (value instanceof Instant instant) {
            return instant.atZone(zone).toLocalDateTime();
        }
        if (value instanceof LocalDate date) {
            return date.atStartOfDay();
        }
        if (value instanceof LocalTime time) {
            return time.atDate(LocalDate.EPOCH);
        }
        if (value instanceof String text) {
            try {
                return LocalDateTime.parse(text.trim());
            } catch (RuntimeException e) {
                throw cannotConvert(columnIndex, value, "java.sql.Timestamp");
            }
        }
        throw cannotConvert(columnIndex, value, "java.sql.Timestamp");
    }

    @Override
    public Date getDate(int columnIndex) throws SQLException {
        return getDate(columnIndex, null);
    }

    @Override
    public Date getDate(String columnLabel) throws SQLException {
        return getDate(findColumn(columnLabel), null);
    }

    @Override
    public Date getDate(int columnIndex, Calendar calendar) throws SQLException {
        Object value = valueAt(columnIndex);
        if (value == null) {
            return null;
        }
        return Date.valueOf(toLocalDate(columnIndex, value, zoneOf(calendar)));
    }

    @Override
    public Date getDate(String columnLabel, Calendar calendar) throws SQLException {
        return getDate(findColumn(columnLabel), calendar);
    }

    @Override
    public Time getTime(int columnIndex) throws SQLException {
        return getTime(columnIndex, null);
    }

    @Override
    public Time getTime(String columnLabel) throws SQLException {
        return getTime(findColumn(columnLabel), null);
    }

    @Override
    public Time getTime(int columnIndex, Calendar calendar) throws SQLException {
        Object value = valueAt(columnIndex);
        if (value == null) {
            return null;
        }
        return Time.valueOf(toLocalTime(columnIndex, value, zoneOf(calendar)));
    }

    @Override
    public Time getTime(String columnLabel, Calendar calendar) throws SQLException {
        return getTime(findColumn(columnLabel), calendar);
    }

    @Override
    public Timestamp getTimestamp(int columnIndex) throws SQLException {
        return getTimestamp(columnIndex, null);
    }

    @Override
    public Timestamp getTimestamp(String columnLabel) throws SQLException {
        return getTimestamp(findColumn(columnLabel), null);
    }

    @Override
    public Timestamp getTimestamp(int columnIndex, Calendar calendar) throws SQLException {
        Object value = valueAt(columnIndex);
        if (value == null) {
            return null;
        }
        return Timestamp.valueOf(toLocalDateTime(columnIndex, value, zoneOf(calendar)));
    }

    @Override
    public Timestamp getTimestamp(String columnLabel, Calendar calendar) throws SQLException {
        return getTimestamp(findColumn(columnLabel), calendar);
    }

    // ---------------------------------------------------------------------
    // Streams
    // ---------------------------------------------------------------------

    @Override
    public InputStream getBinaryStream(int columnIndex) throws SQLException {
        byte[] bytes = getBytes(columnIndex);
        return bytes == null ? null : new ByteArrayInputStream(bytes);
    }

    @Override
    public InputStream getBinaryStream(String columnLabel) throws SQLException {
        return getBinaryStream(findColumn(columnLabel));
    }

    @Override
    public Reader getCharacterStream(int columnIndex) throws SQLException {
        String text = getString(columnIndex);
        return text == null ? null : new StringReader(text);
    }

    @Override
    public Reader getCharacterStream(String columnLabel) throws SQLException {
        return getCharacterStream(findColumn(columnLabel));
    }

    @Override
    public Reader getNCharacterStream(int columnIndex) throws SQLException {
        return getCharacterStream(columnIndex);
    }

    @Override
    public Reader getNCharacterStream(String columnLabel) throws SQLException {
        return getCharacterStream(columnLabel);
    }

    /**
     * {@inheritDoc}
     *
     * <p>The stream carries the column's text as UTF-8 rather than ASCII: GQLDB strings are
     * UTF-8, and silently replacing non-ASCII characters would corrupt data.
     */
    @Override
    public InputStream getAsciiStream(int columnIndex) throws SQLException {
        String text = getString(columnIndex);
        return text == null
                ? null
                : new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public InputStream getAsciiStream(String columnLabel) throws SQLException {
        return getAsciiStream(findColumn(columnLabel));
    }

    /**
     * {@inheritDoc}
     *
     * @deprecated as in {@link ResultSet}; use {@link #getCharacterStream(int)}.
     */
    @Deprecated
    @Override
    public InputStream getUnicodeStream(int columnIndex) throws SQLException {
        throw UltipaExceptionMapper.unsupported(
                "getUnicodeStream (deprecated); use getCharacterStream");
    }

    /**
     * {@inheritDoc}
     *
     * @deprecated as in {@link ResultSet}; use {@link #getCharacterStream(String)}.
     */
    @Deprecated
    @Override
    public InputStream getUnicodeStream(String columnLabel) throws SQLException {
        throw UltipaExceptionMapper.unsupported(
                "getUnicodeStream (deprecated); use getCharacterStream");
    }

    // ---------------------------------------------------------------------
    // getObject with a target type
    // ---------------------------------------------------------------------

    @Override
    public <T> T getObject(int columnIndex, Class<T> requestedType) throws SQLException {
        if (requestedType == null) {
            throw UltipaExceptionMapper.invalidArgument("Target type must not be null");
        }
        // A primitive class literal cannot hold the boxed result, and Class.cast would throw an
        // unchecked ClassCastException; use its wrapper and let the caller unbox.
        @SuppressWarnings("unchecked")
        Class<T> type = (Class<T>) wrapperFor(requestedType);

        if (type == String.class) {
            return type.cast(getString(columnIndex));
        }
        if (type == Boolean.class) {
            Object raw = peek(columnIndex);
            return raw == null ? null : type.cast(getBoolean(columnIndex));
        }
        if (type == Byte.class) {
            Object raw = peek(columnIndex);
            return raw == null ? null : type.cast(getByte(columnIndex));
        }
        if (type == Short.class) {
            Object raw = peek(columnIndex);
            return raw == null ? null : type.cast(getShort(columnIndex));
        }
        if (type == Integer.class) {
            Object raw = peek(columnIndex);
            return raw == null ? null : type.cast(getInt(columnIndex));
        }
        if (type == Long.class) {
            Object raw = peek(columnIndex);
            return raw == null ? null : type.cast(getLong(columnIndex));
        }
        if (type == Float.class) {
            Object raw = peek(columnIndex);
            return raw == null ? null : type.cast(getFloat(columnIndex));
        }
        if (type == Double.class) {
            Object raw = peek(columnIndex);
            return raw == null ? null : type.cast(getDouble(columnIndex));
        }
        if (type == BigDecimal.class) {
            return type.cast(getBigDecimal(columnIndex));
        }
        if (type == byte[].class) {
            return type.cast(getBytes(columnIndex));
        }
        if (type == Date.class) {
            return type.cast(getDate(columnIndex));
        }
        if (type == Time.class) {
            return type.cast(getTime(columnIndex));
        }
        if (type == Timestamp.class) {
            return type.cast(getTimestamp(columnIndex));
        }

        Object value = valueAt(columnIndex);
        if (value == null) {
            return null;
        }
        ZoneId zone = ZoneId.systemDefault();
        if (type == LocalDate.class) {
            return type.cast(toLocalDate(columnIndex, value, zone));
        }
        if (type == LocalTime.class) {
            return type.cast(toLocalTime(columnIndex, value, zone));
        }
        if (type == LocalDateTime.class) {
            return type.cast(toLocalDateTime(columnIndex, value, zone));
        }
        if (type == Instant.class) {
            if (value instanceof Instant instant) {
                return type.cast(instant);
            }
            return type.cast(toLocalDateTime(columnIndex, value, zone).atZone(zone).toInstant());
        }
        if (type == OffsetDateTime.class) {
            if (value instanceof OffsetDateTime dateTime) {
                return type.cast(dateTime);
            }
            return type.cast(toLocalDateTime(columnIndex, value, zone).atZone(zone).toOffsetDateTime());
        }
        if (type == OffsetTime.class) {
            if (value instanceof OffsetTime time) {
                return type.cast(time);
            }
            return type.cast(toLocalTime(columnIndex, value, zone)
                    .atOffset(zone.getRules().getOffset(Instant.now())));
        }
        if (type.isInstance(value)) {
            return type.cast(value);
        }
        throw cannotConvert(columnIndex, value, type.getName());
    }

    /** Reads a column without disturbing more state than a getter would, for null checks. */
    private Object peek(int columnIndex) throws SQLException {
        return valueAt(columnIndex);
    }

    /**
     * Returns the wrapper class for a primitive class literal, or the class itself.
     *
     * <p>{@link #getObject(int, Class)} may be handed {@code int.class} rather than
     * {@link Integer}. A primitive class cannot hold a boxed value, so returning the wrapper is
     * the only way to answer such a call instead of failing with an unchecked cast.
     */
    private static Class<?> wrapperFor(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        if (type == boolean.class) {
            return Boolean.class;
        }
        if (type == byte.class) {
            return Byte.class;
        }
        if (type == short.class) {
            return Short.class;
        }
        if (type == int.class) {
            return Integer.class;
        }
        if (type == long.class) {
            return Long.class;
        }
        if (type == float.class) {
            return Float.class;
        }
        if (type == double.class) {
            return Double.class;
        }
        if (type == char.class) {
            return Character.class;
        }
        return type;
    }

    @Override
    public <T> T getObject(String columnLabel, Class<T> type) throws SQLException {
        return getObject(findColumn(columnLabel), type);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Custom type maps are not supported; GQLDB has no user-defined SQL types to map.
     */
    @Override
    public Object getObject(int columnIndex, Map<String, Class<?>> map) throws SQLException {
        throw UltipaExceptionMapper.unsupported("getObject with a custom type map");
    }

    @Override
    public Object getObject(String columnLabel, Map<String, Class<?>> map) throws SQLException {
        throw UltipaExceptionMapper.unsupported("getObject with a custom type map");
    }

    // ---------------------------------------------------------------------
    // Unsupported: SQL large objects and references
    // ---------------------------------------------------------------------

    /**
     * {@inheritDoc}
     *
     * <p>GQLDB lists are returned as {@link java.util.List} from {@link #getObject(int)}; there
     * is no {@code java.sql.Array} representation, which would require a single SQL element type
     * that a GQLDB list need not have.
     */
    @Override
    public Array getArray(int columnIndex) throws SQLException {
        throw UltipaExceptionMapper.unsupported(
                "java.sql.Array; a GQLDB list is returned as java.util.List from getObject");
    }

    @Override
    public Array getArray(String columnLabel) throws SQLException {
        return getArray(findColumn(columnLabel));
    }

    @Override
    public Blob getBlob(int columnIndex) throws SQLException {
        throw UltipaExceptionMapper.unsupported(
                "java.sql.Blob; binary values are returned as byte[] from getBytes");
    }

    @Override
    public Blob getBlob(String columnLabel) throws SQLException {
        return getBlob(findColumn(columnLabel));
    }

    @Override
    public Clob getClob(int columnIndex) throws SQLException {
        throw UltipaExceptionMapper.unsupported(
                "java.sql.Clob; text values are returned as String from getString");
    }

    @Override
    public Clob getClob(String columnLabel) throws SQLException {
        return getClob(findColumn(columnLabel));
    }

    @Override
    public NClob getNClob(int columnIndex) throws SQLException {
        throw UltipaExceptionMapper.unsupported("java.sql.NClob");
    }

    @Override
    public NClob getNClob(String columnLabel) throws SQLException {
        return getNClob(findColumn(columnLabel));
    }

    @Override
    public SQLXML getSQLXML(int columnIndex) throws SQLException {
        throw UltipaExceptionMapper.unsupported("java.sql.SQLXML");
    }

    @Override
    public SQLXML getSQLXML(String columnLabel) throws SQLException {
        return getSQLXML(findColumn(columnLabel));
    }

    @Override
    public Ref getRef(int columnIndex) throws SQLException {
        throw UltipaExceptionMapper.unsupported("java.sql.Ref");
    }

    @Override
    public Ref getRef(String columnLabel) throws SQLException {
        return getRef(findColumn(columnLabel));
    }

    @Override
    public RowId getRowId(int columnIndex) throws SQLException {
        throw UltipaExceptionMapper.unsupported(
                "java.sql.RowId; a node or edge identifier is available from the GraphNode or "
                        + "GraphEdge returned by getObject");
    }

    @Override
    public RowId getRowId(String columnLabel) throws SQLException {
        return getRowId(findColumn(columnLabel));
    }

    @Override
    public URL getURL(int columnIndex) throws SQLException {
        throw UltipaExceptionMapper.unsupported("getURL; read the column as a String");
    }

    @Override
    public URL getURL(String columnLabel) throws SQLException {
        return getURL(findColumn(columnLabel));
    }

    @Override
    public String getCursorName() throws SQLException {
        throw UltipaExceptionMapper.unsupported("Named cursors");
    }

    // ---------------------------------------------------------------------
    // Unsupported: scrolling
    // ---------------------------------------------------------------------

    private SQLException notScrollable(String method) {
        return UltipaExceptionMapper.unsupported(
                method + " on a forward-only result set; use next()");
    }

    @Override
    public boolean previous() throws SQLException {
        throw notScrollable("previous()");
    }

    @Override
    public boolean first() throws SQLException {
        throw notScrollable("first()");
    }

    @Override
    public boolean last() throws SQLException {
        throw notScrollable("last()");
    }

    @Override
    public void beforeFirst() throws SQLException {
        throw notScrollable("beforeFirst()");
    }

    @Override
    public void afterLast() throws SQLException {
        throw notScrollable("afterLast()");
    }

    @Override
    public boolean absolute(int row) throws SQLException {
        throw notScrollable("absolute(int)");
    }

    @Override
    public boolean relative(int rows) throws SQLException {
        throw notScrollable("relative(int)");
    }

    // ---------------------------------------------------------------------
    // Unsupported: updates
    // ---------------------------------------------------------------------

    private SQLException notUpdatable() {
        return UltipaExceptionMapper.unsupported(
                "Updating a result set; run a GQL write statement instead");
    }

    @Override
    public boolean rowUpdated() throws SQLException {
        checkOpen();
        return false;
    }

    @Override
    public boolean rowInserted() throws SQLException {
        checkOpen();
        return false;
    }

    @Override
    public boolean rowDeleted() throws SQLException {
        checkOpen();
        return false;
    }

    @Override
    public void insertRow() throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateRow() throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void deleteRow() throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void refreshRow() throws SQLException {
        throw UltipaExceptionMapper.unsupported("refreshRow(); re-run the query instead");
    }

    @Override
    public void cancelRowUpdates() throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void moveToInsertRow() throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void moveToCurrentRow() throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateNull(int columnIndex) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateNull(String columnLabel) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateBoolean(int columnIndex, boolean value) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateBoolean(String columnLabel, boolean value) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateByte(int columnIndex, byte value) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateByte(String columnLabel, byte value) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateShort(int columnIndex, short value) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateShort(String columnLabel, short value) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateInt(int columnIndex, int value) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateInt(String columnLabel, int value) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateLong(int columnIndex, long value) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateLong(String columnLabel, long value) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateFloat(int columnIndex, float value) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateFloat(String columnLabel, float value) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateDouble(int columnIndex, double value) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateDouble(String columnLabel, double value) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateBigDecimal(int columnIndex, BigDecimal value) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateBigDecimal(String columnLabel, BigDecimal value) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateString(int columnIndex, String value) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateString(String columnLabel, String value) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateNString(int columnIndex, String value) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateNString(String columnLabel, String value) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateBytes(int columnIndex, byte[] value) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateBytes(String columnLabel, byte[] value) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateDate(int columnIndex, Date value) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateDate(String columnLabel, Date value) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateTime(int columnIndex, Time value) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateTime(String columnLabel, Time value) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateTimestamp(int columnIndex, Timestamp value) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateTimestamp(String columnLabel, Timestamp value) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateAsciiStream(int columnIndex, InputStream value, int length) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateAsciiStream(String columnLabel, InputStream value, int length) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateAsciiStream(int columnIndex, InputStream value, long length) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateAsciiStream(String columnLabel, InputStream value, long length) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateAsciiStream(int columnIndex, InputStream value) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateAsciiStream(String columnLabel, InputStream value) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateBinaryStream(int columnIndex, InputStream value, int length) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateBinaryStream(String columnLabel, InputStream value, int length) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateBinaryStream(int columnIndex, InputStream value, long length) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateBinaryStream(String columnLabel, InputStream value, long length) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateBinaryStream(int columnIndex, InputStream value) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateBinaryStream(String columnLabel, InputStream value) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateCharacterStream(int columnIndex, Reader value, int length) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateCharacterStream(String columnLabel, Reader value, int length) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateCharacterStream(int columnIndex, Reader value, long length) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateCharacterStream(String columnLabel, Reader value, long length) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateCharacterStream(int columnIndex, Reader value) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateCharacterStream(String columnLabel, Reader value) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateNCharacterStream(int columnIndex, Reader value, long length) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateNCharacterStream(String columnLabel, Reader value, long length) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateNCharacterStream(int columnIndex, Reader value) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateNCharacterStream(String columnLabel, Reader value) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateObject(int columnIndex, Object value, int scaleOrLength) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateObject(String columnLabel, Object value, int scaleOrLength) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateObject(int columnIndex, Object value) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateObject(String columnLabel, Object value) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateRef(int columnIndex, Ref value) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateRef(String columnLabel, Ref value) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateArray(int columnIndex, Array value) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateArray(String columnLabel, Array value) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateBlob(int columnIndex, Blob value) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateBlob(String columnLabel, Blob value) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateBlob(int columnIndex, InputStream value, long length) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateBlob(String columnLabel, InputStream value, long length) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateBlob(int columnIndex, InputStream value) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateBlob(String columnLabel, InputStream value) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateClob(int columnIndex, Clob value) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateClob(String columnLabel, Clob value) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateClob(int columnIndex, Reader value, long length) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateClob(String columnLabel, Reader value, long length) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateClob(int columnIndex, Reader value) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateClob(String columnLabel, Reader value) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateNClob(int columnIndex, NClob value) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateNClob(String columnLabel, NClob value) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateNClob(int columnIndex, Reader value, long length) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateNClob(String columnLabel, Reader value, long length) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateNClob(int columnIndex, Reader value) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateNClob(String columnLabel, Reader value) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateSQLXML(int columnIndex, SQLXML value) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateSQLXML(String columnLabel, SQLXML value) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateRowId(int columnIndex, RowId value) throws SQLException {
        throw notUpdatable();
    }

    @Override
    public void updateRowId(String columnLabel, RowId value) throws SQLException {
        throw notUpdatable();
    }

    // ---------------------------------------------------------------------
    // Wrapper
    // ---------------------------------------------------------------------

    @Override
    public <T> T unwrap(Class<T> target) throws SQLException {
        if (target != null && target.isInstance(this)) {
            return target.cast(this);
        }
        throw UltipaExceptionMapper.invalidArgument(
                "UltipaResultSet cannot be unwrapped to " + (target == null ? "null" : target.getName()));
    }

    @Override
    public boolean isWrapperFor(Class<?> target) {
        return target != null && target.isInstance(this);
    }
}
