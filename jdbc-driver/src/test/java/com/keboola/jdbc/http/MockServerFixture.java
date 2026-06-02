package com.keboola.jdbc.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;

/**
 * Small helpers for building MockWebServer responses in HTTP client unit tests.
 *
 * <p>Each test class owns its own {@link okhttp3.mockwebserver.MockWebServer} instance
 * (started in {@code @BeforeEach}, closed in {@code @AfterEach}). These helpers just
 * remove the boilerplate of serializing JSON bodies and setting headers.
 */
public final class MockServerFixture {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MockServerFixture() {
        // utility class
    }

    /**
     * Builds an HTTP response with the given status code and a JSON body.
     *
     * @param code HTTP status code
     * @param body any object Jackson can serialize (Map, List, String, etc.); if null, body is empty
     * @return a MockResponse ready to be enqueued
     */
    public static MockResponse jsonResponse(int code, Object body) {
        MockResponse response = new MockResponse()
                .setResponseCode(code)
                .setHeader("Content-Type", "application/json");
        if (body != null) {
            try {
                response.setBody(MAPPER.writeValueAsString(body));
            } catch (Exception e) {
                throw new IllegalArgumentException("Failed to serialize mock response body", e);
            }
        }
        return response;
    }

    /**
     * Builds an HTTP response with the given status code and a raw string body.
     *
     * @param code HTTP status code
     * @param body raw body (may be malformed JSON, used to test parser robustness)
     * @return a MockResponse ready to be enqueued
     */
    public static MockResponse rawResponse(int code, String body) {
        return new MockResponse()
                .setResponseCode(code)
                .setHeader("Content-Type", "application/json")
                .setBody(body);
    }
}
