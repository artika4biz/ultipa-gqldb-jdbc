package com.ultipa.jdbc;

import com.gqldb.types.DayToSecond;
import com.gqldb.types.GqldbDate;
import com.gqldb.types.GqldbDecimal;
import com.gqldb.types.GqldbEdge;
import com.gqldb.types.GqldbError;
import com.gqldb.types.GqldbLocalDateTime;
import com.gqldb.types.GqldbLocalTime;
import com.gqldb.types.GqldbNode;
import com.gqldb.types.GqldbPath;
import com.gqldb.types.GqldbTable;
import com.gqldb.types.GqldbZonedDateTime;
import com.gqldb.types.GqldbZonedTime;
import com.gqldb.types.Point;
import com.gqldb.types.Point3D;
import com.gqldb.types.PropertyType;
import com.gqldb.types.TypedValue;
import com.gqldb.types.Vector;
import com.ultipa.jdbc.graph.GraphEdge;
import com.ultipa.jdbc.graph.GraphError;
import com.ultipa.jdbc.graph.GraphNode;
import com.ultipa.jdbc.graph.GraphPath;
import com.ultipa.jdbc.graph.GraphPoint;
import com.ultipa.jdbc.graph.GraphTable;

import java.math.BigDecimal;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.Period;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The single place where a GQLDB value becomes a Java object, a {@link java.sql.Types} constant
 * and a type name.
 *
 * <p>Two rules shape every mapping here:
 *
 * <ol>
 *   <li><strong>Nothing is invented.</strong> A GQLDB type that has a faithful JDBC counterpart
 *       gets it; a type that does not — a node, an edge, a path, a point, a nested table — is
 *       reported as {@link Types#OTHER} and returned as a documented value type from
 *       {@link com.ultipa.jdbc.graph}, rather than being flattened into a string or dropped.</li>
 *   <li><strong>No SDK type escapes.</strong> Every {@code com.gqldb.*} value is translated,
 *       recursively through lists, sets and maps, so a caller never receives a class from the
 *       underlying SDK.</li>
 * </ol>
 *
 * <p>GQLDB is dynamically typed and its wire protocol carries types per <em>value</em>, not per
 * column: {@code GqlResponse} supplies column names only. Column-level type reporting is
 * therefore inferred from the first row — see {@link UltipaResultSetMetaData}.
 */
public final class UltipaTypeMapper {

    private UltipaTypeMapper() {
    }

    /**
     * Converts a value from the wire into the Java object this driver exposes.
     *
     * @param value the value to convert, may be {@code null}
     * @return the converted object, or {@code null} for a null value
     */
    public static Object toJavaObject(TypedValue value) {
        if (value == null || value.isNull() || value.getType() == PropertyType.NULL) {
            return null;
        }
        return convert(value.toJava());
    }

    /**
     * Converts an object the SDK produced — including one nested inside a collection or a node's
     * properties — into the Java object this driver exposes.
     *
     * @param raw the SDK object, may be {@code null}
     * @return the converted object, or {@code null}
     */
    public static Object convert(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof TypedValue typedValue) {
            return toJavaObject(typedValue);
        }
        if (raw instanceof GqldbNode node) {
            return toGraphNode(node);
        }
        if (raw instanceof GqldbEdge edge) {
            return toGraphEdge(edge);
        }
        if (raw instanceof GqldbPath path) {
            List<GraphNode> nodes = new ArrayList<>(path.getNodes().size());
            for (GqldbNode node : path.getNodes()) {
                nodes.add(toGraphNode(node));
            }
            List<GraphEdge> edges = new ArrayList<>(path.getEdges().size());
            for (GqldbEdge edge : path.getEdges()) {
                edges.add(toGraphEdge(edge));
            }
            return new GraphPath(nodes, edges);
        }
        if (raw instanceof GqldbTable table) {
            List<List<Object>> rows = new ArrayList<>(table.getRows().size());
            for (List<Object> row : table.getRows()) {
                rows.add(convertList(row));
            }
            return new GraphTable(table.getColumns(), rows);
        }
        if (raw instanceof GqldbError error) {
            return new GraphError(error.getCode(), error.getMessage());
        }
        if (raw instanceof Point3D point) {
            return GraphPoint.of3D(point.getX(), point.getY(), point.getZ(), point.getSrid());
        }
        if (raw instanceof Point point) {
            // The SDK documents getX() as longitude and getY() as latitude; keep that.
            return GraphPoint.of2D(point.getLongitude(), point.getLatitude(), point.getSrid());
        }
        if (raw instanceof GqldbDate date) {
            return date.toLocalDate();
        }
        if (raw instanceof GqldbLocalDateTime dateTime) {
            return dateTime.toLocalDateTime();
        }
        if (raw instanceof GqldbZonedDateTime dateTime) {
            return dateTime.toOffsetDateTime();
        }
        if (raw instanceof GqldbLocalTime time) {
            return time.toLocalTime();
        }
        if (raw instanceof GqldbZonedTime time) {
            return time.toOffsetTime();
        }
        if (raw instanceof GqldbDecimal decimal) {
            return decimal.toBigDecimal();
        }
        if (raw instanceof DayToSecond interval) {
            return interval.toDuration();
        }
        if (raw instanceof com.gqldb.types.YearToMonth interval) {
            return interval.toPeriod();
        }
        if (raw instanceof Vector vector) {
            return vector.getValues();
        }
        if (raw instanceof Set<?> set) {
            Set<Object> converted = new LinkedHashSet<>(Math.max(8, set.size() * 2));
            for (Object element : set) {
                converted.add(convert(element));
            }
            return converted;
        }
        if (raw instanceof List<?> list) {
            return convertList(list);
        }
        if (raw instanceof Map<?, ?> map) {
            return convertMap(map);
        }
        return raw;
    }

    private static List<Object> convertList(List<?> list) {
        List<Object> converted = new ArrayList<>(list.size());
        for (Object element : list) {
            converted.add(convert(element));
        }
        return converted;
    }

    private static Map<String, Object> convertMap(Map<?, ?> map) {
        Map<String, Object> converted = new LinkedHashMap<>(Math.max(8, map.size() * 2));
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            Object key = entry.getKey();
            converted.put(key == null ? null : String.valueOf(key), convert(entry.getValue()));
        }
        return converted;
    }

    private static GraphNode toGraphNode(GqldbNode node) {
        return new GraphNode(node.getId(), node.getUuid(), node.getLabels(),
                convertMap(node.getProperties() == null ? Map.of() : node.getProperties()));
    }

    private static GraphEdge toGraphEdge(GqldbEdge edge) {
        return new GraphEdge(edge.getId(), edge.getUuid(), edge.getLabel(),
                edge.getFromNodeId(), edge.getToNodeId(),
                convertMap(edge.getProperties() == null ? Map.of() : edge.getProperties()));
    }

    /**
     * Returns the {@link java.sql.Types} constant for a GQLDB type.
     *
     * <p>Types with no faithful JDBC counterpart map to {@link Types#OTHER}; the Java object is
     * still fully typed.
     *
     * @param type the GQLDB type, may be {@code null}
     * @return the JDBC type constant
     */
    public static int toJdbcType(PropertyType type) {
        if (type == null) {
            return Types.OTHER;
        }
        return switch (type) {
            case BOOL -> Types.BOOLEAN;
            case INT32 -> Types.INTEGER;
            case UINT32, INT64, UINT64 -> Types.BIGINT;
            case FLOAT -> Types.REAL;
            case DOUBLE -> Types.DOUBLE;
            case DECIMAL -> Types.DECIMAL;
            case STRING -> Types.VARCHAR;
            case TEXT -> Types.LONGVARCHAR;
            case BLOB -> Types.VARBINARY;
            case DATE -> Types.DATE;
            case LOCAL_TIME -> Types.TIME;
            case ZONED_TIME -> Types.TIME_WITH_TIMEZONE;
            case LOCAL_DATETIME, DATETIME, TIMESTAMP -> Types.TIMESTAMP;
            case ZONED_DATETIME -> Types.TIMESTAMP_WITH_TIMEZONE;
            case NULL -> Types.NULL;
            default -> Types.OTHER;
        };
    }

    /**
     * Returns the type name this driver reports for a GQLDB type, which is the server's own
     * spelling in lower case, for example {@code "int64"} or {@code "zoned_datetime"}.
     *
     * @param type the GQLDB type, may be {@code null}
     * @return the type name
     */
    public static String typeName(PropertyType type) {
        return type == null ? "unset" : type.name().toLowerCase(Locale.ROOT);
    }

    /**
     * Returns the fully qualified name of the class {@link java.sql.ResultSet#getObject(int)}
     * returns for a GQLDB type.
     *
     * @param type the GQLDB type, may be {@code null}
     * @return the class name
     */
    public static String javaClassName(PropertyType type) {
        if (type == null) {
            return Object.class.getName();
        }
        return switch (type) {
            case BOOL -> Boolean.class.getName();
            case INT32 -> Integer.class.getName();
            case UINT32, INT64, UINT64 -> Long.class.getName();
            case FLOAT -> Float.class.getName();
            case DOUBLE -> Double.class.getName();
            case DECIMAL -> BigDecimal.class.getName();
            case STRING, TEXT -> String.class.getName();
            case BLOB -> "[B";
            case DATE -> LocalDate.class.getName();
            case LOCAL_TIME -> LocalTime.class.getName();
            case ZONED_TIME -> OffsetTime.class.getName();
            case LOCAL_DATETIME, DATETIME -> LocalDateTime.class.getName();
            case TIMESTAMP -> Instant.class.getName();
            case ZONED_DATETIME -> OffsetDateTime.class.getName();
            case YEAR_TO_MONTH -> Period.class.getName();
            case DAY_TO_SECOND -> Duration.class.getName();
            case LIST -> List.class.getName();
            case SET -> Set.class.getName();
            case MAP, RECORD -> Map.class.getName();
            case VECTOR -> "[F";
            case POINT, POINT3D -> GraphPoint.class.getName();
            case NODE -> GraphNode.class.getName();
            case EDGE -> GraphEdge.class.getName();
            case PATH -> GraphPath.class.getName();
            case TABLE -> GraphTable.class.getName();
            case ERROR -> GraphError.class.getName();
            default -> Object.class.getName();
        };
    }

    /**
     * Returns the display size this driver reports for a GQLDB type: an upper bound for fixed
     * width types, and {@link Integer#MAX_VALUE} where the width is unbounded.
     *
     * @param type the GQLDB type, may be {@code null}
     * @return the display size in characters
     */
    public static int displaySize(PropertyType type) {
        if (type == null) {
            return Integer.MAX_VALUE;
        }
        return switch (type) {
            case BOOL -> 5;
            case INT32 -> 11;
            case UINT32 -> 10;
            case INT64 -> 20;
            case UINT64 -> 20;
            case FLOAT -> 15;
            case DOUBLE -> 24;
            case DATE -> 10;
            case LOCAL_TIME -> 18;
            case ZONED_TIME -> 24;
            case LOCAL_DATETIME, DATETIME, TIMESTAMP -> 29;
            case ZONED_DATETIME -> 35;
            case NULL -> 4;
            default -> Integer.MAX_VALUE;
        };
    }

    /**
     * Resolves a server type name, as reported by schema introspection, to a GQLDB type.
     *
     * <p>Comparison is case-insensitive and tolerates a few spellings the server uses
     * interchangeably. An unrecognised name yields {@link PropertyType#UNSET}, which is reported
     * as {@link Types#OTHER} rather than being guessed at.
     *
     * @param serverTypeName the server's type name, may be {@code null}
     * @return the matching type, or {@link PropertyType#UNSET}
     */
    public static PropertyType fromServerTypeName(String serverTypeName) {
        if (serverTypeName == null || serverTypeName.isBlank()) {
            return PropertyType.UNSET;
        }
        String normalised = serverTypeName.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        return switch (normalised) {
            case "bool", "boolean" -> PropertyType.BOOL;
            case "int", "int32", "integer" -> PropertyType.INT32;
            case "uint32" -> PropertyType.UINT32;
            case "int64", "long", "bigint" -> PropertyType.INT64;
            case "uint64" -> PropertyType.UINT64;
            case "float", "float32" -> PropertyType.FLOAT;
            case "double", "float64" -> PropertyType.DOUBLE;
            case "decimal", "numeric" -> PropertyType.DECIMAL;
            case "string", "varchar" -> PropertyType.STRING;
            case "text" -> PropertyType.TEXT;
            case "blob", "bytes", "binary" -> PropertyType.BLOB;
            case "date" -> PropertyType.DATE;
            case "time", "local_time" -> PropertyType.LOCAL_TIME;
            case "zoned_time" -> PropertyType.ZONED_TIME;
            case "datetime", "local_datetime" -> PropertyType.LOCAL_DATETIME;
            case "timestamp" -> PropertyType.TIMESTAMP;
            case "zoned_datetime" -> PropertyType.ZONED_DATETIME;
            case "year_to_month" -> PropertyType.YEAR_TO_MONTH;
            case "day_to_second" -> PropertyType.DAY_TO_SECOND;
            case "list", "array" -> PropertyType.LIST;
            case "set" -> PropertyType.SET;
            case "map" -> PropertyType.MAP;
            case "record" -> PropertyType.RECORD;
            case "point" -> PropertyType.POINT;
            case "point3d" -> PropertyType.POINT3D;
            case "vector" -> PropertyType.VECTOR;
            case "node" -> PropertyType.NODE;
            case "edge" -> PropertyType.EDGE;
            case "path" -> PropertyType.PATH;
            case "table" -> PropertyType.TABLE;
            case "null" -> PropertyType.NULL;
            default -> PropertyType.UNSET;
        };
    }

    /**
     * Returns {@code true} if a GQLDB type is one JDBC has no faithful counterpart for, and
     * which is therefore exposed as a {@link com.ultipa.jdbc.graph} value object.
     *
     * @param type the GQLDB type, may be {@code null}
     * @return whether the type is graph-specific
     */
    public static boolean isGraphSpecific(PropertyType type) {
        if (type == null) {
            return false;
        }
        return switch (type) {
            case NODE, EDGE, PATH, POINT, POINT3D, TABLE, ERROR, VECTOR -> true;
            default -> false;
        };
    }

    /**
     * Renders a converted value as text for {@link java.sql.ResultSet#getString(int)}.
     *
     * <p>Scalars and temporals use their Java {@code toString()}, so temporal values are
     * ISO-8601. Binary values are Base64. Graph values use the {@code toString()} of their
     * {@link com.ultipa.jdbc.graph} type, which keeps their structure visible instead of
     * discarding it.
     *
     * @param converted a value already passed through {@link #convert(Object)}
     * @return the text form, or {@code null} if the value is {@code null}
     */
    public static String renderAsString(Object converted) {
        if (converted == null) {
            return null;
        }
        if (converted instanceof String text) {
            return text;
        }
        if (converted instanceof byte[] bytes) {
            return Base64.getEncoder().encodeToString(bytes);
        }
        if (converted instanceof float[] floats) {
            StringBuilder text = new StringBuilder("[");
            for (int i = 0; i < floats.length; i++) {
                if (i > 0) {
                    text.append(", ");
                }
                text.append(floats[i]);
            }
            return text.append(']').toString();
        }
        return String.valueOf(converted);
    }
}
