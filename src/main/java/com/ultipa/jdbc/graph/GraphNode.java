package com.ultipa.jdbc.graph;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * An immutable graph node returned by {@link java.sql.ResultSet#getObject(int)} for a column
 * whose GQLDB value is a node.
 *
 * <p>This is a driver-owned value type: it carries the node's identity, labels and properties
 * without flattening them into a relational shape, and without exposing any class from the
 * underlying Ultipa SDK to callers.
 *
 * <p>Property values are converted with the same rules as top-level columns, so a list-valued
 * property is a {@link List}, a map-valued property is a {@link Map}, and so on, recursively.
 */
public final class GraphNode {

    private final String id;
    private final String uuid;
    private final List<String> labels;
    private final Map<String, Object> properties;

    /**
     * Creates a node.
     *
     * @param id         the node's primary identifier, may be {@code null} if the server omitted it
     * @param uuid       the node's internal UUID, may be {@code null}
     * @param labels     the node's labels; never {@code null}
     * @param properties the node's properties; never {@code null}
     */
    public GraphNode(String id, String uuid, List<String> labels, Map<String, Object> properties) {
        this.id = id;
        this.uuid = uuid;
        this.labels = labels == null ? List.of() : List.copyOf(labels);
        this.properties = properties == null
                ? Map.of()
                : Collections.unmodifiableMap(new java.util.LinkedHashMap<>(properties));
    }

    /** Returns the node's primary identifier, or {@code null} if the server did not supply one. */
    public String getId() {
        return id;
    }

    /** Returns the node's internal UUID, or {@code null} if the server did not supply one. */
    public String getUuid() {
        return uuid;
    }

    /** Returns the node's labels, in server order. Never {@code null}. */
    public List<String> getLabels() {
        return labels;
    }

    /** Returns the node's properties, in server order. Never {@code null}. */
    public Map<String, Object> getProperties() {
        return properties;
    }

    /**
     * Returns a single property value.
     *
     * @param name the property name
     * @return the value, or {@code null} if the property is absent or explicitly null
     */
    public Object getProperty(String name) {
        return properties.get(name);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof GraphNode other)) {
            return false;
        }
        return Objects.equals(id, other.id)
                && Objects.equals(uuid, other.uuid)
                && labels.equals(other.labels)
                && properties.equals(other.properties);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, uuid, labels, properties);
    }

    @Override
    public String toString() {
        return "GraphNode{id=" + id + ", labels=" + labels + ", properties=" + properties + '}';
    }
}
