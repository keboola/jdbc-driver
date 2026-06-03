package com.keboola.jdbc.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.keboola.jdbc.exception.KeboolaJdbcException;
import com.keboola.jdbc.http.model.Branch;
import com.keboola.jdbc.http.model.Bucket;
import com.keboola.jdbc.http.model.TokenInfo;
import com.keboola.jdbc.http.model.Workspace;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.keboola.jdbc.http.MockServerFixture.jsonResponse;
import static com.keboola.jdbc.http.MockServerFixture.rawResponse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageApiClientTest {

    private MockWebServer server;
    private StorageApiClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        // Pass the MockWebServer base URL as the "host" — StorageApiClient's
        // storageUrl() accepts either a bare host or a full URL prefix.
        String baseUrl = server.url("").toString().replaceAll("/$", "");
        client = new StorageApiClient(baseUrl, "test-token");
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    // ---------------------------------------------------------------------
    // verifyToken
    // ---------------------------------------------------------------------

    @Test
    void verifyToken_happyPath_returnsTokenInfo() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("id", "12345");
        body.put("description", "test token");
        server.enqueue(jsonResponse(200, body));

        TokenInfo info = client.verifyToken();

        assertNotNull(info);
        RecordedRequest req = server.takeRequest();
        assertEquals("GET", req.getMethod());
        assertEquals("/v2/storage/tokens/verify", req.getPath());
        assertEquals("test-token", req.getHeader("X-StorageApi-Token"));
    }

    @Test
    void verifyToken_http401_throwsAuthenticationFailed() {
        server.enqueue(jsonResponse(401, Collections.singletonMap("error", "Invalid token")));

        KeboolaJdbcException ex = assertThrows(KeboolaJdbcException.class, client::verifyToken);
        assertEquals("28000", ex.getSQLState(), "Auth failures must use SQLSTATE 28000");
    }

    @Test
    void verifyToken_http403_throwsAuthenticationFailed() {
        server.enqueue(jsonResponse(403, Collections.singletonMap("error", "Forbidden")));

        KeboolaJdbcException ex = assertThrows(KeboolaJdbcException.class, client::verifyToken);
        assertEquals("28000", ex.getSQLState());
    }

    @Test
    void verifyToken_http400_throwsConnectionFailedWithoutRetry() throws Exception {
        server.enqueue(jsonResponse(400, Collections.singletonMap("error", "Bad request")));

        KeboolaJdbcException ex = assertThrows(KeboolaJdbcException.class, client::verifyToken);
        assertEquals("08001", ex.getSQLState());
        assertEquals(1, server.getRequestCount(), "400 must not trigger retry");
    }

    // ---------------------------------------------------------------------
    // discoverQueryServiceUrl
    // ---------------------------------------------------------------------

    @Test
    void discoverQueryServiceUrl_returnsQueryServiceUrlFromIndex() throws Exception {
        Map<String, Object> queryService = new HashMap<>();
        queryService.put("id", "query");
        queryService.put("url", "https://query.example.com");
        Map<String, Object> otherService = new HashMap<>();
        otherService.put("id", "queue");
        otherService.put("url", "https://queue.example.com");
        Map<String, Object> root = new HashMap<>();
        root.put("services", Arrays.asList(otherService, queryService));
        server.enqueue(jsonResponse(200, root));

        String url = client.discoverQueryServiceUrl();

        assertEquals("https://query.example.com", url);
    }

    @Test
    void discoverQueryServiceUrl_queryServiceMissing_throwsException() {
        Map<String, Object> other = new HashMap<>();
        other.put("id", "queue");
        other.put("url", "https://queue.example.com");
        Map<String, Object> root = new HashMap<>();
        root.put("services", Collections.singletonList(other));
        server.enqueue(jsonResponse(200, root));

        KeboolaJdbcException ex = assertThrows(KeboolaJdbcException.class, client::discoverQueryServiceUrl);
        assertTrue(ex.getMessage().contains("not found"), ex.getMessage());
    }

    @Test
    void discoverQueryServiceUrl_noServicesArray_throwsException() {
        server.enqueue(jsonResponse(200, Collections.singletonMap("other", "value")));

        assertThrows(KeboolaJdbcException.class, client::discoverQueryServiceUrl);
    }

    @Test
    void discoverQueryServiceUrl_nonHttpsUrl_throwsException() {
        Map<String, Object> queryService = new HashMap<>();
        queryService.put("id", "query");
        queryService.put("url", "http://insecure.example.com");
        Map<String, Object> root = new HashMap<>();
        root.put("services", Collections.singletonList(queryService));
        server.enqueue(jsonResponse(200, root));

        KeboolaJdbcException ex = assertThrows(KeboolaJdbcException.class, client::discoverQueryServiceUrl);
        assertTrue(ex.getMessage().contains("HTTPS"), ex.getMessage());
    }

    // ---------------------------------------------------------------------
    // discoverServiceUrl(serviceId)
    // ---------------------------------------------------------------------

    @Test
    void discoverServiceUrl_byId_returnsMatchingUrl() throws Exception {
        Map<String, Object> queueService = new HashMap<>();
        queueService.put("id", "queue");
        queueService.put("url", "https://queue.example.com");
        Map<String, Object> root = new HashMap<>();
        root.put("services", Collections.singletonList(queueService));
        server.enqueue(jsonResponse(200, root));

        assertEquals("https://queue.example.com", client.discoverServiceUrl("queue"));
    }

    @Test
    void discoverServiceUrl_serviceNotFound_throwsException() {
        Map<String, Object> root = new HashMap<>();
        root.put("services", Collections.emptyList());
        server.enqueue(jsonResponse(200, root));

        KeboolaJdbcException ex = assertThrows(KeboolaJdbcException.class,
                () -> client.discoverServiceUrl("missing"));
        assertTrue(ex.getMessage().contains("missing"), ex.getMessage());
    }

    // ---------------------------------------------------------------------
    // List endpoints
    // ---------------------------------------------------------------------

    @Test
    void listBranches_happyPath_returnsBranches() throws Exception {
        Map<String, Object> b1 = new HashMap<>();
        b1.put("id", 1);
        b1.put("name", "Main");
        b1.put("isDefault", true);
        Map<String, Object> b2 = new HashMap<>();
        b2.put("id", 2);
        b2.put("name", "Dev");
        b2.put("isDefault", false);
        server.enqueue(jsonResponse(200, Arrays.asList(b1, b2)));

        List<Branch> branches = client.listBranches();

        assertEquals(2, branches.size());
        RecordedRequest req = server.takeRequest();
        assertEquals("/v2/storage/dev-branches", req.getPath());
    }

    @Test
    void listBranches_emptyArray_returnsEmptyList() throws Exception {
        server.enqueue(jsonResponse(200, Collections.emptyList()));

        assertTrue(client.listBranches().isEmpty());
    }

    @Test
    void listWorkspaces_happyPath_returnsWorkspaces() throws Exception {
        Map<String, Object> w = new HashMap<>();
        w.put("id", 100);
        w.put("name", "wkspc");
        server.enqueue(jsonResponse(200, Collections.singletonList(w)));

        List<Workspace> workspaces = client.listWorkspaces();

        assertEquals(1, workspaces.size());
        RecordedRequest req = server.takeRequest();
        assertEquals("/v2/storage/workspaces", req.getPath());
    }

    @Test
    void listBuckets_happyPath_returnsBuckets() throws Exception {
        Map<String, Object> bucket = new HashMap<>();
        bucket.put("id", "in.c-main");
        bucket.put("stage", "in");
        server.enqueue(jsonResponse(200, Collections.singletonList(bucket)));

        List<Bucket> buckets = client.listBuckets();

        assertEquals(1, buckets.size());
        RecordedRequest req = server.takeRequest();
        assertEquals("/v2/storage/buckets", req.getPath());
    }

    @Test
    void listTables_includesBucketIdAndIncludeParam() throws Exception {
        server.enqueue(jsonResponse(200, Collections.emptyList()));

        client.listTables("in.c-data");

        RecordedRequest req = server.takeRequest();
        assertNotNull(req.getPath());
        assertTrue(req.getPath().contains("/v2/storage/buckets/in.c-data/tables"), req.getPath());
        assertTrue(req.getPath().contains("include=columns,columnMetadata"), req.getPath());
    }

    @Test
    void listComponents_returnsJsonArray() throws Exception {
        Map<String, Object> comp = new HashMap<>();
        comp.put("id", "ex-aws-s3");
        comp.put("name", "S3 Extractor");
        server.enqueue(jsonResponse(200, Collections.singletonList(comp)));

        List<JsonNode> components = client.listComponents();

        assertEquals(1, components.size());
        assertEquals("ex-aws-s3", components.get(0).get("id").asText());
    }

    @Test
    void listEvents_limitPropagatedToQueryString() throws Exception {
        server.enqueue(jsonResponse(200, Collections.emptyList()));

        client.listEvents(50);

        RecordedRequest req = server.takeRequest();
        assertTrue(req.getPath().contains("limit=50"), req.getPath());
    }

    @Test
    void listAllTables_pathIncludesColumnsAndBuckets() throws Exception {
        server.enqueue(jsonResponse(200, Collections.emptyList()));

        client.listAllTables();

        RecordedRequest req = server.takeRequest();
        assertTrue(req.getPath().contains("include=columns,buckets"), req.getPath());
    }

    @Test
    void listBucketsRaw_returnsJsonNodes() throws Exception {
        Map<String, Object> bucket = new HashMap<>();
        bucket.put("id", "in.c-data");
        server.enqueue(jsonResponse(200, Collections.singletonList(bucket)));

        List<JsonNode> buckets = client.listBucketsRaw();

        assertEquals(1, buckets.size());
        assertEquals("in.c-data", buckets.get(0).get("id").asText());
    }

    // ---------------------------------------------------------------------
    // Retry behavior (exponential backoff on 5xx/429)
    // ---------------------------------------------------------------------

    @Test
    void executeGet_retriesOn500AndReturnsSuccessOnRetry() throws Exception {
        server.enqueue(jsonResponse(500, Collections.singletonMap("error", "transient")));
        server.enqueue(jsonResponse(200, Collections.emptyList()));

        // Any list endpoint exercises the retry path
        List<Branch> result = client.listBranches();

        assertTrue(result.isEmpty());
        assertEquals(2, server.getRequestCount(), "Expected initial + 1 retry");
    }

    @Test
    void executeGet_retriesOn429AndReturnsSuccess() throws Exception {
        server.enqueue(rawResponse(429, "Too Many Requests"));
        server.enqueue(jsonResponse(200, Collections.emptyList()));

        List<Branch> result = client.listBranches();

        assertTrue(result.isEmpty());
        assertEquals(2, server.getRequestCount());
    }

    @Test
    void executeGet_exhaustsRetriesOn500_throwsAfterMaxAttempts() {
        // MAX_RETRIES = 3 -> 3 attempts total
        server.enqueue(jsonResponse(500, Collections.singletonMap("error", "down")));
        server.enqueue(jsonResponse(500, Collections.singletonMap("error", "down")));
        server.enqueue(jsonResponse(500, Collections.singletonMap("error", "down")));

        KeboolaJdbcException ex = assertThrows(KeboolaJdbcException.class, client::listBranches);
        assertEquals("08001", ex.getSQLState());
        assertEquals(3, server.getRequestCount());
    }

    @Test
    void executeGet_doesNotRetryOn401() {
        server.enqueue(jsonResponse(401, Collections.singletonMap("error", "no")));

        assertThrows(KeboolaJdbcException.class, client::listBranches);
        assertEquals(1, server.getRequestCount(), "401 must not retry");
    }

    @Test
    void executeGet_doesNotRetryOn400() {
        server.enqueue(jsonResponse(400, Collections.singletonMap("error", "bad")));

        assertThrows(KeboolaJdbcException.class, client::listBranches);
        assertEquals(1, server.getRequestCount(), "400 must not retry");
    }

    // ---------------------------------------------------------------------
    // Auth header
    // ---------------------------------------------------------------------

    @Test
    void allRequests_sendStorageApiTokenHeader() throws Exception {
        server.enqueue(jsonResponse(200, Collections.emptyList()));

        client.listBuckets();

        RecordedRequest req = server.takeRequest();
        assertEquals("test-token", req.getHeader("X-StorageApi-Token"));
    }

    // ---------------------------------------------------------------------
    // Misc
    // ---------------------------------------------------------------------

    @Test
    void getToken_returnsConstructorValue() {
        assertEquals("test-token", client.getToken());
    }

    @Test
    void deserialize_malformedJson_throwsConnectionFailed() {
        server.enqueue(rawResponse(200, "{not json"));

        KeboolaJdbcException ex = assertThrows(KeboolaJdbcException.class, client::verifyToken);
        assertEquals("08001", ex.getSQLState());
    }

    @Test
    void deserialize_unknownPropertiesInResponse_ignored() throws Exception {
        // FAIL_ON_UNKNOWN_PROPERTIES is disabled — extra fields should not break deserialization
        Map<String, Object> body = new HashMap<>();
        body.put("id", "1");
        body.put("description", "ok");
        body.put("unexpected_field", "ignored");
        server.enqueue(jsonResponse(200, body));

        TokenInfo info = client.verifyToken();
        assertNotNull(info);
    }
}
