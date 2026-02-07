/*
 * Minimal codec2 API header for lxst JNI wrapper.
 *
 * Only the functions used by lxst are declared here.
 * The underlying libcodec2.so is LGPL-2.1 licensed.
 * This header declares the public ABI and carries no copyrightable expression.
 */

#ifndef LXST_CODEC2_H
#define LXST_CODEC2_H

#ifdef __cplusplus
extern "C" {
#endif

#define CODEC2_MODE_3200  0
#define CODEC2_MODE_2400  1
#define CODEC2_MODE_1600  2
#define CODEC2_MODE_1400  3
#define CODEC2_MODE_1300  4
#define CODEC2_MODE_1200  5
#define CODEC2_MODE_700C  8

struct CODEC2;

struct CODEC2 *codec2_create(int mode);
void codec2_destroy(struct CODEC2 *codec2_state);
void codec2_encode(struct CODEC2 *codec2_state, unsigned char bytes[], short speech_in[]);
void codec2_decode(struct CODEC2 *codec2_state, short speech_out[], const unsigned char bytes[]);
int  codec2_samples_per_frame(struct CODEC2 *codec2_state);
int  codec2_bytes_per_frame(struct CODEC2 *codec2_state);

#ifdef __cplusplus
}
#endif

#endif /* LXST_CODEC2_H */
