package ru.nsu.ccfit.zuev.osu.game.cursor.trail;

import org.anddev.andengine.engine.camera.Camera;
import org.anddev.andengine.entity.Entity;
import org.anddev.andengine.entity.sprite.Sprite;
import org.anddev.andengine.opengl.texture.region.TextureRegion;

import javax.microedition.khronos.opengles.GL10;

import ru.nsu.ccfit.zuev.osu.game.GameHelper;
import ru.nsu.ccfit.zuev.osu.game.cursor.main.CursorSprite;
import ru.nsu.ccfit.zuev.skins.OsuSkin;

public class CursorTrail extends Entity {

    private static final int TRAIL_CAPACITY = 2048;
    private static final float TRAIL_LIFETIME = 1.0f;
    private static final float MAX_OPACITY = 0.4f;
    private static final float FADE_DURATION_RATIO = 0.6f;
    private static final float SCALE_DURATION_RATIO = 1.6f;
    private static final float TRAIL_STEP_SIZE = 2.2f;

    private final StampSprite stamp;
    private final CursorSprite cursor;

    private final float[] px = new float[TRAIL_CAPACITY];
    private final float[] py = new float[TRAIL_CAPACITY];
    private final float[] pTime = new float[TRAIL_CAPACITY];

    private int head = 0;
    private int count = 0;
    private boolean spawning = false;
    private float currentTime = 0f;
    private float distanceRemainder = 0f;

    // Midpoint Bezier tracking variables
    private float lastInputX = Float.NaN, lastInputY = Float.NaN;
    private float lastMidX = Float.NaN, lastMidY = Float.NaN;

    private final float offsetX;
    private final float offsetY;
    private final float baseSize;

    public CursorTrail(TextureRegion trailTex, CursorSprite cursor) {
        this.cursor = cursor;
        this.stamp = new StampSprite(0, 0, trailTex);

        // Standard Alpha Blending for soft edges without visual overlap clipping
        stamp.setBlendFunction(GL10.GL_SRC_ALPHA, GL10.GL_ONE_MINUS_SRC_ALPHA);

        this.baseSize = cursor.baseSize;
        offsetX = -trailTex.getWidth() / 2f;
        offsetY = -trailTex.getHeight() / 2f;
    }

    public void setParticlesSpawnEnabled(boolean enabled) {
        this.spawning = enabled;
        if (!enabled) {
            // Reset tracking so the next touch starts a fresh curve
            lastInputX = Float.NaN;
            lastInputY = Float.NaN;
            lastMidX = Float.NaN;
            lastMidY = Float.NaN;
        }
    }

    // Replaced tracking to include a "leftover" distance to keep spacing perfect

    public void update(float x, float y, float dt) {
        currentTime += dt;
        if (!spawning) return;

        if (Float.isNaN(lastInputX)) {
            lastInputX = x;
            lastInputY = y;
            lastMidX = x;
            lastMidY = y;
            pushPoint(x, y);
            return;
        }

        float midX = (lastInputX + x) / 2f;
        float midY = (lastInputY + y) / 2f;

        // Pass distanceRemainder to ensure the first point of this segment
        // is exactly TRAIL_STEP_SIZE away from the last point of the previous segment.
        fillPathBezier(lastMidX, lastMidY, lastInputX, lastInputY, midX, midY);

        lastInputX = x;
        lastInputY = y;
        lastMidX = midX;
        lastMidY = midY;
    }

    private void pushPoint(float x, float y) {
        px[head] = x;
        py[head] = y;
        pTime[head] = currentTime;
        head = (head + 1) % TRAIL_CAPACITY;
        if (count < TRAIL_CAPACITY) count++;
    }

    // Midpoint Quadratic Bezier Curve Smoothing
    private void fillPathBezier(float x0, float y0, float cx, float cy, float x1, float y1) {
        // We use a higher sampling rate to find the actual length of the curve
        int samples = 10;
        float totalPathLength = 0;
        float prevX = x0, prevY = y0;

        for (int i = 1; i <= samples; i++) {
            float t = i / (float) samples;
            float u = 1 - t;
            float qx = (u * u * x0) + (2 * u * t * cx) + (t * t * x1);
            float qy = (u * u * y0) + (2 * u * t * cy) + (t * t * y1);
            totalPathLength += (float) Math.hypot(qx - prevX, qy - prevY);
            prevX = qx;
            prevY = qy;
        }

        // Use the remainder from the last frame to start the first point correctly
        float d = TRAIL_STEP_SIZE - distanceRemainder;

        while (d <= totalPathLength) {
            float t = d / totalPathLength;
            float u = 1 - t;
            float qx = (u * u * x0) + (2 * u * t * cx) + (t * t * x1);
            float qy = (u * u * y0) + (2 * u * t * cy) + (t * t * y1);

            pushPoint(qx, qy);
            d += TRAIL_STEP_SIZE;
        }

        // Save the leftover distance so the next segment starts at the right spot
        distanceRemainder = totalPathLength - (d - TRAIL_STEP_SIZE);
    }

    @Override
    protected void onManagedDraw(GL10 pGL, Camera pCamera) {
        // [FREEZE FIX] Constantly tick the clock forward during the draw loop
        // 0.016f represents roughly 60 FPS (1 second / 60 frames)
        currentTime += 0.016f * GameHelper.getSpeedMultiplier();

        if (count == 0) return;

        float currentLifetime = TRAIL_LIFETIME * GameHelper.getSpeedMultiplier();

        if (OsuSkin.get().isRotateCursorTrail()) {
            stamp.setRotation(cursor.getRotation());
        } else {
            stamp.setRotation(0f);
        }

        int newCount = 0;
        for (int i = 0; i < count; i++) {
            int idx = (head - 1 - i);
            if (idx < 0) idx += TRAIL_CAPACITY;

            float age = currentTime - pTime[idx];

            // Once we reach a particle that is too old, we can stop evaluating older ones
            if (age > currentLifetime) {
                break;
            }
            newCount++;

            float fadeLifeRatio = Math.max(0f, 1f - (age / (currentLifetime * FADE_DURATION_RATIO)));
            float scaleLifeRatio = Math.max(0f, 1f - (age / (currentLifetime * SCALE_DURATION_RATIO)));

            stamp.setPosition(px[idx] + offsetX, py[idx] + offsetY);
            stamp.setScale(baseSize * scaleLifeRatio);
            stamp.setAlpha(MAX_OPACITY * fadeLifeRatio);

            stamp.drawNow(pGL, pCamera);
        }

        // Update count so old points are pruned from the render cycle
        this.count = newCount;
    }

    private static class StampSprite extends Sprite {
        public StampSprite(float pX, float pY, TextureRegion pTextureRegion) {
            super(pX, pY, pTextureRegion);
        }

        public void drawNow(GL10 pGL, Camera pCamera) {
            super.onManagedDraw(pGL, pCamera);
        }
    }
}
