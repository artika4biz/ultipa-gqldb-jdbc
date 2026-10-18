/**
 * Driver-owned value types for GQLDB values that have no standard JDBC counterpart: nodes,
 * edges, paths, spatial points, nested tables and error values.
 *
 * <p>These types are returned by {@link java.sql.ResultSet#getObject}. They are deliberately
 * kept in their own package, separate from the standard JDBC implementation, and they never
 * expose a class from the underlying Ultipa SDK. An application that only touches scalar
 * columns never needs to import this package.
 */
package com.ultipa.jdbc.graph;
