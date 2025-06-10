package ai.stainless.micronaut.jupyter

import groovy.util.logging.Slf4j
import io.micronaut.http.annotation.Body
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Post
import jakarta.inject.Inject

@Slf4j
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

    static class StartRequest {
        String file
    }
}