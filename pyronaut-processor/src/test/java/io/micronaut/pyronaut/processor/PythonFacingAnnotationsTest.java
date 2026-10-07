package io.micronaut.pyronaut.processor;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.python.PythonContextRuntime;
import io.micronaut.core.beans.BeanIntrospection;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.inject.ExecutableMethod;
import io.micronaut.python.compiler.PythonIncrementalMode;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PythonFacingAnnotationsTest {
    @TempDir
    Path directory;

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void compilesPythonNamesIntoMicronautBeanAndConstraintMetadata() throws Exception {
        Path python = Files.createDirectories(directory.resolve("python"));
        Path javaDirectory = Files.createDirectories(directory.resolve("java"));
        Path target = directory.resolve("classes");
        Files.writeString(python.resolve("service.py"), """
            from typing import Annotated
            from dataclasses import dataclass
            from pyronaut.build import Dependency
            from pyronaut.annotations import singleton, not_blank, valid
            from pyronaut.annotations import singleton as scoped, not_blank as nonempty, valid as cascade
            from micronaut.context.annotation import Executable
            from micronaut.core.annotation import Introspected

            @Introspected
            @dataclass
            class Payload:
                name: Annotated[str, not_blank]

            @singleton
            class Service:
                @Executable
                def greet(self, name: Annotated[str, not_blank(message="Name required")],
                          nested: Annotated[Payload, valid]) -> str:
                    return name

            @scoped()
            class CalledService:
                @Executable
                def greet(self, name: Annotated[str, nonempty()],
                          nested: Annotated[Payload, cascade()]) -> str:
                    return name
            """);
        List<Path> classpath = Arrays.stream(System.getProperty("java.class.path").split(System.getProperty("path.separator")))
            .map(Path::of)
            .toList();
        new PyronautCompilerExecutor.Default().compile(new PyronautCompilerExecutor.CompileRequest(
            python, javaDirectory, target, classpath, classpath, false, false,
            PythonIncrementalMode.CONSERVATIVE, directory.resolve("cache"), List.of(), null
        ));

        try (var files = Files.walk(target);
             var loader = new URLClassLoader(new java.net.URL[]{target.toUri().toURL()}, getClass().getClassLoader())) {
            Path definitionFile = files.filter(path -> path.getFileName().toString().equals("$Service$Definition.class"))
                .findFirst().orElseThrow(() -> new AssertionError("No compiled singleton bean definition"));
            String definitionName = target.relativize(definitionFile).toString()
                .replace(java.io.File.separatorChar, '.').replaceAll("\\.class$", "");
            BeanDefinition<?> definition = (BeanDefinition<?>) loader.loadClass(definitionName).getConstructor().newInstance();
            assertTrue(definition.isSingleton());
            var method = definition.getExecutableMethods().stream().filter(it -> it.getMethodName().equals("greet"))
                .findFirst().orElseThrow();
            var name = method.getArguments()[0].getAnnotationMetadata();
            assertTrue(name.hasAnnotation("jakarta.validation.constraints.NotBlank"));
            assertEquals("Name required", name.stringValue("jakarta.validation.constraints.NotBlank", "message").orElseThrow());
            assertTrue(method.getArguments()[1].getAnnotationMetadata().hasAnnotation("jakarta.validation.Valid"));
            assertTrue(method.hasStereotype("io.micronaut.validation.Validated"));

            ClassLoader previous = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(loader);
            PythonContextRuntime.resetContext();
            try (var context = ApplicationContext.builder().classLoader(loader).environments("test").start()) {
                Object bean = context.getBean((Class) definition.getBeanType());
                assertSame(bean, context.getBean((Class) definition.getBeanType()));
                BeanIntrospection<?> payload = (BeanIntrospection<?>) loader.loadClass(
                    definition.getBeanType().getPackageName() + ".$Payload$Introspection"
                ).getConstructor().newInstance();
                ExecutableMethod<Object, Object> executable = (ExecutableMethod) method;
                for (String accepted : List.of("John", " John ")) {
                    assertEquals(accepted, executable.invoke(bean, accepted, payload.instantiate("Nested")));
                }
                for (String rejected : new String[]{null, "", " ", "\t\r\n"}) {
                    var blank = assertThrows(ConstraintViolationException.class,
                        () -> executable.invoke(bean, rejected, payload.instantiate("Nested")));
                    assertTrue(blank.getMessage().contains("Name required"));
                }
                assertThrows(ConstraintViolationException.class,
                    () -> executable.invoke(bean, "John", payload.instantiate(" ")));

                Class calledType = loader.loadClass(definition.getBeanType().getPackageName() + ".CalledService");
                Object calledBean = context.getBean(calledType);
                assertSame(calledBean, context.getBean(calledType));
                ExecutableMethod<Object, Object> calledMethod = (ExecutableMethod) context.getBeanDefinition(calledType)
                    .getExecutableMethods().stream().filter(it -> ((ExecutableMethod) it).getMethodName().equals("greet"))
                    .findFirst().orElseThrow();
                assertEquals("John", calledMethod.invoke(calledBean, "John", payload.instantiate("Nested")));
                assertThrows(ConstraintViolationException.class,
                    () -> calledMethod.invoke(calledBean, "", payload.instantiate("Nested")));
                assertThrows(ConstraintViolationException.class,
                    () -> calledMethod.invoke(calledBean, "John", payload.instantiate("")));
            } finally {
                PythonContextRuntime.resetContext();
                Thread.currentThread().setContextClassLoader(previous);
            }
        }
    }
}
