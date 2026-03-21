package com.tradingplatform.app.data.api.interceptor

enum class TimeoutProfile(val connectMs: Int, val readMs: Int, val writeMs: Int) {
    FAST(connectMs = 5_000, readMs = 5_000, writeMs = 5_000),
    MEDIUM(connectMs = 10_000, readMs = 10_000, writeMs = 10_000),
    STANDARD(connectMs = 15_000, readMs = 15_000, writeMs = 15_000),
    SLOW(connectMs = 30_000, readMs = 30_000, writeMs = 30_000),
}
