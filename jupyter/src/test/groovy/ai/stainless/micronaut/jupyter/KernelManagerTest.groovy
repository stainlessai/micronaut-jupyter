
package io.micronaut.configuration.jupyter

import groovy.json.JsonBuilder
import io.micronaut.configuration.jupyter.kernel.KernelExitException
import io.micronaut.configuration.jupyter.kernel.UnexpectedExitException
import io.micronaut.context.ApplicationContext
import io.micronaut.test.annotation.MicronautTest
import spock.lang.AutoCleanup
import spock.lang.Specification

import javax.inject.Inject
import org.slf4j.Logger

@MicronautTest
class KernelManagerTest extends Specification {

    @Inject
    @AutoCleanup
    ApplicationContext applicationContext

    @Inject
    KernelManager kernelManager

    def serverUrl = "http://localhost:8080"
    def connectionFileLocation = "/tmp/micronaut-jupyter"

    class Exits {

        static createKernel (String[] args) {
            System.exit(args[0].toInteger())
        }

    }

    class Interrupts {

        static createKernel (String[] args) {
            throw new KernelExitException("Test exit handling.")
        }

    }

    def "bean exists"() {
        expect:
        //bean should have been created
        applicationContext.containsBean(KernelManager)
    }

    def "starts basic Groovy kernel that can be killed"() {
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
        ServerSocket s = null

        when:
        // create new kernel
        kernelManager.startNewKernel(connectionFile)
        // wait a sec
        sleep(5000)
        //port should be in use
        s = new ServerSocket(
            connectionInfo.control_port,
            5,
            InetAddress.getByName(connectionInfo.ip)
        )

        then:
        // we should be unable to create a new socket
        thrown SocketException
        // we should have an instance of the kernel
        kernelManager.kernelInstances.size() == 1

        when:
        // kill the kernel
        kernelManager.killAllKernels()
        // wait for the kernel to finish
        kernelManager.waitForAllKernels(10000)
        //port should be open
        s = new ServerSocket(
            connectionInfo.control_port,
            5,
            InetAddress.getByName(connectionInfo.ip)
        )

        then:
        noExceptionThrown()
        // the kernel should have finished
        kernelManager.kernelInstances.size() == 0
        kernelManager.kernelThreads.size() == 0

        cleanup:
        // remove connection file
        new File(connectionFile).delete()

        // close our test socket
        if (s) {
            s.close()
            println "Closed socket"
        }
    }

    def "handles a system exit from within kernel"() {
        given:
        // set custom kernel
        kernelManager.kernelClass = Exits
        // create custom logger
        kernelManager.log = Mock(Logger)

        when:
        // create new kernel (exit 1)
        kernelManager.startNewKernel("1")
        // wait a sec
        sleep(1000)

        then:
        1 * kernelManager.log.warn("Kernel exited unexpectedly.", _ as UnexpectedExitException)
        kernelManager.kernelInstances.size() == 0
        kernelManager.kernelThreads.size() == 0
    }

    def "handles thread restart/kill"() {
        given:
        // set custom kernel
        kernelManager.kernelClass = Interrupts

        when:
        // create new kernel (exit 1)
        kernelManager.startNewKernel("")
        // wait a sec
        sleep(1000)

        then:
        0 * kernelManager.log.warn("Kernel exited unexpectedly.", _ as UnexpectedExitException)
        kernelManager.kernelInstances.size() == 0
        kernelManager.kernelThreads.size() == 0
    }
}
