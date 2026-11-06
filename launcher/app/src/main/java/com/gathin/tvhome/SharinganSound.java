package com.gathin.tvhome;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.SystemClock;

/**
 * The short "activation" sound that goes with the Sharingan highlight (res/raw/sharingan_activate.mp3). A SoundPool
 * holds the decoded clip, so playing it costs nothing while Home sits idle. Replace the file with another short clip
 * of the same name (mp3, wav or ogg; keep it to a few seconds) to change the sound.
 */
final class SharinganSound {
    /** Fastest repeat: the clip is about two seconds long, and holding a D-pad key must not restart it every step. */
    private static final long MIN_GAP_MS = 900;
    private final SoundPool pool;
    private final int sound;
    private final float volume;
    private volatile boolean loaded;
    private long last;
    private int stream;

    SharinganSound(Context context, float volume) {
        this.volume = volume;
        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        pool = new SoundPool.Builder().setMaxStreams(2).setAudioAttributes(attributes).build();
        pool.setOnLoadCompleteListener((p, id, status) -> loaded = status == 0);
        sound = pool.load(context, R.raw.sharingan_activate, 1);
    }

    void play() {
        if (!loaded) return;
        long now = SystemClock.uptimeMillis();
        if (now - last < MIN_GAP_MS) return;
        last = now;
        // A new play cuts the old one off rather than layering two copies of the same clip.
        if (stream != 0) pool.stop(stream);
        stream = pool.play(sound, volume, volume, 1, 0, 1f);
    }

    void release() {
        pool.release();
    }
}
