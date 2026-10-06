package com.strongholdprotocol.client;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Small typed wrapper over the app's SharedPreferences.
 *
 * <p>There is deliberately no built-in server address. An APK travels, and a hard-coded host would turn
 * whoever built it into a findable public target; the player types their own on first run.
 */
public final class Prefs {

    private static final String FILE = "stronghold";
    private static final String K_SERVER = "server";
    private static final String K_UPDATE_BASE = "updateBase";
    private static final String K_LAST_SYNC = "lastSyncAt";
    private static final String K_MANIFEST_HASH = "manifestHash";
    private static final String K_ACTIVE_GSRV = "activeGsrv";
    private static final String K_CACHE_BUST = "cacheBust";
    private static final String K_LOCAL_ART_HASH = "localArtHash";

    private Prefs() {}

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** @return the saved server, or "" when the player has not set one yet */
    public static String server(Context c) {
        return normalizeServer(sp(c).getString(K_SERVER, ""));
    }

    public static void setServer(Context c, String v) {
        sp(c).edit().putString(K_SERVER, normalizeServer(v)).apply();
    }

    /**
     * Where resource updates come from. Empty means "the same host as the game server", which is the
     * usual case; a separate value lets a mirror or a second server supply the payload.
     *
     * @return normalised base URL, or "" for "same as {@link #server(Context)}"
     */
    public static String updateBase(Context c) {
        return normalizeServer(sp(c).getString(K_UPDATE_BASE, ""));
    }

    public static void setUpdateBase(Context c, String v) {
        sp(c).edit().putString(K_UPDATE_BASE, normalizeServer(v)).apply();
    }

    public static long lastSyncAt(Context c) {
        return sp(c).getLong(K_LAST_SYNC, 0L);
    }

    public static void setLastSyncAt(Context c, long v) {
        sp(c).edit().putLong(K_LAST_SYNC, v).apply();
    }

    /**
     * Upstream tag the on-phone server should run, e.g. {@code v0.1.4}. Empty means "the version this
     * APK was built with".
     */
    public static String activeGsrv(Context c) {
        return sp(c).getString(K_ACTIVE_GSRV, "");
    }

    public static void setActiveGsrv(Context c, String v) {
        sp(c).edit().putString(K_ACTIVE_GSRV, v == null ? "" : v).apply();
    }

    /**
     * One-shot WebView cache invalidation marker.
     *
     * <p>A bad release cached {@code /data/local-assets.json} as a 404 for a day. That entry would keep
     * hiding the server's copy of the optional local-client art manifest even after the interception was
     * removed, so the cache is dropped exactly once per bump of this number.
     */
    /** Fingerprint of the local-client art index last synced, or empty. */
    public static String localArtHash(Context c) {
        return sp(c).getString(K_LOCAL_ART_HASH, "");
    }

    public static void setLocalArtHash(Context c, String v) {
        sp(c).edit().putString(K_LOCAL_ART_HASH, v == null ? "" : v).apply();
    }

    public static int cacheBust(Context c) {
        return sp(c).getInt(K_CACHE_BUST, 0);
    }

    public static void setCacheBust(Context c, int v) {
        sp(c).edit().putInt(K_CACHE_BUST, v).apply();
    }

    public static String manifestHash(Context c) {
        return sp(c).getString(K_MANIFEST_HASH, "");
    }

    public static void setManifestHash(Context c, String v) {
        sp(c).edit().putString(K_MANIFEST_HASH, v == null ? "" : v).apply();
    }

    /**
     * Trims whitespace, adds a scheme when the player typed a bare host, and strips trailing slashes.
     *
     * @param raw whatever is in the text field
     * @return a usable base URL, or "" when nothing was typed
     */
    public static String normalizeServer(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty()) return "";
        if (!s.matches("(?i)^[a-z][a-z0-9+.\\-]*://.*")) s = "https://" + s;
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }
}
