package com.trediresearch.ucamera

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.StrictMode
import android.os.StrictMode.ThreadPolicy
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
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
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import com.trediresearch.ucamera.video.SerialH264Player
import com.trediresearch.ucamera.video.SerialJpegPlayer
import com.trediresearch.ucamera.status.PollingStatusSource
import com.trediresearch.ucamera.status.SocketIoStatusSource
import com.trediresearch.ucamera.status.StatusCoordinator
import com.trediresearch.ucamera.status.StatusListener
import com.trediresearch.ucamera.webserver.AcquisitionStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import android.view.Surface
import android.graphics.SurfaceTexture
import android.view.TextureView
import com.trediresearch.ucamera.video.SerialPortConnection
import com.trediresearch.ucamera.webserver.AF_RANGES
import com.trediresearch.ucamera.webserver.AF_SPEEDS
import com.trediresearch.ucamera.webserver.AF_WINDOW_FULL
import com.trediresearch.ucamera.webserver.AF_WINDOW_MIN
import com.trediresearch.ucamera.webserver.AF_WINDOW_STEP
import com.trediresearch.ucamera.webserver.CaptureResult
import com.trediresearch.ucamera.webserver.FOCUS_CONTINUOUS
import com.trediresearch.ucamera.webserver.FOCUS_MANUAL
import com.trediresearch.ucamera.webserver.FOCUS_SINGLE
import com.trediresearch.ucamera.webserver.FocusOutcome
import com.trediresearch.ucamera.webserver.Link
import com.trediresearch.ucamera.webserver.OutcomeKind
import com.trediresearch.ucamera.webserver.Webserver
import com.trediresearch.ucamera.webserver.afSearchSummary
import com.trediresearch.ucamera.webserver.focusModeBehaviour
import com.trediresearch.ucamera.webserver.focusModeName
import com.trediresearch.ucamera.webserver.focusModeOutcome
import com.trediresearch.ucamera.webserver.afRangeDescription
import com.trediresearch.ucamera.webserver.afRangeLabel
import com.trediresearch.ucamera.webserver.afSpeedDescription
import com.trediresearch.ucamera.webserver.afSpeedLabel
import com.trediresearch.ucamera.webserver.afWindowDescription
import com.trediresearch.ucamera.webserver.afWindowForSize
import com.trediresearch.ucamera.webserver.afWindowLabel
import com.trediresearch.ucamera.webserver.afWindowSize
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
    // Stato della camera: eventi Socket.IO quando c'e' un percorso IP (Wi-Fi), polling
    // REST solo quando manca (sotto sola radio Skydroid) - vedi startStatusUpdates() e
    // il package status.
    private var statusCoordinator: StatusCoordinator? = null
    private var pollingStatus: PollingStatusSource? = null
    // Lo stato Avvia/Ferma lo decidono gli esiti di start/stop: mentre un comando e' in
    // volo, e per STATE_GRACE_MS dopo, lo stato riportato dal server viene ignorato
    // (un device_status o un polling partiti prima del comando lo riporterebbero indietro).
    @Volatile
    private var remoteStateIgnoredUntil = 0L
    // Vero mentre uno scatto di prova e' in volo. Il polling salta il suo giro: sono due
    // round trip da fino a 6s ciascuno che terrebbero il lock di SerialBridge, mettendo
    // in coda la richiesta piu' fragile che abbiamo proprio quando conta.
    @Volatile
    private var captureInFlight = false
    // Vero mentre un avvia/ferma acquisizione e' in volo (letto e scritto sul main thread).
    private var acquisitionCommandInFlight = false
    // Finestra compatta, dall'alto: barra, preview a tutta larghezza, riga delle schede
    // (vedi window.xml). Con un pannello aperto si aggiunge la sua altezza.
    private val barHeight=22
    private val tabsHeight=36
    private val panelHeight=150
    val windowWidth=270
    // Proporzioni dello stream (larghezza/altezza). Parte da 4:3, quello del QVGA del
    // plugin, e segue i frame veri (vedi onStreamFrameSize()). Limitata per non ottenere
    // finestre assurde da un frame anomalo.
    private var previewAspect = 4.0 / 3.0
    private val MIN_PREVIEW_ASPECT = 1.0
    private val MAX_PREVIEW_ASPECT = 2.4

    // Margine lasciato sotto la finestra compatta e altezza minima della preview.
    private val SCREEN_MARGIN_DP = 8
    private val MIN_PREVIEW_DP = 80

    // Altezza della preview compatta. Di norma l'immagine riempie tutta la larghezza della
    // finestra (con un'altezza fissa di 120dp un 4:3 restava largo 160dp). Ma la finestra
    // deve stare nello schermo: con lo smartphone in orizzontale, preview piena piu' un
    // pannello aperto superavano l'altezza del display e la parte bassa del pannello
    // (ISO, pulsanti) finiva fuori schermo. In quel caso la preview si accorcia.
    private fun previewHeightCompact(panelOpen: Boolean): Int {
        val natural = Math.round(windowWidth / previewAspect).toInt()
        val dm = getCurrentDisplayMetrics()
        val screenDp = (dm.heightPixels / dm.density).toInt()
        val available = screenDp - SCREEN_MARGIN_DP - barHeight - tabsHeight -
                (if (panelOpen) panelHeight else 0)
        return natural.coerceAtMost(available).coerceAtLeast(MIN_PREVIEW_DP)
    }

    // Altezza della finestra compatta con o senza pannello aperto.
    private fun compactHeight(panelOpen: Boolean) =
        barHeight + previewHeightCompact(panelOpen) + tabsHeight + (if (panelOpen) panelHeight else 0)
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

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val layoutInflater = context.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
    private val rootView = layoutInflater.inflate(R.layout.window, null)
    private val windowbar=rootView.findViewById<FrameLayout>(R.id.windowbar);
    lateinit var body:LinearLayout
    // Contiene corpo e pannelli; cambia orientamento a tutto schermo (vedi applyLayout()).
    lateinit var content:LinearLayout
    lateinit var panels_container:LinearLayout
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
    // Scheda Fuoco: tre pulsanti di modo, riga di stato ed esito (vedi updateFocusPanel()).
    lateinit var focus_panel:FrameLayout
    lateinit var btn_focus_mf:Button
    lateinit var btn_focus_afs:Button
    lateinit var btn_focus_afc:Button
    lateinit var focus_status:TextView
    lateinit var focus_result:TextView
    lateinit var afrange_control:FrameLayout;
    lateinit var afspeed_control:FrameLayout;
    lateinit var afwindow_control:FrameLayout;
    lateinit var interval_control:FrameLayout;
    lateinit var gain_control:FrameLayout;
    lateinit var btn_start_acquisition:Button;
    lateinit var btn_start_video:Button;

    lateinit var btn_preview_image:Button
    lateinit var btn_collapse:Button
    lateinit var btn_fullscreen:Button
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
    // Cerchio "in lavorazione" sulla preview (vedi requestInFlight()).
    lateinit var busy_indicator: ProgressBar
    // Badge WIFI/RADIO nella barra: collegamento usato dai comandi (vedi showLink()).
    lateinit var link_badge: TextView

    lateinit var btn_open_acquisition:Button
    lateinit var btn_open_config:Button
    lateinit var btn_open_focus:Button
    lateinit var btn_open_other:Button
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
        // Se la posizione scelta farebbe uscire la finestra dal fondo dello schermo (per
        // esempio dopo l'apertura di un pannello) la si alza quanto basta. compactY resta
        // quella scelta dall'utente: chiudendo il pannello la finestra torna dov'era.
        val maxY = (dm.heightPixels - params.height).coerceAtLeast(0)
        params.y = (compactY ?: (65 * dm.density).toInt()).coerceIn(0, maxY)
        params.x = compactX ?: (15 * dm.density).toInt()


    }


    private fun initWindowParams() {
        calculateSizeAndPosition(windowParams, windowWidth, compactHeight(panelOpen = false))
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
        content = rootView.findViewById(R.id.content) as LinearLayout
        panels_container = rootView.findViewById(R.id.panels_container) as LinearLayout
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
        focus_panel = rootView.findViewById(R.id.focus_panel) as FrameLayout
        btn_focus_mf = rootView.findViewById(R.id.btn_focus_mf) as Button
        btn_focus_afs = rootView.findViewById(R.id.btn_focus_afs) as Button
        btn_focus_afc = rootView.findViewById(R.id.btn_focus_afc) as Button
        focus_status = rootView.findViewById(R.id.focus_status) as TextView
        focus_result = rootView.findViewById(R.id.focus_result) as TextView
        afrange_control = rootView.findViewById(R.id.afrange_control) as FrameLayout
        afspeed_control = rootView.findViewById(R.id.afspeed_control) as FrameLayout
        afwindow_control = rootView.findViewById(R.id.afwindow_control) as FrameLayout
        interval_control = rootView.findViewById(R.id.interval_control) as FrameLayout
        gain_control = rootView.findViewById(R.id.gain_control) as FrameLayout
        recording= rootView.findViewById(R.id.recording) as ImageView
        busy_indicator = rootView.findViewById(R.id.busy_indicator) as ProgressBar
        link_badge = rootView.findViewById(R.id.link_badge) as TextView
        btn_start_acquisition=rootView.findViewById(R.id.btn_start_acquisition) as Button
        btn_start_video=rootView.findViewById(R.id.btn_start_video) as Button
        btn_start_video.visibility = Button.GONE // acquisizione video disabilitata dall'app
        //btn_upload_firmware=rootView.findViewById(R.id.btn_upload_firmware) as Button

        btn_preview_image=rootView.findViewById(R.id.btn_preview_image) as Button

        btn_open_acquisition=rootView.findViewById(R.id.btn_open_acquisition) as Button
        btn_open_config=rootView.findViewById(R.id.btn_open_config) as Button
        btn_open_focus=rootView.findViewById(R.id.btn_open_focus) as Button
        btn_open_other=rootView.findViewById(R.id.btn_open_other) as Button
        btn_collapse=rootView.findViewById(R.id.btn_collapse) as Button
        btn_fullscreen=rootView.findViewById(R.id.btn_fullscreen) as Button
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
        lensposition_control.findViewById<TextView>(R.id.label).text="Lente"
        afrange_control.findViewById<TextView>(R.id.label).text="Range"
        afspeed_control.findViewById<TextView>(R.id.label).text="Velocità"
        afwindow_control.findViewById<TextView>(R.id.label).text="Finestra"
        // "Esposizione" ha l'etichetta dinamica: ci finisce anche AUTO/MAN, vedi
        // updateControlsEnabled(). Il modo di fuoco invece e' il pulsante evidenziato.
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
        // LensPosition per libcamera e' in diottrie, 0.0 (infinito) .. 15.0 (tuning
        // Hawkeye 64MP). Il passo di 1 era troppo grosso: in acqua 1 D ~ 1.33 m.
        bindDoubleParam(lensposition_control, 0.0, 15.0, 0.1, { settings.lensposition }) { settings.lensposition = it }

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

        // Stessa forma del tempo di esposizione: i +/- scorrono una lista di valori
        // ammessi e la casella mostra l'etichetta del valore corrente. Qui pero' la
        // scrittura passa da exec e non da setSettings, quindi resta possibile anche in
        // acquisizione - come per il modo di fuoco.
        afrange_control.findViewById<Button>(R.id.btn_plus).setOnClickListener { stepAfRange(+1) }
        afrange_control.findViewById<Button>(R.id.btn_minus).setOnClickListener { stepAfRange(-1) }
        afspeed_control.findViewById<Button>(R.id.btn_plus).setOnClickListener { stepAfSpeed(+1) }
        afspeed_control.findViewById<Button>(R.id.btn_minus).setOnClickListener { stepAfSpeed(-1) }

        afwindow_control.findViewById<Button>(R.id.btn_plus).setOnClickListener { stepAfWindow(+1) }
        afwindow_control.findViewById<Button>(R.id.btn_minus).setOnClickListener { stepAfWindow(-1) }

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
            captureInFlight = true
            updateControlsEnabled() // spegne TUTTI i comandi e mostra il cerchio
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

        btn_open_other.setOnClickListener{
            openOther()
        }

        btn_open_acquisition.setOnClickListener{
            openAcquisition()
        }

        btn_open_focus.setOnClickListener{
            openFocus()
        }

        rootView.findViewById<Button>(R.id.btn_refresh_preview).setOnClickListener{
            updateConnection(true)
        }

        btn_reset_settings.setOnClickListener{
            btn_reset_settings.isEnabled = false
            resetSettings { updateControlsEnabled() }
        }

        // Modi di fuoco: un tap = un modo, senza ciclo ne' attesa. Passare da MF ad AF-C
        // non attraversa piu' AF-S (che lancerebbe una messa a fuoco non chiesta), e un
        // tap su AF-S gia' attivo rifa' la messa a fuoco.
        btn_focus_mf.setOnClickListener { onFocusModeTapped(FOCUS_MANUAL) }
        btn_focus_afs.setOnClickListener { onFocusModeTapped(FOCUS_SINGLE) }
        btn_focus_afc.setOnClickListener { onFocusModeTapped(FOCUS_CONTINUOUS) }

        // Esposizione automatica on/off. E' una impostazione normale (passa da
        // PUT /camera/settings), a differenza del fuoco che passa da exec.
        btn_ae.setOnClickListener{
            if (!aeButtonEnabled()) return@setOnClickListener
            // Debounce: il tap cambia solo la scritta, l'invio parte TAP_DEBOUNCE_MS dopo
            // l'ultimo tap e solo se lo stato scelto differisce da quello attuale.
            pendingAeEnable = !(pendingAeEnable ?: settings.aeenable)
            updateControlsEnabled()
            tapDebounceHandler.removeCallbacks(sendPendingAeEnable)
            tapDebounceHandler.postDelayed(sendPendingAeEnable, TAP_DEBOUNCE_MS)
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
        updateTabs() // colori delle schede espliciti, uguali con qualunque tema
        applyLayout() // altezza della preview dalle proporzioni dello stream

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


    // onComplete: usato dal pulsante "Reset" per riallineare i controlli a richiesta
    // finita (successo o errore). Come per ogni altra richiesta, finche' il PUT e' in
    // volo tutti i comandi restano spenti (vedi requestInFlight()).
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
        settingsPutsInFlight++
        updateValues()
        // api.setSettings() e' una chiamata sincrona sul bridge seriale: fuori dal
        // main thread per evitare ANR (chiamata da moltissimi listener +/-).
        Thread {
            val result = api.setSettings(settings)
            Handler(Looper.getMainLooper()).post {
                settingsPutsInFlight--
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
    // In piu': il PUT rispedisce l'oggetto intero, afmode e parametri AF compresi, quindi
    // non deve partire mentre un comando di fuoco e' in volo (riporterebbe il modo
    // vecchio), ne' durante un cambio AUTO/MAN.
    private fun canEditSettings() = settingsLoaded && !onAcquisition && !isBusy()

    // Una richiesta verso la camera e' in viaggio. Finche' non torna NESSUN comando e'
    // accettato: i controlli si spengono e compare il cerchio, cosi' un tap non viene mai
    // scartato in silenzio. Sotto radio le richieste passano una alla volta dal lock di
    // SerialBridge: accettare tap in quel tempo voleva dire accodarli o perderli.
    // Il polling di stato in sottofondo non conta: non nasce da un tap.
    private fun requestInFlight() = settingsPutsInFlight > 0 || focusModeInFlight ||
            afParamsInFlight || aeInFlight || captureInFlight || acquisitionCommandInFlight

    // Copia di requestInFlight() leggibile dal thread del polling, che salta i suoi giri
    // finche' un comando dell'utente e' in viaggio (vedi startStatusUpdates()): sotto radio
    // un giro di polling davanti al comando lo ritarderebbe fino a 6 s.
    @Volatile
    private var userRequestActive = false

    // Occupato: richiesta in viaggio, oppure una scelta AUTO/MAN fatta con i tap e in attesa
    // dell'invio (debounce). In attesa resta usabile solo il pulsante AE.
    private fun isBusy() = requestInFlight() || pendingAeEnable != null

    // Pulsanti dei modi di fuoco: passano da exec, quindi sono ammessi anche in acquisizione.
    private fun focusModeButtonEnabled() =
        settingsLoaded && !requestInFlight() && pendingAeEnable == null

    private fun aeButtonEnabled() =
        settingsLoaded && !onAcquisition && !requestInFlight()

    // PUT /camera/settings ancora senza risposta. Finche' ce n'e' uno, modo e parametri
    // di fuoco restano bloccati: il PUT porta con se' l'afmode letto prima, e arrivando
    // dopo il comando di fuoco lo annullerebbe.
    private var settingsPutsInFlight = 0

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

    // Un passo nella lista dei valori ammessi per un parametro di ricerca del fuoco.
    // Stessa meccanica di stepExposureTime(), con due differenze: il valore corrente e'
    // una stringa (quindi niente "piu' vicino in tabella": un valore fuori lista
    // significa che il server parla un vocabolario che non conosciamo, e si riparte da
    // capo), e la scrittura passa da exec, non dai settings.
    private fun stepAfValue(
        values: List<String>,
        current: String?,
        delta: Int,
        apply: (String) -> Unit,
    ) {
        if (!afParamsEditable() || current == null) return
        val i = values.indexOf(current)
        if (i < 0) {
            Log.w("UCamera", "valore di fuoco sconosciuto dal server: $current")
            apply(values[0])
            return
        }
        val next = (i + delta).coerceIn(0, values.lastIndex)
        if (values[next] == current) return // fondo scala: no-op, niente giro di rete
        apply(values[next])
    }

    private fun stepAfRange(delta: Int) =
        stepAfValue(AF_RANGES, settings.afrange, delta) { value ->
            applyFocusParams(afrange_control, "range", afRangeLabel(value), range = value,
                describe = { afRangeDescription(it.afrange) })
        }

    private fun stepAfSpeed(delta: Int) =
        stepAfValue(AF_SPEEDS, settings.afspeed, delta) { value ->
            applyFocusParams(afspeed_control, "velocità", afSpeedLabel(value), speed = value,
                describe = { afSpeedDescription(it.afspeed) })
        }

    // Il '+' allarga il riquadro centrale, il '-' lo stringe. Il valore corrente si
    // aggancia prima al multiplo del passo piu' vicino: una finestra impostata da altri
    // (es. 35%) non deve far avanzare a scalini sfalsati.
    private fun stepAfWindow(delta: Int) {
        if (!afParamsEditable()) return
        val current = afWindowSize(settings.afwindow) ?: return
        val snapped = Math.round(current.toDouble() / AF_WINDOW_STEP).toInt() * AF_WINDOW_STEP
        val next = (snapped + delta * AF_WINDOW_STEP).coerceIn(AF_WINDOW_MIN, AF_WINDOW_FULL)
        if (next == current) return // fondo scala: no-op, niente giro di rete
        val window = afWindowForSize(next)
        applyFocusParams(afwindow_control, "finestra", afWindowLabel(window), window = window,
            describe = { afWindowDescription(it.afwindow) })
    }

    // Cambia un parametro della ricerca lasciando invariato il modo. Il modo va comunque
    // rispedito perche' il server lo pretende, e per questo il gruppo e' nascosto in
    // manuale: rimandare "manual" non applicherebbe nulla, e rimandare "afs" rilancia un
    // one-shot, che qui e' corretto (si rimette a fuoco con il parametro appena cambiato).
    // 'describe' produce il toast di conferma DALLE impostazioni rilette dal server, non
    // dal valore richiesto: se il server avesse applicato altro, il messaggio direbbe il
    // vero. I pulsanti restano spenti per tutto il giro di rete, che sul bridge seriale
    // puo' durare secondi, altrimenti si accodano richieste a ogni tocco ripetuto.
    // In Manuale il modo rispedito e' "manual": il plugin blocca la lente dov'e' (innocuo)
    // e memorizza i parametri, che il prossimo AF-S usera'. Per questo la ricerca si puo'
    // preparare in ogni modo. [control] e [label] servono a mostrare subito il valore
    // chiesto con "…" finche' la camera non lo conferma.
    private fun applyFocusParams(
        control: FrameLayout,
        what: String,
        label: String,
        range: String? = null,
        speed: String? = null,
        window: List<Double>? = null,
        describe: (settings) -> String,
    ) {
        afParamsInFlight = true
        afParamSending = "$what $label"
        control.findViewById<TextView>(R.id.value).text = "$label…"
        updateControlsEnabled()
        val mode = settings.afmode
        Thread {
            val result = api.setFocusMode(mode, range, speed, window)
            // Rilettura: il valore mostrato dev'essere quello del server, non quello
            // chiesto. Vale doppio qui, perche' questi parametri finiscono anche nel PUT
            // dell'intero oggetto fatto dai +/- degli altri controlli.
            val refreshed = if (result.ok) {
                try { api.getSettings() } catch (e: java.net.ConnectException) { null }
            } else null
            Handler(Looper.getMainLooper()).post {
                afParamsInFlight = false
                afParamSending = null
                focusOutcome = null
                if (result.ok) {
                    if (refreshed != null) {
                        settings = refreshed
                        settingsLoaded = true
                    } else {
                        // Risposta persa sul bridge ma comando riuscito: si allinea
                        // almeno il campo toccato, cosi' il pannello non resta indietro.
                        if (range != null) settings.afrange = range
                        if (speed != null) settings.afspeed = speed
                        if (window != null) settings.afwindow = window
                    }
                    updateValues()
                    Toast.makeText(App.activity, describe(settings), Toast.LENGTH_SHORT).show()
                } else {
                    // Il plugin RIFIUTA un parametro che la camera non espone invece di
                    // ignorarlo in silenzio: il messaggio del server dice quale e perche'.
                    // updateValues() rimette il valore vero al posto di quello con "…".
                    val msg = result.message ?: "Parametro di fuoco non applicato"
                    focusOutcome = FocusOutcome(OutcomeKind.ERROR, "✖ $msg")
                    updateValues()
                    Toast.makeText(App.activity, msg, Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    // Vero mentre un cambio AUTO/MAN dell'esposizione e' in volo.
    private var aeInFlight = false

    // Stato AUTO/MAN scelto con il tasto ma non ancora inviato (vedi btn_ae).
    private var pendingAeEnable: Boolean? = null

    private val sendPendingAeEnable: Runnable = Runnable {
        val enable = pendingAeEnable ?: return@Runnable
        if (!settingsLoaded || onAcquisition) {
            // In acquisizione il server rifiuta il PUT: la scelta si scarta invece di
            // restare in coda e partire minuti dopo, a fine acquisizione.
            pendingAeEnable = null
            updateControlsEnabled()
            return@Runnable
        }
        if (requestInFlight()) {
            // Un'altra richiesta e' ancora in volo: si riprova dopo,
            // invece di accodare un PUT che porterebbe valori vecchi.
            tapDebounceHandler.postDelayed(sendPendingAeEnable, TAP_DEBOUNCE_MS)
            return@Runnable
        }
        pendingAeEnable = null
        if (enable == settings.aeenable) {
            updateControlsEnabled() // tornati allo stato di partenza: niente da inviare
            return@Runnable
        }
        applyAeEnable(enable)
    }

    // Cambio AUTO/MAN dell'esposizione. Passa da PUT /camera/settings come gli altri
    // parametri, ma non puo' usare la verifica generica di setSettings(): al passaggio
    // in manuale la camera parte dal tempo e dal guadagno che l'AE stava usando, quindi
    // la rilettura non coincide con i valori locali (vecchi) anche se il cambio e'
    // riuscito, e compariva "Errore durante la modifica" in ritardo. Qui l'esito si
    // giudica dal solo aeenable riletto, e il pannello prende tempo/ISO dal server -
    // altrimenti il primo +/- rispedirebbe i valori vecchi.
    private fun applyAeEnable(enable: Boolean) {
        settings.aeenable = enable
        aeInFlight = true
        settingsPutsInFlight++
        updateValues()
        val requested = settings.copy()
        Thread {
            val result = api.setSettings(requested)
            val refreshed = try { api.getSettings() } catch (e: java.net.ConnectException) { null }
            Handler(Looper.getMainLooper()).post {
                aeInFlight = false
                settingsPutsInFlight--
                if (refreshed != null) {
                    settings = refreshed
                    settingsLoaded = true
                } else if (!result.ok) {
                    // Ne' conferma ne' rilettura: si torna allo stato di prima invece di
                    // mostrare come attivo un cambio che non risulta applicato.
                    settings.aeenable = !enable
                }
                updateValues()
                val applied = if (refreshed != null) refreshed.aeenable == enable else result.ok
                val msg = result.message
                if (!applied) {
                    Toast.makeText(App.activity,
                        msg ?: "Cambio esposizione automatica non riuscito",
                        Toast.LENGTH_LONG).show()
                } else if (msg != null) {
                    Toast.makeText(App.activity, msg, Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    // Vero mentre un cambio di range/velocita'/finestra e' in volo.
    private var afParamsInFlight = false

    // I parametri di ricerca e il modo viaggiano nello STESSO comando (focus_mode, con
    // il modo sempre rispedito): un parametro inviato durante un cambio di modo si
    // porterebbe dietro il modo vecchio. Si escludono perche' con isBusy() nessun
    // comando parte mentre un altro e' in viaggio. Ammessi in ogni modo, anche Manuale.
    private fun afParamsEditable() = settingsLoaded && !isBusy()

    // Parametro di ricerca in invio, es. "range FULL", per la riga di stato della scheda.
    private var afParamSending: String? = null

    // Vero mentre un cambio di modo e' in volo: il polling di stato richiama
    // updateControlsEnabled() e senza questo riattiverebbe il tasto a meta' attesa.
    private var focusModeInFlight = false

    // Debounce del tasto AUTO/MAN dell'esposizione (vedi btn_ae).
    private val tapDebounceHandler = Handler(Looper.getMainLooper())
    private val TAP_DEBOUNCE_MS = 1000L

    // Modo chiesto e non ancora confermato: evidenziato subito, con "AF-S…" se e' un one-shot.
    private var focusModeRequested: String? = null

    // Esito dell'ultimo comando di fuoco, scritto nella scheda (null = niente da dire).
    private var focusOutcome: FocusOutcome? = null

    // Il modo toccato si invia SEMPRE, anche se coincide con settings.afmode: quella e'
    // la copia locale, e sotto radio puo' non essere lo stato reale. Ripetere un modo e'
    // innocuo (in MF il plugin blocca la lente dov'e'), e su AF-S rifa' la messa a fuoco.
    private fun onFocusModeTapped(mode: String) {
        if (!focusModeButtonEnabled()) return
        applyFocusMode(mode)
    }

    // Cambio di modalita' di fuoco. Passa da PUT /camera/exec e non dai settings, quindi
    // resta possibile anche durante l'acquisizione (a differenza di tutto il resto).
    private fun applyFocusMode(mode: String) {
        // In AF-S il plugin risponde solo a ciclo di messa a fuoco concluso
        // (autofocus_cycle(wait=True)): l'attesa col tasto grigio e' normale.
        focusModeInFlight = true
        focusModeRequested = mode
        focusOutcome = null
        updateControlsEnabled()
        val previous = settings.afmode
        Thread {
            val result = api.setFocusMode(mode)
            // Il plugin aggiorna lensposition dai metadati quando entra in manual o
            // dopo un one-shot: senza rilettura il pannello mostrerebbe il valore vecchio.
            // Si rilegge anche dopo un errore: un AF-S fallito puo' aver comunque
            // cambiato modo o mosso la lente, e il pannello deve dire lo stato vero.
            var refreshed = try { api.getSettings() } catch (e: java.net.ConnectException) { null }
            // Un cambio fallito deve lasciare la camera nel modo di partenza (es. MF ->
            // AF-S che non trova il fuoco -> resta MF). Se il plugin e' rimasto nel modo
            // chiesto, lo si riporta indietro esplicitamente.
            if (!result.ok && refreshed != null && refreshed.afmode != previous) {
                Log.w("UCamera", "focus_mode $mode fallito ma il server e' in ${refreshed.afmode}: ripristino $previous")
                api.setFocusMode(previous)
                refreshed = try { api.getSettings() } catch (e: java.net.ConnectException) { null }
            }
            Handler(Looper.getMainLooper()).post {
                focusModeInFlight = false
                focusModeRequested = null
                focusOutcome = focusModeOutcome(mode, result.ok, refreshed, result.message)
                if (refreshed != null) {
                    settings = refreshed
                    settingsLoaded = true
                } else if (result.ok) {
                    settings.afmode = mode
                }
                updateValues()
                if (result.ok) {
                    // Il nome esteso nel toast conferma cosa si e' appena attivato.
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

        // Parametri della ricerca. Le etichette si scrivono sempre, anche quando il
        // gruppo e' nascosto: cosi' al passaggio in AF-S/AF-C compare gia' valorizzato
        // invece di mostrare per un istante il "--" del layout.
        afrange_control.findViewById<TextView>(R.id.value).text = afRangeLabel(settings.afrange)
        afspeed_control.findViewById<TextView>(R.id.value).text = afSpeedLabel(settings.afspeed)
        afwindow_control.findViewById<TextView>(R.id.value).text = afWindowLabel(settings.afwindow)

        updateControlsEnabled()
    }

    // Unico punto che decide quali controlli sono utilizzabili, invece di spargere
    // isEnabled nei listener. Quattro condizioni indipendenti:
    //  - isBusy(): con una richiesta in viaggio (o una scelta AUTO/MAN in attesa di invio)
    //    tutti i comandi si spengono e compare il cerchio busy_indicator;
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
        // La lente passa da PUT /camera/settings (bloccato in acquisizione) ed e' onorata
        // solo in Manuale; negli altri modi mostra in grigio la posizione letta.
        setParamEnabled(lensposition_control, editable && settings.afmode == FOCUS_MANUAL)

        // I parametri della ricerca passano da exec come il modo di fuoco, quindi restano
        // disponibili in acquisizione: la condizione non dipende da 'editable'.
        val afParams = afParamsEditable()
        setParamEnabled(afrange_control, afParams)
        setParamEnabled(afspeed_control, afParams)
        setParamEnabled(afwindow_control, afParams)

        // I due gruppi dell'esposizione si escludono, perche' libcamera li tratta cosi':
        // con AE attivo l'AGC riscrive tempo e guadagno a ogni frame (e i relativi
        // controlli non farebbero nulla), con AE spento e' la compensazione EV a non
        // avere piu' senso. Mostrarli tutti e tre sempre attivi era il motivo per cui
        // "il cambio ISO non avviene" sembrava un bug dell'app.
        setParamEnabled(exposure_control, editable && settings.aeenable)
        setParamEnabled(exposuretime_control, editable && !settings.aeenable)
        setParamEnabled(gain_control, editable && !settings.aeenable)
        btn_ae.isEnabled = aeButtonEnabled()
        // Scritto qui (non in updateValues) per lo stesso motivo del tasto del fuoco:
        // il polling richiama updateControlsEnabled() e deve rispettare "..." e la
        // selezione in attesa di invio.
        btn_ae.text = when {
            aeInFlight -> "..."
            pendingAeEnable != null -> if (pendingAeEnable == true) "AUTO" else "MAN"
            else -> if (settings.aeenable) "AUTO" else "MAN"
        }

        // Lo stato AUTO/MAN sta nell'etichetta, non cliccabile e quindi non fraintendibile:
        // spiega anche perche' con AE attivo "Tempo di esposizione" e "ISO" sono spenti.
        val aeTag = if (!settingsLoaded) "--" else if (settings.aeenable) "AUTO" else "MAN"
        exposure_control.findViewById<TextView>(R.id.label).text = "Esposizione · $aeTag"

        updateFocusPanel()
        btn_reset_settings.isEnabled = editable
        btn_preview_image.isEnabled = settingsLoaded && !onAcquisition && !isBusy()
        btn_start_acquisition.isEnabled = !isBusy()
        busy_indicator.visibility = if (requestInFlight()) View.VISIBLE else View.GONE
        userRequestActive = requestInFlight()
    }

    // Scheda Fuoco: il modo in uso (o quello appena chiesto) e' il pulsante pieno, la riga
    // di stato dice cosa sta succedendo, sotto resta l'esito dell'ultimo comando.
    // Chiamata da updateControlsEnabled(), quindi segue ogni cambio di stato.
    private fun updateFocusPanel() {
        val enabled = focusModeButtonEnabled()
        val active = if (settingsLoaded) (focusModeRequested ?: settings.afmode) else null
        styleFocusButton(btn_focus_mf, FOCUS_MANUAL, active, enabled)
        styleFocusButton(btn_focus_afs, FOCUS_SINGLE, active, enabled)
        styleFocusButton(btn_focus_afc, FOCUS_CONTINUOUS, active, enabled)
        focus_status.text = focusStatusText()

        val outcome = focusOutcome
        focus_result.visibility = if (outcome == null) View.GONE else View.VISIBLE
        if (outcome != null) {
            focus_result.text = outcome.text
            focus_result.setTextColor(when (outcome.kind) {
                OutcomeKind.OK -> COLOR_OK
                OutcomeKind.ERROR -> COLOR_ERROR
                OutcomeKind.INFO -> Color.DKGRAY
            })
        }
    }

    private fun focusStatusText(): String {
        val requested = focusModeRequested
        val sending = afParamSending
        return when {
            !settingsLoaded -> "In attesa delle impostazioni dalla camera…"
            requested == FOCUS_SINGLE -> "Messa a fuoco in corso con " + afSearchSummary(settings)
            requested != null -> "Passaggio a ${focusModeName(requested)}…"
            sending != null -> "Invio $sending alla camera…"
            requestInFlight() -> "Attendere: richiesta in corso…"
            else -> "In uso: ${focusModeName(settings.afmode)} · ${focusModeBehaviour(settings.afmode)}"
        }
    }

    // Pulsante di un modo: sigla grande e nome piccolo sotto; pieno se e' il modo attivo.
    // Su AF-S attivo il nome diventa l'azione disponibile ("tocca = rifai").
    private fun styleFocusButton(button: Button, mode: String, active: String?, enabled: Boolean) {
        val selected = mode == active
        val oneShotRunning = selected && mode == FOCUS_SINGLE && focusModeRequested == FOCUS_SINGLE
        val tag = focusModeLabel(mode) + if (oneShotRunning) "…" else ""
        val sub = when {
            oneShotRunning -> "in corso"
            selected && mode == FOCUS_SINGLE -> "tocca = rifai"
            else -> focusModeName(mode)
        }
        button.text = SpannableString("$tag\n$sub").apply {
            setSpan(RelativeSizeSpan(0.7f), tag.length + 1, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        button.backgroundTintList = ColorStateList.valueOf(if (selected) COLOR_SELECTED else COLOR_UNSELECTED)
        button.setTextColor(if (selected) Color.WHITE else COLOR_SELECTED)
        button.isEnabled = enabled
        button.alpha = if (enabled) 1.0f else 0.4f
    }

    // Badge WIFI/RADIO nella barra: collegamento usato dall'ultima richiesta riuscita
    // (vedi RoutingInterceptor). Riguarda i comandi; la preview video passa dalla seriale.
    private fun showLink(link: Link) {
        val wifi = link == Link.WIFI
        link_badge.text = if (wifi) "WIFI" else "RADIO"
        link_badge.setBackgroundColor(if (wifi) COLOR_OK else COLOR_RADIO)
        link_badge.visibility = View.VISIBLE
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
        applyLayout()
        calculateSizeAndPosition(windowParams, windowWidth, currentCompactHeight())
        try {
            windowManager.updateViewLayout(rootView, windowParams)
        } catch (e: Exception) {
            Log.e("UCamera", "toggleFullscreen: " + e.message, e)
        }
    }

    // Altezza della finestra compatta in base al pannello aperto (ignorata in fullscreen).
    private fun currentCompactHeight(): Int = compactHeight(anyPanelOpen())

    private fun anyPanelOpen() = panels().any { it.visibility == View.VISIBLE }

    // Disposizione di preview e pannello, in tre casi:
    //  - compatta: preview sopra (alta previewHeightCompact()), pannello sotto;
    //  - tutto schermo con display verticale: uguale, ma la preview prende l'altezza libera;
    //  - tutto schermo con display orizzontale: pannello a DESTRA della preview, largo come la
    //    finestra compatta e alto quanto lo schermo. Con il pannello sotto, in orizzontale la
    //    preview restava bassa e lo stream non diventava piu' grande aprendo i parametri.
    // La proporzione dell'immagine la preserva il renderer (vedi SerialJpegPlayer.renderFrame).
    private fun applyLayout() {
        val dm = getCurrentDisplayMetrics()
        val panelOpen = anyPanelOpen()
        val sideBySide = isFullscreen && dm.widthPixels > dm.heightPixels
        fun px(dp: Int) = (dp * dm.density).toInt()

        content.orientation = if (sideBySide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        // Corpo: a fianco del pannello prende tutta la larghezza rimasta (peso 1).
        val bodyParams = body.layoutParams as LinearLayout.LayoutParams
        bodyParams.width = if (sideBySide) 0 else MATCH
        bodyParams.weight = if (sideBySide) 1f else 0f
        body.layoutParams = bodyParams

        panels_container.visibility = if (panelOpen) View.VISIBLE else View.GONE
        setSize(panels_container, if (sideBySide) px(windowWidth) else MATCH, if (sideBySide) MATCH else WRAP)
        // Le schede che scorrono usano tutta l'altezza quando stanno a destra.
        val scrollHeight = if (sideBySide) MATCH else px(panelHeight)
        setSize(settings_panel, MATCH, scrollHeight)
        setSize(focus_panel, MATCH, scrollHeight)

        val compact = px(previewHeightCompact(panelOpen))
        val previewHeight = when {
            sideBySide -> dm.heightPixels - px(barHeight + tabsHeight)
            isFullscreen -> dm.heightPixels - px(barHeight + tabsHeight + (if (panelOpen) panelHeight else 0))
            else -> compact
        }.coerceAtLeast(px(MIN_PREVIEW_DP))
        setSize(preview_container, MATCH, previewHeight)
    }

    // Lo stream ha cambiato dimensioni (o e' arrivato il primo frame): se cambiano le
    // proporzioni si ridimensionano preview e finestra, cosi' l'immagine riempie la larghezza.
    private fun onStreamFrameSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val aspect = (width.toDouble() / height).coerceIn(MIN_PREVIEW_ASPECT, MAX_PREVIEW_ASPECT)
        if (Math.abs(aspect - previewAspect) < 0.01) return
        previewAspect = aspect
        applyLayout()
        calculateSizeAndPosition(windowParams, windowWidth, currentCompactHeight())
        try {
            windowManager.updateViewLayout(rootView, windowParams)
        } catch (e: Exception) {
            Log.e("UCamera", "onStreamFrameSize: " + e.message, e)
        }
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
        runQuietly("stopStatusUpdates") { stopStatusUpdates() }
        // Un cambio AUTO/MAN ancora in attesa non deve partire su un api gia' chiuso.
        runQuietly("tapDebounce") {
            tapDebounceHandler.removeCallbacks(sendPendingAeEnable)
            pendingAeEnable = null
        }
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

    // Pannelli sotto la preview: ne e' aperto al massimo uno. Un tap sul pulsante del
    // pannello gia' aperto lo chiude e la finestra torna compatta. A tutto schermo le
    // dimensioni non cambiano (vedi calculateSizeAndPosition()).
    private fun togglePanel(panel: FrameLayout) {
        val open = panel.visibility != View.VISIBLE
        for (p in panels()) {
            p.visibility = if (open && p === panel) View.VISIBLE else View.GONE
        }
        updateTabs()
        applyLayout() // pannello sotto o a destra, e altezza della preview
        calculateSizeAndPosition(windowParams, windowWidth, currentCompactHeight())
        windowManager.updateViewLayout(rootView, windowParams)
    }

    // Ogni scheda con il suo pannello, nell'ordine della riga sotto la preview.
    private fun tabs() = listOf(
        btn_open_acquisition to acquisition_panel,
        btn_open_config to settings_panel,
        btn_open_focus to focus_panel,
        btn_open_other to other_panel,
    )

    private fun panels() = tabs().map { it.second }

    // La scheda del pannello aperto e' piu' scura con il testo ambra, le altre restano viola.
    private fun updateTabs() {
        for ((button, panel) in tabs()) {
            val selected = panel.visibility == View.VISIBLE
            button.backgroundTintList =
                ColorStateList.valueOf(if (selected) COLOR_TAB_SELECTED else COLOR_SELECTED)
            button.setTextColor(if (selected) COLOR_TAB_TEXT_SELECTED else Color.WHITE)
        }
    }

    fun openAcquisition() = togglePanel(acquisition_panel)

    fun openConfig() = togglePanel(settings_panel)

    fun openFocus() = togglePanel(focus_panel)

    fun openOther() = togglePanel(other_panel)

    @RequiresApi(Build.VERSION_CODES.O)
    fun startAcquisition(video: Boolean=false){
       // Disabilita subito il pulsante e mostra un testo "in corso" (sync, siamo sul
       // main thread del click) cosi' tap ripetuti mentre la richiesta e' in volo
       // (puo' impiegare diversi secondi sul bridge seriale/radio, ora anche in coda
       // dietro al lock di SerialBridge) non spammano il server, e il pulsante non
       // sembra bloccato/rotto nell'attesa.
       if (isBusy()) return // un'altra richiesta e' in volo: il pulsante e' gia' spento
       acquisitionCommandInFlight = true
       btn_start_acquisition.text = if (onAcquisition) "Fermando..." else "Iniziando..."
       updateControlsEnabled()
       // Le chiamate api.* sono sincrone (Retrofit .execute()) e possono impiegare
       // diversi secondi sul bridge seriale/radio: girate su un thread apposito per
       // non bloccare il main thread (rischio ANR, vedi timeout SerialBridge).
       remoteStateIgnoredUntil = Long.MAX_VALUE
       Thread {
         try {
           if(onAcquisition){

               if(api.stopDataset()) {
                   pollingStatus?.trackDataset(null)
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
                       // Id sconosciuto (ALREADY_RUNNING): il polling lo riscopre da GET /datasets/.
                       pollingStatus?.trackDataset(result.takeIf { it > -1 })
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
                       pollingStatus?.trackDataset(result.takeIf { it > -1 })
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
             remoteStateIgnoredUntil = System.currentTimeMillis() + STATE_GRACE_MS
             Handler(Looper.getMainLooper()).post {
                 acquisitionCommandInFlight = false
                 updateControlsEnabled()
             }
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

    // Aggiornamenti dalle sorgenti di stato (thread di rete -> main thread).
    private val statusListener = object : StatusListener {

        override fun onAcquisitionProgress(datasetId: Int, items: Int) {
            Handler(Looper.getMainLooper()).post {
                // Solo testo: con l'acquisizione ferma resta "Ready" (il polling o un evento in
                // ritardo potrebbero riportare l'ultimo progresso dopo lo stop).
                if (onAcquisition && !isRemoteStateIgnored()) {
                    status.text = "Dataset $datasetId Foto $items"
                }
            }
        }

        override fun onRecordingState(isRecording: Boolean) {
            if (isRemoteStateIgnored()) return
            Handler(Looper.getMainLooper()).post {
                // Solo se cambia: setAcquisitionState(false) riscrive "Ready" a ogni chiamata.
                if (!isRemoteStateIgnored() && isRecording != onAcquisition) {
                    setAcquisitionState(isRecording)
                }
            }
        }

        override fun onDepth(meters: Double) {
            Handler(Looper.getMainLooper()).post { depth.text = "%.2f mt".format(meters) }
        }
    }

    private fun isRemoteStateIgnored() = System.currentTimeMillis() < remoteStateIgnoredUntil

    // (Ri)crea le sorgenti di stato per l'host corrente: updateConnection() puo' essere
    // richiamata (cambio IP, "Aggiorna preview") e prima il socket Socket.IO veniva creato
    // una sola volta, restando sull'indirizzo iniziale.
    private fun startStatusUpdates(initial: AcquisitionStatus?) {
        stopStatusUpdates()
        // Il polling salta i giri mentre un comando dell'utente e' in viaggio (scatto di
        // prova compreso): sotto radio avrebbero la precedenza sul bridge e lo ritarderebbero.
        val polling = PollingStatusSource(api, statusListener, isPaused = { userRequestActive })
        polling.trackDataset(initial?.takeIf { it.running }?.datasetId)
        val live = SocketIoStatusSource("http://$remote_host:$remote_port/", statusListener)
        pollingStatus = polling
        statusCoordinator = StatusCoordinator(live, polling).also { it.start() }
    }

    private fun stopStatusUpdates() {
        statusCoordinator?.stop()
        statusCoordinator = null
        pollingStatus = null
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
        api.linkListener = { link -> Handler(Looper.getMainLooper()).post { showLink(link) } }

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
                startStatusUpdates(acqStatus)

                updateValues()
                if (answerAddress) {
                    // "Aggiorna preview" fermava la preview senza mai riavviarla (la
                    // startPreview() in fondo a questo blocco era commentata): il
                    // pulsante faceva l'opposto di quello che dichiara.
                    stopPreview()
                    startPreview()
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
                serialJpegVideoPlayer = SerialJpegPlayer(serialPort, surface) { w, h ->
                    Handler(Looper.getMainLooper()).post { onStreamFrameSize(w, h) }
                }
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

    private companion object {
        // Dopo uno start/stop lo stato riportato dal server si ignora ancora per questo
        // tempo: copre eventi Socket.IO e risposte di polling partiti prima del comando.
        const val STATE_GRACE_MS = 3_000L

        // Colori della scheda Fuoco e del badge del collegamento.
        val COLOR_SELECTED = Color.parseColor("#6200EE")   // colorPrimary del tema
        val COLOR_UNSELECTED = Color.parseColor("#EDE7F6")
        val COLOR_OK = Color.parseColor("#1F8A4C")
        val COLOR_ERROR = Color.parseColor("#B3261E")
        val COLOR_RADIO = Color.parseColor("#A15C00")
        val COLOR_TAB_SELECTED = Color.parseColor("#3700B3") // colorPrimaryVariant del tema
        val COLOR_TAB_TEXT_SELECTED = Color.parseColor("#FFB300")
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