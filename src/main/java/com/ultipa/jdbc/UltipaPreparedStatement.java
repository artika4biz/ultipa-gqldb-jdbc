package com.ultipa.jdbc;

import com.gqldb.types.Point;
import com.gqldb.types.Point3D;
import com.gqldb.types.Vector;
import com.ultipa.jdbc.graph.GraphPoint;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URL;
import java.sql.Array;
import java.sql.BatchUpdateException;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLType;
import java.sql.SQLXML;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.Period;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A {@link PreparedStatement} that binds its parameters <strong>server side</strong>.
 *
 * <h2>How binding works, and why it is safe</h2>
 *
 * <p>GQLDB's wire protocol carries a query's parameters separately from its text: a
 * {@code repeated Parameter} list of typed values, referenced inside the query as {@code $name}.
 * This class translates JDBC's positional {@code ?} into those named parameters — the
 * <em>n</em>th {@code ?} becomes {@code $jdbc_pn} (see {@link GqlParameterRewriter}) — and sends
 * each bound value as a typed protocol value.
 *
 * <p>Consequently <strong>no parameter value is ever serialised into the query text</strong>.
 * This driver performs no quoting, no escaping and no concatenation of caller data into GQL.
 * There is no injection surface in parameter binding, and none of the usual caveats about
 * client-side escaping apply.
 *
 * <h2>Supported parameter types</h2>
 *
 * <p>A bound value is normalised to a type the protocol encodes, and anything else is
 * <em>rejected</em> with {@link java.sql.SQLFeatureNotSupportedException} rather than coerced
 * into a lossy approximation:
 *
 * <ul>
 *   <li>{@code null}, {@link Boolean}, {@link Byte}, {@link Short}, {@link Integer},
 *       {@link Long}, {@link BigInteger}, {@link Float}, {@link Double}, {@link BigDecimal};</li>
 *   <li>{@link String}, {@link Character} and {@code byte[]};</li>
 *   <li>{@link Date}, {@link Time} and {@link Timestamp}, each mapped to the GQLDB temporal type
 *       with matching semantics rather than to a bare instant;</li>
 *   <li>{@link LocalDate}, {@link LocalTime}, {@link OffsetTime}, {@link LocalDateTime},
 *       {@link OffsetDateTime}, {@link ZonedDateTime}, {@link Instant},
 *       {@link java.util.Date};</li>
 *   <li>{@link Duration} and {@link Period}, for the interval types;</li>
 *   <li>{@link List}, {@link Set} and {@link Map}, normalised element by element, so nested
 *       structures bind as structures;</li>
 *   <li>{@code float[]} for a vector, and {@link GraphPoint} for a spatial point.</li>
 * </ul>
 *
 * <h2>Limitations</h2>
 *
 * <p>Placeholder detection is lexical. {@link GqlParameterRewriter} documents what it skips —
 * all five GQL string-literal forms and all three comment forms — and the one case it cannot
 * resolve.
 *
 * <p>The server does not describe a statement's placeholders, so
 * {@link #getParameterMetaData()} reports an exact count and unknown types, and
 * {@link #getMetaData()} cannot describe the result before execution.
 *
 * <p>Statements are not pre-compiled on the server: the protocol has no prepare request, so the
 * query text travels with each execution. The benefit of this class is safe typed binding, not a
 * saved parse.
 */
public final class UltipaPreparedStatement extends UltipaStatement implements PreparedStatement {

    private final String originalGql;
    private final String rewrittenGql;
    private final List<String> parameterNames;
    private final Object[] values;
    private final boolean[] bound;
    private final List<Map<String, Object>> parameterBatch = new ArrayList<>();

    /**
     * Prepares a statement.
     *
     * @param connection the owning connection
     * @param gql        the GQL text, with {@code ?} placeholders
     * @throws SQLException if the text cannot be scanned for placeholders
     */
    UltipaPreparedStatement(UltipaConnection connection, String gql) throws SQLException {
        super(connection);
        if (gql == null || gql.isBlank()) {
            throw UltipaExceptionMapper.invalidArgument("GQL text must not be null or blank");
        }
        this.originalGql = gql;
        GqlParameterRewriter.Rewritten rewritten = GqlParameterRewriter.rewrite(gql);
        this.rewrittenGql = rewritten.gql();
        this.parameterNames = rewritten.parameterNames();
        this.values = new Object[parameterNames.size()];
        this.bound = new boolean[parameterNames.size()];
    }

    /**
     * Returns the GQL text as given to {@link java.sql.Connection#prepareStatement(String)}.
     *
     * <p>A graph-aware extension, useful for diagnostics.
     *
     * @return the original text
     */
    public String getOriginalGql() {
        return originalGql;
    }

    /**
     * Returns the text actually sent to the server, with {@code ?} replaced by named parameters.
     *
     * <p>A graph-aware extension, useful for diagnostics. It never contains parameter
     * <em>values</em>, which travel separately.
     *
     * @return the rewritten text
     */
    public String getRewrittenGql() {
        return rewrittenGql;
    }

    // ---------------------------------------------------------------------
    // Parameter binding
    // ---------------------------------------------------------------------

    private void checkIndex(int parameterIndex) throws SQLException {
        checkOpen();
        if (parameterIndex < 1 || parameterIndex > parameterNames.size()) {
            throw UltipaExceptionMapper.invalidArgument(
                    "Parameter index " + parameterIndex + " is out of range 1.."
                            + parameterNames.size() + "; the statement has "
                            + parameterNames.size() + " placeholder(s)");
        }
    }

    private void bind(int parameterIndex, Object normalised) throws SQLException {
        checkIndex(parameterIndex);
        values[parameterIndex - 1] = normalised;
        bound[parameterIndex - 1] = true;
    }

    private Map<String, Object> currentParameters() throws SQLException {
        Map<String, Object> parameters = new LinkedHashMap<>();
        for (int i = 0; i < parameterNames.size(); i++) {
            if (!bound[i]) {
                throw UltipaExceptionMapper.invalidArgument(
                        "Parameter " + (i + 1) + " of " + parameterNames.size()
                                + " has not been set");
            }
            parameters.put(parameterNames.get(i), values[i]);
        }
        return parameters;
    }

    /**
     * Converts a Java value into one the GQLDB protocol encodes, rejecting anything it does not.
     *
     * @param value          the value a caller bound
     * @param parameterIndex the parameter's position, for error messages
     * @return the value to send
     * @throws SQLException if the type cannot be bound
     */
    private Object normalise(Object value, int parameterIndex) throws SQLException {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean || value instanceof Integer || value instanceof Long
                || value instanceof BigInteger || value instanceof Float
                || value instanceof Double || value instanceof BigDecimal
                || value instanceof String || value instanceof byte[]
                || value instanceof LocalDate || value instanceof LocalTime
                || value instanceof OffsetTime || value instanceof LocalDateTime
                || value instanceof OffsetDateTime || value instanceof ZonedDateTime
                || value instanceof Instant || value instanceof Duration
                || value instanceof Period) {
            return value;
        }

        // Narrow integral types: the protocol encoder has no Byte or Short case.
        if (value instanceof Byte number) {
            return number.intValue();
        }
        if (value instanceof Short number) {
            return number.intValue();
        }
        if (value instanceof Character character) {
            return String.valueOf(character);
        }

        // java.sql temporals carry date-only, time-only and local-datetime semantics; keep them
        // instead of collapsing everything to an instant through java.util.Date.
        if (value instanceof Date date) {
            return date.toLocalDate();
        }
        if (value instanceof Time time) {
            return time.toLocalTime();
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        if (value instanceof java.util.Date date) {
            return date.toInstant();
        }
        if (value instanceof java.util.Calendar calendar) {
            return calendar.toInstant();
        }

        if (value instanceof float[] floats) {
            return new Vector(floats.clone());
        }
        if (value instanceof GraphPoint point) {
            if (point.is3D()) {
                return new Point3D(point.getX(), point.getY(),
                        point.getZ().orElse(0.0d), point.getSrid());
            }
            return new Point(point.getLatitude(), point.getLongitude(), point.getSrid());
        }

        if (value instanceof List<?> list) {
            List<Object> normalised = new ArrayList<>(list.size());
            for (Object element : list) {
                normalised.add(normalise(element, parameterIndex));
            }
            return normalised;
        }
        if (value instanceof Set<?> set) {
            Set<Object> normalised = new LinkedHashSet<>(Math.max(8, set.size() * 2));
            for (Object element : set) {
                normalised.add(normalise(element, parameterIndex));
            }
            return normalised;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> normalised = new LinkedHashMap<>(Math.max(8, map.size() * 2));
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw UltipaExceptionMapper.unsupported(
                            "A Map parameter with non-String keys (parameter " + parameterIndex
                                    + "); GQLDB map keys are strings");
                }
                normalised.put(key, normalise(entry.getValue(), parameterIndex));
            }
            return normalised;
        }

        throw UltipaExceptionMapper.unsupported(
                "Binding a value of type " + value.getClass().getName() + " (parameter "
                        + parameterIndex + "); see UltipaPreparedStatement for the supported types");
    }

    /**
     * {@inheritDoc}
     *
     * <p>The {@code sqlType} argument is accepted and not used: the protocol has a single typed
     * null, so a null needs no type hint to be bound unambiguously.
     */
    @Override
    public void setNull(int parameterIndex, int sqlType) throws SQLException {
        bind(parameterIndex, null);
    }

    @Override
    public void setNull(int parameterIndex, int sqlType, String typeName) throws SQLException {
        bind(parameterIndex, null);
    }

    @Override
    public void setBoolean(int parameterIndex, boolean value) throws SQLException {
        bind(parameterIndex, value);
    }

    @Override
    public void setByte(int parameterIndex, byte value) throws SQLException {
        bind(parameterIndex, (int) value);
    }

    @Override
    public void setShort(int parameterIndex, short value) throws SQLException {
        bind(parameterIndex, (int) value);
    }

    @Override
    public void setInt(int parameterIndex, int value) throws SQLException {
        bind(parameterIndex, value);
    }

    @Override
    public void setLong(int parameterIndex, long value) throws SQLException {
        bind(parameterIndex, value);
    }

    @Override
    public void setFloat(int parameterIndex, float value) throws SQLException {
        bind(parameterIndex, value);
    }

    @Override
    public void setDouble(int parameterIndex, double value) throws SQLException {
        bind(parameterIndex, value);
    }

    @Override
    public void setBigDecimal(int parameterIndex, BigDecimal value) throws SQLException {
        bind(parameterIndex, value);
    }

    @Override
    public void setString(int parameterIndex, String value) throws SQLException {
        bind(parameterIndex, value);
    }

    @Override
    public void setNString(int parameterIndex, String value) throws SQLException {
        bind(parameterIndex, value);
    }

    @Override
    public void setBytes(int parameterIndex, byte[] value) throws SQLException {
        bind(parameterIndex, value == null ? null : value.clone());
    }

    @Override
    public void setDate(int parameterIndex, Date value) throws SQLException {
        bind(parameterIndex, value == null ? null : value.toLocalDate());
    }

    /**
     * {@inheritDoc}
     *
     * <p>The calendar's time zone is applied before the date is taken, so the stored date is the
     * one the caller's calendar names.
     */
    @Override
    public void setDate(int parameterIndex, Date value, Calendar calendar) throws SQLException {
        if (value == null || calendar == null) {
            setDate(parameterIndex, value);
            return;
        }
        ZoneId zone = calendar.getTimeZone().toZoneId();
        bind(parameterIndex, Instant.ofEpochMilli(value.getTime()).atZone(zone).toLocalDate());
    }

    @Override
    public void setTime(int parameterIndex, Time value) throws SQLException {
        bind(parameterIndex, value == null ? null : value.toLocalTime());
    }

    @Override
    public void setTime(int parameterIndex, Time value, Calendar calendar) throws SQLException {
        if (value == null || calendar == null) {
            setTime(parameterIndex, value);
            return;
        }
        ZoneId zone = calendar.getTimeZone().toZoneId();
        bind(parameterIndex, Instant.ofEpochMilli(value.getTime()).atZone(zone).toLocalTime());
    }

    @Override
    public void setTimestamp(int parameterIndex, Timestamp value) throws SQLException {
        bind(parameterIndex, value == null ? null : value.toLocalDateTime());
    }

    @Override
    public void setTimestamp(int parameterIndex, Timestamp value, Calendar calendar)
            throws SQLException {
        if (value == null || calendar == null) {
            setTimestamp(parameterIndex, value);
            return;
        }
        ZoneId zone = calendar.getTimeZone().toZoneId();
        bind(parameterIndex, value.toInstant().atZone(zone).toLocalDateTime());
    }

    @Override
    public void setObject(int parameterIndex, Object value) throws SQLException {
        bind(parameterIndex, normalise(value, parameterIndex));
    }

    /**
     * {@inheritDoc}
     *
     * <p>The value is coerced to the Java type that matches {@code targetSqlType} before being
     * bound, so the caller's declared intent decides the GQLDB type. A target type this driver
     * cannot represent is rejected rather than silently ignored.
     */
    @Override
    public void setObject(int parameterIndex, Object value, int targetSqlType) throws SQLException {
        checkIndex(parameterIndex);
        bind(parameterIndex, coerce(value, targetSqlType, parameterIndex));
    }

    @Override
    public void setObject(int parameterIndex, Object value, int targetSqlType, int scaleOrLength)
            throws SQLException {
        checkIndex(parameterIndex);
        Object coerced = coerce(value, targetSqlType, parameterIndex);
        if (coerced instanceof BigDecimal decimal
                && (targetSqlType == Types.DECIMAL || targetSqlType == Types.NUMERIC)) {
            coerced = decimal.setScale(scaleOrLength, java.math.RoundingMode.HALF_UP);
        }
        bind(parameterIndex, coerced);
    }

    @Override
    public void setObject(int parameterIndex, Object value, SQLType targetSqlType)
            throws SQLException {
        if (targetSqlType == null) {
            setObject(parameterIndex, value);
            return;
        }
        setObject(parameterIndex, value, targetSqlType.getVendorTypeNumber());
    }

    @Override
    public void setObject(int parameterIndex, Object value, SQLType targetSqlType,
                          int scaleOrLength) throws SQLException {
        if (targetSqlType == null) {
            setObject(parameterIndex, value);
            return;
        }
        setObject(parameterIndex, value, targetSqlType.getVendorTypeNumber(), scaleOrLength);
    }

    private Object coerce(Object value, int targetSqlType, int parameterIndex) throws SQLException {
        if (value == null || targetSqlType == Types.NULL) {
            return null;
        }
        return switch (targetSqlType) {
            case Types.BIT, Types.BOOLEAN -> asBoolean(value, parameterIndex);
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER ->
                    asBigDecimal(value, parameterIndex).intValueExact();
            case Types.BIGINT -> asBigDecimal(value, parameterIndex).longValueExact();
            case Types.REAL -> asBigDecimal(value, parameterIndex).floatValue();
            case Types.FLOAT, Types.DOUBLE -> asBigDecimal(value, parameterIndex).doubleValue();
            case Types.DECIMAL, Types.NUMERIC -> asBigDecimal(value, parameterIndex);
            case Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR,
                 Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR ->
                    UltipaTypeMapper.renderAsString(value);
            case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY -> asBytes(value, parameterIndex);
            case Types.DATE -> asTemporal(value, parameterIndex, LocalDate.class);
            case Types.TIME -> asTemporal(value, parameterIndex, LocalTime.class);
            case Types.TIME_WITH_TIMEZONE -> asTemporal(value, parameterIndex, OffsetTime.class);
            case Types.TIMESTAMP -> asTemporal(value, parameterIndex, LocalDateTime.class);
            case Types.TIMESTAMP_WITH_TIMEZONE ->
                    asTemporal(value, parameterIndex, OffsetDateTime.class);
            case Types.OTHER, Types.JAVA_OBJECT -> normalise(value, parameterIndex);
            default -> throw UltipaExceptionMapper.unsupported(
                    "Target SQL type " + targetSqlType + " for parameter " + parameterIndex);
        };
    }

    private boolean asBoolean(Object value, int parameterIndex) throws SQLException {
        if (value instanceof Boolean flag) {
            return flag;
        }
        if (value instanceof Number number) {
            return number.doubleValue() != 0.0d;
        }
        if (value instanceof String text) {
            String normalised = text.trim().toLowerCase(java.util.Locale.ROOT);
            if (normalised.equals("true") || normalised.equals("1") || normalised.equals("yes")) {
                return true;
            }
            if (normalised.equals("false") || normalised.equals("0") || normalised.equals("no")) {
                return false;
            }
        }
        throw UltipaExceptionMapper.invalidArgument(
                "Parameter " + parameterIndex + " cannot be bound as a boolean from "
                        + value.getClass().getName());
    }

    private BigDecimal asBigDecimal(Object value, int parameterIndex) throws SQLException {
        try {
            if (value instanceof BigDecimal decimal) {
                return decimal;
            }
            if (value instanceof BigInteger integer) {
                return new BigDecimal(integer);
            }
            if (value instanceof Number number) {
                return new BigDecimal(number.toString());
            }
            if (value instanceof Boolean flag) {
                return flag ? BigDecimal.ONE : BigDecimal.ZERO;
            }
            if (value instanceof String text) {
                return new BigDecimal(text.trim());
            }
        } catch (NumberFormatException e) {
            throw UltipaExceptionMapper.invalidArgument(
                    "Parameter " + parameterIndex + " is not a valid number: " + value);
        }
        throw UltipaExceptionMapper.invalidArgument(
                "Parameter " + parameterIndex + " cannot be bound as a number from "
                        + value.getClass().getName());
    }

    private byte[] asBytes(Object value, int parameterIndex) throws SQLException {
        if (value instanceof byte[] bytes) {
            return bytes.clone();
        }
        if (value instanceof String text) {
            return text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
        throw UltipaExceptionMapper.invalidArgument(
                "Parameter " + parameterIndex + " cannot be bound as binary from "
                        + value.getClass().getName());
    }

    private Object asTemporal(Object value, int parameterIndex, Class<?> target) throws SQLException {
        ZoneId zone = ZoneId.systemDefault();
        Object normalised = normalise(value, parameterIndex);

        if (target.isInstance(normalised)) {
            return normalised;
        }
        if (target == LocalDate.class) {
            if (normalised instanceof LocalDateTime dateTime) {
                return dateTime.toLocalDate();
            }
            if (normalised instanceof OffsetDateTime dateTime) {
                return dateTime.atZoneSameInstant(zone).toLocalDate();
            }
            if (normalised instanceof Instant instant) {
                return instant.atZone(zone).toLocalDate();
            }
            if (normalised instanceof String text) {
                return LocalDate.parse(text.trim());
            }
        } else if (target == LocalTime.class) {
            if (normalised instanceof LocalDateTime dateTime) {
                return dateTime.toLocalTime();
            }
            if (normalised instanceof OffsetTime time) {
                return time.toLocalTime();
            }
            if (normalised instanceof Instant instant) {
                return instant.atZone(zone).toLocalTime();
            }
            if (normalised instanceof String text) {
                return LocalTime.parse(text.trim());
            }
        } else if (target == LocalDateTime.class) {
            if (normalised instanceof OffsetDateTime dateTime) {
                return dateTime.atZoneSameInstant(zone).toLocalDateTime();
            }
            if (normalised instanceof Instant instant) {
                return instant.atZone(zone).toLocalDateTime();
            }
            if (normalised instanceof LocalDate date) {
                return date.atStartOfDay();
            }
            if (normalised instanceof String text) {
                return LocalDateTime.parse(text.trim());
            }
        } else if (target == OffsetDateTime.class) {
            if (normalised instanceof Instant instant) {
                return instant.atZone(zone).toOffsetDateTime();
            }
            if (normalised instanceof LocalDateTime dateTime) {
                return dateTime.atZone(zone).toOffsetDateTime();
            }
            if (normalised instanceof String text) {
                return OffsetDateTime.parse(text.trim());
            }
        } else if (target == OffsetTime.class) {
            if (normalised instanceof LocalTime time) {
                return time.atOffset(zone.getRules().getOffset(Instant.now()));
            }
            if (normalised instanceof String text) {
                return OffsetTime.parse(text.trim());
            }
        }

        throw UltipaExceptionMapper.invalidArgument(
                "Parameter " + parameterIndex + " cannot be bound as " + target.getSimpleName()
                        + " from " + value.getClass().getName());
    }

    @Override
    public void clearParameters() throws SQLException {
        checkOpen();
        Arrays.fill(values, null);
        Arrays.fill(bound, false);
    }

    // ---------------------------------------------------------------------
    // Execution
    // ---------------------------------------------------------------------

    @Override
    public ResultSet executeQuery() throws SQLException {
        if (!executeInternal(rewrittenGql, currentParameters())) {
            throw UltipaExceptionMapper.invalidArgument(
                    "The statement returned no result set, so executeQuery cannot satisfy its "
                            + "contract of never returning null. Use executeUpdate for a write "
                            + "with no RETURN clause, or execute() if either outcome is possible");
        }
        return getResultSet();
    }

    @Override
    public int executeUpdate() throws SQLException {
        return (int) Math.min(executeLargeUpdate(), Integer.MAX_VALUE);
    }

    @Override
    public long executeLargeUpdate() throws SQLException {
        if (executeInternal(rewrittenGql, currentParameters())) {
            ResultSet open = getResultSet();
            if (open != null) {
                open.close();
            }
            throw UltipaExceptionMapper.invalidArgument(
                    "The statement returned a result set, so executeUpdate cannot report it. "
                            + "Use executeQuery, or execute() if either outcome is possible");
        }
        return getLargeUpdateCount();
    }

    @Override
    public boolean execute() throws SQLException {
        return executeInternal(rewrittenGql, currentParameters());
    }

    @Override
    public void addBatch() throws SQLException {
        checkOpen();
        parameterBatch.add(currentParameters());
    }

    @Override
    public void clearBatch() throws SQLException {
        checkOpen();
        parameterBatch.clear();
        super.clearBatch();
    }

    @Override
    public int[] executeBatch() throws SQLException {
        long[] counts = executeLargeBatch();
        int[] narrowed = new int[counts.length];
        for (int i = 0; i < counts.length; i++) {
            narrowed[i] = (int) Math.min(counts[i], Integer.MAX_VALUE);
        }
        return narrowed;
    }

    @Override
    public long[] executeLargeBatch() throws SQLException {
        checkOpen();
        List<Map<String, Object>> queued = List.copyOf(parameterBatch);
        parameterBatch.clear();

        long[] counts = new long[queued.size()];
        for (int i = 0; i < queued.size(); i++) {
            try {
                if (executeInternal(rewrittenGql, queued.get(i))) {
                    ResultSet open = getResultSet();
                    if (open != null) {
                        open.close();
                    }
                    counts[i] = Statement.EXECUTE_FAILED;
                    throw new BatchUpdateException(
                            "Batch entry " + (i + 1) + " returned a result set; a batched "
                                    + "statement must not return rows",
                            "0A000", 0, Arrays.copyOf(counts, i + 1), null);
                }
                counts[i] = getLargeUpdateCount();
            } catch (BatchUpdateException e) {
                throw e;
            } catch (SQLException e) {
                counts[i] = Statement.EXECUTE_FAILED;
                throw new BatchUpdateException(
                        "Batch entry " + (i + 1) + " failed: " + e.getMessage(),
                        e.getSQLState(), e.getErrorCode(), Arrays.copyOf(counts, i + 1), e);
            }
        }
        return counts;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Not allowed: a {@link PreparedStatement} carries its own GQL text. Create a plain
     * {@link Statement} to run a different statement.
     */
    @Override
    public ResultSet executeQuery(String gql) throws SQLException {
        throw cannotTakeNewText("executeQuery");
    }

    @Override
    public int executeUpdate(String gql) throws SQLException {
        throw cannotTakeNewText("executeUpdate");
    }

    @Override
    public long executeLargeUpdate(String gql) throws SQLException {
        throw cannotTakeNewText("executeLargeUpdate");
    }

    @Override
    public boolean execute(String gql) throws SQLException {
        throw cannotTakeNewText("execute");
    }

    @Override
    public void addBatch(String gql) throws SQLException {
        throw cannotTakeNewText("addBatch");
    }

    private static SQLException cannotTakeNewText(String method) {
        return UltipaExceptionMapper.invalidArgument(
                method + "(String) cannot be called on a PreparedStatement, which carries its "
                        + "own GQL text; use Connection.createStatement() for ad-hoc statements");
    }

    // ---------------------------------------------------------------------
    // Metadata
    // ---------------------------------------------------------------------

    @Override
    public ParameterMetaData getParameterMetaData() throws SQLException {
        checkOpen();
        return new UltipaParameterMetaData(parameterNames.size());
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns the metadata of the current result set, or {@code null} before the statement has
     * been executed. The protocol has no "describe statement" request, so a result's shape is
     * only known once the server has answered. {@code null} is what {@link PreparedStatement}
     * prescribes when the metadata is unavailable.
     */
    @Override
    public ResultSetMetaData getMetaData() throws SQLException {
        checkOpen();
        ResultSet current = getResultSet();
        return current == null ? null : current.getMetaData();
    }

    // ---------------------------------------------------------------------
    // Unsupported parameter kinds
    // ---------------------------------------------------------------------

    @Override
    public void setAsciiStream(int parameterIndex, InputStream value, int length) throws SQLException {
        throw streamsUnsupported();
    }

    @Override
    public void setAsciiStream(int parameterIndex, InputStream value, long length) throws SQLException {
        throw streamsUnsupported();
    }

    @Override
    public void setAsciiStream(int parameterIndex, InputStream value) throws SQLException {
        throw streamsUnsupported();
    }

    @Override
    public void setBinaryStream(int parameterIndex, InputStream value, int length) throws SQLException {
        throw streamsUnsupported();
    }

    @Override
    public void setBinaryStream(int parameterIndex, InputStream value, long length) throws SQLException {
        throw streamsUnsupported();
    }

    @Override
    public void setBinaryStream(int parameterIndex, InputStream value) throws SQLException {
        throw streamsUnsupported();
    }

    @Override
    public void setCharacterStream(int parameterIndex, Reader value, int length) throws SQLException {
        throw streamsUnsupported();
    }

    @Override
    public void setCharacterStream(int parameterIndex, Reader value, long length) throws SQLException {
        throw streamsUnsupported();
    }

    @Override
    public void setCharacterStream(int parameterIndex, Reader value) throws SQLException {
        throw streamsUnsupported();
    }

    @Override
    public void setNCharacterStream(int parameterIndex, Reader value, long length) throws SQLException {
        throw streamsUnsupported();
    }

    @Override
    public void setNCharacterStream(int parameterIndex, Reader value) throws SQLException {
        throw streamsUnsupported();
    }

    /**
     * {@inheritDoc}
     *
     * @deprecated as in {@link PreparedStatement}; use {@link #setCharacterStream(int, Reader)}.
     */
    @Deprecated
    @Override
    public void setUnicodeStream(int parameterIndex, InputStream value, int length)
            throws SQLException {
        throw streamsUnsupported();
    }

    private static SQLException streamsUnsupported() {
        return UltipaExceptionMapper.unsupported(
                "Binding a stream; the protocol sends a parameter as one complete typed value, "
                        + "so read the stream and bind a String or byte[] instead");
    }

    @Override
    public void setBlob(int parameterIndex, Blob value) throws SQLException {
        throw UltipaExceptionMapper.unsupported("Binding a java.sql.Blob; bind a byte[] instead");
    }

    @Override
    public void setBlob(int parameterIndex, InputStream value, long length) throws SQLException {
        throw UltipaExceptionMapper.unsupported("Binding a java.sql.Blob; bind a byte[] instead");
    }

    @Override
    public void setBlob(int parameterIndex, InputStream value) throws SQLException {
        throw UltipaExceptionMapper.unsupported("Binding a java.sql.Blob; bind a byte[] instead");
    }

    @Override
    public void setClob(int parameterIndex, Clob value) throws SQLException {
        throw UltipaExceptionMapper.unsupported("Binding a java.sql.Clob; bind a String instead");
    }

    @Override
    public void setClob(int parameterIndex, Reader value, long length) throws SQLException {
        throw UltipaExceptionMapper.unsupported("Binding a java.sql.Clob; bind a String instead");
    }

    @Override
    public void setClob(int parameterIndex, Reader value) throws SQLException {
        throw UltipaExceptionMapper.unsupported("Binding a java.sql.Clob; bind a String instead");
    }

    @Override
    public void setNClob(int parameterIndex, NClob value) throws SQLException {
        throw UltipaExceptionMapper.unsupported("Binding a java.sql.NClob; bind a String instead");
    }

    @Override
    public void setNClob(int parameterIndex, Reader value, long length) throws SQLException {
        throw UltipaExceptionMapper.unsupported("Binding a java.sql.NClob; bind a String instead");
    }

    @Override
    public void setNClob(int parameterIndex, Reader value) throws SQLException {
        throw UltipaExceptionMapper.unsupported("Binding a java.sql.NClob; bind a String instead");
    }

    @Override
    public void setArray(int parameterIndex, Array value) throws SQLException {
        throw UltipaExceptionMapper.unsupported(
                "Binding a java.sql.Array; bind a java.util.List instead");
    }

    @Override
    public void setRef(int parameterIndex, Ref value) throws SQLException {
        throw UltipaExceptionMapper.unsupported("Binding a java.sql.Ref");
    }

    @Override
    public void setRowId(int parameterIndex, RowId value) throws SQLException {
        throw UltipaExceptionMapper.unsupported(
                "Binding a java.sql.RowId; bind the node or edge identifier as a String");
    }

    @Override
    public void setSQLXML(int parameterIndex, SQLXML value) throws SQLException {
        throw UltipaExceptionMapper.unsupported("Binding a java.sql.SQLXML");
    }

    @Override
    public void setURL(int parameterIndex, URL value) throws SQLException {
        bind(parameterIndex, value == null ? null : value.toString());
    }
}
