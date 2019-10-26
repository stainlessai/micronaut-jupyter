package io.micronaut.configuration.jupyter

import io.micronaut.management.endpoint.annotation.Endpoint
import io.micronaut.management.endpoint.annotation.Write
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import javax.inject.Inject

@Endpoint(id = 'jupyterkernel', defaultSensitive = false)
public class KernelEndpoint {

    private static final Logger log = LoggerFactory.getLogger(KernelEndpoint.class)

    @Inject
    private KernelManager kernelManager

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
