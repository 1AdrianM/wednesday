package com.github.wednesday;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;

@QuarkusTest
class HealthTest {

    @Test
    void healthEndpointReturns200() {
        given().when().get("/q/health").then().statusCode(200);
    }
}
