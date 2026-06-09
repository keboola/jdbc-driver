package com.keboola.jdbc.config;

import com.keboola.jdbc.exception.KeboolaJdbcException;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ConnectionConfig - verifies URL parsing and property validation.
 */
class ConnectionConfigTest {

    private static final String VALID_URL = "jdbc:keboola://connection.keboola.com";
    private static final String VALID_TOKEN = "my-secret-token";

    // -------------------------------------------------------------------------
    // fromUrl() - valid input
    // -------------------------------------------------------------------------

    @Test
    void fromUrl_validUrlWithToken_createsConfig() throws KeboolaJdbcException {
        Properties props = new Properties();
        props.setProperty("token", VALID_TOKEN);

        ConnectionConfig config = ConnectionConfig.fromUrl(VALID_URL, props);

        assertNotNull(config);
    }

    @Test
    void fromUrl_validUrl_extractsHostCorrectly() throws KeboolaJdbcException {
        Properties props = new Properties();
        props.setProperty("token", VALID_TOKEN);

        ConnectionConfig config = ConnectionConfig.fromUrl(VALID_URL, props);

        assertEquals("connection.keboola.com", config.getHost());
    }

    @Test
    void fromUrl_tokenIsStoredTrimmed() throws KeboolaJdbcException {
        Properties props = new Properties();
        props.setProperty("token", "  " + VALID_TOKEN + "  ");

        ConnectionConfig config = ConnectionConfig.fromUrl(VALID_URL, props);

        assertEquals(VALID_TOKEN, config.getToken());
    }

    @Test
    void fromUrl_withBranchProperty_readsBranchId() throws KeboolaJdbcException {
        Properties props = new Properties();
        props.setProperty("token", VALID_TOKEN);
        props.setProperty("branch", "42");

        ConnectionConfig config = ConnectionConfig.fromUrl(VALID_URL, props);

        assertEquals(42, config.getBranchId());
    }

    @Test
    void fromUrl_withWorkspaceProperty_readsWorkspaceId() throws KeboolaJdbcException {
        Properties props = new Properties();
        props.setProperty("token", VALID_TOKEN);
        props.setProperty("workspace", "123");

        ConnectionConfig config = ConnectionConfig.fromUrl(VALID_URL, props);

        assertEquals(123, config.getWorkspaceId());
    }

    @Test
    void fromUrl_withBothBranchAndWorkspace_readsBothIds() throws KeboolaJdbcException {
        Properties props = new Properties();
        props.setProperty("token", VALID_TOKEN);
        props.setProperty("branch", "10");
        props.setProperty("workspace", "20");

        ConnectionConfig config = ConnectionConfig.fromUrl(VALID_URL, props);

        assertEquals(10, config.getBranchId());
        assertEquals(20, config.getWorkspaceId());
    }

    @Test
    void fromUrl_withoutBranchOrWorkspace_returnsNullsForOptionals() throws KeboolaJdbcException {
        Properties props = new Properties();
        props.setProperty("token", VALID_TOKEN);

        ConnectionConfig config = ConnectionConfig.fromUrl(VALID_URL, props);

        assertNull(config.getBranchId());
        assertNull(config.getWorkspaceId());
    }

    @Test
    void fromUrl_withSchemaProperty_readsSchema() throws KeboolaJdbcException {
        Properties props = new Properties();
        props.setProperty("token", VALID_TOKEN);
        props.setProperty("schema", "in.c-main");

        ConnectionConfig config = ConnectionConfig.fromUrl(VALID_URL, props);

        assertEquals("in.c-main", config.getSchema());
    }

    @Test
    void fromUrl_withoutSchema_returnsNull() throws KeboolaJdbcException {
        Properties props = new Properties();
        props.setProperty("token", VALID_TOKEN);

        ConnectionConfig config = ConnectionConfig.fromUrl(VALID_URL, props);

        assertNull(config.getSchema());
    }

    @Test
    void fromUrl_withBlankSchema_returnsNull() throws KeboolaJdbcException {
        Properties props = new Properties();
        props.setProperty("token", VALID_TOKEN);
        props.setProperty("schema", "  ");

        ConnectionConfig config = ConnectionConfig.fromUrl(VALID_URL, props);

        assertNull(config.getSchema());
    }

    @Test
    void fromUrl_urlWithTrailingPath_extractsHostCorrectly() throws KeboolaJdbcException {
        Properties props = new Properties();
        props.setProperty("token", VALID_TOKEN);

        ConnectionConfig config = ConnectionConfig.fromUrl("jdbc:keboola://connection.keboola.com/some/path", props);

        assertEquals("connection.keboola.com", config.getHost());
    }

    @Test
    void fromUrl_nullProperties_throwsMissingTokenException() {
        KeboolaJdbcException ex = assertThrows(
                KeboolaJdbcException.class,
                () -> ConnectionConfig.fromUrl(VALID_URL, null)
        );
        assertTrue(ex.getMessage().contains("token"), "Exception message should mention 'token'");
    }

    // -------------------------------------------------------------------------
    // fromUrl() - missing or empty token
    // -------------------------------------------------------------------------

    @Test
    void fromUrl_missingToken_throwsException() {
        Properties props = new Properties();
        // No token property set

        KeboolaJdbcException ex = assertThrows(
                KeboolaJdbcException.class,
                () -> ConnectionConfig.fromUrl(VALID_URL, props)
        );
        assertTrue(ex.getMessage().contains("token"), "Exception message should mention 'token'");
    }

    @Test
    void fromUrl_emptyToken_throwsException() {
        Properties props = new Properties();
        props.setProperty("token", "");

        KeboolaJdbcException ex = assertThrows(
                KeboolaJdbcException.class,
                () -> ConnectionConfig.fromUrl(VALID_URL, props)
        );
        assertTrue(ex.getMessage().contains("token"), "Exception message should mention 'token'");
    }

    @Test
    void fromUrl_blankToken_throwsException() {
        Properties props = new Properties();
        props.setProperty("token", "   ");

        KeboolaJdbcException ex = assertThrows(
                KeboolaJdbcException.class,
                () -> ConnectionConfig.fromUrl(VALID_URL, props)
        );
        assertTrue(ex.getMessage().contains("token"), "Exception message should mention 'token'");
    }

    // -------------------------------------------------------------------------
    // fromUrl() - invalid URL
    // -------------------------------------------------------------------------

    @Test
    void fromUrl_nullUrl_throwsException() {
        Properties props = new Properties();
        props.setProperty("token", VALID_TOKEN);

        assertThrows(
                KeboolaJdbcException.class,
                () -> ConnectionConfig.fromUrl(null, props)
        );
    }

    @Test
    void fromUrl_wrongUrlPrefix_throwsException() {
        Properties props = new Properties();
        props.setProperty("token", VALID_TOKEN);

        assertThrows(
                KeboolaJdbcException.class,
                () -> ConnectionConfig.fromUrl("jdbc:mysql://localhost", props)
        );
    }

    @Test
    void fromUrl_emptyHost_throwsException() {
        Properties props = new Properties();
        props.setProperty("token", VALID_TOKEN);

        // URL with empty host: "jdbc:keboola://"
        assertThrows(
                KeboolaJdbcException.class,
                () -> ConnectionConfig.fromUrl("jdbc:keboola://", props)
        );
    }

    // -------------------------------------------------------------------------
    // fromUrl() - SSRF protection (hostname validation)
    // -------------------------------------------------------------------------

    @Test
    void fromUrl_ipAddress_throwsException() {
        Properties props = new Properties();
        props.setProperty("token", VALID_TOKEN);

        KeboolaJdbcException ex = assertThrows(
                KeboolaJdbcException.class,
                () -> ConnectionConfig.fromUrl("jdbc:keboola://169.254.169.254", props)
        );
        assertTrue(ex.getMessage().contains("Invalid host"), "Should reject IP addresses");
    }

    @Test
    void fromUrl_localhost_throwsException() {
        Properties props = new Properties();
        props.setProperty("token", VALID_TOKEN);

        assertThrows(
                KeboolaJdbcException.class,
                () -> ConnectionConfig.fromUrl("jdbc:keboola://localhost", props)
        );
    }

    @Test
    void fromUrl_ipv6_throwsException() {
        Properties props = new Properties();
        props.setProperty("token", VALID_TOKEN);

        assertThrows(
                KeboolaJdbcException.class,
                () -> ConnectionConfig.fromUrl("jdbc:keboola://[::1]", props)
        );
    }

    @Test
    void fromUrl_hostWithPort_throwsException() {
        Properties props = new Properties();
        props.setProperty("token", VALID_TOKEN);

        assertThrows(
                KeboolaJdbcException.class,
                () -> ConnectionConfig.fromUrl("jdbc:keboola://evil.com:8080", props)
        );
    }

    @Test
    void fromUrl_validKeboolaHost_accepted() throws KeboolaJdbcException {
        Properties props = new Properties();
        props.setProperty("token", VALID_TOKEN);

        ConnectionConfig config = ConnectionConfig.fromUrl("jdbc:keboola://connection.north-europe.azure.keboola.com", props);
        assertEquals("connection.north-europe.azure.keboola.com", config.getHost());
    }

    // -------------------------------------------------------------------------
    // toString()
    // -------------------------------------------------------------------------

    @Test
    void toString_doesNotExposeToken() throws KeboolaJdbcException {
        Properties props = new Properties();
        props.setProperty("token", VALID_TOKEN);

        ConnectionConfig config = ConnectionConfig.fromUrl(VALID_URL, props);
        String str = config.toString();

        assertFalse(str.contains(VALID_TOKEN), "toString() must not expose the API token");
        assertTrue(str.contains("connection.keboola.com"), "toString() should include the host");
    }

    // ---------------------------------------------------------------------
    // URL query string parsing — Tableau-style "all-in-URL" connections
    // ---------------------------------------------------------------------

    @Test
    void fromUrl_tokenInQueryString_isAccepted() throws KeboolaJdbcException {
        String url = "jdbc:keboola://connection.keboola.com?token=" + VALID_TOKEN;

        ConnectionConfig config = ConnectionConfig.fromUrl(url, new Properties());

        assertEquals("connection.keboola.com", config.getHost());
        assertEquals(VALID_TOKEN, config.getToken());
    }

    @Test
    void fromUrl_allParamsInQueryString_areParsed() throws KeboolaJdbcException {
        String url = "jdbc:keboola://connection.keboola.com"
                + "?token=" + VALID_TOKEN
                + "&branch=42"
                + "&workspace=99"
                + "&schema=my-bucket";

        ConnectionConfig config = ConnectionConfig.fromUrl(url, new Properties());

        assertEquals(VALID_TOKEN, config.getToken());
        assertEquals(42L, config.getBranchId());
        assertEquals(99L, config.getWorkspaceId());
        assertEquals("my-bucket", config.getSchema());
    }

    @Test
    void fromUrl_propertiesOverrideQueryString() throws KeboolaJdbcException {
        String url = "jdbc:keboola://connection.keboola.com?token=url-token&branch=1";
        Properties props = new Properties();
        props.setProperty("token", "props-token");
        props.setProperty("branch", "999");

        ConnectionConfig config = ConnectionConfig.fromUrl(url, props);

        assertEquals("props-token", config.getToken());
        assertEquals(999L, config.getBranchId());
    }

    @Test
    void fromUrl_urlEncodedTokenIsDecoded() throws KeboolaJdbcException {
        // "+" and "%2B" both decode to "+"; a real Keboola token can contain a plus sign
        String url = "jdbc:keboola://connection.keboola.com?token=abc%2Bdef";

        ConnectionConfig config = ConnectionConfig.fromUrl(url, new Properties());

        assertEquals("abc+def", config.getToken());
    }

    @Test
    void fromUrl_emptyQueryStringIsIgnored() throws KeboolaJdbcException {
        // Trailing "?" with no params used to break host extraction; verify it's tolerated
        String url = "jdbc:keboola://connection.keboola.com?";
        Properties props = new Properties();
        props.setProperty("token", VALID_TOKEN);

        ConnectionConfig config = ConnectionConfig.fromUrl(url, props);

        assertEquals("connection.keboola.com", config.getHost());
    }
}
