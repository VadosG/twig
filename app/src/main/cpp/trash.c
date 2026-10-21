/* Same-volume moves that atomically refuse an existing destination. */
#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <stdlib.h>
#include <sys/syscall.h>
#include <unistd.h>

#ifndef RENAME_NOREPLACE
#define RENAME_NOREPLACE 1
#endif

static int move_no_replace(const char *from, const char *to) {
#ifdef SYS_renameat2
    return syscall(SYS_renameat2, AT_FDCWD, from, AT_FDCWD, to, RENAME_NOREPLACE) == 0 ? 0 : errno;
#else
    return ENOSYS;
#endif
}

static char *path_bytes(JNIEnv *env, jbyteArray bytes) {
    jsize length = (*env)->GetArrayLength(env, bytes);
    char *path = malloc((size_t)length + 1);
    if (!path) return NULL;
    (*env)->GetByteArrayRegion(env, bytes, 0, length, (jbyte *)path);
    path[length] = '\0';
    return path;
}

JNIEXPORT jint JNICALL
Java_com_twig_app_TrashMoves_nativeRename(JNIEnv *env, jobject self,
                                          jbyteArray source, jbyteArray destination) {
    (void)self;
    char *from = path_bytes(env, source);
    char *to = path_bytes(env, destination);
    int error = ENOMEM;
    if (from && to && !(*env)->ExceptionCheck(env)) {
        error = move_no_replace(from, to);
    }
    free(from);
    free(to);
    return error;
}
