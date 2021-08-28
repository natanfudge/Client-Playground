import java.io.File


val crash = File("crash.txt").readText()
fun main() {
    val test = HttpTest(useGzip = true, local = false, directApi = true, cache = true)
    repeat(10) {
//        test.testPost()
        test.testGet("UfQkBe3IwVVpuqGv1dSi")
    }

}

