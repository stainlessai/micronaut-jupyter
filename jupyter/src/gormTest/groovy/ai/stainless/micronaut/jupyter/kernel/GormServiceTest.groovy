package ai.stainless.micronaut.jupyter.kernel

import io.micronaut.test.extensions.junit5.annotation.MicronautTest

@MicronautTest(environments = ["gorm"], packages = ["ai.stainless.micronaut.jupyter.gorm"])
class GormServiceTest extends KernelSpec {
    
    def "uses gorm data service" () {
        when:
        // execute notebook
        NotebookExecResult notebookResult = executeNotebook("gormDataService")

        then:
        // commands should have executed successfully
        verifyExecution(notebookResult)
        // test stdout of cells
        notebookResult.outJson.cells
            ?.get(0)?.outputs?.find { it.name == "stdout" }
            ?.text?.get(0)?.contains("ai.stainless.micronaut.jupyter.gorm.")
        // test return value of cells
        notebookResult.outJson.cells
            ?.get(2)?.outputs?.find { it.output_type == "execute_result" }
            ?.data?.get("text/plain")?.get(0)?.contains("Fred Dobbs")
        notebookResult.outJson.cells
            ?.get(2)?.outputs?.find { it.output_type == "execute_result" }
            ?.data?.get("text/plain")?.get(0)?.contains("Joshua Carter")
    }

}
