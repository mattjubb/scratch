package com.vasara.datapedia.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "Graph representation of schema relationships for visualization")
public class SchemaGraph {

    @Schema(description = "Schema summaries acting as graph nodes")
    private List<SchemaSummary> nodes;

    @Schema(description = "Directed reference edges between schemas")
    private List<SchemaReference> edges;

    public SchemaGraph() {
    }

    public SchemaGraph(List<SchemaSummary> nodes, List<SchemaReference> edges) {
        this.nodes = nodes;
        this.edges = edges;
    }

    public List<SchemaSummary> getNodes() {
        return nodes;
    }

    public void setNodes(List<SchemaSummary> nodes) {
        this.nodes = nodes;
    }

    public List<SchemaReference> getEdges() {
        return edges;
    }

    public void setEdges(List<SchemaReference> edges) {
        this.edges = edges;
    }
}
