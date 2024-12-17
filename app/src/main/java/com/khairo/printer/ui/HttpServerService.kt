package com.khairo.printer.ui

import android.app.*
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.khairo.printer.R
import com.khairo.escposprinter.EscPosPrinter
import com.khairo.escposprinter.connection.usb.UsbPrintersConnections
import com.khairo.escposprinter.textparser.PrinterTextParserImg
import kotlinx.coroutines.*
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import org.json.JSONObject

class HttpServerService : Service() {
    private var isRunning = false
    private lateinit var serverSocket: ServerSocket
    private val port = 9080

    override fun onCreate() {
        super.onCreate()
        startForegroundService() // Cria a notificação persistente
        startHttpServer() // Inicia o servidor HTTP
    }

    private fun startForegroundService() {
        val channelId = "http_server_channel"
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "HTTP Server", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Servidor HTTP Ativo")
            .setContentText("Escutando requisições na porta $port")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()

        startForeground(1, notification)
    }

    private fun startHttpServer() {
        isRunning = true
        CoroutineScope(Dispatchers.IO).launch {
            serverSocket = ServerSocket(port)
            while (isRunning) {
                val clientSocket: Socket = serverSocket.accept()
                handleClient(clientSocket)
            }
        }
    }

    private fun handleClient(clientSocket: Socket) {
        clientSocket.use { socket ->
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val requestLine = reader.readLine() ?: return

            // Verifica o método HTTP
            if (requestLine.contains("OPTIONS")) {
                // Responder a requisições OPTIONS para CORS
                val responseHeaders = """
                HTTP/1.1 204 No Content
                Access-Control-Allow-Origin: *
                Access-Control-Allow-Methods: GET, POST, OPTIONS
                Access-Control-Allow-Headers: Content-Type
                Access-Control-Max-Age: 3600
                
            """.trimIndent()
                socket.getOutputStream().write(responseHeaders.toByteArray())
                return
            }

            // Verificar se a requisição é para /print
            if (requestLine.contains("POST /print")) {
                val headers = mutableListOf<String>()
                var line: String?
                while (reader.readLine().also { line = it } != null && line!!.isNotEmpty()) {
                    headers.add(line!!)
                }

                val contentLength = headers.find { it.startsWith("Content-Length:") }
                    ?.split(":")?.get(1)?.trim()?.toInt() ?: 0
                val body = CharArray(contentLength)
                reader.read(body, 0, contentLength)

                val requestBody = String(body)
                println("Request Body: $requestBody")

                processPrintRequest(requestBody)

                val response = "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n\r\nPrint triggered via USB"
                socket.getOutputStream().write(response.toByteArray())
            } else {
                val response = "HTTP/1.1 404 Not Found\r\nContent-Type: text/plain\r\n\r\nInvalid Endpoint"
                socket.getOutputStream().write(response.toByteArray())
            }
        }
    }

    fun formatTicket(data: JSONObject): String {
        try {
            // Configura a conexão da impressora
            val usbConnection = UsbPrintersConnections.selectFirstConnected(this)
            if (usbConnection == null) {
                return "Erro: Nenhuma impressora USB conectada."
            }

            // Inicializa a impressora com a configuração de DPI e largura
            val printer = EscPosPrinter(usbConnection, 203, 48f, 32)

            // Converte a imagem para hexadecimal com base no DPI da impressora
            val imageHex = PrinterTextParserImg.bitmapToHexadecimalString(
                printer,
                applicationContext.resources.getDrawableForDensity(
                    R.drawable.logoh2l,
                    DisplayMetrics.DENSITY_MEDIUM
                )
            )

            // Extração dos dados do JSON
            val unidade = "Minha unidade" // Pode ser fixo ou extraído de outro campo
            val ticketFormat = data.getJSONObject("senha").getString("format")
            val prioridade = data.getJSONObject("prioridade").getString("nome")
            val servico = data.getJSONObject("servico").getString("nome")
            val dataChegada = data.getString("dataChegada")
            val horaChegada = dataChegada.substring(11, 16) // Extrai a hora no formato HH:mm
            val dataFormatada = dataChegada.substring(0, 10) // Extrai a data no formato AAAA-MM-DD

            val horarioLocal = normalizeText("Horário local")

            // Montagem do ticket formatado
            val stringBuilder = StringBuilder()
            stringBuilder.append("[C]<img>$imageHex</img>\n") // Adiciona a imagem
            stringBuilder.append("[L]\n")
            stringBuilder.append("[C]<font size='big'>$unidade</font>\n")
            stringBuilder.append("[C]Novo SGA\n\n")

            stringBuilder.append("[C]<font size='tall'>$prioridade</font>\n\n")
            stringBuilder.append("[C]<font size='big'>$ticketFormat</font>\n\n")

            stringBuilder.append("[C]$servico\n\n")

            stringBuilder.append("[C]$dataFormatada\n")
            stringBuilder.append("[C]Hora de chegada $horaChegada\n")
            stringBuilder.append("[C]( $horarioLocal )\n\n")

            stringBuilder.append("[C]Novo SGA\n")
            stringBuilder.append("[L]\n")
            stringBuilder.append("[L]\n")

            return stringBuilder.toString()
        } catch (e: Exception) {
            e.printStackTrace()
            return "Erro ao formatar o ticket."
        }
    }

    fun normalizeText(text: String): String {
        return text.replace("á", "a")
            .replace("é", "e")
            .replace("í", "i")
            .replace("ó", "o")
            .replace("ú", "u")
            .replace("Á", "A")
            .replace("É", "E")
            .replace("Í", "I")
            .replace("Ó", "O")
            .replace("Ú", "U")
            .replace("ç", "c")
            .replace("Ç", "C")
            .replace("ã", "a")
            .replace("õ", "o")
            .replace("â", "a")
            .replace("ê", "e")
            .replace("ô", "o")
    }

    fun printCustomContent(content: String) {
        val usbConnection = UsbPrintersConnections.selectFirstConnected(this)
        if (usbConnection == null) {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(this, "No USB printer found.", Toast.LENGTH_SHORT).show()
            }
            return
        }
        try {
            val printer = EscPosPrinter(usbConnection, 203, 48f, 32)
            printer.printFormattedTextAndCut(content)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun processPrintRequest(requestBody: String) {
        try {
            val jsonData = JSONObject(requestBody)

            // Formata o ticket
            val formattedTicket = formatTicket(jsonData)

            // Envia para impressão
            printCustomContent(formattedTicket)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        isRunning = false
        serverSocket.close()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
