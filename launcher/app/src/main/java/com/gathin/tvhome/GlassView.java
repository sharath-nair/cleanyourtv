package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * A translucent glass slab: soft sheen fill, bright top specular, crisp rim and a faint outer glow.
 * One instance floats over the grid and is moved with spring animations between tiles.
 */
public class GlassView extends View {

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint specular = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float density;
    private float radius;
    /** Glow colour as 0xRRGGBB: white normally, a festival's accent (diya gold) while a festive skin is on. */
    private int glowRgb = 0xFFFFFF;
    /** Glow strength: the white glow stays faint, a coloured one needs more alpha to read as warm light. */
    private int glowAlpha = 0x30;
    /** glass_style rim or halo: no sheen and no fill, only the crisp rim (and, for halo, a lighter outer glow). */
    private boolean flat;
    private boolean halo;
    /** The Sharingan skin: crimson glass, and a three-tomoe emblem that spins in each time the highlight lands. */
    private boolean sharingan;
    private float spin = 1f;          // 0..1 progress of the emblem's spin; 1 is at rest
    private ValueAnimator spinAnimator;
    private final Paint emblemFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint emblemLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path tomoe = new Path();

    public GlassView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        radius = 20f * density;
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(1.5f * density);
        rim.setColor(0xCCFFFFFF);
        glow.setStyle(Paint.Style.STROKE);
        // Cache the slab as a texture. The spring only changes translation, so the GPU can
        // move it without replaying clipPath and the glow on every frame. That path is
        // software-rendered on the MT5895 and is what makes the highlight crawl.
        setLayerType(LAYER_TYPE_HARDWARE, null);
        emblemLine.setStyle(Paint.Style.STROKE);
        buildTomoe();
    }

    /** One comma-shaped tomoe on a unit emblem: a round head on the track circle, a tail curling round it. */
    private void buildTomoe() {
        final float track = 0.62f, head = 0.15f;
        tomoe.reset();
        tomoe.addCircle(0f, -track, head, Path.Direction.CW);
        final int steps = 14;
        float[] outer = new float[steps * 2 + 2], inner = new float[steps * 2 + 2];
        for (int i = 0; i <= steps; i++) {
            float t = i / (float) steps;
            double angle = Math.toRadians(-90 + 78 * t);
            float half = head * (1f - t) * 0.95f;
            outer[2 * i] = (float) Math.cos(angle) * (track + half);
            outer[2 * i + 1] = (float) Math.sin(angle) * (track + half);
            inner[2 * i] = (float) Math.cos(angle) * (track - half);
            inner[2 * i + 1] = (float) Math.sin(angle) * (track - half);
        }
        tomoe.moveTo(outer[0], outer[1]);
        for (int i = 1; i <= steps; i++) tomoe.lineTo(outer[2 * i], outer[2 * i + 1]);
        for (int i = steps; i >= 0; i--) tomoe.lineTo(inner[2 * i], inner[2 * i + 1]);
        tomoe.close();
    }

    /** Switches the highlight to the Sharingan skin. */
    void setSharingan(boolean on) {
        sharingan = on;
        if (on) rim.setColor(0xFFE8182B);
        if (getWidth() > 0) onSizeChanged(getWidth(), getHeight(), getWidth(), getHeight());
        invalidate();
    }

    /** The highlight has just landed on a tile: spin the emblem in, once. */
    void activate() {
        if (!sharingan) return;
        if (spinAnimator != null) spinAnimator.cancel();
        spinAnimator = ValueAnimator.ofFloat(0f, 1f);
        spinAnimator.setDuration(750);
        spinAnimator.setInterpolator(new DecelerateInterpolator(2f));
        spinAnimator.addUpdateListener(a -> {
            spin = (float) a.getAnimatedValue();
            invalidate();
        });
        spinAnimator.start();
    }

    /** A Sharingan of radius r at (cx, cy), turned by degrees, at the given opacity (0..1). */
    private void drawEmblem(Canvas canvas, float cx, float cy, float r, float degrees, float opacity) {
        int a = Math.round(255 * opacity);
        canvas.save();
        canvas.translate(cx, cy);
        canvas.rotate(degrees);
        // Ring and inner track: a wide faint stroke under a thin bright one reads as a glow without a blur.
        emblemLine.setColor(0x00E8182B | (Math.round(a * 0.28f) << 24));
        emblemLine.setStrokeWidth(r * 0.16f);
        canvas.drawCircle(0f, 0f, r, emblemLine);
        emblemLine.setColor(0x00FF3040 | (a << 24));
        emblemLine.setStrokeWidth(Math.max(density, r * 0.045f));
        canvas.drawCircle(0f, 0f, r, emblemLine);
        canvas.drawCircle(0f, 0f, r * 0.62f, emblemLine);
        // Pupil and the three tomoe.
        emblemFill.setColor(0x00140204 | (a << 24));
        canvas.drawCircle(0f, 0f, r * 0.14f, emblemFill);
        emblemFill.setColor(0x00F01F32 | (a << 24));
        canvas.scale(r, r);
        for (int i = 0; i < 3; i++) {
            canvas.drawPath(tomoe, emblemFill);
            canvas.rotate(120f);
        }
        canvas.restore();
    }

    /** Tints the rim and the outer glow; 0 restores the plain white glass. */
    void setAccent(int argb) {
        if (sharingan) return;   // the skin keeps its own crimson
        if (argb == 0) {
            glowRgb = 0xFFFFFF;
            glowAlpha = 0x30;
            rim.setColor(0xCCFFFFFF);
        } else {
            glowRgb = argb & 0xFFFFFF;
            glowAlpha = 0x70;
            // The rim is the accent lifted towards white, so the edge still reads as glass, not paint.
            int r = (((glowRgb >> 16) & 0xFF) + 255 * 2) / 3, g = (((glowRgb >> 8) & 0xFF) + 255 * 2) / 3,
                    b = ((glowRgb & 0xFF) + 255 * 2) / 3;
            rim.setColor(0xE6000000 | (r << 16) | (g << 8) | b);
        }
        invalidate();
    }

    /** One of Config's GLASS_* styles. */
    void setStyle(String style) {
        flat = Config.GLASS_RIM.equals(style) || Config.GLASS_HALO.equals(style);
        halo = Config.GLASS_HALO.equals(style);
        rim.setStrokeWidth((flat ? 2f : 1.5f) * density);
        invalidate();
    }

    void setRadius(float px) {
        radius = px;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        fill.setShader(new LinearGradient(0, 0, 0, h, sharingan ? 0x38FF3040 : 0x3DFFFFFF,
                sharingan ? 0x0CFF0000 : 0x12FFFFFF, Shader.TileMode.CLAMP));
        specular.setShader(new LinearGradient(0, 0, 0, h * 0.45f, 0x66FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float inset = 6f * density;   // room for the glow
        rect.set(inset, inset, getWidth() - inset, getHeight() - inset);

        // Outer glow: thin rings across the inset, each a little fainter, so it fades out smoothly. Three wide
        // strokes left visible bands once the glow took an app's colour at a stronger alpha. The hardware layer
        // caches the result, so the rings cost one draw, not one per frame of the spring.
        if (!flat || halo) {
            int rings = 12;
            float step = inset / rings;
            int alpha = sharingan ? 0x9A : flat ? glowAlpha / 2 : glowAlpha;
            int glowColour = sharingan ? 0xE0101A : glowRgb;
            glow.setStrokeWidth(step + 0.5f);
            for (int i = 0; i < rings; i++) {
                float o = step * (i + 0.5f);
                float t = 1f - (float) i / rings;
                glow.setColor(Math.round(alpha * t) << 24 | glowColour);
                canvas.drawRoundRect(rect.left - o, rect.top - o, rect.right + o, rect.bottom + o, radius + o, radius + o, glow);
            }
        }
        if (flat) {
            canvas.drawRoundRect(rect, radius, radius, rim);
            return;
        }

        canvas.drawRoundRect(rect, radius, radius, fill);

        // Sheen across the top 45%: clipRect stays on the GPU (clipPath did not on the MT5895) and
        // the round rect under it keeps the top corners rounded with a flat bottom edge.
        canvas.save();
        canvas.clipRect(rect.left, rect.top, rect.right, rect.top + rect.height() * 0.45f);
        canvas.drawRoundRect(rect, radius, radius, specular);
        canvas.restore();

        canvas.drawRoundRect(rect, radius, radius, rim);
        if (sharingan) {
            // The spinning emblem, centred on the tile: fades in fast, turns down to a stop and fades out.
            if (spin < 1f) {
                float big = Math.min(rect.width(), rect.height()) * 0.5f - 2f * density;
                float opacity = Math.min(1f, spin / 0.12f) * (1f - spin) * 0.95f;
                drawEmblem(canvas, rect.centerX(), rect.centerY(), big, 480f * spin, opacity);
            }
            // The small emblem that stays in the corner while the highlight rests.
            float mini = 9f * density;
            drawEmblem(canvas, rect.right - mini - 7f * density, rect.top + mini + 7f * density, mini, 0f, 0.92f);
        }
    }
}
