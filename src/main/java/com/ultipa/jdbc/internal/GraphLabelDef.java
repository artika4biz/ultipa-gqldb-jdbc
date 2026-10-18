package com.ultipa.jdbc.internal;

import java.util.List;

/**
 * A node or edge label together with the properties declared for it, as returned by the
 * server's {@code SHOW NODE TYPES} and {@code SHOW EDGE TYPES} introspection.
 *
 * <p>Labels and properties arrive together, so {@code DatabaseMetaData.getTables} and
 * {@code getColumns} are both served from one server round trip.
 *
 * @param name       the label name
 * @param properties the properties declared for the label; never {@code null}
 */
public record GraphLabelDef(String name, List<GraphPropertyDef> properties) {

    /**
     * Creates a label definition, defensively copying the property list.
     *
     * @param name       the label name
     * @param properties the declared properties, may be {@code null} for none
     */
    public GraphLabelDef(String name, List<GraphPropertyDef> properties) {
        this.name = name;
        this.properties = properties == null ? List.of() : List.copyOf(properties);
    }
}
