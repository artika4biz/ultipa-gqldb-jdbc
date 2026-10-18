package com.ultipa.jdbc.graph;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * An immutable graph edge returned by {@link java.sql.ResultSet#getObject(int)} for a column
 * whose GQLDB value is an edge.
 *
 * <p>Endpoints are reported as node identifiers, which is what the protocol carries; the driver
 * does not fetch the endpoint nodes themselves.
 */
public final class GraphEdge {

    private final String id;
    private final String uuid;
    private final String label;
    private final String fromNodeId;
    private final String toNodeId;
    private final Map<String, Object> properties;

    /**
     * Creates an edge.
     *
     * @param id         the edge's primary identifier, may be {@code null}
     * @param uuid       the edge's internal UUID, may be {@code null}
     * @param label      the edge's label, may be {@code null}
     * @param fromNodeId the source node's identifier, may be {@code null}
     * @param toNodeId   the destination node's identifier, may be {@code null}
     * @param properties the edge's properties; never {@code null} in practice
     */
    public GraphEdge(String id, String uuid, String label,
                     String fromNodeId, String toNodeId, Map<String, Object> properties) {
        this.id = id;
        this.uuid = uuid;
        this.label = label;
        this.fromNodeId = fromNodeId;
        this.toNodeId = toNodeId;
        this.properties = properties == null
                ? Map.of()
                : Collections.unmodifiableMap(new java.util.LinkedHashMap<>(properties));
    }

    /** Returns the edge's primary identifier, or {@code null}. */
    public String getId() {
        return id;
    }

    /** Returns the edge's internal UUID, or {@code null}. */
    public String getUuid() {
        return uuid;
    }

    /** Returns the edge's label, or {@code null}. */
    public String getLabel() {
        return label;
    }

    /** Returns the identifier of the node this edge starts at, or {@code null}. */
    public String getFromNodeId() {
        return fromNodeId;
    }

    /** Returns the identifier of the node this edge ends at, or {@code null}. */
    public String getToNodeId() {
        return toNodeId;
    }

    /** Returns the edge's properties. Never {@code null}. */
    public Map<String, Object> getProperties() {
        return properties;
    }

    /**
     * Returns a single property value.
     *
     * @param name the property name
     * @return the value, or {@code null} if absent or explicitly null
     */
    public Object getProperty(String name) {
        return properties.get(name);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof GraphEdge other)) {
            return false;
        }
        return Objects.equals(id, other.id)
                && Objects.equals(uuid, other.uuid)
                && Objects.equals(label, other.label)
                && Objects.equals(fromNodeId, other.fromNodeId)
                && Objects.equals(toNodeId, other.toNodeId)
                && properties.equals(other.properties);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, uuid, label, fromNodeId, toNodeId, properties);
    }

    @Override
    public String toString() {
        return "GraphEdge{id=" + id + ", label=" + label
                + ", from=" + fromNodeId + ", to=" + toNodeId
                + ", properties=" + properties + '}';
    }
}
