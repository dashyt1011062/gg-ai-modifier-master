// GG-AI root memory scanner.
// The command protocol is intentionally small, but every command always emits
// exactly one JSON response so the Kotlin side can safely serialize requests.

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <iostream>
#include <string>
#include <unistd.h>
#include <vector>

#define BUFFER_SIZE (64 * 1024)
#define MAX_RESULTS 500

enum FuzzyMode { CHANGED = 0, UNCHANGED = 1, INCREASED = 2, DECREASED = 3 };

struct Region {
    uint64_t start;
    uint64_t size;
};

static int mem_fd = -1;

static void respond_error(const char* message) {
    std::printf("{\"status\":\"error\",\"msg\":\"%s\"}\n", message);
    std::fflush(stdout);
}

static void respond_results(const std::vector<uint64_t>& results) {
    std::printf("{\"status\":\"ok\",\"count\":%zu,\"addrs\":\"", results.size());
    for (size_t i = 0; i < results.size(); ++i) {
        if (i != 0) std::printf(",");
        std::printf("%llx", static_cast<unsigned long long>(results[i]));
    }
    std::printf("\"}\n");
    std::fflush(stdout);
}

static bool open_mem(int pid) {
    if (mem_fd >= 0) {
        close(mem_fd);
        mem_fd = -1;
    }
    char path[64];
    std::snprintf(path, sizeof(path), "/proc/%d/mem", pid);
    mem_fd = open(path, O_RDWR | O_CLOEXEC);
    return mem_fd >= 0;
}

static std::vector<uint8_t> hex_to_bytes(const std::string& hex) {
    std::vector<uint8_t> bytes;
    if ((hex.size() & 1U) != 0U) return bytes;
    bytes.reserve(hex.size() / 2U);
    for (size_t i = 0; i < hex.size(); i += 2U) {
        char* end = nullptr;
        const std::string token = hex.substr(i, 2U);
        const long value = std::strtol(token.c_str(), &end, 16);
        if (end == token.c_str() || *end != '\0') {
            bytes.clear();
            return bytes;
        }
        bytes.push_back(static_cast<uint8_t>(value));
    }
    return bytes;
}

static std::string bytes_to_hex(const uint8_t* bytes, size_t size) {
    static const char digits[] = "0123456789abcdef";
    std::string out(size * 2U, '0');
    for (size_t i = 0; i < size; ++i) {
        out[i * 2U] = digits[(bytes[i] >> 4U) & 0x0FU];
        out[i * 2U + 1U] = digits[bytes[i] & 0x0FU];
    }
    return out;
}

static bool parse_string_field(const std::string& json, const char* key, std::string& out) {
    const std::string marker = std::string("\"") + key + "\":\"";
    const size_t begin = json.find(marker);
    if (begin == std::string::npos) return false;
    const size_t value_begin = begin + marker.size();
    const size_t value_end = json.find('"', value_begin);
    if (value_end == std::string::npos) return false;
    out = json.substr(value_begin, value_end - value_begin);
    return true;
}

static bool parse_int_field(const std::string& json, const char* key, int64_t& out) {
    const std::string marker = std::string("\"") + key + "\":";
    const size_t begin = json.find(marker);
    if (begin == std::string::npos) return false;
    const char* value = json.c_str() + begin + marker.size();
    char* end = nullptr;
    out = std::strtoll(value, &end, 10);
    return end != value;
}

static bool parse_double_field(const std::string& json, const char* key, double& out) {
    const std::string marker = std::string("\"") + key + "\":";
    const size_t begin = json.find(marker);
    if (begin == std::string::npos) return false;
    const char* value = json.c_str() + begin + marker.size();
    char* end = nullptr;
    out = std::strtod(value, &end);
    return end != value;
}

static std::vector<Region> parse_regions(const std::string& json) {
    std::vector<Region> regions;
    const size_t list_begin = json.find("\"regions\":[");
    if (list_begin == std::string::npos) return regions;
    size_t cursor = list_begin + std::strlen("\"regions\":[");
    const size_t list_end = json.find(']', cursor);
    if (list_end == std::string::npos) return regions;

    while (cursor < list_end) {
        const size_t start_pos = json.find("\"start\":", cursor);
        const size_t size_pos = json.find("\"size\":", cursor);
        if (start_pos == std::string::npos || size_pos == std::string::npos ||
            start_pos >= list_end || size_pos >= list_end) {
            break;
        }

        char* start_end = nullptr;
        char* size_end = nullptr;
        const uint64_t start = std::strtoull(
            json.c_str() + start_pos + std::strlen("\"start\":"), &start_end, 10);
        const uint64_t size = std::strtoull(
            json.c_str() + size_pos + std::strlen("\"size\":"), &size_end, 10);
        if (start_end == json.c_str() + start_pos + std::strlen("\"start\":") ||
            size_end == json.c_str() + size_pos + std::strlen("\"size\":")) {
            break;
        }
        if (size > 0U) regions.push_back({start, size});

        const size_t object_end = json.find('}', size_pos);
        if (object_end == std::string::npos || object_end >= list_end) break;
        cursor = object_end + 1U;
    }
    return regions;
}

static std::vector<uint64_t> parse_addresses(const std::string& json) {
    std::vector<uint64_t> addresses;
    const size_t list_begin = json.find("\"addrs\":[");
    if (list_begin == std::string::npos) return addresses;
    size_t cursor = list_begin + std::strlen("\"addrs\":[");
    const size_t list_end = json.find(']', cursor);
    if (list_end == std::string::npos) return addresses;

    while (cursor < list_end) {
        while (cursor < list_end &&
               (json[cursor] == ' ' || json[cursor] == ',' || json[cursor] == '"')) {
            ++cursor;
        }
        if (cursor >= list_end) break;
        char* end = nullptr;
        const uint64_t address = std::strtoull(json.c_str() + cursor, &end, 16);
        if (end == json.c_str() + cursor) break;
        addresses.push_back(address);
        cursor = static_cast<size_t>(end - json.c_str());
    }
    return addresses;
}

static bool is_aligned(uint64_t relative_address, int type_size) {
    return type_size <= 1 || (relative_address % static_cast<uint64_t>(type_size)) == 0U;
}

static void search_exact(const std::vector<Region>& regions,
                         int type_size,
                         const std::vector<uint8_t>& target) {
    if (type_size <= 0 || target.empty()) {
        respond_error("invalid exact search parameters");
        return;
    }

    std::vector<uint64_t> results;
    std::vector<uint8_t> buffer(BUFFER_SIZE);
    const size_t overlap = target.size() > 1U ? target.size() - 1U : 0U;

    for (const Region& region : regions) {
        uint64_t offset = 0U;
        while (offset < region.size && results.size() < MAX_RESULTS) {
            const size_t requested = static_cast<size_t>(
                std::min<uint64_t>(region.size - offset, buffer.size()));
            const ssize_t read_size = pread64(mem_fd, buffer.data(), requested,
                                              static_cast<off64_t>(region.start + offset));
            if (read_size <= 0) {
                offset += requested;
                continue;
            }

            for (size_t i = 0; i + target.size() <= static_cast<size_t>(read_size); ++i) {
                const uint64_t relative = offset + i;
                if (!is_aligned(relative, type_size)) continue;
                if (std::memcmp(buffer.data() + i, target.data(), target.size()) == 0) {
                    results.push_back(region.start + relative);
                    if (results.size() >= MAX_RESULTS) break;
                }
            }

            const uint64_t advance = static_cast<uint64_t>(read_size) > overlap
                ? static_cast<uint64_t>(read_size) - overlap
                : static_cast<uint64_t>(read_size);
            if (advance == 0U) break;
            offset += advance;
        }
        if (results.size() >= MAX_RESULTS) break;
    }
    respond_results(results);
}

static int64_t read_integer(const uint8_t* data, const std::string& type) {
    if (type == "byte") {
        int8_t value = 0;
        std::memcpy(&value, data, sizeof(value));
        return static_cast<int64_t>(value);
    }
    if (type == "word") {
        int16_t value = 0;
        std::memcpy(&value, data, sizeof(value));
        return static_cast<int64_t>(value);
    }
    if (type == "dword") {
        int32_t value = 0;
        std::memcpy(&value, data, sizeof(value));
        return static_cast<int64_t>(value);
    }
    int64_t value = 0;
    std::memcpy(&value, data, sizeof(value));
    return value;
}

static double read_decimal(const uint8_t* data, const std::string& type) {
    if (type == "float") {
        float value = 0.0F;
        std::memcpy(&value, data, sizeof(value));
        return static_cast<double>(value);
    }
    double value = 0.0;
    std::memcpy(&value, data, sizeof(value));
    return value;
}

static void search_range(const std::vector<Region>& regions,
                         const std::string& type,
                         int type_size,
                         double low_decimal,
                         double high_decimal,
                         int64_t low_integer,
                         int64_t high_integer) {
    const bool decimal_type = type == "float" || type == "double";
    if (type_size <= 0 ||
        (decimal_type ? low_decimal > high_decimal : low_integer > high_integer)) {
        respond_error("invalid range search parameters");
        return;
    }
    std::vector<uint64_t> results;
    std::vector<uint8_t> buffer(BUFFER_SIZE);
    const size_t overlap = type_size > 1 ? static_cast<size_t>(type_size - 1) : 0U;

    for (const Region& region : regions) {
        uint64_t offset = 0U;
        while (offset < region.size && results.size() < MAX_RESULTS) {
            const size_t requested = static_cast<size_t>(
                std::min<uint64_t>(region.size - offset, buffer.size()));
            const ssize_t read_size = pread64(mem_fd, buffer.data(), requested,
                                              static_cast<off64_t>(region.start + offset));
            if (read_size <= 0) {
                offset += requested;
                continue;
            }

            for (size_t i = 0; i + static_cast<size_t>(type_size) <=
                                static_cast<size_t>(read_size); ++i) {
                const uint64_t relative = offset + i;
                if (!is_aligned(relative, type_size)) continue;

                bool match = false;
                if (decimal_type) {
                    const double value = read_decimal(buffer.data() + i, type);
                    match = std::isfinite(value) && value >= low_decimal && value <= high_decimal;
                } else {
                    const int64_t value = read_integer(buffer.data() + i, type);
                    match = value >= low_integer && value <= high_integer;
                }
                if (match) {
                    results.push_back(region.start + relative);
                    if (results.size() >= MAX_RESULTS) break;
                }
            }

            const uint64_t advance = static_cast<uint64_t>(read_size) > overlap
                ? static_cast<uint64_t>(read_size) - overlap
                : static_cast<uint64_t>(read_size);
            if (advance == 0U) break;
            offset += advance;
        }
        if (results.size() >= MAX_RESULTS) break;
    }
    respond_results(results);
}

static void search_aob(const std::vector<Region>& regions,
                       const std::vector<uint8_t>& pattern,
                       const std::vector<uint8_t>& mask) {
    if (pattern.empty() || pattern.size() != mask.size()) {
        respond_error("invalid aob pattern");
        return;
    }

    std::vector<uint64_t> results;
    std::vector<uint8_t> buffer(BUFFER_SIZE);
    const size_t overlap = pattern.size() > 1U ? pattern.size() - 1U : 0U;

    for (const Region& region : regions) {
        uint64_t offset = 0U;
        while (offset < region.size && results.size() < MAX_RESULTS) {
            const size_t requested = static_cast<size_t>(
                std::min<uint64_t>(region.size - offset, buffer.size()));
            const ssize_t read_size = pread64(mem_fd, buffer.data(), requested,
                                              static_cast<off64_t>(region.start + offset));
            if (read_size <= 0) {
                offset += requested;
                continue;
            }

            for (size_t i = 0; i + pattern.size() <= static_cast<size_t>(read_size); ++i) {
                bool match = true;
                for (size_t j = 0; j < pattern.size(); ++j) {
                    if (mask[j] != 0U && buffer[i + j] != pattern[j]) {
                        match = false;
                        break;
                    }
                }
                if (match) {
                    results.push_back(region.start + offset + i);
                    if (results.size() >= MAX_RESULTS) break;
                }
            }

            const uint64_t advance = static_cast<uint64_t>(read_size) > overlap
                ? static_cast<uint64_t>(read_size) - overlap
                : static_cast<uint64_t>(read_size);
            if (advance == 0U) break;
            offset += advance;
        }
        if (results.size() >= MAX_RESULTS) break;
    }
    respond_results(results);
}

static int compare_typed(const uint8_t* current,
                         const uint8_t* previous,
                         const std::string& type) {
    if (type == "float" || type == "double") {
        const double a = read_decimal(current, type);
        const double b = read_decimal(previous, type);
        if (a < b) return -1;
        if (a > b) return 1;
        return 0;
    }
    const int64_t a = read_integer(current, type);
    const int64_t b = read_integer(previous, type);
    if (a < b) return -1;
    if (a > b) return 1;
    return 0;
}

static void search_fuzzy(const std::vector<uint64_t>& addresses,
                         const std::vector<uint8_t>& previous_values,
                         int mode,
                         const std::string& type,
                         int type_size) {
    if (type_size <= 0 || previous_values.size() < addresses.size() *
                                              static_cast<size_t>(type_size)) {
        respond_error("invalid fuzzy search parameters");
        return;
    }

    std::vector<uint64_t> results;
    uint8_t current[8] = {0};
    for (size_t i = 0; i < addresses.size() && results.size() < MAX_RESULTS; ++i) {
        const ssize_t read_size = pread64(mem_fd, current, static_cast<size_t>(type_size),
                                          static_cast<off64_t>(addresses[i]));
        if (read_size != type_size) continue;
        const uint8_t* previous = previous_values.data() + i * static_cast<size_t>(type_size);
        const int comparison = compare_typed(current, previous, type);
        const bool equal = std::memcmp(current, previous, static_cast<size_t>(type_size)) == 0;

        bool match = false;
        switch (mode) {
            case CHANGED: match = !equal; break;
            case UNCHANGED: match = equal; break;
            case INCREASED: match = comparison > 0; break;
            case DECREASED: match = comparison < 0; break;
            default: break;
        }
        if (match) results.push_back(addresses[i]);
    }
    respond_results(results);
}

static void read_memory(uint64_t address, int size) {
    if (size <= 0 || size > 1024 * 1024) {
        respond_error("invalid read size");
        return;
    }
    std::vector<uint8_t> buffer(static_cast<size_t>(size));
    const ssize_t read_size = pread64(mem_fd, buffer.data(), buffer.size(),
                                      static_cast<off64_t>(address));
    if (read_size != size) {
        respond_error("read failed");
        return;
    }
    const std::string hex = bytes_to_hex(buffer.data(), buffer.size());
    std::printf("{\"status\":\"ok\",\"data\":\"%s\"}\n", hex.c_str());
    std::fflush(stdout);
}

static void write_memory(uint64_t address, const std::vector<uint8_t>& data) {
    if (data.empty()) {
        respond_error("empty write");
        return;
    }
    const ssize_t written = pwrite64(mem_fd, data.data(), data.size(),
                                     static_cast<off64_t>(address));
    if (written != static_cast<ssize_t>(data.size())) {
        respond_error("write failed");
        return;
    }
    std::printf("{\"status\":\"ok\"}\n");
    std::fflush(stdout);
}

static void execute_command(const std::string& json) {
    std::string command;
    int64_t pid_value = 0;
    if (!parse_string_field(json, "cmd", command) ||
        !parse_int_field(json, "pid", pid_value) || pid_value <= 0) {
        respond_error("invalid command");
        return;
    }
    if (!open_mem(static_cast<int>(pid_value))) {
        respond_error("failed to open mem");
        return;
    }

    if (command == "search_exact") {
        int64_t type_size_value = 0;
        std::string target_hex;
        if (!parse_int_field(json, "type_size", type_size_value) ||
            !parse_string_field(json, "target", target_hex)) {
            respond_error("missing exact search fields");
            return;
        }
        search_exact(parse_regions(json), static_cast<int>(type_size_value),
                     hex_to_bytes(target_hex));
        return;
    }

    if (command == "search_range") {
        int64_t type_size_value = 0;
        int64_t low_integer = 0;
        int64_t high_integer = 0;
        double low_decimal = 0.0;
        double high_decimal = 0.0;
        std::string type;
        if (!parse_string_field(json, "type", type) ||
            !parse_int_field(json, "type_size", type_size_value) ||
            !parse_int_field(json, "low", low_integer) ||
            !parse_int_field(json, "high", high_integer) ||
            !parse_double_field(json, "low", low_decimal) ||
            !parse_double_field(json, "high", high_decimal)) {
            respond_error("missing range search fields");
            return;
        }
        search_range(parse_regions(json), type, static_cast<int>(type_size_value),
                     low_decimal, high_decimal, low_integer, high_integer);
        return;
    }

    if (command == "search_aob") {
        std::string pattern_hex;
        std::string mask_hex;
        if (!parse_string_field(json, "pattern", pattern_hex) ||
            !parse_string_field(json, "mask", mask_hex)) {
            respond_error("missing aob fields");
            return;
        }
        search_aob(parse_regions(json), hex_to_bytes(pattern_hex), hex_to_bytes(mask_hex));
        return;
    }

    if (command == "search_fuzzy") {
        int64_t mode_value = 0;
        int64_t type_size_value = 0;
        std::string type;
        std::string old_values_hex;
        if (!parse_int_field(json, "mode", mode_value) ||
            !parse_int_field(json, "type_size", type_size_value) ||
            !parse_string_field(json, "type", type) ||
            !parse_string_field(json, "old_vals", old_values_hex)) {
            respond_error("missing fuzzy fields");
            return;
        }
        search_fuzzy(parse_addresses(json), hex_to_bytes(old_values_hex),
                     static_cast<int>(mode_value), type,
                     static_cast<int>(type_size_value));
        return;
    }

    if (command == "read") {
        int64_t address_value = 0;
        int64_t size_value = 0;
        if (!parse_int_field(json, "addr", address_value) ||
            !parse_int_field(json, "size", size_value)) {
            respond_error("missing read fields");
            return;
        }
        read_memory(static_cast<uint64_t>(address_value), static_cast<int>(size_value));
        return;
    }

    if (command == "write") {
        int64_t address_value = 0;
        std::string data_hex;
        if (!parse_int_field(json, "addr", address_value) ||
            !parse_string_field(json, "data", data_hex)) {
            respond_error("missing write fields");
            return;
        }
        write_memory(static_cast<uint64_t>(address_value), hex_to_bytes(data_hex));
        return;
    }

    respond_error("unknown command");
}

int main() {
    std::ios::sync_with_stdio(false);
    std::string line;
    while (std::getline(std::cin, line)) {
        if (line.empty()) {
            respond_error("empty command");
            continue;
        }
        execute_command(line);
    }
    if (mem_fd >= 0) close(mem_fd);
    return 0;
}
