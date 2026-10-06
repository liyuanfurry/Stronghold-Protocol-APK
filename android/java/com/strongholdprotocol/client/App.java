package com.strongholdprotocol.client;

import android.app.Application;

/**
 * Process-scoped singletons.
 *
 * <p>Both live here rather than in an Activity because neither should die with a screen rotation:
 * the asset cache keeps a 272 MiB update running, and the local server is a child process that other
 * devices on the LAN are connected to.
 */
public class App extends Application {

    private AssetCache cache;
    private LocalServer localServer;

    @Override
    public void onCreate() {
        super.onCreate();
        cache = new AssetCache(this);
        localServer = new LocalServer(this);
    }

    public AssetCache cache() { return cache; }

    public LocalServer localServer() { return localServer; }

    public static AssetCache cacheOf(android.content.Context c) {
        return ((App) c.getApplicationContext()).cache();
    }

    public static LocalServer localServerOf(android.content.Context c) {
        return ((App) c.getApplicationContext()).localServer();
    }
}
