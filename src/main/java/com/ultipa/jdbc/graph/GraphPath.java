package com.ultipa.jdbc.graph;

import java.util.List;
import java.util.Objects;

/**
 * An immutable graph path: the alternating node and edge sequence GQLDB returns for a path value.
 *
 * <p>The node and edge lists are kept exactly as the server sent them rather than being
 * interleaved or reduced to a string, so no path information is lost.
 */
public final class GraphPath {

    private final List<GraphNode> nodes;
    private final List<GraphEdge> edges;

    /**
     * Creates a path.
     *
     * @param nodes the path's nodes in traversal order; never {@code null}
     * @param edges the path's edges in traversal order; never {@code null}
     */
    public GraphPath(List<GraphNode> nodes, List<GraphEdge> edges) {
        this.nodes = nodes == null ? List.of() : List.copyOf(nodes);
        this.edges = edges == null ? List.of() : List.copyOf(edges);
    }

    /** Returns the path's nodes in traversal order. */
    public List<GraphNode> getNodes() {
        return nodes;
    }

    /** Returns the path's edges in traversal order. */
    public List<GraphEdge> getEdges() {
        return edges;
    }

    /** Returns the first node of the path, or {@code null} if the path is empty. */
    public GraphNode getStartNode() {
        return nodes.isEmpty() ? null : nodes.get(0);
    }

    /** Returns the last node of the path, or {@code null} if the path is empty. */
    public GraphNode getEndNode() {
        return nodes.isEmpty() ? null : nodes.get(nodes.size() - 1);
    }

    /** Returns the path length, defined as the number of edges. */
    public int length() {
        return edges.size();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof GraphPath other)) {
            return false;
        }
        return nodes.equals(other.nodes) && edges.equals(other.edges);
    }

    @Override
    public int hashCode() {
        return Objects.hash(nodes, edges);
    }

    @Override
    public String toString() {
        return "GraphPath{length=" + length() + ", nodes=" + nodes + ", edges=" + edges + '}';
    }
}
