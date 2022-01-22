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

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
// java.sql
import java.io.Serializable;

import org.apache.empire.commons.StringUtils;


/**
 * Base class for all objects that directly or indirectly belong to a database including the database object itself.
 * Examples are: tables, views, columns, indexes, relations etc.
 * Not included are: drivers, helper classes
 */
public abstract class DBObject implements Serializable
{
    private static final long serialVersionUID = 1L;
    // Logger
    // private static final Logger log = LoggerFactory.getLogger(DBObject.class);

    /**
     * Returns the database object to which this object belongs to.
     * For the database object itself this function will return the this pointer.
     * 
     * @return the database object
     */
    public abstract <T extends DBDatabase> T getDatabase();
    
    
    /**
     * Serialize transient database
     * @param strm the stream
     * @param db
     * @throws IOException
     */
    protected void writeDatabase(ObjectOutputStream strm, DBDatabase db) throws IOException
    {
        String dbid = (db!=null ? db.getIdentifier() : ""); 
        strm.writeObject(dbid);
    }
    
    /**
     * Serialize transient database
     * @param strm the stream
     * @param db
     * @throws IOException
     */
    protected DBDatabase readDatabase(ObjectInputStream strm) throws IOException, ClassNotFoundException
    {
        String dbid = String.valueOf(strm.readObject());
        if (StringUtils.isEmpty(dbid))
            return null; // No Database
        // find database
        DBDatabase sdb = DBDatabase.findById(dbid);
        if (sdb==null)
            throw new ClassNotFoundException(dbid);
        return sdb;
    }

    /*
    private void readObject(ObjectInputStream strm) throws IOException, ClassNotFoundException,
        SecurityException, IllegalArgumentException 
    {
        System.out.println("Serialization Reading Object "+getClass().getName());
        //perform the default serialization for all non-transient, non-static fields
        strm.defaultReadObject();
    }

    private void writeObject(ObjectOutputStream strm) throws IOException 
    {
        System.out.println("Serialization Writing Object "+getClass().getName());
        //perform the default serialization for all non-transient, non-static fields
        strm.defaultWriteObject();
    }
    */
}