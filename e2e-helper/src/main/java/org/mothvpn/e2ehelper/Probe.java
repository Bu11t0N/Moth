package org.mothvpn.e2ehelper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

/** GET /ip on the E2E echo server: directly, through a socket bound to the VPN network, and from the tun0 address. */
public class Probe extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String host = intent.getStringExtra("host") == null ? "10.0.2.2" : intent.getStringExtra("host");
        PendingResult pending = goAsync();
        new Thread(() -> {
            StringBuilder out = new StringBuilder();
            out.append("direct=").append(attempt(host, null, null));
            Network vpn = null;
            ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities c = cm.getNetworkCapabilities(n);
                if (c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) vpn = n;
            }
            out.append(";bound=").append(vpn == null ? "NOVPN" : attempt(host, vpn, null));
            InetAddress tun = null;
            try {
                NetworkInterface i = NetworkInterface.getByName("tun0");
                if (i != null) for (InetAddress a : Collections.list(i.getInetAddresses())) if (a instanceof Inet4Address) tun = a;
            } catch (Exception ignored) { }
            out.append(";tunip=").append(tun == null ? "NOTUN" : attempt(host, null, tun));
            pending.setResultData(out.toString());
            pending.finish();
        }).start();
    }

    private static String attempt(String host, Network network, InetAddress local) {
        try (Socket s = new Socket()) {
            if (network != null) network.bindSocket(s);
            if (local != null) s.bind(new InetSocketAddress(local, 0));
            s.connect(new InetSocketAddress(host, 8081), 2500);
            s.setSoTimeout(2500);
            OutputStream o = s.getOutputStream();
            o.write(("GET /ip HTTP/1.0\r\nHost: " + host + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            o.flush();
            InputStream in = s.getInputStream();
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[1024];
            for (int n; (n = in.read(chunk)) > 0; ) buffer.write(chunk, 0, n);
            String response = new String(buffer.toByteArray(), StandardCharsets.US_ASCII);
            int body = response.indexOf("\r\n\r\n");
            return body < 0 ? "ERR:bad response" : response.substring(body + 4).trim();
        } catch (Exception e) {
            return "ERR:" + e.getClass().getSimpleName() + " " + String.valueOf(e.getMessage()).replace(';', ',');
        }
    }
}
