package com.trediresearch.ucamera

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.StrictMode
import android.os.StrictMode.ThreadPolicy
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.View.OnTouchListener
import android.view.WindowManager
import android.webkit.WebView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import io.socket.client.Socket
import io.socket.emitter.Emitter
import org.json.JSONObject
import java.time.LocalDate


@RequiresApi(Build.VERSION_CODES.O)
class Window(private val context: Context) {
//    var webserver_url="http://192.168.1.145:45032"

    var webserver_url="http://192.168.0.109:45032"
    var onAcquisition=false;
    val windowHeight=150
    val windowHeightMax=900
    val windowWidth=270
    var interval=5
    var camera_connected=false;
    var OnCameraConnected=false;

    lateinit var settings:settings
    lateinit var api:Webserver

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
    lateinit var preview:WebView
    lateinit var status:TextView
    lateinit var recording:ImageView

    lateinit var btn_open_acquisition:Button
    lateinit var btn_open_config:Button
    val EXPOSURE_TIME_LABEL= arrayListOf<String>("1/2","1/4","1/8","1/15","1/30","1/60","1/125","1/250","1/500","1/1000","1/2000")
    val EXPOSURE_TIME= arrayListOf<Int>(453000,250000,125000,66666,33333,16666,8000,4000,2000,1000,500)


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
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
        PixelFormat.TRANSLUCENT
    )


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
        // We have to set gravity for which the calculated position is relative.
        params.gravity = Gravity.TOP or Gravity.RIGHT
        params.width = (widthInDp * dm.density).toInt()
        params.height = (heightInDp * dm.density).toInt()
        params.y = 130
        params.x = 30






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
                        windowParams.x = initialX - (event.rawX - initialTouchX).toInt()
                        windowParams.y = initialY + (event.rawY - initialTouchY).toInt()
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

        btn_preview_image=rootView.findViewById(R.id.btn_preview_image) as Button

        btn_open_acquisition=rootView.findViewById(R.id.btn_open_acquisition) as Button
        btn_open_config=rootView.findViewById(R.id.btn_open_config) as Button
        btn_collapse=rootView.findViewById(R.id.btn_collapse) as Button

        preview=rootView.findViewById(R.id.preview) as WebView
        //rectimage=rootView.findViewById(R.id.rect) as ImageView
        status=rootView.findViewById(R.id.status) as TextView
        brightness_control.findViewById<TextView>(R.id.label).text="Luminosità"
        contrast_control.findViewById<TextView>(R.id.label).text="Contrasto"
        sharpness_control.findViewById<TextView>(R.id.label).text="Nitidezza"
        saturation_control.findViewById<TextView>(R.id.label).text="Saturazione"
        exposure_control.findViewById<TextView>(R.id.label).text="Esposizione"
        exposuretime_control.findViewById<TextView>(R.id.label).text="Tempo di esposizione (ms)"
        lensposition_control.findViewById<TextView>(R.id.label).text="Fuoco"
        interval_control.findViewById<TextView>(R.id.label).text="Intervallo scatto (s)"
        gain_control.findViewById<TextView>(R.id.label).text="ISO"



        /*Abilitazione drag and drop della window*/



        val policy = ThreadPolicy.Builder()
            .permitAll().build()
        StrictMode.setThreadPolicy(policy)



        brightness_control.findViewById<Button>(R.id.btn_plus).setOnClickListener {
            if(settings.brightness < 1) {
                settings.brightness = settings.brightness+0.1;
                api.setSettings(settings)
                updateValues()
            }
        }

        brightness_control.findViewById<Button>(R.id.btn_minus).setOnClickListener {
            if(settings.brightness > -1) {
                settings.brightness =settings.brightness -0.1;
                setSettings()
            }
        }


        contrast_control.findViewById<Button>(R.id.btn_plus).setOnClickListener {
            if(settings.contrast < 32) {
                settings.contrast = settings.contrast+1;
                setSettings()

            }
        }

        contrast_control.findViewById<Button>(R.id.btn_minus).setOnClickListener {
            if(settings.contrast > 0) {
                settings.contrast =settings.contrast -1;
                setSettings()

            }
        }


        sharpness_control.findViewById<Button>(R.id.btn_plus).setOnClickListener {
            if(settings.sharpness < 16) {
                settings.sharpness = settings.sharpness+1;
                setSettings()

            }
        }

        sharpness_control.findViewById<Button>(R.id.btn_minus).setOnClickListener {
            if(settings.sharpness > 0) {
                settings.sharpness =settings.sharpness -1;
                setSettings()

            }
        }

        saturation_control.findViewById<Button>(R.id.btn_plus).setOnClickListener {
            if(settings.saturation < 32) {
                settings.saturation = settings.saturation+1;
                setSettings()

            }
        }

        saturation_control.findViewById<Button>(R.id.btn_minus).setOnClickListener {
            if(settings.saturation > 0) {
                settings.saturation =settings.saturation -1;
                setSettings()

            }
        }

        exposure_control.findViewById<Button>(R.id.btn_plus).setOnClickListener {
            if(settings.exposurevalue < 8) {
                settings.exposurevalue = settings.exposurevalue+1;
                setSettings()

            }
        }

        exposure_control.findViewById<Button>(R.id.btn_minus).setOnClickListener {
            if(settings.exposurevalue > -8) {
                settings.exposurevalue =settings.exposurevalue -1;
                setSettings()

            }
        }

        exposuretime_control.findViewById<Button>(R.id.btn_plus).setOnClickListener {
                var id=0
                var value=EXPOSURE_TIME[id]

                for(v in EXPOSURE_TIME){
                        if(v==settings.exposureTime){
                            if(id<EXPOSURE_TIME.size)
                                value=EXPOSURE_TIME[id+1]
                            break
                        }
                        id++
                }

                settings.exposureTime=value
                setSettings()


        }

        exposuretime_control.findViewById<Button>(R.id.btn_minus).setOnClickListener {
            var id=0
            var value=EXPOSURE_TIME[id]

            for(v in EXPOSURE_TIME){
                if(v==settings.exposureTime){
                    if(id>0)
                        value=EXPOSURE_TIME[id-1]
                    break
                }
                id++
            }
            settings.exposureTime=value
            setSettings()


        }


        lensposition_control.findViewById<Button>(R.id.btn_plus).setOnClickListener {
            if(settings.lensposition < 32) {
                settings.lensposition = settings.lensposition+1;
                setSettings()

            }
        }

        lensposition_control.findViewById<Button>(R.id.btn_minus).setOnClickListener {
            if(settings.lensposition > 0) {
                settings.lensposition =settings.lensposition -1;
                setSettings()

            }
        }


        interval_control.findViewById<Button>(R.id.btn_plus).setOnClickListener {
            if(interval < 20) {
                interval=interval+1
                updateValues()
            }
        }

        interval_control.findViewById<Button>(R.id.btn_minus).setOnClickListener {
            if(interval > 0) {
                interval =interval -1;
                updateValues()
            }
        }

        gain_control.findViewById<Button>(R.id.btn_plus).setOnClickListener {
            if(settings.gain<9) {
                settings.gain=settings.gain+1
                setSettings()
            }
        }

        gain_control.findViewById<Button>(R.id.btn_minus).setOnClickListener {
        if(settings.gain>0){
            settings.gain =settings.gain -1;
            setSettings()
            }
        }



        btn_preview_image.setOnClickListener{
            Handler(Looper.getMainLooper()).post {
                btn_preview_image.isEnabled=false
                Toast.makeText(App.activity,"Cattura dello scatto di prova in corso...", Toast.LENGTH_SHORT).show()


            }




            Thread {
                val image = api.capture()
                if (image != null) {
                    val imageViewer: ImageViewer = ImageViewer(context, image)
                    imageViewer.open()
                } else {
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(
                            App.activity,
                            "Errore durante lo scatto di prova",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
                Handler(Looper.getMainLooper()).post {
                    btn_preview_image.isEnabled = true
                }

            }.start()


        }


        btn_open_config.setOnClickListener{
            val u:Uploader=Uploader()
            u.uploadFirmware()
            openConfig()
        }

        rootView.findViewById<Button>(R.id.btn_open_other).setOnClickListener{
            openOther()
        }

        btn_open_acquisition.setOnClickListener{
            openAcquisition()
        }

        rootView.findViewById<Button>(R.id.btn_refresh_preview).setOnClickListener{
            updateConnection()
        }

        rootView.findViewById<Button>(R.id.btn_reset_settings).setOnClickListener{
            resetSettings()
        }



        rootView.findViewById<Button>(R.id.btn_quit).setOnClickListener{
            App.activity.finish()
            System.exit(0);


        }

        btn_collapse.setOnClickListener {
           collapse()
        }

        btn_start_acquisition.setOnClickListener{
            startAcquisition()
        }

        btn_start_video.setOnClickListener{
            startAcquisition(true)
        }


        updateConnection()
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

    fun resetSettings(){
        settings.gain=1;
        settings.contrast= 1.0;
        settings.brightness= 0.0
        settings.sharpness=1.0
        settings.exposureTime=250000
        settings.exposurevalue=0
        settings.lensposition=0.0
        settings.saturation= 1.0
        setSettings()
        updateValues()


    }

    fun setSettings(){
        if(api.setSettings(settings)){
            updateValues()
        }else{
            Toast.makeText(App.activity,"Errore durante la modifica delle impostazioni", Toast.LENGTH_LONG).show()
        }
    }

    fun updateValues(){
        brightness_control.findViewById<TextView>(R.id.value).text=settings.brightness.toString()
        contrast_control.findViewById<TextView>(R.id.value).text=settings.contrast.toString()
        sharpness_control.findViewById<TextView>(R.id.value).text=settings.sharpness.toString()
        saturation_control.findViewById<TextView>(R.id.value).text=settings.saturation.toString()
        exposure_control.findViewById<TextView>(R.id.value).text=settings.exposurevalue.toString()

        var id=0
        for(v in EXPOSURE_TIME){
            if(v==settings.exposureTime){
                exposuretime_control.findViewById<TextView>(R.id.value).text=EXPOSURE_TIME_LABEL[id]
                break
            }
            id++
        }


        lensposition_control.findViewById<TextView>(R.id.value).text=settings.lensposition.toString()
        interval_control.findViewById<TextView>(R.id.value).text=interval.toString()


        gain_control.findViewById<TextView>(R.id.value).text=(settings.gain*100).toString()


    }

    init {
        initWindowParams()
        initWindow()
    }


    fun open() {
        try {
            windowManager.addView(rootView, windowParams)
        } catch (e: Exception) {
            // Ignore exception for now, but in production, you should have some
            // warning for the user here.
        }
    }


    fun close() {
        try {
            windowManager.removeView(rootView)
        } catch (e: Exception) {
            // Ignore exception for now, but in production, you should have some
            // warning for the user here.
        }
    }

    fun openAcquisition(){
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
       if(onAcquisition){

           if(api.stopDataset()) {
               setAcquisitionState(false)
           }else{
               Toast.makeText(App.activity,"Errore durante l'arresto dell'acquisizione", Toast.LENGTH_LONG).show()
           }
       }else{
           val d:dataset=dataset()
           d.datasetname= LocalDate.now().toString()

           if(!video){
               if(api.startDataset(d)>-1) {
                   setAcquisitionState(true)
               }else{
                   Toast.makeText(App.activity,"Errore durante l'avvio dell'acquisizione", Toast.LENGTH_LONG).show()
               }
           }else{
               if(api.startVideo(d)>-1) {
                   setAcquisitionState(true)
               }else{
                   Toast.makeText(App.activity,"Errore durante l'avvio dell'acquisizione", Toast.LENGTH_LONG).show()
               }
           }

       }
    }

    fun setAcquisitionState(state:Boolean){
        Handler(Looper.getMainLooper()).post {
            if (state) {
                recording.visibility=ImageView.VISIBLE
                btn_start_acquisition.text = "Ferma"
                onAcquisition = true
            } else {
                status.text = "Ready"
                recording.visibility=ImageView.GONE
                btn_start_acquisition.text = "Avvia"
                onAcquisition = false
            }
        }
    }

    fun updateConnection(){
        var s:SocketIOConnection=SocketIOConnection()
        s.init(webserver_url)
        onCameraState(false)

        api= Webserver();
        api.init(webserver_url)
        settings= api.getSettings()
        updateValues()

        s.socket.on(Socket.EVENT_CONNECT,Emitter.Listener {
            onCameraState(true)
        })

        s.socket.on(Socket.EVENT_DISCONNECT,Emitter.Listener {
            onCameraState(false)
            Thread.sleep(2000)
            s.init(webserver_url);
        })


        s.socket.on("device_status", Emitter.Listener { it->
            it.forEach {
                    row->
                var device=row as JSONObject
                if(device.get("name")=="arducam"){
                    if(!(device.get("is_recording") as Boolean)){
                        setAcquisitionState(false)
                    }
                }
            }

        })

        s.socket.on("datasets_storage_status", Emitter.Listener { it->
            it.forEach {
                    row->

                setAcquisitionState(true)
                var dataset=row as JSONObject
                Handler(Looper.getMainLooper()).post {
                    status.text = "Dataset " + dataset.getJSONObject("current_camera_acquisition")
                        .get("dataset_id")
                        .toString() + " Foto " + dataset.getJSONObject("current_camera_acquisition")
                        .get("items").toString()
                }
            }

        });

        s.socket.connect()
        startPreview()

    }

    fun startPreview(){
            preview.loadUrl(webserver_url+"/preview")
            preview.reload()

    }
    @SuppressLint("ResourceAsColor")
    fun onCameraState(connected:Boolean){
        camera_connected=connected;
        Handler(Looper.getMainLooper()).post {
            if (connected) {
                main_panel.setBackgroundColor(R.color.white)
                status.text = "Ready"
                btn_open_config.isEnabled = true
                btn_open_acquisition.isEnabled = true
                preview.visibility = WebView.VISIBLE
            } else {
                main_panel.setBackgroundColor(R.color.purple_200)
                status.text = "No connected"
                btn_open_config.isEnabled = false
                btn_open_acquisition.isEnabled = false
                preview.visibility = WebView.INVISIBLE

            }
        }

    }


    fun uploadFirmware(){

        val intent = Intent()
            .setType("*/*")
            .setAction(Intent.ACTION_GET_CONTENT)

        App.activity.startActivityForResult(Intent.createChooser(intent, "Select a file"), 111)



    }

}