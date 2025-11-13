#ifndef AUDX_AUDX_H
#define AUDX_AUDX_H

#include "model_loader.h"
#include <stdbool.h>
#include <stdint.h>

/* Forward declarations for rnnoise types */
#ifdef __cplusplus
extern "C" {
#endif
typedef struct DenoiseState DenoiseState;
typedef struct RNNModel RNNModel;
#ifdef __cplusplus
}
#endif

enum {
  AUDX_DENOISER_SUCCESS = 0,
  AUDX_DENOISER_ERROR_INVALID = -1,
  AUDX_DENOISER_ERROR_MEMORY = -2,
  AUDX_DENOISER_ERROR_MODEL = -3,
};

/**
 * @struct DenoiserConfig
 * @brief Configuration parameters for initializing and controlling the
 * denoiser.
 *
 * This structure defines model selection, file paths, and VAD (Voice Activity
 * Detection) parameters used during denoiser initialization.
 */
struct DenoiserConfig {
  /**
   * Preset model selection.
   *
   * Defines which built-in RNNoise model to use for denoising.
   * For example: MODEL_PRESET_DEFAULT, MODEL_PRESET_LIGHT, etc.
   */
  enum ModelPreset model_preset;

  /**
   * Optional path to a custom model file (.rnnn).
   *
   * If provided, this model will override the preset model.
   * Can be NULL to use the default built-in model.
   */
  const char *model_path;

  /**
   * Voice Activity Detection (VAD) threshold.
   *
   * Range: 0.0–1.0 (default: 0.5)
   *
   * Frames with VAD scores above this threshold are classified as speech.
   * Lower values make speech detection more sensitive (detects more speech),
   * while higher values make it stricter.
   */
  float vad_threshold;

  /**
   * Enable or disable VAD results in the denoiser output.
   *
   * If true, the DenoiserResult struct will include valid VAD scores and
   * speech flags. If false, VAD is disabled and results are not computed.
   */
  bool enable_vad_output;
};

/**
 * @struct DenoiserResult
 * @brief Holds per-frame output data from the denoiser.
 *
 * This structure contains information about the processed frame,
 * including VAD probability, speech detection flag, and frame size.
 */
struct DenoiserResult {
  /**
   * Probability of speech presence in the processed frame.
   *
   * Range: 0.0–1.0, where higher values indicate stronger confidence
   * that the frame contains speech.
   */
  float vad_probability;

  /**
   * Speech detection flag based on the configured VAD threshold.
   *
   * True if vad_probability >= vad_threshold, otherwise false.
   */
  bool is_speech;

  /**
   * Number of audio samples processed in this frame.
   *
   * For RNNoise, this is typically 480 samples (10 ms at 48 kHz).
   */
  int samples_processed;
};

/**
 * @struct DenoiserStats
 * @brief Holds runtime statistics and performance metrics of the denoiser.
 *
 * This structure is filled by get_denoiser_stats() and provides
 * information about the number of frames processed, speech activity
 * detection (VAD) results, and processing performance.
 */
struct DenoiserStats {
  /** Total number of audio frames processed since initialization */
  int frame_processed;

  /** Percentage of frames where speech was detected (0.0–100.0) */
  float speech_detected;

  /** Average VAD (Voice Activity Detection) score across all frames */
  float vscores_avg;

  /** Minimum VAD score observed */
  float vscores_min;

  /** Maximum VAD score observed */
  float vscores_max;

  /** Total processing time (in milliseconds) accumulated for all frames */
  float ptime_total;

  /** Average processing time per frame (in milliseconds) */
  float ptime_avg;

  /** Processing time (in milliseconds) for the last processed frame */
  float ptime_last;
};

/**
 * @struct Denoiser
 * @brief Optimized single-channel (mono) denoiser context.
 *
 * This version of the denoiser is designed for efficient, single-threaded
 * operation without worker threads. It maintains state for the RNNoise-based
 * denoiser, configuration parameters, runtime statistics, and error handling.
 */
struct Denoiser {

  /**
   * Number of audio channels.
   *
   * Always 1 in this optimized mono implementation.
   */
  int num_channels;

  /**
   * Voice Activity Detection (VAD) threshold.
   *
   * Range: 0.0–1.0 (default: 0.5)
   *
   * Determines whether a frame is classified as speech based on the
   * probability score returned by the VAD model.
   */
  float vad_threshold;

  /**
   * Enable or disable VAD output in processing results.
   *
   * If true, the denoiser computes and reports VAD probability and
   * speech detection flags in DenoiserResult.
   */
  bool enable_vad_output;

  /**
   * Internal RNNoise state for a single channel.
   *
   * This object maintains the model’s memory across frames.
   */
  DenoiseState *denoiser_state;

  /**
   * Pointer to the loaded RNNoise model.
   *
   * Can be NULL if using the embedded default model.
   */
  RNNModel *model;

  /**
   * Temporary buffer for one frame of floating-point audio samples.
   *
   * Length: FRAME_SIZE (e.g., 480 samples for 10 ms @ 48 kHz)
   */
  float *processing_buffer;

  /**
   * Total number of frames processed since initialization.
   */
  uint64_t frames_processed;

  /**
   * Number of frames classified as speech (based on VAD threshold).
   */
  uint64_t speech_frames;

  /**
   * Accumulated sum of VAD scores (used to compute averages).
   */
  float total_vad_score;

  /**
   * Minimum VAD score observed during processing.
   */
  float min_vad_score;

  /**
   * Maximum VAD score observed during processing.
   */
  float max_vad_score;

  /**
   * Total processing time across all frames (in milliseconds).
   */
  double total_processing_time;

  /**
   * Processing time for the last processed frame (in milliseconds).
   */
  double last_frame_time;

  /**
   * Internal error message buffer (null-terminated string).
   *
   * Used to store the most recent error or diagnostic message.
   */
  char error_buffer[256];
};

/**
 * @brief Create denoiser instance
 *
 * NOTE: This optimized version only supports mono audio (num_channels=1)
 *
 * @param config    Configuration (must not be NULL)
 * @param denoiser  denoiser to identify
 *
 * @return 0 on success, negative error code on failure
 */
int denoiser_create(const struct DenoiserConfig *config,
                    struct Denoiser *denoiser);

/**
 * @brief Process audio samples
 *
 * REQUIREMENTS:
 * - input_pcm must be exactly 480 samples (mono only)
 * - Audio must be 48kHz, 16-bit signed PCM
 * - Optimized for real-time processing with minimal overhead
 *
 * THREAD SAFETY:
 * - This function must be called from a single thread only
 * - No internal worker threads (simplified for mono)
 * - Statistics (frames_processed, etc.) are NOT thread-safe
 * - Do NOT call this function concurrently from multiple threads
 *
 * @param denoiser      Denoiser context
 * @param input_pcm     Input samples (480 samples)
 * @param output_pcm    output samples (same size as input)
 * @param result        Result info (Optional, can be NULL)
 *
 * @return 0 on success, negative error code on failure
 */
int denoiser_process(struct Denoiser *denoiser, const int16_t *input_pcm,
                     int16_t *output_pcm, struct DenoiserResult *result);

/**
 * @brief Destroy denoiser instance
 *
 * @param denoiser  Denoiser to destroy
 */
void denoiser_destroy(struct Denoiser *denoiser);

/**
 * @brief Get last error message
 *
 * @param denoiser  Denoiser to track
 *
 * @return Error string or NULL
 */
const char *get_denoiser_error(struct Denoiser *denoiser);

/**
 * @brief Retrieve runtime statistics from the denoiser instance.
 *
 * Populates the provided @ref DenoiserStats structure with metrics
 * about processed frames, VAD (Voice Activity Detection) scores, and
 * performance timing information.
 *
 * @param denoiser Pointer to an initialized Denoiser instance.
 * @param stats    Pointer to a DenoiserStats struct to be filled with results.
 *
 * @return
 * - AUDX_DENOISER_SUCCESS (0) on success
 * - AUDX_DENOISER_ERROR_INVALID (-1) if @p denoiser or @p stats is NULL
 *
 * @note This function does not allocate memory. The @p stats pointer must
 *       point to valid writable memory provided by the caller.
 *
 * @see DenoiserStats
 */
int get_denoiser_stats(struct Denoiser *denoiser, struct DenoiserStats *stats);

/**
 * @brief Get RNNoiser version
 *
 * @return version string
 */
const char *denoiser_version(void);

#endif // AUDX_AUDX_H
