package ai.stainless.micronaut.jupyter

import ai.stainless.micronaut.jupyter.kernel.KernelExitException
import ai.stainless.micronaut.jupyter.kernel.Micronaut
import ai.stainless.micronaut.jupyter.kernel.StandardStreamHandler
import ai.stainless.micronaut.jupyter.kernel.UnexpectedExitException
import com.twosigma.beakerx.kernel.Kernel
import io.micronaut.context.ApplicationContext
import io.micronaut.context.annotation.Value
import io.micronaut.runtime.context.scope.refresh.RefreshEvent
import io.micronaut.runtime.event.annotation.EventListener
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import jakarta.inject.Inject
import jakarta.inject.Singleton
import java.lang.reflect.UndeclaredThrowableException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Consumer

/**
 * Manages the Jupyter kernel instances that are created.
 * This class is responsible for starting, monitoring, and terminating kernel instances.
 */
@Singleton
public class KernelManager {
    private static final Logger log = LoggerFactory.getLogger(KernelManager.class)
    private static final long DEFAULT_SHUTDOWN_TIMEOUT_MS = 5000

    @Value('${jupyter.kernel.redirectLogOutput:true}')
    Boolean redirectLogOutput

    @Value('${jupyter.kernel.shutdownTimeoutMs:5000}')
    Long shutdownTimeoutMs = DEFAULT_SHUTDOWN_TIMEOUT_MS

    private Class<? extends Kernel> kernelClass = Micronaut
    private final List<Kernel> kernelInstances = new CopyOnWriteArrayList<>()
    // Dependency injection for StandardStreamHandler
    @Inject
    private StandardStreamHandler streamHandler
    private ExecutorService kernelExecutor
    private final List<Thread> exitPreventionHooks = new CopyOnWriteArrayList<>()

    @Inject
    private ApplicationContext applicationContext

    /**
     * Creates a new KernelManager
     */
    public KernelManager() {
        log.info("Initializing KernelManager")
        initializeExecutor()
        log.info("KernelManager initialized successfully")
    }

    /**
     * Initialize thread pool with named threads for better debugging
     */
    private void initializeExecutor() {
        ThreadFactory threadFactory = new ThreadFactory() {
            private final AtomicInteger threadNumber = new AtomicInteger(1)

            @Override
            public Thread newThread(Runnable r) {
                Thread thread = new Thread(r, "Jupyter-Kernel-" + threadNumber.getAndIncrement())
                thread.setDaemon(false) // Allow JVM to exit when these threads are running
                return thread
            }
        }

        kernelExecutor = Executors.newCachedThreadPool(threadFactory)
        log.debug("Kernel executor service initialized")
    }

    @PostConstruct
    public void postConstruct() {
        log.debug("PostConstruct: Initializing stream handler")
        streamHandler.redirectLogOutput = redirectLogOutput
        streamHandler.init()
    }

    @EventListener
    public void onRefresh(RefreshEvent event) {
        log.debug("Processing RefreshEvent")
        updateRedirectLogOutput()
    }

    @PreDestroy
    public void destroy() {
        log.info("Destroying KernelManager")
        logStackTraceIfDebugEnabled()

        // Clean up resources
        killAllKernels()

        // Shutdown executor service
        shutdownExecutor()

        // Remove exit prevention hooks
        removeAllExitPreventionHooks()

        // Restore streams
        streamHandler.restore()

        log.info("KernelManager destroyed successfully")
    }

    private void shutdownExecutor() {
        if (kernelExecutor != null && !kernelExecutor.isShutdown()) {
            log.debug("Shutting down kernel executor service")
            kernelExecutor.shutdown()
            try {
                if (!kernelExecutor.awaitTermination(shutdownTimeoutMs, TimeUnit.MILLISECONDS)) {
                    log.warn("Kernel executor did not terminate in {}ms, forcing shutdown", shutdownTimeoutMs)
                    List<Runnable> tasksNotExecuted = kernelExecutor.shutdownNow()
                    log.debug("{} kernel tasks were never executed", tasksNotExecuted.size())
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt()
                log.warn("Interrupted while waiting for kernel executor shutdown", e)
                kernelExecutor.shutdownNow()
            }
        }
    }

    private void logStackTraceIfDebugEnabled() {
        if (log.isDebugEnabled() || log.isTraceEnabled()) {
            StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace()
            for (StackTraceElement ste : stackTrace) {
                log.debug("${ste.className}(${ste.fileName}:${ste.lineNumber})".toString())
            }
        }
    }

    private void updateRedirectLogOutput() {
        if (streamHandler != null) {
            streamHandler.redirectLogOutput = redirectLogOutput
            log.debug("Updated streamHandler.redirectLogOutput to: ${redirectLogOutput}")
        }
    }

    /**
     * Starts a new kernel instance with the specified connection file
     *
     * @param connectionFile Path to the connection file for the kernel
     * @throws IllegalArgumentException if connectionFile is null or empty
     */
    public void startNewKernel(String connectionFile) {
        if (connectionFile == null || connectionFile.trim().isEmpty()) {
            throw new IllegalArgumentException("Connection file path cannot be null or empty")
        }

        log.info("Starting new Micronaut kernel with connection file: ${connectionFile}")

        kernelExecutor.submit(() -> {
            String threadName = Thread.currentThread().getName()
            log.debug("Kernel thread started: {}", threadName)

            Micronaut kernel = null
            try {
                kernel = createAndInitializeKernel(connectionFile)
                // Use add method directly on the original list, not on any unmodifiable wrapper
                kernelInstances.add(kernel)
                kernel.run()
            } catch (KernelExitException e) {
                log.debug("Kernel exited normally, ending thread.")
                log.trace("Received KernelExitException:", e)
            } catch (UnexpectedExitException e) {
                log.warn("Kernel exited unexpectedly.", e)
            } catch (Throwable e) {
                log.error("Unhandled kernel exception.", e)
            } finally {
                cleanupKernel(kernel)
                log.debug("Kernel thread finished: {}", threadName)
            }
        })
    }

    /**
     * Create and initialize a new kernel instance
     */
    private Micronaut createAndInitializeKernel(String connectionFile) throws Exception {
        try {
            // Register exit prevention for this kernel
            Thread exitPreventionHook = addExitPreventionHook();

            // Create and initialize the kernel
            Micronaut kernel = kernelClass.createKernel([connectionFile] as String[])
            kernel.applicationContext = getOrCreateApplicationContext()
            kernel.streamHandler = streamHandler
            kernel.init()

            return kernel
        } catch (UndeclaredThrowableException e) {
            if (e.cause) {
                log.debug("Handling UndeclaredThrowableException by unwrapping the cause: ${e.cause.class}")
                throw e.cause
            } else {
                throw new RuntimeException("Received UndeclaredThrowableException with no cause.", e)
            }
        } catch (Exception e) {
            log.error("Failed to create kernel", e)
            throw e
        }
    }

    /**
     * Get the application context or create a new one if not available
     */
    private ApplicationContext getOrCreateApplicationContext() {
        if (applicationContext != null) {
            return applicationContext
        }

        log.warn("No application context injected, creating a new one")
        return ApplicationContext.run()
    }

    /**
     * Add a shutdown hook to detect and handle System.exit calls
     * This is a modern alternative to using the deprecated SecurityManager
     *
     * @return The Thread of the exit prevention hook
     */
    private Thread addExitPreventionHook() {
        Thread exitPreventionHook = new Thread(() -> {
            String threadName = Thread.currentThread().getName()
            log.warn("Exit prevention hook triggered: {}", threadName)

            // Force interruption of kernel threads to prevent clean exit
            for (Kernel kernel : new ArrayList<>(kernelInstances)) {
                try {
                    log.debug("Attempting to kill kernel from exit prevention hook")
                    kernel.kill()
                } catch (Exception e) {
                    log.error("Failed to kill kernel during exit prevention", e)
                }
            }

            // Interrupt parent thread group
            ThreadGroup parentGroup = Thread.currentThread().getThreadGroup().getParent();
            if (parentGroup != null) {
                parentGroup.interrupt();
            }

            log.info("Exit prevention hook completed: {}", threadName)
        }, "ExitPreventionHook-" + UUID.randomUUID().toString());

        // Set as daemon to ensure JVM can exit
        exitPreventionHook.setDaemon(true);
        exitPreventionHook.setPriority(Thread.MAX_PRIORITY);

        Runtime.getRuntime().addShutdownHook(exitPreventionHook);
        exitPreventionHooks.add(exitPreventionHook);

        log.debug("Added exit prevention hook: {}", exitPreventionHook.getName())
        return exitPreventionHook;
    }

    /**
     * Remove all registered exit prevention hooks
     */
    private void removeAllExitPreventionHooks() {
        Runtime runtime = Runtime.getRuntime();

        log.debug("Removing {} exit prevention hooks", exitPreventionHooks.size())
        for (Thread hook : exitPreventionHooks) {
            try {
                runtime.removeShutdownHook(hook);
                log.debug("Removed exit prevention hook: {}", hook.getName())
            } catch (IllegalStateException e) {
                // JVM is already shutting down, hooks can't be removed
                log.debug("Could not remove hook (JVM shutting down): {}", hook.getName())
            } catch (Exception e) {
                log.warn("Error removing exit prevention hook: {}", hook.getName(), e)
            }
        }

        exitPreventionHooks.clear();
    }

    /**
     * Cleanup resources for a kernel that has terminated
     */
    private void cleanupKernel(Micronaut kernel) {
        if (kernel != null && kernelInstances.contains(kernel)) {
            log.info("Cleaning up resources for terminated kernel")
            kernelInstances.remove(kernel)
        }
    }

    /**
     * Gets the list of active kernel instances
     *
     * @return A copy of the list of active kernel instances
     */
    public List<Kernel> getKernelInstances() {
        return new ArrayList<>(kernelInstances)
    }

    /**
     * Gets the approximate number of active kernel threads
     *
     * @return Active kernel thread count
     */
    public int getActiveKernelCount() {
        return kernelInstances.size()
    }

    /**
     * Kills all active kernel instances
     */
    public void killAllKernels() {
        int count = kernelInstances.size()
        if (count == 0) {
            log.debug("No active kernels to kill")
            return
        }

        log.info("Killing all kernels (count: {})", count)
        List<Kernel> instancesToKill = new ArrayList<>(kernelInstances)

        for (Kernel kernel : instancesToKill) {
            try {
                kernel.kill()
                log.debug("Kernel killed successfully")
            } catch (Exception e) {
                log.error("Error killing kernel", e)
            }
        }

        // Wait for cleanup to complete
        long startTime = System.currentTimeMillis()
        while (!kernelInstances.isEmpty() &&
                System.currentTimeMillis() - startTime < shutdownTimeoutMs) {
            try {
                Thread.sleep(100)
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt()
                log.warn("Interrupted while waiting for kernels to terminate")
                break
            }
        }

        if (!kernelInstances.isEmpty()) {
            log.warn("{} kernels did not terminate within timeout", kernelInstances.size())
        }
    }

    /**
     * Executes a function for each active kernel instance
     *
     * @param consumer Consumer function to execute
     */
    public void forEachKernel(Consumer<Kernel> consumer) {
        for (Kernel kernel : new ArrayList<>(kernelInstances)) {
            try {
                consumer.accept(kernel)
            } catch (Exception e) {
                log.error("Error executing function on kernel", e)
            }
        }
    }
}