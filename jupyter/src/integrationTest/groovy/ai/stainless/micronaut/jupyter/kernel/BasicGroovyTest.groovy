package ai.stainless.micronaut.jupyter.kernel

import io.micronaut.test.extensions.junit5.annotation.MicronautTest

@MicronautTest
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
