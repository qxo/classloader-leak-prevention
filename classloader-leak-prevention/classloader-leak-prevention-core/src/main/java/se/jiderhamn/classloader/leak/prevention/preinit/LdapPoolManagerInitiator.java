package se.jiderhamn.classloader.leak.prevention.preinit;

import se.jiderhamn.classloader.leak.prevention.ClassLoaderLeakPreventor;
import se.jiderhamn.classloader.leak.prevention.PreClassLoaderInitiator;

/**
 * The contextClassLoader of the thread loading the com.sun.jndi.ldap.LdapPoolManager class may be kept
 * from being garbage collected, since it will start a new thread if the system property
 * {@code com.sun.jndi.ldap.connect.pool.timeout} is set to a value greater than 0.
 * 
 * See http://java.jiderhamn.se/2012/02/26/classloader-leaks-v-common-mistakes-and-known-offenders/
 * 
 * @author Mattias Jiderhamn
 */
public class LdapPoolManagerInitiator implements PreClassLoaderInitiator {
  @Override
  public void doOutsideClassLoader(ClassLoaderLeakPreventor preventor) {
    try {
       if( System.getProperty("com.sun.jndi.ldap.connect.pool.timeout") != null ){
            Class.forName("com.sun.jndi.ldap.LdapPoolManager");
        }
         Class cls = Class.forName("com.sun.jndi.ldap.LdapCtx");
         Object v = preventor.getStaticFieldValue(cls, "EMPTY_SCHEMA");
		 if (v != null) {
			final Throwable ex = preventor.getFieldValue(v, "readOnlyEx");
			preventor.info("clear " + v + " readOnlyEx:" + ex +" hashCode:"+(ex == null ? null : ex.hashCode()));
			if (ex != null) {
				synchronized (v) {
					preventor.setFieldValue(v, "readOnlyEx", null);
				}
			}
		}
        
    }
    catch(ClassNotFoundException cnfex) {
      if(preventor.isOracleJRE())
        preventor.error(cnfex);
    }
    
  }
}