package com.keboola.jdbc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration tests for the Keboola JDBC driver.
 *
 * Requires environment variables:
 *   KEBOOLA_TOKEN     - Storage API token (required)
 *   KEBOOLA_HOST      - Connection host (default: connection.keboola.com)
 *   KEBOOLA_WORKSPACE - Workspace ID (optional, auto-discovers if not set)
 *   KEBOOLA_BRANCH    - Branch ID (optional, uses default branch if not set)
 *
 * Run with: mvn verify -Pkeboola-integration
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class KeboolaDriverIT {

    private static Connection connection;
    private static String token;
    private static String host;

    @BeforeAll
    static void setUp() throws Exception {
        token = System.getenv("KEBOOLA_TOKEN");
        Assumptions.assumeTrue(token != null && !token.isEmpty(),
                "Skipping integration tests: KEBOOLA_TOKEN not set");

        host = System.getenv("KEBOOLA_HOST");
        if (host == null || host.isEmpty()) {
            host = "connection.keboola.com";
        }

        // Force driver registration
        Class.forName("com.keboola.jdbc.KeboolaDriver");

        Properties props = new Properties();
        props.setProperty("token", token);

        String workspace = System.getenv("KEBOOLA_WORKSPACE");
        if (workspace != null && !workspace.isEmpty()) {
            props.setProperty("workspace", workspace);
        }

        String branch = System.getenv("KEBOOLA_BRANCH");
        if (branch != null && !branch.isEmpty()) {
            props.setProperty("branch", branch);
        }

        String url = "jdbc:keboola://" + host;
        connection = DriverManager.getConnection(url, props);
        assertNotNull(connection, "Connection should not be null");
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (connection != null && !connection.isClosed()) {
            connection.close();
        }
    }

    // =========================================================================
    // Connection tests
    // =========================================================================

    @Test
    @Order(1)
    void connection_isValid() throws SQLException {
        assertTrue(connection.isValid(5));
        assertFalse(connection.isClosed());
    }

    @Test
    @Order(2)
    void connection_hasCatalog() throws SQLException {
        String catalog = connection.getCatalog();
        assertNotNull(catalog, "Catalog (project name) should not be null");
        assertFalse(catalog.isEmpty(), "Catalog should not be empty");
        System.out.println("  Catalog (project): " + catalog);
    }

    @Test
    @Order(3)
    void connection_autoCommitIsTrue() throws SQLException {
        assertTrue(connection.getAutoCommit(), "Auto-commit should be true by default");
    }

    // =========================================================================
    // DatabaseMetaData tests
    // =========================================================================

    @Test
    @Order(10)
    void metadata_driverInfo() throws SQLException {
        DatabaseMetaData meta = connection.getMetaData();
        assertNotNull(meta);
        assertEquals("Keboola JDBC Driver", meta.getDriverName());
        assertTrue(meta.getDriverMajorVersion() >= 1);
        System.out.println("  Driver: " + meta.getDriverName() + " " + meta.getDriverVersion());
    }

    @Test
    @Order(11)
    void metadata_getSchemas_returnsAtLeastOne() throws SQLException {
        DatabaseMetaData meta = connection.getMetaData();
        List<String> schemas = new ArrayList<>();

        try (ResultSet rs = meta.getSchemas()) {
            while (rs.next()) {
                schemas.add(rs.getString("TABLE_SCHEM"));
            }
        }

        assertFalse(schemas.isEmpty(), "Should have at least one schema (bucket)");
        System.out.println("  Schemas: " + schemas);
    }

    @Test
    @Order(12)
    void metadata_getTables_returnsResults() throws SQLException {
        DatabaseMetaData meta = connection.getMetaData();
        List<String> tables = new ArrayList<>();

        try (ResultSet rs = meta.getTables(null, null, null, null)) {
            while (rs.next()) {
                String schema = rs.getString("TABLE_SCHEM");
                String table = rs.getString("TABLE_NAME");
                tables.add(schema + "." + table);
            }
        }

        assertFalse(tables.isEmpty(), "Should have at least one table");
        System.out.println("  Tables (" + tables.size() + "): " + tables.subList(0, Math.min(5, tables.size())) + "...");
    }

    @Test
    @Order(13)
    void metadata_getColumns_returnsColumnsForFirstTable() throws SQLException {
        DatabaseMetaData meta = connection.getMetaData();

        // Find the first table
        String firstSchema = null;
        String firstTable = null;
        try (ResultSet rs = meta.getTables(null, null, null, null)) {
            if (rs.next()) {
                firstSchema = rs.getString("TABLE_SCHEM");
                firstTable = rs.getString("TABLE_NAME");
            }
        }
        assertNotNull(firstTable, "Need at least one table for column test");

        List<String> columns = new ArrayList<>();
        try (ResultSet rs = meta.getColumns(null, firstSchema, firstTable, null)) {
            while (rs.next()) {
                String colName = rs.getString("COLUMN_NAME");
                String typeName = rs.getString("TYPE_NAME");
                columns.add(colName + " (" + typeName + ")");
            }
        }

        assertFalse(columns.isEmpty(), "Table should have at least one column");
        System.out.println("  Columns of " + firstSchema + "." + firstTable + ": " + columns);
    }

    @Test
    @Order(14)
    void metadata_getCatalogs_returnsSomething() throws SQLException {
        DatabaseMetaData meta = connection.getMetaData();
        try (ResultSet rs = meta.getCatalogs()) {
            assertTrue(rs.next(), "Should have at least one catalog");
            String catalog = rs.getString("TABLE_CAT");
            assertNotNull(catalog);
            System.out.println("  Catalog: " + catalog);
        }
    }

    // =========================================================================
    // SQL execution tests
    // =========================================================================

    @Test
    @Order(20)
    void statement_selectLiteral() throws SQLException {
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT 1 AS num, 'hello' AS greeting")) {

            assertTrue(rs.next(), "Should have one row");
            assertEquals("1", rs.getString("num"));
            assertEquals("hello", rs.getString("greeting"));
            assertFalse(rs.next(), "Should have only one row");
        }
    }

    @Test
    @Order(21)
    void statement_selectWithMultipleRows() throws SQLException {
        // Use UNION ALL to create multiple rows without depending on table data
        String sql = "SELECT 1 AS id, 'a' AS val UNION ALL SELECT 2, 'b' UNION ALL SELECT 3, 'c'";
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            List<String> values = new ArrayList<>();
            while (rs.next()) {
                values.add(rs.getString("id") + "=" + rs.getString("val"));
            }
            assertEquals(3, values.size(), "Should have 3 rows");
            System.out.println("  Rows: " + values);
        }
    }

    @Test
    @Order(22)
    void statement_resultSetMetaData() throws SQLException {
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT 42 AS num, 'test' AS str, CURRENT_DATE() AS dt")) {

            ResultSetMetaData rsMeta = rs.getMetaData();
            assertNotNull(rsMeta);
            assertEquals(3, rsMeta.getColumnCount());
            assertEquals("NUM", rsMeta.getColumnName(1).toUpperCase());
            assertEquals("STR", rsMeta.getColumnName(2).toUpperCase());
        }
    }

    @Test
    @Order(23)
    void statement_showTables() throws SQLException {
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SHOW TABLES")) {

            int count = 0;
            while (rs.next()) {
                count++;
            }
            assertTrue(count >= 0, "SHOW TABLES should execute without error");
            System.out.println("  SHOW TABLES returned " + count + " rows");
        }
    }

    @Test
    @Order(24)
    void statement_executeReturnsFalseForDDL() throws SQLException {
        // SHOW commands internally behave as queries but let's test execute() boolean return
        try (Statement stmt = connection.createStatement()) {
            boolean hasResultSet = stmt.execute("SELECT 1");
            assertTrue(hasResultSet, "SELECT should return true from execute()");
        }
    }

    // =========================================================================
    // USE SCHEMA interception tests
    // =========================================================================

    @Test
    @Order(30)
    void useSchema_setsCurrentSchema() throws SQLException {
        String schema = discoverFirstSchema();
        Assumptions.assumeTrue(schema != null, "Need at least one schema");

        try (Statement stmt = connection.createStatement()) {
            stmt.execute("USE SCHEMA \"" + schema + "\"");
            assertEquals(schema, connection.getSchema());
        } finally {
            connection.setSchema(null);
        }
    }

    @Test
    @Order(31)
    void useSchema_caseInsensitive() throws SQLException {
        String schema = discoverFirstSchema();
        Assumptions.assumeTrue(schema != null, "Need at least one schema");

        try (Statement stmt = connection.createStatement()) {
            stmt.execute("use schema \"" + schema + "\"");
            assertEquals(schema, connection.getSchema());
        } finally {
            connection.setSchema(null);
        }
    }

    private String discoverFirstSchema() throws SQLException {
        try (ResultSet rs = connection.getMetaData().getSchemas()) {
            while (rs.next()) {
                String name = rs.getString("TABLE_SCHEM");
                // Skip system/virtual schemas that cannot be USEd in Snowflake
                if ("INFORMATION_SCHEMA".equals(name) || "_keboola".equals(name)) {
                    continue;
                }
                return name;
            }
        }
        return null;
    }

    @Test
    @Order(32)
    void useSchema_thenSelectWithoutQualification() throws SQLException {
        // Find a real (non-virtual, non-system) table from metadata to test against
        String schema = null;
        String table = null;
        try (ResultSet rs = connection.getMetaData().getTables(null, null, null, null)) {
            while (rs.next()) {
                String s = rs.getString("TABLE_SCHEM");
                // Skip virtual and system schemas
                if ("_keboola".equals(s) || "INFORMATION_SCHEMA".equals(s)) {
                    continue;
                }
                schema = s;
                table = rs.getString("TABLE_NAME");
                break;
            }
        }
        Assumptions.assumeTrue(schema != null && table != null,
                "Need at least one table in the project to test USE SCHEMA");

        try (Statement stmt = connection.createStatement()) {
            // Set schema via USE SCHEMA
            stmt.execute("USE SCHEMA \"" + schema + "\"");
            assertEquals(schema, connection.getSchema());

            // Query with unqualified table name - Snowflake should resolve it
            try (ResultSet rs = stmt.executeQuery("SELECT * FROM \"" + table + "\" LIMIT 1")) {
                assertNotNull(rs.getMetaData(), "ResultSet metadata should be available");
            }
        } finally {
            connection.setSchema(null);
        }
    }

    @Test
    @Order(33)
    void setSchema_thenSelectWithoutQualification() throws SQLException {
        // Same test but using Connection.setSchema() API instead of SQL USE SCHEMA
        String schema = null;
        String table = null;
        try (ResultSet rs = connection.getMetaData().getTables(null, null, null, null)) {
            while (rs.next()) {
                String s = rs.getString("TABLE_SCHEM");
                if ("_keboola".equals(s) || "INFORMATION_SCHEMA".equals(s)) {
                    continue;
                }
                schema = s;
                table = rs.getString("TABLE_NAME");
                break;
            }
        }
        Assumptions.assumeTrue(schema != null && table != null,
                "Need at least one table in the project to test setSchema");

        try {
            // Set schema via JDBC API
            connection.setSchema(schema);
            assertEquals(schema, connection.getSchema());

            // Query with unqualified table name
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT * FROM \"" + table + "\" LIMIT 1")) {
                assertNotNull(rs.getMetaData(), "ResultSet metadata should be available");
            }
        } finally {
            connection.setSchema(null);
        }
    }

    // =========================================================================
    // Session persistence tests (SET variables across execute() calls)
    // =========================================================================

    @Test
    @Order(34)
    void sessionVariable_persistsAcrossExecuteCalls() throws SQLException {
        // SET a variable in one execute() call, then SELECT it in another.
        // This only works if the Query Service session persists across jobs.
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("SET JDBC_TEST_VAR = 42");

            // Separate execute() call — different job, but same session
            try (ResultSet rs = stmt.executeQuery("SELECT $JDBC_TEST_VAR AS val")) {
                assertTrue(rs.next(), "Should have a result row");
                assertEquals("42", rs.getString("val"));
            }
        }
    }

    @Test
    @Order(35)
    void sessionVariable_multiStatementInOneCall() throws SQLException {
        // SET and SELECT in the same execute() call (semicolon-separated)
        try (Statement stmt = connection.createStatement()) {
            boolean hasRs = stmt.execute("SET JDBC_TEST_MULTI = 99; SELECT $JDBC_TEST_MULTI AS val");
            assertTrue(hasRs, "Last statement is SELECT, should return ResultSet");

            try (ResultSet rs = stmt.getResultSet()) {
                assertTrue(rs.next());
                assertEquals("99", rs.getString("val"));
            }
        }
    }

    @Test
    @Order(36)
    void sessionVariable_persistsAcrossStatements() throws SQLException {
        // SET on one Statement instance, read on another — both share the connection session
        try (Statement stmt1 = connection.createStatement()) {
            stmt1.execute("SET JDBC_TEST_CROSS = 'hello'");
        }

        try (Statement stmt2 = connection.createStatement();
             ResultSet rs = stmt2.executeQuery("SELECT $JDBC_TEST_CROSS AS val")) {
            assertTrue(rs.next());
            assertEquals("hello", rs.getString("val"));
        }
    }

    // =========================================================================
    // PreparedStatement tests
    // =========================================================================

    @Test
    @Order(40)
    void preparedStatement_withStringParameter() throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT ? AS val")) {
            ps.setString(1, "hello world");
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("hello world", rs.getString("val"));
            }
        }
    }

    @Test
    @Order(41)
    void preparedStatement_withIntParameter() throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT ? AS num")) {
            ps.setInt(1, 42);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("42", rs.getString("num"));
            }
        }
    }

    @Test
    @Order(42)
    void preparedStatement_withNullParameter() throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT ? AS val")) {
            ps.setNull(1, java.sql.Types.VARCHAR);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertNull(rs.getString("val"));
            }
        }
    }

    @Test
    @Order(43)
    void preparedStatement_withMultipleParameters() throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT ? AS a, ? AS b, ? AS c")) {
            ps.setString(1, "foo");
            ps.setInt(2, 99);
            ps.setBoolean(3, true);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("foo", rs.getString("a"));
                assertEquals("99", rs.getString("b"));
            }
        }
    }

    @Test
    @Order(44)
    void preparedStatement_withSpecialCharacters() throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT ? AS val")) {
            ps.setString(1, "it's a \"test\" with 'quotes'");
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("it's a \"test\" with 'quotes'", rs.getString("val"));
            }
        }
    }

    // =========================================================================
    // Real table query tests (depend on data in the project)
    // =========================================================================

    @Test
    @Order(50)
    void query_realTableIfExists() throws SQLException {
        // Try to find any table and query it
        DatabaseMetaData meta = connection.getMetaData();
        String schema = null;
        String table = null;

        try (ResultSet rs = meta.getTables(null, null, null, null)) {
            if (rs.next()) {
                schema = rs.getString("TABLE_SCHEM");
                table = rs.getString("TABLE_NAME");
            }
        }

        Assumptions.assumeTrue(table != null, "No tables found in project, skipping real table query");

        String sql = "SELECT * FROM \"" + schema + "\".\"" + table + "\" LIMIT 5";
        System.out.println("  Querying: " + sql);

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            ResultSetMetaData rsMeta = rs.getMetaData();
            int colCount = rsMeta.getColumnCount();
            assertTrue(colCount > 0, "Table should have at least one column");

            int rowCount = 0;
            while (rs.next()) {
                rowCount++;
            }
            System.out.println("  Returned " + rowCount + " rows, " + colCount + " columns");
            assertTrue(rowCount >= 0);
        }
    }

    // =========================================================================
    // Error handling tests
    // =========================================================================

    @Test
    @Order(60)
    void statement_invalidSQL_throwsSQLException() {
        assertThrows(SQLException.class, () -> {
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("THIS IS NOT VALID SQL AT ALL!!!");
            }
        });
    }

    @Test
    @Order(61)
    void statement_selectFromNonexistentTable_throwsSQLException() {
        assertThrows(SQLException.class, () -> {
            try (Statement stmt = connection.createStatement()) {
                stmt.executeQuery("SELECT * FROM \"nonexistent_schema_xyz\".\"nonexistent_table_xyz\"");
            }
        });
    }

    // =========================================================================
    // Connection re-creation test
    // =========================================================================

    @Test
    @Order(70)
    void connection_canCreateSecondConnection() throws Exception {
        Properties props = new Properties();
        props.setProperty("token", token);

        String workspace = System.getenv("KEBOOLA_WORKSPACE");
        if (workspace != null && !workspace.isEmpty()) {
            props.setProperty("workspace", workspace);
        }

        String branch = System.getenv("KEBOOLA_BRANCH");
        if (branch != null && !branch.isEmpty()) {
            props.setProperty("branch", branch);
        }

        try (Connection conn2 = DriverManager.getConnection("jdbc:keboola://" + host, props)) {
            assertTrue(conn2.isValid(5));
            assertNotNull(conn2.getCatalog());

            // Both connections should work independently
            try (Statement stmt1 = connection.createStatement();
                 Statement stmt2 = conn2.createStatement();
                 ResultSet rs1 = stmt1.executeQuery("SELECT 'conn1' AS src");
                 ResultSet rs2 = stmt2.executeQuery("SELECT 'conn2' AS src")) {

                assertTrue(rs1.next());
                assertTrue(rs2.next());
                assertEquals("conn1", rs1.getString(1));
                assertEquals("conn2", rs2.getString(1));
            }
        }
    }

    // =========================================================================
    // Statement lifecycle tests
    // =========================================================================

    @Test
    @Order(80)
    void statement_closeAndReuse() throws SQLException {
        Statement stmt = connection.createStatement();
        assertFalse(stmt.isClosed());

        stmt.close();
        assertTrue(stmt.isClosed());

        // Creating a new statement should work fine
        try (Statement stmt2 = connection.createStatement();
             ResultSet rs = stmt2.executeQuery("SELECT 'still works' AS status")) {
            assertTrue(rs.next());
            assertEquals("still works", rs.getString(1));
        }
    }

    @Test
    @Order(81)
    void resultSet_getByColumnIndex() throws SQLException {
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT 'a' AS col1, 'b' AS col2, 'c' AS col3")) {
            assertTrue(rs.next());
            assertEquals("a", rs.getString(1));
            assertEquals("b", rs.getString(2));
            assertEquals("c", rs.getString(3));
        }
    }

    @Test
    @Order(82)
    void resultSet_wasNull() throws SQLException {
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT NULL AS nullable_col")) {
            assertTrue(rs.next());
            assertNull(rs.getString(1));
            assertTrue(rs.wasNull(), "wasNull() should return true after reading NULL");
        }
    }

    // =========================================================================
    // QUERY_TAG verification (E2E)
    // =========================================================================

    @Test
    @Order(100)
    void queryTag_mergesDriverKeysIntoQueryServiceTag() throws Exception {
        // Run twice: Query Service sets QUERY_TAG per job, so every job must carry the driver keys.
        for (int i = 0; i < 2; i++) {
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT CURRENT_QUERY_TAG()")) {
                assertTrue(rs.next());
                String rawTag = rs.getString(1);
                assertNotNull(rawTag, "QUERY_TAG value should not be null");

                JsonNode tag = new ObjectMapper().readTree(rawTag);
                assertEquals("query-service", tag.path("keboola_service").asText(),
                        "Query Service keys must stay in the tag, was: " + rawTag);
                assertEquals("jdbc-driver", tag.path("client").asText(),
                        "QUERY_TAG client key missing, was: " + rawTag);
                assertTrue(tag.hasNonNull("tokenId") && !tag.get("tokenId").asText().isEmpty(),
                        "QUERY_TAG must carry a tokenId for attribution, was: " + rawTag);
                assertTrue(tag.hasNonNull("projectId"),
                        "QUERY_TAG must carry a projectId for attribution, was: " + rawTag);
            }
        }
    }
}
