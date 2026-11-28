// The Arm generic timer's frequency (CNTFRQ_EL0, readable from user space): FEX's profile stats count in its ticks.
#include <jni.h>
#include <stdint.h>

JNIEXPORT jlong JNICALL
Java_com_droiddeck_launcher_session_FexStatsLog_nativeTimerHz(JNIEnv *env, jclass clazz) {
    (void)env; (void)clazz;
#if defined(__aarch64__)
    uint64_t hz;
    __asm__ volatile("mrs %0, cntfrq_el0" : "=r"(hz));
    return (jlong)hz;
#else
    return 0;
#endif
}
