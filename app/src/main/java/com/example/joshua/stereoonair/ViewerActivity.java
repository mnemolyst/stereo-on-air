package com.example.joshua.stereoonair;

import android.annotation.SuppressLint;
import android.app.ActionBar;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.MediaCodec;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.net.NetworkInfo;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;

import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;

/**
 * An example full-screen activity that shows and hides the system UI (i.e.
 * status bar and navigation/system bar) with user interaction.
 */
public class ViewerActivity extends Activity {

    private final static String TAG = "ViewerActivity";
    private WifiP2pManager manager;
    private WifiP2pManager.Channel channel;
    private BroadcastReceiver receiver;
    private IntentFilter intentFilter;

    private MediaFormat mediaFormat;
    private HandlerThread leftCodecThread;
    private HandlerThread rightCodecThread;
    private HandlerThread leftNetworkThread;
    private HandlerThread rightNetworkThread;
    private Handler leftCodecHandler;
    private Handler rightCodecHandler;
    private Handler leftNetworkHandler;
    private Handler rightNetworkHandler;
    private SurfaceHolder leftSurfaceHolder;
    private SurfaceHolder rightSurfaceHolder;
    private SurfaceHolder.Callback leftHolderCallback;
    private SurfaceHolder.Callback rightHolderCallback;
    private ServerSocket serverSocket;
    private MediaCodec leftVideoCodec;
    private MediaCodec rightVideoCodec;
    private ArrayBlockingQueue<ByteBuffer> leftBlockingQueue;
    private ArrayBlockingQueue<ByteBuffer> rightBlockingQueue;
    private MediaCodec.Callback leftCodecCallback;
    private MediaCodec.Callback rightCodecCallback;

    private enum Side { LEFT, RIGHT }
    private Side acceptingSocketForSide = Side.LEFT;
    private boolean leftConnected = false;

    private int presentation = 0;

    private class BroadcastReceiver extends android.content.BroadcastReceiver {

        private WifiP2pManager manager;
        private WifiP2pManager.Channel channel;
        private Activity activity;

        public BroadcastReceiver(
                WifiP2pManager manager,
                WifiP2pManager.Channel channel,
                Activity activity) {

            super();
            this.manager = manager;
            this.channel = channel;
            this.activity = activity;
        }

        @Override
        public void onReceive(Context context, Intent intent) {

            String action = intent.getAction();

            if (WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION.equals(action)) {

                int state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1);
                if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                    Log.d(TAG, "wifi p2p state changed: enabled");
                } else {
                    Log.d(TAG, "wifi p2p state changed: not enabled");
                }
            } else if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(action)) {
                Log.d(TAG, "wifi p2p connection changed");

                NetworkInfo networkInfo = intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO);

                if (networkInfo.isConnected()) {
                    Log.d(TAG, "network isConnected");

                    manager.requestConnectionInfo(channel, connectionInfoListener);
                }

            } else if (WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION.equals(action)) {
                Log.d(TAG, "wifi p2p device changed");
            }
        }
    }

    private WifiP2pManager.ConnectionInfoListener connectionInfoListener = new WifiP2pManager.ConnectionInfoListener() {

        @Override
        public void onConnectionInfoAvailable(WifiP2pInfo info) {
            Log.d(TAG, "onConnectionInfoAvailable: " + info.toString());

            if (info.groupFormed) {
                MainActivity.serverAddress = info.groupOwnerAddress.getHostAddress();
            }

//            if (info.groupFormed && info.isGroupOwner) {
//            } else if (info.groupFormed) {
//            }
        }
    };

    private void startServer() {

    }

    private void stopServer() {

        Log.d(TAG, "stopServer");

//        leftVideoCodec.stop();
//        leftVideoCodec.release();
//        rightVideoCodec.stop();
//        rightVideoCodec.release();

        leftCodecThread.interrupt();
        rightCodecThread.interrupt();
        leftNetworkThread.interrupt();
        rightNetworkThread.interrupt();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_viewer);

        mVisible = true;
        mControlsView = findViewById(R.id.fullscreen_content_controls);
        mContentView = findViewById(R.id.fullscreen_content);


        // Set up the user interaction to manually show or hide the system UI.
        mContentView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                toggle();
            }
        });

        // Upon interacting with UI controls, delay any scheduled hide()
        // operations to prevent the jarring behavior of controls going away
        // while interacting with the UI.
        findViewById(R.id.close_button).setOnTouchListener(mDelayHideTouchListener);


        try {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress(MainActivity.port));
            serverSocket.setSoTimeout(5_000);
        } catch (IOException exception) {
            Log.e(TAG, "start binding ServerSocket exception: " + exception.getMessage());
            return;
        }

        mediaFormat = MediaFormat.createVideoFormat(MainActivity.mimeType, MainActivity.videoWidth, MainActivity.videoHeight);


        leftBlockingQueue = new ArrayBlockingQueue<>(3);
        leftCodecCallback = createCodecCallback(leftBlockingQueue);
        leftVideoCodec = createCodec(mediaFormat);

        rightBlockingQueue = new ArrayBlockingQueue<>(3);
        rightCodecCallback = createCodecCallback(rightBlockingQueue);
        rightVideoCodec = createCodec(mediaFormat);


        manager = (WifiP2pManager) getSystemService(Context.WIFI_P2P_SERVICE);
        channel = manager.initialize(this, getMainLooper(), null);

        receiver = new BroadcastReceiver(manager, channel, this);

        intentFilter = new IntentFilter();
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);
    }

    @Override
    protected void onResume() {

        Log.d(TAG, "onResume");
        super.onResume();
        registerReceiver(receiver, intentFilter);

        leftNetworkThread = new HandlerThread("leftNetworkThread");
        leftNetworkThread.start();
        leftNetworkHandler = new Handler(leftNetworkThread.getLooper());
        leftNetworkHandler.post(createSocketRunnable());

        leftCodecThread = new HandlerThread("leftCodecThread");
        leftCodecThread.start();
        leftCodecHandler = new Handler(leftCodecThread.getLooper());
        leftHolderCallback = createSurfaceHolderCallback(leftCodecHandler, leftVideoCodec, leftCodecCallback);
        SurfaceView leftSurfaceView = findViewById(R.id.surface_view_left);
        leftSurfaceHolder = leftSurfaceView.getHolder();
        leftSurfaceHolder.addCallback(leftHolderCallback);

        rightNetworkThread = new HandlerThread("rightNetworkThread");
        rightNetworkThread.start();
        rightNetworkHandler = new Handler(rightNetworkThread.getLooper());
        rightNetworkHandler.post(createSocketRunnable());

        rightCodecThread = new HandlerThread("rightCodecThread");
        rightCodecThread.start();
        rightCodecHandler = new Handler(rightCodecThread.getLooper());
        rightHolderCallback = createSurfaceHolderCallback(rightCodecHandler, rightVideoCodec, rightCodecCallback);
        SurfaceView rightSurfaceView = findViewById(R.id.surface_view_right);
        rightSurfaceHolder = rightSurfaceView.getHolder();
        rightSurfaceHolder.addCallback(rightHolderCallback);
    }

    private MediaCodec.Callback createCodecCallback(final ArrayBlockingQueue<ByteBuffer> queue) {

        return new MediaCodec.Callback() {

            @Override
            public void onInputBufferAvailable(MediaCodec codec, int index) {

                Log.d(TAG, "onInputBufferAvailable thread id: " + Thread.currentThread().getId());
                ByteBuffer inputBuffer;
                try {
                    inputBuffer = codec.getInputBuffer(index);
                } catch (IllegalStateException e) {
                    e.printStackTrace();
                    return;
                }

                if (inputBuffer == null) {
                    return;
                }

                try {
                    ByteBuffer buffer = queue.take();
                    inputBuffer.put(buffer);
                    codec.queueInputBuffer(index, 0, buffer.capacity(), presentation++, 0);
                } catch (InterruptedException exception) {
                    Log.e(TAG, "Interrupted reading from ByteBuffer queue");
                }
            }

            @Override
            public void onOutputBufferAvailable(MediaCodec codec, int index, MediaCodec.BufferInfo info) {

                try {
                    codec.releaseOutputBuffer(index, info.presentationTimeUs);
                } catch (IllegalStateException exception) {
                    Log.e(TAG, "onOutputBufferAvailable exception: " + exception.getMessage());
                }
            }

            @Override
            public void onError(MediaCodec codec, MediaCodec.CodecException e) {
                Log.e(TAG, "MediaCodec.Callback.onError", e);
            }

            @Override
            public void onOutputFormatChanged(MediaCodec codec, MediaFormat format) {

                codec.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING);
            }
        };
    };

    private MediaCodec createCodec(MediaFormat mediaFormat) {

        MediaCodecList codecList = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
        String codecName = codecList.findDecoderForFormat(mediaFormat);
//        Log.d(TAG, "codecName: " + codecName);

        try {
            return MediaCodec.createByCodecName(codecName);

        } catch (IOException exception) {
            Log.e(TAG, "createCodec exception: " + exception.getMessage());
//            stopSelf();
            return null;
        }
    }

    private SurfaceHolder.Callback createSurfaceHolderCallback(final Handler handler, final MediaCodec codec, final MediaCodec.Callback callback) {

        return new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(final SurfaceHolder holder) {

                Log.d(TAG, "surfaceCreated");
                handler.post(new Runnable() {
                    @Override
                    public void run() {

                        codec.setCallback(callback, handler);
                        codec.configure(mediaFormat, holder.getSurface(), null, 0);
                        codec.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING);
                        codec.start();
                    }
                });
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {

            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {

                Log.d(TAG, "surfaceDestroyed");
                codec.stop();
                codec.release();
            }
        };
    }

    private Runnable createSocketRunnable() {

        return new Runnable() {

            private Socket socket;
            private DataInputStream inputStream;
            private ArrayBlockingQueue<ByteBuffer> myQueue;

            @Override
            public void run() {

                // The first of these Runnables to get here claims the left half of the screen.
                // The second takes right and waits for the left to connect before listening on the
                // ServerSocket.
                if (acceptingSocketForSide == Side.LEFT) {
                    acceptingSocketForSide = Side.RIGHT;
                    myQueue = leftBlockingQueue;
                } else {
                    while (! leftConnected) {
                        try {
                            Thread.sleep(500);
                        } catch (InterruptedException exception) {
                            Log.e(TAG, "Interrupted waiting for left to connect");
                            return;
                        }
                    }
                    myQueue = rightBlockingQueue;
                }
                while (true) {
                    try {
                        socket = serverSocket.accept();
                        leftConnected = true;
                        Log.d(TAG, "Connected! Accepted socket on port " + serverSocket.getLocalPort());
                        inputStream = new DataInputStream(socket.getInputStream());
                        break;
                    } catch (SocketTimeoutException exception) {
                        if (Thread.interrupted()) {
                            Log.d(TAG, "Interrupted waiting for socket connection");
                            return;
                        }
                    } catch (IOException exception) {
                        Log.e(TAG, "Accept serverSocket exception: " + exception.getMessage());
//                        stopSelf();
                        return;
                    }
                }
                // Make a really big byte array to hold video frames
                byte[] bytes = new byte[100_000];
                int numBytes;
                try {
                    while (true) {
                        numBytes = inputStream.read(bytes);
                        if (numBytes == bytes.length) {
                            Log.e(TAG, "Maxed out byte buffer with " + numBytes + " bytes");
                        } else if (numBytes == -1) {
                            Log.e(TAG, "socket inputStream EOF");
                            return;
//                            stopSelf();
                        }
                        ByteBuffer buffer = ByteBuffer.allocate(numBytes);
                        buffer.put(bytes, 0, numBytes);
                        buffer.rewind();
                        myQueue.put(buffer);
                    }
                } catch (InterruptedException exception) {
                    Log.e(TAG, "Interrupted reading socket input stream");
                } catch (IOException exception) {
                    Log.e(TAG, "Read socket inputStream exception: " + exception.getMessage());
//                    stopSelf();
                    return;
                }
            }
        };
    }

    @Override
    protected void onPause() {

        Log.d(TAG, "onPause");
        super.onPause();
        unregisterReceiver(receiver);
        leftSurfaceHolder.removeCallback(leftHolderCallback);
        rightSurfaceHolder.removeCallback(rightHolderCallback);
        stopServer();
    }

    @Override
    protected void onDestroy() {

        super.onDestroy();
        try {
            serverSocket.close();
        } catch (IOException exception) {
            Log.e(TAG, exception.getMessage());
        }
    }

    //////////////
    // Boilerplate fullscreen Activity stuff below //
    //////////////

    /**
     * Whether or not the system UI should be auto-hidden after
     * {@link #AUTO_HIDE_DELAY_MILLIS} milliseconds.
     */
    private static final boolean AUTO_HIDE = true;

    /**
     * If {@link #AUTO_HIDE} is set, the number of milliseconds to wait after
     * user interaction before hiding the system UI.
     */
    private static final int AUTO_HIDE_DELAY_MILLIS = 3000;
    /**
     * Some older devices needs a small delay between UI widget updates
     * and a change of the status and navigation bar.
     */
    private static final int UI_ANIMATION_DELAY = 300;
    private final Handler mHideHandler = new Handler();
    private View mContentView;
    private final Runnable mHidePart2Runnable = new Runnable() {
        @SuppressLint("InlinedApi")
        @Override
        public void run() {
            // Delayed removal of status and navigation bar

            // Note that some of these constants are new as of API 16 (Jelly Bean)
            // and API 19 (KitKat). It is safe to use them, as they are inlined
            // at compile-time and do nothing on earlier devices.
            mContentView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LOW_PROFILE
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
        }
    };
    private View mControlsView;
    private final Runnable mShowPart2Runnable = new Runnable() {
        @Override
        public void run() {
            // Delayed display of UI elements
            ActionBar actionBar = getActionBar();
            if (actionBar != null) {
                actionBar.show();
            }
            mControlsView.setVisibility(View.VISIBLE);
        }
    };
    private boolean mVisible;
    private final Runnable mHideRunnable = new Runnable() {
        @Override
        public void run() {
            hide();
        }
    };

    /**
     * Touch listener to use for in-layout UI controls to delay hiding the
     * system UI. This is to prevent the jarring behavior of controls going away
     * while interacting with activity UI.
     */
    private final View.OnTouchListener mDelayHideTouchListener = new View.OnTouchListener() {
        @Override
        public boolean onTouch(View view, MotionEvent motionEvent) {
            if (AUTO_HIDE) {
                delayedHide(AUTO_HIDE_DELAY_MILLIS);
            }
            return false;
        }
    };

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);

        // Trigger the initial hide() shortly after the activity has been
        // created, to briefly hint to the user that UI controls
        // are available.
        delayedHide(100);
    }

    private void toggle() {
        if (mVisible) {
            hide();
        } else {
            show();
        }
    }

    private void hide() {
        // Hide UI first
        ActionBar actionBar = getActionBar();
        if (actionBar != null) {
            actionBar.hide();
        }
        mControlsView.setVisibility(View.GONE);
        mVisible = false;

        // Schedule a runnable to remove the status and navigation bar after a delay
        mHideHandler.removeCallbacks(mShowPart2Runnable);
        mHideHandler.postDelayed(mHidePart2Runnable, UI_ANIMATION_DELAY);
    }

    @SuppressLint("InlinedApi")
    private void show() {
        // Show the system bar
        mContentView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        mVisible = true;

        // Schedule a runnable to display UI elements after a delay
        mHideHandler.removeCallbacks(mHidePart2Runnable);
        mHideHandler.postDelayed(mShowPart2Runnable, UI_ANIMATION_DELAY);
    }

    /**
     * Schedules a call to hide() in delay milliseconds, canceling any
     * previously scheduled calls.
     */
    private void delayedHide(int delayMillis) {
        mHideHandler.removeCallbacks(mHideRunnable);
        mHideHandler.postDelayed(mHideRunnable, delayMillis);
    }
}
