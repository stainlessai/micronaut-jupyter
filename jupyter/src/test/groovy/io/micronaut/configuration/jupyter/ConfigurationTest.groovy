package io.micronaut.configuration.jupyter

import io.micronaut.context.ApplicationContext
import io.micronaut.context.env.Environment
import spock.lang.Specification

class ConfigurationTest extends Specification {

    def defaultKernelDirectory = "micronaut"
    def defaultKernels = "/usr/local/share/jupyter/kernels"
    def kernelJsonName = "kernel.json"
    def defaultKernelLocation = "$defaultKernels/$defaultKernelDirectory/$kernelJsonName"
    def serverUrl = "http://localhost:8080"

    def "can be disabled"() {
        given:
        // create application context
        ApplicationContext applicationContext = ApplicationContext.run([
            'jupyter.enabled': false
        ] as Map, Environment.TEST)

        expect:
        // beans should NOT have been created
        !applicationContext.containsBean(InstallKernel)
        !applicationContext.containsBean(KernelEndpoint)
        !applicationContext.containsBean(KernelManager)

        cleanup:
        applicationContext.close()
    }

}
