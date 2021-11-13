import jetbrains.letsPlot.geom.geomPoint
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.nio.file.Paths
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.*
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test

//interface CrashEntry {
//    lastRead: Date
//    uploadDate: Date
//    size: number
//}
//
//type DatabaseOverview = CrashEntry[]




class OnDemandActions {
    @Test
    fun `Download Database Overview`() = runBlocking {
        with(HttpTest(local = false)) {
            val response = downloadDatabaseOverview(System.getenv("AdminPassword"))
            databaseOverviewFile.writeText(response.body!!)
        }
    }

    //  Data A: {
    // Graph A: Date -> Size

    @Test
    fun `Analyze Database Overview`() {
        val overview = readDatabaseOverview()
        val totalSize = overview.sumOf { it.size }
        println(totalSize / 1024)
    }

//    @Test
//    fun `Show Database Overview Graphs`() {
//
//    }
}