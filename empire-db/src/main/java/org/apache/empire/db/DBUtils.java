package org.apache.empire.db;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.apache.empire.commons.ObjectUtils;
import org.apache.empire.commons.Options;
import org.apache.empire.commons.StringUtils;
import org.apache.empire.data.DataType;
import org.apache.empire.data.list.DataListEntry;
import org.apache.empire.data.list.DataListHead;
import org.apache.empire.db.context.DBContextAware;
import org.apache.empire.db.exceptions.ConstraintViolationException;
import org.apache.empire.db.exceptions.QueryFailedException;
import org.apache.empire.db.exceptions.QueryNoResultException;
import org.apache.empire.db.exceptions.StatementFailedException;
import org.apache.empire.db.list.DBRecordListFactory;
import org.apache.empire.db.list.DBRecordListFactoryImpl;
import org.apache.empire.dbms.DBMSFeature;
import org.apache.empire.dbms.DBMSHandler;
import org.apache.empire.exceptions.InternalException;
import org.apache.empire.exceptions.InvalidArgumentException;
import org.apache.empire.exceptions.UnexpectedReturnValueException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DBUtils implements DBContextAware
{
    // Logger (Use logger from DBDatabase.class)
    protected static final Logger log = LoggerFactory.getLogger(DBDatabase.class);
    
    // Threshold for long running queries in milliseconds
    protected static long longRunndingStmtThreshold = 30000;
    // Default list capacity
    protected static int  DEFAULT_LIST_CAPACITY  = 10;
    // Max-Rows for list queries
    protected static int  MAX_QUERY_ROWS  = 999;
    
    // the context
    protected final DBContext context;
    // the dbms
    protected final DBMSHandler dbms;
    
    /**
     * Constructs an empty DBRecordSet object.
     * @param useFieldIndexMap 
     */
    public DBUtils(DBContext context)
    {
        this.context = context;
        this.dbms = context.getDbms();
    }

    /**
     * Returns the current Context
     * @return
     */
    @Override
    @SuppressWarnings("unchecked")
    public <T extends DBContext> T  getContext()
    {
        return ((T)context);
    }

    /**
     * Executes an update, insert or delete SQL-Statement.<BR>
     * We recommend to use a DBCommand object in order to build the sqlCmd.<BR>
     * <P>
     * @param sqlCmd the SQL-Command
     * @param sqlParams a list of objects to replace sql parameters
     * @param setGenKeys object to set the generated keys for
     * @return the row count for insert, update or delete or 0 for SQL statements that return nothing
     */
    public int executeSQL(String sqlCmd, Object[] sqlParams, DBMSHandler.DBSetGenKeys setGenKeys)
    {
        try 
        {   // Debug
            if (log.isInfoEnabled())
                log.info("Executing: " + sqlCmd);
            // execute SQL
            long start = System.currentTimeMillis();
            int affected = dbms.executeSQL(sqlCmd, sqlParams, context.getConnection(), setGenKeys);
            // number of affected records
            if (affected < 0)
                throw new UnexpectedReturnValueException(affected, "dbms.executeSQL()");
            // Log
            long execTime = (System.currentTimeMillis() - start);
            if (log.isInfoEnabled())
                log.info("executeSQL affected {} Records in {} ms ", affected, execTime);
            else if (execTime>=longRunndingStmtThreshold)
                log.warn("Long running statement took {} seconds for statement {}.", execTime / 1000, sqlCmd);
            // Return number of affected records
            return affected;
            
        } catch (SQLIntegrityConstraintViolationException sqle) {
            // ConstraintViolation
            throw new ConstraintViolationException(dbms, sqlCmd, sqle);
        } catch (SQLException sqle) {
            // Other error
            throw new StatementFailedException(dbms, sqlCmd, sqle);
        }    
    }
    
    /**
     * Executes a select SQL-Statement and returns a ResultSet containing the query results.<BR>
     * This function returns a JDBC ResultSet.<BR>
     * Instead of using this function directly you should use a DBReader object instead.<BR>
     * <P>
     * @param sqlCmd the SQL-Command
     * @param sqlParams a list of parameters for parameter queries (may depend on dbms)
     * @param scrollable true if the reader should be scrollable or false if not
     * @return the JDBC ResutSet
     */
    public ResultSet executeQuery(String sqlCmd, Object[] sqlParams, boolean scrollable)
    {
        try
        {   // Debug
            if (log.isDebugEnabled())
                log.debug("Executing: " + sqlCmd);
            // Execute the Statement
            long start = System.currentTimeMillis();
            ResultSet rs = dbms.executeQuery(sqlCmd, sqlParams, scrollable, context.getConnection());
            if (rs == null)
                throw new UnexpectedReturnValueException(rs, "dbms.executeQuery()");
            // Debug
            long queryTime = (System.currentTimeMillis() - start);
            if (log.isDebugEnabled())
                log.debug("executeQuery successful in {} ms", queryTime);
            else if (queryTime>=longRunndingStmtThreshold)
                log.warn("Long running query took {} seconds for statement {}.", queryTime / 1000, sqlCmd);
            // Return number of affected records
            return rs;
    
        } catch (SQLException sqle) 
        {   // Error
            throw new QueryFailedException(dbms, sqlCmd, sqle);
        } 
    }
    
    /**
     * Returns the value of the first row/column of a sql-query as an object.
     * If the query does not return a result the value ObjectUtils.NO_VALUE is returned.
     * 
     * @param sqlCmd the SQL-Command
     * @param sqlParams list of query parameter values
     * @param dataType the expected data type
     * @param forceResult if true a QueryNoResultException result is thrown if no record exists otherwise null is returned
     * 
     * @return the value of the first column in the first row of the query 
     */
    public Object querySingleValue(String sqlCmd, Object[] sqlParams, DataType dataType, boolean forceResult)
    {
        // Debug
        long start = System.currentTimeMillis();
        if (log.isDebugEnabled())
            log.debug("Executing: " + sqlCmd);
        // Read value
        Object result = dbms.querySingleValue(sqlCmd, sqlParams, dataType, context.getConnection());
        if (result==ObjectUtils.NO_VALUE)
        {   // Query returned no result
            if (forceResult)
                throw new QueryNoResultException(sqlCmd);
            else
                result = null;
        }
        // Debug
        long queryTime = (System.currentTimeMillis() - start);
        if (log.isDebugEnabled())
            log.debug("querySingleValue successful in {} ms. Result value={}.", queryTime, result);
        else if (queryTime>=longRunndingStmtThreshold)
            log.warn("Long running query took {} seconds for statement {}.", queryTime / 1000, sqlCmd);
        // done
        return result;
    }
    
    /**
     * Returns the value of the first row/column of a sql-query as an object.
     * If the query does not return a result a QueryNoResultException is thrown
     * 
     * @param cmd the Command object that contains the select statement
     * @param dataType the expected data type
     * 
     * @return the value of the first column in the first row of the query 
     */
    public final Object querySingleValue(DBCommand cmd, DataType dataType, boolean forceResult)
    {
        return querySingleValue(cmd.getSelect(), cmd.getParamValues(), dataType, forceResult);
    }
    
    /**
     * Returns the value of the first row/column of a sql-query as an object.
     * If the query does not return a result a QueryNoResultException is thrown
     * 
     * @param cmd the Command object that contains the select statement
     * 
     * @return the value of the first column in the first row of the query 
     */
    public final Object querySingleValue(DBCommand cmd, boolean forceResult)
    {
        return querySingleValue(cmd, DataType.UNKNOWN, forceResult);  
    }
    
    /**
     * Returns the value of the first row/column of a sql-query as an object.
     * If the query does not return a result a QueryNoResultException is thrown
     * 
     * @param cmd the Command object that contains the select statement
     * 
     * @return the value of the first column in the first row of the query 
     */
    public final Object querySingleValue(DBCommand cmd)
    {
        return querySingleValue(cmd, DataType.UNKNOWN, true);  
    }

    /**
     * Returns the value of the first row/column of a sql-query as an int.
     * If the query does not return a result or if the query result is NULL, then the defaultValue is returned
     * 
     * @param cmd the Command object that contains the select statement
     * @param defaultValue the default value if no value was returned by the database
     *
     * @return the result as a int value
     */
    public final int querySingleInt(String sqlCmd, Object[] sqlParams, int defaultValue)
    { 
        Object value = querySingleValue(sqlCmd, sqlParams, DataType.INTEGER, false);
        return ObjectUtils.getInteger(value, defaultValue);
    }

    /**
     * Returns the value of the first row/column of a sql-query as an int.
     * If the query does not return a result or if the query result is NULL, then the defaultValue is returned
     * 
     * @param cmd the Command object that contains the select statement
     * @param defaultValue the default value if no value was returned by the database
     *
     * @return the result as a int value
     */
    public final int querySingleInt(DBCommand cmd, int defaultValue)
    { 
        Object value = querySingleValue(cmd.getSelect(), cmd.getParamValues(), DataType.INTEGER, false);
        return ObjectUtils.getInteger(value, defaultValue);
    }

    /**
     * Returns the value of the first row/column of a sql-query as an int.
     * If the query does not return a result a QueryNoResultException is thrown
     * 
     * @param cmd the Command object that contains the select statement
     *
     * @return the result as a int value
     */
    public final int querySingleInt(DBCommand cmd)
    { 
        Object value = querySingleValue(cmd.getSelect(), cmd.getParamValues(), DataType.INTEGER, true);
        return ObjectUtils.getInteger(value);
    }

    /**
     * Returns the value of the first row/column of a sql-query as an int.
     * If the query does not return a result or if the query result is NULL, then the defaultValue is returned
     * 
     * @param cmd the Command object that contains the select statement
     * @param defaultValue the default value if no value was returned by the database
     *
     * @return the result as a int value
     */
    public final long querySingleLong(String sqlCmd, Object[] sqlParams, long defaultValue)
    { 
        Object value = querySingleValue(sqlCmd, sqlParams, DataType.INTEGER, false);
        return ObjectUtils.getLong(value, defaultValue);
    }
    
    /**
     * Returns the value of the first row/column of a sql-query as a long.
     * If the query does not return a result or if the query result is NULL, then the defaultValue is returned
     * 
     * @param cmd the Command object that contains the select statement
     * @param defaultValue the default value
     * 
     * @return the result as a long value
     */
    public final long querySingleLong(DBCommand cmd, long defaultValue)
    { 
        Object value = querySingleValue(cmd.getSelect(), cmd.getParamValues(), DataType.INTEGER, false);
        return ObjectUtils.getLong(value, defaultValue);
    }

    /**
     * Returns the value of the first row/column of a sql-query as a long.
     * If the query does not return a result a QueryNoResultException is thrown
     * 
     * @param cmd the Command object that contains the select statement
     *
     * @return the result as a long value
     */
    public final long querySingleLong(DBCommand cmd)
    { 
        Object value = querySingleValue(cmd.getSelect(), cmd.getParamValues(), DataType.INTEGER, true);
        return ObjectUtils.getLong(value);
    }
    
    /**
     * Returns the value of the first row/column of a sql-query as a string.
     * If the query does not return a result or if the query result is NULL, then the defaultValue is returned
     * 
     * @param cmd the Command object that contains the select statement
     * @param defaultValue the default value if no value was returned by the database
     *
     * @return the result as a String object, if no result a empty String
     */
    public final String querySingleString(DBCommand cmd, String defaultValue)
    { 
        Object value = querySingleValue(cmd.getSelect(), cmd.getParamValues(), DataType.VARCHAR, false);
        return StringUtils.toString(value, defaultValue);
    }
    
    /**
     * Returns the value of the first row/column of a sql-query as a string.
     * If the query does not return a result a QueryNoResultException is thrown.
     * If the query result is NULL an empty string is returned.
     * 
     * @param cmd the Command object that contains the select statement
     *
     * @return the result as a String object, if no result a empty String
     */
    public final String querySingleString(DBCommand cmd)
    { 
        Object value = querySingleValue(cmd.getSelect(), cmd.getParamValues(), DataType.VARCHAR, true);
        return StringUtils.toString(value);
    }

    /**
     * Returns the number of rows returned by executing the select statement
     * @param cmd the select command
     * @return the number of rows that will be returned
     */
    public int queryRowCount(DBCommand cmd)
    {   // execute Native Query
        if (cmd==null || !cmd.isValid())
            return 0;
        // Check for aggregation
        boolean aggregate = false;
        DBColumnExpr[] exprList = cmd.getSelectExprList();
        for (int i=0; i<exprList.length; i++)
        {
            if (exprList[i].isAggregate())
            {   aggregate = true;
                break;
            }
        }
        // check if aggregate 
        if (aggregate)
        {   // For Aggregations: Wrap  
            DBCommand subCmd = cmd.clone();
            subCmd.clearOrderBy();
            String sql = "SELECT COUNT(*) FROM ("+subCmd.getSelect() + ") q";
            return querySingleInt(sql, null, 0);
        }
        // find any rowset
        DBRowSet rs = exprList[0].getUpdateColumn().getRowSet();
        // create the count command
        DBCommand countCmd = cmd.clone();
        countCmd.clearSelect();
        countCmd.clearOrderBy();
        countCmd.select(rs.count());
        // perform query
        return querySingleInt(countCmd);
    }

    /**
     * Adds the first column of a query result to a collection.
     * If the query has no result, an empty list is returned.
     * 
     * @param c the class type for the list 
     * @param <T> the type for the list
     * @param sqlCmd the SQL statement
     * @param dataType the expected data type
     * @param maxRows maximum number of rows or -1 for all rows
     * 
     * @return the number of elements that have been added to the collection 
     */
    public <T> int querySimpleList(Class<T> c, String sqlCmd, Object[] sqlParams, DataType dataType, Collection<T> result, int maxRows)
    {   // Start query
        ResultSet rs = null;
        try
        {   // Log performance
            long start = System.currentTimeMillis();
            if (log.isDebugEnabled())
                log.debug("Executing: " + sqlCmd);
            // Get the next Value
            rs = dbms.executeQuery(sqlCmd, sqlParams, false, context.getConnection());
            if (rs == null)
                throw new UnexpectedReturnValueException(rs, "dbms.executeQuery()");
            // Check Result
            int count=0;
            while (rs.next() && (maxRows<0 || count<maxRows))
            {   
                T item = ObjectUtils.convert(c, dbms.getResultValue(rs, 1, dataType));
                result.add(item);
                count++;
            }
            // Debug
            long queryTime = (System.currentTimeMillis() - start);
            if (log.isDebugEnabled())
                log.debug("querySimpleList retured {} items in {} ms.", count, queryTime);
            else if (queryTime>=longRunndingStmtThreshold)
                log.warn("Long running query took {} seconds for statement {}.", queryTime / 1000, sqlCmd);
            // done
            return count;
        } catch (ClassCastException e) 
        {   log.error("querySingleValue cast exception: ", e);
            throw new InternalException(e);
        } catch (SQLException sqle) 
        {   // Error
            throw new QueryFailedException(dbms, sqlCmd, sqle);
        } finally
        { // Cleanup
            dbms.closeResultSet(rs);
        }
    }
    
    /**
     * Adds the first column of a query result to a collection.
     * If the query has no result, an empty list is returned.
     * 
     * @param c the class type for the list 
     * @param <T> the type for the list
     * @param cmd the Command object that contains the select statement
     * 
     * @return the number of elements that have been added to the collection 
     */
    public final <T> int querySimpleList(Class<T> c, DBCommand cmd, Collection<T> result)
    {
        return querySimpleList(c, cmd.getSelect(), cmd.getParamValues(), DataType.UNKNOWN, result, MAX_QUERY_ROWS); 
    }

    /**
     * Returns a one dimensional array from an sql query.
     * The array is filled with the values of the first column.
     * 
     * @param c the class type for the list 
     * @param <T> the type for the list
     * @param cmd the Command object that contains the select statement
     * 
     * @return a list of the values of the first column of an sql query 
     */
    public final <T> List<T> querySimpleList(Class<T> c, DBCommand cmd)
    {   // Execute the  Statement
        List<T> result = new ArrayList<T>();
        if (querySimpleList(c, cmd, result)<0)
            return null;
        return result;
    }
    
    /**
     * Returns a one dimensional array from an sql query.
     * The array is filled with the values of the first column.
     * 
     * @param cmd the Command object that contains the select statement
     * @return a list of values of type Object 
     */
    public final List<Object> querySimpleList(DBCommand cmd)
    {   // Execute the  Statement
        return querySimpleList(Object.class, cmd);
    }
    
    /**
     * Fills an option list provided with the result from a query.
     * The option list is filled with the values of the first and second column.
     * 
     * @param sqlCmd the SQL statement
     * @param options the option list to where the options are added
     * @return an Options object containing a set a of values and their corresponding names 
     */
    public int queryOptionList(String sqlCmd, Object[] sqlParams, Options options)
    {   // Execute the  Statement
        ResultSet rs = null;
        try
        {   // Debug
            long start = System.currentTimeMillis();
            if (log.isDebugEnabled())
                log.debug("Executing: " + sqlCmd);
            // Get the next Value
            rs = dbms.executeQuery(sqlCmd, sqlParams, false, context.getConnection());
            if (rs == null)
                throw new UnexpectedReturnValueException(rs, "dbms.executeQuery()");
            if (rs.getMetaData().getColumnCount()<2)
                throw new InvalidArgumentException("sqlCmd", sqlCmd);
            // Check Result
            int count = 0;
            while (rs.next())
            {
                Object value = rs.getObject(1);
                String text  = rs.getString(2);
                options.add(value, text, true);
                count++;
            }
            // Debug
            long queryTime = (System.currentTimeMillis() - start);
            if (log.isDebugEnabled())
                log.debug("queryOptionList retured {} items in {} ms.", count, queryTime);
            else if (queryTime>=longRunndingStmtThreshold)
                log.warn("Long running query took {} seconds for statement {}.", queryTime / 1000, sqlCmd);
            // done
            return count;
        } catch (SQLException sqle) 
        {   // Error
            throw new QueryFailedException(dbms, sqlCmd, sqle);
        } finally
        { // Cleanup
            dbms.closeResultSet(rs);
        }
    }
    
    /**
     * Fills an option list provided with the result from a query.
     * The option list is filled with the values of the first and second column.
     * 
     * @param cmd the Command object that contains the select statement
     * @param options the option list to where the options are added
     * @return an Options object containing a set a of values and their corresponding names 
     */
    public final int queryOptionList(DBCommand cmd, Options options)
    {   // Execute the  Statement
        return queryOptionList(cmd.getSelect(), cmd.getParamValues(), options); 
    }

    /**
     * Returns a list of key value pairs from an sql query.
     * The option list is filled with the values of the first and second column.
     * 
     * @param cmd the Command object that contains the select statement
     * @return an Options object containing a set a of values and their corresponding names 
     */
    public final Options queryOptionList(DBCommand cmd)
    {   // Execute the  Statement
        Options options = new Options();
        queryOptionList(cmd.getSelect(), cmd.getParamValues(), options);
        return options; 
    }
    
    /**
     * Adds the result of a query to a given collection.<br>
     * The individual rows will be added as an array of objects (object[])
     * <p>This function should only be used for small lists.
     * Otherwise a DBReader should be used!</p>
     * 
     * @param sqlCmd the SQL statement
     * @return a list of object arrays
     */
    public int queryObjectList(String sqlCmd, Object[] sqlParams, Collection<Object[]> result, int maxRows)
    {   // Perform query
        ResultSet rs = null;
        try
        {   // Log performance
            long start = System.currentTimeMillis();
            if (log.isDebugEnabled())
                log.debug("Executing: " + sqlCmd);
            // Get the next Value
            rs = dbms.executeQuery(sqlCmd, sqlParams, false, context.getConnection());
            if (rs == null)
                throw new UnexpectedReturnValueException(rs, "dbms.executeQuery()");
            // Read List
            int colCount = rs.getMetaData().getColumnCount();
            int count = 0;
            while (rs.next() && (maxRows<0 || count<maxRows))
            {   // Read row
                Object[] item = new Object[colCount];
                for (int i=0; i<colCount; i++)
                {   // Read from Resultset
                    item[i] = dbms.getResultValue(rs, i+1, DataType.UNKNOWN);
                }
                result.add(item);
                count++;
            }
            // Debug
            long queryTime = (System.currentTimeMillis() - start);
            if (log.isDebugEnabled())
                log.debug("queryObjectList retured {} items in {} ms.", count, queryTime);
            else if (queryTime>=longRunndingStmtThreshold)
                log.warn("Long running query took {} seconds for statement {}.", queryTime / 1000, sqlCmd);
            // done
            return count;
        } catch (SQLException sqle) 
        {   // Error
            throw new QueryFailedException(dbms, sqlCmd, sqle);
        } finally
        { // Cleanup
            dbms.closeResultSet(rs);
        }
    } 

    /**
     * Returns the result of a query as a list Object-Arrays 
     * This function should only be used for small lists.
     * 
     * @param cmd the Command object that contains the select statement
     * @return a list of object arrays
     */
    public final List<Object[]> queryObjectList(DBCommand cmd)
    {   // Execute the  Statement
        List<Object[]> result = new ArrayList<Object[]>();
        queryObjectList(cmd.getSelect(), cmd.getParamValues(), result, MAX_QUERY_ROWS);
        return result;
    }

    /**
     * Returns all values of the first row of a sql-query as an array.
     * If the query does not return a result a QueryNoResultException is thrown
     * 
     * @param sqlCmd the SQL-Command
     * @param sqlParams list of query parameter values
     * 
     * @return the values of the first row
     */
    public Object[] querySingleRow(String sqlCmd, Object[] sqlParams)
    {
        List<Object[]> result = new ArrayList<Object[]>(1);
        queryObjectList(sqlCmd, sqlParams, result, 1);
        if (result.size()<1)
            throw new QueryNoResultException(sqlCmd);
        return result.get(0);
    }
    
    /**
     * Returns all values of the first row of a sql-query as an array.
     * If the query does not return a result a QueryNoResultException is thrown
     * 
     * @param cmd the Command object that contains the select statement
     * 
     * @return the values of the first row 
     */
    public final Object[] querySingleRow(DBCommand cmd)
    {
        return querySingleRow(cmd.getSelect(), cmd.getParamValues()); 
    }

    /**
     * Crates a default DataListHead for a DataListEntry class
     * The DataListEntry class must provide the following constructor
     *      DataListEntry(DataListHead<? extends DataListEntry> head, int rownum, Object values[])
     * @param entryClass the entryClass for which to create the list head 
     * @return
     */
    protected <T extends DataListEntry> DataListHead<T> createDefaultDataListHead(DBCommand cmd, Class<T> entryClass) 
    {
        return new DataListHead<T>(entryClass, cmd.getSelectExprList());
    }
    
    /**
     * Executes a query and returns a list of DataListEntry items
     * @param sqlCmd the SQL-Command for the query
     * @param listHead the HeadInfo to be used for each list item
     * @param first the number of records to skip from the beginning of the result
     * @param pageSize the maximum number of item to return
     * @return the list 
     */
    public <T extends DataListEntry> List<T> queryDataList(DBCommand cmd, DataListHead<T> listHead, int first, int pageSize)
    {
        DBReader r = new DBReader(context);
        try
        {   // check pageSize
            if (pageSize==0)
            {   log.warn("PageSize must not be 0. Setting to -1 for all records!");
                pageSize = -1;
            }
            // set range
            DBMSHandler dbms = cmd.getDatabase().getDbms();
            if (pageSize>0 && dbms.isSupported(DBMSFeature.QUERY_LIMIT_ROWS))
            {   // let the database limit the rows
                if (first>0 && dbms.isSupported(DBMSFeature.QUERY_SKIP_ROWS))
                {   // let the database skip the rows
                    cmd.skipRows(first);
                    // no need to skip rows ourself
                    first = 0;
                }
                cmd.limitRows(first+pageSize);
            }
            // Runquery
            r.open(cmd);
            if (first>0) 
            {   // skip rows
                r.skipRows(first);
            }
            // Create a list of data entries
            int maxCount = (pageSize>=0) ? pageSize : MAX_QUERY_ROWS;
            ArrayList<T> list = new ArrayList<T>((pageSize>=0) ? pageSize : 10);
            // add data
            int rownum = 0;
            while (r.moveNext() && maxCount != 0)
            {   // Create bean an init
                T entry = listHead.newEntry(++rownum, r);
                if (entry==null)
                    continue;
                // add entry
                list.add(entry);
                // Decrease count
                if (maxCount > 0)
                    maxCount--;
            }
            // check
            if (rownum==MAX_QUERY_ROWS)
            {
                log.warn("********************************************************");
                log.warn("Query Result was limited to {} by MAX_QUERY_ROWS", rownum);
                log.warn("********************************************************");
            }
            return list;
        }
        finally
        {
            r.close();
        }
    }
    
    /**
     * Queries a list of DataListEntry items
     */
    public final <T extends DataListEntry> List<T> queryDataList(DBCommand cmd, Class<T> entryClass)
    {
        return queryDataList(cmd, createDefaultDataListHead(cmd, entryClass), 0, MAX_QUERY_ROWS);
    }
    
    /**
     * Queries a list of DataListEntry items
     */
    @SuppressWarnings("unchecked")
    public final <T extends DataListEntry> List<T> queryDataList(DBCommand cmd)
    {
        return (List<T>)queryDataList(cmd, DataListEntry.class);
    }

    /**
     * Queries a single DataListEntry item
     */
    public final <T extends DataListEntry> T queryDataEntry(DBCommand cmd, Class<T> entryClass, boolean forceResult)
    {
        List<T> dle = queryDataList(cmd, createDefaultDataListHead(cmd, entryClass), 0, 1);
        if (dle.isEmpty())
        {   if (forceResult)
                throw new QueryNoResultException(cmd.getSelect());
            return null;
        }
        return dle.get(0);
    }
    
    /**
     * Queries a single DataListEntry item
     */
    public final <T extends DataListEntry> T queryDataEntry(DBCommand cmd, Class<T> entryClass)
    {
        return queryDataEntry(cmd, entryClass, true);
    }
    
    /**
     * Queries a single DataListEntry item
     */
    @SuppressWarnings("unchecked")
    public final <T extends DataListEntry> T queryDataEntry(DBCommand cmd)
    {
        return (T)queryDataEntry(cmd, DataListEntry.class);
    }

    /**
     * Crates a default DBRecordListFactory for a DBRecord class
     * The DBRecord class must provide the following constructor
     *      DBRecord(DBContext context, DBRowSet rowset)
     * @param recordClass the recordClass for which to create the list head 
     * @return
     */
    protected <T extends DBRecord> DBRecordListFactory<T> createDefaultRecordListFactory(DBContext context, DBRowSet rowset, Class<T> recordClass) 
    {
        return new DBRecordListFactoryImpl<T>(recordClass, context, rowset);
    }
    
    /**
     * Executes a query and returns a list of DBRecord items
     * @param sqlCmd the SQL-Command for the query
     * @param listHead the HeadInfo to be used for each list item
     * @param first the number of records to skip from the beginning of the result
     * @param pageSize the maximum number of item to return
     * @return the list 
     */
    public <T extends DBRecord> List<T> queryRecordList(DBCommand cmd, DBRecordListFactory<T> factory, int first, int pageSize)
    {
        List<T> list = null;
        DBReader r = new DBReader(context);
        try
        {   // prepare
            factory.prepareQuery(cmd);
            // check pageSize
            if (pageSize==0)
            {   log.warn("PageSize must not be 0. Setting to -1 for all records!");
                pageSize = -1;
            }
            // set range
            DBMSHandler dbms = cmd.getDatabase().getDbms();
            if (pageSize>0 && dbms.isSupported(DBMSFeature.QUERY_LIMIT_ROWS))
            {   // let the database limit the rows
                if (first>0 && dbms.isSupported(DBMSFeature.QUERY_SKIP_ROWS))
                {   // let the database skip the rows
                    cmd.skipRows(first);
                    // no need to skip rows ourself
                    first = 0;
                }
                cmd.limitRows(first+pageSize);
            }
            // Runquery
            r.open(cmd);
            if (first>0) 
            {   // skip rows
                r.skipRows(first);
            }
            // Create a list of data entries
            int maxCount = (pageSize>=0) ? pageSize : MAX_QUERY_ROWS;
            list = factory.newList((pageSize>=0) ? pageSize : DEFAULT_LIST_CAPACITY);
            // add data
            int rownum = 0;
            while (r.moveNext() && maxCount != 0)
            {   // Create bean an init
                T entry = factory.newRecord(++rownum, r);
                if (entry==null)
                    continue;
                // add entry
                if (entry.isValid())
                    list.add(entry);
                else
                    log.warn("Record {} is not valid thus it will not be added to the RecordListQuery.", rownum);
                // Decrease count
                if (maxCount > 0)
                    maxCount--;
            }
            // check
            if (rownum==MAX_QUERY_ROWS)
            {
                log.warn("********************************************************");
                log.warn("Query Result was limited to {} by MAX_QUERY_ROWS", rownum);
                log.warn("********************************************************");
            }
            return list;
        }
        finally
        {   // close reader
            r.close();
            // complete
            if (list!=null)
                factory.completeQuery(list);
        }
    }

    public final <T extends DBRecord> List<T> queryRecordList(DBCommand cmd, DBRowSet rowset)
    {
        @SuppressWarnings("unchecked")
        DBRecordListFactory<T> factory = (DBRecordListFactory<T>)createDefaultRecordListFactory(context, rowset, DBRecord.class);
        return queryRecordList(cmd, factory, 0, -1);
    }
    
}
