package se.jiderhamn.classloader.leak.prevention;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.commons.lang3.JavaVersion;
import se.jiderhamn.classloader.leak.prevention.cleanup.*;
import se.jiderhamn.classloader.leak.prevention.preinit.*;

import static java.util.Collections.synchronizedMap;

/**
 * Orchestrator class responsible for invoking the preventative and cleanup measures.
 * Contains the configuration and can be reused for multiple classloaders (assume it is not itself loaded by the
 * classloader which we want to avoid leaking). In that case, the {@link #logger} may need to be thread safe.
 * @author Mattias Jiderhamn
 */
public class ClassLoaderLeakPreventorFactory {
  
  /** 
   * {@link ClassLoader} to be used when invoking the {@link PreClassLoaderInitiator}s.
   * Defaults to {@link ClassLoader#getSystemClassLoader()}, but could be any other framework or 
   * app server classloader.
   */
  protected final ClassLoader leakSafeClassLoader;
  
  /** 
   * The {@link Logger} that will be passed on to the different {@link PreClassLoaderInitiator}s and 
   * {@link ClassLoaderPreMortemCleanUp}s 
   */
  protected Logger logger = new JULLogger();

  /** 
   * Map from name to {@link PreClassLoaderInitiator}s with all the actions to invoke in the 
   * {@link #leakSafeClassLoader}. Maintains insertion order. Thread safe.
   */
  protected final Map<String, PreClassLoaderInitiator> preInitiators =
      synchronizedMap(new LinkedHashMap<String, PreClassLoaderInitiator>());

  /** 
   * Map from name to {@link ClassLoaderPreMortemCleanUp}s with all the actions to invoke to make a 
   * {@link ClassLoader} ready for Garbage Collection. Maintains insertion order. Thread safe.
   */
  protected final Map<String, ClassLoaderPreMortemCleanUp> cleanUps = 
      synchronizedMap(new LinkedHashMap<String, ClassLoaderPreMortemCleanUp>());

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // Constructors
  
  /** 
   * Create new {@link ClassLoaderLeakPreventorFactory} with {@link ClassLoader#getSystemClassLoader()} as the 
   * {@link #leakSafeClassLoader} and default {@link PreClassLoaderInitiator}s and {@link ClassLoaderPreMortemCleanUp}s. 
   */
  public ClassLoaderLeakPreventorFactory() {
    this(ClassLoader.getSystemClassLoader());
  }

  /** 
   * Create new {@link ClassLoaderLeakPreventorFactory} with supplied {@link ClassLoader} as the 
   * {@link #leakSafeClassLoader} and default {@link PreClassLoaderInitiator}s and {@link ClassLoaderPreMortemCleanUp}s.  
   */
  public ClassLoaderLeakPreventorFactory(ClassLoader leakSafeClassLoader) {
    this.leakSafeClassLoader = leakSafeClassLoader;
    configureDefaults();
  }
  
  /** Configure default {@link PreClassLoaderInitiator}s and {@link ClassLoaderPreMortemCleanUp}s */
  public void configureDefaults() {
    // The pre-initiators part is heavily inspired by Tomcats JreMemoryLeakPreventionListener  
    // See http://svn.apache.org/viewvc/tomcat/trunk/java/org/apache/catalina/core/JreMemoryLeakPreventionListener.java?view=markup
    this.addPreInitiator(AwtToolkitInitiator.class);
    // initSecurityProviders()
    this.addPreInitiator(JdbcDriversInitiator.class);
    this.addPreInitiator(SunAwtAppContextInitiator.class);
    this.addPreInitiator(SecurityPolicyInitiator.class);
    this.addPreInitiator(SecurityProvidersInitiator.class);
    this.addPreInitiator(DocumentBuilderFactoryInitiator.class);
    this.addPreInitiator(ReplaceDOMNormalizerSerializerAbortException.class);
    this.addPreInitiator(DatatypeConverterImplInitiator.class);
    this.addPreInitiator(JavaxSecurityLoginConfigurationInitiator.class);
    this.addPreInitiator(JarUrlConnectionInitiator.class);
    // Load Sun specific classes that may cause leaks
    this.addPreInitiator(LdapPoolManagerInitiator.class);
    this.addPreInitiator(Java2dDisposerInitiator.class);
	if ( ! JavaVersion.JAVA_RECENT.atLeast(JavaVersion.JAVA_9) ) {
		this.addPreInitiator(SunGCInitiator.class);
	}
    this.addPreInitiator(OracleJdbcThreadInitiator.class);

    this.addCleanUp( BeanIntrospectorCleanUp.class);
    
    // Apache Commons Pool can leave unfinished threads. Anything specific we can do?
    this.addCleanUp( BeanELResolverCleanUp.class);
    this.addCleanUp( BeanValidationCleanUp.class);
    this.addCleanUp( JavaServerFaces2746CleanUp.class);
    this.addCleanUp( GeoToolsCleanUp.class);
    // Can we do anything about Google Guice ?
    // Can we do anything about Groovy http://jira.codehaus.org/browse/GROOVY-4154 ?
    this.addCleanUp( IntrospectionUtilsCleanUp.class);
    // Can we do anything about Logback http://jira.qos.ch/browse/LBCORE-205 ?
    this.addCleanUp( IIOServiceProviderCleanUp.class); // clear ImageIO registry
    this.addCleanUp( ThreadGroupContextCleanUp.class);
    this.addCleanUp( X509TrustManagerImplUnparseableExtensionCleanUp.class);
    
    ////////////////////
    // Fix generic leaks
    this.addCleanUp( DriverManagerCleanUp.class);
    
    this.addCleanUp( DefaultAuthenticatorCleanUp.class);

    this.addCleanUp( MBeanCleanUp.class);
    this.addCleanUp( MXBeanNotificationListenersCleanUp.class);
    
    this.addCleanUp( ShutdownHookCleanUp.class);
    this.addCleanUp( PropertyEditorCleanUp.class);
    this.addCleanUp( SecurityProviderCleanUp.class);
    this.addCleanUp( JceSecurityCleanUp.class); // (Probably best to do after deregistering the providers)
    this.addCleanUp( ProxySelectorCleanUp.class);
    this.addCleanUp( RmiTargetsCleanUp.class);
    this.addCleanUp( StopThreadsCleanUp.class);
    this.addCleanUp( ThreadGroupCleanUp.class);
    this.addCleanUp( ThreadLocalCleanUp.class); // This must be done after threads have been stopped, or new ThreadLocals may be added by those threads
    this.addCleanUp( KeepAliveTimerCacheCleanUp.class);
    this.addCleanUp( ResourceBundleCleanUp.class);
    this.addCleanUp( ApacheCommonsLoggingCleanUp.class); // Do this last, in case other shutdown procedures want to log something.
    
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // Factory methods
  
  /** 
   * Create new {@link ClassLoaderLeakPreventor} used to prevent the provided {@link Thread#contextClassLoader} of the
   * {@link Thread#currentThread()} from leaking.
   * 
   * Please be aware that {@link ClassLoaderLeakPreventor}s created by the same factory share the same 
   * {@link PreClassLoaderInitiator} and {@link ClassLoaderPreMortemCleanUp} instances, in case their config is changed. 
   */
  public ClassLoaderLeakPreventor newLeakPreventor() {
    return newLeakPreventor(Thread.currentThread().getContextClassLoader());
  }
  
  /** Create new {@link ClassLoaderLeakPreventor} used to prevent the provided {@link ClassLoader} from leaking */
  public ClassLoaderLeakPreventor newLeakPreventor(ClassLoader classLoader) {
    return new ClassLoaderLeakPreventor(leakSafeClassLoader, classLoader, logger,
        new ArrayList<PreClassLoaderInitiator>(preInitiators.values()), // Snapshot
        new ArrayList<ClassLoaderPreMortemCleanUp>(cleanUps.values())); // Snapshot
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // Methods for configuring the factory 
  
  /** Set logger */
  public void setLogger(Logger logger) {
    this.logger = logger;
  }
  
  /** Add a new {@link PreClassLoaderInitiator}, using the class name as name */
  public void addPreInitiator(PreClassLoaderInitiator preClassLoaderInitiator) {
    addConsideringOrder(this.preInitiators, preClassLoaderInitiator);
  }
  
  public void addPreInitiator(Class<?> cls) {
     PreClassLoaderInitiator preClassLoaderInitiator   = newInstance(cls);
     if( preClassLoaderInitiator != null){
        addConsideringOrder(this.preInitiators, preClassLoaderInitiator);
     }
  }

  /** Add a new {@link ClassLoaderPreMortemCleanUp}, using the class name as name */
  public void addCleanUp(ClassLoaderPreMortemCleanUp classLoaderPreMortemCleanUp) {
    addConsideringOrder(this.cleanUps, classLoaderPreMortemCleanUp);
  }
  
  private <T> T newInstance(Class cls){
        try{
            return  cls == null ? null : (T) cls.newInstance();
         }catch(Throwable ex){
           //ignore
       }
       return null;
  }
  
   public void addCleanUp(Class<?> cls) {
        ClassLoaderPreMortemCleanUp classLoaderPreMortemCleanUp = newInstance(cls);
        if( classLoaderPreMortemCleanUp != null){
            addConsideringOrder(this.cleanUps, classLoaderPreMortemCleanUp);
        }
  }
  
  /** Add new {@link I} entry to {@code map}, taking {@link MustBeAfter} into account */
  private <I> void addConsideringOrder(Map<String, I> map, I newEntry) {
    for(Map.Entry<String, I> entry : map.entrySet()) {
      if(entry.getValue() instanceof MustBeAfter<?>) {
        final Class<? extends ClassLoaderPreMortemCleanUp>[] existingMustBeAfter = 
            ((MustBeAfter<ClassLoaderPreMortemCleanUp>)entry.getValue()).mustBeBeforeMe();
        for(Class<? extends ClassLoaderPreMortemCleanUp> clazz : existingMustBeAfter) {
          if(clazz.isAssignableFrom(newEntry.getClass())) { // Entry needs to be after new entry
            // TODO Resolve order automatically #51
            throw new IllegalStateException(clazz.getName() + " must be added after " + newEntry.getClass());
          }
        }
      }
    }
    
    map.put(newEntry.getClass().getName(), newEntry);
  }

  /** Add a new named {@link ClassLoaderPreMortemCleanUp} */
  public void addCleanUp(String name, ClassLoaderPreMortemCleanUp classLoaderPreMortemCleanUp) {
    this.cleanUps.put(name, classLoaderPreMortemCleanUp);
  }
  
  /** Remove all the currently configured {@link PreClassLoaderInitiator}s */
  public void clearPreInitiators() {
    this.cleanUps.clear();
  }

  /** Remove all the currently configured {@link ClassLoaderPreMortemCleanUp}s */
  public void clearCleanUps() {
    this.cleanUps.clear();
  }
  
  /** 
   * Get instance of {@link PreClassLoaderInitiator} for further configuring.
   * 
   * Please be aware that {@link ClassLoaderLeakPreventor}s created by the same factory share the same 
   * {@link PreClassLoaderInitiator} and {@link ClassLoaderPreMortemCleanUp} instances, in case their config is changed. 
   */
  public <C extends PreClassLoaderInitiator> C getPreInitiator(Class<C> clazz) {
    return (C) this.preInitiators.get(clazz.getName());
  }

  /** 
   * Get instance of {@link ClassLoaderPreMortemCleanUp} for further configuring.
   * 
   * Please be aware that {@link ClassLoaderLeakPreventor}s created by the same factory share the same 
   * {@link PreClassLoaderInitiator} and {@link ClassLoaderPreMortemCleanUp} instances, in case their config is changed. 
   */
  public <C extends ClassLoaderPreMortemCleanUp> C getCleanUp(Class<C> clazz) {
    return (C) this.cleanUps.get(clazz.getName());
  }

  /** Get instance of {@link PreClassLoaderInitiator} for further configuring */
  public <C extends PreClassLoaderInitiator> void removePreInitiator(Class<C> clazz) {
    this.preInitiators.remove(clazz.getName());
  }

  /** Get instance of {@link ClassLoaderPreMortemCleanUp} for further configuring */
  public <C extends ClassLoaderPreMortemCleanUp> void removeCleanUp(Class<C> clazz) {
    this.cleanUps.remove(clazz.getName());
  }
}