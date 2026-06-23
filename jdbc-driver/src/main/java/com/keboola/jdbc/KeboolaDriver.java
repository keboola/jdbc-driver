package com.keboola.jdbc;

import com.keboola.jdbc.config.ConnectionConfig;
import com.keboola.jdbc.config.DriverConfig;
import com.keboola.jdbc.http.StorageApiClient;
import com.keboola.jdbc.http.model.Branch;
import com.keboola.jdbc.http.model.Workspace;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * JDBC Driver entry point for Keboola Connection.
 *
 * <p>Connection URL format:
 * <pre>
 *   jdbc:keboola://&lt;host&gt;[?branch=&lt;id&gt;&amp;workspace=&lt;id&gt;]
 * </pre>
 *
 * <p>Supported connection properties:
 * <ul>
 *   <li>{@code token} (required) - Keboola Storage API token</li>
 *   <li>{@code branch} (optional) - branch ID or name; defaults to the project's default branch</li>
 *   <li>{@code workspace} (required) - workspace ID to run queries in</li>
 * </ul>
 *
 * <p>The driver registers itself with the {@link DriverManager} in a static initializer.
 */
public class KeboolaDriver implements Driver {

    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(KeboolaDriver.class);

    /** JDBC URL prefix accepted by this driver. */
    public static final String URL_PREFIX = "jdbc:keboola://";

    // Self-register with the JDBC DriverManager on class load
    static {
        try {
            DriverManager.registerDriver(new KeboolaDriver());
            LOG.info("KeboolaDriver registered with DriverManager");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to register KeboolaDriver", e);
        }
    }

    // -------------------------------------------------------------------------
    // Driver interface
    // -------------------------------------------------------------------------

    @Override
    public boolean acceptsURL(String url) throws SQLException {
        return url != null && url.startsWith(URL_PREFIX);
    }

    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        if (!acceptsURL(url)) {
            // Per JDBC spec, return null if this driver does not handle the URL
            return null;
        }
        LOG.debug("Connecting to URL: {}", sanitizeUrlForLog(url));
        ConnectionConfig config;
        try {
            config = ConnectionConfig.fromUrl(url, info);
        } catch (com.keboola.jdbc.exception.KeboolaJdbcException e) {
            throw new SQLException("Invalid connection configuration: " + e.getMessage(), e);
        }
        return new KeboolaConnection(config);
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException {
        Properties effectiveInfo = (info != null) ? info : new Properties();
        // Mirror ConnectionConfig: the token may also be supplied via the standard
        // 'password' property, so honor it here too when populating API-backed choices.
        String token = effectiveInfo.getProperty("token", "");
        if (token.isEmpty()) {
            token = effectiveInfo.getProperty("password", "");
        }

        List<DriverPropertyInfo> props = new ArrayList<>();

        // --- token property ---
        DriverPropertyInfo tokenProp = new DriverPropertyInfo("token", token);
        tokenProp.required = true;
        tokenProp.description = "Keboola Storage API Token";
        props.add(tokenProp);

        // --- branch property ---
        String branchValue = effectiveInfo.getProperty("branch", "");
        DriverPropertyInfo branchProp = new DriverPropertyInfo("branch", branchValue);
        branchProp.required = false;
        branchProp.description = "Branch name or ID (default: main branch)";

        // --- workspace property ---
        String workspaceValue = effectiveInfo.getProperty("workspace", "");
        DriverPropertyInfo workspaceProp = new DriverPropertyInfo("workspace", workspaceValue);
        workspaceProp.required = true;
        workspaceProp.description = "Workspace ID";

        // Attempt to populate choices from the API if a token is provided
        if (!token.isEmpty()) {
            try {
                String host = extractHost(url);
                StorageApiClient apiClient = new StorageApiClient(host, token);

                // Populate branch choices
                List<Branch> branches = apiClient.listBranches();
                branchProp.choices = branches.stream()
                        .map(b -> b.getId() + " (" + b.getName() + ")")
                        .toArray(String[]::new);

                // Populate workspace choices
                List<Workspace> workspaces = apiClient.listWorkspaces();
                workspaceProp.choices = workspaces.stream()
                        .map(w -> String.valueOf(w.getId()) + " (" + w.getName() + ")")
                        .toArray(String[]::new);

                LOG.debug("Populated {} branch choices and {} workspace choices",
                        branchProp.choices.length, workspaceProp.choices.length);

            } catch (Exception e) {
                // Non-fatal: return properties without choices so the user can enter values manually
                LOG.warn("Could not load API choices for getPropertyInfo (bad token or network error): {}", e.getMessage());
                branchProp.choices = new String[0];
                workspaceProp.choices = new String[0];
            }
        } else {
            branchProp.choices = new String[0];
            workspaceProp.choices = new String[0];
        }

        props.add(branchProp);
        props.add(workspaceProp);

        return props.toArray(new DriverPropertyInfo[0]);
    }

    @Override
    public int getMajorVersion() {
        return DriverConfig.MAJOR_VERSION;
    }

    @Override
    public int getMinorVersion() {
        return DriverConfig.MINOR_VERSION;
    }

    @Override
    public boolean jdbcCompliant() {
        // Keboola JDBC driver is not fully JDBC-compliant (read-only, no transactions, etc.)
        return false;
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException("java.util.logging not used by this driver");
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Removes any token or sensitive query parameters from the URL before logging.
     */
    private String sanitizeUrlForLog(String url) {
        if (url == null) {
            return "null";
        }
        int queryStart = url.indexOf('?');
        if (queryStart >= 0) {
            return url.substring(0, queryStart) + "?<redacted>";
        }
        return url;
    }

    /**
     * Extracts the host part from a Keboola JDBC URL.
     *
     * <p>For {@code jdbc:keboola://connection.keboola.com?token=...}, returns
     * {@code connection.keboola.com}.
     *
     * @param url the JDBC URL
     * @return the host portion
     */
    private String extractHost(String url) {
        if (url == null || !url.startsWith(URL_PREFIX)) {
            return "";
        }
        // Remove prefix
        String remainder = url.substring(URL_PREFIX.length());
        // Strip query string if present
        int queryStart = remainder.indexOf('?');
        if (queryStart >= 0) {
            remainder = remainder.substring(0, queryStart);
        }
        // Strip trailing slash
        if (remainder.endsWith("/")) {
            remainder = remainder.substring(0, remainder.length() - 1);
        }
        return remainder;
    }
}
