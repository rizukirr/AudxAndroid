#ifndef AUDX_H
#define AUDX_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* --- Utility Converters --- */
#define PCM_SCALE_FLOAT_MAX 32767.0f
#define PCM_SCALE_FLOAT_MIN -32768.0f

#ifdef HAS_X86_SIMD
// SSE4.1-optimized int16 to float conversion
static inline void pcm_int16_to_float(const short *input, float *output,
                                      int count) {
  int i = 0;
  // Process 8 samples at a time using SSE4.1
  for (; i <= count - 8; i += 8) {
    __m128i in16 = _mm_loadu_si128((__m128i *)&input[i]);
    // Unpack to 32-bit integers (lower 4 samples)
    __m128i lo32 = _mm_cvtepi16_epi32(in16);
    // Unpack to 32-bit integers (upper 4 samples)
    __m128i hi32 = _mm_cvtepi16_epi32(_mm_srli_si128(in16, 8));
    // Convert to float
    __m128 flo = _mm_cvtepi32_ps(lo32);
    __m128 fhi = _mm_cvtepi32_ps(hi32);
    // Store results
    _mm_storeu_ps(&output[i], flo);
    _mm_storeu_ps(&output[i + 4], fhi);
  }
  // Handle remaining samples (scalar fallback)
  for (; i < count; i++)
    output[i] = (float)input[i];
}

// SSE2-optimized float to int16 conversion with clamping
static inline void pcm_float_to_int16(const float *input, short *output,
                                      int count) {
  int i = 0;
  const __m128 max_val = _mm_set1_ps(PCM_SCALE_FLOAT_MAX);
  const __m128 min_val = _mm_set1_ps(PCM_SCALE_FLOAT_MIN);

  // Process 8 samples at a time using SSE2
  for (; i <= count - 8; i += 8) {
    // Load 8 floats
    __m128 flo = _mm_loadu_ps(&input[i]);
    __m128 fhi = _mm_loadu_ps(&input[i + 4]);
    // Clamp to valid range
    flo = _mm_min_ps(_mm_max_ps(flo, min_val), max_val);
    fhi = _mm_min_ps(_mm_max_ps(fhi, min_val), max_val);
    // Convert to 32-bit integers
    __m128i lo32 = _mm_cvtps_epi32(flo);
    __m128i hi32 = _mm_cvtps_epi32(fhi);
    // Pack to 16-bit integers
    __m128i packed = _mm_packs_epi32(lo32, hi32);
    // Store results
    _mm_storeu_si128((__m128i *)&output[i], packed);
  }
  // Handle remaining samples (scalar fallback)
  for (; i < count; i++) {
    float val = input[i];
    if (val > PCM_SCALE_FLOAT_MAX)
      val = PCM_SCALE_FLOAT_MAX;
    if (val < PCM_SCALE_FLOAT_MIN)
      val = PCM_SCALE_FLOAT_MIN;
    output[i] = (int16_t)val;
  }
}

#elif defined(HAS_ARM_NEON)
// ARM NEON-optimized int16 to float conversion
static inline void pcm_int16_to_float(const short *input, float *output,
                                      int count) {
  int i = 0;
  // Process 8 samples at a time using NEON
  for (; i <= count - 8; i += 8) {
    int16x8_t in16 = vld1q_s16(&input[i]);
    // Widen to 32-bit integers
    int32x4_t lo32 = vmovl_s16(vget_low_s16(in16));
    int32x4_t hi32 = vmovl_s16(vget_high_s16(in16));
    // Convert to float
    float32x4_t flo = vcvtq_f32_s32(lo32);
    float32x4_t fhi = vcvtq_f32_s32(hi32);
    // Store results
    vst1q_f32(&output[i], flo);
    vst1q_f32(&output[i + 4], fhi);
  }
  // Handle remaining samples (scalar fallback)
  for (; i < count; i++)
    output[i] = (float)input[i];
}

// ARM NEON-optimized float to int16 conversion with clamping
static inline void pcm_float_to_int16(const float *input, short *output,
                                      int count) {
  int i = 0;
  const float32x4_t max_val = vdupq_n_f32(PCM_SCALE_FLOAT_MAX);
  const float32x4_t min_val = vdupq_n_f32(PCM_SCALE_FLOAT_MIN);

  // Process 8 samples at a time using NEON
  for (; i <= count - 8; i += 8) {
    // Load 8 floats
    float32x4_t flo = vld1q_f32(&input[i]);
    float32x4_t fhi = vld1q_f32(&input[i + 4]);
    // Clamp to valid range
    flo = vminq_f32(vmaxq_f32(flo, min_val), max_val);
    fhi = vminq_f32(vmaxq_f32(fhi, min_val), max_val);
    // Convert to 32-bit integers (ARMv7-compatible)
    int32x4_t lo32 = vcvtq_s32_f32(flo);
    int32x4_t hi32 = vcvtq_s32_f32(fhi);
    // Narrow to 16-bit integers
    int16x4_t lo16 = vmovn_s32(lo32);
    int16x4_t hi16 = vmovn_s32(hi32);
    // Combine and store
    int16x8_t result = vcombine_s16(lo16, hi16);
    vst1q_s16(&output[i], result);
  }
  // Handle remaining samples (scalar fallback)
  for (; i < count; i++) {
    float val = input[i];
    if (val > PCM_SCALE_FLOAT_MAX)
      val = PCM_SCALE_FLOAT_MAX;
    if (val < PCM_SCALE_FLOAT_MIN)
      val = PCM_SCALE_FLOAT_MIN;
    output[i] = (int16_t)val;
  }
}

#else
// Scalar fallback for platforms without SIMD
static inline void pcm_int16_to_float(const short *input, float *output,
                                      int count) {
  for (int i = 0; i < count; i++)
    output[i] = (float)input[i];
}

static inline void pcm_float_to_int16(const float *input, short *output,
                                      int count) {
  for (int i = 0; i < count; i++) {
    float val = input[i];
    if (val > PCM_SCALE_FLOAT_MAX)
      val = PCM_SCALE_FLOAT_MAX;
    if (val < PCM_SCALE_FLOAT_MIN)
      val = PCM_SCALE_FLOAT_MIN;
    output[i] = (int16_t)val;
  }
}
#endif

// RNNoise requires 48Khz input and output
#define FRAME_RATE 48000
#define FRAME_SIZE 480

#define calculate_frame_sample(sample_rate) (sample_rate * 10 / 1000)

typedef struct AudxState AudxState;

AudxState *audx_create(char *model_path, unsigned int in_rate,
                       int resample_quality);

float audx_process(AudxState *state, float *in, float *out);

float audx_process_int(AudxState *state, short *in, short *out);

void audx_destroy(AudxState *state);

#ifdef __cplusplus
}
#endif

#endif // AUDX_H
