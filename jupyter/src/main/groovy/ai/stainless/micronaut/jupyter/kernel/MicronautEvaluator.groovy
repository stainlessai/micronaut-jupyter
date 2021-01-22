package ai.stainless.micronaut.jupyter.kernel

/*
 * Customized GroovyEvaluator implementation that uses custom threading and
 * class loader. License from BeakerX pasted below.
 */

/*
 *  Copyright 2014 TWO SIGMA OPEN SOURCE, LLC
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
import com.twosigma.beakerx.BeakerXClient;
import com.twosigma.beakerx.TryResult;
import com.twosigma.beakerx.autocomplete.AutocompleteResult;
import com.twosigma.beakerx.autocomplete.MagicCommandAutocompletePatterns;
import com.twosigma.beakerx.evaluator.JobDescriptor;
import com.twosigma.beakerx.evaluator.TempFolderFactory;
import com.twosigma.beakerx.evaluator.TempFolderFactoryImpl;
import com.twosigma.beakerx.groovy.autocomplete.GroovyAutocomplete;
import com.twosigma.beakerx.groovy.autocomplete.GroovyClasspathScanner
import com.twosigma.beakerx.groovy.evaluator.GroovyEvaluator
import com.twosigma.beakerx.groovy.evaluator.GroovyWorkerThread;
import com.twosigma.beakerx.jvm.classloader.BeakerXUrlClassLoader;
import com.twosigma.beakerx.jvm.object.SimpleEvaluationObject;
import com.twosigma.beakerx.jvm.threads.BeakerCellExecutor;
import com.twosigma.beakerx.jvm.threads.CellExecutor;
import com.twosigma.beakerx.kernel.Classpath;
import com.twosigma.beakerx.kernel.EvaluatorParameters;
import com.twosigma.beakerx.kernel.ExecutionOptions;
import com.twosigma.beakerx.kernel.ImportPath;
import com.twosigma.beakerx.kernel.Imports;
import com.twosigma.beakerx.kernel.PathToJar;
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.customizers.ImportCustomizer;

import java.io.File;
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

    private Boolean loaded = false
    Micronaut kernel

    public MicronautEvaluator(
        String id,
        EvaluatorParameters evaluatorParameters,
        BeakerXClient beakerxClient,
        MagicCommandAutocompletePatterns autocompletePatterns
    ) {
        this(
            id,
            new BeakerCellExecutor("groovy"),
            new TempFolderFactoryImpl(),
            evaluatorParameters,
            beakerxClient,
            autocompletePatterns
        )
    }

    public MicronautEvaluator(
        String id,
        CellExecutor cellExecutor,
        TempFolderFactory tempFolderFactory,
        EvaluatorParameters evaluatorParameters,
        BeakerXClient beakerxClient,
        MagicCommandAutocompletePatterns autocompletePatterns
    ) {
        super(
            id,
            id,
            cellExecutor,
            tempFolderFactory,
            evaluatorParameters,
            beakerxClient,
            autocompletePatterns
        )
    }

    public void init () {
        //if we are already loaded
        if (loaded) {
            //don't load us a second time
            return
        }
        //if we have no kernel
        if (!kernel) {
            throw new RuntimeException("Kernel must be set before initializing.")
        }
        //init class loader
        reloadClassloader()
        gac = createGroovyAutocomplete(
            new GroovyClasspathScanner(),
            groovyClassLoader,
            imports,
            autocompletePatterns
        )
        outDir = envVariablesFilter(outDir, System.getenv())
        // we are loaded
        loaded = true
    }

    @Override
    public TryResult evaluate(SimpleEvaluationObject seo, String code, ExecutionOptions executionOptions) {
        return evaluate(seo, new MicronautWorkerThread(this, new JobDescriptor(code, seo, executionOptions)))
    }

    @Override
    public AutocompleteResult autocomplete(String code, int caretPosition) {
        return gac.find(code, caretPosition);
    }

    @Override
    protected void doResetEnvironment() {
        String cpp = createClasspath(classPath);
        reloadClassloader();
        gac = createGroovyAutocomplete(new GroovyClasspathScanner(cpp), groovyClassLoader, imports, autocompletePatterns);
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
        Binding newBinding = new Binding()
        //set variables on binding
        newBinding.setVariable("_boundApplicationContext", kernel.applicationContext)
        //return binding
        return newBinding
    }

    private GroovyAutocomplete createGroovyAutocomplete(GroovyClasspathScanner c, GroovyClassLoader groovyClassLoader, Imports imports, MagicCommandAutocompletePatterns autocompletePatterns) {
        return new GroovyAutocomplete(c, groovyClassLoader, imports, autocompletePatterns);
    }

    private String createClasspath(Classpath classPath) {
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
        this.scriptBinding = createBinding()
    }

    @Override
    public ClassLoader getClassLoader() {
        return groovyClassLoader;
    }
    
    @Override
    public GroovyClassLoader getGroovyClassLoader() {
        return groovyClassLoader;
    }
    
    @Override
    public Binding getScriptBinding() {
        return scriptBinding;
    }

/*
     * Custom implementation of GroovyClassLoaderFactory methods that use
     * custom compiler config
     */
    
    public GroovyClassLoader newEvaluator(ClassLoader parent) {
        
        if (!imports.isEmpty()) {
            Iterator var2 = imports.getImportPaths().iterator()

            while(var2.hasNext()) {
                ImportPath importLine = (ImportPath)var2.next()
                addImportPathToImportCustomizer(icz, importLine)
            }
        }

        CompilerConfiguration config = (new CompilerConfiguration()).addCompilationCustomizers(icz)

        String gjp = String.join(File.pathSeparatorChar as String, classpath.getPathsAsStrings())
        config.setClasspath(gjp)

        // set custom base class
        config.scriptBaseClass = "ai.stainless.micronaut.jupyter.kernel.MicronautJupyterScript"

        return new GroovyClassLoader(parent, config)
    }

}
