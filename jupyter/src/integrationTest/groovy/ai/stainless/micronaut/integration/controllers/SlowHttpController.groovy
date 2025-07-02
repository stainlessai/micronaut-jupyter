package ai.stainless.micronaut.integration.controllers

import groovy.util.logging.Slf4j
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.Post
import io.micronaut.http.annotation.Body
import io.micronaut.http.MediaType
import io.micronaut.http.annotation.Produces
import jakarta.inject.Singleton
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

@Slf4j
@Controller
@Singleton
class SlowHttpController {
    
    private final AtomicInteger responseDelayMs = new AtomicInteger(0)
    private final AtomicBoolean neverRespond = new AtomicBoolean(false)
    
    @Get("/slow")
    @Produces(MediaType.APPLICATION_JSON)
    String slow() throws InterruptedException {
        log.info("Received request to /slow endpoint")
        
        if (neverRespond.get()) {
            log.debug("Configured to never respond - sleeping indefinitely")
            Thread.sleep(60000) // Hold for 1 minute
            return null
        }
        
        int delay = responseDelayMs.get()
        if (delay > 0) {
            log.debug("Applying response delay of {}ms", delay)
            Thread.sleep(delay)
        }
        
        String response = """{"message": "Hello from SlowHttpController", "delay": ${delay}, "timestamp": ${System.currentTimeMillis()}}"""
        log.debug("Sending response after {}ms delay", delay)
        return response
    }
    
    @Post("/slow/config/delay")
    @Produces(MediaType.APPLICATION_JSON)
    String setDelay(@Body Map<String, Integer> config) {
        int delayMs = config.delay ?: 0
        responseDelayMs.set(delayMs)
        log.info("Set response delay to {}ms", delayMs)
        return """{"message": "Delay set to ${delayMs}ms"}"""
    }
    
    @Post("/slow/config/never-respond")
    @Produces(MediaType.APPLICATION_JSON)
    String setNeverRespond(@Body Map<String, Boolean> config) {
        boolean neverRespond = config.neverRespond ?: false
        this.neverRespond.set(neverRespond)
        log.info("Set never respond to {}", neverRespond)
        return """{"message": "Never respond set to ${neverRespond}"}"""
    }
}