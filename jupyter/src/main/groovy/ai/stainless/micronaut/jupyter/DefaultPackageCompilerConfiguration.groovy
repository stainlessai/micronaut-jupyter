package ai.stainless.micronaut.jupyter

import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.customizers.ASTTransformationCustomizer

/**
 * A custom CompilerConfiguration that applies a default package name
 * to Groovy scripts/classes if they don't have one.
 * Note: this isn't currently used but was tried as an attempt to workaround
 * the packageName is null BUG! in Groovy 4.0.28
 */
class DefaultPackageCompilerConfiguration extends CompilerConfiguration {

    public static final String DEFAULT_FALLBACK_PACKAGE = "ai.stainless.default.script"

    /**
     * Default constructor. Uses a predefined fallback default package name.
     */
    DefaultPackageCompilerConfiguration() {
        this(DEFAULT_FALLBACK_PACKAGE)
        System.out.println("Loaded the DefaultPackageCompilerConfiguration")
    }

    /**
     * Constructor to specify the default package name.
     * @param defaultPackageName The package name to apply if none is found (e.g., "my.default.package").
     * If null or empty, a fallback default is used.
     */
    DefaultPackageCompilerConfiguration(String defaultPackageName) {
        super() // Initialize with default Groovy compiler settings
        String effectivePackageName = determineEffectivePackageName(defaultPackageName)
        addDefaultPackageTransformation(effectivePackageName)
        // System.out.println("[DefaultPackageCompilerConfiguration] Initialized with default package: ${effectivePackageName}")
    }

    /**
     * Constructor that inherits settings from an existing CompilerConfiguration
     * and then adds the default package functionality.
     * @param defaultPackageName The package name to apply.
     * @param existingConfiguration An existing configuration whose settings will be copied.
     */
    DefaultPackageCompilerConfiguration(String defaultPackageName, CompilerConfiguration existingConfiguration) {
        super(existingConfiguration) // Copies all settings from the existing configuration
        String effectivePackageName = determineEffectivePackageName(defaultPackageName)
        addDefaultPackageTransformation(effectivePackageName)
        // System.out.println("[DefaultPackageCompilerConfiguration] Initialized from existing config, with default package: ${effectivePackageName}")
    }

    /**
     * Constructor that inherits settings from an existing CompilerConfiguration
     * and then adds the default package functionality using the fallback default package.
     * @param existingConfiguration An existing configuration whose settings will be copied.
     */
    DefaultPackageCompilerConfiguration(CompilerConfiguration existingConfiguration) {
        this(DEFAULT_FALLBACK_PACKAGE, existingConfiguration)
    }

    private String determineEffectivePackageName(String desiredPackageName) {
        return (desiredPackageName != null && !desiredPackageName.trim().isEmpty()) ?
                desiredPackageName.trim() : DEFAULT_FALLBACK_PACKAGE
    }

    private void addDefaultPackageTransformation(String packageName) {
        // Create the AST transformation that will add the default package
        DefaultPackageASTTransformation transformation = new DefaultPackageASTTransformation(packageName)

        // Create a customizer for this transformation
        ASTTransformationCustomizer astTransformationCustomizer = new ASTTransformationCustomizer(transformation)

        // Add the customizer to this compiler configuration
        this.addCompilationCustomizers(astTransformationCustomizer)
    }
}