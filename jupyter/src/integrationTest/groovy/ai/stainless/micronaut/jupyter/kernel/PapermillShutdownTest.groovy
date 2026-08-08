package ai.stainless.micronaut.jupyter.kernel

import com.github.dockerjava.api.model.Capability
import groovy.json.JsonOutput
import groovy.util.logging.Slf4j
import org.slf4j.LoggerFactory
import org.testcontainers.containers.ExecConfig
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Network
import org.testcontainers.images.builder.ImageFromDockerfile
import org.testcontainers.images.builder.Transferable
import org.testcontainers.utility.MountableFile
import spock.lang.Shared
import spock.lang.Specification

import java.nio.file.Paths

/**
 * Regression guard: papermill must exit zero after a successful notebook execution,
 * across repeated runs, under adverse kernel shutdown conditions — BeakerX's
 * killAllThreads() Thread.stop()ing live Netty event-loop threads while the client's
 * asyncio cleanup runs on a CPU-constrained container with asyncio debug mode on.
 *
 * The kernel and papermill run colocated in one container (the run script launches
 * the kernel server in the background), matching how this library is commonly
 * deployed for headless notebook execution.
 *
 * Environment overrides so deployments can run this guard against their own stack:
 * - PAPERMILL_TEST_IMAGE: use a custom image (must contain Java 17 and papermill).
 *   Default: a generic image built from jupyter/base-notebook.
 * - PAPERMILL_TEST_ENGINE: pass --engine to papermill (e.g. a custom engine that
 *   publishes progress to NATS). A NATS server container (alias "nats") and the
 *   NATS_URL/NATS_USER/NATS_PASSWORD/NOTEBOOK_ID env vars are always provided,
 *   with netem latency on the NATS link so publish/disconnect tasks stay pending
 *   into the shutdown window.
 */
@Slf4j
class PapermillShutdownTest extends Specification {

    private static final org.slf4j.Logger testLog = LoggerFactory.getLogger(PapermillShutdownTest.class)

    private static final String CUSTOM_IMAGE = System.getenv("PAPERMILL_TEST_IMAGE")
    private static final String PAPERMILL_ENGINE = System.getenv("PAPERMILL_TEST_ENGINE") ?: ""

    @Shared
    GenericContainer container

    @Shared
    GenericContainer natsContainer

    @Shared
    Network network

    def setupSpec() {
        def currentDir = System.getProperty("user.dir")
        def projectRoot = currentDir.endsWith("/jupyter") ? new File(currentDir).getParent() : currentDir
        def jarPath = Paths.get(projectRoot, "jupyter", "build", "libs", "integration-test-0.1-all.jar").toString()
        def jarFile = new File(jarPath)

        if (!jarFile.exists()) {
            throw new RuntimeException("Required JAR not found: ${jarPath}. Run 'gradle assemble' first.")
        }

        def notebooksDir = Paths.get(projectRoot, "jupyter", "src", "test", "resources", "notebooks").toString()

        network = Network.newNetwork()

        // NATS server for progress-publishing papermill engines: keeps the engine's
        // asyncio machinery live at shutdown time when PAPERMILL_TEST_ENGINE is used
        natsContainer = new GenericContainer("nats:2-alpine")
                .withNetwork(network)
                .withNetworkAliases("nats")
                .withExposedPorts(4222)
        natsContainer.start()

        container = (CUSTOM_IMAGE
                ? new GenericContainer(CUSTOM_IMAGE)
                : new GenericContainer(new ImageFromDockerfile("papermill-shutdown-test", false)
                        .withDockerfileFromBuilder { builder ->
                            builder
                                    .from("jupyter/base-notebook")
                                    .user("root")
                                    .run("apt-get update && apt-get install -y curl procps openjdk-17-jre-headless")
                                    .run("pip install --upgrade papermill nbclient")
                                    .user("jovyan")
                                    .build()
                        }))
                .withNetwork(network)
                .withCopyFileToContainer(MountableFile.forHostPath(jarPath), "/opt/jupyter.jar")
                .withCopyFileToContainer(MountableFile.forHostPath(notebooksDir), "/notebooks/")
                .withEnv("JUPYTER_PATH", "/tmp/jupyter")
                .withEnv("JUPYTER_KERNEL_BIND_HOST", "127.0.0.1")
                .withEnv("NATS_URL", "nats://nats:4222")
                .withEnv("NATS_USER", "test")
                .withEnv("NATS_PASSWORD", "test")
                // Debug mode slows the client's event loop considerably, widening any
                // shutdown race window; deployments have been observed running with it
                // enabled unintentionally
                .withEnv("PYTHONASYNCIODEBUG", "1")
                // Constrain to 1 CPU like a throttled container runtime: the JVM shutdown
                // (killAllThreads) and papermill's cleanup compete for the same core.
                // NET_ADMIN allows tc netem latency on the NATS link below.
                .withCreateContainerCmdModifier { cmd ->
                    cmd.hostConfig.withCpuPeriod(100000L).withCpuQuota(100000L)
                            .withCapAdd(Capability.NET_ADMIN)
                }
                .withCommand("sleep", "3600")

        container.start()

        // Delay external traffic so NATS publish/disconnect tasks stay pending longer
        // (kernel<->papermill ZMQ is on loopback and is unaffected)
        def tcResult = container.execInContainer(ExecConfig.builder()
                .user("root")
                .command(["/bin/sh", "-c",
                          "(which tc || (apt-get update -qq && apt-get install -y -qq iproute2)) " +
                          "&& tc qdisc add dev eth0 root netem delay 75ms && echo NETEM_OK"] as String[])
                .build())
        testLog.info("netem setup: exit={} out={} err={}", tcResult.exitCode, tcResult.stdout, tcResult.stderr)

        // Notebook with many cells and heavy output, so client-side message handling
        // is still draining when shutdown begins
        def cells = []
        // Each client must issue a real request: Netty event loops spawn lazily on
        // first use, and live event-loop threads are what killAllThreads() spends
        // seconds Thread.stop()ing during the client's cleanup window
        cells << [cell_type: 'code', execution_count: null, metadata: [:], outputs: [], source:
                '''import io.micronaut.http.client.HttpClient
import io.micronaut.http.HttpRequest
import java.net.URL
def clients = []
for (int i = 0; i < 8; i++) {
    def c = HttpClient.create(new URL("http://localhost:8080"))
    try { c.toBlocking().exchange(HttpRequest.GET("/health"), String) } catch (Exception e) { println "req ${i}: ${e.message}" }
    clients.add(c)
}
println "Created ${clients.size()} HTTP clients with live Netty event loops"
println "Netty threads: " + Thread.getAllStackTraces().keySet().findAll { it.name.contains("nioEventLoop") }.size()''']
        (1..40).each { c ->
            cells << [cell_type: 'code', execution_count: null, metadata: [:], outputs: [], source:
                    "(1..24).each { println \"cell ${c} item \${it}\" }\nprintln \"cell ${c} done\""]
        }
        cells << [cell_type: 'code', execution_count: null, metadata: [:], outputs: [], source:
                'println "All work complete, active threads: ${Thread.activeCount()}"']
        def notebook = [
                cells         : cells,
                metadata      : [kernelspec   : [display_name: 'Micronaut', language: 'groovy', name: 'micronaut'],
                                 language_info: [name: 'groovy']],
                nbformat      : 4,
                nbformat_minor: 4
        ]
        container.copyFileToContainer(
                Transferable.of(JsonOutput.toJson(notebook)), "/notebooks/papermillShutdown.ipynb")

        // No hand-rolled kernelspec: the server's InstallKernel bean writes
        // kernel.json/kernel.sh itself at startup, pointed at /tmp/jupyter/kernels
        // via -Djupyter.kernel.location since notebook images run as a non-root user
        // and the default location is not writable.
    }

    def cleanupSpec() {
        if (container != null) {
            container.stop()
        }
        if (natsContainer != null) {
            natsContainer.stop()
        }
        if (network != null) {
            network.close()
        }
    }

    /**
     * Runs papermill N times, each against a freshly started kernel server, and
     * returns the list of papermill exit codes.
     */
    private List<Integer> runPapermillLoop(int runs) {
        def script = '''\
#!/bin/bash
# no `set -e`: if papermill fails we must still kill the background JVM,
# otherwise the next run connects to this stale kernel server

# Make sure no kernel server from a previous run is still alive.
# SIGKILL: the kernel installs a SIGTERM handler that only logs, so TERM is ignored.
pkill -9 -f jupyter.jar 2>/dev/null
while curl -sf http://localhost:8080/health >/dev/null 2>&1; do
    sleep 1
done

java -noverify -Djupyter.kernel.location=/tmp/jupyter/kernels \
    -jar /opt/jupyter.jar > /tmp/java-$1.log 2>&1 &
JAVA_PID=$!

MAX_WAIT=60
WAITED=0
while ! curl -sf http://localhost:8080/health >/dev/null 2>&1; do
    sleep 1
    WAITED=$((WAITED + 1))
    if [ $WAITED -ge $MAX_WAIT ]; then
        echo "Kernel server failed to start"
        kill -9 $JAVA_PID 2>/dev/null
        exit 1
    fi
done

ENGINE_ARGS=""
if [ -n "$2" ]; then
    ENGINE_ARGS="--engine $2"
fi
export NOTEBOOK_ID="papermill-shutdown-test-$1"
papermill $ENGINE_ARGS --no-progress-bar \
    /notebooks/papermillShutdown.ipynb /tmp/output-$1.ipynb
PAPERMILL_EXIT=$?
echo "PAPERMILL_EXIT_CODE=${PAPERMILL_EXIT}"

# Kernel-side shutdown evidence (ThreadDeath, socket close timing)
echo "=== JAVA LOG (shutdown window) ==="
grep -aE "ThreadDeath|Killing kernel|killAllThreads|shutdown|Closing" /tmp/java-$1.log | tail -30

kill -9 $JAVA_PID 2>/dev/null
wait $JAVA_PID 2>/dev/null
exit $PAPERMILL_EXIT
'''
        container.execInContainer("/bin/sh", "-c", "cat > /tmp/run-papermill-n.sh << 'SCRIPT'\n${script}SCRIPT")
        container.execInContainer("chmod", "+x", "/tmp/run-papermill-n.sh")

        def exitCodes = []
        for (int i = 0; i < runs; i++) {
            def result = container.execInContainer("/bin/bash", "/tmp/run-papermill-n.sh", "${i}", PAPERMILL_ENGINE)
            def output = result.stdout + "\n" + result.stderr

            def exitMatch = output =~ /PAPERMILL_EXIT_CODE=(\d+)/
            def exitCode = exitMatch ? exitMatch[0][1].toInteger() : result.exitCode
            exitCodes.add(exitCode)
            testLog.info("Run {}: exit code = {}", i, exitCode)

            if (exitCode != 0) {
                testLog.info("Run {} FAILED, output:\n{}", i, output)
            } else if (i == 0) {
                // Log the first run so shutdown behavior can be inspected even on success
                testLog.info("Run 0 output:\n{}", output)
            }
        }
        testLog.info("Exit codes: {}", exitCodes)
        return exitCodes
    }

    def "papermill exits zero across repeated runs under adverse shutdown conditions"() {
        when: "Run papermill 10 times, each with a fresh kernel server"
        def exitCodes = runPapermillLoop(10)

        then: "All runs exit 0"
        exitCodes.every { it == 0 }
    }
}
