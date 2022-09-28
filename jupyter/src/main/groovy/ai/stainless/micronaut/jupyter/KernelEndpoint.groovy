package ai.stainless.micronaut.jupyter

import groovy.util.logging.Slf4j
import io.micronaut.management.endpoint.annotation.Endpoint
import io.micronaut.management.endpoint.annotation.Write

import jakarta.inject.Inject

@Slf4j
@Endpoint(id = 'jupyterkernel', defaultSensitive = false)
public class KernelEndpoint {

//    @Inject
    KernelManager kernelManager = new KernelManager()

    @Write
    public Map start (Map request) {
        log.info ("Received start request: $request")
        // get connection file
        String connectionFile = request.file as String

        // start kernel
        kernelManager.startNewKernel(connectionFile)

        return [
            "message": "Kernel start request received!"
        ]
    }

}
