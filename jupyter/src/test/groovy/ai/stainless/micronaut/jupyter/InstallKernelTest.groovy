
package ai.stainless.micronaut.jupyter

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import groovy.json.JsonSlurper
import io.micronaut.context.ApplicationContext
import io.micronaut.context.env.Environment
import spock.lang.Specification

import java.nio.file.Files

class InstallKernelTest extends Specification {

    def defaultKernelDirectory = "micronaut"
    def defaultKernels = "/usr/local/share/jupyter/kernels"
    def testKernels = "/tmp/test-location/jupyter/kernels"
    def kernelJsonName = "kernel.json"
    def kernelShName = "kernel.sh"
    def defaultKernelLocation = "$defaultKernels/$defaultKernelDirectory"
    def testKernelLocation = "$testKernels/$defaultKernelDirectory"
    def serverUrl = "http://localhost:9999"

    def "bean is created"() {
        given:
        // create application context
        ApplicationContext applicationContext = ApplicationContext.run(Environment.TEST)

        expect:
        //bean should have been created
        applicationContext.containsBean(InstallKernel)
        
        cleanup:
        applicationContext.close()
    }

    def "default location"() {
        given:
        // create application context with no custom location
        ApplicationContext applicationContext = ApplicationContext.run([
            'jupyter.kernel.install': false,
            'jupyter.kernel.location': null
        ])

        expect:
        //bean should have been created
        applicationContext.getBean(InstallKernel).getKernelLocation() == "/usr/local/share/jupyter/kernels"

        cleanup:
        applicationContext.close()
    }

    def "installs kernel with default config on context creation"() {
        given:
        // delete possibly existing kernel directory
        File existing = new File("$testKernelLocation")
        if (existing.exists()) {
            existing.delete()
        }
        // create application context
        ApplicationContext applicationContext = ApplicationContext.run(Environment.TEST)

        when:
        def kernelConfig = new File("$testKernelLocation/$kernelJsonName")
        def kernelShFile = new File("$testKernelLocation/$kernelShName")

        then:
        // default kernel should have been created
        kernelConfig.exists()
        kernelShFile.exists()
        Files.isExecutable(kernelShFile.toPath())

        when:
        def kernelJson = new JsonSlurper().parse(kernelConfig)
        def kernelCommand = kernelShFile.text

        then:
        kernelJson.display_name == "Micronaut"
        kernelJson.argv == [
            "$testKernelLocation/$kernelShName",
            "{connection_file}"
        ]
        kernelCommand.indexOf("#!/bin/bash") == 0
        kernelCommand.contains("$serverUrl/jupyterkernel" as String)
        kernelCommand[-1] == "\n"
        
        cleanup:
        applicationContext.close()
    }

    def "installs kernel at configured location"() {
        given:
        // create application context
        ApplicationContext applicationContext = ApplicationContext.run([
            'jupyter.kernel.location': testKernels
        ], Environment.TEST)

        when:
        def kernelConfig = new File("$testKernelLocation/$kernelJsonName")
        def kernelShFile = new File("$testKernelLocation/$kernelShName")

        then:
        // kernel should have been created at given location
        kernelConfig.exists()
        kernelShFile.exists()

        when:
        def kernelJson = new JsonSlurper().parse(kernelConfig)

        then:
        kernelJson.argv[0] == "$testKernelLocation/$kernelShName"

        cleanup:
        applicationContext.close()
    }

    /*
     * As far as I can tell, it isn't possible to allow a configurable endpoint url
     * @see https://stackoverflow.com/questions/58459116/micronaut-configuration-placeholder-in-endpoint-id-ignores-custom-value
     *
     * UPDATE: Configurable endpoints do work, more investigation needs to be done as
     * to why this test doesn't work (probably the way I am reading the
     * annotation).
     *
     * /
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
    **/

    def "kernel uses configured name"() {
        given:
        def kernelName = "Micronaut Version_23"
        def kernelDir = "micronaut-version-23"
        def customLocation = "$testKernels/$kernelDir/$kernelJsonName"
        // create application context
        ApplicationContext applicationContext = ApplicationContext.run([
            'jupyter.kernel.location': testKernels,
            'jupyter.kernel.name': kernelName
        ], Environment.TEST)

        when:
        // parse kernel
        def kernelJson = new JsonSlurper().parse(new File(customLocation))

        then:
        kernelJson.display_name == kernelName

        cleanup:
        applicationContext.close()
    }

    /*
     * TODO: Test passing no server-url without an embedded server running,
     * this should throw error with helpful message.
     * If a server-url is given, no error should be thrown even if no embedded
     * server is running.
     * I haven't figured out a way to write a test that runs the app without
     * an embedded server.
     * Currently, this is tested by disabled the http-netty-server dependency
     * in build.gradle and making sure the correct tests fail and pass.
     */

    def "kernel uses configured server-url"() {
        given:
        def testUrl = "http://localhost:4321"
        // create application context
        ApplicationContext applicationContext = ApplicationContext.run([
            'jupyter.server-url': testUrl
        ], Environment.TEST)

        when:
        def kernelShFile = new File("$testKernelLocation/$kernelShName")
        def kernelCommand = kernelShFile.text

        then:
        kernelCommand.contains("$testUrl/jupyterkernel" as String)

        cleanup:
        applicationContext.close()
    }

    def "updates existing kernel"() {
        given:
        def kernelName = "Micronaut Version_23"
        def kernelDir = "micronaut-version-23"
        def customLocation = "$testKernels/$kernelDir/$kernelJsonName"
        // create application context
        ApplicationContext applicationContext = ApplicationContext.run([
            'jupyter.kernel.location': testKernels,
            'jupyter.kernel.name': kernelName
        ], Environment.TEST)
        // create kernel a second time
        applicationContext.getBean(InstallKernel).install()
        //create mapper
        ObjectMapper mapper = new ObjectMapper()
        mapper.configure(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, true)

        when:
        // parse kernel
        mapper.readValue(new File(customLocation), Map)

        then:
        noExceptionThrown()

        cleanup:
        applicationContext.close()
    }
}
