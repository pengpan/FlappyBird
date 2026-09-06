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
 * 一个使用 Canvas API 绘制的自包含 Flappy Bird（像素鸟）复刻。
 *
 * 世界模型：小鸟固定在屏幕的水平位置，只做竖直方向的移动，整个场景（管道、
 * 地面纹理）向左滚动。每根管道记录的是它的绝对世界坐标 x，
 * 其屏幕坐标 = worldX - scrollX。
 */
public class GameView extends View {

    // ---- 手感相关常量（决定游戏节奏与判定，与屏幕尺寸相乘得到像素值） --------------
    private static final float BIRD_D_FRACTION   = 0.150f; // 小鸟直径占屏幕宽的比例
    private static final float BIRD_X_FRACTION   = 0.330f; // 小鸟固定的屏幕横向位置
    private static final float PIPE_W_FRACTION   = 0.140f; // 管道柱体宽度
    private static final float GROUND_H_FRACTION = 0.100f; // 地面条高度
    private static final float GAP_BIRD_RATIO    = 4.80f;  // 上、下管道间距 = 该比例 × 小鸟直径
    private static final float PIPE_SPACING_W    = 0.56f;  // 相邻管道水平间隔 = 该比例 × 屏宽
    private static final float FIRST_PIPE_W      = 1.20f;  // 第一根管道出现距离 = 该比例 × 屏宽
    private static final float MAX_FALL_BIRD     = 12.0f;  // 终端下落速度 = 小鸟直径 / 秒

    // ---- 可调节玩法参数（已持久化保存） ----------------------------------------
    private static final float DEFAULT_SPEED   = 0.36f;   // 默认滚动速度
    private static final float DEFAULT_GRAVITY = 8.20f;   // 默认重力
    private static final float DEFAULT_FLAP    = 5.20f;   // 默认拍翅力度
    private static final float MIN_SPEED   = 0.18f, MAX_SPEED   = 0.70f; // 速度滑杆范围
    private static final float MIN_GRAVITY = 4.0f,  MAX_GRAVITY = 16.0f; // 重力滑杆范围
    private static final float MIN_FLAP    = 3.0f,  MAX_FLAP    = 9.0f;   // 拍翅力度滑杆范围
    private static final int S_SPEED = 0, S_GRAVITY = 1, S_FLAP = 2;      // 设置项在面板中的序号
    private static final float MIN_PIPE_BIRD     = 1.60f;  // 管道在缺口外的余量下限 = 小鸟直径倍数
    private static final float BIRD_HIT_SLACK    = 0.78f;  // 碰撞判定用半径的该比例（稍宽松）

    // ---- 调色板 -----------------------------------------------------------
    private static final int SKY_TOP    = Color.rgb(0x4e, 0xc0, 0xca); // 天空渐变顶部
    private static final int SKY_BOTTOM = Color.rgb(0x9d, 0xe6, 0xe9); // 天空渐变底部
    private static final int CLOUD      = Color.argb(210, 255, 255, 255); // 云朵（半透明白）
    private static final int GROUND_MAIN = Color.rgb(0xde, 0xd8, 0x95);   // 地面土壤主体色
    private static final int GROUND_SHADE = Color.rgb(0xc7, 0xbf, 0x7a);  // 土壤接缝阴影色
    private static final int GRASS      = Color.rgb(0x6d, 0xb0, 0x30);    // 草地
    private static final int GRASS_DARK = Color.rgb(0x58, 0x94, 0x25);    // 草地接缝深色
    private static final int PIPE_BODY  = Color.rgb(0x73, 0xbf, 0x2e);    // 管道主体
    private static final int PIPE_HI    = Color.rgb(0x97, 0xdd, 0x50);    // 管道高光
    private static final int PIPE_EDGE  = Color.rgb(0x2c, 0x63, 0x13);    // 管道描边
    private static final int BIRD_BODY  = Color.rgb(0xff, 0xd5, 0x00);    // 鸟身主体黄
    private static final int BIRD_BELLY = Color.rgb(0xff, 0xe8, 0x80);    // 鸟腹部浅黄
    private static final int BIRD_WING  = Color.rgb(0xf5, 0xa6, 0x23);    // 鸟翅膀橙
    private static final int BIRD_WING_EDGE = Color.rgb(0xb9, 0x6e, 0x0f); // 翅膀描边
    private static final int BIRD_BEAK  = Color.rgb(0xf0, 0x84, 0x08);    // 鸟喙橙
    private static final int BIRD_OUTLINE = Color.rgb(0x3a, 0x2a, 0x17);  // 鸟的外轮廓深棕
    private static final int SCORE_COLOR = Color.rgb(0xfb, 0xfb, 0xfb);   // 计分文字底色
    private static final int TEXT_DARK  = Color.rgb(0x35, 0x35, 0x35);    // 深灰文字色

    // 游戏状态机：待机 / 游戏中 / 结算 / 设置面板
    private enum State { READY, PLAYING, GAME_OVER, SETTINGS }

    // 画笔：fill 用于填充，edge 用于描边，textFill/textStroke 用于带描边的文字
    private final Paint fill  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Random random = new Random();

    // 管道与云朵的容器；scratch 为复用的矩形，避免频繁创建对象
    private final List<Pipe> pipes = new ArrayList<>();
    private final List<Cloud> clouds = new ArrayList<>();
    private final RectF scratch = new RectF();

    // 持久化：保存最高分与三项设置
    private final SharedPreferences prefs;

    private State state = State.READY;
    private State prevState = State.READY;   // 离开设置面板后要恢复到的状态

    // 视图 / 布局尺寸（屏幕像素）
    private int vw, vh;
    private float groundY, birdX, birdY, birdR;          // 地面高度、鸟的坐标与半径
    private float pipeW, capW, capH, gapHalf, minPipeLen; // 管道宽/管口宽/管口高/缺口半高/管道余量
    private float accel, flapV, maxFallV, scrollV;        // 重力加速度、拍翅初速、最大落速、滚动速度
    private float spacingX;                               // 相邻管道的水平间隔

    // 可调节设置（持久化），dragSetting 为当前被拖动的滑杆行序号（-1 表示无）
    private float speedSetting;
    private float gravitySetting;
    private float flapSetting;
    private int dragSetting = -1;

    // 运行期状态
    private float birdVy;        // 小鸟当前竖直速度
    private float birdAngle;     // 小鸟当前倾角（度），做了平滑
    private float scrollX;       // 世界已滚动的距离
    private float nextSpawnX;    // 下一对管道的绝对世界坐标 x
    private int score, best;     // 本局得分、历史最高分
    private float wingT;         // 拍翅动画时钟
    private float runT;          // 本局计时（待机状态用于上下浮动）
    private boolean allowRestart;  // 结算后是否允许点击重新开始
    private boolean landed;      // 死亡后小鸟是否已落到地面静止
    private float readyBobBaseY; // 待机浮动动画的基准高度
    private float settleT;       // 落地静止后经过的时间
    private long lastFrame;      // 上一帧时间戳
    private boolean ticking;     // 动画循环是否在运行

    // 主循环：每帧计算时间步长、推进物理/逻辑并请求重绘
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

    // 构造：读取持久化的最高分与三项设置
    public GameView(Context context) {
        super(context);
        prefs = context.getSharedPreferences("flappy_bird", Context.MODE_PRIVATE);
        best = prefs.getInt("best", 0);
        speedSetting = prefs.getFloat("speed", DEFAULT_SPEED);
        gravitySetting = prefs.getFloat("gravity", DEFAULT_GRAVITY);
        flapSetting = prefs.getFloat("flap", DEFAULT_FLAP);
    }

    // ------------------------------------------------------------------ 管道
    // worldX 为左边缘的世界坐标；gapTop 是上管底部 y；gapBot 是下管顶部 y
    private static class Pipe {
        float worldX;   // 左边缘世界坐标
        float gapTop;   // 上管下边缘 y（即缺口上沿）
        float gapBot;   // 下管上边缘 y（即缺口下沿）
        boolean scored; // 是否已计分
        Pipe(float wx, float gt, float gb) { worldX = wx; gapTop = gt; gapBot = gb; }
    }

    private static class Cloud {
        float x, y, s, v;
        Cloud(float x, float y, float s, float v) { this.x = x; this.y = y; this.s = s; this.v = v; }
    }

    // ------------------------------------------------------------------ 初始化
    // 视图尺寸变化时按新尺寸计算各类像素尺寸并生成云朵
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

    // 根据屏宽 vw / 屏高 vh 和手感常量换算游戏对象的像素尺寸
    private void computeMetrics() {
        birdR  = vw * BIRD_D_FRACTION * 0.5f;   // 小鸟半径 = 屏宽 × 0.15 / 2
        float birdD = birdR * 2f;               // 小鸟直径
        birdX  = vw * BIRD_X_FRACTION;          // 小鸟固定横向位置
        groundY = vh * (1f - GROUND_H_FRACTION); // 地面条顶部的 y
        pipeW  = vw * PIPE_W_FRACTION;          // 管道柱体宽
        capW   = pipeW * 1.12f;                 // 管口（管帽）比柱体略宽
        capH   = birdD * 0.55f;                 // 管口高度
        gapHalf = (GAP_BIRD_RATIO * birdD) * 0.5f; // 上下管间缺口高度的一半
        minPipeLen = MIN_PIPE_BIRD * birdD;     // 缺口外留出的最小管道长度
        accel  = gravitySetting * birdD;        // 重力加速度（按设置换算）
        flapV  = flapSetting * birdD;           // 拍翅产生的上升初速度
        maxFallV = MAX_FALL_BIRD * birdD;       // 终端下落速度
        scrollV = speedSetting * vw;            // 世界向左滚动的线速度
        spacingX = PIPE_SPACING_W * vw;         // 相邻管道间隔
    }

    // 随机生成 6 朵大小、位置、速度各异的云
    private void buildClouds() {
        clouds.clear();
        for (int i = 0; i < 6; i++) {
            float s = (0.5f + random.nextFloat() * 0.8f) * birdR * 1.6f;
            clouds.add(new Cloud(random.nextFloat() * vw,
                    vh * (0.06f + random.nextFloat() * 0.28f),
                    s, 0.25f + random.nextFloat() * 0.4f));
        }
    }

    // 进入待机状态：把鸟放在基准高度并归零相关计时
    private void startReady() {
        readyBobBaseY = groundY * 0.62f;
        birdY = readyBobBaseY;
        birdVy = 0;
        runT = 0;
    }

    // 开始一局：清空管道、重置分数与位置，赋予一次轻跳
    private void startPlaying() {
        score = 0;
        pipes.clear();
        scrollX = 0;
        lastFrame = 0;
        nextSpawnX = FIRST_PIPE_W * vw;   // 给玩家一段缓冲期，再出现第一根管道
        readyBobBaseY = groundY * 0.62f;
        birdY = readyBobBaseY;
        birdVy = -flapV * 0.8f;          // 开局的一记轻柔起跳
        birdAngle = 0;
        allowRestart = false;
        landed = false;
        state = State.PLAYING;
    }

    // 游戏结束：振动反馈、刷新最高分并重置落地相关标志
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

    // ------------------------------------------------------------------ 主逻辑循环
    private void step(float dt) {
        runT += dt;
        wingT += dt;

        if (state == State.PLAYING) {
            // 游戏中的物理：加速下落（带终端速度）、更新鸟高与滚动距离
            birdVy = Math.min(birdVy + accel * dt, maxFallV);
            birdY += birdVy * dt;
            scrollX += scrollV * dt;

            // 依据竖直速度平滑调整倾角：上升抬头、下落俯身
            float target = birdVy < 0 ? -30f : Math.min(85f, 40f * (birdVy / maxFallV));
            smoothAngle(target, dt);

            // 持续补充新管道，保证画面右侧始终有待出现的管道
            while (nextSpawnX - scrollX < vw + pipeW) {
                spawnPipe(nextSpawnX);
                nextSpawnX += spacingX;
            }

            // 遍历管道：回收移出屏幕的、穿过即得分、发生碰撞即结束
            Iterator<Pipe> it = pipes.iterator();
            while (it.hasNext()) {
                Pipe p = it.next();
                float sx = p.worldX - scrollX;
                if (sx + capW < -pipeW) {
                    it.remove();   // 已完全滚出屏幕左侧，移除
                } else {
                    if (!p.scored && sx + capW < birdX) {
                        p.scored = true;   // 管口已越过小鸟，算作穿过，得一分
                        score++;
                    }
                    if (hitPipe(p)) {
                        gameOver();
                        return;
                    }
                }
            }

            // 落到地面同样判定游戏结束
            if (birdY + birdR >= groundY) {
                birdY = groundY - birdR;
                gameOver();
            }
        } else if (state == State.READY) {
            // 待机：场景慢速滚动，小鸟做上下浮动呼吸动画
            scrollX += scrollV * dt * 0.55f;
            birdY = readyBobBaseY + (float) Math.sin(runT * 2.6f) * birdR * 0.9f;
            birdAngle *= 0.9f;
        } else if (state == State.SETTINGS) {
            // 设置面板：画面静止，云朵仍由下方的 updateClouds 漂动
        } else { // GAME_OVER 结算
            if (!landed) {
                // 死亡后小鸟继续下落，直到落到地面静止
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
                allowRestart = true;   // 落地停顿片刻后才允许重新开始
            } else {
                settleT += dt;
            }
        }

        updateClouds(dt);
    }

    // 把当前倾角向目标值平滑过渡
    private void smoothAngle(float target, float dt) {
        birdAngle += (target - birdAngle) * Math.min(1f, 16f * dt);
    }

    // 云朵持续向左漂移，移出左侧后重新随机出现在右侧
    private void updateClouds(float dt) {
        for (Cloud c : clouds) {
            c.x -= c.v * vw * dt * 0.02f;
            if (c.x < -c.s * 3) {
                c.x = vw + c.s;
                c.y = vh * (0.06f + random.nextFloat() * 0.28f);
            }
        }
    }

    // 在世界坐标 worldX 处生成一对管道，缺口中心在可用范围内随机
    private void spawnPipe(float worldX) {
        float low = minPipeLen + gapHalf;
        float high = groundY - gapHalf - minPipeLen;
        if (high < low) high = low;
        float gapCenter = low + random.nextFloat() * (high - low);
        pipes.add(new Pipe(worldX, gapCenter - gapHalf, gapCenter + gapHalf));
    }

    // 判断小鸟是否撞上管道（用略小的半径、按管口宽度所在列判定）
    private boolean hitPipe(Pipe p) {
        float sx = p.worldX - scrollX;
        float margin = (capW - pipeW) * 0.5f;
        float hitR = birdR * BIRD_HIT_SLACK;
        // 分别与上管（0 ~ gapTop）和下管（gapBot ~ 地面）所在的宽列求碰撞
        return circleHitsRect(birdX, birdY, hitR, sx - margin, 0, sx - margin + capW, p.gapTop)
                || circleHitsRect(birdX, birdY, hitR, sx - margin, p.gapBot, sx - margin + capW, groundY);
    }

    // 圆与矩形是否相交：取圆心到矩形的最近点，再比较距离与半径
    private boolean circleHitsRect(float cx, float cy, float r,
                                   float rx1, float ry1, float rx2, float ry2) {
        float nx = Math.max(rx1, Math.min(cx, rx2));
        float ny = Math.max(ry1, Math.min(cy, ry2));
        float dx = cx - nx, dy = cy - ny;
        return dx * dx + dy * dy <= r * r;
    }

    // ----------------------------------------------------------------- 输入
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        float x = event.getX();
        float y = event.getY();

        // 设置面板的触摸单独处理（滑杆、按钮）
        if (state == State.SETTINGS) {
            return handleSettingsTouch(action, x, y);
        }
        // 仅响应按下动作，其余阶段忽略
        if (action != MotionEvent.ACTION_DOWN) return true;

        switch (state) {
            case READY:
                if (inSettingsButton(x, y)) {
                    prevState = state;   // 记住来源状态，返回时恢复
                    state = State.SETTINGS;
                } else {
                    startPlaying();
                }
                break;
            case PLAYING:
                birdVy = -flapV;   // 点击即拍翅，赋予一次上升初速度
                break;
            case GAME_OVER:
                if (inSettingsButton(x, y)) {
                    prevState = state;
                    state = State.SETTINGS;
                } else if (allowRestart) {
                    startPlaying();   // 允许后单次点击立即开始新一局
                }
                break;
        }
        return true;
    }

    // 处理设置面板内的触摸：返回 / 恢复默认 / 拖动滑杆
    private boolean handleSettingsTouch(int action, float x, float y) {
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                if (inBackButton(x, y)) {
                    state = prevState;   // 返回进入前的状态
                    dragSetting = -1;
                } else if (inResetButton(x, y)) {
                    resetSettings();     // 恢复默认参数
                } else {
                    dragSetting = settingRowAt(x, y);   // 判断按到哪一行滑杆
                    if (dragSetting >= 0) {
                        updateSettingFromX(x);
                    }
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                if (dragSetting >= 0) {
                    updateSettingFromX(x);   // 拖动中持续更新对应参数
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragSetting = -1;   // 松手结束拖动
                return true;
        }
        return true;
    }

    // ----------------------------------------------------------------- 绘制
    // 每帧重绘：依次画天空、云、管道、地面、小鸟，最后画 HUD 或设置面板
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

    // 画天空：由上到下的青色渐变
    private void drawSky(Canvas canvas) {
        LinearGradient sky = new LinearGradient(0, 0, 0, groundY, SKY_TOP, SKY_BOTTOM,
                Shader.TileMode.CLAMP);
        fill.setShader(sky);
        canvas.drawRect(0, 0, vw, groundY, fill);
        fill.setShader(null);
    }

    // 画云朵：由几个重叠圆形拼成的云团，随云朵坐标整体缩放
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

    // 遍历所有管道：上管从上往下伸到缺口上沿，下管从地面往上伸到缺口下沿
    private void drawPipes(Canvas canvas) {
        for (Pipe p : pipes) {
            float sx = p.worldX - scrollX;
            float margin = (capW - pipeW) * 0.5f;

            // 上管：从天花板向下延伸到 p.gapTop
            drawPipeColumn(canvas, sx, p.gapTop, false, margin);
            // 下管：从地面向上延伸到 p.gapBot
            drawPipeColumn(canvas, sx, p.gapBot, true, margin);
        }
    }

    private void drawPipeColumn(Canvas canvas, float left, float openY, boolean extendsDownward,
                                float margin) {
        // extendsDownward == true  : 下管，占据 [openY, groundY]
        // extendsDownward == false : 上管，占据 [0, openY]
        float y0 = extendsDownward ? openY : 0;
        float y1 = extendsDownward ? groundY : openY;
        // 管口（加宽的帽）紧贴缺口一侧
        float capTop = extendsDownward ? openY : openY - capH;
        float capBot = extendsDownward ? openY + capH : openY;

        fill.setColor(PIPE_BODY);
        canvas.drawRect(left, y0, left + pipeW, y1, fill);
        // 柔和的竖向高光条
        fill.setColor(PIPE_HI);
        canvas.drawRect(left + pipeW * 0.12f, y0, left + pipeW * 0.34f, y1, fill);
        // 沿整个管形轮廓描一圈边
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(edgeWidth());
        edge.setColor(PIPE_EDGE);
        canvas.drawRect(left - margin, y0, left - margin + capW, y1, edge);
        // 管口帽（比柱体宽 margin×2）
        fill.setColor(PIPE_BODY);
        canvas.drawRect(left - margin, capTop, left - margin + capW, capBot, fill);
        fill.setColor(PIPE_HI);
        canvas.drawRect(left - margin + pipeW * 0.12f, capTop,
                left - margin + pipeW * 0.34f, capBot, fill);
        edge.setStyle(Paint.Style.FILL);
    }

    // 描边宽度随小鸟大小自适应，且不小于 3px
    private float edgeWidth() {
        return Math.max(3f, birdR * 0.12f);
    }

    // 画地面：上缘一条草地，下方为土壤，各自带随滚动移动的接缝纹理
    private void drawGround(Canvas canvas) {
        // 草地带
        fill.setColor(GRASS);
        canvas.drawRect(0, groundY, vw, groundY + birdR * 0.55f, fill);
        // 土壤
        fill.setColor(GROUND_MAIN);
        canvas.drawRect(0, groundY + birdR * 0.55f, vw, vh, fill);
        // 土壤里的竖向接缝（随滚动移动，营造移动感）
        float tile = vw * 0.09f;
        fill.setColor(GROUND_SHADE);
        float off = tile - (scrollX % tile);
        for (float x = off; x < vw + tile; x += tile) {
            canvas.drawRect(x, groundY + birdR * 0.55f, x + tile * 0.12f, vh, fill);
        }
        // 草地里的接缝
        fill.setColor(GRASS_DARK);
        float tileG = vw * 0.055f;
        float offG = tileG - (scrollX % tileG);
        for (float x = offG; x < vw + tileG; x += tileG) {
            canvas.drawRect(x, groundY, x + tileG * 0.16f, groundY + birdR * 0.55f, fill);
        }
    }

    // 画小鸟：整体先平移到鸟的位置并旋转倾角，再逐部件绘制
    private void drawBird(Canvas canvas) {
        canvas.save();
        canvas.translate(birdX, birdY);
        canvas.rotate(birdAngle);

        float R = birdR;
        float ow = Math.max(2.5f, R * 0.09f);   // 卡通描边宽度
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(ow);
        edge.setColor(BIRD_OUTLINE);

        // 尾羽：伸向身体后方，大多被身体遮挡
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

        // 身体：一个黄色圆形
        fill.setColor(BIRD_BODY);
        canvas.drawCircle(0f, 0f, R, fill);
        canvas.drawCircle(0f, 0f, R, edge);

        // 腹部：位于左下方、保持落在身体圆内
        fill.setColor(BIRD_BELLY);
        scratch.set(-0.88f * R, -0.18f * R, 0.70f * R, 0.97f * R);
        canvas.drawOval(scratch, fill);

        // 翅膀：侧视图，绕肩部随动画摆动
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
        // 翅膀上的羽毛接缝线
        edge.setStrokeWidth(Math.max(1.6f, R * 0.05f));
        edge.setColor(BIRD_WING_EDGE);
        canvas.drawLine(-0.92f * R, 0.02f * R, 0.14f * R, 0.06f * R, edge);
        canvas.restore();

        // 眼睛：大而朝前，带深色眼圈
        edge.setStrokeWidth(Math.max(1.6f, R * 0.06f));
        edge.setColor(BIRD_OUTLINE);
        float eyeX = 0.28f * R, eyeY = -0.42f * R, eyeR = 0.30f * R;
        fill.setColor(Color.WHITE);
        canvas.drawCircle(eyeX, eyeY, eyeR, fill);
        canvas.drawCircle(eyeX, eyeY, eyeR, edge);
        fill.setColor(BIRD_OUTLINE);
        canvas.drawCircle(eyeX + 0.09f * R, eyeY + 0.02f * R, 0.15f * R, fill);   // 瞳孔
        fill.setColor(Color.WHITE);
        canvas.drawCircle(eyeX + 0.15f * R, eyeY - 0.05f * R, 0.05f * R, fill);  // 高光

        // 喙：上喙 + 下喙
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

    // ------------------------------------------------------------------- HUD（界面文字）
    // 统一初始化两把文字画笔的公共属性
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

    // 按状态绘制屏幕文字：游戏中的得分、待机的标题、结算的统计面板
    private void drawHud(Canvas canvas) {
        initTextPaints();
        if (state == State.PLAYING) {
            // 游戏中：屏幕上方居中显示当前得分
            float size = vh * 0.13f;
            textFill.setTextSize(size);
            textStroke.setTextSize(size);
            textStroke.setStrokeWidth(size * 0.14f);
            textStroke.setColor(TEXT_DARK);
            drawTextCentered(canvas, String.valueOf(score), vw * 0.5f, vh * 0.16f);
        } else if (state == State.READY) {
            // 待机：标题 + 开始提示 + 历史最高分
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
        } else { // GAME_OVER 结算
            // 中央半透明变暗面板，把统计文字与背景区分开
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

            // 允许重开且处于每秒闪烁的亮半周期时，才显示提示文字
            if (allowRestart && (runT % 1.0f) < 0.5f) {
                float t3 = vh * 0.042f;
                textFill.setTextSize(t3);
                textStroke.setTextSize(t3);
                textStroke.setStrokeWidth(t3 * 0.12f);
                drawTextCentered(canvas, "点击重新开始", vw * 0.5f, vh * 0.68f);
            }
        }
    }

    // 先画描边再画填充，实现带深色描边的居中文字
    private void drawTextCentered(Canvas canvas, String s, float cx, float cy) {
        canvas.drawText(s, cx, cy, textStroke);
        canvas.drawText(s, cx, cy, textFill);
    }

    // ------------------------------------------------------------- 设置面板布局
    // 面板外框
    private float panelLeft()   { return vw * 0.07f; }
    private float panelRight()  { return vw * 0.93f; }
    private float panelTop()    { return vh * 0.24f; }
    private float panelBottom() { return vh * 0.78f; }
    // 滑杆轨道左右边界
    private float trackLeft()   { return vw * 0.34f; }
    private float trackRight()  { return vw * 0.60f; }
    // 第 row 行滑杆的中心 y，以及命中判定半高
    private float settingRowY(int row) { return panelTop() + vh * 0.16f + row * vh * 0.10f; }
    private float settingRowHitHalf()  { return vh * 0.048f; }

    // 面板底部「返回」按钮范围
    private float backLeft()   { return panelLeft() + vw * 0.05f; }
    private float backRight()  { return panelLeft() + vw * 0.38f; }
    // 按钮区域上下边界
    private float btnTop()     { return panelBottom() - vh * 0.11f; }
    private float btnBottom()  { return panelBottom() - vh * 0.04f; }
    // 面板底部「恢复默认」按钮范围
    private float resetLeft()  { return panelRight() - vw * 0.38f; }
    private float resetRight() { return panelRight() - vw * 0.05f; }

    // 屏幕右上角「设置」按钮（待机 / 结算界面可见）
    private float settingsBtnRight()  { return vw - vw * 0.03f; }
    private float settingsBtnTop()    { return vh * 0.03f; }
    private float settingsBtnBottom() { return settingsBtnTop() + vh * 0.062f; }
    private float settingsBtnLeft()   { return settingsBtnRight() - vw * 0.16f; }

    // 命中检测：点 (x,y) 是否落在「返回」按钮内
    private boolean inBackButton(float x, float y) {
        return x >= backLeft() && x <= backRight() && y >= btnTop() && y <= btnBottom();
    }
    // 命中检测：点 (x,y) 是否落在「恢复默认」按钮内
    private boolean inResetButton(float x, float y) {
        return x >= resetLeft() && x <= resetRight() && y >= btnTop() && y <= btnBottom();
    }
    // 命中检测：点 (x,y) 是否落在右上角「设置」按钮内
    private boolean inSettingsButton(float x, float y) {
        return x >= settingsBtnLeft() && x <= settingsBtnRight()
                && y >= settingsBtnTop() && y <= settingsBtnBottom();
    }

    // 返回点 (x,y) 命中的滑杆行序号，未命中返回 -1
    private int settingRowAt(float x, float y) {
        float half = settingRowHitHalf();
        for (int i = 0; i < 3; i++) {
            float cy = settingRowY(i);
            if (y >= cy - half && y <= cy + half) return i;
        }
        return -1;
    }

    // 按行序号取当前设置值
    private float settingValue(int idx) {
        switch (idx) {
            case S_SPEED:   return speedSetting;
            case S_GRAVITY: return gravitySetting;
            default:        return flapSetting;
        }
    }
    // 按行序号取下限
    private float settingMin(int idx) {
        switch (idx) {
            case S_SPEED:   return MIN_SPEED;
            case S_GRAVITY: return MIN_GRAVITY;
            default:        return MIN_FLAP;
        }
    }
    // 按行序号取上限
    private float settingMax(int idx) {
        switch (idx) {
            case S_SPEED:   return MAX_SPEED;
            case S_GRAVITY: return MAX_GRAVITY;
            default:        return MAX_FLAP;
        }
    }
    // 按行序号取中文标签
    private String settingLabel(int idx) {
        switch (idx) {
            case S_SPEED:   return "速度";
            case S_GRAVITY: return "重力";
            default:        return "拍翅力度";
        }
    }

    // 把数值夹取到 [lo, hi] 区间
    private float clampf(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    // 写入某项设置并持久化、按新参数重算尺寸
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

    // 由手指横坐标换算成滑杆进度并更新当前拖动项
    private void updateSettingFromX(float x) {
        float t = clampf((x - trackLeft()) / (trackRight() - trackLeft()), 0f, 1f);
        int idx = dragSetting;
        setSetting(idx, settingMin(idx) + t * (settingMax(idx) - settingMin(idx)));
    }

    // 把三项设置写入 SharedPreferences
    private void saveSettings() {
        prefs.edit()
                .putFloat("speed", speedSetting)
                .putFloat("gravity", gravitySetting)
                .putFloat("flap", flapSetting)
                .apply();
    }

    // 把三项设置恢复为默认值
    private void resetSettings() {
        speedSetting = DEFAULT_SPEED;
        gravitySetting = DEFAULT_GRAVITY;
        flapSetting = DEFAULT_FLAP;
        saveSettings();
        computeMetrics();
    }

    // 画整个设置面板：半透明遮罩 + 浅色面板 + 标题 + 三行滑杆 + 两个按钮
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

    // 面板标题：深色文字、米白描边
    private void drawPanelTitle(Canvas canvas, String s, float cy, float size) {
        textFill.setColor(TEXT_DARK);
        textFill.setTextSize(size);
        textStroke.setColor(Color.rgb(0xff, 0xfb, 0xef));
        textStroke.setTextSize(size);
        textStroke.setStrokeWidth(size * 0.12f);
        drawTextCentered(canvas, s, vw * 0.5f, cy);
    }

    // 画一行设置：左侧中文标签、中间轨道与已填充进度、右侧数值、滑钮
    private void drawSettingRow(Canvas canvas, int idx) {
        float cy = settingRowY(idx);
        float size = vw * 0.042f;

        // 标签：靠左
        textFill.setColor(TEXT_DARK);
        textFill.setTextSize(size);
        textFill.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(settingLabel(idx), panelLeft() + vw * 0.03f, cy + size * 0.35f, textFill);

        // 轨道 + 已调节的填充进度
        float halfH = vh * 0.010f;
        fill.setColor(Color.rgb(0xdd, 0xd8, 0xc8));
        canvas.drawRoundRect(trackLeft(), cy - halfH, trackRight(), cy + halfH,
                halfH, halfH, fill);
        float t = (settingValue(idx) - settingMin(idx)) / (settingMax(idx) - settingMin(idx));
        float knobX = trackLeft() + t * (trackRight() - trackLeft());
        fill.setColor(Color.rgb(0x6d, 0xb0, 0x30));
        canvas.drawRoundRect(trackLeft(), cy - halfH, knobX, cy + halfH, halfH, halfH, fill);

        // 滑钮：白底深色描边圆
        float kr = vh * 0.020f;
        fill.setColor(Color.WHITE);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(edgeWidth());
        edge.setColor(TEXT_DARK);
        canvas.drawCircle(knobX, cy, kr, fill);
        canvas.drawCircle(knobX, cy, kr, edge);
        edge.setStyle(Paint.Style.FILL);

        // 当前数值：靠右
        textFill.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(String.format(Locale.US, "%.2f", settingValue(idx)),
                panelRight() - vw * 0.03f, cy + size * 0.35f, textFill);
        textFill.setTextAlign(Paint.Align.CENTER);
    }

    // 通用按钮：绿色圆角矩形填充 + 深色描边 + 白色居中文字
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

    // --------------------------------------------------------------- 生命周期
    // 视图挂载到窗口时启动动画循环
    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        ticking = true;
        lastFrame = 0;
        postOnAnimation(frame);
    }

    // 视图从窗口卸载时停止动画循环，避免空转
    @Override
    protected void onDetachedFromWindow() {
        ticking = false;
        removeCallbacks(frame);
        super.onDetachedFromWindow();
    }
}
