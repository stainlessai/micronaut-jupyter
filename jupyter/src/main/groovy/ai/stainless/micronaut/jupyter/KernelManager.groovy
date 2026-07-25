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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Consumer
import java.nio.file.Paths
import java.util.UUID
import java.util.ArrayList
import java.util.Set

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
    
    // Kernel tracking for restart isolation (minimal addition)
    private final Map<String, Kernel> kernelById = new ConcurrentHashMap<>()
    private final Map<String, String> connectionFileToKernelId = new ConcurrentHashMap<>()
    private final Set<String> reservedKernelIds = ConcurrentHashMap.newKeySet()
    
    // Dependency injection for StandardStreamHandler
    @Inject
    private StandardStreamHandler streamHandler
    // One isolated executor per kernel so killing/restarting one kernel can never
    // touch another kernel's threads
    private final Map<String, ExecutorService> kernelExecutors = new ConcurrentHashMap<>()
    private final List<Thread> exitPreventionHooks = new CopyOnWriteArrayList<>()

    @Inject
    private ApplicationContext applicationContext

    /**
     * Creates a new KernelManager
     */
    public KernelManager() {
        log.info("Initializing KernelManager")
    }

    /**
     * Create an isolated thread pool for a single kernel, with threads named after
     * the kernel for better debugging
     */
    private ExecutorService createKernelExecutor(String kernelId) {
        String shortId = kernelId.length() > 8 ? kernelId.substring(0, 8) : kernelId
        ThreadFactory threadFactory = new ThreadFactory() {
            private final AtomicInteger threadNumber = new AtomicInteger(1)

            @Override
            public Thread newThread(Runnable r) {
                Thread thread = new Thread(r, "Jupyter-Kernel-" + shortId + "-" + threadNumber.getAndIncrement())
                thread.setDaemon(false) // Allow JVM to exit when these threads are running
                // Set default uncaught exception handler for all threads created by this factory
                thread.setUncaughtExceptionHandler(
                    ai.stainless.micronaut.jupyter.kernel.GlobalUncaughtExceptionHandler.createLoggingOnlyHandler()
                )
                return thread
            }
        }

        return Executors.newCachedThreadPool(threadFactory)
    }

    @PostConstruct
    public void postConstruct() {
        log.debug("PostConstruct: Initializing stream handler")
        streamHandler.redirectLogOutput = redirectLogOutput
        streamHandler.init()
        ShutdownForensics.install()
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

        // Shutdown any remaining executor services
        shutdownAllExecutors()

        // Remove exit prevention hooks
        removeAllExitPreventionHooks()

        // Restore streams
        streamHandler.restore()

        log.info("KernelManager destroyed successfully")
    }

    private void shutdownAllExecutors() {
        List<String> kernelIds = new ArrayList<>(kernelExecutors.keySet())
        for (String kernelId : kernelIds) {
            ExecutorService executor = kernelExecutors.remove(kernelId)
            if (executor == null || executor.isShutdown()) {
                continue
            }
            log.debug("Shutting down executor service for kernel '{}'", kernelId)
            executor.shutdown()
            try {
                if (!executor.awaitTermination(shutdownTimeoutMs, TimeUnit.MILLISECONDS)) {
                    log.warn("Executor for kernel '{}' did not terminate in {}ms, forcing shutdown", kernelId, shutdownTimeoutMs)
                    List<Runnable> tasksNotExecuted = executor.shutdownNow()
                    log.debug("{} kernel tasks were never executed", tasksNotExecuted.size())
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt()
                log.warn("Interrupted while waiting for kernel executor shutdown", e)
                executor.shutdownNow()
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

        // Generate unique kernel ID for tracking (restart isolation)
        String kernelId = generateKernelId()

        // Thread-safe check for duplicate kernel ID and prevent starting if already exists
        synchronized (reservedKernelIds) {
            if (reservedKernelIds.contains(kernelId) || kernelById.containsKey(kernelId)) {
                log.warn("Kernel with ID '{}' already exists, ignoring duplicate start request", kernelId)
                return
            }
            // Reserve the kernel ID immediately to prevent race conditions
            reservedKernelIds.add(kernelId)
        }

        connectionFileToKernelId.put(connectionFile, kernelId)

        log.info("Starting new Micronaut kernel with ID '{}' and connection file: {}", kernelId, connectionFile)

        ExecutorService kernelExecutor = createKernelExecutor(kernelId)
        kernelExecutors.put(kernelId, kernelExecutor)

        kernelExecutor.submit(() -> {
            String threadName = Thread.currentThread().getName()
            log.debug("Kernel thread started: {}", threadName)

            // Set up global uncaught exception handler for this thread
            Thread.currentThread().setUncaughtExceptionHandler(
                ai.stainless.micronaut.jupyter.kernel.GlobalUncaughtExceptionHandler.createLoggingOnlyHandler()
            )

            Micronaut kernel = null
            try {
                kernel = createAndInitializeKernel(connectionFile)
                // Wire restart isolation into the kernel's ZMQ sockets: a
                // shutdown_request with restart=true will restart only this kernel
                kernel.setRestartContext(kernelId, () -> restartKernel(kernelId))
                // Update kernel tracking with actual kernel instance
                synchronized (reservedKernelIds) {
                    reservedKernelIds.remove(kernelId)
                    kernelById.put(kernelId, kernel)
                }
                // Use add method directly on the original list, not on any unmodifiable wrapper
                kernelInstances.add(kernel)
                kernel.run()
            } catch (KernelExitException e) {
                log.debug("Kernel '{}' exited normally, ending thread.", kernelId)
                log.trace("Received KernelExitException:", e)
            } catch (UnexpectedExitException e) {
                log.warn("Kernel '{}' exited unexpectedly.", kernelId, e)
            } catch (Throwable e) {
                log.error("Unhandled kernel '{}' exception.", kernelId, e)
            } finally {
                cleanupKernel(kernel, kernelId)
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
     * Generate a unique kernel ID using UUID
     */
    private String generateKernelId() {
        return UUID.randomUUID().toString()
    }

    /**
     * Cleanup resources for a kernel that has terminated
     */
    private void cleanupKernel(Micronaut kernel, String kernelId) {
        if (kernel != null || kernelId != null) {
            log.info("Cleaning up resources for terminated kernel '{}'", kernelId)
            if (kernel != null) {
                kernelInstances.remove(kernel)
            }
            synchronized (reservedKernelIds) {
                reservedKernelIds.remove(kernelId)
                kernelById.remove(kernelId)
            }
            connectionFileToKernelId.entrySet().removeIf(entry -> kernelId.equals(entry.getValue()))

            // Release this kernel's isolated executor. Called from the executor's own
            // thread, so use shutdown() which lets the current task finish.
            ExecutorService executor = kernelExecutors.remove(kernelId)
            if (executor != null) {
                executor.shutdown()
            }
        }
    }

    /**
     * Legacy cleanup method for backward compatibility
     */
    private void cleanupKernel(Micronaut kernel) {
        if (kernel != null && kernelInstances.contains(kernel)) {
            log.info("Cleaning up resources for terminated kernel (legacy)")
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
        int trackingCount = kernelById.size()
        
        log.info("Killing all kernels (active: {}, tracked: {})", count, trackingCount)

        if (count > 0) {
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
        } else {
            log.debug("No active kernels to kill")
        }
        
        // Always clear tracking maps, even if no active kernels
        synchronized (reservedKernelIds) {
            kernelById.clear()
            connectionFileToKernelId.clear()
            reservedKernelIds.clear()
        }

        // Shut down executors for any kernels that did not clean themselves up;
        // each executor terminates once its kernel task finishes
        for (ExecutorService executor : new ArrayList<>(kernelExecutors.values())) {
            executor.shutdown()
        }
        kernelExecutors.clear()

        log.debug("Cleared all kernel tracking maps")
    }

    /**
     * Restart a specific kernel by ID (isolation-aware).
     * Kills only this kernel and releases its resources; the Jupyter client then
     * relaunches the kernel process, which re-registers via the /start endpoint
     * with the same connection file, producing a fresh kernel and executor.
     */
    public void restartKernel(String kernelId) {
        if (kernelId == null || kernelId.trim().isEmpty()) {
            throw new IllegalArgumentException("Kernel ID cannot be null or empty")
        }

        log.info("Restarting kernel with ID: {}", kernelId)
        
        // Get the specific kernel
        Kernel kernel = kernelById.get(kernelId)
        if (kernel == null) {
            log.warn("No kernel found with ID: {}", kernelId)
            return
        }

        try {
            // Kill only the specific kernel
            kernel.kill()
            log.info("Kernel '{}' killed for restart", kernelId)
        } catch (Exception e) {
            log.error("Error killing kernel '{}'", kernelId, e)
        }
    }

    /**
     * Get kernel by ID
     */
    public Kernel getKernelById(String kernelId) {
        return kernelById.get(kernelId)
    }

    /**
     * Get the IDs of all tracked kernels
     */
    public List<String> getKernelIds() {
        return new ArrayList<>(kernelById.keySet())
    }

    /**
     * Get kernel ID from connection file
     */
    public String getKernelIdFromConnectionFile(String connectionFile) {
        return connectionFileToKernelId.get(connectionFile)
    }

    /**
     * Get count of isolated kernels for testing
     */
    public int getIsolatedKernelCount() {
        return kernelById.size()
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