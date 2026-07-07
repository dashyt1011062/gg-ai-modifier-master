/**
 * GG-AI Root Memory Scanner - 优化版（参考 C-Android-Memory-Tool）
 * 
 * 关键优化：
 * - 使用 pread64 直接读取 /proc/pid/mem（不需要 process_vm_readv）
 * - 4KB (0x1000) 缓冲区分块读取
 * - 类型化缓冲区（DWORD buff[1024]）提高效率
 * - 简单高效的循环结构
 */

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <cstdint>
#include <vector>
#include <fcntl.h>
#include <unistd.h>

#define BUFFER_SIZE 0x1000  // 4KB
#define BUFFER_ITEMS 1024   // 4KB / 4 bytes
#define MAX_RESULTS 500

typedef int64_t QWORD;
typedef int32_t DWORD;
typedef int16_t WORD;
typedef int8_t BYTE;
typedef float FLOAT;
typedef double DOUBLE;

enum FuzzyMode { CHANGED = 0, UNCHANGED = 1, INCREASED = 2, DECREASED = 3 };

struct Region {
    uint64_t start;
    uint64_t size;
};

// 全局文件句柄
static int mem_fd = -1;

// 打开 /proc/pid/mem
static bool open_mem(int pid) {
    if (mem_fd >= 0) close(mem_fd);
    
    char path[64];
    snprintf(path, sizeof(path), "/proc/%d/mem", pid);
    mem_fd = open(path, O_RDWR);
    
    if (mem_fd < 0) {
        fprintf(stderr, "Failed to open %s\n", path);
        return false;
    }
    return true;
}

// 十六进制字符串转字节数组
static std::vector<uint8_t> hex_to_bytes(const char* hex) {
    std::vector<uint8_t> bytes;
    size_t len = strlen(hex);
    for (size_t i = 0; i + 1 < len; i += 2) {
        char byte_str[3] = {hex[i], hex[i+1], 0};
        bytes.push_back((uint8_t)strtol(byte_str, nullptr, 16));
    }
    return bytes;
}

// 字节数组转十六进制字符串
static void bytes_to_hex(const uint8_t* bytes, size_t len, char* out) {
    for (size_t i = 0; i < len; i++) {
        sprintf(out + i * 2, "%02x", bytes[i]);
    }
    out[len * 2] = 0;
}

// ==================== 精确搜索 DWORD ====================
static void search_exact_dword(int pid, const std::vector<Region>& regions, DWORD value) {
    std::vector<uint64_t> results;
    DWORD buff[BUFFER_ITEMS];
    
    for (const auto& reg : regions) {
        uint64_t page_count = reg.size / BUFFER_SIZE;
        
        for (uint64_t j = 0; j < page_count && results.size() < MAX_RESULTS; j++) {
            ssize_t got = pread64(mem_fd, buff, BUFFER_SIZE, reg.start + j * BUFFER_SIZE);
            if (got != BUFFER_SIZE) continue;
            
            for (int i = 0; i < BUFFER_ITEMS; i++) {
                if (buff[i] == value) {
                    results.push_back(reg.start + j * BUFFER_SIZE + i * 4);
                    if (results.size() >= MAX_RESULTS) break;
                }
            }
        }
    }
    
    printf("{\"status\":\"ok\",\"count\":%zu,\"addrs\":\"", results.size());
    for (size_t i = 0; i < results.size(); i++) {
        if (i > 0) printf(",");
        printf("%lx", results[i]);
    }
    printf("\"}\n");
    fflush(stdout);
}

// ==================== 精确搜索 FLOAT ====================
static void search_exact_float(int pid, const std::vector<Region>& regions, FLOAT value) {
    std::vector<uint64_t> results;
    FLOAT buff[BUFFER_ITEMS];
    
    for (const auto& reg : regions) {
        uint64_t page_count = reg.size / BUFFER_SIZE;
        
        for (uint64_t j = 0; j < page_count && results.size() < MAX_RESULTS; j++) {
            ssize_t got = pread64(mem_fd, buff, BUFFER_SIZE, reg.start + j * BUFFER_SIZE);
            if (got != BUFFER_SIZE) continue;
            
            for (int i = 0; i < BUFFER_ITEMS; i++) {
                if (buff[i] == value) {
                    results.push_back(reg.start + j * BUFFER_SIZE + i * 4);
                    if (results.size() >= MAX_RESULTS) break;
                }
            }
        }
    }
    
    printf("{\"status\":\"ok\",\"count\":%zu,\"addrs\":\"", results.size());
    for (size_t i = 0; i < results.size(); i++) {
        if (i > 0) printf(",");
        printf("%lx", results[i]);
    }
    printf("\"}\n");
    fflush(stdout);
}

// ==================== 精确搜索 DOUBLE ====================
static void search_exact_double(int pid, const std::vector<Region>& regions, DOUBLE value) {
    std::vector<uint64_t> results;
    DOUBLE buff[BUFFER_ITEMS / 2];  // DOUBLE 是 8 字节
    
    for (const auto& reg : regions) {
        uint64_t page_count = reg.size / BUFFER_SIZE;
        
        for (uint64_t j = 0; j < page_count && results.size() < MAX_RESULTS; j++) {
            ssize_t got = pread64(mem_fd, buff, BUFFER_SIZE, reg.start + j * BUFFER_SIZE);
            if (got != BUFFER_SIZE) continue;
            
            for (int i = 0; i < BUFFER_ITEMS / 2; i++) {
                if (buff[i] == value) {
                    results.push_back(reg.start + j * BUFFER_SIZE + i * 8);
                    if (results.size() >= MAX_RESULTS) break;
                }
            }
        }
    }
    
    printf("{\"status\":\"ok\",\"count\":%zu,\"addrs\":\"", results.size());
    for (size_t i = 0; i < results.size(); i++) {
        if (i > 0) printf(",");
        printf("%lx", results[i]);
    }
    printf("\"}\n");
    fflush(stdout);
}

// ==================== 范围搜索 DWORD ====================
static void search_range_dword(int pid, const std::vector<Region>& regions, DWORD low, DWORD high) {
    std::vector<uint64_t> results;
    DWORD buff[BUFFER_ITEMS];
    
    for (const auto& reg : regions) {
        uint64_t page_count = reg.size / BUFFER_SIZE;
        
        for (uint64_t j = 0; j < page_count && results.size() < MAX_RESULTS; j++) {
            ssize_t got = pread64(mem_fd, buff, BUFFER_SIZE, reg.start + j * BUFFER_SIZE);
            if (got != BUFFER_SIZE) continue;
            
            for (int i = 0; i < BUFFER_ITEMS; i++) {
                if (buff[i] >= low && buff[i] <= high) {
                    results.push_back(reg.start + j * BUFFER_SIZE + i * 4);
                    if (results.size() >= MAX_RESULTS) break;
                }
            }
        }
    }
    
    printf("{\"status\":\"ok\",\"count\":%zu,\"addrs\":\"", results.size());
    for (size_t i = 0; i < results.size(); i++) {
        if (i > 0) printf(",");
        printf("%lx", results[i]);
    }
    printf("\"}\n");
    fflush(stdout);
}

// ==================== 范围搜索 FLOAT ====================
static void search_range_float(int pid, const std::vector<Region>& regions, FLOAT low, FLOAT high) {
    std::vector<uint64_t> results;
    FLOAT buff[BUFFER_ITEMS];
    
    for (const auto& reg : regions) {
        uint64_t page_count = reg.size / BUFFER_SIZE;
        
        for (uint64_t j = 0; j < page_count && results.size() < MAX_RESULTS; j++) {
            ssize_t got = pread64(mem_fd, buff, BUFFER_SIZE, reg.start + j * BUFFER_SIZE);
            if (got != BUFFER_SIZE) continue;
            
            for (int i = 0; i < BUFFER_ITEMS; i++) {
                if (buff[i] >= low && buff[i] <= high) {
                    results.push_back(reg.start + j * BUFFER_SIZE + i * 4);
                    if (results.size() >= MAX_RESULTS) break;
                }
            }
        }
    }
    
    printf("{\"status\":\"ok\",\"count\":%zu,\"addrs\":\"", results.size());
    for (size_t i = 0; i < results.size(); i++) {
        if (i > 0) printf(",");
        printf("%lx", results[i]);
    }
    printf("\"}\n");
    fflush(stdout);
}

// ==================== AOB 特征码搜索 ====================
static void search_aob(int pid, const std::vector<Region>& regions,
                      const std::vector<uint8_t>& pattern, const std::vector<uint8_t>& mask) {
    std::vector<uint64_t> results;
    uint8_t buff[BUFFER_SIZE];
    size_t pat_len = pattern.size();
    
    for (const auto& reg : regions) {
        uint64_t page_count = reg.size / BUFFER_SIZE;
        
        for (uint64_t j = 0; j < page_count && results.size() < MAX_RESULTS; j++) {
            ssize_t got = pread64(mem_fd, buff, BUFFER_SIZE, reg.start + j * BUFFER_SIZE);
            if (got < (ssize_t)pat_len) continue;
            
            for (size_t i = 0; i + pat_len <= (size_t)got; i++) {
                bool match = true;
                for (size_t k = 0; k < pat_len; k++) {
                    if (mask[k] == 1 && buff[i + k] != pattern[k]) {
                        match = false;
                        break;
                    }
                }
                if (match) {
                    results.push_back(reg.start + j * BUFFER_SIZE + i);
                    if (results.size() >= MAX_RESULTS) break;
                }
            }
        }
    }
    
    printf("{\"status\":\"ok\",\"count\":%zu,\"addrs\":\"", results.size());
    for (size_t i = 0; i < results.size(); i++) {
        if (i > 0) printf(",");
        printf("%lx", results[i]);
    }
    printf("\"}\n");
    fflush(stdout);
}

// ==================== 模糊搜索 ====================
static void search_fuzzy(int pid, const std::vector<uint64_t>& addrs,
                        const std::vector<uint8_t>& old_vals, int mode, int type_size) {
    std::vector<uint64_t> results;
    uint8_t cur_buf[8];
    
    for (size_t i = 0; i < addrs.size(); i++) {
        ssize_t got = pread64(mem_fd, cur_buf, type_size, addrs[i]);
        if (got != type_size) continue;
        
        int64_t old_num = 0, cur_num = 0;
        memcpy(&old_num, &old_vals[i * type_size], type_size);
        memcpy(&cur_num, cur_buf, type_size);
        
        if (type_size == 4) {
            old_num = (int32_t)old_num;
            cur_num = (int32_t)cur_num;
        }
        
        bool match = false;
        switch (mode) {
            case CHANGED:   if (cur_num != old_num) match = true; break;
            case UNCHANGED: if (cur_num == old_num) match = true; break;
            case INCREASED: if (cur_num > old_num)  match = true; break;
            case DECREASED: if (cur_num < old_num)  match = true; break;
        }
        if (match) results.push_back(addrs[i]);
    }
    
    printf("{\"status\":\"ok\",\"count\":%zu,\"addrs\":\"", results.size());
    for (size_t i = 0; i < results.size(); i++) {
        if (i > 0) printf(",");
        printf("%lx", results[i]);
    }
    printf("\"}\n");
    fflush(stdout);
}

// ==================== 读取内存 ====================
static void cmd_read(int pid, uint64_t addr, int size) {
    std::vector<uint8_t> buf(size);
    ssize_t got = pread64(mem_fd, buf.data(), size, addr);
    
    if (got != size) {
        printf("{\"status\":\"error\",\"msg\":\"read failed\"}\n");
        fflush(stdout);
        return;
    }
    
    char hex[size * 2 + 1];
    bytes_to_hex(buf.data(), size, hex);
    printf("{\"status\":\"ok\",\"data\":\"%s\"}\n", hex);
    fflush(stdout);
}

// ==================== 写入内存 ====================
static void cmd_write(int pid, uint64_t addr, const std::vector<uint8_t>& data) {
    ssize_t wr = pwrite64(mem_fd, data.data(), data.size(), addr);
    
    if (wr == (ssize_t)data.size()) {
        printf("{\"status\":\"ok\"}\n");
    } else {
        printf("{\"status\":\"error\",\"msg\":\"write failed\"}\n");
    }
    fflush(stdout);
}

// ==================== JSON 解析和命令执行 ====================
static void parse_and_execute(const char* json_line) {
    // 提取 cmd
    const char* cmd_start = strstr(json_line, "\"cmd\":\"");
    if (!cmd_start) return;
    cmd_start += 7;
    const char* cmd_end = strchr(cmd_start, '"');
    if (!cmd_end) return;
    
    char cmd[64];
    size_t cmd_len = cmd_end - cmd_start;
    if (cmd_len >= sizeof(cmd)) return;
    memcpy(cmd, cmd_start, cmd_len);
    cmd[cmd_len] = 0;
    
    // 提取 pid
    const char* pid_start = strstr(json_line, "\"pid\":");
    if (!pid_start) return;
    int pid = atoi(pid_start + 6);
    
    // 打开 /proc/pid/mem
    if (!open_mem(pid)) {
        printf("{\"status\":\"error\",\"msg\":\"failed to open mem\"}\n");
        fflush(stdout);
        return;
    }
    
    if (strcmp(cmd, "search_exact") == 0) {
        // 提取 type_size
        const char* ts_start = strstr(json_line, "\"type_size\":");
        if (!ts_start) return;
        int type_size = atoi(ts_start + 12);
        
        // 提取 target
        const char* target_start = strstr(json_line, "\"target\":\"");
        if (!target_start) return;
        target_start += 10;
        const char* target_end = strchr(target_start, '"');
        if (!target_end) return;
        
        char target_hex[1024];
        size_t target_len = target_end - target_start;
        if (target_len >= sizeof(target_hex)) return;
        memcpy(target_hex, target_start, target_len);
        target_hex[target_len] = 0;
        
        auto target = hex_to_bytes(target_hex);
        
        // 提取 regions
        std::vector<Region> regions;
        const char* reg_start = strstr(json_line, "\"regions\":[");
        if (reg_start) {
            reg_start += 11;
            while (*reg_start) {
                const char* start_str = strstr(reg_start, "\"start\":");
                const char* size_str = strstr(reg_start, "\"size\":");
                if (!start_str || !size_str) break;
                
                Region r;
                r.start = strtoull(start_str + 8, nullptr, 0);
                r.size = strtoull(size_str + 7, nullptr, 0);
                regions.push_back(r);
                
                reg_start = strchr(size_str, '}');
                if (!reg_start) break;
                reg_start++;
            }
        }
        
        // 根据类型调用不同的搜索函数
        if (type_size == 4 && target.size() == 4) {
            DWORD value;
            memcpy(&value, target.data(), 4);
            
            // 判断是 DWORD 还是 FLOAT（简单判断：如果看起来像浮点数就用 FLOAT）
            FLOAT fvalue;
            memcpy(&fvalue, target.data(), 4);
            if (fvalue > -1000000.0f && fvalue < 1000000.0f && fvalue != (FLOAT)(int)fvalue) {
                search_exact_float(pid, regions, fvalue);
            } else {
                search_exact_dword(pid, regions, value);
            }
        } else if (type_size == 8 && target.size() == 8) {
            DOUBLE value;
            memcpy(&value, target.data(), 8);
            search_exact_double(pid, regions, value);
        }
        
    } else if (strcmp(cmd, "search_range") == 0) {
        const char* ts_start = strstr(json_line, "\"type_size\":");
        if (!ts_start) return;
        int type_size = atoi(ts_start + 12);
        
        const char* low_start = strstr(json_line, "\"low\":");
        const char* high_start = strstr(json_line, "\"high\":");
        if (!low_start || !high_start) return;
        
        int64_t low = strtoll(low_start + 6, nullptr, 0);
        int64_t high = strtoll(high_start + 7, nullptr, 0);
        
        std::vector<Region> regions;
        const char* reg_start = strstr(json_line, "\"regions\":[");
        if (reg_start) {
            reg_start += 11;
            while (*reg_start) {
                const char* start_str = strstr(reg_start, "\"start\":");
                const char* size_str = strstr(reg_start, "\"size\":");
                if (!start_str || !size_str) break;
                
                Region r;
                r.start = strtoull(start_str + 8, nullptr, 0);
                r.size = strtoull(size_str + 7, nullptr, 0);
                regions.push_back(r);
                
                reg_start = strchr(size_str, '}');
                if (!reg_start) break;
                reg_start++;
            }
        }
        
        if (type_size == 4) {
            search_range_dword(pid, regions, (DWORD)low, (DWORD)high);
        }
        
    } else if (strcmp(cmd, "search_aob") == 0) {
        const char* pattern_start = strstr(json_line, "\"pattern\":\"");
        const char* mask_start = strstr(json_line, "\"mask\":\"");
        if (!pattern_start || !mask_start) return;
        
        pattern_start += 11;
        const char* pattern_end = strchr(pattern_start, '"');
        if (!pattern_end) return;
        
        char pattern_hex[4096];
        size_t pattern_len = pattern_end - pattern_start;
        if (pattern_len >= sizeof(pattern_hex)) return;
        memcpy(pattern_hex, pattern_start, pattern_len);
        pattern_hex[pattern_len] = 0;
        
        mask_start += 8;
        const char* mask_end = strchr(mask_start, '"');
        if (!mask_end) return;
        
        char mask_hex[4096];
        size_t mask_len = mask_end - mask_start;
        if (mask_len >= sizeof(mask_hex)) return;
        memcpy(mask_hex, mask_start, mask_len);
        mask_hex[mask_len] = 0;
        
        auto pattern = hex_to_bytes(pattern_hex);
        auto mask = hex_to_bytes(mask_hex);
        
        std::vector<Region> regions;
        const char* reg_start = strstr(json_line, "\"regions\":[");
        if (reg_start) {
            reg_start += 11;
            while (*reg_start) {
                const char* start_str = strstr(reg_start, "\"start\":");
                const char* size_str = strstr(reg_start, "\"size\":");
                if (!start_str || !size_str) break;
                
                Region r;
                r.start = strtoull(start_str + 8, nullptr, 0);
                r.size = strtoull(size_str + 7, nullptr, 0);
                regions.push_back(r);
                
                reg_start = strchr(size_str, '}');
                if (!reg_start) break;
                reg_start++;
            }
        }
        
        search_aob(pid, regions, pattern, mask);
        
    } else if (strcmp(cmd, "read") == 0) {
        const char* addr_start = strstr(json_line, "\"addr\":");
        const char* size_start = strstr(json_line, "\"size\":");
        if (!addr_start || !size_start) return;
        
        uint64_t addr = strtoull(addr_start + 7, nullptr, 0);
        int size = atoi(size_start + 7);
        
        cmd_read(pid, addr, size);
        
    } else if (strcmp(cmd, "write") == 0) {
        const char* addr_start = strstr(json_line, "\"addr\":");
        const char* data_start = strstr(json_line, "\"data\":\"");
        if (!addr_start || !data_start) return;
        
        uint64_t addr = strtoull(addr_start + 7, nullptr, 0);
        data_start += 8;
        const char* data_end = strchr(data_start, '"');
        if (!data_end) return;
        
        char data_hex[4096];
        size_t data_len = data_end - data_start;
        if (data_len >= sizeof(data_hex)) return;
        memcpy(data_hex, data_start, data_len);
        data_hex[data_len] = 0;
        
        auto data = hex_to_bytes(data_hex);
        cmd_write(pid, addr, data);
    }
}

int main() {
    // 从 stdin 读取命令，每行一个 JSON
    char line[65536];
    while (fgets(line, sizeof(line), stdin)) {
        parse_and_execute(line);
    }
    
    if (mem_fd >= 0) close(mem_fd);
    return 0;
}
