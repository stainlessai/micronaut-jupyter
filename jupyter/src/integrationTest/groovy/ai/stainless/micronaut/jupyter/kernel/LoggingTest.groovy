package ai.stainless.micronaut.jupyter.kernel

import io.micronaut.test.annotation.MicronautTest

@MicronautTest
class LoggingTest extends KernelSpec {

    def "Logging output doesn't go to cell output" () {

        when:
        // execute notebook
        NotebookExecResult notebookResult = executeNotebook("logging")

        then:
        // commands should have executed successfully
        verifyExecution(notebookResult)
        // test stdout of cell
        notebookResult.outJson.cells?.get(2)?.outputs?.find { it.name == "stdout" }?.text == [
            "Before in-cell logging\n",
            "After in-cell logging\n"
        ]
        notebookResult.outJson.cells?.get(4)?.outputs?.find { it.name == "stdout" }?.text == [
            "Before in-cell logging\n",
            "After in-cell logging\n"
        ]
        notebookResult.outJson.cells?.get(5)?.outputs?.find { it.name == "stdout" }?.text == [
            "Before in-cell logging\n",
            "After in-cell logging\n"
        ]

    }

}
