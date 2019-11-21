package ai.stainless.micronaut.jupyter.kernel

import io.micronaut.test.annotation.MicronautTest

@MicronautTest(environments = ["gorm"], packages = ["ai.stainless.micronaut.jupyter.gorm"])
class GormCollectionTest extends KernelSpec {
    
    def "can fetch collection" () {
        when:
        // execute notebook
        NotebookExecResult notebookResult = executeNotebook("gormCollection")

        then:
        // commands should have executed successfully
        verifyExecution(notebookResult)
        // test stdout of cells
        notebookResult.outJson.cells?.get(3)?.outputs?.find { it.name == "stdout" }?.text == [
            "[Fred Dobbs]\n"
        ]
    }

}
