/* Original JNI adapter: state queries and an explicit, fd-scoped input grab. */
#include <jni.h>
#include <errno.h>
#include <linux/input.h>
#include <stdio.h>
#include <stdint.h>
#include <string.h>
#include <sys/ioctl.h>

static void throw_io(JNIEnv *env, const char *message) {
    jclass type = (*env)->FindClass(env, "java/io/IOException");
    if (type != NULL) (*env)->ThrowNew(env, type, message);
}

static int query(int fd, unsigned long request, void *result) {
    int rc;
    /* A signal storm must not keep stop/cancellation waiting indefinitely. */
    for (int attempt = 0; attempt < 32; attempt++) {
        rc = ioctl(fd, request, result);
        if (rc >= 0 || errno != EINTR) return rc;
    }
    errno = EINTR;
    return -1;
}

/* The source reader alone owns/closes this object, including while ioctl runs. */
static int owned_fd(JNIEnv *env, jobject descriptor) {
    if (descriptor == NULL) {
        throw_io(env, "Input query requires an owned file descriptor");
        return -1;
    }
    jclass fd_class = (*env)->GetObjectClass(env, descriptor);
    if (fd_class == NULL) return -1;
    jfieldID field = (*env)->GetFieldID(env, fd_class, "descriptor", "I");
    if (field == NULL) {
        (*env)->ExceptionClear(env);
        throw_io(env, "Android file descriptor ABI is unavailable");
        return -1;
    }
    jint fd = (*env)->GetIntField(env, descriptor, field);
    if (fd < 0) throw_io(env, "Selected input descriptor has been closed");
    return fd;
}

JNIEXPORT void JNICALL
Java_dev_codek_xboxprobe_NativeEvdevState_setExclusiveState(JNIEnv *env, jclass owner,
                                                          jobject descriptor, jboolean enabled) {
    (void) owner;
    int fd = owned_fd(env, descriptor);
    if (fd < 0) return;
    if (query(fd, EVIOCGRAB, (void *)(uintptr_t)(enabled ? 1 : 0)) < 0) {
        int code = errno;
        char message[160];
        snprintf(message, sizeof(message), "EVIOCGRAB %s failed: %s (errno %d)",
                 enabled ? "acquire" : "release", strerror(code), code);
        throw_io(env, message);
    }
}

JNIEXPORT jintArray JNICALL
Java_dev_codek_xboxprobe_NativeEvdevState_readState(JNIEnv *env, jclass owner,
                                                  jobject descriptor, jintArray key_array,
                                                  jintArray axis_array) {
    (void) owner;
    if (descriptor == NULL || key_array == NULL || axis_array == NULL) {
        throw_io(env, "Input-state query requires an owned descriptor and capability arrays");
        return NULL;
    }
    jsize key_count = (*env)->GetArrayLength(env, key_array);
    jsize axis_count = (*env)->GetArrayLength(env, axis_array);
    if (key_count > KEY_CNT || axis_count > ABS_CNT) {
        throw_io(env, "Input-state capability array exceeds Linux UAPI bounds");
        return NULL;
    }
    jint keys[KEY_CNT], axes[ABS_CNT], values[KEY_CNT + ABS_CNT];
    (*env)->GetIntArrayRegion(env, key_array, 0, key_count, keys);
    (*env)->GetIntArrayRegion(env, axis_array, 0, axis_count, axes);
    if ((*env)->ExceptionCheck(env)) return NULL;
    for (jsize i = 0; i < key_count; i++) {
        if (keys[i] < 0 || keys[i] > KEY_MAX) {
            throw_io(env, "Input-state query has an invalid key code");
            return NULL;
        }
    }
    for (jsize i = 0; i < axis_count; i++) {
        if (axes[i] < 0 || axes[i] > ABS_MAX) {
            throw_io(env, "Input-state query has an invalid axis code");
            return NULL;
        }
    }
    int fd = owned_fd(env, descriptor);
    if (fd < 0) return NULL;
    unsigned char pressed[(KEY_CNT + 7) / 8] = {0};
    if (query(fd, EVIOCGKEY(sizeof(pressed)), pressed) < 0) {
        char message[128];
        snprintf(message, sizeof(message), "EVIOCGKEY failed: %s (errno %d)", strerror(errno), errno);
        throw_io(env, message);
        return NULL;
    }
    for (jsize i = 0; i < key_count; i++) {
        values[i] = (pressed[keys[i] / 8] >> (keys[i] % 8)) & 1;
    }
    for (jsize i = 0; i < axis_count; i++) {
        struct input_absinfo axis;
        memset(&axis, 0, sizeof(axis));
        if (query(fd, EVIOCGABS(axes[i]), &axis) < 0) {
            char message[128];
            snprintf(message, sizeof(message), "EVIOCGABS(%d) failed: %s (errno %d)", axes[i], strerror(errno), errno);
            throw_io(env, message);
            return NULL;
        }
        values[key_count + i] = axis.value;
    }
    jintArray result = (*env)->NewIntArray(env, key_count + axis_count);
    if (result != NULL) (*env)->SetIntArrayRegion(env, result, 0, key_count + axis_count, values);
    return result;
}
