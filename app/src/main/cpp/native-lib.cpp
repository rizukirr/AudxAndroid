#include <jni.h>
#include <string>
#include <android/log.h>

extern "C" {
#include "include/audx/denoiser.h"
}

#define LOG_TAG "DenoiserJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" JNIEXPORT jlong JNICALL
Java_com_android_audx_AudxDenoiser_createNative(
        JNIEnv *env,
        jobject /* this */,
        jint modelPreset,
        jstring modelPath,
        jfloat vadThreshold,
        jboolean enableVadOutput) {

    struct DenoiserConfig config{};
    config.model_preset = static_cast<ModelPreset>(modelPreset);

    const char *model_path_str = nullptr;
    if (modelPath != nullptr) {
        model_path_str = env->GetStringUTFChars(modelPath, nullptr);
    }
    config.model_path = model_path_str;
    config.vad_threshold = vadThreshold;
    config.enable_vad_output = enableVadOutput;

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

    LOGI("Denoiser created successfully (channels: %d, model_preset: %d, vad_threshold: %.2f, enable_vad: %d)",
         1, modelPreset, vadThreshold, enableVadOutput);
    return reinterpret_cast<jlong>(denoiser);
}

extern "C" JNIEXPORT void JNICALL
Java_com_android_audx_AudxDenoiser_destroyNative(
        JNIEnv *env,
        jobject /* this */,
        jlong handle) {

    auto *denoiser = reinterpret_cast<Denoiser *>(handle);
    if (denoiser != nullptr) {
        denoiser_destroy(denoiser);
        delete denoiser;
        LOGI("Denoiser destroyed");
    }
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_android_audx_AudxDenoiser_processNative(
        JNIEnv *env,
        jobject /* this */,
        jlong handle,
        jshortArray inputArray,
        jshortArray outputArray) {

    auto *denoiser = reinterpret_cast<Denoiser *>(handle);
    if (denoiser == nullptr) {
        LOGE("Invalid denoiser handle");
        return nullptr;
    }

    // Get array pointers
    jshort *input = env->GetShortArrayElements(inputArray, nullptr);
    jshort *output = env->GetShortArrayElements(outputArray, nullptr);

    // Debug: Log first few input samples
    LOGI("First 5 input samples: %d, %d, %d, %d, %d",
         input[0], input[1], input[2], input[3], input[4]);

    struct DenoiserResult result{};
    int ret = denoiser_process(denoiser, input, output, &result);

    // Debug: Log first few output samples
    LOGI("First 5 output samples: %d, %d, %d, %d, %d",
         output[0], output[1], output[2], output[3], output[4]);
    LOGI("Return code: %d, VAD: %.3f, samples: %d",
         ret, result.vad_probability, result.samples_processed);

    // Release arrays
    env->ReleaseShortArrayElements(inputArray, input, JNI_ABORT);
    env->ReleaseShortArrayElements(outputArray, output, 0);

    if (ret != AUDX_SUCCESS) {
        LOGE("Denoiser processing failed: %d", ret);
        return nullptr;
    }

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
    jobject resultObj = env->NewObject(
            resultClass,
            ctor,
            result.vad_probability,
            result.is_speech,
            result.samples_processed
    );

    return resultObj;
}

// Expose native audio format constants to Kotlin
extern "C" JNIEXPORT jint JNICALL
Java_com_android_audx_AudxDenoiser_getSampleRateNative(
        JNIEnv *env,
        jclass /* clazz */) {
    return AUDX_SAMPLE_RATE_48KHZ;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_android_audx_AudxDenoiser_getChannelsNative(
        JNIEnv *env,
        jclass /* clazz */) {
    return AUDX_CHANNELS_MONO;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_android_audx_AudxDenoiser_getBitDepthNative(
        JNIEnv *env,
        jclass /* clazz */) {
    return AUDX_BIT_DEPTH_16;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_android_audx_AudxDenoiser_getFrameSizeNative(
        JNIEnv *env,
        jclass /* clazz */) {
    return AUDX_FRAME_SIZE;
}
