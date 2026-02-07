/*
 * MIT-licensed JNI wrapper for libcodec2 (LGPL-2.1).
 *
 * Clean-room implementation — only the 6 functions lxst needs.
 * Uses uintptr_t for handle casts (safe on ILP32 and LP64).
 * Uses codec2_decode() instead of codec2_decode_ber() with BER=0.0.
 * Encodes directly into jbyteArray (no CharArray quirk).
 *
 * SPDX-License-Identifier: MIT
 */

#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include "include/codec2/codec2.h"

typedef struct {
    struct CODEC2 *c2;
    int nsam;   /* samples per frame */
    int nbyte;  /* bytes per encoded frame */
} Codec2Ctx;

static Codec2Ctx *ctx_from_handle(jlong h) {
    return (Codec2Ctx *)(uintptr_t)h;
}

/* --- JNI methods -------------------------------------------------------- */

static jlong nCreate(JNIEnv *env, jclass cls, jint mode) {
    Codec2Ctx *ctx = (Codec2Ctx *)malloc(sizeof(Codec2Ctx));
    if (!ctx) return 0;

    ctx->c2 = codec2_create(mode);
    if (!ctx->c2) { free(ctx); return 0; }

    ctx->nsam  = codec2_samples_per_frame(ctx->c2);
    ctx->nbyte = codec2_bytes_per_frame(ctx->c2);
    return (jlong)(uintptr_t)ctx;
}

static void nDestroy(JNIEnv *env, jclass cls, jlong handle) {
    Codec2Ctx *ctx = ctx_from_handle(handle);
    if (!ctx) return;
    codec2_destroy(ctx->c2);
    free(ctx);
}

static jint nGetSamplesPerFrame(JNIEnv *env, jclass cls, jlong handle) {
    return ctx_from_handle(handle)->nsam;
}

static jint nGetFrameBytes(JNIEnv *env, jclass cls, jlong handle) {
    return ctx_from_handle(handle)->nbyte;
}

static jint nEncode(JNIEnv *env, jclass cls, jlong handle,
                    jshortArray pcm, jbyteArray out) {
    Codec2Ctx *ctx = ctx_from_handle(handle);

    jshort *pcmBuf = (*env)->GetShortArrayElements(env, pcm, NULL);
    jbyte  *outBuf = (*env)->GetByteArrayElements(env, out, NULL);

    codec2_encode(ctx->c2, (unsigned char *)outBuf, pcmBuf);

    (*env)->ReleaseShortArrayElements(env, pcm, pcmBuf, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, out, outBuf, 0);
    return ctx->nbyte;
}

static jint nDecode(JNIEnv *env, jclass cls, jlong handle,
                    jbyteArray encoded, jshortArray pcmOut) {
    Codec2Ctx *ctx = ctx_from_handle(handle);

    jbyte  *encBuf = (*env)->GetByteArrayElements(env, encoded, NULL);
    jshort *pcmBuf = (*env)->GetShortArrayElements(env, pcmOut, NULL);

    codec2_decode(ctx->c2, pcmBuf, (const unsigned char *)encBuf);

    (*env)->ReleaseByteArrayElements(env, encoded, encBuf, JNI_ABORT);
    (*env)->ReleaseShortArrayElements(env, pcmOut, pcmBuf, 0);
    return ctx->nsam;
}

/* --- RegisterNatives ---------------------------------------------------- */

static const JNINativeMethod sMethods[] = {
    {"create",             "(I)J",      (void *)nCreate},
    {"destroy",            "(J)V",      (void *)nDestroy},
    {"getSamplesPerFrame", "(J)I",      (void *)nGetSamplesPerFrame},
    {"getFrameBytes",      "(J)I",      (void *)nGetFrameBytes},
    {"encode",             "(J[S[B)I",  (void *)nEncode},
    {"decode",             "(J[B[S)I",  (void *)nDecode},
};

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    JNIEnv *env;
    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK)
        return JNI_ERR;

    jclass cls = (*env)->FindClass(env, "tech/torlando/lxst/codec/NativeCodec2");
    if (!cls) return JNI_ERR;

    jint ret = (*env)->RegisterNatives(env, cls, sMethods,
                                       sizeof(sMethods) / sizeof(sMethods[0]));
    (*env)->DeleteLocalRef(env, cls);
    return ret == 0 ? JNI_VERSION_1_6 : JNI_ERR;
}
