
package ai.stainless.micronaut.jupyter

import ai.stainless.micronaut.jupyter.kernel.UnexpectedExitException
import groovy.json.JsonBuilder
import ai.stainless.micronaut.jupyter.kernel.KernelExitException
import groovy.json.JsonParser
import groovy.json.JsonSlurper
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
        // create connection file
        String connectionFile = this.class.classLoader.getResource("micronaut_kernel_connection.json").getFile()
        println "Connection file location: $connectionFile"
        Map connectionInfo = new JsonSlurper().parse(new File(connectionFile)) as Map
        ServerSocket s = null

        when:
        // create new kernel
        kernelManager.startNewKernel(connectionFile)
        // wait a sec
        sleep(5000)
        //port should be in use
        s = new ServerSocket(
            connectionInfo.control_port as Integer,
            5,
            InetAddress.getByName(connectionInfo.ip as String)
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
            connectionInfo.control_port as Integer,
            5,
            InetAddress.getByName(connectionInfo.ip as String)
        )

        then:
        noExceptionThrown()
        // the kernel should have finished
        kernelManager.kernelInstances.size() == 0
        kernelManager.kernelThreads.size() == 0

        cleanup:
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
