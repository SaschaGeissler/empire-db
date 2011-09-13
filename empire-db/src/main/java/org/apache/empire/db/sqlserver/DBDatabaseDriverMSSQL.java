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
package org.apache.empire.db.sqlserver;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.GregorianCalendar;
import java.util.Iterator;

import org.apache.empire.commons.ObjectUtils;
import org.apache.empire.commons.StringUtils;
import org.apache.empire.data.DataType;
import org.apache.empire.db.DBCmdType;
import org.apache.empire.db.DBColumn;
import org.apache.empire.db.DBCommand;
import org.apache.empire.db.DBCommandExpr;
import org.apache.empire.db.DBDatabase;
import org.apache.empire.db.DBDatabaseDriver;
import org.apache.empire.db.DBDriverFeature;
import org.apache.empire.db.DBExpr;
import org.apache.empire.db.DBIndex;
import org.apache.empire.db.DBObject;
import org.apache.empire.db.DBRelation;
import org.apache.empire.db.DBSQLScript;
import org.apache.empire.db.DBTable;
import org.apache.empire.db.DBTableColumn;
import org.apache.empire.db.DBView;
import org.apache.empire.db.exceptions.InternalSQLException;
import org.apache.empire.exceptions.InvalidArgumentException;
import org.apache.empire.exceptions.InvalidPropertyException;
import org.apache.empire.exceptions.NotImplementedException;
import org.apache.empire.exceptions.NotSupportedException;


/**
 * This class provides support for the Microsoft SQL-Server database system.
 * 
 *
 * 
 */
public class DBDatabaseDriverMSSQL extends DBDatabaseDriver
{
    private final static long serialVersionUID = 1L;
  
    /**
     * Defines the Microsoft SQL-Server command type.
     */ 
    public static class DBCommandMSSQL extends DBCommand
    {
        private final static long serialVersionUID = 1L;
        protected int limit = -1;

        public DBCommandMSSQL(DBDatabase db)
    	{
    		super(db);
    	}
        
        @Override
        public void limitRows(int numRows)
        {
            limit = numRows;
        }
         
        @Override
        public void clearLimit()
        {
            limit = -1;
        }
        
        @Override
        protected void addSelect(StringBuilder buf)
        {   
            // Prepares statement
            buf.append("SELECT ");
            if (selectDistinct)
                buf.append("DISTINCT ");
            // Add limit
            if (limit>=0)
            {   // Limit
                buf.append("TOP ");
                buf.append(String.valueOf(limit));
                buf.append(" ");
            }
            // Add Select Expressions
            addListExpr(buf, select, CTX_ALL, ", ");
        }
    }
    
    // Properties
    private String databaseName = null;
    private String objectOwner = "dbo";
    private String sequenceTableName = "Sequences";
    // Sequence treatment
    // When set to 'false' (default) MySQL's auto-increment feature is used.
    private boolean useSequenceTable = false;
    
    /**
     * Constructor for the MSSQL database driver.<br>
     */
    public DBDatabaseDriverMSSQL()
    {
        // Default Constructor
    }

    public String getDatabaseName()
    {
        return databaseName;
    }

    public void setDatabaseName(String databaseName)
    {
        this.databaseName = databaseName;
    }

    public String getObjectOwner()
    {
        return objectOwner;
    }

    public void setObjectOwner(String objectOwner)
    {
        this.objectOwner = objectOwner;
    }

    /**
     * returns whether a sequence table is used for record identiy management.<br>
     * Default is false. In this case the AutoIncrement feature of MySQL is used.
     * @return true if a sequence table is used instead of identity columns.
     */
    public boolean isUseSequenceTable()
    {
        return useSequenceTable;
    }

    /**
     * If set to true a special table is used for sequence number generation.<br>
     * Otherwise the AutoIncrement feature of MySQL is used identiy fields. 
     * @param useSequenceTable true to use a sequence table or false otherwise.
     */
    public void setUseSequenceTable(boolean useSequenceTable)
    {
        this.useSequenceTable = useSequenceTable;
    }

    /**
     * returns the name of the sequence table
     * @return the name of the table used for sequence number generation
     */
	public String getSequenceTableName()
	{
		return sequenceTableName;
	}

    /**
     * Sets the name of the sequence table.
     * @param sequenceTableName the name of the table used for sequence number generation
     */
	public void setSequenceTableName(String sequenceTableName)
	{
		this.sequenceTableName = sequenceTableName;
	}

    /** {@inheritDoc} */
    @Override
    public void attachDatabase(DBDatabase db, Connection conn)
    {
        // Prepare
        try
        {   // Set Database
            if (StringUtils.isNotEmpty(databaseName))
                executeSQL("USE " + databaseName, null, conn, null);
            // Set Dateformat
            executeSQL("SET DATEFORMAT ymd", null, conn, null);
            // Sequence Table
            if (useSequenceTable && db.getTable(sequenceTableName)==null)
                new DBSeqTable(sequenceTableName, db);
            // Check Schema
            String schema = db.getSchema();
            if (StringUtils.isNotEmpty(schema) && schema.indexOf('.')<0 && StringUtils.isNotEmpty(objectOwner))
            {   // append database owner
                db.setSchema(schema + "." + objectOwner);
            }
            // call Base implementation
            super.attachDatabase(db, conn);
            
        } catch (SQLException e) {
            // throw exception
            throw new InternalSQLException(this, e);
        }
    }

    /**
     * Creates a new Microsoft SQL-Server command object.
     * 
     * @return the new DBCommandMSSQL object
     */
    @Override
    public DBCommand createCommand(DBDatabase db)
    {
        if (db == null)
            return null;
        // create command object
        return new DBCommandMSSQL(db);
    }

    /**
     * Returns whether or not a particular feature is supported by this driver
     * @param type type of requested feature. @see DBDriverFeature
     * @return true if the features is supported or false otherwise
     */
    @Override
    public boolean isSupported(DBDriverFeature type)
    {
        switch (type)
        {   // return support info 
            case CREATE_SCHEMA:     return true;
            case SEQUENCES:         return useSequenceTable;    
            case QUERY_LIMIT_ROWS:  return true;
            case QUERY_SKIP_ROWS:   return false;
            default:
                // All other features are not supported by default
                return false;
        }
    }

    /**
     * Gets an sql phrase template for this database system.<br>
     * @see DBDatabaseDriver#getSQLPhrase(int)
     * @return the phrase template
     */
    @Override
    public String getSQLPhrase(int phrase)
    {
        switch (phrase)
        {
            // sql-phrases
            case SQL_NULL_VALUE:              return "null";
            case SQL_PARAMETER:               return " ? ";
            case SQL_RENAME_TABLE:            return " ";
            case SQL_RENAME_COLUMN:           return " AS ";
            case SQL_DATABASE_LINK:           return "@";
            case SQL_QUOTES_OPEN:             return "[";
            case SQL_QUOTES_CLOSE:            return "]";
            case SQL_CONCAT_EXPR:             return " + ";
            // data types
            case SQL_BOOLEAN_TRUE:            return "1";
            case SQL_BOOLEAN_FALSE:           return "0";
            case SQL_CURRENT_DATE:            return "convert(char, getdate(), 111)";
            case SQL_DATE_PATTERN:            return "yyyy-MM-dd";
            case SQL_DATE_TEMPLATE:           return "'{0}'";
            case SQL_CURRENT_DATETIME:        return "getdate()";
            case SQL_DATETIME_PATTERN:        return "yyyy-MM-dd HH:mm:ss.SSS";
            case SQL_DATETIME_TEMPLATE:       return "'{0}'";
            // functions
            case SQL_FUNC_COALESCE:           return "coalesce(?, {0})";
            case SQL_FUNC_SUBSTRING:          return "substring(?, {0}, 4000)";
            case SQL_FUNC_SUBSTRINGEX:        return "substring(?, {0}, {1})";
            case SQL_FUNC_REPLACE:            return "replace(?, {0}, {1})";
            case SQL_FUNC_REVERSE:            return "reverse(?)"; 
            case SQL_FUNC_STRINDEX:           return "charindex({0}, ?)"; 
            case SQL_FUNC_STRINDEXFROM:       return "charindex({0}, ?, {1})"; 
            case SQL_FUNC_LENGTH:             return "len(?)";
            case SQL_FUNC_UPPER:              return "upper(?)";
            case SQL_FUNC_LOWER:              return "lcase(?)";
            case SQL_FUNC_TRIM:               return "trim(?)";
            case SQL_FUNC_LTRIM:              return "ltrim(?)";
            case SQL_FUNC_RTRIM:              return "rtrim(?)";
            case SQL_FUNC_ESCAPE:             return "? escape '{0}'";
            // Numeric
            case SQL_FUNC_ABS:                return "abs(?)";
            case SQL_FUNC_ROUND:              return "round(?,{0})";
            case SQL_FUNC_TRUNC:              return "trunc(?,{0})";
            case SQL_FUNC_CEILING:            return "ceiling(?)";
            case SQL_FUNC_FLOOR:              return "floor(?)";
            // Date
            case SQL_FUNC_DAY:                return "day(?)";
            case SQL_FUNC_MONTH:              return "month(?)";
            case SQL_FUNC_YEAR:               return "year(?)";
            // Aggregation
            case SQL_FUNC_SUM:                return "sum(?)";
            case SQL_FUNC_MAX:                return "max(?)";
            case SQL_FUNC_MIN:                return "min(?)";
            case SQL_FUNC_AVG:                return "avg(?)";
            // Others
            case SQL_FUNC_DECODE:             return "case ? {0} end";
            case SQL_FUNC_DECODE_SEP:         return " ";
            case SQL_FUNC_DECODE_PART:        return "when {0} then {1}";
            case SQL_FUNC_DECODE_ELSE:        return "else {0}";
            // Not defined
            default:
                log.error("SQL phrase " + String.valueOf(phrase) + " is not defined!");
                return "?";
        }
    }

    /**
     * @see DBDatabaseDriver#getConvertPhrase(DataType, DataType, Object)
     */
    @Override
    public String getConvertPhrase(DataType destType, DataType srcType, Object format)
    {
        switch(destType)
        {
           case BOOL:      return "convert(bit, ?)";
           case INTEGER:   return "convert(int, ?)";
           case DECIMAL:   return "convert(decimal, ?)";
           case DOUBLE:    return "convert(float, ?)";
           case DATE:      return "convert(datetime, ?, 111)";
           case DATETIME:  return "convert(datetime, ?, 120)";
           // Convert to text
           case TEXT:
                // Date-Time-Format "YYYY-MM-DD hh.mm.ss"
                if (srcType==DataType.DATE)
                    return "replace(convert(nvarchar, ?, 111), '/', '-')";
                if (srcType==DataType.DATETIME)
                    return "convert(nvarchar, ?, 120)";
                return "convert(nvarchar, ?)";
           case BLOB:
                return "convert(varbinary, ?)";
           // Unknown Type                                       
           default:
               	log.error("getConvertPhrase: unknown type " + destType);
                return "?";
        }
    }

    /**
     * @see DBDatabaseDriver#getNextSequenceValue(DBDatabase, String, int, Connection)
     */
    @Override
    public Object getNextSequenceValue(DBDatabase db, String seqName, int minValue, Connection conn)
    { 	
        if (useSequenceTable)
        {   // Use a sequence Table to generate Sequences
            DBTable t = db.getTable(sequenceTableName);
            return ((DBSeqTable)t).getNextValue(seqName, minValue, conn);
        }
        else
        {   // Post Detection
            return null;
        }
    }

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
        if (column.getDataType()==DataType.UNIQUEID)
        {
            return db.querySingleValue("select newid()", conn);
        }
        return super.getColumnAutoValue(db, column, conn);
    }

    /**
     * @see DBDatabaseDriver#getDDLScript(DBCmdType, DBObject, DBSQLScript)  
     */
    @Override
    public void getDDLScript(DBCmdType type, DBObject dbo, DBSQLScript script)
    {
        // The Object's database must be attached to this driver
        if (dbo==null || dbo.getDatabase().getDriver()!=this)
            throw new InvalidArgumentException("dbo", dbo);
        // Check Type of object
        if (dbo instanceof DBDatabase)
        { // Database
            switch (type)
            {
                case CREATE:
                    createDatabase((DBDatabase) dbo, script, true);
                    return;
                case DROP:
                    dropObject(((DBDatabase) dbo).getSchema(), "DATABASE", script);
                    return;
                default:
                    throw new NotImplementedException(this, "getDDLScript." + dbo.getClass().getName() + "." + type);
            }
        } 
        else if (dbo instanceof DBTable)
        { // Table
            switch (type)
            {
                case CREATE:
                    createTable((DBTable) dbo, script);
                    return;
                case DROP:
                    dropObject(((DBTable) dbo).getName(), "TABLE", script);
                    return;
                default:
                    throw new NotImplementedException(this, "getDDLScript." + dbo.getClass().getName() + "." + type);
            }
        } 
        else if (dbo instanceof DBView)
        { // View
            switch (type)
            {
                case CREATE:
                    createView((DBView) dbo, script);
                    return;
                case DROP:
                    dropObject(((DBView) dbo).getName(), "VIEW", script);
                    return;
                default:
                    throw new NotImplementedException(this, "getDDLScript." + dbo.getClass().getName() + "." + type);
            }
        } 
        else if (dbo instanceof DBRelation)
        { // Relation
            switch (type)
            {
                case CREATE:
                    createRelation((DBRelation) dbo, script);
                    return;
                case DROP:
                    dropObject(((DBRelation) dbo).getName(), "CONSTRAINT", script);
                    return;
                default:
                    throw new NotImplementedException(this, "getDDLScript." + dbo.getClass().getName() + "." + type);
            }
        } 
        else if (dbo instanceof DBTableColumn)
        { // Table Column
            alterTable((DBTableColumn) dbo, type, script);
            return;
        } 
        else
        { // dll generation not supported for this type
            throw new NotSupportedException(this, "getDDLScript() for "+dbo.getClass().getName());
        }
    }

    /**
     * Overridden. Returns a timestamp that is used for record updates created by the database server.
     * 
     * @return the current date and time of the database server.
     */
    @Override
    public java.sql.Timestamp getUpdateTimestamp(Connection conn)
    {
        // Default implementation
    	GregorianCalendar cal = new GregorianCalendar();
    	return new java.sql.Timestamp(cal.getTimeInMillis());
    }

    /*
     * return the sql for creating a Database
     */
    protected boolean createDatabase(DBDatabase db, DBSQLScript script, boolean createSchema)
    {
        // User Master to create Database
        if (createSchema)
        {   // check database Name
            if (StringUtils.isEmpty(databaseName))
                throw new InvalidPropertyException("databaseName", databaseName);
            // Create Database
            script.addStmt("USE master");
            script.addStmt("IF NOT EXISTS(SELECT * FROM sys.databases WHERE name = '" + databaseName + "') CREATE DATABASE " + databaseName);
            script.addStmt("USE " + databaseName);
            script.addStmt("SET DATEFORMAT ymd");
            // Sequence Table
            if (useSequenceTable && db.getTable(sequenceTableName)==null)
                new DBSeqTable(sequenceTableName, db);
        }
        // Create all Tables
        Iterator<DBTable> tables = db.getTables().iterator();
        while (tables.hasNext())
        {
            createTable(tables.next(), script);
        }
        // Create Relations
        Iterator<DBRelation> relations = db.getRelations().iterator();
        while (relations.hasNext())
        {
            createRelation(relations.next(), script);
        }
        // Create Views
        Iterator<DBView> views = db.getViews().iterator();
        while (views.hasNext())
        {
            createView(views.next(), script);
        }
        // Done
        return true;
    }
    
    /**
     * Returns true if the table has been created successfully.
     * 
     * @return true if the table has been created successfully
     */
    protected void createTable(DBTable t, DBSQLScript script)
    {
        StringBuilder sql = new StringBuilder();
        sql.append("-- creating table ");
        sql.append(t.getName());
        sql.append(" --\r\n");
        sql.append("CREATE TABLE ");
        t.addSQL(sql, DBExpr.CTX_FULLNAME);
        sql.append(" (");
        boolean addSeparator = false;
        Iterator<DBColumn> columns = t.getColumns().iterator();
        while (columns.hasNext())
        {
            DBTableColumn c = (DBTableColumn) columns.next();
            sql.append((addSeparator) ? ",\r\n   " : "\r\n   ");
            if (appendColumnDesc(c, sql)==false)
                continue; // Ignore and continue;
            addSeparator = true;
        }
        // Primary Key
        DBIndex pk = t.getPrimaryKey();
        if (pk != null)
        { // add the primary key
            sql.append(",\r\n CONSTRAINT ");
            appendElementName(sql, pk.getName());
            sql.append(" PRIMARY KEY (");
            addSeparator = false;
            // columns
            DBColumn[] keyColumns = pk.getColumns();
            for (int i = 0; i < keyColumns.length; i++)
            {
                sql.append((addSeparator) ? ", " : "");
                keyColumns[i].addSQL(sql, DBExpr.CTX_NAME);
                addSeparator = true;
            }
            sql.append(")");
        }
        sql.append(")");
        // Create the table
        script.addStmt(sql);
        // Create other Indexes (except primary key)
        Iterator<DBIndex> indexes = t.getIndexes().iterator();
        while (indexes.hasNext())
        {
            DBIndex idx = indexes.next();
            if (idx == pk || idx.getType() == DBIndex.PRIMARYKEY)
                continue;

            // Cretae Index
            sql.setLength(0);
            sql.append((idx.getType() == DBIndex.UNIQUE) ? "CREATE UNIQUE INDEX " : "CREATE INDEX ");
            appendElementName(sql, idx.getName());
            sql.append(" ON ");
            t.addSQL(sql, DBExpr.CTX_FULLNAME);
            sql.append(" (");
            addSeparator = false;

            // columns
            DBColumn[] idxColumns = idx.getColumns();
            for (int i = 0; i < idxColumns.length; i++)
            {
                sql.append((addSeparator) ? ", " : "");
                idxColumns[i].addSQL(sql, DBExpr.CTX_NAME);
                sql.append("");
                addSeparator = true;
            }
            sql.append(")");
            // Create Index
            script.addStmt(sql);
        }
    }
    
    /**
     * Appends a table column definition to a ddl statement
     * @param c the column which description to append
     * @param sql the sql builder object
     * @return true if the column was successfully appended or false otherwise
     */
    protected boolean appendColumnDesc(DBTableColumn c, StringBuilder sql)
    {
        // Append name
        c.addSQL(sql, DBExpr.CTX_NAME);
        sql.append(" ");
        switch (c.getDataType())
        {
            case INTEGER:
                sql.append("[int]");
                break;
            case AUTOINC:
                sql.append("[int]");
                if (useSequenceTable==false)
                {   // Make this column the identity column
                    int minValue = ObjectUtils.getInteger(c.getAttribute(DBColumn.DBCOLATTR_MINVALUE), 1);
                    sql.append(" IDENTITY(");
                    sql.append(String.valueOf(minValue));
                    sql.append(", 1) NOT NULL");
                    return true;
                }
                break;
            case TEXT:
            { // Check fixed or variable length
                int size = Math.abs((int) c.getSize());
                if (size == 0)
                    size = 100;
                sql.append("[nvarchar](");
                sql.append(String.valueOf(size));
                sql.append(")");
            }
                break;
            case CHAR:
            { // Check fixed or variable length
                int size = Math.abs((int) c.getSize());
                if (size == 0)
                    size = 1;
                sql.append("[char] (");
                sql.append(String.valueOf(size));
                sql.append(")");
            }
                break;
            case DATE:
                sql.append("[datetime]");
                break;
            case DATETIME:
                sql.append("[datetime]");
                break;
            case BOOL:
                sql.append("[bit]");
                break;
            case DOUBLE:
                sql.append("[float]");
                break;
            case DECIMAL:
            {
                sql.append("[decimal](");
                int prec = (int) c.getSize();
                int scale = (int) ((c.getSize() - prec) * 10 + 0.5);
                // sql.append((prec+scale).ToString());sql.append(",");
                sql.append(String.valueOf(prec));
                sql.append(",");
                sql.append(String.valueOf(scale));
                sql.append(")");
            }
                break;
            case CLOB:
                sql.append("[ntext]");
                break;
            case BLOB:
                sql.append("[image]");
                if (c.getSize() > 0)
                    sql.append(" (" + (long) c.getSize() + ") ");
                break;
            case UNIQUEID:
                sql.append("[uniqueidentifier]");
                if (c.isAutoGenerated())
                    sql.append(" ROWGUIDCOL");
                break;
            case UNKNOWN:
                 log.error("Cannot append column of Data-Type 'UNKNOWN'");
                 return false;
        }
        // Default Value
        if (isDDLColumnDefaults() && !c.isAutoGenerated() && c.getDefaultValue()!=null)
        {   sql.append(" DEFAULT ");
            sql.append(getValueString(c.getDefaultValue(), c.getDataType()));
        }
        // Nullable
        if (c.isRequired() ||  c.isAutoGenerated())
            sql.append(" NOT NULL");
        // Done
        return true;
    }

    /**
     * Returns true if the relation has been created successfully.
     * 
     * @return true if the relation has been created successfully
     */
    protected void createRelation(DBRelation r, DBSQLScript script)
    {
        DBTable sourceTable = (DBTable) r.getReferences()[0].getSourceColumn().getRowSet();
        DBTable targetTable = (DBTable) r.getReferences()[0].getTargetColumn().getRowSet();

        StringBuilder sql = new StringBuilder();
        sql.append("-- creating foreign key constraint ");
        sql.append(r.getName());
        sql.append(" --\r\n");
        sql.append("ALTER TABLE ");
        sourceTable.addSQL(sql, DBExpr.CTX_FULLNAME);
        sql.append(" ADD CONSTRAINT ");
        appendElementName(sql, r.getName());
        sql.append(" FOREIGN KEY (");
        // Source Names
        boolean addSeparator = false;
        DBRelation.DBReference[] refs = r.getReferences();
        for (int i = 0; i < refs.length; i++)
        {
            sql.append((addSeparator) ? ", " : "");
            refs[i].getSourceColumn().addSQL(sql, DBExpr.CTX_NAME);
            addSeparator = true;
        }
        // References
        sql.append(") REFERENCES ");
        targetTable.addSQL(sql, DBExpr.CTX_FULLNAME);
        sql.append(" (");
        // Target Names
        addSeparator = false;
        for (int i = 0; i < refs.length; i++)
        {
            sql.append((addSeparator) ? ", " : "");
            refs[i].getTargetColumn().addSQL(sql, DBExpr.CTX_NAME);
            addSeparator = true;
        }
        // done
        sql.append(")");
        script.addStmt(sql);
    }

    /**
     * Creates an alter table dll statement for adding, modifiying or droping a column.
     * @param col the column which to add, modify or drop
     * @param type the type of operation to perform
     * @param script to which to append the sql statement to
     * @return true if the statement was successfully appended to the buffer
     */
    protected void alterTable(DBTableColumn col, DBCmdType type, DBSQLScript script)
    {
        StringBuilder sql = new StringBuilder();
        sql.append("ALTER TABLE ");
        col.getRowSet().addSQL(sql, DBExpr.CTX_FULLNAME);
        switch(type)
        {
            case CREATE:
                sql.append(" ADD ");
                appendColumnDesc(col, sql);
                break;
            case ALTER:
                sql.append(" ALTER COLUMN ");
                appendColumnDesc(col, sql);
                break;
            case DROP:
                sql.append(" DROP COLUMN ");
                sql.append(col.getName());
                break;
        }
        // done
        script.addStmt(sql);
    }

    /**
     * Returns true if the view has been created successfully.
     * 
     * @return true if the view has been created successfully
     */
    protected void createView(DBView v, DBSQLScript script)
    {
        // Create the Command
        DBCommandExpr cmd = v.createCommand();
        if (cmd==null)
        {   // Check whether Error information is available
            log.error("No command has been supplied for view " + v.getName());
            throw new NotImplementedException(this, v.getName() + ".createCommand");
        }
        // Make sure there is no OrderBy
        cmd.clearOrderBy();

        // Build String
        StringBuilder sql = new StringBuilder();
        sql.append( "CREATE VIEW ");
        v.addSQL(sql, DBExpr.CTX_FULLNAME);
        sql.append( " (" );
        boolean addSeparator = false;
        for(DBColumn c : v.getColumns())
        {
            if (addSeparator)
                sql.append(", ");
            // Add Column name
            c.addSQL(sql, DBExpr.CTX_NAME);
            // next
            addSeparator = true;
        }
        sql.append(")\r\nAS\r\n");
        cmd.addSQL( sql, DBExpr.CTX_DEFAULT);
        // done
        script.addStmt(sql.toString());
    }
        
    /**
     * Returns true if the object has been dropped successfully.
     * 
     * @return true if the object has been dropped successfully
     */
    protected void dropObject(String name, String objType, DBSQLScript script)
    {
        if (name == null || name.length() == 0)
            throw new InvalidArgumentException("name", name);
        // Create Drop Statement
        StringBuilder sql = new StringBuilder();
        sql.append("DROP ");
        sql.append(objType);
        sql.append(" ");
        appendElementName(sql, name);
        script.addStmt(sql);
    }

}
