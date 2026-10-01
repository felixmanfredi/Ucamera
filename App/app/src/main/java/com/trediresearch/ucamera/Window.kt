package com.trediresearch.ucamera

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.StrictMode
import android.os.StrictMode.ThreadPolicy
import android.text.InputType
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.View.OnTouchListener
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import com.trediresearch.ucamera.video.SerialH264Player
import com.trediresearch.ucamera.video.SerialJpegPlayer
import io.socket.client.Socket
import io.socket.emitter.Emitter
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import android.view.Surface
import android.graphics.SurfaceTexture
import android.view.TextureView
import com.trediresearch.ucamera.video.SerialPortConnection
import com.trediresearch.ucamera.webserver.CaptureResult
import com.trediresearch.ucamera.webserver.FOCUS_MANUAL
import com.trediresearch.ucamera.webserver.FOCUS_MODES
import com.trediresearch.ucamera.webserver.FOCUS_SINGLE
import com.trediresearch.ucamera.webserver.Webserver
import com.trediresearch.ucamera.webserver.dataset
import com.trediresearch.ucamera.webserver.focusModeDescription
import com.trediresearch.ucamera.webserver.focusModeLabel
import com.trediresearch.ucamera.webserver.settings

enum class FpvPreviewMode { SERIAL_H264, JPEG_SERIAL }

@RequiresApi(Build.VERSION_CODES.O)
class Window(private val context: Context) {

    var remote_host="192.168.1.145"
    var remote_port=45032
    var stream_port=8877
    // FPV preview source: JPEG_SERIAL (jpeg_udp_streamer.py -> SkydroidSerial2Net ESP32
    // bridge -> UART, same wire the REST bridge/H264 path already use), or
    // SERIAL_H264 (the original RTP/H264-over-serial path).
    var fpvPreviewMode = FpvPreviewMode.JPEG_SERIAL
    var onAcquisition=false;
    // Polling periodico dello stato acquisizione (sostituisce device_status via
    // Socket.IO, che richiede un vero percorso IP e non funziona sul solo bridge
    // seriale/radio Skydroid) - vedi startStatusPolling()/stopStatusPolling().
    private var statusPollingActive = false
    // Vero mentre uno scatto di prova e' in volo. Il polling salta il suo giro: sono due
    // round trip da fino a 6s ciascuno che terrebbero il lock di SerialBridge, mettendo
    // in coda la richiesta piu' fragile che abbiamo proprio quando conta.
    @Volatile
    private var captureInFlight = false
    private val statusPollHandler = Handler(Looper.getMainLooper())
    private val statusPollIntervalMs = 5000L
    val windowHeight=150
    val windowHeightMax=300
    val windowWidth=270
    // Larghezza della preview nella finestra compatta (come da layout). A tutto schermo
    // viene ricalcolata in base allo spazio disponibile, vedi applyPreviewSize().
    private val previewWidthCompact=150
    // Modalita' a tutto schermo: la finestra occupa tutto il display invece dei dp fissi
    // sopra, cosi' la preview e' grande abbastanza da valutare inquadratura e fuoco.
    private var isFullscreen=false
    // Posizione della finestra compatta prima di andare a tutto schermo: va ripristinata
    // all'uscita, altrimenti si torna alla posizione di default e non dove l'utente
    // aveva trascinato la finestra.
    private var compactX: Int? = null
    private var compactY: Int? = null
    var interval=5.0
    var camera_connected=false;

    // NON lateinit: i listener +/- sono registrati da initWindow() PRIMA che
    // updateConnection() abbia letto le impostazioni, e quel thread ha due uscite
    // anticipate (getVersion()/getSettings() falliti con camera spenta o radio non
    // agganciata). Con lateinit bastava aprire "Parametri" e premere un + per avere
    // UninitializedPropertyAccessException. Ora l'oggetto esiste sempre e
    // settingsLoaded dice se contiene dati veri: finche' e' false i controlli
    // restano disabilitati, invece di mostrare valori inventati come fossero reali.
    var settings: settings = settings()
    private var settingsLoaded = false
    lateinit var api: Webserver
    lateinit var s: SocketIOConnection

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val layoutInflater = context.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
    private val rootView = layoutInflater.inflate(R.layout.window, null)
    private val windowbar=rootView.findViewById<FrameLayout>(R.id.windowbar);
    lateinit var body:LinearLayout
    lateinit var main_panel:LinearLayout
    lateinit var settings_panel:FrameLayout
    lateinit var other_panel:FrameLayout

    lateinit var acquisition_panel:FrameLayout
    lateinit var brightness_control:FrameLayout;
    lateinit var contrast_control:FrameLayout;
    lateinit var sharpness_control:FrameLayout;
    lateinit var saturation_control:FrameLayout;
    lateinit var exposure_control:FrameLayout;
    lateinit var exposuretime_control:FrameLayout;
    lateinit var lensposition_control:FrameLayout;
    lateinit var interval_control:FrameLayout;
    lateinit var gain_control:FrameLayout;
    lateinit var btn_start_acquisition:Button;
    lateinit var btn_start_video:Button;

    lateinit var btn_preview_image:Button
    lateinit var btn_collapse:Button
    lateinit var btn_fullscreen:Button
    lateinit var btn_autofocus:Button
    lateinit var btn_ae:Button
    lateinit var btn_reset_settings:Button
    lateinit var preview_container:View
    //lateinit var preview: VLCVideoLayout //:WebView
    lateinit var preview: TextureView //:WebView
    // TextureView (not SurfaceView) because SurfaceView's independently-composited
    // layer doesn't get parented correctly by SurfaceFlinger inside a
    // TYPE_APPLICATION_OVERLAY window (confirmed via logcat: "Failed to find layer
    // (SurfaceView - #0) in layer parent (no-parent)"), leaving the preview blank
    // even though frames decode and draw without error.
    private var previewSurface: Surface? = null

    lateinit var status:TextView
    lateinit var depth:TextView
    lateinit var recording:ImageView

    lateinit var btn_open_acquisition:Button
    lateinit var btn_open_config:Button
    //lateinit var btn_upload_firmware:Button
    // Tempi di esposizione selezionabili, in MICROsecondi, dal piu' lungo al piu' corto.
    // Le due liste vanno tenute della stessa lunghezza: EXPOSURE_TIME_LABEL[i] descrive
    // EXPOSURE_TIME[i]. Il primo valore non e' 500000 come suggerirebbe l'etichetta
    // "1/2": 453000 e' il massimo che il sensore accetta nella modalita' di preview, e
    // alzarlo a 500000 lo farebbe rifiutare da set_controls lato plugin.
    val EXPOSURE_TIME_LABEL= arrayListOf<String>("1/2","1/4","1/8","1/15","1/30","1/60","1/125","1/250","1/500","1/1000","1/2000")
    val EXPOSURE_TIME= arrayListOf<Int>(453000,250000,125000,66666,33333,16666,8000,4000,2000,1000,500)

    private var videoPlayer: SerialH264Player? = null
    private var serialJpegVideoPlayer: SerialJpegPlayer? = null
    private val paramValueFormat = DecimalFormat("0.##").apply {
        decimalFormatSymbols = DecimalFormatSymbols.getInstance(Locale.getDefault())
    }

    private val windowParams = WindowManager.LayoutParams(
        0,
        0,
        0,
        0,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        },
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                // Required for the TextureView (preview) to render at all: without it,
                // this Service-added overlay window is not hardware-accelerated and
                // TextureView silently draws nothing.
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT
    )


    private val MATCH = android.view.ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = android.view.ViewGroup.LayoutParams.WRAP_CONTENT

    private var serialPort: SerialPortConnection? = null

    private fun getCurrentDisplayMetrics(): DisplayMetrics {
        val dm = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(dm)
        return dm
    }


    private fun calculateSizeAndPosition(
        params: WindowManager.LayoutParams,
        widthInDp: Int,
        heightInDp: Int
    ) {

        val dm = getCurrentDisplayMetrics()

        if (isFullscreen) {
            // Tutto schermo: niente dp fissi, niente offset. FLAG_LAYOUT_NO_LIMITS e'
            // gia' attivo sui windowParams, quindi MATCH_PARENT copre l'intero display.
            params.gravity = Gravity.TOP or Gravity.START
            params.width = WindowManager.LayoutParams.MATCH_PARENT
            params.height = WindowManager.LayoutParams.MATCH_PARENT
            params.x = 0
            params.y = 0
            return
        }

        // We have to set gravity for which the calculated position is relative.
        params.gravity = Gravity.TOP or Gravity.RIGHT
        params.width = (widthInDp * dm.density).toInt()
        params.height = (heightInDp * dm.density).toInt()
        // x/y sono pixel, non dp: senza conversione la posizione cambia con la densita'
        // dello schermo, mentre larghezza e altezza sopra sono gia' convertite.
        params.y = compactY ?: (65 * dm.density).toInt()
        params.x = compactX ?: (15 * dm.density).toInt()


    }


    private fun initWindowParams() {
        calculateSizeAndPosition(windowParams, windowWidth, windowHeight)
    }


    @RequiresApi(Build.VERSION_CODES.O)
    @SuppressLint("SetTextI18n")
    private fun initWindow() {

        windowbar.setOnTouchListener(object : OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            override fun onTouch(v: View, event: MotionEvent): Boolean {
                Log.d("AD", "Action E$event")
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        Log.d("AD", "Action Down")
                        initialX = windowParams.x
                        initialY = windowParams.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }

                    MotionEvent.ACTION_UP -> {
                        Log.d("AD", "Action Up")
                        val Xdiff = (event.rawX - initialTouchX).toInt()
                        val Ydiff = (event.rawY - initialTouchY).toInt()
                        if (Xdiff < 10 && Ydiff < 10) {
                            /*
                            if (isViewCollapsed()) {
                                collapsedView.setVisibility(View.GONE)
                                expandedView.setVisibility(View.VISIBLE)
                            }*/
                        }
                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        Log.d("AD", "Action Move")
                        // A tutto schermo trascinare non ha senso e porterebbe la finestra
                        // fuori dal display: il gesto resta catturato ma non muove nulla.
                        if (isFullscreen) return true
                        windowParams.x = initialX - (event.rawX - initialTouchX).toInt()
                        windowParams.y = initialY + (event.rawY - initialTouchY).toInt()
                        compactX = windowParams.x
                        compactY = windowParams.y
                        windowManager.updateViewLayout(rootView, windowParams)
                        return true
                    }
                }
                return false
            }
        })

        //mappa i controlli
        body= rootView.findViewById(R.id.body) as LinearLayout
        main_panel= rootView.findViewById(R.id.main_panel) as LinearLayout
        settings_panel= rootView.findViewById(R.id.settings_panel) as FrameLayout
        acquisition_panel= rootView.findViewById(R.id.acquisition_panel) as FrameLayout
        other_panel= rootView.findViewById(R.id.other_panel) as FrameLayout
        brightness_control = rootView.findViewById(R.id.brightness_control) as FrameLayout
        contrast_control = rootView.findViewById(R.id.contrast_control) as FrameLayout
        sharpness_control = rootView.findViewById(R.id.sharpness_control) as FrameLayout
        saturation_control = rootView.findViewById(R.id.saturation_control) as FrameLayout
        exposure_control = rootView.findViewById(R.id.exposure_control) as FrameLayout
        exposuretime_control = rootView.findViewById(R.id.exposuretime_control) as FrameLayout
        lensposition_control = rootView.findViewById(R.id.lensposition_control) as FrameLayout
        interval_control = rootView.findViewById(R.id.interval_control) as FrameLayout
        gain_control = rootView.findViewById(R.id.gain_control) as FrameLayout
        recording= rootView.findViewById(R.id.recording) as ImageView
        btn_start_acquisition=rootView.findViewById(R.id.btn_start_acquisition) as Button
        btn_start_video=rootView.findViewById(R.id.btn_start_video) as Button
        btn_start_video.visibility = Button.GONE // acquisizione video disabilitata dall'app
        //btn_upload_firmware=rootView.findViewById(R.id.btn_upload_firmware) as Button

        btn_preview_image=rootView.findViewById(R.id.btn_preview_image) as Button

        btn_open_acquisition=rootView.findViewById(R.id.btn_open_acquisition) as Button
        btn_open_config=rootView.findViewById(R.id.btn_open_config) as Button
        btn_collapse=rootView.findViewById(R.id.btn_collapse) as Button
        btn_fullscreen=rootView.findViewById(R.id.btn_fullscreen) as Button
        btn_autofocus=rootView.findViewById(R.id.btn_autofocus) as Button
        btn_ae=rootView.findViewById(R.id.btn_ae) as Button
        btn_reset_settings=rootView.findViewById(R.id.btn_reset_settings) as Button
        preview_container=rootView.findViewById(R.id.preview_container) as View

        //preview=rootView.findViewById(R.id.preview) as VLCVideoLayout //as WebView
        preview=rootView.findViewById(R.id.preview) as TextureView //as WebView

        preview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
                previewSurface = Surface(surfaceTexture)
                startPreview()
            }

            override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
                // eventuale gestione resize, se serve
            }

            override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
                // Without this, the active player keeps holding the now-invalid Surface
                // (e.g. after a window resize recreates it) and every render attempt
                // throws "Surface has already been released" instead of showing video.
                stopPreview()
                previewSurface?.release()
                previewSurface = null
                return true
            }

            override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) {
                // no-op: frames are pushed explicitly via lockCanvas/unlockCanvasAndPost
            }
        }




        // Versione presa dal pacchetto invece che scritta nel layout: il literal era
        // rimasto a "1.1" mentre versionName era gia' 1.2, cioe' la barra mostrava una
        // versione che non esisteva.
        rootView.findViewById<TextView>(R.id.title).text = "3DR UCamera " + appVersionName()

        //rectimage=rootView.findViewById(R.id.rect) as ImageView
        status=rootView.findViewById(R.id.status) as TextView
        depth=rootView.findViewById(R.id.depth) as TextView
        brightness_control.findViewById<TextView>(R.id.label).text="Luminosità"
        contrast_control.findViewById<TextView>(R.id.label).text="Contrasto"
        sharpness_control.findViewById<TextView>(R.id.label).text="Nitidezza"
        saturation_control.findViewById<TextView>(R.id.label).text="Saturazione"
        exposuretime_control.findViewById<TextView>(R.id.label).text="Tempo di esposizione"
        // "Esposizione" e "Fuoco" hanno l'etichetta dinamica: ci finisce anche la
        // modalita' corrente, vedi updateControlsEnabled().
        interval_control.findViewById<TextView>(R.id.label).text="Intervallo scatto (s)"
        gain_control.findViewById<TextView>(R.id.label).text="ISO"



        /*Abilitazione drag and drop della window*/



        val policy = ThreadPolicy.Builder()
            .permitAll().build()
        StrictMode.setThreadPolicy(policy)


        // I controlli +/- condividono tutti la stessa forma: leggi, applica un passo,
        // clampa, invia. Prima erano otto coppie copiate a mano, ognuna con la propria
        // guardia scritta a parte - ed e' esattamente li' che si annidavano l'off-by-one
        // del tempo di esposizione e il limite inferiore sbagliato del guadagno.
        bindDoubleParam(brightness_control, -1.0, 1.0, 0.1, { settings.brightness }) { settings.brightness = it }
        bindDoubleParam(contrast_control, 0.0, 32.0, 1.0, { settings.contrast }) { settings.contrast = it }
        bindDoubleParam(sharpness_control, 0.0, 16.0, 1.0, { settings.sharpness }) { settings.sharpness = it }
        bindDoubleParam(saturation_control, 0.0, 32.0, 1.0, { settings.saturation }) { settings.saturation = it }
        bindDoubleParam(lensposition_control, 0.0, 32.0, 1.0, { settings.lensposition }) { settings.lensposition = it }

        // Guadagno analogico, mostrato come "ISO" (gain x 100). Il minimo e' 1.0, non 0:
        // AnalogueGain = 0 non e' valido, e il plugin scrive il valore nel proprio
        // dizionario PRIMA di passarlo a libcamera - se set_controls solleva, lo stato
        // resta corrotto e OGNI PUT successivo fallisce finche' non si chiama
        // /camera/settings/reset. Un solo tap di troppo bloccava tutte le impostazioni.
        // Il massimo resta 9.0: il limite reale del sensore non e' esposto dall'API
        // (il server lo legge in camera_controls ma si limita a loggarlo), quindi non
        // vale la pena spingersi oltre un valore gia' noto come buono.
        bindDoubleParam(gain_control, 1.0, 9.0, 1.0, { settings.gain }) { settings.gain = it }

        exposure_control.findViewById<Button>(R.id.btn_plus).setOnClickListener {
            if (!canEditSettings()) return@setOnClickListener
            if (settings.exposurevalue < 8) {
                settings.exposurevalue = settings.exposurevalue + 1
                setSettings()
            }
        }

        exposure_control.findViewById<Button>(R.id.btn_minus).setOnClickListener {
            if (!canEditSettings()) return@setOnClickListener
            if (settings.exposurevalue > -8) {
                settings.exposurevalue = settings.exposurevalue - 1
                setSettings()
            }
        }

        exposuretime_control.findViewById<Button>(R.id.btn_plus).setOnClickListener {
            stepExposureTime(+1)
        }

        exposuretime_control.findViewById<Button>(R.id.btn_minus).setOnClickListener {
            stepExposureTime(-1)
        }

        // L'intervallo e' locale all'app (va in dataset.interval all'avvio), non e' una
        // impostazione della camera: non dipende da settingsLoaded ne' passa da setSettings.
        interval_control.findViewById<Button>(R.id.btn_plus).setOnClickListener {
            if(interval < 20.0) {
                interval=interval+0.5
                updateValues()
            }
        }

        interval_control.findViewById<Button>(R.id.btn_minus).setOnClickListener {
            if(interval > 0.0) {
                interval =interval -0.5;
                updateValues()
            }
        }


        btn_preview_image.setOnClickListener{
            // Gia' sul main thread: disabilitare dentro un post() faceva partire il
            // Thread di rete PRIMA della disabilitazione, lasciando aperta la finestra
            // per un secondo tap.
            btn_preview_image.isEnabled=false
            captureInFlight = true
            Toast.makeText(App.activity,"Cattura dello scatto di prova in corso...", Toast.LENGTH_SHORT).show()

            Thread {
                var result: CaptureResult? = null
                try {
                    result = api.capture()
                } finally {
                    captureInFlight = false
                    // try/finally: prima il riabilitare era l'ultima istruzione del thread,
                    // quindi qualunque eccezione (p.es. ImageViewer costruito fuori dal main
                    // thread) lasciava il pulsante disabilitato PER SEMPRE - da li' in poi
                    // ogni tap era un no-op silenzioso, senza toast ne' errore.
                    val r = result
                    Handler(Looper.getMainLooper()).post {
                        // updateControlsEnabled() e non "isEnabled = true": nel frattempo
                        // puo' essere partita un'acquisizione, e lo scatto di prova non
                        // sarebbe piu' lecito (il server lo rifiuterebbe).
                        updateControlsEnabled()
                        val bmp = r?.bitmap
                        if (bmp != null) {
                            // ImageViewer inflatta un layout e fa windowManager.addView():
                            // nessuna delle due e' thread-safe, vanno fatte qui sul main thread.
                            try {
                                ImageViewer(context, bmp).open()
                            } catch (e: Exception) {
                                Log.e("UCamera", "ImageViewer: " + e.message, e)
                                Toast.makeText(App.activity,
                                    "Immagine acquisita ma non visualizzabile", Toast.LENGTH_LONG).show()
                            }
                        } else {
                            // Messaggio specifico (incluso quello del server) invece del
                            // generico "Errore durante lo scatto di prova": distingue
                            // rifiuto della camera, bridge muto, risposta troncata e timeout.
                            Toast.makeText(App.activity,
                                r?.userMessage() ?: "Errore durante lo scatto di prova",
                                Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }.start()
        }

        btn_open_config.setOnClickListener{
            openConfig()
        }

        rootView.findViewById<Button>(R.id.btn_open_other).setOnClickListener{
            openOther()
        }

        btn_open_acquisition.setOnClickListener{
            openAcquisition()
        }

        rootView.findViewById<Button>(R.id.btn_refresh_preview).setOnClickListener{
            updateConnection(true)
        }

        btn_reset_settings.setOnClickListener{
            btn_reset_settings.isEnabled = false
            resetSettings { updateControlsEnabled() }
        }

        // Selettore di modalita' di fuoco: manual -> afs -> afc -> manual. Sostituisce il
        // vecchio pulsante "AF" one-shot: il plugin ora espone un solo comando
        // (exec focus_mode) e 'lensposition' e' onorato SOLO in manual, quindi la modalita'
        // corrente deve essere visibile e cambiabile, altrimenti i +/- di "Fuoco" sembrano
        // rotti mentre e' semplicemente attivo l'autofocus.
        // Esposizione automatica on/off. E' una impostazione normale (passa da
        // PUT /camera/settings), a differenza del fuoco che passa da exec.
        btn_ae.setOnClickListener{
            if (!canEditSettings()) return@setOnClickListener
            settings.aeenable = !settings.aeenable
            setSettings()
        }

        btn_autofocus.setOnClickListener{
            if (!settingsLoaded) return@setOnClickListener
            // Ciclo semplice e prevedibile. Atterrare su "afs" fa partire il one-shot;
            // per rifarlo si gira di nuovo. Niente scorciatoie nascoste su un pulsante
            // da 18dp che deve restare leggibile a colpo d'occhio.
            val current = FOCUS_MODES.indexOf(settings.afmode).let { if (it < 0) 0 else it }
            applyFocusMode(FOCUS_MODES[(current + 1) % FOCUS_MODES.size])
        }


        rootView.findViewById<Button>(R.id.btn_quit).setOnClickListener{
            App.activity.finish()
            System.exit(0);


        }

        btn_collapse.setOnClickListener {
           collapse()
        }

        btn_fullscreen.setOnClickListener {
           toggleFullscreen()
        }

        // La chevron e' piccola e a volte manca il tap: anche l'etichetta dei metri
        // accanto collassa/espande la finestra, cosi' l'area cliccabile e' piu' grande.
        depth.setOnClickListener {
           collapse()
        }

        btn_start_acquisition.setOnClickListener{
            startAcquisition()
        }

        btn_start_video.setOnClickListener{
            startAcquisition(true)
        }

        // openConnection() e' dichiarata throws Exception: non essendo protetta,
        // una porta mancante o gia' occupata faceva uscire l'eccezione dal blocco init{}
        // e crashare FloatingService all'avvio. Senza bridge l'app resta usabile
        // (via IP, vedi Webserver.init con serialPort null), quindi non si muore qui.
        serialPort = try {
            SerialPortConnection.newBuilder("/dev/ttyHS0", 4000000)
                .flags(8192)
                .readSize(16384) // default 2048: a 4 Mbaud sono ~5ms di wire, troppe syscall
                .build()
                .also { it.openConnection() }
        } catch (e: Exception) {
            Log.e("UCamera", "Apertura /dev/ttyHS0 fallita: " + e.message, e)
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(App.activity, "Porta seriale non disponibile", Toast.LENGTH_LONG).show()
            }
            null
        }
        // Stato iniziale coerente: finche' il primo getSettings() non e' arrivato i
        // controlli restano disabilitati. Senza, con la camera spenta updateConnection()
        // esce prima di aggiornare la UI e il pannello sembrava utilizzabile.
        updateControlsEnabled()

        updateConnection()

    }

    private fun appVersionName(): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
    } catch (e: Exception) {
        ""
    }

    fun collapse(){
        if(body.visibility==LinearLayout.GONE){
            body.visibility=LinearLayout.VISIBLE
            btn_collapse.setBackgroundResource(R.drawable.down);
        }else{
            body.visibility=LinearLayout.GONE
            btn_collapse.setBackgroundResource(R.drawable.up);

        }
    }

    fun getAppConfig(){
        val sharedPref = context?.getSharedPreferences("ucamera", Context.MODE_PRIVATE)
        if(sharedPref!=null) {
            remote_host=sharedPref.getString("remote_host", "192.168.1.145").toString()
            //webserver_url = sharedPref.getString("webserver_url", "http://192.168.1.145:45032").toString()
        }
    }

    fun saveAppConfig(){
        val sharedPref = context?.getSharedPreferences("ucamera", Context.MODE_PRIVATE)
        if(sharedPref!=null) {
            with(sharedPref.edit()) {
                putString("remote_host",remote_host)
                //putString("webserver_url", webserver_url)
                apply()
            }
        }
    }


    // onComplete: usato dal pulsante "Reset" per disabilitarsi durante l'invio e
    // riabilitarsi solo a richiesta finita (successo o errore) - a differenza dei
    // controlli +/- (rapidi, incrementali, lasciati senza blocco), Reset cambia 8
    // valori in un colpo ed e' un'azione singola come avvia/ferma/autofocus, quindi
    // merita lo stesso trattamento per non restare "impallato" su tap ripetuti.
    fun resetSettings(onComplete: (() -> Unit)? = null){
        if (!settingsLoaded) { onComplete?.invoke(); return } // primo fetch da updateConnection() non ancora arrivato
        // afmode NON si tocca: non e' scrivibile via settings (si cambia solo con
        // exec focus_mode) e rimandarlo indietro con un valore diverso da quello
        // corrente butterebbe la camera fuori dall'autofocus.
        settings.gain=1.0;
        settings.contrast= 1.0;
        settings.brightness= 0.0
        settings.sharpness=1.0
        settings.exposureTime=250000
        settings.exposurevalue=0
        settings.lensposition=0.0
        settings.saturation= 1.0
        settings.aeenable=true // default del manifest lato plugin
        setSettings(onComplete)
        updateValues()


    }

    fun setSettings(onComplete: (() -> Unit)? = null){
        if (!settingsLoaded) { onComplete?.invoke(); return } // primo fetch da updateConnection() non ancora arrivato
        // Feedback immediato: il valore locale e' gia' stato mutato dal chiamante
        // (es. btn_plus/btn_minus) prima di arrivare qui, quindi si puo' mostrare
        // subito senza aspettare la conferma di rete (che puo' impiegare diversi
        // secondi sul bridge seriale/radio) - altrimenti il testo a schermo resta
        // "congelato" sul vecchio valore per tutta la durata del giro di rete.
        updateValues()
        // api.setSettings() e' una chiamata sincrona sul bridge seriale: fuori dal
        // main thread per evitare ANR (chiamata da moltissimi listener +/-).
        Thread {
            val result = api.setSettings(settings)
            Handler(Looper.getMainLooper()).post {
                updateValues()
                // Il server risponde "success" anche quando scarta una chiave, e il motivo
                // vero sta solo nell'array "message" (es. "Unsupported changes to gain
                // property", "Cannot Set Settings during recording!"). Prima veniva buttato
                // via e restava il toast generico, che non diceva nulla di utile.
                val msg = result.message
                if (!result.ok) {
                    Toast.makeText(App.activity,
                        msg ?: "Errore durante la modifica delle impostazioni",
                        Toast.LENGTH_LONG).show()
                } else if (msg != null) {
                    Toast.makeText(App.activity, msg, Toast.LENGTH_LONG).show()
                }
                onComplete?.invoke()
            }
        }.start()
    }

    // Vero solo quando i controlli parametri sono realmente utilizzabili. Due condizioni,
    // entrambe necessarie: servono le impostazioni lette dal server (prima non c'e' nulla
    // di sensato da incrementare), e il server rifiuta qualunque PUT durante la
    // registrazione ("Cannot Set Settings during recording!"), quindi in acquisizione i
    // tap produrrebbero solo errori.
    private fun canEditSettings() = settingsLoaded && !onAcquisition

    // Aggancia i due pulsanti +/- di un controllo a un parametro Double con il suo
    // intervallo valido. Il clamp sta qui, in un punto solo: prima ogni coppia di
    // listener aveva la propria guardia scritta a mano, con i limiti sparsi nel codice.
    private fun bindDoubleParam(
        control: FrameLayout,
        min: Double,
        max: Double,
        step: Double,
        get: () -> Double,
        set: (Double) -> Unit
    ) {
        fun apply(direction: Int) {
            if (!canEditSettings()) return
            val current = get()
            // Arrotondamento a 2 decimali: con passo 0.1 la somma ripetuta accumula
            // errore binario (0.1+0.1+0.1 = 0.30000000000000004), che poi si trascina
            // nel JSON e rende impossibile la riverifica di setSettings().
            val raw = current + direction * step
            val next = (Math.round(raw * 100.0) / 100.0).coerceIn(min, max)
            if (kotlin.math.abs(next - current) < 1e-9) return // gia' al fondo scala: niente richiesta
            set(next)
            setSettings()
        }
        control.findViewById<Button>(R.id.btn_plus).setOnClickListener { apply(+1) }
        control.findViewById<Button>(R.id.btn_minus).setOnClickListener { apply(-1) }
    }

    // Indice del tempo di esposizione corrente nella tabella EXPOSURE_TIME. Se il valore
    // non e' in tabella (il server non valida ne' arrotonda, e puo' essere stato impostato
    // da un altro client o restare al default 2000 del manifest) si aggancia al piu'
    // vicino: prima il ciclo non trovava corrispondenza e il valore restava a
    // EXPOSURE_TIME[0], facendo saltare all'esposizione PIU' LENTA anche premendo "-".
    private fun currentExposureIndex(): Int {
        val i = EXPOSURE_TIME.indexOf(settings.exposureTime)
        if (i >= 0) return i
        return EXPOSURE_TIME.indices.minByOrNull {
            kotlin.math.abs(EXPOSURE_TIME[it] - settings.exposureTime)
        } ?: 0
    }

    // Un passo nella tabella dei tempi. La vecchia versione del pulsante "+" testava
    // `id < EXPOSURE_TIME.size` invece di `size - 1`: sull'ultimo valore (1/2000) la
    // guardia passava e EXPOSURE_TIME[11] su una lista di 11 elementi lanciava
    // IndexOutOfBoundsException sul main thread, dentro il click listener -> crash.
    private fun stepExposureTime(delta: Int) {
        if (!canEditSettings()) return
        val next = (currentExposureIndex() + delta).coerceIn(0, EXPOSURE_TIME.lastIndex)
        if (EXPOSURE_TIME[next] == settings.exposureTime) return // fondo scala: no-op
        settings.exposureTime = EXPOSURE_TIME[next]
        setSettings()
    }

    // Cambio di modalita' di fuoco. Passa da PUT /camera/exec e non dai settings, quindi
    // resta possibile anche durante l'acquisizione (a differenza di tutto il resto).
    private fun applyFocusMode(mode: String) {
        btn_autofocus.isEnabled = false
        Thread {
            val result = api.setFocusMode(mode)
            // Il plugin aggiorna lensposition dai metadati quando entra in manual o
            // dopo un one-shot: senza rilettura il pannello mostrerebbe il valore vecchio.
            val refreshed = if (result.ok) {
                try { api.getSettings() } catch (e: java.net.ConnectException) { null }
            } else null
            Handler(Looper.getMainLooper()).post {
                btn_autofocus.isEnabled = settingsLoaded
                if (result.ok) {
                    if (refreshed != null) {
                        settings = refreshed
                        settingsLoaded = true
                    } else {
                        settings.afmode = mode
                    }
                    updateValues()
                    // Su un pulsante da 40dp la sigla da sola non basta a capire cosa si
                    // e' appena attivato: il nome esteso lo dice senza ambiguita'.
                    Toast.makeText(App.activity,
                        focusModeDescription(settings.afmode), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(App.activity,
                        result.message ?: "Cambio modalita' di fuoco non riuscito",
                        Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    fun updateValues(){
        if (!settingsLoaded) { updateControlsEnabled(); return } // primo fetch da updateConnection() non ancora arrivato
        brightness_control.findViewById<TextView>(R.id.value).text=paramValueFormat.format(settings.brightness)
        contrast_control.findViewById<TextView>(R.id.value).text=paramValueFormat.format(settings.contrast)
        sharpness_control.findViewById<TextView>(R.id.value).text=paramValueFormat.format(settings.sharpness)
        saturation_control.findViewById<TextView>(R.id.value).text=paramValueFormat.format(settings.saturation)
        exposure_control.findViewById<TextView>(R.id.value).text=paramValueFormat.format(settings.exposurevalue)

        // Il valore puo' non essere in tabella (il server accetta qualsiasi intero):
        // prima mancava il ramo else e l'etichetta restava quella precedente, cioe' la
        // UI mostrava un tempo di esposizione che la camera non stava usando.
        val expIdx = EXPOSURE_TIME.indexOf(settings.exposureTime)
        exposuretime_control.findViewById<TextView>(R.id.value).text =
            if (expIdx >= 0) EXPOSURE_TIME_LABEL[expIdx]
            else "${settings.exposureTime}µs"


        lensposition_control.findViewById<TextView>(R.id.value).text=paramValueFormat.format(settings.lensposition)
        interval_control.findViewById<TextView>(R.id.value).text=paramValueFormat.format(interval)


        gain_control.findViewById<TextView>(R.id.value).text=paramValueFormat.format((settings.gain*100))

        btn_ae.text = if (settings.aeenable) "AUTO" else "MAN"

        updateControlsEnabled()
    }

    // Unico punto che decide quali controlli sono utilizzabili, invece di spargere
    // isEnabled nei listener. Tre condizioni indipendenti:
    //  - settingsLoaded: prima del primo GET non c'e' niente di reale da modificare;
    //  - onAcquisition: il server rifiuta ogni PUT /camera/settings durante la
    //    registrazione, e lo scatto di prova andrebbe in conflitto con lo scatto
    //    manuale (per questo il server lo rifiuta a sua volta);
    //  - afmode: lensposition e' onorato da libcamera solo in manual.
    private fun updateControlsEnabled() {
        val editable = canEditSettings()
        for (control in listOf(brightness_control, contrast_control, sharpness_control,
                               saturation_control)) {
            setParamEnabled(control, editable)
        }
        setParamEnabled(lensposition_control, editable && settings.afmode == FOCUS_MANUAL)

        // I due gruppi dell'esposizione si escludono, perche' libcamera li tratta cosi':
        // con AE attivo l'AGC riscrive tempo e guadagno a ogni frame (e i relativi
        // controlli non farebbero nulla), con AE spento e' la compensazione EV a non
        // avere piu' senso. Mostrarli tutti e tre sempre attivi era il motivo per cui
        // "il cambio ISO non avviene" sembrava un bug dell'app.
        setParamEnabled(exposure_control, editable && settings.aeenable)
        setParamEnabled(exposuretime_control, editable && !settings.aeenable)
        setParamEnabled(gain_control, editable && !settings.aeenable)
        btn_ae.isEnabled = editable

        // La sigla sui pulsanti si legge come azione ("premi per fare AF-C"), non come
        // stato. L'indicazione di come e' impostato ADESSO sta quindi nelle etichette,
        // che non sono cliccabili e quindi non possono essere fraintese. Serve anche a
        // spiegare perche' certi +/- sono spenti: in AF-C "Fuoco" non ha effetto, e con
        // l'esposizione automatica non ne hanno "Tempo di esposizione" e "ISO".
        val focusTag = if (settingsLoaded) focusModeLabel(settings.afmode) else "--"
        val aeTag = if (!settingsLoaded) "--" else if (settings.aeenable) "AUTO" else "MAN"
        lensposition_control.findViewById<TextView>(R.id.label).text = "Fuoco · $focusTag"
        exposure_control.findViewById<TextView>(R.id.label).text = "Esposizione · $aeTag"

        // Il fuoco passa da exec, non dai settings: resta disponibile in acquisizione.
        btn_autofocus.isEnabled = settingsLoaded
        // Il modo corrente sul pulsante va scritto QUI e non in updateValues(), che esce
        // in anticipo finche' le impostazioni non sono arrivate: in quel caso restava il
        // testo del layout, che sembrava uno stato ("AF") senza esserlo. Serve anche a
        // capire perche' i +/- di "Fuoco" non rispondono: sono onorati solo in manuale.
        btn_autofocus.text = if (settingsLoaded) focusModeLabel(settings.afmode) else "--"
        btn_reset_settings.isEnabled = editable
        btn_preview_image.isEnabled = settingsLoaded && !onAcquisition
    }

    private fun setParamEnabled(control: FrameLayout, enabled: Boolean) {
        control.findViewById<Button>(R.id.btn_plus).isEnabled = enabled
        control.findViewById<Button>(R.id.btn_minus).isEnabled = enabled
        control.alpha = if (enabled) 1.0f else 0.4f
    }

    // Alterna finestra compatta e tutto schermo. La SurfaceTexture viene distrutta e
    // ricreata da updateViewLayout(), quindi la preview si ferma e riparte da sola
    // tramite SurfaceTextureListener - a patto che stop/start siano simmetrici
    // (vedi stopPreview(), che ora azzera i player).
    fun toggleFullscreen() {
        if (!isFullscreen) {
            compactX = windowParams.x
            compactY = windowParams.y
        }
        isFullscreen = !isFullscreen
        btn_fullscreen.text = if (isFullscreen) "min" else "FS"
        if (isFullscreen) {
            // A tutto schermo il corpo deve essere visibile, altrimenti si otterrebbe
            // uno schermo intero vuoto con la sola barra del titolo.
            body.visibility = LinearLayout.VISIBLE
            btn_collapse.setBackgroundResource(R.drawable.down)
        }
        applyPreviewSize()
        calculateSizeAndPosition(windowParams, windowWidth, currentCompactHeight())
        try {
            windowManager.updateViewLayout(rootView, windowParams)
        } catch (e: Exception) {
            Log.e("UCamera", "toggleFullscreen: " + e.message, e)
        }
    }

    // Altezza della finestra compatta in base al pannello aperto (ignorata in fullscreen).
    private fun currentCompactHeight(): Int {
        val anyPanelOpen = settings_panel.visibility == FrameLayout.VISIBLE ||
                acquisition_panel.visibility == FrameLayout.VISIBLE ||
                other_panel.visibility == FrameLayout.VISIBLE
        return if (anyPanelOpen) windowHeightMax else windowHeight
    }

    // La preview e' larga 150dp nel layout compatto: a tutto schermo va allargata,
    // altrimenti si guadagna spazio senza vedere meglio. La proporzione dell'immagine
    // la preserva il renderer (vedi SerialJpegPlayer.renderFrame), non questa misura.
    private fun applyPreviewSize() {
        val dm = getCurrentDisplayMetrics()
        setSize(
            preview_container,
            if (isFullscreen) (dm.widthPixels * 0.5f).toInt()
            else (previewWidthCompact * dm.density).toInt(),
            MATCH,
        )
    }

    private fun setSize(v: View, width: Int, height: Int) {
        val lp = v.layoutParams ?: return
        lp.width = width
        lp.height = height
        v.layoutParams = lp
    }

    init {
        getAppConfig()
        initWindowParams()
        initWindow()
    }


    fun open() {
        try {


            windowManager.addView(rootView, windowParams)



        } catch (e: Exception) {
            Log.e("UCamera",e.message.toString())
            // Ignore exception for now, but in production, you should have some
            // warning for the user here.
        }
    }


    // Ogni passo in un try/catch suo: prima erano tutti in un unico blocco, quindi il
    // fallimento del primo (p.es. removeView su una finestra gia' rimossa) saltava tutti
    // gli altri, lasciando aperti polling, delegate seriali e la porta stessa.
    fun close() {
        runQuietly("stopStatusPolling") { stopStatusPolling() }
        runQuietly("stopPreview") { stopPreview() }
        runQuietly("api.shutdown") { if (::api.isInitialized) api.shutdown() }
        runQuietly("serialPort.close") { serialPort?.closeConnection(); serialPort = null }
        runQuietly("removeView") { windowManager.removeView(rootView) }
    }

    private inline fun runQuietly(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.e("UCamera", "close/$what: " + e.message)
        }
    }

    fun openAcquisition(){
        // A tutto schermo l'apertura/chiusura dei pannelli non deve toccare le
        // dimensioni della finestra, altrimenti la riporterebbe a quelle compatte.
        if(acquisition_panel.visibility==FrameLayout.VISIBLE){
            acquisition_panel.visibility=FrameLayout.GONE
            other_panel.visibility=FrameLayout.GONE
            windowParams.height=windowHeight;
            calculateSizeAndPosition(windowParams,windowWidth,windowHeight);
            windowManager.updateViewLayout(rootView,windowParams)
        }else{
            acquisition_panel.visibility=FrameLayout.VISIBLE
            settings_panel.visibility=FrameLayout.GONE
            other_panel.visibility=FrameLayout.GONE
            calculateSizeAndPosition(windowParams,windowWidth,windowHeightMax);
            windowManager.updateViewLayout(rootView,windowParams)
        }

    }

    fun openConfig(){
        // A tutto schermo l'apertura/chiusura dei pannelli non deve toccare le
        // dimensioni della finestra, altrimenti la riporterebbe a quelle compatte.
        if(settings_panel.visibility==FrameLayout.VISIBLE){
            acquisition_panel.visibility=FrameLayout.GONE
            settings_panel.visibility=FrameLayout.GONE
            other_panel.visibility=FrameLayout.GONE
            windowParams.height=windowHeight;
            calculateSizeAndPosition(windowParams,windowWidth,windowHeight);
            windowManager.updateViewLayout(rootView,windowParams)
        }else{
            settings_panel.visibility=FrameLayout.VISIBLE
            other_panel.visibility=FrameLayout.GONE
            acquisition_panel.visibility=FrameLayout.GONE
            calculateSizeAndPosition(windowParams,windowWidth,windowHeightMax);
            windowManager.updateViewLayout(rootView,windowParams)
        }

    }

    fun openOther(){
        // A tutto schermo l'apertura/chiusura dei pannelli non deve toccare le
        // dimensioni della finestra, altrimenti la riporterebbe a quelle compatte.
        if(other_panel.visibility==FrameLayout.VISIBLE){
            acquisition_panel.visibility=FrameLayout.GONE
            settings_panel.visibility=FrameLayout.GONE
            other_panel.visibility=FrameLayout.GONE
            calculateSizeAndPosition(windowParams,windowWidth,windowHeight);
            windowManager.updateViewLayout(rootView,windowParams)
        }else{
            other_panel.visibility=FrameLayout.VISIBLE
            acquisition_panel.visibility=FrameLayout.GONE
            settings_panel.visibility=FrameLayout.GONE
            calculateSizeAndPosition(windowParams,windowWidth,windowHeightMax);
            windowManager.updateViewLayout(rootView,windowParams)
        }

    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun startAcquisition(video: Boolean=false){
       // Disabilita subito il pulsante e mostra un testo "in corso" (sync, siamo sul
       // main thread del click) cosi' tap ripetuti mentre la richiesta e' in volo
       // (puo' impiegare diversi secondi sul bridge seriale/radio, ora anche in coda
       // dietro al lock di SerialBridge) non spammano il server, e il pulsante non
       // sembra bloccato/rotto nell'attesa.
       btn_start_acquisition.isEnabled = false
       btn_start_acquisition.text = if (onAcquisition) "Fermando..." else "Iniziando..."
       // Le chiamate api.* sono sincrone (Retrofit .execute()) e possono impiegare
       // diversi secondi sul bridge seriale/radio: girate su un thread apposito per
       // non bloccare il main thread (rischio ANR, vedi timeout SerialBridge).
       Thread {
         try {
           if(onAcquisition){

               if(api.stopDataset()) {
                   setAcquisitionState(false)
               }else{
                   // Fallito per davvero (isAcquisitionRunning() conferma ancora attiva,
                   // vedi Webserver.stopDataset()): resta nello stato "in corso", non "false".
                   setAcquisitionState(true)

                   Handler(Looper.getMainLooper()).post {
                       Toast.makeText(App.activity,
                           api.lastServerMessages.firstOrNull() ?: "Errore durante l'arresto dell'acquisizione",
                           Toast.LENGTH_LONG).show()
                   }
               }
           }else{
               val d: dataset = dataset()
               val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.ITALY)
               d.datasetname = sdf.format(Date())

               if(!video){
                   d.interval = if (interval > 0.0) interval else null; //Set interval
                   val result = api.startDataset(d)
                   if(result>-1 || result==Webserver.ALREADY_RUNNING) {
                       // ALREADY_RUNNING: il server ha gia' un'acquisizione aperta (es.
                       // risposta al primo tap persa sul bridge radio) - risincronizza lo
                       // stato locale invece di segnalare un errore che non e' reale.
                       setAcquisitionState(true)
                   }else{
                       setAcquisitionState(false)
                       Handler(Looper.getMainLooper()).post {
                           Toast.makeText(App.activity,
                               api.lastServerMessages.firstOrNull() ?: "Errore durante l'avvio dell'acquisizione",
                               Toast.LENGTH_LONG).show()
                       }
                   }
               }else{
                   val result = api.startVideo(d)
                   if(result>-1 || result==Webserver.ALREADY_RUNNING) {
                       setAcquisitionState(true)
                   }else{
                       setAcquisitionState(false)
                       Handler(Looper.getMainLooper()).post {
                           Toast.makeText(App.activity,
                               api.lastServerMessages.firstOrNull() ?: "Errore durante l'avvio dell'acquisizione",
                               Toast.LENGTH_LONG).show()
                       }
                   }
               }

           }
         } finally {
             Handler(Looper.getMainLooper()).post { btn_start_acquisition.isEnabled = true }
         }
       }.start()
    }

    fun setAcquisitionState(state:Boolean){
        Handler(Looper.getMainLooper()).post {
            if (state) {
                recording.visibility=ImageView.VISIBLE
                btn_start_acquisition.text = "Ferma"
                btn_start_video.visibility= ImageView.GONE
                onAcquisition = true
            } else {
                status.text = "Ready"
                recording.visibility=ImageView.GONE
                btn_start_acquisition.text = "Avvia Scatto Foto"
                // btn_start_video resta nascosto (acquisizione video disabilitata, vedi initWindow)
                onAcquisition = false
            }
            // In acquisizione il server rifiuta sia PUT /camera/settings sia
            // /camera/capture: i controlli vanno disabilitati, non lasciati a produrre
            // errori. Il fuoco resta disponibile (passa da exec).
            updateControlsEnabled()
        }
    }

    // Ogni statusPollIntervalMs interroga GET /datasets/ e GET /location_system/status
    // (via SerialBridge, che ora serializza le richieste con un lock) per sapere se
    // un'acquisizione e' in corso con quanti scatti, e la profondita' attuale -
    // sostituisce device_status/datasets_storage_status/location_status via Socket.IO,
    // che sul solo bridge seriale/radio Skydroid non e' raggiungibile (serve un vero
    // percorso IP).
    private val statusPollRunnable = object : Runnable {
        override fun run() {
            if (!statusPollingActive) return
            if (captureInFlight) {
                // Riprova al giro dopo: perdere un aggiornamento di stato non costa
                // nulla, ritardare lo scatto si'.
                statusPollHandler.postDelayed(this, statusPollIntervalMs)
                return
            }
            Thread {
                val acqStatus = api.getAcquisitionStatus()
                // getDepthMeters() si auto-disattiva dopo qualche errore: la rotta
                // /location_system/status non e' esposta dal server, e continuare a
                // interrogarla costa uno slot del bridge seriale ogni ciclo per un 404.
                val depthMeters = if (api.depthPollingEnabled) api.getDepthMeters() else null
                Handler(Looper.getMainLooper()).post {
                    if (acqStatus != null) {
                        setAcquisitionState(acqStatus.running)
                        if (acqStatus.running) {
                            status.text = "Dataset ${acqStatus.datasetId} Foto ${acqStatus.items}"
                        }
                    }
                    if (depthMeters != null) {
                        depth.text = "%.2f mt".format(depthMeters)
                    }
                    if (statusPollingActive) {
                        statusPollHandler.postDelayed(this, statusPollIntervalMs)
                    }
                }
            }.start()
        }
    }

    fun startStatusPolling() {
        if (statusPollingActive) return
        statusPollingActive = true
        statusPollHandler.postDelayed(statusPollRunnable, statusPollIntervalMs)
    }

    fun stopStatusPolling() {
        statusPollingActive = false
        statusPollHandler.removeCallbacks(statusPollRunnable)
    }

    fun updateConnection(answerAddress: Boolean=false){

        // Chiude il Webserver precedente PRIMA di sostituirlo: ogni init() registra un
        // nuovo SerialBridge come delegate della porta seriale, e senza shutdown i
        // vecchi restavano registrati per sempre. Dopo N riconnessioni ogni risposta
        // veniva parsata N volte, completando altrettanti future orfani.
        if (::api.isInitialized) {
            try { api.shutdown() } catch (e: Exception) { Log.e("UCamera", "shutdown: " + e.message) }
        }

        api= Webserver();
        api.init("http://"+remote_host+":"+remote_port, serialPort, context)

        // getVersion()/getSettings() sono chiamate sincrone sul bridge seriale/radio
        // (fino a 20s di timeout, vedi SerialBridge): fuori dal main thread per
        // evitare ANR - questa funzione viene chiamata direttamente da initWindow()
        // e da click listener. Il resto (view, dialog, socket.io) torna sul main
        // thread via Handler.post, invariato rispetto a prima.
        Thread {
            var ucamera_version=""
            try{
                ucamera_version=api.getVersion().version
            }catch(e:java.net.ConnectException){
                Log.e("UCamera",e.message.toString())
                Handler(Looper.getMainLooper()).post {
                    if(answerAddress) {
                        // Set up the input
                        val input: EditText = EditText(App.activity);
        // Specify the type of input expected; this, for example, sets the input as a password, and will mask the text
                        input.setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)

                        //richiedi di inserire un nuovo indirizzo IP
                        val builder = AlertDialog.Builder(App.activity)
                        builder.setTitle("UCamera non trovata")
                        builder.setMessage("Dispositivo non trovato. Indicare un nuovo indirizzo IP su cui cercare la camera")
                        builder.setView(input)
                        builder.setPositiveButton(android.R.string.yes) { dialog, which ->
                            remote_host=input.text.toString()
                            //webserver_url = "http://" + input.text.toString() + ":45032"
                            saveAppConfig()
                            updateConnection(true)
                        }

                        builder.setNegativeButton(android.R.string.no) { dialog, which ->
                            Toast.makeText(
                                context,
                                android.R.string.no, Toast.LENGTH_SHORT
                            ).show()
                        }


                        builder.show()
                    }

                    onCameraState(false)
                }
                return@Thread
            }

            /*
            //verifica se bisogna aggiornare il server
            if(ucamera_version!="1.1.9"){
                //effettua l'aggiornamento
               // uploadFirmware()
                return;
            }*/

            // getSettings() lancia deliberatamente ConnectException se la richiesta va in
            // timeout/errore (vedi Webserver.getSettings()) - senza catch qui l'eccezione
            // risale non gestita e crasha l'app (bug preesistente, non solo un problema di
            // ANR): stesso trattamento del fallimento di getVersion() sopra.
            val fetchedSettings: settings
            try {
                fetchedSettings = api.getSettings()
            } catch (e: java.net.ConnectException) {
                Log.e("UCamera","updateConnection/getSettings: "+e.message.toString())
                Handler(Looper.getMainLooper()).post {
                    onCameraState(false)
                }
                return@Thread
            }
            // Risincronizza onAcquisition con lo stato reale del server: se un'acquisizione
            // era gia' partita altrove (app riavviata, tap precedente la cui risposta si e'
            // persa, ecc.) il pulsante deve mostrare subito "Ferma" invece di lasciare
            // capire solo al prossimo tentativo di avvio fallito.
            val acqStatus = api.getAcquisitionStatus()

            Handler(Looper.getMainLooper()).post {
                settings = fetchedSettings
                settingsLoaded = true
                setAcquisitionState(acqStatus?.running ?: false)
                if (acqStatus?.running == true) {
                    status.text = "Dataset ${acqStatus.datasetId} Foto ${acqStatus.items}"
                }
                startStatusPolling()

                updateValues()
                if (answerAddress) {
                    // "Aggiorna preview" fermava la preview senza mai riavviarla (la
                    // startPreview() in fondo a questo blocco era commentata): il
                    // pulsante faceva l'opposto di quello che dichiara.
                    stopPreview()
                    startPreview()
                }

                if (!::s.isInitialized) {
                    s=SocketIOConnection()
                    s.init("http://"+remote_host+":"+remote_port+"/")
                    onCameraState(false)

                    s.socket.on(Socket.EVENT_CONNECT,Emitter.Listener {
                        onCameraState(true)
                    })

                    s.socket.on(Socket.EVENT_DISCONNECT,Emitter.Listener {
                        onCameraState(false)
                        Thread.sleep(2000)
                        s.init("http://"+remote_host+":"+remote_port+"/");
                    })


                    s.socket.on("device_status", Emitter.Listener { it->
                        it.forEach {
                                row->
                            var device=row as JSONObject
                            if(device.get("name")=="arducam"){
                                var isRecording = device.getBoolean("is_recording")
                                setAcquisitionState(isRecording)
                            }
                        }

                    })

                    s.socket.on("datasets_storage_status", Emitter.Listener { it->
                        it.forEach {
                                row->
                            var dataset=row as JSONObject
                            var acquisition = dataset.getJSONObject("current_camera_acquisition")
                            if (acquisition.length() != 0) {
                                //setAcquisitionState(true)
                                Handler(Looper.getMainLooper()).post {
                                    status.text = "Dataset " + acquisition.get("dataset_id").toString() +
                                            " Foto " + acquisition.get("items").toString()
                                }
                            } else {
                                //setAcquisitionState(false)
                            }
                        }

                    });

                    s.socket.on("location_status", Emitter.Listener{ it ->
                        it.forEach {
                            row->
                            var location = row as JSONObject
                            var altitude = location.optJSONArray("altitude")
                            if (altitude != null && altitude.length() >= 2 && altitude.getString(1) == "BSL") {
                                val altitudeValue = altitude.getDouble(0)
                                Handler(Looper.getMainLooper()).post {
                                    depth.text = "%.2f mt".format(altitudeValue)
                                }
                            }


                        }

                    })
                }
                if (!s.isConnected()) {
                    s.socket.connect()
                }
                //startPreview()
            }
        }.start()
    }
    fun startPreview(){
        val surface = previewSurface ?: return
        // Ferma prima un eventuale player ancora attivo: startPreview() puo' essere
        // chiamata piu' volte (surface ricreata a ogni resize della finestra, incluso il
        // passaggio a tutto schermo) e sovrascrivere il campo lasciava il delegate
        // precedente registrato sulla seriale, a decodificare in parallelo.
        stopPreview()
        when (fpvPreviewMode) {
            FpvPreviewMode.JPEG_SERIAL -> {
                serialJpegVideoPlayer = SerialJpegPlayer(serialPort, surface)
                serialJpegVideoPlayer?.start()
            }
            FpvPreviewMode.SERIAL_H264 -> {
                videoPlayer = SerialH264Player( serialPort, surface)
                videoPlayer?.start()
            }
        }
    }
    /*
    fun startPreview(){
        var rtspUrl = "rtsp://"+remote_host+":"+stream_port+"/camera-preview"
        libVlc = LibVLC(preview.context, arrayListOf(
            "--rtsp-tcp",          // forza TCP (equivalente a quello che fai con Exo)
            "--network-caching=150" // puoi provare 150-500
        ))
        vlcPlayer = MediaPlayer(libVlc).apply {
            attachViews(preview, null, false, false)

            val media = Media(libVlc, Uri.parse(rtspUrl))
            media.addOption(":rtsp-tcp")
            media.addOption(":network-caching=150")
            this.media = media
            media.release()

            play()
        }

    }

    fun stopPreview(){
        vlcPlayer?.stop()
        vlcPlayer?.detachViews()
        vlcPlayer?.release()
        vlcPlayer = null
        libVlc?.release()
        libVlc = null
    }
    */
    // Azzera i campi dopo lo stop: senza, ogni chiamata successiva rifermava player
    // gia' fermi e, soprattutto, startPreview() non aveva modo di sapere se ce n'era
    // ancora uno vivo da chiudere.
    fun stopPreview(){
        videoPlayer?.stop()
        videoPlayer = null
        serialJpegVideoPlayer?.stop()
        serialJpegVideoPlayer = null
    }

    @SuppressLint("ResourceAsColor")
    fun onCameraState(connected:Boolean){
        camera_connected=connected;
        /*
        Handler(Looper.getMainLooper()).post {
            if (connected) {
                main_panel.setBackgroundColor(R.color.white)
                status.text = "Ready"
                btn_open_config.isEnabled = true
                btn_open_acquisition.isEnabled = true
                preview.visibility = VLCVideoLayout.VISIBLE
            } else {
                main_panel.setBackgroundColor(R.color.purple_200)
                status.text = "No connected"
                btn_open_config.isEnabled = false
                btn_open_acquisition.isEnabled = false
                preview.visibility = VLCVideoLayout.INVISIBLE

            }
        }*/

    }

    fun uploadFirmware(){
        //val u:Uploader=Uploader()
        //if(u.uploadFirmware(remote_host)){
        //    Toast.makeText(App.activity,"Firmware aggiornato correttamente", Toast.LENGTH_SHORT);
        //}else{
        //    Toast.makeText(App.activity,"Errore durante l'aggiornamento firmware. Riprovare",
        //        Toast.LENGTH_SHORT);
        //}
    }


}