package com.mchange.v2.c3p0.impl;

import com.mchange.v2.c3p0.stmt.*;
import com.mchange.v2.c3p0.ConnectionCustomizer;
import com.mchange.v2.c3p0.SQLWarnings;
import com.mchange.v2.c3p0.UnifiedConnectionTester;
import com.mchange.v2.c3p0.WrapperConnectionPoolDataSource;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.util.LinkedList;
import java.util.WeakHashMap;

import javax.sql.ConnectionEvent;
import javax.sql.ConnectionEventListener;
import javax.sql.ConnectionPoolDataSource;
import javax.sql.PooledConnection;

import com.mchange.v1.db.sql.ConnectionUtils;
import com.mchange.v2.async.AsynchronousRunner;
import com.mchange.v2.async.ThreadPoolReportingAsynchronousRunner;
import com.mchange.v2.log.MLevel;
import com.mchange.v2.log.MLog;
import com.mchange.v2.log.MLogger;
import com.mchange.v2.c3p0.C3P0Registry;
import com.mchange.v2.c3p0.ConnectionTester;
import com.mchange.v2.c3p0.QueryConnectionTester;
import com.mchange.v2.resourcepool.CannotAcquireResourceException;
import com.mchange.v2.resourcepool.ResourcePool;
import com.mchange.v2.resourcepool.ResourcePoolException;
import com.mchange.v2.resourcepool.ResourcePoolFactory;
import com.mchange.v2.resourcepool.TimeoutException;
import com.mchange.v2.sql.SqlUtils;

public final class C3P0PooledConnectionPool
{
    private final static boolean ASYNCHRONOUS_CONNECTION_EVENT_LISTENER = false;

    final static MLogger logger = MLog.getLogger( C3P0PooledConnectionPool.class );

    final ResourcePool rp;
    final ConnectionEventListener cl = new ConnectionEventListenerImpl();

    final ConnectionTester     connectionTester;
    final GooGooStatementCache scache;

    final Resurrectables resurrectables;

    final boolean c3p0PooledConnections;

    final int checkoutTimeout;
    final int connectionIsValidTimeout;

    final boolean disableSessionBoundaries;

    final AsynchronousRunner sharedTaskRunner;
    final AsynchronousRunner deferredStatementDestroyer;;

    final InUseLockFetcher inUseLockFetcher;

    //MT: protected by this' lock
    private RequestBoundaryMarker requestBoundaryMarker;

    public int getStatementDestroyerNumConnectionsInUse()                           { return scache == null ? -1 : scache.getStatementDestroyerNumConnectionsInUse(); }
    public int getStatementDestroyerNumConnectionsWithDeferredDestroyStatements()   { return scache == null ? -1 : scache.getStatementDestroyerNumConnectionsWithDeferredDestroyStatements(); }
    public int getStatementDestroyerNumDeferredDestroyStatements()                  { return scache == null ? -1 : scache.getStatementDestroyerNumDeferredDestroyStatements(); }

    private static class Resurrectables
    {
        //MT: protected by this' lock
        WeakHashMap candidates = new WeakHashMap();

        public synchronized void markResurrectable( Object resc )
        {
            candidates.put( resc, this );
            if (Debug.DEBUG && logger.isLoggable(MLevel.FINER))
                logger.log(MLevel.FINER, "Marked broken resource resurrectable: " + resc);
        }

        // both checks and removes/clears pconn
        public synchronized boolean checkResurrectable( Object resc )
        {
            boolean out = (candidates.remove( resc ) != null);
            if (Debug.DEBUG && logger.isLoggable(MLevel.FINER) && out)
                logger.log(MLevel.FINER, "Found and cleared resurrectable resource: " + resc);
            return out;
        }
    }

    /**
     *  This "lock fetcher" crap is a lot of ado about very little.
     *  In theory, there is a hazard that pool maintenance tasks could
     *  get sufficiently backed up in the Thread pool that say, multple
     *  Connection tests might run simultaneously. That would be okay,
     *  but the Statement cache's "mark-in-use" functionality doesn't
     *  track nested use of resources. So we enforce exclusive, sequential
     *  execution of internal tests by requiring the tests hold a lock.
     *  But what lock to hold? The obvious choice is the tested resource's
     *  lock, but NewPooledConnection is designed for use by clients that
     *  do not hold its lock. So, we give NewPooledConnection an internal
     *  Object, an "inInternalUseLock", and lock on this resource instead.
     */

    private interface InUseLockFetcher
    {
	public Object getInUseLock(Object resc);
    }

    private static class ResourceItselfInUseLockFetcher implements InUseLockFetcher
    {
	public Object getInUseLock(Object resc) { return resc; }
    }

    private static class C3P0PooledConnectionNestedLockLockFetcher implements InUseLockFetcher
    {
	public Object getInUseLock(Object resc)
	{ return ((AbstractC3P0PooledConnection) resc).inInternalUseLock; }
    }

    private static InUseLockFetcher RESOURCE_ITSELF_IN_USE_LOCK_FETCHER = new ResourceItselfInUseLockFetcher();
    private static InUseLockFetcher C3P0_POOLED_CONNECION_NESTED_LOCK_LOCK_FETCHER = new C3P0PooledConnectionNestedLockLockFetcher();

    private interface RequestBoundaryMarker
    {
	public void attemptNotifyBeginRequest(PooledConnection pc);
	public void attemptNotifyEndRequest(PooledConnection pc);
    }

    private static RequestBoundaryMarker NO_OP_REQUEST_BOUNDARY_MARKER = new RequestBoundaryMarker()
    {
	public void attemptNotifyBeginRequest(PooledConnection pc) {}
	public void attemptNotifyEndRequest(PooledConnection pc) {}
    };

    private static RequestBoundaryMarker INTERFACE_REQUEST_BOUNDARY_MARKER = new RequestBoundaryMarker()
    {
	public void attemptNotifyBeginRequest(PooledConnection pc)
	{
	    if (pc instanceof AbstractC3P0PooledConnection)
	    {
		AbstractC3P0PooledConnection acpc = (AbstractC3P0PooledConnection) pc;
		Connection conn = acpc.getPhysicalConnection();
		try
		{
                    conn.beginRequest();

                    if (Debug.DEBUG && logger.isLoggable(MLevel.FINEST))
                        logger.log(MLevel.FINEST, "beginRequest method called");
		}
                catch (AbstractMethodError ame)
                {
                    if (logger.isLoggable(MLevel.WARNING))
                        logger.log(MLevel.WARNING, "AbstractMethodError invoking beginRequest method for Connction, even though Connections were tested for the presence of this method previously.", ame);
                }
		catch (Exception ex)
		{
                    if (logger.isLoggable(MLevel.WARNING))
                        logger.log(MLevel.WARNING, "Error invoking beginRequest method for connection", ex);
                }
	    }
	}
	public void attemptNotifyEndRequest(PooledConnection pc)
	{
	    if (pc instanceof AbstractC3P0PooledConnection)
	    {
		AbstractC3P0PooledConnection acpc = (AbstractC3P0PooledConnection) pc;
		Connection conn = acpc.getPhysicalConnection();
		try
		{
                    conn.endRequest();

                    if (Debug.DEBUG && logger.isLoggable(MLevel.FINEST))
                        logger.log(MLevel.FINEST, "endRequest method called");
		}
                catch (AbstractMethodError ame)
                {
                    if (logger.isLoggable(MLevel.WARNING))
                        logger.log(MLevel.WARNING, "AbstractMethodError invoking endRequest method for Connction, even though Connections were tested for the presence of this method previously.", ame);
                }
		catch (Exception ex)
		{
                    if (logger.isLoggable(MLevel.WARNING))
                        logger.log(MLevel.WARNING, "Error invoking endRequest method for connection", ex);
                }
	    }
	}
    };

    private static class ReflectiveRequestBoundaryMarker implements RequestBoundaryMarker
    {
	Method beginRequest;
	Method endRequest;

	ReflectiveRequestBoundaryMarker(Method beginRequest, Method endRequest)
	{
	    this.beginRequest = beginRequest;
	    this.endRequest   = endRequest;
            if (!beginRequest.isAccessible()) beginRequest.setAccessible(true);
            if (!endRequest.isAccessible()) endRequest.setAccessible(true);
	}
	public void attemptNotifyBeginRequest(PooledConnection pc)
	{
	    if (pc instanceof AbstractC3P0PooledConnection)
	    {
		AbstractC3P0PooledConnection acpc = (AbstractC3P0PooledConnection) pc;
		Connection conn = acpc.getPhysicalConnection();
		try
		{
		    beginRequest.invoke(conn);

                    if (Debug.DEBUG && logger.isLoggable(MLevel.FINEST))
                        logger.log(MLevel.FINEST, "beginRequest method called");
		}
		catch (Exception ex)
		{
                    if (logger.isLoggable(MLevel.WARNING))
                        logger.log(MLevel.WARNING, "Error invoking beginRequest method for connection", ex);
                }
	    }
	}
	public void attemptNotifyEndRequest(PooledConnection pc)
	{
	    if (pc instanceof AbstractC3P0PooledConnection)
	    {
		AbstractC3P0PooledConnection acpc = (AbstractC3P0PooledConnection) pc;
		Connection conn = acpc.getPhysicalConnection();
		try
		{
		    endRequest.invoke(conn);

                    if (Debug.DEBUG && logger.isLoggable(MLevel.FINEST))
                        logger.log(MLevel.FINEST, "endRequest method called");
		}
		catch (Exception ex)
		{
                    if (logger.isLoggable(MLevel.WARNING))
                        logger.log(MLevel.WARNING, "Error invoking endRequest method for connection", ex);
                }
	    }
	}
    }

    // we assume (pretty safely I think) that all PooledConnections we see will have the same type
    // and physical connection type
    private synchronized RequestBoundaryMarker findRequestBoundaryMarker(PooledConnection pc)
    {
	if (this.requestBoundaryMarker != null)
	    return this.requestBoundaryMarker;
	else
	{
	    if (this.disableSessionBoundaries)
	    {
		this.requestBoundaryMarker = NO_OP_REQUEST_BOUNDARY_MARKER;

                if (Debug.DEBUG && logger.isLoggable(MLevel.FINE))
		    logger.log(MLevel.FINE, "Installed no-op request boundary marker due to markSessionBoundaries setting.");
	    }
	    else if (pc instanceof AbstractC3P0PooledConnection)
	    {
		AbstractC3P0PooledConnection acpc = (AbstractC3P0PooledConnection) pc;
		Connection conn = acpc.getPhysicalConnection();
		try
		{
		    Method beginRequest = conn.getClass().getMethod("beginRequest");
		    Method endRequest = conn.getClass().getMethod("endRequest");

                    if (Debug.DEBUG && logger.isLoggable(MLevel.FINEST))
                        logger.log(MLevel.FINEST, "Request boundary methods found.");

                    boolean intfc_has_methods;
                    try
                    {
                        Method intfcBeginRequest = Connection.class.getMethod("beginRequest");
                        Method intfcEndRequest = Connection.class.getMethod("endRequest");

                        if (Debug.DEBUG && logger.isLoggable(MLevel.FINEST))
                            logger.log(MLevel.FINEST, "Interface request boundary methods found.");

                        intfc_has_methods = true;
                    }
                    catch (NoSuchMethodException e)
                    { intfc_has_methods = false; }

                    if (intfc_has_methods)
                    {
                        this.requestBoundaryMarker = INTERFACE_REQUEST_BOUNDARY_MARKER;

                        if (Debug.DEBUG && logger.isLoggable(MLevel.FINE))
                            logger.log(MLevel.FINE, "Installed interface-based request boundary marker.");
                    }
                    else
                    {
                        this.requestBoundaryMarker = new ReflectiveRequestBoundaryMarker(beginRequest, endRequest);

                        if (Debug.DEBUG && logger.isLoggable(MLevel.FINE))
                            logger.log(MLevel.FINE, "Installed reflective request boundary marker.");
                    }
		}
		catch (NoSuchMethodException nsme)
		{
		    // let methods be null, driver does not implement them
                    if (logger.isLoggable(MLevel.WARNING))
                        logger.log(MLevel.WARNING, "Request boundary methods not found.");

		    this.requestBoundaryMarker = NO_OP_REQUEST_BOUNDARY_MARKER;

                    if (Debug.DEBUG && logger.isLoggable(MLevel.FINE))
                        logger.log(MLevel.FINE, "Installed no-op request boundary marker, because request boundary methods not found.");
		}
		catch (SecurityException se)
		{
                    if (logger.isLoggable(MLevel.WARNING))
                        logger.log(MLevel.WARNING, "Could not make boundary methods accessible.");
                    if (Debug.DEBUG && logger.isLoggable(MLevel.FINE))
                        logger.log(MLevel.FINE, "SecurityException:", se);

		    this.requestBoundaryMarker = NO_OP_REQUEST_BOUNDARY_MARKER;

                    if (Debug.DEBUG && logger.isLoggable(MLevel.FINE))
                        logger.log(MLevel.FINE, "Installed no-op request boundary marker, because request boundary methods could not be made accessible.");
		}
                catch (Exception e)
                {
                    if (logger.isLoggable(MLevel.WARNING))
                        logger.log(MLevel.WARNING, "An unexpected Exception occurred while querying request boundary methods.", e);

		    this.requestBoundaryMarker = NO_OP_REQUEST_BOUNDARY_MARKER;

                    if (Debug.DEBUG && logger.isLoggable(MLevel.FINE))
                        logger.log(MLevel.FINE, "Installed no-op request boundary marker, because an unexpected exception occurred while querying request boundary methods.");
                }
	    }
	    else
	    {
		this.requestBoundaryMarker = NO_OP_REQUEST_BOUNDARY_MARKER;

		if (logger.isLoggable(MLevel.WARNING))
		    logger.log(MLevel.WARNING, "Could not mark request boundaries when pooling non-c3p0 PooledConnections.");
		if (Debug.DEBUG && logger.isLoggable(MLevel.FINE))
		    logger.log(MLevel.FINE, "Installed no-op request boundary marker, because we are working with non-c3p0 pooled connections, and do not support request boundary marking in this case.");
	    }

	    return this.requestBoundaryMarker;
	}
    }

    private void markBeginRequest(PooledConnection pc) { findRequestBoundaryMarker(pc).attemptNotifyBeginRequest(pc); }
    private void markEndRequest(PooledConnection pc) { findRequestBoundaryMarker(pc).attemptNotifyEndRequest(pc); }

    C3P0PooledConnectionPool( final ConnectionPoolDataSource cpds,
			      final DbAuth auth,
			      int min,
			      int max,
			      int start,
			      int inc,
			      int acq_retry_attempts,
			      int acq_retry_delay,
			      boolean break_after_acq_failure,
			      int checkoutTimeout, //milliseconds
			      final int connectionIsValidTimeout, // seconds
			      int idleConnectionTestPeriod, //seconds
			      int maxIdleTime, //seconds
			      int maxIdleTimeExcessConnections, //seconds
			      int maxConnectionAge, //seconds
			      int propertyCycle, //seconds
			      int unreturnedConnectionTimeout, //seconds
			      boolean debugUnreturnedConnectionStackTraces,
			      boolean forceSynchronousCheckins,
			      final boolean testConnectionOnCheckout,
			      final boolean testConnectionOnCheckin,
                              boolean attemptResurrectOnCheckin,
			      int maxStatements,
			      int maxStatementsPerConnection,
			      String markSessionBoundaries,
			      /* boolean statementCacheDeferredClose,      */
			      final ConnectionTester connectionTester,
			      final ConnectionCustomizer connectionCustomizer,
			      final String testQuery,
			      final ResourcePoolFactory fact,
			      ThreadPoolReportingAsynchronousRunner taskRunner,
			      ThreadPoolReportingAsynchronousRunner deferredStatementDestroyer,
			      final String parentDataSourceIdentityToken) throws SQLException
    {
        try
        {
            this.c3p0PooledConnections = (cpds instanceof WrapperConnectionPoolDataSource);

            if (!c3p0PooledConnections)
            {
                if (logger.isLoggable(MLevel.WARNING) && (maxStatements > 0 || maxStatementsPerConnection > 0))
                    logger.log(MLevel.WARNING, "Statement caching is configured, but cannot be supported, because the provided ConnectionPoolDataSource is not a c3p0 implementation. Initializing with no statement cache.");
                this.scache = null;
            }
            else if (maxStatements > 0 && maxStatementsPerConnection > 0)
                this.scache = new DoubleMaxStatementCache( taskRunner, deferredStatementDestroyer, maxStatements, maxStatementsPerConnection );
            else if (maxStatementsPerConnection > 0)
                this.scache = new PerConnectionMaxOnlyStatementCache( taskRunner, deferredStatementDestroyer, maxStatementsPerConnection );
            else if (maxStatements > 0)
                this.scache = new GlobalMaxOnlyStatementCache( taskRunner, deferredStatementDestroyer, maxStatements );
            else
                this.scache = null;

            if (attemptResurrectOnCheckin)
                this.resurrectables = new Resurrectables();
            else
                this.resurrectables = null;

            this.connectionTester = connectionTester;

            this.checkoutTimeout = checkoutTimeout;

	    this.connectionIsValidTimeout = connectionIsValidTimeout;

            this.sharedTaskRunner = taskRunner;
	    this.deferredStatementDestroyer = deferredStatementDestroyer;

	    if ("always".equalsIgnoreCase(markSessionBoundaries))
		this.disableSessionBoundaries = false;
	    else if ("never".equalsIgnoreCase(markSessionBoundaries))
		this.disableSessionBoundaries = true;
	    else if ("if-no-statement-cache".equalsIgnoreCase(markSessionBoundaries))
		this.disableSessionBoundaries = this.scache != null;
	    else
		{
		    if (logger.isLoggable(MLevel.WARNING))
			logger.log(MLevel.WARNING, "markSessionBoundaries should be one of 'always','never', or 'if-no-statement-cache'. Found illegal value '" + markSessionBoundaries  + "'. Defaulting to 'always'.");
		    this.disableSessionBoundaries = false;
		}

	    this.inUseLockFetcher = (c3p0PooledConnections ? C3P0_POOLED_CONNECION_NESTED_LOCK_LOCK_FETCHER : RESOURCE_ITSELF_IN_USE_LOCK_FETCHER);

            class PooledConnectionResourcePoolManager implements ResourcePool.Manager
            {
                ConnectionTestPath connectionTestPath;

                void initAfterResourcePoolConstructed()
                {
		    if (connectionTester == null)
		    {
			if (testQuery != null)
			{
			    if(connectionIsValidTimeout == C3P0Defaults.connectionIsValidTimeout())
			    {
				if (logger.isLoggable(MLevel.WARNING))
				    logger.log( MLevel.WARNING,
						"Although no ConnectionTester is set, preferredTestQuery (or automaticTestTable) is also set, which can only be supported by a ConnectionTester. " +
						"Reverting to use of ConnectionTester com.mchange.v2.c3p0.impl.DefaultConnectionTester." );
				this.connectionTestPath = new ConnectionTesterConnectionTestPath( rp, C3P0Registry.getConnectionTester(DefaultConnectionTester.class.getName()), scache, testQuery, c3p0PooledConnections );
			    }
			    else
			    {
				if (logger.isLoggable(MLevel.WARNING))
				    logger.log( MLevel.WARNING,
						"Both a preferredTestQuery (or automaticTestTable) and a non-default value of connectionIsValidTimeout are set, but only " +
						"one can be simultaneously supported. Will use Connection.isValid( connectionIsValidTimeout ), and " +
						"ignore test query '" + testQuery + "'." );
				this.connectionTestPath = new IsValidSimplifiedConnectionTestPath( rp, connectionIsValidTimeout );
			    }
			}
			else
			    this.connectionTestPath = new IsValidSimplifiedConnectionTestPath( rp, connectionIsValidTimeout );
		    }
		    else
		    {
			if (logger.isLoggable(MLevel.WARNING) && connectionIsValidTimeout != C3P0Defaults.connectionIsValidTimeout())
			{
				logger.log( MLevel.WARNING,
					    "A ConnectionTester '" + connectionTester +
					    "' is explicitly set, but also a nondefault connectionIsValidTimeout (" + connectionIsValidTimeout +
					    "). Unfortunately connectionIsValidTimeout is not supported by ConnectionTesters, and will be ignored. " +
					    "If you are using com.mchange.v2.c3p0.impl.DefaultConnectionTester, you may " +
					    "set config or system property com.mchange.v2.c3p0.impl.DefaultConnectionTester.isValidTimeout instead. " +
					    "Alternatively, just switch to simple isValid(...) testing by setting connectionTesterClassName to null" );
			}
			this.connectionTestPath = new ConnectionTesterConnectionTestPath( rp, connectionTester, scache, testQuery, c3p0PooledConnections );
		    }
                }

                public Object acquireResource() throws Exception
                {
                    PooledConnection out;

                    if ( connectionCustomizer == null)
                    {
                        out = (auth.equals( C3P0ImplUtils.NULL_AUTH ) ?
                               cpds.getPooledConnection() :
                               cpds.getPooledConnection( auth.getUser(),
                                                         auth.getPassword() ) );
                    }
                    else
                    {
                        try
                        {
                            WrapperConnectionPoolDataSourceBase wcpds = (WrapperConnectionPoolDataSourceBase) cpds;

                            out = (auth.equals( C3P0ImplUtils.NULL_AUTH ) ?
                                   wcpds.getPooledConnection( connectionCustomizer, parentDataSourceIdentityToken ) :
                                   wcpds.getPooledConnection( auth.getUser(),
                                                              auth.getPassword(),
                                                              connectionCustomizer, parentDataSourceIdentityToken ) );
                        }
                        catch (ClassCastException e)
                        {
                            String msg =
                                "Cannot use a ConnectionCustomizer with a non-c3p0 ConnectionPoolDataSource." +
                                " ConnectionPoolDataSource: " + cpds.getClass().getName();
                            throw SqlUtils.toSQLException(msg, e);
                        }
                    }

                    //connectionCounter.increment();
                    //totalOpenedCounter.increment();

                    try
                    {
                        if (scache != null)
                        {
                            if (c3p0PooledConnections)
                                ((AbstractC3P0PooledConnection) out).initStatementCache(scache);
                            else
                            {
                                // System.err.print("Warning! StatementPooling not ");
                                // System.err.print("implemented for external (non-c3p0) ");
                                // System.err.println("ConnectionPoolDataSources.");

                                logger.warning("StatementPooling not implemented for external (non-c3p0) ConnectionPoolDataSources.");
                            }
                        }

                        // log and clear any SQLWarnings present upon acquisition
                        Connection con = null;
                        try
                        {
                            waitMarkPooledConnectionInUse(out);
                            con = out.getConnection();
                            SQLWarnings.logAndClearWarnings( con );
                        }
                        finally
                        {
                            //invalidate the proxy Connection
                            ConnectionUtils.attemptClose( con );

                            unmarkPooledConnectionInUse(out);
                        }

                        return out;
                    }
                    catch (Exception e)
                    {
                        if (logger.isLoggable( MLevel.WARNING ))
                            logger.log(MLevel.WARNING,
				       "A PooledConnection was acquired, but an Exception occurred while preparing it for use. Attempting to destroy.",
				       e);
                        try { destroyResource( out, false ); }
                        catch (Exception e2)
                        {
                            if (logger.isLoggable( MLevel.WARNING ))
                                logger.log( MLevel.WARNING,
                                                "An Exception occurred while trying to close partially acquired PooledConnection.",
                                                e2 );
                        }

                        throw e;
                    }
                    finally
                    {
                        if (logger.isLoggable( MLevel.FINEST ))
                            logger.finest(this + ".acquireResource() returning. " );
                        //"Currently open Connections: " + connectionCounter.getValue() +
                        //"; Failed close count: " + failedCloseCounter.getValue() +
                        //"; Total processed by this pool: " + totalOpenedCounter.getValue());
                    }
                }

                // REFURBISHMENT:
                // the PooledConnection refurbishes itself when
                // its Connection view is closed, prior to being
                // checked back in to the pool. But we still may want to
                // test to make sure it is still good.

                public void refurbishResourceOnCheckout( Object resc ) throws Exception
                {
		    synchronized (inUseLockFetcher.getInUseLock(resc))
		    {
			if ( connectionCustomizer != null )
			{
			    Connection physicalConnection = null;
			    try
			    {
				physicalConnection =  ((AbstractC3P0PooledConnection) resc).getPhysicalConnection();
				waitMarkPhysicalConnectionInUse( physicalConnection );
				if ( testConnectionOnCheckout )
				{
				    if ( Debug.DEBUG && logger.isLoggable( MLevel.FINER ) )
					finerLoggingTestPooledConnection( resc, "CHECKOUT" );
				    else
					testPooledConnection( resc );
				}
				connectionCustomizer.onCheckOut( physicalConnection, parentDataSourceIdentityToken );
			    }
			    catch (ClassCastException e)
			    {
				throw SqlUtils.toSQLException("Cannot use a ConnectionCustomizer with a non-c3p0 PooledConnection." +
							      " PooledConnection: " + resc +
							      "; ConnectionPoolDataSource: " + cpds.getClass().getName(), e);
			    }
			    finally
			    { unmarkPhysicalConnectionInUse(physicalConnection); }
			}
			else
			{
			    if ( testConnectionOnCheckout )
			    {
				PooledConnection pc = (PooledConnection) resc;
				try
				{
				    waitMarkPooledConnectionInUse( pc );

				    if ( Debug.DEBUG && logger.isLoggable( MLevel.FINER ) )
					finerLoggingTestPooledConnection( pc, "CHECKOUT" );
				    else
					testPooledConnection( pc );
				}
				finally
				{
				    unmarkPooledConnectionInUse(pc);
				}
			    }
			}
		    }
                }

		// TODO: refactor this by putting the connectionCustomizer if logic inside the (currently repeated) logic
                public void refurbishResourceOnCheckin( Object resc ) throws Exception
                {
		    Connection proxyToClose = null; // can't close a proxy while we own parent PooledConnection's lock.
                    boolean attemptResurrect = (resurrectables != null && resurrectables.checkResurrectable(resc));
		    try
		    {
		      synchronized (inUseLockFetcher.getInUseLock(resc))
		      {
			if ( connectionCustomizer != null )
			{
			    Connection physicalConnection = null;
			    try
			    {
				physicalConnection =  ((AbstractC3P0PooledConnection) resc).getPhysicalConnection();

				// so by the time we are checked in, all marked-for-destruction statements should be closed.
				waitMarkPhysicalConnectionInUse( physicalConnection );
				connectionCustomizer.onCheckIn( physicalConnection, parentDataSourceIdentityToken );
				SQLWarnings.logAndClearWarnings( physicalConnection );

				if ( testConnectionOnCheckin || attemptResurrect)
				{
				    if ( Debug.DEBUG && logger.isLoggable( MLevel.FINER ) )
					finerLoggingTestPooledConnection( resc, "CHECKIN" );
				    else
					testPooledConnection( resc );
				}

			    }
			    catch (ClassCastException e)
			    {
				throw SqlUtils.toSQLException("Cannot use a ConnectionCustomizer with a non-c3p0 PooledConnection." +
							      " PooledConnection: " + resc +
							      "; ConnectionPoolDataSource: " + cpds.getClass().getName(), e);
			    }
			    finally
			    { unmarkPhysicalConnectionInUse(physicalConnection); }
			}
			else
			{
			    PooledConnection pc = (PooledConnection) resc;
			    Connection con = null;

			    try
			    {

				// so by the time we are checked in, all marked-for-destruction statements should be closed.
				waitMarkPooledConnectionInUse( pc );
				con = pc.getConnection();
				SQLWarnings.logAndClearWarnings(con);

				if ( testConnectionOnCheckin || attemptResurrect )
				{
				    if ( Debug.DEBUG && logger.isLoggable( MLevel.FINER ) )
					finerLoggingTestPooledConnection( resc, con, "CHECKIN" );
				    else
					testPooledConnection( resc, con );
				}

			    }
			    finally
			    {
				proxyToClose = con;
				unmarkPooledConnectionInUse( pc );
			    }
			}
		      }
                      // if we haven't failed the test by throwing then...
                      if (Debug.DEBUG && logger.isLoggable(MLevel.FINE) && attemptResurrect)
                          logger.log(MLevel.FINE, "A resource that had previously experienced a Connection error has been successfully resurrected on checkin: " + resc);
		    }
		    finally
		    {
			// close any opened proxy Connection
			ConnectionUtils.attemptClose(proxyToClose);
		    }
                }

                public void refurbishIdleResource( Object resc ) throws Exception
                {
		    synchronized (inUseLockFetcher.getInUseLock(resc))
		    {
			PooledConnection pc = (PooledConnection) resc;
			try
			{
			    waitMarkPooledConnectionInUse( pc );
			    if ( Debug.DEBUG && logger.isLoggable( MLevel.FINER ) )
				finerLoggingTestPooledConnection( resc, "IDLE CHECK" );
			    else
				testPooledConnection( resc );
			}
			finally
			{ unmarkPooledConnectionInUse( pc ); }
		    }
                }

                private void finerLoggingTestPooledConnection(Object resc, String testImpetus) throws Exception
		{ finerLoggingTestPooledConnection( resc, null, testImpetus); }


                private void finerLoggingTestPooledConnection(Object resc, Connection proxyConn, String testImpetus) throws Exception
                {
                    logger.finer("Testing PooledConnection [" + resc + "] on " + testImpetus + ".");
                    try
                    {
                        testPooledConnection( resc, proxyConn );
                        logger.finer("Test of PooledConnection [" + resc + "] on " + testImpetus + " has SUCCEEDED.");
                    }
                    catch (Exception e)
                    {
                        logger.log(MLevel.FINER, "Test of PooledConnection [" + resc + "] on "+testImpetus+" has FAILED.", e);
                        e.fillInStackTrace();
                        throw e;
                    }
                }

                // connections should be marked in use prior to any test
                // and unmarked in some finally after the test and other operations complete
                private void testPooledConnection(Object resc) throws Exception
		{ testPooledConnection( resc, null ); }

                // connections should be marked in use prior to any test
                // and unmarked in some finally after the test and other operations complete
                private void testPooledConnection(Object resc, Connection proxyConn) throws Exception
                {
                    PooledConnection pc = (PooledConnection) resc;
		    assert !Boolean.FALSE.equals(pooledConnectionInUse( pc )); //null or true are okay

                    connectionTestPath.testPooledConnection( pc, proxyConn );
                }

                public void destroyResource(Object resc, boolean checked_out) throws Exception
                {
		    synchronized (inUseLockFetcher.getInUseLock(resc))
		    {
			try
			    {
				waitMarkPooledConnectionInUse((PooledConnection) resc);

				if ( connectionCustomizer != null )
				    {
					Connection physicalConnection = null;
					try
					    {
						physicalConnection =  ((AbstractC3P0PooledConnection) resc).getPhysicalConnection();

						connectionCustomizer.onDestroy( physicalConnection, parentDataSourceIdentityToken );
					    }
					catch (ClassCastException e)
					    {
						throw SqlUtils.toSQLException("Cannot use a ConnectionCustomizer with a non-c3p0 PooledConnection." +
									      " PooledConnection: " + resc +
									      "; ConnectionPoolDataSource: " + cpds.getClass().getName(), e);
					    }
					catch (Exception e)
					    {
						if (logger.isLoggable( MLevel.WARNING ))
						    logger.log( MLevel.WARNING,
								"An exception occurred while executing the onDestroy() method of " + connectionCustomizer +
								". c3p0 will attempt to destroy the target Connection regardless, but this issue " +
								" should be investigated and fixed.",
								e );
					    }
				    }

				if (Debug.DEBUG && Debug.TRACE == Debug.TRACE_MAX && logger.isLoggable( MLevel.FINER ))
				    logger.log( MLevel.FINER, "Preparing to destroy PooledConnection: " + resc);

				if (c3p0PooledConnections)
				    ((AbstractC3P0PooledConnection) resc).closeMaybeCheckedOut( checked_out );
				else
				    ((PooledConnection) resc).close();

				// inaccurate, as Connections can be removed more than once
				//connectionCounter.decrement();

				if (Debug.DEBUG && Debug.TRACE == Debug.TRACE_MAX && logger.isLoggable( MLevel.FINER ))
				    logger.log( MLevel.FINER,
						"Successfully destroyed PooledConnection: " + resc );
				//". Currently open Connections: " + connectionCounter.getValue() +
				//"; Failed close count: " + failedCloseCounter.getValue() +
				//"; Total processed by this pool: " + totalOpenedCounter.getValue());
			    }
			catch (Exception e)
			    {
				//failedCloseCounter.increment();

				if (Debug.DEBUG && Debug.TRACE == Debug.TRACE_MAX && logger.isLoggable( MLevel.FINER ))
				    logger.log( MLevel.FINER, "Failed to destroy PooledConnection: " + resc );
				//". Currently open Connections: " + connectionCounter.getValue() +
				//"; Failed close count: " + failedCloseCounter.getValue() +
				//"; Total processed by this pool: " + totalOpenedCounter.getValue());

				throw e;
			    }
			finally
			    { unmarkPooledConnectionInUse((PooledConnection) resc); }
		    }
		}
            }

            PooledConnectionResourcePoolManager manager = new PooledConnectionResourcePoolManager();

            synchronized (fact)
            {
                fact.setMin( min );
                fact.setMax( max );
                fact.setStart( start );
                fact.setIncrement( inc );
                fact.setIdleResourceTestPeriod( idleConnectionTestPeriod * 1000);
                fact.setResourceMaxIdleTime( maxIdleTime * 1000 );
                fact.setExcessResourceMaxIdleTime( maxIdleTimeExcessConnections * 1000 );
                fact.setResourceMaxAge( maxConnectionAge * 1000 );
                fact.setExpirationEnforcementDelay( propertyCycle * 1000 );
                fact.setDestroyOverdueResourceTime( unreturnedConnectionTimeout * 1000 );
                fact.setDebugStoreCheckoutStackTrace( debugUnreturnedConnectionStackTraces );
                fact.setForceSynchronousCheckins( forceSynchronousCheckins );
                fact.setAcquisitionRetryAttempts( acq_retry_attempts );
                fact.setAcquisitionRetryDelay( acq_retry_delay );
                fact.setBreakOnAcquisitionFailure( break_after_acq_failure );
                this.rp = fact.createPool( manager );
            }

            manager.initAfterResourcePoolConstructed();
        }
        catch (ResourcePoolException e)
        { throw SqlUtils.toSQLException(e); }
    }

    public PooledConnection checkoutPooledConnection() throws SQLException
    {
        //System.err.println(this + " -- CHECKOUT");
        try
	    {
		PooledConnection pc = (PooledConnection) this.checkoutAndMarkConnectionInUse();
		pc.addConnectionEventListener( cl );
		markBeginRequest(pc);
		return pc;
	    }
        catch (TimeoutException e)
        { throw SqlUtils.toSQLException("An attempt by a client to checkout a Connection has timed out.", e); }
        catch (CannotAcquireResourceException e)
        { throw SqlUtils.toSQLException("Connections could not be acquired from the underlying database!", "08001", e); }
        catch (Exception e)
        { throw SqlUtils.toSQLException(e); }
    }

    private void waitMarkPhysicalConnectionInUse(Connection physicalConnection) throws InterruptedException
    {
        if (scache != null)
            scache.waitMarkConnectionInUse(physicalConnection);
    }

    private boolean tryMarkPhysicalConnectionInUse(Connection physicalConnection)
    { return (scache != null ? scache.tryMarkConnectionInUse(physicalConnection) : true); }

    private void unmarkPhysicalConnectionInUse(Connection physicalConnection)
    {
        if (scache != null)
            scache.unmarkConnectionInUse(physicalConnection);
    }

    private void waitMarkPooledConnectionInUse(PooledConnection pooledCon) throws InterruptedException
    {
	if (c3p0PooledConnections)
	    waitMarkPhysicalConnectionInUse(((AbstractC3P0PooledConnection) pooledCon).getPhysicalConnection());
    }

    private boolean tryMarkPooledConnectionInUse(PooledConnection pooledCon)
    {
	if (c3p0PooledConnections)
	    return tryMarkPhysicalConnectionInUse(((AbstractC3P0PooledConnection) pooledCon).getPhysicalConnection());
	else
	    return true;
    }

    private void unmarkPooledConnectionInUse(PooledConnection pooledCon)
    {
	if (c3p0PooledConnections)
	    unmarkPhysicalConnectionInUse(((AbstractC3P0PooledConnection) pooledCon).getPhysicalConnection());
    }

    private Boolean physicalConnectionInUse(Connection physicalConnection) throws InterruptedException
    {
        if (physicalConnection != null && scache != null)
            return scache.inUse(physicalConnection);
	else
	    return null;
    }

    private Boolean pooledConnectionInUse(PooledConnection pc) throws InterruptedException
    {
        if (pc != null && scache != null)
            return scache.inUse(((AbstractC3P0PooledConnection) pc).getPhysicalConnection());
	else
	    return null;
    }



    private Object checkoutAndMarkConnectionInUse() throws TimeoutException, CannotAcquireResourceException, ResourcePoolException, InterruptedException
    {
        Object out = null;
	boolean success = false;
	while (! success)
	    {
		try
		    {
			out = rp.checkoutResource( checkoutTimeout );
			if (out instanceof AbstractC3P0PooledConnection)
			    {
				// cast should succeed, because scache != null implies c3p0 pooled Connections
				AbstractC3P0PooledConnection acpc = (AbstractC3P0PooledConnection) out;
				Connection physicalConnection = acpc.getPhysicalConnection();
				success = tryMarkPhysicalConnectionInUse(physicalConnection);
			    }
			else
			    success = true; //we don't pool statements from non-c3p0 PooledConnections
		    }
		finally
		    {
			try { if (!success && out != null) rp.checkinResource( out );}
			catch (Exception e) { logger.log(MLevel.WARNING, "Failed to check in a Connection that was unusable due to pending Statement closes.", e); }
		    }
            }
        return out;
    }

    private void unmarkConnectionInUseAndCheckin(PooledConnection pcon) throws ResourcePoolException
    {
        if (scache != null)
        {
            try
            {
                // cast should generally succeed, because scache != null implies c3p0 pooled Connections
                // but clients can try to check-in whatever they want, so there are potential failures here
                AbstractC3P0PooledConnection acpc = (AbstractC3P0PooledConnection) pcon;
                Connection physicalConnection = acpc.getPhysicalConnection();
                unmarkPhysicalConnectionInUse(physicalConnection);
            }
            catch (ClassCastException e)
            {
                if (logger.isLoggable(MLevel.SEVERE))
                    logger.log(MLevel.SEVERE,
                               "You are checking a non-c3p0 PooledConnection implementation into" +
                               "a c3p0 PooledConnectionPool instance that expects only c3p0-generated PooledConnections." +
                               "This isn't good, and may indicate a c3p0 bug, or an unusual (and unspported) use " +
                               "of the c3p0 library.", e);
            }
       }
       rp.checkinResource(pcon);
    }

    private void checkinPooledConnection(PooledConnection pcon) throws SQLException
    {
        //System.err.println(this + " -- CHECKIN");
        try
	    {
		pcon.removeConnectionEventListener( cl );
		unmarkConnectionInUseAndCheckin( pcon );
		markEndRequest( pcon );
	    }
        catch (ResourcePoolException e)
        { throw SqlUtils.toSQLException(e); }
    }

    public float getEffectivePropertyCycle() throws SQLException
    {
        try
        { return rp.getEffectiveExpirationEnforcementDelay() / 1000f; }
        catch (ResourcePoolException e)
        { throw SqlUtils.toSQLException(e); }
    }

    public int getNumThreadsAwaitingCheckout() throws SQLException
    {
        try
        { return rp.getNumCheckoutWaiters(); }
        catch (ResourcePoolException e)
        { throw SqlUtils.toSQLException(e); }
    }

    public int getStatementCacheNumStatements()
    { return scache == null ? 0 : scache.getNumStatements(); }

    public int getStatementCacheNumCheckedOut()
    { return scache == null ? 0 : scache.getNumStatementsCheckedOut(); }

    public int getStatementCacheNumConnectionsWithCachedStatements()
    { return scache == null ? 0 : scache.getNumConnectionsWithCachedStatements(); }

    public String dumpStatementCacheStatus()
    { return scache == null ? "Statement caching disabled." : scache.dumpStatementCacheStatus(); }

    public void close() throws SQLException
    { close( true ); }

    public void close( boolean close_outstanding_connections ) throws SQLException
    {
        // System.err.println(this + " closing.");
        Exception throwMe = null;

        try { if (scache != null) scache.close(); }
        catch (SQLException e)
        { throwMe = e; }

        try
        { rp.close( close_outstanding_connections ); }
        catch (ResourcePoolException e)
        {
            if ( throwMe != null && logger.isLoggable( MLevel.WARNING ) )
                logger.log( MLevel.WARNING, "An Exception occurred while closing the StatementCache.", throwMe);
            throwMe = e;
        }

        if (throwMe != null)
            throw SqlUtils.toSQLException( throwMe );
    }

    class ConnectionEventListenerImpl implements ConnectionEventListener
    {

        //
        // We might want to check Connections in asynchronously,
        // because this is called
        // (indirectly) from a sync'ed method of NewPooledConnection, but
        // NewPooledConnection may be closed synchronously from a sync'ed
        // method of the resource pool, leading to a deadlock. Checking
        // Connections in asynchronously breaks the cycle.
        //
        // But then we want checkins to happen quickly and reliably,
        // whereas pool shutdowns are rare, so perhaps it's best to
        // leave this synchronous, and let the closing of pooled
        // resources on pool closes happen asynchronously to break
        // the deadlock.
        //
        // For now we're leaving both versions around, but with faster
        // and more reliable synchronous checkin enabled, and async closing
        // of resources in BasicResourcePool.close().
        //
        public void connectionClosed(final ConnectionEvent evt)
        {
            //System.err.println("Checking in: " + evt.getSource());

            if (ASYNCHRONOUS_CONNECTION_EVENT_LISTENER)
            {
                Runnable r = new Runnable()
                {
                    public void run()
                    { doCheckinResource( evt ); }
                };
                sharedTaskRunner.postRunnable( r );
            }
            else
                doCheckinResource( evt );
        }

        private void doCheckinResource(ConnectionEvent evt)
        {
            try
            {
		//rp.checkinResource( evt.getSource() );
		checkinPooledConnection( (PooledConnection) evt.getSource() );
	    }
            catch (Exception e)
            {
                //e.printStackTrace();
                logger.log( MLevel.WARNING,
                                "An Exception occurred while trying to check a PooledConection into a ResourcePool.",
                                e );
            }
        }

        //
        // We might want to update the pool asynchronously, because this is called
        // (indirectly) from a sync'ed method of NewPooledConnection, but
        // NewPooledConnection may be closed synchronously from a sync'ed
        // method of the resource pool, leading to a deadlock. Updating
        // pool status asynchronously breaks the cycle.
        //
        // But then we want checkins to happen quickly and reliably,
        // whereas pool shutdowns are rare, so perhaps it's best to
        // leave all ConnectionEvent handling synchronous, and let the closing of pooled
        // resources on pool closes happen asynchronously to break
        // the deadlock.
        //
        // For now we're leaving both versions around, but with faster
        // and more reliable synchrounous ConnectionEventHandling enabled, and async closing
        // of resources in BasicResourcePool.close().
        //
        public void connectionErrorOccurred(final ConnectionEvent evt)
        {
//          System.err.println("CONNECTION ERROR OCCURRED!");
//          System.err.println();
            if ( logger.isLoggable( MLevel.FINE ) )
                logger.fine("CONNECTION ERROR OCCURRED!");

            final PooledConnection pc = (PooledConnection) evt.getSource();
            int status;
            if (pc instanceof NewPooledConnection)
                status = ((NewPooledConnection) pc).getConnectionStatus();
            else //default to invalid connection, but not invalid database
                status = ConnectionTester.CONNECTION_IS_INVALID;

            final int final_status = status;

            if (ASYNCHRONOUS_CONNECTION_EVENT_LISTENER)
            {
                Runnable r = new Runnable()
                {
                    public void run()
                    { doMarkPoolStatus( pc, final_status ); }
                };
                sharedTaskRunner.postRunnable( r );
            }
            else
                doMarkPoolStatus( pc, final_status );
        }

        private void doMarkPoolStatus(PooledConnection pc, int status)
        {
            try
            {
                switch (status)
                {
                case ConnectionTester.CONNECTION_IS_OKAY:
                    throw new RuntimeException("connectionErrorOcccurred() should only be " +
                    "called for errors fatal to the Connection.");
                case ConnectionTester.CONNECTION_IS_INVALID:
                    if (resurrectables == null)
                        rp.markBroken( pc );
                    else
                        resurrectables.markResurrectable( pc );
                    break;
                case ConnectionTester.DATABASE_IS_INVALID:
                    if (logger.isLoggable(MLevel.WARNING))
                        logger.warning("A ConnectionTest has failed, reporting that all previously acquired Connections are likely invalid. " +
                        "The pool will be reset.");
                    rp.resetPool();
                    break;
                default:
                    throw new RuntimeException("Bad Connection Tester (" + connectionTester + ") " +
                                    "returned invalid status (" + status + ").");
                }
            }
            catch ( ResourcePoolException e )
            {
                //System.err.println("Uh oh... our resource pool is probably broken!");
                //e.printStackTrace();
                logger.log(MLevel.WARNING, "Uh oh... our resource pool is probably broken!", e);
            }
        }
    }

    public int getNumConnections() throws SQLException
    {
        try { return rp.getPoolSize(); }
        catch ( Exception e )
        {
            //e.printStackTrace();
            logger.log( MLevel.WARNING, null, e );
            throw SqlUtils.toSQLException( e );
        }
    }

    public int getNumIdleConnections() throws SQLException
    {
        try { return rp.getAvailableCount(); }
        catch ( Exception e )
        {
            //e.printStackTrace();
            logger.log( MLevel.WARNING, null, e );
            throw SqlUtils.toSQLException( e );
        }
    }

    public int getNumBusyConnections() throws SQLException
    {
        try
        { return rp.getAwaitingCheckinNotExcludedCount(); }
        catch ( Exception e )
        {
            //e.printStackTrace();
            logger.log( MLevel.WARNING, null, e );
            throw SqlUtils.toSQLException( e );
        }
    }

    public int getNumUnclosedOrphanedConnections() throws SQLException
    {
        try { return rp.getExcludedCount(); }
        catch ( Exception e )
        {
            //e.printStackTrace();
            logger.log( MLevel.WARNING, null, e );
            throw SqlUtils.toSQLException( e );
        }
    }

    public long getStartTime() throws SQLException
    {
        try { return rp.getStartTime(); }
        catch ( Exception e )
        {
            //e.printStackTrace();
            logger.log( MLevel.WARNING, null, e );
            throw SqlUtils.toSQLException( e );
        }
    }

    public long getUpTime() throws SQLException
    {
        try { return rp.getUpTime(); }
        catch ( Exception e )
        {
            //e.printStackTrace();
            logger.log( MLevel.WARNING, null, e );
            throw SqlUtils.toSQLException( e );
        }
    }

    public long getNumFailedCheckins() throws SQLException
    {
        try { return rp.getNumFailedCheckins(); }
        catch ( Exception e )
        {
            //e.printStackTrace();
            logger.log( MLevel.WARNING, null, e );
            throw SqlUtils.toSQLException( e );
        }
    }

    public long getNumFailedCheckouts() throws SQLException
    {
        try { return rp.getNumFailedCheckouts(); }
        catch ( Exception e )
        {
            //e.printStackTrace();
            logger.log( MLevel.WARNING, null, e );
            throw SqlUtils.toSQLException( e );
        }
    }

    public long getNumFailedIdleTests() throws SQLException
    {
        try { return rp.getNumFailedIdleTests(); }
        catch ( Exception e )
        {
            //e.printStackTrace();
            logger.log( MLevel.WARNING, null, e );
            throw SqlUtils.toSQLException( e );
        }
    }

    public Throwable getLastCheckinFailure() throws SQLException
    {
        try { return rp.getLastCheckinFailure(); }
        catch ( Exception e )
        {
            //e.printStackTrace();
            logger.log( MLevel.WARNING, null, e );
            throw SqlUtils.toSQLException( e );
        }
    }

    public Throwable getLastCheckoutFailure() throws SQLException
    {
        try { return rp.getLastCheckoutFailure(); }
        catch ( Exception e )
        {
            //e.printStackTrace();
            logger.log( MLevel.WARNING, null, e );
            throw SqlUtils.toSQLException( e );
        }
    }

    public Throwable getLastIdleTestFailure() throws SQLException
    {
        try { return rp.getLastIdleCheckFailure(); }
        catch ( Exception e )
        {
            //e.printStackTrace();
            logger.log( MLevel.WARNING, null, e );
            throw SqlUtils.toSQLException( e );
        }
    }

    public Throwable getLastConnectionTestFailure() throws SQLException
    {
        try { return rp.getLastResourceTestFailure(); }
        catch ( Exception e )
        {
            //e.printStackTrace();
            logger.log( MLevel.WARNING, null, e );
            throw SqlUtils.toSQLException( e );
        }
    }

    public Throwable getLastAcquisitionFailure() throws SQLException
    {
        try { return rp.getLastAcquisitionFailure(); }
        catch ( Exception e )
        {
            //e.printStackTrace();
            logger.log( MLevel.WARNING, null, e );
            throw SqlUtils.toSQLException( e );
        }
    }

    /**
     * Discards all Connections managed by the pool
     * and reacquires new Connections to populate.
     * Current checked out Connections will still
     * be valid, and should still be checked into the
     * pool (so the pool can destroy them).
     */
    public void reset() throws SQLException
    {
        try { rp.resetPool(); }
        catch ( Exception e )
        {
            //e.printStackTrace();
            logger.log( MLevel.WARNING, null, e );
            throw SqlUtils.toSQLException( e );
        }
    }
}
