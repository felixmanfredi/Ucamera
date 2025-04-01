package com.trediresearch.ucamera

import android.Manifest
import android.app.Activity
import android.bluetooth.*
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.provider.Settings
import android.util.Log
import android.view.Menu
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.net.toFile
import androidx.navigation.ui.AppBarConfiguration
import com.android.volley.Request
import com.android.volley.Response
import com.android.volley.toolbox.StringRequest
import com.android.volley.toolbox.Volley
import java.io.File
import java.util.*


class MainActivity : AppCompatActivity() {
    private var intentService: Intent? = null

    var mView: HUDView? = null

    private lateinit var appBarConfiguration: AppBarConfiguration




    @RequiresApi(Build.VERSION_CODES.S)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        App.activity=this;
        // Start the foreground service.
        startFloatingService()

        loadConfig()
        checkPermissionGiven()

    }

    override fun onDestroy() {

        stopFloatingService()
        super.onDestroy()
    }


    private val resultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (it.resultCode != Activity.RESULT_OK) {
            startService(intentService)
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun checkPermissionGiven() {

    requestPermissions(
        arrayOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            //Manifest.permission.BLUETOOTH_PRIVILEGED,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.SYSTEM_ALERT_WINDOW,
            Manifest.permission.CHANGE_WIFI_STATE,
            Manifest.permission.ACCESS_WIFI_STATE,

        ),1
    )

        if (!Settings.canDrawOverlays(this)) {
            val localIntent = Intent("android.settings.action.MANAGE_OVERLAY_PERMISSION")
            localIntent.setData(Uri.parse("package:$packageName"))
            localIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(localIntent)
        }

        // Make sure we have access coarse location enabled, if not, prompt the user to enable it
        // Make sure we have access coarse location enabled, if not, prompt the user to enable it
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            val builder: AlertDialog.Builder = AlertDialog.Builder(this)
            builder.setTitle("This app needs location access")
            builder.setMessage("Please grant location access so this app can detect peripherals.")
            builder.setPositiveButton(android.R.string.ok, null)
            builder.setOnDismissListener(DialogInterface.OnDismissListener {
                requestPermissions(
                    arrayOf(
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    ), 1
                )
            })
            builder.show()
        }

        if (checkSelfPermission(Manifest.permission.BLUETOOTH_ADMIN) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                arrayOf(
                    Manifest.permission.BLUETOOTH_ADMIN
                ), 100
            )
        }


        if (checkSelfPermission(Manifest.permission.BLUETOOTH) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                arrayOf(
                    Manifest.permission.BLUETOOTH
                ), 100
            )
        }

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_SCAN
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return
        }




    }



    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Inflate the menu; this adds items to the action bar if it is present.
        //menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }




    fun saveConfig() {
        val prefs: SharedPreferences = this.getSharedPreferences(
            "com.trediresearch.ucamera", MODE_PRIVATE
        )
        /*
        prefs.edit().putInt("step", App.step).apply();
        prefs.edit().putLong("delay_shoot", App.delay_shoot).apply();
        */

    }

    fun loadConfig(){
        val prefs: SharedPreferences = this.getSharedPreferences(
            "com.trediresearch.ucamera", MODE_PRIVATE
        )
        /*
        App.step=prefs.getInt("step",30);
        App.delay_shoot=prefs.getLong("delay_shoot",2000);
        */

    }


    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == 111 && resultCode == RESULT_OK) {
            val selectedFile = data?.data // The URI with the location of the file

            val uploader:Uploader=Uploader()
            if (selectedFile != null) {
                val file = createTempFile()
                selectedFile?.let { this.contentResolver.openInputStream(it) }.use { input ->
                    file.outputStream().use { output ->
                        input?.copyTo(output)
                    }
                }

                uploader.copyFileToSftp(file,"/home/admin/test.pdf")
                //uploader.runCmdToSSH()
                file.delete()


            }


        }
    }

}


class HUDView(context: Context?) : ViewGroup(context) {
    private val mLoadPaint: Paint

    init {
        Toast.makeText(getContext(), "HUDView", Toast.LENGTH_LONG).show()
        mLoadPaint = Paint()
        mLoadPaint.isAntiAlias = true
        mLoadPaint.textSize = 10f
        mLoadPaint.setARGB(255, 255, 0, 0)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawText("Hello World", 5f, 15f, mLoadPaint)
    }

    override fun onLayout(arg0: Boolean, arg1: Int, arg2: Int, arg3: Int, arg4: Int) {}
    override fun onTouchEvent(event: MotionEvent): Boolean {
        //return super.onTouchEvent(event);
        Toast.makeText(context, "onTouchEvent", Toast.LENGTH_LONG).show()
        return true
    }
}