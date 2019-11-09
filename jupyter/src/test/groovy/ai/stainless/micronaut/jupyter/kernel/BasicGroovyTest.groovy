package ai.stainless.micronaut.jupyter.kernel

import com.github.dockerjava.api.model.AccessMode
import com.github.dockerjava.api.model.Bind
import com.github.dockerjava.api.model.Capability
import com.github.dockerjava.api.model.HostConfig
import com.github.dockerjava.api.model.Volume
import groovy.json.JsonSlurper
import io.micronaut.test.annotation.MicronautTest
import org.testcontainers.containers.Container
import org.testcontainers.containers.GenericContainer
import org.testcontainers.images.builder.ImageFromDockerfile
import org.testcontainers.spock.Testcontainers
import spock.lang.Specification

@Testcontainers
@MicronautTest
class BasicGroovyTest extends Specification {

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

    def "executes a Groovy notebook" () {

        when:
        // execute notebook
        Container.ExecResult execResult = jupyterContainer.execInContainer(
            "/bin/sh",
            "-c",
            "umask 0000 && jupyter nbconvert --to notebook --execute /notebooks/println.ipynb"
        )
        // get output
        Container.ExecResult outResult = jupyterContainer.execInContainer("cat", "/notebooks/println.nbconvert.ipynb")
        Map outJson
        if (outResult.exitCode == 0) {
            outJson = new JsonSlurper().parseText(outResult.stdout) as Map
        }

        then:
        // commands should have executed successfully
        execResult.exitCode == 0
        outResult.exitCode == 0
        outJson != null
        // test stdout of cell
        outJson.cells?.get(0)?.outputs?.find { it.name == "stdout" }?.text == [
            "1\n",
            "2\n",
            "3\n",
            "4\n"
        ]
        // test return value of cell
        outJson.cells?.get(0)?.outputs?.find { it.output_type == "execute_result" }?.data?.get("text/plain") == [
            "[1, 2, 3, 4]"
        ]

    }

}
