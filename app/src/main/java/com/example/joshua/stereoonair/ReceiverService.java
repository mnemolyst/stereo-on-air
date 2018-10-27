package com.example.joshua.stereoonair;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import java.io.DataInputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class ReceiverService extends Service {

    private final static String TAG = "ReceiverService";
    private OnFrameReceivedCallback onFrameReceivedCallback;
    private ServerSocket serverSocket;
    private Socket socket;

    class receiverServiceBinder extends Binder {

        ReceiverService getService() {

            return ReceiverService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {

        return new receiverServiceBinder();
    }

    static abstract class OnFrameReceivedCallback {
        abstract void onFrameReceived();
    }

    public void registerOnFrameReceivedCallback(OnFrameReceivedCallback callback) {
        onFrameReceivedCallback = callback;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        HandlerThread thread = new HandlerThread("receiverThread");
        thread.start();
        Looper looper = thread.getLooper();
        Handler handler = new Handler(looper);

        Runnable runnable = new Runnable() {
            @Override
            public void run() {
                openSocket();
            }
        };

        handler.post(runnable);

        return START_NOT_STICKY;
    }

    public void openSocket() {

        Log.d(TAG, "receiverService openSocket thread id: " + String.valueOf(Thread.currentThread().getId()));
        try {
            serverSocket = new ServerSocket(MainActivity.port);
            socket = serverSocket.accept();
            DataInputStream inputStream = new DataInputStream(socket.getInputStream());
            byte[] buffer = new byte[128];
            int numBytes = inputStream.read(buffer);
            String s = new String(buffer, 0, numBytes, StandardCharsets.UTF_8);
            Log.d(TAG, "bytes length: " + String.valueOf(numBytes));
            Log.d(TAG, s);
        } catch (IOException exception) {
            Log.e(TAG, exception.getMessage());
        }
    }
}
