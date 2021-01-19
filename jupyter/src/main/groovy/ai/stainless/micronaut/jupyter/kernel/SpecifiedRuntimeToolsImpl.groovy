package ai.stainless.micronaut.jupyter.kernel

import com.twosigma.beakerx.kernel.ImportPath
import com.twosigma.beakerx.kernel.KernelFunctionality
import com.twosigma.beakerx.kernel.PathToJar
import com.twosigma.beakerx.kernel.RuntimetoolsImpl

class SpecifiedRuntimeToolsImpl extends RuntimetoolsImpl {

    private String pathToJar;

    public SpecifiedRuntimeToolsImpl(String pathToJar) {
        this.pathToJar = pathToJar;
    }

    public void configRuntimeJars(KernelFunctionality kernel) {
        kernel.addJarsToClasspath(Arrays.asList(new PathToJar(this.pathToJar)));
        kernel.addImport(new ImportPath("com.twosigma.beakerx.BxDriverManager"));
    }

}
