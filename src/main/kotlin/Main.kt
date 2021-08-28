import java.io.File


fun main() {
    val test = HttpTest(useGzip = true, local = false, directApi = true, cache = true, malformed = false)
    repeat(1) {
        test.testPost()
//        test.testGet("UfQkBe3IwVVpuqGv1dSi")
    }

}

