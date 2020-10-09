package moe.lemonneko.gdh

import com.alibaba.fastjson.JSONException
import com.alibaba.fastjson.JSONObject
import io.ktor.application.*
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.request.*
import io.ktor.features.*
import io.ktor.http.cio.websocket.*
import io.ktor.http.content.*
import io.ktor.routing.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.websocket.*
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.lemonneko.nekogit.NekoGit
import moe.lemonneko.nekogit.cmds.GitClone
import moe.lemonneko.nekogit.exceptions.CloneException
import org.slf4j.LoggerFactory
import java.io.*
import java.net.SocketTimeoutException
import java.net.URL
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipOutputStream
import kotlin.collections.set
import kotlin.math.floor

private val logger = LoggerFactory.getLogger("GDHApplicationKt")
private val sessions = ArrayList<DefaultWebSocketServerSession>()

private val repoDir = File(System.getProperty("user.home") + File.separator + "repos")
private val archiveDir = File(System.getProperty("user.home") + File.separator + "archives")

private val httpClient = HttpClient(OkHttp)

private const val HOUR = 1000L * 60 * 60
private const val DAY = HOUR * 24

fun main() {
    println("=========================================================")
    println("|                                                       |")
    println("|      GGGGGGGGGG      DDDDDDDDDD      HHH      HHH     |")
    println("|     GGG              DDD     DDD     HHH      HHH     |")
    println("|    GGG     GGGGGG    DDD      DDD    HHHHHHHHHHHH     |")
    println("|     GGG      GGG     DDD     DDD     HHH      HHH     |")
    println("|      GGGGGGGGGG      DDDDDDDDDD      HHH      HHH     |")
    println("|                                                       |")
    println("|   - Help You To Downloading Resources From Github -   |")
    println("|                                                       |")
    println("=========================================================")

    NekoGit.init()

    val oldFileDeleteJob = GlobalScope.launch {
        logger.info("old file delete job started.")
        while (true) {
            archiveDir.listFiles { f ->
                System.currentTimeMillis() - f.lastModified() > DAY
            }?.forEach {
                logger.info("deleting old file: ${it.name}")
                it.delete()
            }
            delay(HOUR)
        }
    }

    Runtime.getRuntime().addShutdownHook(Thread {
        NekoGit.destroy()
        oldFileDeleteJob.cancel()
        logger.info("old file delete job stopped")
    })

    embeddedServer(
        Netty,
        port = 4000,
        module = Application::mainModule
    ).start(wait = true)

    NekoGit.destroy()
}

fun Application.mainModule() {
    install(WebSockets)
    routing {
        webSocket(path = "/websocket", handler = DefaultWebSocketServerSession::websocket)
        files()
    }
}

private suspend fun DefaultWebSocketServerSession.websocket() {
    logger.info("websocket connected.")
    if (!sessions.contains(this)) {
        sessions.add(this)
        logger.info("added this session to session list.")
        sessions.forEach {
            it.sendJson(listOf("online" to sessions.size.toString()))
        }
    }
    try {
        var count = 0
        for (frame in incoming) {
            count++
            logger.info("received message count: $count")
            processMessage(frame)
            logger.info("message process done")
        }
    } catch (e: ClosedReceiveChannelException) {
        logger.info("session closed with: ${closeReason.await()}")
    } catch (e: Throwable) {
        logger.error("session error with: ${closeReason.await()}", e)
    }
    logger.info("session closed.")
    if (sessions.contains(this)) {
        sessions.remove(this)
        logger.info("removed this session from session list.")
        sessions.forEach {
            it.sendJson(listOf("online" to sessions.size.toString()))
        }
    }
}

private suspend fun DefaultWebSocketServerSession.processMessage(frame: Frame) {
    if (frame is Frame.Text) {
        val text = frame.readText()
        logger.info("received message: $text")
        try {
            sendStatus(Status.PARSING)
            val jsonObject = JSONObject.parseObject(text)
            val request = jsonObject.getString("request")
                ?: throw BadRequestException("request format error, request not found")
            val url = jsonObject.getString("url")
            val token = jsonObject.getString("token")
            when (request) {
                "check" -> {
                    token ?: throw BadRequestException("request format error, token not found")
                    doCheck(token)
                }
                "download" -> {
                    url ?: throw BadRequestException("request format error, url not found")
                    val fileName = url.substring(url.lastIndexOf('/') + 1)
                    sendStatus(Status.DOWNLOADING, "0")
                    doDownload(url, {
                        sendStatus(Status.DOWNLOADING, it.toString())
                        logger.info("progress: $it")
                    }) {
                        throw it
                    }
                    sendStatus(Status.COMPLETED, fileName)
                    logger.info("done")
                }
                "clone" -> {
                    url ?: throw BadRequestException("request format error, url not found")
                    doClone(url, this::onCloneProgress) {
                        throw it
                    }
                }
                else -> throw BadRequestException("request method error: $request")
            }
        } catch (e: Throwable) {
            when (e) {
                is JSONException -> sendStatus(Status.ERROR, "请求不是JSON，这是严重错误，请联系柠喵")
                is NullPointerException -> sendStatus(Status.ERROR, "请求格式错误，这是严重错误，请联系柠喵")
                is SocketTimeoutException -> sendStatus(Status.ERROR, "Github没有响应，请重试")
                is CloneException -> sendStatus(Status.ERROR, "仓库不存在或者仓库是私有仓库")
                is FileNotFoundException -> sendStatus(Status.ERROR, "文件不存在，请检查链接是否正确")
                is BadRequestException -> sendStatus(Status.ERROR, "请求格式错误，这是严重错误，请联系柠喵")
                else -> {
                    logger.error("error: ${e.message}", e)
                    sendStatus(Status.ERROR, "未知错误，请联系柠喵")
                }
            }
        }
    }
}

private suspend fun DefaultWebSocketServerSession.onCloneProgress(progress: Int, repo: File) {
    if (progress != 100) {
        sendStatus(Status.CHECKING_OUT, progress.toString())
    } else {
        val output = doZip(repo)
        sendStatus(Status.COMPLETED, output)
        repo.delete()
    }
}

private suspend fun DefaultWebSocketServerSession.doCheck(token: String) {
    val request =
        httpClient.post<String>("https://www.recaptcha.net/recaptcha/api/siteverify?secret=6LdjY9AZAAAAAFYOcL3znvRS08uQPFCopYGRjW1m&response=$token")
    val jsonObject = JSONObject.parseObject(request)
    val success = jsonObject.getBoolean("success")
    if (success) {
        sendStatus(Status.CHECKING, "success")
    }
}

private fun DefaultWebSocketServerSession.doDownload(
    url: String,
    progress: suspend (Int) -> Unit,
    onError: (Throwable) -> Unit
) {
    logger.info("do download, url=$url")
    val fileName = url.substring(url.lastIndexOf('/') + 1)
    logger.info("file name: $fileName")
    val file = File(archiveDir, fileName)

    saveFileFromURL(url, file, progress, onError)
}

private fun DefaultWebSocketServerSession.saveFileFromURL(
    url: String,
    file: File,
    progress: suspend (Int) -> Unit,
    onError: (Throwable) -> Unit
) {
    val connection = URL(url).openConnection()
    connection.connectTimeout = 5000

    val contentLength: Float

    contentLength = connection.contentLength.toFloat()

    logger.info("connected, content length: $contentLength")
    var i = 0F

    if (!archiveDir.exists()) {
        archiveDir.mkdirs()
    }

    if (!file.exists()) {
        file.createNewFile()
    }

    val out = BufferedOutputStream(FileOutputStream(file))

    val input: BufferedInputStream

    try {
        input = connection.getInputStream().buffered()
    } catch (e: Throwable) {
        out.close()
        onError(e)
        return
    }

    var byte: Int
    do {
        byte = input.read()
        out.write(byte)
        i++
        if (contentLength > 0 && (i % (1024 * 64)) == 0F) {
            launch {
                progress(floor((i / contentLength) * 100).toInt())
            }
        }
    } while (byte != -1)

    out.flush()
    out.close()
    input.close()
}

private fun DefaultWebSocketServerSession.doClone(
    url: String,
    onProgress: suspend (Int, File) -> Unit,
    onError: (Throwable) -> Unit
) {
    logger.info("do clone, url=$url")
    launch {
        sendStatus(Status.CHECKING_OUT, "0")
    }
    val repo = File(repoDir, url.substring(url.lastIndexOf('/') + 1))

    if (repo.exists()) {
        logger.warn("repo exists, deleting...")
        repo.delete()
    }

    @Suppress("ObjectLiteralToLambda")
    val fetchCallback = object : GitClone.FetchCallback {
        override fun progress(receiveProgress: Int, indexProgress: Int) {
            launch {
                onProgress(indexProgress, repo)
            }
        }
    }

    @Suppress("ObjectLiteralToLambda")
    val checkoutCallback = object : GitClone.CheckoutCallback {
        override fun progress(it: Int) {
            launch {
                onProgress(it, repo)
            }
        }
    }

    @Suppress("ObjectLiteralToLambda")
    val errorCallback = object : GitClone.ErrorCallback {
        override fun handleError(e: Throwable) {
            onError(e)
        }
    }

    GitClone.doClone(url, repo.absolutePath, fetchCallback, checkoutCallback, errorCallback)
}

fun Routing.files() {
    static("/files") {
        staticRootFolder = File(System.getProperty("user.home") + "/archives")
        files(".")
    }
}

private fun doZip(file: File): String {
    if (!archiveDir.exists()) {
        archiveDir.mkdirs()
    }
    val outputFile = File(archiveDir, file.name + ".zip")
    if (!outputFile.exists()) {
        outputFile.createNewFile()
    }

    val zout = ZipOutputStream(FileOutputStream(outputFile))
    val bout = zout.buffered()

    doZip0(file, zout, bout, "")

    bout.close()
    zout.close()
    return outputFile.name
}

@Throws(ZipException::class, IOException::class)
private fun doZip0(file: File, zout: ZipOutputStream, bout: BufferedOutputStream, path: String) {
    println("compressing: ${path + File.separator + file.name}")
    if (file.isDirectory) {
        file.listFiles()?.forEach {
            doZip0(it, zout, bout, path + File.separator + file.name)
        }
    } else {
        val zipEntry = ZipEntry(path + File.separator + file.name)
        zout.putNextEntry(zipEntry)
        bout.write(file.readBytes())
        bout.flush()
    }
    zout.closeEntry()
}

private suspend fun DefaultWebSocketServerSession.sendJson(messagePairs: List<Pair<String, String>>) {
    val jsonObject = JSONObject()
    messagePairs.forEach {
        jsonObject[it.first] = it.second
    }
    send(Frame.Text(jsonObject.toJSONString()))
}

private suspend fun DefaultWebSocketServerSession.sendStatus(status: Status, text: String = "") = sendJson(
    listOf(
        "status" to status.value,
        "text" to text
    )
)

private enum class Status(val value: String) {
    PARSING("parsing"),
    CHECKING_OUT("checking out"),
    DOWNLOADING("downloading"),
    CHECKING("checking"),
    ERROR("error"),
    COMPLETED("completed")
}