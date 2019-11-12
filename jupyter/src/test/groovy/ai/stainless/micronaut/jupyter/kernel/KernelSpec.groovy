package ai.stainless.micronaut.jupyter.kernel

import com.github.dockerjava.api.model.AccessMode
import com.github.dockerjava.api.model.Bind
import com.github.dockerjava.api.model.Capability
import com.github.dockerjava.api.model.HostConfig
import com.github.dockerjava.api.model.Volume
import groovy.json.JsonSlurper
import org.testcontainers.containers.Container.ExecResult
import org.testcontainers.containers.GenericContainer
import org.testcontainers.images.builder.ImageFromDockerfile
import org.testcontainers.spock.Testcontainers
import spock.lang.Specification

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
class KernelSpec extends Specification {

    ImageFromDockerfile jupyterImage = new ImageFromDockerfile("micronaut-jupyter")
        .withFileFromClasspath("Dockerfile", "jupyter.Dockerfile")
        .withFileFromClasspath("notebooks/", "notebooks/")

    GenericContainer jupyterContainer = new GenericContainer(jupyterImage)
        .withCreateContainerCmdModifier { cmd ->
            Volume tmpVolume = new Volume("/tmp")
            cmd
                .withHostConfig(
                    new HostConfig()
                        .withSysctls([
                            "net.ipv4.conf.all.route_localnet": "1"
                        ])
                        .withCapAdd(Capability.NET_ADMIN)
                        .withBinds(new Bind("/tmp", tmpVolume, AccessMode.rw))
                )
                .withVolumes(tmpVolume)
        }
        .withEnv("JUPYTER_PATH", "/tmp/test-location/jupyter")

    def setup () {
        // execute iptables commands,
        // create NAT that forwards all traffic from localhost to our host
        jupyterContainer.execInContainer(
            "/bin/sh",
            "-c",
            "iptables -t nat -A OUTPUT -d 127.0.0.1 -j DNAT --to-destination \$(ip route | awk '/default/ { print \$3 }')"
            //"iptables -t nat -A OUTPUT -d 127.0.0.1 -j DNAT --to-destination \$(ip route | tr '\\n' ' ' | awk '{print \$(NF)}')"
        )
        jupyterContainer.execInContainer(
            "/bin/sh",
            "-c",
            "iptables -t nat -A POSTROUTING -m addrtype --src-type LOCAL --dst-type UNICAST -j MASQUERADE"
        )
    }

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

}
