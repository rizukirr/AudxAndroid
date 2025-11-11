#ifndef AUDX_REALTIME_DENOISER_H
#define AUDX_REALTIME_DENOISER_H

#include "model_loader.h"
#include <stdbool.h>
#include <stdint.h>
#include <time.h>

/* Forward declarations for rnnoise types */
#ifdef __cplusplus
extern "C" {
#endif
typedef struct DenoiseState DenoiseState;
typedef struct RNNModel RNNModel;
#ifdef __cplusplus
}
#endif

#define FRAME_SIZE 480

#define REALTIME_DENOISER_SUCCESS 0
#define REALTIME_DENOISER_ERROR_INVALID -1
#define REALTIME_DENOISER_ERROR_MEMORY -2
#define REALTIME_DENOISER_ERROR_MODEL -3
#define REALTIME_DENOISER_ERROR_FORMAT -4

struct DenoiserConfig {
  int num_channels; // 1 = mono (stereo not supported in optimized version)
  enum ModelPreset model_preset; // Which model to use
  const char *model_path;        // Path to custom model, can be NULL
  float vad_threshold;           // 0.0-1.0 default 0.5
  bool enable_vad_output;        // Enable VAD in results
};

struct DenoiserResult {
  float vad_probability; // result of vad probability 0.0-1.0
  bool is_speech;        // above threshold?
  int samples_processed; // should be 480
};

struct DenoiserStats {
  int frame_processed;
  float speech_detected;
  float vscores_avg;
  float vscores_min;
  float vscores_max;
  float ptime_total;
  float ptime_avg;
  float ptime_last;
};

/**
 * @brief Optimized mono denoiser context (no worker threads)
 */
struct Denoiser {
  /* Configuration */
  int num_channels; // Always 1 in optimized version
  float vad_threshold;
  bool enable_vad_output;

  /* RNNoise state (single channel) */
  DenoiseState *denoiser_state;

  /* Loaded model (NULL for embedded) */
  RNNModel *model;

  /* Single processing buffer (frame-sized float array) */
  float *processing_buffer; // [FRAME_SIZE]

  /* Statistics */
  uint64_t frames_processed;
  uint64_t speech_frames;
  float total_vad_score;
  float min_vad_score;
  float max_vad_score;

  /* Timing info */
  double total_processing_time;
  double last_frame_time;

  /* Error handling */
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
 * @brief get statistics
 *
 * @param denoiser  Denoiser to track
 *
 * @return Statistics string (do not free)
 */
int get_denoiser_stats(struct Denoiser *denoiser, struct DenoiserStats *stats);

/**
 * @brief Get RNNoiser version
 *
 * @return version string
 */
const char *denoiser_version(void);

#endif // AUDX_REALTIME_DENOISER_H
