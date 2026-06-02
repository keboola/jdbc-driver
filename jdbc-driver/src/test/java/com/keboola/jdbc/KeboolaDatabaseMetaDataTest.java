package com.keboola.jdbc;

import com.keboola.jdbc.config.DriverConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KeboolaDatabaseMetaDataTest {

    @Mock
    private KeboolaConnection connection;

    @Mock
    private Statement statement;

    private KeboolaDatabaseMetaData meta;

    @BeforeEach
    void setUp() throws Exception {
        meta = new KeboolaDatabaseMetaData(connection);
        lenient().when(connection.createStatement()).thenReturn(statement);
        lenient().when(connection.getCatalog()).thenReturn("CURRENT_DB");
    }

    // Helper: build a fake SHOW result with given columns and rows
    private static ResultSet showResult(List<String> columnNames, List<List<Object>> rows) {
        return new ArrayResultSet(columnNames, rows);
    }

    private static List<Object> row(Object... values) {
        return new ArrayList<>(Arrays.asList(values));
    }

    // ---------------------------------------------------------------------
    // Driver / database identity
    // ---------------------------------------------------------------------

    @Test
    void getDatabaseProductName_returnsKeboola() {
        assertEquals("Keboola", meta.getDatabaseProductName());
    }

    @Test
    void getDatabaseProductVersion_returnsDriverVersion() {
        assertEquals(DriverConfig.DRIVER_VERSION, meta.getDatabaseProductVersion());
    }

    @Test
    void getDriverName_returnsConfiguredName() {
        assertEquals(DriverConfig.DRIVER_NAME, meta.getDriverName());
    }

    @Test
    void getDriverVersion_returnsConfiguredVersion() {
        assertEquals(DriverConfig.DRIVER_VERSION, meta.getDriverVersion());
    }

    @Test
    void getDriverMajorVersion_returnsConfiguredMajor() {
        assertEquals(DriverConfig.MAJOR_VERSION, meta.getDriverMajorVersion());
    }

    @Test
    void getDriverMinorVersion_returnsConfiguredMinor() {
        assertEquals(DriverConfig.MINOR_VERSION, meta.getDriverMinorVersion());
    }

    @Test
    void getDatabaseMajorVersion_returnsConfiguredMajor() {
        assertEquals(DriverConfig.MAJOR_VERSION, meta.getDatabaseMajorVersion());
    }

    @Test
    void getDatabaseMinorVersion_returnsConfiguredMinor() {
        assertEquals(DriverConfig.MINOR_VERSION, meta.getDatabaseMinorVersion());
    }

    @Test
    void getJDBCMajorVersion_returns4() {
        assertEquals(4, meta.getJDBCMajorVersion());
    }

    @Test
    void getJDBCMinorVersion_returns2() {
        assertEquals(2, meta.getJDBCMinorVersion());
    }

    // ---------------------------------------------------------------------
    // getCatalogs (SHOW DATABASES + filtering + sort)
    // ---------------------------------------------------------------------

    @Test
    void getCatalogs_returnsSortedDatabases() throws Exception {
        ResultSet showRs = showResult(
                Collections.singletonList("name"),
                Arrays.asList(row("ZULU"), row("ALPHA"), row("MIKE"))
        );
        when(statement.executeQuery("SHOW DATABASES")).thenReturn(showRs);

        ResultSet result = meta.getCatalogs();

        List<String> dbs = new ArrayList<>();
        while (result.next()) {
            dbs.add(result.getString("TABLE_CAT"));
        }
        assertEquals(Arrays.asList("ALPHA", "MIKE", "ZULU"), dbs);
    }

    @Test
    void getCatalogs_filtersSystemDatabases() throws Exception {
        // DriverConfig.FILTERED_DATABASES = SNOWFLAKE, SNOWFLAKE_LEARNING_DB, SNOWFLAKE_SAMPLE_DATA
        ResultSet showRs = showResult(
                Collections.singletonList("name"),
                Arrays.asList(row("USER_DB"), row("SNOWFLAKE"), row("SNOWFLAKE_SAMPLE_DATA"))
        );
        when(statement.executeQuery("SHOW DATABASES")).thenReturn(showRs);

        ResultSet result = meta.getCatalogs();

        List<String> dbs = new ArrayList<>();
        while (result.next()) {
            dbs.add(result.getString("TABLE_CAT"));
        }
        assertEquals(Collections.singletonList("USER_DB"), dbs);
    }

    @Test
    void getCatalogs_emptyResult_returnsEmpty() throws Exception {
        ResultSet showRs = showResult(
                Collections.singletonList("name"),
                Collections.emptyList()
        );
        when(statement.executeQuery("SHOW DATABASES")).thenReturn(showRs);

        ResultSet result = meta.getCatalogs();

        assertFalse(result.next());
    }

    // ---------------------------------------------------------------------
    // getSchemas
    // ---------------------------------------------------------------------

    @Test
    void getSchemas_emptyCatalog_returnsEmpty() throws Exception {
        ResultSet result = meta.getSchemas("", null);

        assertFalse(result.next());
    }

    @Test
    void getSchemas_returnsRealSchemasAndVirtualKeboola() throws Exception {
        ResultSet showRs = showResult(
                Arrays.asList("name", "database_name"),
                Arrays.asList(row("PUBLIC", "CURRENT_DB"), row("MY_SCHEMA", "CURRENT_DB"))
        );
        when(statement.executeQuery(anyString())).thenReturn(showRs);

        ResultSet result = meta.getSchemas(null, null);

        List<String> schemas = new ArrayList<>();
        while (result.next()) {
            schemas.add(result.getString("TABLE_SCHEM"));
        }
        assertTrue(schemas.contains("_keboola"), "Virtual _keboola schema must be injected: " + schemas);
        assertTrue(schemas.contains("PUBLIC"), schemas.toString());
        assertTrue(schemas.contains("MY_SCHEMA"), schemas.toString());
    }

    @Test
    void getSchemas_filtersSchemasFromSystemDatabases() throws Exception {
        ResultSet showRs = showResult(
                Arrays.asList("name", "database_name"),
                Arrays.asList(row("PUBLIC", "USER_DB"), row("INFORMATION_SCHEMA", "SNOWFLAKE"))
        );
        when(statement.executeQuery(anyString())).thenReturn(showRs);

        ResultSet result = meta.getSchemas(null, null);

        List<String> schemas = new ArrayList<>();
        while (result.next()) {
            String s = result.getString("TABLE_SCHEM");
            String db = result.getString("TABLE_CATALOG");
            schemas.add(s + "@" + db);
        }
        assertFalse(schemas.contains("INFORMATION_SCHEMA@SNOWFLAKE"),
                "Schemas from filtered system DBs must not appear: " + schemas);
    }

    @Test
    void getSchemas_withPattern_includesLikeClause() throws Exception {
        ResultSet showRs = showResult(
                Arrays.asList("name", "database_name"),
                Collections.emptyList()
        );
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        when(statement.executeQuery(sqlCaptor.capture())).thenReturn(showRs);

        meta.getSchemas(null, "PUB%");

        String sql = sqlCaptor.getValue();
        assertTrue(sql.startsWith("SHOW SCHEMAS"), sql);
        assertTrue(sql.contains("LIKE 'PUB%'"), sql);
    }

    @Test
    void getSchemas_withCatalogPattern_includesInClause() throws Exception {
        ResultSet showRs = showResult(
                Arrays.asList("name", "database_name"),
                Collections.emptyList()
        );
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        when(statement.executeQuery(sqlCaptor.capture())).thenReturn(showRs);

        meta.getSchemas("MY_DB", null);

        assertTrue(sqlCaptor.getValue().contains("MY_DB"), sqlCaptor.getValue());
    }

    // ---------------------------------------------------------------------
    // getTables — picks the right SHOW command per requested type
    // ---------------------------------------------------------------------

    @Test
    void getTables_bothTablesAndViewsRequested_usesShowObjects() throws Exception {
        ResultSet showRs = showResult(
                Arrays.asList("name", "schema_name", "database_name", "kind", "comment"),
                Collections.emptyList()
        );
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        when(statement.executeQuery(sqlCaptor.capture())).thenReturn(showRs);

        meta.getTables(null, null, null, new String[]{"TABLE", "VIEW"});

        assertTrue(sqlCaptor.getValue().startsWith("SHOW OBJECTS"), sqlCaptor.getValue());
    }

    @Test
    void getTables_onlyViewsRequested_usesShowViews() throws Exception {
        ResultSet showRs = showResult(
                Arrays.asList("name", "schema_name", "database_name", "kind", "comment"),
                Collections.emptyList()
        );
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        when(statement.executeQuery(sqlCaptor.capture())).thenReturn(showRs);

        meta.getTables(null, null, null, new String[]{"VIEW"});

        assertTrue(sqlCaptor.getValue().startsWith("SHOW VIEWS"), sqlCaptor.getValue());
    }

    @Test
    void getTables_onlyTablesRequested_usesShowTables() throws Exception {
        ResultSet showRs = showResult(
                Arrays.asList("name", "schema_name", "database_name", "kind", "comment"),
                Collections.emptyList()
        );
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        when(statement.executeQuery(sqlCaptor.capture())).thenReturn(showRs);

        meta.getTables(null, null, null, new String[]{"TABLE"});

        assertTrue(sqlCaptor.getValue().startsWith("SHOW TABLES"), sqlCaptor.getValue());
    }

    @Test
    void getTables_tableNamePattern_isIncludedAsLike() throws Exception {
        ResultSet showRs = showResult(
                Arrays.asList("name", "schema_name", "database_name", "kind", "comment"),
                Collections.emptyList()
        );
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        when(statement.executeQuery(sqlCaptor.capture())).thenReturn(showRs);

        meta.getTables(null, null, "users%", new String[]{"TABLE"});

        assertTrue(sqlCaptor.getValue().contains("LIKE 'users%'"), sqlCaptor.getValue());
    }

    @Test
    void getTables_virtualKeboolaSchema_includesVirtualTablesWithoutQueryingSnowflake() throws Exception {
        // When schemaPattern == "_keboola" we must not call SHOW TABLES at all
        ResultSet result = meta.getTables(null, "_keboola", null, new String[]{"TABLE"});

        List<String> names = new ArrayList<>();
        while (result.next()) {
            names.add(result.getString("TABLE_NAME"));
        }
        assertFalse(names.isEmpty(), "Virtual _keboola tables must be returned");
        verify(statement, org.mockito.Mockito.never()).executeQuery(anyString());
    }

    // ---------------------------------------------------------------------
    // getColumns — JSON data_type parsing
    // ---------------------------------------------------------------------

    @Test
    void getColumns_parsesJsonDataTypeForVarchar() throws Exception {
        String varcharJson = "{\"type\":\"TEXT\",\"length\":255,\"byteLength\":1020,\"nullable\":true}";
        ResultSet showRs = showResult(
                Arrays.asList("database_name", "schema_name", "table_name", "column_name", "data_type"),
                Collections.singletonList(row("CURRENT_DB", "PUBLIC", "USERS", "EMAIL", varcharJson))
        );
        when(statement.executeQuery(anyString())).thenReturn(showRs);

        // Pass an explicit non-_keboola schema pattern to suppress virtual column injection.
        ResultSet result = meta.getColumns(null, "PUBLIC", null, null);

        assertTrue(result.next());
        assertEquals("EMAIL", result.getString("COLUMN_NAME"));
        assertEquals("USERS", result.getString("TABLE_NAME"));
    }

    @Test
    void getColumns_returnsEmpty_whenNoMatchingColumns() throws Exception {
        ResultSet showRs = showResult(
                Arrays.asList("database_name", "schema_name", "table_name", "column_name", "data_type"),
                Collections.emptyList()
        );
        when(statement.executeQuery(anyString())).thenReturn(showRs);

        // schemaPattern "PUBLIC" excludes virtual _keboola columns
        ResultSet result = meta.getColumns(null, "PUBLIC", null, null);

        assertFalse(result.next());
    }

    // ---------------------------------------------------------------------
    // Capability flags & quoting
    // ---------------------------------------------------------------------

    @Test
    void getIdentifierQuoteString_isDoubleQuote() {
        assertEquals("\"", meta.getIdentifierQuoteString());
    }

    @Test
    void isCatalogAtStart_isTrue() throws SQLException {
        assertTrue(meta.isCatalogAtStart());
    }

    @Test
    void getCatalogSeparator_isDot() throws SQLException {
        assertEquals(".", meta.getCatalogSeparator());
    }

    @Test
    void getConnection_returnsInjectedConnection() throws SQLException {
        assertNotNull(meta.getConnection());
        assertEquals(connection, meta.getConnection());
    }
}
