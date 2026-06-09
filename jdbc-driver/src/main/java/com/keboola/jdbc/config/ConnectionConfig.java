package com.keboola.jdbc.config;

import com.keboola.jdbc.exception.KeboolaJdbcException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Parsed connection parameters derived from a JDBC URL and connection properties.
 *
 * Expected JDBC URL format:
 *   jdbc:keboola://connection.keboola.com[?token=...&branch=...&workspace=...&schema=...]
 *
 * Supported properties (may be supplied via Properties or as URL query parameters;
 * Properties take precedence when both are present):
 *   token     (required) - Keboola Storage API token
 *   branch    (optional) - branch ID to execute queries against
 *   workspace (optional) - workspace ID to use for query execution
 *   schema    (optional) - default schema (bucket) to use for unqualified table references
 */
public class ConnectionConfig {

    private static final Logger LOG = LoggerFactory.getLogger(ConnectionConfig.class);

    /**
     * Matches a valid DNS hostname: labels of alphanumeric + hyphens, separated by dots.
     * Rejects raw IP addresses, localhost, and special characters to prevent SSRF.
     */
    private static final Pattern VALID_HOSTNAME = Pattern.compile(
            "^([a-zA-Z0-9]([a-zA-Z0-9\\-]*[a-zA-Z0-9])?\\.)+[a-zA-Z]{2,}$"
    );

    private static final Set<String> KNOWN_KEYS = new HashSet<>(Arrays.asList(
            "token", "branch", "workspace", "schema"
    ));

    private final String host;
    private final String token;
    private final Long   branchId;
    private final Long   workspaceId;
    private final String schema;

    private ConnectionConfig(String host, String token, Long branchId, Long workspaceId, String schema) {
        this.host        = host;
        this.token       = token;
        this.branchId    = branchId;
        this.workspaceId = workspaceId;
        this.schema      = schema;
    }

    /**
     * Parses the JDBC URL and connection properties into a validated {@link ConnectionConfig}.
     *
     * @param url   JDBC URL, e.g. "jdbc:keboola://connection.keboola.com"
     * @param props connection properties containing at minimum "token"
     * @return a fully validated {@link ConnectionConfig} instance
     * @throws KeboolaJdbcException if the URL is malformed, the host is empty, or the token is missing
     */
    public static ConnectionConfig fromUrl(String url, Properties props) throws KeboolaJdbcException {
        if (url == null || !url.startsWith(DriverConfig.URL_PREFIX)) {
            throw KeboolaJdbcException.connectionFailed(
                    "Invalid JDBC URL. Expected format: " + DriverConfig.URL_PREFIX + "<host>"
            );
        }

        String host = extractHost(url);
        if (host.isEmpty()) {
            // NOTE: do not echo the URL — it may carry a token in its query string.
            throw KeboolaJdbcException.connectionFailed(
                    "Host must not be empty in JDBC URL. Expected format: "
                            + DriverConfig.URL_PREFIX + "<host>"
            );
        }

        if (!VALID_HOSTNAME.matcher(host).matches()) {
            throw KeboolaJdbcException.connectionFailed(
                    "Invalid host in JDBC URL: '" + host + "'. "
                    + "Only valid DNS hostnames are accepted (IP addresses and localhost are rejected)"
            );
        }

        // Merge URL query parameters into properties. Properties win over URL params.
        Properties effectiveProps = new Properties();
        mergeQueryParams(effectiveProps, url);
        if (props != null) {
            for (String name : props.stringPropertyNames()) {
                effectiveProps.setProperty(name, props.getProperty(name));
            }
        }
        warnOnUnknownKeys(effectiveProps);

        String token = effectiveProps.getProperty("token");
        if (token == null || token.trim().isEmpty()) {
            throw KeboolaJdbcException.authenticationFailed(
                    "Property 'token' is required but was not provided"
            );
        }

        Long branchId    = parseOptionalLong(effectiveProps, "branch");
        Long workspaceId = parseOptionalLong(effectiveProps, "workspace");
        String schema    = parseOptionalString(effectiveProps, "schema");

        return new ConnectionConfig(host, token.trim(), branchId, workspaceId, schema);
    }

    /**
     * Extracts the hostname from the JDBC URL by stripping the prefix and any trailing path.
     * E.g. "jdbc:keboola://connection.keboola.com" -> "connection.keboola.com"
     */
    private static String extractHost(String url) {
        // Strip the jdbc:keboola:// prefix
        String remainder = url.substring(DriverConfig.URL_PREFIX.length());
        // Remove any trailing path
        int slashIndex = remainder.indexOf('/');
        if (slashIndex >= 0) {
            remainder = remainder.substring(0, slashIndex);
        }
        // Remove any trailing query string
        int queryIndex = remainder.indexOf('?');
        if (queryIndex >= 0) {
            remainder = remainder.substring(0, queryIndex);
        }
        return remainder.trim();
    }

    /**
     * Parses any query string in the JDBC URL ({@code ?k=v&k2=v2}) and writes the pairs
     * into {@code target}. Values are URL-decoded. Pairs without an "=" are ignored.
     * Anything before the first "?" is the host portion and is skipped here.
     */
    private static void mergeQueryParams(Properties target, String url) {
        int queryIndex = url.indexOf('?');
        if (queryIndex < 0 || queryIndex == url.length() - 1) {
            return;
        }
        String query = url.substring(queryIndex + 1);
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = urlDecode(pair.substring(0, eq));
            String value = urlDecode(pair.substring(eq + 1));
            if (!key.isEmpty()) {
                target.setProperty(key, value);
            }
        }
    }

    /**
     * Percent-decodes a JDBC URL query value. Unlike {@code application/x-www-form-urlencoded},
     * a literal {@code '+'} stays a {@code '+'} — Keboola Storage API tokens commonly contain
     * a plus sign and most users will paste it without URL-encoding it as {@code %2B}.
     */
    private static String urlDecode(String s) {
        try {
            // Pre-escape literal '+' so URLDecoder doesn't turn it into a space.
            String safe = s.replace("+", "%2B");
            return java.net.URLDecoder.decode(safe, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            // Malformed escape — fall back to the raw value rather than failing the whole connection
            return s;
        }
    }

    private static void warnOnUnknownKeys(Properties props) {
        for (String name : props.stringPropertyNames()) {
            if (!KNOWN_KEYS.contains(name)) {
                LOG.warn("Unknown JDBC connection property '{}' — ignored. Known keys: {}",
                        name, KNOWN_KEYS);
            }
        }
    }

    /**
     * Reads an optional long property; returns null if absent or blank.
     *
     * @throws KeboolaJdbcException if the value is present but not a valid number
     */
    private static Long parseOptionalLong(Properties props, String key) throws KeboolaJdbcException {
        String raw = props.getProperty(key);
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw KeboolaJdbcException.connectionFailed(
                    "Property '" + key + "' must be a valid number, got: " + raw
            );
        }
    }

    /**
     * Reads an optional string property; returns null if absent or blank.
     */
    private static String parseOptionalString(Properties props, String key) {
        String raw = props.getProperty(key);
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        return raw.trim();
    }

    // --- Getters ---

    /** Returns the Keboola Connection host, e.g. "connection.keboola.com". */
    public String getHost() {
        return host;
    }

    /** Returns the Keboola Storage API token used to authenticate requests. */
    public String getToken() {
        return token;
    }

    /**
     * Returns the branch ID if explicitly configured, or null to indicate that
     * the caller should discover the default branch via the Storage API.
     */
    public Long getBranchId() {
        return branchId;
    }

    /**
     * Returns the workspace ID if explicitly configured, or null to indicate that
     * the caller should create or discover a suitable workspace.
     */
    public Long getWorkspaceId() {
        return workspaceId;
    }

    /**
     * Returns the default schema (bucket name) if explicitly configured, or null.
     * When set, unqualified table references in SQL will be qualified with this schema.
     */
    public String getSchema() {
        return schema;
    }

    @Override
    public String toString() {
        return "ConnectionConfig{host='" + host + "', branchId=" + branchId
                + ", workspaceId=" + workspaceId + ", schema=" + schema + "}";
    }
}
