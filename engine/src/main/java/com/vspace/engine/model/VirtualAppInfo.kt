package com.vspace.engine.model

import android.graphics.drawable.Drawable

data class VirtualAppInfo(
    val packageName: String,
    val name: String,
    val apkPath: String,
    val dataDir: String,
    val versionName: String,
    val versionCode: Int,
    val stubProcessIndex: Int,
    val icon: Drawable?
)
