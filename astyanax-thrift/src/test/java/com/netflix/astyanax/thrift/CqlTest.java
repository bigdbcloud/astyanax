package com.netflix.astyanax.thrift;

import java.util.Map;
import java.util.UUID;
import java.util.Map.Entry;

import junit.framework.Assert;

import org.apache.cassandra.db.marshal.UTF8Type;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableMap;
import com.netflix.astyanax.AstyanaxContext;
import com.netflix.astyanax.Keyspace;
import com.netflix.astyanax.connectionpool.NodeDiscoveryType;
import com.netflix.astyanax.connectionpool.OperationResult;
import com.netflix.astyanax.connectionpool.impl.ConnectionPoolConfigurationImpl;
import com.netflix.astyanax.connectionpool.impl.ConnectionPoolType;
import com.netflix.astyanax.connectionpool.impl.CountingConnectionPoolMonitor;
import com.netflix.astyanax.cql.CqlSchema;
import com.netflix.astyanax.cql.CqlStatementResult;
import com.netflix.astyanax.ddl.KeyspaceDefinition;
import com.netflix.astyanax.impl.AstyanaxConfigurationImpl;
import com.netflix.astyanax.model.Column;
import com.netflix.astyanax.model.ColumnFamily;
import com.netflix.astyanax.model.ColumnList;
import com.netflix.astyanax.model.CqlResult;
import com.netflix.astyanax.model.Row;
import com.netflix.astyanax.model.Rows;
import com.netflix.astyanax.serializers.IntegerSerializer;
import com.netflix.astyanax.serializers.MapSerializer;
import com.netflix.astyanax.serializers.StringSerializer;
import com.netflix.astyanax.serializers.UUIDSerializer;
import com.netflix.astyanax.util.SingletonEmbeddedCassandra;

public class CqlTest {

    private static Logger LOG = LoggerFactory.getLogger(CqlTest.class);

    private static Keyspace                  keyspace;
    private static AstyanaxContext<Keyspace> keyspaceContext;

    private static String TEST_CLUSTER_NAME  = "cass_sandbox";
    private static String TEST_KEYSPACE_NAME = "CqlTest";

    private static final String SEEDS = "localhost:9160";

    private static final long   CASSANDRA_WAIT_TIME = 1000;
    private static final int    TTL                 = 20;
    private static final int    TIMEOUT             = 10;
    
    static ColumnFamily<Integer, String> CQL3_CF = ColumnFamily.newColumnFamily(
            "Cql3CF", 
            IntegerSerializer.get(), 
            StringSerializer.get());
    
    static ColumnFamily<String, String> User_CF = ColumnFamily.newColumnFamily(
            "UserCF", 
            StringSerializer.get(), 
            StringSerializer.get());
    static ColumnFamily<UUID, String> UUID_CF = ColumnFamily.newColumnFamily(
            "uuidtest", 
            UUIDSerializer.get(), 
            StringSerializer.get());
    
    @BeforeClass
    public static void setup() throws Exception {
        SingletonEmbeddedCassandra.getInstance();
        
        Thread.sleep(CASSANDRA_WAIT_TIME);
        
        createKeyspace();
    }

    @AfterClass
    public static void teardown() throws Exception {
        if (keyspaceContext != null)
            keyspaceContext.shutdown();
        
        Thread.sleep(CASSANDRA_WAIT_TIME);
    }

    public static void createKeyspace() throws Exception {
        keyspaceContext = new AstyanaxContext.Builder()
                .forCluster(TEST_CLUSTER_NAME)
                .forKeyspace(TEST_KEYSPACE_NAME)
                .withAstyanaxConfiguration(
                        new AstyanaxConfigurationImpl()
                                .setDiscoveryType(NodeDiscoveryType.RING_DESCRIBE)
                                .setConnectionPoolType(ConnectionPoolType.TOKEN_AWARE)
                                .setDiscoveryDelayInSeconds(60000)
                                .setTargetCassandraVersion("1.2")
                                .setCqlVersion("3.0.0")
                                )
                .withConnectionPoolConfiguration(
                        new ConnectionPoolConfigurationImpl(TEST_CLUSTER_NAME
                                + "_" + TEST_KEYSPACE_NAME)
                                .setSocketTimeout(30000)
                                .setMaxTimeoutWhenExhausted(2000)
                                .setMaxConnsPerHost(10)
                                .setInitConnsPerHost(10)
                                .setSeeds(SEEDS))
                .withConnectionPoolMonitor(new CountingConnectionPoolMonitor())
                .buildKeyspace(ThriftFamilyFactory.getInstance());

        keyspaceContext.start();
        
        keyspace = keyspaceContext.getEntity();
        
        try {
            keyspace.dropKeyspace();
            Thread.sleep(CASSANDRA_WAIT_TIME);
        }
        catch (Exception e) {
            LOG.info("Error dropping keyspace " + e.getMessage());
        }
        
        keyspace.createKeyspace(ImmutableMap.<String, Object>builder()
                .put("strategy_options", ImmutableMap.<String, Object>builder()
                        .put("replication_factor", "1")
                        .build())
                .put("strategy_class",     "SimpleStrategy")
                .build()
                );
        
        Thread.sleep(CASSANDRA_WAIT_TIME);
        
        OperationResult<CqlStatementResult> result;

        result = keyspace.prepareCqlStatement()
            .withCql("CREATE TABLE employees (empID int, deptID int, first_name varchar, last_name varchar, PRIMARY KEY (empID, deptID));")
            .execute();
        
        result = keyspace.prepareCqlStatement()
            .withCql("CREATE TABLE users (id text PRIMARY KEY, given text, surname text, favs map<text, text>);")
            .execute();
        
        result = keyspace.prepareCqlStatement()
                .withCql("CREATE TABLE uuidtest (id UUID PRIMARY KEY, given text, surname text);")
                .execute();
        
        Thread.sleep(CASSANDRA_WAIT_TIME);
        
        KeyspaceDefinition ki = keyspaceContext.getEntity().describeKeyspace();
        LOG.info("Describe Keyspace: " + ki.getName());
        
    }
    
    @Test
    public void testCompoundKey() throws Exception {
        OperationResult<CqlStatementResult> result;
        result = keyspace
                .prepareCqlStatement()
                .withCql("INSERT INTO employees (empID, deptID, first_name, last_name) VALUES ('111', '222', 'eran', 'landau');")
                .execute();
        
        result = keyspace
                .prepareCqlStatement()
                .withCql("INSERT INTO employees (empID, deptID, first_name, last_name) VALUES ('111', '233', 'netta', 'landau');")
                .execute();
        
        result = keyspace
                .prepareCqlStatement()
                .withCql("SELECT * FROM employees WHERE empId='111';")
                .execute();

        Assert.assertTrue(!result.getResult().getRows(CQL3_CF).isEmpty());
        for (Row<Integer, String> row : result.getResult().getRows(CQL3_CF)) {
            LOG.info("CQL Key: " + row.getKey());

            ColumnList<String> columns = row.getColumns();
            
            LOG.info("   empid      : " + columns.getIntegerValue("empid",      null));
            LOG.info("   deptid     : " + columns.getIntegerValue("deptid",     null));
            LOG.info("   first_name : " + columns.getStringValue ("first_name", null));
            LOG.info("   last_name  : " + columns.getStringValue ("last_name",  null));
        }   
    }
    
    @Test
    public void testPreparedCql() throws Exception {
        OperationResult<CqlResult<Integer, String>> result;
        
        final String INSERT_STATEMENT = "INSERT INTO employees (empID, deptID, first_name, last_name) VALUES (?, ?, ?, ?);";
        
        result = keyspace
                .prepareQuery(CQL3_CF)
                    .withCql(INSERT_STATEMENT)
                .asPreparedStatement()
                    .withIntegerValue(222)
                    .withIntegerValue(333)
                    .withStringValue("Netta")
                    .withStringValue("Landau")
                .execute();
        
        result = keyspace
                .prepareQuery(CQL3_CF)
                .withCql("SELECT * FROM employees WHERE empId='222';")
                .execute();
        Assert.assertTrue(!result.getResult().getRows().isEmpty());
        for (Row<Integer, String> row : result.getResult().getRows()) {
            LOG.info("CQL Key: " + row.getKey());

            ColumnList<String> columns = row.getColumns();
            
            LOG.info("   empid      : " + columns.getIntegerValue("empid",      null));
            LOG.info("   deptid     : " + columns.getIntegerValue("deptid",     null));
            LOG.info("   first_name : " + columns.getStringValue ("first_name", null));
            LOG.info("   last_name  : " + columns.getStringValue ("last_name",  null));
        }           
    }
    
    @Test
    public void testKeyspaceCql() throws Exception {
        keyspace.prepareQuery(CQL3_CF)
                .withCql("INSERT INTO employees (empID, deptID, first_name, last_name) VALUES ('999', '233', 'arielle', 'landau');")
                .execute();
        
        CqlStatementResult result = keyspace.prepareCqlStatement()
                .withCql("SELECT * FROM employees WHERE empID = '999';")
                .execute()
                .getResult();
        
        CqlSchema schema = result.getSchema();
        Rows<Integer, String> rows = result.getRows(CQL3_CF);
        
        Assert.assertEquals(1,  rows.size());
//        Assert.assertTrue(999 == rows.getRowByIndex(0).getKey());
        
    }

    @Test
    public void testCollections() throws Exception {
        OperationResult<CqlStatementResult> result;
        result = keyspace
                .prepareCqlStatement()
                .withCql("INSERT INTO users (id, given, surname, favs) VALUES ('jsmith', 'John', 'Smith', { 'fruit' : 'apple', 'band' : 'Beatles' })")
                .execute();

        Rows<String, String> rows = keyspace.prepareCqlStatement()
                .withCql("SELECT * FROM users;")
                .execute()
                .getResult()
                .getRows(User_CF);
        
        MapSerializer<String, String> mapSerializer = new MapSerializer<String, String>(UTF8Type.instance, UTF8Type.instance);
        
        for (Row<String, String> row : rows) {
            LOG.info(row.getKey());
            for (Column<String> column : row.getColumns()) {
                LOG.info("  " + column.getName());
            }
            Column<String> favs = row.getColumns().getColumnByName("favs");
            Map<String, String> map = favs.getValue(mapSerializer);
            for (Entry<String, String> entry : map.entrySet()) {
                LOG.info(" fav: " + entry.getKey() + " = " + entry.getValue());
            }
        }
    }
    @Test
    public  void testUUID() throws Exception{
    	CqlStatementResult result = keyspace.prepareCqlStatement()
    	        .withCql("SELECT * FROM uuidtest ;")
    	        .execute()
    	        .getResult();
    	OperationResult<CqlResult<UUID,String>>  res1 =keyspace.prepareQuery(UUID_CF)
        .withCql("INSERT INTO uuidtest (id, given, surname) VALUES (00000000-0000-0000-0000-000000000000, 'x', 'arielle');")
        .execute();
    	CqlResult<UUID,String> res2 = res1.getResult();
//    	boolean b = res2.hasRows();
//    	int num = res2.getNumber();

    	result = keyspace.prepareCqlStatement()
        .withCql("SELECT * FROM uuidtest ;")
        .execute()
        .getResult();
    	String b = result.toString();

    	CqlSchema schema = result.getSchema();
    	Rows<UUID, String> rows = result.getRows(UUID_CF);

    	Assert.assertEquals(1,  rows.size());
//		AstyanaxContext<Keyspace> context = new AstyanaxContext.Builder()
//				.forCluster("Test Cluster")
//				.forKeyspace("grd")
//				.withAstyanaxConfiguration(
//						new AstyanaxConfigurationImpl()
//								.setDiscoveryType(
//										NodeDiscoveryType.RING_DESCRIBE)
//								.setCqlVersion("3.0.0")
//								.setTargetCassandraVersion("1.2.3"))
//				// TODO: set both connectionTimeout and readTimeout
//				.withConnectionPoolConfiguration(
//						new ConnectionPoolConfigurationImpl("MyConnectionPool")
//								.setPort(50825).setMaxConnsPerHost(10)
//								.setSeeds("localhost:9160")
//								.setConnectTimeout(20000))
//				.withConnectionPoolMonitor(new CountingConnectionPoolMonitor())
//				.buildKeyspace(ThriftFamilyFactory.getInstance());
//
//		context.start();
//
//		// logger.log(Level.INFO, "getting context.. done ");
//		Keyspace keyspace = context.getEntity();
//
//		ColumnFamily<UUID, String> routeCF = new ColumnFamily<UUID, String>(
//				"grd.test1", (Serializer<UUID>) UUIDSerializer.get(),
//				(Serializer<String>) StringSerializer.get());

//		OperationResult<CqlResult<UUID, String>> result;
//		try {
//			result = keyspace.prepareQuery(routeCF)
//					.withCql("select * from grd.test1;").execute();
//			for (Row<UUID, String> row : result.getResult().getRows()) {
//				System.out.println("Row Key: " + row.getKey());
//				ColumnList<String> columns = row.getColumns();
//				System.out.println("col1: "
//						+ columns.getUUIDValue("col1", null));
//				System.out.println("col2: "
//						+ columns.getStringValue("col2", null));
//				System.out.println("col3: "
//						+ columns.getStringValue("col3", null));
//			}
//		} catch (ConnectionException e) {
//			// TODO Auto-generated catch block
//			e.printStackTrace();
//		}

	}
}
