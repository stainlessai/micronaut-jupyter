package ai.stainless.micronaut.jupyter.kernel

import groovy.json.JsonSlurper
import org.testcontainers.containers.Container.ExecResult
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.BindMode
import org.testcontainers.containers.Network
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.ImageFromDockerfile
import org.testcontainers.utility.MountableFile
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
    ImageFromDockerfile jupyterImage = new ImageFromDockerfile("micronaut-jupyter-base", true)
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

//        // Debug: Check if kernel files were created by InstallKernel
//        def kernelFilesCheck = micronautContainer.execInContainer("find", "/tmp/test-location", "-name", "*.sh", "-o", "-name", "*.json")
//        System.err.println("DEBUG: Kernel files in Micronaut container: " + kernelFilesCheck.stdout + kernelFilesCheck.stderr)
//
//        // Check the shared /tmp directory
//        def sharedTmpCheck = micronautContainer.execInContainer("ls", "-la", "/tmp")
//        System.err.println("DEBUG: /tmp directory in Micronaut container: " + sharedTmpCheck.stdout)

        // Start Jupyter container connected to same network
        // the "withFileSystemBind" was the ONLY WAY to get kernel.json and kernel.sh
        // into the container.
        // X Dockerfile COPY and .withFileFromPath <- Not working
        // X withCopyToContainer <- Not working
        jupyterContainer = new GenericContainer(jupyterImage)
                .withNetwork(testNetwork)
                .withEnv("JUPYTER_PATH", "/tmp/test-location/jupyter")
                .withEnv("JUPYTER_SERVER", "http://${micronautIp}:8080")
                .withFileSystemBind(
                        Paths.get("src/test/resources/tmp/test-location/jupyter/kernels/micronaut")
                                .toAbsolutePath().toString(),
                        "/tmp/test-location/jupyter/kernels/micronaut", BindMode.READ_WRITE)
        jupyterContainer.start()

        jupyterContainer.execInContainer("chmod", "+x", "/tmp/test-location/jupyter/kernels/micronaut/kernel.sh");

        // Set up port forwarding using socat in the Jupyter container
        System.err.println("DEBUG: Setting up port forwarding from localhost:8080 to micronaut-server:8080")

        // Test direct connection to micronaut-server first
        def directTest = jupyterContainer.execInContainer("curl", "-f", "--connect-timeout", "5", "--max-time", "10", "http://micronaut-server:8080/health")
        System.err.println("DEBUG: Direct connection test: exitCode=" + directTest.exitCode + " stdout=" + directTest.stdout + " stderr=" + directTest.stderr)

        // Start socat port forwarder for HTTP port 8080 only
        // ZMQ ports will be forwarded dynamically by kernel.sh
        def socatResult = jupyterContainer.execInContainer("/bin/sh", "-c", "nohup socat TCP-LISTEN:8080,bind=127.0.0.1,reuseaddr,fork TCP:micronaut-server:8080 </dev/null >/dev/null 2>&1 & echo \$!")
        System.err.println("DEBUG: socat HTTP (8080) start result: exitCode=" + socatResult.exitCode + " stdout=" + socatResult.stdout + " stderr=" + socatResult.stderr)

        // Give socat a moment to start
        Thread.sleep(2000)

        // Test the port forwarding
        def localTest = jupyterContainer.execInContainer("curl", "-f", "--connect-timeout", "5", "--max-time", "10", "http://localhost:8080/health")
        System.err.println("DEBUG: Localhost connection test: exitCode=" + localTest.exitCode + " stdout=" + localTest.stdout + " stderr=" + localTest.stderr)

        // Debug: Check shared /tmp directory in Jupyter container
        def jupyterTmpCheck = jupyterContainer.execInContainer("ls", "-la", "/tmp")
        System.err.println("DEBUG: /tmp directory in Jupyter container: " + jupyterTmpCheck.stdout)

        // Debug: Check if the kernel files exist in the Jupyter container
        def jupyterKernelCheck = jupyterContainer.execInContainer("find", "/tmp/test-location", "-name", "*.sh", "-o", "-name", "*.json")
        System.err.println("DEBUG: Kernel files in Jupyter container: " + jupyterKernelCheck.stdout + jupyterKernelCheck.stderr)
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

        // Debug: dump contents of all files in kernels directory
        def kernelDirsCheck = jupyterContainer.execInContainer("find", "/tmp/test-location/jupyter/kernels", "-type", "d")
        System.err.println("DEBUG: Kernel directories:")
        System.err.println(kernelDirsCheck.stdout)

        def allKernelFiles = jupyterContainer.execInContainer("find", "/tmp/test-location/jupyter/kernels", "-type", "f")
        System.err.println("DEBUG: All kernel files:")
        System.err.println(allKernelFiles.stdout)

        // Dump content of each file
        def kernelShFiles = jupyterContainer.execInContainer("find", "/tmp/test-location/jupyter/kernels", "-name", "kernel.sh")
        def shFiles = kernelShFiles.stdout.trim().split('\n')
        for (String shFile : shFiles) {
            if (shFile.trim()) {
                def shContent = jupyterContainer.execInContainer("cat", shFile.trim())
                System.err.println("DEBUG: Content of ${shFile}:")
                System.err.println(shContent.stdout)
                System.err.println("-----")
            }
        }

        def kernelJsonFiles = jupyterContainer.execInContainer("find", "/tmp/test-location/jupyter/kernels", "-name", "kernel.json")
        def jsonFiles = kernelJsonFiles.stdout.trim().split('\n')
        for (String jsonFile : jsonFiles) {
            if (jsonFile.trim()) {
                def jsonContent = jupyterContainer.execInContainer("cat", jsonFile.trim())
                System.err.println("DEBUG: Content of ${jsonFile}:")
                System.err.println(jsonContent.stdout)
                System.err.println("-----")
            }
        }

        System.err.println("DEBUG: Running jupyter nb-convert... ")
        // execute notebook
        result.execResult = jupyterContainer.execInContainer(
                "/bin/sh",
                "-c",
                "umask 0000 && jupyter nbconvert --debug --to notebook --execute /notebooks/${notebookName}.ipynb"
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