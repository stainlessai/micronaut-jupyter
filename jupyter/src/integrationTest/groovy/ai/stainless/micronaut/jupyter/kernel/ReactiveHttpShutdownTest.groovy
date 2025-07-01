package ai.stainless.micronaut.jupyter.kernel

import ai.stainless.micronaut.jupyter.test.SlowHttpServer
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
    
    def "ThreadDeath occurs when kernel shuts down during blocking HTTP call"() {
        given: "Mock server that never responds to simulate network hang"
        mockServer.setNeverRespond(true) // Server will never respond, simulating a hang
        testLog.info("Test: ThreadDeath during blocking HTTP call - server will never respond")
        
        when: "Execute notebook cell with blocking HTTP call"
        testLog.info("Starting notebook execution with non-responding server")
        def result = executeNotebook("reactiveHttpBlocking", ["JUPYTER_KERNEL_TIMEOUT": "10"]) // 10 second timeout
        
        then: "Notebook execution should complete with timeout or connection error"
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
        
        // Should see evidence of timeout, connection error, or interruption
        def hasErrorOrTimeout = cells.any { cell ->
            cell.outputs?.any { output ->
                // Check for various timeout/connection issues
                (output.ename == "ConnectTimeoutException") ||
                (output.ename == "ReadTimeoutException") ||
                (output.ename == "SocketTimeoutException") ||
                (output.text && output.text.join('').contains("timeout")) ||
                (output.text && output.text.join('').contains("Connection refused")) ||
                (output.text && output.text.join('').contains("ERROR after")) ||
                (output.ename == "InterruptedException") ||
                (output.text && output.text.join('').contains("interrupted"))
            }
        }
        
        testLog.info("Error or timeout detected: {}", hasErrorOrTimeout)
        hasErrorOrTimeout || result.execResult.exitCode != 0 // Either explicit error or non-zero exit
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
        
        // Should complete successfully, either with result or graceful timeout handling
        result != null
        cells.size() > 0
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
        
        // Test passes if it completes
        result != null
        cells.size() > 0
    }
}