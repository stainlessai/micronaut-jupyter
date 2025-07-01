package ai.stainless.micronaut.integration

import groovy.util.logging.Slf4j
import io.micronaut.runtime.Micronaut
import groovy.transform.CompileStatic

@Slf4j
@CompileStatic
class Application {

    static void main(String[] args) {
        log.info("Starting Integration Test Micronaut Application")
        Micronaut.run(Application, args)
    }
}