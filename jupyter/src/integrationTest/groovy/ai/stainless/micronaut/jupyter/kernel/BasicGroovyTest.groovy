package ai.stainless.micronaut.jupyter.kernel

class BasicGroovyTest extends KernelSpec {

    def "executes a Groovy notebook" () {

        when:
        // execute notebook
        NotebookExecResult notebookResult = executeNotebook("println")

        then:
        // commands should have executed successfully
        verifyExecution(notebookResult)
        // test stdout of cell
        notebookResult.outJson.cells[0].outputs[0].text == "hello\n"
    }

}
