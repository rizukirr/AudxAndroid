/**
 * @file native-lib.cpp
 * @brief JNI implementation for the AudxDenoiser streaming audio processor.
 *
 * This file provides the JNI bindings between the Kotlin AudxDenoiser class and the
 * underlying C++ audio processing pipeline. It implements a stateful streaming model
 * that handles arbitrary input sample rates by buffering and resampling audio to meet
 * the fixed-frame requirements of the RNNoise-based denoiser.
 */

#include <android/log.h>
#include <jni.h>
#include <string>
#include <vector>

extern "C" {
#include "audx/common.h"
#include "audx/denoiser.h"
#include "audx/resample.h"
}

#define LOG_TAG "DenoiserJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/**
 * @struct ResamplerContext
 * @brief Holds the state and configuration for the entire streaming and resampling pipeline.
 */
struct ResamplerContext {
    /// Flag indicating if resampling is necessary.
    bool needs_resampling;
    /// The number of samples at `input_rate` required to produce one full frame for the denoiser.
    int input_frame_samples;
    /// The fixed number of samples required by the denoiser (e.g., 480).
    int output_frame_samples;
    /// A stateful upsampler instance (input_rate -> output_rate).
    AudxResampler upsampler;
    /// A stateful downsampler instance (output_rate -> input_rate).
    AudxResampler downsampler;

    /// Buffer to accumulate incoming audio chunks from JNI calls.
    std::vector<int16_t> input_buffer;
    /// Buffer to accumulate processed audio chunks before returning to JNI.
    std::vector<int16_t> output_buffer;

    /// The VAD probability from the last processed 10ms frame.
    float last_vad_prob = 0.0f;
    /// The speech detection flag from the last processed 10ms frame.
    bool is_speech = false;
};

/**
 * @struct NativeHandle
 * @brief Encapsulates all native components required for a denoiser instance.
 */
struct NativeHandle {
    /// Pointer to the underlying denoiser engine instance.
    Denoiser *denoiser;
    /// Pointer to the context holding resamplers and streaming buffers.
    ResamplerContext *resampler_ctx;
};

// Forward declarations for internal helpers
int audx_process_stream(NativeHandle *handle);

jobject create_jni_result(JNIEnv *env, NativeHandle *handle);

/**
 * @brief JNI entry point to create and initialize a native denoiser instance.
 *
 * Called by the AudxDenoiser constructor. This function sets up the denoiser engine,
 * calculates the required frame sizes, and initializes the stateful resamplers and
 * streaming buffers.
 *
 * @return A long representing the pointer to the created NativeHandle, or 0 on failure.
 */
extern "C" JNIEXPORT jlong JNICALL
Java_com_android_audx_AudxDenoiser_createNative(
        JNIEnv *env, jobject /* this */, jstring modelPath,
        jfloat vadThreshold, jboolean statsEnabled, jint inputSampleRate,
        jint resampleQuality) {

    auto *config = new DenoiserConfig();
    if (modelPath != nullptr) {
        config->model_preset = static_cast<ModelPreset>(0);
    } else {
        config->model_preset = static_cast<ModelPreset>(1);
    }

    const char *model_path_str = nullptr;
    if (modelPath != nullptr) {
        model_path_str = env->GetStringUTFChars(modelPath, nullptr);
    }
    config->model_path = model_path_str;
    config->vad_threshold = vadThreshold;
    config->stats_enabled = statsEnabled;

    auto *denoiser = new Denoiser();
    if (denoiser_create(config, denoiser) != AUDX_SUCCESS) {
        LOGE("Failed to create denoiser");
        delete config;
        delete denoiser;
        if (model_path_str) env->ReleaseStringUTFChars(modelPath, model_path_str);
        return 0;
    }

    if (model_path_str) env->ReleaseStringUTFChars(modelPath, model_path_str);
    delete config;

    auto *resampler_ctx = new ResamplerContext();
    resampler_ctx->needs_resampling = (inputSampleRate != AUDX_DEFAULT_SAMPLE_RATE);
    resampler_ctx->output_frame_samples = AUDX_DEFAULT_FRAME_SIZE; // 480

    if (resampler_ctx->needs_resampling) {
        resampler_ctx->input_frame_samples = (int) ((double) AUDX_DEFAULT_FRAME_SIZE *
                                                    (double) inputSampleRate /
                                                    (double) AUDX_DEFAULT_SAMPLE_RATE);
        int err;
        resampler_ctx->upsampler = audx_resample_create(1, inputSampleRate,
                                                        AUDX_DEFAULT_SAMPLE_RATE, resampleQuality,
                                                        &err);
        resampler_ctx->downsampler = audx_resample_create(1, AUDX_DEFAULT_SAMPLE_RATE,
                                                          inputSampleRate, resampleQuality, &err);
        if (!resampler_ctx->upsampler || !resampler_ctx->downsampler) {
            // Handle error
            return 0;
        }
    } else {
        resampler_ctx->input_frame_samples = AUDX_DEFAULT_FRAME_SIZE;
        resampler_ctx->upsampler = nullptr;
        resampler_ctx->downsampler = nullptr;
    }

    resampler_ctx->input_buffer.reserve(resampler_ctx->input_frame_samples * 2);
    resampler_ctx->output_buffer.reserve(resampler_ctx->input_frame_samples * 2);

    auto *handle = new NativeHandle{denoiser, resampler_ctx};
    return reinterpret_cast<jlong>(handle);
}

/**
 * @brief JNI entry point to destroy the native denoiser instance and release resources.
 */
extern "C" JNIEXPORT void JNICALL
Java_com_android_audx_AudxDenoiser_destroyNative(JNIEnv *env, jobject, jlong handle) {
    auto *native_handle = reinterpret_cast<NativeHandle *>(handle);
    if (!native_handle) return;
    if (native_handle->denoiser) {
        denoiser_destroy(native_handle->denoiser);
        delete native_handle->denoiser;
    }
    if (native_handle->resampler_ctx) {
        audx_resample_destroy(native_handle->resampler_ctx->upsampler);
        audx_resample_destroy(native_handle->resampler_ctx->downsampler);
        delete native_handle->resampler_ctx;
    }
    delete native_handle;
    LOGI("Denoiser and resampler destroyed");
}

/**
 * @brief JNI entry point for processing a chunk of audio.
 *
 * This function takes an arbitrary-sized chunk of audio, adds it to the internal
 * input buffer, processes as many full frames as possible, and returns the
 * resulting denoised audio and VAD statistics.
 *
 * @param handle The native handle pointer.
 * @param inputArray The incoming raw audio chunk.
 * @return A DenoiseStreamResult object containing the processed audio and VAD stats.
 */
extern "C" JNIEXPORT jobject JNICALL
Java_com_android_audx_AudxDenoiser_processNative(JNIEnv *env, jobject, jlong handle,
                                                 jshortArray inputArray) {
    auto *native_handle = reinterpret_cast<NativeHandle *>(handle);
    if (!native_handle || !native_handle->resampler_ctx) return nullptr;

    jsize input_len = env->GetArrayLength(inputArray);
    jshort *input_ptr = env->GetShortArrayElements(inputArray, nullptr);

    // 1. Feed new audio into the internal input buffer.
    native_handle->resampler_ctx->input_buffer.insert(
            native_handle->resampler_ctx->input_buffer.end(), input_ptr, input_ptr + input_len);
    env->ReleaseShortArrayElements(inputArray, input_ptr, JNI_ABORT);

    // 2. Process all available full frames from the input buffer.
    audx_process_stream(native_handle);

    // 3. Package the results and return to Kotlin.
    return create_jni_result(env, native_handle);
}

/**
 * @brief JNI entry point to flush remaining audio at the end of a stream.
 *
 * This function processes any audio left in the input buffer by padding it with
 * silence to form a final frame.
 *
 * @return A DenoiseStreamResult object containing the final processed audio.
 */
extern "C" JNIEXPORT jobject JNICALL
Java_com_android_audx_AudxDenoiser_flushNative(JNIEnv *env, jobject, jlong handle) {
    auto *native_handle = reinterpret_cast<NativeHandle *>(handle);
    if (!native_handle || !native_handle->resampler_ctx) return nullptr;

    ResamplerContext *ctx = native_handle->resampler_ctx;
    // Add silent padding to ensure the last partial frame gets processed.
    std::vector<int16_t> padding(ctx->input_frame_samples, 0);
    ctx->input_buffer.insert(ctx->input_buffer.end(), padding.begin(), padding.end());

    audx_process_stream(native_handle);

    jobject result = create_jni_result(env, native_handle);
    ctx->input_buffer.clear(); // Clear any remaining padding.

    return result;
}

/**
 * @brief Processes audio from the input buffer in a loop.
 *
 * This is the core of the streaming engine. It continuously consumes chunks from the
 * `input_buffer` that are large enough to form a full frame for the denoiser,
 * processes them, and places the result in the `output_buffer`.
 *
 * @param handle The native handle containing the denoiser and resampler contexts.
 * @return The number of frames processed in this call.
 */
int audx_process_stream(NativeHandle *handle) {
    ResamplerContext *ctx = handle->resampler_ctx;
    Denoiser *denoiser = handle->denoiser;
    int frames_processed = 0;

    // Loop as long as there's enough data in the input buffer for at least one full frame.
    while (ctx->input_buffer.size() >= ctx->input_frame_samples) {
        // 1. Consume one frame's worth of samples from the input buffer.
        std::vector<int16_t> input_frame(ctx->input_buffer.begin(),
                                         ctx->input_buffer.begin() + ctx->input_frame_samples);
        ctx->input_buffer.erase(ctx->input_buffer.begin(),
                                ctx->input_buffer.begin() + ctx->input_frame_samples);

        DenoiserResult frame_result{};

        if (ctx->needs_resampling) {
            // Resampling Path
            std::vector<int16_t> upsampled_buffer(ctx->output_frame_samples);
            std::vector<int16_t> denoised_buffer(ctx->output_frame_samples);

            // 2. Upsample to the denoiser's required sample rate (e.g., 48kHz).
            audx_uint32_t in_len = input_frame.size();
            audx_uint32_t out_len = upsampled_buffer.size();
            audx_resample_process(ctx->upsampler, input_frame.data(), &in_len,
                                  upsampled_buffer.data(), &out_len);
            upsampled_buffer.resize(out_len);

            // The resampler might not produce exactly the required number of samples on every call.
            // Pad with silence if necessary to meet the denoiser's strict frame size requirement.
            if (upsampled_buffer.size() < ctx->output_frame_samples) {
                upsampled_buffer.resize(ctx->output_frame_samples, 0);
            }

            // 3. Denoise the 48kHz frame.
            denoiser_process(denoiser, upsampled_buffer.data(), denoised_buffer.data(),
                             &frame_result);

            // 4. Downsample the clean audio back to the original input rate.
            in_len = denoised_buffer.size();
            out_len = ctx->input_frame_samples * 2; // Provide a larger buffer to be safe.
            std::vector<int16_t> downsampled_buffer(out_len);
            audx_resample_process(ctx->downsampler, denoised_buffer.data(), &in_len,
                                  downsampled_buffer.data(), &out_len);

            // 5. Append the result to the main output buffer.
            ctx->output_buffer.insert(ctx->output_buffer.end(), downsampled_buffer.begin(),
                                      downsampled_buffer.begin() + out_len);
        } else {
            // Non-resampling path (input is already at 48kHz).
            std::vector<int16_t> denoised_buffer(ctx->input_frame_samples);
            denoiser_process(denoiser, input_frame.data(), denoised_buffer.data(), &frame_result);
            ctx->output_buffer.insert(ctx->output_buffer.end(), denoised_buffer.begin(),
                                      denoised_buffer.end());
        }

        // Store the VAD result of this frame, overwriting the previous one.
        ctx->last_vad_prob = frame_result.vad_probability;
        ctx->is_speech = frame_result.is_speech;
        frames_processed++;
    }
    return frames_processed;
}

/**
 * @brief Creates a Java DenoiseStreamResult object from the native context.
 *
 * This helper function handles the JNI boilerplate for finding the Kotlin class
 * and its constructor, creating the Java short array for the audio, and constructing
 * the final result object.
 *
 * @param env The JNI environment pointer.
 * @param handle The native handle.
 * @return A new jobject of type DenoiseStreamResult, or nullptr on failure.
 */
jobject create_jni_result(JNIEnv *env, NativeHandle *handle) {
    ResamplerContext *ctx = handle->resampler_ctx;

    // Create jshortArray for the audio from the output buffer.
    jshortArray audioArray = env->NewShortArray(ctx->output_buffer.size());
    if (audioArray == nullptr) return nullptr;
    env->SetShortArrayRegion(audioArray, 0, ctx->output_buffer.size(), ctx->output_buffer.data());
    ctx->output_buffer.clear(); // Clear the buffer for the next call.

    // Find the DenoiseStreamResult class and its constructor.
    jclass resultClass = env->FindClass("com/android/audx/DenoiseStreamResult");
    if (resultClass == nullptr) {
        LOGE("Cannot find DenoiseStreamResult class");
        return nullptr;
    }
    // Constructor signature: ([SFZ)V -> (short[], float, boolean) -> void
    jmethodID ctor = env->GetMethodID(resultClass, "<init>", "([SFZ)V");
    if (ctor == nullptr) {
        LOGE("Cannot find DenoiseStreamResult constructor");
        return nullptr;
    }

    // Create and return the result object.
    return env->NewObject(resultClass, ctor, audioArray, ctx->last_vad_prob,
                          (jboolean) ctx->is_speech);
}

/**
 * @brief Retrieve runtime statistics from the denoiser instance.
 *
 * Populates the provided @ref DenoiserStats structure with metrics
 * about processed frames, VAD (Voice Activity Detection) scores, and
 * performance timing information.
 *
 * @param env The JNI environment pointer.
 * @param handle The native handle containing Denoiser context.
 *
 * @return A new jobject of type DenosierStatsResult, or nullptr on failure.
 */
extern "C" JNIEXPORT jobject JNICALL
Java_com_android_audx_AudxDenoiser_getStatsNative(JNIEnv *env, jobject /*this*/, jlong handle) {
    auto *native_handle = reinterpret_cast<NativeHandle *>(handle);
    if (!native_handle || !native_handle->denoiser) return 0;

    struct DenoiserStats stats;
    memset(&stats, 0, sizeof(stats));
    int ret = get_denoiser_stats(native_handle->denoiser, &stats);
    if (ret != AUDX_SUCCESS) {
        LOGE("Failed to get denoiser stats");
        return nullptr;
    }

    // Find the DenoiseStreamResult class and its constructor.
    jclass resultClass = env->FindClass("com/android/audx/DenoiserStatsResult");
    if (resultClass == nullptr) {
        LOGE("Cannot find DenoiseStreamResult class");
        return nullptr;
    }
    // Constructor signature: ([SFZ)V -> (short[], float, boolean) -> void
    jmethodID ctor = env->GetMethodID(resultClass, "<init>", "(IFFFFFFF)V");
    if (ctor == nullptr) {
        LOGE("Cannot find DenoiseStreamResult constructor");
        return nullptr;
    }

    return env->NewObject(resultClass, ctor, stats.frame_processed, stats.speech_detected,
                          stats.vscores_avg, stats.vscores_min, stats.vscores_max,
                          stats.ptime_total, stats.ptime_avg, stats.ptime_last);
}

/**
 * @brief Resets all collected runtime statistics for the denoiser instance.
 *
 * This function calls the native C function to reset all statistics fields
 * within the Denoiser struct to their initial, zeroed-out state.
 *
 * @param env The JNI environment pointer.
 * @param handle The native handle containing Denoiser context.
 */
extern "C" JNIEXPORT void JNICALL
Java_com_android_audx_AudxDenoiser_cleanStatsNative(JNIEnv *env, jobject /*this*/, jlong handle) {
    auto *native_handle = reinterpret_cast<NativeHandle *>(handle);
    if (!native_handle || !native_handle->denoiser) return;

    // Reset all stats fields in the Denoiser struct
    Denoiser *denoiser = native_handle->denoiser;
    denoiser->frames_processed = 0;
    denoiser->speech_frames = 0;
    denoiser->total_vad_score = 0.0f;
    denoiser->min_vad_score = 1.0f;  // Reset to max possible value
    denoiser->max_vad_score = 0.0f;  // Reset to min possible value
    denoiser->total_processing_time = 0.0;
    denoiser->last_frame_time = 0.0;
}

// Expose native audio format constants to Kotlin
extern "C" JNIEXPORT jint JNICALL
Java_com_android_audx_AudxDenoiser_getSampleRateNative(JNIEnv *env, jclass /* clazz */) {
    return AUDX_DEFAULT_SAMPLE_RATE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_android_audx_AudxDenoiser_getChannelsNative(JNIEnv *env, jclass /* clazz */) {
    return AUDX_DEFAULT_CHANNELS;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_android_audx_AudxDenoiser_getBitDepthNative(JNIEnv *env, jclass /* clazz */) {
    return AUDX_DEFAULT_BIT_DEPTH;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_android_audx_AudxDenoiser_getFrameSizeNative(JNIEnv *env, jclass /* clazz */) {
    return AUDX_DEFAULT_FRAME_SIZE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_android_audx_AudxDenoiser_getResamplerQualityMaxNative(JNIEnv *env, jclass /* clazz */) {
    return AUDX_RESAMPLER_QUALITY_MAX;
}


extern "C" JNIEXPORT jint JNICALL
Java_com_android_audx_AudxDenoiser_getResamplerQualityMinNative(JNIEnv *env, jclass /* clazz */) {
    return AUDX_RESAMPLER_QUALITY_MIN;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_android_audx_AudxDenoiser_getResamplerQualityDefaultNative(JNIEnv *env,
                                                                    jclass /* clazz */) {
    return AUDX_RESAMPLER_QUALITY_DEFAULT;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_android_audx_AudxDenoiser_getResamplerQualityVoipNative(JNIEnv *env, jclass /* clazz */) {
    return AUDX_RESAMPLER_QUALITY_VOIP;
}