package ai.stainless.micronaut.jupyter.kernel;

/**
 * A utility class to process Java code strings, specifically to ensure
 * a "package micronaut.jupyter" declaration is present if no other package
 * declaration is found at the beginning of the code.
 */
public class EnsureScriptPackage {

    /**
     * Checks if the given Java code string starts with a "package" declaration
     * as its first non-whitespace token. If not, it prepends
     * "package micronaut.jupyter\n" to the string.
     * If the input string is null, it's treated as an empty file, and the
     * "package micronaut.jupyter\n" string is returned.
     *
     * @param javaCode The Java code as a string. This can be null.
     * @return The original string if its first non-whitespace token is "package",
     * or the modified string with "package micronaut.jupyter\n" prepended.
     * If javaCode is null, returns "package micronaut.jupyter\n".
     */
    public String ensurePackageMicronautJupyter(String javaCode) {
        // Store the original code for return or concatenation.
        // If javaCode is null, treat it as an empty string for the purpose of prepending.
        String originalCodeForReturn = (javaCode == null) ? "" : javaCode;

        // Handle cases where the input is null or effectively empty (all whitespace).
        // In these scenarios, a "package" declaration is missing.
        if (javaCode == null || javaCode.trim().isEmpty()) {
            return "package micronaut.jupyter\n" + originalCodeForReturn;
        }

        // To find the "first non-whitespace string", we trim leading/trailing
        // whitespace from the code and then split by any whitespace sequence.
        String trimmedCode = javaCode.trim();

        // Split the trimmed code by whitespace. We are interested in the first token.
        // Using a limit of 2 for the split is a small optimization, as we only need the first part.
        String[] tokens = trimmedCode.split("\\s+", 2);

        // The trimmedCode was not empty, so tokens array will have at least one element.
        // Check if the first token is exactly "package".
        if (tokens[0].equals("package")) {
            // The first non-whitespace string is "package", so return the original code.
            return originalCodeForReturn;
        } else {
            // The first non-whitespace string is not "package", so prepend the specified package string.
            return "package micronaut.jupyter\n" + originalCodeForReturn;
        }
    }

    /**
     * Main method for demonstrating and testing the ensurePackageMicronautJupyter method.
     * @param args Command line arguments (not used).
     */
    public static void main(String[] args) {
        EnsureScriptPackage processor = new EnsureScriptPackage();

        System.out.println("--- Test Cases ---");

        String test1 = "package com.example;\n\nclass Test {}";
        System.out.println("Input 1:\n\"" + test1 + "\"");
        System.out.println("Output 1:\n\"" + processor.ensurePackageMicronautJupyter(test1) + "\"\n");
        // Expected: "package com.example;\n\nclass Test {}" (original)

        String test2 = "import java.util.*;\nclass Test {}";
        System.out.println("Input 2:\n\"" + test2 + "\"");
        System.out.println("Output 2:\n\"" + processor.ensurePackageMicronautJupyter(test2) + "\"\n");
        // Expected: "package micronaut.jupyter\nimport java.util.*;\nclass Test {}" (prepended)

        String test3 = "  package com.another;\nclass Another {}"; // Leading whitespace
        System.out.println("Input 3:\n\"" + test3 + "\"");
        System.out.println("Output 3:\n\"" + processor.ensurePackageMicronautJupyter(test3) + "\"\n");
        // Expected: "  package com.another;\nclass Another {}" (original, leading whitespace preserved)

        String test4 = "class MyClass {}";
        System.out.println("Input 4:\n\"" + test4 + "\"");
        System.out.println("Output 4:\n\"" + processor.ensurePackageMicronautJupyter(test4) + "\"\n");
        // Expected: "package micronaut.jupyter\nclass MyClass {}" (prepended)

        String test5 = ""; // Empty string
        System.out.println("Input 5:\n\"" + test5 + "\"");
        System.out.println("Output 5:\n\"" + processor.ensurePackageMicronautJupyter(test5) + "\"\n");
        // Expected: "package micronaut.jupyter\n" (prepended to empty string)

        String test6 = "   "; // Only whitespace
        System.out.println("Input 6:\n\"" + test6 + "\"");
        System.out.println("Output 6:\n\"" + processor.ensurePackageMicronautJupyter(test6) + "\"\n");
        // Expected: "package micronaut.jupyter\n   " (prepended, original whitespace preserved)

        String test7 = "package"; // Exactly "package" with no other content
        System.out.println("Input 7:\n\"" + test7 + "\"");
        System.out.println("Output 7:\n\"" + processor.ensurePackageMicronautJupyter(test7) + "\"\n");
        // Expected: "package" (original)

        String test8 = "   package   "; // "package" surrounded by whitespace
        System.out.println("Input 8:\n\"" + test8 + "\"");
        System.out.println("Output 8:\n\"" + processor.ensurePackageMicronautJupyter(test8) + "\"\n");
        // Expected: "   package   " (original)

        String test9 = "packagemain"; // "package" is part of another word, not standalone
        System.out.println("Input 9:\n\"" + test9 + "\"");
        System.out.println("Output 9:\n\"" + processor.ensurePackageMicronautJupyter(test9) + "\"\n");
        // Expected: "package micronaut.jupyter\npackagemain" (prepended)

        String test10 = null; // Null input
        System.out.println("Input 10:\n" + test10);
        System.out.println("Output 10:\n\"" + processor.ensurePackageMicronautJupyter(test10) + "\"\n");
        // Expected: "package micronaut.jupyter\n" (prepended to effective empty string)

        String test11 = "\n\n  package my.package;\n//code"; // Starts with newlines then package
        System.out.println("Input 11:\n\"" + test11 + "\"");
        System.out.println("Output 11:\n\"" + processor.ensurePackageMicronautJupyter(test11) + "\"\n");
        // Expected: "\n\n  package my.package;\n//code" (original)
    }
}
