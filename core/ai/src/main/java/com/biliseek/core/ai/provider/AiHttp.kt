package com.biliseek.core.ai.provider

import okhttp3.CookieJar
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 超时（工单 §3.7.2）。推理模型会先思考很久，所以首个事件给得宽；之后两个事件之间超时即判中断。
 */
internal data class AiTimeouts(
    val connectMs: Long = 15_000,
    /** 流式：请求发出到第一个 SSE 事件。 */
    val firstEventMs: Long = 120_000,
    /** 流式：两个 SSE 事件之间（思考增量也算事件）。 */
    val idleMs: Long = 60_000,
    /** 非流式：整个调用。 */
    val callMs: Long = 180_000,
)

/**
 * 从调用方给的 client 派生出本模块专用的 client：**清空全部拦截器、不带 Cookie**。
 *
 * 结构性保证工单 §3.6：即使调用方误把挂着 B 站 Cookie / 拦截器的 client 传进来，
 * 也不会把 SESSDATA 带给第三方 AI 主机。连接池与线程池仍然共享。
 */
internal fun OkHttpClient.isolatedForAi(timeouts: AiTimeouts): OkHttpClient =
    newBuilder()
        .apply {
            interceptors().clear()
            networkInterceptors().clear()
        }
        .cookieJar(CookieJar.NO_COOKIES)
        .connectTimeout(timeouts.connectMs, TimeUnit.MILLISECONDS)
        // 读超时兜底用首事件时长；事件间的空闲超时由流式读取的看门狗负责
        .readTimeout(timeouts.firstEventMs, TimeUnit.MILLISECONDS)
        .writeTimeout(timeouts.connectMs, TimeUnit.MILLISECONDS)
        .build()
