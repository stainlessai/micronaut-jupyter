package ai.stainless.micronaut.jupyter.kernel

import io.micronaut.test.annotation.MicronautTest

@MicronautTest(environments = ["md"], packages = ["ai.stainless.micronaut.jupyter.md"])
class MicronautDataTest extends KernelSpec {

    def "uses micronaut data repo" () {
        when:
        // execute notebook
        NotebookExecResult notebookResult = executeNotebook("mdRepository")

        then:
        // commands should have executed successfully
        verifyExecution(notebookResult)
        // test stdout of cells
        notebookResult.outJson.cells
            ?.get(0)?.outputs?.find { it.name == "stdout" }
            ?.text?.get(0)?.contains("ai.stainless.micronaut.jupyter.md.")
        // there is a warning message from hibernate I couldn't suppress
        notebookResult.outJson.cells
            ?.get(3)?.outputs?.find { it.name == "stdout" }
            ?.text?.contains("[Fred Dobbs]\n")

        // test return value of cells
        notebookResult.outJson.cells
            ?.get(2)?.outputs?.find { it.output_type == "execute_result" }
            ?.data?.get("text/plain")?.get(0)?.contains("Fred Dobbs")
        notebookResult.outJson.cells
            ?.get(2)?.outputs?.find { it.output_type == "execute_result" }
            ?.data?.get("text/plain")?.get(0)?.contains("Joshua Carter")

    }

}
