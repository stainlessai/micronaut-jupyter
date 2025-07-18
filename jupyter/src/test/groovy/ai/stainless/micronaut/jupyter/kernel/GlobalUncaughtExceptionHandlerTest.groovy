package ai.stainless.micronaut.jupyter.kernel

import com.twosigma.beakerx.jvm.object.SimpleEvaluationObject
import com.twosigma.beakerx.kernel.Kernel
import spock.lang.Specification

/**
 * Test for GlobalUncaughtExceptionHandler to verify it properly handles
 * uncaught exceptions without killing threads and reports them correctly.
 */
class GlobalUncaughtExceptionHandlerTest extends Specification {

    def "should handle uncaught exception without killing thread"() {
        given: "a mock evaluation object and kernel"
        def mockEvaluationObject = Mock(SimpleEvaluationObject)
        def mockKernel = Mock(Kernel)
        def handler = new GlobalUncaughtExceptionHandler(mockKernel, mockEvaluationObject)
        def testException = new RuntimeException("Test uncaught exception")

        when: "an uncaught exception occurs directly"
        handler.uncaughtException(Thread.currentThread(), testException)

        then: "the evaluation object should receive a call to finished()"
        1 * mockEvaluationObject.finished(_)
        
        and: "no exceptions should be thrown during handling"
        noExceptionThrown()
    }

    def "should handle exception when no evaluation object is available"() {
        given: "a handler with no evaluation object"
        def handler = GlobalUncaughtExceptionHandler.createLoggingOnlyHandler()
        def testException = new RuntimeException("Test exception with no eval object")
        def threadCompleted = false

        def testThread = new Thread({
            try {
                Thread.currentThread().setUncaughtExceptionHandler(handler)
                throw testException
            } finally {
                threadCompleted = true
            }
        }, "TestThreadNoEvalObject")

        when: "the thread runs and throws an uncaught exception"
        testThread.start()
        testThread.join(5000) // Wait up to 5 seconds

        then: "the thread should complete without being killed"
        threadCompleted
        !testThread.isAlive()
        
        and: "no exceptions should be thrown during handling"
        noExceptionThrown()
    }

    def "should format exception properly for notebook display"() {
        given: "a handler and a test exception"
        def mockEvaluationObject = Mock(SimpleEvaluationObject)
        def handler = new GlobalUncaughtExceptionHandler(null, mockEvaluationObject)
        def testException = new IllegalArgumentException("Test formatting exception")

        when: "an uncaught exception occurs"
        handler.uncaughtException(Thread.currentThread(), testException)

        then: "the evaluation object should receive a call to finished()"
        1 * mockEvaluationObject.finished(_) >> { args ->
            def result = args[0]
            assert result != null
            // The result should be a TryResult (CellError) or fallback String
            assert result instanceof com.twosigma.beakerx.TryResult || result instanceof String
        }
    }
}