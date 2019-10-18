
package io.micronaut.configuration.jupyter

import groovy.json.JsonSlurper
import io.micronaut.context.ApplicationContext
import io.micronaut.context.env.Environment
import io.micronaut.test.annotation.MicronautTest
import spock.lang.Specification

class InstallKernelTest extends Specification {

    def defaultKernelDirectory = "micronaut"
    def defaultKernels = "/usr/local/share/jupyter/kernels"
    def kernelJsonName = "kernel.json"
    def defaultKernelLocation = "$defaultKernels/$defaultKernelDirectory/$kernelJsonName"
    def serverUrl = "http://localhost:8080"

    def "bean is created"() {
        given:
        // create application context
        ApplicationContext applicationContext = ApplicationContext.run([
            'jupyter.server-url': serverUrl
        ] as Map, Environment.TEST)

        expect:
        //bean should have been created
        applicationContext.containsBean(InstallKernel)
        
        cleanup:
        applicationContext.close()
    }

    def "installs kernel with default config on context creation"() {
        given:
        // delete possibly existing kernel
        File existing = new File(defaultKernelLocation)
        if (existing.exists()) {
            existing.delete()
        }
        // create application context
        ApplicationContext applicationContext = ApplicationContext.run([
            'jupyter.server-url': serverUrl
        ], Environment.TEST)

        when:
        def kernel = new File(defaultKernelLocation)

        then:
        // default kernel should have been created
        kernel.exists()

        when:
        def kernelJson = new JsonSlurper().parse(kernel)

        then:
        kernelJson.display_name == "Micronaut"
        kernelJson.argv.contains("$serverUrl/jupyter/kernel" as String)
        
        cleanup:
        applicationContext.close()
    }

    def "installs kernel at configured location"() {
        given:
        def customPath = "/tmp/test-location/jupyter-kernels"
        def customLocation = "$customPath/$defaultKernelDirectory/kernel.json"
        // create application context
        ApplicationContext applicationContext = ApplicationContext.run([
            'jupyter.kernel.location': customPath,
            'jupyter.server-url': serverUrl
        ], Environment.TEST)

        expect:
        // default kernel should have been created
        new File(customLocation).exists()

        cleanup:
        applicationContext.close()
    }

    def "kernel includes custom endpoint url"() {
        given:
        def endpointPath = "command/jupyter/start-kernel"
        def serverUrl = "https://localhost:8080"
        // create application context
        ApplicationContext applicationContext = ApplicationContext.run([
            'jupyter.endpoint.path': endpointPath,
            'jupyter.server-url': serverUrl
        ], Environment.TEST)

        when:
        // parse kernel
        def kernelJson = new JsonSlurper().parse(new File(defaultKernelLocation))

        then:
        kernelJson.argv.contains("$serverUrl/$endpointPath" as String)

        cleanup:
        applicationContext.close()
    }

    def "kernel uses configured name"() {
        given:
        def kernelName = "Micronaut Version_23"
        def kernelDir = "micronaut-version-23"
        def customLocation = "$defaultKernels/$kernelDir/$kernelJsonName"
        // create application context
        ApplicationContext applicationContext = ApplicationContext.run([
            'jupyter.kernel.name': kernelName,
            'jupyter.server-url': serverUrl
        ], Environment.TEST)

        when:
        // parse kernel
        def kernelJson = new JsonSlurper().parse(new File(customLocation))

        then:
        kernelJson.display_name == kernelName

        cleanup:
        applicationContext.close()
    }
}
