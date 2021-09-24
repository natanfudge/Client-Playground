import TestCrash.*
import okhttp3.*
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.*
import java.io.File
import java.io.IOException
import java.lang.Integer.min
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine


private fun <T> T.applyIf(case: Boolean, application: T. () -> Unit): T = if (case) apply(application) else this
private fun <T> T.mapIf(case: Boolean, application: (T) -> T): T = if (case) let(application) else this

enum class TestCrash {
    Forge,
    Fabric,
    Malformed,
    Huge
}

/////////////////
// Post:
//  - Local, No GZIP: 100ms
//  - Local, Yes GZIP: 100ms
//  - Online, No GZIP: 500-1200ms
//  - Online, GZIP: 300-400ms
//  - Online, GZIP through and through: ~200MS
/////////////////

fun getRandomString(length: Int): String {
    val allowedChars = ('A'..'Z') + ('a'..'z') + ('0'..'9')
    return (1..length)
        .map { allowedChars.random() }
        .joinToString("")
}

class HttpTest(
    local: Boolean = true,

    useGzip: Boolean = true,
    private val directApi: Boolean = true,
    cache: Boolean = true,
) {

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
        .readTimeout(10,TimeUnit.MINUTES)
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

    suspend fun makeRequest(request: Request): Response = suspendCoroutine { cont ->
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                cont.resume(response)
            }
        })
    }

    suspend fun uploadCrash(crash: TestCrash, config: Request.Builder.() -> Unit = {}): Response {
        val path = if (directApi) "uploadCrash" else "widgets/api/upload-crash"

        val crashText = getCrashLogContents(crash)

        val request = Request.Builder()
            .post(crashText.toRequestBody())
            .url("$domain/$path").apply(config).build()

        return makeRequest(request)
    }

    private fun httpParameters(vararg parameters: Pair<String,String?>) : String {
        val notNull = parameters.filter{it.second != null}
        if(notNull.isEmpty()) return  ""
        else return "?" + notNull.joinToString("&"){(k,v) -> "$k=$v"}
    }

    suspend fun deleteCrash(id: String?, key: String?): Response {
        val path = if (directApi) "deleteCrash" else "widgets/api/delete-crash"

        val request = Request.Builder()
            .delete()
            .url("$domain/$path" + httpParameters("crashId" to id, "key" to key))
            .build()

        return makeRequest(request)
    }

    suspend fun getCrash(id: String?) : Response {
        val path = if (directApi) "getCrash" else "widgets/api/get-crash"

        val request = Request.Builder()
            .cacheControl(CacheControl.Builder().build())
            .url(if (id == null) "$domain/${path}" else "$domain/${path}/$id").build()

        return makeRequest(request)
    }

    private fun testRequest(request: Request) {
        val startTime = System.currentTimeMillis()
        client.newCall(request).execute().use { response ->
            val body = response.body!!.string();
            println("Got response: ${body.substring(0, min(100, body.length))} with code ${response.code}")
            val endTime = System.currentTimeMillis()
            println("Time taken: ${endTime - startTime}ms")
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

 fun getCrashLogContents(crash: TestCrash) = when (crash) {
    Forge -> File("forge_crash.txt").readText()
    Fabric -> File("fabric_crash.txt").readText()
    Malformed -> File("malformed_crash.txt").readText()
    Huge -> buildString { repeat(10_000_000) { append(getRandomString(1)) } }
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
