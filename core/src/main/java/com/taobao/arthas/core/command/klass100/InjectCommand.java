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
import javassist.ClassClassPath;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtMethod;
import javassist.LoaderClassPath;
import javassist.NotFoundException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.concurrent.atomic.AtomicInteger;

@Name("inject")
@Summary("inject code")
@Description(Constants.EXAMPLE + " inject <Class_name> -n <line_number> -code \"<piece of code>\"\n"
        + "  inject --deleteAll             # delete all injection entries\n"
        + Constants.WIKI + Constants.WIKI_HOME
        + "inject")
public class InjectCommand extends AnnotatedCommand {
    private static final Logger logger = LoggerFactory.getLogger(InjectCommand.class);


    private static volatile List<InjectEntry> injectEntries = new ArrayList<>();
    private static volatile List<String> injectEntriesClasses = new ArrayList<>();

    private static volatile InjectEntry injectEntry = null;
    private static volatile ClassFileTransformer transformer = null;

    private Integer line;

    private String code;
    private String method;

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
    public void setMethode(String method) {
        this.method = method;
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

    private static void initTransformer() {
        if (transformer != null) {
            return;
        } else {
            synchronized (InjectCommand.class) {
                transformer = new CodeInjector3();
                TransformerManager transformerManager = ArthasBootstrap.getInstance().getTransformerManager();
                transformerManager.addRetransformer(transformer);
            }
        }
    }

    @Override
    public void process(CommandProcess process) {
        initTransformer();
        Instrumentation inst = process.session().getInstrumentation();

        if (this.list) {
            InjectModel injectModel = new InjectModel();
            injectModel.setInjectEntries(allInjectEntries());
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
        
        if (className == null || method== null || line == null || code == null) {
            process.end(-1, String.format("Invalid params. classname: %s, method: %s, line: %s, code: %s", className, method, line, code));
            return;
        }

        for (Class<?> clazz : inst.getAllLoadedClasses()) {
            if (clazz.getName().equals(className)) {
                try {
                    //initInjectEntry(className, line, code, clazz.getClassLoader());
                    InjectEntry entry = new InjectEntry(className, method, line, code, clazz.getClassLoader());
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
                    process.end(-1, message);
                    return;
                }
            }
        }

        process.end(-1, "No matched class.");
    }

    @Override
    public void complete(Completion completion) {
        logger.info("completion {}",completion.lineTokens());
        if (!CompletionUtils.completeClassName(completion)) {
            super.complete(completion);
        }
    }
//
//    static class CodeInjector implements ClassFileTransformer {
//
//        @Override
//        public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
//            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
//            byte[] byteCode = classfileBuffer;
//
//            String finalTargetClassName = injectEntry.getClassName().replace(".", "/");
//            if (!className.equals(finalTargetClassName)) {
//                return byteCode;
//            }
//
//            if (loader.equals(injectEntry.getClassLoader())) {
//                logger.info("[Agent] Transforming class {}", injectEntry.getClassName());
//                try {
//                    ClassPool cp = ClassPool.getDefault();
//                    cp.appendClassPath(new LoaderClassPath(loader));
//                    CtClass cc = cp.get(injectEntry.getClassName());
//                    CtMethod m = cc.getDeclaredMethod(injectEntry.getMethod());
//
//                    m.addLocalVariable("logger_test", cp.get("org.slf4j.Logger"));
//                    m.insertBefore("logger_test=org.slf4j.LoggerFactory.getLogger(" + injectEntry.getClassName() + ".class);");
//                    m.insertAt(injectEntry.getLineNumber(), "logger_test.error(\"test\");");
//
//                    m.addLocalVariable("label", cp.get("com.inditex.amgadmic.domain.application.ApplicationLabels"));
//                    m.insertBefore("label=ApplicationLabels.builder.build();");
//                    m.insertAt(injectEntry.getLineNumber(), "logger_test.error(label);");
//                    
//
//                    //m.insertAt(1, "org.slf4j.Logger logger_test = org.slf4j.LoggerFactory.getLogger(" + injectEntry.getClassName() + ".class);logger_test.error(\"test\");");
//                    m.insertAt(injectEntry.getLineNumber(), "LOGGER.error(\"inject: " + injectEntry.getId() + "\");");
//
//
//                    byteCode = cc.toBytecode();
//                    cc.detach();
//                } catch (NotFoundException | CannotCompileException | IOException e) {
//                    logger.error("Exception", e);
//                    throw new RuntimeException("Error injecting code", e);
//                }
//            }
//
//            return byteCode;
//
//        }
//    }

    static class CodeInjector2 implements ClassFileTransformer {

        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
            ProtectionDomain protectionDomain, byte[] classfileBuffer) {

            className = className.replace('/', '.');

            List<InjectEntry> allInjectEntries = allInjectEntries();
            
            ListIterator<InjectEntry> listIterator = allInjectEntries
                .listIterator(allInjectEntries.size());
            while (listIterator.hasPrevious()) {
                InjectEntry entry = listIterator.previous();
                if (className.equals(entry.getClassName())) {
                    logger.info("[Agent] Transforming class {}", entry.getClassName());
                    try {
                        ClassPool cp = ClassPool.getDefault();
                        cp.appendClassPath(new LoaderClassPath(loader));
                        
                        LoaderClassPath cp1 = new LoaderClassPath(this.getClass().getClassLoader());
                        cp.insertClassPath(cp1);
                        
                        CtClass cc = cp.get(entry.getClassName());
                        CtMethod m = cc.getDeclaredMethod(entry.getMethod());
                        
                        int insertedAt = m.insertAt(entry.getLineNumber(),
                            "org.slf4j.Logger loggerTest = org.slf4j.LoggerFactory.getLogger(" + entry.getClassName() + ".class); "
                                + "loggerTest.error(\"inject " + entry.getId() + "\");");
                        
                        logger.info(">>> Inserted at {}", insertedAt);
                        
                        cc.writeFile();
                        
                        byte[] byteCode = cc.toBytecode();
                        cc.detach();
                        return byteCode;
                    } catch (NotFoundException | CannotCompileException | IOException e) {
                        logger.error("Exception", e);
                        throw new RuntimeException("Error injecting code", e);
                    }
                }

            }

            return null;

        }
    }

    static class CodeInjector3 implements ClassFileTransformer {

        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
            ProtectionDomain protectionDomain, byte[] classfileBuffer) {

            className = className.replace('/', '.');

            if (!injectEntriesClasses.contains(className)) {
                return null;
            }

            new ClassPool(parent)

            ClassPool cp = ClassPool.getDefault();
            cp.appendClassPath(new LoaderClassPath(loader));
            LoaderClassPath cp1 = new LoaderClassPath(this.getClass().getClassLoader());
            cp.insertClassPath(cp1);
            CtClass cc;
            try {
                cc = cp.get(className);
            } catch (NotFoundException e) {
                logger.error("NotFoundException", e);
                throw new RuntimeException("Error injecting code. Class not found: " + className, e);
            }

            List<InjectEntry> allInjectEntries = allInjectEntries();

            for (InjectEntry entry : allInjectEntries) {
                logger.info("[Agent] Next entry {}: {}", entry.getId(), entry.getClassName());

                if (className.equals(entry.getClassName())) {
                    logger.info("[Agent] Transforming class {}", entry.getClassName());
                    try {
                        CtMethod m = cc.getDeclaredMethod(entry.getMethod());
                        logger.info("[Agent] Transforming method {} with descriptor {}", entry.getMethod(), m.getMethodInfo().getDescriptor());
                        int insertedAt = m.insertAt(entry.getLineNumber(),
                            "org.slf4j.Logger loggerTest = org.slf4j.LoggerFactory.getLogger(" + entry.getClassName() + ".class); "
                                + "loggerTest.error(\"code injector 3 " + entry.getId() + "\");");
                        logger.info(">>> Inserted at {}", insertedAt);
                    } catch (NotFoundException e) {
                        logger.error("NotFoundException", e);
                        throw new RuntimeException("Error injecting code. Method not found: " + entry.getMethod(), e);
                    } catch (CannotCompileException e) {
                        logger.error("CannotCompileException", e);
                        throw new RuntimeException("Error injecting code. Compilation error: ", e);
                    }
                }
            }

            cc.detach();

            try {
                return cc.toBytecode();
            } catch (CannotCompileException e) {
                logger.error("CannotCompileException", e);
                throw new RuntimeException("Error injecting code. Compilation error: ", e);
            } catch (IOException e) {
                logger.error("IOException", e);
                throw new RuntimeException("Error injecting code. IOException: ", e);
            }

        }
    }

    public static class InjectEntry {
        private static final AtomicInteger counter = new AtomicInteger(0);
        
        private int id;
        private String className;
        private String method;
        private Integer lineNumber;
        private String code;
        private ClassLoader classLoader;
        
        public InjectEntry(String className, String method, Integer lineNumber, String code, ClassLoader classLoader) {
            id = counter.incrementAndGet();
            this.className = className;
            this.method = method;
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

    public static synchronized void deleteAllInjectEntries() {
        injectEntries = new ArrayList<>();
    }

    public static List<InjectEntry> allInjectEntries() {
        return injectEntries;
    }

    public static synchronized InjectEntry deleteInjectEntry(int id) {
        InjectEntry result = null;
        List<InjectEntry> tmp = new ArrayList<>();
        for (InjectEntry entry : injectEntries) {
            if (entry.getId() != id) {
                tmp.add(entry);
            } else {
                result = entry;
            }
        }
        injectEntries = tmp;
        setEntriesClasses();
        return result;
    }

    public static synchronized void addInjectEntry(InjectEntry injectEntry) {
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
    
    private static void setEntriesClasses() {
        injectEntriesClasses = new ArrayList<>();
        for (InjectEntry entry : injectEntries) {
            injectEntriesClasses.add(entry.getClassName());
        }
    }

}
