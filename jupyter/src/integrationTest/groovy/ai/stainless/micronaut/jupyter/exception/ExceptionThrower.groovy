package ai.stainless.micronaut.jupyter.exception

import groovy.util.logging.Slf4j

@Slf4j
class ExceptionThrower {

    def throwRuntimeException() {
        log.info("About to throw a RuntimeException")
        throw new RuntimeException("This is a test uncaught exception from ExceptionThrower")
    }

    def throwNullPointerException() {
        log.info("About to throw a NullPointerException")
        String nullString = null
        return nullString.length() // This will throw NPE
    }

    def throwCustomException() {
        log.info("About to throw a custom exception")
        throw new CustomTestException("This is a custom test exception")
    }
}

class CustomTestException extends Exception {
    CustomTestException(String message) {
        super(message)
    }
}