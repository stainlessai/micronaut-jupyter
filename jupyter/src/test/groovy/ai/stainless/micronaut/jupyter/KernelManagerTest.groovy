
package ai.stainless.micronaut.jupyter

import ai.stainless.micronaut.jupyter.kernel.UnexpectedExitException
import ai.stainless.micronaut.jupyter.kernel.KernelExitException
import groovy.json.JsonSlurper
import io.micronaut.context.ApplicationContext
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import spock.lang.AutoCleanup
import spock.lang.Specification
import spock.util.concurrent.PollingConditions

import jakarta.inject.Inject
import org.slf4j.Logger

@MicronautTest(packages = "ai.stainless.micronaut.jupyter")
class KernelManagerTest extends Specification {

    @Inject
    @AutoCleanup
    ApplicationContext applicationContext

    @Inject
    KernelManager kernelManager

    //create conditions
    PollingConditions conditions = new PollingConditions(timeout: 10, initialDelay: 1)

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

    private Boolean portAvailable(String ip, Integer port) {
        ServerSocket s
        try {
            s = new ServerSocket(
                port,
                5,
                InetAddress.getByName(ip)
            )
        }
        catch (SocketException e) {
            return false
        }
        finally {
            if (s) s.close()
        }
        return true
    }

    def "bean exists"() {
        expect:
        //bean should have been created
        applicationContext != null
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

        then:
        conditions.eventually {
            //port should be in use
            !portAvailable(connectionInfo.ip as String, connectionInfo.control_port as Integer)
            // we should have an instance of the kernel
            kernelManager.kernelInstances.size() == 1
        }

        when:
        // kill the kernel
        kernelManager.killAllKernels()
        // wait for the kernel to finish
        kernelManager.waitForAllKernels(10000)

        then:
        portAvailable(connectionInfo.ip as String, connectionInfo.control_port as Integer)
        // the kernel should have finished
        kernelManager.kernelInstances.size() == 0
        kernelManager.kernelThreads.size() == 0
    }

    def "handles a system exit from within kernel"() {
        given:
        // set custom kernel
        kernelManager.kernelClass = Exits
        // create custom logger
        kernelManager.log = Mock(Logger) {
            1 * warn("Kernel exited unexpectedly.", _ as UnexpectedExitException)
        }

        when:
        // create new kernel (exit 1)
        kernelManager.startNewKernel("1")

        then:
        conditions.eventually {
            kernelManager.kernelInstances.size() == 0
            kernelManager.kernelThreads.size() == 0
        }
    }

    def "handles thread restart/kill"() {
        given:
        // set custom kernel
        kernelManager.kernelClass = Interrupts
        // create custom logger
        kernelManager.log = Mock(Logger) {
            0 * warn("Kernel exited unexpectedly.", _)
        }

        when:
        // create new kernel (exit 1)
        kernelManager.startNewKernel("")

        then:
        conditions.eventually {
            kernelManager.kernelInstances.size() == 0
            kernelManager.kernelThreads.size() == 0
        }
    }
}
