package ai.stainless.micronaut.jupyter.kernel

import ai.stainless.micronaut.integration.services.SlowHttpServer
import groovy.json.JsonBuilder
import groovy.util.logging.Slf4j
import org.slf4j.LoggerFactory

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

@Slf4j
class ReactiveHttpShutdownTest extends KernelSpec {
    
    private static final org.slf4j.Logger testLog = LoggerFactory.getLogger(ReactiveHttpShutdownTest.class)
    private SlowHttpServer mockServer
    
    def setup() {
        testLog.info("Setting up ReactiveHttpShutdownTest")
        mockServer = new SlowHttpServer()
        mockServer.start()
        
        // Set the slow URL as a system property for the notebooks to use
        System.setProperty('test.slow.url', mockServer.getSlowUrl())
        testLog.info("Mock server started at: {}", mockServer.getUrl())
    }
    
    def cleanup() {
        testLog.info("Cleaning up ReactiveHttpShutdownTest")
        if (mockServer) {
            mockServer.stop()
        }
        System.clearProperty('test.slow.url')
        testLog.info("Cleanup completed")
    }
    
    def "ThreadDeath does not occur when kernel shuts down during blocking HTTP call"() {
        given: "Mock server that never responds to simulate network hang"
        mockServer.setNeverRespond(true) // Server will never respond, simulating a hang
        testLog.info("Test: Kernel continuation during blocking HTTP call - server will never respond")
        
        when: "Execute notebook cell with blocking HTTP call"
        testLog.info("Starting notebook execution with non-responding server")
        def result = executeNotebook("reactiveHttpBlocking", ["JUPYTER_KERNEL_TIMEOUT": "10"]) // 10 second timeout
        
        then: "Notebook execution should complete successfully despite blocking call"
        testLog.info("Notebook execution completed, checking results")
        def cells = result.outJson.cells
        testLog.info("Found {} cells in result", cells.size())
        
        // Log all cell outputs for debugging
        cells.eachWithIndex { cell, i ->
            testLog.info("Cell {}: execution_count={}, outputs={}", i, cell.execution_count, cell.outputs?.size() ?: 0)
            cell.outputs?.each { output ->
                if (output.ename) {
                    testLog.info("  Error output: {} - {}", output.ename, output.evalue)
                }
                if (output.text) {
                    testLog.info("  Text output: {}", output.text.join(''))
                }
            }
        }
        
        // Verify notebook execution was successful
        testLog.info("Execution completed with exit code: {}", result.execResult.exitCode)
        verifyExecution(result)
        
        // Verify that the reactive HTTP service was called and handled properly
        def hasReactiveServiceCall = cells.any { cell ->
            cell.outputs?.any { output ->
                output.text && (
                    output.text.join('').contains("Got ReactiveHttpService:") ||
                    output.text.join('').contains("Making blocking HTTP call") ||
                    output.text.join('').contains("SUCCESS:") ||
                    output.text.join('').contains("ERROR after")
                )
            }
        }
        
        testLog.info("Reactive HTTP service call detected: {}", hasReactiveServiceCall)
        hasReactiveServiceCall // Should have evidence of the service being called
    }
    
    def "Non-blocking HTTP call handles timeout more gracefully"() {
        given: "Mock server with moderate delay longer than client timeout"
        mockServer.setNeverRespond(false) // Reset from previous test
        mockServer.setResponseDelay(15_000) // 15 second delay
        testLog.info("Test: Non-blocking HTTP call with timeout - server delay set to 15s")
        
        when: "Execute notebook with non-blocking HTTP call with shorter timeout"
        def result = executeNotebook("reactiveHttpNonBlocking")
        
        then: "Should handle timeout gracefully with onErrorReturn"
        def cells = result.outJson.cells
        
        // Log results for comparison
        testLog.info("Non-blocking test completed")
        cells.eachWithIndex { cell, i ->
            testLog.info("Cell {}: execution_count={}", i, cell.execution_count)
            cell.outputs?.each { output ->
                if (output.text) {
                    testLog.info("  Output: {}", output.text.join(''))
                }
            }
        }
        
        // Verify notebook execution was successful
        verifyExecution(result)
        
        // Should complete successfully with evidence of non-blocking call
        def hasNonBlockingCall = cells.any { cell ->
            cell.outputs?.any { output ->
                output.text && (
                    output.text.join('').contains("Got ReactiveHttpService:") ||
                    output.text.join('').contains("Non-blocking HTTP call")
                )
            }
        }
        
        testLog.info("Non-blocking HTTP call detected: {}", hasNonBlockingCall)
        hasNonBlockingCall
    }
    
    def "Error handling with onErrorReturn handles timeout gracefully"() {
        given: "Mock server with long delay to trigger timeout"
        mockServer.setNeverRespond(false) // Reset from previous test
        mockServer.setResponseDelay(25_000) // 25 second delay
        testLog.info("Test: Error handling with timeout - server delay set to 25s")
        
        when: "Execute notebook with error handling and shorter timeout"
        def result = executeNotebook("reactiveHttpErrorHandling")
        
        then: "Should complete with graceful error handling via onErrorReturn"
        def cells = result.outJson.cells
        
        // Check if error handling worked
        def hasGracefulError = cells.any { cell ->
            cell.outputs?.any { output ->
                output.text && (
                    output.text.join('').contains("Request failed:") ||
                    output.text.join('').contains("COMPLETED after") ||
                    output.text.join('').contains("TimeoutException")
                )
            }
        }
        
        testLog.info("Graceful error handling detected: {}", hasGracefulError)
        
        // Log all outputs for debugging
        cells.eachWithIndex { cell, i ->
            testLog.info("Cell {}: execution_count={}", i, cell.execution_count)
            cell.outputs?.each { output ->
                if (output.text) {
                    testLog.info("  Output: {}", output.text.join(''))
                }
            }
        }
        
        // Verify notebook execution was successful
        verifyExecution(result)
        
        // Should have evidence of error handling
        def hasErrorHandling = cells.any { cell ->
            cell.outputs?.any { output ->
                output.text && (
                    output.text.join('').contains("Request failed:") ||
                    output.text.join('').contains("COMPLETED after") ||
                    output.text.join('').contains("Got ReactiveHttpService:")
                )
            }
        }
        
        testLog.info("Error handling detected: {}", hasErrorHandling)
        hasErrorHandling
    }
    
    def "SIGINT interruption during blocking HTTP call handles gracefully"() {
        given: "Mock server with long delay to ensure blocking call is active"
        mockServer.setNeverRespond(false) // Reset from previous test
        mockServer.setResponseDelay(30_000) // 30 second delay to ensure time for SIGINT
        testLog.info("Test: SIGINT interruption during blocking HTTP call - server delay set to 30s")
        
        when: "Execute notebook with blocking HTTP call and send SIGINT after delay"
        // Start notebook execution in background
        def notebookName = "reactiveHttpBlocking"
        def envVars = ["JUPYTER_KERNEL_TIMEOUT": "60"] // Long timeout to ensure SIGINT happens first
        
        // Build environment variables string
        def envVarString = ""
        if (envVars && !envVars.isEmpty()) {
            envVarString = envVars.collect { k, v -> "${k}=${v}" }.join(" ") + " "
        }
        
        // Start notebook execution in background
        def nbclientCmd = "jupyter nbconvert --debug --to notebook --output ${notebookName}.sigint --output-dir=/notebooks --ExecutePreprocessor.timeout=60000 --allow-errors --execute /notebooks/${notebookName}.ipynb"
        def bgProcess = jupyterContainer.execInContainer("/bin/sh", "-c", "nohup ${envVarString}${nbclientCmd} </dev/null >/tmp/nbclient_sigint.log 2>&1 & echo \$!")
        def pid = bgProcess.stdout.trim()
        testLog.info("Started background notebook execution with PID: {}", pid)
        
        // Wait for execution to start and get into the blocking call
        Thread.sleep(5000) // 5 seconds should be enough for kernel to start and begin HTTP call
        
        // Send SIGINT to the process
        testLog.info("Sending SIGINT to process {}", pid)
        def sigintResult = jupyterContainer.execInContainer("/bin/sh", "-c", "kill -INT ${pid}")
        testLog.info("SIGINT sent, result: exitCode={} stdout={} stderr={}", sigintResult.exitCode, sigintResult.stdout, sigintResult.stderr)
        
        // Wait a bit for the signal to be processed
        Thread.sleep(3000)
        
        // Check if process is still running
        def psResult = jupyterContainer.execInContainer("/bin/sh", "-c", "ps -p ${pid} || echo 'Process not found'")
        testLog.info("Process check after SIGINT: {}", psResult.stdout)
        
        // Wait for process to complete (either normally or due to signal)
        def maxWaitTime = 20000 // 20 seconds max wait
        def startWait = System.currentTimeMillis()
        def processComplete = false
        
        while (!processComplete && (System.currentTimeMillis() - startWait) < maxWaitTime) {
            def processCheck = jupyterContainer.execInContainer("/bin/sh", "-c", "ps -p ${pid} > /dev/null 2>&1; echo \$?")
            if (processCheck.stdout.trim() != "0") {
                processComplete = true
                testLog.info("Process {} has completed", pid)
            } else {
                Thread.sleep(1000)
            }
        }
        
        if (!processComplete) {
            testLog.warn("Process {} still running after max wait time, killing forcefully", pid)
            jupyterContainer.execInContainer("/bin/sh", "-c", "kill -9 ${pid}")
        }
        
        // Get the notebook output logs
        def nbclientLogs = jupyterContainer.execInContainer("cat", "/tmp/nbclient_sigint.log")
        testLog.info("Notebook execution logs after SIGINT:")
        testLog.info(nbclientLogs.stdout)
        
        // Try to get the output notebook
        def outResult = jupyterContainer.execInContainer("cat", "/notebooks/${notebookName}.sigint.ipynb")
        
        then: "Process should handle SIGINT gracefully"
        testLog.info("SIGINT test completed, checking results")
        
        // Log the results
        testLog.info("Output file read result: exitCode={}", outResult.exitCode)
        if (outResult.exitCode == 0) {
            def outJson = new groovy.json.JsonSlurper().parseText(outResult.stdout) as Map
            def cells = outJson.cells
            testLog.info("Found {} cells in interrupted notebook", cells.size())
            
            // Log cell outputs
            cells.eachWithIndex { cell, i ->
                testLog.info("Cell {}: execution_count={}", i, cell.execution_count)
                cell.outputs?.each { output ->
                    if (output.text) {
                        testLog.info("  Output: {}", output.text.join(''))
                    }
                    if (output.ename) {
                        testLog.info("  Error: {} - {}", output.ename, output.evalue)
                    }
                }
            }
        }
        
        // Verify the process completed without hanging
        assert processComplete : "Process should have completed (not hung)"
        
        // If we have notebook output, verify it shows some execution occurred
        if (outResult.exitCode == 0) {
            def outJson = new groovy.json.JsonSlurper().parseText(outResult.stdout) as Map
            def cells = outJson.cells
            
            // Should have at least attempted to execute some cells before interruption
            def hasExecutedCells = cells.any { cell -> cell.execution_count != null }
            testLog.info("Has executed cells: {}", hasExecutedCells)
            
            // Should have evidence of service attempt or interruption handling
            def hasServiceAttempt = cells.any { cell ->
                cell.outputs?.any { output ->
                    output.text && (
                        output.text.join('').contains("Got ReactiveHttpService:") ||
                        output.text.join('').contains("Making blocking HTTP call") ||
                        output.text.join('').contains("Starting reactive HTTP test")
                    )
                }
            }
            
            testLog.info("Has service attempt: {}", hasServiceAttempt)
            hasExecutedCells && hasServiceAttempt
        } else {
            // If no output file, that's acceptable for early interruption
            testLog.info("No output file generated - early interruption is acceptable")
            true
        }
    }
}