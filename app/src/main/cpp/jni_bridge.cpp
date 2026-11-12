#include "netdiag/probe.hpp"
#include <jni.h>
#include <limits>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <unordered_map>

namespace {
std::mutex registry_mutex;
std::unordered_map<jlong, std::shared_ptr<netdiag::Cancellation>> registry;
jlong next_id = 1;
jclass result_class = nullptr;
jmethodID result_constructor = nullptr;

std::shared_ptr<netdiag::Cancellation> lookup(jlong id) {
    std::lock_guard<std::mutex> lock(registry_mutex);
    const auto found = registry.find(id);
    return found == registry.end() ? nullptr : found->second;
}

jobject make_result(JNIEnv* env, const netdiag::Result& result) {
    jstring status = env->NewStringUTF(netdiag::status_name(result.status));
    if (!status) return nullptr;
    jstring detail = env->NewStringUTF(result.detail.c_str());
    if (!detail) { env->DeleteLocalRef(status); return nullptr; }
    jobject object = env->NewObject(result_class, result_constructor, status,
        static_cast<jdouble>(result.elapsed_ms), static_cast<jint>(result.system_error), detail);
    env->DeleteLocalRef(status);
    env->DeleteLocalRef(detail);
    return object;
}

jlong create_run(JNIEnv* env, jobject) {
    try {
        auto cancellation = std::make_shared<netdiag::Cancellation>();
        std::lock_guard<std::mutex> lock(registry_mutex);
        if (next_id == std::numeric_limits<jlong>::max()) throw std::runtime_error("Request ID limit reached");
        const jlong id = next_id++;
        registry.emplace(id, std::move(cancellation));
        return id;
    } catch (const std::exception&) {
        jclass type = env->FindClass("java/lang/IllegalStateException");
        if (type) { env->ThrowNew(type, "Unable to create a native request"); env->DeleteLocalRef(type); }
        return 0;
    }
}
void cancel_run(JNIEnv*, jobject, jlong id) { if (auto token = lookup(id)) token->cancel(); }
void release_run(JNIEnv*, jobject, jlong id) {
    std::lock_guard<std::mutex> lock(registry_mutex);
    // An executing worker retains shared ownership; erasing a handle cannot
    // free its cancellation object while that worker is still using it.
    registry.erase(id);
}
jobject run(JNIEnv* env, jobject, jlong id, jint protocol, jstring address, jint port, jint timeout, jlong network) {
    const auto cancellation = lookup(id);
    if (!cancellation || !address) return make_result(env, {netdiag::Status::InvalidInput, 0, 0, "Invalid or released request handle"});
    const char* chars = env->GetStringUTFChars(address, nullptr);
    if (!chars) return nullptr;
    std::string host;
    try { host = chars; }
    catch (...) { env->ReleaseStringUTFChars(address, chars); return make_result(env, {netdiag::Status::SystemError, 0, 0, "Unable to copy address"}); }
    env->ReleaseStringUTFChars(address, chars);
    try {
        const netdiag::Request request{static_cast<netdiag::Protocol>(protocol), host, port, timeout, static_cast<std::uint64_t>(network)};
        return make_result(env, netdiag::probe(request, *cancellation));
    } catch (const std::exception&) {
        return make_result(env, {netdiag::Status::SystemError, 0, 0, "Unexpected native failure"});
    }
}

JNINativeMethod methods[]{
    {const_cast<char*>("createRun"), const_cast<char*>("()J"), reinterpret_cast<void*>(create_run)},
    {const_cast<char*>("cancel"), const_cast<char*>("(J)V"), reinterpret_cast<void*>(cancel_run)},
    {const_cast<char*>("release"), const_cast<char*>("(J)V"), reinterpret_cast<void*>(release_run)},
    {const_cast<char*>("run"), const_cast<char*>("(JILjava/lang/String;IIJ)Ldev/netdiag/NativeResult;"), reinterpret_cast<void*>(run)}
};
} // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass local_result = env->FindClass("dev/netdiag/NativeResult");
    if (!local_result) return JNI_ERR;
    result_class = static_cast<jclass>(env->NewGlobalRef(local_result));
    env->DeleteLocalRef(local_result);
    if (!result_class) return JNI_ERR;
    result_constructor = env->GetMethodID(result_class, "<init>", "(Ljava/lang/String;DILjava/lang/String;)V");
    jclass bridge = env->FindClass("dev/netdiag/NativeBridge");
    const bool registered = result_constructor && bridge && env->RegisterNatives(bridge, methods, sizeof(methods) / sizeof(methods[0])) == JNI_OK;
    if (bridge) env->DeleteLocalRef(bridge);
    if (!registered) { env->DeleteGlobalRef(result_class); result_class = nullptr; return JNI_ERR; }
    return JNI_VERSION_1_6;
}
extern "C" JNIEXPORT void JNICALL JNI_OnUnload(JavaVM* vm, void*) {
    JNIEnv* env = nullptr;
    if (result_class && vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK) env->DeleteGlobalRef(result_class);
    result_class = nullptr;
}
