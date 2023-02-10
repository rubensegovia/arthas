package com.taobao.arthas.core.command.klass100;

import com.alibaba.arthas.deps.org.slf4j.Logger;
import com.alibaba.arthas.deps.org.slf4j.LoggerFactory;
import com.taobao.arthas.core.advisor.TransformerManager;
import com.taobao.arthas.core.command.Constants;
import com.taobao.arthas.core.command.model.InjectModel;
import com.taobao.arthas.core.server.ArthasBootstrap;
import com.taobao.arthas.core.shell.cli.Completion;
import com.taobao.arthas.core.shell.cli.CompletionUtils;
import com.taobao.arthas.core.shell.command.AnnotatedCommand;
import com.taobao.arthas.core.shell.command.CommandProcess;
import com.taobao.middleware.cli.annotations.Argument;
import com.taobao.middleware.cli.annotations.Description;
import com.taobao.middleware.cli.annotations.Name;
import com.taobao.middleware.cli.annotations.Option;
import com.taobao.middleware.cli.annotations.Summary;
import javassist.CannotCompileException;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtMethod;
import javassist.CtNewMethod;
import javassist.LoaderClassPath;
import javassist.NotFoundException;

import javax.xml.bind.DatatypeConverter;
import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.charset.StandardCharsets;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Name("inject")
@Summary("inject code")
@Description(Constants.EXAMPLE + " inject <Fully_qualified_class_name> -m <Method> -n <Line_number> -code \"<Piece_of_code_base64_encoded>\" -s <Method_descriptor>\n"
        + "  inject --deleteAll             # delete all injection entries\n"
        + Constants.WIKI + Constants.WIKI_HOME
        + "inject")
public class InjectCommand extends AnnotatedCommand {
    private static final Logger logger = LoggerFactory.getLogger(InjectCommand.class);

    private static volatile List<InjectEntry> injectEntries = new ArrayList<>();
    private static volatile List<String> injectEntriesClasses = new ArrayList<>();
    private static volatile ClassFileTransformer transformer = null;

    private Integer line;

    private String code;
    private String method;
    private String methodDescriptor;

    private String className;

    private boolean list;

    private int delete = -1;

    private boolean deleteAll;

    @Option(shortName = "n", longName = "lineNumber")
    @Description("inject line.")
    public void setLine(Integer line) {
        this.line = line;
    }

    @Option(shortName = "c", longName = "code")
    @Description("inject code")
    public void setCode(String code) {
        this.code = code;
    }

    @Option(shortName = "m", longName = "method")
    @Description("inject method")
    public void setMethod(String method) {
        this.method = method;
    }

    @Option(shortName = "s", longName = "methodDescriptor")
    @Description("inject method descriptor")
    public void setMethodDescriptor(String methodDescriptor) {
        this.methodDescriptor = methodDescriptor;
    }

    @Argument(argName = "className", index = 0, required = false)
    @Description("class name")
    public void setClassName(String className) {
        this.className = className;
    }

    @Option(shortName = "l", longName = "list", flag = true)
    @Description("list all inject entry.")
    public void setList(boolean list) {
        this.list = list;
    }

    @Option(shortName = "d", longName = "delete")
    @Description("delete inject entry by id.")
    public void setDelete(int delete) {
        this.delete = delete;
    }

    @Option(longName = "deleteAll", flag = true)
    @Description("delete all inject entries.")
    public void setDeleteAll(boolean deleteAll) {
        this.deleteAll = deleteAll;
    }

    @Override
    public void process(CommandProcess process) {
        initTransformer();
        Instrumentation inst = process.session().getInstrumentation();

        if (this.list) {
            InjectModel injectModel = new InjectModel();
            injectModel.setInjectEntries(injectEntries);
            process.appendResult(injectModel);
            process.end();
            return;
        } else if (deleteAll) {
            deleteAllInjectEntries();
            process.appendResult(new InjectModel());
            process.end();
            return;
        } else if (this.delete > 0) {
            deleteInjectEntry(this.delete);
            process.end();
            return;
        }

        if (className == null || method == null || line == null || code == null) {
            process.end(-1, String.format("Invalid params. classname: %s, method: %s, line: %s, code: %s", className, method, line, code));
            return;
        }

        for (Class<?> clazz : inst.getAllLoadedClasses()) {
            if (clazz.getName().equals(className)) {
                String decoded = new String(DatatypeConverter.parseBase64Binary(code), StandardCharsets.UTF_8);
                InjectEntry entry = new InjectEntry(className, method, methodDescriptor, line, decoded, clazz.getClassLoader());
                try {
                    addInjectEntry(entry);

                    InjectModel injectModel = new InjectModel();
                    injectModel.setId(entry.getId());
                    injectModel.setInjectedClass(className);
                    process.appendResult(injectModel);

                    inst.retransformClasses(clazz);
                    process.end();
                    return;
                } catch (Exception e) {
                    String message = "Injection failed for class: [" + clazz.getName() + "] " + e;
                    logger.error(message, e);
                    deleteInjectEntry(entry.getId());
                    process.end(-1, message);
                    return;
                }
            }
        }

        process.end(-1, "No matched class.");
    }

    @Override
    public void complete(Completion completion) {
        logger.info("completion {}", completion.lineTokens());
        if (!CompletionUtils.completeClassName(completion)) {
            super.complete(completion);
        }
    }

    private static void initTransformer() {
        if (transformer == null) {
            synchronized (InjectCommand.class) {
                transformer = new CodeInjector();
                TransformerManager transformerManager = ArthasBootstrap.getInstance().getTransformerManager();
                transformerManager.addRetransformer(transformer);
            }
        }
    }

    static class CodeInjector implements ClassFileTransformer {

        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                                ProtectionDomain protectionDomain, byte[] classfileBuffer) {

            final String fqn = className.replace('/', '.');

            if (!injectEntriesClasses.contains(fqn)) {
                return null;
            }

            ClassPool cp = ClassPool.getDefault();
            cp.appendClassPath(new LoaderClassPath(loader));
            LoaderClassPath cp1 = new LoaderClassPath(this.getClass().getClassLoader());
            cp.insertClassPath(cp1);
            CtClass cc;
            try {
                cc = cp.get(fqn);
            } catch (NotFoundException e) {
                logger.error("Error injecting code. Method not found: {}", fqn, e);
                return null;
            }

            for (InjectEntry entry : injectEntries) {
                logger.info("[Agent] Next entry {}: {}", entry.getId(), entry.getClassName());
                if (fqn.equals(entry.getClassName())) {
                    logger.info("[Agent] Transforming class {}", entry.getClassName());
                    try {
                        CtMethod m = entry.getMethodDescriptor() == null
                                ? cc.getDeclaredMethod(entry.getMethod())
                                : cc.getMethod(entry.getMethod(), entry.getMethodDescriptor());
                        logger.info("[Agent] Transforming method {} with descriptor {}", entry.getMethod(), m.getMethodInfo().getDescriptor());
                        int insertedAt = m.insertAt(entry.getLineNumber(), entry.getCode());
                        logger.info(">>> Inserted at {}", insertedAt);
                    } catch (NotFoundException e) {
                        logger.error("Error injecting code. Method not found: {}", entry.getMethod(), e);
                        deleteInjectEntry(entry.getId());
                        return null;
                    } catch (CannotCompileException e) {
                        logger.error("Error injecting code. Compilation error", e);
                        deleteInjectEntry(entry.getId());
                        return null;
                    }
                }
            }

            cc.detach();

            try {
                return cc.toBytecode();
            } catch (CannotCompileException e) {
                logger.error("Error injecting code. Compilation error", e);
                return null;
            } catch (IOException e) {
                logger.error("IOException", e);
                return null;
            }

        }
    }

    public static class InjectEntry {
        private static final AtomicInteger counter = new AtomicInteger(0);
        private int id;
        private String className;
        private String method;
        private String methodDescriptor;
        private Integer lineNumber;
        private String code;
        private ClassLoader classLoader;

        public InjectEntry(String className, String method, String methodDescriptor, Integer lineNumber, String code, ClassLoader classLoader) {
            id = counter.incrementAndGet();
            this.className = className;
            this.method = method;
            this.methodDescriptor = methodDescriptor;
            this.lineNumber = lineNumber;
            this.code = code;
            this.classLoader = classLoader;
        }

        public int getId() {
            return id;
        }

        public void setId(int id) {
            this.id = id;
        }

        public String getClassName() {
            return className;
        }

        public void setClassName(String className) {
            this.className = className;
        }

        public String getMethod() {
            return method;
        }

        public void setMethod(String method) {
            this.method = method;
        }

        public String getMethodDescriptor() {
            return methodDescriptor;
        }

        public void setMethodDescriptor(String methodDescriptor) {
            this.methodDescriptor = methodDescriptor;
        }

        public Integer getLineNumber() {
            return lineNumber;
        }

        public void setLineNumber(Integer lineNumber) {
            this.lineNumber = lineNumber;
        }

        public String getCode() {
            return code;
        }

        public void setCode(String code) {
            this.code = code;
        }

        public ClassLoader getClassLoader() {
            return classLoader;
        }

        public void setClassLoader(ClassLoader classLoader) {
            this.classLoader = classLoader;
        }
    }

    private static synchronized void deleteAllInjectEntries() {
        injectEntries = new ArrayList<>();
    }

    private static synchronized void deleteInjectEntry(int id) {
        List<InjectEntry> tmp = new ArrayList<>();
        for (InjectEntry entry : injectEntries) {
            if (entry.getId() != id) {
                tmp.add(entry);
            }
        }
        injectEntries = tmp;
        setEntriesClasses();
    }

    private static synchronized void addInjectEntry(InjectEntry injectEntry) {
        List<InjectEntry> tmp = new ArrayList<>(injectEntries);
        tmp.add(injectEntry);
        Collections.sort(tmp, new Comparator<InjectEntry>() {
            @Override
            public int compare(InjectEntry entry1, InjectEntry entry2) {
                return entry1.getId() - entry2.getId();
            }
        });
        injectEntries = tmp;
        setEntriesClasses();
    }

    private static synchronized void setEntriesClasses() {
        injectEntriesClasses = new ArrayList<>();
        for (InjectEntry entry : injectEntries) {
            injectEntriesClasses.add(entry.getClassName());
        }
    }

}
