package com.example.joshua.stereoonair;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.NetworkInfo;
import android.net.wifi.WpsInfo;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo;
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.util.Log;
import android.widget.TextView;

import java.net.InetAddress;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

public class MainActivity extends Activity {

    private final static String TAG = "MainActivity";
    private String receiverBuddyname;
    private WifiP2pManager manager;
    private WifiP2pManager.Channel channel;
    private BroadcastReceiver receiver;
    private IntentFilter intentFilter;
    private final static int SERVER_PORT = 4545;
    private HashMap<String, String> buddies = new HashMap<>();
    private Collection<WifiP2pDevice> peers;
    private boolean isServiceDiscoverySetup = false;

    private class BroadcastReceiver extends android.content.BroadcastReceiver {

        private WifiP2pManager manager;
        private WifiP2pManager.Channel channel;
        private Activity activity;
        private WifiP2pManager.PeerListListener peerListListener;

        public BroadcastReceiver(
                WifiP2pManager manager,
                WifiP2pManager.Channel channel,
                WifiP2pManager.PeerListListener peerListListener,
                Activity activity) {

            super();
            this.manager = manager;
            this.channel = channel;
            this.activity = activity;
            this.peerListListener = peerListListener;
        }

        @Override
        public void onReceive(Context context, Intent intent) {

            String action = intent.getAction();

            if (WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION.equals(action)) {

                int state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1);
                if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                    Log.d(TAG, "wifi p2p enabled");
                } else {
                    Log.d(TAG, "wifi p2p not enabled");
                }
            } else if (WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION.equals(action)) {

                Log.d(TAG, "wifi p2p peers changed");
                manager.requestPeers(channel, peerListListener);

            } else if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(action)) {
                Log.d(TAG, "wifi p2p connection changed");

                NetworkInfo networkInfo = (NetworkInfo) intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO);

                if (networkInfo.isConnected()) {
                    Log.d(TAG, "network isConnected");

                    manager.requestConnectionInfo(channel, new WifiP2pManager.ConnectionInfoListener() {
                        @Override
                        public void onConnectionInfoAvailable(WifiP2pInfo info) {
                            Log.d(TAG, "onConnectionInfoAvailable");

                            String groupOwnerAddress = info.groupOwnerAddress.getHostAddress();

                            if (info.groupFormed && info.isGroupOwner) {
                                Log.d(TAG, "I am the group owner. Owner: " + groupOwnerAddress);
                            } else if (info.groupFormed) {
                                Log.d(TAG, "I am not the group owner. Owner: " + groupOwnerAddress);
                            }

                        }
                    });
                }

            } else if (WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION.equals(action)) {
                Log.d(TAG, "wifi p2p device changed");
            }
        }
    }

    private void discoverPeers() {

        manager.discoverPeers(channel, new WifiP2pManager.ActionListener() {

            @Override
            public void onSuccess() {
                Log.d(TAG, "discoverPeers success");
            }

            @Override
            public void onFailure(int reason) {
                Log.d(TAG, "discoverPeers failure");
            }
        });
    }

    private void connectToPeer() {

        if (! buddies.isEmpty()) {
            WifiP2pConfig config = new WifiP2pConfig();
            HashMap.Entry first = buddies.entrySet().iterator().next();
            String addr = String.valueOf(first.getKey());
            Log.d(TAG, "connectToPeer addr: " + addr);
            config.deviceAddress = String.valueOf(first.getKey());
            config.wps.setup = WpsInfo.PBC;
            manager.connect(channel, config, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    Log.d(TAG, "connect onSuccess");
                }

                @Override
                public void onFailure(int reason) {
                    Log.d(TAG, "connect onFailure: " + String.valueOf(reason));
                }
            });
        }
    }

    private void registerService() {

        HashMap<String, String> record = new HashMap<>();
        record.put("listenport", String.valueOf(SERVER_PORT));
        record.put("buddyname", receiverBuddyname);
        record.put("available", "visible");
        final WifiP2pDnsSdServiceInfo serviceInfo = WifiP2pDnsSdServiceInfo.newInstance("_foo", "_presence._tcp", record);
        manager.clearLocalServices(channel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                manager.addLocalService(channel, serviceInfo, new WifiP2pManager.ActionListener() {
                    @Override
                    public void onSuccess() {
                        Log.d(TAG, "addLocalService onSuccess");
                    }

                    @Override
                    public void onFailure(int reason) {
                        Log.d(TAG, "addLocalService onFailure");
                    }
                });
            }

            @Override
            public void onFailure(int reason) {
                Log.d(TAG, "clearLocalServices onFailure");
            }
        });
    }

    private void setupServiceDiscovery() {

        WifiP2pManager.DnsSdServiceResponseListener serviceResponseListener = new WifiP2pManager.DnsSdServiceResponseListener() {
            @Override
            public void onDnsSdServiceAvailable(String instanceName, String registrationType, WifiP2pDevice srcDevice) {
                Log.d(TAG, "discoverService onDnsSdServiceAvailable: " + instanceName);
//                srcDevice.deviceName = buddies.containsKey(srcDevice.deviceAddress) ? buddies.get(srcDevice.deviceAddress) : srcDevice.deviceName;
            }
        };

        WifiP2pManager.DnsSdTxtRecordListener txtRecordListener = new WifiP2pManager.DnsSdTxtRecordListener() {
            @Override
            public void onDnsSdTxtRecordAvailable(String fullDomainName, Map<String, String> txtRecordMap, WifiP2pDevice srcDevice) {
                Log.d(TAG, "discoverService onDnsSdTxtRecordAvailable: " + txtRecordMap.toString());
                buddies.put(srcDevice.deviceAddress, txtRecordMap.get("buddyname"));

                TextView serviceTextview = findViewById(R.id.service_textview);
                serviceTextview.setText(buddies.toString());
            }
        };

        manager.setDnsSdResponseListeners(channel, serviceResponseListener, txtRecordListener);

        manager.clearServiceRequests(channel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                manager.addServiceRequest(channel, WifiP2pDnsSdServiceRequest.newInstance(), new WifiP2pManager.ActionListener() {
                    @Override
                    public void onSuccess() {
                        Log.d(TAG, "addServiceRequest onSuccess");
                        isServiceDiscoverySetup = true;
                    }

                    @Override
                    public void onFailure(int reason) {
                        Log.d(TAG, "addServiceRequest onFailure");
                    }
                });
            }

            @Override
            public void onFailure(int reason) {
                Log.d(TAG, "clearServiceRequests onFailure");
            }
        });
    }

    private void discoverService() {

        if (!isServiceDiscoverySetup) {
            setupServiceDiscovery();
        }

        manager.discoverServices(channel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                Log.d(TAG, "discoverServices onSuccess");
            }

            @Override
            public void onFailure(int reason) {
                Log.d(TAG, "discoverServices onFailure");
            }
        });
    }

    private void checkConnection() {
        manager.requestConnectionInfo(channel, new WifiP2pManager.ConnectionInfoListener() {
            @Override
            public void onConnectionInfoAvailable(WifiP2pInfo info) {
                Log.d(TAG, "onConnectionInfoAvailable: " + info.toString());
            }
        });
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        manager = (WifiP2pManager) getSystemService(Context.WIFI_P2P_SERVICE);
        channel = manager.initialize(this, getMainLooper(), null);
        receiverBuddyname = "Stereo Receiver " + (int)(Math.random() * 1000);

        WifiP2pManager.PeerListListener peerListListener = new WifiP2pManager.PeerListListener() {

            @Override
            public void onPeersAvailable(WifiP2pDeviceList deviceList) {
                peers = deviceList.getDeviceList();
                Log.d(TAG, "onPeersAvailable");
                Log.d(TAG, "isEmpty: " + String.valueOf(peers.isEmpty()));
                String peerText;
                if (peers.isEmpty()) {
                    peerText = "No peers";
                } else {
                    peerText = "";
                    for (WifiP2pDevice device : peers) {
                        peerText = peerText.concat("address: " + device.deviceAddress + " name: " + device.deviceName);
                    }
                }
                TextView peerTextview = findViewById(R.id.peer_textview);
                peerTextview.setText(peerText);
            }
        };

        receiver = new BroadcastReceiver(manager, channel, peerListListener, MainActivity.this);

        final Button cameraButton = findViewById(R.id.camera_button);
        cameraButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
            }
        });

        final Button receiverButton = findViewById(R.id.receiver_button);
        receiverButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
            }
        });

        final Button connectButton = findViewById(R.id.connect_button);
        connectButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                connectToPeer();
            }
        });

        intentFilter = new IntentFilter();
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);
    }

    @Override
    protected void onResume() {

        super.onResume();
        registerReceiver(receiver, intentFilter);
//        discoverPeers();
        checkConnection();
    }

    @Override
    protected void onPause() {

        super.onPause();
        unregisterReceiver(receiver);
    }
}
