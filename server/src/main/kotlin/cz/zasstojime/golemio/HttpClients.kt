package cz.zasstojime.golemio

import java.net.ProxySelector
import java.net.http.HttpClient
import java.time.Duration

object HttpClients {
    /** Respektuje systémový proxy (`https.proxyHost`), jinak jde napřímo. */
    val default: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .proxy(ProxySelector.getDefault())
        .build()
}
