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
package org.apache.empire.jsf2.app;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;

import javax.faces.FactoryFinder;
import javax.faces.application.Application;
import javax.faces.application.ApplicationFactory;
import javax.faces.application.FacesMessage;
import javax.faces.application.FacesMessage.Severity;
import javax.faces.component.NamingContainer;
import javax.faces.component.UIComponent;
import javax.faces.component.UIViewRoot;
import javax.faces.context.FacesContext;
import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.sql.DataSource;

import org.apache.empire.commons.StringUtils;
import org.apache.empire.data.DataType;
import org.apache.empire.db.DBDatabase;
import org.apache.empire.exceptions.InternalException;
import org.apache.empire.exceptions.InvalidArgumentException;
import org.apache.empire.exceptions.NotSupportedException;
import org.apache.empire.exceptions.UnexpectedReturnValueException;
import org.apache.empire.jsf2.controls.TextAreaInputControl;
import org.apache.empire.jsf2.controls.TextInputControl;
import org.apache.empire.jsf2.impl.FacesImplementation;
import org.apache.empire.jsf2.impl.ResourceTextResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class WebApplication
{
    private static final Logger log                   = LoggerFactory.getLogger(WebApplication.class);

    private static final String CONNECTION_ATTRIBUTE  = "dbConnections";

    private static final String DB_CONTEXT_MAP        = "dbContextMap";
    
    public static String        APPLICATION_BEAN_NAME = "webApplication";

    protected TextResolver[]    textResolvers         = null;

    private String              webRoot               = null;
    
    private FacesImplementation facesImpl			  = null;
    
    private static WebApplication appInstance         = null;
    
    public static WebApplication getInstance()
    {
        if (appInstance==null)
            log.warn("No WebApplication instance available. Please add a PostConstructApplicationEvent using WebAppStartupListener in your faces-config.xml to create the WebApplication object.");
        // return instance
        return appInstance;
    }

    protected abstract void init(ServletContext servletContext);
    
    protected abstract DataSource getAppDataSource(DBDatabase db);

    protected WebApplication()
    {   // subscribe
        log.info("WebApplication {} created", getClass().getName());
        // Must be a singleton
        if (appInstance!=null) {
            throw new RuntimeException("An attempt was made to create second instance of WebApplication. WebApplication must be a singleton!");
        }
        // set Instance
        appInstance = this;
    }

    /**
     * Init the Application
     * @param servletContext
     */
    public final void init(FacesImplementation facesImpl, FacesContext startupContext)
    {
        // Only call once!
        if (this.facesImpl!=null || this.webRoot!=null) 
        {   // already initialized
            log.warn("WARNING: WebApplication has already been initialized! Continuing without init...");
            return;
        }
        // set imppl
        this.facesImpl = facesImpl;
        // webRoot
        ServletContext servletContext = (ServletContext) startupContext.getExternalContext().getContext();
        webRoot = servletContext.getContextPath();
        servletContext.setAttribute("webRoot", webRoot);
        servletContext.setAttribute("app", this);
        // Init
        init(servletContext);
        // text resolvers
        log.info("*** initTextResolvers() ***");
        ApplicationFactory appFactory = (ApplicationFactory) FactoryFinder.getFactory(FactoryFinder.APPLICATION_FACTORY);
        Application app = appFactory.getApplication();
        initTextResolvers(app);
        // Log info
        log.info("*** WebApplication initialization complete ***");
        log.info("JSF-Implementation is '{}'", facesImpl.getClass().getName());
        log.info("WebRoot is '{}'", webRoot);
    }

    public void destroy()
    {
    	// Override if needed
    }
    
    /* Context handling */
    
    /**
     * handle request cleanup
     * @param ctx
     */
    public void onRequestComplete(final FacesContext ctx)
    {
        releaseAllConnections(ctx);
    }

    /**
     * handle view not found
     * @param fc
     * @param HttpServletRequest
     */
    public void onViewNotFound(final FacesContext fc, final HttpServletRequest req)
    {   // View not Found Error
        log.warn("No view found for request to '{}'. Use FacesUtils.redirectDirectly() to redirect to valid view.", req.getRequestURI());
    }

    /**
     * handle view change
     * @param fc
     * @param viewId
     */
    public void onChangeView(final FacesContext fc, String viewId)
    {   // allow custom view change logic

        // clear page resources
        Map<String, Object> sm = FacesUtils.getSessionMap(fc);
        if (sm!=null)
            sm.remove(FacesUtils.PAGE_RESOURCE_MAP_ATTRIBUTE);
    }

    public void addJavascriptCall(final FacesContext fc, String function)
    {
        throw new NotSupportedException(this, "addJavascriptCall");
    }

    /**
     * return the interface for Implementation specific features 
     * that are specific for Mojarra or MyFaces
     */
    public FacesImplementation getFacesImplementation() 
    {
		return facesImpl;
	}
    
    /**
     * returns the web context path as returned from ServletContext.getContextPath()
     */
    public String getWebRoot()
    {
        return webRoot;
    }

    /**
     * returns the active locale for a given FacesContext
     */
    public Locale getContextLocale(final FacesContext ctx)
    {
        UIViewRoot root;
        // Start out with the default locale
        Locale locale;
        Locale defaultLocale = Locale.getDefault();
        locale = defaultLocale;
        // See if this FacesContext has a ViewRoot
        if (null != (root = ctx.getViewRoot()))
        {
            // If so, ask it for its Locale
            if (null == (locale = root.getLocale()))
            {
                // If the ViewRoot has no Locale, fall back to the default.
                locale = defaultLocale;
            }
        }
        return locale;
    }

    public TextResolver getTextResolver(Locale locale)
    {
        // No text Resolvers provided
        if (textResolvers == null || textResolvers.length == 0)
        {
            throw new NotSupportedException(this, "getTextResolver");
        }
        // Lookup resolver for locale
        for (int i = 0; i < textResolvers.length; i++)
            if (locale.equals(textResolvers[i].getLocale()))
                return textResolvers[i];
        // locale not found: return default
        return textResolvers[0];
    }

    public TextResolver getTextResolver(FacesContext ctx)
    {
        return getTextResolver(getContextLocale(ctx));
    }

    /**
     * checks if the current context contains an error
     * @param fc the FacesContext
     * @return true if the context has an error set or false otherwise
     */
    public boolean hasError(final FacesContext fc)
    {
        Iterator<FacesMessage> msgIterator = fc.getMessages();
        if (msgIterator != null)
        { // Check Messages
            while (msgIterator.hasNext())
            { // Check Severity
                Severity fms = msgIterator.next().getSeverity();
                if (fms == FacesMessage.SEVERITY_ERROR || fms == FacesMessage.SEVERITY_FATAL)
                    return true;
            }
        }
        return false;
    }

    /**
     * returns true if a form input element has been partially submitted
     * @param fc the Faces Context
     * @return the componentId or null if no partial submit was been performed
     */
    public boolean isPartialSubmit(final FacesContext fc)
    {
        // Override for your JSF component Framework. e.g. for IceFaces
        // Map<String,String> parameterMap = fc.getExternalContext().getRequestParameterMap();
        // return ObjectUtils.getBoolean(parameterMap.get("ice.submit.partial"));
        return false;
    }

    /**
     * returns the componentId for which a partial submit has been performed.
     * @param fc the Faces Context
     * @return the componentId or null if no partial submit was been performed
     */
    public String getPartialSubmitComponentId(final FacesContext fc)
    {
        // Override for your JSF component Framework. e.g. for IceFaces
        // Map<String,String> parameterMap = fc.getExternalContext().getRequestParameterMap();
        // return parameterMap.get("ice.event.captured");
        return null;
    }

    /**
     * finds the component with the given id that is located in the same NamingContainer as a given component 
     * @param fc the FacesContext
     * @param componentId the component id
     * @param nearComponent a component within the same naming container from which to start the search (optional)
     * @return the component or null if no component was found
     */
    public UIComponent findComponent(FacesContext fc, String componentId, UIComponent nearComponent)
    {
        if (StringUtils.isEmpty(componentId))
            throw new InvalidArgumentException("componentId", componentId);
        // Begin search near given component (if any)
        UIComponent component = null;
        if (nearComponent != null)
        {   // Search below the nearest naming container  
            component = nearComponent.findComponent(componentId);
            if (component == null)
            {   // Recurse upwards
                UIComponent nextParent = nearComponent;
                while (true)
                {
                    nextParent = nextParent.getParent();
                    // search NamingContainers only
                    while (nextParent != null && !(nextParent instanceof NamingContainer))
                    {
                        nextParent = nextParent.getParent();
                    }
                    if (nextParent == null)
                    {
                        break;
                    }
                    else
                    {
                        component = nextParent.findComponent(componentId);
                    }
                    if (component != null)
                    {
                        break;
                    }
                }
            }
        }
        // Not found. Search the entire tree 
        if (component == null)
            component = findChildComponent(fc.getViewRoot(), componentId);
        // done
        return component;
    }

    /**
     * finds a child component with the given id that is located below the given parent component 
     * @param parent the parent
     * @param componentId the component id
     * @return the component or null if no component was found
     */
    public static UIComponent findChildComponent(UIComponent parent, String componentId)
    {
        UIComponent component = null;
        if (parent.getChildCount() == 0)
            return null;
        Iterator<UIComponent> children = parent.getChildren().iterator();
        while (children.hasNext())
        {
            UIComponent nextChild = children.next();
            if (nextChild instanceof NamingContainer)
            {
                component = nextChild.findComponent(componentId);
            }
            if (component == null)
            {
                component = findChildComponent(nextChild, componentId);
            }
            if (component != null)
            {
                break;
            }
        }
        return component;
    }

    /**
     * returns the default input control type for a given data Type
     * @see org.apache.empire.jsf2.controls.InputControlManager
     * @param dataType
     * @return an Input Cnotrol type
     */
    public String getDefaultControlType(DataType dataType)
    {
        switch (dataType)
        {
            case CLOB:
                return TextAreaInputControl.NAME;
            default:
                return TextInputControl.NAME;
        }
    }

    /* Message handling */

    protected void initTextResolvers(Application app)
    {
        int count = 0;
        Iterator<Locale> locales = app.getSupportedLocales();
        for (count = 0; locales.hasNext(); count++)
        {
            locales.next();
        }

        // get message bundles
        String messageBundle = app.getMessageBundle();
        textResolvers = new TextResolver[count];
        locales = app.getSupportedLocales();
        for (int i = 0; locales.hasNext(); i++)
        {
            Locale locale = locales.next();
            textResolvers[i] = new ResourceTextResolver(ResourceBundle.getBundle(messageBundle, locale));
            log.info("added TextResolver for {} bundle='{}'", locale.getLanguage(), messageBundle);
        }
    }

    /**
     * returns a connection from the connection pool
     * 
     * @return
     */
    protected Connection getConnection(DBDatabase db)
    {
        // Get From Pool
        try
        { // Obtain a connection
            Connection conn = getAppDataSource(db).getConnection();
            conn.setAutoCommit(false);
            return conn;
        }
        catch (SQLException e)
        {
            log.error("Failed to get connection from pool.", e);
            throw new InternalException(e);
        }
    }

    /**
     * releases a connection from the connection pool
     */
    protected void releaseConnection(DBDatabase db, Connection conn, boolean commit)
    {
        try
        { // release connection
            if (conn == null)
            {
                return;
            }
            // Commit or rollback connection depending on the exit code
            if (commit)
            { // success: commit all changes
                conn.commit();
                log.debug("REQUEST commited.");
            }
            else
            { // failure: rollback all changes
                conn.rollback();
                log.debug("REQUEST rolled back.");
            }
            // Release Connection
            conn.close();
            // done
            if (log.isDebugEnabled())
                log.debug("REQUEST returned connection to pool.");
        }
        catch (SQLException e)
        {
            log.error("Error releasing connection", e);
            e.printStackTrace();
        }
    }

    /**
     * returns a connection for the current Request
     */
    public Connection getConnectionForRequest(FacesContext fc, WebDBContext<? extends DBDatabase> context)
    {
        if (fc == null)
            throw new InvalidArgumentException("FacesContext", fc);
        if (context == null)
            throw new InvalidArgumentException("context", context);
        // Get Conneciton map
        DBDatabase db = context.getDatabase();
        @SuppressWarnings("unchecked")
        Map<DBDatabase, Connection> connMap = (Map<DBDatabase, Connection>) FacesUtils.getRequestAttribute(fc, CONNECTION_ATTRIBUTE);
        if (connMap== null)
        {   connMap = new HashMap<DBDatabase, Connection>();
            FacesUtils.setRequestAttribute(fc, CONNECTION_ATTRIBUTE, connMap);
        }
        Connection conn = connMap.get(db);
        if (conn==null)
        {   // Get Pooled Connection
            conn = getConnection(db);
            if (conn== null)
                throw new UnexpectedReturnValueException(this, "getConnection"); 
            // Add to map
            connMap.put(db, conn);
        }
        // Store Context
        @SuppressWarnings("unchecked")
        Map<Integer, WebDBContext<? extends DBDatabase>> ctxMap = (Map<Integer, WebDBContext<? extends DBDatabase>>) FacesUtils.getRequestAttribute(fc, DB_CONTEXT_MAP);
        if (ctxMap== null)
        {   ctxMap = new HashMap<Integer, WebDBContext<? extends DBDatabase>>();
            FacesUtils.setRequestAttribute(fc, DB_CONTEXT_MAP, ctxMap);
        }
        ctxMap.put(conn.hashCode(), context);
        // done
        return conn;
    }

    /**
     * releases the current request connection
     * @param fc the FacesContext
     * @param commit when true changes are committed otherwise they are rolled back
     */
    public void releaseAllConnections(final FacesContext fc, boolean commit)
    {
        @SuppressWarnings("unchecked")
        Map<DBDatabase, Connection> connMap = (Map<DBDatabase, Connection>) FacesUtils.getRequestAttribute(fc, CONNECTION_ATTRIBUTE);
        @SuppressWarnings("unchecked")
        Map<Integer, WebDBContext<? extends DBDatabase>> ctxMap = (Map<Integer, WebDBContext<? extends DBDatabase>>) FacesUtils.getRequestAttribute(fc, DB_CONTEXT_MAP);
        if (connMap != null)
        {   // Walk the connection map
            for (Map.Entry<DBDatabase, Connection> e : connMap.entrySet())
            {
                Connection conn = e.getValue();
                releaseConnection(e.getKey(), conn, commit);
                // release connection
                WebDBContext<? extends DBDatabase> ctx = ctxMap.get(conn.hashCode());
                ctx.releaseConnection(commit);
            }
            // remove from request map
            FacesUtils.setRequestAttribute(fc, CONNECTION_ATTRIBUTE, null);
            FacesUtils.setRequestAttribute(fc, DB_CONTEXT_MAP, null);
        }
    }

    public void releaseAllConnections(final FacesContext fc)
    {
        releaseAllConnections(fc, !hasError(fc));
    }

    public void releaseConnection(final FacesContext fc, DBDatabase db, boolean commit)
    {
        @SuppressWarnings("unchecked")
        Map<DBDatabase, Connection> connMap = (Map<DBDatabase, Connection>) FacesUtils.getRequestAttribute(fc, CONNECTION_ATTRIBUTE);
        if (connMap != null && connMap.containsKey(db))
        { // Walk the connection map
            releaseConnection(db, connMap.get(db), commit);
            connMap.remove(db);
            if (connMap.size() == 0)
                FacesUtils.setRequestAttribute(fc, CONNECTION_ATTRIBUTE, null);
        }
    }

    public void releaseConnection(final FacesContext fc, DBDatabase db)
    {
        releaseConnection(fc, db, !hasError(fc));
    }
    
    
}
