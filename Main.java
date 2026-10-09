import javax.sound.sampled.*;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.Path2D;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.*;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

public class Main extends JPanel
        implements ActionListener, KeyListener, MouseListener, MouseMotionListener, FocusListener {

    static final int W = 960, H = 640;
    static final double PLAYER_R = 14, PLAYER_SPEED = 250, DASH_SPEED = 720, DASH_TIME = 0.16, DASH_COOLDOWN = 1.3;
    static final double SPAWN_TIME = 0.6;
    static final int MAX_ALIVE = 36, MAX_PARTICLES = 500;
    static final double[][] OBSTACLES = {
            {W * 0.28, H * 0.30, 38}, {W * 0.72, H * 0.30, 38},
            {W * 0.28, H * 0.70, 38}, {W * 0.72, H * 0.70, 38}};

    enum State {MENU, SETTINGS, PLAYING, PAUSED, GAME_OVER}

    private static final Map<State, EnumSet<State>> ALLOWED = new EnumMap<>(State.class);

    static {
        ALLOWED.put(State.MENU, EnumSet.of(State.PLAYING, State.SETTINGS));
        ALLOWED.put(State.SETTINGS, EnumSet.of(State.MENU));
        ALLOWED.put(State.PLAYING, EnumSet.of(State.PAUSED, State.GAME_OVER));
        ALLOWED.put(State.PAUSED, EnumSet.of(State.PLAYING, State.MENU));
        ALLOWED.put(State.GAME_OVER, EnumSet.of(State.PLAYING, State.MENU));
    }

    State state = State.MENU;

    void changeState(State next) {
        if (next == state) return;
        if (!ALLOWED.get(state).contains(next)) return;
        State prev = state;
        state = next;
        switch (next) {
            case PLAYING:
                if (prev != State.PAUSED) resetGame();
                break;
            case GAME_OVER:
                commitHighScore();
                sound.play(sound.over, settings.volume);
                break;
            case MENU:
                if (prev == State.PAUSED) commitHighScore();
                break;
            case SETTINGS:
                settingsRow = 0;
                break;
            default:
                break;
        }
    }

    enum Difficulty {
        EASY("Easy", 0.75, 0.90, 6), NORMAL("Normal", 1.0, 1.0, 5), HARD("Hard", 1.35, 1.12, 4);
        final String label;
        final double countMul, speedMul;
        final int playerHp;

        Difficulty(String l, double c, double s, int hp) {
            label = l;
            countMul = c;
            speedMul = s;
            playerHp = hp;
        }

        Difficulty cycle(int dir) {
            Difficulty[] v = values();
            return v[(ordinal() + dir + v.length) % v.length];
        }
    }

    static final class Settings {
        static final Path FILE = Paths.get(System.getProperty("user.home"), ".survivor_arena", "save.properties");
        int volume = 70;
        Difficulty difficulty = Difficulty.NORMAL;
        int highScore = 0;

        void load() {
            if (!Files.isRegularFile(FILE)) return;
            Properties p = new Properties();
            try (InputStream in = Files.newInputStream(FILE)) {
                p.load(in);
            } catch (IOException | IllegalArgumentException e) {
                return;
            }
            volume = Math.max(0, Math.min(100, parse(p.getProperty("volume"), 70)));
            highScore = Math.max(0, parse(p.getProperty("highScore"), 0));
            try {
                difficulty = Difficulty.valueOf(p.getProperty("difficulty", "NORMAL"));
            } catch (IllegalArgumentException ignored) {}
        }

        void save() {
            Properties p = new Properties();
            p.setProperty("volume", Integer.toString(volume));
            p.setProperty("difficulty", difficulty.name());
            p.setProperty("highScore", Integer.toString(highScore));
            try {
                Files.createDirectories(FILE.getParent());
                Path tmp = FILE.resolveSibling("save.properties.tmp");
                try (OutputStream out = Files.newOutputStream(tmp)) {
                    p.store(out, "Survivor Arena save data");
                }
                Files.move(tmp, FILE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {}
        }

        private static int parse(String s, int def) {
            try {
                return Integer.parseInt(s.trim());
            } catch (Exception e) {
                return def;
            }
        }
    }

    static final class Sound {
        static final float RATE = 22050f;
        static final AudioFormat FMT = new AudioFormat(RATE, 16, 1, true, false);
        final byte[] shoot = synth(900, 450, 60, 1, 0.12), hit = synth(320, 200, 50, 2, 0.30),
                kill = synth(420, 90, 170, 2, 0.40), hurt = synth(220, 70, 260, 1, 0.50),
                wave = synth(440, 880, 260, 0, 0.35), over = synth(300, 55, 750, 1, 0.45),
                click = synth(660, 660, 40, 0, 0.30), dash = synth(300, 900, 120, 0, 0.25);
        final AtomicInteger open = new AtomicInteger();
        int failures;
        boolean broken;

        static byte[] synth(double f0, double f1, int ms, int wave, double gain) {
            int n = (int) (RATE * ms / 1000.0);
            byte[] b = new byte[n * 2];
            Random r = new Random(7);
            double phase = 0;
            for (int i = 0; i < n; i++) {
                double t = i / (double) n;
                phase += 2 * Math.PI * (f0 + (f1 - f0) * t) / RATE;
                double s = wave == 0 ? Math.sin(phase) : wave == 1 ? (Math.sin(phase) >= 0 ? 0.6 : -0.6) : r.nextDouble() * 2 - 1;
                double env = (1 - t) * Math.min(1, i / (RATE * 0.004));
                short v = (short) (s * env * gain * 32767);
                b[2 * i] = (byte) v;
                b[2 * i + 1] = (byte) (v >> 8);
            }
            return b;
        }

        void play(byte[] src, int volume) {
            if (broken || volume <= 0 || open.get() >= 10) return;
            double g = Math.pow(volume / 100.0, 2);
            byte[] d = new byte[src.length];
            for (int i = 0; i < src.length; i += 2) {
                int s = (short) ((src[i] & 0xFF) | (src[i + 1] << 8));
                s = (int) (s * g);
                d[i] = (byte) s;
                d[i + 1] = (byte) (s >> 8);
            }
            try {
                Clip c = AudioSystem.getClip();
                c.open(FMT, d, 0, d.length);
                open.incrementAndGet();
                c.addLineListener(ev -> {
                    if (ev.getType() == LineEvent.Type.STOP) {
                        ev.getLine().close();
                        open.decrementAndGet();
                    }
                });
                c.start();
            } catch (Exception e) {
                if (++failures > 20) broken = true;
            }
        }
    }

    static abstract class Entity {
        boolean alive;
        double x, y, vx, vy, r;
    }

    static final class Bullet extends Entity {
        boolean friendly;
        double life;
    }

    static final class Particle extends Entity {
        double life, maxLife, size;
        int rgb;
    }

    static final class Loot extends Entity {
        int type;
        double life = 12.0;
    }

    enum EnemyType {
        CHASER(13, 2, 105, 1, 10, false), SHOOTER(14, 3, 80, 3, 30, true), DASHER(15, 4, 100, 2, 20, false);
        final double radius, hp, speed;
        final int cost, points;
        final boolean usesGun;

        EnemyType(double r, double hp, double s, int c, int p, boolean g) {
            radius = r;
            this.hp = hp;
            speed = s;
            cost = c;
            points = p;
            usesGun = g;
        }
    }

    static final class Enemy extends Entity {
        EnemyType type = EnemyType.CHASER;
        double hp, maxHp, speed;
        double spawnT, flash, timer, cool;
        double wx, wy;
        double dirX, dirY;
        double strafe = 1;
        int phase;
        double swingAngle = 0;
    }

    static final class Pool<T extends Entity> {
        final ArrayList<T> active = new ArrayList<>();
        final ArrayDeque<T> free = new ArrayDeque<>();
        final Supplier<T> factory;
        int created;

        Pool(Supplier<T> factory, int prewarm) {
            this.factory = factory;
            for (int i = 0; i < prewarm; i++) {
                free.push(factory.get());
                created++;
            }
        }

        T obtain() {
            T t = free.poll();
            if (t == null) {
                t = factory.get();
                created++;
            }
            t.alive = true;
            active.add(t);
            return t;
        }

        void sweep() {
            for (int i = active.size() - 1; i >= 0; i--) {
                T t = active.get(i);
                if (!t.alive) {
                    int last = active.size() - 1;
                    active.set(i, active.get(last));
                    active.remove(last);
                    free.push(t);
                }
            }
        }

        void clear() {
            for (T t : active) {
                t.alive = false;
                free.push(t);
            }
            active.clear();
        }
    }

    final Settings settings = new Settings();
    final Sound sound = new Sound();
    final Random rng = new Random();
    final javax.swing.Timer timer = new javax.swing.Timer(1000 / 60, this);
    long lastNanos = System.nanoTime();
    double fps = 60;
    boolean debug;

    final Pool<Enemy> enemies = new Pool<>(Enemy::new, 48);
    final Pool<Bullet> bullets = new Pool<>(Bullet::new, 160);
    final Pool<Particle> particles = new Pool<>(Particle::new, 300);
    final Pool<Loot> loots = new Pool<>(Loot::new, 40);

    final boolean[] keys = new boolean[1024];
    int mx = W / 2, my = H / 2;
    boolean mouseDown;
    int settingsRow;

    double px, py, pvx, pvy, invuln, shootCd, dashT, dashCd, dashDx, dashDy;
    int pHp, pMaxHp, ammo, maxCapAmmo, money;

    int score, wave;
    boolean newHigh;
    double gameTime, shake, menuTime;

    final ArrayList<EnemyType> spawnQueue = new ArrayList<>();
    boolean waveActive;
    double waveDelay, spawnTimer, spawnInterval, waveSpeedMul = 1, waveHpMul = 1, bannerT;
    String bannerText = "";

    double avx, avy;

    final Font fTitle = new Font(Font.SANS_SERIF, Font.BOLD, 56), fBig = new Font(Font.SANS_SERIF, Font.BOLD, 34),
            fMed = new Font(Font.SANS_SERIF, Font.BOLD, 20), fSmall = new Font(Font.SANS_SERIF, Font.PLAIN, 15),
            fHud = new Font(Font.MONOSPACED, Font.BOLD, 18);
    final Enemy[] previews = new Enemy[3];

    public Main() {
        setPreferredSize(new Dimension(W, H));
        setFocusable(true);
        setFocusTraversalKeysEnabled(false);
        setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
        addKeyListener(this);
        addMouseListener(this);
        addMouseMotionListener(this);
        addFocusListener(this);
        settings.load();
        for (int i = 0; i < 3; i++) {
            Enemy e = new Enemy();
            e.type = EnemyType.values()[i];
            e.r = e.type.radius;
            e.hp = e.maxHp = e.type.hp;
            previews[i] = e;
        }
        resetGame();
    }

    void start() {
        lastNanos = System.nanoTime();
        timer.start();
    }

    void saveNow() {
        if (state == State.PLAYING || state == State.PAUSED) commitHighScore();
        settings.save();
    }

    void commitHighScore() {
        if (score > settings.highScore) {
            settings.highScore = score;
            newHigh = true;
        }
        settings.save();
    }

    void resetGame() {
        enemies.clear();
        bullets.clear();
        particles.clear();
        loots.clear();
        spawnQueue.clear();
        px = W / 2.0;
        py = H / 2.0;
        pvx = pvy = 0;
        pMaxHp = pHp = settings.difficulty.playerHp;
        ammo = maxCapAmmo = 120;
        money = 0;
        invuln = 0.8;
        shootCd = dashT = dashCd = 0;
        score = 0;
        wave = 0;
        waveActive = false;
        waveDelay = 1.5;
        bannerT = 0;
        shake = 0;
        gameTime = 0;
        newHigh = false;
    }

    @Override
    public void actionPerformed(ActionEvent ev) {
        long now = System.nanoTime();
        double dt = Math.min(0.05, (now - lastNanos) / 1e9);
        lastNanos = now;
        step(dt);
        repaint();
    }

    void step(double dt) {
        fps += (1.0 / Math.max(dt, 1e-4) - fps) * 0.05;
        menuTime += dt;
        switch (state) {
            case PLAYING:
                updatePlaying(dt);
                break;
            case GAME_OVER:
                updateParticles(dt);
                particles.sweep();
                shake = Math.max(0, shake - 30 * dt);
                break;
            default:
                break;
        }
    }

    void updatePlaying(double dt) {
        gameTime += dt;
        shake = Math.max(0, shake - 30 * dt);
        bannerT = Math.max(0, bannerT - dt);
        updatePlayer(dt);
        updateWaves(dt);
        updateEnemies(dt);
        updateBullets(dt);
        updateLoots(dt);
        collisions();
        updateParticles(dt);
        enemies.sweep();
        bullets.sweep();
        particles.sweep();
        loots.sweep();
    }

    boolean down(int code) { return keys[code]; }

    void updatePlayer(double dt) {
        double ax = 0, ay = 0;
        if (down(KeyEvent.VK_W) || down(KeyEvent.VK_UP)) ay -= 1;
        if (down(KeyEvent.VK_S) || down(KeyEvent.VK_DOWN)) ay += 1;
        if (down(KeyEvent.VK_A) || down(KeyEvent.VK_LEFT)) ax -= 1;
        if (down(KeyEvent.VK_D) || down(KeyEvent.VK_RIGHT)) ax += 1;
        double len = Math.hypot(ax, ay);
        if (len > 0) { ax /= len; ay /= len; }

        invuln = Math.max(0, invuln - dt);
        shootCd -= dt;
        dashCd -= dt;

        if ((down(KeyEvent.VK_SHIFT) || down(KeyEvent.VK_SPACE)) && dashCd <= 0 && dashT <= 0) {
            if (len > 0) { dashDx = ax; dashDy = ay; }
            else {
                double a = Math.atan2(my - py, mx - px);
                dashDx = Math.cos(a); dashDy = Math.sin(a);
            }
            dashT = DASH_TIME;
            dashCd = DASH_COOLDOWN;
            invuln = Math.max(invuln, DASH_TIME + 0.06);
            sound.play(sound.dash, settings.volume);
        }

        if (dashT > 0) {
            dashT -= dt;
            pvx = dashDx * DASH_SPEED;
            pvy = dashDy * DASH_SPEED;
            if (rng.nextInt(2) == 0) burst(px, py, 0x87A868, 2, 60);
        } else {
            pvx = ax * PLAYER_SPEED;
            pvy = ay * PLAYER_SPEED;
        }

        px += pvx * dt; py += pvy * dt;
        px = Math.max(PLAYER_R, Math.min(W - PLAYER_R, px));
        py = Math.max(PLAYER_R, Math.min(H - PLAYER_R, py));
        double[] p = {px, py};
        pushOutOfObstacles(p, PLAYER_R);
        px = p[0]; py = p[1];

        if (mouseDown && shootCd <= 0 && ammo > 0) {
            double ang = Math.atan2(my - py, mx - px);
            int shots = Math.min(3, 1 + wave / 6);
            for (int i = 0; i < shots; i++) {
                if (ammo <= 0) break;
                double a = ang + (i - (shots - 1) / 2.0) * 0.13;
                spawnBullet(px + Math.cos(a) * (PLAYER_R + 8), py + Math.sin(a) * (PLAYER_R + 8), a, 680, true);
                ammo--;
            }
            shootCd = Math.max(0.08, 0.16 - 0.0025 * wave);
            sound.play(sound.shoot, settings.volume);
        }
    }

    void damagePlayer(double fromX, double fromY) {
        pHp--;
        invuln = 1.1;
        shake = 10;
        double a = Math.atan2(py - fromY, px - fromX);
        px += Math.cos(a) * 26; py += Math.sin(a) * 26;
        px = Math.max(PLAYER_R, Math.min(W - PLAYER_R, px));
        py = Math.max(PLAYER_R, Math.min(H - PLAYER_R, py));
        burst(px, py, 0xB03030, 14, 220);
        sound.play(sound.hurt, settings.volume);
        if (pHp <= 0) {
            burst(px, py, 0xFFFFFF, 60, 360);
            burst(px, py, 0x5A6840, 40, 260);
            shake = 18;
            changeState(State.GAME_OVER);
        }
    }

    void updateWaves(double dt) {
        if (!waveActive) {
            waveDelay -= dt;
            if (waveDelay <= 0) startWave();
            return;
        }
        if (!spawnQueue.isEmpty()) {
            spawnTimer -= dt;
            if (spawnTimer <= 0 && enemies.active.size() < MAX_ALIVE) {
                spawnEnemy(spawnQueue.remove(spawnQueue.size() - 1));
                spawnTimer = spawnInterval;
            }
        } else if (enemies.active.isEmpty()) {
            waveActive = false;
            waveDelay = 2.5;
            score += 50 * wave;

            pHp = Math.min(pMaxHp, pHp + 2);
            ammo += 40;
            maxCapAmmo = Math.max(maxCapAmmo, ammo);

            for (Bullet b : bullets.active) {
                if (!b.friendly) b.alive = false;
            }
            sound.play(sound.wave, settings.volume);
        }
    }

    void startWave() {
        wave++;
        Difficulty d = settings.difficulty;
        double budget = (5 + wave * 2.6 + Math.pow(wave, 1.3)) * d.countMul;
        waveSpeedMul = Math.min(1.7, 1 + 0.04 * (wave - 1)) * d.speedMul;
        waveHpMul = Math.min(3.0, 1 + 0.08 * (wave - 1));
        spawnInterval = Math.max(0.2, 0.95 - 0.04 * wave);
        double wChaser = Math.max(3, 10 - wave * 0.4);
        double wShooter = wave >= 2 ? Math.min(6, 1.5 + 0.5 * wave) : 0;
        double wDasher = wave >= 4 ? Math.min(5, 0.5 * wave) : 0;

        spawnQueue.clear();
        int remaining = (int) Math.round(budget);
        while (remaining > 0) {
            double ws = remaining >= EnemyType.SHOOTER.cost ? wShooter : 0;
            double wd = remaining >= EnemyType.DASHER.cost ? wDasher : 0;
            double roll = rng.nextDouble() * (wChaser + ws + wd);
            EnemyType t = roll < wChaser ? EnemyType.CHASER : roll < wChaser + ws ? EnemyType.SHOOTER : EnemyType.DASHER;
            spawnQueue.add(t);
            remaining -= t.cost;
        }
        waveActive = true;
        spawnTimer = 0.3;
        bannerText = "WARZONE WAVE " + wave;
        bannerT = 2.2;
        sound.play(sound.click, settings.volume);
    }

    void spawnEnemy(EnemyType t) {
        Enemy e = enemies.obtain();
        e.type = t;
        e.r = t.radius;
        e.maxHp = e.hp = Math.max(1, Math.round(t.hp * waveHpMul));
        e.speed = t.speed * waveSpeedMul;
        e.vx = e.vy = 0;
        e.spawnT = SPAWN_TIME;
        e.flash = 0;
        e.phase = 0;
        e.cool = 1.0;
        e.timer = shooterInterval();
        e.strafe = rng.nextBoolean() ? 1 : -1;
        e.wx = rng.nextDouble() * 100;
        e.wy = 0;
        e.swingAngle = 0;
        double m = 26;
        for (int tries = 0; tries < 14; tries++) {
            int side = rng.nextInt(4);
            double rx = m + rng.nextDouble() * (W - 2 * m), ry = m + rng.nextDouble() * (H - 2 * m);
            e.x = side == 0 ? rx : side == 1 ? rx : side == 2 ? m : W - m;
            e.y = side == 0 ? m : side == 1 ? H - m : ry;
            if (Math.hypot(e.x - px, e.y - py) > 260) break;
        }
        if (t == EnemyType.DASHER) pickWaypoint(e);
    }

    double shooterInterval() { return Math.max(0.9, 2.3 - 0.06 * wave) * (0.8 + 0.4 * rng.nextDouble()); }

    void pickWaypoint(Enemy e) {
        e.wx = 60 + rng.nextDouble() * (W - 120);
        e.wy = 60 + rng.nextDouble() * (H - 120);
    }

    void updateEnemies(double dt) {
        for (int i = 0; i < enemies.active.size(); i++) {
            Enemy e = enemies.active.get(i);
            if (e.alive) updateEnemy(e, dt);
        }
        int n = enemies.active.size();
        for (int i = 0; i < n; i++) {
            Enemy a = enemies.active.get(i);
            if (!a.alive || a.spawnT > 0) continue;
            for (int j = i + 1; j < n; j++) {
                Enemy b = enemies.active.get(j);
                if (!b.alive || b.spawnT > 0) continue;
                double dx = b.x - a.x, dy = b.y - a.y, min = a.r + b.r, d2 = dx * dx + dy * dy;
                if (d2 < min * min && d2 > 1e-6) {
                    double d = Math.sqrt(d2), push = (min - d) * 0.5;
                    if (a.phase != 2 || a.type != EnemyType.DASHER) { a.x -= dx / d * push; a.y -= dy / d * push; }
                    if (b.phase != 2 || b.type != EnemyType.DASHER) { b.x += dx / d * push; b.y += dy / d * push; }
                }
            }
        }
    }

    void updateEnemy(Enemy e, double dt) {
        if (e.spawnT > 0) { e.spawnT -= dt; return; }
        e.flash = Math.max(0, e.flash - dt);
        double dx = px - e.x, dy = py - e.y, dist = Math.hypot(dx, dy) + 1e-6, nx = dx / dist, ny = dy / dist;

        if (!e.type.usesGun) {
            e.swingAngle = Math.sin(gameTime * 14 + e.wx) * 0.75;
        }

        switch (e.type) {
            case CHASER: {
                avoid(e, nx, ny);
                double wob = Math.sin(gameTime * 3 + e.wx) * 0.25;
                double dvx = (nx - ny * wob + avx * 1.5) * e.speed, dvy = (ny + nx * wob + avy * 1.5) * e.speed;
                steer(e, dvx, dvy, e.speed * 5, dt);
                break;
            }
            case SHOOTER: {
                double pref = 300, dvx, dvy;
                if (dist > pref + 50) { dvx = nx; dvy = ny; }
                else if (dist < pref - 80) { dvx = -nx; dvy = -ny; }
                else { dvx = -ny * e.strafe; dvy = nx * e.strafe; }
                if (e.x < 50 || e.x > W - 50 || e.y < 50 || e.y > H - 50) {
                    double cx = W / 2.0 - e.x, cy = H / 2.0 - e.y, cl = Math.hypot(cx, cy) + 1e-6;
                    dvx += cx / cl; dvy += cy / cl;
                }
                avoid(e, dvx, dvy);
                double sp = e.speed * (e.timer < 0.35 ? 0.2 : 1.0);
                steer(e, (dvx + avx * 1.5) * sp, (dvy + avy * 1.5) * sp, sp * 4 + 120, dt);
                e.timer -= dt;
                if (e.timer <= 0) {
                    double bs = 230 * Math.min(1.5, waveSpeedMul);
                    double lead = 0.6 * dist / bs;
                    double ang = Math.atan2(py + pvx * lead - e.y, px + pvx * lead - e.x);
                    int shots = wave >= 8 ? 3 : 1;
                    for (int i = 0; i < shots; i++) {
                        double a = ang + (i - (shots - 1) / 2.0) * 0.22;
                        spawnBullet(e.x + Math.cos(a) * (e.r + 6), e.y + Math.sin(a) * (e.r + 6), a, bs, false);
                    }
                    e.timer = shooterInterval();
                    if (rng.nextInt(3) == 0) e.strafe = -e.strafe;
                }
                break;
            }
            case DASHER: {
                double dashSpeed = 480 * Math.min(1.4, waveSpeedMul);
                switch (e.phase) {
                    case 0: {
                        e.cool -= dt;
                        double wx = e.wx - e.x, wy = e.wy - e.y, wd = Math.hypot(wx, wy) + 1e-6;
                        if (wd < 24) pickWaypoint(e);
                        avoid(e, wx / wd, wy / wd);
                        steer(e, (wx / wd + avx * 1.5) * e.speed * 0.45, (wy / wd + avy * 1.5) * e.speed * 0.45, 400, dt);
                        if (dist < 340 && e.cool <= 0) { e.phase = 1; e.timer = 0.7; e.dirX = nx; e.dirY = ny; }
                        break;
                    }
                    case 1:
                        steer(e, 0, 0, 1200, dt);
                        e.timer -= dt;
                        if (e.timer > 0.2) { e.dirX = nx; e.dirY = ny; }
                        if (e.timer <= 0) { e.phase = 2; e.timer = 0.45; e.vx = e.dirX * dashSpeed; e.vy = e.dirY * dashSpeed; }
                        break;
                    case 2:
                        e.timer -= dt;
                        if (e.timer <= 0) { e.phase = 3; e.timer = 0.8; }
                        break;
                    default:
                        steer(e, 0, 0, 900, dt);
                        e.timer -= dt;
                        if (e.timer <= 0) { e.phase = 0; e.cool = Math.max(0.5, 1.6 - 0.04 * wave); pickWaypoint(e); }
                        break;
                }
                break;
            }
        }

        e.x += e.vx * dt; e.y += e.vy * dt;
        boolean hit = false;
        if (e.x < e.r) { e.x = e.r; hit = true; }
        else if (e.x > W - e.r) { e.x = W - e.r; hit = true; }
        if (e.y < e.r) { e.y = e.r; hit = true; }
        else if (e.y > H - e.r) { e.y = H - e.r; hit = true; }

        double[] p = {e.x, e.y};
        if (pushOutOfObstacles(p, e.r)) hit = true;
        e.x = p[0]; e.y = p[1];
        if (hit && e.type == EnemyType.DASHER && e.phase == 2) {
            e.phase = 3; e.timer = 1.0;
            burst(e.x, e.y, 0x6A5777, 8, 150);
        }
    }

    void steer(Enemy e, double dvx, double dvy, double accel, double dt) {
        double ex = dvx - e.vx, ey = dvy - e.vy, l = Math.hypot(ex, ey), maxD = accel * dt;
        if (l > maxD) { ex *= maxD / l; ey *= maxD / l; }
        e.vx += ex; e.vy += ey;
    }

    void avoid(Enemy e, double goalX, double goalY) {
        avx = avy = 0;
        for (double[] o : OBSTACLES) {
            double dx = e.x - o[0], dy = e.y - o[1], d = Math.hypot(dx, dy), range = o[2] + e.r + 50;
            if (d < range && d > 1e-4) {
                double str = (1 - d / range);
                double rx = dx / d, ry = dy / d;
                double cross = goalX * ry - goalY * rx;
                double tx = cross >= 0 ? -ry : ry;
                double ty = cross >= 0 ? rx : -rx;
                avx += (rx * 0.8 + tx * 0.6) * str;
                avy += (ry * 0.8 + ty * 0.6) * str;
            }
        }
    }

    void spawnBullet(double x, double y, double angle, double speed, boolean friendly) {
        Bullet b = bullets.obtain();
        b.x = x; b.y = y;
        b.vx = Math.cos(angle) * speed;
        b.vy = Math.sin(angle) * speed;
        b.r = friendly ? 4.0 : 4.5;
        b.friendly = friendly;
        b.life = 2.2;
    }

    void spawnLoot(double x, double y) {
        int chance = rng.nextInt(100);
        int type = -1;
        if (chance < 40) type = 1;      // Ammo
        else if (chance < 55) type = 0; // Health
        else if (chance < 75) type = 2; // Money Crate

        if (type != -1) {
            Loot l = loots.obtain();
            l.x = x; l.y = y;
            l.type = type;
            l.life = 12.0;
        }
    }

    void updateLoots(double dt) {
        for (int i = 0; i < loots.active.size(); i++) {
            Loot l = loots.active.get(i);
            if (!l.alive) continue;
            l.life -= dt;
            if (l.life <= 0) { l.alive = false; continue; }

            if (Math.hypot(px - l.x, py - l.y) < PLAYER_R + 12) {
                l.alive = false;
                if (l.type == 0) {
                    pHp = Math.min(pMaxHp, pHp + 2);
                } else if (l.type == 1) {
                    // Refill 20% of Yellow Ammo Bar Capacity
                    int ammoGain = (int) Math.round(maxCapAmmo * 0.20);
                    ammo += Math.max(25, ammoGain);
                } else if (l.type == 2) {
                    money += 100; // Increased Money Loot Drop by 100
                    score += 50;
                }
                sound.play(sound.click, settings.volume);
            }
        }
    }

    void updateBullets(double dt) {
        for (int i = 0; i < bullets.active.size(); i++) {
            Bullet b = bullets.active.get(i);
            if (!b.alive) continue;
            b.x += b.vx * dt; b.y += b.vy * dt; b.life -= dt;
            if (b.life <= 0 || b.x < 0 || b.x > W || b.y < 0 || b.y > H) { b.alive = false; continue; }
            for (double[] o : OBSTACLES) {
                if (Math.hypot(b.x - o[0], b.y - o[1]) < o[2] + b.r) {
                    b.alive = false;
                    burst(b.x, b.y, b.friendly ? 0xFFDD6E : 0xC84444, 4, 80);
                    break;
                }
            }
        }
    }

    void burst(double x, double y, int rgb, int count, double speedMax) {
        for (int i = 0; i < count; i++) {
            if (particles.active.size() >= MAX_PARTICLES) break;
            Particle p = particles.obtain();
            p.x = x; p.y = y;
            double a = rng.nextDouble() * Math.PI * 2;
            double sp = (0.2 + 0.8 * rng.nextDouble()) * speedMax;
            p.vx = Math.cos(a) * sp; p.vy = Math.sin(a) * sp;
            p.rgb = rgb;
            p.maxLife = p.life = 0.2 + 0.45 * rng.nextDouble();
            p.size = 2 + rng.nextDouble() * 3.5;
        }
    }

    void updateParticles(double dt) {
        for (int i = 0; i < particles.active.size(); i++) {
            Particle p = particles.active.get(i);
            if (!p.alive) continue;
            p.x += p.vx * dt; p.y += p.vy * dt;
            p.vx *= Math.pow(0.1, dt); p.vy *= Math.pow(0.1, dt);
            p.life -= dt;
            if (p.life <= 0) p.alive = false;
        }
    }

    void collisions() {
        for (int i = 0; i < bullets.active.size(); i++) {
            Bullet b = bullets.active.get(i);
            if (!b.alive || !b.friendly) continue;
            for (int j = 0; j < enemies.active.size(); j++) {
                Enemy e = enemies.active.get(j);
                if (!e.alive || e.spawnT > 0) continue;
                if (Math.hypot(b.x - e.x, b.y - e.y) < b.r + e.r) {
                    b.alive = false; e.hp--; e.flash = 0.12;
                    burst(b.x, b.y, 0x78A04B, 5, 120);
                    sound.play(sound.hit, settings.volume);
                    if (e.hp <= 0) {
                        e.alive = false; score += e.type.points;
                        int color = e.type == EnemyType.CHASER ? 0x7D975C : e.type == EnemyType.SHOOTER ? 0xD2A032 : 0x6A5777;
                        burst(e.x, e.y, color, 22, 220);
                        spawnLoot(e.x, e.y);
                        sound.play(sound.kill, settings.volume);
                    }
                    break;
                }
            }
        }

        if (invuln <= 0) {
            for (int i = 0; i < bullets.active.size(); i++) {
                Bullet b = bullets.active.get(i);
                if (!b.alive || b.friendly) continue;
                if (Math.hypot(b.x - px, b.y - py) < b.r + PLAYER_R) {
                    b.alive = false; damagePlayer(b.x, b.y);
                    break;
                }
            }
        }

        if (invuln <= 0) {
            for (int i = 0; i < enemies.active.size(); i++) {
                Enemy e = enemies.active.get(i);
                if (!e.alive || e.spawnT > 0) continue;
                if (Math.hypot(px - e.x, py - e.y) < PLAYER_R + e.r) {
                    damagePlayer(e.x, e.y);
                    break;
                }
            }
        }
    }

    boolean pushOutOfObstacles(double[] pos, double radius) {
        boolean hit = false;
        for (double[] o : OBSTACLES) {
            double dx = pos[0] - o[0], dy = pos[1] - o[1], d = Math.hypot(dx, dy), min = o[2] + radius;
            if (d < min) {
                hit = true;
                if (d < 1e-4) { dx = 1; dy = 0; d = 1; }
                pos[0] = o[0] + (dx / d) * min;
                pos[1] = o[1] + (dy / d) * min;
            }
        }
        return hit;
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        if (shake > 0) {
            g2.translate((rng.nextDouble() - 0.5) * shake, (rng.nextDouble() - 0.5) * shake);
        }

        drawWarzoneBackground(g2);

        for (double[] o : OBSTACLES) {
            drawSandbagBarricade(g2, o[0], o[1], o[2]);
        }

        if (state == State.PLAYING || state == State.PAUSED || state == State.GAME_OVER) {
            drawGame(g2);
        }
        switch (state) {
            case MENU: drawMenu(g2); break;
            case SETTINGS: drawSettings(g2); break;
            case PAUSED: drawOverlay(g2, "PAUSED", "Press ESC or P or Click PAUSE to Resume"); break;
            case GAME_OVER: drawOverlay(g2, "MISSION FAILED", "Press SPACE to Restart | ESC for Menu"); break;
            default: break;
        }

        if (debug) drawDebug(g2);
        g2.dispose();
    }

    void drawWarzoneBackground(Graphics2D g2) {
        g2.setColor(new Color(73, 75, 63));
        g2.fillRect(0, 0, W, H);

        Random r = new Random(42);
        for (int i = 0; i < 200; i++) {
            int x = r.nextInt(W), y = r.nextInt(H), s = 3 + r.nextInt(14);
            g2.setColor(new Color(45 + r.nextInt(35), 44 + r.nextInt(30), 35 + r.nextInt(25)));
            g2.fillPolygon(new int[]{x, x + s, x + s / 2}, new int[]{y, y + s / 2, y + s}, 3);
        }

        g2.setColor(new Color(48, 50, 47));
        g2.fillRect(W / 2 - 120, 0, 240, H);
        g2.setColor(new Color(125, 113, 76));
        for (int y = 0; y < H; y += 75) {
            g2.fillRect(W / 2 - 3, y, 6, 35);
        }

        g2.setColor(new Color(0, 0, 0, 45));
        g2.fillRect(0, 0, W, H);
    }

    void drawSandbagBarricade(Graphics2D g2, double ox, double oy, double r) {
        int cx = (int) ox, cy = (int) oy;
        int rows = 3, cols = 4;
        int bw = 25, bh = 16;
        int startX = cx - (cols * bw) / 2;
        int startY = cy - (rows * bh) / 2;

        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                int x = startX + col * 23 + (row % 2) * 8;
                int y = startY + row * 14;
                g2.setColor(new Color(112, 103, 69));
                g2.fillRoundRect(x, y, bw, bh, 7, 7);
                g2.setColor(new Color(48, 48, 37));
                g2.drawRoundRect(x, y, bw, bh, 7, 7);
            }
        }
    }

    void drawLoot(Graphics2D g2, Loot l) {
        Color c;
        String label;
        switch (l.type) {
            case 0: c = Color.GREEN; label = "+"; break;
            case 1: c = Color.YELLOW; label = "A"; break;
            default: c = Color.ORANGE; label = "$"; break;
        }

        g2.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 55));
        g2.fillOval((int) l.x - 14, (int) l.y - 14, 28, 28);
        g2.setColor(c);
        g2.fillRoundRect((int) l.x - 10, (int) l.y - 10, 20, 20, 5, 5);
        g2.setColor(Color.BLACK);
        g2.setFont(new Font("Arial", Font.BOLD, 14));
        g2.drawString(label, (int) l.x - 4, (int) l.y + 5);
    }

    void drawGame(Graphics2D g2) {
        for (int i = 0; i < loots.active.size(); i++) {
            Loot l = loots.active.get(i);
            if (l.alive) drawLoot(g2, l);
        }

        for (int i = 0; i < particles.active.size(); i++) {
            Particle p = particles.active.get(i);
            if (!p.alive) continue;
            float alpha = (float) Math.max(0, Math.min(1, p.life / p.maxLife));
            Color c = new Color((p.rgb >> 16) & 0xFF, (p.rgb >> 8) & 0xFF, p.rgb & 0xFF, (int) (alpha * 255));
            g2.setColor(c);
            int sz = (int) Math.max(1, p.size * alpha);
            g2.fillOval((int) (p.x - sz / 2.0), (int) (p.y - sz / 2.0), sz, sz);
        }

        for (int i = 0; i < bullets.active.size(); i++) {
            Bullet b = bullets.active.get(i);
            if (!b.alive) continue;
            g2.setColor(b.friendly ? new Color(255, 221, 110) : new Color(220, 60, 60));
            int sz = (int) (b.r * 2);
            g2.fillOval((int) (b.x - b.r), (int) (b.y - b.r), sz, sz);
        }

        for (int i = 0; i < enemies.active.size(); i++) {
            Enemy e = enemies.active.get(i);
            if (e.alive) drawEnemy(g2, e);
        }

        if (state != State.GAME_OVER || pHp > 0) {
            if (invuln <= 0 || ((int) (gameTime * 25) % 2 == 0)) {
                drawSoldierPlayer(g2);
            }
        }

        // Left Tactical HUD with Money Counter
        g2.setColor(new Color(5, 10, 8, 220));
        g2.fillRoundRect(12, 12, 230, 130, 12, 12);
        g2.setColor(Color.WHITE);
        g2.setFont(fHud);
        g2.drawString("WARZONE", 23, 35);

        g2.setFont(fSmall);
        g2.drawString("SCORE: " + score, 23, 58);
        g2.drawString("WAVE: " + wave, 23, 78);
        g2.drawString("AMMO: " + ammo, 23, 98);
        g2.setColor(new Color(255, 215, 0));
        g2.drawString("MONEY: $" + money, 23, 118);

        // Right HP & Yellow Ammo Bar
        g2.setColor(Color.WHITE);
        g2.drawString("HP:", W - 180, 32);
        for (int i = 0; i < pMaxHp; i++) {
            g2.setColor(i < pHp ? new Color(100, 200, 80) : new Color(50, 55, 45));
            g2.fillRect(W - 140 + i * 20, 18, 16, 16);
            g2.setColor(Color.WHITE);
            g2.drawRect(W - 140 + i * 20, 18, 16, 16);
        }

        // AMMO BAR (Yellow - Drops continuous & refills 20% on pickup)
        g2.setColor(new Color(40, 48, 38));
        g2.fillRect(W - 180, 46, 140, 12);
        double ammoPercent = Math.max(0, Math.min(1.0, (double) ammo / maxCapAmmo));
        g2.setColor(ammo > 20 ? new Color(255, 221, 110) : new Color(255, 60, 60));
        g2.fillRect(W - 180, 46, (int) (140 * ammoPercent), 12);
        g2.setColor(Color.WHITE);
        g2.drawRect(W - 180, 46, 140, 12);

        // Interactive Pause / Play UI Button
        int btnX = W - 100, btnY = 70, btnW = 85, btnH = 26;
        g2.setColor(state == State.PAUSED ? new Color(200, 50, 50) : new Color(40, 140, 80));
        g2.fillRoundRect(btnX, btnY, btnW, btnH, 8, 8);
        g2.setColor(Color.WHITE);
        g2.drawRoundRect(btnX, btnY, btnW, btnH, 8, 8);
        g2.setFont(fSmall);
        g2.drawString(state == State.PAUSED ? "▶ PLAY" : "|| PAUSE", btnX + 10, btnY + 18);

        if (bannerT > 0) {
            float alpha = (float) Math.min(1.0, bannerT);
            g2.setFont(fBig);
            g2.setColor(new Color(255, 255, 255, (int) (alpha * 255)));
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(bannerText, (W - fm.stringWidth(bannerText)) / 2, H / 3);
        }
    }

    void drawSoldierPlayer(Graphics2D g2) {
        Graphics2D q = (Graphics2D) g2.create();
        q.translate(px, py);
        double angle = Math.atan2(my - py, mx - px) + Math.PI / 2;
        q.rotate(angle);

        q.setColor(new Color(25, 28, 23));
        q.fillOval(-13, -5, 9, 20);
        q.fillOval(4, -5, 9, 20);

        q.setColor(new Color(90, 104, 64));
        q.fillRoundRect(-13, -16, 26, 29, 7, 7);
        q.setColor(new Color(45, 56, 39));
        q.fillRoundRect(-9, -12, 18, 19, 4, 4);

        q.setColor(new Color(142, 129, 82));
        q.fillRect(-7, -9, 5, 6);
        q.fillRect(2, -9, 5, 6);
        q.fillRect(-7, 0, 5, 5);
        q.fillRect(2, 0, 5, 5);

        q.setColor(new Color(90, 104, 64));
        q.fillRoundRect(-18, -12, 8, 18, 4, 4);
        q.fillRoundRect(10, -12, 8, 18, 4, 4);

        q.setColor(new Color(179, 145, 108));
        q.fillOval(-8, -28, 16, 16);
        q.setColor(new Color(51, 67, 44));
        q.fillOval(-11, -32, 22, 16);
        q.setColor(new Color(135, 135, 83));
        q.drawArc(-10, -31, 20, 13, 0, 180);

        q.setColor(new Color(27, 30, 26));
        q.fillRoundRect(-3, -42, 6, 29, 2, 2);
        q.setColor(new Color(130, 130, 112));
        q.fillRect(-2, -48, 4, 15);

        q.dispose();
    }

    void drawEnemy(Graphics2D g2, Enemy e) {
        if (e.spawnT > 0) {
            float alpha = (float) (1.0 - e.spawnT / SPAWN_TIME);
            g2.setColor(new Color(125, 151, 92, (int) (alpha * 120)));
            int sz = (int) (e.r * 2);
            g2.drawOval((int) (e.x - e.r), (int) (e.y - e.r), sz, sz);
            return;
        }

        Graphics2D q = (Graphics2D) g2.create();
        q.translate(e.x, e.y);
        double angle = Math.atan2(py - e.y, px - e.x) + Math.PI / 2;
        q.rotate(angle);

        int r = (int) e.r;

        if (e.type.usesGun) {
            // Mercenary Shooter Top-Down Texture
            q.setColor(new Color(82, 88, 55));
            q.fillRoundRect(-14, 2, 28, 16, 6, 6);
            q.setColor(new Color(45, 52, 32));
            q.fillRect(-12, 4, 8, 12);
            q.fillRect(4, 4, 8, 12);

            q.setColor(new Color(38, 56, 48));
            q.fillRoundRect(-16, -14, 8, 20, 4, 4);
            q.fillRoundRect(8, -14, 8, 20, 4, 4);

            q.setColor(new Color(80, 50, 40));
            q.fillOval(-12, -26, 8, 8);
            q.fillOval(6, -26, 8, 8);

            q.setColor(new Color(150, 75, 25));
            q.fillRect(-18, -28, 36, 5);
            q.setColor(new Color(20, 20, 20));
            q.fillRect(10, -28, 12, 5);
            q.fillRect(20, -29, 3, 7);

            q.setColor(new Color(210, 160, 120));
            q.fillOval(-6, -16, 12, 12);
            q.setColor(new Color(35, 35, 35));
            q.fillOval(-10, -20, 20, 18);
            q.setColor(new Color(20, 20, 20));
            q.fillOval(-9, -22, 18, 8);

            q.setColor(Color.BLACK);
            q.setStroke(new BasicStroke(1.5f));
            q.drawOval(-10, -20, 20, 18);
            q.drawRoundRect(-14, 2, 28, 16, 6, 6);
            q.setStroke(new BasicStroke(1.0f));

            if (e.timer < 0.35) {
                q.setColor(new Color(255, 50, 50, 180));
                q.drawLine(20, -26, 300, -26);
            }
        } else if (e.type == EnemyType.CHASER) {
            // HIGH-TEXTURE MELEE ZOMBIE / ENFORCER
            // Boots & Tattered Trousers
            q.setColor(new Color(35, 40, 30));
            q.fillRoundRect(-12, 2, 9, 16, 3, 3);
            q.fillRoundRect(3, 2, 9, 16, 3, 3);

            // Tactical Armor Vest with Straps
            q.setColor(new Color(65, 80, 50));
            q.fillRoundRect(-14, -14, 28, 24, 6, 6);
            q.setColor(new Color(35, 45, 28));
            q.fillRect(-10, -10, 20, 16);
            q.setColor(new Color(110, 120, 85));
            q.fillRect(-8, -8, 6, 5);
            q.fillRect(2, -8, 6, 5);

            // Muscular Ripped Arms
            q.setColor(new Color(90, 120, 65));
            q.fillRoundRect(-21, -14, 9, 22, 4, 4);
            q.fillRoundRect(12, -14, 9, 22, 4, 4);

            // Head, Goggles & Face Detail
            q.setColor(new Color(110, 140, 80));
            q.fillOval(-10, -28, 20, 20);
            q.setColor(new Color(20, 25, 20));
            q.fillRect(-8, -24, 16, 6);
            q.setColor(Color.RED);
            q.fillRect(-6, -23, 4, 4);
            q.fillRect(2, -23, 4, 4);

            // Steel Machete / Weapon Blade
            q.rotate(e.swingAngle);
            q.setColor(new Color(180, 185, 195));
            Path2D blade = new Path2D.Double();
            blade.moveTo(10, -12);
            blade.lineTo(22, -38);
            blade.lineTo(15, -42);
            blade.closePath();
            q.fill(blade);
            q.setColor(new Color(180, 40, 40)); // Blood stain on blade tip
            q.fillOval(16, -38, 5, 5);

        } else {
            // DASHER: Cyber-exoskeleton Melee Specialist
            q.setColor(new Color(60, 40, 80));
            q.fillRoundRect(-14, -14, 28, 26, 6, 6);
            q.setColor(new Color(140, 60, 200));
            q.drawRoundRect(-14, -14, 28, 26, 6, 6);

            q.setColor(new Color(100, 70, 130));
            q.fillOval(-11, -28, 22, 22);
            q.setColor(new Color(0, 229, 255));
            q.fillRect(-8, -22, 16, 5);

            q.rotate(e.swingAngle);
            q.setColor(new Color(200, 100, 255));
            Path2D blade = new Path2D.Double();
            blade.moveTo(10, -10);
            blade.lineTo(26, -36);
            blade.lineTo(18, -40);
            blade.closePath();
            q.fill(blade);

            if (e.phase == 1) {
                q.setColor(new Color(200, 50, 255, 180));
                q.drawLine(0, 0, 0, -300);
            }
        }

        q.dispose();

        if (e.hp < e.maxHp) {
            g2.setColor(Color.BLACK);
            g2.fillRect((int) e.x - 14, (int) e.y - r - 12, 28, 4);
            g2.setColor(e.type == EnemyType.DASHER ? Color.MAGENTA : Color.GREEN);
            g2.fillRect((int) e.x - 14, (int) e.y - r - 12, Math.max(0, (int) (28 * (e.hp / e.maxHp))), 4);
        }
    }

    void drawMenu(Graphics2D g2) {
        g2.setColor(new Color(10, 14, 12, 230));
        g2.fillRect(0, 0, W, H);

        g2.setColor(new Color(125, 151, 92));
        g2.setFont(fTitle);
        drawCenteredString(g2, "WARZONE SURVIVOR", H / 4);

        g2.setFont(fMed);
        g2.setColor(Color.WHITE);
        drawCenteredString(g2, "PRESS SPACE TO START MISSION", H / 2 - 20);
        drawCenteredString(g2, "PRESS S FOR SETTINGS", H / 2 + 20);

        g2.setFont(fSmall);
        g2.setColor(Color.GRAY);
        drawCenteredString(g2, "HIGH SCORE: " + settings.highScore, H / 2 + 70);

        for (int i = 0; i < 3; i++) {
            Enemy e = previews[i];
            e.x = W / 2.0 - 120 + i * 120;
            e.y = H * 0.8;
            drawEnemy(g2, e);
        }
    }

    void drawSettings(Graphics2D g2) {
        g2.setColor(new Color(10, 14, 12, 240));
        g2.fillRect(0, 0, W, H);

        g2.setColor(new Color(125, 151, 92));
        g2.setFont(fBig);
        drawCenteredString(g2, "MISSION CONFIG", 100);

        g2.setFont(fMed);
        String[] rows = {
                "Volume: < " + settings.volume + "% >",
                "Difficulty: < " + settings.difficulty.label + " >",
                "Back to Menu"
        };

        for (int i = 0; i < rows.length; i++) {
            g2.setColor(i == settingsRow ? Color.YELLOW : Color.WHITE);
            drawCenteredString(g2, (i == settingsRow ? "> " : "  ") + rows[i] + (i == settingsRow ? " <" : "  "), 220 + i * 50);
        }

        g2.setFont(fSmall);
        g2.setColor(Color.GRAY);
        drawCenteredString(g2, "Use UP/DOWN to navigate | LEFT/RIGHT or ENTER to adjust", H - 80);
    }

    void drawOverlay(Graphics2D g2, String title, String sub) {
        g2.setColor(new Color(0, 0, 0, 190));
        g2.fillRect(0, 0, W, H);

        g2.setColor(Color.WHITE);
        g2.setFont(fBig);
        drawCenteredString(g2, title, H / 2 - 20);

        g2.setFont(fMed);
        g2.setColor(Color.LIGHT_GRAY);
        drawCenteredString(g2, sub, H / 2 + 30);
    }

    void drawDebug(Graphics2D g2) {
        g2.setFont(fHud);
        g2.setColor(Color.GREEN);
        int y = 80;
        g2.drawString(String.format("FPS: %.1f", fps), 20, y += 20);
        g2.drawString("Active Enemies: " + enemies.active.size() + " (Created: " + enemies.created + ")", 20, y += 20);
        g2.drawString("Active Bullets: " + bullets.active.size() + " (Created: " + bullets.created + ")", 20, y += 20);
        g2.drawString("Active Particles: " + particles.active.size() + " (Created: " + particles.created + ")", 20, y += 20);
    }

    void drawCenteredString(Graphics2D g2, String text, int y) {
        FontMetrics fm = g2.getFontMetrics();
        g2.drawString(text, (W - fm.stringWidth(text)) / 2, y);
    }

    @Override
    public void keyPressed(KeyEvent e) {
        int c = e.getKeyCode();
        if (c >= 0 && c < keys.length) keys[c] = true;

        if (c == KeyEvent.VK_F3) debug = !debug;

        if (state == State.MENU) {
            if (c == KeyEvent.VK_SPACE || c == KeyEvent.VK_ENTER) changeState(State.PLAYING);
            if (c == KeyEvent.VK_S) changeState(State.SETTINGS);
        } else if (state == State.SETTINGS) {
            if (c == KeyEvent.VK_UP || c == KeyEvent.VK_W) settingsRow = (settingsRow - 1 + 3) % 3;
            if (c == KeyEvent.VK_DOWN || c == KeyEvent.VK_S) settingsRow = (settingsRow + 1) % 3;
            if (c == KeyEvent.VK_LEFT || c == KeyEvent.VK_A) adjustSettings(-1);
            if (c == KeyEvent.VK_RIGHT || c == KeyEvent.VK_D) adjustSettings(1);
            if (c == KeyEvent.VK_ENTER || c == KeyEvent.VK_ESCAPE) {
                if (settingsRow == 2 || c == KeyEvent.VK_ESCAPE) {
                    settings.save();
                    changeState(State.MENU);
                } else {
                    adjustSettings(1);
                }
            }
        } else if (state == State.PLAYING) {
            if (c == KeyEvent.VK_ESCAPE || c == KeyEvent.VK_P) changeState(State.PAUSED);
        } else if (state == State.PAUSED) {
            if (c == KeyEvent.VK_ESCAPE || c == KeyEvent.VK_P) changeState(State.PLAYING);
            if (c == KeyEvent.VK_M) changeState(State.MENU);
        } else if (state == State.GAME_OVER) {
            if (c == KeyEvent.VK_SPACE) changeState(State.PLAYING);
            if (c == KeyEvent.VK_ESCAPE) changeState(State.MENU);
        }
    }

    void adjustSettings(int dir) {
        sound.play(sound.click, settings.volume);
        if (settingsRow == 0) {
            settings.volume = Math.max(0, Math.min(100, settings.volume + dir * 10));
        } else if (settingsRow == 1) {
            settings.difficulty = settings.difficulty.cycle(dir);
        }
    }

    @Override public void keyReleased(KeyEvent e) { if (e.getKeyCode() < keys.length) keys[e.getKeyCode()] = false; }
    @Override public void keyTyped(KeyEvent e) {}
    @Override public void mousePressed(MouseEvent e) {
        if (e.getButton() == MouseEvent.BUTTON1) {
            mouseDown = true;
            // Check Click on Pause/Play Button
            int btnX = W - 100, btnY = 70, btnW = 85, btnH = 26;
            if (e.getX() >= btnX && e.getX() <= btnX + btnW && e.getY() >= btnY && e.getY() <= btnY + btnH) {
                if (state == State.PLAYING) changeState(State.PAUSED);
                else if (state == State.PAUSED) changeState(State.PLAYING);
            }
        }
    }
    @Override public void mouseReleased(MouseEvent e) { if (e.getButton() == MouseEvent.BUTTON1) mouseDown = false; }
    @Override public void mouseMoved(MouseEvent e) { mx = e.getX(); my = e.getY(); }
    @Override public void mouseDragged(MouseEvent e) { mx = e.getX(); my = e.getY(); }
    @Override public void mouseClicked(MouseEvent e) {}
    @Override public void mouseEntered(MouseEvent e) {}
    @Override public void mouseExited(MouseEvent e) {}
    @Override public void focusGained(FocusEvent e) {}
    @Override public void focusLost(FocusEvent e) { for (int i = 0; i < keys.length; i++) keys[i] = false; mouseDown = false; }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("Warzone Survival");
            Main game = new Main();
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setResizable(false);
            frame.add(game);
            frame.pack();
            frame.setLocationRelativeTo(null);
            Runtime.getRuntime().addShutdownHook(new Thread(game::saveNow));
            frame.setVisible(true);
            game.start();
        });
    }
}