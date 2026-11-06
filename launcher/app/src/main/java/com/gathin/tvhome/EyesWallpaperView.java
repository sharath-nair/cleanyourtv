package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.animation.ValueAnimator;
import android.graphics.Shader;
import android.view.animation.DecelerateInterpolator;
import android.view.View;

import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.FloatValueHolder;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

/**
 * A full-screen wallpaper whose two glowing eyes follow the focus. The picture is the base image with the
 * irises painted out, plus one small sprite per iris (itachi_*.png); the sprites are drawn over their rest
 * position, nudged a few source pixels toward whatever has focus. Two bitmaps and four springs: no blur, no
 * video, nothing runs while the eyes are still.
 *
 * The coordinates below are in pixels of the source picture (1920x1200), which is scaled centre-crop to the
 * screen exactly like an ImageView would, so they hold on any TV resolution.
 */
final class EyesWallpaperView extends View {
    private static final int SRC_W = 1920, SRC_H = 1200;
    /** Iris centre of each eye in the source picture, then the top-left of its sprite. */
    private static final float[] CENTER_X = {684f, 1014f}, CENTER_Y = {490f, 440f};
    private static final int[] SPRITE_X = {647, 971}, SPRITE_Y = {460, 401};
    /** Top-left of each eye-opening mask (itachi_eye_mask_*): the glow is drawn only where the mask is opaque, so it
     *  stays inside the eyeball and never spills onto the lids or the skin. */
    private static final int[] MASK_X = {612, 926}, MASK_Y = {459, 406};
    /** Furthest an iris moves from rest, in source pixels: the eyelids stop it any further. */
    private static final float MAX_X = 7f, MAX_Y = 3.5f;
    /** Distance (as a fraction of the screen) at which the gaze is about three quarters of its way to the limit. */
    private static final float REACH = 0.35f;
    private static final float GLOW_RADIUS = 48f;
    /** The halo's paint alpha at rest and at the top of the flare; the gradient's own alpha is scaled by it. */
    private static final int GLOW_REST = 128, GLOW_PEAK = 255;
    /** The halo grows by this fraction at the top of the flare. */
    private static final float GLOW_GROWTH = 0.6f;
    private static final long PULSE_MS = 650;

    private final Bitmap base;
    private final Bitmap[] sprite = new Bitmap[2];
    private final Bitmap[] mask = new Bitmap[2];
    private final Paint maskPaint = new Paint();
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final FloatValueHolder[] dx = {new FloatValueHolder(0f), new FloatValueHolder(0f)};
    private final FloatValueHolder[] dy = {new FloatValueHolder(0f), new FloatValueHolder(0f)};
    private final SpringAnimation[] springX = new SpringAnimation[2];
    private final SpringAnimation[] springY = new SpringAnimation[2];
    private boolean animate = true;
    private float scale = 1f, offsetX, offsetY;
    /** 0 at rest, 1 at the peak of the flare that runs when the gaze changes. */
    private float pulse;
    private ValueAnimator pulseAnimator;
    private final float[] aimed = new float[4];
    private boolean hasAim;

    EyesWallpaperView(Context context, boolean trueColor) {
        super(context);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setWillNotDraw(false);
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = trueColor ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;
        base = BitmapFactory.decodeResource(context.getResources(), R.drawable.itachi_base, options);
        sprite[0] = BitmapFactory.decodeResource(context.getResources(), R.drawable.itachi_eye_l);
        sprite[1] = BitmapFactory.decodeResource(context.getResources(), R.drawable.itachi_eye_r);
        mask[0] = BitmapFactory.decodeResource(context.getResources(), R.drawable.itachi_eye_mask_l);
        mask[1] = BitmapFactory.decodeResource(context.getResources(), R.drawable.itachi_eye_mask_r);
        maskPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        for (int i = 0; i < 2; i++) {
            springX[i] = spring(dx[i]);
            springY[i] = spring(dy[i]);
        }
        // A soft red halo behind each iris: a pre-computed gradient, not a runtime blur.
        glow.setAlpha(GLOW_REST);
        glow.setShader(new RadialGradient(0f, 0f, GLOW_RADIUS, new int[]{0xCCFF1A1A, 0x44C00000, 0x00800000},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
    }

    private SpringAnimation spring(FloatValueHolder holder) {
        SpringAnimation s = new SpringAnimation(holder);
        // Quick, with a hint of overshoot: an eye snaps to its target rather than gliding.
        s.setSpring(new SpringForce(0f).setStiffness(450f).setDampingRatio(0.72f));
        s.addUpdateListener((animation, value, velocity) -> invalidate());
        return s;
    }

    /** False: the eyes jump instead of springing (reduced motion, or animations off). */
    void setAnimate(boolean animate) {
        this.animate = animate;
    }

    /** Turns both eyes toward a point given in this view's own coordinates. */
    void lookAt(float x, float y) {
        if (getWidth() == 0 || getHeight() == 0) return;
        float[] t = new float[4];
        for (int i = 0; i < 2; i++) {
            float ex = offsetX + CENTER_X[i] * scale;
            float ey = offsetY + CENTER_Y[i] * scale;
            t[2 * i] = MAX_X * (float) Math.tanh((x - ex) / (REACH * getWidth()));
            t[2 * i + 1] = MAX_Y * (float) Math.tanh((y - ey) / (REACH * getHeight()));
        }
        aim(t[0], t[1], t[2], t[3]);
    }

    /**
     * Turns both eyes to a gaze given as -1..1 on each axis (left/up is negative), the full range the eyelids
     * allow, whatever the screen position of the thing being looked at. The top row of buttons uses this: they all
     * sit to the right of the face, so looking at them literally would barely move the eyes.
     */
    void lookTowards(float gazeX, float gazeY) {
        float tx = MAX_X * Math.max(-1f, Math.min(1f, gazeX));
        float ty = MAX_Y * Math.max(-1f, Math.min(1f, gazeY));
        aim(tx, ty, tx, ty);
    }

    /** Sends each eye to its target (x, y per eye, in source pixels), flaring the glow when the gaze really changes. */
    private void aim(float x0, float y0, float x1, float y1) {
        float[] t = {x0, y0, x1, y1};
        boolean changed = !hasAim;
        for (int i = 0; i < 4 && !changed; i++) changed = Math.abs(t[i] - aimed[i]) > 0.5f;
        System.arraycopy(t, 0, aimed, 0, 4);
        hasAim = true;
        for (int i = 0; i < 2; i++) {
            move(springX[i], dx[i], t[2 * i]);
            move(springY[i], dy[i], t[2 * i + 1]);
        }
        if (changed && animate) flare();
    }

    /** One quick flare of the halo, back to rest by itself. */
    private void flare() {
        if (pulseAnimator != null) pulseAnimator.cancel();
        pulseAnimator = ValueAnimator.ofFloat(1f, 0f);
        pulseAnimator.setDuration(PULSE_MS);
        pulseAnimator.setInterpolator(new DecelerateInterpolator(1.6f));
        pulseAnimator.addUpdateListener(a -> {
            pulse = (float) a.getAnimatedValue();
            invalidate();
        });
        pulseAnimator.start();
    }

    private void move(SpringAnimation spring, FloatValueHolder holder, float target) {
        if (animate) {
            spring.animateToFinalPosition(target);
        } else {
            spring.cancel();
            holder.setValue(target);
            invalidate();
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        // Centre-crop, as ImageView.ScaleType.CENTER_CROP does.
        scale = Math.max(w / (float) SRC_W, h / (float) SRC_H);
        offsetX = (w - SRC_W * scale) / 2f;
        offsetY = (h - SRC_H * scale) / 2f;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.save();
        canvas.translate(offsetX, offsetY);
        canvas.scale(scale, scale);
        canvas.drawBitmap(base, 0f, 0f, paint);
        glow.setAlpha(GLOW_REST + Math.round((GLOW_PEAK - GLOW_REST) * pulse));
        for (int i = 0; i < 2; i++) {
            float ox = dx[i].getValue(), oy = dy[i].getValue();
            // The glow goes into a small offscreen layer the size of the eye opening, and the mask keeps only the
            // part of it that is inside the eyeball.
            canvas.saveLayer(MASK_X[i], MASK_Y[i], MASK_X[i] + mask[i].getWidth(), MASK_Y[i] + mask[i].getHeight(), null);
            canvas.save();
            canvas.translate(CENTER_X[i] + ox, CENTER_Y[i] + oy);
            canvas.scale(1f + GLOW_GROWTH * pulse, 1f + GLOW_GROWTH * pulse);
            canvas.drawCircle(0f, 0f, GLOW_RADIUS, glow);
            canvas.restore();
            canvas.drawBitmap(mask[i], MASK_X[i], MASK_Y[i], maskPaint);
            canvas.restore();
            canvas.drawBitmap(sprite[i], SPRITE_X[i] + ox, SPRITE_Y[i] + oy, paint);
        }
        canvas.restore();
    }

    /** Frees the bitmaps with the activity. */
    void release() {
        if (pulseAnimator != null) pulseAnimator.cancel();
        for (int i = 0; i < 2; i++) {
            springX[i].cancel();
            springY[i].cancel();
        }
    }
}
