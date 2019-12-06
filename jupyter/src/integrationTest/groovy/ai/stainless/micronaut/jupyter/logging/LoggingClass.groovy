package ai.stainless.micronaut.jupyter.logging

import groovy.util.logging.Slf4j

@Slf4j
class LoggingClass {

    def loggingMethod () {
        log.info ('Info line')
        log.warn ('Warning line')
        log.error ('Error line')
    }

}
