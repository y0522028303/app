package com.nova.mp3;

import android.net.Uri;

public class Track {
    public final String path;
    public final String title;
    public final String artist;
    public final String album;
    public final boolean external;
    public final long duration;
    public final Uri uri;

    public Track(String path, String title, String artist, String album,
                 boolean external, long duration, Uri uri) {
        this.path = path;
        this.title = title == null || title.trim().isEmpty() ? fileName(path) : title;
        this.artist = artist == null || artist.trim().isEmpty() ? "אמן לא ידוע" : artist;
        this.album = album == null || album.trim().isEmpty() ? "אלבום לא ידוע" : album;
        this.external = external;
        this.duration = duration;
        this.uri = uri;
    }

    private static String fileName(String p) {
        if (p == null) return "ללא שם";
        int i = Math.max(p.lastIndexOf('/'), p.lastIndexOf('\\'));
        String n = i >= 0 ? p.substring(i + 1) : p;
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    public String time() {
        long sec = Math.max(0L, duration / 1000L);
        return String.format(java.util.Locale.US, "%d:%02d", sec / 60L, sec % 60L);
    }
}