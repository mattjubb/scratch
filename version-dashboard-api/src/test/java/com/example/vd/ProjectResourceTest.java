package com.example.vd;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ProjectResourceTest {

    // -----------------------------------------------------------------------
    // Seed data
    // -----------------------------------------------------------------------

    @Test @Order(1)
    void listProjects_returnsSeedData() {
        given()
            .when().get("/api/projects")
            .then()
                .statusCode(200)
                .body("$.size()", equalTo(3))
                .body("id", hasItems("core.datapedia", "rates.pricer", "data.analytics"));
    }

    @Test @Order(2)
    void getProject_knownId_returnsProject() {
        given()
            .when().get("/api/projects/core.datapedia")
            .then()
                .statusCode(200)
                .body("id",   equalTo("core.datapedia"))
                .body("name", equalTo("Core Datapedia"))
                .body("stages.'release-current'.version", equalTo("3.3.0"));
    }

    @Test @Order(3)
    void getProject_unknownId_returns404() {
        given()
            .when().get("/api/projects/no.such.project")
            .then()
                .statusCode(404)
                .body("error", containsString("no.such.project"));
    }

    @Test @Order(4)
    void listProjects_filterByQuery() {
        given()
            .queryParam("q", "pricer")
            .when().get("/api/projects")
            .then()
                .statusCode(200)
                .body("$.size()", equalTo(1))
                .body("[0].id", equalTo("rates.pricer"));
    }

    // -----------------------------------------------------------------------
    // Create & delete
    // -----------------------------------------------------------------------

    @Test @Order(10)
    void createProject_validRequest_returns201() {
        given()
            .contentType(ContentType.JSON)
            .body("""
                {
                  "id": "test.service",
                  "name": "Test Service",
                  "description": "Created by test"
                }
                """)
            .when().post("/api/projects")
            .then()
                .statusCode(201)
                .body("id", equalTo("test.service"))
                .header("Location", containsString("test.service"));
    }

    @Test @Order(11)
    void createProject_duplicateId_returns409() {
        given()
            .contentType(ContentType.JSON)
            .body("""
                { "id": "core.datapedia", "name": "Dup" }
                """)
            .when().post("/api/projects")
            .then()
                .statusCode(409);
    }

    @Test @Order(12)
    void createProject_missingId_returns400() {
        given()
            .contentType(ContentType.JSON)
            .body("""
                { "name": "No Id" }
                """)
            .when().post("/api/projects")
            .then()
                .statusCode(400);
    }

    @Test @Order(13)
    void deleteProject_removesFromList() {
        given().when().delete("/api/projects/test.service")
               .then().statusCode(204);
        given().when().get("/api/projects/test.service")
               .then().statusCode(404);
    }

    // -----------------------------------------------------------------------
    // Stages
    // -----------------------------------------------------------------------

    @Test @Order(20)
    void getAllStages_returns9Keys() {
        given()
            .when().get("/api/projects/rates.pricer/stages")
            .then()
                .statusCode(200)
                .body("keySet().size()", equalTo(9))
                .body("containsKey('snapshot-previous')", is(true))
                .body("containsKey('release-current')",  is(true));
    }

    @Test @Order(21)
    void getStage_knownStage_returnsData() {
        given()
            .when().get("/api/projects/rates.pricer/stages/release-current")
            .then()
                .statusCode(200)
                .body("version", equalTo("2.6.0"))
                .body("testsPassed", equalTo(246))
                .body("testsTotal",  equalTo(250));
    }

    @Test @Order(22)
    void patchStage_updatesFields() {
        given()
            .contentType(ContentType.JSON)
            .body("""
                {
                  "version":      "2.7.0-SNAPSHOT",
                  "testsPassed":  200,
                  "testsTotal":   250,
                  "lastUpdatedBy":"test-runner"
                }
                """)
            .when().patch("/api/projects/rates.pricer/stages/snapshot-current")
            .then()
                .statusCode(200)
                .body("version",      equalTo("2.7.0-SNAPSHOT"))
                .body("testsPassed",  equalTo(200))
                .body("lastUpdatedBy", equalTo("test-runner"))
                .body("lastUpdated",  notNullValue());
    }

    @Test @Order(23)
    void patchStage_invalidKey_returns400() {
        given()
            .contentType(ContentType.JSON)
            .body("{}")
            .when().patch("/api/projects/rates.pricer/stages/bad-stage")
            .then()
                .statusCode(400);
    }

    // -----------------------------------------------------------------------
    // Promote
    // -----------------------------------------------------------------------

    @Test @Order(30)
    void promote_snapshotNext_shiftsSlots() {
        // Record current snapshot-current before promote
        String beforeCurrent = given()
            .when().get("/api/projects/data.analytics/stages/snapshot-current")
            .then().statusCode(200)
            .extract().path("version");

        given()
            .queryParam("actor", "ci-bot")
            .when().post("/api/projects/data.analytics/stages/snapshot-next/promote")
            .then()
                .statusCode(200)
                .body("updatedStages.containsKey('snapshot-previous')", is(true))
                .body("updatedStages.containsKey('snapshot-current')",  is(true))
                // The old current is now in previous
                .body("updatedStages.'snapshot-previous'.version", equalTo(beforeCurrent));
    }

    @Test @Order(31)
    void promote_previousStage_returns400() {
        given()
            .when().post("/api/projects/data.analytics/stages/snapshot-previous/promote")
            .then()
                .statusCode(400);
    }

    @Test @Order(32)
    void promote_releaseCurrent_returns400() {
        given()
            .when().post("/api/projects/data.analytics/stages/release-current/promote")
            .then()
                .statusCode(400);
    }

    // -----------------------------------------------------------------------
    // Rebase
    // -----------------------------------------------------------------------

    @Test @Order(40)
    void rebaseStage_updatesDeps() {
        given()
            .queryParam("actor", "ci-bot")
            .when().post("/api/projects/core.datapedia/stages/snapshot-current/rebase")
            .then()
                .statusCode(200)
                .body("lastUpdated", notNullValue());
    }

    @Test @Order(41)
    void rebasePreviousStage_returns400() {
        given()
            .when().post("/api/projects/core.datapedia/stages/snapshot-previous/rebase")
            .then()
                .statusCode(400);
    }

    // -----------------------------------------------------------------------
    // Drift
    // -----------------------------------------------------------------------

    @Test @Order(50)
    void drift_returnsRecordPerDep() {
        given()
            .when().get("/api/projects/core.datapedia/stages/snapshot-current/drift")
            .then()
                .statusCode(200)
                .body("$.size()", equalTo(2))  // core.datapedia depends on 2 projects
                .body("[0].depId", notNullValue());
    }

    // -----------------------------------------------------------------------
    // PRs
    // -----------------------------------------------------------------------

    @Test @Order(60)
    void addAndRemovePr() {
        given()
            .contentType(ContentType.JSON)
            .body("""
                {
                  "number": 9999,
                  "title":  "Test PR",
                  "author": "test-user",
                  "url":    "https://github.com/example/pr/9999"
                }
                """)
            .when().post("/api/projects/data.analytics/stages/snapshot-current/prs")
            .then()
                .statusCode(200)
                .body("prs.find { it.number == 9999 }.title", equalTo("Test PR"));

        given()
            .when().delete("/api/projects/data.analytics/stages/snapshot-current/prs/9999")
            .then()
                .statusCode(200)
                .body("prs.find { it.number == 9999 }", nullValue());
    }

    // -----------------------------------------------------------------------
    // Dependencies
    // -----------------------------------------------------------------------

    @Test @Order(70)
    void addAndRemoveDependency() {
        // data.analytics has no deps — use it as a new dep on rates.pricer (already has it)
        // Instead create a temporary project and wire it up
        given()
            .contentType(ContentType.JSON)
            .body("""
                { "id": "temp.svc" }
                """)
            .when().post("/api/projects")
            .then().statusCode(201);

        given()
            .contentType(ContentType.JSON)
            .body("""
                { "depId": "data.analytics" }
                """)
            .when().post("/api/projects/temp.svc/dependencies")
            .then()
                .statusCode(200)
                .body("dependencies", hasItem("data.analytics"))
                .body("stages.'snapshot-current'.deps.containsKey('data.analytics')", is(true));

        given()
            .when().delete("/api/projects/temp.svc/dependencies/data.analytics")
            .then()
                .statusCode(200)
                .body("dependencies", not(hasItem("data.analytics")));

        given().when().delete("/api/projects/temp.svc").then().statusCode(204);
    }
}
