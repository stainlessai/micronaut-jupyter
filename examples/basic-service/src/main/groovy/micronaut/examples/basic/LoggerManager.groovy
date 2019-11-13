package micronaut.examples.basic

import io.micronaut.context.annotation.Context
import io.micronaut.context.annotation.Prototype
import org.slf4j.ILoggerFactory
import org.slf4j.Logger
import org.slf4j.LoggerFactory

@Context
class LoggerManager {

    public static final Logger defaultLog = LoggerFactory.getLogger(LoggerManager.class)
    public static final ILoggerFactory defaultFactory = LoggerFactory.getILoggerFactory()

    public Logger instanceLog

    public LoggerManager () {
        defaultLog.info ("LoggerManager instantiated.")
        instanceLog = LoggerFactory.getLogger(LoggerManager.class)
        defaultLog.info ("Created instance logger: $instanceLog")
        instanceLog.info ("Logging from instance logger")
        instanceLog.info ("Starting instance poll.")
        pollInstance("defaultpoll01")
    }

    public void pollInstance (String logId) {
        Thread.start {
            while (true) {
                instanceLog.info("Polling instance log with id: $logId")
                sleep(5000)
            }
        }
    }

    public void defaultInfo (String msg) {
        defaultLog.info(msg)
    }

    public void instanceInfo (String msg) {
        instanceLog.info(msg)
    }

    public Logger createLogger (String name) {
        return LoggerFactory.getLogger(name)
    }

    public Logger createDefaultLogger (String name) {
        return defaultFactory.getLogger(name)
    }
    
}
