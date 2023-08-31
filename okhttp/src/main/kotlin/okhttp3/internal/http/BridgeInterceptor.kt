/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package okhttp3.internal.http

import java.io.IOException
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.internal.toHostHeader
import okhttp3.internal.userAgent
import okio.GzipSource
import okio.buffer

/**
 * Bridges from application code to network code. First it builds a network request from a user
 * request. Then it proceeds to call the network. Finally it builds a user response from the network
 * response.
 * 翻译：从应用程序代码到网络代码的桥梁。首先，它根据用户请求构建网络请求。然后它继续调用网络。最后，它从网络响应构建用户响应。
 *
 * 根据[BridgeInterceptor.intercept]方法了解这里所谓的桥梁就是在请求发送之前，对请求进行加头部字段，发送之后获得
 * response对响应进行拆头部字段，cookie管理以及解压缩。
 */
class BridgeInterceptor(private val cookieJar: CookieJar) : Interceptor {

  @Throws(IOException::class)
  override fun intercept(chain: Interceptor.Chain): Response {
    //获取Request
    val userRequest = chain.request()
    val requestBuilder = userRequest.newBuilder()

    val body = userRequest.body
    if (body != null) {
      val contentType = body.contentType()
      if (contentType != null) {
        //在请求头部添加字段 Content-Type 也就是body的编码类型
        requestBuilder.header("Content-Type", contentType.toString())
      }

      val contentLength = body.contentLength()
      if (contentLength != -1L) {
        //如果出现contentLength不为 -1 则需要设置content-length字段，界定是否传输完成
        requestBuilder.header("Content-Length", contentLength.toString())
        requestBuilder.removeHeader("Transfer-Encoding")
      } else {
        //分块传输，所以我们不需要Content-Length来界定是否传输完成。
        requestBuilder.header("Transfer-Encoding", "chunked")
        requestBuilder.removeHeader("Content-Length")
      }
    }

    if (userRequest.header("Host") == null) {
      //添加Host头部字段
      requestBuilder.header("Host", userRequest.url.toHostHeader())
    }

    if (userRequest.header("Connection") == null) {
      //添加连接类型
      requestBuilder.header("Connection", "Keep-Alive")
    }

    // If we add an "Accept-Encoding: gzip" header field we're responsible for also decompressing
    // the transfer stream.
    //对非分片传输且未设置压缩方式的请求，这里默认使用gzip压缩。
    var transparentGzip = false
    if (userRequest.header("Accept-Encoding") == null && userRequest.header("Range") == null) {
      transparentGzip = true
      requestBuilder.header("Accept-Encoding", "gzip")
    }

    //添加cookie 用来跟踪用户身份
    val cookies = cookieJar.loadForRequest(userRequest.url)
    if (cookies.isNotEmpty()) {
      requestBuilder.header("Cookie", cookieHeader(cookies))
    }

    //用户代理
    if (userRequest.header("User-Agent") == null) {
      requestBuilder.header("User-Agent", userAgent)
    }

    //进入下一个拦截器，并获取响应数据
    val networkResponse = chain.proceed(requestBuilder.build())

    //cookie管理
    cookieJar.receiveHeaders(userRequest.url, networkResponse.headers)

    val responseBuilder = networkResponse.newBuilder()
        .request(userRequest)

    //解压缩、去头部字段
    if (transparentGzip &&
        "gzip".equals(networkResponse.header("Content-Encoding"), ignoreCase = true) &&
        networkResponse.promisesBody()) {
      val responseBody = networkResponse.body
      if (responseBody != null) {
        val gzipSource = GzipSource(responseBody.source())
        val strippedHeaders = networkResponse.headers.newBuilder()
            .removeAll("Content-Encoding")
            .removeAll("Content-Length")
            .build()
        responseBuilder.headers(strippedHeaders)
        val contentType = networkResponse.header("Content-Type")
        responseBuilder.body(RealResponseBody(contentType, -1L, gzipSource.buffer()))
      }
    }

    return responseBuilder.build()
  }

  /** Returns a 'Cookie' HTTP request header with all cookies, like `a=b; c=d`. */
  private fun cookieHeader(cookies: List<Cookie>): String = buildString {
    cookies.forEachIndexed { index, cookie ->
      if (index > 0) append("; ")
      append(cookie.name).append('=').append(cookie.value)
    }
  }
}
