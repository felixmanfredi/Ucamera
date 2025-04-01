package com.trediresearch.ucamera;

import android.app.Activity;
import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.bluetooth.BluetoothGatt;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;


public class App extends Application {

    public static App CurrentApp;
    public static boolean is_connected=false;
    public static boolean is_goproconnected=false;
    public static int step=30;
    public static long delay_shoot=1000;

    public static int batteryLevel=0;

    public static BluetoothGatt bluetoothGatt= null;
    public static BluetoothGatt bluetoothGattGoPro=null;
    public static Activity activity=null;

    public static final String CHANNEL_ID="UCameraServiceChannel";
    @Override
    public void onCreate() {

        App.CurrentApp=this;
        super.onCreate();
        createNotificationChannel();
        startService(new Intent(this,UCameraService.class));
    }

    private void createNotificationChannel(){
        if(Build.VERSION.SDK_INT>= Build.VERSION_CODES.O){
            NotificationChannel serviceChannel=new NotificationChannel(CHANNEL_ID,"UCamera Service Channel", NotificationManager.IMPORTANCE_DEFAULT);
            NotificationManager notificationManager=getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(serviceChannel);
        }
    }


    public void QuitApplication(){
        stopService(new Intent(App.CurrentApp,UCameraService.class));
        System.exit(0);
    }
}
