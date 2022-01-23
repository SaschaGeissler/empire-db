/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.empire.db.driver;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.apache.empire.commons.DateUtils;
import org.apache.empire.commons.ObjectUtils;
import org.apache.empire.commons.StringUtils;
import org.apache.empire.data.DataType;
import org.apache.empire.db.DBBlobData;
import org.apache.empire.db.DBClobData;
import org.apache.empire.db.DBCmdParam;
import org.apache.empire.db.DBColumn;
import org.apache.empire.db.DBColumnExpr;
import org.apache.empire.db.DBCombinedCmd;
import org.apache.empire.db.DBCommand;
import org.apache.empire.db.DBCommandExpr;
import org.apache.empire.db.DBDDLGenerator.DDLAlterType;
import org.apache.empire.db.DBDatabase;
import org.apache.empire.db.DBDatabaseDriver;
import org.apache.empire.db.DBExpr;
import org.apache.empire.db.DBRelation;
import org.apache.empire.db.DBSQLScript;
import org.apache.empire.db.DBTable;
import org.apache.empire.db.DBTableColumn;
import org.apache.empire.db.exceptions.EmpireSQLException;
import org.apache.empire.db.exceptions.QueryFailedException;
import org.apache.empire.exceptions.InvalidArgumentException;
import org.apache.empire.exceptions.NotSupportedException;
import org.apache.empire.exceptions.UnexpectedReturnValueException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The DBDatabaseDriver class is an abstract base class for all database drivers.
 * Its purpose is to handle everything that is - or might be - database vendor specific. 
 */
public abstract class DBDatabaseDriverBase implements DBDatabaseDriver
{
    private static final Logger log = LoggerFactory.getLogger(DBDatabaseDriver.class);
      
    // Illegal name chars and reserved SQL keywords
    protected static final char[]   ILLEGAL_NAME_CHARS   = new char[] { '@', '?', '>', '=', '<', ';', ':', 
                                                                    '/', '.', '-', ',', '+', '*', ')', '(',
                                                                    '\'', '&', '%', '!', ' '
                                                                  };        
    protected static final String[] GENERAL_SQL_KEYWORDS = new String[] { "user", "group", 
                                                           "table", "column", "view", "index", "constraint", 
                                                           "select", "udpate", "insert", "alter", "delete", 
                                                           "order" };        
    protected final Set<String> reservedSQLKeywords;

    /**
     * This class is used to emulate sequences by using a sequence table.
     * It is used with the executeSQL function and only required for insert statements
     */
    public static class DBSeqTable extends DBTable
    {
        // *Deprecated* private static final long serialVersionUID = 1L;
      
        public DBColumn C_SEQNAME;
        public DBColumn C_SEQVALUE;
        public DBColumn C_TIMESTAMP;

        /**
         * Constructor
         * 
         * @param tableName the table name
         * @param db the database object
         */
        public DBSeqTable(String tableName, DBDatabase db)
        {
            super(tableName, db);
            // Add all Colums
            C_SEQNAME   = addColumn("SeqName",  DataType.VARCHAR,   40, true);
            C_SEQVALUE  = addColumn("SeqValue", DataType.INTEGER,    0, true);
            C_TIMESTAMP = addColumn("SeqTime",  DataType.DATETIME,   0, true);
            // Primary Key
            setPrimaryKey(new DBColumn[] { C_SEQNAME });
        }

        // Overrideable
        public Object getNextValue(String SeqName, long minValue, Connection conn)
        {
            DBDatabaseDriver driver = db.getDriver();
            // Create a Command
            PreparedStatement stmt = null;
            try
            {   // The select Statement
                DBCommand cmd = driver.createCommand(db);
                DBCmdParam nameParam = cmd.addParam(SeqName);
                cmd.select(C_SEQVALUE);
                cmd.select(C_TIMESTAMP);
                cmd.where (C_SEQNAME.is(nameParam));
                String selectCmd = cmd.getSelect();
                // Get the next Value
                long seqValue = 0;
                while (seqValue == 0)
                {
                    // stmt = conn.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY);
                    stmt = conn.prepareStatement(selectCmd, ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY);
                    stmt.setString(1, SeqName);
                    // Query existing value
                    ResultSet rs = stmt.executeQuery();
                    if (rs.next())
                    { // Read the Sequence Value
                        seqValue = Math.max(rs.getLong(1) + 1, minValue);
                        java.sql.Timestamp current = rs.getTimestamp(2);
                        driver.closeResultSet(rs);
                        // Update existing Record
                        cmd.clear();
                        DBCmdParam name = cmd.addParam(SeqName);
                        DBCmdParam time = cmd.addParam(current);
                        cmd.set(C_SEQVALUE.to(seqValue));
                        cmd.set(C_TIMESTAMP.to(DBDatabase.SYSDATE));
                        cmd.where(C_SEQNAME.is(name));
                        cmd.where(C_TIMESTAMP.is(time));
                        if (driver.executeSQL(cmd.getUpdate(), cmd.getParamValues(), conn, null) < 1)
                            seqValue = 0; // Try again
                    } 
                    else
                    { // Close Reader
                        driver.closeResultSet(rs);
                        // sequence does not exist
                        seqValue = minValue;
                        log.warn("Sequence {} does not exist! Creating sequence with start-value of {}", SeqName, seqValue);
                        // create a new sequence entry
                        cmd.clear();
                        cmd.set(C_SEQNAME.to(SeqName));
                        cmd.set(C_SEQVALUE.to(seqValue));
                        cmd.set(C_TIMESTAMP.to(DBDatabase.SYSDATE));
                        if (driver.executeSQL(cmd.getInsert(), cmd.getParamValues(), conn, null) < 1)
                            seqValue = 0; // Try again
                    }
                    // check for concurrency problem
                    if (seqValue == 0)
                        log.warn("Failed to increment sequence {}. Trying again!", SeqName);
                    // close
                    closeStatement(stmt);
                    cmd.clear();
                    rs = null;
                }
                if (log.isInfoEnabled())
                    log.info("Sequence {} incremented to {}.", SeqName, seqValue);
                return new Long(seqValue);
            } catch (SQLException e) {
                // throw exception
                throw new EmpireSQLException(this, e);
            } finally
            { // Cleanup
                closeStatement(stmt);
            }
        }

        /*
         * cleanup
         */
        private void closeStatement(Statement stmt)
        {
            try
            { // Statement close
                if (stmt != null)
                    stmt.close();
                // done
                return;
            } catch (SQLException sqle) { 
                // Commit failed!
                throw new EmpireSQLException(this, sqle);
            }
        }
    }
    
    /**
     * Constructor
     */
    protected DBDatabaseDriverBase()
    {
        // Initialize List of reserved Keywords
        reservedSQLKeywords = new HashSet<String>(GENERAL_SQL_KEYWORDS.length);
        for (String keyWord:GENERAL_SQL_KEYWORDS){
             reservedSQLKeywords.add(keyWord);
        }
    }
    
    /**
     * Called when a database is opened
     */
    @Override
    public void attachDatabase(DBDatabase db, Connection conn)
    {
        /* Nothing here */
    }

    /**
     * Called when a database is closed
     */
    @Override
    public void detachDatabase(DBDatabase db, Connection conn)
    {
        /* Nothing here */
    }

    /**
     * This function creates a DBCommand derived object this database
     * @param db the database for which to create a command object for
     * @return a DBCommand object
     */
    @Override
    public abstract DBCommand createCommand(DBDatabase db);

    /**
     * This function gives the driver a chance to provide a custom implementation 
     * for a combined command such as UNION or INTERSECT 
     * @param left the left command
     * @param keyWord the key word (either "UNION" or "INTERSECT")
     * @param left the right command
     * @return a DBCommandExpr object
     */
    @Override
    public DBCommandExpr createCombinedCommand(DBCommandExpr left, String keyWord, DBCommandExpr right)
    {
        return new DBCombinedCmd(left, keyWord, right);
    }

    /**
     * Returns whether or not a particular feature is supported by this driver
     * @param type type of requested feature. @see DBDriverFeature
     * @return true if the features is supported or false otherwise
     */
    @Override
    public abstract boolean isSupported(DBDriverFeature type);

    /**
     * Detects whether a table or column name needs to be quoted or not<br>
     * By default all reserved SQL keywords as well as names 
     * containing a "-", "/", "+" or " " require quoting.<br>
     * Overrides this function to add database specific keywords like "user" or "count"  
     */
    public boolean detectQuoteName(String name)
    {
        // Check for reserved names
        if (reservedSQLKeywords.contains(name.toLowerCase()))
            return true;
        // Check for illegalNameChars
        int len = name.length();
        for (int i=0; i<len; i++)
        {   char c = name.charAt(i);
            for (int j=0; j<ILLEGAL_NAME_CHARS.length; j++)
            {   char ic = ILLEGAL_NAME_CHARS[j]; 
                if (c>ic)
                    break;
                if (c==ic)
                    return true;
            }    
        }
        // Quoting not necessary
        return false;
    }

    /**
     * Appends a table, view or column name to an SQL phrase. 
     * 
     * @param sql the StringBuilder containing the SQL phrase.
     * @param name the name of the object (table, view or column)
     * @param useQuotes use quotes or not
     */
    @Override
    public void appendObjectName(StringBuilder sql, String name, Boolean useQuotes)
    {
        if (useQuotes==null)
            useQuotes = detectQuoteName(name);
        // Check whether to use quotes or not
        if (useQuotes)
            sql.append(getSQLPhrase(DBSqlPhrase.SQL_QUOTES_OPEN));
        // Append Name
        sql.append(name);
        // End Quotes
        if (useQuotes)
            sql.append(getSQLPhrase(DBSqlPhrase.SQL_QUOTES_CLOSE));
    }

    /**
     * Returns a timestamp that is used for record updates.
     * @param conn the connection that might be used 
     * @return the current date and time.
     */
    @Override
    public Timestamp getUpdateTimestamp(Connection conn)
    {
        // Default implementation
        java.util.Date date = new java.util.Date();
        return new java.sql.Timestamp(date.getTime());
    }

    /**
     * Returns the next value of a named sequence The numbers are used for fields of type DBExpr.DT_AUTOINC.<BR>
     * If a driver supports this function it must return true for isSupported(DBDriverFeature.SEQUENCES).
     * 
     * @param db the database
     * @param SeqName the name of the sequence
     * @param minValue the minimum value of the sequence
     * @param conn a valid database connection
     * @return a new unique sequence value or null if an error occurred
     */
    public abstract Object getNextSequenceValue(DBDatabase db, String SeqName, int minValue, Connection conn);
    
    /**
     * Returns an expression for creating a sequence value.
     * This is intended for the use with INSERT INTO statements where many records are affected. 
     * @param col the column for which to obtain an expression providing the next sequence value
     * @return an expression for the next sequence value
     */
    public abstract DBColumnExpr getNextSequenceValueExpr(DBTableColumn column);
    
    /**
     * Returns an auto-generated value for a particular column
     * 
     * @param db the database
     * @param column the column for which a value is required
     * @param conn a valid database connection
     * @return the auto-generated value
     */
    @Override
    public Object getColumnAutoValue(DBDatabase db, DBTableColumn column, Connection conn)
    {
        // Supports sequences?
        DataType type = column.getDataType();
        if (type == DataType.AUTOINC)
        {   // Use a numeric sequence
            if (isSupported(DBDriverFeature.SEQUENCES)==false)
                return null; // Create Later
            String sequenceName = column.getSequenceName();
            return getNextSequenceValue(db, sequenceName, 1, conn);
        }
        else if (type== DataType.UNIQUEID)
        {   // emulate using java.util.UUID
            return UUID.randomUUID();
        }
        else if (type==DataType.DATE || type==DataType.DATETIME || type==DataType.TIMESTAMP)
        {   if (conn==null)
                return null; // No connection
            // Get database system's date and time
            Date ts = getUpdateTimestamp(conn);
            return (type==DataType.DATE ? DateUtils.getDateOnly(ts) : ts);
        }
        // Other types
        throw new NotSupportedException(this, "getColumnAutoValue() for "+type);
    }
    
    /**
     * <P>
     * Reads a single column value from the given JDBC ResultSet and returns a value object of desired data type.<BR> 
     * See {@link DBExpr#getValueClass(DataType)} for java class type mapping.
     * <P>
     * This gives the driver the opportunity to change the value
     * i.e. to simulate missing data types with other types.
     * <P>
     * @param rset the sql Resultset with the current data row
     * @param columnIndex one based column Index of the desired column
     * @param dataType the required data type
     * 
     * @return the value of the Column 
     * 
     * @throws SQLException if a database access error occurs
     */
    @Override
    public Object getResultValue(ResultSet rset, int columnIndex, DataType dataType)
        throws SQLException
    {
        if (dataType == DataType.DATETIME || dataType == DataType.TIMESTAMP)
        { // Get Timestamp (do not use getObject()!) 
            return rset.getTimestamp(columnIndex);
        } 
        else if (dataType == DataType.CLOB)
        {
            java.sql.Clob clob = rset.getClob(columnIndex);
            return ((clob != null) ? clob.getSubString(1, (int) clob.length()) : null);
        } 
        else if (dataType == DataType.BLOB)
        { // Get bytes of a binary large object
            java.sql.Blob blob = rset.getBlob(columnIndex);
            return ((blob != null) ? blob.getBytes(1, (int) blob.length()) : null);
        } 
        else
        {
            return rset.getObject(columnIndex);
        }
    }
   
    /**
     * Returns a sql string for a given value. 
     * Text will be enclosed in single quotes and existing single quotes will be doubled.
     * Empty strings are treated as null.
     * The syntax of Date, Datetime and Boolean values are DBMS specific.
     * 
     * @param value the value which is inserted to the new String
     * @param type the sql data type of the supplied value
     * @return the sql string representing this value
     */
    @Override
    public String getValueString(Object value, DataType type)
    { 
        if (value instanceof Enum<?>)
        {   // convert enum
            log.warn("Enum of type {} supplied for getValueString. Converting value...", value.getClass().getName());
            value = ObjectUtils.getEnumValue((Enum<?>)value, type.isNumeric());
        }
        if (ObjectUtils.isEmpty(value))
        {   // null
            return getSQLPhrase(DBSqlPhrase.SQL_NULL);
        }
        // set string buffer
        switch (type)
        {
            case DATE:
                return getSQLDateTimeString(value, DBSqlPhrase.SQL_DATE_TEMPLATE, DBSqlPhrase.SQL_DATE_PATTERN, DBSqlPhrase.SQL_CURRENT_DATE);
            case DATETIME:
                // Only date (without time) provided?
                if (!DBDatabase.SYSDATE.equals(value) && !(value instanceof Date) && ObjectUtils.lengthOf(value)<=10)
                    return getSQLDateTimeString(value, DBSqlPhrase.SQL_DATE_TEMPLATE, DBSqlPhrase.SQL_DATE_PATTERN, DBSqlPhrase.SQL_CURRENT_TIMESTAMP);
                // Complete Date-Time Object with time 
                return getSQLDateTimeString(value, DBSqlPhrase.SQL_DATETIME_TEMPLATE, DBSqlPhrase.SQL_DATETIME_PATTERN, DBSqlPhrase.SQL_CURRENT_TIMESTAMP);
            case TIMESTAMP:
                return getSQLDateTimeString(value, DBSqlPhrase.SQL_TIMESTAMP_TEMPLATE, DBSqlPhrase.SQL_TIMESTAMP_PATTERN, DBSqlPhrase.SQL_CURRENT_TIMESTAMP);
            case VARCHAR:
            case CHAR:
            case CLOB:
            case UNIQUEID:
                return getSQLTextString(type, value);
            case BOOL:
                // Get Boolean value   
                boolean boolVal = false;
                if (value instanceof Boolean)
                {   boolVal = ((Boolean) value).booleanValue();
                } 
                else
                { // Boolean from String
                    boolVal = stringToBoolean(value.toString());
                }
                return getSQLPhrase((boolVal) ? DBSqlPhrase.SQL_BOOLEAN_TRUE : DBSqlPhrase.SQL_BOOLEAN_FALSE);
            case INTEGER:
            case DECIMAL:
            case FLOAT:
                return getSQLNumberString(value, type);
            case BLOB:
                throw new NotSupportedException(this, "getValueString(?, DataType.BLOB)"); 
            case AUTOINC:
            case UNKNOWN:
                /* Allow expressions */
                return value.toString();
            default:
                log.warn("Unknown DataType {} for getValueString().", type);
                return value.toString();
        }
    }
    
    /**
     * Executes the select, update or delete SQL-Command with a Statement object.
     * 
     * @param sqlCmd the SQL-Command
     * @param sqlParams array of sql command parameters used for prepared statements (Optional).
     * @param conn a valid connection to the database.
     * @param genKeys allows to set the auto generated key of a record (INSERT statements only)
     * 
     * @return the row count for insert, update or delete or 0 for SQL statements that return nothing
     * 
     * @throws SQLException if a database access error occurs
     */
    @Override
    public int executeSQL(String sqlCmd, Object[] sqlParams, Connection conn, DBSetGenKeys genKeys)
        throws SQLException
    {   // Execute the Statement
        Statement stmt = null;
        try
        {
            int count = 0;
            if (sqlParams!=null)
            {   // Use a prepared statement
                PreparedStatement pstmt = (genKeys!=null) 
                    ? conn.prepareStatement(sqlCmd, Statement.RETURN_GENERATED_KEYS)
                    : conn.prepareStatement(sqlCmd);
                stmt = pstmt;
                prepareStatement(pstmt, sqlParams); 
                count = pstmt.executeUpdate(); 
            }
            else
            {   // Execute a simple statement
                stmt = conn.createStatement();
                count = (genKeys!=null)
                    ? stmt.executeUpdate(sqlCmd, Statement.RETURN_GENERATED_KEYS)
                    : stmt.executeUpdate(sqlCmd);
            }
            // Retrieve any auto-generated keys
            if (genKeys!=null && count>0)
            {   // Return Keys
                ResultSet rs = stmt.getGeneratedKeys();
                try {
                    while(rs.next())
                    {
                        genKeys.set(rs.getObject(1));
                    }
                } finally {
                    rs.close();
                }
            }
            // done
            return count;
        } finally {
            closeStatement(stmt);
        }
    }

    /**
     * Executes a list of sql statements as batch
     * @param sqlCmd
     * @param sqlCmdParams
     * @param conn
     * @return
     * @throws SQLException
     */
    @Override
    public int[] executeBatch(String[] sqlCmd, Object[][] sqlCmdParams, Connection conn)
        throws SQLException
    {   // Execute the Statement
        if (sqlCmdParams!=null)
        {   // Use a prepared statement
            PreparedStatement pstmt = null;
            try
            {
                int pos=0;
                String lastCmd = null;
                int[] result = new int[sqlCmd.length];
                for (int i=0; i<=sqlCmd.length; i++)
                {   // get cmd
                    String cmd = (i<sqlCmd.length ? sqlCmd[i] : null);
                    if (StringUtils.compareEqual(cmd, lastCmd, true)==false)
                    {   // close last statement
                        if (pstmt!=null)
                        {   // execute and close
                            log.debug("Executing batch containing {} statements", i-pos);
                            int[] res = pstmt.executeBatch();
                            for (int j=0; j<res.length; j++)
                                result[pos+j]=res[j];
                            pos+=res.length;
                            closeStatement(pstmt);
                            pstmt = null;
                        }
                        // has next?
                        if (cmd==null)
                            break;
                        // new statement
                        log.debug("Creating prepared statement for batch: "+cmd);
                        pstmt = conn.prepareStatement(cmd);
                        lastCmd = cmd;
                    }
                    // add batch
                    if (sqlCmdParams[i]!=null)
                    {   
                        prepareStatement(pstmt, sqlCmdParams[i]); 
                    }   
                    log.debug("Adding batch with {} params.", (sqlCmdParams[i]!=null ? sqlCmdParams[i].length : 0));
                    pstmt.addBatch();
                }
                return result; 
            } finally {
                closeStatement(pstmt);
            }
        }
        else
        {   // Execute a simple statement
            Statement stmt = conn.createStatement();
            try {
                for (int i=0; i<sqlCmd.length; i++)
                {
                    String cmd = sqlCmd[i];
                    log.debug("Adding statement to batch: "+cmd);
                    stmt.addBatch(cmd);
                }
                log.debug("Executing batch containing {} statements", sqlCmd.length);
                int result[] = stmt.executeBatch();
                return result;
            } finally {
                closeStatement(stmt);
            }
        }
    }
    
    /**
     * Executes an select SQL-command and returns the query results
     * 
     * @param sqlCmd the SQL-Command
     * @param sqlParams array of sql command parameters used for prepared statements (Optional).
     * @param scrollable true if scrollable or false otherwise
     * @param conn a valid connection to the database.
     * @return the JDBC resultset
     * @throws SQLException
     */
    @Override
    public ResultSet executeQuery(String sqlCmd, Object[] sqlParams, boolean scrollable, Connection conn)
        throws SQLException
    {
        Statement stmt = null;
        try
        {   // Set scroll type
            int type = (scrollable ? ResultSet.TYPE_SCROLL_INSENSITIVE
                                   : ResultSet.TYPE_FORWARD_ONLY);
            // Create an execute a query statement
            if (sqlParams!=null)
            {   // Use prepared statement
                PreparedStatement pstmt = conn.prepareStatement(sqlCmd, type, ResultSet.CONCUR_READ_ONLY);
                stmt = pstmt;
                prepareStatement(pstmt, sqlParams); 
                return pstmt.executeQuery();
            } else
            {   // Use simple statement
                stmt = conn.createStatement(type, ResultSet.CONCUR_READ_ONLY);
                return stmt.executeQuery(sqlCmd);
            }
        } catch(SQLException e) {
            // close statement (if not null)
            log.error("Error executing query '"+sqlCmd+"' --> "+e.getMessage(), e);
            closeStatement(stmt);
            throw e;
        }
    }

    /**
     * Query a single value 
     * @return the value of the first column in the first row of the query 
     */
    @Override
    public Object querySingleValue(String sqlCmd, Object[] sqlParams, DataType dataType, Connection conn)
    {
        ResultSet rs = null;
        try
        {   // Get the next Value
            rs = executeQuery(sqlCmd, sqlParams, false, conn);
            if (rs == null)
                throw new UnexpectedReturnValueException(rs, "driver.executeQuery()");
            // Check Result
            if (rs.next() == false)
            {   // no result
                log.debug("querySingleValue returned no result");
                return ObjectUtils.NO_VALUE;
            }
            // Read value
            return getResultValue(rs, 1, dataType);
        } catch (SQLException sqle) 
        {   // Error
            throw new QueryFailedException(this, sqlCmd, sqle);
        } finally {
            // Cleanup
            closeResultSet(rs);
        }
    }
    
    /**
     * Appends a statement to enable or disable a foreign key relation.<br>
     * The default is to drop or create the relation 
     * Override this method to provide different behavior for your database.
     * @param r the foreign key relation which should be enabled or disabled
     * @param enable true to enable the relation or false to disable
     * @param script the script to which to add the DDL command(s)
     */
    @Override
    public void appendEnableRelationStmt(DBRelation r, boolean enable, DBSQLScript script)
    {
        if (enable)
            getDDLScript(DDLAlterType.CREATE, r, script);
        else
            getDDLScript(DDLAlterType.DROP, r, script);
    }
    
    /**
     * Extracts native error message of an sqlExeption.
     * 
     * @param e the SQLException
     * @return the error message of the database 
     */
    @Override
    public String extractErrorMessage(SQLException e)
    {
        return e.getMessage();
    }

    /**
     * Convenience function for closing a JDBC Resultset<BR>
     * Use it instead of rset.close() and stmt.close()<BR> 
     * <P>
     * @param rset a ResultSet object
     */
    @Override
    public void closeResultSet(ResultSet rset)
    {
        try
        {   // check ResultSet
            if (rset == null)
                return; // nothing to do
            // close Resultset
            Statement stmt = rset.getStatement();
            rset.close();
            // check Statement
            if (stmt == null)
                return;
            // close Statement
            stmt.close();
            // done
            return;
        } catch (SQLException sqle) { 
            // Commit failed!
            throw new EmpireSQLException(this, sqle);
        }
    }
    
     /**
     * Convenience function for closing a JDBC Resultset<BR>
     * Use it instead of stmt.close()<BR> 
     * <P>
     * @param stmt a Statement object
     */
    protected void closeStatement(Statement stmt)
    {
        try
        { // Statement close
            if (stmt != null)
                stmt.close();
            // done
            return;
        } catch (SQLException sqle) { 
            // Commit failed!
            throw new EmpireSQLException(this, sqle);
        }
    }

    /**
     * Prepares an sql statement by setting the supplied objects as parameters.
     * 
     * @param pstmt the prepared statement
     * @param sqlParams list of objects
     */
    protected void prepareStatement(PreparedStatement pstmt, Object[] sqlParams) 
        throws SQLException
    {
        for (int i=0; i<sqlParams.length; i++)
        {
            Object value = sqlParams[i];
            try {
                addStatementParam(pstmt, i+1, value); // , conn
            } catch(SQLException e) {
                log.error("SQLException: Unable to set prepared statement parameter {} to '{}'", i+1, StringUtils.toString(value));
                throw e;
            }
        }
    }

    /**
     * Adds a statement parameter to a prepared statement
     * 
     * @param pstmt the prepared statement
     * @param paramIndex the parameter index
     * @param value the parameter value
     */
    protected void addStatementParam(PreparedStatement pstmt, int paramIndex, Object value)
        throws SQLException
    {
        if (value instanceof DBBlobData)
        {
            // handling for blobs
            DBBlobData blobData = (DBBlobData)value;
            pstmt.setBinaryStream(paramIndex, blobData.getInputStream(), blobData.getLength());
            // log
            if (log.isDebugEnabled())
                log.debug("Statement param {} set to BLOB data", paramIndex);
        }
        else if(value instanceof DBClobData)
        {
            // handling for clobs
            DBClobData clobData = (DBClobData)value;
            pstmt.setCharacterStream(paramIndex, clobData.getReader(), clobData.getLength());
            // log
            if (log.isDebugEnabled())
                log.debug("Statement param {} set to CLOB data", paramIndex);
        }
        else if(value instanceof Date && !(value instanceof Timestamp))
        {
            // handling for dates
            Timestamp ts = new Timestamp(((Date)value).getTime());
            pstmt.setObject(paramIndex, ts);
            // log
            if (log.isDebugEnabled())
                log.debug("Statement param {} set to date '{}'", paramIndex, ts);
        }
        else if((value instanceof Character) 
             || (value instanceof Enum<?>))
        {
            // Objects that need String conversion
            String strval = value.toString();
            pstmt.setObject(paramIndex, strval);
            // log
            if (log.isDebugEnabled())
                log.debug("Statement param {} set to '{}'", paramIndex, strval);
        }
        else
        {   // simple parameter value 
            pstmt.setObject(paramIndex, value);
            // log
            if (log.isDebugEnabled())
                log.debug("Statement param {} set to '{}'", paramIndex, value);
        }
    }
    
    /**
     * encodes a numeric value for an SQL command string. 
     * @param value the numeric value
     * @param type the number data type
     * @return the string reprentation of the number
     */
    protected String getSQLNumberString(Object value, DataType type)
    {
        // already a number
        if (value instanceof Number)
            return value.toString();
        
        // check if it is a number
        String s = value.toString();
        boolean integerOnly = (type==DataType.INTEGER);
        for (int i=0; i<s.length(); i++)
        {
            char c = s.charAt(i);
            if (c>='0' && c<='9')
                continue; // OK
            if (c=='-' || c=='+')
                continue; // OK
            if (c==' ' && i>0)
                return s.substring(0,i);
            // check 
            if (integerOnly || (c!='.' && c!=','))
                throw new NumberFormatException(s);
        }
        return s;
    }

    /**
     * encodes a Date value for an SQL command string. 
     * @param value
     * @param sqlTemplate
     * @param sqlPattern
     * @param sqlCurrentDate
     * @return
     */
    protected String getSQLDateTimeString(Object value, DBSqlPhrase sqlTemplate, DBSqlPhrase sqlPattern, DBSqlPhrase sqlCurrentDate)
    {
        // is it a sysdate expression
        if (DBDatabase.SYSDATE.equals(value))
            return getSQLPhrase(sqlCurrentDate);
        // Format the date (ymd)
        Timestamp ts; 
        if ((value instanceof Timestamp)) 
        {   // We have a timestamp
            ts = (Timestamp)value;
        }
        else if ((value instanceof Date))
        {   // Convert Date to Timestamp
            ts = new Timestamp(((Date)value).getTime());
        }
        else if ((value instanceof LocalDate))
        {   // Convert LocalDate to Timestamp
            ts = java.sql.Timestamp.valueOf(((LocalDate)value).atStartOfDay());
        }
        else if ((value instanceof LocalDateTime))
        {   // Convert LocalDateTime to Timestamp
            ts = java.sql.Timestamp.valueOf((LocalDateTime)value);
        }
        else 
        {   // "Timestamp format must be yyyy-mm-dd hh:mm:ss[.fffffffff]"
            String dtValue = value.toString().trim();
            try
            {   // parse timestamp
                ts = Timestamp.valueOf(dtValue);
            } catch (Throwable e) {
                // Invalid date
                log.error("Unable to parse date value "+dtValue, e);
                throw new InvalidArgumentException("value", value);
            }
        }
        // Convert to String
        String pattern = getSQLPhrase(sqlPattern);
        SimpleDateFormat sqlFormat = new SimpleDateFormat(getSQLPhrase(sqlPattern));
        String datetime = sqlFormat.format(ts);
        // Add micro / nanoseconds
        int nanos = (ts.getNanos() % 1000000);
        if (pattern.endsWith(".SSS") && nanos>0)
        {   // Add nanoseconds
            if (((nanos) % 100)>0)
                datetime += String.format("%06d", nanos);
            else
                datetime += String.format("%04d",(nanos/100));
        }
        // Now Build String
        String template = getSQLPhrase(sqlTemplate);
        return StringUtils.replace(template, "{0}", datetime);
    }

    /**
     * encodes Text values for an SQL command string.
     * @param type date type (can only be TEXT, CHAR, CLOB and UNIQUEID)
     * @param value the text to be encoded
     * @return the encoded sql value
     */
    protected String getSQLTextString(DataType type, Object value)
    {
        StringBuilder valBuf = new StringBuilder();
        valBuf.append("'");
        if (DBDatabase.EMPTY_STRING.equals(value)==false)
            appendSQLTextValue(valBuf, value.toString());
        valBuf.append("'");
        return valBuf.toString();
    }

    /** 
     * this helper function doubles up single quotes for SQL 
     */
    protected void appendSQLTextValue(StringBuilder buf, String value)
    {
        if (value.indexOf('\'') >= 0)
        { // a routine to double up single quotes for SQL
            int len = value.length();
            for (int i = 0; i < len; i++)
            {
                if (value.charAt(i) == '\'')
                    buf.append("''");
                else
                    buf.append(value.charAt(i));
            }
        } 
        else
        {
            buf.append(value);
        }
    }

    /**
     * this function converts a string containing a boolean expression to a boolean. 
     * @param value the string containing a boolean expression
     * @return true if the string contains either "true", "y" or "1" or false otherwise
     */
    protected boolean stringToBoolean(final String value) 
    {
        return "1".equals(value) ||
               "true".equalsIgnoreCase(value) ||
               "y".equalsIgnoreCase(value);
    }

}