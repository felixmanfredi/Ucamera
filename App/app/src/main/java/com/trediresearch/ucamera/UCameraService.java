package com.trediresearch.ucamera;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

public class UCameraService extends Service {

    private static final int NOTIF_ID=1;

    public UCameraService() {
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }


    @SuppressLint("ForegroundServiceType")
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground();
        return super.onStartCommand(intent, flags, startId);
    }

    @SuppressLint("ForegroundServiceType")
    private void startForeground(){
        Intent notificationIntent=new Intent(this,MainActivity.class);
        PendingIntent pendingIntent=PendingIntent.getActivity(this,0,notificationIntent,PendingIntent.FLAG_IMMUTABLE);

        Notification notification= null;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            notification = new Notification.Builder(this, App.CHANNEL_ID)
                    .setContentTitle("MCS")
                    .setContentText("MCS is running background")
                    .setSmallIcon(R.drawable.ic_launcher_background)
                    .setContentIntent(pendingIntent)
                    .build();
        }

        startForeground(NOTIF_ID,notification);

    }
}