package io.micronaut.pyronaut.dev;

import io.micronaut.dev.compile.CompilationRequest;
import io.micronaut.dev.compile.CompilationResult;
import io.micronaut.dev.compile.SourceCompiler;
import io.micronaut.dev.compile.SourceKind;
import io.micronaut.openapi.visitor.Utils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The processors the launcher holds, as the native image does, keep their static state between the compilations of
 * the development runtime, where a processor path of their own goes with each compilation: the OpenAPI visitor's
 * endpoints held the elements of every compilation, and with them its javac and GraalPy contexts.
 */
class LauncherProcessorStateTest {

    @AfterEach
    void clean() {
        Utils.clean();
    }

    @Test
    void theProcessorsStateIsReleasedAfterEachCompilationWhenTheLauncherHoldsThem() {
        Compiler python = new Compiler(Set.of(SourceKind.PYTHON), Set.of(SourceKind.JAVA));
        Map<SourceKind, SourceCompiler> compilers = new HashMap<>();
        compilers.put(SourceKind.PYTHON, python);
        compilers.put(SourceKind.JAVA, python);

        Map<SourceKind, SourceCompiler> releasing = LauncherProcessorState.releasingAfterCompilation(compilers, LauncherProcessorState.release(LauncherProcessorState.class.getClassLoader()));

        SourceCompiler compiler = releasing.get(SourceKind.PYTHON);
        // one compiler for its two languages
        assertSame(compiler, releasing.get(SourceKind.JAVA));
        assertEquals(Set.of(SourceKind.PYTHON), compiler.kinds());
        assertEquals(Set.of(SourceKind.JAVA), compiler.jointKinds());
        assertTrue(compiler.isAvailable());
        CompilationResult result = compiler.compile(null);
        assertSame(python.result, result);
        assertNotNull(python.endpointsDuringCompilation.getFirst(), "the state was released before the compilation finished");
        assertNull(Utils.getEndpointInfos(), "the OpenAPI visitor kept the endpoints of the compilation");
        compiler.close();
        assertTrue(python.closed);
    }

    @Test
    void theCompilersAreUnchangedWhenTheLauncherHoldsNoSuchProcessor() {
        assertNull(LauncherProcessorState.release(new URLClassLoader(new URL[0], null)));
        Map<SourceKind, SourceCompiler> compilers = Map.of(SourceKind.PYTHON, new Compiler(Set.of(SourceKind.PYTHON), Set.of()));

        assertSame(compilers, LauncherProcessorState.releasingAfterCompilation(compilers, null));
    }

    /**
     * A compiler whose compilation leaves endpoints in the OpenAPI visitor's static state, as the visitor does.
     */
    private static final class Compiler implements SourceCompiler {
        final CompilationResult result = new CompilationResult(CompilationResult.Status.SUCCESS, List.of(), Set.of(), Set.of(), Duration.ZERO);
        final List<Object> endpointsDuringCompilation = new ArrayList<>();
        final Set<SourceKind> kinds;
        final Set<SourceKind> jointKinds;
        boolean closed;

        Compiler(Set<SourceKind> kinds, Set<SourceKind> jointKinds) {
            this.kinds = kinds;
            this.jointKinds = jointKinds;
        }

        @Override
        public Set<SourceKind> kinds() {
            return kinds;
        }

        @Override
        public Set<SourceKind> jointKinds() {
            return jointKinds;
        }

        @Override
        public CompilationResult compile(CompilationRequest request) {
            Utils.setEndpointInfos(new HashMap<>());
            endpointsDuringCompilation.add(Utils.getEndpointInfos());
            return result;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
