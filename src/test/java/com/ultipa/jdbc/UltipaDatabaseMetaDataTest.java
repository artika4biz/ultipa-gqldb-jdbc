package com.ultipa.jdbc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.RowIdLifetime;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UltipaDatabaseMetaDataTest {

    private DatabaseMetaData metaData(FakeGqlExecutor executor) throws SQLException {
        return TestConnections.connection(executor).getMetaData();
    }

    private DatabaseMetaData populated() throws SQLException {
        Map<String, String> personProperties = new LinkedHashMap<>();
        personProperties.put("name", "string");
        personProperties.put("age", "int32");
        personProperties.put("joined", "datetime");
        personProperties.put("mystery", "a_future_type");

        return metaData(new FakeGqlExecutor()
                .withNodeLabel("Person", personProperties)
                .withNodeLabel("Product", Map.of("sku", "string"))
                .withEdgeLabel("KNOWS", Map.of("since", "int32")));
    }

    private static List<List<Object>> drain(ResultSet rows) throws SQLException {
        List<List<Object>> all = new ArrayList<>();
        int columnCount = rows.getMetaData().getColumnCount();
        while (rows.next()) {
            List<Object> row = new ArrayList<>(columnCount);
            for (int i = 1; i <= columnCount; i++) {
                row.add(rows.getObject(i));
            }
            all.add(row);
        }
        return all;
    }

    @Test
    @DisplayName("reports the driver and server identities")
    void reportsIdentities() throws SQLException {
        DatabaseMetaData metaData = metaData(new FakeGqlExecutor().serverVersion("6.2.15"));

        assertAll(
                () -> assertEquals("Ultipa GQLDB", metaData.getDatabaseProductName()),
                () -> assertEquals("6.2.15", metaData.getDatabaseProductVersion()),
                () -> assertEquals(6, metaData.getDatabaseMajorVersion()),
                () -> assertEquals(2, metaData.getDatabaseMinorVersion()),
                () -> assertEquals("Ultipa GQLDB JDBC Driver", metaData.getDriverName()),
                () -> assertEquals("0.1", metaData.getDriverVersion()),
                () -> assertEquals(4, metaData.getJDBCMajorVersion()),
                () -> assertEquals(3, metaData.getJDBCMinorVersion()),
                () -> assertTrue(metaData.getURL().startsWith("jdbc:ultipa://")),
                () -> assertEquals("graph", metaData.getCatalogTerm()));
    }

    @Test
    @DisplayName("says the version is unknown rather than inventing one")
    void handlesMissingServerVersion() throws SQLException {
        DatabaseMetaData metaData = metaData(new FakeGqlExecutor().serverVersion(null));

        assertAll(
                () -> assertEquals("unknown", metaData.getDatabaseProductVersion()),
                () -> assertEquals(0, metaData.getDatabaseMajorVersion()));
    }

    @Test
    @DisplayName("lists graphs as catalogs")
    void listsGraphsAsCatalogs() throws SQLException {
        ResultSet catalogs = metaData(new FakeGqlExecutor()).getCatalogs();

        assertAll(
                () -> assertEquals("TABLE_CAT", catalogs.getMetaData().getColumnLabel(1)),
                () -> assertEquals(List.of(List.of("socialGraph"), List.of("otherGraph")),
                        drain(catalogs)));
    }

    @Test
    @DisplayName("reports no schemas, because GQLDB has no schema level")
    void reportsNoSchemas() throws SQLException {
        DatabaseMetaData metaData = metaData(new FakeGqlExecutor());

        assertAll(
                () -> assertTrue(drain(metaData.getSchemas()).isEmpty()),
                () -> assertEquals("", metaData.getSchemaTerm()),
                () -> assertFalse(metaData.supportsSchemasInDataManipulation()));
    }

    @Test
    @DisplayName("lists node labels as TABLE and edge labels as EDGE")
    void listsLabelsAsTables() throws SQLException {
        List<List<Object>> tables = drain(populated().getTables(null, null, null, null));

        assertAll(
                () -> assertEquals(3, tables.size()),
                () -> assertEquals("socialGraph", tables.get(0).get(0)),
                () -> assertEquals("Person", tables.get(0).get(2)),
                () -> assertEquals("TABLE", tables.get(0).get(3)),
                () -> assertEquals("Node label", tables.get(0).get(4)),
                () -> assertEquals("KNOWS", tables.get(2).get(2)),
                () -> assertEquals("EDGE", tables.get(2).get(3)),
                () -> assertEquals("Edge label", tables.get(2).get(4)));
    }

    @Test
    @DisplayName("lists both table types it uses")
    void listsTableTypes() throws SQLException {
        assertEquals(List.of(List.of("EDGE"), List.of("TABLE")),
                drain(metaData(new FakeGqlExecutor()).getTableTypes()));
    }

    @Test
    @DisplayName("filters tables by type and by name pattern")
    void filtersTables() throws SQLException {
        assertAll(
                () -> assertEquals(2, drain(populated()
                        .getTables(null, null, null, new String[] {"TABLE"})).size()),
                () -> assertEquals(1, drain(populated()
                        .getTables(null, null, null, new String[] {"EDGE"})).size()),
                () -> assertEquals(1, drain(populated()
                        .getTables(null, null, "Person", null)).size()),
                () -> assertEquals(2, drain(populated()
                        .getTables(null, null, "P%", null)).size()),
                () -> assertEquals(1, drain(populated()
                        .getTables(null, null, "Pe_son", null)).size()),
                () -> assertEquals(0, drain(populated()
                        .getTables(null, null, "Nothing", null)).size()));
    }

    @Test
    @DisplayName("a catalog naming another graph yields nothing rather than switching graphs")
    void filtersByCatalog() throws SQLException {
        assertAll(
                () -> assertEquals(3,
                        drain(populated().getTables("socialGraph", null, null, null)).size()),
                () -> assertEquals(0,
                        drain(populated().getTables("otherGraph", null, null, null)).size()));
    }

    @Test
    @DisplayName("reports properties as columns with the derived JDBC type")
    void reportsPropertiesAsColumns() throws SQLException {
        List<List<Object>> columns =
                drain(populated().getColumns(null, null, "Person", null));

        assertAll(
                () -> assertEquals(4, columns.size()),
                () -> assertEquals("Person", columns.get(0).get(2)),
                () -> assertEquals("name", columns.get(0).get(3)),
                () -> assertEquals(Types.VARCHAR, columns.get(0).get(4)),
                () -> assertEquals("string", columns.get(0).get(5)),
                () -> assertEquals(1, columns.get(0).get(16), "ORDINAL_POSITION is 1-based"),
                () -> assertEquals("age", columns.get(1).get(3)),
                () -> assertEquals(Types.INTEGER, columns.get(1).get(4)),
                () -> assertEquals(Types.TIMESTAMP, columns.get(2).get(4)),
                () -> assertEquals(Types.OTHER, columns.get(3).get(4),
                        "an unrecognised server type is OTHER, not a guess"),
                () -> assertEquals("a_future_type", columns.get(3).get(5),
                        "but the server's own type name is preserved"),
                () -> assertEquals(DatabaseMetaData.columnNullableUnknown, columns.get(0).get(10)));
    }

    @Test
    @DisplayName("getColumns exposes the 24 columns JDBC specifies, in order")
    void reportsTheSpecifiedColumnShape() throws SQLException {
        ResultSetMetaData shape = populated().getColumns(null, null, null, null).getMetaData();

        assertAll(
                () -> assertEquals(24, shape.getColumnCount()),
                () -> assertEquals("TABLE_CAT", shape.getColumnLabel(1)),
                () -> assertEquals("TABLE_NAME", shape.getColumnLabel(3)),
                () -> assertEquals("COLUMN_NAME", shape.getColumnLabel(4)),
                () -> assertEquals("DATA_TYPE", shape.getColumnLabel(5)),
                () -> assertEquals("ORDINAL_POSITION", shape.getColumnLabel(17)),
                () -> assertEquals("IS_GENERATEDCOLUMN", shape.getColumnLabel(24)));
    }

    @Test
    @DisplayName("filters columns by name pattern")
    void filtersColumns() throws SQLException {
        assertAll
                (() -> assertEquals(1,
                        drain(populated().getColumns(null, null, "Person", "name")).size()),
                () -> assertEquals(2,
                        drain(populated().getColumns(null, null, "Person", "%e")).size()),
                () -> assertEquals(0,
                        drain(populated().getColumns(null, null, "Person", "nothing")).size()));
    }

    @Test
    @DisplayName("describes the GQLDB type system")
    void describesTypeSystem() throws SQLException {
        List<List<Object>> types = drain(metaData(new FakeGqlExecutor()).getTypeInfo());

        assertAll(
                () -> assertTrue(types.size() > 20),
                () -> assertTrue(types.stream().anyMatch(row ->
                        row.get(0).equals("string") && row.get(1).equals(Types.VARCHAR))),
                () -> assertTrue(types.stream().anyMatch(row ->
                        row.get(0).equals("node") && row.get(1).equals(Types.OTHER))),
                () -> assertFalse(types.stream().anyMatch(row -> row.get(0).equals("unset"))));
    }

    @Test
    @DisplayName("reports transactions as supported and isolation levels as absent")
    void reportsTransactionSupportHonestly() throws SQLException {
        DatabaseMetaData metaData = metaData(new FakeGqlExecutor());

        assertAll(
                () -> assertTrue(metaData.supportsTransactions()),
                () -> assertEquals(Connection.TRANSACTION_NONE,
                        metaData.getDefaultTransactionIsolation()),
                () -> assertTrue(metaData
                        .supportsTransactionIsolationLevel(Connection.TRANSACTION_NONE)),
                () -> assertFalse(metaData
                        .supportsTransactionIsolationLevel(Connection.TRANSACTION_READ_COMMITTED)),
                () -> assertFalse(metaData.supportsSavepoints()),
                () -> assertFalse(metaData.dataDefinitionCausesTransactionCommit()));
    }

    @Test
    @DisplayName("does not claim any SQL grammar level, because the server speaks GQL")
    void claimsNoSqlSupport() throws SQLException {
        DatabaseMetaData metaData = metaData(new FakeGqlExecutor());

        assertAll(
                () -> assertFalse(metaData.supportsMinimumSQLGrammar()),
                () -> assertFalse(metaData.supportsCoreSQLGrammar()),
                () -> assertFalse(metaData.supportsExtendedSQLGrammar()),
                () -> assertFalse(metaData.supportsANSI92EntryLevelSQL()),
                () -> assertFalse(metaData.supportsANSI92IntermediateSQL()),
                () -> assertFalse(metaData.supportsANSI92FullSQL()),
                () -> assertFalse(metaData.supportsOuterJoins()),
                () -> assertFalse(metaData.supportsUnion()),
                () -> assertFalse(metaData.supportsStoredProcedures()),
                () -> assertFalse(metaData.supportsCorrelatedSubqueries()),
                () -> assertFalse(metaData.supportsSelectForUpdate()),
                () -> assertFalse(metaData.supportsConvert()));
    }

    @Test
    @DisplayName("reports the result set and statement capabilities the driver really has")
    void reportsResultSetCapabilities() throws SQLException {
        DatabaseMetaData metaData = metaData(new FakeGqlExecutor());

        assertAll(
                () -> assertTrue(metaData.supportsResultSetType(ResultSet.TYPE_FORWARD_ONLY)),
                () -> assertFalse(metaData.supportsResultSetType(ResultSet.TYPE_SCROLL_INSENSITIVE)),
                () -> assertTrue(metaData.supportsResultSetConcurrency(
                        ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)),
                () -> assertFalse(metaData.supportsResultSetConcurrency(
                        ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_UPDATABLE)),
                () -> assertTrue(metaData
                        .supportsResultSetHoldability(ResultSet.HOLD_CURSORS_OVER_COMMIT)),
                () -> assertFalse(metaData
                        .supportsResultSetHoldability(ResultSet.CLOSE_CURSORS_AT_COMMIT)),
                () -> assertTrue(metaData.supportsBatchUpdates()),
                () -> assertFalse(metaData.supportsStatementPooling()),
                () -> assertFalse(metaData.supportsGetGeneratedKeys()),
                () -> assertFalse(metaData.supportsMultipleResultSets()),
                () -> assertEquals(RowIdLifetime.ROWID_UNSUPPORTED, metaData.getRowIdLifetime()));
    }

    @Test
    @DisplayName("does not advertise an identifier quote it has not verified")
    void doesNotAdvertiseUnverifiedQuoting() throws SQLException {
        DatabaseMetaData metaData = metaData(new FakeGqlExecutor());

        assertAll(
                () -> assertEquals(" ", metaData.getIdentifierQuoteString()),
                () -> assertEquals("\\", metaData.getSearchStringEscape()),
                () -> assertTrue(metaData.getSQLKeywords().contains("MATCH")),
                () -> assertTrue(metaData.supportsMixedCaseIdentifiers()),
                () -> assertFalse(metaData.storesUpperCaseIdentifiers()),
                () -> assertFalse(metaData.storesLowerCaseIdentifiers()));
    }

    @Test
    @DisplayName("returns correctly shaped empty results for metadata the protocol lacks")
    void returnsEmptyShapesForUnavailableMetadata() throws SQLException {
        DatabaseMetaData metaData = populated();

        assertAll(
                () -> assertTrue(drain(metaData.getPrimaryKeys(null, null, "Person")).isEmpty()),
                () -> assertEquals(6,
                        metaData.getPrimaryKeys(null, null, "Person").getMetaData().getColumnCount()),
                () -> assertTrue(drain(metaData.getImportedKeys(null, null, "Person")).isEmpty()),
                () -> assertEquals(14, metaData.getImportedKeys(null, null, "Person")
                        .getMetaData().getColumnCount()),
                () -> assertTrue(drain(
                        metaData.getIndexInfo(null, null, "Person", false, true)).isEmpty()),
                () -> assertTrue(drain(metaData.getProcedures(null, null, null)).isEmpty()),
                () -> assertTrue(drain(metaData.getFunctions(null, null, null)).isEmpty()),
                () -> assertTrue(drain(metaData.getUDTs(null, null, null, null)).isEmpty()),
                () -> assertTrue(drain(metaData.getClientInfoProperties()).isEmpty()),
                () -> assertTrue(drain(metaData.getTablePrivileges(null, null, null)).isEmpty()),
                () -> assertTrue(drain(metaData.getPseudoColumns(null, null, null, null)).isEmpty()));
    }

    @Test
    @DisplayName("reports the connection it describes and that connection's read-only state")
    void reportsItsConnection() throws SQLException {
        Connection connection = TestConnections.connection(new FakeGqlExecutor());
        DatabaseMetaData metaData = connection.getMetaData();

        assertFalse(metaData.isReadOnly());
        connection.setReadOnly(true);

        assertAll(
                () -> assertTrue(metaData.isReadOnly()),
                () -> assertEquals(connection, metaData.getConnection()),
                () -> assertTrue(metaData.isWrapperFor(UltipaDatabaseMetaData.class)));
    }
}
