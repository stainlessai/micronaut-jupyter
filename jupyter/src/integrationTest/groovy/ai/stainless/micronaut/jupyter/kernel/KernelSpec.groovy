package ai.stainless.micronaut.jupyter.kernel

import groovy.json.JsonSlurper
import org.testcontainers.containers.Container.ExecResult
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.BindMode
import org.testcontainers.images.builder.ImageFromDockerfile
import spock.lang.Shared
import org.testcontainers.spock.Testcontainers
import java.nio.file.Paths

/**
 * Stores the results from executing a test notebook in a jupyter container.
 */
class NotebookExecResult {

    /**
     * The process result of executing the notebook.
     */
    ExecResult execResult

    /**
     * The process result of outputting the notebook results.
     */
    ExecResult outResult

    /**
     * The parsed JSON results of executing the notebook.
     */
    Map outJson

}

@Testcontainers
class KernelSpec {

    @Shared
    ImageFromDockerfile jupyterImage = new ImageFromDockerfile("micronaut-jupyter", false)
            .withFileFromClasspath("Dockerfile", "jupyter.Dockerfile")
            .withFileFromClasspath("notebooks/", "notebooks/")
            .withFileFromClasspath("kernel.json", "kernel.json")
            .withFileFromClasspath("kernel.sh", "kernel.sh")

    @Shared
    GenericContainer jupyterContainer = new GenericContainer(jupyterImage)
            .withEnv("JUPYTER_PATH", "/tmp/test-location/jupyter")
            .withFileSystemBind("/tmp", "/tmp", BindMode.READ_WRITE)
            .withExtraHost("host.testcontainers.internal", "host-gateway")
            .withExtraHost("host.docker.internal", "host-gateway")

    protected NotebookExecResult executeNotebook (String notebookName) {
        // create new result
        NotebookExecResult result = new NotebookExecResult()
        // execute notebook
        result.execResult = jupyterContainer.execInContainer(
            "/bin/sh",
            "-c",
            "umask 0000 && jupyter nbconvert --to notebook --execute /notebooks/${notebookName}.ipynb"
        )
        // get output
        result.outResult = jupyterContainer.execInContainer("cat", "/notebooks/${notebookName}.nbconvert.ipynb")
        if (result.outResult.exitCode == 0) {
            result.outJson = new JsonSlurper().parseText(result.outResult.stdout) as Map
        }
        // return result
        return result
    }

    protected verifyExecution (NotebookExecResult execution) {
        // commands should have executed successfully
        execution.execResult.exitCode == 0
        execution.outResult.exitCode == 0
        execution.outJson != null
    }

}