#include "io_redirect.h"
#include <android/log.h>
#include <cstring>
#include <cstdio>
#include <string>
#include <vector>

#define TAG "proc_virtual"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)

// ── Virtual /proc ──────────────────────────────────────────────────
// Filters /proc/self/maps and similar to hide virtual engine traces

struct MapEntry {
    std::string original_line;
    bool should_hide;
};

static const char* HIDDEN_LIBS[] = {
    "libvengine.so",
    "libfake-su.so",
    "com.vspace.app",
    "com.vspace.stub",
    nullptr
};

/**
 * Check if a /proc/maps line should be hidden.
 * Hides memory mappings of our native libraries.
 */
bool should_hide_maps_line(const char* line) {
    if (!line) return false;
    for (int i = 0; HIDDEN_LIBS[i] != nullptr; i++) {
        if (strstr(line, HIDDEN_LIBS[i]) != nullptr) {
            return true;
        }
    }
    return false;
}

/**
 * Filter /proc/self/maps content.
 * Returns a new string with hidden lines removed.
 */
std::string filter_proc_maps(const char* original) {
    std::string result;
    const char* line_start = original;
    const char* line_end;

    while ((line_end = strchr(line_start, '\n')) != nullptr) {
        std::string line(line_start, line_end - line_start + 1);
        if (!should_hide_maps_line(line.c_str())) {
            result += line;
        } else {
            LOGD("filter_proc_maps: hiding line: %s", line.c_str());
        }
        line_start = line_end + 1;
    }

    // Handle last line (no trailing newline)
    if (*line_start) {
        std::string line(line_start);
        if (!should_hide_maps_line(line.c_str())) {
            result += line;
        }
    }

    return result;
}

/**
 * Check if a /proc path is for memory maps.
 */
bool is_proc_maps_path(const char* path) {
    if (!path) return false;
    return strstr(path, "/maps") != nullptr;
}

/**
 * Check if a /proc path is for cmdline.
 */
bool is_proc_cmdline_path(const char* path) {
    if (!path) return false;
    return strstr(path, "/cmdline") != nullptr;
}

/**
 * Get virtual process name for /proc/self/cmdline.
 */
const char* get_virtual_cmdline() {
    // Return a normal-looking cmdline
    return "com.target.app";
}
