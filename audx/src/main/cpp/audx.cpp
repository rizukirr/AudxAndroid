#include "audx.h"
#include <jni.h>

extern "C" JNIEXPORT jlong JNICALL Java_com_audx_android_Audx_denoiseCreateJNI(
    JNIEnv *env, jobject /*this */, jint in_rate, jint resample_quality) {
  AudxState *st = audx_create(nullptr, in_rate, resample_quality);
  if (!st)
    return -1;

  return reinterpret_cast<jlong>(st);
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_audx_android_Audx_denoiseProcessJNI(JNIEnv *env, jobject /* this */,
                                             jlong ptr, jshortArray in,
                                             jshortArray out) {
  auto *st = reinterpret_cast<AudxState *>(ptr);
  if (!st) {
    return -1.0f;
  }

  jshort *input_ptr = env->GetShortArrayElements(in, nullptr);
  jshort *output_ptr = env->GetShortArrayElements(out, nullptr);

  float result = audx_process_int(st, input_ptr, output_ptr);

  env->ReleaseShortArrayElements(in, input_ptr, JNI_ABORT);
  env->ReleaseShortArrayElements(out, output_ptr, 0);

  return result;
}

extern "C" JNIEXPORT void JNICALL Java_com_audx_android_Audx_denoiseDestroyJNI(
    JNIEnv *env, jobject /* this */, jlong ptr) {
  auto *st = reinterpret_cast<AudxState *>(ptr); // FIX: Use AudxCtx*
  if (!st)
    return;

  audx_destroy(st); // Destroy the state
}
