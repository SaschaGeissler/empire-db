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
package org.apache.empire.db.expr.compare;

import java.util.Set;

import org.apache.empire.db.DBColumn;
import org.apache.empire.db.DBDatabase;

/**
 * This class wraps an existing compare expression with parenthesis.
 * <P>
 */
public class DBParenthesisExpr extends DBCompareExpr
{
    // *Deprecated* private static final long serialVersionUID = 1L;
    private final DBCompareExpr wrap;
    
    public DBParenthesisExpr(DBCompareExpr wrap)
    {
        this.wrap = wrap;
    }
    
    public DBCompareExpr getWrapped()
    {
        return wrap;
    }

    @Override
    public final <T extends DBDatabase> T getDatabase()
    {
        return wrap.getDatabase();
    }
    
    @Override
    public boolean isMutuallyExclusive(DBCompareExpr other)
    {
        return wrap.isMutuallyExclusive(other);
    }

    @Override
    public void addReferencedColumns(Set<DBColumn> list)
    {
        wrap.addReferencedColumns(list);
    }

    @Override
    public void addSQL(StringBuilder buf, long context)
    {
        buf.append("(");
        wrap.addSQL(buf, context|CTX_NOPARENTHESES);
        buf.append(")");
    }
}
