package ai.stainless.micronaut.jupyter.kernel

import io.micronaut.test.annotation.MicronautTest

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
        notebookResult.outJson.cells?.get(0)?.outputs?.find { it.name == "stdout" }?.text == [
            "1\n",
            "2\n",
            "3\n",
            "4\n"
        ]
        // test return value of cell
        notebookResult.outJson.cells?.get(0)?.outputs?.find { it.output_type == "execute_result" }?.data?.get("text/plain") == [
            "[1, 2, 3, 4]"
        ]

    }

}
