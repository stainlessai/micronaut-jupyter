package ai.stainless.micronaut.jupyter.kernel

import io.micronaut.test.extensions.junit5.annotation.MicronautTest

@MicronautTest(environments = ["gorm"], packages = ["ai.stainless.micronaut.jupyter.gorm"])
class GormFindersTest extends KernelSpec {
    
    def "uses gorm dynamic finders" () {
        when:
        // execute notebook
        NotebookExecResult notebookResult = executeNotebook("gormFinders")

        then:
        // commands should have executed successfully
        verifyExecution(notebookResult)
        // test return value of cells
        notebookResult.outJson.cells
            ?.get(2)?.outputs?.find { it.output_type == "execute_result" }
            ?.data?.get("text/plain")?.get(0)?.contains("Fred Dobbs")
        notebookResult.outJson.cells
            ?.get(2)?.outputs?.find { it.output_type == "execute_result" }
            ?.data?.get("text/plain")?.get(0)?.contains("Joshua Carter")
        notebookResult.outJson.cells
            ?.get(3)?.outputs?.find { it.output_type == "execute_result" }
            ?.data?.get("text/plain") == ["Joshua"]
    }

}
