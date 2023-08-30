/*
 * Copyright (C) 2016 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package okhttp3.internal.http

import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.Connection
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.internal.checkDuration
import okhttp3.internal.connection.Exchange
import okhttp3.internal.connection.RealCall

/**
 * A concrete interceptor chain that carries the entire interceptor chain: all application
 * interceptors, the OkHttp core, all network interceptors, and finally the network caller.
 * 承载整个拦截器链的具体拦截器链：所有应用程序拦截器、OkHttp 核心、所有网络拦截器，最后是网络调用方。
 *
 * If the chain is for an application interceptor then [exchange] must be null. Otherwise it is for
 * a network interceptor and [exchange] must be non-null.
 * 如果链用于应用程序拦截器，则exchange（交换）必须为空。否则，它适用于网络拦截器，交换必须为非空。
 */
class RealInterceptorChain(
  internal val call: RealCall,
  private val interceptors: List<Interceptor>,
  private val index: Int,
  internal val exchange: Exchange?,
  internal val request: Request,
  internal val connectTimeoutMillis: Int,
  internal val readTimeoutMillis: Int,
  internal val writeTimeoutMillis: Int
) : Interceptor.Chain {

  private var calls: Int = 0

  internal fun copy(
    index: Int = this.index,
    exchange: Exchange? = this.exchange,
    request: Request = this.request,
    connectTimeoutMillis: Int = this.connectTimeoutMillis,
    readTimeoutMillis: Int = this.readTimeoutMillis,
    writeTimeoutMillis: Int = this.writeTimeoutMillis
  ) = RealInterceptorChain(call, interceptors, index, exchange, request, connectTimeoutMillis,
      readTimeoutMillis, writeTimeoutMillis)

  override fun connection(): Connection? = exchange?.connection

  override fun connectTimeoutMillis(): Int = connectTimeoutMillis

  override fun withConnectTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain {
    check(exchange == null) { "Timeouts can't be adjusted in a network interceptor" }

    return copy(connectTimeoutMillis = checkDuration("connectTimeout", timeout.toLong(), unit))
  }

  override fun readTimeoutMillis(): Int = readTimeoutMillis

  override fun withReadTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain {
    check(exchange == null) { "Timeouts can't be adjusted in a network interceptor" }

    return copy(readTimeoutMillis = checkDuration("readTimeout", timeout.toLong(), unit))
  }

  override fun writeTimeoutMillis(): Int = writeTimeoutMillis

  override fun withWriteTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain {
    check(exchange == null) { "Timeouts can't be adjusted in a network interceptor" }

    return copy(writeTimeoutMillis = checkDuration("writeTimeout", timeout.toLong(), unit))
  }

  override fun call(): Call = call

  override fun request(): Request = request

  @Throws(IOException::class)
  override fun proceed(request: Request): Response {
    check(index < interceptors.size)

    calls++

    //exchange在执行连接拦截器前都是为null的，它是对请求流的一个封装
    if (exchange != null) {
      //当请求流不为null时，我们需要检查Url或者端口是否被改变，如果被改变直接报错，因为在建立连接后它不允许被改变
      check(exchange.finder.sameHostAndPort(request.url)) {
        "network interceptor ${interceptors[index - 1]} must retain the same host and port"
      }
      //连接拦截器及其后续拦截器只能执行一次proceed方法
      check(calls == 1) {
        "network interceptor ${interceptors[index - 1]} must call proceed() exactly once"
      }
    }

    // Call the next interceptor in the chain.
    // 通过copy创建一个RealInterceptorChain，这里传入的index是在当前index的基础上加1。
    val next = copy(index = index + 1, request = request)
    //获取指定index上的Interceptor，index是不可变局部变量
    val interceptor = interceptors[index]

    @Suppress("USELESS_ELVIS")
    //调用拦截器的拦截方法，并返回response
    val response = interceptor.intercept(next) ?: throw NullPointerException(
        "interceptor $interceptor returned null")

    //确保连接拦截器及后面的拦截器（除最后一个拦截器外）必须执行一次
    if (exchange != null) {
      check(index + 1 >= interceptors.size || next.calls == 1) {
        "network interceptor $interceptor must call proceed() exactly once"
      }
    }

    check(response.body != null) { "interceptor $interceptor returned a response with no body" }

    return response
  }
}
