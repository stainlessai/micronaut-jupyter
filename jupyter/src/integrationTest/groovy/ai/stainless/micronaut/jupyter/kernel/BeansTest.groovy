package ai.stainless.micronaut.jupyter.kernel

class BeansTest extends KernelSpec {

    def "gets beans from application context" () {
        when:
        // execute notebook
        NotebookExecResult notebookResult = executeNotebook("applicationContext")

        then:
        // commands should have executed successfully
        verifyExecution(notebookResult)
        // test stdout of cells
        notebookResult.outJson.cells?.get(0)?.outputs?.find { it.name == "stdout" }?.text == [
            "class io.micronaut.context.DefaultApplicationContext\n"
        ]
        notebookResult.outJson.cells?.get(1)?.outputs?.find { it.name == "stdout" }?.text == [
            "class ai.stainless.micronaut.jupyter.InstallKernel\n"
        ]
    }

    def "gets beans using service method" () {
        when:
        // execute notebook
        NotebookExecResult notebookResult = executeNotebook("serviceMethod")

        then:
        // commands should have executed successfully
        verifyExecution(notebookResult)
        // test stdout of cells
        notebookResult.outJson.cells?.get(0)?.outputs?.find { it.name == "stdout" }?.text == [
            "class ai.stainless.micronaut.jupyter.InstallKernel\n"
        ]
    }

}
