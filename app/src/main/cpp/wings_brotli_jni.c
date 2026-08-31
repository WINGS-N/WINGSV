// JNI-обёртка над brotli. Кодек нужен обеим сторонам: приложение и разбирает
// присланные ссылки, и собирает свои
#include <jni.h>
#include <stdlib.h>
#include <string.h>

#include "brotli/decode.h"
#include "brotli/encode.h"

// decodeLimit ограничивает распаковку. Ссылка приходит извне, и раздутый вход
// не должен превращаться в сотни мегабайт в куче
static const size_t decodeLimit = 8u * 1024u * 1024u;

JNIEXPORT jbyteArray JNICALL
Java_wings_v_core_BrotliCodec_nativeCompress(JNIEnv *env, jclass clazz, jbyteArray input, jint quality) {
    (void) clazz;
    jsize inputSize = (*env)->GetArrayLength(env, input);
    jbyte *inputBytes = (*env)->GetByteArrayElements(env, input, NULL);
    if (inputBytes == NULL) {
        return NULL;
    }

    size_t outputSize = BrotliEncoderMaxCompressedSize((size_t) inputSize);
    if (outputSize == 0) {
        outputSize = (size_t) inputSize + 1024;
    }
    uint8_t *output = (uint8_t *) malloc(outputSize);
    if (output == NULL) {
        (*env)->ReleaseByteArrayElements(env, input, inputBytes, JNI_ABORT);
        return NULL;
    }

    BROTLI_BOOL ok = BrotliEncoderCompress(
            quality, BROTLI_DEFAULT_WINDOW, BROTLI_MODE_GENERIC,
            (size_t) inputSize, (const uint8_t *) inputBytes,
            &outputSize, output);
    (*env)->ReleaseByteArrayElements(env, input, inputBytes, JNI_ABORT);
    if (!ok) {
        free(output);
        return NULL;
    }

    jbyteArray result = (*env)->NewByteArray(env, (jsize) outputSize);
    if (result != NULL) {
        (*env)->SetByteArrayRegion(env, result, 0, (jsize) outputSize, (const jbyte *) output);
    }
    free(output);
    return result;
}

JNIEXPORT jbyteArray JNICALL
Java_wings_v_core_BrotliCodec_nativeDecompress(JNIEnv *env, jclass clazz, jbyteArray input) {
    (void) clazz;
    jsize inputSize = (*env)->GetArrayLength(env, input);
    jbyte *inputBytes = (*env)->GetByteArrayElements(env, input, NULL);
    if (inputBytes == NULL) {
        return NULL;
    }

    // Размер распакованного заранее неизвестен, поэтому буфер растёт удвоением
    size_t capacity = (size_t) inputSize * 4u + 1024u;
    uint8_t *output = NULL;
    size_t decodedSize = 0;
    BrotliDecoderResult status = BROTLI_DECODER_RESULT_ERROR;

    while (capacity <= decodeLimit) {
        uint8_t *grown = (uint8_t *) realloc(output, capacity);
        if (grown == NULL) {
            break;
        }
        output = grown;
        decodedSize = capacity;
        status = BrotliDecoderDecompress(
                (size_t) inputSize, (const uint8_t *) inputBytes,
                &decodedSize, output);
        if (status != BROTLI_DECODER_RESULT_ERROR) {
            break;
        }
        capacity *= 2u;
    }
    (*env)->ReleaseByteArrayElements(env, input, inputBytes, JNI_ABORT);

    if (status != BROTLI_DECODER_RESULT_SUCCESS) {
        free(output);
        return NULL;
    }
    jbyteArray result = (*env)->NewByteArray(env, (jsize) decodedSize);
    if (result != NULL) {
        (*env)->SetByteArrayRegion(env, result, 0, (jsize) decodedSize, (const jbyte *) output);
    }
    free(output);
    return result;
}
