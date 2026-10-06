package com.strongholdprotocol.client;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.Collections;

/**
 * The device's own addresses, read straight off the interfaces.
 *
 * <p>Deliberately not {@code WifiManager}: that would drag in location permission for an SSID nobody
 * needs. {@link NetworkInterface} needs no permission at all and reports the Wi-Fi address the moment
 * the interface is up.
 */
public final class NetInfo {

    private NetInfo() {}

    /**
     * @return the first usable non-loopback IPv4 address (the LAN address when on Wi-Fi), or null
     */
    public static String localIpv4() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress a = ia.getAddress();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress() && !a.isLinkLocalAddress()) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    /** @return the interface carrying {@link #localIpv4()}, or null */
    public static String localInterfaceName() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress a = ia.getAddress();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress() && !a.isLinkLocalAddress()) {
                        return ni.getName();
                    }
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    /**
     * The /24 prefix a scan should walk, e.g. {@code 192.168.1} for 192.168.1.23.
     *
     * <p>Only /24 is handled on purpose: home Wi-Fi is essentially always /24, and scanning a /16
     * would mean 65k probes for no benefit.
     *
     * @return the first three octets, or null when there is no LAN address
     */
    public static String scanPrefix() {
        String ip = localIpv4();
        if (ip == null) return null;
        int last = ip.lastIndexOf('.');
        return last > 0 ? ip.substring(0, last) : null;
    }
}
