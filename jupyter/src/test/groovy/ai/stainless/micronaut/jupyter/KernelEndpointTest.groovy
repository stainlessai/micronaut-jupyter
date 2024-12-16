
package ai.stainless.micronaut.jupyter

import io.micronaut.context.ApplicationContext
import io.micronaut.core.type.Argument
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.runtime.server.EmbeddedServer
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import io.micronaut.rxjava2.http.client.RxHttpClient
import io.micronaut.test.annotation.MockBean
import spock.lang.Specification

import jakarta.inject.Inject

@MicronautTest
class KernelEndpointTest extends Specification {

    @Inject
    ApplicationContext applicationContext

    @Inject
    EmbeddedServer embeddedServer

    @Inject
    KernelEndpoint kernelEndpoint

    @Inject
    KernelManager kernelManager

    @MockBean(KernelManager)
    KernelManager kernelManager() {
        Mock(KernelManager)
    }

    def "bean exists"() {
        expect:
        //bean should have been created
        applicationContext.containsBean(KernelEndpoint)
    }

    def "starts kernel on request"() {
        given:
        //create http client
        URL server = embeddedServer.getURL()
        RxHttpClient rxClient = embeddedServer.applicationContext.createBean(RxHttpClient, server)
        // set connection file value
        String connectionFile = "/path/to/file/test"

        when:
        HttpResponse<Map> response = rxClient
            .toBlocking()
            .exchange(
            HttpRequest.POST(
                "/jupyterkernel",
                [
                    file: connectionFile
                ]
            ),
            Argument.of(Map)
        )
        Map result = response.body()

        then:
        // check request
        response.status() == HttpStatus.OK
        result.containsKey('message')
        result.message.contains("start")
        result.message.contains("received")
        // check that kernel manager was called
        1 * kernelManager.startNewKernel(connectionFile)
    }
}
