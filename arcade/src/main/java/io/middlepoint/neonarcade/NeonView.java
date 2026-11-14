package io.middlepoint.neonarcade;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Random;

/** Offline arcade: three original games, a TV-friendly gamepad UI and installed app library. */
public final class NeonView extends View {
    private static final int MENU = 0, SNAKE = 1, PONG = 2, BREAKOUT = 3, APPS = 4;
    private static final int BG = Color.rgb(7, 12, 27);
    private static final int CYAN = Color.rgb(67, 234, 245);
    private static final int PINK = Color.rgb(255, 78, 160);
    private static final int GOLD = Color.rgb(255, 204, 84);
    private static final int WHITE = Color.rgb(239, 247, 255);
    private static final int MUTED = Color.rgb(145, 167, 193);
    private static final int PANEL = Color.rgb(19, 31, 57);

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Random random = new Random();
    private final float[] starX = new float[85], starY = new float[85], starSize = new float[85];
    private final ArrayList<AppEntry> apps = new ArrayList<>();
    private final ArrayDeque<Point> snake = new ArrayDeque<>();

    private int screen = MENU, selected = 0, appIndex = 0;
    private boolean active = true, upHeld, downHeld, leftHeld, rightHeld;
    private float analogX, analogY;
    private long lastFrame = SystemClock.uptimeMillis(), lastAnalogNav;
    private String notice = "";
    private long noticeUntil;

    // Snake board is 40x20 at (160,158), 24px cells.
    private static final int SW = 40, SH = 20, CELL = 24;
    private int snakeDir, nextSnakeDir, appleX, appleY, snakeScore;
    private float snakeTimer;
    private boolean snakeDead;

    // Pong: player on the left, AI on the right.
    private float playerY, aiY, pongX, pongY, pongVX, pongVY;
    private int playerScore, aiScore;
    private boolean pongDead;

    // Breakout.
    private final boolean[][] bricks = new boolean[6][11];
    private float paddleX, brickX, brickY, brickVX, brickVY;
    private int brickScore, lives;
    private boolean ballWaiting, brickDead, brickWon;

    private static final class AppEntry {
        final String name, pkg;
        AppEntry(String name, String pkg) { this.name = name; this.pkg = pkg; }
    }

    public NeonView(Context context) {
        super(context);
        setLayerType(View.LAYER_TYPE_HARDWARE, null);
        setFocusable(true);
        setFocusableInTouchMode(true);
        for (int i = 0; i < starX.length; i++) {
            starX[i] = random.nextInt(1280);
            starY[i] = random.nextInt(720);
            starSize[i] = 1f + random.nextFloat() * 2f;
        }
        resetSnake();
        resetPong();
        resetBreakout();
        loadApps();
    }

    public void pause() { active = false; }
    public void resume() {
        active = true;
        lastFrame = SystemClock.uptimeMillis();
        invalidate();
    }

    public boolean goBack() {
        if (screen == MENU) return false;
        screen = MENU;
        upHeld = downHeld = leftHeld = rightHeld = false;
        analogX = analogY = 0;
        invalidate();
        return true;
    }

    public boolean handleKey(int code, boolean down, int repeat) {
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_BUTTON_B) {
            if (down) return goBack();
            return true;
        }
        boolean up = code == KeyEvent.KEYCODE_DPAD_UP;
        boolean dn = code == KeyEvent.KEYCODE_DPAD_DOWN;
        boolean lf = code == KeyEvent.KEYCODE_DPAD_LEFT;
        boolean rt = code == KeyEvent.KEYCODE_DPAD_RIGHT;
        boolean select = code == KeyEvent.KEYCODE_DPAD_CENTER
                || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_BUTTON_A
                || code == KeyEvent.KEYCODE_BUTTON_START || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
        if (!up && !dn && !lf && !rt && !select) return false;

        if (screen == MENU) {
            if (down && repeat == 0) {
                if (lf && selected % 3 > 0) selected--;
                if (rt && selected % 3 < 2) selected++;
                if (up && selected >= 3) selected -= 3;
                if (dn && selected < 3) selected += 3;
                if (select) selectMenu();
            }
        } else if (screen == APPS) {
            if (down && repeat == 0) {
                if (up) appIndex = Math.max(0, appIndex - 1);
                if (dn) appIndex = Math.min(apps.size() - 1, appIndex + 1);
                if (select && !apps.isEmpty()) launchPackage(apps.get(appIndex).pkg);
            }
        } else if (screen == SNAKE) {
            if (down && repeat == 0) {
                if (snakeDead && select) resetSnake();
                else if (up && snakeDir != 2) nextSnakeDir = 0;
                else if (rt && snakeDir != 3) nextSnakeDir = 1;
                else if (dn && snakeDir != 0) nextSnakeDir = 2;
                else if (lf && snakeDir != 1) nextSnakeDir = 3;
            }
        } else if (screen == PONG) {
            if (up) upHeld = down;
            if (dn) downHeld = down;
            if (down && select && pongDead) resetPong();
        } else if (screen == BREAKOUT) {
            if (lf) leftHeld = down;
            if (rt) rightHeld = down;
            if (down && lf) paddleX = Math.max(190, paddleX - 16);
            if (down && rt) paddleX = Math.min(1090, paddleX + 16);
            if (down && select) {
                if (brickDead || brickWon) resetBreakout();
                else ballWaiting = false;
            }
        }
        invalidate();
        return true;
    }

    public boolean handleMotion(MotionEvent event) {
        if (!event.isFromSource(InputDevice.SOURCE_JOYSTICK)) return false;
        if (event.getAction() != MotionEvent.ACTION_MOVE) return false;
        analogX = event.getAxisValue(MotionEvent.AXIS_X);
        analogY = event.getAxisValue(MotionEvent.AXIS_Y);
        if (screen == MENU || screen == APPS) {
            long now = SystemClock.uptimeMillis();
            if (now - lastAnalogNav > 230) {
                if (analogX > .65f) handleKey(KeyEvent.KEYCODE_DPAD_RIGHT, true, 0);
                if (analogX < -.65f) handleKey(KeyEvent.KEYCODE_DPAD_LEFT, true, 0);
                if (analogY > .65f) handleKey(KeyEvent.KEYCODE_DPAD_DOWN, true, 0);
                if (analogY < -.65f) handleKey(KeyEvent.KEYCODE_DPAD_UP, true, 0);
                lastAnalogNav = now;
            }
        } else if (screen == SNAKE) {
            if (analogX > .7f) handleKey(KeyEvent.KEYCODE_DPAD_RIGHT, true, 0);
            else if (analogX < -.7f) handleKey(KeyEvent.KEYCODE_DPAD_LEFT, true, 0);
            else if (analogY > .7f) handleKey(KeyEvent.KEYCODE_DPAD_DOWN, true, 0);
            else if (analogY < -.7f) handleKey(KeyEvent.KEYCODE_DPAD_UP, true, 0);
        }
        return true;
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        long now = SystemClock.uptimeMillis();
        float dt = Math.min(.045f, (now - lastFrame) / 1000f);
        lastFrame = now;
        canvas.save();
        canvas.scale(getWidth() / 1280f, getHeight() / 720f);
        drawBackground(canvas, now);
        switch (screen) {
            case MENU: drawMenu(canvas); break;
            case SNAKE: updateSnake(dt); drawSnake(canvas); break;
            case PONG: updatePong(dt); drawPong(canvas); break;
            case BREAKOUT: updateBreakout(dt); drawBreakout(canvas); break;
            case APPS: drawApps(canvas); break;
        }
        if (now < noticeUntil) {
            box(canvas, 230, 642, 1050, 691, Color.rgb(48, 40, 62), 14);
            text(canvas, notice, 640, 674, 22, GOLD, true, Paint.Align.CENTER);
        }
        canvas.restore();
        if (active) postInvalidateDelayed(16);
    }

    private void drawBackground(Canvas c, long now) {
        p.setShader(new LinearGradient(0, 0, 0, 720, BG,
                Color.rgb(17, 12, 45), Shader.TileMode.CLAMP));
        c.drawRect(0, 0, 1280, 720, p);
        p.setShader(null);
        for (int i = 0; i < starX.length; i++) {
            float twinkle = (float) Math.sin(now / 850.0 + i);
            p.setColor(Color.argb((int) (80 + 55 * twinkle), 133, 194, 255));
            c.drawCircle(starX[i], starY[i], starSize[i], p);
        }
        p.setColor(Color.argb(32, 60, 200, 240));
        p.setStrokeWidth(1);
        for (int x = 0; x < 1280; x += 64) c.drawLine(x, 0, x, 720, p);
        for (int y = 0; y < 720; y += 64) c.drawLine(0, y, 1280, y, p);
    }

    private void drawMenu(Canvas c) {
        text(c, "NEON", 76, 110, 64, CYAN, true, Paint.Align.LEFT);
        text(c, "ARCADE", 255, 110, 64, WHITE, true, Paint.Align.LEFT);
        text(c, "ТВОЯ ИГРОВАЯ СТАНЦИЯ", 82, 145, 19, MUTED, false, Paint.Align.LEFT);
        text(c, "TV + GAMEPAD", 1190, 95, 21, PINK, true, Paint.Align.RIGHT);
        String[] names = {"ЗМЕЙКА", "PONG", "BREAKOUT", "ПРИЛОЖЕНИЯ", "KINOWATCH", "KINOPUB"};
        String[] subs = {"Ретро-классика", "Против компьютера", "Разбей все блоки",
                "Игры и эмуляторы", "Смотреть кино", "Фильмы и сериалы"};
        String[] icons = {"S", "P", "B", "A", "K", "KP"};
        int[] colors = {CYAN, PINK, GOLD, CYAN, PINK, GOLD};
        for (int i = 0; i < 6; i++) {
            int col = i % 3, row = i / 3;
            float x = 76 + col * 380, y = 183 + row * 215;
            boolean focused = i == selected;
            box(c, x, y, x + 354, y + 185,
                    focused ? Color.rgb(34, 55, 83) : PANEL, 23);
            if (focused) outline(c, x, y, x + 354, y + 185, colors[i], 3, 23);
            p.setColor(colors[i]);
            c.drawCircle(x + 56, y + 58, 32, p);
            text(c, icons[i], x + 56, y + 70, icons[i].length() > 1 ? 25 : 33,
                    BG, true, Paint.Align.CENTER);
            text(c, names[i], x + 24, y + 119, 30, WHITE, true, Paint.Align.LEFT);
            text(c, subs[i], x + 24, y + 154, 20, MUTED, false, Paint.Align.LEFT);
            if (focused) text(c, "●", x + 327, y + 42, 23, colors[i], true, Paint.Align.CENTER);
        }
        text(c, "КРЕСТОВИНА — ВЫБОР    •    OK / A — ЗАПУСК    •    BACK — ВЫХОД",
                640, 673, 19, MUTED, false, Paint.Align.CENTER);
    }

    private void drawGameHeader(Canvas c, String title, String subtitle, String score) {
        text(c, title, 76, 92, 47, CYAN, true, Paint.Align.LEFT);
        text(c, subtitle, 80, 126, 20, MUTED, false, Paint.Align.LEFT);
        text(c, score, 1180, 96, 27, GOLD, true, Paint.Align.RIGHT);
        text(c, "BACK / B — МЕНЮ", 1180, 685, 20, MUTED, false, Paint.Align.RIGHT);
    }

    private void resetSnake() {
        snake.clear();
        for (int i = 0; i < 5; i++) snake.addLast(new Point(20 - i, 10));
        snakeDir = nextSnakeDir = 1;
        snakeTimer = 0;
        snakeScore = 0;
        snakeDead = false;
        spawnApple();
    }

    private void spawnApple() {
        for (int tries = 0; tries < 500; tries++) {
            int x = random.nextInt(SW), y = random.nextInt(SH);
            boolean occupied = false;
            for (Point point : snake) if (point.x == x && point.y == y) { occupied = true; break; }
            if (!occupied) { appleX = x; appleY = y; return; }
        }
    }

    private void updateSnake(float dt) {
        if (snakeDead) return;
        snakeTimer += dt;
        float delay = Math.max(.075f, .165f - snakeScore / 2000f);
        if (snakeTimer < delay) return;
        snakeTimer -= delay;
        snakeDir = nextSnakeDir;
        Point head = snake.peekFirst();
        int x = head.x + (snakeDir == 1 ? 1 : snakeDir == 3 ? -1 : 0);
        int y = head.y + (snakeDir == 2 ? 1 : snakeDir == 0 ? -1 : 0);
        if (x < 0 || y < 0 || x >= SW || y >= SH) { snakeDead = true; return; }
        boolean eating = x == appleX && y == appleY;
        if (!eating) snake.removeLast();
        for (Point point : snake) {
            if (point.x == x && point.y == y) { snakeDead = true; return; }
        }
        snake.addFirst(new Point(x, y));
        if (eating) { snakeScore += 10; spawnApple(); }
    }

    private void drawSnake(Canvas c) {
        drawGameHeader(c, "ЗМЕЙКА", "Пульт или геймпад: стрелки", "ОЧКИ  " + snakeScore);
        box(c, 150, 148, 1130, 648, Color.rgb(8, 23, 35), 16);
        for (int i = 0; i < SW; i++) {
            for (int j = 0; j < SH; j++) {
                p.setColor((i + j) % 2 == 0 ? Color.rgb(13, 31, 47) : Color.rgb(11, 27, 42));
                c.drawRect(160 + i * CELL, 158 + j * CELL,
                        160 + (i + 1) * CELL, 158 + (j + 1) * CELL, p);
            }
        }
        p.setColor(PINK);
        c.drawCircle(172 + appleX * CELL, 170 + appleY * CELL, 9, p);
        int idx = 0;
        for (Point point : snake) {
            p.setColor(idx++ == 0 ? GOLD : CYAN);
            c.drawRoundRect(new RectF(162 + point.x * CELL, 160 + point.y * CELL,
                    182 + point.x * CELL, 180 + point.y * CELL), 5, 5, p);
        }
        if (snakeDead) overlay(c, "ИГРА ОКОНЧЕНА", "OK / A — попробовать снова");
    }

    private void resetPong() {
        playerY = aiY = 350;
        playerScore = aiScore = 0;
        pongDead = false;
        resetPongBall(random.nextBoolean() ? 1 : -1);
    }

    private void resetPongBall(int direction) {
        pongX = 640;
        pongY = 390;
        pongVX = direction * 340;
        pongVY = (random.nextBoolean() ? 1 : -1) * (150 + random.nextInt(130));
    }

    private void updatePong(float dt) {
        if (pongDead) return;
        float control = (downHeld ? 1 : 0) - (upHeld ? 1 : 0);
        if (Math.abs(analogY) > .2f) control = analogY;
        playerY = clamp(playerY + control * 490 * dt, 174, 525);
        aiY = clamp(aiY + clamp(pongY - aiY - 48, -1, 1) * 260 * dt, 174, 525);
        pongX += pongVX * dt;
        pongY += pongVY * dt;
        if (pongY < 182 || pongY > 623) {
            pongY = clamp(pongY, 182, 623);
            pongVY *= -1;
        }
        if (pongVX < 0 && pongX <= 160 && pongX >= 135
                && pongY >= playerY && pongY <= playerY + 100) {
            pongVX = Math.min(690, Math.abs(pongVX) * 1.06f);
            pongVY += (pongY - playerY - 50) * 4f;
        }
        if (pongVX > 0 && pongX >= 1120 && pongX <= 1145
                && pongY >= aiY && pongY <= aiY + 100) {
            pongVX = -Math.min(690, Math.abs(pongVX) * 1.06f);
            pongVY += (pongY - aiY - 50) * 4f;
        }
        if (pongX < 115) {
            aiScore++;
            pongDead = aiScore >= 5;
            resetPongBall(1);
        } else if (pongX > 1165) {
            playerScore++;
            pongDead = playerScore >= 5;
            resetPongBall(-1);
        }
    }

    private void drawPong(Canvas c) {
        drawGameHeader(c, "PONG", "Вверх / вниз — ракетка", playerScore + " : " + aiScore);
        box(c, 115, 165, 1165, 640, Color.rgb(9, 25, 42), 16);
        p.setColor(Color.rgb(41, 76, 99));
        for (int y = 182; y < 627; y += 40) c.drawRect(637, y, 643, y + 22, p);
        p.setColor(CYAN);
        c.drawRoundRect(new RectF(135, playerY, 153, playerY + 100), 9, 9, p);
        p.setColor(PINK);
        c.drawRoundRect(new RectF(1127, aiY, 1145, aiY + 100), 9, 9, p);
        p.setColor(GOLD);
        c.drawCircle(pongX, pongY, 11, p);
        if (pongDead) overlay(c, playerScore >= 5 ? "ТЫ ПОБЕДИЛ!" : "ПОБЕДИЛ КОМПЬЮТЕР",
                "OK / A — реванш");
    }

    private void resetBreakout() {
        for (int r = 0; r < bricks.length; r++)
            for (int col = 0; col < bricks[r].length; col++) bricks[r][col] = true;
        paddleX = 640;
        brickX = paddleX;
        brickY = 570;
        brickVX = 220;
        brickVY = -310;
        brickScore = 0;
        lives = 3;
        ballWaiting = true;
        brickDead = brickWon = false;
    }

    private void updateBreakout(float dt) {
        if (brickDead || brickWon) return;
        float control = (rightHeld ? 1 : 0) - (leftHeld ? 1 : 0);
        if (Math.abs(analogX) > .2f) control = analogX;
        paddleX = clamp(paddleX + control * 650 * dt, 200, 1080);
        if (ballWaiting) { brickX = paddleX; brickY = 577; return; }
        brickX += brickVX * dt;
        brickY += brickVY * dt;
        if (brickX < 136 || brickX > 1144) {
            brickX = clamp(brickX, 136, 1144);
            brickVX *= -1;
        }
        if (brickY < 174) { brickY = 174; brickVY = Math.abs(brickVY); }
        if (brickVY > 0 && brickY >= 593 && brickY <= 616
                && Math.abs(brickX - paddleX) < 87) {
            brickVY = -Math.abs(brickVY);
            brickVX = clamp((brickX - paddleX) * 4.3f, -450, 450);
        }
        if (brickY > 640) {
            lives--;
            if (lives <= 0) brickDead = true;
            else { ballWaiting = true; brickVY = -310; brickVX = 220; }
            return;
        }
        int remaining = 0;
        for (int r = 0; r < 6; r++) {
            for (int col = 0; col < 11; col++) {
                if (!bricks[r][col]) continue;
                float x = 164 + col * 87, y = 212 + r * 35;
                if (brickX >= x - 9 && brickX <= x + 82
                        && brickY >= y - 9 && brickY <= y + 29) {
                    bricks[r][col] = false;
                    brickVY *= -1;
                    brickScore += 10;
                } else remaining++;
            }
        }
        if (remaining == 0) brickWon = true;
    }

    private void drawBreakout(Canvas c) {
        drawGameHeader(c, "BREAKOUT", "Влево / вправо — платформа", "ОЧКИ " + brickScore + "    ♥ " + lives);
        box(c, 120, 160, 1160, 646, Color.rgb(9, 25, 42), 16);
        int[] palette = {PINK, Color.rgb(240, 113, 202), GOLD, Color.rgb(255, 151, 96), CYAN,
                Color.rgb(91, 150, 255)};
        for (int r = 0; r < 6; r++) {
            for (int col = 0; col < 11; col++) {
                if (bricks[r][col]) box(c, 164 + col * 87, 212 + r * 35,
                        244 + col * 87, 239 + r * 35, palette[r], 6);
            }
        }
        box(c, paddleX - 80, 606, paddleX + 80, 622, CYAN, 8);
        p.setColor(GOLD);
        c.drawCircle(brickX, brickY, 9, p);
        if (ballWaiting) text(c, "OK / A — ПУСК МЯЧА", 640, 552, 26, WHITE, true, Paint.Align.CENTER);
        if (brickDead) overlay(c, "ШАНСЫ ЗАКОНЧИЛИСЬ", "OK / A — заново");
        if (brickWon) overlay(c, "ВСЕ БЛОКИ РАЗБИТЫ!", "OK / A — новая игра");
    }

    private void loadApps() {
        apps.clear();
        PackageManager pm = getContext().getPackageManager();
        HashSet<String> seen = new HashSet<>();
        for (String category : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent query = new Intent(Intent.ACTION_MAIN).addCategory(category);
            for (ResolveInfo info : pm.queryIntentActivities(query, 0)) {
                if (info.activityInfo == null) continue;
                String pkg = info.activityInfo.packageName;
                if (pkg.equals(getContext().getPackageName())
                        || pkg.equals("com.google.android.tvlauncher")
                        || pkg.equals("com.mitv.tvhome.atv") || seen.contains(pkg)) continue;
                seen.add(pkg);
                String name = String.valueOf(info.loadLabel(pm));
                apps.add(new AppEntry(name, pkg));
            }
        }
        Collections.sort(apps, Comparator.comparing(e -> e.name.toLowerCase()));
        appIndex = 0;
    }

    private void drawApps(Canvas c) {
        text(c, "МОИ ПРИЛОЖЕНИЯ", 78, 100, 52, CYAN, true, Paint.Align.LEFT);
        text(c, "Выбирай игру, эмулятор или кино", 80, 138, 23, MUTED, false, Paint.Align.LEFT);
        text(c, "НАЙДЕНО: " + apps.size(), 1180, 98, 22, GOLD, true, Paint.Align.RIGHT);
        if (apps.isEmpty()) {
            text(c, "Приложений не найдено. Установи игру или эмулятор.", 640, 365, 27,
                    WHITE, false, Paint.Align.CENTER);
        } else {
            int start = Math.max(0, Math.min(appIndex - 4, Math.max(0, apps.size() - 8)));
            for (int i = start; i < Math.min(apps.size(), start + 8); i++) {
                float y = 170 + (i - start) * 57;
                boolean focused = i == appIndex;
                box(c, 94, y, 1180, y + 49, focused ? Color.rgb(32, 55, 83) : PANEL, 10);
                if (focused) outline(c, 94, y, 1180, y + 49, CYAN, 2, 10);
                text(c, apps.get(i).name, 120, y + 34, 25, WHITE, focused, Paint.Align.LEFT);
                text(c, apps.get(i).pkg, 1147, y + 32, 16, MUTED, false, Paint.Align.RIGHT);
            }
        }
        text(c, "ВВЕРХ / ВНИЗ — ВЫБОР    •    OK / A — ЗАПУСК    •    BACK — МЕНЮ",
                640, 684, 18, MUTED, false, Paint.Align.CENTER);
    }

    private void selectMenu() {
        switch (selected) {
            case 0: resetSnake(); screen = SNAKE; break;
            case 1: resetPong(); screen = PONG; break;
            case 2: resetBreakout(); screen = BREAKOUT; break;
            case 3: loadApps(); screen = APPS; break;
            case 4: launchPackage("watch.kino"); break;
            case 5: launchPackage("com.kinopub"); break;
        }
    }

    private void launchPackage(String pkg) {
        PackageManager pm = getContext().getPackageManager();
        Intent intent = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (intent == null) intent = pm.getLaunchIntentForPackage(pkg);
        if (intent == null) {
            showNotice("Приложение не найдено: " + pkg);
            return;
        }
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(intent);
        } catch (RuntimeException exception) {
            showNotice("Не удалось запустить: " + pkg);
        }
    }

    private void showNotice(String message) {
        notice = message;
        noticeUntil = SystemClock.uptimeMillis() + 3200;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private void box(Canvas c, float l, float t, float r, float b, int color, float radius) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        c.drawRoundRect(new RectF(l, t, r, b), radius, radius, p);
    }

    private void outline(Canvas c, float l, float t, float r, float b, int color,
                         float width, float radius) {
        p.setColor(color);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(width);
        c.drawRoundRect(new RectF(l, t, r, b), radius, radius, p);
        p.setStyle(Paint.Style.FILL);
    }

    private void text(Canvas c, String value, float x, float y, float size, int color,
                      boolean bold, Paint.Align align) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        p.setTextSize(size);
        p.setTextAlign(align);
        p.setTypeface(bold ? Typeface.create("sans-serif", Typeface.BOLD)
                : Typeface.create("sans-serif", Typeface.NORMAL));
        c.drawText(value, x, y, p);
    }

    private void overlay(Canvas c, String headline, String instructions) {
        box(c, 240, 297, 1040, 465, Color.rgb(28, 38, 64), 24);
        outline(c, 240, 297, 1040, 465, PINK, 3, 24);
        text(c, headline, 640, 365, 39, WHITE, true, Paint.Align.CENTER);
        text(c, instructions, 640, 420, 23, CYAN, false, Paint.Align.CENTER);
    }
}
