package ai.stainless.micronaut.jupyter.kernel;

/*
 * Customized GroovyCodeRunner implementation that is public.
 * Uses implementation of GroovyCodeRunner in BeakerX project.
 * License from BeakerX pasted below.
 */

/*
 *  Copyright 2017 TWO SIGMA OPEN SOURCE, LLC
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

import ai.stainless.micronaut.jupyter.DefaultPackageCompilerConfiguration;
import com.twosigma.beakerx.TryResult;
import com.twosigma.beakerx.evaluator.Evaluator;
import com.twosigma.beakerx.jvm.object.SimpleEvaluationObject;
import com.twosigma.beakerx.jvm.threads.BxInputStream;
import com.twosigma.beakerx.jvm.threads.InputRequestMessageFactoryImpl;
import groovy.lang.GroovyClassLoader;
import groovy.lang.Script;
import org.codehaus.groovy.runtime.StackTraceUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.Callable;

import static com.twosigma.beakerx.evaluator.BaseEvaluator.INTERUPTED_MSG;
import static com.twosigma.beakerx.groovy.evaluator.GroovyStackTracePrettyPrinter.printStacktrace;

public class MicronautCodeRunner implements Callable<TryResult> {

    public static final Logger logger = LoggerFactory.getLogger(MicronautCodeRunner.class);

    public static final String SCRIPT_NAME = "script";
    private final MicronautEvaluator evaluator;
    private final String theCode;
    private final SimpleEvaluationObject theOutput;
    private final EnsureScriptPackage ensureScriptPackage = new EnsureScriptPackage();

    public MicronautCodeRunner(MicronautEvaluator groovyEvaluator, String code, SimpleEvaluationObject out) {
        this.evaluator = groovyEvaluator;
        theCode = code;
        theOutput = out;
    }

    @Override
    public TryResult call() {
        logger.trace("call()");
        ClassLoader oldld = Thread.currentThread().getContextClassLoader();
        Thread.UncaughtExceptionHandler originalHandler = Thread.currentThread().getUncaughtExceptionHandler();
        TryResult either;
        String scriptName = SCRIPT_NAME;
        
        // Set up uncaught exception handler for this execution (unless disabled via env var)
        String disableHandler = System.getProperty("DISABLE_GLOBAL_EXCEPTION_HANDLER");
        if (!"true".equalsIgnoreCase(disableHandler) && !"1".equals(disableHandler)) {
            Thread.currentThread().setUncaughtExceptionHandler(
                new GlobalUncaughtExceptionHandler(evaluator.getKernel(), theOutput)
            );
        }
        
        try {
            theOutput.setOutputHandler();

            // Create stdin handler for input
            BxInputStream stdInHandler = new BxInputStream(evaluator.getKernel(), new InputRequestMessageFactoryImpl());

            // Check if kernel and streamHandler are available
            if (evaluator.getKernel() != null && evaluator.getKernel().getStreamHandler() != null) {
                // Set output handlers for this call
                evaluator.getKernel().getStreamHandler().setOutputHandlers(
                        theOutput.getStdOutputHandler(),
                        theOutput.getStdErrorHandler(),
                        stdInHandler
                );

                logger.trace("stdInHandler={}", stdInHandler);
                logger.trace("stdOutputHandler={}", theOutput.getStdOutputHandler());
                logger.trace("stdErrorHandler={}", theOutput.getStdErrorHandler());
            } else {
                logger.warn("Unable to set output handlers - kernel or streamHandler is null");
            }

            Object result = null;
            Thread.currentThread().setContextClassLoader(evaluator.getGroovyClassLoader());
            scriptName += System.currentTimeMillis();

            // Class<?> parsedClass = evaluator.getGroovyClassLoader().parseClass(theCode, scriptName);

            // workaround for BUG! packageName is null thrown in Groovy 4.0.28 when no
            // package name is defined on a class
            Class<?> parsedClass = evaluator.getGroovyClassLoader().parseClass(
                    ensureScriptPackage.ensurePackageMicronautJupyter(theCode),
                    scriptName);

            if (canBeInstantiated(parsedClass)) {
                Object instance = parsedClass.getDeclaredConstructor().newInstance();
                if (instance instanceof Script) {
                    result = runScript((Script) instance);
                }
            }
            either = TryResult.createResult(result);
        } catch (Throwable e) {
            either = handleError(scriptName, e);
        } finally {
            theOutput.clrOutputHandler();

            // Safe cleanup of output handlers
            try {
                if (evaluator.getKernel() != null && evaluator.getKernel().getStreamHandler() != null) {
                    evaluator.getKernel().getStreamHandler().clearOutputHandlers();
                }
            } catch (Exception e) {
                logger.warn("Error clearing output handlers", e);
            }

            // Restore original uncaught exception handler
            Thread.currentThread().setUncaughtExceptionHandler(originalHandler);
            Thread.currentThread().setContextClassLoader(oldld);
        }
        return either;
    }

    private TryResult handleError(String scriptName, Throwable e) {
        TryResult either;
        if (e instanceof InvocationTargetException) {
            e = ((InvocationTargetException) e).getTargetException();
        }

        if (e instanceof InterruptedException || e instanceof InvocationTargetException || e instanceof ThreadDeath) {
            either = TryResult.createError(INTERUPTED_MSG);
        } else {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            StackTraceUtils.sanitize(e).printStackTrace(pw);
            String value = sw.toString();
            value = printStacktrace(scriptName, value);
            either = TryResult.createError(value);
        }
        return either;
    }

    private Object runScript(Script script) {
        logger.trace("runScript {}", script);
        evaluator.getScriptBinding().setVariable(Evaluator.BEAKER_VARIABLE_NAME, evaluator.getBeakerX());
        script.setBinding(evaluator.getScriptBinding());
        return script.run();
    }

    private boolean canBeInstantiated(Class<?> parsedClass) {
        return !parsedClass.isEnum();
    }
}