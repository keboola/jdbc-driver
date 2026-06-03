package com.keboola.jdbc.http;

import com.keboola.jdbc.exception.KeboolaJdbcException;
import com.keboola.jdbc.http.model.JobStatus;
import com.keboola.jdbc.http.model.QueryJob;
import com.keboola.jdbc.http.model.QueryResult;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.keboola.jdbc.http.MockServerFixture.jsonResponse;
import static com.keboola.jdbc.http.MockServerFixture.rawResponse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryServiceClientTest {

    private MockWebServer server;
    private QueryServiceClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        // Constructor strips trailing slash itself; pass the URL as-is.
        client = new QueryServiceClient(server.url("").toString(), "test-token");
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    private static Map<String, Object> jobResponse(String id, String status) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("queryJobId", id);
        body.put("status", status);
        body.put("statements", Collections.emptyList());
        return body;
    }

    // ---------------------------------------------------------------------
    // submitJob
    // ---------------------------------------------------------------------

    @Test
    void submitJob_postsStatementsAndReturnsQueryJob() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("queryJobId", "job-1");
        body.put("sessionId", "sess-1");
        server.enqueue(jsonResponse(200, body));

        QueryJob job = client.submitJob(10L, 20L, Arrays.asList("SELECT 1", "SELECT 2"));

        assertEquals("job-1", job.getQueryJobId());
        assertEquals("sess-1", job.getSessionId());

        RecordedRequest req = server.takeRequest();
        assertEquals("POST", req.getMethod());
        assertEquals("/api/v1/branches/10/workspaces/20/queries", req.getPath());
        assertEquals("test-token", req.getHeader("X-StorageAPI-Token"));
        assertTrue(req.getHeader("Content-Type").startsWith("application/json"));
        String reqBody = req.getBody().readUtf8();
        assertTrue(reqBody.contains("\"statements\""), reqBody);
        assertTrue(reqBody.contains("SELECT 1"), reqBody);
        assertTrue(reqBody.contains("SELECT 2"), reqBody);
        assertFalse(reqBody.contains("sessionId"), "No sessionId expected when not provided: " + reqBody);
    }

    @Test
    void submitJob_withSessionId_includesItInBody() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("queryJobId", "job-2");
        server.enqueue(jsonResponse(200, body));

        client.submitJob(1L, 2L, Collections.singletonList("USE SCHEMA s"), "sess-xyz");

        String reqBody = server.takeRequest().getBody().readUtf8();
        assertTrue(reqBody.contains("\"sessionId\":\"sess-xyz\""), reqBody);
    }

    @Test
    void submitJob_emptySessionId_doesNotIncludeFieldInBody() throws Exception {
        server.enqueue(jsonResponse(200, Collections.singletonMap("queryJobId", "job-3")));

        client.submitJob(1L, 2L, Collections.singletonList("SELECT 1"), "");

        String reqBody = server.takeRequest().getBody().readUtf8();
        assertFalse(reqBody.contains("sessionId"), reqBody);
    }

    @Test
    void submitJob_http401_throwsAuthenticationFailed() {
        server.enqueue(jsonResponse(401, Collections.singletonMap("error", "no")));

        KeboolaJdbcException ex = assertThrows(KeboolaJdbcException.class,
                () -> client.submitJob(1L, 2L, Collections.singletonList("SELECT 1")));
        assertEquals("28000", ex.getSQLState());
    }

    @Test
    void submitJob_http400_throwsQueryFailed() {
        server.enqueue(jsonResponse(400, Collections.singletonMap("error", "bad sql")));

        KeboolaJdbcException ex = assertThrows(KeboolaJdbcException.class,
                () -> client.submitJob(1L, 2L, Collections.singletonList("SELECT 1")));
        // queryFailed uses HY000
        assertEquals("HY000", ex.getSQLState());
        assertEquals(1, server.getRequestCount(), "400 must not retry");
    }

    // ---------------------------------------------------------------------
    // getJobStatus
    // ---------------------------------------------------------------------

    @Test
    void getJobStatus_returnsStatusFromApi() throws Exception {
        server.enqueue(jsonResponse(200, jobResponse("j1", "processing")));

        JobStatus status = client.getJobStatus("j1");

        assertEquals("processing", status.getStatus());
        RecordedRequest req = server.takeRequest();
        assertEquals("GET", req.getMethod());
        assertEquals("/api/v1/queries/j1", req.getPath());
    }

    @Test
    void getJobStatus_completedStatus_isTerminal() throws Exception {
        server.enqueue(jsonResponse(200, jobResponse("j1", "completed")));

        JobStatus status = client.getJobStatus("j1");

        assertTrue(status.isTerminal());
        assertTrue(status.isSuccessful());
    }

    @Test
    void getJobStatus_failedStatus_isTerminalButNotSuccessful() throws Exception {
        server.enqueue(jsonResponse(200, jobResponse("j1", "failed")));

        JobStatus status = client.getJobStatus("j1");

        assertTrue(status.isTerminal());
        assertFalse(status.isSuccessful());
    }

    @Test
    void getJobStatus_canceledStatus_isTerminal() throws Exception {
        server.enqueue(jsonResponse(200, jobResponse("j1", "canceled")));

        assertTrue(client.getJobStatus("j1").isTerminal());
    }

    // ---------------------------------------------------------------------
    // waitForCompletion
    // ---------------------------------------------------------------------

    @Test
    void waitForCompletion_alreadyCompleted_returnsImmediately() throws Exception {
        server.enqueue(jsonResponse(200, jobResponse("j1", "completed")));

        JobStatus status = client.waitForCompletion("j1");

        assertEquals("completed", status.getStatus());
        assertEquals(1, server.getRequestCount());
    }

    @Test
    void waitForCompletion_polls_untilTerminal() throws Exception {
        server.enqueue(jsonResponse(200, jobResponse("j1", "created")));
        server.enqueue(jsonResponse(200, jobResponse("j1", "processing")));
        server.enqueue(jsonResponse(200, jobResponse("j1", "completed")));

        JobStatus status = client.waitForCompletion("j1");

        assertEquals("completed", status.getStatus());
        assertEquals(3, server.getRequestCount());
    }

    @Test
    void waitForCompletion_failedStatus_returnsFailedJob() throws Exception {
        server.enqueue(jsonResponse(200, jobResponse("j1", "failed")));

        JobStatus status = client.waitForCompletion("j1");

        assertEquals("failed", status.getStatus());
        assertFalse(status.isSuccessful());
    }

    // ---------------------------------------------------------------------
    // fetchResults
    // ---------------------------------------------------------------------

    @Test
    void fetchResults_buildsCorrectUrlAndReturnsParsedResult() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("columns", Collections.emptyList());
        body.put("data", Collections.emptyList());
        body.put("numberOfRows", 0);
        server.enqueue(jsonResponse(200, body));

        QueryResult result = client.fetchResults("job-1", "stmt-1", 0, 1000);

        assertNotNull(result);
        RecordedRequest req = server.takeRequest();
        assertEquals("GET", req.getMethod());
        assertEquals("/api/v1/queries/job-1/stmt-1/results?offset=0&pageSize=1000", req.getPath());
    }

    @Test
    void fetchResults_paginationOffsetAndPageSizeInUrl() throws Exception {
        server.enqueue(jsonResponse(200, Collections.singletonMap("numberOfRows", 0)));

        client.fetchResults("job-1", "stmt-1", 200, 100);

        RecordedRequest req = server.takeRequest();
        assertTrue(req.getPath().contains("offset=200"), req.getPath());
        assertTrue(req.getPath().contains("pageSize=100"), req.getPath());
    }

    @Test
    @SuppressWarnings("deprecation")
    void fetchResults_deprecatedOverload_usesStatementIdInUrl() throws Exception {
        server.enqueue(jsonResponse(200, Collections.singletonMap("numberOfRows", 0)));

        client.fetchResults("stmt-only", 0, 50);

        RecordedRequest req = server.takeRequest();
        // Deprecated overload uses statementId in the queryJobId slot
        assertTrue(req.getPath().startsWith("/api/v1/queries/stmt-only/results"), req.getPath());
    }

    // ---------------------------------------------------------------------
    // cancelJob
    // ---------------------------------------------------------------------

    @Test
    void cancelJob_postsCancelRequest() throws Exception {
        server.enqueue(jsonResponse(200, Collections.emptyMap()));

        client.cancelJob("job-1");

        RecordedRequest req = server.takeRequest();
        assertEquals("POST", req.getMethod());
        assertEquals("/api/v1/queries/job-1/cancel", req.getPath());
        String body = req.getBody().readUtf8();
        assertTrue(body.contains("reason"), body);
    }

    @Test
    void cancelJob_apiError_propagatesException() {
        server.enqueue(jsonResponse(500, Collections.singletonMap("error", "fail")));
        server.enqueue(jsonResponse(500, Collections.singletonMap("error", "fail")));
        server.enqueue(jsonResponse(500, Collections.singletonMap("error", "fail")));

        assertThrows(KeboolaJdbcException.class, () -> client.cancelJob("job-1"));
    }

    // ---------------------------------------------------------------------
    // Retry behavior
    // ---------------------------------------------------------------------

    @Test
    void executeGet_retriesOn500AndSucceeds() throws Exception {
        server.enqueue(jsonResponse(500, Collections.singletonMap("error", "transient")));
        server.enqueue(jsonResponse(200, jobResponse("j1", "completed")));

        JobStatus status = client.getJobStatus("j1");

        assertEquals("completed", status.getStatus());
        assertEquals(2, server.getRequestCount());
    }

    @Test
    void executeGet_retriesOn429() throws Exception {
        server.enqueue(rawResponse(429, "Too Many Requests"));
        server.enqueue(jsonResponse(200, jobResponse("j1", "completed")));

        client.getJobStatus("j1");

        assertEquals(2, server.getRequestCount());
    }

    @Test
    void executeGet_exhaustsRetries_throwsConnectionFailed() {
        server.enqueue(jsonResponse(500, Collections.singletonMap("error", "down")));
        server.enqueue(jsonResponse(500, Collections.singletonMap("error", "down")));
        server.enqueue(jsonResponse(500, Collections.singletonMap("error", "down")));

        KeboolaJdbcException ex = assertThrows(KeboolaJdbcException.class,
                () -> client.getJobStatus("j1"));
        assertEquals("08001", ex.getSQLState());
        assertEquals(3, server.getRequestCount());
    }

    @Test
    void executeGet_doesNotRetryOn403() {
        server.enqueue(jsonResponse(403, Collections.singletonMap("error", "no")));

        KeboolaJdbcException ex = assertThrows(KeboolaJdbcException.class,
                () -> client.getJobStatus("j1"));
        assertEquals("28000", ex.getSQLState());
        assertEquals(1, server.getRequestCount());
    }

    // ---------------------------------------------------------------------
    // Constructor
    // ---------------------------------------------------------------------

    @Test
    void constructor_stripsTrailingSlashFromBaseUrl() throws Exception {
        // The constructor normalizes trailing slash. Verify by issuing a request that
        // would otherwise build a URL containing a double slash.
        QueryServiceClient withSlash = new QueryServiceClient(
                server.url("").toString(), "tok"); // already ends with /
        server.enqueue(jsonResponse(200, jobResponse("j1", "completed")));

        withSlash.getJobStatus("j1");

        RecordedRequest req = server.takeRequest();
        // Path on MockWebServer must start with exactly one /
        assertTrue(req.getPath().startsWith("/api/v1"), req.getPath());
        assertFalse(req.getPath().startsWith("//"), req.getPath());
    }

    @Test
    void getJobStatus_malformedJson_throwsConnectionFailed() {
        server.enqueue(rawResponse(200, "{garbage"));

        KeboolaJdbcException ex = assertThrows(KeboolaJdbcException.class,
                () -> client.getJobStatus("j1"));
        assertEquals("08001", ex.getSQLState());
    }
}
