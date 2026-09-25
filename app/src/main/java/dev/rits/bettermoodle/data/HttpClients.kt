package dev.rits.bettermoodle.data

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * アプリ共通の OkHttpClient (G11)。接続プールとスレッドプールを1組にまとめる。
 * 用途ごとに設定を変える場合は [shared] の `newBuilder()` から派生させる (プールは共有される)。
 */
object HttpClients {
    val shared: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
