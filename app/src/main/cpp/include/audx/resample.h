#ifndef AUDX_RESAMPLER_H
#define AUDX_RESAMPLER_H

#define audx_int16_t short
#define audx_int32_t int
#define audx_uint16_t unsigned short
#define audx_uint32_t unsigned int

#include <speex/speex_resampler.h>

#ifdef __cplusplus
extern "C" {
#endif

#define AUDX_RESAMPLER_QUALITY_MAX 10
#define AUDX_RESAMPLER_QUALITY_MIN 0
#define AUDX_RESAMPLER_QUALITY_DEFAULT 4
#define AUDX_RESAMPLER_QUALITY_VOIP 3

enum {
  AUDX_RESAMPLER_SUCCESS = 0,
  AUDX_RESAMPLER_ERROR_INVALID = -1,
  AUDX_RESAMPLER_ERROR_MEMORY = -2,
  AUDX_RESAMPLER_ERROR_SPEEX = -3,
};

struct AudxResamplerState {
  audx_uint32_t nb_channels;
  audx_uint32_t input_sample_rate;
  audx_uint32_t output_sample_rate;
  int quality;
  const float *input;
  audx_uint32_t input_len;
  float *output;
  audx_uint32_t output_len;
};

/**
 * @brief Initializes and process a new Audx resampler instance with integer
 * input and output rates.
 *
 * This function creates and configures a Speex resampler to convert audio data
 * between two integer sampling rates (e.g., 44100 Hz → 48000 Hz). It supports
 * mono or multi-channel audio processing depending on the specified channel
 * count.
 *
 * @param nb_channels         Number of audio channels to process (use 1 for
 * mono).
 * @param input_sample_rate   Input sampling rate in Hz (integer value).
 * @param output_sample_rate  Output sampling rate in Hz (integer value).
 * @param quality             Resampling quality level between 0 and 10,
 *                            where 0 provides the fastest but lowest quality,
 *                            and 10 provides the highest quality at the cost of
 * CPU usage.
 * @param err                 Pointer to an integer that receives an error code,
 *                            or NULL if not needed.
 * @param state               Pointer to a SpeexResamplerState that will be
 * initialized on success. Must be freed later with the corresponding Speex
 * resampler destroy function.
 *
 * @return 0 on success, or a negative value if initialization fails.
 */
int audx_resampler(struct AudxResamplerState *state);

#ifdef __cplusplus
}
#endif

#endif // AUDX_RESAMPLER_H
