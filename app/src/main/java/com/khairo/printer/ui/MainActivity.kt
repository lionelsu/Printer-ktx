package com.khairo.printer.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.PendingIntent
import android.app.assist.AssistContent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.AsyncTask
import com.khairo.printer.R
import android.os.Bundle
import android.os.Parcelable
import android.util.DisplayMetrics
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.databinding.DataBindingUtil
import androidx.lifecycle.lifecycleScope
import com.khairo.async.AsyncBluetoothEscPosPrint
import com.khairo.async.AsyncEscPosPrinter
import com.khairo.async.AsyncUsbEscPosPrint
import com.khairo.coroutines.CoroutinesEscPosPrint
import com.khairo.coroutines.CoroutinesEscPosPrinter
import com.khairo.escposprinter.EscPosPrinter
import com.khairo.escposprinter.connection.DeviceConnection
import com.khairo.escposprinter.connection.tcp.TcpConnection
import com.khairo.escposprinter.connection.usb.UsbConnection
import com.khairo.escposprinter.connection.usb.UsbPrintersConnections
import com.khairo.escposprinter.exceptions.EscPosBarcodeException
import com.khairo.escposprinter.exceptions.EscPosConnectionException
import com.khairo.escposprinter.exceptions.EscPosEncodingException
import com.khairo.escposprinter.exceptions.EscPosParserException
import com.khairo.escposprinter.textparser.PrinterTextParserImg
import com.khairo.printer.databinding.ActivityMainBinding
import com.khairo.printer.utils.printViaWifi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import com.khairo.async.*
import org.json.JSONObject
import java.util.*

class MainActivity : AppCompatActivity() {

    private var isServerRunning = false

    private lateinit var binding: ActivityMainBinding

    private var printer: CoroutinesEscPosPrinter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DataBindingUtil.setContentView(this, R.layout.activity_main)

        initializeUsbPrinter()

        binding.apply {
            buttonTcp.setOnClickListener {
                lifecycleScope.launch(Dispatchers.IO) {
                    printTcp()
                }
            }

            buttonBluetooth.setOnClickListener {
                printBluetooth()
            }

            buttonUsb.setOnClickListener {
                printUsb()
            }
        }
        // Teste
        // Iniciar servidor HTTP em paralelo
        // CoroutineScope(Dispatchers.IO).launch {
        //     startHttpServer(9080)
        // }
        val serviceIntent = Intent(this, HttpServerService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
    }


    override fun onDestroy() {
        super.onDestroy()
        isServerRunning = false
    }

    fun initializeUsbPrinter() {
        val usbConnection = UsbPrintersConnections.selectFirstConnected(this)
        val usbManager = this.getSystemService(USB_SERVICE) as UsbManager
        if (usbConnection == null) {
            println("No USB printer found at initialization.")
            return
        }
        // Solicita permissão sem acionar impressão
        val permissionIntent = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION), 0)
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        registerReceiver(usbNewReceiver, filter)
        usbManager.requestPermission(usbConnection.device, permissionIntent)
    }

    private fun startHttpServer(port: Int) {
        isServerRunning = true
        val serverSocket = ServerSocket(port)

        while (isServerRunning) {
            try {
                val clientSocket: Socket = serverSocket.accept()
                handleClient(clientSocket)
            } catch (e: Exception) {
                e.printStackTrace()
                break
            }
        }
        serverSocket.close()
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

    fun printCustomContent(content: String) {
        val usbConnection = UsbPrintersConnections.selectFirstConnected(this)
        if (usbConnection == null) {
            runOnUiThread {
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

    /*==============================================================================================
    ======================================BLUETOOTH PART============================================
    ==============================================================================================*/
    private val PERMISSION_BLUETOOTH = 1

    private fun printBluetooth() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.BLUETOOTH),
                PERMISSION_BLUETOOTH
            )
        } else {
            // this.printIt(BluetoothPrintersConnections.selectFirstPaired());
            AsyncBluetoothEscPosPrint(this).execute(this.getAsyncEscPosPrinter(null))
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String?>,
        grantResults: IntArray
    ) {
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            when (requestCode) {
                PERMISSION_BLUETOOTH -> printBluetooth()
            }
        }
    }

    /*==============================================================================================
    ===========================================USB PART=============================================
    ==============================================================================================*/
    private val ACTION_USB_PERMISSION = "com.android.example.USB_PERMISSION"
    private val usbReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            if (ACTION_USB_PERMISSION == action) {
                synchronized(this) {
                    val usbManager = getSystemService(USB_SERVICE) as UsbManager
                    val usbDevice =
                        intent.getParcelableExtra<Parcelable>(UsbManager.EXTRA_DEVICE) as UsbDevice?
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        if (usbDevice != null) {
                            // printIt(new UsbConnection(usbManager, usbDevice));
                            AsyncUsbEscPosPrint(context)
                                .execute(
                                    getAsyncEscPosPrinter(
                                        UsbConnection(
                                            usbManager,
                                            usbDevice
                                        )
                                    )
                                )
                        }
                    }
                }
            }
        }
    }

    private val usbNewReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            if (ACTION_USB_PERMISSION == action) {
                synchronized(this) {
                    val usbManager = getSystemService(USB_SERVICE) as UsbManager
                    val usbDevice =
                        intent.getParcelableExtra<Parcelable>(UsbManager.EXTRA_DEVICE) as UsbDevice?
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        println("Usb permission granted")
                    } else {
                        println("Usb permissin denied")
                    }
                }
            }
        }
    }

    fun printUsb() {
        val usbConnection = UsbPrintersConnections.selectFirstConnected(this)
        val usbManager = this.getSystemService(USB_SERVICE) as UsbManager
        if (usbConnection == null) {
            AlertDialog.Builder(this)
                .setTitle("USB Connection")
                .setMessage("No USB printer found.")
                .show()
            return
        }
        val permissionIntent = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION), 0)
        val filter = IntentFilter(ACTION_USB_PERMISSION)

        registerReceiver(usbReceiver, filter)
        usbManager.requestPermission(usbConnection.device, permissionIntent)
    }

    /*==============================================================================================
    ===================================ESC/POS PRINTER PART=========================================
    ==============================================================================================*/
    /**
     * Synchronous printing
     */
    @SuppressLint("SimpleDateFormat")
    fun printIt(printerConnection: DeviceConnection?) {
        AsyncTask.execute {
            try {
                val format = SimpleDateFormat("'on' yyyy-MM-dd 'at' HH:mm:ss")
                val printer = EscPosPrinter(
                    printerConnection,
                    203,
                    48f,
                    32
                )
                printer
                    .printFormattedText(
                        "[C]<img>" + PrinterTextParserImg.bitmapToHexadecimalString(
                            printer,
                            applicationContext.resources.getDrawableForDensity(
                                R.drawable.logo,
                                DisplayMetrics.DENSITY_MEDIUM
                            )
                        ) + "</img>\n" +
                                "[L]\n" +
                                "[C]<u><font size='big'>ORDER N°045</font></u>\n" +
                                "[C]<font size='small'>" + format.format(Date()) + "</font>\n" +
                                "[L]\n" +
                                "[C]==================عربي تيست هههههههه==============\n" +
                                "[L]\n" +
                                "[L]<b>BEAUTIFUL SHIRT</b>[R]9.99e\n" +
                                "[L]  + Size : S\n" +
                                "[L]\n" +
                                "[L]<b>AWESOME HAT</b>[R]24.99e\n" +
                                "[L]  + Size : 57/58\n" +
                                "[L]\n" +
                                "[C]--------------------------------\n" +
                                "[R]TOTAL PRICE :[R]34.98e\n" +
                                "[R]TAX :[R]4.23e\n" +
                                "[L]\n" +
                                "[C]================================\n" +
                                "[L]\n" +
                                "[L]<font size='tall'>Customer :</font>\n" +
                                "[L]Raymond DUPONT\n" +
                                "[L]5 rue des girafes\n" +
                                "[L]31547 PERPETES\n" +
                                "[L]Tel : +33801201456\n" +
                                "[L]\n" +
                                "[C]<barcode type='128' height='10'>83125478455134567890</barcode>\n" +
                                "[C]<qrcode size='20'>http://www.developpeur-web.khairo.com/</qrcode>" +
                                "[L]\n" +
                                "[L]\n" +
                                "[L]\n" +
                                "[L]\n" +
                                "[L]\n" +
                                "[L]\n"
                    )
            } catch (e: EscPosConnectionException) {
                e.printStackTrace()
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Broken connection")
                    .setMessage(e.message)
                    .show()
            } catch (e: EscPosParserException) {
                e.printStackTrace()
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Invalid formatted text")
                    .setMessage(e.message)
                    .show()
            } catch (e: EscPosEncodingException) {
                e.printStackTrace()
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Bad selected encoding")
                    .setMessage(e.message)
                    .show()
            } catch (e: EscPosBarcodeException) {
                e.printStackTrace()
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Invalid barcode")
                    .setMessage(e.message)
                    .show()
            }
        }
    }

    /**
     * Asynchronous printing
     */
    @SuppressLint("SimpleDateFormat")
    fun getAsyncEscPosPrinter(printerConnection: DeviceConnection?): AsyncEscPosPrinter {
        val format = SimpleDateFormat("'on' yyyy-MM-dd 'at' HH:mm:ss")
        val printer = AsyncEscPosPrinter(printerConnection!!, 203, 48f, 32)
        return printer.setTextToPrint(
            "[C]<img>" + PrinterTextParserImg.bitmapToHexadecimalString(
                printer,
                this.applicationContext.resources.getDrawableForDensity(
                    R.drawable.logo,
                    DisplayMetrics.DENSITY_MEDIUM
                )
            ) + "</img>\n" +
                    "[L]\n" +
                    "[C]<u><font size='big'>ORDER N°045</font></u>\n" +
                    "[L]\n" +
                    "[C]<u type='double'>" + format.format(Date()) + "</u>\n" +
                    "[C]\n" +
                    "[C]================================\n" +
                    "[L]\n" +
                    "[L]<b>BEAUTIFUL SHIRT</b>[R]9.99e\n" +
                    "[L]  + Size : S\n" +
                    "[L]\n" +
                    "[L]<b>AWESOME HAT</b>[R]24.99e\n" +
                    "[L]  + Size : 57/58\n" +
                    "[L]\n" +
                    "[C]--------------------------------\n" +
                    "[R]TOTAL PRICE :[R]34.98e\n" +
                    "[R]TAX :[R]4.23e\n" +
                    "[L]\n" +
                    "[C]================================\n" +
                    "[L]\n" +
                    "[L]<u><font color='bg-black' size='tall'>Customer :</font></u>\n" +
                    "[L]Raymond DUPONT\n" +
                    "[L]5 rue des girafes\n" +
                    "[L]31547 PERPETES\n" +
                    "[L]Tel : +33801201456\n" +
                    "\n" +
                    "[C]<barcode type='128' height='10'>83125478455134567890</barcode>\n" +
                    "[L]\n" +
                    "[C]<qrcode size='20'>http://www.developpeur-web.khairo.com/</qrcode>\n" +
                    "[L]\n" +
                    "[L]\n" +
                    "[L]\n" +
                    "[L]\n" +
                    "[L]\n" +
                    "[L]\n"
        )
    }

    /*==============================================================================================
    =========================================TCP PART===============================================
    ==============================================================================================*/
    private suspend fun printTcp() {
        try {
            printer =
                CoroutinesEscPosPrinter(
                    TcpConnection(
                        binding.tcpIp.text.toString(),
                        binding.tcpPort.text.toString().toInt()
                    ).apply { connect(this@MainActivity) }, 203, 48f, 32
                )

//             this.printIt(new TcpConnection(ipAddress.getText().toString(), Integer.parseInt(portAddress.getText().toString())));
//            AsyncTcpEscPosPrint(this).execute(printer.setTextToPrint(test))

            CoroutinesEscPosPrint(this).execute(
                printViaWifi(
                    printer!!,
                    45,
                    body,
                    34.98f,
                    4,
                    customer,
                    "83125478455134567890"
                )
            ).apply { printer = null }

        } catch (e: NumberFormatException) {
            AlertDialog.Builder(this)
                .setTitle("Invalid TCP port address")
                .setMessage("Port field must be a number.")
                .show()
            e.printStackTrace()
        }
    }

    private val body: String
        get() = "[L]\n" +
                "[L]    <b>Pizza</b>[R][R]3[R][R]55 $\n" +
                "[L]      + Olive[R][R]1 $\n" +
                "[L]      + Cheese[R][R]5 $\n" +
                "[L]      + Mushroom[R][R]7 $\n" +
                "[L]\n" +
                "[L]    <b>Burger</b>[R][R]7[R][R]43.54 $\n" +
                "[L]      + Cheese[R][R]3 $\n" +
                "[L]\n" +
                "[L]    <b>Shawarma</b>[R][R]2[R][R]4 $\n" +
                "[L]      + Garlic[R][R]0.5 $\n" +
                "[L]\n" +
                "[L]    <b>Steak</b>[R][R]3[R][R]75 $\n" +
                "[L]\n" +
                "[R] PAYMENT METHOD :[R]Visa\n"

    private val customer: String
        get() =
            "[C]================================\n" +
                    "[L]\n" +
                    "[L]<b>Delivery</b>[R]5 $\n" +
                    "[L]\n" +
                    "[L]<u><font color='bg-black' size='tall'>Customer :</font></u>\n" +
                    "[L]Name : Mohammad khair\n" +
                    "[L]Phone : 00962787144627\n" +
                    "[L]Area : Khalda\n" +
                    "[L]street : testing street\n" +
                    "[L]building : 9\n" +
                    "[L]Floor : 2\n" +
                    "[L]Apartment : 1\n" +
                    "[L]Note : This order is just for testing\n"
}
