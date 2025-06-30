package ai.stainless.micronaut.jupyter.kernel

class ExceptionHandlingTest extends KernelSpec {

    def "handles uncaught exceptions gracefully" () {

        when:
        // execute notebook that throws an uncaught exception
        NotebookExecResult notebookResult = executeNotebook("exceptionHandling")

        then:
        // notebook execution should complete (even though a cell failed)
        verifyExecution(notebookResult)
        
        // verify the cells executed as expected
        def cells = notebookResult.outJson.cells
        
        // Cell 0: imports should succeed
        cells[0].execution_count == 1
        cells[0].outputs.size() == 0 // no output expected for imports
        
        // Cell 1: logger creation should succeed
        cells[1].execution_count == 2
        cells[1].outputs[0].data.'text/plain'[0] == 'Logger[jupyter.notebook.exception]'
        
        // Cell 2: import ExceptionThrower should succeed
        cells[2].execution_count == 3
        cells[2].outputs[0].text[0].contains("Before importing ExceptionThrower")
        cells[2].outputs[0].text[1].contains("After importing ExceptionThrower")
        
        // Cell 3: creating ExceptionThrower instance should succeed
        cells[3].execution_count == 4
        cells[3].outputs[0].text[0].contains("Creating ExceptionThrower instance")
        cells[3].outputs[0].text[1].contains("ExceptionThrower created successfully")
        
        // Cell 4: should throw exception and be handled by global exception handler
        cells[4].execution_count == 5
        cells[4].outputs[0].text[0].contains("About to throw RuntimeException")
        // Should have an error output
        cells[4].outputs.find { it.output_type == 'error' } != null
        def errorOutput = cells[4].outputs.find { it.output_type == 'error' }
        errorOutput.ename.contains("RuntimeException") || errorOutput.ename.contains("Exception")
        errorOutput.evalue.contains("This is a test uncaught exception from ExceptionThrower")
        
        // Cell 5: should still execute after exception, proving kernel is still responsive
        cells[5].execution_count == 6
        cells[5].outputs[0].text[0].contains("This cell should still execute after the exception")
    }

}