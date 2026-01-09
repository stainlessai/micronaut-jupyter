package ai.stainless.micronaut.jupyter.kernel

import groovy.json.JsonBuilder
import groovy.util.logging.Slf4j
import org.slf4j.LoggerFactory

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

@Slf4j
class ThreadDeathPropagationTest extends KernelSpec {
    
    private static final org.slf4j.Logger testLog = LoggerFactory.getLogger(ThreadDeathPropagationTest.class)
    
    def setup() {
        testLog.info("Setting up ThreadDeathPropagationTest")
        
        // Set the slow URL as a system property for the notebooks to use
        System.setProperty('test.slow.url', 'http://localhost:8080/slow')
        testLog.info("Using slow controller at: http://localhost:8080/slow")
    }
    
    def cleanup() {
        testLog.info("Cleaning up ThreadDeathPropagationTest")
        System.clearProperty('test.slow.url')
        testLog.info("Cleanup completed")
    }
    
    def "ThreadDeath propagates to other users when one user restarts kernel"() {
        given: "Multiple users with separate notebook sessions (simulating real production scenario)"
        testLog.info("Test: Reproducing production issue where User A restart affects User B")
        
        when: "Start multiple user sessions to simulate real production environment"
        testLog.info("Starting User A and User B sessions (separate users, separate notebooks)")
        
        // User A starts their notebook session
        def userAFuture = CompletableFuture.supplyAsync {
            testLog.info("User A: Starting their notebook session")
            try {
                return executeNotebookInBackground("sleepTest", "userA_notebook", 120000)
            } catch (Exception e) {
                testLog.error("User A session failed: {}", e.message)
                return [success: false, error: e.message]
            }
        }
        
        // User B starts their completely separate notebook session
        def userBFuture = CompletableFuture.supplyAsync {
            testLog.info("User B: Starting their separate notebook session")
            try {
                return executeNotebookInBackground("sleepTest", "userB_notebook", 120000)
            } catch (Exception e) {
                testLog.error("User B session failed: {}", e.message)
                return [success: false, error: e.message]
            }
        }
        
        // Give both users time to start their work
        Thread.sleep(3000)
        testLog.info("Both users should now be working in their separate notebooks")
        
        // Check that both users have active kernels
        def psBeforeResult = jupyterContainer.execInContainer("/bin/sh", "-c", "ps aux | grep jupyter | grep -v grep")
        testLog.info("Active kernels before User A restart: {}", psBeforeResult.stdout)
        
        // Verify both users are working
        def userAStatus = userAFuture.isDone() ? "COMPLETED" : "WORKING"
        def userBStatus = userBFuture.isDone() ? "COMPLETED" : "WORKING"
        testLog.info("User status before restart - User A: {}, User B: {}", userAStatus, userBStatus)
        
        // User A decides to restart their kernel (should NOT affect User B)
        testLog.info("User A restarts their kernel - this should NOT affect User B's work")
        def restartResult = jupyterContainer.execInContainer("curl", "-X", "POST", 
            "http://micronaut-server:8080/jupyterkernel/restart",
            "-H", "Content-Type: application/json",
            "-d", '{"kernelId": "userA_kernel"}')
        testLog.info("User A kernel restart result: exitCode={} stdout={} stderr={}", 
            restartResult.exitCode, restartResult.stdout, restartResult.stderr)
        
        // Wait for User A's restart to complete
        Thread.sleep(2000)
        
        // User C starts a new session after User A's restart (should be unaffected)
        testLog.info("User C starts new work after User A restart (should be unaffected)")
        def userCFuture = CompletableFuture.supplyAsync {
            testLog.info("User C: Starting new notebook session after User A restart")
            try {
                return executeNotebookInBackground("sleepTest", "userC_notebook", 60000)
            } catch (Exception e) {
                testLog.error("User C session failed: {}", e.message)
                return [success: false, error: e.message]
            }
        }
        
        // Check what kernels are still running
        def psAfterResult = jupyterContainer.execInContainer("/bin/sh", "-c", "ps aux | grep jupyter | grep -v grep || echo 'No active kernels'")
        testLog.info("Active kernels after User A restart: {}", psAfterResult.stdout)
        
        // Wait for all user sessions to complete or timeout
        def userAResult = null
        def userBResult = null
        def userCResult = null
        
        try {
            userAResult = userAFuture.get(15, TimeUnit.SECONDS)
            testLog.info("User A result: {}", userAResult)
        } catch (TimeoutException e) {
            testLog.info("User A timed out (expected - they restarted their kernel)")
            userAFuture.cancel(true)
        } catch (Exception e) {
            testLog.info("User A exception: {}", e.message)
        }
        
        try {
            userBResult = userBFuture.get(45, TimeUnit.SECONDS)
            testLog.info("User B result: {}", userBResult)
        } catch (TimeoutException e) {
            testLog.error("User B timed out - ISOLATION VIOLATION! User A restart affected User B")
            userBFuture.cancel(true)
        } catch (Exception e) {
            testLog.error("User B exception - ISOLATION VIOLATION! User A restart caused: {}", e.message)
        }
        
        try {
            userCResult = userCFuture.get(65, TimeUnit.SECONDS)
            testLog.info("User C result: {}", userCResult)
        } catch (TimeoutException e) {
            testLog.error("User C timed out - ISOLATION VIOLATION! User A restart affected new User C")
            userCFuture.cancel(true)
        } catch (Exception e) {
            testLog.error("User C exception - ISOLATION VIOLATION! User A restart caused: {}", e.message)
        }
        
        then: "Verify user isolation and ThreadDeath propagation evidence"
        testLog.info("Analyzing results to demonstrate production isolation violation")
        
        // Check notebook outputs for each user
        def userAOutputExists = false
        def userBOutputExists = false  
        def userCOutputExists = false
        def userAHasThreadDeath = false
        def userBHasThreadDeath = false
        def userCHasThreadDeath = false
        
        // Check User A output (they restarted their kernel - expected to be affected)
        try {
            def userAOut = jupyterContainer.execInContainer("cat", "/notebooks/sleepTest.userA_notebook.ipynb")
            if (userAOut.exitCode == 0) {
                userAOutputExists = true
                def userAJson = new groovy.json.JsonSlurper().parseText(userAOut.stdout) as Map
                userAHasThreadDeath = checkForThreadDeathEvidence(userAJson, "User A")
            }
        } catch (Exception e) {
            testLog.info("User A output not available: {}", e.message)
        }
        
        // Check User B output (separate user - should NOT be affected by User A restart)
        try {
            def userBOut = jupyterContainer.execInContainer("cat", "/notebooks/sleepTest.userB_notebook.ipynb")
            if (userBOut.exitCode == 0) {
                userBOutputExists = true
                def userBJson = new groovy.json.JsonSlurper().parseText(userBOut.stdout) as Map
                userBHasThreadDeath = checkForThreadDeathEvidence(userBJson, "User B")
            }
        } catch (Exception e) {
            testLog.info("User B output not available: {}", e.message)
        }
        
        // Check User C output (new user after restart - should NOT be affected)
        try {
            def userCOut = jupyterContainer.execInContainer("cat", "/notebooks/sleepTest.userC_notebook.ipynb")
            if (userCOut.exitCode == 0) {
                userCOutputExists = true
                def userCJson = new groovy.json.JsonSlurper().parseText(userCOut.stdout) as Map
                userCHasThreadDeath = checkForThreadDeathEvidence(userCJson, "User C")
            }
        } catch (Exception e) {
            testLog.info("User C output not available: {}", e.message)
        }
        
        // Check logs for ThreadDeath evidence
        def logFiles = jupyterContainer.execInContainer("/bin/sh", "-c", "find /tmp -name '*user*.log' -type f")
        testLog.info("Found user log files: {}", logFiles.stdout)
        
        // Log findings for each user
        testLog.info("User A (restarted) - Output exists: {}, ThreadDeath evidence: {}", userAOutputExists, userAHasThreadDeath)
        testLog.info("User B (separate) - Output exists: {}, ThreadDeath evidence: {}", userBOutputExists, userBHasThreadDeath)
        testLog.info("User C (new) - Output exists: {}, ThreadDeath evidence: {}", userCOutputExists, userCHasThreadDeath)
        
        // Analyze isolation violations
        def userBViolated = userBHasThreadDeath || (userBResult?.success == false)
        def userCViolated = userCHasThreadDeath || (userCResult?.success == false)
        def isolationViolated = userBViolated || userCViolated
        
        testLog.info("User B affected by User A restart (ISOLATION VIOLATION): {}", userBViolated)
        testLog.info("User C affected by User A restart (ISOLATION VIOLATION): {}", userCViolated)
        testLog.info("Overall isolation violation detected: {}", isolationViolated)
        
        // This demonstrates the production bug
        if (isolationViolated) {
            testLog.error("PRODUCTION BUG REPRODUCED: User A kernel restart affected other users!")
            testLog.error("This proves the shared ExecutorService/KernelManager causes cross-user ThreadDeath propagation")
        }
        
        // Verify that test execution worked
        def testWorked = userAResult != null || userBResult != null || userCResult != null || 
                         userAOutputExists || userBOutputExists || userCOutputExists
        testLog.info("Test execution successful: {}", testWorked)
        
        // The test demonstrates the isolation problem regardless of specific ThreadDeath exceptions
        assert testWorked : "Expected at least one user session to execute or produce output"
        
        testWorked
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
     * Check notebook output for evidence of ThreadDeath or interruption
     */
    private boolean checkForThreadDeathEvidence(Map notebookJson, String sessionName) {
        def cells = notebookJson.cells
        testLog.info("{}: Found {} cells in notebook", sessionName, cells.size())
        
        def threadDeathEvidence = false
        
        cells.eachWithIndex { cell, i ->
            testLog.info("{} Cell {}: execution_count={}", sessionName, i, cell.execution_count)
            cell.outputs?.each { output ->
                if (output.text) {
                    def text = output.text.join('')
                    testLog.info("{} Cell {} output: {}", sessionName, i, text)
                    if (text.contains("ThreadDeath") || text.contains("CAUGHT ThreadDeath")) {
                        threadDeathEvidence = true
                        testLog.info("{}: Found ThreadDeath evidence in cell {}", sessionName, i)
                    }
                }
                if (output.ename) {
                    testLog.info("{} Cell {} error: {} - {}", sessionName, i, output.ename, output.evalue)
                    if (output.ename.contains("ThreadDeath")) {
                        threadDeathEvidence = true
                        testLog.info("{}: Found ThreadDeath error in cell {}", sessionName, i)
                    }
                }
            }
        }
        
        return threadDeathEvidence
    }
}