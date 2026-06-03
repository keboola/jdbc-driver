package com.keboola.jdbc;

import com.keboola.jdbc.config.ConnectionConfig;
import com.keboola.jdbc.exception.KeboolaJdbcException;
import com.keboola.jdbc.http.JobQueueClient;
import com.keboola.jdbc.http.QueryServiceClient;
import com.keboola.jdbc.http.StorageApiClient;
import com.keboola.jdbc.http.model.Branch;
import com.keboola.jdbc.http.model.JobStatus;
import com.keboola.jdbc.http.model.QueryJob;
import com.keboola.jdbc.http.model.QueryResult;
import com.keboola.jdbc.http.model.StatementStatus;
import com.keboola.jdbc.http.model.TokenInfo;
import com.keboola.jdbc.http.model.Workspace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Array;
import java.sql.Blob;
import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.NClob;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLClientInfoException;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Savepoint;
import java.sql.Statement;
import java.sql.Struct;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * JDBC Connection implementation for Keboola.
 *
 * <p>On construction the following setup is performed:
 * <ol>
 *   <li>Create a {@link StorageApiClient} and verify the provided token</li>
 *   <li>Discover the Query Service URL via the Storage API</li>
 *   <li>Resolve the branch ID (from config or by finding the default branch)</li>
 *   <li>Resolve the workspace ID (required - must be provided in config)</li>
 *   <li>Create a {@link QueryServiceClient} for subsequent query execution</li>
 *   <li>Query CURRENT_DATABASE() and CURRENT_SCHEMA() to initialize catalog/schema</li>
 * </ol>
 *
 * <p>All connections are auto-commit and read-only by default (no transaction support).
 */
public class KeboolaConnection implements Connection {

    private static final Logger LOG = LoggerFactory.getLogger(KeboolaConnection.class);

    private final String host;
    private final StorageApiClient storageClient;
    private final QueryServiceClient queryClient;
    private final TokenInfo tokenInfo;
    private final long branchId;
    private final long workspaceId;

    private boolean closed = false;
    private boolean autoCommit = true;
    private boolean readOnly = true;
    private String catalog;
    private String currentSchema;

    /** Lazy-initialized Job Queue client (created on first access to _keboola.jobs). */
    private volatile JobQueueClient jobQueueClient;

    /**
     * Query Service session ID. Generated on connection init and sent with every job.
     * The server-side session persists SET variables, USE SCHEMA, temp tables, etc.
     * across jobs for up to 24 hours of inactivity. Maps JDBC Connection lifetime
     * to a single Snowflake session on the server side.
     */
    private String sessionId;

    /**
     * Establishes a connection to Keboola using the provided configuration.
     *
     * @param config the parsed connection configuration (host, token, optional branch/workspace)
     * @throws SQLException if token verification, service discovery, or branch/workspace resolution fails
     */
    public KeboolaConnection(ConnectionConfig config) throws SQLException {
        LOG.info("Connecting to Keboola: host={}", config.getHost());

        try {
            this.host = config.getHost();
            storageClient = new StorageApiClient(config.getHost(), config.getToken());
            tokenInfo = verifyToken();
            String queryServiceUrl = discoverQueryServiceUrl();
            queryClient = new QueryServiceClient(queryServiceUrl, config.getToken());
            branchId = resolveBranchId(config);
            workspaceId = resolveWorkspaceId(config);

            // Generate session ID — sent with every job so that SET, USE SCHEMA,
            // temp tables etc. persist across execute() calls
            this.sessionId = UUID.randomUUID().toString();

            // Apply default schema from connection config by sending USE SCHEMA
            // to the server session, so it persists across all subsequent queries
            if (config.getSchema() != null) {
                this.currentSchema = config.getSchema();
                String useSchema = "USE SCHEMA \"" + config.getSchema() + "\"";
                LOG.debug("Setting initial schema via session: {}", useSchema);
                queryClient.submitJob(branchId, workspaceId,
                        java.util.Collections.singletonList(useSchema), sessionId);
            }

            // Discover current database and schema from the server
            initCatalogAndSchema();

            LOG.info("Connected: catalog='{}', branchId={}, workspaceId={}, schema={}, sessionId={}",
                    catalog, branchId, workspaceId, currentSchema, sessionId);

        } catch (KeboolaJdbcException e) {
            throw new SQLException("Failed to connect to Keboola: " + e.getMessage(), e);
        } catch (SQLException e) {
            throw e;
        } catch (Exception e) {
            throw new SQLException("Unexpected error connecting to Keboola: " + e.getMessage(), e);
        }
    }

    /**
     * Package-private constructor used by tests to inject pre-built clients and
     * skip the full network setup flow. Not part of the public API.
     */
    KeboolaConnection(String host,
                      StorageApiClient storageClient,
                      QueryServiceClient queryClient,
                      long branchId,
                      long workspaceId,
                      String catalog,
                      String schema) {
        this.host = host;
        this.storageClient = storageClient;
        this.queryClient = queryClient;
        this.tokenInfo = null;
        this.branchId = branchId;
        this.workspaceId = workspaceId;
        this.catalog = catalog;
        this.currentSchema = schema;
        this.sessionId = "test-session";
    }

    // -------------------------------------------------------------------------
    // Connection setup helpers
    // -------------------------------------------------------------------------

    private TokenInfo verifyToken() throws KeboolaJdbcException {
        LOG.debug("Verifying Storage API token");
        TokenInfo info = storageClient.verifyToken();
        LOG.debug("Token verified: project='{}', tokenId={}", info.getOwner().getName(), info.getId());
        return info;
    }

    private String discoverQueryServiceUrl() throws KeboolaJdbcException {
        LOG.debug("Discovering Query Service URL");
        String url = storageClient.discoverQueryServiceUrl();
        LOG.debug("Query Service URL: {}", url);
        return url;
    }

    private long resolveBranchId(ConnectionConfig config) throws KeboolaJdbcException {
        Long brId = config.getBranchId();
        if (brId != null && brId > 0) {
            LOG.debug("Using configured branchId={}", brId);
            return brId;
        }

        LOG.debug("Resolving default branch");
        List<Branch> branches = storageClient.listBranches();
        return branches.stream()
                .filter(Branch::isDefault)
                .findFirst()
                .map(Branch::getId)
                .orElseThrow(() -> new KeboolaJdbcException("No default branch found in the project"));
    }

    /**
     * Queries the server for CURRENT_DATABASE() and CURRENT_SCHEMA() to initialize
     * the catalog and schema fields. If a schema was specified via connection config,
     * executes USE SCHEMA first.
     */
    private void initCatalogAndSchema() throws SQLException {
        try {
            String initSql;
            if (currentSchema != null && !currentSchema.isEmpty()) {
                initSql = "USE SCHEMA \"" + currentSchema.replace("\"", "\"\"")
                        + "\"; SELECT CURRENT_DATABASE(), CURRENT_SCHEMA()";
            } else {
                initSql = "SELECT CURRENT_DATABASE(), CURRENT_SCHEMA()";
            }

            List<String> stmts = KeboolaStatement.splitStatements(initSql);
            QueryJob job = queryClient.submitJob(branchId, workspaceId, stmts, sessionId);
            JobStatus jobStatus = queryClient.waitForCompletion(job.getQueryJobId());

            StatementStatus lastStmt = jobStatus.getStatements().get(jobStatus.getStatements().size() - 1);
            QueryResult result = queryClient.fetchResults(job.getQueryJobId(), lastStmt.getId(), 0, 100);

            if (result.getData() != null && !result.getData().isEmpty()) {
                List<String> row = result.getData().get(0);
                if (row.size() >= 2) {
                    String db = row.get(0);
                    String sch = row.get(1);
                    if (db != null) this.catalog = db;
                    if (sch != null) this.currentSchema = sch;
                }
            }
        } catch (Exception e) {
            LOG.warn("Failed to discover current database/schema from server: {}", e.getMessage());
        }
    }

    private long resolveWorkspaceId(ConnectionConfig config) throws KeboolaJdbcException {
        Long wsId = config.getWorkspaceId();
        if (wsId != null && wsId > 0) {
            LOG.debug("Using configured workspaceId={}", wsId);
            return wsId;
        }

        LOG.debug("No workspace specified, auto-discovering from API");
        List<Workspace> allWorkspaces = storageClient.listWorkspaces();
        List<Workspace> activeWorkspaces = allWorkspaces;

        if (activeWorkspaces.isEmpty()) {
            throw new KeboolaJdbcException(
                    "No active workspaces found in the project. "
                    + "Create a workspace in Keboola UI first, then reconnect.");
        }

        Workspace selected = activeWorkspaces.get(activeWorkspaces.size() - 1);
        LOG.info("Auto-selected workspace: id={}, name='{}', type={} (out of {} available)",
                selected.getId(), selected.getName(), selected.getType(), activeWorkspaces.size());
        return selected.getId();
    }

    // -------------------------------------------------------------------------
    // Statement factory methods
    // -------------------------------------------------------------------------

    @Override
    public Statement createStatement() throws SQLException {
        checkClosed();
        return new KeboolaStatement(this, queryClient, branchId, workspaceId);
    }

    @Override
    public Statement createStatement(int resultSetType, int resultSetConcurrency) throws SQLException {
        checkClosed();
        validateResultSetType(resultSetType, resultSetConcurrency);
        return createStatement();
    }

    @Override
    public Statement createStatement(int resultSetType, int resultSetConcurrency,
                                     int resultSetHoldability) throws SQLException {
        checkClosed();
        validateResultSetType(resultSetType, resultSetConcurrency);
        return createStatement();
    }

    @Override
    public PreparedStatement prepareStatement(String sql) throws SQLException {
        checkClosed();
        return new KeboolaPreparedStatement(this, queryClient, branchId, workspaceId, sql);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int resultSetType,
                                              int resultSetConcurrency) throws SQLException {
        checkClosed();
        validateResultSetType(resultSetType, resultSetConcurrency);
        return prepareStatement(sql);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int resultSetType,
                                              int resultSetConcurrency,
                                              int resultSetHoldability) throws SQLException {
        checkClosed();
        validateResultSetType(resultSetType, resultSetConcurrency);
        return prepareStatement(sql);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws SQLException {
        return prepareStatement(sql);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int[] columnIndexes) throws SQLException {
        return prepareStatement(sql);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, String[] columnNames) throws SQLException {
        return prepareStatement(sql);
    }

    @Override
    public CallableStatement prepareCall(String sql) throws SQLException {
        throw new SQLFeatureNotSupportedException("prepareCall (stored procedures) not supported");
    }

    @Override
    public CallableStatement prepareCall(String sql, int resultSetType,
                                         int resultSetConcurrency) throws SQLException {
        throw new SQLFeatureNotSupportedException("prepareCall (stored procedures) not supported");
    }

    @Override
    public CallableStatement prepareCall(String sql, int resultSetType,
                                         int resultSetConcurrency,
                                         int resultSetHoldability) throws SQLException {
        throw new SQLFeatureNotSupportedException("prepareCall (stored procedures) not supported");
    }

    // -------------------------------------------------------------------------
    // Metadata
    // -------------------------------------------------------------------------

    @Override
    public DatabaseMetaData getMetaData() throws SQLException {
        checkClosed();
        return new KeboolaDatabaseMetaData(this);
    }

    // -------------------------------------------------------------------------
    // Connection lifecycle
    // -------------------------------------------------------------------------

    @Override
    public void close() throws SQLException {
        if (!closed) {
            closed = true;
            LOG.info("KeboolaConnection closed");
        }
    }

    @Override
    public boolean isClosed() throws SQLException {
        return closed;
    }

    @Override
    public boolean isValid(int timeout) throws SQLException {
        return !closed;
    }

    // -------------------------------------------------------------------------
    // Catalog / schema
    // -------------------------------------------------------------------------

    @Override
    public String getCatalog() throws SQLException {
        checkClosed();
        return catalog;
    }

    @Override
    public void setCatalog(String catalog) throws SQLException {
        checkClosed();
        if (catalog == null) return;
        LOG.debug("setCatalog({})", catalog);
        try (Statement stmt = createStatement()) {
            stmt.execute("USE DATABASE \"" + catalog.replace("\"", "\"\"") + "\"");
        }
        this.catalog = catalog;
    }

    @Override
    public String getSchema() throws SQLException {
        return currentSchema;
    }

    @Override
    public void setSchema(String schema) throws SQLException {
        checkClosed();
        LOG.debug("setSchema({})", schema);
        if (schema != null) {
            try (Statement stmt = createStatement()) {
                stmt.execute("USE SCHEMA \"" + schema.replace("\"", "\"\"") + "\"");
            }
        }
        this.currentSchema = schema;
    }

    /**
     * Updates the local schema from server response (backendContext).
     * Does not execute any SQL — just stores the value reported by the server.
     */
    void updateSchemaFromServer(String schema) {
        this.currentSchema = schema;
    }

    /**
     * Updates the local catalog from server response (backendContext).
     * Does not execute any SQL — just stores the value reported by the server.
     */
    void updateCatalogFromServer(String catalog) {
        this.catalog = catalog;
    }

    // -------------------------------------------------------------------------
    // Auto-commit (always true - no transaction support)
    // -------------------------------------------------------------------------

    @Override
    public boolean getAutoCommit() throws SQLException {
        return autoCommit;
    }

    @Override
    public void setAutoCommit(boolean autoCommit) throws SQLException {
        this.autoCommit = autoCommit;
    }

    @Override
    public void commit() throws SQLException {
        checkClosed();
    }

    @Override
    public void rollback() throws SQLException {
        checkClosed();
    }

    @Override
    public void rollback(Savepoint savepoint) throws SQLException {
        throw new SQLFeatureNotSupportedException("Savepoints are not supported");
    }

    // -------------------------------------------------------------------------
    // Read-only mode
    // -------------------------------------------------------------------------

    @Override
    public boolean isReadOnly() throws SQLException {
        return readOnly;
    }

    @Override
    public void setReadOnly(boolean readOnly) throws SQLException {
        this.readOnly = readOnly;
    }

    // -------------------------------------------------------------------------
    // Transaction isolation (reported as READ_COMMITTED, no actual support)
    // -------------------------------------------------------------------------

    @Override
    public int getTransactionIsolation() throws SQLException {
        return Connection.TRANSACTION_READ_COMMITTED;
    }

    @Override
    public void setTransactionIsolation(int level) throws SQLException {
        // no-op
    }

    @Override
    public SQLWarning getWarnings() throws SQLException { return null; }

    @Override
    public void clearWarnings() throws SQLException { }

    @Override
    public String nativeSQL(String sql) throws SQLException { return sql; }

    @Override
    public Map<String, Class<?>> getTypeMap() throws SQLException {
        throw new SQLFeatureNotSupportedException("getTypeMap not supported");
    }

    @Override
    public void setTypeMap(Map<String, Class<?>> map) throws SQLException {
        throw new SQLFeatureNotSupportedException("setTypeMap not supported");
    }

    @Override
    public int getHoldability() throws SQLException {
        return ResultSet.CLOSE_CURSORS_AT_COMMIT;
    }

    @Override
    public void setHoldability(int holdability) throws SQLException { }

    @Override
    public Savepoint setSavepoint() throws SQLException {
        throw new SQLFeatureNotSupportedException("Savepoints are not supported");
    }

    @Override
    public Savepoint setSavepoint(String name) throws SQLException {
        throw new SQLFeatureNotSupportedException("Savepoints are not supported");
    }

    @Override
    public void releaseSavepoint(Savepoint savepoint) throws SQLException {
        throw new SQLFeatureNotSupportedException("Savepoints are not supported");
    }

    @Override
    public Blob createBlob() throws SQLException {
        throw new SQLFeatureNotSupportedException("createBlob not supported");
    }

    @Override
    public Clob createClob() throws SQLException {
        throw new SQLFeatureNotSupportedException("createClob not supported");
    }

    @Override
    public NClob createNClob() throws SQLException {
        throw new SQLFeatureNotSupportedException("createNClob not supported");
    }

    @Override
    public SQLXML createSQLXML() throws SQLException {
        throw new SQLFeatureNotSupportedException("createSQLXML not supported");
    }

    @Override
    public Array createArrayOf(String typeName, Object[] elements) throws SQLException {
        throw new SQLFeatureNotSupportedException("createArrayOf not supported");
    }

    @Override
    public Struct createStruct(String typeName, Object[] attributes) throws SQLException {
        throw new SQLFeatureNotSupportedException("createStruct not supported");
    }

    @Override
    public Properties getClientInfo() throws SQLException { return new Properties(); }

    @Override
    public String getClientInfo(String name) throws SQLException { return null; }

    @Override
    public void setClientInfo(String name, String value) throws SQLClientInfoException { }

    @Override
    public void setClientInfo(Properties properties) throws SQLClientInfoException { }

    @Override
    public void abort(Executor executor) throws SQLException { close(); }

    @Override
    public void setNetworkTimeout(Executor executor, int milliseconds) throws SQLException { }

    @Override
    public int getNetworkTimeout() throws SQLException { return 0; }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) return iface.cast(this);
        throw new SQLException("Not a wrapper for " + iface.getName());
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return iface.isInstance(this);
    }

    // -------------------------------------------------------------------------
    // Accessors for internal use by other driver classes
    // -------------------------------------------------------------------------

    public String getHost() { return host; }

    /** Returns the Storage API client (used by virtual table handlers). */
    public StorageApiClient getStorageClient() { return storageClient; }

    public QueryServiceClient getQueryClient() { return queryClient; }

    public TokenInfo getTokenInfo() { return tokenInfo; }

    public long getBranchId() { return branchId; }

    public long getWorkspaceId() { return workspaceId; }

    /**
     * Returns the Job Queue API client, lazily discovering the service URL on first access.
     * Thread-safe via double-checked locking.
     */
    public JobQueueClient getJobQueueClient() throws SQLException {
        if (jobQueueClient == null) {
            synchronized (this) {
                if (jobQueueClient == null) {
                    try {
                        String queueUrl = storageClient.discoverServiceUrl("queue");
                        jobQueueClient = new JobQueueClient(queueUrl, storageClient.getToken());
                        LOG.info("Job Queue client initialized: {}", queueUrl);
                    } catch (KeboolaJdbcException e) {
                        throw new SQLException("Failed to discover Job Queue service: " + e.getMessage(), e);
                    }
                }
            }
        }
        return jobQueueClient;
    }

    public String getSessionId() { return sessionId; }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private void checkClosed() throws SQLException {
        if (closed) throw new SQLException("Connection is closed");
    }

    private void validateResultSetType(int resultSetType, int resultSetConcurrency)
            throws SQLFeatureNotSupportedException {
        if (resultSetType != ResultSet.TYPE_FORWARD_ONLY) {
            throw new SQLFeatureNotSupportedException(
                    "Only TYPE_FORWARD_ONLY ResultSet type is supported, got: " + resultSetType);
        }
        if (resultSetConcurrency != ResultSet.CONCUR_READ_ONLY) {
            throw new SQLFeatureNotSupportedException(
                    "Only CONCUR_READ_ONLY ResultSet concurrency is supported, got: " + resultSetConcurrency);
        }
    }
}
