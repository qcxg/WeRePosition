package com.shiraka.wereposition.btprobe;

import android.app.Activity;
import android.bluetooth.*;
import android.os.Bundle;
import android.os.ParcelUuid;
import android.widget.TextView;
import org.json.*;
import java.nio.file.Files;
import java.io.File;
import java.util.*;
import java.util.concurrent.*;

/** Separate client process: no dependency on the module or its hook implementation. */
public class ProbeActivity extends Activity {
    private final Map<Integer, BluetoothProfile> profiles = new ConcurrentHashMap<>();
    private final CountDownLatch connected = new CountDownLatch(2);

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TextView output = new TextView(this);
        output.setPadding(32, 64, 32, 32);
        output.setText("Reading Bluetooth APIs…");
        setContentView(output);
        BluetoothAdapter adapter = getSystemService(BluetoothManager.class).getAdapter();
        BluetoothProfile.ServiceListener listener = new BluetoothProfile.ServiceListener() {
            @Override public void onServiceConnected(int profile, BluetoothProfile proxy) {
                profiles.put(profile, proxy);
                connected.countDown();
            }
            @Override public void onServiceDisconnected(int profile) { profiles.remove(profile); }
        };
        for (int profile : new int[]{BluetoothProfile.A2DP, BluetoothProfile.HEADSET}) {
            if (!adapter.getProfileProxy(this, listener, profile)) connected.countDown();
        }
        new Thread(() -> {
            JSONObject report = new JSONObject();
            try {
                report.put("phase", getIntent().getStringExtra("phase"));
                connected.await(8, TimeUnit.SECONDS);
                report.put("enabled", adapter.isEnabled());
                report.put("bonded", devices(adapter.getBondedDevices()));
                for (int profile : new int[]{BluetoothProfile.A2DP, BluetoothProfile.HEADSET}) {
                    JSONObject data = new JSONObject();
                    data.put("adapterState", adapter.getProfileConnectionState(profile));
                    BluetoothProfile proxy = profiles.get(profile);
                    data.put("proxyAvailable", proxy != null);
                    if (proxy != null) {
                        data.put("connected", devices(proxy.getConnectedDevices()));
                        data.put("matchingConnected", devices(proxy.getDevicesMatchingConnectionStates(new int[]{BluetoothProfile.STATE_CONNECTED})));
                        data.put("matchingDisconnected", devices(proxy.getDevicesMatchingConnectionStates(new int[]{BluetoothProfile.STATE_DISCONNECTED})));
                        JSONObject states = new JSONObject();
                        for (BluetoothDevice device : adapter.getBondedDevices()) {
                            states.put(device.getAddress(), proxy.getConnectionState(device));
                        }
                        data.put("states", states);
                    }
                    report.put(profile == BluetoothProfile.A2DP ? "a2dp" : "headset", data);
                }
                android.util.Log.i("WRP_BT_PROBE", "phase=" + report.optString("phase", "manual")
                    + " bonded=" + report.getJSONArray("bonded").length()
                    + " a2dpConnected=" + count(report.getJSONObject("a2dp"), "connected")
                    + " headsetConnected=" + count(report.getJSONObject("headset"), "connected"));
            } catch (Throwable error) {
                android.util.Log.e("WRP_BT_PROBE", "Bluetooth API read failed", error);
                try { report.put("error", error.toString()); } catch (JSONException ignored) {}
            } finally {
                profiles.forEach((profile, proxy) -> adapter.closeProfileProxy(profile, proxy));
            }
            try {
                Files.writeString(new File(getFilesDir(), "report.json").toPath(), report.toString(2));
                runOnUiThread(() -> output.setText(report.toString()));
            } catch (Exception error) {
                runOnUiThread(() -> output.setText(error.toString()));
            }
        }, "BluetoothProbe").start();
    }

    private static int count(JSONObject object, String key) {
        JSONArray values = object.optJSONArray(key);
        return values == null ? -1 : values.length();
    }

    private JSONArray devices(Collection<BluetoothDevice> devices) throws JSONException {
        JSONArray rows = new JSONArray();
        for (BluetoothDevice device : devices) {
            JSONObject row = new JSONObject();
            row.put("address", device.getAddress());
            row.put("name", device.getName());
            row.put("alias", device.getAlias());
            row.put("bondState", device.getBondState());
            row.put("type", device.getType());
            BluetoothClass klass = device.getBluetoothClass();
            row.put("deviceClass", klass == null ? -1 : klass.getDeviceClass());
            JSONArray uuids = new JSONArray();
            ParcelUuid[] values = device.getUuids();
            if (values != null) for (ParcelUuid uuid : values) uuids.put(uuid.toString());
            row.put("uuids", uuids);
            rows.put(row);
        }
        return rows;
    }
}
