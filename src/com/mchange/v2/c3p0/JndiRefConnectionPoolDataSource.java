package com.mchange.v2.c3p0;

import com.mchange.v2.c3p0.impl.*;

import java.beans.PropertyVetoException;
import java.io.PrintWriter;
import java.io.Serializable;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Hashtable;
import java.util.logging.Logger;
import javax.naming.NamingException;
import javax.naming.Reference;
import javax.naming.Referenceable;
import javax.sql.ConnectionPoolDataSource;
import javax.sql.PooledConnection;
import com.mchange.v2.beans.BeansUtils;
import com.mchange.v2.log.MLevel;
import com.mchange.v2.log.MLog;
import com.mchange.v2.log.MLogger;
import com.mchange.v2.naming.JavaBeanReferenceMaker;
import com.mchange.v2.naming.JavaBeanObjectFactory;
import com.mchange.v2.naming.ReferenceMaker;

public final class JndiRefConnectionPoolDataSource extends IdentityTokenResolvable implements ConnectionPoolDataSource, Serializable, Referenceable
{
    final static MLogger logger = MLog.getLogger( JndiRefConnectionPoolDataSource.class );

    final static Collection IGNORE_PROPS = Arrays.asList( new String[] {"reference", "pooledConnection"} );

    JndiRefForwardingDataSource     jrfds;
    WrapperConnectionPoolDataSource wcpds;

    String identityToken;

    public JndiRefConnectionPoolDataSource()
    { this( true ); }

    public JndiRefConnectionPoolDataSource( boolean autoregister )
    {
	jrfds = new JndiRefForwardingDataSource();
	wcpds = new WrapperConnectionPoolDataSource();
	wcpds.setNestedDataSource( jrfds );

	if (autoregister)
	    {
		this.identityToken = C3P0ImplUtils.allocateIdentityToken( this );
		C3P0Registry.reregister( this );
	    }
    }

    public boolean isJndiLookupCaching()
    { return jrfds.isCaching();  }

    public void setJndiLookupCaching( boolean caching )
    { jrfds.setCaching( caching ); }

    public Hashtable getJndiEnv()
    { return jrfds.getJndiEnv(); }

    public void setJndiEnv( Hashtable jndiEnv )
    { jrfds.setJndiEnv( jndiEnv ); }

    public Object getJndiName()
    { return jrfds.getJndiName(); }

    public void setJndiName( Object jndiName ) throws PropertyVetoException
    { jrfds.setJndiName( jndiName ); }

    public int getAcquireIncrement()
    { return wcpds.getAcquireIncrement(); }

    public void setAcquireIncrement( int acquireIncrement )
    { wcpds.setAcquireIncrement( acquireIncrement ); }

    public int getAcquireRetryAttempts()
    { return wcpds.getAcquireRetryAttempts(); }

    public void setAcquireRetryAttempts( int ara )
    { wcpds.setAcquireRetryAttempts( ara ); }

    public int getAcquireRetryDelay()
    { return wcpds.getAcquireRetryDelay(); }

    public void setAcquireRetryDelay( int ard )
    { wcpds.setAcquireRetryDelay( ard ); }

    public boolean isAttemptResurrectOnCheckin()
    { return wcpds.isAttemptResurrectOnCheckin(); }

    public void setAttemptResurrectOnCheckin( boolean attemptResurrectOnCheckin )
    { wcpds.setAttemptResurrectOnCheckin( attemptResurrectOnCheckin ); }

    public boolean isAutoCommitOnClose()
    { return wcpds.isAutoCommitOnClose(); }

    public void setAutoCommitOnClose( boolean autoCommitOnClose )
    { wcpds.setAutoCommitOnClose( autoCommitOnClose ); }

    public void setAutomaticTestTable( String att )
    { wcpds.setAutomaticTestTable( att ); }

    public String getAutomaticTestTable()
    { return wcpds.getAutomaticTestTable(); }

    public void setBreakAfterAcquireFailure( boolean baaf )
    { wcpds.setBreakAfterAcquireFailure( baaf ); }

    public boolean isBreakAfterAcquireFailure()
    { return wcpds.isBreakAfterAcquireFailure(); }

    public void setCheckoutTimeout( int ct )
    { wcpds.setCheckoutTimeout( ct ); }

    public int getCheckoutTimeout()
    { return wcpds.getCheckoutTimeout(); }

    public void setConnectionIsValidTimeout( int civt )
    { wcpds.setConnectionIsValidTimeout( civt ); }

    public int getConnectionIsValidTimeout()
    { return wcpds.getConnectionIsValidTimeout(); }

    public String getConnectionTesterClassName()
    { return wcpds.getConnectionTesterClassName(); }

    public void setConnectionTesterClassName( String connectionTesterClassName ) throws PropertyVetoException
    { wcpds.setConnectionTesterClassName( connectionTesterClassName ); }

    public String getConnectionCustomizerClassName()
    { return wcpds.getConnectionCustomizerClassName(); }

    public void setConnectionCustomizerClassName( String connectionCustomizerClassName ) throws PropertyVetoException
    { wcpds.setConnectionCustomizerClassName( connectionCustomizerClassName ); }

    public String getTaskRunnerFactoryClassName()
    { return wcpds.getTaskRunnerFactoryClassName(); }

    public void setTaskRunnerFactoryClassName( String taskRunnerFactoryClassName ) throws PropertyVetoException
    { wcpds.setTaskRunnerFactoryClassName( taskRunnerFactoryClassName ); }

    public String getContextClassLoaderSource()
    { return wcpds.getContextClassLoaderSource(); }

    public void setContextClassLoaderSource( String contextClassLoaderSource ) throws PropertyVetoException
    { wcpds.setContextClassLoaderSource( contextClassLoaderSource ); }

    public boolean isDebugUnreturnedConnectionStackTraces()
    { return wcpds.isDebugUnreturnedConnectionStackTraces(); }

    public void setDebugUnreturnedConnectionStackTraces( boolean debugUnreturnedConnectionStackTraces )
    { wcpds.setDebugUnreturnedConnectionStackTraces( debugUnreturnedConnectionStackTraces ); }

    public boolean isForceIgnoreUnresolvedTransactions()
    { return wcpds.isForceIgnoreUnresolvedTransactions(); }

    public void setForceIgnoreUnresolvedTransactions( boolean forceIgnoreUnresolvedTransactions )
    { wcpds.setForceIgnoreUnresolvedTransactions( forceIgnoreUnresolvedTransactions ); }

    public boolean isForceSynchronousCheckins()
    { return wcpds.isForceSynchronousCheckins(); }

    public void setForceSynchronousCheckins( boolean forceSynchronousCheckins )
    { wcpds.setForceSynchronousCheckins( forceSynchronousCheckins ); }

    public String getIdentityToken()
    { return identityToken; }

    public void setIdentityToken(String identityToken)
    { this.identityToken = identityToken; }

    public void setIdleConnectionTestPeriod( int idleConnectionTestPeriod )
    { wcpds.setIdleConnectionTestPeriod( idleConnectionTestPeriod ); }

    public int getIdleConnectionTestPeriod()
    { return wcpds.getIdleConnectionTestPeriod(); }

    public int getInitialPoolSize()
    { return wcpds.getInitialPoolSize(); }

    public void setInitialPoolSize( int initialPoolSize )
    { wcpds.setInitialPoolSize( initialPoolSize ); }

    public String getMarkSessionBoundaries()
    { return wcpds.getMarkSessionBoundaries(); }

    public void setMarkSessionBoundaries( String markSessionBoundaries ) throws PropertyVetoException
    { wcpds.setMarkSessionBoundaries( markSessionBoundaries ); }

    public int getMaxIdleTime()
    { return wcpds.getMaxIdleTime(); }

    public void setMaxIdleTime( int maxIdleTime )
    { wcpds.setMaxIdleTime( maxIdleTime ); }

    public int getMaxIdleTimeExcessConnections()
    { return wcpds.getMaxIdleTimeExcessConnections(); }

    public void setMaxIdleTimeExcessConnections( int maxIdleTimeExcessConnections )
    { wcpds.setMaxIdleTimeExcessConnections( maxIdleTimeExcessConnections ); }

    public int getMaxPoolSize()
    { return wcpds.getMaxPoolSize(); }

    public void setMaxPoolSize( int maxPoolSize )
    { wcpds.setMaxPoolSize( maxPoolSize ); }

    public int getMaxStatements()
    { return wcpds.getMaxStatements(); }

    public void setMaxStatements( int maxStatements )
    { wcpds.setMaxStatements( maxStatements ); }

    public int getMaxStatementsPerConnection()
    { return wcpds.getMaxStatementsPerConnection(); }

    public void setMaxStatementsPerConnection( int mspc )
    { wcpds.setMaxStatementsPerConnection( mspc ); }

    public int getMinPoolSize()
    { return wcpds.getMinPoolSize(); }

    public void setMinPoolSize( int minPoolSize )
    { wcpds.setMinPoolSize( minPoolSize ); }

    public int getMaxAdministrativeTaskTime()
    { return wcpds.getMaxAdministrativeTaskTime(); }

    public void setMaxAdministrativeTaskTime( int maxAdministrativeTaskTime )
    { wcpds.setMaxAdministrativeTaskTime( maxAdministrativeTaskTime ); }

    public int getMaxConnectionAge()
    { return wcpds.getMaxConnectionAge(); }

    public void setMaxConnectionAge( int maxConnectionAge )
    { wcpds.setMaxConnectionAge( maxConnectionAge ); }

    public String getPreferredTestQuery()
    { return wcpds.getPreferredTestQuery(); }

    public void setPreferredTestQuery( String ptq )
    { wcpds.setPreferredTestQuery( ptq ); }

    public String getUserOverridesAsString()
    { return wcpds.getUserOverridesAsString(); }

    public void setUserOverridesAsString( String userOverridesAsString ) throws PropertyVetoException
    { wcpds.setUserOverridesAsString( userOverridesAsString ); }

    public int getPropertyCycle()
    { return wcpds.getPropertyCycle(); }

    public void setPropertyCycle( int propertyCycle )
    { wcpds.setPropertyCycle( propertyCycle ); }

    public int getUnreturnedConnectionTimeout()
    { return wcpds.getUnreturnedConnectionTimeout(); }

    public void setUnreturnedConnectionTimeout( int unreturnedConnectionTimeout )
    { wcpds.setUnreturnedConnectionTimeout( unreturnedConnectionTimeout ); }

    public int getStatementCacheNumDeferredCloseThreads()
    { return wcpds.getStatementCacheNumDeferredCloseThreads(); }

    public void setStatementCacheNumDeferredCloseThreads( int statementCacheNumDeferredCloseThreads )
    { wcpds.setStatementCacheNumDeferredCloseThreads( statementCacheNumDeferredCloseThreads ); }

    public boolean isTestConnectionOnCheckin()
    { return wcpds.isTestConnectionOnCheckin(); }

    public void setTestConnectionOnCheckin( boolean testConnectionOnCheckin )
    { wcpds.setTestConnectionOnCheckin( testConnectionOnCheckin ); }

    public boolean isTestConnectionOnCheckout()
    { return wcpds.isTestConnectionOnCheckout(); }

    public void setTestConnectionOnCheckout( boolean testConnectionOnCheckout )
    { wcpds.setTestConnectionOnCheckout( testConnectionOnCheckout ); }

    public boolean isPrivilegeSpawnedThreads()
    { return wcpds.isPrivilegeSpawnedThreads(); }

    public void setPrivilegeSpawnedThreads( boolean privilegeSpawnedThreads )
    { wcpds.setPrivilegeSpawnedThreads( privilegeSpawnedThreads ); }

    public String getFactoryClassLocation()
    { return jrfds.getFactoryClassLocation(); }

    public void setFactoryClassLocation( String factoryClassLocation )
    {
	jrfds.setFactoryClassLocation( factoryClassLocation );
	wcpds.setFactoryClassLocation( factoryClassLocation );
    }

    final static JavaBeanReferenceMaker referenceMaker = new JavaBeanReferenceMaker();

    // i'm hesitant to include overrideDefaultUser and overrideDefaultPassword
    // for security reasons
    static
    {
	referenceMaker.setFactoryClassName( C3P0JavaBeanObjectFactory.class.getName() );
	referenceMaker.addReferenceProperty("acquireIncrement");
	referenceMaker.addReferenceProperty("acquireRetryAttempts");
	referenceMaker.addReferenceProperty("acquireRetryDelay");
	referenceMaker.addReferenceProperty("attemptResurrectOnCheckin");
	referenceMaker.addReferenceProperty("autoCommitOnClose");
	referenceMaker.addReferenceProperty("automaticTestTable");
	referenceMaker.addReferenceProperty("breakAfterAcquireFailure");
	referenceMaker.addReferenceProperty("checkoutTimeout");
	referenceMaker.addReferenceProperty("connectionIsValidTimeout");
	referenceMaker.addReferenceProperty("connectionCustomizerClassName");
	referenceMaker.addReferenceProperty("connectionTesterClassName");
	referenceMaker.addReferenceProperty("contextClassLoaderSource");
	referenceMaker.addReferenceProperty("debugUnreturnedConnectionStackTraces");
	referenceMaker.addReferenceProperty("factoryClassLocation");
	referenceMaker.addReferenceProperty("forceIgnoreUnresolvedTransactions");
	referenceMaker.addReferenceProperty("forceSynchronousCheckins");
	referenceMaker.addReferenceProperty("idleConnectionTestPeriod");
	referenceMaker.addReferenceProperty("identityToken");
	referenceMaker.addReferenceProperty("initialPoolSize");
	referenceMaker.addReferenceProperty("jndiEnv");
	referenceMaker.addReferenceProperty("jndiLookupCaching");
	referenceMaker.addReferenceProperty("jndiName");
	referenceMaker.addReferenceProperty("markSessionBoundaries");
	referenceMaker.addReferenceProperty("maxAdministrativeTaskTime");
	referenceMaker.addReferenceProperty("maxConnectionAge");
	referenceMaker.addReferenceProperty("maxIdleTime");
	referenceMaker.addReferenceProperty("maxIdleTimeExcessConnections");
	referenceMaker.addReferenceProperty("maxPoolSize");
	referenceMaker.addReferenceProperty("maxStatements");
	referenceMaker.addReferenceProperty("maxStatementsPerConnection");
	referenceMaker.addReferenceProperty("minPoolSize");
	referenceMaker.addReferenceProperty("preferredTestQuery");
	referenceMaker.addReferenceProperty("privilegeSpawnedThreads");
	referenceMaker.addReferenceProperty("propertyCycle");
	referenceMaker.addReferenceProperty("statementCacheNumDeferredCloseThreads");
	referenceMaker.addReferenceProperty("taskRunnerFactoryClassName");
	referenceMaker.addReferenceProperty("testConnectionOnCheckin");
	referenceMaker.addReferenceProperty("testConnectionOnCheckout");
	referenceMaker.addReferenceProperty("unreturnedConnectionTimeout");
	referenceMaker.addReferenceProperty("userOverridesAsString");
    }

    public Reference getReference() throws NamingException
    { return referenceMaker.createReference( this ); }

    //implementation of javax.sql.ConnectionPoolDataSource
    public PooledConnection getPooledConnection()
	throws SQLException
    { return wcpds.getPooledConnection(); }

    public PooledConnection getPooledConnection(String user, String password)
	throws SQLException
    { return wcpds.getPooledConnection( user, password ); }

    public PrintWriter getLogWriter()
	throws SQLException
    { return wcpds.getLogWriter(); }

    public void setLogWriter(PrintWriter out)
	throws SQLException
    { wcpds.setLogWriter( out ); }

    public void setLoginTimeout(int seconds)
	throws SQLException
    { wcpds.setLoginTimeout( seconds ); }

    public int getLoginTimeout()
	throws SQLException
    { return wcpds.getLoginTimeout(); }

    public String toString()
    {
	StringBuffer sb = new StringBuffer(512);
	sb.append( super.toString() );
	sb.append(" [");
	try { BeansUtils.appendPropNamesAndValues( sb, this, IGNORE_PROPS ); }
	catch (Exception e)
	    {
		//e.printStackTrace();
		if ( Debug.DEBUG && logger.isLoggable( MLevel.FINE ) )
		    logger.log( MLevel.FINE, "An exception occurred while extracting property names and values for toString()", e);
		sb.append( e.toString() );
	    }
	sb.append("]");
	return sb.toString();
    }


    // JDK7 add-on
    public Logger getParentLogger() throws SQLFeatureNotSupportedException
    { throw new SQLFeatureNotSupportedException("javax.sql.DataSource.getParentLogger() is not currently supported by " + this.getClass().getName());}
}
