package ai.stainless.micronaut.jupyter.kernel

import io.micronaut.test.annotation.MicronautTest

@MicronautTest
class BeansTest extends KernelSpec {

    def "gets beans from application context" () {
        when:
        // execute notebook
        NotebookExecResult notebookResult = executeNotebook("applicationContext")

        then:
        // commands should have executed successfully
        notebookResult.execResult.exitCode == 0
        notebookResult.outResult.exitCode == 0
        notebookResult.outJson != null
        // test stdout of cells
        notebookResult.outJson.cells?.get(0)?.outputs?.find { it.name == "stdout" }?.text == [
            "io.micronaut.context.ApplicationContext\n"
        ]
        notebookResult.outJson.cells?.get(1)?.outputs?.find { it.name == "stdout" }?.text == [
            "ai.stainless.micronaut.jupyter.InstallKernel\n"
        ]
    }

    def "gets beans using service method" () {
        when:
        // execute notebook
        NotebookExecResult notebookResult = executeNotebook("serviceMethod")

        then:
        // commands should have executed successfully
        notebookResult.execResult.exitCode == 0
        notebookResult.outResult.exitCode == 0
        notebookResult.outJson != null
        // test stdout of cells
        notebookResult.outJson.cells?.get(0)?.outputs?.find { it.name == "stdout" }?.text == [
            "ai.stainless.micronaut.jupyter.InstallKernel\n"
        ]
    }

}
