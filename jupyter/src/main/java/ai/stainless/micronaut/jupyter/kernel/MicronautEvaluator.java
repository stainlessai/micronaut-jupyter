package ai.stainless.micronaut.jupyter.kernel;

import com.twosigma.beakerx.BeakerXClient;
import com.twosigma.beakerx.TryResult;
import com.twosigma.beakerx.autocomplete.AutocompleteResult;
import com.twosigma.beakerx.autocomplete.MagicCommandAutocompletePatterns;
import com.twosigma.beakerx.evaluator.ClasspathScanner;
import com.twosigma.beakerx.evaluator.JobDescriptor;
import com.twosigma.beakerx.evaluator.TempFolderFactory;
import com.twosigma.beakerx.evaluator.TempFolderFactoryImpl;
import com.twosigma.beakerx.groovy.autocomplete.GroovyAutocomplete;
import com.twosigma.beakerx.groovy.autocomplete.GroovyClasspathScanner;
import com.twosigma.beakerx.groovy.evaluator.GroovyEvaluator;
import com.twosigma.beakerx.groovy.kernel.Groovy;
import com.twosigma.beakerx.inspect.Inspect;
import com.twosigma.beakerx.jvm.classloader.BeakerXUrlClassLoader;
import com.twosigma.beakerx.jvm.object.EvaluationObject;
import com.twosigma.beakerx.jvm.threads.BeakerCellExecutor;
import com.twosigma.beakerx.jvm.threads.CellExecutor;
import com.twosigma.beakerx.kernel.*;
import com.twosigma.beakerx.mimetype.MIMEContainer;
import groovy.lang.Binding;
import groovy.lang.GroovyClassLoader;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.customizers.ImportCustomizer;

import java.io.File;
import java.util.Iterator;
import java.util.concurrent.Executors;

import static com.twosigma.beakerx.groovy.evaluator.EnvVariablesFilter.envVariablesFilter;
import static com.twosigma.beakerx.groovy.evaluator.GroovyClassLoaderFactory.addImportPathToImportCustomizer;
import static com.twosigma.beakerx.groovy.evaluator.GroovyClassLoaderFactory.newParentClassLoader;

public class MicronautEvaluator extends GroovyEvaluator {

    private GroovyClassLoader groovyClassLoader;
    private Binding scriptBinding = null;
    private ImportCustomizer icz;
    private BeakerXUrlClassLoader beakerxUrlClassLoader;

    private GroovyAutocomplete gac;

    private Boolean loaded = false;

    private Micronaut kernel;

    public MicronautEvaluator(String id,
                              String sId,
                              EvaluatorParameters evaluatorParameters,
                              BeakerXClient beakerxClient,
                              MagicCommandAutocompletePatterns autocompletePatterns,
                              ClasspathScanner classpathScanner,
                              Inspect inspect) {
        this(id,
                sId,
                new BeakerCellExecutor("groovy"),
                new TempFolderFactoryImpl(),
                evaluatorParameters,
                beakerxClient,
                autocompletePatterns,
                classpathScanner,
                inspect);
    }

    public MicronautEvaluator(String id,
                              String sId,
                              CellExecutor cellExecutor,
                              TempFolderFactory tempFolderFactory,
                              EvaluatorParameters evaluatorParameters,
                              BeakerXClient beakerxClient,
                              MagicCommandAutocompletePatterns autocompletePatterns,
                              ClasspathScanner classpathScanner,
                              Inspect inspect) {
        super(id,
                sId,
                cellExecutor,
                tempFolderFactory,
                evaluatorParameters,
                beakerxClient,
                autocompletePatterns,
                classpathScanner,
                inspect);
        
        gac = createGroovyAutocomplete(new GroovyClasspathScanner(), groovyClassLoader, imports, autocompletePatterns, scriptBinding);
        outDir = envVariablesFilter(outDir, System.getenv());
    }

    public void init() {
        //if we are already loaded
        if (loaded) {
            //don't load us a second time
            return;
        }
        //if we have no kernel
        if (kernel == null) {
            throw new RuntimeException("Kernel must be set before initializing.");
        }
        //init class loader
        reloadClassloader();
        gac = createGroovyAutocomplete(
                new GroovyClasspathScanner(),
                groovyClassLoader,
                imports,
                autocompletePatterns,
                scriptBinding
        );
        outDir = envVariablesFilter(outDir, System.getenv());
        // we are loaded
        loaded = true;
    }

    @Override
    public TryResult evaluate(EvaluationObject seo, String code, ExecutionOptions executionOptions) {
        logger.debug("evaluate " + code);
        TryResult result = evaluate(seo, new MicronautWorkerThread(this, new JobDescriptor(code, seo, executionOptions)));
        logger.debug("returning " + result);
        logger.debug("isError? " + result.isError());
        logger.debug("isResult? " + result.isResult());
        logger.debug("result= " + result.result());
        if (result.result() instanceof MIMEContainer) {
            MIMEContainer mimeResult = (MIMEContainer) result.result();
            logger.debug("result data= " + mimeResult.getData());
        }
        return result;
    }

    @Override
    public AutocompleteResult autocomplete(String code, int caretPosition) {
        return gac.find(code, caretPosition);
    }

    @Override
    protected void doResetEnvironment() {
        String cpp = createClasspath(classPath);
        reloadClassloader();
        gac = createGroovyAutocomplete(new GroovyClasspathScanner(cpp), groovyClassLoader, imports, autocompletePatterns, scriptBinding);
        executorService.shutdown();
        executorService = Executors.newSingleThreadExecutor();
    }

    @Override
    public void exit() {
        super.exit();
        killAllThreads();
        executorService.shutdown();
        executorService = Executors.newSingleThreadExecutor();
    }

    @Override
    protected void addJarToClassLoader(PathToJar pathToJar) {
        this.beakerxUrlClassLoader.addJar(pathToJar);
    }

    @Override
    protected void addImportToClassLoader(ImportPath anImport) {
        addImportPathToImportCustomizer(icz, anImport);
    }

    private Binding createBinding() {
        //create binding
        Binding newBinding = new Binding();
        //set variables on binding
        newBinding.setVariable("_boundApplicationContext", kernel.getApplicationContext());
        //return binding
        return newBinding;
    }

    private static GroovyAutocomplete createGroovyAutocomplete(GroovyClasspathScanner c,
                                                               GroovyClassLoader groovyClassLoader,
                                                               Imports imports,
                                                               MagicCommandAutocompletePatterns autocompletePatterns,
                                                               Binding scriptBinding) {
        return new GroovyAutocomplete(c,
                groovyClassLoader,
                imports,
                autocompletePatterns,
                scriptBinding);
    }

    private static String createClasspath(Classpath classPath) {
        StringBuilder cppBuilder = new StringBuilder();
        for (String pt : classPath.getPathsAsStrings()) {
            cppBuilder.append(pt);
            cppBuilder.append(File.pathSeparator);
        }
        String cpp = cppBuilder.toString();
        cpp += File.pathSeparator;
        cpp += System.getProperty("java.class.path");
        return cpp;
    }

    private void reloadClassloader() {
        this.beakerxUrlClassLoader = newParentClassLoader(getClasspath());
        this.icz = new ImportCustomizer();
        this.groovyClassLoader = newEvaluator(beakerxUrlClassLoader);
        this.scriptBinding = createBinding();
    }

    @Override
    public ClassLoader getClassLoader() {
        return groovyClassLoader;
    }
    
    public GroovyClassLoader getGroovyClassLoader() {
        return groovyClassLoader;
    }

    public Binding getScriptBinding() {
        return scriptBinding;
    }

    public Micronaut getKernel() {
        return kernel;
    }

    public void setKernel(Micronaut kernel) {
        this.kernel = kernel;
    }

    /*
     * Custom implementation of GroovyClassLoaderFactory methods that use
     * custom compiler config
     */

    public GroovyClassLoader newEvaluator(ClassLoader parent) {

        if (!imports.isEmpty()) {
            Iterator var2 = imports.getImportPaths().iterator();

            while (var2.hasNext()) {
                ImportPath importLine = (ImportPath) var2.next();
                addImportPathToImportCustomizer(icz, importLine);
            }
        }

        CompilerConfiguration config = (new CompilerConfiguration()).addCompilationCustomizers(icz);

        String gjp = String.join(""+File.pathSeparatorChar, getClasspath().getPathsAsStrings());
        config.setClasspath(gjp);

        // set custom base class
        config.setScriptBaseClass("ai.stainless.micronaut.jupyter.kernel.MicronautJupyterScript");

        return new GroovyClassLoader(parent, config);
    }

}
