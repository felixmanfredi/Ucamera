package com.trediresearch.ucamera.video;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.util.Log;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.hoho.android.usbserial.util.SerialInputOutputManager;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class UsbSerialConnection implements SerialInputOutputManager.Listener {

    private static final String TAG = "UsbSerialConnection";
    private static final String ACTION_USB_PERMISSION = "com.trediresearch.ucamera.USB_PERMISSION";

    private final Context context;
    private final int baudrate;
    private final int dataBits;
    private final int stopBits;
    private final int parity;
    private final int readSize;

    private volatile boolean connect = false;
    private Delegate delegate;

    private UsbSerialPort usbSerialPort;
    private SerialInputOutputManager ioManager;

    private final Lock lock = new ReentrantLock();
    private final Condition notEntry = lock.newCondition();
    private final Condition notFull = lock.newCondition();

    private List<byte[]> messageQueue;
    private ForwardThread mForwardThread;

    private BroadcastReceiver usbPermissionReceiver;

    private UsbSerialConnection(Context context, int baudrate, int dataBits, int stopBits, int parity, int readSize) {
        this.context = context.getApplicationContext();
        this.baudrate = baudrate;
        this.dataBits = dataBits;
        this.stopBits = stopBits;
        this.parity = parity;
        this.readSize = readSize;
    }

    public static Builder newBuilder(Context context, int baudrate) {
        return new Builder(context, baudrate);
    }

    public void setDelegate(Delegate delegate) {
        this.delegate = delegate;
    }

    public boolean isConnection() {
        return this.connect;
    }

    /**
     * Tenta di aprire la connessione USB. Se i permessi mancano, li richiede
     * all'utente e completa l'apertura in modo asincrono appena concessi.
     */
    public synchronized void openConnection() throws Exception {
        UsbManager manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        if (manager == null) {
            throw new IOException("UsbManager non disponibile nel sistema.");
        }

        List<UsbSerialDriver> availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager);
        if (availableDrivers.isEmpty()) {
            throw new IOException("Nessun dispositivo USB seriale (ESP32) rilevato.");
        }

        UsbSerialDriver driver = availableDrivers.get(0);
        UsbDevice device = driver.getDevice();

        // --- CONTROLLO E RICHIESTA PERMESSI USB ---
        if (!manager.hasPermission(device)) {
            requestUsbPermission(manager, device);
            return; // L'apertura reale avverrà dentro il BroadcastReceiver dopo l'OK dell'utente
        }

        // Se abbiamo già i permessi, apriamo subito la porta
        startPortConnection(manager, driver);
    }

    /**
     * Registra un Receiver temporaneo e mostra il dialogo di sistema per i permessi USB.
     */
    private void requestUsbPermission(UsbManager manager, UsbDevice device) {
        int flags = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                ? PendingIntent.FLAG_MUTABLE
                : 0;

        PendingIntent permissionIntent = PendingIntent.getBroadcast(
                context,
                0,
                new Intent(ACTION_USB_PERMISSION),
                flags
        );

        usbPermissionReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (ACTION_USB_PERMISSION.equals(action)) {
                    synchronized (this) {
                        UsbDevice grantedDevice = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                        boolean isGranted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);

                        if (isGranted && grantedDevice != null) {
                            try {
                                List<UsbSerialDriver> drivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager);
                                if (!drivers.isEmpty()) {
                                    startPortConnection(manager, drivers.get(0));
                                }
                            } catch (Exception e) {
                                Log.e(TAG, "Errore durante l'apertura post-permesso", e);
                            }
                        } else {
                            Log.w(TAG, "Permesso USB negato dall'utente.");
                        }
                    }
                    // Deregistra il receiver dopo la risposta
                    unregisterPermissionReceiver();
                }
            }
        };

        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbPermissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(usbPermissionReceiver, filter);
        }

        manager.requestPermission(device, permissionIntent);
    }

    /**
     * Inizializza l'hardware, apre la porta seriale e avvia i thread di I/O.
     */
    private synchronized void startPortConnection(UsbManager manager, UsbSerialDriver driver) throws IOException {
        UsbDeviceConnection connection = manager.openDevice(driver.getDevice());
        if (connection == null) {
            throw new IOException("Impossibile aprire la connessione USB (Hardware BUS occupato o errore driver).");
        }

        usbSerialPort = driver.getPorts().get(0);
        usbSerialPort.open(connection);
        usbSerialPort.setParameters(baudrate, dataBits, stopBits, parity);

        this.connect = true;
        this.messageQueue = new ArrayList<>();

        // Avvia il thread per l'invio asincrono dei dati in coda
        mForwardThread = new ForwardThread();
        mForwardThread.start();

        // Avvia la lettura continua via USB
        ioManager = new SerialInputOutputManager(usbSerialPort, this);
        ioManager.setReadBufferSize(readSize);
        Executors.newSingleThreadExecutor().submit(ioManager);

        if (delegate != null) {
            delegate.connect();
        }
    }

    /**
     * Chiude la connessione e libera le risorse hardware e i Receiver.
     */
    public synchronized void closeConnection() throws IOException {
        this.connect = false;
        this.delegate = null;

        unregisterPermissionReceiver();

        if (ioManager != null) {
            ioManager.setListener(null);
            ioManager.stop();
            ioManager = null;
        }

        if (mForwardThread != null) {
            mForwardThread.interrupt();
            mForwardThread = null;
        }

        if (messageQueue != null) {
            messageQueue.clear();
            messageQueue = null;
        }

        if (usbSerialPort != null) {
            try {
                usbSerialPort.close();
            } catch (IOException ignored) {}
            usbSerialPort = null;
        }
    }

    private void unregisterPermissionReceiver() {
        if (usbPermissionReceiver != null) {
            try {
                context.unregisterReceiver(usbPermissionReceiver);
            } catch (IllegalArgumentException ignored) {}
            usbPermissionReceiver = null;
        }
    }

    public void sendData(final byte[] bytes) {
        if (bytes != null && this.connect) {
            new Thread(() -> {
                lock.lock();
                try {
                    if (messageQueue != null) {
                        messageQueue.add(bytes);
                        notEntry.signalAll();
                    }
                } finally {
                    lock.unlock();
                }
            }).start();
        }
    }

    private void next() {
        lock.lock();
        try {
            if (!this.connect) {
                try {
                    this.notEntry.await();
                } catch (InterruptedException ignored) {}
            } else {
                if (messageQueue == null || messageQueue.isEmpty()) {
                    try {
                        this.notEntry.await();
                    } catch (InterruptedException ignored) {}
                } else {
                    byte[] arrayOfByte = messageQueue.remove(0);
                    if (usbSerialPort != null && arrayOfByte != null) {
                        try {
                            usbSerialPort.write(arrayOfByte, 2000);
                        } catch (IOException e) {
                            Log.e(TAG, "Errore durante la scrittura su USB", e);
                        }
                    }
                    this.notFull.signalAll();
                }
            }
        } finally {
            lock.unlock();
        }
    }

    // --- Callbacks per SerialInputOutputManager ---

    @Override
    public void onNewData(byte[] data) {
        if (delegate != null && data != null && data.length > 0) {
            delegate.received(data, data.length);
        }
    }

    @Override
    public void onRunError(Exception e) {
        Log.e(TAG, "Errore IO della connessione USB", e);
        try {
            closeConnection();
        } catch (IOException ignored) {}
    }

    // --- Thread Coda Invio Dati ---

    private class ForwardThread extends Thread {
        @Override
        public void run() {
            while (!isInterrupted() && connect) {
                next();
            }
        }
    }

    // --- Interfaccia Delegate ---

    public interface Delegate {
        void connect();
        void received(byte[] data, int length);
    }

    // --- Builder Pattern ---

    public static final class Builder {
        private final Context context;
        private final int baudrate;
        private int dataBits = UsbSerialPort.DATABITS_8;
        private int stopBits = UsbSerialPort.STOPBITS_1;
        private int parity = UsbSerialPort.PARITY_NONE;
        private int readSize = 2048;

        private Builder(Context context, int baudrate) {
            this.context = context;
            this.baudrate = baudrate;
        }

        public Builder dataBits(int dataBits) {
            this.dataBits = dataBits;
            return this;
        }

        public Builder stopBits(int stopBits) {
            this.stopBits = stopBits;
            return this;
        }

        public Builder parity(int parity) {
            this.parity = parity;
            return this;
        }

        public Builder readSize(int readSize) {
            this.readSize = readSize;
            return this;
        }

        public UsbSerialConnection build() {
            return new UsbSerialConnection(context, baudrate, dataBits, stopBits, parity, readSize);
        }
    }
}