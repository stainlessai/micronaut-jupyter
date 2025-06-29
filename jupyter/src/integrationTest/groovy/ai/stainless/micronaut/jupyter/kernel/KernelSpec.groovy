package ai.stainless.micronaut.jupyter.kernel

import groovy.json.JsonSlurper
import org.testcontainers.containers.Container.ExecResult
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.BindMode
import org.testcontainers.containers.Network
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.ImageFromDockerfile
import spock.lang.Shared
import spock.lang.Specification
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

class KernelSpec extends Specification {

    @Shared
    Network testNetwork

    @Shared
    GenericContainer micronautContainer

    @Shared
    ImageFromDockerfile jupyterImage = new ImageFromDockerfile("micronaut-jupyter", false)
            .withFileFromClasspath("Dockerfile", "jupyter.Dockerfile")
            .withFileFromClasspath("notebooks/", "notebooks/")

    @Shared
    GenericContainer jupyterContainer

    def setupSpec() {
        // Create shared network for containers to communicate
        testNetwork = Network.newNetwork()

        // Start Micronaut server in container using basic-service example
        micronautContainer = new GenericContainer("openjdk:17-jdk-slim")
                .withNetwork(testNetwork)
                .withNetworkAliases("micronaut-server")
                .withClasspathResourceMapping("application-integration.yml", "/app/application.yml", BindMode.READ_ONLY)
                .withFileSystemBind("/Users/dstieglitz/idea-projects/micronaut-jupyter/examples/basic-service/build/libs", "/app/libs", BindMode.READ_ONLY)
                .withWorkingDirectory("/app")
                .withCommand("java", "-jar", "libs/basic-service-0.1-all.jar")
                .withExposedPorts(8080)
                .waitingFor(Wait.forHttp("/health").forPort(8080).forStatusCode(200))
        micronautContainer.start()

        // Get the container IP from the shared network
        def networkSettings = micronautContainer.getContainerInfo().getNetworkSettings()
        System.err.println("DEBUG: Available networks: " + networkSettings.getNetworks().keySet())

        String micronautIp = networkSettings.getNetworks()
                .values()
                .iterator()
                .next()
                .getIpAddress()

        System.err.println("DEBUG: Micronaut container IP: " + micronautIp)

        // Create custom application config with correct server URL
        String customConfig = """
micronaut:
  server:
    port: 8080
    host: 0.0.0.0
  management:
    endpoints:
      health:
        enabled: true
        sensitive: false
      all:
        enabled: true
        sensitive: false

jupyter:
  kernel:
    install: true
    location: /tmp/test-location/jupyter/kernels
  server-url: http://${micronautIp}:8080
"""

        // Write config to temporary file
        File tempConfigFile = File.createTempFile("micronaut-config-", ".yml")
        tempConfigFile.write(customConfig)
        tempConfigFile.deleteOnExit()

        // Stop the container to restart with correct config
        micronautContainer.stop()

        // Restart with custom config
        micronautContainer = new GenericContainer("openjdk:17-jdk-slim")
                .withNetwork(testNetwork)
                .withNetworkAliases("micronaut-server")
                .withFileSystemBind(tempConfigFile.absolutePath, "/app/application.yml", BindMode.READ_ONLY)
                .withFileSystemBind("/Users/dstieglitz/idea-projects/micronaut-jupyter/examples/basic-service/build/libs", "/app/libs", BindMode.READ_ONLY)
                .withWorkingDirectory("/app")
                .withCommand("java", "-jar", "libs/basic-service-0.1-all.jar")
                .withExposedPorts(8080)
                .waitingFor(Wait.forHttp("/health").forPort(8080).forStatusCode(200))
        micronautContainer.start()

        // Debug: Check Micronaut logs to see if InstallKernel ran
        String micronautLogs = micronautContainer.getLogs()
        System.err.println("DEBUG: Micronaut container logs:")
        System.err.println(micronautLogs)

        // Start Jupyter container connected to same network
        jupyterContainer = new GenericContainer(jupyterImage)
                .withNetwork(testNetwork)
                .withEnv("JUPYTER_PATH", "/tmp/test-location/jupyter")
                .withEnv("JUPYTER_SERVER", "http://${micronautIp}:8080")
                .withFileSystemBind("/tmp", "/tmp", BindMode.READ_WRITE)
        jupyterContainer.start()
    }

    def cleanupSpec() {
        if (jupyterContainer != null) {
            jupyterContainer.stop()
        }
        if (micronautContainer != null) {
            micronautContainer.stop()
        }
        if (testNetwork != null) {
            testNetwork.close()
        }
    }

    protected NotebookExecResult executeNotebook(String notebookName) {
        // create new result
        NotebookExecResult result = new NotebookExecResult()

        // Debug: check environment variables
        def envCheck = jupyterContainer.execInContainer("env")
        System.err.println("DEBUG: Container environment variables:")
        System.err.println(envCheck.stdout)

        def kernelspecCheck = jupyterContainer.execInContainer("/bin/sh",
                "-c", "jupyter kernelspec list")
        System.err.println("DEBUG: Kernelspec check:")
        System.err.println(kernelspecCheck.stdout)

        // Debug: check if the kernel.sh was generated correctly
        def kernelCheck = jupyterContainer.execInContainer("find", "/tmp/test-location/jupyter/kernels", "-name", "kernel.sh", "-exec", "cat", "{}", "\\;")
        System.err.println("DEBUG: Generated kernel.sh content:")
        System.err.println(kernelCheck.stdout)

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

    protected verifyExecution(NotebookExecResult execution) {
        // commands should have executed successfully
        execution.execResult.exitCode == 0
        execution.outResult.exitCode == 0
        execution.outJson != null
    }

}