package ai.stainless.micronaut.jupyter.kernel

import com.twosigma.beakerx.kernel.ImportPath
import com.twosigma.beakerx.kernel.KernelFunctionality
import com.twosigma.beakerx.kernel.PathToJar
import com.twosigma.beakerx.kernel.RuntimetoolsImpl

class MicronautRuntimeToolsImpl extends RuntimetoolsImpl {

    public MicronautRuntimeToolsImpl() {
    }

    public void configRuntimeJars(KernelFunctionality kernel) {
        String pathToJar = System.getenv("RUNTIMETOOLS_PATH");
        if (pathToJar != null) {
            kernel.addJarsToClasspath(Arrays.asList(new PathToJar(pathToJar)));
            kernel.addImport(new ImportPath("com.twosigma.beakerx.BxDriverManager"));
        }
    }

}
