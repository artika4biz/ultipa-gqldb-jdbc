package com.ultipa.jdbc;

import com.gqldb.types.PropertyType;
import com.ultipa.jdbc.internal.GraphLabelDef;
import com.ultipa.jdbc.internal.GraphPropertyDef;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.RowIdLifetime;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/**
 * Reports what this driver and an Ultipa GQLDB server actually support.
 *
 * <h2>The mapping to JDBC's relational vocabulary</h2>
 *
 * <ul>
 *   <li>A <strong>graph</strong> is a JDBC <strong>catalog</strong>: graphs are independent,
 *       switchable namespaces. {@link #getCatalogs()} lists them.</li>
 *   <li>There is no schema level, so {@link #getSchemas()} is empty.</li>
 *   <li>A <strong>node label</strong> is reported as a table of type {@code "TABLE"}, and an
 *       <strong>edge label</strong> as one of type {@code "EDGE"}. Both appear in
 *       {@link #getTables}, and {@link #getTableTypes()} lists both, so a tool that filters on
 *       {@code "TABLE"} sees node labels while edge labels stay distinguishable rather than
 *       being conflated with them.</li>
 *   <li>A <strong>property</strong> is a column. {@link #getColumns} reports the properties
 *       a label <em>declares</em>, with the JDBC type derived from the server's type name.
 *       A schema-less (open) graph declares none — its properties live on individual elements
 *       — so {@code getColumns} is legitimately empty there while {@link #getTables} still
 *       lists every label. Query a row and read {@link java.sql.ResultSetMetaData} to learn
 *       the shape of a result in such a graph.</li>
 * </ul>
 *
 * <h2>Honesty of the capability flags</h2>
 *
 * <p>Every {@code supportsXxx} method below answers for GQL and for this driver, not for a
 * hypothetical SQL engine. The SQL grammar levels, SQL-92 conformance, stored procedures, unions,
 * outer joins and the rest are reported as unsupported, because the server does not accept SQL.
 * {@link #supportsTransactions()} is {@code true} because the protocol really does have
 * begin/commit/rollback — but {@link #getDefaultTransactionIsolation()} is
 * {@link Connection#TRANSACTION_NONE}, because it carries no isolation levels.
 *
 * <p>Methods that would require information the protocol does not provide — key and index
 * metadata, privileges, user-defined types, server function catalogues — return an empty result
 * set of the correct shape. An empty result is the contract-compliant way to say "nothing to
 * report"; it is not a claim that the underlying feature exists.
 */
public final class UltipaDatabaseMetaData implements DatabaseMetaData {

    /** The table type reported for node labels. */
    public static final String TABLE_TYPE_NODE = "TABLE";

    /** The table type reported for edge labels. */
    public static final String TABLE_TYPE_EDGE = "EDGE";

    /**
     * GQL keywords that are not SQL:2003 keywords, as
     * {@link #getSQLKeywords()} is specified to report.
     */
    private static final String GQL_KEYWORDS = String.join(",",
            "ACYCLIC", "ANY", "BINDING", "BOUND", "CALL", "DETACH", "DIRECTED", "EDGE",
            "ELEMENT", "GQLSTATUS", "GRAPH", "LABEL", "LABELED", "LET", "MATCH", "NODE",
            "PATH", "PATHS", "PROPERTY", "RECORD", "REMOVE", "REPEATABLE", "RETURN", "SHORTEST",
            "SIMPLE", "TRAIL", "WALK", "YIELD");

    private final UltipaConnection connection;

    /**
     * Creates metadata for a connection.
     *
     * @param connection the connection to describe
     */
    UltipaDatabaseMetaData(UltipaConnection connection) {
        this.connection = connection;
    }

    // ---------------------------------------------------------------------
    // Identity and versions
    // ---------------------------------------------------------------------

    @Override
    public String getDatabaseProductName() {
        return "Ultipa GQLDB";
    }

    /**
     * {@inheritDoc}
     *
     * <p>The version string the server reported at login, or {@code "unknown"} if it reported
     * none. The driver does not parse or embellish it.
     */
    @Override
    public String getDatabaseProductVersion() {
        String version = connection.executor().getServerVersion();
        return version == null || version.isBlank() ? "unknown" : version;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Derived from the leading numeric component of the server version, or {@code 0} if the
     * version is not in a recognisable form. {@link #getDatabaseProductVersion()} is the
     * authoritative value.
     */
    @Override
    public int getDatabaseMajorVersion() {
        return versionComponent(0);
    }

    @Override
    public int getDatabaseMinorVersion() {
        return versionComponent(1);
    }

    private int versionComponent(int index) {
        String version = connection.executor().getServerVersion();
        if (version == null) {
            return 0;
        }
        String[] parts = version.trim().split("[.\\-+_]");
        if (index >= parts.length) {
            return 0;
        }
        try {
            return Integer.parseInt(parts[index]);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @Override
    public String getDriverName() {
        return UltipaDriver.DRIVER_NAME;
    }

    @Override
    public String getDriverVersion() {
        return UltipaDriver.version();
    }

    @Override
    public int getDriverMajorVersion() {
        return UltipaDriver.MAJOR_VERSION;
    }

    @Override
    public int getDriverMinorVersion() {
        return UltipaDriver.MINOR_VERSION;
    }

    @Override
    public int getJDBCMajorVersion() {
        return 4;
    }

    @Override
    public int getJDBCMinorVersion() {
        return 3;
    }

    @Override
    public String getURL() {
        List<String> hosts = connection.config().getHosts();
        String graph = connection.config().getGraph();
        return UltipaConnectionConfig.URL_PREFIX + String.join(",", hosts)
                + (graph == null ? "" : "/" + graph);
    }

    @Override
    public String getUserName() {
        return connection.config().getUser();
    }

    @Override
    public Connection getConnection() {
        return connection;
    }

    @Override
    public boolean isReadOnly() throws SQLException {
        return connection.isReadOnly();
    }

    // ---------------------------------------------------------------------
    // Catalogs, schemas, tables, columns
    // ---------------------------------------------------------------------

    @Override
    public ResultSet getCatalogs() throws SQLException {
        List<List<Object>> rows = new ArrayList<>();
        for (String graph : connection.executor().listGraphs()) {
            rows.add(List.of(graph));
        }
        return UltipaMetaDataResultSets.of(List.of("TABLE_CAT"), rows);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Always empty: GQLDB has no schema level. Graphs are reported as catalogs.
     */
    @Override
    public ResultSet getSchemas() {
        return UltipaMetaDataResultSets.empty(List.of("TABLE_SCHEM", "TABLE_CATALOG"));
    }

    @Override
    public ResultSet getSchemas(String catalog, String schemaPattern) {
        return getSchemas();
    }

    @Override
    public ResultSet getTableTypes() {
        return UltipaMetaDataResultSets.of(List.of("TABLE_TYPE"),
                List.of(List.of(TABLE_TYPE_EDGE), List.of(TABLE_TYPE_NODE)));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Node labels are reported with table type {@code "TABLE"} and edge labels with
     * {@code "EDGE"}; {@code REMARKS} says which. Only the connection's current graph is
     * introspected: the protocol's introspection statements act on the selected graph, so a
     * {@code catalog} argument naming a different graph yields an empty result rather than a
     * silent switch of the session's graph.
     */
    @Override
    public ResultSet getTables(String catalog, String schemaPattern, String tableNamePattern,
                               String[] types) throws SQLException {
        List<String> columns = List.of(
                "TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "TABLE_TYPE", "REMARKS",
                "TYPE_CAT", "TYPE_SCHEM", "TYPE_NAME", "SELF_REFERENCING_COL_NAME", "REF_GENERATION");

        String currentGraph = connection.getCatalog();
        if (!UltipaMetaDataResultSets.equalsIgnoringCase(emptyToNull(catalog), currentGraph)) {
            return UltipaMetaDataResultSets.empty(columns);
        }

        boolean wantNodes = wantsType(types, TABLE_TYPE_NODE);
        boolean wantEdges = wantsType(types, TABLE_TYPE_EDGE);

        List<List<Object>> rows = new ArrayList<>();
        if (wantNodes) {
            for (GraphLabelDef label : connection.executor().listLabels(true)) {
                if (UltipaMetaDataResultSets.matches(tableNamePattern, label.name())) {
                    rows.add(tableRow(currentGraph, label.name(), TABLE_TYPE_NODE, "Node label"));
                }
            }
        }
        if (wantEdges) {
            for (GraphLabelDef label : connection.executor().listLabels(false)) {
                if (UltipaMetaDataResultSets.matches(tableNamePattern, label.name())) {
                    rows.add(tableRow(currentGraph, label.name(), TABLE_TYPE_EDGE, "Edge label"));
                }
            }
        }
        return UltipaMetaDataResultSets.of(columns, rows);
    }

    private static List<Object> tableRow(String catalog, String name, String type, String remarks) {
        List<Object> row = new ArrayList<>();
        row.add(catalog);
        row.add(null);
        row.add(name);
        row.add(type);
        row.add(remarks);
        row.add(null);
        row.add(null);
        row.add(null);
        row.add(null);
        row.add(null);
        return row;
    }

    private static boolean wantsType(String[] types, String type) {
        if (types == null) {
            return true;
        }
        for (String candidate : types) {
            if (candidate != null && candidate.equalsIgnoreCase(type)) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Reports the properties <em>declared</em> for each matching node or edge label.
     * {@code DATA_TYPE} is derived from the server's property type name; a type name the driver
     * does not recognise is reported as {@link Types#OTHER} rather than guessed at.
     * {@code NULLABLE} is {@link #columnNullableUnknown}, because the introspection output does
     * not state nullability.
     *
     * <p>Only a graph with a declared schema has properties to report. In a schema-less graph
     * this returns no rows, which is the truthful answer: the server declares no properties
     * there, and the driver does not sample elements to invent a schema.
     */
    @Override
    public ResultSet getColumns(String catalog, String schemaPattern, String tableNamePattern,
                                String columnNamePattern) throws SQLException {
        List<String> columns = List.of(
                "TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "COLUMN_NAME", "DATA_TYPE", "TYPE_NAME",
                "COLUMN_SIZE", "BUFFER_LENGTH", "DECIMAL_DIGITS", "NUM_PREC_RADIX", "NULLABLE",
                "REMARKS", "COLUMN_DEF", "SQL_DATA_TYPE", "SQL_DATETIME_SUB", "CHAR_OCTET_LENGTH",
                "ORDINAL_POSITION", "IS_NULLABLE", "SCOPE_CATALOG", "SCOPE_SCHEMA", "SCOPE_TABLE",
                "SOURCE_DATA_TYPE", "IS_AUTOINCREMENT", "IS_GENERATEDCOLUMN");

        String currentGraph = connection.getCatalog();
        if (!UltipaMetaDataResultSets.equalsIgnoringCase(emptyToNull(catalog), currentGraph)) {
            return UltipaMetaDataResultSets.empty(columns);
        }

        List<List<Object>> rows = new ArrayList<>();
        for (boolean nodeLabels : new boolean[] {true, false}) {
            for (GraphLabelDef label : connection.executor().listLabels(nodeLabels)) {
                if (!UltipaMetaDataResultSets.matches(tableNamePattern, label.name())) {
                    continue;
                }
                int ordinal = 0;
                for (GraphPropertyDef property : label.properties()) {
                    ordinal++;
                    if (!UltipaMetaDataResultSets.matches(columnNamePattern, property.name())) {
                        continue;
                    }
                    rows.add(columnRow(currentGraph, label.name(), property, ordinal));
                }
            }
        }
        return UltipaMetaDataResultSets.of(columns, rows);
    }

    private static List<Object> columnRow(String catalog, String tableName,
                                          GraphPropertyDef property, int ordinal) {
        PropertyType type = UltipaTypeMapper.fromServerTypeName(property.type());
        List<Object> row = new ArrayList<>();
        row.add(catalog);
        row.add(null);
        row.add(tableName);
        row.add(property.name());
        row.add(UltipaTypeMapper.toJdbcType(type));
        row.add(property.type() == null ? UltipaTypeMapper.typeName(type) : property.type());
        row.add(UltipaTypeMapper.displaySize(type));
        row.add(null);
        row.add(null);
        row.add(10);
        row.add(columnNullableUnknown);
        row.add(null);
        row.add(null);
        row.add(null);
        row.add(null);
        row.add(null);
        row.add(ordinal);
        row.add("");
        row.add(null);
        row.add(null);
        row.add(null);
        row.add(null);
        row.add("NO");
        row.add("NO");
        return row;
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Describes the GQLDB type system: one row per type the server can return, with the JDBC
     * type the driver maps it to.
     */
    @Override
    public ResultSet getTypeInfo() {
        List<String> columns = List.of(
                "TYPE_NAME", "DATA_TYPE", "PRECISION", "LITERAL_PREFIX", "LITERAL_SUFFIX",
                "CREATE_PARAMS", "NULLABLE", "CASE_SENSITIVE", "SEARCHABLE", "UNSIGNED_ATTRIBUTE",
                "FIXED_PREC_SCALE", "AUTO_INCREMENT", "LOCAL_TYPE_NAME", "MINIMUM_SCALE",
                "MAXIMUM_SCALE", "SQL_DATA_TYPE", "SQL_DATETIME_SUB", "NUM_PREC_RADIX");

        List<List<Object>> rows = new ArrayList<>();
        for (PropertyType type : PropertyType.values()) {
            if (type == PropertyType.UNSET) {
                continue;
            }
            boolean textual = type == PropertyType.STRING || type == PropertyType.TEXT;
            boolean unsigned = type == PropertyType.UINT32 || type == PropertyType.UINT64;
            List<Object> row = new ArrayList<>();
            row.add(UltipaTypeMapper.typeName(type));
            row.add(UltipaTypeMapper.toJdbcType(type));
            row.add(UltipaTypeMapper.displaySize(type));
            row.add(textual ? "'" : null);
            row.add(textual ? "'" : null);
            row.add(null);
            row.add(typeNullable);
            row.add(textual ? 1 : 0);
            row.add(UltipaTypeMapper.isGraphSpecific(type) ? typePredNone : typeSearchable);
            row.add(unsigned ? 1 : 0);
            row.add(0);
            row.add(0);
            row.add(UltipaTypeMapper.typeName(type));
            row.add(0);
            row.add(0);
            row.add(null);
            row.add(null);
            row.add(10);
            rows.add(row);
        }
        return UltipaMetaDataResultSets.of(columns, rows);
    }

    // ---------------------------------------------------------------------
    // Metadata the protocol does not expose: correctly shaped, empty
    // ---------------------------------------------------------------------

    /**
     * {@inheritDoc}
     *
     * <p>Always empty. A node's identifier is not a relational primary key over a property, and
     * the protocol does not describe one, so none is reported. A node or edge identifier is
     * available at the value level, from the {@link com.ultipa.jdbc.graph.GraphNode} or
     * {@link com.ultipa.jdbc.graph.GraphEdge} that {@code getObject} returns.
     */
    @Override
    public ResultSet getPrimaryKeys(String catalog, String schema, String table) {
        return UltipaMetaDataResultSets.empty(List.of(
                "TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "COLUMN_NAME", "KEY_SEQ", "PK_NAME"));
    }

    private static List<String> crossReferenceColumns() {
        return List.of("PKTABLE_CAT", "PKTABLE_SCHEM", "PKTABLE_NAME", "PKCOLUMN_NAME",
                "FKTABLE_CAT", "FKTABLE_SCHEM", "FKTABLE_NAME", "FKCOLUMN_NAME", "KEY_SEQ",
                "UPDATE_RULE", "DELETE_RULE", "FK_NAME", "PK_NAME", "DEFERRABILITY");
    }

    /**
     * {@inheritDoc}
     *
     * <p>Always empty. Relationships in a graph are edges, which are reported as tables of type
     * {@code "EDGE"} by {@link #getTables}, not as foreign-key constraints between tables.
     */
    @Override
    public ResultSet getImportedKeys(String catalog, String schema, String table) {
        return UltipaMetaDataResultSets.empty(crossReferenceColumns());
    }

    @Override
    public ResultSet getExportedKeys(String catalog, String schema, String table) {
        return UltipaMetaDataResultSets.empty(crossReferenceColumns());
    }

    @Override
    public ResultSet getCrossReference(String parentCatalog, String parentSchema, String parentTable,
                                       String foreignCatalog, String foreignSchema,
                                       String foreignTable) {
        return UltipaMetaDataResultSets.empty(crossReferenceColumns());
    }

    /**
     * {@inheritDoc}
     *
     * <p>Always empty: index metadata is not plumbed through this driver. The server does expose
     * its indexes through {@code SHOW INDEX}, which can be run as an ordinary GQL statement.
     */
    @Override
    public ResultSet getIndexInfo(String catalog, String schema, String table,
                                  boolean unique, boolean approximate) {
        return UltipaMetaDataResultSets.empty(List.of(
                "TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "NON_UNIQUE", "INDEX_QUALIFIER",
                "INDEX_NAME", "TYPE", "ORDINAL_POSITION", "COLUMN_NAME", "ASC_OR_DESC",
                "CARDINALITY", "PAGES", "FILTER_CONDITION"));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Always empty: GQLDB has no stored procedures.
     */
    @Override
    public ResultSet getProcedures(String catalog, String schemaPattern, String procedureNamePattern) {
        return UltipaMetaDataResultSets.empty(List.of(
                "PROCEDURE_CAT", "PROCEDURE_SCHEM", "PROCEDURE_NAME", "RESERVED1", "RESERVED2",
                "RESERVED3", "REMARKS", "PROCEDURE_TYPE", "SPECIFIC_NAME"));
    }

    @Override
    public ResultSet getProcedureColumns(String catalog, String schemaPattern,
                                         String procedureNamePattern, String columnNamePattern) {
        return UltipaMetaDataResultSets.empty(List.of(
                "PROCEDURE_CAT", "PROCEDURE_SCHEM", "PROCEDURE_NAME", "COLUMN_NAME", "COLUMN_TYPE",
                "DATA_TYPE", "TYPE_NAME", "PRECISION", "LENGTH", "SCALE", "RADIX", "NULLABLE",
                "REMARKS", "COLUMN_DEF", "SQL_DATA_TYPE", "SQL_DATETIME_SUB", "CHAR_OCTET_LENGTH",
                "ORDINAL_POSITION", "IS_NULLABLE", "SPECIFIC_NAME"));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Always empty: the protocol does not expose a catalogue of server functions. GQL
     * functions are documented by Ultipa and can be called directly in a statement.
     */
    @Override
    public ResultSet getFunctions(String catalog, String schemaPattern, String functionNamePattern) {
        return UltipaMetaDataResultSets.empty(List.of(
                "FUNCTION_CAT", "FUNCTION_SCHEM", "FUNCTION_NAME", "REMARKS", "FUNCTION_TYPE",
                "SPECIFIC_NAME"));
    }

    @Override
    public ResultSet getFunctionColumns(String catalog, String schemaPattern,
                                        String functionNamePattern, String columnNamePattern) {
        return UltipaMetaDataResultSets.empty(List.of(
                "FUNCTION_CAT", "FUNCTION_SCHEM", "FUNCTION_NAME", "COLUMN_NAME", "COLUMN_TYPE",
                "DATA_TYPE", "TYPE_NAME", "PRECISION", "LENGTH", "SCALE", "RADIX", "NULLABLE",
                "REMARKS", "CHAR_OCTET_LENGTH", "ORDINAL_POSITION", "IS_NULLABLE", "SPECIFIC_NAME"));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Always empty: the protocol does not report per-object privileges.
     */
    @Override
    public ResultSet getTablePrivileges(String catalog, String schemaPattern, String tableNamePattern) {
        return UltipaMetaDataResultSets.empty(List.of(
                "TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "GRANTOR", "GRANTEE", "PRIVILEGE",
                "IS_GRANTABLE"));
    }

    @Override
    public ResultSet getColumnPrivileges(String catalog, String schema, String table,
                                         String columnNamePattern) {
        return UltipaMetaDataResultSets.empty(List.of(
                "TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "COLUMN_NAME", "GRANTOR", "GRANTEE",
                "PRIVILEGE", "IS_GRANTABLE"));
    }

    @Override
    public ResultSet getBestRowIdentifier(String catalog, String schema, String table,
                                          int scope, boolean nullable) {
        return UltipaMetaDataResultSets.empty(List.of(
                "SCOPE", "COLUMN_NAME", "DATA_TYPE", "TYPE_NAME", "COLUMN_SIZE", "BUFFER_LENGTH",
                "DECIMAL_DIGITS", "PSEUDO_COLUMN"));
    }

    @Override
    public ResultSet getVersionColumns(String catalog, String schema, String table) {
        return UltipaMetaDataResultSets.empty(List.of(
                "SCOPE", "COLUMN_NAME", "DATA_TYPE", "TYPE_NAME", "COLUMN_SIZE", "BUFFER_LENGTH",
                "DECIMAL_DIGITS", "PSEUDO_COLUMN"));
    }

    @Override
    public ResultSet getUDTs(String catalog, String schemaPattern, String typeNamePattern,
                             int[] types) {
        return UltipaMetaDataResultSets.empty(List.of(
                "TYPE_CAT", "TYPE_SCHEM", "TYPE_NAME", "CLASS_NAME", "DATA_TYPE", "REMARKS",
                "BASE_TYPE"));
    }

    @Override
    public ResultSet getSuperTypes(String catalog, String schemaPattern, String typeNamePattern) {
        return UltipaMetaDataResultSets.empty(List.of(
                "TYPE_CAT", "TYPE_SCHEM", "TYPE_NAME", "SUPERTYPE_CAT", "SUPERTYPE_SCHEM",
                "SUPERTYPE_NAME"));
    }

    @Override
    public ResultSet getSuperTables(String catalog, String schemaPattern, String tableNamePattern) {
        return UltipaMetaDataResultSets.empty(List.of(
                "TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "SUPERTABLE_NAME"));
    }

    @Override
    public ResultSet getAttributes(String catalog, String schemaPattern, String typeNamePattern,
                                   String attributeNamePattern) {
        return UltipaMetaDataResultSets.empty(List.of(
                "TYPE_CAT", "TYPE_SCHEM", "TYPE_NAME", "ATTR_NAME", "DATA_TYPE", "ATTR_TYPE_NAME",
                "ATTR_SIZE", "DECIMAL_DIGITS", "NUM_PREC_RADIX", "NULLABLE", "REMARKS",
                "ATTR_DEF", "SQL_DATA_TYPE", "SQL_DATETIME_SUB", "CHAR_OCTET_LENGTH",
                "ORDINAL_POSITION", "IS_NULLABLE", "SCOPE_CATALOG", "SCOPE_SCHEMA", "SCOPE_TABLE",
                "SOURCE_DATA_TYPE"));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Always empty: this driver supports no client-info properties, as
     * {@link Connection#setClientInfo(String, String)} reports.
     */
    @Override
    public ResultSet getClientInfoProperties() {
        return UltipaMetaDataResultSets.empty(List.of(
                "NAME", "MAX_LEN", "DEFAULT_VALUE", "DESCRIPTION"));
    }

    @Override
    public ResultSet getPseudoColumns(String catalog, String schemaPattern, String tableNamePattern,
                                      String columnNamePattern) {
        return UltipaMetaDataResultSets.empty(List.of(
                "TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "COLUMN_NAME", "DATA_TYPE",
                "COLUMN_SIZE", "DECIMAL_DIGITS", "NUM_PREC_RADIX", "COLUMN_USAGE", "REMARKS",
                "CHAR_OCTET_LENGTH", "IS_NULLABLE"));
    }

    // ---------------------------------------------------------------------
    // Transactions
    // ---------------------------------------------------------------------

    /**
     * {@inheritDoc}
     *
     * <p>{@code true}: the protocol has an explicit transaction service and queries carry a
     * transaction id, so {@link Connection#commit()} and {@link Connection#rollback()} do real
     * work.
     */
    @Override
    public boolean supportsTransactions() {
        return true;
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@link Connection#TRANSACTION_NONE}: the protocol carries no isolation levels, so no
     * other level can be honestly reported.
     */
    @Override
    public int getDefaultTransactionIsolation() {
        return Connection.TRANSACTION_NONE;
    }

    @Override
    public boolean supportsTransactionIsolationLevel(int level) {
        return level == Connection.TRANSACTION_NONE;
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@code false}, and so is
     * {@link #supportsDataManipulationTransactionsOnly()}: whether GQLDB admits DDL inside a
     * transaction is not documented, and this driver does not claim either answer. What is
     * known is recorded instead in {@link Connection#setCatalog(String)}, which the server
     * rejects inside a transaction.
     */
    @Override
    public boolean supportsDataDefinitionAndDataManipulationTransactions() {
        return false;
    }

    @Override
    public boolean supportsDataManipulationTransactionsOnly() {
        return false;
    }

    @Override
    public boolean dataDefinitionCausesTransactionCommit() {
        return false;
    }

    @Override
    public boolean dataDefinitionIgnoredInTransactions() {
        return false;
    }

    @Override
    public boolean supportsSavepoints() {
        return false;
    }

    @Override
    public boolean supportsMultipleTransactions() {
        return true;
    }

    @Override
    public boolean autoCommitFailureClosesAllResultSets() {
        return false;
    }

    // ---------------------------------------------------------------------
    // Result sets
    // ---------------------------------------------------------------------

    @Override
    public boolean supportsResultSetType(int type) {
        return type == ResultSet.TYPE_FORWARD_ONLY;
    }

    @Override
    public boolean supportsResultSetConcurrency(int type, int concurrency) {
        return type == ResultSet.TYPE_FORWARD_ONLY && concurrency == ResultSet.CONCUR_READ_ONLY;
    }

    @Override
    public boolean supportsResultSetHoldability(int holdability) {
        return holdability == ResultSet.HOLD_CURSORS_OVER_COMMIT;
    }

    @Override
    public int getResultSetHoldability() {
        return ResultSet.HOLD_CURSORS_OVER_COMMIT;
    }

    @Override
    public boolean ownUpdatesAreVisible(int type) {
        return false;
    }

    @Override
    public boolean ownDeletesAreVisible(int type) {
        return false;
    }

    @Override
    public boolean ownInsertsAreVisible(int type) {
        return false;
    }

    @Override
    public boolean othersUpdatesAreVisible(int type) {
        return false;
    }

    @Override
    public boolean othersDeletesAreVisible(int type) {
        return false;
    }

    @Override
    public boolean othersInsertsAreVisible(int type) {
        return false;
    }

    @Override
    public boolean updatesAreDetected(int type) {
        return false;
    }

    @Override
    public boolean deletesAreDetected(int type) {
        return false;
    }

    @Override
    public boolean insertsAreDetected(int type) {
        return false;
    }

    @Override
    public boolean supportsBatchUpdates() {
        return true;
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@code false}: the protocol has no prepare request, so a statement cannot be pooled
     * server side. {@link java.sql.Statement#setPoolable(boolean)} remains a hint a connection
     * pool may act on.
     */
    @Override
    public boolean supportsStatementPooling() {
        return false;
    }

    @Override
    public boolean supportsGetGeneratedKeys() {
        return false;
    }

    @Override
    public boolean generatedKeyAlwaysReturned() {
        return false;
    }

    @Override
    public RowIdLifetime getRowIdLifetime() {
        return RowIdLifetime.ROWID_UNSUPPORTED;
    }

    @Override
    public boolean locatorsUpdateCopy() {
        return false;
    }

    @Override
    public boolean supportsStoredFunctionsUsingCallSyntax() {
        return false;
    }

    @Override
    public boolean supportsRefCursors() {
        return false;
    }

    @Override
    public boolean supportsSharding() {
        return false;
    }

    // ---------------------------------------------------------------------
    // Identifiers and syntax
    // ---------------------------------------------------------------------

    /**
     * {@inheritDoc}
     *
     * <p>Returns {@code " "}, the JDBC encoding for "identifier quoting is not supported".
     * Ultipa documents the backtick as a <em>string literal</em> delimiter and does not document
     * a delimited-identifier form, so the driver does not advertise one it has not verified.
     */
    @Override
    public String getIdentifierQuoteString() {
        return " ";
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns {@code "\"}: the escape applies to the {@code %} and {@code _} wildcards of
     * this class's own pattern arguments, which the driver matches locally.
     */
    @Override
    public String getSearchStringEscape() {
        return "\\";
    }

    @Override
    public String getSQLKeywords() {
        return GQL_KEYWORDS;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Empty: the driver does not ship a catalogue of GQL functions, and listing a partial one
     * would mislead a tool into thinking it is complete.
     */
    @Override
    public String getNumericFunctions() {
        return "";
    }

    @Override
    public String getStringFunctions() {
        return "";
    }

    @Override
    public String getSystemFunctions() {
        return "";
    }

    @Override
    public String getTimeDateFunctions() {
        return "";
    }

    @Override
    public String getCatalogTerm() {
        return "graph";
    }

    @Override
    public String getSchemaTerm() {
        return "";
    }

    @Override
    public String getProcedureTerm() {
        return "";
    }

    @Override
    public String getCatalogSeparator() {
        return "";
    }

    @Override
    public boolean isCatalogAtStart() {
        return true;
    }

    @Override
    public String getExtraNameCharacters() {
        return "";
    }

    @Override
    public int getSQLStateType() {
        return sqlStateSQL;
    }

    /**
     * {@inheritDoc}
     *
     * <p>GQLDB label and property names are case-sensitive, so identifiers are neither
     * upper-cased nor lower-cased by the server, and nothing is stored in mixed case on the
     * driver's behalf.
     */
    @Override
    public boolean storesUpperCaseIdentifiers() {
        return false;
    }

    @Override
    public boolean storesLowerCaseIdentifiers() {
        return false;
    }

    @Override
    public boolean storesMixedCaseIdentifiers() {
        return true;
    }

    @Override
    public boolean supportsMixedCaseIdentifiers() {
        return true;
    }

    @Override
    public boolean storesUpperCaseQuotedIdentifiers() {
        return false;
    }

    @Override
    public boolean storesLowerCaseQuotedIdentifiers() {
        return false;
    }

    @Override
    public boolean storesMixedCaseQuotedIdentifiers() {
        return false;
    }

    @Override
    public boolean supportsMixedCaseQuotedIdentifiers() {
        return false;
    }

    // ---------------------------------------------------------------------
    // SQL features: not supported, because the server speaks GQL
    // ---------------------------------------------------------------------

    @Override
    public boolean supportsMinimumSQLGrammar() {
        return false;
    }

    @Override
    public boolean supportsCoreSQLGrammar() {
        return false;
    }

    @Override
    public boolean supportsExtendedSQLGrammar() {
        return false;
    }

    @Override
    public boolean supportsANSI92EntryLevelSQL() {
        return false;
    }

    @Override
    public boolean supportsANSI92IntermediateSQL() {
        return false;
    }

    @Override
    public boolean supportsANSI92FullSQL() {
        return false;
    }

    @Override
    public boolean supportsIntegrityEnhancementFacility() {
        return false;
    }

    @Override
    public boolean supportsOuterJoins() {
        return false;
    }

    @Override
    public boolean supportsFullOuterJoins() {
        return false;
    }

    @Override
    public boolean supportsLimitedOuterJoins() {
        return false;
    }

    @Override
    public boolean supportsUnion() {
        return false;
    }

    @Override
    public boolean supportsUnionAll() {
        return false;
    }

    @Override
    public boolean supportsSubqueriesInComparisons() {
        return false;
    }

    @Override
    public boolean supportsSubqueriesInExists() {
        return false;
    }

    @Override
    public boolean supportsSubqueriesInIns() {
        return false;
    }

    @Override
    public boolean supportsSubqueriesInQuantifieds() {
        return false;
    }

    @Override
    public boolean supportsCorrelatedSubqueries() {
        return false;
    }

    @Override
    public boolean supportsStoredProcedures() {
        return false;
    }

    @Override
    public boolean supportsSelectForUpdate() {
        return false;
    }

    @Override
    public boolean supportsPositionedDelete() {
        return false;
    }

    @Override
    public boolean supportsPositionedUpdate() {
        return false;
    }

    @Override
    public boolean supportsOpenCursorsAcrossCommit() {
        return true;
    }

    @Override
    public boolean supportsOpenCursorsAcrossRollback() {
        return true;
    }

    @Override
    public boolean supportsOpenStatementsAcrossCommit() {
        return true;
    }

    @Override
    public boolean supportsOpenStatementsAcrossRollback() {
        return true;
    }

    @Override
    public boolean supportsMultipleResultSets() {
        return false;
    }

    @Override
    public boolean supportsMultipleOpenResults() {
        return false;
    }

    @Override
    public boolean supportsNamedParameters() {
        return false;
    }

    @Override
    public boolean supportsAlterTableWithAddColumn() {
        return false;
    }

    @Override
    public boolean supportsAlterTableWithDropColumn() {
        return false;
    }

    @Override
    public boolean supportsColumnAliasing() {
        return true;
    }

    @Override
    public boolean nullPlusNonNullIsNull() {
        return true;
    }

    @Override
    public boolean supportsConvert() {
        return false;
    }

    @Override
    public boolean supportsConvert(int fromType, int toType) {
        return false;
    }

    @Override
    public boolean supportsTableCorrelationNames() {
        return false;
    }

    @Override
    public boolean supportsDifferentTableCorrelationNames() {
        return false;
    }

    @Override
    public boolean supportsExpressionsInOrderBy() {
        return true;
    }

    @Override
    public boolean supportsOrderByUnrelated() {
        return true;
    }

    @Override
    public boolean supportsGroupBy() {
        return true;
    }

    @Override
    public boolean supportsGroupByUnrelated() {
        return true;
    }

    @Override
    public boolean supportsGroupByBeyondSelect() {
        return true;
    }

    @Override
    public boolean supportsLikeEscapeClause() {
        return false;
    }

    @Override
    public boolean supportsNonNullableColumns() {
        return true;
    }

    @Override
    public boolean nullsAreSortedHigh() {
        return false;
    }

    @Override
    public boolean nullsAreSortedLow() {
        return false;
    }

    @Override
    public boolean nullsAreSortedAtStart() {
        return false;
    }

    @Override
    public boolean nullsAreSortedAtEnd() {
        return false;
    }

    @Override
    public boolean allProceduresAreCallable() {
        return false;
    }

    @Override
    public boolean allTablesAreSelectable() {
        return true;
    }

    @Override
    public boolean usesLocalFiles() {
        return false;
    }

    @Override
    public boolean usesLocalFilePerTable() {
        return false;
    }

    @Override
    public boolean supportsCatalogsInDataManipulation() {
        return false;
    }

    @Override
    public boolean supportsCatalogsInProcedureCalls() {
        return false;
    }

    @Override
    public boolean supportsCatalogsInTableDefinitions() {
        return false;
    }

    @Override
    public boolean supportsCatalogsInIndexDefinitions() {
        return false;
    }

    @Override
    public boolean supportsCatalogsInPrivilegeDefinitions() {
        return false;
    }

    @Override
    public boolean supportsSchemasInDataManipulation() {
        return false;
    }

    @Override
    public boolean supportsSchemasInProcedureCalls() {
        return false;
    }

    @Override
    public boolean supportsSchemasInTableDefinitions() {
        return false;
    }

    @Override
    public boolean supportsSchemasInIndexDefinitions() {
        return false;
    }

    @Override
    public boolean supportsSchemasInPrivilegeDefinitions() {
        return false;
    }

    // ---------------------------------------------------------------------
    // Limits: 0 means "unknown or not limited by the driver"
    // ---------------------------------------------------------------------

    @Override
    public int getMaxBinaryLiteralLength() {
        return 0;
    }

    @Override
    public int getMaxCharLiteralLength() {
        return 0;
    }

    @Override
    public int getMaxColumnNameLength() {
        return 0;
    }

    @Override
    public int getMaxColumnsInGroupBy() {
        return 0;
    }

    @Override
    public int getMaxColumnsInIndex() {
        return 0;
    }

    @Override
    public int getMaxColumnsInOrderBy() {
        return 0;
    }

    @Override
    public int getMaxColumnsInSelect() {
        return 0;
    }

    @Override
    public int getMaxColumnsInTable() {
        return 0;
    }

    @Override
    public int getMaxConnections() {
        return 0;
    }

    @Override
    public int getMaxCursorNameLength() {
        return 0;
    }

    @Override
    public int getMaxIndexLength() {
        return 0;
    }

    @Override
    public int getMaxSchemaNameLength() {
        return 0;
    }

    @Override
    public int getMaxProcedureNameLength() {
        return 0;
    }

    @Override
    public int getMaxCatalogNameLength() {
        return 0;
    }

    @Override
    public int getMaxRowSize() {
        return 0;
    }

    @Override
    public boolean doesMaxRowSizeIncludeBlobs() {
        return false;
    }

    @Override
    public int getMaxStatementLength() {
        return 0;
    }

    @Override
    public int getMaxStatements() {
        return 0;
    }

    @Override
    public int getMaxTableNameLength() {
        return 0;
    }

    @Override
    public int getMaxTablesInSelect() {
        return 0;
    }

    @Override
    public int getMaxUserNameLength() {
        return 0;
    }

    @Override
    public long getMaxLogicalLobSize() {
        return 0L;
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
                "UltipaDatabaseMetaData cannot be unwrapped to "
                        + (target == null ? "null" : target.getName()));
    }

    @Override
    public boolean isWrapperFor(Class<?> target) {
        return target != null && target.isInstance(this);
    }
}
