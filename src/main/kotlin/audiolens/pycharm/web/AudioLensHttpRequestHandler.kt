package audiolens.pycharm.web

import com.intellij.openapi.application.ApplicationManager
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpHeaderValues
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpUtil
import io.netty.handler.codec.http.HttpVersion
import io.netty.handler.codec.http.QueryStringDecoder
import org.jetbrains.ide.HttpRequestHandler

class AudioLensHttpRequestHandler : HttpRequestHandler() {
    override fun isSupported(request: FullHttpRequest): Boolean =
        request.method() == HttpMethod.GET && QueryStringDecoder(request.uri()).path().startsWith("/audiolens/")

    override fun process(
        urlDecoder: QueryStringDecoder,
        request: FullHttpRequest,
        context: ChannelHandlerContext,
    ): Boolean {
        val parts = urlDecoder.path().removePrefix("/").split('/')
        if (parts.size != 3 || parts[0] != "audiolens") return false
        val registry = ApplicationManager.getApplication().getService(AudioLensSessionRegistry::class.java)
        val session = registry.find(parts[1]) ?: return send(context, request, HttpResponseStatus.NOT_FOUND, "text/plain", "Not found".toByteArray())
        return try {
            when (parts[2]) {
                "index.html" -> send(context, request, HttpResponseStatus.OK, "text/html; charset=utf-8", session.html().toByteArray())
                "webview.js" -> send(context, request, HttpResponseStatus.OK, "text/javascript; charset=utf-8", registry.webviewBytes)
                "source" -> {
                    val offset = parameter(urlDecoder, "offset")?.toLongOrNull() ?: error("Invalid offset")
                    val length = parameter(urlDecoder, "length")?.toIntOrNull() ?: error("Invalid length")
                    val stamp = parameter(urlDecoder, "stamp") ?: error("Missing source stamp")
                    send(context, request, HttpResponseStatus.OK, "application/octet-stream", session.source.read(offset, length, stamp))
                }
                "payload" -> {
                    val id = parameter(urlDecoder, "id") ?: error("Missing payload id")
                    val bytes = session.payloads.take(id)
                        ?: return send(context, request, HttpResponseStatus.GONE, "text/plain", "Payload expired".toByteArray())
                    send(context, request, HttpResponseStatus.OK, "application/octet-stream", bytes)
                }
                else -> send(context, request, HttpResponseStatus.NOT_FOUND, "text/plain", "Not found".toByteArray())
            }
        } catch (error: Exception) {
            send(context, request, HttpResponseStatus.BAD_REQUEST, "text/plain; charset=utf-8", (error.message ?: "Bad request").toByteArray())
        }
    }

    private fun parameter(decoder: QueryStringDecoder, name: String): String? = decoder.parameters()[name]?.singleOrNull()

    private fun send(
        context: ChannelHandlerContext,
        request: FullHttpRequest,
        status: HttpResponseStatus,
        contentType: String,
        bytes: ByteArray,
    ): Boolean {
        val response = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, Unpooled.wrappedBuffer(bytes))
        response.headers()[HttpHeaderNames.CONTENT_TYPE] = contentType
        response.headers()[HttpHeaderNames.CONTENT_LENGTH] = bytes.size
        response.headers()[HttpHeaderNames.CACHE_CONTROL] = "no-store, max-age=0"
        response.headers()[HttpHeaderNames.PRAGMA] = "no-cache"
        response.headers()["X-Content-Type-Options"] = "nosniff"
        response.headers()["Content-Security-Policy"] = "default-src 'self'; img-src 'self' blob: data:; media-src 'self' blob: data:; style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline'; worker-src blob:; connect-src 'self' blob: data:"
        if (HttpUtil.isKeepAlive(request)) {
            response.headers()[HttpHeaderNames.CONNECTION] = HttpHeaderValues.KEEP_ALIVE
        }
        context.writeAndFlush(response)
        return true
    }
}
