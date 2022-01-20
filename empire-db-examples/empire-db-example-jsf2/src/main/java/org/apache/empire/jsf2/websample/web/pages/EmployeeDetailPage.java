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
package org.apache.empire.jsf2.websample.web.pages;

import org.apache.empire.jsf2.pageelements.RecordPageElement;
import org.apache.empire.jsf2.pages.PageOutcome;
import org.apache.empire.jsf2.websample.db.SampleDB;
import org.apache.empire.jsf2.websample.db.records.EmployeeRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EmployeeDetailPage extends SamplePage
{
    private static final Logger               log               = LoggerFactory.getLogger(EmployeeDetailPage.class);
    private static final long                 serialVersionUID  = 1L;

    private String                            idParam;

    private RecordPageElement<EmployeeRecord> employee;

    private int                               activeTab         = 0;
    
    public EmployeeDetailPage()
    {
        log.trace("EmployeeDetailPage created");

        SampleDB db = getDatabase();
        EmployeeRecord emplRec = new EmployeeRecord(getSampleContext());
        employee = new RecordPageElement<EmployeeRecord>(this, emplRec.getTable(), emplRec);
    }

    public String getIdParam()
    {
        return this.idParam;
    }

    public void setIdParam(String idParam)
    {
        log.info("EmployeeDetailPage idParam = {}.", idParam);
        this.idParam = idParam;
    }

    public RecordPageElement<EmployeeRecord> getEmployee()
    {
        return employee;
    }

    public EmployeeRecord getEmployeeRecord()
    {
        return employee.getRecord();
    }

    public int getActiveTab()
    {
        return activeTab;
    }

    public void setActiveTab(int activeTab)
    {
        this.activeTab = activeTab;
    }

    @Override
    public void doInit()
    { // Notify Elements
        if (!employee.getRecord().isValid())
        {
            employee.reloadRecord();
        }
    }

    public void doLoad()
    {
        log.info("EmployeeDetailPage Loading entryId {}.", this.idParam);
        // load the record
        this.employee.loadRecord(this.idParam);
    }

    public void doCreate()
    {
        getEmployeeRecord().create();
        doRefresh();
    }
    
    public PageOutcome doSave()
    {

        getEmployeeRecord().update();
        
        /* test transaction
        SampleDB db = this.getDatabase();
        if (getEmployeeRecord().isNull(db.T_EMPLOYEES.PHONE_NUMBER))
            throw new MiscellaneousErrorException("Phone number must not be empty!");
         */
        
        return getParentOutcome(true);
    }

    public PageOutcome doDelete()
    {
        getEmployeeRecord().delete();
        return getParentOutcome(true);
    }
    
    public PageOutcome doCancel()
    {
        return getParentOutcome(true);
    }

    public void onTabChanged(int newPage)
    {
        log.debug("onTabChanged " + newPage);
    }
    
}
