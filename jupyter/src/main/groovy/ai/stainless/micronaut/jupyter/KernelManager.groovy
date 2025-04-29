package ai.stainless.micronaut.jupyter

import ai.stainless.micronaut.jupyter.kernel.KernelExitException
import ai.stainless.micronaut.jupyter.kernel.Micronaut
import ai.stainless.micronaut.jupyter.kernel.StandardStreamHandler
import ai.stainless.micronaut.jupyter.kernel.UnexpectedExitException
import com.twosigma.beakerx.kernel.Kernel
import io.micronaut.context.ApplicationContext
import io.micronaut.context.annotation.Value
import io.micronaut.runtime.context.scope.Refreshable
import io.micronaut.runtime.context.scope.refresh.RefreshEvent
import io.micronaut.runtime.event.annotation.EventListener
import org.apache.groovy.groovysh.util.NoExitSecurityManager
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import jakarta.inject.Inject
import jakarta.inject.Singleton
import java.lang.reflect.UndeclaredThrowableException

/**
 * Manages the Jupyter kernel instances that are created.
 */
//@Refreshable  // removed because refreshing calls destroy which resets streams but doesn't actually destroy the app
//@Singleton   // instantiate plainly 
public class KernelManager {
    private class KernelSecurityManager extends NoExitSecurityManager {

        // intercept exit calls
        @Override
        public void checkExit (int status) {
            throw new UnexpectedExitException("Kernel exited unexpectedly, ending thread.")
        }

    }

    // use custom logger property that can be overwritten by test
    public static Logger log = LoggerFactory.getLogger(KernelManager.class)

//    @Inject
//    ApplicationContext applicationContext

    @Value('${jupyter.kernel.redirectLogOutput:true}')
    Boolean redirectLogOutput

    Class kernelClass = Micronaut
    private List<Thread> kernelThreads = []
    private List<Kernel> kernelInstances = []
    private SecurityManager originalSecurityManager
    private StandardStreamHandler streamHandler

    public KernelManager() {
        // create an set new security manager to manage BeakerX System.exit() calls
        setSecurityManager()

        // start redirecting STDOUT, STDERR, and STDIN now
        streamHandler = new StandardStreamHandler(redirectLogOutput: true)
        streamHandler.init()
        log.info("KernelManager started")
    }

    @PostConstruct
    public void postConstruct () {
        updateRedirectLogOutput()
    }

    @EventListener
    public void onRefresh (RefreshEvent event) {
        updateRedirectLogOutput()
    }

    @PreDestroy
    public void destroy () {
        log.info("destroy called")
        if (log.isDebugEnabled() || log.isTraceEnabled()) {
            for (StackTraceElement ste : Thread.currentThread().getStackTrace()) {
                log.debug("${ste.className}(${ste.fileName}:${ste.lineNumber})".toString())
            }
        }
        // if were overrode a security manager
        if (originalSecurityManager) {
            //restore it
            System.setSecurityManager(originalSecurityManager)
        }

        // stop stream redirects
        streamHandler.restore()
    }

    private void setSecurityManager () {
        //get existing security manager
        SecurityManager existingSm = System.getSecurityManager()
        if (!(existingSm instanceof KernelSecurityManager)) {
            //create new security manager
            SecurityManager sm = new KernelSecurityManager()
            //sm = new NoExitSecurityManager()
            //if there was an existing security manager
            if (existingSm != null) {
                // warn about this
                log.warn "Found existing security manager: $existingSm, will override with custom: $sm"
            }
            //if the existing security manager was an instance of no exit
            if (existingSm instanceof NoExitSecurityManager) {
                // we can't override this due to some sort of a bug, where
                // setting NoExitSecurityManager twice causes a StackOverflowError
                log.warn "Existing security manager is of type NoExitSecurityManager, " +
                    "will not override in order to prevent bug."
            }
            else {
                //we are good to replace with our own security manager
                //set security manager to intercept exit calls from the beakerx kernel
                System.setSecurityManager(sm)
                originalSecurityManager = existingSm
            }
        }
    }

    private void updateRedirectLogOutput () {
        streamHandler.redirectLogOutput = redirectLogOutput
    }

    public void startNewKernel (String connectionFile) {
        // start kernel in new thread
        kernelThreads << Thread.start {

            log.info "Starting new Micronaut kernel! Connection file: $connectionFile"
            Micronaut kernel = null
            try {
                try {
                    // create and run kernel
                    kernel = kernelClass.createKernel([connectionFile] as String[])
                    kernel.applicationContext = ApplicationContext.run()
                    kernel.streamHandler = streamHandler
                    kernelInstances << kernel
                    kernel.init()
                    kernel.run()
                }
                catch (UndeclaredThrowableException e) {
                    if (e.cause) {
                        log.debug "Squelcing UndeclaredThrowableException, throwing ${e.cause.class}"
                        throw e.cause
                    } else {
                        throw new RuntimeException("Received UndeclaredThrowableException with no cause.", e)
                    }
                }
            } catch (KernelExitException e) {
                log.debug "Kernel exited, ending thread."
                log.trace "Recevied KernelExitException:", e
            }
            catch (UnexpectedExitException e) {
                log.warn "Kernel exited unexpectedly.", e
            }
            catch (Throwable e) {
                log.error "Unhandled kernel exception.", e
            }

            log.info "Micronaut kernel exited, ending thread."
            if (kernelInstances.contains(kernel)) {
                kernelInstances.remove(kernel)
            }
            if (kernelThreads.contains(Thread.currentThread())) {
                kernelThreads.remove(Thread.currentThread())
            }

        }
    }

    public List<Kernel> getKernelInstances() {
        return kernelInstances
    }

    public List<Thread> getKernelThreads() {
        return kernelThreads
    }

    public killAllKernels () {
        kernelInstances.each { it.kill() }
    }

    public waitForAllKernels (Long timeout = 0, Boolean throwException = true) {
        log.debug "Waiting for ${kernelThreads.size()} kernel threads to finish (timeout: $timeout)"
        try {
            kernelThreads.collect().each {
                it.join(timeout)
                if (it.isAlive() && throwException) {
                    throw new RuntimeException(
                        "Timeout of $timeout expired while waiting for thread $it to finish."
                    )
                }
            }
        }
        catch (InterruptedException e) {
            throw new RuntimeException (e)
        }
    }
}
