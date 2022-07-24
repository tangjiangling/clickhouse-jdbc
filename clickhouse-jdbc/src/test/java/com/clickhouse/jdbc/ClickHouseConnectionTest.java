package com.clickhouse.jdbc;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.UUID;

import com.clickhouse.client.ClickHouseUtils;
import com.clickhouse.client.config.ClickHouseClientOption;

import org.testng.Assert;
import org.testng.annotations.Test;

public class ClickHouseConnectionTest extends JdbcIntegrationTest {
    @Override
    public ClickHouseConnection newConnection(Properties properties) throws SQLException {
        return newDataSource(properties).getConnection();
    }

    @Test(groups = "integration")
    public void testCreateArray() throws SQLException {
        try (ClickHouseConnection conn = newConnection(new Properties())) {
            Array array = conn.createArrayOf("Array(Int8)", null);
            Assert.assertEquals(array.getArray(), new byte[0]);
        }
    }

    @Test(groups = "integration")
    public void testNonExistDatabase() throws Exception {
        String database = UUID.randomUUID().toString();
        Properties props = new Properties();
        props.setProperty(ClickHouseClientOption.DATABASE.getKey(), database);
        SQLException exp = null;
        try (ClickHouseConnection conn = newConnection(props)) {
            // do nothing
        }
        Assert.assertNull(exp, "Should not have SQLException even the database does not exist");

        try (ClickHouseConnection conn = newConnection(props)) {
            Assert.assertEquals(conn.getSchema(), database);
            try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery("select 1")) {
                Assert.fail("Should have SQLException");
            }
        } catch (SQLException e) {
            exp = e;
        }
        Assert.assertNotNull(exp, "Should have SQLException since the database does not exist");
        Assert.assertEquals(exp.getErrorCode(), 81);

        props.setProperty(JdbcConfig.PROP_CREATE_DATABASE, Boolean.TRUE.toString());
        try (ClickHouseConnection conn = newConnection(props)) {
            exp = null;
        }
        Assert.assertNull(exp, "Should not have SQLException because database will be created automatically");

        props.setProperty(JdbcConfig.PROP_CREATE_DATABASE, Boolean.FALSE.toString());
        try (ClickHouseConnection conn = newConnection(props)) {
            Assert.assertEquals(conn.getSchema(), database);
            try (Statement stmt = conn.createStatement();
                    ResultSet rs = stmt.executeQuery(
                            ClickHouseUtils.format("select * from system.databases where name='%s'", database))) {
                Assert.assertTrue(rs.next(), "Should have at least one record in system.databases table");
                Assert.assertEquals(rs.getString("name"), database);
                Assert.assertFalse(rs.next(), "Should have only one record in system.databases table");
                exp = new SQLException();
            }
        }
        Assert.assertNotNull(exp, "Should not have SQLException because the database has been created");
    }

    @Test(groups = "integration")
    public void testReadOnly() throws SQLException {
        Properties props = new Properties();
        props.setProperty("user", "dba");
        props.setProperty("password", "dba");
        try (Connection conn = newConnection(props); Statement stmt = conn.createStatement()) {
            Assert.assertFalse(conn.isReadOnly(), "Connection should NOT be readonly");
            Assert.assertFalse(stmt.execute(
                    "drop table if exists test_readonly; drop user if exists readonly1; drop user if exists readonly2; "
                            + "create table test_readonly(id String)engine=Memory; "
                            + "create user readonly1 IDENTIFIED WITH no_password SETTINGS readonly=1; "
                            + "create user readonly2 IDENTIFIED WITH no_password SETTINGS readonly=2; "
                            + "grant insert on test_readonly TO readonly1, readonly2"));
            conn.setReadOnly(false);
            Assert.assertFalse(conn.isReadOnly(), "Connection should NOT be readonly");
            conn.setReadOnly(true);
            Assert.assertTrue(conn.isReadOnly(), "Connection should be readonly");

            try (Statement s = conn.createStatement()) {
                SQLException exp = null;
                try {
                    s.execute("insert into test_readonly values('readonly1')");
                } catch (SQLException e) {
                    exp = e;
                }
                Assert.assertNotNull(exp, "Should fail with SQL exception");
                Assert.assertEquals(exp.getErrorCode(), 164);
            }

            conn.setReadOnly(false);
            Assert.assertFalse(conn.isReadOnly(), "Connection should NOT be readonly");

            try (Statement s = conn.createStatement()) {
                Assert.assertFalse(s.execute("insert into test_readonly values('readonly1')"));
            }
        }

        props.clear();
        props.setProperty("user", "readonly1");
        try (Connection conn = newConnection(props); Statement stmt = conn.createStatement()) {
            Assert.assertTrue(conn.isReadOnly(), "Connection should be readonly");
            conn.setReadOnly(true);
            Assert.assertTrue(conn.isReadOnly(), "Connection should be readonly");
            SQLException exp = null;
            try {
                stmt.execute("insert into test_readonly values('readonly1')");
            } catch (SQLException e) {
                exp = e;
            }
            Assert.assertNotNull(exp, "Should fail with SQL exception");
            Assert.assertEquals(exp.getErrorCode(), 164);

            exp = null;
            try {
                conn.setReadOnly(true);
                stmt.execute("set max_result_rows=5; select 1");
            } catch (SQLException e) {
                exp = e;
            }
            Assert.assertNotNull(exp, "Should fail with SQL exception");
            Assert.assertEquals(exp.getErrorCode(), 164);
        }

        props.setProperty("user", "readonly2");
        try (Connection conn = newConnection(props); Statement stmt = conn.createStatement()) {
            Assert.assertTrue(conn.isReadOnly(), "Connection should be readonly");
            Assert.assertTrue(stmt.execute("set max_result_rows=5; select 1"));

            Assert.assertThrows(SQLException.class, () -> conn.setReadOnly(false));
            Assert.assertTrue(conn.isReadOnly(), "Connection should be readonly");

            SQLException exp = null;
            try (Statement s = conn.createStatement()) {
                Assert.assertFalse(s.execute("insert into test_readonly values('readonly2')"));
            } catch (SQLException e) {
                exp = e;
            }
            Assert.assertNotNull(exp, "Should fail with SQL exception");
            Assert.assertEquals(exp.getErrorCode(), 164);

            conn.setReadOnly(true);
            Assert.assertTrue(conn.isReadOnly(), "Connection should be readonly");
        }

        props.setProperty(ClickHouseClientOption.SERVER_TIME_ZONE.getKey(), "UTC");
        props.setProperty(ClickHouseClientOption.SERVER_VERSION.getKey(), "21.8");
        try (Connection conn = newConnection(props); Statement stmt = conn.createStatement()) {
            Assert.assertFalse(conn.isReadOnly(), "Connection should NOT be readonly");
            Assert.assertTrue(stmt.execute("set max_result_rows=5; select 1"));

            conn.setReadOnly(true);
            Assert.assertTrue(conn.isReadOnly(), "Connection should be readonly");
            conn.setReadOnly(false);
            Assert.assertFalse(conn.isReadOnly(), "Connection should NOT be readonly");

            SQLException exp = null;
            try (Statement s = conn.createStatement()) {
                Assert.assertFalse(s.execute("insert into test_readonly values('readonly2')"));
            } catch (SQLException e) {
                exp = e;
            }
            Assert.assertNotNull(exp, "Should fail with SQL exception");
            Assert.assertEquals(exp.getErrorCode(), 164);
        }
    }

    @Test // (groups = "integration")
    public void testTransaction() throws Exception {
        testAutoCommit();
        testManualCommit();
        testNestedTransactions();
        testParallelTransactions();
    }

    @Test // (groups = "integration")
    public void testAutoCommit() throws Exception {
        Properties props = new Properties();
        props.setProperty("transactionSupport", "true");
        String tableName = "test_jdbc_tx_auto_commit";
        try (Connection c = newConnection(props); Statement s = c.createStatement()) {
            s.execute("drop table if exists " + tableName + "; "
                    + "create table " + tableName + "(id UInt64) engine=MergeTree order by id");
        }

        try (ClickHouseConnection conn = newConnection();
                ClickHouseConnection txConn = newConnection(props);
                Statement stmt = conn.createStatement();
                Statement txStmt = txConn.createStatement();
                PreparedStatement ps = conn.prepareStatement("insert into " + tableName);
                PreparedStatement txPs = txConn.prepareStatement("insert into " + tableName)) {
            Assert.assertTrue(conn.getAutoCommit());
            Assert.assertTrue(txConn.getAutoCommit());
            Assert.assertFalse(conn.isTransactionSupported());
            Assert.assertTrue(txConn.isTransactionSupported());
            Assert.assertFalse(conn.isImplicitTransactionSupported());
            if (txConn.getServerVersion().check("[22.7,)")) {
                Assert.assertTrue(txConn.isImplicitTransactionSupported(),
                        "Implicit transaction is supported since 22.7");
            } else {
                Assert.assertFalse(txConn.isImplicitTransactionSupported(),
                        "Implicit transaction is NOT supported before 22.7");
            }

            checkRowCount(stmt, "select 1", 1);
            checkRowCount(txStmt, "select 1", 1);

            txStmt.execute("drop table if exists " + tableName + "; "
                    + "create table " + tableName + "(id UInt64) engine=MergeTree order by id");
            checkRowCount(stmt, tableName, 0);
            checkRowCount(txStmt, tableName, 0);

            stmt.executeUpdate("insert into " + tableName + " values(1)");
            checkRowCount(stmt, tableName, 1);
            checkRowCount(txStmt, tableName, 1);

            txStmt.executeUpdate("insert into " + tableName + " values(2)");
            checkRowCount(stmt, tableName, 2);
            checkRowCount(txStmt, tableName, 2);

            try (Connection c = newConnection(props); Statement s = c.createStatement()) {
                c.setAutoCommit(false);
                s.executeUpdate("insert into " + tableName + " values(-1)");
                checkRowCount(stmt, tableName, 3);
                checkRowCount(txStmt, tableName, 2);
                checkRowCount(s, tableName, 3);
                c.rollback();
                checkRowCount(stmt, tableName, 2);
                checkRowCount(txStmt, tableName, 2);
                checkRowCount(s, tableName, 2);
            }
            checkRowCount(stmt, tableName, 2);
            checkRowCount(txStmt, tableName, 2);

            try (Connection c = newConnection(props); Statement s = c.createStatement()) {
                c.setAutoCommit(false);
                s.executeUpdate("insert into " + tableName + " values(-2)");
                checkRowCount(stmt, tableName, 3);
                checkRowCount(txStmt, tableName, 2);
                checkRowCount(s, tableName, 3);
            }
            checkRowCount(stmt, tableName, 3);
            checkRowCount(txStmt, tableName, 3);

            ps.setInt(1, 3);
            ps.addBatch();
            ps.setInt(1, 4);
            ps.addBatch();
            ps.executeBatch();
            checkRowCount(stmt, tableName, 5);
            checkRowCount(txStmt, tableName, 5);

            txPs.setInt(1, 5);
            txPs.addBatch();
            txPs.setInt(1, 6);
            txPs.addBatch();
            txPs.executeBatch();
            checkRowCount(stmt, tableName, 7);
            checkRowCount(txStmt, tableName, 7);
        }
    }

    @Test // (groups = "integration")
    public void testManualCommit() throws Exception {
        Properties props = new Properties();
        props.setProperty("autoCommit", "false");
        Properties txProps = new Properties();
        txProps.putAll(props);
        txProps.setProperty("transactionSupport", "true");
        String tableName = "test_jdbc_manual_tx";
        try (Connection c = newConnection(txProps); Statement s = c.createStatement()) {
            s.execute("drop table if exists " + tableName + "; "
                    + "create table " + tableName + "(id UInt64, value String) engine=MergeTree order by id");
        }

        try (ClickHouseConnection conn = newConnection(props);
                ClickHouseConnection txConn = newConnection(txProps);
                Statement stmt = conn.createStatement();
                Statement txStmt = txConn.createStatement();
                PreparedStatement ps = conn.prepareStatement("insert into " + tableName);
                PreparedStatement txPs = txConn.prepareStatement("insert into " + tableName)) {
            Assert.assertFalse(conn.getAutoCommit());
            Assert.assertFalse(txConn.getAutoCommit());
            Assert.assertFalse(conn.isTransactionSupported());
            Assert.assertTrue(txConn.isTransactionSupported());
            Assert.assertFalse(conn.isImplicitTransactionSupported());
            if (txConn.getServerVersion().check("[22.7,)")) {
                Assert.assertTrue(txConn.isImplicitTransactionSupported(),
                        "Implicit transaction is supported since 22.7");
            } else {
                Assert.assertFalse(txConn.isImplicitTransactionSupported(),
                        "Implicit transaction is NOT supported before 22.7");
            }

            checkRowCount(stmt, "select 1", 1);
            checkRowCount(txStmt, "select 1", 1);
            txConn.commit();

            checkRowCount(stmt, "select 1", 1);
            checkRowCount(txStmt, "select 1", 1);
            txConn.rollback();

            checkRowCount(stmt, tableName, 0);
            checkRowCount(txStmt, tableName, 0);

            txStmt.executeUpdate("insert into " + tableName + " values(0, '0')");
            checkRowCount(stmt, tableName, 1);
            checkRowCount(txStmt, tableName, 1);
            txConn.rollback();
            checkRowCount(stmt, tableName, 0);
            checkRowCount(txStmt, tableName, 0);

            stmt.executeUpdate("insert into " + tableName + " values(1, 'a')");
            checkRowCount(stmt, tableName, 1);
            checkRowCount(txStmt, tableName, 1);

            txStmt.executeUpdate("insert into " + tableName + " values(2, 'b')");
            checkRowCount(stmt, tableName, 2);
            checkRowCount(txStmt, tableName, 2);

            try (Connection c = newConnection(txProps); Statement s = c.createStatement()) {
                s.executeUpdate("insert into " + tableName + " values(-1, '-1')");
                checkRowCount(stmt, tableName, 3);
                checkRowCount(txStmt, tableName, 2);
                checkRowCount(s, tableName, 2);
                c.rollback();
                checkRowCount(stmt, tableName, 2);
                checkRowCount(txStmt, tableName, 2);
                checkRowCount(s, tableName, 1);
            }
            checkRowCount(stmt, tableName, 2);
            checkRowCount(txStmt, tableName, 2);

            try (Connection c = newConnection(txProps); Statement s = c.createStatement()) {
                s.executeUpdate("insert into " + tableName + " values(3, 'c')");
                checkRowCount(stmt, tableName, 3);
                checkRowCount(txStmt, tableName, 2);
                checkRowCount(s, tableName, 2);
                txConn.commit();
                checkRowCount(stmt, tableName, 3);
                checkRowCount(txStmt, tableName, 2);
                checkRowCount(s, tableName, 2);
            }
            checkRowCount(stmt, tableName, 3);
            checkRowCount(txStmt, tableName, 2);
            txConn.commit();
            checkRowCount(txStmt, tableName, 3);

            txConn.setAutoCommit(true);
            Assert.assertTrue(txConn.getAutoCommit());
            try (Statement s = txConn.createStatement()) {
                s.executeUpdate("insert into " + tableName + " values(4, 'd')");
                checkRowCount(stmt, tableName, 4);
                checkRowCount(txStmt, tableName, 4);
                checkRowCount(s, tableName, 4);
            }

            try (Statement s = txConn.createStatement()) {
                checkRowCount(stmt, tableName, 4);
                checkRowCount(txStmt, tableName, 4);
                checkRowCount(s, tableName, 4);
            }
        }
    }

    @Test // (groups = "integration")
    public void testNestedTransactions() throws Exception {
        Properties props = new Properties();
        props.setProperty("autoCommit", "false");
        props.setProperty("transactionSupport", "true");
        String tableName = "test_jdbc_nested_tx";
        try (Connection c = newConnection(props); Statement s = c.createStatement()) {
            s.execute("drop table if exists " + tableName + "; "
                    + "create table " + tableName + "(id UInt64) engine=MergeTree order by id");
        }

        try (Connection conn = newConnection(props);
                Statement stmt = conn.createStatement();
                PreparedStatement ps = conn.prepareStatement("insert into " + tableName)) {
            checkRowCount(stmt, tableName, 0);
            stmt.executeQuery("insert into " + tableName + " values(1)");
            checkRowCount(stmt, tableName, 1);
            ps.setInt(1, 2);
            ps.executeUpdate();
            checkRowCount(stmt, tableName, 2);
            ps.setInt(1, 3);
            ps.executeBatch();
            checkRowCount(stmt, tableName, 2);
            ps.setInt(1, 3);
            ps.addBatch();
            ps.executeBatch();
            checkRowCount(stmt, tableName, 3);
            try (Connection c = newConnection(); Statement s = c.createStatement()) {
                checkRowCount(s, tableName, 3);
            }

            conn.rollback();
            checkRowCount(stmt, tableName, 0);
            try (Connection c = newConnection(); Statement s = c.createStatement()) {
                checkRowCount(s, tableName, 0);
            }
        }
    }

    @Test // (groups = "integration")
    public void testParallelTransactions() throws Exception {
        Properties props = new Properties();
        props.setProperty("autoCommit", "false");
        props.setProperty("transactionSupport", "true");
        String tableName = "test_jdbc_parallel_tx";
        try (Connection c = newConnection(props); Statement s = c.createStatement()) {
            s.execute("drop table if exists " + tableName + "; "
                    + "create table " + tableName + "(id UInt64) engine=MergeTree order by id");
        }

        try (Connection conn1 = newConnection(props);
                Connection conn2 = newConnection(props);
                Statement stmt1 = conn1.createStatement();
                Statement stmt2 = conn2.createStatement();
                PreparedStatement ps1 = conn1.prepareStatement("insert into " + tableName);
                PreparedStatement ps2 = conn2.prepareStatement("insert into " + tableName)) {
            stmt1.executeUpdate("insert into " + tableName + " values(-1)");
            checkRowCount(stmt1, tableName, 1);
            checkRowCount(stmt2, tableName, 0);
            conn1.rollback();
            checkRowCount(stmt1, tableName, 0);
            checkRowCount(stmt2, tableName, 0);

            stmt2.executeUpdate("insert into " + tableName + " values(-2)");
            checkRowCount(stmt1, tableName, 0);
            checkRowCount(stmt2, tableName, 1);
            conn2.commit();
            checkRowCount(stmt1, tableName, 0);
            checkRowCount(stmt2, tableName, 1);
            conn1.commit();
            checkRowCount(stmt1, tableName, 1);
            checkRowCount(stmt2, tableName, 1);

            ps1.setInt(1, 1);
            ps1.addBatch();
            ps1.setInt(1, 2);
            ps1.addBatch();
            ps1.setInt(1, 3);
            ps1.addBatch();
            ps1.executeBatch();
            checkRowCount(stmt1, tableName, 4);
            checkRowCount(stmt2, tableName, 1);
            conn1.commit();
            checkRowCount(stmt1, tableName, 4);
            checkRowCount(stmt2, tableName, 1);
            try (Connection c = newConnection(props); Statement s = c.createStatement()) {
                checkRowCount(s, tableName, 4);
            }

            ps2.setInt(1, 4);
            ps2.addBatch();
            ps2.setInt(1, 5);
            ps2.addBatch();
            ps2.setInt(1, 6);
            // ps2.addBatch();
            ps2.executeBatch();
            checkRowCount(stmt1, tableName, 4);
            checkRowCount(stmt2, tableName, 3);
            conn2.commit();
            checkRowCount(stmt1, tableName, 4);
            checkRowCount(stmt2, tableName, 6);
            try (Connection c = newConnection(props); Statement s = c.createStatement()) {
                checkRowCount(s, tableName, 6);
            }
        }
    }
}
