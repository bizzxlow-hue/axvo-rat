package com.xnullcat.app;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.view.Surface;
import android.view.TextureView;

/** Video kecil tanpa suara yang diputar berulang dan mengisi seluruh kotaknya (dipotong tengah). */
final class LoopVideo extends TextureView implements TextureView.SurfaceTextureListener {

    private final String asset;
    private MediaPlayer mp;
    private int vw;
    private int vh;

    LoopVideo(Context c, String asset) {
        super(c);
        this.asset = asset;
        setOpaque(true);
        setAlpha(0f);
        setSurfaceTextureListener(this);
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
        release();
        try {
            final MediaPlayer m = new MediaPlayer();
            mp = m;
            try (AssetFileDescriptor fd = getContext().getAssets().openFd(asset)) {
                m.setDataSource(fd.getFileDescriptor(), fd.getStartOffset(), fd.getLength());
            }
            m.setSurface(new Surface(st));
            m.setVolume(0f, 0f);
            m.setLooping(true);
            m.setOnVideoSizeChangedListener(new MediaPlayer.OnVideoSizeChangedListener() {
                @Override
                public void onVideoSizeChanged(MediaPlayer p, int w2, int h2) {
                    vw = w2;
                    vh = h2;
                    fit();
                }
            });
            m.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer p) {
                    if (mp != p) {
                        return;
                    }
                    p.start();
                    fit();
                    animate().alpha(1f).setDuration(700).start();
                }
            });
            m.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer p, int what, int extra) {
                    return true; // video gagal: kotak tetap kosong, fitur lain tetap jalan
                }
            });
            m.prepareAsync();
        } catch (Exception e) {
            release();
        }
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
        fit();
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
        release();
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture st) {
        // tidak dipakai
    }

    /** Memperbesar gambar supaya menutup seluruh kotak tanpa gepeng. */
    private void fit() {
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0 || vw == 0 || vh == 0) {
            return;
        }
        float view = (float) w / h;
        float vid = (float) vw / vh;
        float sx = 1f;
        float sy = 1f;
        if (vid < view) {
            sy = view / vid;
        } else {
            sx = vid / view;
        }
        Matrix m = new Matrix();
        m.setScale(sx, sy, w / 2f, h / 2f);
        setTransform(m);
    }

    private void release() {
        MediaPlayer m = mp;
        mp = null;
        if (m != null) {
            try {
                m.release();
            } catch (Exception ignored) {
                // sudah dilepas
            }
        }
    }
}
