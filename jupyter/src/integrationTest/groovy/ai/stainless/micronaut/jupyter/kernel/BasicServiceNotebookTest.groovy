package ai.stainless.micronaut.jupyter.kernel

/**
 * Runs the use-library example notebook from examples/basic-service.
 * Verifies that the Workflow library executes correctly through the kernel.
 */
class BasicServiceNotebookTest extends KernelSpec {

    def "use-library notebook executes workflow and produces correct result"() {
        when:
        NotebookExecResult result = executeNotebook("use-library")

        then: "notebook executed successfully"
        verifyExecution(result)

        and: "workflow execution produces the expected numeric result"
        result.outJson.cells.any { cell ->
            cell.outputs?.any { output ->
                output.toString().contains("53.333")
            }
        }

        and: "workflow status shows SUCCEEDED"
        result.outJson.cells.any { cell ->
            cell.outputs?.any { output ->
                output.toString().contains("SUCCEEDED")
            }
        }
    }
}
