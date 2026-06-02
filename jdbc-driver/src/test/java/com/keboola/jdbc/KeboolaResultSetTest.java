package com.keboola.jdbc;

import com.keboola.jdbc.config.DriverConfig;
import com.keboola.jdbc.http.QueryServiceClient;
import com.keboola.jdbc.http.model.QueryResult;
import com.keboola.jdbc.http.model.ResultColumn;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KeboolaResultSetTest {

    @Mock
    private QueryServiceClient client;

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private static ResultColumn col(String name, String type) {
        return new ResultColumn(name, type, true, null);
    }

    private static List<ResultColumn> cols(String... typedNames) {
        List<ResultColumn> list = new ArrayList<>();
        for (String n : typedNames) {
            // format "name:TYPE"
            String[] parts = n.split(":", 2);
            list.add(col(parts[0], parts.length > 1 ? parts[1] : "VARCHAR"));
        }
        return list;
    }

    @SafeVarargs
    private static List<List<String>> rows(List<String>... rowsArr) {
        return new ArrayList<>(Arrays.asList(rowsArr));
    }

    private static QueryResult page(List<ResultColumn> columns, List<List<String>> data) {
        return new QueryResult("completed", columns, data, 0, data.size());
    }

    /** Build a page with exactly PAGE_SIZE rows (triggers hasMorePages=true). */
    private static List<List<String>> fullPage(int columnCount) {
        List<List<String>> rows = new ArrayList<>();
        for (int i = 0; i < DriverConfig.DEFAULT_PAGE_SIZE; i++) {
            List<String> row = new ArrayList<>();
            for (int c = 0; c < columnCount; c++) {
                row.add("r" + i + "c" + c);
            }
            rows.add(row);
        }
        return rows;
    }

    private KeboolaResultSet newRs(List<ResultColumn> columns, List<List<String>> data) {
        return new KeboolaResultSet(client, "job-1", "stmt-1", page(columns, data));
    }

    // ---------------------------------------------------------------------
    // Construction & basic state
    // ---------------------------------------------------------------------

    @Test
    void construction_singlePage_iteratesThenReturnsFalse() throws Exception {
        KeboolaResultSet rs = newRs(
                cols("id:INTEGER", "name:VARCHAR"),
                rows(Arrays.asList("1", "alice"), Arrays.asList("2", "bob"))
        );

        assertTrue(rs.next());
        assertTrue(rs.next());
        assertFalse(rs.next(), "Third next() should return false (single page exhausted)");
    }

    @Test
    void construction_emptyResult_nextReturnsFalseImmediately() throws Exception {
        KeboolaResultSet rs = newRs(cols("id:INTEGER"), rows());

        assertFalse(rs.next());
    }

    @Test
    void getMetaData_returnsNonNullMetadata() throws Exception {
        KeboolaResultSet rs = newRs(cols("id:INTEGER", "name:VARCHAR"), rows());

        assertNotNull(rs.getMetaData());
        assertEquals(2, rs.getMetaData().getColumnCount());
    }

    // ---------------------------------------------------------------------
    // Paging
    // ---------------------------------------------------------------------

    @Test
    void next_acrossPageBoundary_fetchesNextPage() throws Exception {
        List<ResultColumn> columns = cols("n:INTEGER");
        // First page: PAGE_SIZE rows -> hasMorePages = true
        KeboolaResultSet rs = newRs(columns, fullPage(1));

        // Second page: 3 rows, less than PAGE_SIZE -> hasMorePages = false after
        QueryResult secondPage = page(columns, rows(
                Collections.singletonList("a"),
                Collections.singletonList("b"),
                Collections.singletonList("c")
        ));
        when(client.fetchResults(anyString(), anyString(), anyInt(), anyInt())).thenReturn(secondPage);

        // Consume all rows on page 1
        for (int i = 0; i < DriverConfig.DEFAULT_PAGE_SIZE; i++) {
            assertTrue(rs.next(), "Row " + i + " should be available on first page");
        }
        // Now next() should trigger fetchNextPage and return the first row of page 2
        assertTrue(rs.next());
        assertEquals("a", rs.getString(1));
        assertTrue(rs.next());
        assertEquals("b", rs.getString(1));
        assertTrue(rs.next());
        assertEquals("c", rs.getString(1));
        assertFalse(rs.next(), "After page 2 exhausted, no more rows");

        verify(client, times(1)).fetchResults(anyString(), anyString(), anyInt(), anyInt());
    }

    @Test
    void next_fetchesNextPageWithCorrectOffset() throws Exception {
        List<ResultColumn> columns = cols("n:INTEGER");
        KeboolaResultSet rs = newRs(columns, fullPage(1));
        when(client.fetchResults(anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn(page(columns, rows()));

        // Consume page 1 then trigger fetch
        for (int i = 0; i < DriverConfig.DEFAULT_PAGE_SIZE; i++) rs.next();
        rs.next();

        // Offset should equal PAGE_SIZE (rows already consumed)
        verify(client).fetchResults("job-1", "stmt-1", DriverConfig.DEFAULT_PAGE_SIZE, DriverConfig.DEFAULT_PAGE_SIZE);
    }

    @Test
    void next_smallFirstPage_doesNotFetchNext() throws Exception {
        // First page has fewer rows than PAGE_SIZE -> hasMorePages=false
        KeboolaResultSet rs = newRs(
                cols("n:INTEGER"),
                rows(Collections.singletonList("1"), Collections.singletonList("2"))
        );

        while (rs.next()) {
            // consume
        }

        verify(client, never()).fetchResults(anyString(), anyString(), anyInt(), anyInt());
    }

    @Test
    void next_emptySecondPage_returnsFalseGracefully() throws Exception {
        List<ResultColumn> columns = cols("n:INTEGER");
        KeboolaResultSet rs = newRs(columns, fullPage(1));
        when(client.fetchResults(anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn(page(columns, rows()));

        for (int i = 0; i < DriverConfig.DEFAULT_PAGE_SIZE; i++) rs.next();
        assertFalse(rs.next(), "Empty second page should yield false");
    }

    @Test
    void next_fetchFailure_wrappedAsSQLException() throws Exception {
        List<ResultColumn> columns = cols("n:INTEGER");
        KeboolaResultSet rs = newRs(columns, fullPage(1));
        when(client.fetchResults(anyString(), anyString(), anyInt(), anyInt()))
                .thenThrow(new RuntimeException("network exploded"));

        for (int i = 0; i < DriverConfig.DEFAULT_PAGE_SIZE; i++) rs.next();
        SQLException ex = assertThrows(SQLException.class, rs::next);
        assertTrue(ex.getMessage().contains("Failed to fetch"), ex.getMessage());
    }

    // ---------------------------------------------------------------------
    // Type getters — happy path by index
    // ---------------------------------------------------------------------

    @Test
    void getString_returnsRawValue() throws Exception {
        KeboolaResultSet rs = newRs(cols("s:VARCHAR"), rows(Collections.singletonList("hello")));
        assertTrue(rs.next());
        assertEquals("hello", rs.getString(1));
    }

    @Test
    void getInt_parsesValue() throws Exception {
        KeboolaResultSet rs = newRs(cols("n:INTEGER"), rows(Collections.singletonList("42")));
        assertTrue(rs.next());
        assertEquals(42, rs.getInt(1));
    }

    @Test
    void getLong_parsesValue() throws Exception {
        KeboolaResultSet rs = newRs(cols("n:BIGINT"),
                rows(Collections.singletonList("9223372036854775807")));
        assertTrue(rs.next());
        assertEquals(Long.MAX_VALUE, rs.getLong(1));
    }

    @Test
    void getDouble_parsesValue() throws Exception {
        KeboolaResultSet rs = newRs(cols("n:FLOAT"), rows(Collections.singletonList("3.14")));
        assertTrue(rs.next());
        assertEquals(3.14, rs.getDouble(1), 0.0001);
    }

    @Test
    void getFloat_parsesValue() throws Exception {
        KeboolaResultSet rs = newRs(cols("n:FLOAT"), rows(Collections.singletonList("1.5")));
        assertTrue(rs.next());
        assertEquals(1.5f, rs.getFloat(1), 0.0001f);
    }

    @Test
    void getShort_parsesValue() throws Exception {
        KeboolaResultSet rs = newRs(cols("n:SMALLINT"), rows(Collections.singletonList("17")));
        assertTrue(rs.next());
        assertEquals((short) 17, rs.getShort(1));
    }

    @Test
    void getBoolean_trueLiteral_returnsTrue() throws Exception {
        KeboolaResultSet rs = newRs(cols("b:BOOLEAN"), rows(Collections.singletonList("true")));
        assertTrue(rs.next());
        assertTrue(rs.getBoolean(1));
    }

    @Test
    void getBoolean_oneLiteral_returnsTrue() throws Exception {
        KeboolaResultSet rs = newRs(cols("b:BOOLEAN"), rows(Collections.singletonList("1")));
        assertTrue(rs.next());
        assertTrue(rs.getBoolean(1));
    }

    @Test
    void getBoolean_falseLiteral_returnsFalse() throws Exception {
        KeboolaResultSet rs = newRs(cols("b:BOOLEAN"), rows(Collections.singletonList("false")));
        assertTrue(rs.next());
        assertFalse(rs.getBoolean(1));
    }

    @Test
    void getBigDecimal_parsesValue() throws Exception {
        KeboolaResultSet rs = newRs(cols("n:NUMBER"), rows(Collections.singletonList("123.45")));
        assertTrue(rs.next());
        assertEquals(new BigDecimal("123.45"), rs.getBigDecimal(1));
    }

    @Test
    void getBytes_returnsUtf8Bytes() throws Exception {
        KeboolaResultSet rs = newRs(cols("s:VARCHAR"), rows(Collections.singletonList("abc")));
        assertTrue(rs.next());
        assertArrayEquals(new byte[]{'a', 'b', 'c'}, rs.getBytes(1));
    }

    @Test
    void getObject_varcharType_returnsString() throws Exception {
        KeboolaResultSet rs = newRs(cols("s:VARCHAR"), rows(Collections.singletonList("hello")));
        assertTrue(rs.next());
        assertEquals("hello", rs.getObject(1));
    }

    // ---------------------------------------------------------------------
    // Null handling + wasNull
    // ---------------------------------------------------------------------

    @Test
    void getString_null_returnsNullAndSetsWasNull() throws Exception {
        KeboolaResultSet rs = newRs(cols("s:VARCHAR"),
                rows(Collections.singletonList(null)));
        assertTrue(rs.next());
        assertNull(rs.getString(1));
        assertTrue(rs.wasNull());
    }

    @Test
    void getInt_null_returnsZeroAndSetsWasNull() throws Exception {
        KeboolaResultSet rs = newRs(cols("n:INTEGER"),
                rows(Collections.singletonList(null)));
        assertTrue(rs.next());
        assertEquals(0, rs.getInt(1));
        assertTrue(rs.wasNull());
    }

    @Test
    void getLong_null_returnsZero() throws Exception {
        KeboolaResultSet rs = newRs(cols("n:BIGINT"),
                rows(Collections.singletonList(null)));
        assertTrue(rs.next());
        assertEquals(0L, rs.getLong(1));
        assertTrue(rs.wasNull());
    }

    @Test
    void getDouble_null_returnsZero() throws Exception {
        KeboolaResultSet rs = newRs(cols("n:FLOAT"),
                rows(Collections.singletonList(null)));
        assertTrue(rs.next());
        assertEquals(0.0, rs.getDouble(1), 0.0);
        assertTrue(rs.wasNull());
    }

    @Test
    void getBoolean_null_returnsFalse() throws Exception {
        KeboolaResultSet rs = newRs(cols("b:BOOLEAN"),
                rows(Collections.singletonList(null)));
        assertTrue(rs.next());
        assertFalse(rs.getBoolean(1));
        assertTrue(rs.wasNull());
    }

    @Test
    void getBigDecimal_null_returnsNull() throws Exception {
        KeboolaResultSet rs = newRs(cols("n:NUMBER"),
                rows(Collections.singletonList(null)));
        assertTrue(rs.next());
        assertNull(rs.getBigDecimal(1));
        assertTrue(rs.wasNull());
    }

    @Test
    void wasNull_afterNonNullRead_returnsFalse() throws Exception {
        KeboolaResultSet rs = newRs(cols("s:VARCHAR"),
                rows(Collections.singletonList("not-null")));
        assertTrue(rs.next());
        rs.getString(1);
        assertFalse(rs.wasNull());
    }

    // ---------------------------------------------------------------------
    // Getters by column name
    // ---------------------------------------------------------------------

    @Test
    void getString_byColumnName_resolvesCaseInsensitively() throws Exception {
        KeboolaResultSet rs = newRs(cols("FirstName:VARCHAR"),
                rows(Collections.singletonList("alice")));
        assertTrue(rs.next());
        assertEquals("alice", rs.getString("firstname"));
        assertEquals("alice", rs.getString("FIRSTNAME"));
    }

    @Test
    void getInt_byColumnName_returnsValue() throws Exception {
        KeboolaResultSet rs = newRs(cols("count:INTEGER"),
                rows(Collections.singletonList("5")));
        assertTrue(rs.next());
        assertEquals(5, rs.getInt("count"));
    }

    @Test
    void getString_byUnknownColumnName_throwsSQLException() throws Exception {
        KeboolaResultSet rs = newRs(cols("a:VARCHAR"),
                rows(Collections.singletonList("x")));
        assertTrue(rs.next());
        SQLException ex = assertThrows(SQLException.class, () -> rs.getString("nope"));
        assertTrue(ex.getMessage().contains("nope"));
    }

    // ---------------------------------------------------------------------
    // Index bounds & parsing errors
    // ---------------------------------------------------------------------

    @Test
    void getString_indexZero_throwsSQLException() throws Exception {
        KeboolaResultSet rs = newRs(cols("a:VARCHAR"),
                rows(Collections.singletonList("x")));
        assertTrue(rs.next());
        assertThrows(SQLException.class, () -> rs.getString(0));
    }

    @Test
    void getString_indexBeyondColumnCount_throwsSQLException() throws Exception {
        KeboolaResultSet rs = newRs(cols("a:VARCHAR"),
                rows(Collections.singletonList("x")));
        assertTrue(rs.next());
        assertThrows(SQLException.class, () -> rs.getString(99));
    }

    @Test
    void getInt_nonNumericValue_throwsSQLException() throws Exception {
        KeboolaResultSet rs = newRs(cols("n:INTEGER"),
                rows(Collections.singletonList("not-a-number")));
        assertTrue(rs.next());
        assertThrows(SQLException.class, () -> rs.getInt(1));
    }

    @Test
    void getDouble_nonNumericValue_throwsSQLException() throws Exception {
        KeboolaResultSet rs = newRs(cols("n:FLOAT"),
                rows(Collections.singletonList("abc")));
        assertTrue(rs.next());
        assertThrows(SQLException.class, () -> rs.getDouble(1));
    }

    @Test
    void getBigDecimal_invalidValue_throwsSQLException() throws Exception {
        KeboolaResultSet rs = newRs(cols("n:NUMBER"),
                rows(Collections.singletonList("not-decimal")));
        assertTrue(rs.next());
        assertThrows(SQLException.class, () -> rs.getBigDecimal(1));
    }

    // ---------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------

    @Test
    void close_setsIsClosedTrue() throws Exception {
        KeboolaResultSet rs = newRs(cols("a:VARCHAR"), rows());
        assertFalse(rs.isClosed());
        rs.close();
        assertTrue(rs.isClosed());
    }

    @Test
    void close_calledTwice_isIdempotent() throws Exception {
        KeboolaResultSet rs = newRs(cols("a:VARCHAR"), rows());
        rs.close();
        rs.close(); // must not throw
        assertTrue(rs.isClosed());
    }

    @Test
    void next_afterClose_throwsSQLException() throws Exception {
        KeboolaResultSet rs = newRs(cols("a:VARCHAR"), rows());
        rs.close();
        assertThrows(SQLException.class, rs::next);
    }

    @Test
    void getString_afterClose_throwsSQLException() throws Exception {
        KeboolaResultSet rs = newRs(cols("a:VARCHAR"),
                rows(Collections.singletonList("x")));
        rs.next();
        rs.close();
        assertThrows(SQLException.class, () -> rs.getString(1));
    }

    @Test
    void getMetaData_afterClose_throwsSQLException() throws Exception {
        KeboolaResultSet rs = newRs(cols("a:VARCHAR"), rows());
        rs.close();
        assertThrows(SQLException.class, rs::getMetaData);
    }

    // ---------------------------------------------------------------------
    // ResultSet-level properties
    // ---------------------------------------------------------------------

    @Test
    void getType_returnsForwardOnly() throws Exception {
        KeboolaResultSet rs = newRs(cols("a:VARCHAR"), rows());
        assertEquals(ResultSet.TYPE_FORWARD_ONLY, rs.getType());
    }

    @Test
    void getConcurrency_returnsReadOnly() throws Exception {
        KeboolaResultSet rs = newRs(cols("a:VARCHAR"), rows());
        assertEquals(ResultSet.CONCUR_READ_ONLY, rs.getConcurrency());
    }

    @Test
    void getFetchDirection_returnsForward() throws Exception {
        KeboolaResultSet rs = newRs(cols("a:VARCHAR"), rows());
        assertEquals(ResultSet.FETCH_FORWARD, rs.getFetchDirection());
    }

    // ---------------------------------------------------------------------
    // Row state
    // ---------------------------------------------------------------------

    @Test
    void getString_beforeNext_throwsSQLException() {
        KeboolaResultSet rs = newRs(cols("a:VARCHAR"),
                rows(Collections.singletonList("x")));
        // No next() called yet -> not positioned on a row
        assertThrows(SQLException.class, () -> rs.getString(1));
    }
}
