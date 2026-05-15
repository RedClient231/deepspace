#include <jni.h>
#include <android/log.h>
#include "hook.h"
#include "io_redirect.h"
#include "memory_bridge.h"

#define TAG "vengine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)

// ── JNI OnLoad ─────────────────────────────────────────────────────

static JavaVM* g_vm = nullptr;

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    g_vm = vm;
    LOGI("JNI_OnLoad: initializing vengine");

    // Initialize PLT hooks
    int ret = hook_init();
    if (ret != 0) {
        LOGE("hook_init failed: %d", ret);
    }

    // Register hooks for libc functions
    hook_register(".*\\.so$", "open",   (void*)hook_open,   nullptr);
    hook_register(".*\\.so$", "openat", (void*)hook_openat, nullptr);
    hook_register(".*\\.so$", "fopen",  (void*)hook_fopen,  nullptr);
    hook_register(".*\\.so$", "stat",   (void*)hook_stat,   nullptr);
    hook_register(".*\\.so$", "access", (void*)hook_access, nullptr);
    hook_register(".*\\.so$", "ptrace", (void*)hook_ptrace, nullptr);
    hook_register(".*\\.so$", "execve", (void*)hook_execve, nullptr);

    // Apply all hooks
    ret = hook_refresh();
    if (ret != 0) {
        LOGE("hook_refresh failed: %d", ret);
    }

    LOGI("vengine hooks installed");
    return JNI_VERSION_1_6;
}

// ── Native Init (called from VirtualCore) ──────────────────────────

extern "C" JNIEXPORT void JNICALL
Java_com_vspace_engine_VirtualCore_nativeInit(
    JNIEnv* env, jobject /*thiz*/, jstring dataPath) {
    const char* path = env->GetStringUTFChars(dataPath, nullptr);
    LOGI("nativeInit: data_path=%s", path);

    // Initialize IO redirection
    io_redirect_init(path);

    // Initialize memory bridge
    memory_bridge_init();

    env->ReleaseStringUTFChars(dataPath, path);
}

// ── Install Hooks ──────────────────────────────────────────────────

extern "C" JNIEXPORT void JNICALL
Java_com_vspace_engine_VirtualCore_nativeInstallHooks(
    JNIEnv* /*env*/, jobject /*thiz*/) {
    LOGI("nativeInstallHooks: refreshing hooks");
    hook_refresh();
}

// ── Path Redirection JNI ───────────────────────────────────────────

extern "C" JNIEXPORT jstring JNICALL
Java_com_vspace_engine_VirtualCore_nativeRedirectPath(
    JNIEnv* env, jobject /*thiz*/, jstring originalPath) {
    const char* path = env->GetStringUTFChars(originalPath, nullptr);
    const char* redirected = redirect_path(path);
    jstring result = nullptr;
    if (redirected != nullptr) {
        result = env->NewStringUTF(redirected);
    }
    env->ReleaseStringUTFChars(originalPath, path);
    return result;
}

// ── Memory Read/Write JNI ──────────────────────────────────────────

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_vspace_engine_VirtualCore_nativeReadMemory(
    JNIEnv* env, jobject /*thiz*/, jint pid, jlong address, jint size) {
    auto* buf = new uint8_t[size];
    int ret = memory_bridge_read(pid, (uint64_t)address, buf, size);
    if (ret > 0) {
        jbyteArray result = env->NewByteArray(ret);
        env->SetByteArrayRegion(result, 0, ret, (jbyte*)buf);
        delete[] buf;
        return result;
    }
    delete[] buf;
    return nullptr;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_vspace_engine_VirtualCore_nativeWriteMemory(
    JNIEnv* env, jobject /*thiz*/, jint pid, jlong address, jbyteArray data) {
    jsize len = env->GetArrayLength(data);
    jbyte* buf = env->GetByteArrayElements(data, nullptr);
    int ret = memory_bridge_write(pid, (uint64_t)address, buf, len);
    env->ReleaseByteArrayElements(data, buf, JNI_ABORT);
    return ret > 0 ? JNI_TRUE : JNI_FALSE;
}

// ── MemoryBridge JNI ───────────────────────────────────────────────

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_vspace_engine_ipc_MemoryBridge_nativeReadMemory(
    JNIEnv* env, jobject /*thiz*/, jint pid, jlong address, jint size) {
    auto* buf = new uint8_t[size];
    int ret = memory_bridge_read(pid, (uint64_t)address, buf, size);
    if (ret > 0) {
        jbyteArray result = env->NewByteArray(ret);
        env->SetByteArrayRegion(result, 0, ret, (jbyte*)buf);
        delete[] buf;
        return result;
    }
    delete[] buf;
    return nullptr;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_vspace_engine_ipc_MemoryBridge_nativeWriteMemory(
    JNIEnv* env, jobject /*thiz*/, jint pid, jlong address, jbyteArray data) {
    jsize len = env->GetArrayLength(data);
    jbyte* buf = env->GetByteArrayElements(data, nullptr);
    int ret = memory_bridge_write(pid, (uint64_t)address, buf, len);
    env->ReleaseByteArrayElements(data, buf, JNI_ABORT);
    return ret > 0 ? JNI_TRUE : JNI_FALSE;
}

// ── Utility ────────────────────────────────────────────────────────

JavaVM* get_java_vm() {
    return g_vm;
}
