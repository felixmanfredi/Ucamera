package com.trediresearch.ucamera

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.activity.viewModels
import androidx.core.app.NotificationCompat


const val INTENT_COMMAND = "ucamera.tredireseach.COMMAND"
const val INTENT_COMMAND_EXIT = "EXIT"
const val INTENT_COMMAND_NOTE = "NOTE"

private const val NOTIFICATION_CHANNEL_GENERAL = "mcs4"
private const val CODE_FOREGROUND_SERVICE = 1
private const val CODE_EXIT_INTENT = 2
private const val CODE_NOTE_INTENT = 3


class FloatingService : Service() {


    override fun onBind(intent: Intent?): IBinder? = null

    // Anche quando e' il sistema a terminare il servizio la finestra va chiusa:
    // altrimenti restano vivi il ReadThread su /dev/ttyHS0 e il polling periodico.
    override fun onDestroy() {
        window?.close()
        window = null
        super.onDestroy()
    }


    /**
     * Remove the foreground notification and stop the service.
     */
    private fun stopService() {
        // Window.close() rilascia polling, preview, delegate del bridge e la porta
        // seriale: senza, uscendo dalla notifica il processo restava con /dev/ttyHS0
        // aperto e il suo ReadThread vivo.
        window?.close()
        window = null
        stopForeground(true)
        stopSelf()
    }


    /**
     * Create and show the foreground notification.
     */
    @SuppressLint("ForegroundServiceType")
    private fun showNotification() {

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val exitIntent = Intent(this, FloatingService::class.java).apply {
            putExtra(INTENT_COMMAND, INTENT_COMMAND_EXIT)
        }

        val noteIntent = Intent(this, FloatingService::class.java).apply {
            putExtra(INTENT_COMMAND, INTENT_COMMAND_NOTE)
        }

        val exitPendingIntent = PendingIntent.getService(
            this, CODE_EXIT_INTENT, exitIntent, PendingIntent.FLAG_IMMUTABLE
        )

        val notePendingIntent = PendingIntent.getService(
            this, CODE_NOTE_INTENT, noteIntent, PendingIntent.FLAG_IMMUTABLE
        )

        // From Android O, it's necessary to create a notification channel first.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                with(
                    NotificationChannel(
                        NOTIFICATION_CHANNEL_GENERAL,
                        getString(R.string.notification_channel_general),
                        NotificationManager.IMPORTANCE_DEFAULT
                    )
                ) {
                    enableLights(false)
                    setShowBadge(false)
                    enableVibration(false)
                    setSound(null, null)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                    manager.createNotificationChannel(this)
                }
            } catch (ignored: Exception) {
                // Ignore exception.
            }
        }

        with(
            NotificationCompat.Builder(
                this,
                NOTIFICATION_CHANNEL_GENERAL
            )
        ) {
            setTicker(null)
            setContentTitle(getString(R.string.app_name))
            setContentText(getString(R.string.notification_text))
            setAutoCancel(false)
            setOngoing(true)
            setWhen(System.currentTimeMillis())
            setSmallIcon(R.mipmap.ic_launcher)
            priority = Notification.PRIORITY_DEFAULT
            setContentIntent(notePendingIntent)
            addAction(
                NotificationCompat.Action(
                    0,
                    getString(R.string.notification_exit),
                    exitPendingIntent
                )
            )
            startForeground(CODE_FOREGROUND_SERVICE, build())
        }

    }


    private var window: Window? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        val command = intent?.getStringExtra(INTENT_COMMAND)

        // Exit the service if we receive the EXIT command.
        // START_NOT_STICKY is important here, we don't want
        // the service to be relaunched.
        if (command == INTENT_COMMAND_EXIT) {
            stopService()
            return START_NOT_STICKY
        }

        // Be sure to show the notification first for all commands.
        // Don't worry, repeated calls have no effects.
        showNotification()

        // Show the floating window for adding a new note.
        //if (command == INTENT_COMMAND_NOTE) {
            if (!drawOverOtherAppsEnabled()) {
                startPermissionActivity()
            } else if (window == null) {
                // UNA SOLA Window per servizio. onStartCommand puo' essere richiamata piu'
                // volte (il servizio e' START_STICKY, piu' ogni nuovo Intent), e ogni Window
                // apre /dev/ttyHS0 con un proprio ReadThread: due lettori sullo stesso fd si
                // spartiscono i byte in arrivo e corrompono sia le risposte REST sia i frame
                // video, in modo intermittente e difficilissimo da diagnosticare.
                window = Window(this).also { it.open() }
            }
        //}

        return START_STICKY
    }

}