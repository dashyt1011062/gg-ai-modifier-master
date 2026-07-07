/**
 * GG-AI Root Memory Scanner - 独立可执行文件
 * 
 * 设计：
 * - 编译为独立可执行文件，通过 su 以 root 权限运行
 * - 使用 process_vm_readv/writev 系统调用（有 root 权限）
 * - 2MB 高速缓冲区滑动窗口
 * - 通过 stdout 输出结果，stdin 接收命令
 * 
 * 命令格式（JSON）：
 * {"cmd":"search_exact","pid":1234,"regions":[{"start":0x1000,"size":0x2000}],"type_size":4,"target":"01020304"}
 * {"cmd":"search_range","pid":1234,"regions":[...],"type_size":4,"low":100,"high":200}
 * {"cmd":"search_aob","pid":1234,"regions":[...],"pattern":"010203??05","mask":"11101011"}
 * {"cmd":"search_fuzzy","pid":1234,"addrs":[0x1000,0x2000],"old_vals":"01020304...","mode":0,"type_size":4}
 * {"cmd":"read","pid":1234,"addr":0x1000,"size":4}
 * {"cmd":"write","pid":1234,"addr":0x1000,"data":"01020304"}
 */

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <cstdint>
#include <vector>
#include <sys/uio.h>
#include <unistd.h>

#define BUFFER_SIZE (2 * 1024 * 1024)
#define MAX_RESULTS 500

enum FuzzyMode { CHANGED = 0, UNCHANGED = 1, INCREASED = 2, DECREASED = 3 };

struct Region {
    uint64_t start;
    uint64_t size;
};

// 使用 process_vm_readv 读取内存
static ssize_t read_process_memory(pid_t pid, uintptr_t remote_addr, void* local_buf, size_t len) {
    struct iovec local[1];
    struct iovec remote[1];
    
    local[0].iov_base = local_buf;
    local[0].iov_len = len;
    remote[0].iov_base = (void*)remote_addr;
    remote[0].iov_len = len;
    
    return process_vm_readv(pid, local, 1, remote, 1, 0);
}

// 使用 process_vm_writev 写入内存
static ssize_t write_process_memory(pid_t pid, uintptr_t remote_addr, const void* local_buf, size_t len) {
    struct iovec local[1];
    struct iovec remote[1];
    
    local[0].iov_base = (void*)local_buf;
    local[0].iov_len = len;
    remote[0].iov_base = (void*)remote_addr;
    remote[0].iov_len = len;
    
    return process_vm_writev(pid, local, 1, remote, 1, 0);
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

// 精确搜索
static void cmd_search_exact(int pid, const std::vector<Region>& regions, int type_size, 
                             const std::vector<uint8_t>& target) {
    std::vector<uint64_t> results;
    std::vector<uint8_t> buf(BUFFER_SIZE);
    
    for (const auto& reg : regions) {
        uint64_t offset = 0;
        while (offset < reg.size && results.size() < MAX_RESULTS) {
            size_t to_read = (size_t)((reg.size - offset) < buf.size() ? (reg.size - offset) : buf.size());
            ssize_t got = read_process_memory(pid, reg.start + offset, buf.data(), to_read);
            
            if (got <= 0) {
                offset += to_read;
                continue;
            }
            
            // 在缓冲区中搜索
            for (size_t i = 0; i + target.size() <= (size_t)got; i += type_size) {
                if (memcmp(buf.data() + i, target.data(), target.size()) == 0) {
                    results.push_back(reg.start + offset + i);
                    if (results.size() >= MAX_RESULTS) break;
                }
            }
            
            // 滑动窗口
            offset += (got - (target.size() - 1));
        }
    }
    
    // 输出结果（十六进制地址，逗号分隔）
    printf("{\"status\":\"ok\",\"count\":%zu,\"addrs\":\"", results.size());
    for (size_t i = 0; i < results.size(); i++) {
        if (i > 0) printf(",");
        printf("%lx", results[i]);
    }
    printf("\"}\n");
    fflush(stdout);
}

// 范围搜索
static void cmd_search_range(int pid, const std::vector<Region>& regions, int type_size,
                             int64_t low_bound, int64_t high_bound) {
    std::vector<uint64_t> results;
    std::vector<uint8_t> buf(BUFFER_SIZE);
    
    for (const auto& reg : regions) {
        uint64_t offset = 0;
        while (offset < reg.size && results.size() < MAX_RESULTS) {
            size_t to_read = (size_t)((reg.size - offset) < buf.size() ? (reg.size - offset) : buf.size());
            ssize_t got = read_process_memory(pid, reg.start + offset, buf.data(), to_read);
            
            if (got <= 0) {
                offset += to_read;
                continue;
            }
            
            for (size_t i = 0; i + type_size <= (size_t)got; i += type_size) {
                int64_t val = 0;
                memcpy(&val, &buf[i], type_size);
                if (type_size == 4) val = (int32_t)val;
                
                if (val >= low_bound && val <= high_bound) {
                    results.push_back(reg.start + offset + i);
                    if (results.size() >= MAX_RESULTS) break;
                }
            }
            
            offset += (got - (type_size - 1));
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

// AOB 特征码搜索
static void cmd_search_aob(int pid, const std::vector<Region>& regions,
                          const std::vector<uint8_t>& pattern, const std::vector<uint8_t>& mask) {
    std::vector<uint64_t> results;
    std::vector<uint8_t> buf(BUFFER_SIZE);
    
    for (const auto& reg : regions) {
        uint64_t offset = 0;
        while (offset < reg.size && results.size() < MAX_RESULTS) {
            size_t to_read = (size_t)((reg.size - offset) < buf.size() ? (reg.size - offset) : buf.size());
            ssize_t got = read_process_memory(pid, reg.start + offset, buf.data(), to_read);
            
            if (got <= 0) {
                offset += to_read;
                continue;
            }
            
            for (size_t i = 0; i + pattern.size() <= (size_t)got; i++) {
                bool match = true;
                for (size_t j = 0; j < pattern.size(); j++) {
                    if (mask[j] == 1 && buf[i + j] != pattern[j]) {
                        match = false;
                        break;
                    }
                }
                if (match) {
                    results.push_back(reg.start + offset + i);
                    if (results.size() >= MAX_RESULTS) break;
                }
            }
            
            offset += (got - (pattern.size() - 1));
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

// 模糊搜索
static void cmd_search_fuzzy(int pid, const std::vector<uint64_t>& addrs,
                            const std::vector<uint8_t>& old_vals, int mode, int type_size) {
    std::vector<uint64_t> results;
    uint8_t cur_buf[8];
    
    for (size_t i = 0; i < addrs.size(); i++) {
        ssize_t got = read_process_memory(pid, addrs[i], cur_buf, type_size);
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

// 读取内存
static void cmd_read(int pid, uint64_t addr, int size) {
    std::vector<uint8_t> buf(size);
    ssize_t got = read_process_memory(pid, addr, buf.data(), size);
    
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

// 写入内存
static void cmd_write(int pid, uint64_t addr, const std::vector<uint8_t>& data) {
    ssize_t wr = write_process_memory(pid, addr, data.data(), data.size());
    
    if (wr == (ssize_t)data.size()) {
        printf("{\"status\":\"ok\"}\n");
    } else {
        printf("{\"status\":\"error\",\"msg\":\"write failed\"}\n");
    }
    fflush(stdout);
}

// 简单的 JSON 解析（仅支持本程序需要的格式）
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
        
        // 提取 regions（简化：假设格式正确）
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
        
        cmd_search_exact(pid, regions, type_size, target);
        
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
        
        cmd_search_range(pid, regions, type_size, low, high);
        
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
    return 0;
}
