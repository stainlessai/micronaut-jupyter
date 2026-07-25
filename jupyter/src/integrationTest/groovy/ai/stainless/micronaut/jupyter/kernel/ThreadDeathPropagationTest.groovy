package ai.stainless.micronaut.jupyter.kernel

import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j
import org.slf4j.LoggerFactory

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Regression gate for cross-user kernel isolation.
 *
 * This test originally REPRODUCED a production bug where restarting one user's
 * kernel killed every other user's kernel (shared ExecutorService plus
 * killAllKernels() on restart). It now exercises the isolated restart path
 * (POST /jupyterkernel/restart/{kernelId}) and FAILS if restarting one kernel
 * affects any other user's kernel.
 */
@Slf4j
class ThreadDeathPropagationTest extends KernelSpec {

    private static final org.slf4j.Logger testLog = LoggerFactory.getLogger(ThreadDeathPropagationTest.class)

    def setup() {
        testLog.info("Setting up ThreadDeathPropagationTest")
    }

    def cleanup() {
        testLog.info("Cleaning up ThreadDeathPropagationTest")
    }

    def "isolated kernel restart does not affect other users' kernels"() {
        when: "User A and User B work in separate notebook sessions"
        def userAFuture = CompletableFuture.supplyAsync {
            testLog.info("User A: Starting their notebook session")
            try {
                return executeNotebookInBackground("sleepIsolationTest", "userA_notebook", 90000)
            } catch (Exception e) {
                testLog.error("User A session failed: {}", e.message)
                return [success: false, error: e.message]
            }
        }
        def userBFuture = CompletableFuture.supplyAsync {
            testLog.info("User B: Starting their separate notebook session")
            try {
                return executeNotebookInBackground("sleepIsolationTest", "userB_notebook", 90000)
            } catch (Exception e) {
                testLog.error("User B session failed: {}", e.message)
                return [success: false, error: e.message]
            }
        }

        // Both kernels must register with the server before we can restart one
        List<String> kernelIds = waitForKernelIds(2, 90000)
        testLog.info("Active kernels before restart: {}", kernelIds)
        assert kernelIds.size() == 2: "Expected exactly 2 kernels before restart, got: ${kernelIds}"

        // Give both users time to enter their 30 second sleep cells
        Thread.sleep(3000)

        // Restart ONE kernel via the isolated restart endpoint. We cannot know which
        // user it belongs to, but that does not matter: the other kernel must survive.
        String targetKernelId = kernelIds[0]
        testLog.info("Restarting kernel {} via isolated endpoint - other kernels must be unaffected", targetKernelId)
        def restartResult = jupyterContainer.execInContainer("curl", "-s", "-X", "POST",
            "http://micronaut-server:8080/jupyterkernel/restart/${targetKernelId}")
        testLog.info("Isolated restart result: exitCode={} stdout={}", restartResult.exitCode, restartResult.stdout)
        Map restartResponse = new JsonSlurper().parseText(restartResult.stdout) as Map

        // The restarted kernel must actually die (guards against a silent no-op restart)
        boolean targetKernelGone = waitForKernelGone(targetKernelId, 30000)
        testLog.info("Restarted kernel {} cleaned up: {}", targetKernelId, targetKernelGone)

        // User C starts a brand new session after the restart
        def userCFuture = CompletableFuture.supplyAsync {
            testLog.info("User C: Starting new notebook session after the restart")
            try {
                return executeNotebookInBackground("sleepIsolationTest", "userC_notebook", 60000)
            } catch (Exception e) {
                testLog.error("User C session failed: {}", e.message)
                return [success: false, error: e.message]
            }
        }

        awaitQuietly(userAFuture, "User A", 150)
        awaitQuietly(userBFuture, "User B", 150)
        awaitQuietly(userCFuture, "User C", 90)

        boolean userASleptOk = notebookHasSleepSuccess("userA_notebook")
        boolean userBSleptOk = notebookHasSleepSuccess("userB_notebook")
        boolean userCSleptOk = notebookHasSleepSuccess("userC_notebook")
        testLog.info("Sleep completed uninterrupted - User A: {}, User B: {}, User C: {}",
            userASleptOk, userBSleptOk, userCSleptOk)

        if (!(userASleptOk || userBSleptOk)) {
            testLog.error("ISOLATION VIOLATION: restarting kernel {} interrupted every user's notebook", targetKernelId)
        }

        then: "The isolated restart request succeeded"
        restartResponse.status == "ok"

        and: "The restarted kernel was killed and cleaned up"
        targetKernelGone

        and: "Exactly one user was affected: the other user's sleep completed uninterrupted"
        assert userASleptOk || userBSleptOk:
            "ISOLATION VIOLATION: restart of kernel ${targetKernelId} interrupted all users' notebooks"
        assert !(userASleptOk && userBSleptOk):
            "Expected the restarted kernel's notebook to be interrupted, but both notebooks completed - " +
            "the restart appears to have had no effect"

        and: "A new user starting after the restart is unaffected"
        assert userCSleptOk: "User C's fresh session was affected by the earlier restart"
    }

    /**
     * Execute a notebook in background with session tracking
     */
    private def executeNotebookInBackground(String notebookName, String sessionId, long timeoutMs) {
        def outputName = "${notebookName}.${sessionId}"

        testLog.info("Executing notebook {} in background with session ID: {}", notebookName, sessionId)

        // Execute notebook in background using the same pattern as KernelSpec
        def nbclientCmd = "jupyter nbconvert --debug --to notebook --output ${outputName} --output-dir=/notebooks --ExecutePreprocessor.timeout=${timeoutMs} --allow-errors --execute /notebooks/${notebookName}.ipynb"

        def bgProcess = jupyterContainer.execInContainer("/bin/sh", "-c", "nohup ${nbclientCmd} </dev/null >/tmp/nbclient_${sessionId}.log 2>&1 & echo \$!")
        def pid = bgProcess.stdout.trim()

        testLog.info("Background notebook execution started for session {} with PID: {}", sessionId, pid)

        // Wait for process to complete or timeout
        def maxWaitTime = timeoutMs + 5000 // Extra 5 seconds buffer
        def startWait = System.currentTimeMillis()
        def processComplete = false

        while (!processComplete && (System.currentTimeMillis() - startWait) < maxWaitTime) {
            def processCheck = jupyterContainer.execInContainer("/bin/sh", "-c", "ps -p ${pid} > /dev/null 2>&1; echo \$?")
            if (processCheck.stdout.trim() != "0") {
                processComplete = true
                testLog.info("Process {} for session {} has completed", pid, sessionId)
            } else {
                Thread.sleep(1000)
            }
        }

        if (!processComplete) {
            testLog.warn("Process {} for session {} still running after timeout", pid, sessionId)
        }

        // Get the logs
        def logs = jupyterContainer.execInContainer("cat", "/tmp/nbclient_${sessionId}.log")
        testLog.info("Session {} logs: {}", sessionId, logs.stdout)

        return [
            success: processComplete,
            sessionId: sessionId,
            outputName: outputName,
            pid: pid,
            logs: logs.stdout
        ]
    }

    /**
     * Fetch the list of active kernel IDs from the server
     */
    private List<String> fetchKernelIds() {
        try {
            def result = jupyterContainer.execInContainer("curl", "-s", "http://micronaut-server:8080/jupyterkernel/kernels")
            if (result.exitCode != 0) {
                return []
            }
            def json = new JsonSlurper().parseText(result.stdout) as Map
            return (json.kernels ?: []) as List<String>
        } catch (Exception e) {
            testLog.debug("Failed to fetch kernel list: {}", e.message)
            return []
        }
    }

    /**
     * Poll the server until at least the expected number of kernels are registered
     */
    private List<String> waitForKernelIds(int expectedCount, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs
        List<String> ids = []
        while (System.currentTimeMillis() < deadline) {
            ids = fetchKernelIds()
            if (ids.size() >= expectedCount) {
                return ids
            }
            Thread.sleep(1000)
        }
        return ids
    }

    /**
     * Poll the server until the given kernel ID is no longer registered
     */
    private boolean waitForKernelGone(String kernelId, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!fetchKernelIds().contains(kernelId)) {
                return true
            }
            Thread.sleep(1000)
        }
        return false
    }

    /**
     * Wait for a user session future, logging rather than throwing on failure.
     * The assertions are based on notebook contents, not process results.
     */
    private def awaitQuietly(CompletableFuture future, String label, int timeoutSeconds) {
        try {
            def result = future.get(timeoutSeconds, TimeUnit.SECONDS)
            testLog.info("{} result: {}", label, result)
            return result
        } catch (TimeoutException e) {
            testLog.warn("{} timed out after {}s", label, timeoutSeconds)
            future.cancel(true)
            return null
        } catch (Exception e) {
            testLog.warn("{} failed: {}", label, e.message)
            return null
        }
    }

    /**
     * True if the session's output notebook shows its 30 second sleep completing
     * uninterrupted - proof that its kernel was not affected by the restart.
     * The sleep cell evaluates to a "SLEEP_RESULT: ..." string, which the kernel
     * records as an execute_result (println output goes to the server log instead).
     */
    private boolean notebookHasSleepSuccess(String sessionId) {
        try {
            def out = jupyterContainer.execInContainer("cat", "/notebooks/sleepIsolationTest.${sessionId}.ipynb")
            if (out.exitCode != 0) {
                return false
            }
            def json = new JsonSlurper().parseText(out.stdout) as Map
            return json.cells.any { cell ->
                cell.outputs?.any { output ->
                    outputText(output).contains("SLEEP_RESULT: SUCCESS")
                }
            }
        } catch (Exception e) {
            testLog.info("Could not read notebook for session {}: {}", sessionId, e.message)
            return false
        }
    }

    /**
     * Collect the readable text of a notebook cell output, covering both
     * stream outputs (text) and execute_result outputs (data['text/plain'])
     */
    private String outputText(Map output) {
        def parts = []
        if (output.text) {
            parts << (output.text instanceof List ? output.text.join('') : output.text.toString())
        }
        def plain = output.data?.get('text/plain')
        if (plain) {
            parts << (plain instanceof List ? plain.join('') : plain.toString())
        }
        return parts.join('\n')
    }
}
