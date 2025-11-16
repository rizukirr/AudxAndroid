#include <android/log.h>
#include <jni.h>
#include <string>

extern "C" {
#include "audx/common.h"
#include "audx/denoiser.h"
#include "audx/resample.h"
}

#define LOG_TAG "DenoiserJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/**
 * Resampler context struct to hold resampling state
 */
struct ResamplerContext {
  int input_rate;
  int output_rate;
  int quality;
  bool needs_resampling;
  int input_frame_samples;
  int output_frame_samples;
  AudxResampler upsampler;   // Persistent upsampler (input_rate -> 48kHz)
  AudxResampler downsampler; // Persistent downsampler (48kHz -> input_rate)
};

/**
 * Combined native handle containing both denoiser and resampler context
 */
struct NativeHandle {
  Denoiser *denoiser;
  ResamplerContext *resampler_ctx;
};

extern "C" JNIEXPORT jlong JNICALL
Java_com_android_audx_AudxDenoiser_createNative(
    JNIEnv *env, jobject /* this */, jint modelPreset, jstring modelPath,
    jfloat vadThreshold, jboolean statsEnabled, jint inputSampleRate,
    jint resampleQuality) {

  struct DenoiserConfig config{};
  config.model_preset = static_cast<ModelPreset>(modelPreset);

  const char *model_path_str = nullptr;
  if (modelPath != nullptr) {
    model_path_str = env->GetStringUTFChars(modelPath, nullptr);
  }
  config.model_path = model_path_str;
  config.vad_threshold = vadThreshold;
  config.stats_enabled = statsEnabled;

  auto *denoiser = new Denoiser();

  int ret = denoiser_create(&config, denoiser);
  if (ret != AUDX_SUCCESS) {
    delete denoiser;
    return 0;
  }

  if (model_path_str != nullptr) {
    env->ReleaseStringUTFChars(modelPath, model_path_str);
  }

  if (ret < 0) {
    LOGE("Failed to create denoiser: %d", ret);
    delete denoiser;
    return 0;
  }

  // Create resampler context
  auto *resampler_ctx = new ResamplerContext();
  resampler_ctx->input_rate = inputSampleRate;
  resampler_ctx->output_rate = AUDX_DEFAULT_SAMPLE_RATE;
  resampler_ctx->quality = resampleQuality;
  resampler_ctx->needs_resampling =
      (inputSampleRate != AUDX_DEFAULT_SAMPLE_RATE);
  resampler_ctx->upsampler = nullptr;
  resampler_ctx->downsampler = nullptr;

  // Calculate frame sizes for 10ms chunks
  resampler_ctx->input_frame_samples = get_frame_samples(inputSampleRate);
  resampler_ctx->output_frame_samples = AUDX_DEFAULT_FRAME_SIZE;

  // Create persistent resamplers if needed
  if (resampler_ctx->needs_resampling) {
    int err;
    resampler_ctx->upsampler = audx_resample_create(
        1, inputSampleRate, AUDX_DEFAULT_SAMPLE_RATE, resampleQuality, &err);
    resampler_ctx->downsampler = audx_resample_create(
        1, AUDX_DEFAULT_SAMPLE_RATE, inputSampleRate, resampleQuality, &err);

    if (!resampler_ctx->upsampler || !resampler_ctx->downsampler) {
      LOGE("Failed to create persistent resamplers");
      audx_resample_destroy(resampler_ctx->upsampler);
      audx_resample_destroy(resampler_ctx->downsampler);
      delete resampler_ctx;
      denoiser_destroy(denoiser);
      delete denoiser;
      return 0;
    }
  }

  LOGI("Denoiser created with input_rate=%d, needs_resampling=%d, quality=%d",
       inputSampleRate, resampler_ctx->needs_resampling, resampleQuality);

  // Create combined handle
  auto *handle = new NativeHandle();
  handle->denoiser = denoiser;
  handle->resampler_ctx = resampler_ctx;

  return reinterpret_cast<jlong>(handle);
}

extern "C" JNIEXPORT void JNICALL
Java_com_android_audx_AudxDenoiser_destroyNative(JNIEnv *env,
                                                 jobject /* this */,
                                                 jlong handle) {

  auto *native_handle = reinterpret_cast<NativeHandle *>(handle);
  if (native_handle != nullptr) {
    if (native_handle->denoiser != nullptr) {
      denoiser_destroy(native_handle->denoiser);
      delete native_handle->denoiser;
    }

    if (native_handle->resampler_ctx != nullptr) {
      audx_resample_destroy(native_handle->resampler_ctx->upsampler);
      audx_resample_destroy(native_handle->resampler_ctx->downsampler);
      delete native_handle->resampler_ctx;
    }

    delete native_handle;
    LOGI("Denoiser and resampler destroyed");
  }
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_android_audx_AudxDenoiser_processNative(JNIEnv *env,
                                                 jobject /* this */,
                                                 jlong handle,
                                                 jshortArray inputArray,
                                                 jshortArray outputArray) {

  auto *native_handle = reinterpret_cast<NativeHandle *>(handle);
  if (native_handle == nullptr || native_handle->denoiser == nullptr) {
    LOGE("Invalid native handle");
    return nullptr;
  }

  Denoiser *denoiser = native_handle->denoiser;
  ResamplerContext *resampler_ctx = native_handle->resampler_ctx;

  // Get array pointers
  auto *input = static_cast<jshort *>(
      env->GetPrimitiveArrayCritical(inputArray, nullptr));
  auto *output = static_cast<jshort *>(
      env->GetPrimitiveArrayCritical(outputArray, nullptr));

  int ret;
  struct DenoiserResult result{};

  if (resampler_ctx->needs_resampling) {
    // Allocate temporary buffers for resampling
    auto *resampled_input = (audx_int16_t *)malloc(
        resampler_ctx->output_frame_samples * sizeof(audx_int16_t));
    auto *resampled_output = (audx_int16_t *)malloc(
        resampler_ctx->output_frame_samples * sizeof(audx_int16_t));

    if (!resampled_input || !resampled_output) {
      LOGE("Memory allocation failed for resample buffers");
      free(resampled_input);
      free(resampled_output);
      env->ReleasePrimitiveArrayCritical(inputArray, input, JNI_ABORT);
      env->ReleasePrimitiveArrayCritical(outputArray, output, JNI_ABORT);
      return nullptr;
    }

    // Resample input to 48kHz using persistent upsampler
    audx_uint32_t in_len = resampler_ctx->input_frame_samples;
    audx_uint32_t out_len = resampler_ctx->output_frame_samples;

    ret = audx_resample_process(resampler_ctx->upsampler, input, &in_len,
                                resampled_input, &out_len);

    if (ret != AUDX_SUCCESS) {
      LOGE("Input resampling failed: %d", ret);
      free(resampled_input);
      free(resampled_output);
      env->ReleasePrimitiveArrayCritical(inputArray, input, JNI_ABORT);
      env->ReleasePrimitiveArrayCritical(outputArray, output, JNI_ABORT);
      return nullptr;
    }

    // Denoise at 48kHz
    ret =
        denoiser_process(denoiser, resampled_input, resampled_output, &result);

    if (ret != AUDX_SUCCESS) {
      LOGE("Denoiser processing failed: %d", ret);
      free(resampled_input);
      free(resampled_output);
      env->ReleasePrimitiveArrayCritical(inputArray, input, JNI_ABORT);
      env->ReleasePrimitiveArrayCritical(outputArray, output, JNI_ABORT);
      return nullptr;
    }

    // Resample output back to original rate using persistent downsampler
    in_len = resampler_ctx->output_frame_samples;
    out_len = resampler_ctx->input_frame_samples;
    ret = audx_resample_process(resampler_ctx->downsampler, resampled_output,
                                &in_len, (int16_t *)output, &out_len);

    free(resampled_input);
    free(resampled_output);

    if (ret != AUDX_SUCCESS) {
      LOGE("Output resampling failed: %d", ret);
      env->ReleasePrimitiveArrayCritical(inputArray, input, JNI_ABORT);
      env->ReleasePrimitiveArrayCritical(outputArray, output, JNI_ABORT);
      return nullptr;
    }

    // Update result to reflect actual output samples
    result.samples_processed = (int)out_len;
  } else {
    // No resampling needed, process directly
    ret = denoiser_process(denoiser, input, output, &result);

    if (ret != AUDX_SUCCESS) {
      LOGE("Denoiser processing failed: %d", ret);
      env->ReleasePrimitiveArrayCritical(inputArray, input, JNI_ABORT);
      env->ReleasePrimitiveArrayCritical(outputArray, output, JNI_ABORT);
      return nullptr;
    }
  }

  // Release arrays
  env->ReleasePrimitiveArrayCritical(inputArray, input, JNI_ABORT);
  env->ReleasePrimitiveArrayCritical(outputArray, output, 0);

  // Find Kotlin class
  jclass resultClass = env->FindClass("com/android/audx/DenoiserResult");
  if (resultClass == nullptr) {
    LOGE("Cannot find DenoiserResult class");
    return nullptr;
  }

  // Find constructor: (FFI)V — 2 floats + int
  jmethodID ctor = env->GetMethodID(resultClass, "<init>", "(FZI)V");
  if (ctor == nullptr) {
    LOGE("Cannot find DenoiserResult constructor");
    return nullptr;
  }

  // Create and return Kotlin object
  jobject resultObj =
      env->NewObject(resultClass, ctor, result.vad_probability,
                     result.is_speech, result.samples_processed);

  return resultObj;
}

// Expose native audio format constants to Kotlin
extern "C" JNIEXPORT jint JNICALL
Java_com_android_audx_AudxDenoiser_getSampleRateNative(JNIEnv *env,
                                                       jclass /* clazz */) {
  return AUDX_DEFAULT_SAMPLE_RATE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_android_audx_AudxDenoiser_getChannelsNative(JNIEnv *env,
                                                     jclass /* clazz */) {
  return AUDX_DEFAULT_CHANNELS;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_android_audx_AudxDenoiser_getBitDepthNative(JNIEnv *env,
                                                     jclass /* clazz */) {
  return AUDX_DEFAULT_BIT_DEPTH;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_android_audx_AudxDenoiser_getFrameSizeNative(JNIEnv *env,
                                                      jclass /* clazz */) {
  return AUDX_DEFAULT_FRAME_SIZE;
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_android_audx_AudxDenoiser_getStatsNative(JNIEnv *env,
                                                  jobject /* this */,
                                                  jlong handle) {

  auto *native_handle = reinterpret_cast<NativeHandle *>(handle);
  if (native_handle == nullptr || native_handle->denoiser == nullptr) {
    LOGE("Invalid native handle");
    return nullptr;
  }

  struct DenoiserStats stats{};
  int ret = get_denoiser_stats(native_handle->denoiser, &stats);

  if (ret != AUDX_SUCCESS) {
    LOGE("Failed to get denoiser stats: %d", ret);
    return nullptr;
  }

  // Find Kotlin class
  jclass statsClass = env->FindClass("com/android/audx/DenoiserStats");
  if (statsClass == nullptr) {
    LOGE("Cannot find DenoiserStats class");
    return nullptr;
  }

  // Find constructor: (IFFFFFFF)V — int + 7 floats
  jmethodID ctor = env->GetMethodID(statsClass, "<init>", "(IFFFFFFF)V");
  if (ctor == nullptr) {
    LOGE("Cannot find DenoiserStats constructor");
    return nullptr;
  }

  // Create and return Kotlin object
  jobject statsObj = env->NewObject(
      statsClass, ctor, stats.frame_processed, stats.speech_detected,
      stats.vscores_avg, stats.vscores_min, stats.vscores_max,
      stats.ptime_total, stats.ptime_avg, stats.ptime_last);

  return statsObj;
}

extern "C" JNIEXPORT void JNICALL
Java_com_android_audx_AudxDenoiser_resetStatsNative(JNIEnv *env,
                                                    jobject /* this */,
                                                    jlong handle) {

  auto *native_handle = reinterpret_cast<NativeHandle *>(handle);
  if (native_handle == nullptr || native_handle->denoiser == nullptr) {
    LOGE("Invalid native handle");
    return;
  }

  Denoiser *denoiser = native_handle->denoiser;

  // Manually reset all statistics fields
  denoiser->frames_processed = 0;
  denoiser->speech_frames = 0;
  denoiser->total_vad_score = 0.0f;
  denoiser->min_vad_score = 1.0f; // Reset to max so first frame sets new min
  denoiser->max_vad_score = 0.0f; // Reset to min so first frame sets new max
  denoiser->total_processing_time = 0.0;
  denoiser->last_frame_time = 0.0;

  LOGI("Denoiser statistics reset");
}
