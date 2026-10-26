# Micronaut Jupyter

## Build Requirements

- JDK 25 (`/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home` on macOS)
- Set `JAVA_HOME` before running Gradle:
  ```bash
  export JAVA_HOME=/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home
  ```

## Build

```bash
./gradlew :examples:basic-service:shadowJar
```

## Key Dependencies

- Groovy 5.0.8 (required for JDK 25 ASM compatibility)
- Micronaut 4.7.1
- Gradle 9.x (required for JDK 25 reflection support)
- BeakerX kernel base 2.2-SNAPSHOT (from `ai.stainless` group)
