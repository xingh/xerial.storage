/*--------------------------------------------------------------------------
 *  Copyright 2007 Taro L. Saito
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *--------------------------------------------------------------------------*/
//--------------------------------------
// xerial-storage Project
//
// DatabaseAccessBase.java
// Since: 2007/02/25
//
// $URL$ 
// $Author$
//--------------------------------------
package org.xerial.db.sql;

import java.io.IOException;
import java.io.Writer;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.xerial.db.DBErrorCode;
import org.xerial.db.DBException;
import org.xerial.db.Relation;
import org.xerial.db.datatype.DataType;
import org.xerial.json.JSONObject;
import org.xerial.json.JSONValue;
import org.xerial.util.Predicate;
import org.xerial.util.StringUtil;
import org.xerial.util.bean.BeanException;
import org.xerial.util.bean.BeanUtil;
import org.xerial.util.log.Logger;

/**
 * A base implementation of the {@link DatabaseAccess} interface.
 * 
 * @author leo
 * 
 */
public class DatabaseAccessBase implements DatabaseAccess
{

    private ConnectionPool _connectionPool;
    private static Logger _logger = Logger.getLogger(DatabaseAccessBase.class);

    private int queryTimeout = 60;
    private boolean autoCommit = true;

    private HashMap<String, Relation> tableRelationCatalog = new HashMap<String, Relation>();

    public DatabaseAccessBase(ConnectionPool connectionPool) throws DBException
    {
        _connectionPool = connectionPool;

        // validate connection
        Connection con = _connectionPool.getConnection();
        _connectionPool.returnConnection(con);
    }

    public void dispose() throws DBException
    {
        _connectionPool.closeAll();
    }

    protected Statement createStatement(Connection connection) throws SQLException
    {
        Statement statement = connection.createStatement();
        statement.setQueryTimeout(queryTimeout);
        return statement;
    }

    /**
     * perforam a given SQL query, then output its results
     * 
     * @param <T>
     *            row type : Bean class type
     * @param sql
     *            sql statement
     * @param resultRowType
     *            it must be equal to the T
     * @param result
     * @throws DBException
     */
    @SuppressWarnings("unchecked")
    public <T> List<T> query(String sql, Class<T> resultRowType) throws DBException
    {
        return queryWithHandler(sql, new BeanReader(resultRowType));
    }

    
    public <T> void query(String sql, ResultSetHandler<T> pullHandler) throws DBException
    {
        Connection connection = null;
        try
        {
            connection = getConnection(true);
            Statement statement = createStatement(connection);
            _logger.debug(sql);

            pullHandler.init();

            ResultSet rs = statement.executeQuery(sql);
            while (rs.next())
            {
                pullHandler.handle(rs);
            }
            rs.close();
            statement.close();
        }
        catch (SQLException e)
        {
            throw new DBException(DBErrorCode.QueryError, e);
        }
        finally
        {

            if (connection != null)
                _connectionPool.returnConnection(connection);

            pullHandler.finish();
        }
    }

    public <T> List<T> query(String sql, Class<T> resultRowType, Predicate<T> filter) throws DBException
    {
        Connection connection = null;
        BeanReader<T> beanReader = new BeanReader<T>(resultRowType);
        ArrayList<T> result = new ArrayList<T>();
        try
        {
            connection = getConnection(true);
            Statement statement = createStatement(connection);
            _logger.debug(sql);

            ResultSet rs = statement.executeQuery(sql);
            while (rs.next())
            {
                T row = beanReader.handle(rs);
                if (row != null && filter.apply(row))
                    result.add(row);
            }

            rs.close();
            statement.close();
        }
        catch (SQLException e)
        {
            throw new DBException(DBErrorCode.QueryError, e);
        }
        finally
        {
            if (connection != null)
                _connectionPool.returnConnection(connection);
        }
        return result;
    }
    
    public <T> List<T> queryWithHandler(String sql, ResultSetHandler<T> handler) throws DBException
    {
        Connection connection = null;
        ArrayList<T> result = new ArrayList<T>();
        try
        {
            connection = getConnection(true);
            Statement statement = createStatement(connection);
            _logger.debug(sql);

            handler.init();

            ResultSet rs = statement.executeQuery(sql);
            while (rs.next())
            {
                T row = handler.handle(rs);
                if (row != null)
                    result.add(row);
                else
                    _logger.warn("null handler result is returned");
            }

            rs.close();
            statement.close();
        }
        catch (SQLException e)
        {
            throw new DBException(DBErrorCode.QueryError, e);
        }
        finally
        {
            if (connection != null)
                _connectionPool.returnConnection(connection);

            handler.finish();
        }
        return result;
    }

    /**
     * Accumulate the query result within the ResultSetHandler, then return the
     * result from the handler
     * 
     * @param <T>
     * @param sql
     * @param handler
     * @return
     * @throws DBException
     */
    public <T> T accumulate(String sql, ResultSetHandler<T> handler) throws DBException
    {
        Connection connection = null;
        T result = null;
        try
        {
            connection = getConnection(true);
            Statement statement = createStatement(connection);
            _logger.debug(sql);

            handler.init();

            ResultSet rs = statement.executeQuery(sql);

            while (rs.next())
            {
                result = handler.handle(rs);
            }

            rs.close();
            statement.close();

        }
        catch (SQLException e)
        {
            throw new DBException(DBErrorCode.QueryError, e);
        }
        finally
        {
            if (connection != null)
                _connectionPool.returnConnection(connection);

            handler.finish();
        }
        return result;
    }

    private PreparedStatement getPreparedStatement(Connection connection, String sql) throws SQLException
    {
        PreparedStatement preparedStatement = connection.prepareStatement(sql);
        preparedStatement.setQueryTimeout(queryTimeout);
        return preparedStatement;
    }

    public int updateWithPreparedStatement(String sqlForPreparedStatement, PreparedStatementHandler handler)
            throws DBException
    {
        Connection connection = null;
        try
        {
            connection = getConnection(false);
            _logger.debug(sqlForPreparedStatement);
            PreparedStatement preparedStatement = getPreparedStatement(connection, sqlForPreparedStatement);
            handler.setup(preparedStatement);
            int ret = preparedStatement.executeUpdate();

            preparedStatement.close();

            return ret;
        }
        catch (SQLException e)
        {
            throw new DBException(DBErrorCode.UpdateError, e);
        }
        finally
        {
            if (connection != null)
            {
                _connectionPool.returnConnection(connection);
            }
        }

    }

    public int update(String sql) throws DBException
    {
        return update(sql, autoCommit);
    }

    public int update(String sql, boolean autoCommit) throws DBException
    {
        Connection connection = null;
        try
        {
            connection = getConnection(false);
            connection.setAutoCommit(autoCommit);
            Statement statement = createStatement(connection);

            _logger.debug(sql);
            int ret = statement.executeUpdate(sql);
            statement.close();
            return ret;
        }
        catch (SQLException e)
        {
            throw new DBException(DBErrorCode.UpdateError, e);
        }
        finally
        {
            if (connection != null)
            {
                _connectionPool.returnConnection(connection);
            }
        }

    }

    public <T> int insertAndRetrieveKeys(String sql) throws DBException
    {
        Connection connection = null;
        try
        {
            connection = getConnection(false);
            connection.setAutoCommit(autoCommit);
            Statement statement = createStatement(connection);

            _logger.debug(sql);
            int ret = statement.executeUpdate(sql);

            ResultSet rs = statement.getGeneratedKeys();
            int id = (rs == null) ? -1 : rs.getInt(1);
            rs.close();
            statement.close();
            return id;
        }
        catch (SQLException e)
        {
            throw new DBException(DBErrorCode.UpdateError, e);
        }
        finally
        {
            if (connection != null)
            {
                _connectionPool.returnConnection(connection);
            }
        }
    }

    public ConnectionPool getConnectionPool()
    {
        return _connectionPool;
    }

    private Connection getConnection(boolean readOnly) throws DBException, SQLException
    {
        Connection conn = _connectionPool.getConnection();
        conn.setAutoCommit(autoCommit);
        // conn.setReadOnly(readOnly);
        return conn;
    }

    public void setAutoCommit(boolean enableAutoCommit)
    {
        autoCommit = enableAutoCommit;
    }

    public void setQueryTimeout(int sec)
    {
        this.queryTimeout = sec;
    }

    public Set<String> getPrimaryKeyColumns(String tableName) throws DBException
    {

        Connection connection = null;

        try
        {
            connection = getConnection(true);
            DatabaseMetaData metadata = connection.getMetaData();
            return getPrimaryKeyColumns(metadata, tableName);
        }
        catch (SQLException e)
        {
            throw new DBException(DBErrorCode.QueryError, e);
        }
        finally
        {
            if (connection != null)
                _connectionPool.returnConnection(connection);
        }

    }

    public Set<String> getPrimaryKeyColumns(DatabaseMetaData metadata, String tableName) throws SQLException
    {
        HashSet<String> primaryKeyColumnSet = new HashSet<String>();
        // retrieve primary key information
        ResultSet primaryKeyResult = metadata.getPrimaryKeys(null, null, tableName);
        for (; primaryKeyResult.next();)
        {
            String columnName = primaryKeyResult.getString("COLUMN_NAME");
            primaryKeyColumnSet.add(columnName);
        }
        primaryKeyResult.close();

        return primaryKeyColumnSet;
    }

    public Relation getRelation(String tableName) throws DBException
    {
        if (tableRelationCatalog.containsKey(tableName))
            return tableRelationCatalog.get(tableName);

        Relation relation = new Relation();

        Connection connection = null;
        try
        {
            connection = getConnection(true);
            DatabaseMetaData metadata = connection.getMetaData();

            Set<String> primaryKeyColumnSet = getPrimaryKeyColumns(metadata, tableName);

            int column = 1;
            ResultSet resultSet = metadata.getColumns(null, null, tableName, null);
            for (; resultSet.next();)
            {
                String columnName = resultSet.getString("COLUMN_NAME");
                String typeName = resultSet.getString("TYPE_NAME");
                DataType dt = Relation.getDataType(columnName, typeName);

                ResultSetMetaData rmeta = resultSet.getMetaData();

                assert (dt != null);
                dt.setNullable(resultSet.getInt("NULLABLE") == ResultSetMetaData.columnNullable);
                dt.setPrimaryKey(primaryKeyColumnSet.contains(columnName));

                relation.add(dt);

                column++;
            }
            resultSet.close();
        }
        catch (SQLException e)
        {
            throw new DBException(DBErrorCode.QueryError, e);
        }

        finally
        {
            if (connection != null)
                _connectionPool.returnConnection(connection);
        }

        tableRelationCatalog.put(tableName, relation);
        return relation;
    }

    public List<String> getTableNameList() throws DBException
    {
        ArrayList<String> tableNameList = new ArrayList<String>();

        Connection connection = null;
        try
        {
            connection = getConnection(true);
            DatabaseMetaData metadata = connection.getMetaData();
            for (ResultSet resultSet = metadata.getTables(null, null, "%", new String[] { "TABLE", "VIEW" }); resultSet
                    .next();)
            {
                tableNameList.add(resultSet.getString("TABLE_NAME").toLowerCase());
            }
        }
        catch (SQLException e)
        {
            throw new DBException(DBErrorCode.QueryError, e);
        }
        finally
        {
            if (connection != null)
                _connectionPool.returnConnection(connection);
        }

        return tableNameList;
    }

    public <T> List<T> singleColumnQuery(String sql, String targetColumn, Class<T> resultColumnType) throws DBException
    {
        return queryWithHandler(sql, new ColumnReader<T>(targetColumn));
    }

    public <T> void query(String sql, BeanResultHandler<T> beanResultHandler) throws DBException
    {

        Connection connection = null;
        try
        {
            connection = getConnection(true);
            Statement statement = createStatement(connection);
            _logger.debug(sql);

            beanResultHandler.init();

            ResultSet rs = statement.executeQuery(sql);
            while (rs.next())
            {
                T bean = beanResultHandler.toBean(rs);
                beanResultHandler.handle(bean);
            }
            rs.close();
        }
        catch (SQLException e)
        {
            throw new DBException(DBErrorCode.QueryError, e);
        }
        finally
        {
            if (connection != null)
                _connectionPool.returnConnection(connection);
            beanResultHandler.finish();
        }

    }

    public <T> int insert(String tableName, T bean) throws DBException
    {
        String sql;
        try
        {
            sql = SQLExpression.fillTemplate("insert into $1 values($2)", tableName, createValueTupleFromBean(
                    tableName, bean));
            return update(sql);
        }
        catch (BeanException e)
        {
            throw new DBException(DBErrorCode.InvalidBeanClass, e);
        }
    }

    /**
     * Align the content of a bean object so that it matches with the
     * corresponding relation (table schema)
     * 
     * For example, give a bean class, e.g.
     * <code>class Person { int id; String name; (getters are ommited) } </code>
     * and a table named person with a schema 'id, name', the
     * createValueTupleFromBean("person", (a Person object)) will give a tuple
     * representation of the Person object (id=1, name="leo"), that is
     * '1,"leo"'.
     * 
     * This returned string can be used as it is within an insert statement of
     * the SQL, i.e., <code>insert into person values(1, "leo")</code>
     * 
     * @param tableName
     * @param bean
     * @return
     * @throws DBException
     * @throws InvalidBeanException
     */
    protected String createValueTupleFromBean(String tableName, Object bean) throws DBException, BeanException
    {
        Relation r = getRelation(tableName);

        JSONObject json = (JSONObject) BeanUtil.toJSONObject(bean);

        ArrayList<String> tupleValue = new ArrayList<String>();
        for (DataType dt : r.getDataTypeList())
        {
            JSONValue jsonValue = json.get(dt.getName());
            String value = (jsonValue == null) ? "" : jsonValue.toJSONString();
            tupleValue.add(value);
        }

        return StringUtil.join(tupleValue, ",");
    }

    public <T> void toJSON(String sql, Class<T> beanClass, Writer writer) throws DBException, IOException
    {
        writer.append("{");
        writer.append(StringUtil.quote(beanClass.getSimpleName().toLowerCase(), StringUtil.DOUBLE_QUOTE));
        writer.append(":[\n");
        Connection connection = null;
        try
        {
            connection = getConnection(true);
            Statement statement = createStatement(connection);
            _logger.debug(sql);

            int rowCount = 0;
            ResultSet rs = statement.executeQuery(sql);
            while (rs.next())
            {
                if (rowCount > 0)
                    writer.append(",\n");

                writer.append(BeanUtil.toJSONFromResultSet(rs));

                rowCount++;
            }
            rs.close();
            statement.close();
        }
        catch (SQLException e)
        {
            throw new DBException(DBErrorCode.QueryError, e);
        }
        finally
        {
            if (connection != null)
                _connectionPool.returnConnection(connection);
            writer.append("]}");
        }

    }

    public boolean hasTable(String tableName) throws DBException
    {
        return getTableNameList().contains(tableName);
    }

    public boolean isAutoCommit()
    {
        return autoCommit;
    }
}
