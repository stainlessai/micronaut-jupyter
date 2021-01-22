package ai.stainless.micronaut.jupyter.kernel

import com.twosigma.beakerx.TryResult
import com.twosigma.beakerx.jvm.object.SimpleEvaluationObject
import com.twosigma.beakerx.jvm.threads.BxInputStream
import com.twosigma.beakerx.jvm.threads.InputRequestMessageFactoryImpl

class MicronautCodeRunner extends PublicGroovyCodeRunner {

    MicronautEvaluator evaluator
    SimpleEvaluationObject seo

    MicronautCodeRunner(MicronautEvaluator evaluator, String code, SimpleEvaluationObject seo) {
        super(evaluator, code, seo)
        this.evaluator = evaluator
        this.seo = seo
    }

    @Override
    public TryResult call() {

        try {
            // create stdin (the one in the seo is private, but unused)
            BxInputStream stdInHandler = new BxInputStream(evaluator.kernel, new InputRequestMessageFactoryImpl())
            // set output handlers for this calll
            evaluator.kernel.streamHandler.setOutputHandlers(
                seo.stdOutputHandler,
                seo.stdErrorHandler,
                stdInHandler
            )
            // call parent call method
            return super.call()
        } finally {
            // after execution, clear the handlers
            evaluator.kernel.streamHandler.clearOutputHandlers()
        }

    }
}
