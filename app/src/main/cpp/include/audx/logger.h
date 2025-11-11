#ifndef AUDX_LOGGER_H
#define AUDX_LOGGER_H

#ifdef AUDX_ANDROID
#include <android/log.h>
#define LOG_TAG "AUDX"
#define AUDX_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define AUDX_LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define AUDX_LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define AUDX_LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#else
#include <stdio.h>
#define AUDX_LOGE(...) fprintf(stderr, "[ERROR] " __VA_ARGS__); fprintf(stderr, "\n")
#define AUDX_LOGW(...) fprintf(stderr, "[WARN] " __VA_ARGS__); fprintf(stderr, "\n")
#define AUDX_LOGI(...) fprintf(stdout, "[INFO] " __VA_ARGS__); fprintf(stdout, "\n")
#define AUDX_LOGD(...) fprintf(stdout, "[DEBUG] " __VA_ARGS__); fprintf(stdout, "\n")
#endif

#endif // AUDX_LOGGER_H
