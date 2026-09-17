import com.sun.source.util.JavacTask;
import javax.tools.*;
import java.util.*;

/** Parses Android files only. This is explicitly NOT Android type-checking or an APK build. */
public class SyntaxCheck {
    public static void main(String[] args) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("Java compiler module required");
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(diagnostics, null, null)) {
            JavacTask task = (JavacTask)compiler.getTask(null, files, diagnostics,
                    List.of("-proc:none", "--release", "17"), null, files.getJavaFileObjects(args));
            task.parse();
        }
        boolean error = false;
        for (Diagnostic<?> d : diagnostics.getDiagnostics()) if (d.getKind() == Diagnostic.Kind.ERROR) {
            System.err.println(d); error = true;
        }
        if (error) System.exit(1);
        System.out.println("PASS: Java syntax for " + args.length + " source files (no Android symbol resolution)");
    }
}
