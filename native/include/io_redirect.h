#pragma once

#include <jni.h>
#include <cstdint>
#include <cstddef>

/**
 * IO redirection header.
 * Provides functions to intercept and redirect file system paths
 * for virtual apps running in the sandbox.
 */

void io_redirect_init(const char* virtual_root);
void io_redirect_add_package(const char* package_name, const char* data_dir);
const char* redirect_path(const char* path);
