package com.biliseek.app

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.biliseek.core.network.BiliNetwork
import okhttp3.OkHttpClient

/**
 * 装配全局 [ImageLoader]。
 *
 * **封面必须带 Referer。** B 站图片 CDN（`i0.hdslb.com`）对热链有校验，
 * 拿默认 OkHttp 直接请求会 403。这与 `PlaybackService` 给 ExoPlayer 的 DataSource
 * 注入请求头是同一件事，头也复用同一份 [BiliNetwork.mediaRequestHeaders]，
 * 不再发明第二套。
 */
class BiliSeekApp : Application(), SingletonImageLoader.Factory {

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        val headers = BiliNetwork.get(this).mediaRequestHeaders
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .apply { headers.forEach { (name, value) -> header(name, value) } }
                    .build()
                chain.proceed(request)
            }
            .build()

        return ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { client })) }
            .build()
    }
}
