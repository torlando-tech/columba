/*
 * MIT-licensed JNI wrapper for libopus (BSD-3-Clause).
 *
 * Clean-room implementation — only the 4 functions lxst needs.
 * Uses uintptr_t for handle casts (safe on ILP32 and LP64).
 * Uses opus_encoder_destroy()/opus_decoder_destroy() (not free()).
 * Skips redundant opus_encoder_init() after opus_encoder_create().
 *
 * SPDX-License-Identifier: MIT
 */

#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include "include/opus/opus.h"

typedef struct {
    OpusEncoder *enc;
    OpusDecoder *dec;
} OpusCtx;

static OpusCtx *ctx_from_handle(jlong h) {
    return (OpusCtx *)(uintptr_t)h;
}

/* --- JNI methods -------------------------------------------------------- */

static jlong nCreate(JNIEnv *env, jclass cls,
                     jint sampleRate, jint channels, jint application,
                     jint bitrate, jint complexity) {
    int encErr, decErr;

    OpusEncoder *enc = opus_encoder_create(sampleRate, channels, application, &encErr);
    if (encErr != OPUS_OK || !enc) return 0;

    opus_encoder_ctl(enc, OPUS_SET_BITRATE(bitrate));
    opus_encoder_ctl(enc, OPUS_SET_COMPLEXITY(complexity));

    OpusDecoder *dec = opus_decoder_create(sampleRate, channels, &decErr);
    if (decErr != OPUS_OK || !dec) {
        opus_encoder_destroy(enc);
        return 0;
    }

    OpusCtx *ctx = (OpusCtx *)malloc(sizeof(OpusCtx));
    if (!ctx) {
        opus_encoder_destroy(enc);
        opus_decoder_destroy(dec);
        return 0;
    }
    ctx->enc = enc;
    ctx->dec = dec;
    return (jlong)(uintptr_t)ctx;
}

static void nDestroy(JNIEnv *env, jclass cls, jlong handle) {
    OpusCtx *ctx = ctx_from_handle(handle);
    if (!ctx) return;
    opus_encoder_destroy(ctx->enc);
    opus_decoder_destroy(ctx->dec);
    free(ctx);
}

static jint nEncode(JNIEnv *env, jclass cls, jlong handle,
                    jshortArray pcm, jint framesPerChannel, jbyteArray out) {
    OpusCtx *ctx = ctx_from_handle(handle);

    jint outLen = (*env)->GetArrayLength(env, out);
    jshort *pcmBuf = (*env)->GetShortArrayElements(env, pcm, NULL);
    jbyte  *outBuf = (*env)->GetByteArrayElements(env, out, NULL);

    int encoded = opus_encode(ctx->enc, pcmBuf, framesPerChannel,
                              (unsigned char *)outBuf, outLen);

    (*env)->ReleaseShortArrayElements(env, pcm, pcmBuf, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, out, outBuf, 0);
    return encoded;
}

static jint nDecode(JNIEnv *env, jclass cls, jlong handle,
                    jbyteArray encoded, jshortArray pcmOut,
                    jint maxFramesPerChannel) {
    OpusCtx *ctx = ctx_from_handle(handle);

    jint encLen = (*env)->GetArrayLength(env, encoded);
    jbyte  *encBuf = (*env)->GetByteArrayElements(env, encoded, NULL);
    jshort *pcmBuf = (*env)->GetShortArrayElements(env, pcmOut, NULL);

    int decoded = opus_decode(ctx->dec, (const unsigned char *)encBuf, encLen,
                              pcmBuf, maxFramesPerChannel, 0);

    (*env)->ReleaseByteArrayElements(env, encoded, encBuf, JNI_ABORT);
    (*env)->ReleaseShortArrayElements(env, pcmOut, pcmBuf, 0);
    return decoded;
}

/* --- RegisterNatives ---------------------------------------------------- */

static const JNINativeMethod sMethods[] = {
    {"create",  "(IIIII)J",   (void *)nCreate},
    {"destroy", "(J)V",       (void *)nDestroy},
    {"encode",  "(J[SI[B)I",  (void *)nEncode},
    {"decode",  "(J[B[SI)I",  (void *)nDecode},
};

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    JNIEnv *env;
    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK)
        return JNI_ERR;

    jclass cls = (*env)->FindClass(env, "tech/torlando/lxst/codec/NativeOpus");
    if (!cls) return JNI_ERR;

    jint ret = (*env)->RegisterNatives(env, cls, sMethods,
                                       sizeof(sMethods) / sizeof(sMethods[0]));
    (*env)->DeleteLocalRef(env, cls);
    return ret == 0 ? JNI_VERSION_1_6 : JNI_ERR;
}
