package com.keboola.jdbc.http;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keboola.jdbc.config.DriverConfig;
import com.keboola.jdbc.exception.KeboolaJdbcException;
import com.keboola.jdbc.http.model.Branch;
import com.keboola.jdbc.http.model.Bucket;
import com.keboola.jdbc.http.model.TableInfo;
import com.keboola.jdbc.http.model.TokenInfo;
import com.keboola.jdbc.http.model.Workspace;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * HTTP client for the Keboola Storage API (v2).
 *
 * Handles authentication via the X-StorageApi-Token header, JSON deserialization,
 * and retry logic (up to {@link DriverConfig#MAX_RETRIES} attempts for 5xx/429 responses).
 * 4xx responses other than 429 (e.g. 401, 400) cause an immediate failure without retrying.
 */
public class StorageApiClient {

    private static final Logger LOG = LoggerFactory.getLogger(StorageApiClient.class);

    private static final String HEADER_TOKEN = "X-StorageApi-Token";

    private final String       host;
    private final String       token;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    /**
     * Creates a new Storage API client.
     *
     * @param host  Keboola Connection host, e.g. "connection.keboola.com"
     * @param token Keboola Storage API token used for all requests
     */
    public StorageApiClient(String host, String token) {
        this.host  = host;
        this.token = token;

        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(DriverConfig.HTTP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(DriverConfig.HTTP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(DriverConfig.HTTP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build();

        this.objectMapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /** Returns the token used for API authentication (needed by JobQueueClient). */
    public String getToken() {
        return token;
    }

    // --- Public API methods ---

    /**
     * Verifies the token and returns its metadata.
     * Endpoint: GET https://{host}/v2/storage/tokens/verify
     *
     * @return token metadata including owner and permissions
     * @throws KeboolaJdbcException on authentication failure or network error
     */
    public TokenInfo verifyToken() throws KeboolaJdbcException {
        String url = storageUrl("/v2/storage/tokens/verify");
        LOG.info("Verifying token against {}", url);
        String body = executeGet(url);
        return deserialize(body, TokenInfo.class);
    }

    /**
     * Queries the Storage API index to discover the URL of the Query Service.
     * Endpoint: GET https://{host}/v2/storage
     * Parses the "services" array for the entry with id "query" and returns its "url" field.
     *
     * @return base URL of the Query Service (e.g. "https://query.keboola.com")
     * @throws KeboolaJdbcException if the query service is not listed or the response is malformed
     */
    public String discoverQueryServiceUrl() throws KeboolaJdbcException {
        String url = storageUrl("/v2/storage");
        LOG.info("Discovering Query Service URL from {}", url);
        String body = executeGet(url);
        return parseQueryServiceUrl(body, url);
    }

    /**
     * Lists all development branches visible to the token.
     * Endpoint: GET https://{host}/v2/storage/dev-branches/
     *
     * @return list of branches; never null
     * @throws KeboolaJdbcException on network or API error
     */
    public List<Branch> listBranches() throws KeboolaJdbcException {
        String url = storageUrl("/v2/storage/dev-branches");
        LOG.info("Listing branches from {}", url);
        String body = executeGet(url);
        return deserializeList(body, Branch[].class);
    }

    /**
     * Lists all workspaces visible to the token.
     * Endpoint: GET https://{host}/v2/storage/workspaces
     *
     * @return list of workspaces; never null
     * @throws KeboolaJdbcException on network or API error
     */
    public List<Workspace> listWorkspaces() throws KeboolaJdbcException {
        String url = storageUrl("/v2/storage/workspaces");
        LOG.info("Listing workspaces from {}", url);
        String body = executeGet(url);
        return deserializeList(body, Workspace[].class);
    }

    /**
     * Lists all buckets visible to the token.
     * Endpoint: GET https://{host}/v2/storage/buckets
     *
     * @return list of buckets; never null
     * @throws KeboolaJdbcException on network or API error
     */
    public List<Bucket> listBuckets() throws KeboolaJdbcException {
        String url = storageUrl("/v2/storage/buckets");
        LOG.info("Listing buckets from {}", url);
        String body = executeGet(url);
        return deserializeList(body, Bucket[].class);
    }

    /**
     * Lists all tables within a bucket, including column definitions and metadata.
     * Endpoint: GET https://{host}/v2/storage/buckets/{bucketId}/tables?include=columns,columnMetadata
     *
     * @param bucketId full bucket ID, e.g. "in.c-main"
     * @return list of tables with column information; never null
     * @throws KeboolaJdbcException on network or API error
     */
    public List<TableInfo> listTables(String bucketId) throws KeboolaJdbcException {
        String url = storageUrl("/v2/storage/buckets/" + bucketId + "/tables?include=columns,columnMetadata");
        LOG.info("Listing tables for bucket '{}' from {}", bucketId, url);
        String body = executeGet(url);
        return deserializeList(body, TableInfo[].class);
    }

    // --- Virtual table API methods ---

    /**
     * Lists all components with their configurations.
     * Endpoint: GET https://{host}/v2/storage/components
     *
     * @return list of component JSON nodes; never null
     * @throws KeboolaJdbcException on network or API error
     */
    public List<JsonNode> listComponents() throws KeboolaJdbcException {
        String url = storageUrl("/v2/storage/components");
        LOG.info("Listing components from {}", url);
        String body = executeGet(url);
        return deserializeJsonArray(body);
    }

    /**
     * Lists recent storage events.
     * Endpoint: GET https://{host}/v2/storage/events?limit=N
     *
     * @param limit maximum number of events to return
     * @return list of event JSON nodes; never null
     * @throws KeboolaJdbcException on network or API error
     */
    public List<JsonNode> listEvents(int limit) throws KeboolaJdbcException {
        String url = storageUrl("/v2/storage/events?limit=" + limit);
        LOG.info("Listing events from {}", url);
        String body = executeGet(url);
        return deserializeJsonArray(body);
    }

    /**
     * Lists all tables across all buckets with metadata.
     * Endpoint: GET https://{host}/v2/storage/tables?include=columns,buckets
     *
     * @return list of table JSON nodes; never null
     * @throws KeboolaJdbcException on network or API error
     */
    public List<JsonNode> listAllTables() throws KeboolaJdbcException {
        String url = storageUrl("/v2/storage/tables?include=columns,buckets");
        LOG.info("Listing all tables from {}", url);
        String body = executeGet(url);
        return deserializeJsonArray(body);
    }

    /**
     * Lists all buckets as raw JSON nodes (for virtual table display with full metadata).
     * Endpoint: GET https://{host}/v2/storage/buckets
     *
     * @return list of bucket JSON nodes; never null
     * @throws KeboolaJdbcException on network or API error
     */
    public List<JsonNode> listBucketsRaw() throws KeboolaJdbcException {
        String url = storageUrl("/v2/storage/buckets");
        LOG.info("Listing buckets (raw) from {}", url);
        String body = executeGet(url);
        return deserializeJsonArray(body);
    }

    /**
     * Discovers a service URL from the Storage API index by service ID.
     *
     * @param serviceId the service ID to find (e.g. "queue", "query")
     * @return base URL of the service
     * @throws KeboolaJdbcException if the service is not found
     */
    public String discoverServiceUrl(String serviceId) throws KeboolaJdbcException {
        String url = storageUrl("/v2/storage");
        LOG.info("Discovering service '{}' URL from {}", serviceId, url);
        String body = executeGet(url);
        return parseServiceUrl(body, serviceId, url);
    }

    // --- Internal helpers ---

    private String storageUrl(String path) {
        // Accept either a bare host ("connection.keboola.com") or a full base URL
        // ("http://localhost:1234"). The latter is used by unit tests against MockWebServer.
        // Strip any trailing slash on the base URL so paths starting with "/" don't
        // produce double slashes (e.g. "http://x/" + "/v2/storage" -> "http://x//v2/storage").
        String base = host;
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.startsWith("http://") || base.startsWith("https://")) {
            return base + path;
        }
        return "https://" + base + path;
    }

    /**
     * Executes a GET request with retry logic.
     * Retries up to {@link DriverConfig#MAX_RETRIES} times for 5xx and 429 responses.
     * Fails immediately for 401 and 400 responses.
     */
    private String executeGet(String url) throws KeboolaJdbcException {
        Request request = new Request.Builder()
                .url(url)
                .header(HEADER_TOKEN, token)
                .get()
                .build();

        return executeWithRetry(request, url);
    }

    /**
     * Executes the given request with exponential back-off retry for transient errors.
     *
     * @param request the HTTP request to execute
     * @param urlForLog the URL string used in log and error messages
     * @return response body as a string
     * @throws KeboolaJdbcException on permanent failure or after exhausting retries
     */
    private String executeWithRetry(Request request, String urlForLog) throws KeboolaJdbcException {
        int attempts = 0;
        long delayMs  = DriverConfig.POLL_INITIAL_INTERVAL_MS;

        while (true) {
            attempts++;
            LOG.debug("HTTP GET {} (attempt {}/{})", urlForLog, attempts, DriverConfig.MAX_RETRIES);

            try (Response response = httpClient.newCall(request).execute()) {
                int code = response.code();
                LOG.debug("HTTP {} <- {}", code, urlForLog);

                if (code == 200) {
                    ResponseBody responseBody = response.body();
                    return responseBody != null ? responseBody.string() : "";
                }

                // Fail immediately on authentication and bad-request errors
                if (code == 401 || code == 403) {
                    throw KeboolaJdbcException.authenticationFailed(
                            "HTTP " + code + " from " + urlForLog
                    );
                }
                if (code == 400) {
                    throw KeboolaJdbcException.connectionFailed(
                            "Bad request (HTTP 400) for " + urlForLog
                    );
                }

                // Retry on 5xx and 429 (rate limit)
                boolean retryable = (code == 429 || code >= 500);
                if (!retryable || attempts >= DriverConfig.MAX_RETRIES) {
                    throw KeboolaJdbcException.connectionFailed(
                            "HTTP " + code + " from " + urlForLog + " after " + attempts + " attempt(s)"
                    );
                }

                LOG.warn("Retryable HTTP {} from {}; waiting {}ms before retry {}/{}",
                        code, urlForLog, delayMs, attempts + 1, DriverConfig.MAX_RETRIES);
                sleepUninterruptibly(delayMs);
                delayMs = Math.min((long) (delayMs * DriverConfig.POLL_BACKOFF_FACTOR),
                        DriverConfig.POLL_MAX_INTERVAL_MS);

            } catch (IOException e) {
                if (attempts >= DriverConfig.MAX_RETRIES) {
                    throw KeboolaJdbcException.connectionFailed(
                            "IO error after " + attempts + " attempt(s) for " + urlForLog, e
                    );
                }
                LOG.warn("IO error for {} (attempt {}): {}; retrying in {}ms",
                        urlForLog, attempts, e.getMessage(), delayMs);
                sleepUninterruptibly(delayMs);
                delayMs = Math.min((long) (delayMs * DriverConfig.POLL_BACKOFF_FACTOR),
                        DriverConfig.POLL_MAX_INTERVAL_MS);
            }
        }
    }

    /**
     * Parses the Query Service URL from the Storage API index response body.
     * Looks for an object in the "services" array whose "id" field equals "query".
     */
    private String parseQueryServiceUrl(String responseBody, String requestUrl) throws KeboolaJdbcException {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode services = root.get("services");
            if (services == null || !services.isArray()) {
                throw KeboolaJdbcException.connectionFailed(
                        "Storage API index response does not contain 'services' array from " + requestUrl
                );
            }
            for (JsonNode service : services) {
                JsonNode idNode  = service.get("id");
                JsonNode urlNode = service.get("url");
                if (idNode != null && "query".equals(idNode.asText()) && urlNode != null) {
                    String queryUrl = urlNode.asText();
                    if (!queryUrl.startsWith("https://")) {
                        throw KeboolaJdbcException.connectionFailed(
                                "Query Service URL must use HTTPS, got: " + queryUrl
                        );
                    }
                    LOG.info("Discovered Query Service URL: {}", queryUrl);
                    return queryUrl;
                }
            }
            throw KeboolaJdbcException.connectionFailed(
                    "Query Service not found in 'services' array from " + requestUrl
            );
        } catch (IOException e) {
            throw KeboolaJdbcException.connectionFailed(
                    "Failed to parse Storage API index response from " + requestUrl, e
            );
        }
    }

    /** Deserializes a JSON string into the specified type. */
    private <T> T deserialize(String json, Class<T> type) throws KeboolaJdbcException {
        try {
            return objectMapper.readValue(json, type);
        } catch (IOException e) {
            throw KeboolaJdbcException.connectionFailed(
                    "Failed to deserialize API response as " + type.getSimpleName(), e
            );
        }
    }

    /**
     * Deserializes a JSON array string into a list using an array type as an intermediary.
     * Returns an empty list if the JSON represents an empty array.
     */
    private <T> List<T> deserializeList(String json, Class<T[]> arrayType) throws KeboolaJdbcException {
        try {
            T[] array = objectMapper.readValue(json, arrayType);
            return array != null ? Arrays.asList(array) : Collections.emptyList();
        } catch (IOException e) {
            throw KeboolaJdbcException.connectionFailed(
                    "Failed to deserialize API response as list of " + arrayType.getComponentType().getSimpleName(), e
            );
        }
    }

    /**
     * Parses a service URL from the Storage API index response by service ID.
     * Generic version of parseQueryServiceUrl.
     */
    private String parseServiceUrl(String responseBody, String serviceId, String requestUrl)
            throws KeboolaJdbcException {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode services = root.get("services");
            if (services == null || !services.isArray()) {
                throw KeboolaJdbcException.connectionFailed(
                        "Storage API index response does not contain 'services' array from " + requestUrl
                );
            }
            for (JsonNode service : services) {
                JsonNode idNode = service.get("id");
                JsonNode urlNode = service.get("url");
                if (idNode != null && serviceId.equals(idNode.asText()) && urlNode != null) {
                    String serviceUrl = urlNode.asText();
                    if (!serviceUrl.startsWith("https://")) {
                        throw KeboolaJdbcException.connectionFailed(
                                serviceId + " service URL must use HTTPS, got: " + serviceUrl
                        );
                    }
                    LOG.info("Discovered {} service URL: {}", serviceId, serviceUrl);
                    return serviceUrl;
                }
            }
            throw KeboolaJdbcException.connectionFailed(
                    "Service '" + serviceId + "' not found in 'services' array from " + requestUrl
            );
        } catch (IOException e) {
            throw KeboolaJdbcException.connectionFailed(
                    "Failed to parse Storage API index response from " + requestUrl, e
            );
        }
    }

    /**
     * Deserializes a JSON array string into a list of JsonNode objects.
     */
    private List<JsonNode> deserializeJsonArray(String json) throws KeboolaJdbcException {
        try {
            JsonNode root = objectMapper.readTree(json);
            List<JsonNode> result = new java.util.ArrayList<>();
            if (root.isArray()) {
                for (JsonNode node : root) {
                    result.add(node);
                }
            }
            return result;
        } catch (IOException e) {
            throw KeboolaJdbcException.connectionFailed(
                    "Failed to parse JSON array response", e
            );
        }
    }

    /** Sleeps for the given duration, logging a warning if interrupted. */
    private static void sleepUninterruptibly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn("Sleep interrupted during retry back-off");
        }
    }
}
