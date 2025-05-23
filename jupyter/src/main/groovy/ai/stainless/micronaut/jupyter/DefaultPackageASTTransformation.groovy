package ai.stainless.micronaut.jupyter;

import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.ast.PackageNode
import org.codehaus.groovy.control.CompilePhase
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.transform.ASTTransformation
import org.codehaus.groovy.transform.GroovyASTTransformation

/**
 * An AST Transformation that adds a default package to a source unit
 * if no package is explicitly defined.
 * Note: this isn't currently used but was tried as an attempt to workaround
 * the packageName is null BUG! in Groovy 4.0.28
 */
@GroovyASTTransformation(phase = CompilePhase.SEMANTIC_ANALYSIS)
class DefaultPackageASTTransformation implements ASTTransformation {

    private final String defaultPackageName

    /**
     * Constructor.
     * @param defaultPackageName The default package name to apply (e.g., "com.example.defaultpkg").
     */
    DefaultPackageASTTransformation(String defaultPackageName) {
        // System.out.println("DefaultPackageASTTransformation "+defaultPackageName)
        if (defaultPackageName == null || defaultPackageName.trim().isEmpty()) {
            throw new IllegalArgumentException("Default package name cannot be null or empty.")
        }
        this.defaultPackageName = defaultPackageName.trim()
    }

    @Override
    void visit(ASTNode[] nodes, SourceUnit sourceUnit) {
        if (sourceUnit == null) {
            // Should not happen in normal compilation
            return
        }
        ModuleNode moduleNode = sourceUnit.getAST()
        if (moduleNode == null) {
            // AST not yet available or source unit is problematic
            return
        }

        // Check if a package is already defined in the source code
        boolean packageNotDefined = (moduleNode.getPackage() == null ||
                moduleNode.getPackage().getName() == null ||
                moduleNode.getPackage().getName().isEmpty())

        if (packageNotDefined) {
            // System.out.println("[DefaultPackageASTTransformation] Applying default package '${defaultPackageName}' to ${sourceUnit.getName()}")
            PackageNode defaultPackage = new PackageNode(defaultPackageName)
            moduleNode.setPackage(defaultPackage)

            // If there are classes, their package needs to be updated too.
            // ModuleNode.setPackage() should ideally handle this, but let's be explicit for clarity
            // as ClassNode.getPackageName() might derive from ModuleNode.
            // However, direct manipulation of ClassNode's package is often unnecessary
            // as it's derived from the ModuleNode's package.
            // Re-setting the module for classes can ensure consistency if needed.
//             moduleNode.getClasses().each { classNode ->
//                classNode.setModule(moduleNode)
//            }
        } else {
            // System.out.println("[DefaultPackageASTTransformation] Existing package '${moduleNode.getPackage().getName()}' found in ${sourceUnit.getName()}. Default not applied.")
        }
    }
}


