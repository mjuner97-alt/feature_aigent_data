package com.agentscopea2a.util;


public class ThreadContextUtils {
    // 使用 InheritableThreadLocal 来支持子线程继承上下文
    private static final InheritableThreadLocal<StringBuilder> context = new InheritableThreadLocal<>();

    /**
     * 设置当前线程的上下文 (StringBuilder)
     */
    public static void setContext(StringBuilder value) {
        context.set(value);
    }

    /**
     * 获取当前线程的上下文 (StringBuilder)
     */
    public static StringBuilder getContext() {
        return context.get();
    }

    /**
     * 移除当前线程的上下文，防止内存泄漏
     */
    public static void clearContext() {
        context.remove();
    }
}