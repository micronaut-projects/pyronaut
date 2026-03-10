# pyronaut-processor

`pyronaut-processor` executes `io.micronaut.python.compiler.PyronautCompiler` to process Python sources and generate Micronaut build-time metadata.

## Current execution mode

- **Default:** JVM/JIT execution (`application` plugin launcher)
- **Native image:** currently **experimental** and disabled by default due an upstream GraalVM native-image compiler issue.

Enable native-image tasks explicitly only for local experimentation:

```bash
./gradlew :micronaut-pyronaut-processor:nativeCompile -PpyronautProcessorNative=true
./gradlew :micronaut-pyronaut-processor:nativeSmokeTest -PpyronautProcessorNative=true
```

Until the GraalVM issue is fixed, production flow should use JVM execution.
