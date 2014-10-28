package codeset.hazelcast.store.sql;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;

import codeset.hazelcast.store.serialize.KryoSerializer;
import codeset.hazelcast.store.serialize.Serializer;

import com.hazelcast.core.MapStore;

/**
 * Store objects from Hazelcast as blobs in a SQL Database.
 * 
 * @author ingemar.svensson
 *
 */
public class SqlMapStore implements MapStore<String, Object> {

    private final static int MAX_BATCH_SIZE = 1000;

    /**
     * The class of the objects stored. This is required in order to make the
     * read from the database know what type of class it should instantiate.
     */
    private Class<?> type;

    /**
     * A DataSource to use.
     */
    private DataSource dataSource;

    /**
     * The SQL statements to use. Holds the SQL used and can be extended
     * for any type of SQL database.
     */
    private Statements statements;

    /**
     * Serializer implementation to use for serializing the objects to bytes or
     * from bytes into objects.
     */
    private Serializer serializer;

    /**
     * Construct a new SqlMapStore for a type with default Kryo serialization.
     * 
     * @param type
     * @param dataSource
     * @param statements
     */
    public SqlMapStore(Class<?> type, DataSource dataSource, Statements statements) {
        this(type, dataSource, statements, new KryoSerializer());
    }

    /**
     * Construct a new SqlMapStore for a type.
     * 
     * @param type
     * @param dataSource
     * @param statements
     * @param serializer
     */
    public SqlMapStore(Class<?> type, DataSource dataSource, Statements statements, Serializer serializer) {

        this.type = type;
        this.dataSource = dataSource;
        this.statements = statements;
        this.serializer = serializer;

    }

    /**
     * Store a single value in the database. It checks if the value exists and
     * if so, it will perform an update otherwise an insert.
     * 
     * @param key The key of the value in the map. This should be the primary
     * key of the value in the database.
     * @param value The value to persist to the database.
     */
    @Override
    public void store(String key, Object value) {

        try (Connection connection = dataSource.getConnection()) {
            CallableStatement statement = null;
            if(load(key) == null) {
                statement = connection.prepareCall(statements.getInsertSql());
                statement.setString(1, key);
                statement.setString(2, type.getName());
                statement.setBytes(3, serializer.toBytes(value));
            } else {
                statement = connection.prepareCall(statements.getUpdateSql());
                statement.setString(1, key);
                statement.setString(2, type.getName());
                statement.setBytes(3, serializer.toBytes(value));
                statement.setString(4, key);
            }
            statement.executeUpdate();
            connection.commit();
        } catch (SQLException e) {
            throw new SqlMapStoreException("Failed to store " + type.getName() + " with key " + key, e);
        }

    }

    @Override
    public void storeAll(Map<String, Object> values) {

        for (Map.Entry<String, Object> entry : values.entrySet()) {
            store(entry.getKey(), entry.getValue());
        }

    }

    @Override
    public Object load(String key) {

        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement statement = connection.prepareStatement(statements.getLoadSql())) {
                statement.setString(1, key);

                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next()) {
                        byte[] bytes = resultSet.getBytes(1);
                        return serializer.fromBytes(type, bytes);
                    }
                }

            }
        } catch (SQLException e) {
            throw new SqlMapStoreException("Failed to load " + type.getName() + " for key " + key, e);
        }
        return null;

    }

    /**
     * Retrieve a map of values to be stored in the map. The values will be
     * stored using putTransient() hence no store operation will be triggered.
     * 
     * @param keys The keys for which to load the values.
     */
    @Override
    public Map<String, Object> loadAll(Collection<String> keys) {

        Map<String, Object> results = new HashMap<>();
        try (Connection connection = dataSource.getConnection()) {
            StringBuilder sql = new StringBuilder(statements.getLoadAllSql());
            sql.append("(");
            for (int i = 0; i < keys.size(); i++) {
                if (i > 0) {
                    sql.append(", ");
                }
                sql.append("?");
            }
            sql.append(")");

            try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
                int idx = 0;
                for (String key : keys) {
                    statement.setString(++idx, key);
                }

                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        String key = resultSet.getString(1);
                        byte[] bytes = resultSet.getBytes(2);
                        Object value = serializer.fromBytes(type, bytes);
                        results.put(key, value);
                    }
                }
            }

        } catch (SQLException e) {
            throw new SqlMapStoreException("Failed to load all " + type.getName() + "(s)", e);
        }
        return results;

    }

    @Override
    public Set<String> loadAllKeys() {

        final Set<String> keys = new HashSet<>();
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement statement = connection.prepareStatement(statements.getLoadKeysSql())) {
                statement.setString(1, type.getName());
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        String key = resultSet.getString(1);
                        keys.add(key);
                    }
                }
            }
        } catch (SQLException e) {
            throw new SqlMapStoreException("Failed to load all keys for " + type.getName(), e);
        }
        return keys;

    }

    @Override
    public void delete(String key) {

        try (Connection connection = dataSource.getConnection()) {
            try (CallableStatement statement = connection.prepareCall(statements.getDeleteSql())) {
                statement.setString(1, key);
                statement.executeUpdate();
            }
            connection.commit();
        } catch (SQLException e) {
            throw new SqlMapStoreException("Failed to delete " + type.getName() + " with key " + key, e);
        }

    }

    @Override
    public void deleteAll(Collection<String> keys) {

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(statements.getDeleteAllSql())) {
                int count = 0;
                for (String key : keys) {

                    statement.setString(1, key);
                    statement.addBatch();

                    if ((++count) % MAX_BATCH_SIZE == 0) {
                        statement.executeBatch();
                        connection.commit();
                    }

                }
                statement.executeBatch();
            }
            connection.commit();
        } catch (SQLException e) {
            throw new SqlMapStoreException("Failed to deleteAll map values", e);
       }

    }

}