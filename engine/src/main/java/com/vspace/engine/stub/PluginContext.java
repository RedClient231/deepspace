package com.vspace.engine.stub;

import dalvik.system.DexClassLoader;

/**
 * Shared holder for the target app's ClassLoader and Resources.
 * StubApp (in the stub process) stores them here after loading.
 * StubActivity reads them to avoid creating duplicate ClassLoaders.
 */
public final class PluginContext {

    private static volatile ClassLoader sClassLoader;
    private static volatile android.content.res.Resources sResources;

    public static void setClassLoader(DexClassLoader cl) {
        sClassLoader = cl;
    }

    public static ClassLoader getClassLoader() {
        return sClassLoader;
    }

    public static void setResources(android.content.res.Resources res) {
        sResources = res;
    }

    public static android.content.res.Resources getResources() {
        return sResources;
    }

    public static void clear() {
        sClassLoader = null;
        sResources = null;
    }
}
