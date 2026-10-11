/* Modified for AEmulator Super Plus, 2026-10-11: neighbouring networks and signal level. GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.stub;

import android.net.DhcpInfo;
import android.net.wifi.WifiConfiguration;
import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.SystemClock;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * IWifiManager for a guest that has no wireless hardware: the one network the host configured is always in range, and
 * it is connected while Wi-Fi is switched on and the network has not been forgotten or disconnected. A secured network
 * only accepts its own passphrase.
 *
 * Transaction codes and return types are read from the guest framework's own IWifiManager at start-up, so the same
 * service serves Android 4.0 through 7.1; methods that are not implemented answer "false / 0 / null".
 */
final class WifiService extends Binder {
    static final String DESCRIPTOR = "android.net.wifi.IWifiManager";
    static final int NET_ID = 0;
    /** Private transaction that only this service answers: how the watcher tells our "wifi" from the framework's own. */
    static final int PROBE = 0x41454d55;
    static final int PROBE_ANSWER = 0x57494649;

    /** Told when the connection comes or goes (the network agent that carries it into ConnectivityService). */
    interface Listener {
        void onConnectionChanged(boolean connected);
    }

    private static final int STATE_DISABLED = 1;
    private static final int STATE_ENABLED = 3;
    private static final int AP_STATE_DISABLED = 11;

    private final WifiConfig cfg;
    private final Map<Integer, String> names = new HashMap<Integer, String>();
    private final Map<String, Method> methods = new HashMap<String, Method>();
    private final Map<Integer, Boolean> addedOk = new HashMap<Integer, Boolean>();
    private int nextId = 100;

    private boolean enabled = true;
    private boolean saved = true;
    private boolean down;          // disconnect() was called: stays off until reconnect / enableNetwork / connect
    private boolean connected = true;
    private Listener listener;
    private volatile LegacyWifi legacy;
    private final Messenger messenger;
    private final java.util.Set<String> unhandled = new java.util.HashSet<String>();

    WifiService(WifiConfig cfg, Looper looper) throws Exception {
        this.cfg = cfg;
        Class<?> stub = Class.forName(DESCRIPTOR + "$Stub");
        for (Field f : stub.getDeclaredFields()) {
            if (!f.getName().startsWith("TRANSACTION_")) continue;
            f.setAccessible(true);
            names.put(f.getInt(null), f.getName().substring("TRANSACTION_".length()));
        }
        for (Method m : Class.forName(DESCRIPTOR).getMethods()) {
            if (!methods.containsKey(m.getName())) methods.put(m.getName(), m);
        }
        messenger = new Messenger(new ChannelHandler(looper));
        StringBuilder all = new StringBuilder();
        for (Map.Entry<Integer, String> e : names.entrySet()) all.append(e.getKey()).append('=').append(e.getValue()).append(' ');
        Refl.log("transactions: " + all.toString().trim());
        Refl.log("IWifiManager: " + names.size() + " transactions, " + (connected ? "connected to " : "ready for ") + cfg.ssid
                + (cfg.secured ? " (WPA2)" : " (open)"));
    }

    void setLegacy(LegacyWifi l) {
        legacy = l;
    }

    synchronized boolean isEnabled() {
        return enabled;
    }

    synchronized void setListener(Listener l) {
        listener = l;
    }

    synchronized boolean isConnected() {
        return connected;
    }

    private void update() {
        LegacyWifi lg = legacy;
        if (lg != null) lg.changed();
        boolean now = enabled && saved && !down;
        Listener l = null;
        synchronized (this) {
            if (now == connected) return;
            connected = now;
            l = listener;
        }
        Refl.log(now ? "connected to " + cfg.ssid : "disconnected");
        if (l != null) l.onConnectionChanged(now);
    }

    // ------------------------------------------------------------------ credentials

    private static String unquote(String s) {
        if (s == null) return null;
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) return s.substring(1, s.length() - 1);
        return s;
    }

    /** True when [c] names our network and carries its passphrase (or none, for an open network). */
    private boolean credentialsOk(WifiConfiguration c) {
        if (c == null || !cfg.ssid.equals(unquote(c.SSID))) return false;
        String psk = unquote(c.preSharedKey);
        if (cfg.secured) return psk != null && psk.equals(cfg.psk);
        return psk == null || psk.length() == 0;
    }

    private static WifiConfiguration readConfig(Parcel data) {
        if (data.dataAvail() < 4 || data.readInt() == 0) return null;
        // CREATOR is hidden in the public SDK; the field is there at run time
        try {
            @SuppressWarnings("unchecked") android.os.Parcelable.Creator<WifiConfiguration> c =
                (android.os.Parcelable.Creator<WifiConfiguration>) WifiConfiguration.class.getField("CREATOR").get(null);
            return c.createFromParcel(data);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ objects handed to apps

    private Object wifiSsid() {
        return wifiSsid(cfg.ssid);
    }

    private static Object wifiSsid(String name) {
        Class<?> c = Refl.cls("android.net.wifi.WifiSsid");
        try {
            Object s = Refl.callStatic(c, "createFromHex", Refl.hex(name.getBytes("UTF-8")));
            if (s != null) return s;
        } catch (Throwable ignored) { }
        return Refl.callStatic(c, "createFromAsciiEncoded", name);
    }

    Object wifiInfo() {
        Object wi = Refl.newNoArg("android.net.wifi.WifiInfo");
        if (wi == null || !isConnected()) return wi;
        // before 4.2 the name is a plain String, from 4.2 a WifiSsid
        Object ssid = wifiSsid();
        Refl.call(wi, "setSSID", ssid != null ? ssid : cfg.ssid);
        Refl.call(wi, "setBSSID", cfg.bssid);
        Refl.call(wi, "setMacAddress", cfg.mac);
        Refl.call(wi, "setNetworkId", Integer.valueOf(NET_ID));
        Refl.call(wi, "setRssi", Integer.valueOf(cfg.rssi));
        Refl.call(wi, "setLinkSpeed", Integer.valueOf(cfg.speed));
        Refl.call(wi, "setFrequency", Integer.valueOf(cfg.frequency));
        Refl.call(wi, "setMeteredHint", Boolean.FALSE);
        try {
            Refl.call(wi, "setInetAddress", InetAddress.getByName(cfg.ip));
        } catch (Throwable ignored) { }
        try {
            Class<?> ss = Class.forName("android.net.wifi.SupplicantState");
            Object completed = ss.getField("COMPLETED").get(null);
            Refl.call(wi, "setSupplicantState", completed);
        } catch (Throwable ignored) { }
        return wi;
    }

    private Object scanResult() {
        return scanResult(cfg.ssid, cfg.bssid, cfg.secured ? "[WPA2-PSK-CCMP][ESS]" : "[ESS]", cfg.rssi, cfg.frequency);
    }

    /** One entry of the scan list: our network, or one of the visible-but-locked neighbours (WifiConfig.neighbors). */
    private static Object scanResult(String name, String bssid, String caps, int rssi, int frequency) {
        Class<?> ssidClass = Refl.cls("android.net.wifi.WifiSsid");
        Object ssid = wifiSsid(name);
        Object r = null;
        if (ssidClass != null) {
            r = Refl.create("android.net.wifi.ScanResult",
                    new Class<?>[] { ssidClass, String.class, String.class, int.class, int.class, long.class },
                    ssid, bssid, caps, Integer.valueOf(rssi),
                    Integer.valueOf(frequency), Long.valueOf(SystemClock.elapsedRealtime() * 1000L));
        } else {
            // before 4.2: ScanResult(String ssid, String bssid, String caps, int level, int frequency)
            r = Refl.create("android.net.wifi.ScanResult",
                    new Class<?>[] { String.class, String.class, String.class, int.class, int.class },
                    name, bssid, caps, Integer.valueOf(rssi), Integer.valueOf(frequency));
        }
        if (r == null && ssidClass != null) {
            // newer frameworks: a longer constructor that starts with the same six parameters
            Class<?> sr = Refl.cls("android.net.wifi.ScanResult");
            if (sr != null) {
                for (java.lang.reflect.Constructor<?> k : sr.getDeclaredConstructors()) {
                    Class<?>[] p = k.getParameterTypes();
                    if (p.length < 6 || p[0] != ssidClass || p[1] != String.class || p[2] != String.class) continue;
                    Object[] a = new Object[p.length];
                    for (int i = 0; i < p.length; i++) a[i] = Refl.defaultFor(p[i]);
                    a[0] = ssid; a[1] = bssid; a[2] = caps;
                    a[3] = Integer.valueOf(rssi); a[4] = Integer.valueOf(frequency);
                    a[5] = Long.valueOf(SystemClock.elapsedRealtime() * 1000L);
                    try {
                        k.setAccessible(true);
                        r = k.newInstance(a);
                        break;
                    } catch (Throwable t) {
                        Refl.log("ScanResult constructor: " + t);
                    }
                }
            }
        }
        if (r == null) return null;
        Refl.setField(r, "SSID", name);
        Refl.setField(r, "BSSID", bssid);
        Refl.setField(r, "capabilities", caps);
        Refl.setField(r, "level", Integer.valueOf(rssi));
        Refl.setField(r, "frequency", Integer.valueOf(frequency));
        Refl.setField(r, "timestamp", Long.valueOf(SystemClock.elapsedRealtime() * 1000L));
        if (ssid != null) Refl.setField(r, "wifiSsid", ssid);
        return r;
    }

    private WifiConfiguration savedConfig() {
        WifiConfiguration c = new WifiConfiguration();
        c.SSID = "\"" + cfg.ssid + "\"";
        c.networkId = NET_ID;
        c.status = isConnected() ? WifiConfiguration.Status.CURRENT : WifiConfiguration.Status.ENABLED;
        if (cfg.secured) {
            c.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
            c.allowedProtocols.set(WifiConfiguration.Protocol.RSN);
            c.allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.CCMP);
            c.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.CCMP);
        } else {
            c.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
        }
        return c;
    }

    private DhcpInfo dhcpInfo() {
        DhcpInfo d = new DhcpInfo();
        d.ipAddress = WifiConfig.ipInt(cfg.ip);
        d.gateway = WifiConfig.ipInt(cfg.gateway);
        d.netmask = WifiConfig.netmaskInt(cfg.prefix);
        d.dns1 = WifiConfig.ipInt(cfg.dns1);
        d.dns2 = WifiConfig.ipInt(cfg.dns2);
        d.serverAddress = WifiConfig.ipInt(cfg.gateway);
        d.leaseDuration = 86400;
        return d;
    }

    // ------------------------------------------------------------------ transport

    /** A list result: 4.4 returns List<T>, newer frameworks ParceledListSlice<T>, whatever the guest's interface says. */
    private void writeList(String method, List<? extends Parcelable> list, Parcel reply) {
        Method m = methods.get(method);
        boolean slice = m != null && m.getReturnType().getName().endsWith("ParceledListSlice");
        if (!slice) {
            reply.writeTypedList(list);
            return;
        }
        Object s = Refl.create(m.getReturnType().getName(), new Class<?>[] { List.class }, list);
        if (s instanceof Parcelable) {
            reply.writeInt(1);
            ((Parcelable) s).writeToParcel(reply, Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
        } else {
            reply.writeInt(0);
        }
    }

    private static void writeParcelable(Parcel reply, Parcelable p) {
        if (p == null) {
            reply.writeInt(0);
        } else {
            reply.writeInt(1);
            p.writeToParcel(reply, Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
        }
    }

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws android.os.RemoteException {
        if (code == INTERFACE_TRANSACTION) {
            if (reply != null) reply.writeString(DESCRIPTOR);
            return true;
        }
        if (code == PROBE) {
            if (reply != null) reply.writeInt(PROBE_ANSWER);
            return true;
        }
        String name = names.get(Integer.valueOf(code));
        if (name == null) return super.onTransact(code, data, reply, flags);
        try {
            data.enforceInterface(DESCRIPTOR);
            handle(name, data, reply);
        } catch (Throwable t) {
            Refl.log(name + " failed: " + t);
            if (reply != null) {
                reply.setDataSize(0);
                reply.setDataPosition(0);
                reply.writeNoException();
                reply.writeInt(0);
            }
        }
        return true;
    }

    private synchronized void handle(String name, Parcel data, Parcel reply) {
        if (reply == null) return; // one-way call: nothing to answer
        reply.writeNoException();
        if (name.equals("getWifiEnabledState")) {
            reply.writeInt(enabled ? STATE_ENABLED : STATE_DISABLED);
        } else if (name.equals("getWifiApEnabledState")) {
            reply.writeInt(AP_STATE_DISABLED);
        } else if (name.equals("setWifiEnabled")) {
            // 4.4: (boolean), 6.0+: (String callingPackage, boolean)
            if (data.dataAvail() > 4) data.readString();
            enabled = data.readInt() != 0;
            update();
            reply.writeInt(1);
        } else if (name.equals("getConnectionInfo")) {
            Object wi = wifiInfo();
            writeParcelable(reply, wi instanceof Parcelable ? (Parcelable) wi : null);
        } else if (name.equals("getScanResults")) {
            List<Parcelable> list = new ArrayList<Parcelable>();
            Object r = enabled ? scanResult() : null;
            if (r instanceof Parcelable) list.add((Parcelable) r);
            if (enabled) for (WifiConfig.Neighbor n : cfg.neighbors) {
                Object o = scanResult(n.ssid, n.bssid, n.caps, n.rssi, n.frequency);
                if (o instanceof Parcelable) list.add((Parcelable) o);
            }
            writeList("getScanResults", list, reply);
        } else if (name.equals("getConfiguredNetworks")) {
            List<Parcelable> list = new ArrayList<Parcelable>();
            if (saved) list.add(savedConfig());
            writeList("getConfiguredNetworks", list, reply);
        } else if (name.equals("getDhcpInfo")) {
            writeParcelable(reply, dhcpInfo());
        } else if (name.equals("addOrUpdateNetwork")) {
            WifiConfiguration c = readConfig(data);
            boolean ok = credentialsOk(c);
            int id = ok ? NET_ID : nextId++;
            addedOk.put(Integer.valueOf(id), Boolean.valueOf(ok));
            reply.writeInt(id);
        } else if (name.equals("enableNetwork")) {
            int id = data.readInt();
            boolean ok = id == NET_ID && saved || Boolean.TRUE.equals(addedOk.get(Integer.valueOf(id)));
            if (ok) { saved = true; down = false; update(); }
            reply.writeInt(1);
        } else if (name.equals("removeNetwork")) {
            int id = data.readInt();
            if (id == NET_ID) { saved = false; update(); }
            addedOk.remove(Integer.valueOf(id));
            reply.writeInt(1);
        } else if (name.equals("disableNetwork")) {
            int id = data.readInt();
            if (id == NET_ID) { down = true; update(); }
            reply.writeInt(1);
        } else if (name.equals("disconnect")) {
            down = true;
            update();
            reply.writeInt(1);
        } else if (name.equals("reconnect") || name.equals("reassociate")) {
            down = false;
            update();
            reply.writeInt(1);
        } else if (name.equals("pingSupplicant") || name.equals("saveConfiguration") || name.equals("isScanAlwaysAvailable")
                || name.equals("acquireWifiLock")) {
            reply.writeInt(1);
        } else if (name.equals("startScan")) {
            LegacyWifi lg = legacy;
            if (lg != null) lg.scanDone();
        } else if (name.toLowerCase().contains("messenger")) {
            // 4.0 hands WifiManager the state machine's messenger (getWifiStateMachineMessenger), 4.1+ the service's
            writeParcelable(reply, messenger);
        } else {
            if (unhandled.add(name)) Refl.log("not implemented, answering 0/null: " + name);
            reply.writeInt(0);
        }
    }

    // ------------------------------------------------------------------ the AsyncChannel WifiManager.connect()/save()/forget() use

    private final class ChannelHandler extends Handler {
        private final int fullConnection;
        private final int fullyConnected;
        private final int connectNetwork;
        private final int saveNetwork;
        private final int forgetNetwork;
        private final Class<?> mgr = Refl.cls("android.net.wifi.WifiManager");
        private final Class<?> sm = Refl.cls("android.net.wifi.WifiStateMachine");

        /** First constant that exists under any of [names] in WifiManager or WifiStateMachine (names differ between 4.0 and 4.1+). */
        private int konst(int fallback, String... names) {
            for (String n : names) {
                for (Class<?> c : new Class<?>[] { mgr, sm }) {
                    if (c == null) continue;
                    int v = Refl.intConst(c, n, Integer.MIN_VALUE);
                    if (v != Integer.MIN_VALUE) return v;
                }
            }
            return fallback;
        }

        ChannelHandler(Looper looper) {
            super(looper);
            Class<?> ac = Refl.cls("com.android.internal.util.AsyncChannel");
            fullConnection = Refl.intConst(ac, "CMD_CHANNEL_FULL_CONNECTION", -1);
            fullyConnected = Refl.intConst(ac, "CMD_CHANNEL_FULLY_CONNECTED", -1);
            connectNetwork = konst(-1, "CONNECT_NETWORK", "CMD_CONNECT_NETWORK");
            saveNetwork = konst(-1, "SAVE_NETWORK", "CMD_SAVE_NETWORK");
            forgetNetwork = konst(-1, "FORGET_NETWORK", "CMD_FORGET_NETWORK");
            Refl.log("channel codes: connect=" + connectNetwork + " save=" + saveNetwork + " forget=" + forgetNetwork
                    + " full=" + fullConnection);
        }

        private void answer(Message in, int what, int arg1) {
            if (in.replyTo == null || what < 0) return;
            Message m = Message.obtain();
            m.what = what;
            m.arg1 = arg1;
            m.arg2 = in.arg2; // the key of the caller's ActionListener
            try {
                in.replyTo.send(m);
            } catch (Throwable t) {
                Refl.log("reply: " + t);
            }
        }

        @Override
        public void handleMessage(Message msg) {
            synchronized (WifiService.this) {
                handleLocked(msg);
            }
        }

        private void handleLocked(Message msg) {
            if (msg.what == fullConnection) {
                answer(msg, fullyConnected, 0);
                return;
            }
            boolean connect = msg.what == connectNetwork;
            boolean save = msg.what == saveNetwork;
            boolean forget = msg.what == forgetNetwork;
            if (!connect && !save && !forget) { Refl.log("channel message ignored: what=" + msg.what); return; }
            // the matching *_FAILED / *_SUCCEEDED codes follow the command in WifiManager (BASE + 1, +2, +3 ...)
            int failed = msg.what + 1;
            int succeeded = msg.what + 2;
            String tag = connect ? "CONNECT_NETWORK" : save ? "SAVE_NETWORK" : "FORGET_NETWORK";
            failed = konst(failed, tag + "_FAILED");
            succeeded = konst(succeeded, tag + "_SUCCEEDED");
            if (forget) {
                if (msg.arg1 == NET_ID) { saved = false; update(); }
                answer(msg, succeeded, 0);
                return;
            }
            boolean ok;
            if (msg.obj instanceof WifiConfiguration) ok = credentialsOk((WifiConfiguration) msg.obj);
            else ok = msg.arg1 == NET_ID && saved || Boolean.TRUE.equals(addedOk.get(Integer.valueOf(msg.arg1)));
            if (ok) {
                saved = true;
                if (connect) down = false;
                update();
                answer(msg, succeeded, 0);
            } else {
                answer(msg, failed, 0);
            }
        }
    }
}
