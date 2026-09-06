package com.example.flappybird;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * A self-contained Flappy Bird clone rendered with the Canvas API.
 *
 * World model: the bird stays at a fixed screen x and only moves vertically. The whole
 * world (pipes, ground texture) scrolls left. A pipe pair is stored by its absolute
 * world x; its screen x is worldX - scrollX.
 */
public class GameView extends View {

    // ---- tunable game feel -------------------------------------------------
    private static final float BIRD_D_FRACTION   = 0.150f; // bird diameter as fraction of screen width
    private static final float BIRD_X_FRACTION   = 0.330f; // fixed bird screen position
    private static final float PIPE_W_FRACTION   = 0.140f; // pipe column width
    private static final float GROUND_H_FRACTION = 0.100f; // ground strip height
    private static final float GAP_BIRD_RATIO    = 4.80f;  // vertical pipe gap = ratio x bird diameter
    private static final float PIPE_SPACING_W    = 0.56f;  // horizontal gap between pipes = fraction of width
    private static final float FIRST_PIPE_W      = 1.20f;  // distance of first pipe = fraction of width
    private static final float MAX_FALL_BIRD     = 12.0f;  // terminal fall = bird diameters / second

    // ---- adjustable gameplay settings (persisted) ---------------------------
    private static final float DEFAULT_SPEED   = 0.36f;
    private static final float DEFAULT_GRAVITY = 8.20f;
    private static final float DEFAULT_FLAP    = 5.20f;
    private static final float MIN_SPEED   = 0.18f, MAX_SPEED   = 0.70f;
    private static final float MIN_GRAVITY = 4.0f,  MAX_GRAVITY = 16.0f;
    private static final float MIN_FLAP    = 3.0f,  MAX_FLAP    = 9.0f;
    private static final int S_SPEED = 0, S_GRAVITY = 1, S_FLAP = 2;
    private static final float MIN_PIPE_BIRD     = 1.60f;  // min pipe stub beyond gap = bird diameters
    private static final float BIRD_HIT_SLACK    = 0.78f;  // collision uses this fraction of bird radius

    // ---- palette -----------------------------------------------------------
    private static final int SKY_TOP    = Color.rgb(0x4e, 0xc0, 0xca);
    private static final int SKY_BOTTOM = Color.rgb(0x9d, 0xe6, 0xe9);
    private static final int CLOUD      = Color.argb(210, 255, 255, 255);
    private static final int GROUND_MAIN = Color.rgb(0xde, 0xd8, 0x95);
    private static final int GROUND_SHADE = Color.rgb(0xc7, 0xbf, 0x7a);
    private static final int GRASS      = Color.rgb(0x6d, 0xb0, 0x30);
    private static final int GRASS_DARK = Color.rgb(0x58, 0x94, 0x25);
    private static final int PIPE_BODY  = Color.rgb(0x73, 0xbf, 0x2e);
    private static final int PIPE_HI    = Color.rgb(0x97, 0xdd, 0x50);
    private static final int PIPE_EDGE  = Color.rgb(0x2c, 0x63, 0x13);
    private static final int BIRD_BODY  = Color.rgb(0xff, 0xd5, 0x00);
    private static final int BIRD_BELLY = Color.rgb(0xff, 0xe8, 0x80);
    private static final int BIRD_WING  = Color.rgb(0xf5, 0xa6, 0x23);
    private static final int BIRD_WING_EDGE = Color.rgb(0xb9, 0x6e, 0x0f);
    private static final int BIRD_BEAK  = Color.rgb(0xf0, 0x84, 0x08);
    private static final int BIRD_OUTLINE = Color.rgb(0x3a, 0x2a, 0x17);
    private static final int SCORE_COLOR = Color.rgb(0xfb, 0xfb, 0xfb);
    private static final int TEXT_DARK  = Color.rgb(0x35, 0x35, 0x35);

    private enum State { READY, PLAYING, GAME_OVER, SETTINGS }

    private final Paint fill  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Random random = new Random();

    private final List<Pipe> pipes = new ArrayList<>();
    private final List<Cloud> clouds = new ArrayList<>();
    private final RectF scratch = new RectF();

    private final SharedPreferences prefs;

    private State state = State.READY;
    private State prevState = State.READY;   // state to restore when leaving settings

    // view / layout metrics (screen pixels)
    private int vw, vh;
    private float groundY, birdX, birdY, birdR;
    private float pipeW, capW, capH, gapHalf, minPipeLen;
    private float accel, flapV, maxFallV, scrollV;
    private float spacingX;

    // adjustable settings (persisted)
    private float speedSetting;
    private float gravitySetting;
    private float flapSetting;
    private int dragSetting = -1;   // slider row currently being dragged (-1 = none)

    // runtime state
    private float birdVy;
    private float birdAngle;        // current tilt (deg), smoothed
    private float scrollX;          // distance world has scrolled
    private float nextSpawnX;       // absolute world x of next pipe pair
    private int score, best;
    private float wingT;            // anim clock
    private float runT;             // clock since round start (used for READY bobbing)
    private boolean allowRestart;
    private boolean landed;         // bird has come to rest on the ground after death
    private float readyBobBaseY;
    private float settleT;          // time bird has rested on ground after death
    private long lastFrame;
    private boolean ticking;

    private final Runnable frame = new Runnable() {
        @Override public void run() {
            if (!ticking) return;
            long now = SystemClock.uptimeMillis();
            float dt = lastFrame == 0 ? 0.016f : Math.min((now - lastFrame) / 1000f, 0.05f);
            lastFrame = now;
            step(dt);
            invalidate();
            postOnAnimation(this);
        }
    };

    public GameView(Context context) {
        super(context);
        prefs = context.getSharedPreferences("flappy_bird", Context.MODE_PRIVATE);
        best = prefs.getInt("best", 0);
        speedSetting = prefs.getFloat("speed", DEFAULT_SPEED);
        gravitySetting = prefs.getFloat("gravity", DEFAULT_GRAVITY);
        flapSetting = prefs.getFloat("flap", DEFAULT_FLAP);
    }

    // ------------------------------------------------------------------ pipes
    private static class Pipe {
        float worldX;   // left edge, world coords
        float gapTop;   // y of bottom of top pipe
        float gapBot;   // y of top of bottom pipe
        boolean scored;
        Pipe(float wx, float gt, float gb) { worldX = wx; gapTop = gt; gapBot = gb; }
    }

    private static class Cloud {
        float x, y, s, v;
        Cloud(float x, float y, float s, float v) { this.x = x; this.y = y; this.s = s; this.v = v; }
    }

    // ------------------------------------------------------------------ setup
    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (w <= 0 || h <= 0) return;
        vw = w; vh = h;
        computeMetrics();
        buildClouds();
        if (state == State.READY && birdY == 0) {
            startReady();
        }
    }

    private void computeMetrics() {
        birdR  = vw * BIRD_D_FRACTION * 0.5f;
        float birdD = birdR * 2f;
        birdX  = vw * BIRD_X_FRACTION;
        groundY = vh * (1f - GROUND_H_FRACTION);
        pipeW  = vw * PIPE_W_FRACTION;
        capW   = pipeW * 1.12f;
        capH   = birdD * 0.55f;
        gapHalf = (GAP_BIRD_RATIO * birdD) * 0.5f;
        minPipeLen = MIN_PIPE_BIRD * birdD;
        accel  = gravitySetting * birdD;
        flapV  = flapSetting * birdD;
        maxFallV = MAX_FALL_BIRD * birdD;
        scrollV = speedSetting * vw;
        spacingX = PIPE_SPACING_W * vw;
    }

    private void buildClouds() {
        clouds.clear();
        for (int i = 0; i < 6; i++) {
            float s = (0.5f + random.nextFloat() * 0.8f) * birdR * 1.6f;
            clouds.add(new Cloud(random.nextFloat() * vw,
                    vh * (0.06f + random.nextFloat() * 0.28f),
                    s, 0.25f + random.nextFloat() * 0.4f));
        }
    }

    private void startReady() {
        readyBobBaseY = groundY * 0.62f;
        birdY = readyBobBaseY;
        birdVy = 0;
        runT = 0;
    }

    private void startPlaying() {
        score = 0;
        pipes.clear();
        scrollX = 0;
        lastFrame = 0;
        nextSpawnX = FIRST_PIPE_W * vw;   // grace period before first pipe arrives
        readyBobBaseY = groundY * 0.62f;
        birdY = readyBobBaseY;
        birdVy = -flapV * 0.8f;          // a gentle hop starts the round
        birdAngle = 0;
        allowRestart = false;
        landed = false;
        state = State.PLAYING;
    }

    private void gameOver() {
        state = State.GAME_OVER;
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        if (score > best) {
            best = score;
            prefs.edit().putInt("best", best).apply();
        }
        runT = 0;
        settleT = 0;
        landed = false;
        allowRestart = false;
    }

    // ------------------------------------------------------------------ loop
    private void step(float dt) {
        runT += dt;
        wingT += dt;

        if (state == State.PLAYING) {
            // physics
            birdVy = Math.min(birdVy + accel * dt, maxFallV);
            birdY += birdVy * dt;
            scrollX += scrollV * dt;

            float target = birdVy < 0 ? -30f : Math.min(85f, 40f * (birdVy / maxFallV));
            smoothAngle(target, dt);

            // spawn pipes
            while (nextSpawnX - scrollX < vw + pipeW) {
                spawnPipe(nextSpawnX);
                nextSpawnX += spacingX;
            }

            // move + cull + collisions + score
            Iterator<Pipe> it = pipes.iterator();
            while (it.hasNext()) {
                Pipe p = it.next();
                float sx = p.worldX - scrollX;
                if (sx + capW < -pipeW) {
                    it.remove();
                } else {
                    if (!p.scored && sx + capW < birdX) {
                        p.scored = true;
                        score++;
                    }
                    if (hitPipe(p)) {
                        gameOver();
                        return;
                    }
                }
            }

            if (birdY + birdR >= groundY) {
                birdY = groundY - birdR;
                gameOver();
            }
        } else if (state == State.READY) {
            scrollX += scrollV * dt * 0.55f;
            birdY = readyBobBaseY + (float) Math.sin(runT * 2.6f) * birdR * 0.9f;
            birdAngle *= 0.9f;
        } else if (state == State.SETTINGS) {
            // static; clouds keep drifting via updateClouds below
        } else { // GAME_OVER
            if (!landed) {
                // bird keeps falling until it rests on the ground
                birdVy = Math.min(birdVy + accel * dt, maxFallV);
                birdY += birdVy * dt;
                float target = Math.min(85f, 40f * (birdVy / maxFallV));
                smoothAngle(target, dt);
                if (birdY + birdR >= groundY) {
                    birdY = groundY - birdR;
                    birdVy = 0;
                    landed = true;
                    settleT = 0;
                }
            } else if (settleT > 0.5f) {
                allowRestart = true;
            } else {
                settleT += dt;
            }
        }

        updateClouds(dt);
    }

    private void smoothAngle(float target, float dt) {
        birdAngle += (target - birdAngle) * Math.min(1f, 16f * dt);
    }

    private void updateClouds(float dt) {
        for (Cloud c : clouds) {
            c.x -= c.v * vw * dt * 0.02f;
            if (c.x < -c.s * 3) {
                c.x = vw + c.s;
                c.y = vh * (0.06f + random.nextFloat() * 0.28f);
            }
        }
    }

    private void spawnPipe(float worldX) {
        float low = minPipeLen + gapHalf;
        float high = groundY - gapHalf - minPipeLen;
        if (high < low) high = low;
        float gapCenter = low + random.nextFloat() * (high - low);
        pipes.add(new Pipe(worldX, gapCenter - gapHalf, gapCenter + gapHalf));
    }

    private boolean hitPipe(Pipe p) {
        float sx = p.worldX - scrollX;
        float margin = (capW - pipeW) * 0.5f;
        float hitR = birdR * BIRD_HIT_SLACK;
        // collide with cap-width column against top pipe
        return circleHitsRect(birdX, birdY, hitR, sx - margin, 0, sx - margin + capW, p.gapTop)
                || circleHitsRect(birdX, birdY, hitR, sx - margin, p.gapBot, sx - margin + capW, groundY);
    }

    private boolean circleHitsRect(float cx, float cy, float r,
                                   float rx1, float ry1, float rx2, float ry2) {
        float nx = Math.max(rx1, Math.min(cx, rx2));
        float ny = Math.max(ry1, Math.min(cy, ry2));
        float dx = cx - nx, dy = cy - ny;
        return dx * dx + dy * dy <= r * r;
    }

    // ----------------------------------------------------------------- input
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        float x = event.getX();
        float y = event.getY();

        if (state == State.SETTINGS) {
            return handleSettingsTouch(action, x, y);
        }
        if (action != MotionEvent.ACTION_DOWN) return true;

        switch (state) {
            case READY:
                if (inSettingsButton(x, y)) {
                    prevState = state;
                    state = State.SETTINGS;
                } else {
                    startPlaying();
                }
                break;
            case PLAYING:
                birdVy = -flapV;
                break;
            case GAME_OVER:
                if (inSettingsButton(x, y)) {
                    prevState = state;
                    state = State.SETTINGS;
                } else if (allowRestart) {
                    startPlaying();   // one tap immediately starts a new round
                }
                break;
        }
        return true;
    }

    private boolean handleSettingsTouch(int action, float x, float y) {
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                if (inBackButton(x, y)) {
                    state = prevState;
                    dragSetting = -1;
                } else if (inResetButton(x, y)) {
                    resetSettings();
                } else {
                    dragSetting = settingRowAt(x, y);
                    if (dragSetting >= 0) {
                        updateSettingFromX(x);
                    }
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                if (dragSetting >= 0) {
                    updateSettingFromX(x);
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragSetting = -1;
                return true;
        }
        return true;
    }

    // ----------------------------------------------------------------- draw
    @Override
    protected void onDraw(Canvas canvas) {
        if (vw == 0) return;
        drawSky(canvas);
        drawClouds(canvas);
        drawPipes(canvas);
        drawGround(canvas);
        drawBird(canvas);
        if (state == State.SETTINGS) {
            drawSettings(canvas);
        } else {
            drawHud(canvas);
            if (state == State.READY || state == State.GAME_OVER) {
                drawButton(canvas, settingsBtnLeft(), settingsBtnTop(),
                        settingsBtnRight(), settingsBtnBottom(), "设置");
            }
        }
    }

    private void drawSky(Canvas canvas) {
        LinearGradient sky = new LinearGradient(0, 0, 0, groundY, SKY_TOP, SKY_BOTTOM,
                Shader.TileMode.CLAMP);
        fill.setShader(sky);
        canvas.drawRect(0, 0, vw, groundY, fill);
        fill.setShader(null);
    }

    private void drawClouds(Canvas canvas) {
        fill.setColor(CLOUD);
        for (Cloud c : clouds) {
            canvas.save();
            canvas.translate(c.x, c.y);
            canvas.scale(c.s, c.s);
            float r = 1f;
            canvas.drawCircle(0, 0, r, fill);
            canvas.drawCircle(1.1f * r, 0.25f * r, 0.8f * r, fill);
            canvas.drawCircle(0.7f * r, -0.55f * r, 0.62f * r, fill);
            canvas.drawCircle(1.6f * r, -0.15f * r, 0.55f * r, fill);
            canvas.restore();
        }
    }

    private void drawPipes(Canvas canvas) {
        for (Pipe p : pipes) {
            float sx = p.worldX - scrollX;
            float margin = (capW - pipeW) * 0.5f;

            // top pipe (hangs from ceiling down to p.gapTop)
            drawPipeColumn(canvas, sx, p.gapTop, false, margin);
            // bottom pipe (rises from ground up to p.gapBot)
            drawPipeColumn(canvas, sx, p.gapBot, true, margin);
        }
    }

    private void drawPipeColumn(Canvas canvas, float left, float openY, boolean extendsDownward,
                                float margin) {
        // extendsDownward == true : bottom pipe occupying [openY, groundY]
        // extendsDownward == false: top pipe occupying [0, openY]
        float y0 = extendsDownward ? openY : 0;
        float y1 = extendsDownward ? groundY : openY;
        // cap band hugs the gap-side opening
        float capTop = extendsDownward ? openY : openY - capH;
        float capBot = extendsDownward ? openY + capH : openY;

        fill.setColor(PIPE_BODY);
        canvas.drawRect(left, y0, left + pipeW, y1, fill);
        // soft highlight
        fill.setColor(PIPE_HI);
        canvas.drawRect(left + pipeW * 0.12f, y0, left + pipeW * 0.34f, y1, fill);
        // outline around the whole pipe silhouette
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(edgeWidth());
        edge.setColor(PIPE_EDGE);
        canvas.drawRect(left - margin, y0, left - margin + capW, y1, edge);
        // cap band
        fill.setColor(PIPE_BODY);
        canvas.drawRect(left - margin, capTop, left - margin + capW, capBot, fill);
        fill.setColor(PIPE_HI);
        canvas.drawRect(left - margin + pipeW * 0.12f, capTop,
                left - margin + pipeW * 0.34f, capBot, fill);
        edge.setStyle(Paint.Style.FILL);
    }

    private float edgeWidth() {
        return Math.max(3f, birdR * 0.12f);
    }

    private void drawGround(Canvas canvas) {
        // grass band
        fill.setColor(GRASS);
        canvas.drawRect(0, groundY, vw, groundY + birdR * 0.55f, fill);
        // soil
        fill.setColor(GROUND_MAIN);
        canvas.drawRect(0, groundY + birdR * 0.55f, vw, vh, fill);
        // scrolling seams in soil
        float tile = vw * 0.09f;
        fill.setColor(GROUND_SHADE);
        float off = tile - (scrollX % tile);
        for (float x = off; x < vw + tile; x += tile) {
            canvas.drawRect(x, groundY + birdR * 0.55f, x + tile * 0.12f, vh, fill);
        }
        // seams in grass
        fill.setColor(GRASS_DARK);
        float tileG = vw * 0.055f;
        float offG = tileG - (scrollX % tileG);
        for (float x = offG; x < vw + tileG; x += tileG) {
            canvas.drawRect(x, groundY, x + tileG * 0.16f, groundY + birdR * 0.55f, fill);
        }
    }

    private void drawBird(Canvas canvas) {
        canvas.save();
        canvas.translate(birdX, birdY);
        canvas.rotate(birdAngle);

        float R = birdR;
        float ow = Math.max(2.5f, R * 0.09f);   // cartoon outline width
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(ow);
        edge.setColor(BIRD_OUTLINE);

        // tail feathers (stick out behind the body, mostly hidden by it)
        fill.setColor(BIRD_WING_EDGE);
        Path tailA = new Path();
        tailA.moveTo(-0.45f * R, 0.10f * R);
        tailA.lineTo(-1.06f * R, -0.26f * R);
        tailA.lineTo(-0.98f * R, 0.20f * R);
        tailA.close();
        canvas.drawPath(tailA, fill);
        canvas.drawPath(tailA, edge);
        Path tailB = new Path();
        tailB.moveTo(-0.42f * R, 0.34f * R);
        tailB.lineTo(-1.04f * R, 0.16f * R);
        tailB.lineTo(-0.94f * R, 0.50f * R);
        tailB.close();
        canvas.drawPath(tailB, fill);
        canvas.drawPath(tailB, edge);

        // body
        fill.setColor(BIRD_BODY);
        canvas.drawCircle(0f, 0f, R, fill);
        canvas.drawCircle(0f, 0f, R, edge);

        // belly (lower-left, kept inside the body circle)
        fill.setColor(BIRD_BELLY);
        scratch.set(-0.88f * R, -0.18f * R, 0.70f * R, 0.97f * R);
        canvas.drawOval(scratch, fill);

        // wing (side view, flaps around its shoulder)
        float flapSwing = (float) Math.sin(wingT * 15f);
        canvas.save();
        canvas.translate(0.06f * R, 0.18f * R);
        canvas.rotate(flapSwing * 32f - 4f);
        fill.setColor(BIRD_WING);
        Path wing = new Path();
        wing.moveTo(0.32f * R, -0.04f * R);
        wing.cubicTo(0.06f * R, -0.56f * R, -0.90f * R, -0.42f * R, -1.02f * R, -0.02f * R);
        wing.cubicTo(-0.92f * R, 0.30f * R, -0.16f * R, 0.44f * R, 0.30f * R, 0.22f * R);
        wing.close();
        canvas.drawPath(wing, fill);
        canvas.drawPath(wing, edge);
        // feather seam
        edge.setStrokeWidth(Math.max(1.6f, R * 0.05f));
        edge.setColor(BIRD_WING_EDGE);
        canvas.drawLine(-0.92f * R, 0.02f * R, 0.14f * R, 0.06f * R, edge);
        canvas.restore();

        // eye (big, forward-facing, with a dark ring)
        edge.setStrokeWidth(Math.max(1.6f, R * 0.06f));
        edge.setColor(BIRD_OUTLINE);
        float eyeX = 0.28f * R, eyeY = -0.42f * R, eyeR = 0.30f * R;
        fill.setColor(Color.WHITE);
        canvas.drawCircle(eyeX, eyeY, eyeR, fill);
        canvas.drawCircle(eyeX, eyeY, eyeR, edge);
        fill.setColor(BIRD_OUTLINE);
        canvas.drawCircle(eyeX + 0.09f * R, eyeY + 0.02f * R, 0.15f * R, fill);
        fill.setColor(Color.WHITE);
        canvas.drawCircle(eyeX + 0.15f * R, eyeY - 0.05f * R, 0.05f * R, fill);

        // beak (upper + lower mandible)
        fill.setColor(BIRD_BEAK);
        Path up = new Path();
        up.moveTo(0.66f * R, -0.24f * R);
        up.lineTo(1.46f * R, -0.02f * R);
        up.lineTo(0.66f * R, 0.12f * R);
        up.close();
        canvas.drawPath(up, fill);
        canvas.drawPath(up, edge);
        Path lo = new Path();
        lo.moveTo(0.72f * R, 0.14f * R);
        lo.lineTo(1.24f * R, 0.22f * R);
        lo.lineTo(0.78f * R, 0.44f * R);
        lo.close();
        canvas.drawPath(lo, fill);
        canvas.drawPath(lo, edge);

        canvas.restore();
        edge.setStyle(Paint.Style.FILL);
    }

    // ------------------------------------------------------------------- HUD
    private void initTextPaints() {
        textFill.setColor(SCORE_COLOR);
        textFill.setStyle(Paint.Style.FILL);
        textFill.setTextAlign(Paint.Align.CENTER);
        textFill.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        textStroke.setColor(Color.rgb(0x35, 0x5f, 0x3a));
        textStroke.setStyle(Paint.Style.STROKE);
        textStroke.setTextAlign(Paint.Align.CENTER);
        textStroke.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
    }

    private void drawHud(Canvas canvas) {
        initTextPaints();
        if (state == State.PLAYING) {
            float size = vh * 0.13f;
            textFill.setTextSize(size);
            textStroke.setTextSize(size);
            textStroke.setStrokeWidth(size * 0.14f);
            textStroke.setColor(TEXT_DARK);
            drawTextCentered(canvas, String.valueOf(score), vw * 0.5f, vh * 0.16f);
        } else if (state == State.READY) {
            float t1 = vh * 0.070f;
            textFill.setTextSize(t1);
            textStroke.setTextSize(t1);
            textStroke.setStrokeWidth(t1 * 0.14f);
            textStroke.setColor(TEXT_DARK);
            drawTextCentered(canvas, "像素鸟", vw * 0.5f, vh * 0.16f);

            float t2 = vh * 0.048f;
            textFill.setTextSize(t2);
            textStroke.setTextSize(t2);
            textStroke.setStrokeWidth(t2 * 0.12f);
            drawTextCentered(canvas, "点击开始", vw * 0.5f, vh * 0.52f);
            if (best > 0) {
                drawTextCentered(canvas, "最高分  " + best, vw * 0.5f, vh * 0.52f + t2 * 1.6f);
            }
        } else { // GAME_OVER
            // dim panel
            fill.setColor(Color.argb(90, 0, 0, 0));
            canvas.drawRoundRect(vw * 0.08f, vh * 0.28f, vw * 0.92f, vh * 0.72f,
                    birdR, birdR, fill);

            float t1 = vh * 0.062f;
            textFill.setTextSize(t1);
            textStroke.setTextSize(t1);
            textStroke.setStrokeWidth(t1 * 0.13f);
            textStroke.setColor(TEXT_DARK);
            drawTextCentered(canvas, "游戏结束", vw * 0.5f, vh * 0.37f);

            float t2 = vh * 0.045f;
            textFill.setTextSize(t2);
            textStroke.setTextSize(t2);
            textStroke.setStrokeWidth(t2 * 0.12f);
            drawTextCentered(canvas, "得分  " + score, vw * 0.5f, vh * 0.46f);
            drawTextCentered(canvas, "最高分  " + best, vw * 0.5f, vh * 0.54f);

            if (allowRestart && (runT % 1.0f) < 0.5f) {
                float t3 = vh * 0.042f;
                textFill.setTextSize(t3);
                textStroke.setTextSize(t3);
                textStroke.setStrokeWidth(t3 * 0.12f);
                drawTextCentered(canvas, "点击重新开始", vw * 0.5f, vh * 0.68f);
            }
        }
    }

    private void drawTextCentered(Canvas canvas, String s, float cx, float cy) {
        canvas.drawText(s, cx, cy, textStroke);
        canvas.drawText(s, cx, cy, textFill);
    }

    // ------------------------------------------------------------- settings
    private float panelLeft()   { return vw * 0.07f; }
    private float panelRight()  { return vw * 0.93f; }
    private float panelTop()    { return vh * 0.24f; }
    private float panelBottom() { return vh * 0.78f; }
    private float trackLeft()   { return vw * 0.34f; }
    private float trackRight()  { return vw * 0.60f; }
    private float settingRowY(int row) { return panelTop() + vh * 0.16f + row * vh * 0.10f; }
    private float settingRowHitHalf()  { return vh * 0.048f; }

    private float backLeft()   { return panelLeft() + vw * 0.05f; }
    private float backRight()  { return panelLeft() + vw * 0.38f; }
    private float btnTop()     { return panelBottom() - vh * 0.11f; }
    private float btnBottom()  { return panelBottom() - vh * 0.04f; }
    private float resetLeft()  { return panelRight() - vw * 0.38f; }
    private float resetRight() { return panelRight() - vw * 0.05f; }

    private float settingsBtnRight()  { return vw - vw * 0.03f; }
    private float settingsBtnTop()    { return vh * 0.03f; }
    private float settingsBtnBottom() { return settingsBtnTop() + vh * 0.062f; }
    private float settingsBtnLeft()   { return settingsBtnRight() - vw * 0.16f; }

    private boolean inBackButton(float x, float y) {
        return x >= backLeft() && x <= backRight() && y >= btnTop() && y <= btnBottom();
    }
    private boolean inResetButton(float x, float y) {
        return x >= resetLeft() && x <= resetRight() && y >= btnTop() && y <= btnBottom();
    }
    private boolean inSettingsButton(float x, float y) {
        return x >= settingsBtnLeft() && x <= settingsBtnRight()
                && y >= settingsBtnTop() && y <= settingsBtnBottom();
    }

    private int settingRowAt(float x, float y) {
        float half = settingRowHitHalf();
        for (int i = 0; i < 3; i++) {
            float cy = settingRowY(i);
            if (y >= cy - half && y <= cy + half) return i;
        }
        return -1;
    }

    private float settingValue(int idx) {
        switch (idx) {
            case S_SPEED:   return speedSetting;
            case S_GRAVITY: return gravitySetting;
            default:        return flapSetting;
        }
    }
    private float settingMin(int idx) {
        switch (idx) {
            case S_SPEED:   return MIN_SPEED;
            case S_GRAVITY: return MIN_GRAVITY;
            default:        return MIN_FLAP;
        }
    }
    private float settingMax(int idx) {
        switch (idx) {
            case S_SPEED:   return MAX_SPEED;
            case S_GRAVITY: return MAX_GRAVITY;
            default:        return MAX_FLAP;
        }
    }
    private String settingLabel(int idx) {
        switch (idx) {
            case S_SPEED:   return "速度";
            case S_GRAVITY: return "重力";
            default:        return "拍翅力度";
        }
    }

    private float clampf(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private void setSetting(int idx, float v) {
        float c = clampf(v, settingMin(idx), settingMax(idx));
        switch (idx) {
            case S_SPEED:   speedSetting = c; break;
            case S_GRAVITY: gravitySetting = c; break;
            case S_FLAP:    flapSetting = c; break;
        }
        saveSettings();
        computeMetrics();
    }

    private void updateSettingFromX(float x) {
        float t = clampf((x - trackLeft()) / (trackRight() - trackLeft()), 0f, 1f);
        int idx = dragSetting;
        setSetting(idx, settingMin(idx) + t * (settingMax(idx) - settingMin(idx)));
    }

    private void saveSettings() {
        prefs.edit()
                .putFloat("speed", speedSetting)
                .putFloat("gravity", gravitySetting)
                .putFloat("flap", flapSetting)
                .apply();
    }

    private void resetSettings() {
        speedSetting = DEFAULT_SPEED;
        gravitySetting = DEFAULT_GRAVITY;
        flapSetting = DEFAULT_FLAP;
        saveSettings();
        computeMetrics();
    }

    private void drawSettings(Canvas canvas) {
        fill.setColor(Color.argb(130, 0, 0, 0));
        canvas.drawRect(0, 0, vw, vh, fill);

        fill.setColor(Color.rgb(0xff, 0xfb, 0xef));
        canvas.drawRoundRect(panelLeft(), panelTop(), panelRight(), panelBottom(),
                birdR, birdR, fill);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(edgeWidth());
        edge.setColor(TEXT_DARK);
        canvas.drawRoundRect(panelLeft(), panelTop(), panelRight(), panelBottom(),
                birdR, birdR, edge);
        edge.setStyle(Paint.Style.FILL);

        initTextPaints();
        drawPanelTitle(canvas, "设置", panelTop() + vh * 0.07f, vw * 0.075f);
        for (int i = 0; i < 3; i++) drawSettingRow(canvas, i);
        drawButton(canvas, backLeft(), btnTop(), backRight(), btnBottom(), "返回");
        drawButton(canvas, resetLeft(), btnTop(), resetRight(), btnBottom(), "恢复默认");
    }

    private void drawPanelTitle(Canvas canvas, String s, float cy, float size) {
        textFill.setColor(TEXT_DARK);
        textFill.setTextSize(size);
        textStroke.setColor(Color.rgb(0xff, 0xfb, 0xef));
        textStroke.setTextSize(size);
        textStroke.setStrokeWidth(size * 0.12f);
        drawTextCentered(canvas, s, vw * 0.5f, cy);
    }

    private void drawSettingRow(Canvas canvas, int idx) {
        float cy = settingRowY(idx);
        float size = vw * 0.042f;

        // label
        textFill.setColor(TEXT_DARK);
        textFill.setTextSize(size);
        textFill.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(settingLabel(idx), panelLeft() + vw * 0.03f, cy + size * 0.35f, textFill);

        // track + fill
        float halfH = vh * 0.010f;
        fill.setColor(Color.rgb(0xdd, 0xd8, 0xc8));
        canvas.drawRoundRect(trackLeft(), cy - halfH, trackRight(), cy + halfH,
                halfH, halfH, fill);
        float t = (settingValue(idx) - settingMin(idx)) / (settingMax(idx) - settingMin(idx));
        float knobX = trackLeft() + t * (trackRight() - trackLeft());
        fill.setColor(Color.rgb(0x6d, 0xb0, 0x30));
        canvas.drawRoundRect(trackLeft(), cy - halfH, knobX, cy + halfH, halfH, halfH, fill);

        // knob
        float kr = vh * 0.020f;
        fill.setColor(Color.WHITE);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(edgeWidth());
        edge.setColor(TEXT_DARK);
        canvas.drawCircle(knobX, cy, kr, fill);
        canvas.drawCircle(knobX, cy, kr, edge);
        edge.setStyle(Paint.Style.FILL);

        // value
        textFill.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(String.format(Locale.US, "%.2f", settingValue(idx)),
                panelRight() - vw * 0.03f, cy + size * 0.35f, textFill);
        textFill.setTextAlign(Paint.Align.CENTER);
    }

    private void drawButton(Canvas canvas, float l, float t, float r, float b, String label) {
        float rad = vh * 0.02f;
        fill.setColor(Color.rgb(0x6d, 0xb0, 0x30));
        canvas.drawRoundRect(l, t, r, b, rad, rad, fill);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(edgeWidth());
        edge.setColor(TEXT_DARK);
        canvas.drawRoundRect(l, t, r, b, rad, rad, edge);
        edge.setStyle(Paint.Style.FILL);

        float size = vw * 0.042f;
        textFill.setColor(Color.WHITE);
        textFill.setTextSize(size);
        textStroke.setColor(TEXT_DARK);
        textStroke.setTextSize(size);
        textStroke.setStrokeWidth(size * 0.12f);
        drawTextCentered(canvas, label, (l + r) / 2f, (t + b) / 2f + size * 0.35f);
    }

    // --------------------------------------------------------------- lifecycle
    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        ticking = true;
        lastFrame = 0;
        postOnAnimation(frame);
    }

    @Override
    protected void onDetachedFromWindow() {
        ticking = false;
        removeCallbacks(frame);
        super.onDetachedFromWindow();
    }
}
