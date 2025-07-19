package ai.stainless.micronaut.jupyter

import groovy.util.logging.Slf4j
import io.micronaut.http.annotation.Body
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.PathVariable
import io.micronaut.http.annotation.Post
import io.micronaut.security.rules.SecurityRule
import io.micronaut.security.annotation.Secured
import jakarta.inject.Inject

@Slf4j
@Secured(SecurityRule.IS_ANONYMOUS)
@Controller("/jupyterkernel")
public class KernelEndpoint {

    @Inject
    KernelManager kernelManager

    @Post("/start")
    public Map start(@Body StartRequest request) {
        log.info("Received connection file: ${request.file}")

        if (this.kernelManager == null) {
            throw new IllegalStateException("KernelManager was not injected")
        }

        kernelManager.startNewKernel(request.file)

        return [
                "message": "Kernel start request received!"
        ]
    }

    /**
     * Custom restart endpoint for testing purposes only.
     * 
     * NOTE: This is NOT how Jupyter normally handles kernel restarts.
     * Standard Jupyter restart requests come through ZMQ protocol messages,
     * but this implementation does not handle ZMQ restart messages properly.
     * 
     * This HTTP endpoint kills ALL active kernels for ALL users, violating
     * user isolation. It's intended for ThreadDeath propagation testing only.
     * 
     * In a proper implementation, Jupyter restart requests should:
     * 1. Come through ZMQ SHUTDOWN_REQUEST with restart=true flag
     * 2. Only affect the specific requesting kernel
     * 3. Maintain isolation between different users' kernels
     */
    @Post("/restart")
    public Map restart() {
        log.info("Received kernel restart request")
        
        if (this.kernelManager == null) {
            throw new IllegalStateException("KernelManager was not injected")
        }
        
        kernelManager.restartKernel("all-kernels")
        
        return [
            "message": "Kernel restart request received!"
        ]
    }


    static class StartRequest {
        String file
    }
}