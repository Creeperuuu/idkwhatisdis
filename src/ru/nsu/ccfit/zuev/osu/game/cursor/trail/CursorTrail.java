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
    // Add this inside the CursorTrail class
    private static final float[][] PASTEL_COLORS = {
            {0.98f, 0.75f, 0.75f}, // Pastel Red
            {0.98f, 0.90f, 0.75f}, // Pastel Yellow
            {0.75f, 0.98f, 0.75f}, // Pastel Green
            {0.75f, 0.90f, 0.98f}, // Pastel Blue
            {0.85f, 0.75f, 0.98f} // Pastel Purple
    };

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

        float distMoved = (float) Math.hypot(x - lastInputX, y - lastInputY);
        if (distMoved < 0.05f) return; // Don't process if the cursor barely moved
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
        int samples = 20; // Increased samples for smoother length estimation
        float[] tx = new float[samples + 1];
        float[] ty = new float[samples + 1];
        float[] dists = new float[samples + 1];
        float totalPathLength = 0;

        // 1. Pre-calculate the points and the cumulative distance along the curve
        tx[0] = x0;
        ty[0] = y0;
        dists[0] = 0;

        for (int i = 1; i <= samples; i++) {
            float t = i / (float) samples;
            float u = 1 - t;
            tx[i] = (u * u * x0) + (2 * u * t * cx) + (t * t * x1);
            ty[i] = (u * u * y0) + (2 * u * t * cy) + (t * t * y1);

            // Accumulate distance from the previous sample point
            totalPathLength += (float) Math.hypot(tx[i] - tx[i - 1], ty[i] - ty[i - 1]);
            dists[i] = totalPathLength;
        }

        // 2. Walk the curve using the TRAIL_STEP_SIZE and the distanceRemainder
        // 'd' is the distance along the curve where the NEXT point should be
        float d = TRAIL_STEP_SIZE - distanceRemainder;

        int sampleIdx = 0;
        while (d <= totalPathLength) {
            // Find which sample segment 'd' falls into
            while (sampleIdx < samples && dists[sampleIdx + 1] < d) {
                sampleIdx++;
            }

            // Interpolate within the sample segment for higher precision
            float segStartDist = dists[sampleIdx];
            float segEndDist = dists[sampleIdx + 1];
            float segRatio = (d - segStartDist) / (segEndDist - segStartDist);

            float qx = tx[sampleIdx] + (tx[sampleIdx + 1] - tx[sampleIdx]) * segRatio;
            float qy = ty[sampleIdx] + (ty[sampleIdx + 1] - ty[sampleIdx]) * segRatio;

            pushPoint(qx, qy);
            d += TRAIL_STEP_SIZE;
        }

        // 3. Update the remainder for the next segment call
        // We subtract the distance traveled to the last pushed point from the total
        distanceRemainder = totalPathLength - (d - TRAIL_STEP_SIZE);
    }

    @Override
    protected void onManagedDraw(GL10 pGL, Camera pCamera) {
        currentTime += 0.016f * GameHelper.getSpeedMultiplier();
        if (count == 0) return;

        float currentLifetime = TRAIL_LIFETIME * GameHelper.getSpeedMultiplier();

        for (int i = 0; i < count; i++) {
            int idx = (head - 1 - i);
            if (idx < 0) idx += TRAIL_CAPACITY;

            float age = currentTime - pTime[idx];
            if (age > currentLifetime) break;

            // Calculate gradient color
            float progress = age / currentLifetime; // 0.0 to 1.0
            float[] color = getPastelGradient(progress);
            stamp.setColor(color[0], color[1], color[2]);

            float fadeLifeRatio = Math.max(0f, 1f - (age / (currentLifetime * FADE_DURATION_RATIO)));
            float scaleLifeRatio = Math.max(0f, 1f - (age / (currentLifetime * SCALE_DURATION_RATIO)));

            stamp.setPosition(px[idx] + offsetX, py[idx] + offsetY);
            stamp.setScale(baseSize * scaleLifeRatio);
            stamp.setAlpha(MAX_OPACITY * fadeLifeRatio);

            stamp.drawNow(pGL, pCamera);
        }
    }

    private float[] getPastelGradient(float progress) {
        // Maps progress (0 to 1) to the colors array
        float scaled = progress * (PASTEL_COLORS.length - 1);
        int index = (int) scaled;
        float lerp = scaled - index;

        if (index >= PASTEL_COLORS.length - 1) return PASTEL_COLORS[PASTEL_COLORS.length - 1];

        float[] c1 = PASTEL_COLORS[index];
        float[] c2 = PASTEL_COLORS[index + 1];

        return new float[]{
                c1[0] + (c2[0] - c1[0]) * lerp,
                c1[1] + (c2[1] - c1[1]) * lerp,
                c1[2] + (c2[2] - c1[2]) * lerp
        };
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
