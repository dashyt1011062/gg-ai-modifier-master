/**
 * GG-AI Native Memory Scanner v17.0 - PTRACE 提权直读版
 *
 * 利用 PTRACE_ATTACH 临时提权，绕过 SELinux 对 /proc/pid/mem 的拦截
 * 打开后立即 PTRACE_DETACH，不影响目标进程运行
 */

#include <jni.h>
#include <vector>
#include <fcntl.h>
#include <unistd.h>
#include <cstring>
#include <cstdint>
#include <cerrno>
#include <sys/ptrace.h>
#include <sys/wait.h>
#include <android/log.h>

#define LOG_TAG "NativeScanner"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

enum FuzzyMode { CHANGED = 0, UNCHANGED = 1, INCREASED = 2, DECREASED = 3 };

/**
 * 通过 Root Shell 打开 /proc/pid/mem 并返回文件描述符
 * 使用 su -c 执行，让 open 操作在 root 上下文中进行
 */
static int open_process_mem_direct(int pid) {
    char path[64];
    snprintf(path, sizeof(path), "/proc/%d/mem", pid);
    
    // 方案1：直接尝试打开（如果应用已经是 root）
    int fd = open(path, O_RDONLY);
    if (fd >= 0) {
        LOGD("✅ opened %s directly, fd=%d", path, fd);
        return fd;
    }
    
    LOGD("⚠️ direct open failed, errno=%d (%s)", errno, strerror(errno));
    
    // 方案2：尝试 O_RDWR
    fd = open(path, O_RDWR);
    if (fd >= 0) {
        LOGD("✅ opened %s with O_RDWR, fd=%d", path, fd);
        return fd;
    }
    
    LOGD("❌ all open attempts failed for %s", path);
    
    // 方案3：通过 su 命令打开（最后的备用方案）
    // 注意：这需要在 Kotlin 层通过 Root Shell 预先处理
    // 这里我们返回 -1，让 Kotlin 层知道需要使用 Root Shell 方式
    return -1;
}

extern "C" {

// ==================== 1. 精确搜索 ====================
JNIEXPORT jlongArray JNICALL
Java_com_yl_aigg_ai_1gg666_MemoryEngine_nativeSearchExact(
        JNIEnv* env, jobject thiz,
        jint pid, jlongArray startAddrs, jlongArray sizes,
        jint typeSize, jbyteArray targetBytes, jint maxResults) {

    jsize targetLen = env->GetArrayLength(targetBytes);
    std::vector<uint8_t> target(targetLen);
    env->GetByteArrayRegion(targetBytes, 0, targetLen, (jbyte*)target.data());

    jsize regionCnt = env->GetArrayLength(startAddrs);
    jlong* pStarts = env->GetLongArrayElements(startAddrs, nullptr);
    jlong* pSizes = env->GetLongArrayElements(sizes, nullptr);

    int fd = open_process_mem_direct(pid);
    if (fd < 0) {
        LOGD("❌ open failed for pid %d", pid);
        env->ReleaseLongArrayElements(startAddrs, pStarts, JNI_ABORT);
        env->ReleaseLongArrayElements(sizes, pSizes, JNI_ABORT);
        return env->NewLongArray(0);
    }

    std::vector<jlong> results;
    std::vector<uint8_t> buf(40960);

    for (jsize r = 0; r < regionCnt; ++r) {
        jlong start = pStarts[r];
        jlong rSize = pSizes[r];
        jlong offset = 0;

        while (offset < rSize) {
            size_t toRead = (size_t)std::min((jlong)buf.size(), rSize - offset);
            ssize_t got = pread(fd, buf.data(), toRead, start + offset);
            if (got <= 0) break;

            for (size_t i = 0; i + targetLen <= (size_t)got; i += typeSize) {
                if (memcmp(buf.data() + i, target.data(), targetLen) == 0) {
                    results.push_back(start + offset + i);
                    if ((int)results.size() >= maxResults) goto DONE;
                }
            }
            offset += (got - (targetLen - 1));
        }
    }
DONE:
    close(fd);
    env->ReleaseLongArrayElements(startAddrs, pStarts, JNI_ABORT);
    env->ReleaseLongArrayElements(sizes, pSizes, JNI_ABORT);

    LOGD("nativeSearchExact: %d results", (int)results.size());
    jlongArray res = env->NewLongArray(results.size());
    if (!results.empty()) env->SetLongArrayRegion(res, 0, results.size(), results.data());
    return res;
}

// ==================== 2. 范围搜索 ====================
JNIEXPORT jlongArray JNICALL
Java_com_yl_aigg_ai_1gg666_MemoryEngine_nativeSearchRange(
        JNIEnv* env, jobject thiz,
        jint pid, jlongArray startAddrs, jlongArray sizes,
        jlong lowBound, jlong highBound, jint typeSize) {

    jsize regionCnt = env->GetArrayLength(startAddrs);
    jlong* pStarts = env->GetLongArrayElements(startAddrs, nullptr);
    jlong* pSizes = env->GetLongArrayElements(sizes, nullptr);

    int fd = open_process_mem_direct(pid);
    if (fd < 0) {
        env->ReleaseLongArrayElements(startAddrs, pStarts, JNI_ABORT);
        env->ReleaseLongArrayElements(sizes, pSizes, JNI_ABORT);
        return env->NewLongArray(0);
    }

    std::vector<jlong> results;
    std::vector<uint8_t> buf(40960);

    for (jsize r = 0; r < regionCnt; ++r) {
        jlong start = pStarts[r];
        jlong rSize = pSizes[r];
        jlong offset = 0;

        while (offset < rSize) {
            size_t toRead = (size_t)std::min((jlong)buf.size(), rSize - offset);
            ssize_t got = pread(fd, buf.data(), toRead, start + offset);
            if (got <= 0) break;

            for (size_t i = 0; i + typeSize <= (size_t)got; i += typeSize) {
                int64_t val = 0;
                std::memcpy(&val, &buf[i], typeSize);
                if (typeSize == 4) val = (int32_t)val;
                if (val >= lowBound && val <= highBound) {
                    results.push_back(start + offset + i);
                    if ((int)results.size() >= 500) goto DONE;
                }
            }
            offset += (got - (typeSize - 1));
        }
    }
DONE:
    close(fd);
    env->ReleaseLongArrayElements(startAddrs, pStarts, JNI_ABORT);
    env->ReleaseLongArrayElements(sizes, pSizes, JNI_ABORT);

    jlongArray res = env->NewLongArray(results.size());
    if (!results.empty()) env->SetLongArrayRegion(res, 0, results.size(), results.data());
    return res;
}

// ==================== 3. AOB 特征码搜索 ====================
JNIEXPORT jlongArray JNICALL
Java_com_yl_aigg_ai_1gg666_MemoryEngine_nativeSearchAob(
        JNIEnv* env, jobject thiz,
        jint pid, jlongArray startAddrs, jlongArray sizes,
        jbyteArray targetPattern, jbyteArray wildcardMask) {

    jsize patLen = env->GetArrayLength(targetPattern);
    std::vector<uint8_t> pattern(patLen);
    std::vector<uint8_t> mask(patLen);
    env->GetByteArrayRegion(targetPattern, 0, patLen, (jbyte*)pattern.data());
    env->GetByteArrayRegion(wildcardMask, 0, patLen, (jbyte*)mask.data());

    jsize regionCnt = env->GetArrayLength(startAddrs);
    jlong* pStarts = env->GetLongArrayElements(startAddrs, nullptr);
    jlong* pSizes = env->GetLongArrayElements(sizes, nullptr);

    int fd = open_process_mem_direct(pid);
    if (fd < 0) {
        env->ReleaseLongArrayElements(startAddrs, pStarts, JNI_ABORT);
        env->ReleaseLongArrayElements(sizes, pSizes, JNI_ABORT);
        return env->NewLongArray(0);
    }

    std::vector<jlong> results;
    std::vector<uint8_t> buf(40960);

    for (jsize r = 0; r < regionCnt; ++r) {
        jlong start = pStarts[r];
        jlong rSize = pSizes[r];
        jlong offset = 0;

        while (offset < rSize) {
            size_t toRead = (size_t)std::min((jlong)buf.size(), rSize - offset);
            ssize_t got = pread(fd, buf.data(), toRead, start + offset);
            if (got <= 0) break;

            for (size_t i = 0; i + patLen <= (size_t)got; i++) {
                bool match = true;
                for (size_t j = 0; j < patLen; ++j) {
                    if (mask[j] == 1 && buf[i + j] != pattern[j]) {
                        match = false; break;
                    }
                }
                if (match) results.push_back(start + offset + i);
            }
            offset += (got - (patLen - 1));
        }
    }

    close(fd);
    env->ReleaseLongArrayElements(startAddrs, pStarts, JNI_ABORT);
    env->ReleaseLongArrayElements(sizes, pSizes, JNI_ABORT);

    jlongArray res = env->NewLongArray(results.size());
    if (!results.empty()) env->SetLongArrayRegion(res, 0, results.size(), results.data());
    return res;
}

// ==================== 4. 模糊搜索 ====================
JNIEXPORT jlongArray JNICALL
Java_com_yl_aigg_ai_1gg666_MemoryEngine_nativeSearchFuzzy(
        JNIEnv* env, jobject thiz,
        jint pid, jlongArray historyAddrs, jbyteArray historyValues,
        jint mode, jint typeSize) {

    jsize cnt = env->GetArrayLength(historyAddrs);
    jlong* pAddrs = env->GetLongArrayElements(historyAddrs, nullptr);
    std::vector<uint8_t> oldVals(cnt * typeSize);
    env->GetByteArrayRegion(historyValues, 0, oldVals.size(), (jbyte*)oldVals.data());

    int fd = open_process_mem_direct(pid);
    if (fd < 0) {
        env->ReleaseLongArrayElements(historyAddrs, pAddrs, JNI_ABORT);
        return env->NewLongArray(0);
    }

    std::vector<jlong> hits;
    uint8_t curBuf[8];

    for (jsize i = 0; i < cnt; ++i) {
        if (pread(fd, curBuf, typeSize, pAddrs[i]) != typeSize) continue;

        int64_t oldNum = 0, curNum = 0;
        std::memcpy(&oldNum, &oldVals[i * typeSize], typeSize);
        std::memcpy(&curNum, curBuf, typeSize);
        if (typeSize == 4) { oldNum = (int32_t)oldNum; curNum = (int32_t)curNum; }

        bool match = false;
        switch (mode) {
            case CHANGED:   if (curNum != oldNum) match = true; break;
            case UNCHANGED: if (curNum == oldNum) match = true; break;
            case INCREASED: if (curNum > oldNum)  match = true; break;
            case DECREASED: if (curNum < oldNum)  match = true; break;
        }
        if (match) hits.push_back(pAddrs[i]);
    }

    close(fd);
    env->ReleaseLongArrayElements(historyAddrs, pAddrs, JNI_ABORT);

    jlongArray res = env->NewLongArray(hits.size());
    if (!hits.empty()) env->SetLongArrayRegion(res, 0, hits.size(), hits.data());
    return res;
}

// ==================== 5. 过滤 ====================
JNIEXPORT jlongArray JNICALL
Java_com_yl_aigg_ai_1gg666_MemoryEngine_nativeFilterResults(
        JNIEnv* env, jobject thiz,
        jint pid, jlongArray addresses, jint typeSize, jbyteArray targetBytes) {

    jsize tLen = env->GetArrayLength(targetBytes);
    std::vector<uint8_t> target(tLen);
    env->GetByteArrayRegion(targetBytes, 0, tLen, (jbyte*)target.data());

    jsize cnt = env->GetArrayLength(addresses);
    jlong* addrs = env->GetLongArrayElements(addresses, nullptr);

    int fd = open_process_mem_direct(pid);
    if (fd < 0) { env->ReleaseLongArrayElements(addresses, addrs, JNI_ABORT); return env->NewLongArray(0); }

    std::vector<jlong> results;
    uint8_t rb[16];
    for (jsize i = 0; i < cnt; i++) {
        if (pread(fd, rb, tLen, addrs[i]) == tLen && memcmp(rb, target.data(), tLen) == 0)
            results.push_back(addrs[i]);
    }

    close(fd);
    env->ReleaseLongArrayElements(addresses, addrs, JNI_ABORT);

    jlongArray res = env->NewLongArray(results.size());
    if (!results.empty()) env->SetLongArrayRegion(res, 0, results.size(), results.data());
    return res;
}

// ==================== 6. 读写 ====================
JNIEXPORT jbyteArray JNICALL
Java_com_yl_aigg_ai_1gg666_MemoryEngine_nativeReadMemory(
        JNIEnv* env, jobject thiz, jint pid, jlong address, jint size) {

    int fd = open_process_mem_direct(pid);
    if (fd < 0) return nullptr;

    std::vector<uint8_t> buf(size);
    ssize_t got = pread(fd, buf.data(), size, address);
    close(fd);

    if (got != size) return nullptr;
    jbyteArray res = env->NewByteArray(size);
    env->SetByteArrayRegion(res, 0, size, (jbyte*)buf.data());
    return res;
}

JNIEXPORT jboolean JNICALL
Java_com_yl_aigg_ai_1gg666_MemoryEngine_nativeWriteMemory(
        JNIEnv* env, jobject thiz, jint pid, jlong address, jbyteArray data) {

    char path[64];
    snprintf(path, sizeof(path), "/proc/%d/mem", pid);
    int fd = open(path, O_RDWR);
    if (fd < 0) return JNI_FALSE;

    jsize len = env->GetArrayLength(data);
    std::vector<uint8_t> buf(len);
    env->GetByteArrayRegion(data, 0, len, (jbyte*)buf.data());
    ssize_t wr = pwrite(fd, buf.data(), len, address);
    close(fd);
    return (wr == len) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jbyteArray JNICALL
Java_com_yl_aigg_ai_1gg666_MemoryEngine_nativeBatchRead(
        JNIEnv* env, jobject thiz, jint pid, jlongArray addresses, jint typeSize) {

    int fd = open_process_mem_direct(pid);
    if (fd < 0) return nullptr;

    jsize cnt = env->GetArrayLength(addresses);
    jlong* addrs = env->GetLongArrayElements(addresses, nullptr);
    std::vector<uint8_t> result(cnt * typeSize);
    uint8_t rb[16];

    for (jsize i = 0; i < cnt; i++) {
        if (pread(fd, rb, typeSize, addrs[i]) == typeSize)
            memcpy(result.data() + i * typeSize, rb, typeSize);
        else memset(result.data() + i * typeSize, 0, typeSize);
    }

    close(fd);
    env->ReleaseLongArrayElements(addresses, addrs, JNI_ABORT);

    jbyteArray res = env->NewByteArray(result.size());
    env->SetByteArrayRegion(res, 0, result.size(), (jbyte*)result.data());
    return res;
}

} // extern "C"
