
package io.micronaut.configuration.jupyter

import groovy.json.JsonBuilder
import io.micronaut.context.ApplicationContext
import io.micronaut.test.annotation.MicronautTest
import spock.lang.Specification

import javax.inject.Inject

@MicronautTest
class KernelManagerTest extends Specification {

    @Inject
    ApplicationContext applicationContext

    @Inject
    KernelManager kernelManager

    def serverUrl = "http://localhost:8080"
    def connectionFileLocation = "/tmp/micronaut-jupyter"

    def "bean exists"() {
        expect:
        //bean should have been created
        applicationContext.containsBean(KernelManager)
    }

    def "starts basic Groovy kernel"() {
        given:
        //create tmp directory
        new File(connectionFileLocation).mkdirs()
        // create connection file
        String connectionFile = "$connectionFileLocation/connection_file"
        Map connectionInfo = [
            control_port: 50160,
            shell_port: 57503,
            transport: "tcp",
            signature_scheme: "hmac-sha256",
            stdin_port: 52597,
            hb_port: 42540,
            ip: "127.0.0.1",
            iopub_port: 40885,
            key: "a0436f6c-1916-498b-8eb9-e81ab9368e84"
        ]
        new File(connectionFile).withWriter { writer ->
            writer.write new JsonBuilder(connectionInfo).toString()
        }
        println "Connection file exists: ${new File(connectionFile).exists()}"

        when:
        // create new kernel
        kernelManager.startNewKernel(connectionFile)
        // wait a sec
        sleep(1000)
        //port should be in use
        def s = new Socket(connectionInfo.ip, connectionInfo.hb_port)

        then:
        // we should be unable to create a new socket
        thrown SocketException

        cleanup:
        new File(connectionFile).delete()
    }
}
