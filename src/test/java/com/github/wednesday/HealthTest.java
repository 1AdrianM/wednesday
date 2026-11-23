package com.github.wednesday;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

@QuarkusTest
class HealthTest {

    @Test
    void healthEndpointReturns200() {
        given().when().get("/q/health").then().statusCode(200);
    }

    @Test
    void browserClientIsServedAtRoot() {
        given().when().get("/").then().statusCode(200)
                .body(containsString("<title>Wednesday</title>"))
                .body(containsString("/ws/voice"));
    }
}
