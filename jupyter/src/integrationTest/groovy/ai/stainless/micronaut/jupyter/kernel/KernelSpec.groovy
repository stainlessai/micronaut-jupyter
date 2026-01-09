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

        // Start Micronaut server in container using integration test application
        // Use relative paths from project root
        def currentDir = System.getProperty("user.dir")
        // When running from gradle, working directory is the jupyter subproject, so go up one level
        def projectRoot = currentDir.endsWith("/jupyter") ? new File(currentDir).getParent() : currentDir
        // Detect which jar to use based on test context
        def integrationTestJarPath = Paths.get(projectRoot, "jupyter", "build", "libs", "integration-test-0.1-all.jar").toString()
        def mdTestJarPath = Paths.get(projectRoot, "jupyter", "build", "libs", "md-test-0.1-all.jar").toString()
        
        // Check if this is an mdTest by looking for mdTest-specific files or system properties
        def isMdTest = false
        
        // Method 1: Check if md-test jar exists and integration test jar is older
        def mdTestJar = new File(mdTestJarPath)
        def integrationTestJar = new File(integrationTestJarPath)
        
        if (mdTestJar.exists() && integrationTestJar.exists()) {
            // If md-test jar is newer, it's likely we're running mdTest
            isMdTest = mdTestJar.lastModified() > integrationTestJar.lastModified()
        }
        
        // Method 2: Check system property or environment variable
        def testTask = System.getProperty("gradle.test.task") ?: System.getenv("GRADLE_TEST_TASK")
        if (testTask == "mdTest") {
            isMdTest = true
        }
        
        // Method 3: Check if there are mdTest classes being compiled recently
        def mdTestClassesDir = new File(projectRoot, "jupyter/build/classes/groovy/mdTest")
        def integrationTestClassesDir = new File(projectRoot, "jupyter/build/classes/groovy/integrationTest")
        
        if (mdTestClassesDir.exists() && integrationTestClassesDir.exists()) {
            // Check if mdTest classes are newer
            def mdTestTime = mdTestClassesDir.listFiles()?.collect { it.lastModified() }?.max() ?: 0
            def integrationTestTime = integrationTestClassesDir.listFiles()?.collect { it.lastModified() }?.max() ?: 0
            if (mdTestTime > integrationTestTime) {
                isMdTest = true
            }
        }
        
        def jarPath = isMdTest ? mdTestJarPath : integrationTestJarPath
        def jarFile = new File(jarPath)
        
        def testStartupScriptPath = Paths.get(projectRoot, "jupyter", "src", "test", "resources", "test-startup.sh").toString()
        def testLogFilePath = Paths.get(projectRoot, "jupyter", "src", "integrationTest", "resources", "logback-integration-test.xml")
        def integrationConfigPath = Paths.get(projectRoot, "jupyter", "src", "integrationTest", "resources", "application-integration-test.yml").toString()

        // Check if the required JAR exists
        if (!jarFile.exists()) {
            def jarType = isMdTest ? "MD test" : "integration test"
            throw new RuntimeException("Required JAR file not found: ${jarPath}. Run 'gradle assemble' to build the ${jarType} JAR before running tests.")
        }
        
        integrationTestJarPath = jarPath

        def kernelDir = Paths.get(projectRoot, "jupyter", "src", "test", "resources", "kernels", "micronaut").toString()

        // We use a shared /tmp directory among the containers for communication
        def testTempDir = Paths.get(projectRoot, "jupyter", "src", "test", "test_temp").toString()
        // Delete contents of test temp directory if it exists
        def testTempDirFile = new File(testTempDir)
        if (testTempDirFile.exists()) {
            testTempDirFile.deleteDir()
        }
        testTempDirFile.mkdirs()

        // First, create a custom image with all the dependencies
        def micronautImage = new ImageFromDockerfile()
                .withDockerfileFromBuilder { builder ->
                    builder
                            .from("eclipse-temurin:17-jdk-jammy")
                            .run("apt-get update && apt-get install -y net-tools procps curl")
                            .workDir("/app")
                            .build()
                }

        // Then create the container using the custom image
        micronautContainer = new GenericContainer(micronautImage)
                .withNetwork(testNetwork)
                .withNetworkAliases("micronaut-server")
                .withCopyFileToContainer(
                        MountableFile.forHostPath(integrationConfigPath),
                        "/app/application.yml")
                .withCopyFileToContainer(
                        MountableFile.forHostPath(integrationTestJarPath),
                        "/app/libs/integration-test-0.1-all.jar")
                .withCopyFileToContainer(MountableFile.forHostPath(
                        testStartupScriptPath,
                        0774),
                        "/app/test-startup.sh")
                .withFileSystemBind(
                        testTempDir,
                        "/tmp", BindMode.READ_WRITE)
                .withFileSystemBind(
                        kernelDir,
                        "/usr/share/jupyter/kernels/micronaut", BindMode.READ_WRITE)
                .withCopyFileToContainer(MountableFile.forHostPath(testLogFilePath), "/app/libs/logback.xml")
                .withEnv("JUPYTER_KERNEL_BIND_HOST", "0.0.0.0")
                .withWorkingDirectory("/app")
                .withCommand("/bin/sh", "-c", "/app/test-startup.sh")
                .withExposedPorts(8080)
                .waitingFor(Wait.forHttp("/health").forPort(8080).forStatusCode(200))

        micronautContainer.start()

        // Get the container IP from the shared network
        def networkSettings = micronautContainer.getContainerInfo().getNetworkSettings()
//        System.err.println("DEBUG: Available networks: " + networkSettings.getNetworks().keySet())
//
        String micronautIp = networkSettings.getNetworks()
                .values()
                .iterator()
                .next()
                .getIpAddress()
//
//        System.err.println("DEBUG: Micronaut container IP: " + micronautIp)
//
//        // Debug: Check Micronaut logs to see if InstallKernel ran
//        String micronautLogs = micronautContainer.getLogs()
//        System.err.println("DEBUG: Micronaut container logs:")
//        System.err.println(micronautLogs)

//        // Debug: Check if kernel files were created by InstallKernel
//        def kernelFilesCheck = micronautContainer.execInContainer("find", "/usr/share", "-name", "*.sh", "-o", "-name", "*.json")
//        System.err.println("DEBUG: Kernel files in Micronaut container: " + kernelFilesCheck.stdout + kernelFilesCheck.stderr)
//
        // Check the shared /tmp directory
//        def whoami = micronautContainer.execInContainer("whoami")
//        System.err.println("DEBUG: whoami: " + whoami.stdout)

//        def sharedTmpCheck = micronautContainer.execInContainer("ls", "-la", "/app")
//        System.err.println("DEBUG: /app directory in Micronaut container: " + sharedTmpCheck.stdout)
//        def app_config_check = micronautContainer.execInContainer("cat", "/app/application.yml")
//        System.err.println("DEBUG: /app/application.yml: " + app_config_check.stdout)

        // Start Jupyter container connected to same network
        // the "withFileSystemBind" was the ONLY WAY to get kernel.json and kernel.sh
        // into the container.
        // X Dockerfile COPY and .withFileFromPath <- Not working
        // X withCopyToContainer <- Not working
        jupyterContainer = new GenericContainer(jupyterImage)
                .withNetwork(testNetwork)
                .withEnv("JUPYTER_PATH", "/usr/share/jupyter")
                .withEnv("JUPYTER_SERVER", "http://${micronautIp}:8080")
                .withEnv("MICRONAUT_SERVER_IP", micronautIp)
                .withFileSystemBind(
                        testTempDir,
                        "/tmp", BindMode.READ_WRITE)
                .withFileSystemBind(
                        kernelDir,
                        "/usr/share/jupyter/kernels/micronaut", BindMode.READ_WRITE)
        jupyterContainer.start()

        jupyterContainer.execInContainer("chmod", "+x", "/usr/share/jupyter/kernels/micronaut/kernel.sh");

        // Set up port forwarding using socat in the Jupyter container
        System.err.println("DEBUG: Setting up port forwarding from localhost:8080 to micronaut-server:8080")

        // Test direct connection to micronaut-server first
        // def directTest = jupyterContainer.execInContainer("curl", "-f", "--connect-timeout", "5", "--max-time", "10", "http://micronaut-server:8080/health")
        // System.err.println("DEBUG: Direct connection test: exitCode=" + directTest.exitCode + " stdout=" + directTest.stdout + " stderr=" + directTest.stderr)

        // FIXME this is done again in executeNotebook, so marked for deletion
        // Start socat port forwarder for HTTP port 8080 only using the actual IP
        // ZMQ ports will be forwarded dynamically by kernel.sh
//        def socatResult = jupyterContainer.execInContainer("/bin/sh", "-c", "nohup socat TCP-LISTEN:8080,bind=127.0.0.1,reuseaddr,fork TCP:${micronautIp}:8080 </dev/null >/dev/null 2>&1 & echo \$!")
//        System.err.println("DEBUG: socat HTTP (8080) start result: exitCode=" + socatResult.exitCode + " stdout=" + socatResult.stdout.trim() + " stderr=" + socatResult.stderr.trim())
//
//        // Give socat a moment to start
//        Thread.sleep(2000)
        // End - FIXME

        // Test the port forwarding
        // System.err.println("DEBUG: Test port forwarding from localhost:8080 to micronaut-server:8080")
        // def localTest = jupyterContainer.execInContainer("curl", "-f", "--connect-timeout", "5", "--max-time", "10", "http://localhost:8080/health")
        // System.err.println("DEBUG: Localhost connection test: exitCode=" + localTest.exitCode + " stdout=" + localTest.stdout + " stderr=" + localTest.stderr)

        // Test the Jupiter kernel endpoint specifically
//        System.err.println("DEBUG: Test calling jupyterkernel start")
//        def kernelEndpointTest = jupyterContainer.execInContainer("curl", "-v", "--connect-timeout", "5", "--max-time", "10", "-X", "POST", "http://micronaut-server:8080/jupyterkernel/start", "-H", "Content-Type: application/json", "-d", "{\"file\":\"/dummy/path\"}")
//        System.err.println("DEBUG: Kernel endpoint test: exitCode=" + kernelEndpointTest.exitCode + " stdout=" + kernelEndpointTest.stdout + " stderr=" + kernelEndpointTest.stderr)

        // Debug: Check shared /tmp directory in Jupyter container
        // def jupyterTmpCheck = jupyterContainer.execInContainer("ls", "-la", "/tmp")
        // System.err.println("DEBUG: /tmp directory in Jupyter container: " + jupyterTmpCheck.stdout)

        // Debug: Check if the kernel files exist in the Jupyter container
        // def jupyterKernelCheck = jupyterContainer.execInContainer("find", "/usr/share", "-name", "*.sh", "-o", "-name", "*.json")
        // System.err.println("DEBUG: Kernel files in Jupyter container: " + jupyterKernelCheck.stdout + jupyterKernelCheck.stderr)
    }

    def cleanupSpec() {
        System.err.println("DEBUG: cleanupSpec()")
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
        return executeNotebook(notebookName, [:])
    }

    protected NotebookExecResult executeNotebook(String notebookName, Map<String, String> envVars) {
        // create new result
        NotebookExecResult result = new NotebookExecResult()

        // Debug: Check current Micronaut container IP
        def networkSettings = micronautContainer.getContainerInfo().getNetworkSettings()
        String currentMicronautIp = networkSettings.getNetworks()
                .values()
                .iterator()
                .next()
                .getIpAddress()
        System.err.println("DEBUG: Current Micronaut IP: " + currentMicronautIp)

        //
        // UNCOMMENT THIS SECTION TO DEBUG CONTAINER AND NETWORK STATES
        //

        // Debug: Check container states
//        System.err.println("DEBUG: === Container State Check ===")
//        System.err.println("DEBUG: Micronaut container running: " + micronautContainer.isRunning())
//        System.err.println("DEBUG: Jupyter container running: " + jupyterContainer.isRunning())

        // Debug: Check if anything is listening on port 8080 in Micronaut container
//        def netstatResult = micronautContainer.execInContainer("/bin/sh", "-c", "netstat -ln 2>/dev/null || ss -ln 2>/dev/null || echo 'No netstat/ss available'")
//        System.err.println("DEBUG: Network listening ports in Micronaut container:")
//        System.err.println(netstatResult.stdout)
        
        // Debug: Check current Micronaut container logs
//        String currentMicronautLogs = micronautContainer.getLogs()
//        System.err.println("DEBUG: Current Micronaut container logs:")
//        System.err.println(currentMicronautLogs.split('\n').takeRight(20).join('\n')) // Last 20 lines
        
        // Debug: Check if Java process is running
//        def javaProcessCheck = micronautContainer.execInContainer("/bin/sh", "-c", "pgrep java || echo 'No java process found'")
//        System.err.println("DEBUG: Java process check in Micronaut container:")
//        System.err.println(javaProcessCheck.stdout)
        
        // Debug: Check existing socat processes in Jupyter container
//        def socatCheck = jupyterContainer.execInContainer("/bin/sh", "-c", "ps aux | grep socat || echo 'No socat processes found'")
//        System.err.println("DEBUG: Existing socat processes in Jupyter container:")
//        System.err.println(socatCheck.stdout)
        
        // Debug: Test DNS resolution for micronaut-server
//        def nslookupResult = jupyterContainer.execInContainer("/bin/sh", "-c", "nslookup micronaut-server || echo 'DNS lookup failed'")
//        System.err.println("DEBUG: DNS resolution for micronaut-server:")
//        System.err.println(nslookupResult.stdout)
        
        // Debug: Test direct connectivity to new IP
//        def directIpTest = jupyterContainer.execInContainer("curl", "-f", "--connect-timeout", "5", "--max-time", "10", "http://${currentMicronautIp}:8080/health")
//        System.err.println("DEBUG: Direct IP connection test to ${currentMicronautIp}: exitCode=" + directIpTest.exitCode + " stdout=" + directIpTest.stdout + " stderr=" + directIpTest.stderr)
        
        // Debug: Test micronaut-server hostname connectivity  
//        def hostnameTest = jupyterContainer.execInContainer("curl", "-f", "--connect-timeout", "5", "--max-time", "10", "http://micronaut-server:8080/health")
//        System.err.println("DEBUG: Hostname connection test to micronaut-server: exitCode=" + hostnameTest.exitCode + " stdout=" + hostnameTest.stdout + " stderr=" + hostnameTest.stderr)

        // Debug: check environment variables
//        def envCheck = jupyterContainer.execInContainer("env")
//        System.err.println("DEBUG: Container environment variables:")
//        System.err.println(envCheck.stdout)

//        def kernelspecCheck = jupyterContainer.execInContainer("/bin/sh",
//                "-c", "jupyter kernelspec list")
//        System.err.println("DEBUG: Kernelspec check:")
//        System.err.println(kernelspecCheck.stdout)

        // Debug: dump contents of all files in kernels directory
//        def kernelDirsCheck = jupyterContainer.execInContainer("find", "/usr/share/jupyter/kernels", "-type", "d")
//        System.err.println("DEBUG: Kernel directories:")
//        System.err.println(kernelDirsCheck.stdout)

//        def allKernelFiles = jupyterContainer.execInContainer("find", "/usr/share/jupyter/kernels", "-type", "f")
//        System.err.println("DEBUG: All kernel files:")
//        System.err.println(allKernelFiles.stdout)

        // Dump content of each file
//        def kernelShFiles = jupyterContainer.execInContainer("find", "/usr/share/jupyter/kernels", "-name", "kernel.sh")
//        def shFiles = kernelShFiles.stdout.trim().split('\n')
//        for (String shFile : shFiles) {
//            if (shFile.trim()) {
//                def shContent = jupyterContainer.execInContainer("cat", shFile.trim())
//                System.err.println("DEBUG: Content of ${shFile}:")
//                System.err.println(shContent.stdout)
//                System.err.println("-----")
//            }
//        }

//        def kernelJsonFiles = jupyterContainer.execInContainer("find", "/usr/share/jupyter/kernels", "-name", "kernel.json")
//        def jsonFiles = kernelJsonFiles.stdout.trim().split('\n')
//        for (String jsonFile : jsonFiles) {
//            if (jsonFile.trim()) {
//                def jsonContent = jupyterContainer.execInContainer("cat", jsonFile.trim())
//                System.err.println("DEBUG: Content of ${jsonFile}:")
//                System.err.println(jsonContent.stdout)
//                System.err.println("-----")
//            }
//        }

        //
        // END - UNCOMMENT THIS SECTION TO DEBUG CONTAINER AND NETWORK STATES
        //

        // Start socat port forwarder for HTTP port 8080 only using the actual IP
        // ZMQ ports will be forwarded dynamically by kernel.sh
        def socatResult = jupyterContainer.execInContainer("/bin/sh", "-c", "nohup socat TCP-LISTEN:8080,bind=127.0.0.1,reuseaddr,fork TCP:${currentMicronautIp}:8080 </dev/null >/dev/null 2>&1 & echo \$!")
        System.err.println("DEBUG: socat HTTP (8080) start result: exitCode=" + socatResult.exitCode + " stdout=" + socatResult.stdout.trim() + " stderr=" + socatResult.stderr.trim())
//
//        // Give socat a moment to start
        Thread.sleep(2000)
        //
        //
        // End

//        def localTest = jupyterContainer.execInContainer("curl", "-f", "--connect-timeout", "5", "--max-time", "10", "http://localhost:8080/health")
//        System.err.println("DEBUG: Localhost connection test: exitCode=" + localTest.exitCode + " stdout=" + localTest.stdout + " stderr=" + localTest.stderr)

        // Test health endpoint specifically with verbose output
//        def healthTest = jupyterContainer.execInContainer("curl", "-v", "--connect-timeout", "5", "--max-time", "10", "http://micronaut-server:8080/health")
//        System.err.println("DEBUG: Health endpoint test: exitCode=" + healthTest.exitCode + " stdout=" + healthTest.stdout + " stderr=" + healthTest.stderr)
//
//        if (healthTest.exitCode != 0) {
//            throw new Exception("Health check failed, can't connect to micronaut-server from jupyter server")
//        }

        def nbConvVer = jupyterContainer.execInContainer("/bin/sh", "-c", "jupyter execute --version")
        System.err.println("DEBUG: nbclient version: " + nbConvVer.stdout.trim())

        // IN FOREGROUND
        def nbclientCmd = "jupyter nbconvert --debug --to notebook --output ${notebookName}.nbclient --output-dir=/notebooks --ExecutePreprocessor.timeout=30000 --allow-errors --execute /notebooks/${notebookName}.ipynb"
        
        // Build environment variables string
        def envVarString = ""
        if (envVars && !envVars.isEmpty()) {
            envVarString = envVars.collect { k, v -> "${k}=${v}" }.join(" ") + " "
        }
        
        def process = jupyterContainer.execInContainer("/bin/sh", "-c", "${envVarString}${nbclientCmd} </dev/null >/tmp/nbclient.log 2>&1")
        // End - IN FOREGROUND

//        def nbclientCmd = "papermill /notebooks/${notebookName}.ipynb"
//        def process = jupyterContainer.execInContainer("/bin/sh", "-c", "${nbclientCmd} </dev/null >/tmp/papermill.log 2>&1")
//        System.err.println("DEBUG: Started nbclient process: " + process.stdout.trim())

//        // IN BACKGROUND
//        def nbclientCmd = "jupyter execute --ExecutePreprocessor.timeout=30000 --debug --to notebook --output /notebooks/${notebookName}.nbclient.ipynb --execute /notebooks/${notebookName}.ipynb"
//        def bgProcess = jupyterContainer.execInContainer("/bin/sh", "-c", "nohup ${nbclientCmd} </dev/null >/tmp/nbclient.log 2>&1 & echo \$!")
//        System.err.println("DEBUG: Started nbclient process with PID: " + bgProcess.stdout.trim())
//
//        // Wait a bit for the kernel.sh script to make the /jupyterkernel/start request
//        Thread.sleep(1000)
//        // End - IN BACKGROUND

        // Check container states before attempting netstat
//        System.err.println("DEBUG: Container states after kernel start attempt:")
//        System.err.println("DEBUG: Micronaut container running: " + micronautContainer.isRunning())
//        System.err.println("DEBUG: Jupyter container running: " + jupyterContainer.isRunning())

        // Only check ports if Micronaut container is still running
        if (micronautContainer.isRunning()) {
//            def netstatAfterKernel = micronautContainer.execInContainer("/bin/sh", "-c", "netstat -ln")
//            System.err.println("DEBUG: All network ports after kernel start request:")
//            System.err.println(netstatAfterKernel.stdout)
        } else {
            System.err.println("ERROR: Micronaut container has stopped - cannot check ports")
            // Get final logs before it died
        }
        
        // Check nbclient logs from the asynchronous call
//        def nbclientLogs = jupyterContainer.execInContainer("cat", "/tmp/nbclient.log")
//        System.err.println("DEBUG: nbclient logs from asynchronous execution:")
//        System.err.println(nbclientLogs.stdout)
//        System.err.println("DEBUG: nbclient stderr from asynchronous execution:")
//        System.err.println(nbclientLogs.stderr)

        // Check final nbclient logs again after waiting
        def finalNbclientLogs = jupyterContainer.execInContainer("cat", "/tmp/nbclient.log")
        System.err.println("DEBUG: Final nbclient logs after waiting:")
        System.err.println(finalNbclientLogs.stdout)
        
        // List all files in notebooks directory to see what was created
//        def notebookFiles = jupyterContainer.execInContainer("ls", "-la", "/notebooks/")
//        System.err.println("DEBUG: All files in /notebooks/ directory:")
//        System.err.println(notebookFiles.stdout)
        
        // Check for any .ipynb files with different names
//        def ipynbFiles = jupyterContainer.execInContainer("/bin/sh", "-c", "find /notebooks/ -name '*.ipynb' -type f")
//        System.err.println("DEBUG: All .ipynb files found:")
//        System.err.println(ipynbFiles.stdout)
        
        // Try to get output from the expected file first, then try other possible names
        result.outResult = jupyterContainer.execInContainer("cat", "/notebooks/${notebookName}.nbclient.ipynb")
        if (result.outResult.exitCode != 0) {
            // Try without the .nbclient suffix
            def altResult = jupyterContainer.execInContainer("cat", "/notebooks/${notebookName}.ipynb")
            if (altResult.exitCode == 0) {
                result.outResult = altResult
                System.err.println("DEBUG: Found output in original file without .nbclient suffix")
            }
        }
        
        if (result.outResult.exitCode == 0) {
            result.outJson = new JsonSlurper().parseText(result.outResult.stdout) as Map
        }

        String finalLogs = micronautContainer.getLogs()
        System.err.println("DEBUG: Final Micronaut container logs:")
        //System.err.println(finalLogs.split('\n').takeRight(30).join('\n'))
        System.err.println(finalLogs)

        // Set execResult based on whether we got valid output
        result.execResult = new ExecResult(result.outResult.exitCode, result.outResult.stdout, result.outResult.stderr)
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