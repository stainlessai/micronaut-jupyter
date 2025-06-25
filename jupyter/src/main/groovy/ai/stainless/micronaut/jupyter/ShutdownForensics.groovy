package ai.stainless.micronaut.jupyter

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.lang.management.ManagementFactory
import java.lang.management.ThreadMXBean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Comprehensive shutdown forensics to identify what's killing kernels
 */
class ShutdownForensics {
    private static final Logger log = LoggerFactory.getLogger(ShutdownForensics.class)
    private static final AtomicLong shutdownCounter = new AtomicLong(0)
    private static final AtomicBoolean installed = new AtomicBoolean(false)

    /**
     * Install shutdown forensics (call once)
     * Only installs if ENABLE_SHUTDOWN_FORENSICS environment variable is set
     */
    static void install() {
        if (!System.getenv("ENABLE_SHUTDOWN_FORENSICS")) {
            log.debug("Shutdown forensics disabled (ENABLE_SHUTDOWN_FORENSICS not set)")
            return
        }

        if (installed.compareAndSet(false, true)) {
            Thread forensicsHook = new Thread(() -> {
                long shutdownId = shutdownCounter.incrementAndGet()

                log.error("=== SHUTDOWN FORENSICS #{} ===", shutdownId)
                captureShutdownCaller()
                logSystemState()
                logKubernetesInfo()
                logAllThreads()
                log.error("=== END FORENSICS #{} ===", shutdownId)

                try { Thread.sleep(1000) } catch (InterruptedException ignored) {}
            }, "ShutdownForensics")

            forensicsHook.setDaemon(false)
            Runtime.getRuntime().addShutdownHook(forensicsHook)
            log.info("Shutdown forensics installed")
        }
    }

    private static void captureShutdownCaller() {
        log.error("SHUTDOWN TRIGGER ANALYSIS:")

        Thread.getAllStackTraces().forEach { thread, stackTrace ->
            boolean suspicious = stackTrace.any { element ->
                String className = element.getClassName()
                String methodName = element.getMethodName()

                return className.contains("System") && methodName.contains("exit") ||
                        className.contains("Runtime") && methodName.contains("exit") ||
                        className.contains("Shutdown") ||
                        methodName.contains("shutdown") ||
                        methodName.contains("kill") ||
                        methodName.contains("destroy")
            }

            if (suspicious || thread.getName().contains("kernel") ||
                    thread.getName().contains("Jupyter")) {

                log.error("SUSPICIOUS THREAD [{}]:", thread.getName())
                stackTrace.each { element ->
                    log.error("  ${element.className}.${element.methodName}(${element.fileName}:${element.lineNumber})")
                }
            }
        }
    }

    private static void logSystemState() {
        Runtime runtime = Runtime.getRuntime()
        double memoryUsage = ((runtime.totalMemory() - runtime.freeMemory()) * 100.0) / runtime.maxMemory()

        log.error("SYSTEM STATE:")
        log.error("  Memory usage: {}%", String.format("%.1f", memoryUsage))
        log.error("  Max memory: {} MB", runtime.maxMemory() / 1024 / 1024)
        log.error("  Used memory: {} MB", (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024)

        if (memoryUsage > 90) {
            log.error("  *** POSSIBLE OOM TRIGGER ***")
        }
    }

    private static void logKubernetesInfo() {
        log.error("KUBERNETES CHECK:")

        ["HOSTNAME", "POD_NAME", "POD_NAMESPACE", "NODE_NAME", "KUBERNETES_SERVICE_HOST"].each { var ->
            String value = System.getenv(var)
            if (value) log.error("  {}: {}", var, value)
        }

        if (new File("/proc/1/cgroup").exists()) {
            log.error("  Container: YES")
        }
    }

    private static void logAllThreads() {
        log.error("ACTIVE THREADS:")
        Thread.getAllStackTraces().keySet().each { thread ->
            log.error("  {}: {} | {}", thread.getName(), thread.getState(),
                    thread.isDaemon() ? "daemon" : "normal")
        }
    }
}