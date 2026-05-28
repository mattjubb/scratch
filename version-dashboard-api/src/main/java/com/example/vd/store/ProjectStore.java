package com.example.vd.store;

import com.example.vd.model.DepPin;
import com.example.vd.model.Project;
import com.example.vd.model.Stage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Singleton in-memory store for all project data.
 *
 * <p>Seed data is loaded from {@code projects.json} on the classpath at startup.
 * All mutation methods enforce the same promotion and rebase rules as the frontend
 * Alpine.js component so the two implementations stay in sync.
 *
 * <p>All public methods are thread-safe via a {@link ReentrantReadWriteLock}.
 */
@ApplicationScoped
public class ProjectStore {

    static final List<String> LANES      = List.of("snapshot", "candidate", "release");
    static final List<String> ITERATIONS = List.of("previous", "current", "next");
    public static final List<String> STAGE_KEYS;

    static {
        List<String> keys = new ArrayList<>();
        for (String lane : LANES) {
            for (String iter : ITERATIONS) {
                keys.add(lane + "-" + iter);
            }
        }
        STAGE_KEYS = List.copyOf(keys);
    }

    // -----------------------------------------------------------------------
    // State
    // -----------------------------------------------------------------------

    private final List<Project> projects = new ArrayList<>();
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    @PostConstruct
    void init() {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("projects.json")) {
            if (is == null) {
                throw new IllegalStateException("projects.json not found on classpath");
            }
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(is);
            for (JsonNode node : root.get("projects")) {
                projects.add(mapper.treeToValue(node, Project.class));
            }
            migrate(projects);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load seed data from projects.json", e);
        }
    }

    /**
     * Ensures every project has all 9 stage keys and every stage has all required fields.
     * Mirrors migrateProjects() in app.js.
     */
    private static void migrate(List<Project> projects) {
        for (Project p : projects) {
            if (p.imageTag == null) p.imageTag = "";
            for (String sk : STAGE_KEYS) {
                Stage stage = p.stages.computeIfAbsent(sk, k -> new Stage());
                if (stage.version == null) stage.version = "";
                if (stage.imageVersion == null) stage.imageVersion = "";
                if (stage.lastUpdatedBy == null) stage.lastUpdatedBy = "";
                if (stage.prs == null) stage.prs = new ArrayList<>();
                if (stage.deps == null) stage.deps = new LinkedHashMap<>();
                // normalise dep pins: string values from old data become DepPin objects
                stage.deps.replaceAll((depId, pin) -> {
                    if (pin == null) return new DepPin();
                    if (pin.version == null) pin.version = "";
                    if (pin.imageVersion == null) pin.imageVersion = "";
                    return pin;
                });
            }
        }
    }

    // -----------------------------------------------------------------------
    // Reads
    // -----------------------------------------------------------------------

    /** Returns a shallow copy of the project list (objects themselves are live). */
    public List<Project> findAll() {
        lock.readLock().lock();
        try {
            return new ArrayList<>(projects);
        } finally {
            lock.readLock().unlock();
        }
    }

    public Optional<Project> findById(String id) {
        lock.readLock().lock();
        try {
            return projects.stream().filter(p -> p.id.equals(id)).findFirst();
        } finally {
            lock.readLock().unlock();
        }
    }

    public boolean exists(String id) {
        return findById(id).isPresent();
    }

    // -----------------------------------------------------------------------
    // Project CRUD
    // -----------------------------------------------------------------------

    public Project add(Project project) {
        lock.writeLock().lock();
        try {
            ensureStages(project);
            projects.add(project);
            return project;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Replaces the mutable metadata fields of an existing project.
     * Stages, dependencies, artifacts, and lead-developers are handled
     * by their own dedicated methods.
     */
    public Project update(String id, String name, String description,
                          String githubRepo, String imageTag) {
        lock.writeLock().lock();
        try {
            Project p = require(id);
            if (name        != null) p.name        = name;
            if (description != null) p.description = description;
            if (githubRepo  != null) p.githubRepo  = githubRepo;
            if (imageTag    != null) p.imageTag     = imageTag;
            return p;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Deletes a project and removes it from every other project's dependency list
     * and dep-pin maps, exactly as deleteProject() does in app.js.
     */
    public void delete(String id) {
        lock.writeLock().lock();
        try {
            require(id);
            for (Project p : projects) {
                p.dependencies.remove(id);
                for (Stage stage : p.stages.values()) {
                    stage.deps.remove(id);
                }
            }
            projects.removeIf(p -> p.id.equals(id));
        } finally {
            lock.writeLock().unlock();
        }
    }

    // -----------------------------------------------------------------------
    // Lead developers & Artifacts (full-list replacement)
    // -----------------------------------------------------------------------

    public Project replaceLeadDevelopers(String projectId,
                                         List<com.example.vd.model.LeadDeveloper> devs) {
        lock.writeLock().lock();
        try {
            Project p = require(projectId);
            p.leadDevelopers = devs == null ? new ArrayList<>() : new ArrayList<>(devs);
            return p;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public Project replaceArtifacts(String projectId,
                                    List<com.example.vd.model.Artifact> artifacts) {
        lock.writeLock().lock();
        try {
            Project p = require(projectId);
            p.artifacts = artifacts == null ? new ArrayList<>() : new ArrayList<>(artifacts);
            return p;
        } finally {
            lock.writeLock().unlock();
        }
    }

    // -----------------------------------------------------------------------
    // Dependencies
    // -----------------------------------------------------------------------

    /**
     * Adds {@code depId} to {@code projectId}'s dependency list and seeds dep-pin
     * entries for all 9 stages, initialised from the dependency's current versions.
     * Mirrors addDependency() in app.js.
     */
    public Project addDependency(String projectId, String depId) {
        lock.writeLock().lock();
        try {
            Project p   = require(projectId);
            Project dep = require(depId);
            if (p.dependencies.contains(depId)) return p;
            p.dependencies.add(depId);
            for (String sk : STAGE_KEYS) {
                Stage pStage   = p.stages.get(sk);
                Stage depStage = dep.stages.get(sk);
                if (pStage == null) continue;
                if (pStage.deps == null) pStage.deps = new LinkedHashMap<>();
                pStage.deps.put(depId, new DepPin(
                        depStage != null ? depStage.version : "",
                        depStage != null ? depStage.imageVersion : ""
                ));
            }
            return p;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Removes {@code depId} from {@code projectId}'s dependency list and clears
     * all associated dep-pin entries. Mirrors removeDependency() in app.js.
     */
    public Project removeDependency(String projectId, String depId) {
        lock.writeLock().lock();
        try {
            Project p = require(projectId);
            p.dependencies.remove(depId);
            for (Stage stage : p.stages.values()) {
                if (stage.deps != null) stage.deps.remove(depId);
            }
            return p;
        } finally {
            lock.writeLock().unlock();
        }
    }

    // -----------------------------------------------------------------------
    // Stages
    // -----------------------------------------------------------------------

    public Stage getStage(String projectId, String stageKey) {
        lock.readLock().lock();
        try {
            return require(projectId).stages.get(stageKey);
        } finally {
            lock.readLock().unlock();
        }
    }

    public Stage updateStage(String projectId, String stageKey,
                             String version, String imageVersion,
                             Integer testsPassed, Integer testsTotal,
                             String lastUpdatedBy) {
        lock.writeLock().lock();
        try {
            Stage stage = requireStage(projectId, stageKey);
            if (version      != null) stage.version      = version;
            if (imageVersion != null) stage.imageVersion = imageVersion;
            if (testsPassed  != null) stage.testsPassed  = testsPassed;
            if (testsTotal   != null) stage.testsTotal   = testsTotal;
            touch(stage, lastUpdatedBy);
            return stage;
        } finally {
            lock.writeLock().unlock();
        }
    }

    // -----------------------------------------------------------------------
    // Promote
    // -----------------------------------------------------------------------

    /**
     * Promotes a stage forward in the pipeline and returns every stage that changed.
     *
     * <ul>
     *   <li>{@code *-next} → {@code *-current}: old current shifts to previous; next becomes
     *       current; dep pins on the new current are rebased.</li>
     *   <li>{@code *-current} (non-release) → next lane's {@code *-next}: copies the stage and
     *       rebases its dep pins.</li>
     *   <li>{@code release-current} and {@code *-previous} cannot be promoted — caller must
     *       validate before calling.</li>
     * </ul>
     *
     * <p>Mirrors promote() in app.js.
     */
    public Map<String, Stage> promote(String projectId, String stageKey, String actor) {
        lock.writeLock().lock();
        try {
            Project project = require(projectId);
            String[] parts  = stageKey.split("-", 2);
            String lane     = parts[0];
            String iter     = parts[1];

            Map<String, Stage> changed = new LinkedHashMap<>();

            if ("next".equals(iter)) {
                // next → current; current → previous
                String prevKey = lane + "-previous";
                String curKey  = lane + "-current";
                String nxtKey  = lane + "-next";

                project.stages.put(prevKey, project.stages.get(curKey).copy());
                project.stages.put(curKey,  project.stages.get(nxtKey).copy());

                rebaseStageInternal(project, curKey);
                touch(project.stages.get(curKey), actor);

                changed.put(prevKey, project.stages.get(prevKey));
                changed.put(curKey,  project.stages.get(curKey));

            } else if ("current".equals(iter)) {
                int laneIdx  = LANES.indexOf(lane);
                String nextLane    = LANES.get(laneIdx + 1); // caller validates laneIdx < 2
                String targetKey   = nextLane + "-next";

                project.stages.put(targetKey, project.stages.get(stageKey).copy());
                rebaseStageInternal(project, targetKey);
                touch(project.stages.get(targetKey), actor);

                changed.put(targetKey, project.stages.get(targetKey));
            }

            return changed;
        } finally {
            lock.writeLock().unlock();
        }
    }

    // -----------------------------------------------------------------------
    // Rebase
    // -----------------------------------------------------------------------

    /**
     * Rebases all dep pins at {@code stageKey} from the live versions of each
     * dependency project at the same stage. Mirrors rebase() in app.js.
     */
    public Stage rebaseStage(String projectId, String stageKey, String actor) {
        lock.writeLock().lock();
        try {
            Project project = require(projectId);
            rebaseStageInternal(project, stageKey);
            Stage stage = project.stages.get(stageKey);
            touch(stage, actor);
            return stage;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Rebases dep pins for a single dependency across ALL stages.
     * Mirrors rebaseDep() in app.js.
     */
    public Project rebaseDep(String projectId, String depId, String actor) {
        lock.writeLock().lock();
        try {
            Project project = require(projectId);
            if (!project.dependencies.contains(depId)) {
                throw new IllegalArgumentException(depId + " is not a dependency of " + projectId);
            }
            for (String sk : STAGE_KEYS) {
                rebaseCellInternal(project, sk, depId);
            }
            return project;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Rebases a single dep-pin cell. Mirrors rebaseCell() in app.js.
     */
    public Stage rebaseCell(String projectId, String stageKey, String depId) {
        lock.writeLock().lock();
        try {
            Project project = require(projectId);
            rebaseCellInternal(project, stageKey, depId);
            return project.stages.get(stageKey);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Rebases ALL dep pins across ALL stages.  Mirrors rebaseAll() in app.js.
     */
    public Project rebaseAll(String projectId) {
        lock.writeLock().lock();
        try {
            Project project = require(projectId);
            for (String sk : STAGE_KEYS) {
                rebaseStageInternal(project, sk);
            }
            return project;
        } finally {
            lock.writeLock().unlock();
        }
    }

    // -----------------------------------------------------------------------
    // Dep pins
    // -----------------------------------------------------------------------

    public Stage updateDepPin(String projectId, String stageKey, String depId,
                              String version, String imageVersion) {
        lock.writeLock().lock();
        try {
            Stage stage = requireStage(projectId, stageKey);
            DepPin pin  = stage.deps.computeIfAbsent(depId, k -> new DepPin());
            if (version      != null) pin.version      = version;
            if (imageVersion != null) pin.imageVersion = imageVersion;
            return stage;
        } finally {
            lock.writeLock().unlock();
        }
    }

    // -----------------------------------------------------------------------
    // PRs
    // -----------------------------------------------------------------------

    public Stage addPr(String projectId, String stageKey, com.example.vd.model.PullRequest pr) {
        lock.writeLock().lock();
        try {
            Stage stage = requireStage(projectId, stageKey);
            // reject duplicate PR numbers
            boolean dup = stage.prs.stream().anyMatch(p -> p.number == pr.number);
            if (dup) throw new IllegalArgumentException(
                    "PR #" + pr.number + " already exists at stage " + stageKey);
            stage.prs.add(pr);
            return stage;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public Stage removePr(String projectId, String stageKey, int prNumber) {
        lock.writeLock().lock();
        try {
            Stage stage = requireStage(projectId, stageKey);
            stage.prs.removeIf(p -> p.number == prNumber);
            return stage;
        } finally {
            lock.writeLock().unlock();
        }
    }

    // -----------------------------------------------------------------------
    // Drift
    // -----------------------------------------------------------------------

    /**
     * Returns drift information for every declared dependency at the given stage.
     * Mirrors depDriftFields() in app.js.
     */
    public List<com.example.vd.dto.DriftInfo> drift(String projectId, String stageKey) {
        lock.readLock().lock();
        try {
            Project project = require(projectId);
            Stage   stage   = project.stages.get(stageKey);
            List<com.example.vd.dto.DriftInfo> result = new ArrayList<>();
            if (stage == null) return result;

            for (String depId : project.dependencies) {
                Optional<Project> depOpt = projects.stream()
                        .filter(p -> p.id.equals(depId)).findFirst();
                if (depOpt.isEmpty()) continue;

                Stage depStage = depOpt.get().stages.get(stageKey);
                DepPin pinned  = stage.deps.getOrDefault(depId, new DepPin());

                com.example.vd.dto.DriftInfo info = new com.example.vd.dto.DriftInfo();
                info.depId            = depId;
                info.pinnedVersion    = pinned.version;
                info.pinnedImageVersion = pinned.imageVersion;
                info.actualVersion    = depStage != null ? depStage.version : "";
                info.actualImageVersion = depStage != null ? depStage.imageVersion : "";
                info.versionDrifted   = !info.pinnedVersion.equals(info.actualVersion);
                info.imageDrifted     = !info.pinnedImageVersion.equals(info.actualImageVersion);
                result.add(info);
            }
            return result;
        } finally {
            lock.readLock().unlock();
        }
    }

    // -----------------------------------------------------------------------
    // Reset
    // -----------------------------------------------------------------------

    /** Discards all in-memory state and re-seeds from the classpath JSON. */
    public void reset() {
        lock.writeLock().lock();
        try {
            projects.clear();
            init();
        } finally {
            lock.writeLock().unlock();
        }
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    private Project require(String id) {
        return projects.stream()
                .filter(p -> p.id.equals(id))
                .findFirst()
                .orElseThrow(() -> new ProjectNotFoundException(id));
    }

    private Stage requireStage(String projectId, String stageKey) {
        Project project = require(projectId);
        Stage stage = project.stages.get(stageKey);
        if (stage == null) {
            throw new StageNotFoundException(projectId, stageKey);
        }
        return stage;
    }

    /** Ensures every stage key is present in the project's stages map. */
    private static void ensureStages(Project project) {
        for (String sk : STAGE_KEYS) {
            project.stages.computeIfAbsent(sk, k -> new Stage());
        }
    }

    private void rebaseStageInternal(Project project, String stageKey) {
        for (String depId : project.dependencies) {
            rebaseCellInternal(project, stageKey, depId);
        }
    }

    private void rebaseCellInternal(Project project, String stageKey, String depId) {
        Stage stage = project.stages.get(stageKey);
        if (stage == null) return;
        if (stage.deps == null) stage.deps = new LinkedHashMap<>();
        Project dep = projects.stream().filter(p -> p.id.equals(depId)).findFirst().orElse(null);
        if (dep == null) return;
        Stage depStage = dep.stages.get(stageKey);
        if (depStage == null) return;
        stage.deps.put(depId, new DepPin(
                depStage.version      != null ? depStage.version : "",
                depStage.imageVersion != null ? depStage.imageVersion : ""
        ));
    }

    private static void touch(Stage stage, String actor) {
        stage.lastUpdated   = Instant.now().toString();
        stage.lastUpdatedBy = actor != null ? actor : "";
    }

    // -----------------------------------------------------------------------
    // Typed exceptions (translated to HTTP status codes by ExceptionMapper)
    // -----------------------------------------------------------------------

    public static class ProjectNotFoundException extends RuntimeException {
        public final String projectId;
        public ProjectNotFoundException(String id) {
            super("No project with id '" + id + "'");
            this.projectId = id;
        }
    }

    public static class StageNotFoundException extends RuntimeException {
        public StageNotFoundException(String projectId, String stageKey) {
            super("Stage '" + stageKey + "' not found on project '" + projectId + "'");
        }
    }

    public static class ConflictException extends RuntimeException {
        public ConflictException(String msg) { super(msg); }
    }
}
