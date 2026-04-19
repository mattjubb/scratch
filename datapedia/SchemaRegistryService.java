package com.vasara.datapedia.service;

import com.vasara.datapedia.model.*;

import java.util.List;
import java.util.Optional;

/**
 * Service contract for the Vasara Datapedia schema registry.
 * <p>
 * Implementations back onto whatever persistence layer is in play
 * (ConfigMap, database, in-memory for tests, etc.).
 */
public interface SchemaRegistryService {

    /**
     * Returns the full hierarchical tree of groups → projects → schema summaries.
     *
     * @param filter optional case-insensitive substring filter across name, group, and project
     */
    GroupProjectTree getTree(String filter);

    /**
     * Lists all schemas, optionally filtered.
     *
     * @param group      optional group filter (exact match)
     * @param project    optional project filter (exact match)
     * @param schemaType optional schema type filter
     * @param filter     optional case-insensitive substring across name, group, project
     */
    List<SchemaSummary> listSchemas(String group, String project, SchemaType schemaType, String filter);

    /**
     * Retrieves a single schema by its unique registry ID, including all versions.
     */
    Optional<SchemaEntry> getSchemaById(long id);

    /**
     * Retrieves a specific version of a schema.
     */
    Optional<SchemaVersion> getSchemaVersion(long id, int version);

    /**
     * Retrieves the latest version of a schema.
     */
    Optional<SchemaVersion> getLatestSchemaVersion(long id);

    /**
     * Looks up a schema by its fully qualified name (namespace + name).
     */
    Optional<SchemaEntry> getSchemaByQualifiedName(String group, String project, String name);

    /**
     * Registers a new schema or appends a new version to an existing one.
     *
     * @return the full updated schema entry
     */
    SchemaEntry registerSchema(SchemaRegistrationRequest request);

    /**
     * Returns the dependency graph rooted at the given schema ID.
     * If depth is null, the full transitive closure is returned.
     *
     * @param rootId the schema to start traversal from
     * @param depth  maximum traversal depth (null for unlimited)
     */
    SchemaGraph getGraph(Long rootId, Integer depth);

    /**
     * Returns all schemas that reference the given schema ID (reverse dependencies).
     */
    List<SchemaSummary> getReferencedBy(long id);

    /**
     * Returns all distinct group names.
     */
    List<String> getGroups();

    /**
     * Returns all distinct project names within a group.
     */
    List<String> getProjects(String group);
}
