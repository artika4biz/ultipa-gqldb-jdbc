package com.ultipa.jdbc.internal;

import com.gqldb.GqldbClient;
import com.gqldb.GqldbConfig;
import com.gqldb.GraphInfo;
import com.gqldb.QueryConfig;
import com.gqldb.Response;
import com.gqldb.Session;
import com.gqldb.Transaction;
import com.gqldb.types.ConvenienceTypes;
import com.ultipa.jdbc.UltipaConnectionConfig;
import com.ultipa.jdbc.UltipaExceptionMapper;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@link GqlExecutor} backed by the official Ultipa GQLDB SDK.
 *
 * <p>This is the only class in the driver that constructs a {@link GqldbClient} or catches an
 * SDK exception. Every SDK call made here is wrapped so that callers see a
 * {@link SQLException} and never a {@code com.gqldb} or gRPC exception.
 *
 * <p>The current graph is tracked locally and refreshed from every response's
 * {@code current_graph} field, which the protocol documents as authoritative after each
 * successful request — including for a {@code USE GRAPH} embedded in a compound statement,
 * which client-side text inspection cannot see.
 */
public final class SdkGqlExecutor implements GqlExecutor {

    private final GqldbClient client;
    private final String serverVersion;
    private volatile String currentGraph;
    private volatile boolean closed;

    private SdkGqlExecutor(GqldbClient client, Session session, String initialGraph) {
        this.client = client;
        this.serverVersion = session == null ? null : session.getServerVersion();
        String sessionGraph = session == null ? null : session.getDefaultGraph();
        this.currentGraph = isPresent(sessionGraph) ? sessionGraph : initialGraph;
    }

    /**
     * Opens a session: builds the SDK client from a JDBC configuration and authenticates.
     *
     * <p>If authentication fails, the client is closed before the exception propagates, so a
     * rejected connection leaves no gRPC channel behind.
     *
     * @param config the parsed JDBC configuration
     * @return a connected executor
     * @throws SQLException if the configuration is invalid, the host is unreachable, or the
     *                      credentials are rejected
     */
    public static SdkGqlExecutor open(UltipaConnectionConfig config) throws SQLException {
        GqldbConfig sdkConfig = config.toSdkConfig();

        GqldbClient client;
        try {
            client = new GqldbClient(sdkConfig);
        } catch (RuntimeException e) {
            throw UltipaExceptionMapper.mapConnectionFailure(e);
        }

        try {
            Session session = client.login(config.getUser(), config.getPassword());
            return new SdkGqlExecutor(client, session, config.getGraph());
        } catch (RuntimeException e) {
            closeQuietly(client);
            throw UltipaExceptionMapper.mapConnectionFailure(e);
        } catch (Error e) {
            closeQuietly(client);
            throw e;
        }
    }

    @Override
    public Response execute(String gql, QueryConfig config) throws SQLException {
        Response response;
        try {
            response = client.gql(gql, config);
        } catch (RuntimeException e) {
            throw UltipaExceptionMapper.map("executing a GQL statement", e);
        }
        String graphAfter = response.getCurrentGraph();
        if (graphAfter != null) {
            // An empty value is meaningful: it means no graph is selected any more.
            currentGraph = graphAfter.isEmpty() ? null : graphAfter;
        }
        return response;
    }

    @Override
    public String getServerVersion() {
        return serverVersion;
    }

    @Override
    public String getCurrentGraph() {
        return currentGraph;
    }

    @Override
    public void useGraph(String graph) throws SQLException {
        try {
            client.useGraph(graph);
            currentGraph = graph;
        } catch (RuntimeException e) {
            throw UltipaExceptionMapper.map("selecting graph " + graph, e);
        }
    }

    @Override
    public List<String> listGraphs() throws SQLException {
        try {
            List<GraphInfo> graphs = client.listGraphs();
            List<String> names = new ArrayList<>(graphs.size());
            for (GraphInfo graph : graphs) {
                names.add(graph.getName());
            }
            return names;
        } catch (RuntimeException e) {
            throw UltipaExceptionMapper.map("listing graphs", e);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Two server statements are needed, because they answer different questions:
     *
     * <ul>
     *   <li>{@code SHOW NODE LABELS} / {@code SHOW EDGE LABELS} report the labels actually in
     *       use. They work for every graph, schema-less or not, so they are the authoritative
     *       list of "tables".</li>
     *   <li>{@code SHOW NODE TYPES} / {@code SHOW EDGE TYPES} report a graph's <em>declared
     *       schema</em> — key labels, implied labels, endpoint types and property definitions.
     *       A schema-less (open) graph declares none, so these return nothing even when the
     *       labels above are populated.</li>
     * </ul>
     *
     * <p>The two are therefore merged: every label is reported, carrying its declared
     * properties where the graph declares them and none where it does not. A label with no
     * declared properties is a real answer for an open graph, whose properties live on
     * individual nodes rather than in a schema.
     *
     * <p>The label column is list-valued, because a node may carry several labels, so the
     * rows are flattened and de-duplicated with their first-seen order kept.
     */
    @Override
    public List<GraphLabelDef> listLabels(boolean nodeLabels) throws SQLException {
        try {
            Map<String, List<GraphPropertyDef>> declared = new LinkedHashMap<>();
            if (nodeLabels) {
                for (ConvenienceTypes.NodeTypeInfo type : client.showNodeTypes()) {
                    declared.put(type.getName(), toPropertyDefs(type.getProperties()));
                }
            } else {
                for (ConvenienceTypes.EdgeTypeInfo type : client.showEdgeTypes()) {
                    declared.put(type.getName(), toPropertyDefs(type.getProperties()));
                }
            }

            List<List<String>> labelRows = new ArrayList<>();
            List<ConvenienceTypes.LabelInfo> labelInfos =
                    nodeLabels ? client.showNodeLabels() : client.showEdgeLabels();
            for (ConvenienceTypes.LabelInfo info : labelInfos) {
                labelRows.add(info.getLabels());
            }

            return mergeLabels(labelRows, declared);
        } catch (RuntimeException e) {
            throw UltipaExceptionMapper.map(
                    "listing " + (nodeLabels ? "node" : "edge") + " labels", e);
        }
    }

    /**
     * Merges the labels in use with the declared schema, keeping first-seen order.
     *
     * <p>Separated from the SDK calls so that the merge itself is unit-testable: it takes and
     * returns only driver types.
     *
     * @param labelRows the label column of each {@code SHOW … LABELS} row; each entry may name
     *                  several labels, and may be {@code null}
     * @param declared  declared properties by label name, empty for a schema-less graph
     * @return one entry per distinct label, plus any declared label not currently in use
     */
    static List<GraphLabelDef> mergeLabels(List<List<String>> labelRows,
                                           Map<String, List<GraphPropertyDef>> declared) {
        Map<String, List<GraphPropertyDef>> merged = new LinkedHashMap<>();

        if (labelRows != null) {
            for (List<String> row : labelRows) {
                if (row == null) {
                    continue;
                }
                for (String label : row) {
                    if (label == null || label.isBlank()) {
                        continue;
                    }
                    merged.putIfAbsent(label, declared.getOrDefault(label, List.of()));
                }
            }
        }

        // A closed graph may declare a label that no element uses yet; it is still a table.
        for (Map.Entry<String, List<GraphPropertyDef>> entry : declared.entrySet()) {
            if (entry.getKey() != null && !entry.getKey().isBlank()) {
                merged.putIfAbsent(entry.getKey(), entry.getValue());
            }
        }

        List<GraphLabelDef> labels = new ArrayList<>(merged.size());
        merged.forEach((name, properties) -> labels.add(new GraphLabelDef(name, properties)));
        return labels;
    }

    private static List<GraphPropertyDef> toPropertyDefs(List<ConvenienceTypes.PropertyDef> source) {
        if (source == null) {
            return List.of();
        }
        List<GraphPropertyDef> properties = new ArrayList<>(source.size());
        for (ConvenienceTypes.PropertyDef property : source) {
            properties.add(new GraphPropertyDef(property.getName(), property.getType()));
        }
        return properties;
    }

    @Override
    public long beginTransaction(String graph, boolean readOnly, int timeoutSeconds)
            throws SQLException {
        if (graph == null || graph.isBlank()) {
            throw UltipaExceptionMapper.invalidTransactionState(
                    "A graph must be selected before a transaction can be started; "
                            + "name one in the JDBC URL or call Connection.setCatalog(String)");
        }
        try {
            Transaction transaction = client.beginTransaction(graph, readOnly, timeoutSeconds);
            return transaction.getId();
        } catch (RuntimeException e) {
            throw UltipaExceptionMapper.map("beginning a transaction", e);
        }
    }

    @Override
    public void commit(long transactionId) throws SQLException {
        try {
            client.commit(transactionId);
        } catch (RuntimeException e) {
            throw UltipaExceptionMapper.map("committing a transaction", e);
        }
    }

    @Override
    public void rollback(long transactionId) throws SQLException {
        try {
            client.rollback(transactionId);
        } catch (RuntimeException e) {
            throw UltipaExceptionMapper.map("rolling a transaction back", e);
        }
    }

    @Override
    public boolean isAlive() {
        if (closed) {
            return false;
        }
        try {
            client.ping();
            return true;
        } catch (RuntimeException e) {
            // isValid() is specified not to throw for an unreachable server.
            return false;
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            client.logout();
        } catch (RuntimeException e) {
            // A failed logout must not stop the transport from being released; the server
            // expires the session on its own.
        }
        closeQuietly(client);
    }

    private static void closeQuietly(GqldbClient client) {
        try {
            client.close();
        } catch (RuntimeException e) {
            // Nothing useful can be done while releasing resources.
        }
    }

    private static boolean isPresent(String text) {
        return text != null && !text.isBlank();
    }
}
