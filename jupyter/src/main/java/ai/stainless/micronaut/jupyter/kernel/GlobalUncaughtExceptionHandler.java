package ai.stainless.micronaut.jupyter.kernel;

import com.twosigma.beakerx.TryResult;
import com.twosigma.beakerx.jvm.object.SimpleEvaluationObject;
import com.twosigma.beakerx.kernel.Kernel;
import org.codehaus.groovy.runtime.StackTraceUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.Thread.UncaughtExceptionHandler;

import static com.twosigma.beakerx.groovy.evaluator.GroovyStackTracePrettyPrinter.printStacktrace;

/**
 * Global uncaught exception handler for Jupyter kernel threads.
 * This handler catches any uncaught exceptions and reports them to the Jupyter notebook
 * interface without terminating the executor threads.
 */
public class GlobalUncaughtExceptionHandler implements UncaughtExceptionHandler {
    
    private static final Logger logger = LoggerFactory.getLogger(GlobalUncaughtExceptionHandler.class);
    
    private final Kernel kernel;
    private final SimpleEvaluationObject evaluationObject;
    
    /**
     * Creates a new global uncaught exception handler.
     * 
     * @param kernel The Jupyter kernel instance to report exceptions to
     * @param evaluationObject The evaluation object for outputting exception messages
     */
    public GlobalUncaughtExceptionHandler(Kernel kernel, SimpleEvaluationObject evaluationObject) {
        this.kernel = kernel;
        this.evaluationObject = evaluationObject;
    }
    
    @Override
    public void uncaughtException(Thread thread, Throwable exception) {
        logger.error("Uncaught exception in thread {}: {}", thread.getName(), exception.getMessage(), exception);
        
        try {
            // Format the exception for display in the notebook
            String formattedError = formatExceptionForNotebook(exception);
            
            // Report the exception to the Jupyter notebook interface
            reportExceptionToNotebook(formattedError);
            
        } catch (Exception e) {
            // If we can't report the exception to the notebook, at least log it
            logger.error("Failed to report uncaught exception to notebook", e);
            logger.error("Original uncaught exception was:", exception);
        }
        
        // DO NOT terminate the thread or executor - let it continue running
        logger.info("Thread {} will continue running after uncaught exception", thread.getName());
    }
    
    /**
     * Formats an exception for display in the Jupyter notebook using the same
     * formatting as the existing code execution error handling.
     */
    private String formatExceptionForNotebook(Throwable exception) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        
        // Use the same stack trace sanitization as MicronautCodeRunner
        StackTraceUtils.sanitize(exception).printStackTrace(pw);
        String stackTrace = sw.toString();
        
        // Use the same pretty printing as MicronautCodeRunner
        return printStacktrace("uncaught-exception", stackTrace);
    }
    
    /**
     * Reports the formatted exception to the Jupyter notebook interface.
     */
    private void reportExceptionToNotebook(String formattedError) {
        if (evaluationObject != null) {
            // Create an error result and publish it
            TryResult errorResult = TryResult.createError(formattedError);
            evaluationObject.finished(errorResult.result());
            
            logger.debug("Reported uncaught exception to notebook via evaluation object");
        } else {
            // If no evaluation object available, just log the exception
            // The kernel's logging will still be visible to users through the console
            logger.warn("No evaluation object available to report uncaught exception to notebook. Exception details: {}", formattedError);
        }
    }
    
    /**
     * Creates a default handler when no evaluation object is available.
     * This handler will only log the exception.
     */
    public static GlobalUncaughtExceptionHandler createLoggingOnlyHandler() {
        return new GlobalUncaughtExceptionHandler(null, null);
    }
}