package com.trediresearch.ucamera.video;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;

import android_serialport_api.SerialPort;

public class SerialPortConnection {
    private int baudrate;

    private volatile boolean connect;

    private int dataBits;

    private final List<Delegate> delegates = new CopyOnWriteArrayList<>();

    private int fifoSize;

    private int flags;

    private int flowCon;

    private InputStream inputStream;

    private Lock lock;

    private ForwardThread mForwardThread;

    private ReadThread mReadThread;

    private SerialPort mSerialPort;

    private List<byte[]> messageQueue;

    private Condition notEntry;

    private Condition notFull;

    public OutputStream outputStream;

    private int parity;

    private String path;

    private int readSize = 2048;

    private int stopBits;

    private SerialPortConnection(String paramString, int paramInt1, int paramInt2, int paramInt3, int paramInt4, int paramInt5, int paramInt6, int paramInt7, int paramInt8) {
        this.path = paramString;
        this.baudrate = paramInt1;
        this.dataBits = paramInt2;
        this.stopBits = paramInt4;
        this.parity = paramInt3;
        this.flags = paramInt8;
        this.flowCon = paramInt5;
        this.fifoSize = paramInt6;
        this.readSize = paramInt7;
    }

    public static Builder newBuilder(String paramString, int paramInt) {
        return new Builder(paramString, paramInt);
    }

    private void next() {
        try {
            this.lock.lock();
            boolean bool = this.connect;
            if (!bool) {
                try {
                    this.notEntry.await();
                } catch (InterruptedException interruptedException) {
                    interruptedException.printStackTrace();
                }
            } else {
                List<byte[]> list = this.messageQueue;
                if (list == null || list.isEmpty()) {
                    try {
                        this.notEntry.await();
                    } catch (InterruptedException interruptedException) {}
                } else {
                    byte[] arrayOfByte = this.messageQueue.get(0);
                    try {
                        OutputStream outputStream = this.outputStream;
                        if (outputStream != null && arrayOfByte != null)
                            outputStream.write(arrayOfByte);
                    } catch (IOException iOException) {
                        iOException.printStackTrace();
                    }
                    this.messageQueue.remove(0);
                    this.notFull.signalAll();
                    return;
                }
            }
            return;
        } finally {
            this.lock.unlock();
        }
    }

    private void received(byte[] paramArrayOfbyte, int paramInt) {
        for (Delegate delegate : this.delegates) {
            delegate.received(paramArrayOfbyte, paramInt);
        }
    }

    public void closeConnection() throws IOException {
        this.connect = false;
        this.delegates.clear();
        ForwardThread forwardThread = this.mForwardThread;
        if (forwardThread != null) {
            forwardThread.interrupt();
            this.mForwardThread = null;
        }
        List<byte[]> list = this.messageQueue;
        if (list != null) {
            list.clear();
            this.messageQueue = null;
        }
        ReadThread readThread = this.mReadThread;
        if (readThread != null) {
            readThread.interrupt();
            this.mReadThread = null;
        }
        OutputStream outputStream = this.outputStream;
        if (outputStream != null) {
            outputStream.close();
            this.outputStream = null;
        }
        InputStream inputStream = this.inputStream;
        if (inputStream != null) {
            inputStream.close();
            this.inputStream = null;
        }
        SerialPort serialPort = this.mSerialPort;
        if (serialPort != null) {
            serialPort.close();
            this.mSerialPort = null;
        }
    }

    public boolean isConnection() {
        return this.connect;
    }

    public void openConnection() throws Exception {
        SerialPort serialPort =new SerialPort(new File(this.path),this.baudrate,this.stopBits,this.dataBits,this.parity,this.flowCon,this.flags);

        this.mSerialPort = serialPort;
        this.inputStream = serialPort.getInputStream();
        this.outputStream = this.mSerialPort.getOutputStream();
        this.connect = true;
        this.messageQueue = (List)new ArrayList<Byte>();
        ForwardThread forwardThread = new ForwardThread();
        this.mForwardThread = forwardThread;
        forwardThread.start();
        if (this.inputStream != null) {
            ReadThread readThread = new ReadThread();
            this.mReadThread = readThread;
            readThread.start();
        }
        for (Delegate delegate : this.delegates) {
            delegate.connect();
        }
    }

    public void sendData(final byte[] bytes) {
        if (bytes != null && this.connect)
            (new Runnable() {

                public void run() {
                    try {

                        SerialPortConnection.this.lock.lock();
                        SerialPortConnection.this.messageQueue.add(bytes);
                        SerialPortConnection.this.notEntry.signalAll();
                        return;
                    } finally {
                        SerialPortConnection.this.lock.unlock();
                    }
                }
            }).run();
    }

    public void addDelegate(Delegate paramDelegate) {
        this.delegates.add(paramDelegate);
    }

    public void removeDelegate(Delegate paramDelegate) {
        this.delegates.remove(paramDelegate);
    }

    public static final class Builder {
        private int baudrate;

        private int dataBits = 8;

        private int fifoSize = -1;

        private int flags = 0;

        private int flowCon = 0;

        private int parity = 0;

        private String path;

        private int readSize = 2048;

        private int stopBits = 1;

        private Builder(String param1String, int param1Int) {
            this.path = param1String;
            this.baudrate = param1Int;
        }

        public SerialPortConnection build() {
            return new SerialPortConnection(this.path, this.baudrate, this.dataBits, this.parity, this.stopBits, this.flowCon, this.fifoSize, this.readSize, this.flags);
        }

        public Builder dataBits(int param1Int) {
            this.dataBits = param1Int;
            return this;
        }

        public Builder fifoSize(int param1Int) {
            this.fifoSize = param1Int;
            return this;
        }

        public Builder flags(int param1Int) {
            this.flags = param1Int;
            return this;
        }

        public Builder flowCon(int param1Int) {
            this.flowCon = param1Int;
            return this;
        }

        public Builder parity(int param1Int) {
            this.parity = param1Int;
            return this;
        }

        public Builder readSize(int param1Int) {
            this.readSize = param1Int;
            return this;
        }

        public Builder stopBits(int param1Int) {
            this.stopBits = param1Int;
            return this;
        }
    }

    public static interface Delegate {
        void connect();

        void received(byte[] param1ArrayOfbyte, int param1Int);
    }

    class ForwardThread extends Thread {

        /*
        public void run() {
            SerialPortConnection.access$002(SerialPortConnection.this, new ReentrantLock());
            SerialPortConnection serialPortConnection = SerialPortConnection.this;
            SerialPortConnection.access$202(serialPortConnection, serialPortConnection.lock.newCondition());
            serialPortConnection = SerialPortConnection.this;
            SerialPortConnection.access$302(serialPortConnection, serialPortConnection.lock.newCondition());
            while (!isInterrupted())
                SerialPortConnection.this.next();
        }

         */
    }

    class ReadThread extends Thread {
        public void run() {
            byte[] arrayOfByte = new byte[SerialPortConnection.this.readSize];
            while (!isInterrupted()) {
                try {
                    if (SerialPortConnection.this.inputStream == null)
                        return;
                    int i = SerialPortConnection.this.inputStream.read(arrayOfByte);
                    if (i > 0)
                        SerialPortConnection.this.received(arrayOfByte, i);
                } catch (IOException iOException) {
                    iOException.printStackTrace();
                }
            }
        }
    }
}
