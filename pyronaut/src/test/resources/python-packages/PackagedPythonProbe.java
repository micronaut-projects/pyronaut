import io.micronaut.context.python.GraalPyContextCustomizer;
import org.graalvm.polyglot.Context;
import org.graalvm.python.embedding.GraalPyResources;
import org.graalvm.python.embedding.VirtualFileSystem;

import java.util.ServiceLoader;

/** Imports a vendored package without the environment used to build it. */
public class PackagedPythonProbe {
    public static void main(String[] args) throws Exception {
        System.setProperty("org.graalvm.python.vfs.allow_multiple", "true");
        try (var vfs = VirtualFileSystem.newBuilder().resourceDirectory("META-INF/GRAALPY-VFS/micronaut-application")
                .resourceClassLoader(Thread.currentThread().getContextClassLoader()).build()) {
            var builder = Context.newBuilder("python").allowAllAccess(true).apply(GraalPyResources.forVirtualFileSystem(vfs));
            String hostPython = System.getenv("PYRONAUT_TEST_HOST_PYTHON");
            if (hostPython != null) {
                builder.option("python.Executable", hostPython);
            }
            ServiceLoader.load(GraalPyContextCustomizer.class).forEach(customizer -> customizer.customize(builder));
            try (var context = builder.build()) {
                if ("true".equals(System.getenv("PYRONAUT_TEST_WORKSPACE"))) {
                    context.eval("python", "import local_dependency; assert local_dependency.VALUE == 42");
                }
                context.eval("python", "import idna, sys, xxhash; assert 'graalpy_vfs' in sys.prefix; "
                    + "assert xxhash.xxh64(b'').hexdigest() == 'ef46db3751d8e999'; "
                    + "assert idna.encode('bücher.example') == b'xn--bcher-kva.example'; print('PACKAGED_IMPORT_OK')");
            }
        }
    }
}
