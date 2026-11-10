package com.keboola.jdbc.http;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keboola.jdbc.config.DriverConfig;
import com.keboola.jdbc.exception.KeboolaJdbcException;
import com.keboola.jdbc.http.model.JobStatus;
import com.keboola.jdbc.http.model.QueryJob;
import com.keboola.jdbc.http.model.QueryResult;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * HTTP client for the Keboola Query Service API (v1).
 *
 * Handles SQL query submission, asynchronous job polling with exponential back-off,
 * paginated result fetching, and job cancellation.
 *
 * Retry policy:
 * - 5xx and 429 responses are retried up to {@link DriverConfig#MAX_RETRIES} times.
 * - 401/403 and 400 responses cause an immediate failure.
 */
public class QueryServiceClient {

    private static final Logger LOG = LoggerFactory.getLogger(QueryServiceClient.class);

    private static final String HEADER_TOKEN  = "X-StorageAPI-Token";
    private static final MediaType JSON_MEDIA  = MediaType.parse("application/json");

    private final String       queryServiceUrl;
    private final String       token;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final java.util.Map<String, Object> queryTag;

    /**
     * Creates a new Query Service client that sends no query tag.
     *
     * @param queryServiceUrl base URL of the Query Service, e.g. "https://query.keboola.com"
     * @param token           Keboola Storage API token used for all requests
     */
    public QueryServiceClient(String queryServiceUrl, String token) {
        this(queryServiceUrl, token, java.util.Collections.emptyMap());
    }

    /**
     * Creates a new Query Service client.
     *
     * @param queryServiceUrl base URL of the Query Service, e.g. "https://query.keboola.com"
     * @param token           Keboola Storage API token used for all requests
     * @param queryTag        keys sent as {@code queryTag} with every job. Query Service sets
     *                        QUERY_TAG per statement, so a session-level ALTER SESSION tag would be
     *                        overwritten; these keys are merged into its tag instead.
     */
    public QueryServiceClient(String queryServiceUrl, String token, java.util.Map<String, Object> queryTag) {
        this.queryServiceUrl = queryServiceUrl.endsWith("/")
                ? queryServiceUrl.substring(0, queryServiceUrl.length() - 1)
                : queryServiceUrl;
        this.token = token;
        this.queryTag = queryTag;

        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(DriverConfig.HTTP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(DriverConfig.HTTP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(DriverConfig.HTTP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build();

        this.objectMapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    // --- Public API methods ---

    /**
     * Submits SQL statements to the Query Service for asynchronous execution.
     * All statements execute in the same Snowflake session within a single job.
     * Endpoint: POST /api/v1/branches/{branchId}/workspaces/{workspaceId}/queries
     *
     * @param branchId    the branch to execute the query against
     * @param workspaceId the workspace to execute the query in
     * @param statements  the SQL statements to execute (in order, same session)
     * @return job metadata including the queryJobId needed for polling
     * @throws KeboolaJdbcException on network error or non-retryable API error
     */
    /**
     * Submits SQL statements to the Query Service for asynchronous execution.
     * All statements execute in the same Snowflake session within a single job.
     * If a sessionId is provided, the job joins that existing session (preserving
     * SET variables, USE SCHEMA, temp tables etc. from previous jobs).
     *
     * @param branchId    the branch to execute the query against
     * @param workspaceId the workspace to execute the query in
     * @param statements  the SQL statements to execute (in order, same session)
     * @param sessionId   optional session ID to reuse (null for new session)
     * @return job metadata including the queryJobId and sessionId
     */
    public QueryJob submitJob(long branchId, long workspaceId, java.util.List<String> statements, String sessionId) throws KeboolaJdbcException {
        String url  = queryServiceUrl + "/api/v1/branches/" + branchId + "/workspaces/" + workspaceId + "/queries";
        String body = buildSubmitJobBody(statements, sessionId);

        LOG.info("Submitting query job to {} ({} statement(s), sessionId={})", url, statements.size(), sessionId);
        for (int i = 0; i < statements.size(); i++) {
            LOG.debug("Statement [{}]: {}", i, statements.get(i));
        }

        String responseBody = executePost(url, body);
        return deserialize(responseBody, QueryJob.class);
    }

    /**
     * Submits SQL statements without a session ID (new session).
     * Convenience overload for backward compatibility.
     */
    public QueryJob submitJob(long branchId, long workspaceId, java.util.List<String> statements) throws KeboolaJdbcException {
        return submitJob(branchId, workspaceId, statements, null);
    }

    /**
     * Fetches the current status of a query job.
     * Endpoint: GET /api/v1/queries/{queryJobId}
     *
     * @param queryJobId the job ID returned by {@link #submitJob}
     * @return current job status including per-statement status details
     * @throws KeboolaJdbcException on network error or API error
     */
    public JobStatus getJobStatus(String queryJobId) throws KeboolaJdbcException {
        String url = queryServiceUrl + "/api/v1/queries/" + queryJobId;
        LOG.debug("Polling job status: {}", url);
        String body = executeGet(url);
        return deserialize(body, JobStatus.class);
    }

    /**
     * Polls the job status using exponential back-off until the job reaches a terminal state
     * (completed, failed, or canceled), or until {@link DriverConfig#MAX_WAIT_TIME_SECONDS} elapses.
     *
     * Back-off starts at {@link DriverConfig#POLL_INITIAL_INTERVAL_MS} and grows by
     * {@link DriverConfig#POLL_BACKOFF_FACTOR} per poll, capped at {@link DriverConfig#POLL_MAX_INTERVAL_MS}.
     *
     * @param queryJobId the job to poll
     * @return the terminal {@link JobStatus}
     * @throws KeboolaJdbcException if the job times out or a network error occurs
     */
    public JobStatus waitForCompletion(String queryJobId) throws KeboolaJdbcException {
        long deadlineMs  = System.currentTimeMillis() + DriverConfig.MAX_WAIT_TIME_SECONDS * 1_000L;
        long intervalMs  = DriverConfig.POLL_INITIAL_INTERVAL_MS;

        LOG.info("Waiting for query job {} to complete (max {}s)", queryJobId, DriverConfig.MAX_WAIT_TIME_SECONDS);

        while (true) {
            JobStatus status = getJobStatus(queryJobId);

            if (status.isTerminal()) {
                LOG.info("Query job {} reached terminal status: {}", queryJobId, status.getStatus());
                return status;
            }

            long remainingMs = deadlineMs - System.currentTimeMillis();
            if (remainingMs <= 0) {
                throw KeboolaJdbcException.timeout(queryJobId, DriverConfig.MAX_WAIT_TIME_SECONDS);
            }

            long sleepMs = Math.min(intervalMs, remainingMs);
            LOG.debug("Job {} status={}, sleeping {}ms before next poll", queryJobId, status.getStatus(), sleepMs);
            sleepUninterruptibly(sleepMs);

            intervalMs = Math.min((long) (intervalMs * DriverConfig.POLL_BACKOFF_FACTOR),
                    DriverConfig.POLL_MAX_INTERVAL_MS);
        }
    }

    /**
     * Polls the job status until completion and returns the first {@link StatementStatus}.
     * This overload is provided for compatibility with callers that need the statement-level status.
     *
     * Equivalent to calling {@link #waitForCompletion(String)} and extracting the first statement.
     *
     * @param queryJobId the job to poll
     * @return the first {@link StatementStatus} from the terminal job
     * @throws KeboolaJdbcException if the job times out, has no statements, or a network error occurs
     */
    public com.keboola.jdbc.http.model.StatementStatus waitForCompletionStatement(String queryJobId)
            throws KeboolaJdbcException {
        JobStatus job = waitForCompletion(queryJobId);
        if (job.getStatements() == null || job.getStatements().isEmpty()) {
            throw KeboolaJdbcException.queryFailed(queryJobId, "Job completed but returned no statements");
        }
        return job.getStatements().get(0);
    }

    /**
     * Fetches a page of results for a completed statement without a job ID.
     * This overload is provided for compatibility with callers that track the statementId separately.
     * Uses an empty string as the queryJobId in the URL (statementId is used directly).
     *
     * @param statementId the statement ID
     * @param offset      zero-based row offset
     * @param pageSize    maximum rows per page
     * @return a page of result rows
     * @throws KeboolaJdbcException on network or API error
     * @deprecated Prefer {@link #fetchResults(String, String, int, int)} which includes the queryJobId.
     */
    @Deprecated
    public com.keboola.jdbc.http.model.QueryResult fetchResults(String statementId, int offset, int pageSize)
            throws KeboolaJdbcException {
        // The statementId may be a composite "queryJobId/statementId" or just a statementId.
        // Fall back to using statementId as both to maintain backward compatibility.
        String url = queryServiceUrl + "/api/v1/queries/" + statementId
                + "/results?offset=" + offset + "&pageSize=" + pageSize;
        LOG.debug("Fetching results (compat): {} (offset={}, pageSize={})", url, offset, pageSize);
        String body = executeGet(url);
        return deserialize(body, com.keboola.jdbc.http.model.QueryResult.class);
    }

    /**
     * Fetches a page of results for a completed statement.
     * Endpoint: GET /api/v1/queries/{queryJobId}/{statementId}/results?offset={offset}&pageSize={pageSize}
     *
     * @param queryJobId  the parent job ID
     * @param statementId the specific statement ID within the job
     * @param offset      zero-based row offset for pagination
     * @param pageSize    maximum number of rows to return (use {@link DriverConfig#DEFAULT_PAGE_SIZE})
     * @return a page of result rows with column metadata
     * @throws KeboolaJdbcException on network error or API error
     */
    public QueryResult fetchResults(String queryJobId, String statementId, int offset, int pageSize)
            throws KeboolaJdbcException {
        String url = queryServiceUrl
                + "/api/v1/queries/" + queryJobId
                + "/" + statementId
                + "/results?offset=" + offset + "&pageSize=" + pageSize;
        LOG.debug("Fetching results: {} (offset={}, pageSize={})", url, offset, pageSize);
        String body = executeGet(url);
        return deserialize(body, QueryResult.class);
    }

    /**
     * Requests cancellation of a running query job.
     * Endpoint: POST /api/v1/queries/{queryJobId}/cancel
     *
     * @param queryJobId the job to cancel
     * @throws KeboolaJdbcException on network error or API error
     */
    public void cancelJob(String queryJobId) throws KeboolaJdbcException {
        String url  = queryServiceUrl + "/api/v1/queries/" + queryJobId + "/cancel";
        String body = "{\"reason\": \"Canceled by JDBC driver\"}";
        LOG.info("Canceling query job {}", queryJobId);
        executePost(url, body);
    }

    // --- Internal helpers ---

    /**
     * Builds the JSON body for a query submission request.
     * The statements list is sent directly per the Query Service API contract.
     */
    private String buildSubmitJobBody(java.util.List<String> statements, String sessionId) throws KeboolaJdbcException {
        try {
            java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("statements", statements);
            if (sessionId != null && !sessionId.isEmpty()) {
                body.put("sessionId", sessionId);
            }
            if (!queryTag.isEmpty()) {
                body.put("queryTag", queryTag);
            }
            return objectMapper.writeValueAsString(body);
        } catch (IOException e) {
            throw KeboolaJdbcException.connectionFailed("Failed to serialize query request body", e);
        }
    }

    /** Executes a GET request with retry logic. */
    private String executeGet(String url) throws KeboolaJdbcException {
        Request request = new Request.Builder()
                .url(url)
                .header(HEADER_TOKEN, token)
                .get()
                .build();
        return executeWithRetry(request, url);
    }

    /** Executes a POST request with a JSON body, with retry logic. */
    private String executePost(String url, String jsonBody) throws KeboolaJdbcException {
        RequestBody requestBody = RequestBody.create(jsonBody, JSON_MEDIA);
        Request request = new Request.Builder()
                .url(url)
                .header(HEADER_TOKEN, token)
                .post(requestBody)
                .build();
        return executeWithRetry(request, url);
    }

    /**
     * Executes the given request with exponential back-off retry for transient errors.
     * Retries up to {@link DriverConfig#MAX_RETRIES} times for 5xx and 429 responses.
     * Fails immediately for 401/403 and 400 responses.
     */
    private String executeWithRetry(Request request, String urlForLog) throws KeboolaJdbcException {
        int  attempts = 0;
        long delayMs  = DriverConfig.POLL_INITIAL_INTERVAL_MS;

        while (true) {
            attempts++;
            LOG.debug("HTTP {} {} (attempt {}/{})",
                    request.method(), urlForLog, attempts, DriverConfig.MAX_RETRIES);

            try (Response response = httpClient.newCall(request).execute()) {
                int code = response.code();
                LOG.debug("HTTP {} <- {}", code, urlForLog);

                if (code >= 200 && code < 300) {
                    ResponseBody responseBody = response.body();
                    return responseBody != null ? responseBody.string() : "";
                }

                // Fail immediately on authentication errors
                if (code == 401 || code == 403) {
                    throw KeboolaJdbcException.authenticationFailed(
                            "HTTP " + code + " from " + urlForLog
                    );
                }
                // Fail immediately on bad request (do not expose response body — may contain internal details)
                if (code == 400) {
                    ResponseBody responseBody = response.body();
                    String detail = (responseBody != null) ? responseBody.string() : "(no body)";
                    LOG.debug("HTTP 400 response body from {}: {}", urlForLog, detail);
                    throw KeboolaJdbcException.queryFailed("(unknown)",
                            "HTTP 400 from " + urlForLog + " (see DEBUG log for details)");
                }

                // Retry on 5xx and 429
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

    /** Sleeps for the given duration, logging a warning if interrupted. */
    private static void sleepUninterruptibly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn("Sleep interrupted during retry back-off or polling");
        }
    }
}
