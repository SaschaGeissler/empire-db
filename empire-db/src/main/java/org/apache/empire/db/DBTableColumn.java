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
package org.apache.empire.db;

// Java
import java.sql.Connection;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;

import org.apache.empire.commons.Attributes;
import org.apache.empire.commons.Errors;
import org.apache.empire.data.DataType;
import org.apache.empire.data.DataMode;
import org.apache.empire.xml.XMLUtil;
import org.w3c.dom.Element;


/**
 * This class represent one column of a table.
 * It contains all properties of this columns (e.g. the column width).
 * <P>
 * 
 *
 */
public class DBTableColumn extends DBColumn
{
    // Column Information
    protected DataType  type;
    protected double    size;
    protected DataMode  dataMode;
    protected Object    defValue;

    /**
     * Constructs a DBTableColumn object set the specified parameters to this object.
     * 
     * @param table the table object to add the column to, set to null if you don't want it added to a table
     * @param type the type of the column e.g. integer, text, date
     * @param name the column name
     * @param size the column width
     * @param dataMode determines whether this column is optional, required or auto-generated 
     * @param defValue the object value
     */
    public DBTableColumn(DBTable table, DataType type, String name, double size, DataMode dataMode, Object defValue)
    {
        super(table, name);
        // check properties
        // Make sure (DataType.INTEGER & DataMode.AutoGenerated) = DataType.AUTOINC
        if (type==DataType.AUTOINC && dataMode!=DataMode.AutoGenerated)
            dataMode=DataMode.AutoGenerated;
        if (type==DataType.INTEGER && dataMode==DataMode.AutoGenerated)
            type=DataType.AUTOINC;
        // set column properties
        this.type = type;
        this.size = size;
        this.dataMode = dataMode;
        this.defValue = defValue;
        // xml
        attributes = new Attributes();
        options = null;
        // Add Column to Table
        if (table != null)
            table.addColumn(this);
    }

    /**
     * @deprecated use DBTableColumn(DBTable table, DataType type, String name, double size, DataMode dataMode, Object defValue) instead 
     * 
     * @param table the table object to add the column to, set to null if you don't want it added to a table
     * @param type the type of the column e.g. integer, text, date
     * @param name the column name
     * @param size the column width
     * @param required true if not null column
     * @param defValue the object value
     */
    @Deprecated
    public DBTableColumn(DBTable table, DataType type, String name, double size, boolean required, Object defValue)
    {
        this(table, type, name, size, (required ? DataMode.NotNull : DataMode.Nullable), defValue);
    }

    /**
     * Clone Constructor - use clone()
     */
    protected DBTableColumn(DBTable newTable, DBTableColumn other)
    {
        super(newTable, other.name);
        // Copy
        this.type = other.type;
        this.size = other.size;
        this.dataMode = other.dataMode;
        this.defValue = other.defValue;
        attributes = other.attributes;
        options = other.options;
        // Add Column to Table
        if (newTable != null)
            newTable.addColumn(this);
    }
    
    /**
     * Returns the default column value.
     * For columns of type DBDataType.AUTOINC this is assumed to be the name of a sequence
     * 
     * @return the default column value
     */
    public Object getDefaultValue()
    {
        return defValue;
    }

    /**
     * Sets the default column value.
     * 
     * @param defValue the default column value
     */
    public void setDefaultValue(Object defValue)
    {
        this.defValue = defValue;
    }
    
    /**
     * Returns the default column value. 
     * Unlike getDefaultValue this function is used when creating or adding records.
     * If the column value is DBDataType AUTOIN this function will return a new sequence value for this record
     * 
     * @param conn a valid database connection
     * @return the default column value
     */
    public Object getRecordDefaultValue(Connection conn)
    {	// Check params   
        if (rowset==null)
            return defValue;
        // Detect default value
        DBDatabase db = rowset.getDatabase();
        if (isAutoGenerated())
        {   // If no connection is supplied defer till later
        	if (conn==null)
        		return null; // Create Later
            // Other auto-generated values
        	DBDatabaseDriver driver = db.getDriver();
            return driver.getColumnAutoValue(db, this, conn);
        }
        // Normal value
        return defValue;
    }

    /**
     * Returns the data type.
     * 
     * @return the data type
     */
    @Override
    public DataType getDataType()
    {
        return type;
    }

    /**
     * Gets the the column width.
     * 
     * @return the column width
     */
    @Override
    public double getSize()
    {
        return size;
    }

    /**
     * Changes the size of the table column<BR>
     * Use for dynamic data model changes only.<BR>
     * @param size the new column size
     */
    public void setSize(double size)
    {
        this.size = size;
    }

    /**
     * Returns true if column is mandatory. Only for the graphic presentation.
     * 
     * @return true if column is mandatory 
     */
    @Override
    public boolean isRequired()
    {
        return (dataMode==DataMode.NotNull);
    }
    
    /**
     * Returns true if column is a numeric sequence or otherwise generated value
     * 
     * @return true if column is auto increment
     */
    @Override
    public boolean isAutoGenerated()
    {
        return (dataMode==DataMode.AutoGenerated);
    }

    /**
     * Changes the required property of the table column<BR>
     * Use for dynamic data model changes only.<BR>
     * @param required true if the column is required or false otherwise
     */
    public void setRequired(boolean required)
    {
        if (isAutoGenerated())
        {   // cannot change auto-generated columns
            error(Errors.NoAccess);
            return; 
        }
        // Set DataMode
        dataMode=(required ? DataMode.NotNull : DataMode.Nullable);
    }

    /**
     * Checks whether the column is read only.
     * 
     * @return true if the column is read only
     */
    @Override
    public boolean isReadOnly()
    {
        if (attributes!=null &&
            attributes.containsKey(DBCOLATTR_READONLY))
            return true;
        // Check DataMode
        return (dataMode==DataMode.ReadOnly || dataMode==DataMode.AutoGenerated);
    }

    /**
     * Sets the read only attribute of the column.
     *
     * @param readOnly true if the column should be read only or false otherwise
     */
    public void setReadOnly(boolean readOnly)
    {
        if (readOnly)
        {
            setAttribute(DBCOLATTR_READONLY, Boolean.TRUE);
        }
        else  
        {   // Remove Attribute
            if (attributes!=null)
                attributes.remove(DBCOLATTR_READONLY);
        }
    }

    /**
     * Checks whether the supplied value is valid for this column.
     * If the type of the value supplied does not match the columns
     * data type the value will be checked for compatibility. 
     * 
     * @param value the checked to check for validity
     * @return true if the value is valid or false otherwise.
     */
    @Override
    public boolean checkValue(Object value)
    {
        // Check for NULL
        if (isRequired() && (value == null || value.toString().length() < 1))
            return error(DBErrors.FieldNotNull, getName());
        // Is value valid
        switch (type)
        {
            case DATE:
            case DATETIME:
                // Check whether value is a valid date/time value!
                if (value!=null && (value instanceof Date)==false && value.equals(DBDatabase.SYSDATE)==false)
                {   try
                    {   // Parse date time value
                        SimpleDateFormat sdFormat = new SimpleDateFormat("dd-MM-yyyy");
                        sdFormat.setLenient(true);
                        sdFormat.parse(value.toString());
                        // OK
                    } catch (ParseException e)
                    {   // Error
                        log.error("checkValue exception: " + e.toString() + " column=" + getName() + " value=" + String.valueOf(value));
                        return error(DBErrors.FieldInvalidDateFormat, getName());
                    }
                }    
                break;

            case DECIMAL:
            case DOUBLE:
                if (value!=null && (value instanceof java.lang.Number)==false)
                {   try
                    {   // Convert to String and check
                        String val = value.toString();
                        if (val.length() > 0)
                            Double.parseDouble(val);
                        // thows NumberFormatException if not a number!
                    } catch (NumberFormatException nfe)
                    {
                        log.error("checkValue exception: " + nfe.toString() + " column=" + getName() + " value=" + String.valueOf(value));
                        return error(DBErrors.FieldNotNumeric, getName());
                    }
                }
                break;

            case INTEGER:
                if (value!=null && (value instanceof java.lang.Number)==false)
                {   try
                    {   // Convert to String and check
                        String val = value.toString();
                        if (val.length() > 0)
                            Long.parseLong(val);
                        // throws NumberFormatException if not an integer!
                    } catch (NumberFormatException nfe)
                    {
                        log.error("checkValue exception: " + nfe.toString() + " column=" + getName() + " value=" + String.valueOf(value));
                        return error(DBErrors.FieldNotNumeric, getName());
                    }
                }
                break;

            case TEXT:
            case CHAR:
                if (value!=null && value.toString().length() > size)
                    return error(DBErrors.FieldValueTooLong, getName(), (int)size);
                break;
                
            default:
                if (log.isDebugEnabled())
                    log.debug("No column validation has been implemented for data type " + type);
                break;

        }
        return success();
    }

    /**
     * Creates a foreign key relation for this column.
     * 
     * @param target the referenced primary key column
     * @return the reference object
     */
    public DBRelation.DBReference referenceOn(DBTableColumn target)
    {
        return new DBRelation.DBReference(this, target);
    }

    /**
     * Sets field elements, default attributes and all options to
     * the specified Element object (XML tag).
     * 
     * @param parent the parent object
     * @param flags a long value
     * @return the work up Element object
     */
    @Override
    public Element addXml(Element parent, long flags)
    { // Add Field element
        Element elem = XMLUtil.addElement(parent, "column");
        elem.setAttribute("name", name);
        // set default attributes
        DBIndex primaryKey = ((DBTable) rowset).getPrimaryKey();
        if (primaryKey != null)
        {
            int keyIndex;
            if ((keyIndex = ((DBTable) rowset).getPrimaryKey().getColumnPos(this)) >= 0)
                elem.setAttribute("key", String.valueOf(keyIndex + 1));
        }
        if (size > 0)
        {
            elem.setAttribute("size", String.valueOf((int)size));
            if (getDataType()==DataType.DECIMAL)
                elem.setAttribute("decimals", String.valueOf((int)(size*10)%10));
        }
        if (isRequired())
            elem.setAttribute(DBCOLATTR_MANDATORY, String.valueOf(Boolean.TRUE));
        // add All Attributes
        if (attributes!=null)
            attributes.addXml(elem, flags);
        // add All Options
        if (options!=null)
            options.addXml(elem, flags);
        // done
        return elem;
    }
}