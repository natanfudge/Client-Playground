import kotlinx.coroutines.runBlocking
import java.io.File


fun main() = runBlocking {
    with(HttpTest(local = false)) {

        val getResponse = getCrash("Vhorw9N8ROWEs1bahYa2")
        val getResponseBody = getResponse.body!!.string()
        println("Body = $getResponseBody")
    }

//    File("Hugh Mungus.txt").writeText(buildString { repeat(10_000_000) { append(getRandomString(1)) } })
}

