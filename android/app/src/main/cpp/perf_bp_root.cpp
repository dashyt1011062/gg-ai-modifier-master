#include <errno.h>
#include <fcntl.h>
#include <linux/hw_breakpoint.h>
#include <linux/perf_event.h>
#include <poll.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/syscall.h>
#include <unistd.h>

#include <dirent.h>
#include <string>
#include <vector>

#ifndef HW_BREAKPOINT_EMPTY
#define HW_BREAKPOINT_EMPTY 0
#endif

static long perf_event_open_call(struct perf_event_attr *attr, pid_t pid, int cpu, int group_fd, unsigned long flags) {
    return syscall(__NR_perf_event_open, attr, pid, cpu, group_fd, flags);
}

static uint64_t parse_u64(const char *s) {
    if (!s) return 0;
    if (strlen(s) > 2 && s[0] == '0' && (s[1] == 'x' || s[1] == 'X')) return strtoull(s + 2, nullptr, 16);
    size_t n = strlen(s);
    if (n > 1 && (s[n - 1] == 'h' || s[n - 1] == 'H')) {
        std::string tmp(s, n - 1);
        return strtoull(tmp.c_str(), nullptr, 16);
    }
    return strtoull(s, nullptr, 0);
}

static int parse_bp_type(const char *mode) {
    if (!mode) return HW_BREAKPOINT_W;
    if (!strcmp(mode, "r")) return HW_BREAKPOINT_R;
    if (!strcmp(mode, "w")) return HW_BREAKPOINT_W;
    if (!strcmp(mode, "rw") || !strcmp(mode, "wr")) return HW_BREAKPOINT_R | HW_BREAKPOINT_W;
    if (!strcmp(mode, "x") || !strcmp(mode, "exec")) return HW_BREAKPOINT_X;
    return HW_BREAKPOINT_W;
}

static std::vector<pid_t> list_tids(pid_t pid) {
    std::vector<pid_t> tids;
    char path[128];
    snprintf(path, sizeof(path), "/proc/%d/task", pid);
    DIR *dir = opendir(path);
    if (!dir) {
        tids.push_back(pid);
        return tids;
    }
    struct dirent *ent;
    while ((ent = readdir(dir)) != nullptr) {
        if (ent->d_name[0] == '.') continue;
        char *end = nullptr;
        long tid = strtol(ent->d_name, &end, 10);
        if (end && *end == '\0' && tid > 0) tids.push_back((pid_t)tid);
    }
    closedir(dir);
    if (tids.empty()) tids.push_back(pid);
    return tids;
}

static int open_bp_for_tid(pid_t tid, uint64_t addr, uint64_t len, int bp_type, int disabled) {
    struct perf_event_attr attr;
    memset(&attr, 0, sizeof(attr));
    attr.type = PERF_TYPE_BREAKPOINT;
    attr.size = sizeof(attr);
    attr.config = 0;
    attr.sample_period = 1;
    attr.sample_type = PERF_SAMPLE_IP | PERF_SAMPLE_TID | PERF_SAMPLE_ADDR;
    attr.disabled = disabled ? 1 : 0;
    attr.exclude_kernel = 1;
    attr.exclude_hv = 1;
    attr.wakeup_events = 1;
    attr.bp_type = bp_type;
    attr.bp_addr = addr;
    attr.bp_len = len;
    return (int)perf_event_open_call(&attr, tid, -1, -1, PERF_FLAG_FD_CLOEXEC);
}

static const char* type_name(int bp_type) {
    if (bp_type == HW_BREAKPOINT_R) return "r";
    if (bp_type == HW_BREAKPOINT_W) return "w";
    if (bp_type == (HW_BREAKPOINT_R | HW_BREAKPOINT_W)) return "rw";
    if (bp_type == HW_BREAKPOINT_X) return "x";
    return "?";
}

static void print_json_error(const char *stage, int err) {
    printf("{\"status\":\"error\",\"stage\":\"%s\",\"errno\":%d,\"message\":\"%s\"}\n", stage, err, strerror(err));
    fflush(stdout);
}

static int cmd_probe() {
    int fd = open_bp_for_tid(getpid(), (uint64_t)(uintptr_t)&fd, sizeof(int), HW_BREAKPOINT_W, 1);
    if (fd < 0) {
        print_json_error("perf_event_open", errno);
        return 1;
    }
    close(fd);
    printf("{\"status\":\"ok\",\"helper\":\"perf_bp_root\",\"breakpoint\":true}\n");
    fflush(stdout);
    return 0;
}

static int cmd_wait(int argc, char **argv) {
    if (argc < 7) {
        printf("{\"status\":\"error\",\"message\":\"usage: wait <pid> <addr> <len> <r|w|rw|x> <timeout_ms>\"}\n");
        return 2;
    }
    pid_t pid = (pid_t)strtol(argv[2], nullptr, 10);
    uint64_t addr = parse_u64(argv[3]);
    uint64_t len = parse_u64(argv[4]);
    int bp_type = parse_bp_type(argv[5]);
    int timeout_ms = (int)strtol(argv[6], nullptr, 10);
    if (pid <= 0 || addr == 0 || len == 0 || timeout_ms < 0) {
        printf("{\"status\":\"error\",\"message\":\"bad arguments\"}\n");
        return 2;
    }
    if (!(len == 1 || len == 2 || len == 4 || len == 8)) len = 4;

    std::vector<pid_t> tids = list_tids(pid);
    std::vector<int> fds;
    std::vector<pid_t> attached;
    int first_errno = 0;
    for (pid_t tid : tids) {
        int fd = open_bp_for_tid(tid, addr, len, bp_type, 1);
        if (fd >= 0) {
            fds.push_back(fd);
            attached.push_back(tid);
        } else if (first_errno == 0) {
            first_errno = errno;
        }
    }
    if (fds.empty()) {
        print_json_error("perf_event_open", first_errno ? first_errno : errno);
        return 1;
    }

    std::vector<struct pollfd> pfds(fds.size());
    for (size_t i = 0; i < fds.size(); ++i) {
        ioctl(fds[i], PERF_EVENT_IOC_RESET, 0);
        ioctl(fds[i], PERF_EVENT_IOC_ENABLE, 0);
        pfds[i].fd = fds[i];
        pfds[i].events = POLLIN | POLLERR | POLLHUP;
        pfds[i].revents = 0;
    }

    int ret = poll(pfds.data(), pfds.size(), timeout_ms);
    pid_t hit_tid = 0;
    uint64_t count = 0;
    if (ret > 0) {
        for (size_t i = 0; i < pfds.size(); ++i) {
            if (pfds[i].revents) {
                hit_tid = attached[i];
                uint64_t data[3] = {0, 0, 0};
                ssize_t n = read(pfds[i].fd, data, sizeof(data));
                if (n >= (ssize_t)sizeof(uint64_t)) count = data[0];
                break;
            }
        }
    }

    for (int fd : fds) {
        ioctl(fd, PERF_EVENT_IOC_DISABLE, 0);
        close(fd);
    }

    if (ret < 0) {
        print_json_error("poll", errno);
        return 1;
    }
    if (ret == 0) {
        printf("{\"status\":\"timeout\",\"pid\":%d,\"addr\":%llu,\"len\":%llu,\"mode\":\"%s\",\"threads\":%zu}\n",
               pid, (unsigned long long)addr, (unsigned long long)len, type_name(bp_type), attached.size());
        fflush(stdout);
        return 0;
    }

    printf("{\"status\":\"hit\",\"pid\":%d,\"tid\":%d,\"addr\":%llu,\"len\":%llu,\"mode\":\"%s\",\"count\":%llu,\"threads\":%zu}\n",
           pid, hit_tid, (unsigned long long)addr, (unsigned long long)len, type_name(bp_type), (unsigned long long)count, attached.size());
    fflush(stdout);
    return 0;
}

int main(int argc, char **argv) {
    if (argc < 2) {
        printf("{\"status\":\"error\",\"message\":\"usage: perf_bp_root probe|wait ...\"}\n");
        return 2;
    }
    if (!strcmp(argv[1], "probe")) return cmd_probe();
    if (!strcmp(argv[1], "wait")) return cmd_wait(argc, argv);
    printf("{\"status\":\"error\",\"message\":\"unknown command\"}\n");
    return 2;
}
