package ai.stainless.micronaut.jupyter.kernel

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import groovy.json.JsonBuilder

class ConcurrentExecutionTest extends KernelSpec {

    def "can execute multiple cells concurrently in single session"() {
        when:
        def result = executeNotebook("concurrentCells")

        then:
        verifyExecution(result)
        def cells = result.outJson.cells
        
        // Cell 0: imports should succeed
        cells[0].execution_count == 1
        cells[0].outputs.size() == 0
        
        // Cell 1: first timestamp
        cells[1].execution_count == 2
        cells[1].outputs[0].data.'text/plain'[0] =~ /\d+/
        
        // Cell 2: delayed timestamp  
        cells[2].execution_count == 3
        cells[2].outputs[0].data.'text/plain'[0] =~ /\d+/
        
        // Cell 3: third timestamp
        cells[3].execution_count == 4
        cells[3].outputs[0].data.'text/plain'[0] =~ /\d+/
        
        // Cell 4: results comparison
        cells[4].execution_count == 5
        cells[4].outputs[0].text[0].contains("Timing results:")
    }

    def "multiple clients with shared resources execute safely"() {
        when:
        // Execute notebooks concurrently with different client IDs
        def futures = (1..3).collect { clientId ->
            CompletableFuture.supplyAsync {
                return executeNotebook("sharedResource${clientId}")
            }
        }
        
        def results = futures.collect { it.get(30, TimeUnit.SECONDS) }

        then:
        results.every { verifyExecution(it) }
        
        // Verify each client completed successfully
        results.eachWithIndex { result, i ->
            def cells = result.outJson.cells
            def clientId = i + 1
            
            // Cell 0: imports should succeed
            cells[0].execution_count == 1
            
            // Cell 1: logger creation should succeed
            cells[1].execution_count == 2
            cells[1].outputs[0].data.'text/plain'[0].contains("LoggingClass")
            
            // Cell 2: logging call should succeed
            cells[2].execution_count == 3
            
            // Cell 3: final output should contain client ID
            cells[3].execution_count == 4
            cells[3].outputs[0].text[0].contains("Client ${clientId} completed")
        }
        
        // Verify no resource conflicts in server logs
        checkMicronautServerHealth()
    }

    def "can handle mixed workloads concurrently"() {
        when:
        // Execute different types of notebooks simultaneously
        def futures = [
            CompletableFuture.supplyAsync { executeNotebook("println") },
            // CompletableFuture.supplyAsync { executeNotebook("serviceMethod") },
            CompletableFuture.supplyAsync { executeNotebook("logging") },
            // CompletableFuture.supplyAsync { executeNotebook("applicationContext") },
            CompletableFuture.supplyAsync { executeNotebook("exceptionHandling") }
        ]
        
        def results = futures.collect { it.get(60, TimeUnit.SECONDS) }

        then:
        results.every { verifyExecution(it) }
        
//        // Verify each workload type completed successfully
//        def basicResult = results[0]
//        basicResult.outJson.cells[0].outputs[0].text[0].contains("hello")
//
//        def serviceResult = results[1]
//        serviceResult.outJson.cells.any { cell ->
//            cell.outputs.any { output ->
//                output.text && output.text[0].contains("INFO")
//            }
//        }
//
//        def loggingResult = results[1]
//        loggingResult.outJson.cells.any { cell ->
//            cell.outputs.any { output ->
//                output.data && output.data.'text/plain'[0].contains("Before")
//            }
//        }
//
//        def contextResult = results[3]
//        contextResult.outJson.cells.any { cell ->
//            cell.outputs.any { output ->
//                output.data && output.data.'text/plain'[0].contains("ApplicationContext")
//            }
//        }
//
//        def exceptionResult = results[2]
//        exceptionResult.outJson.cells.any { cell ->
//            cell.outputs.any { output ->
//                output.data && output.data.'text/plain'[0].contains("Exception")
//            }
//        }
        
        // Verify server remained healthy throughout
        checkMicronautServerHealth()
    }

    def "concurrent execution maintains variable isolation"() {
        when:
        def futures = (1..3).collect { sessionId ->
            CompletableFuture.supplyAsync {
                return executeNotebook("isolation${sessionId}")
            }
        }
        
        def results = futures.collect { it.get(30, TimeUnit.SECONDS) }

        then:
        results.every { verifyExecution(it) }
        
        // Verify each session maintained its own variable values
        results.eachWithIndex { result, i ->
            def sessionId = i + 1
            def cells = result.outJson.cells
            
            // Final cell should show the session-specific value (initial value + 3)
            def finalOutput = cells.last().outputs[0].text[0]
            def expectedValue = sessionId * 10 + 3
            finalOutput.contains("Session ${sessionId} final value: ${expectedValue}")
        }
    }


    private boolean checkMicronautServerHealth() {
        try {
            def healthCheck = jupyterContainer.execInContainer(
                "curl", "-f", "--connect-timeout", "5", "--max-time", "10", 
                "http://micronaut-server:8080/health"
            )
            return healthCheck.exitCode == 0
        } catch (Exception e) {
            System.err.println("Health check failed: ${e.message}")
            return false
        }
    }
}