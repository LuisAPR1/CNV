package pt.ulisboa.tecnico.cnv.javassist;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;

import javassist.ClassPool;
import javassist.CtBehavior;
import javassist.CtClass;
import javassist.CtMethod;

/**
 * Javassist-based Java agent that instruments workload classes at load time
 * to collect execution metrics (method calls, basic blocks).
 */
public class JavassistAgent {

    /** Packages whose classes will be instrumented. */
    private static final String[] TARGET_PACKAGES = {
            "pt.ulisboa.tecnico.cnv.fractals",
            "pt.ulisboa.tecnico.cnv.grayscott",
            "pt.ulisboa.tecnico.cnv.dna"
    };

    /** Handler classes where request start/stop is injected. */
    private static final String[] HANDLER_CLASSES = {
            "pt.ulisboa.tecnico.cnv.fractals.FractalsHandler",
            "pt.ulisboa.tecnico.cnv.grayscott.GrayScottHandler",
            "pt.ulisboa.tecnico.cnv.dna.DnaHandler"
    };

    public static void premain(String args, Instrumentation inst) {
        System.out.println("[JavassistAgent] Agent loaded. Instrumenting workload classes...");
        inst.addTransformer(new CNVTransformer());
    }

    /**
     * ClassFileTransformer that uses Javassist to inject metric-collection code.
     */
    static class CNVTransformer implements ClassFileTransformer {

        @Override
        public byte[] transform(ClassLoader loader, String className,
                                Class<?> classBeingRedefined,
                                ProtectionDomain protectionDomain,
                                byte[] classfileBuffer) {
            if (className == null) return null;

            String dotName = className.replace('/', '.');

            // Only instrument our target packages.
            boolean isTarget = false;
            for (String pkg : TARGET_PACKAGES) {
                if (dotName.startsWith(pkg)) {
                    isTarget = true;
                    break;
                }
            }
            if (!isTarget) return null;

            try {
                ClassPool pool = ClassPool.getDefault();
                CtClass cc = pool.get(dotName);

                if (cc.isFrozen()) {
                    cc.defrost();
                }

                boolean isHandler = false;
                for (String h : HANDLER_CLASSES) {
                    if (dotName.equals(h)) {
                        isHandler = true;
                        break;
                    }
                }

                for (CtMethod method : cc.getDeclaredMethods()) {
                    if (method.isEmpty()) continue;

                    // Instrument the handle(HttpExchange) method in handlers
                    // to start/stop metric collection per request.
                    if (isHandler && method.getName().equals("handle")
                            && method.getParameterTypes().length == 1) {
                        instrumentHandleMethod(method, dotName);
                    }

                    // Instrument ALL methods to count method calls and basic blocks.
                    instrumentMethod(method);
                }

                byte[] bytecode = cc.toBytecode();
                cc.detach();
                System.out.println("[JavassistAgent] Instrumented: " + dotName);
                return bytecode;

            } catch (Exception e) {
                System.err.println("[JavassistAgent] Failed to instrument " + dotName + ": " + e.getMessage());
                return null;
            }
        }

        /**
         * Wraps the handle() method with startRequest/stopRequest calls.
         */
        private void instrumentHandleMethod(CtMethod method, String className) throws Exception {
            // Build a request ID from query string for tracing.
            String startCode =
                    "{ " +
                    "  String __uri = $1.getRequestURI().toString(); " +
                    "  pt.ulisboa.tecnico.cnv.javassist.MetricRegistry.startRequest(__uri); " +
                    "}";
            method.insertBefore(startCode);

            String stopCode =
                    "{ pt.ulisboa.tecnico.cnv.javassist.MetricRegistry.stopRequest(); }";
            method.insertAfter(stopCode, /* asFinally */ true);
        }

        /**
         * Inserts method-call counting and basic-block counting at the start of each method.
         */
        private void instrumentMethod(CtMethod method) throws Exception {
            // Count this method invocation.
            method.insertBefore(
                    "{ pt.ulisboa.tecnico.cnv.javassist.MetricRegistry.incrementMethodCalls(); }");

            // Count basic blocks: use Javassist's insertAt to add counters.
            // A simple but effective heuristic: count the method body as 1 basic block,
            // plus additional blocks estimated from the bytecode length.
            int codeLength = method.getMethodInfo().getCodeAttribute() != null
                    ? method.getMethodInfo().getCodeAttribute().getCodeLength() : 0;
            // Rough estimate: 1 basic block per ~15 bytecode bytes (a common heuristic).
            long estimatedBlocks = Math.max(1, codeLength / 15);
            method.insertBefore(
                    "{ pt.ulisboa.tecnico.cnv.javassist.MetricRegistry.incrementBasicBlocks("
                            + estimatedBlocks + "L); }");
        }
    }
}
