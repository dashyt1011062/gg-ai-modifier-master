/**
 * GG-AI Native Memory Scanner v19.0 - 高性能版
 *
 * 参考 libscan-gt 设计：
 * - 使用 process_vm_readv/writev 系统调用（Android 5.0+）
 * - 2MB 高速缓冲区滑动窗口
 * - 纯 C++ 实现所有搜索算法
 * - 不需要打开 /proc/pid/mem 文件描述符
 */

#include <jni.h>
#include <vector>
#include <cstring>
#include <cstdint>
#include <sys/uio.h>
#include <android/log.h>

#define LOG_TAG "NativeScannerV19"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// 2MB 高速缓冲区
#define BUFFER_SIZE (2 * 1024 * 1024)

enum FuzzyMode { CHANGED = 0, UNCHANGED = 1, INCREASED = 2, DECREASED = 3 };

/**
 * 使用 process_vm_readv 高速读取内存
 * 优点：不需要打开文件描述符，直接系统调用
 */
static ssize_t read_process_memory(pid_t pid, uintptr_t remote_addr, void* local_buf, size_t len) {
    struct iovec local[1];
    struct iovec remote[1];
    
    local[0].iov_base = local_buf;
    local[0].iov_len = len;
    remote[0].iov_base = (void*)remote_addr;
    remote[0].iov_len = len;
    
    return process_vm_readv(pid, local, 1, remote, 1, 0);
}

/**
 * 使用 process_vm_writev 高速写入内存
 */
static ssize_t write_process_memory(pid_t pid, uintptr_t remote_addr, const void* local_buf, size_t len) {
    struct iovec local[1];
    struct iovec remote[1];
    
    local[0].iov_base = (void*)local_buf;
    local[0].iov_len = len;
    remote[0].iov_base = (void*)remote_addr;
    remote[0].iov_len = len;
    
    return process_vm_writev(pid, local, 1, remote, 1, 0);
}

extern "C" {

// ==================== 1. 精确搜索 ====================
JNIEXPORT jlongArray JNICALL
Java_com_yl_aigg_ai_1gg666_MemoryEngine_nativeSearchExactV19(
        JNIEnv* env, jobject thiz,
        jint pid, jlongArray startAddrs, jlongArray sizes,
        jint typeSize, jbyteArray targetBytes, jint maxResults) {

    jsize targetLen = env->GetArrayLength(targetBytes);
    std::vector<uint8_t> target(targetLen);
    env->GetByteArrayRegion(targetBytes, 0, targetLen, (jbyte*)target.data());

    jsize regionCnt = env->GetArrayLength(startAddrs);
    jlong* pStarts = env->GetLongArrayElements(startAddrs, nullptr);
    jlong* pSizes = env->GetLongArrayElements(sizes, nullptr);

    std::vector<jlong> results;
    std::vector<uint8_t> buf(BUFFER_SIZE);

    LOGD("🚀 V19 精确搜索开始: pid=%d, regions=%d, target_size=%d", pid, regionCnt, targetLen);

    for (jsize r = 0; r < regionCnt; ++r) {
        jlong start = pStarts[r];
        jlong rSize = pSizes[r];
        jlong offset = 0;

        while (offset < rSize) {
            size_t toRead = (size_t)std::min((jlong)buf.size(), rSize - offset);
            ssize_t got = read_process_memory(pid, start + offset, buf.data(), toRead);
            
            if (got <= 0) {
                offset += toRead;
                continue;
            }

            // 在缓冲区中搜索目标值
            for (size_t i = 0; i + targetLen <= (size_t)got; i += typeSize) {
                if (memcmp(buf.data() + i, target.data(), targetLen) == 0) {
                    results.push_back(start + offset + i);
                    if ((int)results.size() >= maxResults) goto DONE;
                }
            }
            
            // 滑动窗口：重叠 (targetLen - 1) 字节
            offset += (got - (targetLen - 1));
        }
    }
DONE:
    env->ReleaseLongArrayElements(startAddrs, pStarts, JNI_ABORT);
    env->ReleaseLongArrayElements(sizes, pSizes, JNI_ABORT);

    LOGD("✅ V19 精确搜索完成: %d results", (int)results.size());
    
    jlongArray res = env->NewLongArray(results.size());
    if (!results.empty()) env->SetLongArrayRegion(res, 0, results.size(), results.data());
    return res;
}

// ==================== 2. 范围搜索 ====================
JNIEXPORT jlongArray JNICALL
Java_com_yl_aigg_ai_1gg666_MemoryEngine_nativeSearchRangeV19(
        JNIEnv* env, jobject thiz,
        jint pid, jlongArray startAddrs, jlongArray sizes,
        jlong lowBound, jlong highBound, jint typeSize) {

    jsize regionCnt = env->GetArrayLength(startAddrs);
    jlong* pStarts = env->GetLongArrayElements(startAddrs, nullptr);
    jlong* pSizes = env->GetLongArrayElements(sizes, nullptr);

    std::vector<jlong> results;
    std::vector<uint8_t> buf(BUFFER_SIZE);

    LOGD("🚀 V19 范围搜索开始: pid=%d, range=[%lld, %lld]", pid, lowBound, highBound);

    for (jsize r = 0; r < regionCnt; ++r) {
        jlong start = pStarts[r];
        jlong rSize = pSizes[r];
        jlong offset = 0;

        while (offset < rSize) {
            size_t toRead = (size_t)std::min((jlong)buf.size(), rSize - offset);
            ssize_t got = read_process_memory(pid, start + offset, buf.data(), toRead);
            
            if (got <= 0) {
                offset += toRead;
                continue;
            }

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
    env->ReleaseLongArrayElements(startAddrs, pStarts, JNI_ABORT);
    env->ReleaseLongArrayElements(sizes, pSizes, JNI_ABORT);

    LOGD("✅ V19 范围搜索完成: %d results", (int)results.size());

    jlongArray res = env->NewLongArray(results.size());
    if (!results.empty()) env->SetLongArrayRegion(res, 0, results.size(), results.data());
    return res;
}

// ==================== 3. AOB 特征码搜索 ====================
JNIEXPORT jlongArray JNICALL
Java_com_yl_aigg_ai_1gg666_MemoryEngine_nativeSearchAobV19(
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

    std::vector<jlong> results;
    std::vector<uint8_t> buf(BUFFER_SIZE);

    LOGD("🚀 V19 AOB搜索开始: pid=%d, pattern_len=%d", pid, patLen);

    for (jsize r = 0; r < regionCnt; ++r) {
        jlong start = pStarts[r];
        jlong rSize = pSizes[r];
        jlong offset = 0;

        while (offset < rSize) {
            size_t toRead = (size_t)std::min((jlong)buf.size(), rSize - offset);
            ssize_t got = read_process_memory(pid, start + offset, buf.data(), toRead);
            
            if (got <= 0) {
                offset += toRead;
                continue;
            }

            for (size_t i = 0; i + patLen <= (size_t)got; i++) {
                bool match = true;
                for (size_t j = 0; j < patLen; ++j) {
                    if (mask[j] == 1 && buf[i + j] != pattern[j]) {
                        match = false;
                        break;
                    }
                }
                if (match) results.push_back(start + offset + i);
            }
            
            offset += (got - (patLen - 1));
        }
    }

    env->ReleaseLongArrayElements(startAddrs, pStarts, JNI_ABORT);
    env->ReleaseLongArrayElements(sizes, pSizes, JNI_ABORT);

    LOGD("✅ V19 AOB搜索完成: %d results", (int)results.size());

    jlongArray res = env->NewLongArray(results.size());
    if (!results.empty()) env->SetLongArrayRegion(res, 0, results.size(), results.data());
    return res;
}

// ==================== 4. 模糊搜索 ====================
JNIEXPORT jlongArray JNICALL
Java_com_yl_aigg_ai_1gg666_MemoryEngine_nativeSearchFuzzyV19(
        JNIEnv* env, jobject thiz,
        jint pid, jlongArray historyAddrs, jbyteArray historyValues,
        jint mode, jint typeSize) {

    jsize cnt = env->GetArrayLength(historyAddrs);
    jlong* pAddrs = env->GetLongArrayElements(historyAddrs, nullptr);
    std::vector<uint8_t> oldVals(cnt * typeSize);
    env->GetByteArrayRegion(historyValues, 0, oldVals.size(), (jbyte*)oldVals.data());

    std::vector<jlong> hits;
    uint8_t curBuf[8];

    LOGD("🚀 V19 模糊搜索开始: pid=%d, addresses=%d, mode=%d", pid, cnt, mode);

    for (jsize i = 0; i < cnt; ++i) {
        ssize_t got = read_process_memory(pid, pAddrs[i], curBuf, typeSize);
        if (got != typeSize) continue;

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

    env->ReleaseLongArrayElements(historyAddrs, pAddrs, JNI_ABORT);

    LOGD("✅ V19 模糊搜索完成: %d results", (int)hits.size());

    jlongArray res = env->NewLongArray(hits.size());
    if (!hits.empty()) env->SetLongArrayRegion(res, 0, hits.size(), hits.data());
    return res;
}

// ==================== 5. 读写内存 ====================
JNIEXPORT jbyteArray JNICALL
Java_com_yl_aigg_ai_1gg666_MemoryEngine_nativeReadMemoryV19(
        JNIEnv* env, jobject thiz, jint pid, jlong address, jint size) {

    std::vector<uint8_t> buf(size);
    ssize_t got = read_process_memory(pid, address, buf.data(), size);

    if (got != size) return nullptr;
    
    jbyteArray res = env->NewByteArray(size);
    env->SetByteArrayRegion(res, 0, size, (jbyte*)buf.data());
    return res;
}

JNIEXPORT jboolean JNICALL
Java_com_yl_aigg_ai_1gg666_MemoryEngine_nativeWriteMemoryV19(
        JNIEnv* env, jobject thiz, jint pid, jlong address, jbyteArray data) {

    jsize len = env->GetArrayLength(data);
    std::vector<uint8_t> buf(len);
    env->GetByteArrayRegion(data, 0, len, (jbyte*)buf.data());
    
    ssize_t wr = write_process_memory(pid, address, buf.data(), len);
    return (wr == len) ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
