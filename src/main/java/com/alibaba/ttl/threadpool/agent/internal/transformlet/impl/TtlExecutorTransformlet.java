package com.alibaba.ttl.threadpool.agent.internal.transformlet.impl;

import com.alibaba.ttl.threadpool.agent.internal.logging.Logger;
import com.alibaba.ttl.threadpool.agent.internal.transformlet.JavassistTransformlet;
import javassist.*;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static com.alibaba.ttl.threadpool.agent.internal.transformlet.impl.Utils.getCtClass;
import static com.alibaba.ttl.threadpool.agent.internal.transformlet.impl.Utils.signatureOfMethod;

/**
 * TTL {@link JavassistTransformlet} for {@link java.util.concurrent.Executor}.
 *
 * @author Jerry Lee (oldratlee at gmail dot com)
 * @author wuwen5 (wuwen.55 at aliyun dot com)
 * @see java.util.concurrent.Executor
 * @see java.util.concurrent.ExecutorService
 * @see java.util.concurrent.ThreadPoolExecutor
 * @see java.util.concurrent.ScheduledThreadPoolExecutor
 * @see java.util.concurrent.Executors
 * @since 2.5.1
 */
public class TtlExecutorTransformlet implements JavassistTransformlet {
    private static final Logger logger = Logger.getLogger(TtlExecutorTransformlet.class);

    private static Set<String> EXECUTOR_CLASS_NAMES = new HashSet<String>();
    private static final Map<String, String> PARAM_TYPE_NAME_TO_DECORATE_METHOD_CLASS = new HashMap<String, String>();

    private static final String THREAD_POOL_EXECUTOR_CLASS_NAME = "java.util.concurrent.ThreadPoolExecutor";
    private static final String RUNNABLE_CLASS_NAME = "java.lang.Runnable";
    private static final String THREAD_CLASS_NAME = "java.lang.Thread";
    private static final String THROWABLE_CLASS_NAME = "java.lang.Throwable";

    static {
        EXECUTOR_CLASS_NAMES.add(THREAD_POOL_EXECUTOR_CLASS_NAME);
        EXECUTOR_CLASS_NAMES.add("java.util.concurrent.ScheduledThreadPoolExecutor");

        PARAM_TYPE_NAME_TO_DECORATE_METHOD_CLASS.put(RUNNABLE_CLASS_NAME, "com.alibaba.ttl.TtlRunnable");
        PARAM_TYPE_NAME_TO_DECORATE_METHOD_CLASS.put("java.util.concurrent.Callable", "com.alibaba.ttl.TtlCallable");
    }

    private static final String THREAD_FACTORY_CLASS_NAME = "java.util.concurrent.ThreadFactory";

    private final boolean disableInheritable;

    public TtlExecutorTransformlet(boolean disableInheritable) {
        this.disableInheritable = disableInheritable;
    }

    @Override
    public byte[] doTransform(String className, byte[] classFileBuffer, ClassLoader loader) throws IOException, NotFoundException, CannotCompileException {
        if (EXECUTOR_CLASS_NAMES.contains(className)) {
            final CtClass clazz = getCtClass(classFileBuffer, loader);

            for (CtMethod method : clazz.getDeclaredMethods()) {
                updateMethodOfExecutorClass(method);
            }

            if (disableInheritable) updateConstructorDisableInheritable(clazz);

            return clazz.toBytecode();
        } else {
            final CtClass clazz = getCtClass(classFileBuffer, loader);

            if (clazz.isPrimitive() || clazz.isArray() || clazz.isInterface() || clazz.isAnnotation()) {
                return null;
            }
            if (!clazz.subclassOf(clazz.getClassPool().get(THREAD_POOL_EXECUTOR_CLASS_NAME))) return null;

            logger.info("Transforming class " + className);

            final boolean updated = updateBeforeAndAfterExecuteMethodOfExecutorSubclass(clazz);
            if (updated) return clazz.toBytecode();
            else return null;
        }
    }

    private void updateMethodOfExecutorClass(final CtMethod method) throws NotFoundException, CannotCompileException {
        final int modifiers = method.getModifiers();
        if (!Modifier.isPublic(modifiers) || Modifier.isStatic(modifiers)) return;

        CtClass[] parameterTypes = method.getParameterTypes();
        StringBuilder insertCode = new StringBuilder();
        for (int i = 0; i < parameterTypes.length; i++) {
            final String paramTypeName = parameterTypes[i].getName();
            if (PARAM_TYPE_NAME_TO_DECORATE_METHOD_CLASS.containsKey(paramTypeName)) {
                String code = String.format(
                        // decorate to TTL wrapper,
                        // then set AutoWrapper attachment/Tag
                        "$%d = %s.get($%d, false, true);"
                                + "\ncom.alibaba.ttl.spi.TtlAttachmentsDelegate.setAutoWrapper($%<d);",
                        i + 1, PARAM_TYPE_NAME_TO_DECORATE_METHOD_CLASS.get(paramTypeName), i + 1);
                logger.info("insert code before method " + signatureOfMethod(method) + " of class " + method.getDeclaringClass().getName() + ": " + code);
                insertCode.append(code);
            }
        }
        if (insertCode.length() > 0) method.insertBefore(insertCode.toString());
    }

    private void updateConstructorDisableInheritable(final CtClass clazz) throws NotFoundException, CannotCompileException {
        for (CtConstructor constructor : clazz.getDeclaredConstructors()) {
            final CtClass[] parameterTypes = constructor.getParameterTypes();
            final StringBuilder insertCode = new StringBuilder();
            for (int i = 0; i < parameterTypes.length; i++) {
                final String paramTypeName = parameterTypes[i].getName();
                if (THREAD_FACTORY_CLASS_NAME.equals(paramTypeName)) {
                    String code = String.format("$%d = com.alibaba.ttl.threadpool.TtlExecutors.getDisableInheritableThreadFactory($%<d);", i + 1);
                    logger.info("insert code before method " + signatureOfMethod(constructor) + " of class " + constructor.getDeclaringClass().getName() + ": " + code);
                    insertCode.append(code);
                }
            }
            if (insertCode.length() > 0) constructor.insertBefore(insertCode.toString());
        }
    }

    private boolean updateBeforeAndAfterExecuteMethodOfExecutorSubclass(final CtClass clazz) throws NotFoundException, CannotCompileException {
        final CtClass runnableClass = clazz.getClassPool().get(RUNNABLE_CLASS_NAME);
        final CtClass threadClass = clazz.getClassPool().get(THREAD_CLASS_NAME);
        final CtClass throwableClass = clazz.getClassPool().get(THROWABLE_CLASS_NAME);
        boolean updated = false;

        try {
            final CtMethod beforeExecute = clazz.getDeclaredMethod("beforeExecute", new CtClass[]{threadClass, runnableClass});
            // unwrap runnable if IsAutoWrapper
            String code = "$2 = com.alibaba.ttl.threadpool.agent.internal.transformlet.impl.Utils.unwrapIfIsAutoWrapper($2);";
            logger.info("insert code before method " + signatureOfMethod(beforeExecute) + " of class " + beforeExecute.getDeclaringClass().getName() + ": " + code);
            beforeExecute.insertBefore(code);
            updated = true;
        } catch (NotFoundException e) {
            // does not override beforeExecute method, do nothing.
        }

        try {
            final CtMethod afterExecute = clazz.getDeclaredMethod("afterExecute", new CtClass[]{runnableClass, throwableClass});
            // unwrap runnable if IsAutoWrapper
            String code = "$1 = com.alibaba.ttl.threadpool.agent.internal.transformlet.impl.Utils.unwrapIfIsAutoWrapper($1);";
            logger.info("insert code before method " + signatureOfMethod(afterExecute) + " of class " + afterExecute.getDeclaringClass().getName() + ": " + code);
            afterExecute.insertBefore(code);
            updated = true;
        } catch (NotFoundException e) {
            // does not override afterExecute method, do nothing.
        }

        return updated;
    }
}
