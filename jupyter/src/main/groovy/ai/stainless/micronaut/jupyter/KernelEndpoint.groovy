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
     * 
     * @deprecated Use per-kernel restart via ZMQ protocol instead
     */
    @Post("/restart")
    public Map restart() {
        log.warn("DEPRECATED: Global restart endpoint called - this affects ALL users!")
        log.warn("This violates user isolation and should only be used for testing ThreadDeath propagation")
        
        if (this.kernelManager == null) {
            throw new IllegalStateException("KernelManager was not injected")
        }
        
        // Use the old global killAllKernels for backward compatibility in tests
        kernelManager.killAllKernels()
        
        return [
            "message": "DEPRECATED: Global kernel restart completed - affected ALL users!",
            "warning": "This endpoint violates user isolation and will be removed"
        ]
    }
    
    /**
     * Restart a specific kernel by ID with proper isolation
     * This is the proper way to restart kernels without affecting other users
     */
    @Post("/restart/{kernelId}")
    public Map restartKernel(@PathVariable String kernelId) {
        log.info("Received isolated restart request for kernel: {}", kernelId)
        
        if (this.kernelManager == null) {
            throw new IllegalStateException("KernelManager was not injected")
        }
        
        if (kernelId == null || kernelId.trim().isEmpty()) {
            return [
                "status": "error",
                "message": "Kernel ID cannot be null or empty"
            ]
        }
        
        try {
            kernelManager.restartKernel(kernelId)
            return [
                "status": "ok",
                "message": "Kernel '${kernelId}' restart completed",
                "kernelId": kernelId
            ]
        } catch (Exception e) {
            log.error("Error restarting kernel '{}'", kernelId, e)
            return [
                "status": "error", 
                "message": "Failed to restart kernel '${kernelId}': ${e.message}",
                "kernelId": kernelId
            ]
        }
    }


    static class StartRequest {
        String file
    }
}