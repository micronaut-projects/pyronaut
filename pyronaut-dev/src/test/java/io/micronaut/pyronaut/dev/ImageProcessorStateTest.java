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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The processors a native image holds keep their static state between the compilations of the development runtime,
 * where javac on the JVM loads them anew for each: the OpenAPI visitor's endpoints held the elements of every
 * compilation, and with them its javac and GraalPy contexts.
 */
class ImageProcessorStateTest {

    @AfterEach
    void clean() {
        Utils.clean();
    }

    @Test
    void theProcessorsStateIsReleasedAfterEachCompilationWhenTheyRunFromTheImage() {
        Compiler python = new Compiler(Set.of(SourceKind.PYTHON), Set.of(SourceKind.JAVA));
        Map<SourceKind, SourceCompiler> compilers = new HashMap<>();
        compilers.put(SourceKind.PYTHON, python);
        compilers.put(SourceKind.JAVA, python);

        Map<SourceKind, SourceCompiler> releasing = ImageProcessorState.releasingAfterCompilation(compilers, true);

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
    void theCompilersAreUnchangedOnTheJvm() {
        Map<SourceKind, SourceCompiler> compilers = Map.of(SourceKind.PYTHON, new Compiler(Set.of(SourceKind.PYTHON), Set.of()));

        assertSame(compilers, ImageProcessorState.releasingAfterCompilation(compilers, false));
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
