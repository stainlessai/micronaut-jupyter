package ai.stainless.micronaut.integration.services

import groovy.util.logging.Slf4j
import io.micronaut.http.HttpRequest
import io.micronaut.http.client.HttpClient
import io.reactivex.Single
import jakarta.inject.Inject
import jakarta.inject.Singleton

import java.time.Duration

@Slf4j
@Singleton
class ReactiveHttpService {
    
    @Inject
    HttpClient httpClient
    
    /**
     * Makes a blocking HTTP call - susceptible to ThreadDeath during shutdown
     */
    String makeBlockingCall(String url) {
        log.info("Starting blocking HTTP call to: {}", url)
        try {
            def result = httpClient.retrieve(HttpRequest.GET(url), String.class)
                .single()
                .block() // This is the problematic pattern from the logs
            log.info("Blocking HTTP call completed successfully")
            return result
        } catch (Exception e) {
            log.error("Error in blocking HTTP call: {}", e.class.simpleName, e)
            throw e
        }
    }
    
    /**
     * Makes a non-blocking HTTP call with timeout
     */
    String makeNonBlockingCall(String url, int timeoutSeconds = 30) {
        log.info("Starting non-blocking HTTP call to: {} with timeout: {}s", url, timeoutSeconds)
        try {
            def result = httpClient.retrieve(HttpRequest.GET(url), String.class)
                .single()
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .block()
            log.info("Non-blocking HTTP call completed successfully")
            return result
        } catch (Exception e) {
            log.error("Error in non-blocking HTTP call: {}", e.class.simpleName, e)
            throw e
        }
    }
    
    /**
     * Makes a call with custom error handling
     */
    String makeCallWithErrorHandling(String url, int timeoutSeconds = 30) {
        log.info("Starting HTTP call with error handling to: {}", url)
        try {
            def result = httpClient.retrieve(HttpRequest.GET(url), String.class)
                .single()
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .onErrorReturn { throwable ->
                    log.warn("HTTP call failed, returning default: {}", throwable.message)
                    "Request failed: ${throwable.class.simpleName}"
                }
                .block()
            log.info("HTTP call with error handling completed")
            return result
        } catch (Exception e) {
            log.error("Unexpected error in HTTP call with error handling: {}", e.class.simpleName, e)
            throw e
        }
    }
}