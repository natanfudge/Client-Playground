import okhttp3.*
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.*
import java.io.File
import java.io.IOException
import java.lang.Integer.min


private fun <T> T.applyIf(case: Boolean, application: T. () -> Unit): T = if (case) apply(application) else this

/////////////////
// Post:
//  - Local, No GZIP: 100ms
//  - Local, Yes GZIP: 100ms
//  - Online, No GZIP: 500-1200ms
//  - Online, GZIP: 300-400ms
//  - Online, GZIP through and through: ~200MS
/////////////////
class HttpTest(
    private val useGzip: Boolean,
    private val local: Boolean,
    private val directApi: Boolean,
    private val cache: Boolean,
    private val malformed: Boolean
) {
    private val crash = File(if (malformed) "malformed_crash.txt" else "crash.txt").readText()

    private val client = OkHttpClient.Builder()
        .applyIf(cache) {
            cache(
                Cache(
                    directory = File("http_cache"),
                    // $0.05 worth of phone storage in 2020
                    maxSize = 50L * 1024L * 1024L, // 50 MiB

                )
            )
        }
        .applyIf(useGzip) { addInterceptor(GzipRequestInterceptor()) }
        .addInterceptor(LoggingInterceptor())
        .addInterceptor(GzipResponseInterceptor())
        .eventListener(object : EventListener() {
            override fun cacheHit(call: Call, response: Response) {
                println("Hit cache")
            }

            override fun cacheMiss(call: Call) {
                println("Miss cache")
            }

            override fun cacheConditionalHit(call: Call, cachedResponse: Response) {
                println("Conditional hit ")
            }
        })
        .build()

    private val domain = if (local) "http://localhost:5001/crashy-9dd87/europe-west1"
    else "https://europe-west1-crashy-9dd87.cloudfunctions.net/"


    fun testPost() {
        val path = if (directApi) "uploadCrash" else "widgets/api/upload-crash"

        val request = Request.Builder()
            .post(crash.toRequestBody())
            .url("$domain/$path").build()

        testRequest(request)
    }

    //https://europe-west1-crashy-9dd87.cloudfunctions.net/getCrash
    fun testGet(id: String) {
        val path = if (directApi) "getCrash" else "widgets/api/get-crash"

        val request = Request.Builder()
            .cacheControl(CacheControl.Builder().build())
            .url("$domain/${path}/$id").build()

        testRequest(request)
    }

    private fun testRequest(request: Request) {
        val startTime = System.currentTimeMillis()
        client.newCall(request).execute().use { response ->
            val body = response.body!!.string();
            println("Got response: ${body.substring(0, min(100,body.length))} with code ${response.code}")
            val endTime = System.currentTimeMillis()
            println("Time taken: ${endTime - startTime}ms")
        }
    }

}

class LoggingInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {

//       println("Unzipped request: ${chain.request().body!!.source().unzip()}")
//       println("Zipped request: ${chain.request().body!!.source().buffer().readUtf8()}")
        return chain.proceed(chain.request())
    }

    fun RequestBody.source(): Source {
        val buffer = Buffer()
        writeTo(buffer)
        return buffer
    }

    private fun bodyToString(request: Request): String? {
        return try {
            val copy = request.newBuilder().build()
            val buffer = Buffer()
            copy.body!!.writeTo(buffer)
            buffer.readUtf8()
        } catch (e: IOException) {
            "did not work"
        }
    }
}

class GzipRequestInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest: Request = chain.request()

        // do not set content encoding in negative use case
        if (originalRequest.body == null || originalRequest.header("Content-Encoding") != null) {
            return chain.proceed(originalRequest)
        }
        val compressedRequest = originalRequest.newBuilder()
//            .header("Content-Encoding", "gzip")
            .header("Content-Type", "application/gzip")
            .method(originalRequest.method, gzip(originalRequest.body))
            .build()
        return chain.proceed(compressedRequest)
    }

    private fun gzip(body: RequestBody?): RequestBody {
        return object : RequestBody() {
            override fun contentType(): MediaType? {
                return body!!.contentType()
            }

            override fun contentLength(): Long {
                return -1 // We don't know the compressed length in advance!
            }

            override fun writeTo(sink: BufferedSink) {
                val gzipSink: BufferedSink = GzipSink(sink).buffer()
                body!!.writeTo(gzipSink)
                gzipSink.close()
            }
        }
    }
}

class GzipResponseInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val newRequest: Request.Builder = chain.request().newBuilder()
        newRequest.addHeader("Accept-Encoding", "gzip")
        val response: Response = chain.proceed(newRequest.build())
        return if (isGzipped(response)) {
            unzipResponse(response)
        } else {
            response
        }
    }

    private fun unzipResponse(response: Response): Response {
        if (response.body == null) {
            return response
        }
        val bodyString: String = response.body!!.source().unzip()
        val responseBody = bodyString.toResponseBody(response.body!!.contentType())
        val strippedHeaders = response.headers.newBuilder()
            .removeAll("Content-Encoding")
            .removeAll("Content-Length")
            .build()
        return response.newBuilder()
            .headers(strippedHeaders)
            .body(responseBody)
            .message(response.message)
            .build()
    }

    private fun isGzipped(response: Response): Boolean {
        return response.header("Content-Encoding") == "gzip"
    }
}

private fun Source.unzip(): String = gzip().buffer().readUtf8()
