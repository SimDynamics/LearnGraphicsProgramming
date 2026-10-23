#include <jni.h>
#include <sched.h>
#include <unistd.h>
#include <cerrno>
#include <cstring>
#include <string>
#include <vector>

namespace {
void fail(JNIEnv* env, const std::string& message) {
    jclass type = env->FindClass("java/lang/IllegalStateException");
    if (type != nullptr) env->ThrowNew(type, message.c_str());
}

bool current_mask(JNIEnv* env, cpu_set_t& mask) {
    CPU_ZERO(&mask);
    if (sched_getaffinity(0, sizeof(mask), &mask) != 0) {
        const int error = errno;
        fail(env, "sched_getaffinity(current thread): " + std::string(std::strerror(error)));
        return false;
    }
    if (CPU_COUNT(&mask) == 0) {
        fail(env, "Android returned an empty current-thread CPU mask");
        return false;
    }
    return true;
}

jintArray mask_array(JNIEnv* env, const cpu_set_t& mask) {
    std::vector<jint> cpus;
    for (int cpu = 0; cpu < CPU_SETSIZE; ++cpu) {
        if (CPU_ISSET(cpu, &mask)) cpus.push_back(static_cast<jint>(cpu));
    }
    jintArray result = env->NewIntArray(static_cast<jsize>(cpus.size()));
    if (result != nullptr) env->SetIntArrayRegion(result, 0, static_cast<jsize>(cpus.size()), cpus.data());
    return result;
}

bool requested_mask(JNIEnv* env, jintArray cpus, cpu_set_t& mask) {
    if (cpus == nullptr) {
        fail(env, "A CPU affinity mask is required");
        return false;
    }
    const jsize count = env->GetArrayLength(cpus);
    if (count <= 0 || count > CPU_SETSIZE) {
        fail(env, "CPU affinity must contain supported CPU identifiers");
        return false;
    }
    std::vector<jint> requested(static_cast<size_t>(count));
    env->GetIntArrayRegion(cpus, 0, count, requested.data());
    if (env->ExceptionCheck()) return false;
    CPU_ZERO(&mask);
    for (jint cpu : requested) {
        if (cpu < 0 || cpu >= CPU_SETSIZE) {
            fail(env, "CPU identifier exceeds the supported native affinity mask");
            return false;
        }
        CPU_SET(cpu, &mask);
    }
    return true;
}
}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_learngraphicsprogramming_app_narration_NarrationCpuBudget_nativeCurrentTid(JNIEnv*, jobject) {
    return static_cast<jlong>(gettid());
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_learngraphicsprogramming_app_narration_NarrationCpuBudget_nativeAllowedCpus(JNIEnv* env, jobject) {
    cpu_set_t mask;
    if (!current_mask(env, mask)) return nullptr;
    return mask_array(env, mask);
}

extern "C" JNIEXPORT void JNICALL
Java_com_learngraphicsprogramming_app_narration_NarrationCpuBudget_nativeSetAllowedCpus(
    JNIEnv* env, jobject, jintArray cpus) {
    cpu_set_t mask;
    if (!requested_mask(env, cpus, mask)) return;
    // pid zero always targets only this calling thread. Never use the application pid here.
    if (sched_setaffinity(0, sizeof(mask), &mask) != 0) {
        const int error = errno;
        fail(env, "sched_setaffinity(current thread): " + std::string(std::strerror(error)));
    }
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_learngraphicsprogramming_app_narration_NarrationCpuBudget_nativeRestoreAllowedCpus(
    JNIEnv* env, jobject, jintArray cpus, jboolean recover_current_cpuset) {
    cpu_set_t requested;
    if (!requested_mask(env, cpus, requested)) return nullptr;
    if (sched_setaffinity(0, sizeof(requested), &requested) != 0) {
        const int error = errno;
        if (error != EINVAL || !recover_current_cpuset) {
            fail(env, "sched_setaffinity(restore current thread): " + std::string(std::strerror(error)));
            return nullptr;
        }
        // The saved mask was valid when read with this same sizeof(cpu_set_t). EINVAL
        // now means it has no online CPU permitted by the thread's current cpuset.
        // Only after the outermost optional lease ends, relinquish our stale pinning.
        // Linux intersects this request with the current cpuset and online CPUs; this
        // does not modify that cpuset, another TID, or the process-wide CPU policy.
        cpu_set_t available;
        CPU_ZERO(&available);
        for (int cpu = 0; cpu < CPU_SETSIZE; ++cpu) CPU_SET(cpu, &available);
        if (sched_setaffinity(0, sizeof(available), &available) != 0) {
            const int restore_error = errno;
            fail(env, "sched_setaffinity(restore current cpuset): " + std::string(std::strerror(restore_error)));
            return nullptr;
        }
    }
    cpu_set_t actual;
    if (!current_mask(env, actual)) return nullptr;
    if (!recover_current_cpuset) {
        for (int cpu = 0; cpu < CPU_SETSIZE; ++cpu) {
            if (CPU_ISSET(cpu, &actual) && !CPU_ISSET(cpu, &requested)) {
                fail(env, "Android did not verify the restored current-thread CPU mask");
                return nullptr;
            }
        }
    }
    return mask_array(env, actual);
}
