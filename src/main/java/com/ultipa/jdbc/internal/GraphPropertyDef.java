package com.ultipa.jdbc.internal;

/**
 * A property definition as reported by the server's schema introspection: a name and the
 * server's own type name.
 *
 * <p>The type is kept as the server's string rather than being eagerly converted, because
 * {@code DatabaseMetaData} needs both the original name ({@code TYPE_NAME}) and the derived
 * JDBC type ({@code DATA_TYPE}).
 *
 * @param name the property name
 * @param type the server's type name, for example {@code "string"} or {@code "int64"}
 */
public record GraphPropertyDef(String name, String type) {
}
