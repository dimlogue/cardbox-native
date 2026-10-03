package com.igll.carddbnative;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Outline;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.LruCache;
import android.view.Gravity;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.HorizontalScrollView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 卡盒 · 原生版（Phase 1）
 * 纯 Java 手写界面，不依赖任何第三方库；数据与混合版同一份 cards.json。
 * 本文件：数据模型 + 五页骨架（全部卡片 / 学生推荐 / 我的卡片 / 资讯 / 设置）+ 详情页。
 * 仍在迁移中的功能会明确标「迁移中」，不装样子。
 */
public class MainActivity extends Activity {
    // ── P4-fix 统一动画语言（全 App 共用，禁止各处自创曲线）──
    // 进入：先快后稳的大减速；退出：短促收尾；按压回弹：轻微 overshoot 弹簧感。
    static final DecelerateInterpolator ANIM_ENTER = new DecelerateInterpolator(2.2f);
    static final DecelerateInterpolator ANIM_EXIT = new DecelerateInterpolator(1.6f);
    static final OvershootInterpolator ANIM_SPRING = new OvershootInterpolator(1.4f);
    static final int ANIM_DUR_PAGE = 260;    // 切页 淡入+上移
    static final int ANIM_DUR_SHEET_IN = 280; // 悬浮窗升起
    static final int ANIM_DUR_SHEET_OUT = 190; // 悬浮窗收起
    static final int ANIM_DUR_FADE = 220;    // 普通淡入/步骤切换
    /** 通用按压反馈：按下 90ms 缩到 .92，松手 260ms 弹簧回弹到 1.0（微 overshoot）。 */
    static void pressBounce(View v, boolean down) {
        if (v == null) return;
        v.animate().cancel();
        if (down) v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(90).setInterpolator(ANIM_EXIT).start();
        else v.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(ANIM_SPRING).start();
    }


    // ---------- 小工具 ----------
    static int dp(Context c, float v) { return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f); }

    // 沉浸式状态栏高度（各页顶部留白与首页悬浮栏定位用）
    int statusBarH() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : dp(this, 24);
    }
    // P2d-fix：带大标题页面的顶部安全留白 = 状态栏高度 + 舒适间距，标题文字绝不进状态栏
    int pageTopPad() { return statusBarH() + dp(this, 16); }
    // Q26：edge-to-edge 后系统导航栏（手势条/三键）真实高度——API 30+ 由 WindowInsets 实测，
    // 低版本回落系统 dimen；拿不到记 0（内容照常铺底，只是不额外避让）
    int navInsetPx = -1;
    int navBarH() {
        if (navInsetPx >= 0) return navInsetPx;
        int id = getResources().getIdentifier("navigation_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : 0;
    }
    // Q26：导航栏高度实测到达后，把常驻底部控件（dock/悬浮钮）抬到手势条之上
    void applyNavInset() {
        if (navWrap != null && navWrap.getLayoutParams() instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) navWrap.getLayoutParams();
            int want = dp(this, 12) + navBarH();
            if (lp.bottomMargin != want) { lp.bottomMargin = want; navWrap.setLayoutParams(lp); }
        }
        if (searchFab != null && searchFab.getParent() != null
            && searchFab.getLayoutParams() instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) searchFab.getLayoutParams();
            lp.bottomMargin = dp(this, 166) + navBarH(); searchFab.setLayoutParams(lp);
        }
        if (filterFab != null && filterFab.getParent() != null
            && filterFab.getLayoutParams() instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) filterFab.getLayoutParams();
            lp.bottomMargin = dp(this, 108) + navBarH(); filterFab.setLayoutParams(lp);
        }
        syncTopFab();
    }
    // P2d-fix：长页面底部安全留白，确保末行能完整滚出悬浮 dock 之外（dock 高约 67dp+底边距 12dp）
    int dockPad() { return dp(this, 112) + navBarH(); } // Q26：再加导航栏避让，末行滚出抬高后的 dock
    // P-scroll：全 App 长列表统一细淡滚动条——3dp 细窄、低对比蓝灰，滚动时显、停稳后淡出，不许一根长粗条挂右边
    void thinScrollbar(ScrollView sv) { thinScrollbar(sv, true); }
    // 更新日志这类短框常驻需求：同款细淡，但不自动淡出（用户 15:28 要求右侧滑杆常显）
    void thinScrollbarPersistent(ScrollView sv) { thinScrollbar(sv, false); }
    void thinScrollbar(ScrollView sv, boolean fade) {
        if (sv == null) return;
        sv.setVerticalScrollBarEnabled(true);
        sv.setHorizontalScrollBarEnabled(false);
        sv.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);
        sv.setScrollbarFadingEnabled(fade);
        if (fade) {
            sv.setScrollBarFadeDuration(650);
            sv.setScrollBarDefaultDelayBeforeFade(350);
        }
        try { sv.setScrollBarSize(dp(this, 3)); } catch (Exception e) { /* 个别机型静默 */ }
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                GradientDrawable thumb = new GradientDrawable();
                thumb.setColor(Color.argb(118, 92, 108, 140));
                thumb.setCornerRadius(dp(this, 3));
                thumb.setSize(dp(this, 3), dp(this, 48));
                sv.setVerticalScrollbarThumbDrawable(thumb);
                GradientDrawable track = new GradientDrawable();
                track.setColor(Color.TRANSPARENT);
                sv.setVerticalScrollbarTrackDrawable(track);
            } catch (Exception e) { /* 低版本/个别机型回落系统细条 */ }
        }
    }
    // P-scroll：当前长列表（回顶钮指向它）——详情/更新日志为覆盖层时优先于底下主页
    ScrollView activeLongScroll() {
        if (changelogOpen && changelogScroll != null) return changelogScroll;
        if (detailScroll != null && detailCard != null) return detailScroll;
        switch (tab) {
            case "mine": return mineScrollView;
            case "student": return studentScroll;
            case "news": return newsScroll;
            case "settings": return settingsScroll;
            default: return homeScroll;
        }
    }
    // Q5 回顶钮时机：滑动中藏起，停稳 650ms 后才淡入；滚深门槛 480dp（对照混合版 qfScrolling/qfTop）
    int topFabLastY = 0;
    Runnable topFabShowTask = null;
    void cancelTopFabShow() {
        if (topFabShowTask != null) { mainHandler.removeCallbacks(topFabShowTask); topFabShowTask = null; }
    }
    void showTopFab() {
        if (topFab == null || topFabShown) return;
        topFabShown = true;
        topFab.animate().cancel();
        topFab.setVisibility(View.VISIBLE);
        topFab.setAlpha(0f); topFab.setScaleX(0.82f); topFab.setScaleY(0.82f);
        topFab.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(190)
            .setInterpolator(ANIM_ENTER).start();
    }
    void hideTopFab() {
        if (topFab == null || !topFabShown) return;
        topFabShown = false;
        final View fab = topFab;
        fab.animate().cancel();
        fab.animate().alpha(0f).scaleX(0.85f).scaleY(0.85f).setDuration(170)
            .setInterpolator(ANIM_EXIT)
            .withEndAction(() -> { if (fab == topFab && !topFabShown) fab.setVisibility(View.GONE); }).start();
    }
    void updateTopFabVisibility(int y) {
        if (topFab == null) return;
        topFabLastY = y;
        // 回到顶部附近：立即隐去并取消待显
        if (y <= dp(this, 480)) { cancelTopFabShow(); hideTopFab(); return; }
        // 还在滑动：先藏，停稳 650ms 后由定时器按最新位置决定是否淡入
        hideTopFab();
        cancelTopFabShow();
        topFabShowTask = () -> {
            topFabShowTask = null;
            if (topFab != null && topFabLastY > dp(this, 480)) showTopFab();
        };
        mainHandler.postDelayed(topFabShowTask, 650);
    }
    void syncTopFab() {
        // Q5：回顶钮只在全部卡片/学生/我的卡片三个列表页出现（对照混合版 syncQuickFab 的 tab 口径）；
        // 设置/资讯不挂，浮窗（筛选/选卡/欢迎/关于/详情/日志）升起时退场（Q12 名单先行落地这一钮）
        boolean pageOk = "home".equals(tab) || "student".equals(tab) || "mine".equals(tab);
        boolean covered = welcomeOpen || wizardOpen || aboutOpen || filterSheet != null
            || detailCard != null || changelogOpen;
        ScrollView sv = activeLongScroll();
        if (sv == null || covered || !pageOk) {
            cancelTopFabShow();
            if (topFab != null && topFab.getParent() != null) ((ViewGroup) topFab.getParent()).removeView(topFab);
            topFab = null;
            topFabShown = false;
            return;
        }
        if (topFab == null || topFab.getParent() != content) {
            if (topFab != null && topFab.getParent() != null) ((ViewGroup) topFab.getParent()).removeView(topFab);
            topFab = buildTopFab();
            topFabShown = false;
            // Q5：页面下方正中、悬浮栏上方居中（对照混合版 .qf-top：left 50% + bottom 112）
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(this, 44), dp(this, 44));
            lp.gravity = Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM;
            lp.bottomMargin = dp(this, 112) + navBarH(); // Q26：导航栏避让
            content.addView(topFab, lp);
            topFab.setVisibility(View.GONE);
        } else if (topFab.getLayoutParams() instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) topFab.getLayoutParams();
            int wantBottom = dp(this, 112) + navBarH(); // Q26：导航栏避让
            if (lp.bottomMargin != wantBottom || lp.gravity != (Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM)) {
                lp.bottomMargin = wantBottom; lp.gravity = Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM;
                lp.rightMargin = 0; topFab.setLayoutParams(lp);
            }
        }
        updateTopFabVisibility(sv.getScrollY());
    }
    // P-scroll：平滑回顶（ValueAnimator 减速曲线，按距离定 240–520ms，与悬浮搜索回顶同一套手感语言）
    void smoothScrollTop(final ScrollView sv) {
        if (sv == null) return;
        final int from = sv.getScrollY();
        if (from <= 0) return;
        long dur = Math.min(520, 240 + from / 5);
        ValueAnimator va = ValueAnimator.ofInt(from, 0);
        va.setDuration(dur);
        va.setInterpolator(ANIM_ENTER);
        va.addUpdateListener(a -> sv.scrollTo(0, (int) a.getAnimatedValue()));
        va.start();
    }
    // P-scroll：细线向上箭头（Canvas 线条，与导航/搜索图标同语言，禁用 emoji）
    class TopIconView extends View {
        TopIconView(Context c) { super(c); }
        @Override protected void onDraw(Canvas cv) {
            // Q23：照混合版 qfTop svg（viewBox 24、path M12 19V5 M5.5 11.5L12 5l6.5 6.5、stroke-width 2）
            // 在 .qf-top svg 19×19 盒内绘制——必须按内衬区缩放（旧实现用整钮 44dp 直算，箭头撑满圆显粗大）。
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            p.setColor(Color.rgb(0x1C, 0x1C, 0x1E)); // 对照混合版 .qf-top 深色箭头
            float ox = getPaddingLeft(), oy = getPaddingTop();
            float sx = (getWidth() - getPaddingLeft() - getPaddingRight()) / 24f;
            float sy = (getHeight() - getPaddingTop() - getPaddingBottom()) / 24f;
            p.setStrokeWidth(2f * sx);
            cv.drawLine(ox + 12f * sx, oy + 19f * sy, ox + 12f * sx, oy + 5f * sy, p);
            cv.drawLine(ox + 5.5f * sx, oy + 11.5f * sy, ox + 12f * sx, oy + 5f * sy, p);
            cv.drawLine(ox + 18.5f * sx, oy + 11.5f * sy, ox + 12f * sx, oy + 5f * sy, p);
        }
    }
    View buildTopFab() {
        FrameLayout fab = new FrameLayout(this);
        // Q23：与 Q3 悬浮钮同款玻璃底（glassFabBg + applyGlassFabShadow + live 玻璃层），对照混合版 .qf-top/.qf-btn
        // 同语言：44dp 圆钮、白色 .5 描边、深色细线箭头；玻璃模糊由 glassLayer 垫底。
        fab.setBackground(glassFabBg());
        fab.addView(glassLayer(fab, -1, true), new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // Q23 固定浅白染色托底（盖在模糊层之上、箭头之下）：对照混合版 .qf-top 的 background:rgba(255,255,255,.32)
        // 在 backdrop 模糊之上叠染的层序——原生玻璃层为不透明位图，托底若垫在其下会被背景色整片染透
        // （用户 18:34 截图土黄饼根因：钮正压黄卡、饱和 1.4 放大底色且旧染色 108/96 在图层之下不起作用）。
        // 故托底改盖在图层之上，用固定浅白渐变把底色压回可认形、不染色的区间。
        View wash = new View(this);
        wash.setClickable(false); wash.setFocusable(false);
        wash.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        GradientDrawable washBg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{Color.argb(170, 255, 255, 255), Color.argb(156, 244, 248, 253)});
        washBg.setShape(GradientDrawable.OVAL);
        washBg.setStroke(dp(this, 1), Color.argb(140, 255, 255, 255));
        wash.setBackground(washBg);
        fab.addView(wash, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        applyGlassFabShadow(fab);
        TopIconView icon = new TopIconView(this);
        int pad = dp(this, 12);
        icon.setPadding(pad, pad, pad, pad);
        fab.addView(icon, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        fab.setOnClickListener(v -> { haptic(); smoothScrollTop(activeLongScroll()); });
        fab.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) pressBounce(v, true);
            else if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL)
                pressBounce(v, false);
            return false;
        });
        return fab;
    }
    static GradientDrawable roundRect(int color, float radiusDp, Context c) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color); g.setCornerRadius(dp(c, radiusDp));
        return g;
    }
    // P5 视觉细修：图片占位改柔和渐变（混合版无灰块口径）——浅蓝→浅紫对角渐变，不再用整块灰蓝
    static GradientDrawable placeholderGrad(float radiusDp, Context c) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
            new int[]{ Color.rgb(0xED, 0xF2, 0xFB), Color.rgb(0xE3, 0xE9, 0xF8), Color.rgb(0xEF, 0xEAF, 0xF7) });
        g.setCornerRadius(dp(c, radiusDp));
        return g;
    }
    // Q27：圆角裁切不能只靠默认 BACKGROUND outline（GradientDrawable/RippleDrawable 的 outline 在部分机型上
    // 退化为直角矩形，ImageView 的方形位图四角便盖过圆角背景、elevation 阴影也按直角轮廓打出黑角）。
    // 显式给视图装 RoundRect outline 并 clipToOutline，位图/背景/阴影三者共用同一圆角半径。
    static void roundClip(final View v, final float radiusDp, final Context c) {
        try {
            final float r = dp(c, radiusDp);
            v.setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r);
                }
            });
            v.setClipToOutline(true);
        } catch (Throwable ignored) { /* 个别机型 outline 异常时保留原背景圆角，不为裁切冒崩点 */ }
    }
    // Q27（用户 19:24 钉法）：位图级四角圆角——按显示尺寸把半径换算到位图坐标里，用 SRC_IN 把源图
    // 四角真切成透明，不靠视图 outline 硬剪；源图在 Img 缓存里多处共用，只出圆角副本、绝不动源图。
    static Bitmap roundBitmap(Bitmap src, float radiusPx) {
        if (src == null || src.getWidth() <= 0 || src.getHeight() <= 0) return src;
        try {
            Bitmap out = Bitmap.createBitmap(src.getWidth(), src.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(out);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            RectF rf = new RectF(0, 0, src.getWidth(), src.getHeight());
            cv.drawRoundRect(rf, radiusPx, radiusPx, p);
            p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
            cv.drawBitmap(src, 0, 0, p);
            p.setXfermode(null);
            return out;
        } catch (Throwable t) { return src; } // 圆角副本失败回落源图，不为修角冒崩点
    }

    // ---------- Q18 崩溃留痕 + 玻璃自动降级 ----------
    void noteGlassFailure() {
        glassFailCount++;
        if (glassFailCount >= 3 && !glassDisabled) {
            glassDisabled = true;
            try { if (prefs != null) prefs.edit().putBoolean("glass_disabled", true).apply(); } catch (Throwable ignored) {}
            // clear all live glass images/effects so tint fallback shows, never drag the page down
            try {
                for (ImageView iv : new java.util.ArrayList<>(glassViews)) {
                    try { iv.setImageBitmap(null); if (Build.VERSION.SDK_INT >= 31) iv.setRenderEffect(null); } catch (Throwable ignored) {}
                }
                // Q21 黑匣子定案：绝不主动 recycle——仍被某个 ImageView 显示列表引用的图一旦 recycle，下一帧 onDraw 即
                // 「Canvas: trying to use a recycled bitmap」整 App 闪退（0.67/0.70 两案同签名）。只解引用交系统回收。
                glassSnap = null;
            } catch (Throwable ignored) {}
        }
    }
    void noteGlassSuccess() { if (glassFailCount > 0) glassFailCount = 0; }

    void installCrashHandler() {
        try { sCrashVersion = appVersion(); } catch (Throwable ignored) { sCrashVersion = ""; }
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                java.io.StringWriter sw = new java.io.StringWriter();
                java.io.PrintWriter pw = new java.io.PrintWriter(sw);
                e.printStackTrace(pw); pw.flush();
                StringBuilder sb = new StringBuilder();
                sb.append("time=").append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(new java.util.Date())).append("\n");
                sb.append("version=").append(sCrashVersion).append("\n");
                sb.append("page=").append(sCrashTab).append("\n");
                sb.append("thread=").append(t == null ? "" : t.getName()).append("\n");
                sb.append(sw.toString());
                String txt = sb.toString();
                // file first (survives even if prefs write races process death)
                try {
                    File f = new File(getFilesDir(), "crash_last.txt");
                    FileOutputStream fos = new FileOutputStream(f, false);
                    fos.write(txt.getBytes("UTF-8")); fos.close();
                } catch (Throwable ignored) {}
                try {
                    if (prefs != null) prefs.edit().putString("crash_log", txt).commit();
                } catch (Throwable ignored) {}
            } catch (Throwable ignored) {}
            if (prev != null) prev.uncaughtException(t, e);
            else { android.os.Process.killProcess(android.os.Process.myPid()); System.exit(10); }
        });
    }
    void loadCrashLog() {
        crashLogText = null;
        try { if (prefs != null) crashLogText = prefs.getString("crash_log", null); } catch (Throwable ignored) {}
        if (crashLogText == null || crashLogText.trim().isEmpty()) {
            try {
                File f = new File(getFilesDir(), "crash_last.txt");
                if (f.exists()) {
                    FileInputStream in = new FileInputStream(f);
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192]; int n;
                    while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                    in.close();
                    crashLogText = new String(bos.toByteArray(), "UTF-8");
                    if (crashLogText != null && prefs != null) {
                        try { prefs.edit().putString("crash_log", crashLogText).apply(); } catch (Throwable ignored) {}
                    }
                }
            } catch (Throwable ignored) { crashLogText = null; }
        }
        if (crashLogText != null && crashLogText.trim().isEmpty()) crashLogText = null;
    }
    void clearCrashLog() {
        crashLogText = null;
        try { if (prefs != null) prefs.edit().remove("crash_log").apply(); } catch (Throwable ignored) {}
        try { File f = new File(getFilesDir(), "crash_last.txt"); if (f.exists()) f.delete(); } catch (Throwable ignored) {}
    }

    // ---------- Q11 真毛玻璃地基 ----------
    void pruneGlass() {
        java.util.Iterator<ImageView> it = glassViews.iterator();
        while (it.hasNext()) {
            ImageView iv = it.next();
            if (!iv.isAttachedToWindow()) {
                glassCrops.remove(iv); // Q21：只解引用不 recycle（同 noteGlassFailure 定案，防 recycled bitmap 闪退）
                glassHosts.remove(iv);
                it.remove();
            }
        }
    }

    /** 建一层玻璃模糊层：host 是它要贴合的玻璃面（定位/抓图时整面让开），radiusDp<0 为椭圆。 */
    ImageView glassLayer(View host, float radiusDp, boolean live) {
        if (glassDisabled) {
            // Q18: glass chain auto-disabled after consecutive failures -> return invisible placeholder,
            // tint underneath takes over; never crash the host page for glass.
            ImageView ph = new ImageView(this);
            ph.setClickable(false); ph.setFocusable(false);
            ph.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            ph.setTag(live ? "live" : "frozen");
            ph.setVisibility(View.INVISIBLE);
            return ph;
        }
        if (!live) captureGlassSnapshot(); // 浮窗升起前先抓底层（此时浮窗本体还没入树，抓到的就是它身后的画面）
        final ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.FIT_XY);
        iv.setClickable(false);
        iv.setFocusable(false);
        iv.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        iv.setTag(live ? "live" : "frozen");
        if (Build.VERSION.SDK_INT >= 21) {
            iv.setClipToOutline(true);
            final float r = radiusDp < 0 ? -1f : dp(this, radiusDp);
            iv.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override public void getOutline(View v, android.graphics.Outline o) {
                    if (r < 0) o.setOval(0, 0, Math.max(1, v.getWidth()), Math.max(1, v.getHeight()));
                    else o.setRoundRect(0, 0, Math.max(1, v.getWidth()), Math.max(1, v.getHeight()), r);
                }
            });
        }
        if (Build.VERSION.SDK_INT >= 23) {
            // Q16 (3): edge highlight band (hand-drawn Fresnel approximation) - 1dp white bright line, center stays unfogged, no AGSL refraction
            GradientDrawable fg = new GradientDrawable();
            fg.setColor(Color.TRANSPARENT);
            if (radiusDp < 0) fg.setShape(GradientDrawable.OVAL); else fg.setCornerRadius(dp(this, radiusDp));
            fg.setStroke(dp(this, 1), Color.argb(95, 255, 255, 255));
            try { iv.setForeground(fg); } catch (Throwable t) { /* some OEMs unsupported -> no highlight */ }
        }
        glassViews.add(iv);
        if (host != null) glassHosts.put(iv, host);
        iv.post(() -> applyGlass(iv));
        return iv;
    }

    /** 抓当前根视图快照（0.2 降采样）：抓图时把所有已登记玻璃面整面隐藏，避免把玻璃自己拍进背景。 */
    Bitmap captureGlassSnapshot() {
        // Q16 (1) crash kill: full Throwable guard. On some vivo/OEM devices drawing a subtree that carries
        // RenderEffect into a software Canvas throws Error (not Exception), which the old catch missed -> crash.
        // On failure we must also restore host visibility and the glassCapturing flag, else glass layers stay
        // invisible forever. Worst case: keep previous snapshot, or none (tint layer underneath still shows).
        if (rootView == null || !rootView.isAttachedToWindow() || rootView.getWidth() <= 0 || rootView.getHeight() <= 0 || glassCapturing) return glassSnap;
        pruneGlass();
        glassCapturing = true;
        java.util.Map<View, Integer> saved = new java.util.HashMap<>();
        Bitmap out = null;
        try {
            for (ImageView iv : new java.util.ArrayList<>(glassViews)) {
                View h = glassHosts.get(iv);
                View t = h != null ? h : iv;
                if (t != null && t.isAttachedToWindow() && !saved.containsKey(t)) {
                    saved.put(t, t.getVisibility());
                    t.setVisibility(View.INVISIBLE);
                }
            }
            int w = rootView.getWidth(), h = rootView.getHeight();
            float s = 0.20f;
            out = Bitmap.createBitmap(Math.max(1, Math.round(w * s)), Math.max(1, Math.round(h * s)), Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(out);
            cv.scale(s, s);
            rootView.draw(cv);
        } catch (Throwable t) {
            out = null; // degrade: keep previous frame; tint underneath still renders
            noteGlassFailure();
        } finally {
            for (java.util.Map.Entry<View, Integer> e : saved.entrySet()) {
                try { e.getKey().setVisibility(e.getValue()); } catch (Throwable ignored) {}
            }
            glassCapturing = false;
        }
        if (out != null) {
            // Q21：换快照只换引用——旧图可能仍被某玻璃层显示列表引用，主动 recycle 即触发黑匣子同签名闪退；0.2 降采样小图交系统回收。
            glassSnap = out;
            noteGlassSuccess();
        }
        return glassSnap;
    }

    /** 把全屏快照按本层在根视图中的位置裁出对应区域（带饱和），API 31+ 再叠硬件模糊。 */
    void applyGlass(final ImageView iv) {
        // Q16 (1): full Throwable guard; any failure clears this layer's image/effect and the tint underneath takes over
        try {
            if (iv == null || rootView == null || !iv.isAttachedToWindow() || glassCapturing) return;
            if (iv.getWidth() <= 0 || iv.getHeight() <= 0) {
                Integer r0 = glassRetry.get(iv);
                int rn = r0 == null ? 0 : r0;
                if (rn > 24) return; // never-laid-out glass layer must not spin-post forever (old behaviour)
                glassRetry.put(iv, rn + 1);
                iv.postDelayed(() -> applyGlass(iv), 50);
                return;
            }
            glassRetry.remove(iv);
            if (glassSnap == null || glassSnap.isRecycled()) captureGlassSnapshot();
            Bitmap full = glassSnap;
            if (full == null || full.isRecycled()) return;
            int[] rl = new int[2]; rootView.getLocationOnScreen(rl);
            int[] il = new int[2]; iv.getLocationOnScreen(il);
            int left = il[0] - rl[0], top = il[1] - rl[1];
            int w = iv.getWidth(), h = iv.getHeight();
            if (rootView.getWidth() <= 0) return;
            float s = (float) full.getWidth() / (float) rootView.getWidth();
            Bitmap out = Bitmap.createBitmap(Math.max(1, Math.round(w * s)), Math.max(1, Math.round(h * s)), Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(out);
            Paint pt = new Paint(Paint.FILTER_BITMAP_FLAG);
            ColorMatrix cm = new ColorMatrix();
            cm.setSaturation(1.4f);
            pt.setColorFilter(new ColorMatrixColorFilter(cm));
            android.graphics.Matrix m = new android.graphics.Matrix();
            m.setTranslate(-left * s, -top * s);
            cv.drawBitmap(full, m, pt);
            iv.setImageBitmap(out);
            glassCrops.put(iv, out); // Q21：旧裁片只解引用不 recycle（黑匣子定案：显示列表在用时 recycle 必崩，见 noteGlassFailure）
            if (Build.VERSION.SDK_INT >= 31) {
                // Q18 (4): blur 13 -> 11 + saturation 1.4 kept: snapshot already 0.2-downsampled,
                // lower radius keeps background colour/shape recognisable (no milky wall)
                try { iv.setRenderEffect(RenderEffect.createBlurEffect(11f, 11f, Shader.TileMode.CLAMP)); }
                catch (Throwable t) { noteGlassFailure(); }
            }
            noteGlassSuccess();
        } catch (Throwable t) {
            noteGlassFailure();
            try { iv.setImageBitmap(null); if (Build.VERSION.SDK_INT >= 31) iv.setRenderEffect(null); } catch (Throwable ignored) {}
        }
    }

    /** 滚动停稳后刷新 live 玻璃（底栏/悬浮钮/回顶/搜索胶囊）；有浮窗在场时不刷，浮窗用的是冻结快照。 */
    void refreshLiveGlass() {
        if (glassDisabled || glassCapturing || rootView == null || rootView.getWidth() <= 0) return;
        // Q21 ③：底栏拖动/弹簧进行中不做整屏抓图——capture 是全树 draw，正是滑动发卡与 MOVE 被饿死的主因之一；落稳后防抖任务会补上最终帧。
        if (navDragging || navSpringRunning) return;
        if (cardMenuPop != null || filterSheet != null || wizardOpen || aboutOpen
            || detailCard != null || welcomeOpen || changelogOpen) return;
        captureGlassSnapshot();
        for (ImageView iv : new java.util.ArrayList<>(glassViews)) {
            if ("live".equals(iv.getTag()) && iv.isAttachedToWindow()) applyGlass(iv);
        }
    }

    void scheduleGlassRefresh() {
        // Q16 (2) live glass: while scrolling, re-sample throttled at 140ms (dock / fabs / search capsule track the
        // background instead of showing one frozen frame), then the existing 380ms debounce settles a final frame.
        // refreshLiveGlass() itself skips while any overlay sheet is open.
        // Q21：底栏手势优先——拖动/弹簧期间连排队都免了，避免手势一结束就被积压的抓图任务堵住切页。
        if (navDragging || navSpringRunning) return;
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastLiveGlassMs >= 140) {
            lastLiveGlassMs = now;
            mainHandler.removeCallbacks(liveGlassTask);
            mainHandler.post(liveGlassTask);
        }
        mainHandler.removeCallbacks(glassRefreshTask);
        mainHandler.postDelayed(glassRefreshTask, 380);
    }

    // P5 空状态：对照混合版 .empty（居中、灰字、上下 36px 留白），包进白卡（圆角 14）不裸贴页面底
    View emptyState(String s) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(roundRect(Color.WHITE, 14, this));
        box.setPadding(dp(this, 20), dp(this, 32), dp(this, 20), dp(this, 32));
        TextView t = tv(this, s, 13.5f, Color.rgb(0x8E, 0x8E, 0x93), false);
        t.setGravity(android.view.Gravity.CENTER);
        t.setLineSpacing(dp(this, 3), 1f);
        box.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return box;
    }
    // ---------- 显示偏好（Phase 4a：字体三档/界面大小/高刷/触感） ----------
    static String fontMode = "default"; // default=软件默认栈 / system=本机 / serif=内置宋体
    static float uiScale = 1f;          // 界面大小：0.9 紧凑 / 1 标准 / 1.12 大号（作用于 sp）
    static int hapticLevel = 2; // P3 触感分档：0 关 / 1 轻(10ms) / 2 中(20ms) / 3 强(40ms)，存 prefs haptic_level（旧 boolean haptic 自动迁移）
    static android.graphics.Typeface serifTf = null;

    static android.graphics.Typeface serifTypeface(Context c, boolean bold) {
        if (serifTf == null) {
            try { serifTf = android.graphics.Typeface.createFromAsset(c.getAssets(), "fonts/serif.ttf"); }
            catch (Exception e) { serifTf = android.graphics.Typeface.SERIF; }
        }
        return bold ? android.graphics.Typeface.create(serifTf, android.graphics.Typeface.BOLD) : serifTf;
    }

    static TextView tv(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s); t.setTextSize(sp * uiScale); t.setTextColor(color);
        if ("serif".equals(fontMode)) t.setTypeface(serifTypeface(c, bold));
        else if ("system".equals(fontMode)) t.setTypeface(bold ? android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD) : android.graphics.Typeface.SANS_SERIF);
        else if (bold) t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setIncludeFontPadding(false);
        return t;
    }
    static String orgLabel(String org) {
        if (org == null) return "";
        switch (org) {
            case "visa": return "VISA";
            case "mastercard": return "万事达";
            case "mastercard-nucc": return "万事达-网联";
            case "amex-cn": return "运通-人民币";
            case "unionpay": return "银联";
            case "jcb": return "JCB";
            default: return org;
        }
    }

    // ---------- 数据模型 ----------
    static class Card {
        String id, name, bank, org, type, status, review, image, keywords, url;
        double score;
        String scoreLabel;
        JSONObject specs;
        JSONArray variants;
        boolean hasScore;
        boolean studentPick;
        int studentOrder;
        String studentReason;
        String spec(String key) {
            if (specs == null) return "";
            String v = specs.optString(key, "");
            return v == null ? "" : v;
        }
        boolean isCredit() { return "credit".equals(type); }
    }

    static class Store {
        static List<Card> all = new ArrayList<>();
        static Map<String, Card> byId = new HashMap<>();
        static int dataVersion = 0;

        static String readAll(InputStream in) throws Exception {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            return new String(bos.toByteArray(), "UTF-8");
        }

        // 解析一整份 cards.json 成功返回 true，并替换当前数据（先解到临时表，成功才换，避免半截数据）
        static boolean parseInto(String json) {
            try {
                JSONObject root = new JSONObject(json);
                JSONArray arr = root.getJSONArray("cards");
                if (arr == null || arr.length() == 0) return false;
                List<Card> tmp = new ArrayList<>();
                Map<String, Card> tmpBy = new HashMap<>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    Card cd = new Card();
                    cd.id = o.optString("id"); cd.name = o.optString("name");
                    cd.bank = o.optString("bank"); cd.org = o.optString("org");
                    cd.type = o.optString("type", "debit"); cd.status = o.optString("status");
                    cd.review = o.optString("review"); cd.image = o.optString("image");
                    cd.keywords = o.optString("keywords"); cd.url = o.optString("url");
                    cd.score = o.optDouble("score", 0); cd.hasScore = o.has("score") && !o.isNull("score"); cd.scoreLabel = o.optString("score_label");
                    cd.specs = o.optJSONObject("specs");
                    cd.variants = o.optJSONArray("variants");
                    JSONObject sp = o.optJSONObject("student_pick");
                    cd.studentPick = sp != null;
                    cd.studentOrder = sp != null ? sp.optInt("order", 999) : 999;
                    cd.studentReason = sp != null ? sp.optString("reason", "") : "";
                    tmp.add(cd); tmpBy.put(cd.id, cd);
                }
                all = tmp; byId = tmpBy;
                dataVersion = root.optInt("data_version", dataVersion);
                return true;
            } catch (Exception e) { return false; }
        }

        static int versionOf(String json) {
            try { return new JSONObject(json).optInt("data_version", 0); } catch (Exception e) { return 0; }
        }

        static void load(Context c) {
            if (!all.isEmpty()) return;
            String assetJson = null;
            try { assetJson = readAll(c.getAssets().open("data/cards.json")); } catch (Exception e) { /* 读不到走空 */ }
            // OTA 文件（filesDir/cards-ota.json）比内置新才优先用它（对照混合版 boot 的 OTA 优先逻辑）
            String otaJson = null;
            try {
                File f = new File(c.getFilesDir(), "cards-ota.json");
                if (f.exists()) otaJson = readAll(new FileInputStream(f));
            } catch (Exception e) { otaJson = null; }
            if (otaJson != null && assetJson != null && versionOf(otaJson) > versionOf(assetJson)) {
                if (parseInto(otaJson)) return;
            }
            if (assetJson != null) parseInto(assetJson);
            if (all.isEmpty() && otaJson != null) parseInto(otaJson);
        }
    }

    static class Img {
        static LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(48 * 1024) {
            protected int sizeOf(String k, Bitmap b) { return b.getByteCount() / 1024; }
        };
        static final java.util.Set<String> fetching = new java.util.HashSet<>();

        static Bitmap decode(InputStream in) {
            BitmapFactory.Options op = new BitmapFactory.Options();
            op.inSampleSize = 2;
            Bitmap b = BitmapFactory.decodeStream(in, null, op);
            try { in.close(); } catch (Exception e) {}
            return b;
        }

        static Bitmap get(Context c, String path) {
            if (path == null || path.isEmpty()) return null;
            Bitmap hit = cache.get(path);
            if (hit != null) return hit;
            try {
                Bitmap b = decode(c.getAssets().open(path));
                if (b != null) { cache.put(path, b); return b; }
            } catch (Exception e) { /* 内置没有（OTA 新卡图）走远程兜底 */ }
            // OTA 新卡的图不在安装包里：先读已缓存的远程图，没有就后台拉一次（数据仓 images/ 同步自 publish-data）
            try {
                File f = new File(new File(c.getFilesDir(), "ota-images"), new File(path).getName());
                if (f.exists()) {
                    Bitmap b = decode(new FileInputStream(f));
                    if (b != null) { cache.put(path, b); return b; }
                }
                fetchRemote(c.getApplicationContext(), path, f);
            } catch (Exception e) { /* 拿不到图就占位，不崩 */ }
            return null;
        }

        static void fetchRemote(final Context ctx, final String path, final File dest) {
            synchronized (fetching) { if (!fetching.add(path)) return; }
            new Thread(() -> {
                try {
                    String name = new File(path).getName();
                    HttpURLConnection conn = (HttpURLConnection) new URL("https://cdn.jsdelivr.net/gh/dimlogue/cardbox-data@main/images/" + name).openConnection();
                    conn.setConnectTimeout(8000); conn.setReadTimeout(8000);
                    if (conn.getResponseCode() == 200) {
                        dest.getParentFile().mkdirs();
                        FileOutputStream fos = new FileOutputStream(dest);
                        InputStream in = conn.getInputStream();
                        byte[] buf = new byte[8192]; int n;
                        while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
                        in.close(); fos.close();
                    }
                    conn.disconnect();
                } catch (Exception e) { /* 断网就下次再试 */ }
                synchronized (fetching) { fetching.remove(path); }
            }).start();
        }
    }

    // ---------- 自定义卡片（Phase 3b，对照 app.js 的 CUSTOM_STYLES / customCards） ----------
    static class CustomCard {
        String id, name, bank, org, note;
        int style;
    }
    static final int[][] CUSTOM_STYLES = {
        {0x3B82C4, 0x1E3A5F}, {0x8E44AD, 0x2C1A4D}, {0x16A085, 0x0A3D2E},
        {0xE67E22, 0x7E2F0E}, {0x2C3E50, 0x0D1520}, {0xC0392B, 0x4D0F0A}
    };
    static final String[] CUSTOM_ORGS = {"Visa", "万事达", "美国运通", "银联", "JCB"};
    java.util.List<CustomCard> customCards = new ArrayList<>();
    boolean customOpen = false;
    Dialog customDialog = null;
    // P-deck：我的卡片页滚动位置保持（换序/开合不甩回顶部）
    ScrollView mineScrollView = null;
    int mineScrollSaveY = 0;
    // P-keepscroll：各主页面滚动位置保存（关详情/筛选/向导/日志/欢迎页等弹层后默认回到原位，不跳顶）
    java.util.Map<String, Integer> pageScrollSaveY = new java.util.HashMap<>();
    ScrollView studentScroll = null;
    ScrollView newsScroll = null;
    ScrollView settingsScroll = null;

    void savePageScroll(String key, ScrollView sv) {
        if (sv != null) pageScrollSaveY.put(key, sv.getScrollY());
    }

    int savedPageScrollY(String key, ScrollView sv) {
        if (sv != null && sv.getScrollY() > 0) return sv.getScrollY();
        Integer y = pageScrollSaveY.get(key);
        return y == null ? 0 : y;
    }

    void restorePageScroll(String key, final ScrollView sv) {
        if (sv == null) return;
        final int y = savedPageScrollY(key, sv);
        if (y > 0) sv.post(() -> sv.scrollTo(0, y));
    }

    // 明确该回顶的入口（如从自定义卡跳去卡库搜这家银行看结果）专用，不走默认保位
    void resetPageScroll(String key, ScrollView sv) {
        pageScrollSaveY.put(key, 0);
        if (sv != null) sv.scrollTo(0, 0);
    }

    // 打开整屏覆盖层（详情/向导/欢迎/日志）前，先记下当前主页面滚到哪
    void captureCurrentPageScroll() {
        savePageScroll("home", homeScroll);
        savePageScroll("mine", mineScrollView);
        if (mineScrollView != null) mineScrollSaveY = mineScrollView.getScrollY();
        savePageScroll("student", studentScroll);
        savePageScroll("news", newsScroll);
        savePageScroll("settings", settingsScroll);
    }

    void restoreCurrentTabScroll() {
        switch (tab) {
            case "home": restorePageScroll("home", homeScroll); break;
            case "student": restorePageScroll("student", studentScroll); break;
            case "news": restorePageScroll("news", newsScroll); break;
            case "settings": restorePageScroll("settings", settingsScroll); break;
            case "mine":
                if (mineScrollView != null && mineScrollSaveY > 0)
                    mineScrollView.post(() -> mineScrollView.scrollTo(0, mineScrollSaveY));
                break;
            default: break;
        }
    }

    void loadCustomCards() {
        customCards = new ArrayList<>();
        try {
            String raw = prefs.getString("custom_cards", "[]");
            JSONArray arr = new JSONArray(raw == null || raw.isEmpty() ? "[]" : raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                CustomCard c = new CustomCard();
                c.id = o.optString("id");
                c.name = o.optString("name");
                c.bank = o.optString("bank");
                c.org = o.optString("org");
                c.note = o.optString("note");
                c.style = o.optInt("style", 0);
                if (c.id == null || c.id.isEmpty()) c.id = "custom-" + i;
                if (c.style < 0 || c.style >= CUSTOM_STYLES.length) c.style = 0;
                customCards.add(c);
            }
        } catch (Throwable e) {
            // Q21：单条坏数据不许整表蒸发——退到逐条抢救，能读几张读几张；整表清空只会把用户卡一次丢光。
            customCards = new ArrayList<>();
            try {
                String raw2 = prefs.getString("custom_cards", "[]");
                JSONArray arr2 = new JSONArray(raw2 == null || raw2.isEmpty() ? "[]" : raw2);
                for (int i = 0; i < arr2.length(); i++) {
                    try {
                        JSONObject o = arr2.getJSONObject(i);
                        CustomCard c = new CustomCard();
                        c.id = o.optString("id");
                        c.name = o.optString("name");
                        c.bank = o.optString("bank");
                        c.org = o.optString("org");
                        c.note = o.optString("note");
                        c.style = o.optInt("style", 0);
                        if (c.id == null || c.id.isEmpty()) c.id = "custom-" + i;
                        if (c.style < 0 || c.style >= CUSTOM_STYLES.length) c.style = 0;
                        customCards.add(c);
                    } catch (Throwable ignored) { /* 跳过这一条坏数据 */ }
                }
            } catch (Throwable ignored2) { /* 原始串本身不可解析才保持空表 */ }
        }
    }

    void saveCustomCards() {
        try {
            JSONArray arr = new JSONArray();
            for (CustomCard c : customCards) {
                JSONObject o = new JSONObject();
                o.put("id", c.id == null ? "" : c.id);
                o.put("name", c.name == null ? "" : c.name);
                o.put("bank", c.bank == null ? "" : c.bank);
                o.put("org", c.org == null ? "" : c.org);
                o.put("note", c.note == null ? "" : c.note);
                o.put("style", c.style);
                arr.put(o);
            }
            // Q21：自定义卡是用户资产，apply() 异步落盘在紧接着的崩溃/强杀下可能来不及刷盘；JSON 很小，直接 commit 同步落盘。
            prefs.edit().putString("custom_cards", arr.toString()).commit();
        } catch (Throwable e) { /* 存不下就保持内存中的列表 */ }
    }

    GradientDrawable customGradient(int style) {
        int[] pair = CUSTOM_STYLES[Math.max(0, Math.min(style, CUSTOM_STYLES.length - 1))];
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
            new int[]{Color.rgb((pair[0] >> 16) & 0xFF, (pair[0] >> 8) & 0xFF, pair[0] & 0xFF),
                      Color.rgb((pair[1] >> 16) & 0xFF, (pair[1] >> 8) & 0xFF, pair[1] & 0xFF)});
        g.setCornerRadius(dp(this, 14));
        return g;
    }

    static int styleRgb(int hex) {
        return Color.rgb((hex >> 16) & 0xFF, (hex >> 8) & 0xFF, hex & 0xFF);
    }

    // P-deck ②：与下一张顶色衔接的三段渐变（本卡顶→本卡底→下一张顶），无下一张时退回双色
    GradientDrawable customBlendedGradient(int style, int nextStyle, boolean first, boolean last) {
        int s = Math.max(0, Math.min(style, CUSTOM_STYLES.length - 1));
        int[] pair = CUSTOM_STYLES[s];
        GradientDrawable g;
        if (nextStyle >= 0) {
            int ns = Math.max(0, Math.min(nextStyle, CUSTOM_STYLES.length - 1));
            g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{styleRgb(pair[0]), styleRgb(pair[1]), styleRgb(CUSTOM_STYLES[ns][0])});
        } else {
            g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{styleRgb(pair[0]), styleRgb(pair[1])});
        }
        float r = dp(this, 14);
        if (first && last) g.setCornerRadius(r);
        else if (first) g.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        else if (last) g.setCornerRadii(new float[]{0, 0, 0, 0, r, r, r, r});
        else g.setCornerRadius(0);
        return g;
    }

    // P-deck ③：换序/开合后保持我的卡片页滚动位置，不甩回顶部
    void refreshMineKeepScroll() {
        if (mineScrollView != null) mineScrollSaveY = mineScrollView.getScrollY();
        showTab("mine");
    }

    // ---------- 全局状态 ----------
    SharedPreferences prefs;
    Set<String> mine = new HashSet<>();
    // 我的卡片拖动顺序（Phase 3c，对照 app.js 的 cbMineOrder）：卡 id 的有序列表
    List<String> mineOrder = new ArrayList<>();
    long lastDragEndAt = 0;
    String tab = "home";
    Card detailCard = null;
    View detailView = null; // Q6：详情贴底浮窗根（遮罩+窗体+关闭字形），底层页面不切走
    View detailSheetWrap = null;
    View detailShade = null;
    View detailCloseGlyph = null;
    ScrollView detailScroll = null;
    boolean detailClosing = false;
    int detailVariantIdx = 0;
    TextView detailBinView = null;
    LinearLayout detailVerInfoBox = null;
    java.util.List<View> detailDots = new java.util.ArrayList<>();
    Button detailMineBtn = null;
    final java.util.ArrayDeque<Card> detailQueue = new java.util.ArrayDeque<>();

    // 情景选卡状态（Phase 3a，对照 app.js 的 wiz 全局状态）
    boolean wizardOpen = false;
    boolean detailFromWiz = false;
    // P2b：选卡悬浮窗根视图（遮罩+底部升起的大圆角窗），底层页面保留不切页，关窗回到原页原位
    View wizardSheet = null;
    int lastWizStepShown = -1; // P4：选卡步骤切换方向判定（前进从右滑入、后退从左）
    String wizSc = null;
    int wizStep = 0;
    Map<String, String> wizA = new HashMap<>();

    // 欢迎页 / 更新日志（Phase 4b，对照 app.js showWelcome/renderChangelog）
    boolean welcomeOpen = false;
    boolean changelogOpen = false;
    ScrollView changelogScroll = null;
    boolean settingsLogOpen = false;
    ScrollView settingsLogScroll = null;
    // P-about：关于卡盒悬浮窗（对照混合版 aboutDlg）
    boolean aboutOpen = false;
    View aboutSheet = null;
    View aboutSponsorBody = null;
    TextView aboutSponsorArrow = null;
    boolean aboutSponsorOpen = false;
    boolean sponsorSavePending = false;

    // P4b 悬浮提示条（对照混合版 .toast：白色毛玻璃长条，带操作钮与自动消失）
    FrameLayout rootView = null;
    View floatToastView = null;
    Runnable floatToastTimer = null;
    final android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    // Q11 真毛玻璃地基（自研零三方）：抓底层快照（降采样+饱和 1.6 近似混合版 saturate）垫在玻璃面之下，
    // API 31+ 再叠 RenderEffect 硬件模糊；低版本靠降采样放大回落柔糊，不再是纯染色。
    // live 面（底栏/悬浮钮/回顶/搜索胶囊）滚动停稳 380ms 后刷新快照；frozen 面（各浮窗）升起时抓一次冻结。
    final java.util.List<ImageView> glassViews = new java.util.ArrayList<>();
    final java.util.Map<ImageView, View> glassHosts = new java.util.HashMap<>();
    final java.util.Map<ImageView, Bitmap> glassCrops = new java.util.HashMap<>();
    Bitmap glassSnap = null;
    boolean glassCapturing = false;
    final Runnable glassRefreshTask = new Runnable() { public void run() { refreshLiveGlass(); } };
    long lastLiveGlassMs = 0; // Q16: throttle stamp for scrolling-time live re-sampling
    final Runnable liveGlassTask = new Runnable() { public void run() { refreshLiveGlass(); } };
    final java.util.Map<ImageView, Integer> glassRetry = new java.util.HashMap<>(); // Q16: layout retry cap per glass layer
    // Q18: glass consecutive-failure auto-disable + crash trace
    int glassFailCount = 0;
    boolean glassDisabled = false;
    String crashLogText = null;
    static volatile String sCrashTab = "home";
    static volatile String sCrashVersion = "";

    FrameLayout content;
    FrameLayout navBar;
    LinearLayout navRow;
    View navIndicator;
    ImageView navIndicatorGlass;
    final java.util.List<String> navOrder = java.util.Arrays.asList("home", "student", "mine", "news", "settings");
    float navPos = 0f;              // indicator position in tab-index units (fractional while dragging/springing)
    int navSettled = 0;
    boolean navDragging = false;
    int navDragIdx = -1;
    float navSpringV = 0f;
    boolean navSpringRunning = false;
    // Q21 ①：弹簧只许一条回路——代次令牌 + 任务句柄。新弹簧/取消/按下接管都自增代次并摘除旧任务，
    // 旧 Runnable 即使已被系统派发也会因代次不符立即退出，杜绝两条回路同驱 navPos 互殴空转。
    int navSpringGen = 0;
    Runnable navSpringTask = null;
    float navDownRawX = 0f;
    // Q21 ②：首页渲染签名（查询/筛选/排序/列数/分组/展开集/收藏集/数据版本）——未变时切页回来不重搭 213 张瓷砖。
    String homeRenderSig = null;
    int homeRenderGen = 0; // 分帧渲染代次：新的 refresh 作废上一轮未跑完的续帧任务
    long navGlassMs = 0;
    FrameLayout navWrap;
    Map<String, View> pages = new HashMap<>();
    Map<String, LinearLayout> navItems = new HashMap<>();
    Map<String, NavIconView> navIcons = new HashMap<>();
    Map<String, TextView> navLabels = new HashMap<>();

    // 首页控件（切页回来保持搜索词）
    EditText searchBox;
    String query = "";
    TextView homeCount;
    Button filterBtn;
    View filterSheet = null;
    ScrollView filterScroll = null; // P2e：筛选窗内滚动区（窗框固定不滚，四角不被内容切掉）
    // Q3 悬浮搜索圆钮（首页右侧竖列上钮，玻璃底深色放大镜，点了回顶聚焦顶部搜索框）
    View searchFab = null;
    // Q3 悬浮筛选钮（搜索钮正下方，玻璃底深色滑杆图标，有已选条件时带蓝角标计数）
    View filterFab = null;
    TextView filterFabBadge = null;
    // P-scroll 悬浮回顶圆钮（长列表滚过一段后出现，点了平滑回顶）
    View topFab = null;
    boolean topFabShown = false;
    // P-searchfix：首页悬浮搜索栏本体与显隐状态（滚动时收起/失焦，不再赖在视角上）
    View homeSearchBar = null;
    boolean homeSearchBarShown = true;
    int lastHomeScrollY = 0;
    // Q10 float search capsule (only over mid-page scroll positions; never moves the list)
    View floatSearchBar = null;
    EditText floatSearchBox = null;
    boolean floatSearchOpen = false;
    boolean syncingSearchText = false;
    View inlineClearBtn = null;
    // Q1 长按贴卡菜单（对照混合版现行 openCardMenu：按住 450ms、原卡蓝框高亮、贴卡小菜单）
    View cardMenuBackdrop = null;
    View cardMenuClone = null;
    View cardMenuPop = null;
    View cardMenuGlass = null; // Q11：长按菜单下的冻结模糊层

    // 筛选状态（Phase 2a-1：与混合版 chipRow 相同的单选切换语义，点已选项再点一次取消）
    String filterType = null;   // "debit" / "credit" / null
    String filterOrg = null;    // org 代码 / null
    String filterStatus = null; // "在发" / "已停发" / null

    // 特点与发卡行（Phase 2a-2，对照 app.js 的 FEATS/featMatch 与 state.bank）
    java.util.Set<String> filterFeats = new java.util.LinkedHashSet<>(); // feat key，多选且 AND（每项都要满足）；保序与标签栏一致
    String filterBank = null; // 发卡行，单选切换（同 chipRow 对 bank 的语义）
    LinearLayout activeFilterBar = null;

    // 排序 / 列数 / 显示方式（Phase 2a-3，对照 app.js applySort/colsNowVal/groupBank+bankOpen）
    String sortMode = null; // null=默认 / score-desc / score-asc / name / bank
    int cols = 2; // 1/2/3
    boolean groupBank = false;
    java.util.Set<String> bankOpen = new java.util.HashSet<>();
    LinearLayout homeList = null;
    ScrollView homeScroll = null;

    static String sortLabel(String v) {
        if ("score-desc".equals(v)) return "评分由高到低";
        if ("score-asc".equals(v)) return "评分由低到高";
        if ("name".equals(v)) return "名称";
        if ("bank".equals(v)) return "银行";
        return v;
    }

    void applySort(List<Card> list) {
        final java.text.Collator zh = java.text.Collator.getInstance(java.util.Locale.CHINA);
        java.util.Comparator<Card> byScoreDesc = (a, b) -> Double.compare(b.score, a.score);
        if (groupBank) {
            // 混合版：分组时先银行中文序，组内按评分（选了升序则升序，否则默认降序）
            list.sort((a, b) -> {
                int r = zh.compare(a.bank == null ? "" : a.bank, b.bank == null ? "" : b.bank);
                if (r != 0) return r;
                return "score-asc".equals(sortMode) ? Double.compare(a.score, b.score) : Double.compare(b.score, a.score);
            });
        } else if ("score-asc".equals(sortMode)) {
            list.sort((a, b) -> Double.compare(a.score, b.score));
        } else if ("score-desc".equals(sortMode)) {
            list.sort(byScoreDesc);
        } else if ("name".equals(sortMode)) {
            list.sort((a, b) -> { int r = zh.compare(a.name == null ? "" : a.name, b.name == null ? "" : b.name); return r != 0 ? r : Double.compare(b.score, a.score); });
        } else if ("bank".equals(sortMode)) {
            list.sort((a, b) -> { int r = zh.compare(a.bank == null ? "" : a.bank, b.bank == null ? "" : b.bank); return r != 0 ? r : Double.compare(b.score, a.score); });
        }
    }

    void persistViewPrefs() {
        SharedPreferences.Editor e = prefs.edit();
        if (sortMode == null) e.remove("sort_mode"); else e.putString("sort_mode", sortMode);
        e.putInt("cols", cols);
        e.putBoolean("group_bank", groupBank);
        e.putStringSet("bank_open", new HashSet<>(bankOpen));
        e.apply();
    }

    static final String[][] FEATS = {
        {"3ds", "3DS"}, {"online", "可网付"}, {"noftf", "无货币转换费"},
        {"autofx", "自动购汇"}, {"applepay", "Apple Pay"}
    };

    // 与混合版 featMatch 逐项同口径（读 specs 中文字段）
    static boolean featMatch(Card c, String k) {
        String v3 = c.spec("3DS").trim();
        switch (k) {
            case "3ds": return "有".equals(v3);
            case "online": { String x = c.spec("网付").trim(); return !x.isEmpty() && !"不支持".equals(x); }
            case "noftf": return "无".equals(c.spec("货币转换费（FTF）").trim());
            case "autofx": { String x = c.spec("自动购汇").trim(); return x.startsWith("有") || x.startsWith("支持"); }
            case "applepay": return c.spec("Apple Pay").trim().startsWith("支持");
            default: return false;
        }
    }

    static String featLabel(String k) {
        for (String[] f : FEATS) if (f[0].equals(k)) return f[1];
        return k;
    }

    int activeFilterCount() {
        int n = filterFeats.size();
        if (filterType != null) n++;
        if (filterOrg != null) n++;
        if (filterStatus != null) n++;
        if (filterBank != null) n++;
        return n;
    }

    // 发卡行：去重后排序（同 app.js renderFilters 的 banks 取法）
    List<String> distinctBanks() {
        List<String> out = new ArrayList<>();
        for (Card c : Store.all) {
            if (c.bank != null && !c.bank.isEmpty() && !out.contains(c.bank)) out.add(c.bank);
        }
        java.util.Collections.sort(out);
        return out;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window w = getWindow();
        // P2d 沉浸式状态栏：透明，内容顶到状态栏底下；各页顶部留白按 statusBarH() 补齐
        // Q26：导航栏一并透明做 edge-to-edge（内容铺到屏底，消灭底部白色断带），
        // 控件避让靠 navBarH() 实测值（见 applyNavInset），不许与手势条打架
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);
        int uiFlags = View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (Build.VERSION.SDK_INT >= 26) uiFlags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        w.getDecorView().setSystemUiVisibility(uiFlags);
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false); // Q26：与上面的 LAYOUT 标志双保险，内容真正铺满全屏
            w.getDecorView().setOnApplyWindowInsetsListener((v, insets) -> {
                try {
                    int b = insets.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom;
                    if (b != navInsetPx) { navInsetPx = b; applyNavInset(); }
                } catch (Throwable ignored) {}
                return insets;
            });
        }
        w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        prefs = getSharedPreferences("cardbox_native", MODE_PRIVATE);
        // Q18: crash trace first (so even early onCreate crashes are recorded), then restore glass-disable flag
        glassDisabled = false;
        try { glassDisabled = prefs.getBoolean("glass_disabled", false); } catch (Throwable ignored) {}
        loadCrashLog();
        installCrashHandler();
        fontMode = prefs.getString("font_mode", "default");
        uiScale = prefs.getFloat("ui_scale", 1f);
        if (uiScale != 0.9f && uiScale != 1f && uiScale != 1.12f) uiScale = 1f;
        if (prefs.contains("haptic_level")) hapticLevel = prefs.getInt("haptic_level", 2);
        else hapticLevel = prefs.getBoolean("haptic", true) ? 2 : 0; // 旧开关迁移：开→中档
        if (hapticLevel < 0 || hapticLevel > 3) hapticLevel = 2;
        applyHighRefresh();
        try { mine = new HashSet<>(prefs.getStringSet("mine_ids", new HashSet<String>())); } catch (Exception e) { mine = new HashSet<>(); }
        sortMode = prefs.getString("sort_mode", null);
        cols = prefs.getInt("cols", 2); if (cols != 1 && cols != 2 && cols != 3) cols = 2;
        groupBank = prefs.getBoolean("group_bank", false);
        try { bankOpen = new HashSet<>(prefs.getStringSet("bank_open", new HashSet<String>())); } catch (Exception e) { bankOpen = new HashSet<>(); }
        loadCustomCards();
        loadMineOrder();
        Store.load(this);

        FrameLayout root = new FrameLayout(this);
        rootView = root;
        root.setBackgroundColor(Color.rgb(0xF2, 0xF3, 0xF7));

        content = new FrameLayout(this);
        // P1b 浮感修正：内容区不再留底部硬白边，各页滚动视图全高延伸到悬浮条底下，
        // 滚动时内容从半透明条下隐约滑过；最后一项靠各页内衬的底部留白滚出条外。
        content.setPadding(0, 0, 0, 0);
        content.setClipToPadding(false);
        root.addView(content, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        buildNav(root);
        setContentView(root);
        // Q11：任意滚动停稳后刷新 live 玻璃快照（380ms 防抖，不逐帧抓图保流畅）
        root.getViewTreeObserver().addOnScrollChangedListener(() -> scheduleGlassRefresh());

        showTab("home");
        checkDataUpdate(false);
        if (!prefs.getBoolean("welcomed", false)) showWelcome();
    }

    // 高刷：开启时把窗口首选刷新率设为屏幕支持的最高档（对照混合版 Bridge setHighRefresh）
    void applyHighRefresh() {
        try {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            if (prefs != null && prefs.getBoolean("high_refresh", false)) {
                float best = 0;
                int bestId = 0;
                for (android.view.Display.Mode m : getWindowManager().getDefaultDisplay().getSupportedModes()) {
                    if (m.getRefreshRate() > best) { best = m.getRefreshRate(); bestId = m.getModeId(); }
                }
                if (bestId != 0) lp.preferredDisplayModeId = bestId;
                lp.preferredRefreshRate = best;
            } else {
                lp.preferredDisplayModeId = 0;
                lp.preferredRefreshRate = 0;
            }
            getWindow().setAttributes(lp);
        } catch (Exception e) { /* 个别机型不支持就静默 */ }
    }

    // 当前安装包版本号（设置页展示用，避免写死过期）
    String appVersion() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (Exception e) { return ""; }
    }

    // P3：按档位震动（Vibrator 系统服务，与铃声/静音无关，静音下照常震）；振幅等效分档，旧机型只认时长
    void haptic() {
        if (hapticLevel <= 0) return;
        int ms = hapticLevel == 1 ? 10 : (hapticLevel == 2 ? 20 : 40);
        int amp = hapticLevel == 1 ? 70 : (hapticLevel == 2 ? 150 : 255);
        try {
            Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (v == null) return;
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(ms, amp));
            else v.vibrate(ms);
        } catch (Exception e) { /* 无振动器静默 */ }
    }

    // ---------- P4b 悬浮提示条 ----------
    // 对照混合版 .toast 数值：左右 16dp、距底 106dp、圆角 16、白色半透、12/15 内边距、16px 圆角；
    // 浮现：上浮 12dp+缩放 .98→1+淡入（250ms 减速），收起 180ms；无操作 2200ms、有「撤销」等操作钮 4500ms 自动消失。
    void showFloatToast(String msg) { showFloatToast(msg, null, null); }

    void showFloatToast(final String msg, final String actionLabel, final Runnable onAction) {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            runOnUiThread(() -> showFloatToast(msg, actionLabel, onAction));
            return;
        }
        dismissFloatToast(true);
        if (rootView == null) return;
        final LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{Color.argb(196, 255, 255, 255), Color.argb(186, 246, 249, 253)}); // Q11：半透染色盖在冻结模糊层上
        bg.setCornerRadius(dp(this, 16));
        bg.setStroke(dp(this, 1), Color.argb(20, 20, 30, 60));
        bar.setBackground(bg);
        if (Build.VERSION.SDK_INT >= 21) bar.setElevation(dp(this, 10));
        bar.setPadding(dp(this, 15), dp(this, 12), dp(this, 15), dp(this, 12));
        TextView txt = tv(this, msg, 14, Color.rgb(0x1C, 0x1C, 0x1E), false);
        txt.setSingleLine(true);
        txt.setEllipsize(android.text.TextUtils.TruncateAt.END);
        bar.addView(txt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (actionLabel != null && onAction != null) {
            TextView act = tv(this, actionLabel, 12.5f, Color.WHITE, true);
            act.setGravity(Gravity.CENTER);
            GradientDrawable ab = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{Color.rgb(0x0A, 0x84, 0xFF), Color.rgb(0x5E, 0x5C, 0xE6)});
            ab.setCornerRadius(dp(this, 999));
            act.setBackground(ab);
            act.setPadding(dp(this, 14), dp(this, 7), dp(this, 14), dp(this, 7));
            LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            alp.leftMargin = dp(this, 10);
            bar.addView(act, alp);
            act.setOnClickListener(v -> {
                haptic();
                dismissFloatToast(false);
                onAction.run();
            });
            act.setOnTouchListener((v, e) -> {
                if (e.getAction() == MotionEvent.ACTION_DOWN) pressBounce(v, true);
                else if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL)
                    pressBounce(v, false);
                return false;
            });
            bar.setClickable(true);
        } else {
            bar.setClickable(false);
        }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM;
        lp.leftMargin = dp(this, 16);
        lp.rightMargin = dp(this, 16);
        lp.bottomMargin = dp(this, 106) + navBarH(); // Q26：导航栏避让
        // Q11：提示条垫冻结模糊层，外壳只剩半透染色
        FrameLayout toastWrap = new FrameLayout(this);
        toastWrap.addView(glassLayer(toastWrap, 16, false), new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        toastWrap.addView(bar, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        rootView.addView(toastWrap, lp);
        toastWrap.bringToFront();
        floatToastView = toastWrap;
        toastWrap.setAlpha(0f);
        toastWrap.setTranslationY(dp(this, 12));
        toastWrap.setScaleX(0.98f); toastWrap.setScaleY(0.98f);
        toastWrap.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
            .setDuration(250).setInterpolator(ANIM_ENTER).start();
        floatToastTimer = () -> dismissFloatToast(false);
        mainHandler.postDelayed(floatToastTimer, (actionLabel != null && onAction != null) ? 4500 : 2200);
    }

    void dismissFloatToast(boolean immediate) {
        if (floatToastTimer != null) { mainHandler.removeCallbacks(floatToastTimer); floatToastTimer = null; }
        final View bar = floatToastView;
        floatToastView = null;
        if (bar == null) return;
        if (bar.getParent() == null) return;
        if (immediate) {
            if (bar.getParent() instanceof ViewGroup) ((ViewGroup) bar.getParent()).removeView(bar);
            return;
        }
        bar.animate().cancel();
        bar.animate().alpha(0f).translationY(dp(this, 8)).scaleX(0.98f).scaleY(0.98f)
            .setDuration(180).setInterpolator(ANIM_EXIT)
            .withEndAction(() -> { if (bar.getParent() instanceof ViewGroup) ((ViewGroup) bar.getParent()).removeView(bar); })
            .start();
    }

    // 收藏切换统一入口：移除出「撤销」（按原顺序恢复，因 mineOrder 未动、重新加入即回原位）、加入给普通提示
    // Q21：收藏集合同走 commit 同步落盘——用户资产不赌 apply() 的异步刷盘窗口（崩溃/强杀紧跟保存时不丢）。
    void persistMineSet() {
        try { prefs.edit().putStringSet("mine_ids", new HashSet<>(mine)).commit(); } catch (Throwable ignored) {}
    }

    void toggleMineWithToast(final Card c, final Runnable uiRefresh) {
        if (mine.contains(c.id)) {
            mine.remove(c.id);
            persistMineSet();
            pages.remove("mine");
            if (uiRefresh != null) uiRefresh.run();
            showFloatToast("已从我的卡片移除：" + c.name, "撤销", () -> {
                mine.add(c.id);
                persistMineSet();
                pages.remove("mine");
                if (uiRefresh != null) uiRefresh.run();
                showFloatToast("已恢复：" + c.name);
            });
        } else {
            mine.add(c.id);
            persistMineSet();
            pages.remove("mine");
            if (uiRefresh != null) uiRefresh.run();
            showFloatToast("已加入我的卡片：" + c.name);
        }
    }

    // 字体/界面大小变化后整页重建（各页都是缓存 View，必须重造才生效）
    void rebuildPages() {
        captureCurrentPageScroll(); // P-keepscroll：整页重建（字体/界面大小等）前先记下各页滚动位置
        pages.clear();
        if (content != null) content.removeAllViews();
        showTab(tab);
    }

    // ---------- 底部导航（P1 悬浮底栏：对照混合版 .dock-glass） ----------
    // 细线自绘图标：Canvas 线条，禁用 emoji 与系统老图标
    class NavIconView extends View {
        final String kind;
        boolean on = false;
        NavIconView(Context c, String k) { super(c); kind = k; }
        void setOn(boolean v) { on = v; invalidate(); }
        @Override protected void onDraw(Canvas cv) {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            p.setStrokeWidth(dp(getContext(), 1.7f));
            // Q4：未选色对照混合版 .dock-glass button 的 #3A3A3C（旧 #636366 在亮玻璃+花背景上发飘读不清），选中仍 #1C1C1E
            p.setColor(on ? Color.rgb(0x1C, 0x1C, 0x1E) : Color.rgb(0x3A, 0x3A, 0x3C));
            float w = getWidth(), h = getHeight();
            float sx = w / 24f, sy = h / 24f;
            // 在 24x24 网格上画，坐标随控件尺寸缩放
            java.util.function.BiConsumer<float[], float[]> line = (a, b) ->
                cv.drawLine(a[0] * sx, a[1] * sy, b[0] * sx, b[1] * sy, p);
            switch (kind) {
                case "home": {
                    line.accept(new float[]{4f, 11.5f}, new float[]{12f, 4.5f});
                    line.accept(new float[]{12f, 4.5f}, new float[]{20f, 11.5f});
                    line.accept(new float[]{6.5f, 9.5f}, new float[]{6.5f, 19.5f});
                    line.accept(new float[]{17.5f, 9.5f}, new float[]{17.5f, 19.5f});
                    line.accept(new float[]{6.5f, 19.5f}, new float[]{17.5f, 19.5f});
                    cv.drawRect(new RectF(10.2f * sx, 13.5f * sy, 13.8f * sx, 19.5f * sy), p);
                    break;
                }
                case "student": {
                    // 学士帽：菱形帽面 + 流苏 + 头弧
                    cv.drawLine(12f * sx, 5.5f * sy, 21.5f * sx, 10f * sy, p);
                    cv.drawLine(21.5f * sx, 10f * sy, 12f * sx, 14.5f * sy, p);
                    cv.drawLine(12f * sx, 14.5f * sy, 2.5f * sx, 10f * sy, p);
                    cv.drawLine(2.5f * sx, 10f * sy, 12f * sx, 5.5f * sy, p);
                    cv.drawLine(21.5f * sx, 10f * sy, 21.5f * sx, 16f * sy, p);
                    cv.drawArc(new RectF(7.5f * sx, 12.5f * sy, 16.5f * sx, 20f * sy), 0, 180, false, p);
                    break;
                }
                case "mine": {
                    RectF r = new RectF(3.5f * sx, 6.5f * sy, 20.5f * sx, 17.5f * sy);
                    cv.drawRoundRect(r, 2.5f * sx, 2.5f * sy, p);
                    cv.drawLine(3.5f * sx, 10.5f * sy, 20.5f * sx, 10.5f * sy, p);
                    cv.drawLine(6.5f * sx, 14.5f * sy, 11f * sx, 14.5f * sy, p);
                    break;
                }
                case "news": {
                    RectF r = new RectF(5f * sx, 4.5f * sy, 19f * sx, 19.5f * sy);
                    cv.drawRoundRect(r, 1.8f * sx, 1.8f * sy, p);
                    cv.drawLine(8f * sx, 8.5f * sy, 16f * sx, 8.5f * sy, p);
                    cv.drawLine(8f * sx, 12f * sy, 16f * sx, 12f * sy, p);
                    cv.drawLine(8f * sx, 15.5f * sy, 13.5f * sx, 15.5f * sy, p);
                    break;
                }
                default: { // settings 齿轮：外圈 + 八齿 + 中心圆
                    for (int i = 0; i < 8; i++) {
                        double a = Math.toRadians(i * 45.0);
                        float x1 = (float) (12 + 5.1 * Math.cos(a)), y1 = (float) (12 + 5.1 * Math.sin(a));
                        float x2 = (float) (12 + 7.4 * Math.cos(a)), y2 = (float) (12 + 7.4 * Math.sin(a));
                        cv.drawLine(x1 * sx, y1 * sy, x2 * sx, y2 * sy, p);
                    }
                    cv.drawCircle(12f * sx, 12f * sy, 5.1f * sx, p);
                    cv.drawCircle(12f * sx, 12f * sy, 2.1f * sx, p);
                    break;
                }
            }
        }
    }

    Drawable floatingBarBg() {
        // Q4 复核（对照混合版 .dock-glass：background rgba(255,255,255,.58) 均匀单色 +
        // border 1px rgba(255,255,255,.55) + 顶部 inset 高光）：旧三段渐变顶 172/底 138
        // 上下透光不均、顶过亮底偏灰，改均匀染色 argb 150(.59) 贴 .58，顶部高光只靠描边带出；
        // 真糊由玻璃层（applyGlass blur+saturate）承担，此处只剩染色不许再抢不透明度，
        // 否则底下卡片透不过来、图标反而发飘。
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{Color.argb(108, 255, 255, 255), Color.argb(104, 248, 250, 255),
                Color.argb(102, 238, 243, 250)});
        g.setCornerRadius(dp(this, 26));
        g.setStroke(dp(this, 1), Color.argb(140, 255, 255, 255));
        return g;
    }

    Drawable navPillBg() {
        // Q4 复核（对照混合版 .dock-pill：background rgba(255,255,255,.68) 均匀 +
        // inset 0 1px 高光 + 0 2px 10px 软影）：旧顶段 198(.78) 过实、压住玻璃透光且与
        // dock 本体 .58 拉出两截色，改均匀 argb 174(.68) 贴原值，高光靠描边。
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{Color.argb(148, 255, 255, 255), Color.argb(145, 247, 250, 254),
                Color.argb(142, 240, 245, 251)});
        g.setCornerRadius(dp(this, 18));
        g.setStroke(dp(this, 1), Color.argb(150, 255, 255, 255));
        return g;
    }

    void buildNav(FrameLayout root) {
        navWrap = new FrameLayout(this);
        FrameLayout.LayoutParams wrapLp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wrapLp.gravity = Gravity.BOTTOM;
        wrapLp.leftMargin = dp(this, 12);
        wrapLp.rightMargin = dp(this, 12);
        wrapLp.bottomMargin = dp(this, 12) + navBarH(); // Q26：抬到系统手势条之上
        navWrap.setLayoutParams(wrapLp);

        // Q17: navBar is now a FrameLayout stack: dock tint / liquid indicator / item row.
        navBar = new FrameLayout(this);
        if (Build.VERSION.SDK_INT >= 21) navBar.setElevation(dp(this, 18));
        if (Build.VERSION.SDK_INT >= 28) {
            navBar.setOutlineAmbientShadowColor(Color.argb(36, 20, 30, 60));
            navBar.setOutlineSpotShadowColor(Color.argb(36, 20, 30, 60));
        }
        navBar.setClipToOutline(false);

        // Q20 根因（主会话 18:31 定案）：此处原先塞过一块 MATCH_PARENT 的 dockBg 背景 View——
        // 裸 View 按 MATCH_PARENT 在 WRAP_CONTENT 父容器里会量到整屏高，把底栏撑成全屏巨卡（0.63 起真机实锤）。
        // 背景一律直接设在 navBar 自己身上，禁止再往底栏里塞 MATCH_PARENT 背景板。
        navBar.setBackground(floatingBarBg());

        // liquid glass drop: glass layer (live, shared snapshot) + pill tint on top edge.
        navIndicator = new FrameLayout(this);
        ((FrameLayout) navIndicator).setBackground(navPillBg());
        // Q20 紧急拆弹（主会话 2026-10-03 18:23 亲手）：指示块内的实时玻璃层在真机上撑成全屏巨卡且拖动卡死，
        // 先整层摘除——指示块保留 navPillBg 药丸+拖动+弹簧，只是不再采样玻璃；液态玻璃等黑匣子证据齐了再议。
        navIndicatorGlass = null;
        if (Build.VERSION.SDK_INT >= 21) navIndicator.setElevation(dp(this, 2));
        FrameLayout.LayoutParams indLp = new FrameLayout.LayoutParams(dp(this, 60), dp(this, 52));
        navBar.addView(navIndicator, indLp);

        navRow = new LinearLayout(this);
        navRow.setOrientation(LinearLayout.HORIZONTAL);
        navRow.setPadding(dp(this, 8), dp(this, 8), dp(this, 8), dp(this, 8));
        navBar.addView(navRow, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        String[][] tabs = {
            {"home", "全部卡片"}, {"student", "学生推荐"}, {"mine", "我的卡片"}, {"news", "资讯"}, {"settings", "设置"}
        };
        for (String[] t : tabs) {
            final String key = t[0];
            final int idx = navOrder.indexOf(key);
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER_HORIZONTAL);
            item.setPadding(dp(this, 4), dp(this, 8), dp(this, 4), dp(this, 8));
            item.setBackground(null); // selection shown by the liquid indicator, not per-item pill
            NavIconView icon = new NavIconView(this, key);
            item.addView(icon, new LinearLayout.LayoutParams(dp(this, 23), dp(this, 23)));
            TextView label = tv(this, t[1], 10f, Color.rgb(0x3A, 0x3A, 0x3C), false);
            label.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            llp.topMargin = dp(this, 2);
            item.addView(label, llp);
            item.setOnClickListener(v -> { if (!navDragging) { haptic(); showTab(key); } });
            // Q17/Q21: drag on the dock itself - finger drags the drop, passing a tab ticks haptic, release springs to nearest and only then switches page.
            // Q21 ③ 触摸竞争治理：按下即向父级声明不许拦截（底栏整条手势归条目独占），坐标统一用 rawX 换算到 navRow，
            // 手指滑出起始条目后仍由按下条目独占 MOVE 流，不再出现滑到一半被别的视图抢走而「滑不动」。
            item.setOnTouchListener((v, e) -> {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        navDownRawX = e.getRawX(); navDownMs = android.os.SystemClock.uptimeMillis();
                        try { v.getParent().requestDisallowInterceptTouchEvent(true); } catch (Throwable ignored) {}
                        // 手指已落到底栏：正在跑的弹簧立刻让位给手指（代次作废旧回路），不许弹簧与手指同驱指示块。
                        if (navSpringRunning) cancelNavSpring();
                        return false;
                    case MotionEvent.ACTION_MOVE:
                        if (!navDragging && Math.abs(e.getRawX() - navDownRawX) > dp(this, 9)) {
                            navDragging = true; navDragIdx = Math.round(navPos); cancelNavSpring();
                        }
                        if (navDragging) { navDragTo(e.getRawX()); return true; }
                        return false;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        try { v.getParent().requestDisallowInterceptTouchEvent(false); } catch (Throwable ignored) {}
                        if (navDragging) { navDragging = false; settleNav(); return true; }
                        return false;
                }
                return false;
            });
            navItems.put(key, item);
            navIcons.put(key, icon);
            navLabels.put(key, label);
            navRow.addView(item, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        navWrap.addView(glassLayer(navWrap, 26, true), new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        navWrap.addView(navBar, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(navWrap);
        navBar.post(() -> layoutNavIndicator(navOrder.indexOf(tab == null ? "home" : tab), false));
    }

    float navDownX = 0f; long navDownMs = 0;

    float navSlotW() {
        if (navRow == null || navRow.getWidth() <= 0) return 0f;
        return (navRow.getWidth() - dp(this, 16)) / 5f;
    }

    void layoutNavIndicator(int idx, boolean snap) {
        if (navIndicator == null) return;
        float slot = navSlotW();
        if (slot <= 0) { navBar.post(() -> layoutNavIndicator(idx, snap)); return; }
        int w = Math.max(dp(this, 40), Math.round(slot - dp(this, 6)));
        View sample = navItems.get("home");
        int h = sample != null && sample.getHeight() > 0 ? sample.getHeight() - dp(this, 4) : dp(this, 52);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) navIndicator.getLayoutParams();
        if (lp.width != w || lp.height != h) { lp.width = w; lp.height = h; lp.topMargin = dp(this, 10); navIndicator.setLayoutParams(lp); }
        navIndicator.setPivotX(w / 2f); navIndicator.setPivotY(h / 2f); // stretch around the drop's centre
        if (snap) navPos = idx;
        placeNavIndicator(0f);
    }

    void placeNavIndicator(float vel) {
        if (navIndicator == null || navBar == null) return;
        float slot = navSlotW(); if (slot <= 0) return;
        float x = dp(this, 8) + navPos * slot + dp(this, 3);
        navIndicator.setTranslationX(x);
        float stretch = Math.min(1.30f, 1f + Math.abs(vel) * 0.045f);
        navIndicator.setScaleX(stretch);
        navIndicator.setScaleY(1f - (stretch - 1f) * 0.38f);
        // Q20 拆弹：拖动/弹簧帧里不再做 applyGlass 位图裁图（曾是卡死与巨卡来源之一）。
    }

    // Q21：rawX（屏幕坐标）→ 标签小数位：以 navRow 在屏位置为原点，跨条目滑动时坐标连续不跳变。
    void navDragTo(float rawX) {
        float slot = navSlotW(); if (slot <= 0 || navRow == null) return;
        int[] loc = new int[2]; navRow.getLocationOnScreen(loc);
        float navX = rawX - loc[0];
        float p = (navX - dp(this, 8)) / slot - 0.5f;
        p = Math.max(-0.12f, Math.min(4.12f, p));
        float vel = (p - navPos) * 18f;
        navPos = p;
        placeNavIndicator(vel);
        int idx = Math.max(0, Math.min(4, Math.round(navPos)));
        if (idx != navDragIdx) { navDragIdx = idx; haptic(); } // one tick per tab passed
    }

    void cancelNavSpring() {
        navSpringRunning = false;
        navSpringGen++;
        if (navSpringTask != null) { mainHandler.removeCallbacks(navSpringTask); navSpringTask = null; }
    }

    void settleNav() { springNavTo(Math.max(0, Math.min(4, Math.round(navPos))), true); }

    // hand-written damped spring (no libs): stiffness 240, damping ratio ~0.62 -> snappy settle with slight overshoot.
    // Q21 ①：全程只许一条回路——启动时摘除旧任务并自增代次，帧内先验代次再推进；取消/新弹簧/手指接管任一发生，旧回路当帧自尽。
    // Q21 ②：刚度 170→240 缩短落位时长，落位才切页的等待随之压短；拖动中不切页的防闪烁语义不变。
    void springNavTo(final int target, final boolean switchPage) {
        final int gen = ++navSpringGen;
        if (navSpringTask != null) mainHandler.removeCallbacks(navSpringTask);
        navSpringRunning = true;
        navSpringV = 0f;
        final long[] last = { android.os.SystemClock.uptimeMillis() };
        navSpringTask = new Runnable() {
            public void run() {
                if (gen != navSpringGen || !navSpringRunning) return; // 已被更新的回路/取消取代
                long now = android.os.SystemClock.uptimeMillis();
                float dt = Math.min(0.032f, Math.max(0.001f, (now - last[0]) / 1000f)); last[0] = now;
                float k = 240f, c = 2f * 0.62f * (float) Math.sqrt(k);
                float a = -k * (navPos - target) - c * navSpringV;
                navSpringV += a * dt;
                navPos += navSpringV * dt;
                placeNavIndicator(navSpringV);
                if (Math.abs(navPos - target) < 0.002f && Math.abs(navSpringV) < 0.08f) {
                    navPos = target; navSpringV = 0f; navSpringRunning = false; navSpringTask = null;
                    placeNavIndicator(0f);
                    navSettled = target;
                    if (switchPage) {
                        String key = navOrder.get(target);
                        if (!key.equals(tab)) showTab(key); else layoutNavIndicator(target, false);
                    }
                    return;
                }
                mainHandler.postDelayed(this, 16);
            }
        };
        mainHandler.post(navSpringTask);
    }

    void showTab(String key) {
        // Q25: tab switch is a dismiss path too - close the float capsule and the IME
        // before content.removeAllViews() detaches the page that owns the EditText,
        // otherwise the keyboard is orphaned on the next tab.
        if (floatSearchOpen) closeFloatSearch(); else blurSearchBoxes();
        dismissCardMenu();
        tab = key;
        sCrashTab = key;
        content.removeAllViews();
        View page = null;
        try {
            page = pages.get(key);
            if (page == null) {
                switch (key) {
                    case "student": page = buildStudentPage(); break;
                    case "mine": page = buildMinePage(); break;
                    case "news": page = buildNewsPage(); break;
                    case "settings": page = buildSettingsPage(); break;
                    default: page = buildHomePage();
                }
                pages.put(key, page);
            } else if ("mine".equals(key)) {
                // 我的卡片每次进来重建，保证收藏增减即时反映
                page = buildMinePage();
                pages.put(key, page);
            }
        } catch (Throwable t) {
            // Q18: any page-build crash must not kill the app; record via crash handler path + fallback home
            noteGlassFailure();
            try {
                java.io.StringWriter sw = new java.io.StringWriter();
                t.printStackTrace(new java.io.PrintWriter(sw));
                String txt = "page-build failed tab=" + key + " version=" + sCrashVersion + "\n" + sw.toString();
                try { prefs.edit().putString("crash_log", txt).apply(); } catch (Throwable ignored) {}
                crashLogText = txt;
            } catch (Throwable ignored) {}
            pages.remove(key);
            if (!"home".equals(key)) {
                try { page = buildHomePage(); pages.put("home", page); tab = "home"; sCrashTab = "home"; }
                catch (Throwable t2) { page = new FrameLayout(this); }
            } else {
                page = new FrameLayout(this);
            }
            try { showFloatToast("页面打开失败，已回到首页"); } catch (Throwable ignored) {}
        }
        content.addView(page);
        // Q21：弹簧/拖动未落稳时不抓玻璃全图（整屏 draw 会抢主线程、拖动随之发卡）；落稳后由滚动停稳防抖补刷。
        if (rootView != null && !navSpringRunning && !navDragging) rootView.post(() -> refreshLiveGlass()); // Q11：切页后按新页画面刷新玻璃
        // P4：切页淡入 + 轻微上移（220ms 减速曲线，与全 App 开合手感同一语言）
        page.animate().cancel();
        page.setAlpha(0f);
        page.setTranslationY(dp(this, 10));
        page.animate().alpha(1f).translationY(0f)
            .setDuration(220).setInterpolator(ANIM_ENTER).start();
        if ("home".equals(key) && homeList != null) refreshHome();
        restoreCurrentTabScroll();
        syncSearchFab();
        for (Map.Entry<String, LinearLayout> e : navItems.entrySet()) {
            boolean on = e.getKey().equals(key);
            NavIconView ic = navIcons.get(e.getKey());
            if (ic != null) ic.setOn(on);
            TextView lb = navLabels.get(e.getKey());
            if (lb != null) {
                lb.setTextColor(on ? Color.rgb(0x1C, 0x1C, 0x1E) : Color.rgb(0x3A, 0x3A, 0x3C));
                android.graphics.Typeface cur = lb.getTypeface();
                if (cur != null) lb.setTypeface(cur, on ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            }
        }
        // Q17: move the liquid drop (spring if layout ready; the tap path also lands here)
        // Q21：navSettled 只在弹簧真正落稳时写入（弹簧帧内），此处提前写入会让「已落位」与动画中的实际位置脱节。
        int targetIdx = navOrder.indexOf(key);
        if (targetIdx >= 0 && navIndicator != null) {
            if (Math.abs(navPos - targetIdx) > 0.01f) springNavTo(targetIdx, false);
            else navSettled = targetIdx;
        }
    }

    // ---------- Q3 悬浮钮玻璃化（对照混合版 .qf-btn/.quick-fab） ----------
    // 混合版数值：48dp 圆钮、右 20dp、竖列（搜索上/筛选下）gap 10dp、列底距屏底 108dp；
    // 底 rgba(255,255,255,.45)+blur28、描边 rgba(255,255,255,.55) 1dp、图标深色 #1C1C1E 细线 1.8/24 网格、svg 本体 22dp。
    // Q11 起钮内已垫真模糊快照层（见 glassLayer），此渐变只作半透染色盖在模糊上。
    Drawable glassFabBg() {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{Color.argb(118, 255, 255, 255), Color.argb(110, 248, 250, 255),
                Color.argb(104, 232, 238, 246)});
        g.setShape(GradientDrawable.OVAL);
        g.setStroke(dp(this, 1), Color.argb(140, 255, 255, 255));
        return g;
    }
    void applyGlassFabShadow(View v) {
        if (Build.VERSION.SDK_INT >= 21) v.setElevation(dp(this, 12));
        if (Build.VERSION.SDK_INT >= 28) {
            v.setOutlineAmbientShadowColor(Color.argb(41, 20, 30, 60));
            v.setOutlineSpotShadowColor(Color.argb(41, 20, 30, 60));
        }
    }
    // 细线放大镜（对照混合版 qfSearch svg：circle 11,11 r6.5 + 柄 15.8→20.5，stroke 1.8）
    class SearchIconView extends View {
        int iconColor = Color.rgb(0x1C, 0x1C, 0x1E);
        SearchIconView(Context c) { super(c); }
        @Override protected void onDraw(Canvas cv) {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            float ox = getPaddingLeft(), oy = getPaddingTop();
            float sx = (getWidth() - getPaddingLeft() - getPaddingRight()) / 24f;
            float sy = (getHeight() - getPaddingTop() - getPaddingBottom()) / 24f;
            p.setStrokeWidth(1.8f * sx);
            p.setColor(iconColor);
            cv.drawCircle(ox + 11f * sx, oy + 11f * sy, 6.5f * sx, p);
            cv.drawLine(ox + 15.8f * sx, oy + 15.8f * sy, ox + 20.5f * sx, oy + 20.5f * sy, p);
        }
    }
    // Q10: thin-line X for search capsules (mixed .clear-btn / #floatSearchClose), Canvas-drawn, no emoji font glyph
    class CloseIconView extends View {
        int iconColor = Color.rgb(0x1C, 0x1C, 0x1E);
        float lineDp = 1.6f;
        CloseIconView(Context c) { super(c); }
        @Override protected void onDraw(Canvas cv) {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setColor(iconColor);
            float w = getWidth() - getPaddingLeft() - getPaddingRight();
            float h = getHeight() - getPaddingTop() - getPaddingBottom();
            float m = Math.min(w, h) * 0.26f;
            float cx = getPaddingLeft() + w / 2f, cy = getPaddingTop() + h / 2f;
            p.setStrokeWidth(dp(getContext(), lineDp));
            cv.drawLine(cx - m, cy - m, cx + m, cy + m, p);
            cv.drawLine(cx + m, cy - m, cx - m, cy + m, p);
        }
    }
    // Q3 滑杆图标（对照混合版 qfFilter svg：两横线+两圆钮，废除旧漏斗）
    class FilterIconView extends View {
        int iconColor = Color.rgb(0x1C, 0x1C, 0x1E);
        FilterIconView(Context c) { super(c); }
        @Override protected void onDraw(Canvas cv) {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            float ox = getPaddingLeft(), oy = getPaddingTop();
            float sx = (getWidth() - getPaddingLeft() - getPaddingRight()) / 24f;
            float sy = (getHeight() - getPaddingTop() - getPaddingBottom()) / 24f;
            p.setStrokeWidth(1.8f * sx);
            p.setColor(iconColor);
            cv.drawLine(ox + 4f * sx, oy + 8f * sy, ox + 13f * sx, oy + 8f * sy, p);
            cv.drawLine(ox + 19f * sx, oy + 8f * sy, ox + 20f * sx, oy + 8f * sy, p);
            cv.drawLine(ox + 4f * sx, oy + 16f * sy, ox + 7f * sx, oy + 16f * sy, p);
            cv.drawLine(ox + 13f * sx, oy + 16f * sy, ox + 20f * sx, oy + 16f * sy, p);
            cv.drawCircle(ox + 16f * sx, oy + 8f * sy, 2.2f * sx, p);
            cv.drawCircle(ox + 10f * sx, oy + 16f * sy, 2.2f * sx, p);
        }
    }

    // 只在首页、且没有整屏覆盖层时出现；覆盖层（详情/向导/欢迎/日志）都会
    // content.removeAllViews()，天然把它清掉，回到首页时 showTab 会再挂回来。
    void syncSearchFab() {
        boolean want = "home".equals(tab) && detailCard == null && !wizardOpen && !welcomeOpen && !changelogOpen && filterSheet == null; // P2e：筛选窗开着时双钮退场
        if (!want) {
            if (searchFab != null && searchFab.getParent() != null)
                ((ViewGroup) searchFab.getParent()).removeView(searchFab);
            searchFab = null;
            if (filterFab != null && filterFab.getParent() != null)
                ((ViewGroup) filterFab.getParent()).removeView(filterFab);
            filterFab = null;
            filterFabBadge = null;
            syncTopFab();
            return;
        }
        // 搜索钮
        if (searchFab == null || searchFab.getParent() != content) {
            if (searchFab != null && searchFab.getParent() != null)
                ((ViewGroup) searchFab.getParent()).removeView(searchFab);
            searchFab = buildSearchFab();
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(this, 48), dp(this, 48));
            lp.gravity = Gravity.END | Gravity.BOTTOM;
            lp.rightMargin = dp(this, 20);
            lp.bottomMargin = dp(this, 166) + navBarH(); // Q3：竖列上钮 = 列底 108 + 钮 48 + 间距 10；Q26 再加导航栏避让
            content.addView(searchFab, lp);
            searchFab.setAlpha(0f);
            searchFab.setScaleX(0.8f);
            searchFab.setScaleY(0.8f);
            searchFab.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(ANIM_DUR_FADE).setInterpolator(ANIM_ENTER).start();
        }
        // Q3 筛选钮：搜索钮正下方（同右距 20、列底 108），废除旧横排左邻位
        if (filterFab == null || filterFab.getParent() != content) {
            if (filterFab != null && filterFab.getParent() != null)
                ((ViewGroup) filterFab.getParent()).removeView(filterFab);
            filterFab = buildFilterFab();
            FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(dp(this, 48), dp(this, 48));
            flp.gravity = Gravity.END | Gravity.BOTTOM;
            flp.rightMargin = dp(this, 20);
            flp.bottomMargin = dp(this, 108) + navBarH(); // Q26：导航栏避让
            content.addView(filterFab, flp);
            filterFab.setAlpha(0f);
            filterFab.setScaleX(0.8f);
            filterFab.setScaleY(0.8f);
            filterFab.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(ANIM_DUR_FADE).setInterpolator(ANIM_ENTER).start();
        } else {
            updateFilterFabBadge();
        }
        syncTopFab();
    }

    void updateFilterFabBadge() {
        if (filterFabBadge == null || filterFab == null) return;
        int n = activeFilterCount();
        // Q3：混合版已选只靠蓝角标计数，钮体玻璃底恒定不变（废除旧淡蓝底切换）
        if (n <= 0) {
            filterFabBadge.setVisibility(View.GONE);
        } else {
            filterFabBadge.setVisibility(View.VISIBLE);
            filterFabBadge.setText(n > 9 ? "9+" : String.valueOf(n));
        }
    }

    View buildFilterFab() {
        FrameLayout fab = new FrameLayout(this);
        fab.setClipChildren(false);
        fab.setClipToPadding(false);
        int n0 = activeFilterCount();
        fab.setBackground(glassFabBg());
        fab.addView(glassLayer(fab, -1, true), new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        applyGlassFabShadow(fab);
        FilterIconView icon = new FilterIconView(this);
        int pad = dp(this, 13); // 48 钮内 svg 本体 22dp：(48-22)/2
        icon.setPadding(pad, pad, pad, pad);
        fab.addView(icon, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // 角标：对照混合版 .qf-badge（#007AFF 蓝、右上 -4 外探、min 18×18、字 .68rem）
        TextView badge = tv(this, "", 10, Color.WHITE, true);
        badge.setGravity(Gravity.CENTER);
        GradientDrawable bbg = new GradientDrawable();
        bbg.setShape(GradientDrawable.OVAL);
        bbg.setColor(Color.rgb(0x00, 0x7A, 0xFF));
        badge.setBackground(bbg);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(dp(this, 18), dp(this, 18));
        blp.gravity = Gravity.END | Gravity.TOP;
        blp.topMargin = -dp(this, 4);
        blp.rightMargin = -dp(this, 4);
        fab.addView(badge, blp);
        filterFabBadge = badge;
        if (n0 <= 0) badge.setVisibility(View.GONE);
        else { badge.setVisibility(View.VISIBLE); badge.setText(n0 > 9 ? "9+" : String.valueOf(n0)); }
        fab.setOnClickListener(v -> {
            haptic();
            openFilterSheet();
        });
        fab.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) pressBounce(v, true);
            else if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL)
                pressBounce(v, false);
            return false;
        });
        return fab;
    }

    View buildSearchFab() {
        FrameLayout fab = new FrameLayout(this);
        fab.setBackground(glassFabBg());
        fab.addView(glassLayer(fab, -1, true), new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        applyGlassFabShadow(fab);
        SearchIconView icon = new SearchIconView(this);
        int pad = dp(this, 13); // 48 钮内 svg 本体 22dp
        icon.setPadding(pad, pad, pad, pad);
        fab.addView(icon, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        fab.setOnClickListener(v -> {
            haptic();
            focusSearch();
        });
        fab.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) pressBounce(v, true);
            else if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL)
                pressBounce(v, false);
            return false;
        });
        return fab;
    }

    // Q10: blur whichever search box has focus + hide keyboard (inline or float)
    // Q25: capture the window token BEFORE clearing focus. The old order cleared focus
    // first and then read getCurrentFocus(), which is null by then, so hideSoftInput was
    // skipped and the IME stayed on screen after scroll/outside-tap dismissal.
    void blurSearchBoxes() {
        IBinder token = null;
        try {
            View foc = getCurrentFocus();
            if (foc != null) token = foc.getWindowToken();
        } catch (Throwable ignored) {}
        if (token == null) {
            try { if (floatSearchBox != null) token = floatSearchBox.getWindowToken(); } catch (Throwable ignored) {}
        }
        if (token == null) {
            try { if (searchBox != null) token = searchBox.getWindowToken(); } catch (Throwable ignored) {}
        }
        if (token == null) {
            try {
                if (getWindow() != null && getWindow().getDecorView() != null)
                    token = getWindow().getDecorView().getWindowToken();
            } catch (Throwable ignored) {}
        }
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null && token != null) imm.hideSoftInputFromWindow(token, 0);
        } catch (Throwable ignored) { /* no IME: focus is still cleared below */ }
        try { if (floatSearchBox != null && floatSearchBox.hasFocus()) floatSearchBox.clearFocus(); } catch (Throwable ignored) {}
        try { if (searchBox != null && searchBox.hasFocus()) searchBox.clearFocus(); } catch (Throwable ignored) {}
    }

    void dismissSearch() {
        if (floatSearchOpen) { closeFloatSearch(); return; }
        blurSearchBoxes();
    }

    // Legacy hook kept as no-op: the inline bar lives in the scroll flow now and scrolls away by itself.
    void setHomeSearchBarShown(boolean show, boolean animate) {
        homeSearchBarShown = true;
    }

    void syncInlineClearBtn() {
        if (inlineClearBtn == null) return;
        boolean has = query != null && !query.isEmpty();
        inlineClearBtn.setVisibility(has ? View.VISIBLE : View.GONE);
    }

    // Keep inline/float boxes mirrored without re-entrant watcher loops; single refresh per real change.
    void applySearchText(String text, EditText from) {
        String t = text == null ? "" : text;
        query = t.trim();
        if (!syncingSearchText) {
            syncingSearchText = true;
            try {
                if (from != searchBox && searchBox != null && !t.equals(searchBox.getText().toString()))
                    searchBox.setText(t);
                if (from != floatSearchBox && floatSearchBox != null && !t.equals(floatSearchBox.getText().toString()))
                    floatSearchBox.setText(t);
            } finally { syncingSearchText = false; }
        }
        syncInlineClearBtn();
        refreshHome();
    }

    void openFloatSearch() {
        if (floatSearchBar == null || floatSearchOpen) return;
        floatSearchOpen = true;
        if (searchBox != null && floatSearchBox != null) {
            syncingSearchText = true;
            try { floatSearchBox.setText(searchBox.getText().toString()); } finally { syncingSearchText = false; }
        }
        final View bar = floatSearchBar;
        bar.setVisibility(View.VISIBLE);
        bar.setAlpha(0f);
        bar.setTranslationY(-dp(this, 8));
        bar.animate().alpha(1f).translationY(0f).setDuration(ANIM_DUR_FADE)
            .setInterpolator(ANIM_ENTER).start();
        // The glass layer inside was laid out while GONE (applyGlass gave up after retries):
        // re-sample once now that it has a position, so the capsule is blurred glass, not blank.
        bar.post(() -> refreshLiveGlass());
        if (floatSearchBox != null) floatSearchBox.postDelayed(() -> {
            if (!floatSearchOpen || floatSearchBox == null) return;
            // Q25: reopen from a clean focus state, never on top of a stale inline focus.
            try { if (searchBox != null && searchBox.hasFocus()) searchBox.clearFocus(); } catch (Throwable ignored) {}
            floatSearchBox.requestFocus();
            try { floatSearchBox.setSelection(floatSearchBox.getText().length()); } catch (Exception e) { /* ignore */ }
            showKeyboard(floatSearchBox);
        }, 60);
    }

    void closeFloatSearch() {
        if (!floatSearchOpen) { blurSearchBoxes(); return; }
        floatSearchOpen = false;
        // Keep the list exactly where it is (Q10 red line): no scrollTo here, unlike the old P-searchfix path.
        blurSearchBoxes();
        final View bar = floatSearchBar;
        if (bar == null) return;
        bar.animate().alpha(0f).translationY(-dp(this, 8)).setDuration(180)
            .setInterpolator(ANIM_EXIT)
            .withEndAction(() -> { if (!floatSearchOpen) bar.setVisibility(View.GONE); }).start();
    }

    void showKeyboard(View target) {
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(target, InputMethodManager.SHOW_IMPLICIT);
        } catch (Exception e) { /* no IME: silent */ }
    }

    // Tap outside whichever capsule is focused -> that search goes away.
    @Override public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev != null && ev.getAction() == MotionEvent.ACTION_DOWN) {
            boolean foc = (searchBox != null && searchBox.hasFocus())
                || (floatSearchBox != null && floatSearchBox.hasFocus());
            if (foc) {
                float x = ev.getRawX(), y = ev.getRawY();
                boolean inside = false;
                for (View cap : new View[]{homeSearchBar, floatSearchBar}) {
                    if (cap == null || cap.getVisibility() != View.VISIBLE || cap.getWidth() <= 0) continue;
                    int[] loc = new int[2];
                    cap.getLocationOnScreen(loc);
                    if (x >= loc[0] && x <= loc[0] + cap.getWidth()
                        && y >= loc[1] && y <= loc[1] + cap.getHeight()) { inside = true; break; }
                }
                if (!inside) dismissSearch();
            }
        }
        return super.dispatchTouchEvent(ev);
    }

    void focusSearch() {
        // Q10 (mixed openFloatSearch): near the top the inline capsule is already on screen;
        // mid-page must pop the float capsule in place without touching the list position.
        int y = homeScroll != null ? homeScroll.getScrollY() : 0;
        if (y < dp(this, 120)) {
            if (y > 0 && homeScroll != null) {
                int from = y;
                long dur = Math.min(420, 200 + from / 6);
                ValueAnimator va = ValueAnimator.ofInt(from, 0);
                va.setDuration(dur);
                va.setInterpolator(ANIM_ENTER);
                va.addUpdateListener(a -> { if (homeScroll != null) homeScroll.scrollTo(0, (int) a.getAnimatedValue()); });
                va.start();
                if (searchBox != null) searchBox.postDelayed(() -> {
                    if (searchBox == null) return;
                    searchBox.requestFocus();
                    try { searchBox.setSelection(searchBox.getText().length()); } catch (Exception e) { /* ignore */ }
                    showKeyboard(searchBox);
                }, dur + 60);
            } else if (searchBox != null) {
                searchBox.requestFocus();
                try { searchBox.setSelection(searchBox.getText().length()); } catch (Exception e) { /* ignore */ }
                showKeyboard(searchBox);
            }
            return;
        }
        openFloatSearch();
    }

    // ---------- 通用：卡片瓷砖 ----------
    // P4：卡片按压波纹——圆角底 + 淡蓝灰涟漪（RippleDrawable，minSdk 24 可用），clipToOutline 防溢出圆角
    Drawable rippleBg(int color, float radiusDp) {
        Drawable base = roundRect(color, radiusDp, this);
        return new RippleDrawable(ColorStateList.valueOf(Color.argb(38, 10, 92, 214)), base, null);
    }

    View cardTile(final Card c, ViewGroup parent) {
        return cardTile(c, parent, cols);
    }

    // Q24 首页瓷砖加卡钮（对照混合版 .mine-btn：32dp 半透圆钮，3 列 26dp）：未加入灰半透底＋白色加号、
    // 已加入蓝半透底＋白色勾（rgba(0,122,255,.38)），细线 Canvas 绘制禁用 emoji；按下 .9 回弹照 .mine-btn:active。
    class MineAddBtn extends View {
        boolean on = false;
        MineAddBtn(Context ctx) { super(ctx); setClickable(true); setFocusable(false); }
        void setOn(boolean v) { on = v; invalidate(); }
        @Override protected void onDraw(Canvas cv) {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            float r = Math.min(getWidth(), getHeight()) / 2f;
            p.setStyle(Paint.Style.FILL);
            p.setColor(on ? Color.argb(97, 0, 122, 255) : Color.argb(64, 120, 120, 128));
            cv.drawCircle(cx, cy, r, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            p.setStrokeWidth(dp(getContext(), 1.8f));
            p.setColor(Color.WHITE);
            float s = r * 0.42f;
            if (on) {
                android.graphics.Path path = new android.graphics.Path();
                path.moveTo(cx - s, cy + s * 0.05f);
                path.lineTo(cx - s * 0.25f, cy + s * 0.72f);
                path.lineTo(cx + s * 1.05f, cy - s * 0.62f);
                cv.drawPath(path, p);
            } else {
                cv.drawLine(cx - s, cy, cx + s, cy, p);
                cv.drawLine(cx, cy - s, cx, cy + s, p);
            }
        }
    }

    // P-grid：瓷砖规格统一——图区按 1.586 卡面比例定高（同列同宽同高）、卡名预留两行、行内等高拉伸，底边齐平
    // Q24：卡图改全幅 cover 铺满图区（对照混合版 .art/.art-img object-fit:cover，图区贴瓷砖顶边满宽、不留白、
    // 不拉伸；圆角靠瓷砖外框 clipToOutline 平滑裁切，冲突时保铺满）+ 图右上半透圆加卡钮（.mine-btn）。
    View cardTile(final Card c, ViewGroup parent, int nCols) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(rippleBg(Color.WHITE, 14));
        box.setClipToOutline(true);
        roundClip(box, 14, this); // Q27：瓷砖同机制——外框显式圆角轮廓，顶图四角随瓷砖一同裁净
        AbsListView.LayoutParams lp = new AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        box.setLayoutParams(lp);

        int nc = (nCols == 1 || nCols == 3) ? nCols : 2;
        int availW = getResources().getDisplayMetrics().widthPixels - dp(this, 28) - (nc - 1) * dp(this, 10);
        int tileW = availW / nc;
        int imgH = Math.max(dp(this, 40), Math.round(tileW / 1.586f));
        FrameLayout art = new FrameLayout(this);
        box.addView(art, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, imgH));
        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP); // cover：铺满不留白、等比不拉伸
        iv.setBackground(placeholderGrad(0, this));
        art.addView(iv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        Bitmap b = Img.get(this, c.image);
        if (b != null) iv.setImageBitmap(b); else iv.setImageBitmap(null);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(this, 8), dp(this, 7), dp(this, 8), dp(this, 10));
        box.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView name = tv(this, c.name, 13, Color.rgb(0x1C, 0x1C, 0x1E), true);
        name.setMaxLines(2);
        name.setMinLines(2);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        body.addView(name, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView sub = tv(this, c.bank + " · " + orgLabel(c.org), 10.5f, Color.rgb(0x8E, 0x8E, 0x93), false);
        sub.setMaxLines(1);
        sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        body.addView(sub);

        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(this, 6);
        body.addView(chips, clp);
        float chipSp = nc == 3 ? 8.5f : 10f;
        chips.addView(chip(String.format(java.util.Locale.US, "%.1f分", c.score), Color.rgb(0xE8, 0xF1, 0xFD), Color.rgb(0x0A, 0x5C, 0xD6), chipSp));
        chips.addView(chip("已停发".equals(c.status) ? "已停发" : "在发",
            "已停发".equals(c.status) ? Color.rgb(0xF3, 0xE8, 0xE8) : Color.rgb(0xE6, 0xF6, 0xEC),
            "已停发".equals(c.status) ? Color.rgb(0xB0, 0x23, 0x2B) : Color.rgb(0x1D, 0x8A, 0x49), chipSp));
        final TextView addedChip = chip("已添加", Color.rgb(0xE6, 0xF6, 0xEC), Color.rgb(0x1D, 0x8A, 0x49), chipSp);
        if (mine.contains(c.id)) chips.addView(addedChip);

        // Q24 加卡钮：点它直接切换我的卡片不进详情（对照混合版 [data-mine] 点击 stopPropagation + toggleMine，
        // 反馈走既有悬浮提示条/撤销）；钮态与「已添加」chip 在切换/撤销后就地同步，不整页重绘。
        final MineAddBtn mineBtn = new MineAddBtn(this);
        mineBtn.setOn(mine.contains(c.id));
        int btnSize = nc == 3 ? dp(this, 26) : dp(this, 32);
        int btnEdge = nc == 3 ? dp(this, 6) : dp(this, 8);
        FrameLayout.LayoutParams blp2 = new FrameLayout.LayoutParams(btnSize, btnSize);
        blp2.gravity = Gravity.TOP | Gravity.RIGHT;
        blp2.topMargin = btnEdge; blp2.rightMargin = btnEdge;
        art.addView(mineBtn, blp2);
        mineBtn.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) pressBounce(v, true);
            else if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL) pressBounce(v, false);
            return false;
        });
        mineBtn.setOnClickListener(v -> {
            haptic();
            toggleMineWithToast(c, () -> {
                boolean in = mine.contains(c.id);
                mineBtn.setOn(in);
                if (in) { if (addedChip.getParent() == null) chips.addView(addedChip); }
                else if (addedChip.getParent() != null) chips.removeView(addedChip);
            });
        });
        // Q1 长按贴卡菜单（对照混合版 450ms 长按；我的卡片页会清掉此触摸改走拖动排序，语义不冲突；
        // 加卡钮为独立可点子视图，按住它不触发贴卡菜单，同混合版 closest('[data-mine]') 排除）
        attachCardMenuLongPress(box, c, false);
        return box;
    }

    // Q1 长按贴卡菜单（对照混合版 app.js openCardMenu/closeCardMenu 现行行为，删除 P-press 放大预览）：
    // 按住 450ms 触发；被按卡大小不变、原地 1:1 快照浮在轻暗遮罩上 + 2.5dp 蓝框 + 浮起阴影，其余内容被遮罩压暗；
    // 贴着被按卡浮出 224dp 小菜单（查看详情 / 添加到我的卡片 或 从我的卡片移除，细线图标禁用 emoji），菜单出现时底栏让开。
    // Q11 起菜单下已垫冻结真模糊层；其余背景仍以 rgba(18,22,36,.14) 轻暗（混合版为 blur+轻暗）。
    class CardMenuTouch implements View.OnTouchListener {
        final View anchor;
        final Card card;
        final boolean fromWiz;
        float downX, downY;
        boolean armed = false, fired = false;
        final Runnable fireTask;
        CardMenuTouch(View v, Card c, boolean wiz) {
            anchor = v; card = c; fromWiz = wiz;
            fireTask = () -> {
                if (!armed) return;
                fired = true;
                openCardMenu(card, anchor, fromWiz);
            };
        }
        @Override public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    armed = true; fired = false;
                    downX = e.getRawX(); downY = e.getRawY();
                    mainHandler.removeCallbacks(fireTask);
                    mainHandler.postDelayed(fireTask, 450);
                    return false;
                case MotionEvent.ACTION_MOVE:
                    // 移动超过 10dp 视为滑动，取消（对照混合版）
                    if (armed && !fired && (Math.abs(e.getRawX() - downX) > dp(MainActivity.this, 10)
                        || Math.abs(e.getRawY() - downY) > dp(MainActivity.this, 10))) {
                        armed = false;
                        mainHandler.removeCallbacks(fireTask);
                    }
                    return false;
                case MotionEvent.ACTION_UP: {
                    mainHandler.removeCallbacks(fireTask);
                    boolean was = fired;
                    armed = false; fired = false;
                    return was; // 已弹菜单时吞掉松手，避免紧接着的 click 误开详情
                }
                case MotionEvent.ACTION_CANCEL:
                    mainHandler.removeCallbacks(fireTask);
                    armed = false; fired = false;
                    return false;
                default:
                    return false;
            }
        }
    }

    void attachCardMenuLongPress(View v, Card c, boolean fromWiz) {
        v.setOnTouchListener(new CardMenuTouch(v, c, fromWiz));
    }

    // Q1 菜单细线星标（Canvas 手绘星形轮廓，与导航/搜索图标同一线条语言）
    class StarIconView extends View {
        int iconColor = Color.rgb(0x1C, 0x1C, 0x1E);
        StarIconView(Context c) { super(c); }
        @Override protected void onDraw(Canvas cv) {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            p.setStrokeWidth(dp(getContext(), 1.8f));
            p.setColor(iconColor);
            float w = getWidth(), h = getHeight();
            float sx = w / 24f, sy = h / 24f;
            float[][] pts = {
                {12f, 3f}, {14.23f, 8.93f}, {20.56f, 9.22f}, {15.61f, 13.17f}, {17.29f, 19.28f},
                {12f, 15.8f}, {6.71f, 19.28f}, {8.39f, 13.17f}, {3.44f, 9.22f}, {9.77f, 8.93f}
            };
            android.graphics.Path path = new android.graphics.Path();
            for (int i = 0; i < pts.length; i++) {
                float x = pts[i][0] * sx, y = pts[i][1] * sy;
                if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
            }
            path.close();
            cv.drawPath(path, p);
        }
    }

    View cardMenuRow(View icon, String text, final Runnable act) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(this, 10), dp(this, 11), dp(this, 10), dp(this, 11));
        row.addView(icon, new LinearLayout.LayoutParams(dp(this, 20), dp(this, 20)));
        TextView t = tv(this, text, 15, Color.rgb(0x1C, 0x1C, 0x1E), false);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.leftMargin = dp(this, 10);
        row.addView(t, tlp);
        // 按下底色 rgba(0,122,255,.10)（对照 .cp-row:active）
        row.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) v.setBackground(roundRect(Color.argb(26, 0, 122, 255), 10, MainActivity.this));
            else if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL) v.setBackground(null);
            return false;
        });
        row.setOnClickListener(v -> { haptic(); act.run(); });
        return row;
    }

    void openCardMenu(final Card c, final View anchor, final boolean fromWiz) {
        if (c == null || anchor == null || rootView == null) return;
        if (!anchor.isAttachedToWindow() || cardMenuPop != null) return;
        haptic(); // 对照混合版触发时 buzz(12)，走用户触感档位
        dismissSearch();

        View backdrop = new View(this);
        backdrop.setBackgroundColor(Color.argb(36, 18, 22, 36));
        backdrop.setClickable(true);
        backdrop.setOnClickListener(v -> closeCardMenu());
        rootView.addView(backdrop, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        cardMenuBackdrop = backdrop;

        // 高亮：源卡 1:1 快照浮在遮罩上（原大小不变），蓝框 2.5dp rgba(0,122,255,.7) + 浮起阴影（对照 pop-clone）
        int w = Math.max(1, anchor.getWidth()), h = Math.max(1, anchor.getHeight());
        int[] rl = new int[2]; rootView.getLocationOnScreen(rl);
        int[] al = new int[2]; anchor.getLocationOnScreen(al);
        int left = al[0] - rl[0], top = al[1] - rl[1];
        FrameLayout clone = new FrameLayout(this);
        try {
            Bitmap snap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            anchor.draw(new Canvas(snap));
            ImageView iv = new ImageView(this);
            iv.setImageBitmap(snap);
            iv.setScaleType(ImageView.ScaleType.FIT_XY);
            clone.addView(iv, new FrameLayout.LayoutParams(w, h));
        } catch (Exception e) { /* 快照失败仍保留蓝框定位 */ }
        GradientDrawable border = new GradientDrawable();
        border.setColor(Color.TRANSPARENT);
        border.setCornerRadius(dp(this, 14));
        border.setStroke(dp(this, 2.5f), Color.argb(179, 0, 122, 255));
        View borderV = new View(this);
        borderV.setBackground(border);
        borderV.setClickable(false);
        clone.addView(borderV, new FrameLayout.LayoutParams(w, h));
        clone.setClickable(false);
        if (Build.VERSION.SDK_INT >= 21) clone.setElevation(dp(this, 18));
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(w, h);
        clp.leftMargin = left; clp.topMargin = top;
        rootView.addView(clone, clp);
        cardMenuClone = clone;

        // 贴卡小菜单（对照 .card-pop：宽 224、内边距 6、圆角 16、白色半透、深柔影）
        LinearLayout pop = new LinearLayout(this);
        pop.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable pbg = new GradientDrawable();
        pbg.setColor(Color.argb(198, 255, 255, 255)); // Q11：半透染色盖在冻结模糊层上
        pbg.setCornerRadius(dp(this, 16));
        pbg.setStroke(dp(this, 1), Color.argb(46, 20, 30, 60));
        pop.setBackground(pbg);
        if (Build.VERSION.SDK_INT >= 21) pop.setElevation(dp(this, 24));
        pop.setPadding(dp(this, 6), dp(this, 6), dp(this, 6), dp(this, 6));
        pop.setClickable(true);

        TextView title = tv(this, c.name, 12.5f, Color.rgb(0x8E, 0x8E, 0x93), false);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setPadding(dp(this, 10), dp(this, 7), dp(this, 10), dp(this, 5));
        pop.addView(title, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        SearchIconView detailIcon = new SearchIconView(this);
        detailIcon.iconColor = Color.rgb(0x1C, 0x1C, 0x1E);
        pop.addView(cardMenuRow(detailIcon, "查看详情", () -> {
            dismissCardMenu();
            openDetail(c, fromWiz);
        }));
        StarIconView starIcon = new StarIconView(this);
        pop.addView(cardMenuRow(starIcon, mine.contains(c.id) ? "从我的卡片移除" : "添加到我的卡片", () -> {
            closeCardMenu();
            toggleMineWithToast(c, () -> {
                if (fromWiz) { if (wizardOpen) showWizardPage(); }
                else if ("home".equals(tab)) refreshHome();
            });
        }));

        // 定位：优先贴在卡下方 8dp，放不下改上方；左右夹在屏内 12dp（对照 openCardMenu 的 W=224 定位）
        int popW = dp(this, 224);
        // Q11：菜单入树前先备好冻结模糊层（快照含轻暗遮罩之下的页面，与 backdrop-filter 叠序一致）
        final ImageView menuGlass = glassLayer(pop, 16, false);
        cardMenuGlass = menuGlass;
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(popW, ViewGroup.LayoutParams.WRAP_CONTENT);
        rootView.addView(pop, plp);
        cardMenuPop = pop;
        pop.measure(View.MeasureSpec.makeMeasureSpec(popW, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int popH = pop.getMeasuredHeight();
        int rootW = rootView.getWidth(), rootH = rootView.getHeight();
        int pl = Math.max(dp(this, 12), Math.min(rootW - popW - dp(this, 12), left + w / 2 - popW / 2));
        boolean below = top + h + dp(this, 8) + popH <= rootH - dp(this, 12);
        int pt = below ? top + h + dp(this, 8) : Math.max(dp(this, 12), top - dp(this, 8) - popH);
        plp.leftMargin = pl; plp.topMargin = pt;
        pop.setLayoutParams(plp);
        FrameLayout.LayoutParams mglp = new FrameLayout.LayoutParams(popW, popH);
        mglp.leftMargin = pl; mglp.topMargin = pt;
        rootView.addView(menuGlass, Math.max(0, rootView.indexOfChild(pop)), mglp);
        pop.setPivotX(Math.max(0, Math.min(popW, left + w / 2 - pl)));
        pop.setPivotY(below ? 0 : popH);
        pop.setAlpha(0f); pop.setScaleX(0.94f); pop.setScaleY(0.94f);
        pop.animate().alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(160).setInterpolator(ANIM_ENTER).start();

        // 菜单出现时底栏让开，不挡靠近底部的卡（对照 setDockVisible(false)）
        if (navWrap != null) navWrap.setVisibility(View.GONE);
    }

    static void removeViewNow(View v) {
        if (v != null && v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v);
    }

    // 关闭走 140ms 缩小淡出（对照 closeCardMenu），遮罩与高亮立即撤，底栏立即恢复
    void closeCardMenu() {
        final View pop = cardMenuPop, bd = cardMenuBackdrop, cl = cardMenuClone, mg = cardMenuGlass;
        cardMenuPop = null; cardMenuBackdrop = null; cardMenuClone = null; cardMenuGlass = null;
        removeViewNow(mg);
        if (navWrap != null) navWrap.setVisibility(View.VISIBLE);
        removeViewNow(bd);
        removeViewNow(cl);
        if (pop == null) return;
        pop.animate().cancel();
        pop.animate().alpha(0f).scaleX(0.94f).scaleY(0.94f)
            .setDuration(140).setInterpolator(ANIM_EXIT)
            .withEndAction(() -> removeViewNow(pop)).start();
    }

    // 立即关闭（切页/开详情/返回拦截）：不走动画，避免浮层残留
    void dismissCardMenu() {
        View pop = cardMenuPop, bd = cardMenuBackdrop, cl = cardMenuClone, mg = cardMenuGlass;
        cardMenuPop = null; cardMenuBackdrop = null; cardMenuClone = null; cardMenuGlass = null;
        removeViewNow(mg);
        if (navWrap != null) navWrap.setVisibility(View.VISIBLE);
        removeViewNow(pop);
        removeViewNow(bd);
        removeViewNow(cl);
    }

    TextView chip(String s, int bg, int fg) {
        return chip(s, bg, fg, 10f);
    }

    TextView chip(String s, int bg, int fg, float sp) {
        TextView t = tv(this, s, sp, fg, true);
        t.setBackground(roundRect(bg, 7, this));
        t.setPadding(dp(this, 6), dp(this, 3), dp(this, 6), dp(this, 3));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(this, 5);
        t.setLayoutParams(lp);
        return t;
    }

    class CardAdapter extends BaseAdapter {
        List<Card> data;
        CardAdapter(List<Card> d) { data = d; }
        public int getCount() { return data.size(); }
        public Object getItem(int i) { return data.get(i); }
        public long getItemId(int i) { return i; }
        public View getView(int i, View convert, ViewGroup parent) {
            return cardTile(data.get(i), parent);
        }
    }

    // ---------- 首页 ----------
    // P2d/Q11：毛玻璃白悬浮搜索栏（圆角 + 淡描边 + 投影，Q11 起栏内垫 live 真模糊层，上为半透染色）
    GradientDrawable glassPillBg() {
        // Q18 (4): tint 148 -> 128 so background colour/shape stays recognisable through the capsule
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{Color.argb(128, 255, 255, 255), Color.argb(118, 246, 247, 250)});
        g.setCornerRadius(dp(this, 999));
        g.setStroke(dp(this, 1), Color.argb(70, 20, 30, 60));
        return g;
    }

    // Q10 float-search shell: shallow glass, slight blue (mixed .float-search rgba(255,255,255,.85)+blur24 saturate1.7)
    GradientDrawable glassFloatBg() {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{Color.argb(222, 255, 255, 255), Color.argb(212, 238, 245, 255)});
        g.setCornerRadius(dp(this, 999));
        g.setStroke(dp(this, 1), Color.argb(153, 255, 255, 255));
        return g;
    }

    // P2d：整页改单 ScrollView 流，内容从悬浮栏底下滚过；英雄卡与网格同流 10dp 间隔，不再压首排
    View buildHomePage() {
        FrameLayout page = new FrameLayout(this);

        homeScroll = new ScrollView(this);
        thinScrollbar(homeScroll);
        homeScroll.setFillViewport(true);
        homeScroll.setClipToPadding(false);
        // P-searchfix：列表滚动时搜索自动收起/失焦；点列表区域（框外）收键盘
        // Q10: any list scroll dismisses the float capsule (mixed onscroll closeFloatSearch);
        // the inline capsule needs no handler here - it scrolls off with the content by itself.
        homeScroll.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN && floatSearchOpen) closeFloatSearch();
            return false;
        });
        homeScroll.setOnScrollChangeListener((v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
            pageScrollSaveY.put("home", scrollY);
            if (floatSearchOpen && scrollY != oldScrollY) closeFloatSearch();
            lastHomeScrollY = scrollY;
            updateTopFabVisibility(scrollY);
        });
        page.addView(homeScroll, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        // Q10: title + inline search flow together under the status bar; nothing floats over them.
        col.setPadding(dp(this, 14), statusBarH() + dp(this, 16), dp(this, 14), dockPad());
        homeScroll.addView(col, new ScrollView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        col.addView(tv(this, "卡盒", 24, Color.rgb(0x1C, 0x1C, 0x1E), true));

        Set<String> banks = new HashSet<>();
        for (Card c : Store.all) banks.add(c.bank);
        int debit = 0, stopped = 0;
        for (Card c : Store.all) { if (!c.isCredit()) debit++; if ("已停发".equals(c.status)) stopped++; }

        TextView stats = tv(this, Store.all.size() + " 张卡 · " + banks.size() + " 家银行", 12, Color.rgb(0x8E, 0x8E, 0x93), false);
        LinearLayout.LayoutParams stlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stlp.topMargin = dp(this, 8);
        col.addView(stats, stlp);

        LinearLayout frow = new LinearLayout(this);
        frow.setOrientation(LinearLayout.HORIZONTAL);
        frow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams frowLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        frowLp.topMargin = dp(this, 8);
        col.addView(frow, frowLp);
        homeCount = tv(this, "", 12.5f, Color.rgb(0x8E, 0x8E, 0x93), false);
        frow.addView(homeCount, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        filterBtn = new Button(this);
        filterBtn.setText("筛选");
        filterBtn.setTextSize(12.5f);
        filterBtn.setAllCaps(false);
        filterBtn.setMinWidth(0);
        filterBtn.setBackground(roundRect(Color.WHITE, 11, this));
        filterBtn.setOnClickListener(v -> { haptic(); openFilterSheet(); });
        frow.addView(filterBtn, new LinearLayout.LayoutParams(dp(this, 92), dp(this, 34)));

        // 已选标签栏（Phase 2a-2，对照 renderActiveFilters：点标签即移除该项筛选）
        android.widget.HorizontalScrollView afScroll = new android.widget.HorizontalScrollView(this);
        afScroll.setHorizontalScrollBarEnabled(false);
        activeFilterBar = new LinearLayout(this);
        activeFilterBar.setOrientation(LinearLayout.HORIZONTAL);
        activeFilterBar.setPadding(0, dp(this, 8), 0, dp(this, 2));
        afScroll.addView(activeFilterBar);
        afScroll.setVisibility(View.GONE);
        col.addView(afScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        activeFilterBar.setTag(afScroll);

        // 卡库总览（与混合版同款深蓝卡）：与网格同在一条滚动流里，间距 10dp，结构上不可能遮挡
        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable hg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
            new int[]{Color.rgb(0x0B, 0x3D, 0x91), Color.rgb(0x0A, 0x6E, 0xD6), Color.rgb(0x00, 0xA3, 0xC8)});
        hg.setCornerRadius(dp(this, 20));
        hero.setBackground(hg);
        hero.setPadding(dp(this, 18), dp(this, 16), dp(this, 18), dp(this, 16));
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = dp(this, 10);
        col.addView(hero, hlp);
        hero.addView(tv(this, "卡库总览", 13, Color.argb(200, 255, 255, 255), false));
        TextView big = tv(this, Store.all.size() + " 张卡", 34, Color.WHITE, true);
        hero.addView(big);
        hero.addView(tv(this, banks.size() + " 家银行 · 6 大卡组织", 13, Color.argb(220, 255, 255, 255), false));
        LinearLayout tiles = new LinearLayout(this);
        tiles.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(this, 12);
        hero.addView(tiles, tlp);
        tiles.addView(heroTile(debit + " 张", "借记卡"), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        tiles.addView(heroTile((Store.all.size() - debit) + " 张", "信用卡"), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        tiles.addView(heroTile(stopped + " 张", "已停发"), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        homeList = new LinearLayout(this);
        homeList.setOrientation(LinearLayout.VERTICAL);
        col.addView(homeList, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Q10 inline search capsule (mixed header .top/#search): in-flow under the title, pill 999,
        // glass white rgba(255,255,255,.78)+blur20, thin magnifier, clear-X circle appears once typing.
        FrameLayout inlineWrap = new FrameLayout(this);
        inlineWrap.setBackground(glassPillBg());
        if (Build.VERSION.SDK_INT >= 21) inlineWrap.setElevation(dp(this, 6));
        inlineWrap.addView(glassLayer(inlineWrap, 28, true), new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout inlineRow = new LinearLayout(this);
        inlineRow.setOrientation(LinearLayout.HORIZONTAL);
        inlineRow.setGravity(Gravity.CENTER_VERTICAL);
        inlineRow.setPadding(dp(this, 14), dp(this, 4), dp(this, 8), dp(this, 4));
        inlineWrap.addView(inlineRow, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        SearchIconView sicon = new SearchIconView(this);
        sicon.iconColor = Color.rgb(0x63, 0x63, 0x66);
        inlineRow.addView(sicon, new LinearLayout.LayoutParams(dp(this, 20), dp(this, 20)));
        searchBox = new EditText(this);
        searchBox.setHint("搜索卡名 / 银行 / BIN…");
        searchBox.setTextSize(15);
        searchBox.setSingleLine(true);
        searchBox.setBackground(null);
        searchBox.setPadding(dp(this, 8), dp(this, 7), dp(this, 4), dp(this, 7));
        if (query != null && !query.isEmpty()) searchBox.setText(query);
        inlineRow.addView(searchBox, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        FrameLayout clearWrap = new FrameLayout(this);
        GradientDrawable cbg = new GradientDrawable();
        cbg.setShape(GradientDrawable.OVAL);
        cbg.setColor(Color.rgb(0xD1, 0xD1, 0xD6));
        clearWrap.setBackground(cbg);
        CloseIconView clearIcon = new CloseIconView(this);
        clearIcon.iconColor = Color.WHITE;
        clearIcon.lineDp = 1.4f;
        int cpad = dp(this, 8);
        clearIcon.setPadding(cpad, cpad, cpad, cpad);
        clearWrap.addView(clearIcon, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        clearWrap.setOnClickListener(v -> {
            haptic();
            if (searchBox != null) { searchBox.setText(""); searchBox.requestFocus(); }
        });
        inlineRow.addView(clearWrap, new LinearLayout.LayoutParams(dp(this, 28), dp(this, 28)));
        inlineClearBtn = clearWrap;
        searchBox.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence t, int a, int b, int c) {}
            public void onTextChanged(CharSequence t, int a, int b, int c) {
                if (!syncingSearchText) applySearchText(t.toString(), searchBox);
            }
            public void afterTextChanged(Editable t) {}
        });
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ilp.topMargin = dp(this, 12);
        // Insert directly under the title (index 1), matching mixed order: title -> search -> stats.
        col.addView(inlineWrap, Math.min(1, col.getChildCount()), ilp);
        homeSearchBar = inlineWrap;
        homeSearchBarShown = true;
        syncInlineClearBtn();

        // Q10 float search capsule (mixed #floatSearch): fixed top pill, shallow glass + slight blue,
        // thin magnifier, 28dp X circle; only shown mid-page via the search fab, list never moves for it.
        FrameLayout floatWrap = new FrameLayout(this);
        floatWrap.setBackground(glassFloatBg());
        if (Build.VERSION.SDK_INT >= 21) floatWrap.setElevation(dp(this, 14));
        floatWrap.addView(glassLayer(floatWrap, 28, true), new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout floatRow = new LinearLayout(this);
        floatRow.setOrientation(LinearLayout.HORIZONTAL);
        floatRow.setGravity(Gravity.CENTER_VERTICAL);
        floatRow.setPadding(dp(this, 14), dp(this, 4), dp(this, 8), dp(this, 4));
        floatWrap.addView(floatRow, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        SearchIconView ficon = new SearchIconView(this);
        ficon.iconColor = Color.rgb(0x63, 0x63, 0x66);
        floatRow.addView(ficon, new LinearLayout.LayoutParams(dp(this, 20), dp(this, 20)));
        floatSearchBox = new EditText(this);
        floatSearchBox.setHint("搜索卡名 / 银行 / BIN…");
        floatSearchBox.setTextSize(15);
        floatSearchBox.setSingleLine(true);
        floatSearchBox.setBackground(null);
        floatSearchBox.setPadding(dp(this, 8), dp(this, 7), dp(this, 4), dp(this, 7));
        floatRow.addView(floatSearchBox, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        FrameLayout closeWrap = new FrameLayout(this);
        GradientDrawable xbg = new GradientDrawable();
        xbg.setShape(GradientDrawable.OVAL);
        xbg.setColor(Color.argb(46, 120, 120, 128));
        closeWrap.setBackground(xbg);
        CloseIconView closeIcon = new CloseIconView(this);
        closeIcon.iconColor = Color.rgb(0x1C, 0x1C, 0x1E);
        int xpad = dp(this, 8);
        closeIcon.setPadding(xpad, xpad, xpad, xpad);
        closeWrap.addView(closeIcon, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        closeWrap.setOnClickListener(v -> { haptic(); closeFloatSearch(); });
        floatRow.addView(closeWrap, new LinearLayout.LayoutParams(dp(this, 28), dp(this, 28)));
        floatSearchBox.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence t, int a, int b, int c) {}
            public void onTextChanged(CharSequence t, int a, int b, int c) {
                if (!syncingSearchText) applySearchText(t.toString(), floatSearchBox);
            }
            public void afterTextChanged(Editable t) {}
        });
        FrameLayout.LayoutParams flp2 = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        flp2.gravity = Gravity.TOP;
        flp2.leftMargin = dp(this, 12);
        flp2.rightMargin = dp(this, 12);
        flp2.topMargin = statusBarH() + dp(this, 8);
        floatWrap.setVisibility(View.GONE);
        page.addView(floatWrap, flp2);
        floatSearchBar = floatWrap;
        floatSearchOpen = false;

        refreshHome();
        return page;
    }

    View heroTile(String v, String k) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setBackground(roundRect(Color.argb(38, 255, 255, 255), 12, this));
        t.setPadding(dp(this, 10), dp(this, 9), dp(this, 10), dp(this, 9));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = dp(this, 8);
        t.setLayoutParams(lp);
        t.addView(tv(this, v, 15, Color.WHITE, true));
        t.addView(tv(this, k, 10.5f, Color.argb(200, 255, 255, 255), false));
        return t;
    }

    List<Card> filteredHome() {
        List<Card> out = new ArrayList<>();
        String q = query.toLowerCase();
        for (Card c : Store.all) {
            String ct = (c.type == null || c.type.isEmpty()) ? "debit" : c.type;
            if (filterType != null && !filterType.equals(ct)) continue;
            if (filterOrg != null && !filterOrg.equals(c.org == null ? "" : c.org)) continue;
            if (filterStatus != null && !filterStatus.equals(c.status == null ? "" : c.status)) continue;
            if (filterBank != null && !filterBank.equals(c.bank == null ? "" : c.bank)) continue;
            boolean featOk = true;
            for (String f : filterFeats) if (!featMatch(c, f)) { featOk = false; break; }
            if (!featOk) continue;
            if (q.isEmpty()) { out.add(c); continue; }
            String bin = c.spec("BIN");
            if (c.name.toLowerCase().contains(q) || c.bank.toLowerCase().contains(q)
                || bin.contains(q) || (c.keywords != null && c.keywords.toLowerCase().contains(q))) out.add(c);
        }
        return out;
    }

    // Q21 ② 首页渲染签名：把决定网格内容的全部输入拼成一把钥匙——切页回来/关弹层这类「什么都没变」的 refresh 直接跳过整表重搭。
    String homeSig() {
        StringBuilder sb = new StringBuilder();
        sb.append(query).append('|').append(filterType).append('|').append(filterOrg).append('|')
          .append(filterStatus).append('|').append(filterBank).append('|').append(filterFeats).append('|')
          .append(sortMode).append('|').append(cols).append('|').append(groupBank).append('|')
          .append(new java.util.TreeSet<>(bankOpen)).append('|').append(Store.dataVersion).append('|')
          .append(Store.all.size()).append('|').append(mine.size()).append(':');
        for (String id : new java.util.TreeSet<>(mine)) sb.append(id).append(',');
        return sb.toString();
    }

    void refreshHome() {
        if (homeList == null) return;
        // Q21 ②：签名未变的重复 refresh（切页回来、关详情、关筛选）不再把 213 张瓷砖连图带字重搭一遍——这是切页发慢的主因。
        String sig = homeSig();
        if (sig.equals(homeRenderSig) && homeList.getChildCount() > 0) {
            if (homeCount != null) homeCount.setText("共 " + filteredHome().size() + " 张");
            return;
        }
        // P-keepscroll：重渲染前记下滚动位置——列表一清空高度骤降，系统会把 scrollY 钳到顶，重建后按原位恢复
        final int keepY = savedPageScrollY("home", homeScroll);
        List<Card> list = filteredHome();
        applySort(list);
        if (homeCount != null) homeCount.setText("共 " + list.size() + " 张");
        if (filterBtn != null) {
            int n = activeFilterCount();
            filterBtn.setText(n == 0 ? "筛选" : "筛选 · " + n);
        }
        renderActiveFilters();
        renderHomeList(list);
        homeRenderSig = sig;
        updateFilterFabBadge();
        if (keepY > 0 && homeScroll != null) homeScroll.post(() -> homeScroll.scrollTo(0, keepY));
    }

    void renderHomeList(List<Card> list) {
        final int gen = ++homeRenderGen; // 作废上一轮尚未跑完的分帧续帧
        homeList.removeAllViews();
        if (list.isEmpty()) {
            homeList.addView(emptyState("没有符合条件的卡\n换个筛选条件或清空筛选试试"));
            return;
        }
        if (query.isEmpty() && activeFilterCount() == 0) homeList.addView(wizardBanner());
        if (!groupBank) {
            addCardRowsChunked(homeList, list, gen);
            return;
        }
        // 按银行折叠：组按卡数降序、同数按银行中文序（同混合版 renderGrid 分组）
        Map<String, List<Card>> groups = new java.util.LinkedHashMap<>();
        for (Card c : list) {
            String b = (c.bank == null || c.bank.isEmpty()) ? "其他" : c.bank;
            if (!groups.containsKey(b)) groups.put(b, new ArrayList<>());
            groups.get(b).add(c);
        }
        List<Map.Entry<String, List<Card>>> order = new ArrayList<>(groups.entrySet());
        final java.text.Collator zh = java.text.Collator.getInstance(java.util.Locale.CHINA);
        order.sort((x, y) -> { int r = Integer.compare(y.getValue().size(), x.getValue().size()); return r != 0 ? r : zh.compare(x.getKey(), y.getKey()); });
        int maxN = 1;
        for (Map.Entry<String, List<Card>> e : order) maxN = Math.max(maxN, e.getValue().size());
        boolean forceOpen = !query.isEmpty() || filterOrg != null || filterStatus != null || filterType != null || !filterFeats.isEmpty();
        for (Map.Entry<String, List<Card>> e : order) {
            final String bank = e.getKey();
            List<Card> cs = e.getValue();
            boolean open = forceOpen || bankOpen.contains(bank);
            homeList.addView(bankHead(bank, cs, open, maxN, () -> {
                if (bankOpen.contains(bank)) bankOpen.remove(bank); else bankOpen.add(bank);
                persistViewPrefs();
                refreshHome();
            }));
            if (open) addCardRowsChunked(homeList, cs, gen);
        }
    }

    // Q21 ② 分帧渲染：首帧只搭首屏（约 4 行），其余每帧续搭 4 行——213 张不再一口气堵死主线程，
    // 切页/筛选后的第一眼立刻出现，列表在手指碰到前就已补齐；代次令牌保证快速连改筛选时旧续帧不会把过期卡塞回来。
    // 分组模式下续帧按记录的插入位回插本组行尾（下一组标题之前），不许续帧一律 append 到全表末尾把组冲散。
    void addCardRowsChunked(final LinearLayout container, final List<Card> list, final int gen) {
        final int rows = (list.size() + cols - 1) / cols;
        final int[] insertAt = { container.getChildCount() };
        final int firstRows = Math.min(rows, 4);
        for (int r = 0; r < firstRows; r++) addCardRowAt(container, list, r, insertAt);
        if (rows <= firstRows) return;
        final int[] next = { firstRows };
        final Runnable[] step = new Runnable[1];
        step[0] = () -> {
            if (gen != homeRenderGen || container != homeList) return; // 已有更新一轮渲染接管
            int end = Math.min(rows, next[0] + 4);
            for (int r = next[0]; r < end; r++) addCardRowAt(container, list, r, insertAt);
            next[0] = end;
            if (next[0] < rows) container.post(step[0]);
        };
        container.post(step[0]);
    }

    void addCardRowAt(LinearLayout container, List<Card> list, int rowIdx, int[] insertAt) {
        int i = rowIdx * cols;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(this, 10);
        row.setLayoutParams(rlp);
        int at = Math.max(0, Math.min(insertAt[0], container.getChildCount()));
        container.addView(row, at);
        insertAt[0] = at + 1;
        for (int j = 0; j < cols; j++) {
            if (i + j < list.size()) {
                final Card c = list.get(i + j);
                View tile = cardTile(c, row);
                LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
                if (j > 0) tlp.leftMargin = dp(this, 10);
                tile.setLayoutParams(tlp);
                tile.setOnClickListener(v -> openDetail(c));
                row.addView(tile);
            } else {
                View spacer = new View(this);
                LinearLayout.LayoutParams slp2 = new LinearLayout.LayoutParams(0, 1, 1f);
                if (j > 0) slp2.leftMargin = dp(this, 10);
                spacer.setLayoutParams(slp2);
                row.addView(spacer);
            }
        }
    }

    View bankHead(final String bank, List<Card> cs, boolean open, int maxN, final Runnable onToggle) {
        int nd = 0;
        for (Card c : cs) if (!c.isCredit()) nd++;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(rippleBg(Color.WHITE, 14));
        box.setClipToOutline(true);
        box.setPadding(dp(this, 12), dp(this, 10), dp(this, 12), dp(this, 10));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(this, 10);
        box.setLayoutParams(blp);
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        box.addView(top);
        TextView letter = tv(this, bank.isEmpty() ? "卡" : bank.substring(0, 1), 16, Color.rgb(0x0A, 0x5C, 0xD6), true);
        letter.setGravity(Gravity.CENTER);
        letter.setBackground(roundRect(Color.rgb(0xE8, 0xF1, 0xFD), 10, this));
        top.addView(letter, new LinearLayout.LayoutParams(dp(this, 38), dp(this, 38)));
        LinearLayout tx = new LinearLayout(this);
        tx.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams txlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        txlp.leftMargin = dp(this, 10);
        top.addView(tx, txlp);
        tx.addView(tv(this, bank, 14.5f, open ? Color.rgb(0x0A, 0x5C, 0xD6) : Color.rgb(0x1C, 0x1C, 0x1E), true));
        tx.addView(tv(this, "借记 " + nd + " · 信用 " + (cs.size() - nd), 11, Color.rgb(0x8E, 0x8E, 0x93), false));
        TextView cnt = tv(this, cs.size() + " 张", 11, Color.rgb(0x0A, 0x5C, 0xD6), true);
        cnt.setBackground(roundRect(Color.rgb(0xE8, 0xF1, 0xFD), 999, this));
        cnt.setPadding(dp(this, 8), dp(this, 3), dp(this, 8), dp(this, 3));
        top.addView(cnt);
        TextView arrow = tv(this, open ? " ▾" : " ▸", 14, Color.rgb(0x8E, 0x8E, 0x93), false);
        top.addView(arrow);
        // 占比条：按本轮最大组归一（同混合版 bbar）
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackground(roundRect(Color.rgb(0xE9, 0xEE, 0xF5), 999, this));
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(this, 4));
        barLp.topMargin = dp(this, 8);
        bar.setLayoutParams(barLp);
        View fill = new View(this);
        fill.setBackground(roundRect(Color.rgb(0x0A, 0x5C, 0xD6), 999, this));
        bar.addView(fill, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, (float) cs.size()));
        View rest = new View(this);
        bar.addView(rest, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, (float) Math.max(0, maxN - cs.size())));
        box.addView(bar);
        box.setOnClickListener(v -> onToggle.run());
        return box;
    }

    // 已选标签栏：顺序与混合版 renderActiveFilters 一致（银行/组织/状态/特点/类型），点标签删除该项
    void renderActiveFilters() {
        if (activeFilterBar == null) return;
        activeFilterBar.removeAllViews();
        if (filterBank != null) activeFilterBar.addView(afPill(filterBank, () -> { filterBank = null; refreshHome(); }));
        if (filterOrg != null) activeFilterBar.addView(afPill(orgLabel(filterOrg), () -> { filterOrg = null; refreshHome(); }));
        if (filterStatus != null) activeFilterBar.addView(afPill(filterStatus, () -> { filterStatus = null; refreshHome(); }));
        for (final String f : new ArrayList<>(filterFeats))
            activeFilterBar.addView(afPill(featLabel(f), () -> { filterFeats.remove(f); refreshHome(); }));
        if (filterType != null) activeFilterBar.addView(afPill("credit".equals(filterType) ? "信用卡" : "借记卡", () -> { filterType = null; refreshHome(); }));
        if (sortMode != null) activeFilterBar.addView(afPill(sortLabel(sortMode), () -> { sortMode = null; persistViewPrefs(); refreshHome(); }));
        View wrap = (View) activeFilterBar.getParent();
        if (wrap != null) wrap.setVisibility(activeFilterBar.getChildCount() == 0 ? View.GONE : View.VISIBLE);
    }

    TextView afPill(String label, final Runnable onRemove) {
        TextView t = tv(this, label + "  ✕", 11.5f, Color.rgb(0x0A, 0x5C, 0xD6), true);
        t.setBackground(roundRect(Color.rgb(0xE8, 0xF1, 0xFD), 999, this));
        t.setPadding(dp(this, 10), dp(this, 5), dp(this, 10), dp(this, 5));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(this, 7);
        t.setLayoutParams(lp);
        t.setOnClickListener(v -> onRemove.run());
        return t;
    }

    // ---------- 筛选面板（Phase 2a-1；P2 改悬浮卡窗：左右/底部留空、四角全圆+描边+投影、开合动画） ----------
    void openFilterSheet() {
        closeFilterSheetNow();
        // P2e/P2c：右下双钮退场，不与筛选窗叠压
        if (searchFab != null && searchFab.getParent() != null)
            ((ViewGroup) searchFab.getParent()).removeView(searchFab);
        searchFab = null;
        if (filterFab != null && filterFab.getParent() != null)
            ((ViewGroup) filterFab.getParent()).removeView(filterFab);
        filterFab = null;
        filterFabBadge = null;
        final FrameLayout sheet = new FrameLayout(this);
        sheet.setBackgroundColor(Color.argb(38, 18, 22, 36)); // 轻遮罩（混合版 .dlg-backdrop.light）
        sheet.setOnClickListener(v -> closeFilterSheet());
        // 窗框：真正浮起的卡片——固定不滚，四角完整圆角+描边，滚动只在窗内
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable cg = new GradientDrawable();
        cg.setColor(Color.argb(192, 255, 255, 255)); // Q11：半透染色盖在冻结模糊层上
        cg.setCornerRadius(dp(this, 24));
        cg.setStroke(dp(this, 1), Color.argb(18, 20, 30, 60));
        card.setBackground(cg);
        if (Build.VERSION.SDK_INT >= 21) {
            card.setElevation(dp(this, 24));
            card.setClipToOutline(true);
        }
        card.setOnClickListener(v -> {});
        // 头部固定（标题+清空/完成），不随内容滚动
        LinearLayout chead = new LinearLayout(this);
        chead.setOrientation(LinearLayout.HORIZONTAL);
        chead.setGravity(Gravity.CENTER_VERTICAL);
        chead.setPadding(dp(this, 16), dp(this, 12), dp(this, 10), dp(this, 4));
        TextView cttl = tv(this, "\u7b5b\u9009", 16, Color.rgb(0x1C, 0x1C, 0x1E), true);
        chead.addView(cttl, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView clearT = tv(this, "\u6e05\u7a7a", 13, Color.rgb(0x8E, 0x8E, 0x93), false);
        clearT.setPadding(dp(this, 8), dp(this, 6), dp(this, 8), dp(this, 6));
        clearT.setOnClickListener(v -> {
            haptic();
            final String sType = filterType, sOrg = filterOrg, sStatus = filterStatus, sBank = filterBank, sSort = sortMode;
            final boolean sGroup = groupBank;
            final java.util.Set<String> sFeats = new java.util.LinkedHashSet<>(filterFeats);
            filterType = null; filterOrg = null; filterStatus = null;
            filterFeats.clear(); filterBank = null;
            sortMode = null; groupBank = false; persistViewPrefs();
            rebuildFilterPanel(filterPanelRef); refreshHome();
            showFloatToast("已清空筛选", "撤销", () -> {
                filterType = sType; filterOrg = sOrg; filterStatus = sStatus; filterBank = sBank; sortMode = sSort;
                groupBank = sGroup;
                filterFeats.clear(); filterFeats.addAll(sFeats);
                persistViewPrefs();
                if (filterPanelRef != null) rebuildFilterPanel(filterPanelRef);
                refreshHome();
                showFloatToast("已恢复筛选");
            });
        });
        chead.addView(clearT);
        TextView doneT = tv(this, "\u5b8c\u6210", 13, Color.rgb(0x0A, 0x5C, 0xD6), true);
        doneT.setPadding(dp(this, 8), dp(this, 6), dp(this, 10), dp(this, 6));
        doneT.setOnClickListener(v -> { haptic(); closeFilterSheet(); });
        chead.addView(doneT);
        card.addView(chead);
        ScrollView sc = new ScrollView(this);
        thinScrollbar(sc);
        sc.setBackgroundColor(Color.TRANSPARENT);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(this, 14), dp(this, 2), dp(this, 14), dp(this, 14));
        sc.addView(panel, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(sc, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        filterScroll = sc;
        filterPanelRef = panel;
        rebuildFilterPanel(panel);
        int sw = getResources().getDisplayMetrics().widthPixels;
        int cardW = Math.min(sw - dp(this, 28), dp(this, 368));
        int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.60);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(cardW, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.gravity = Gravity.END | Gravity.BOTTOM;
        clp.rightMargin = dp(this, 14);
        clp.bottomMargin = dp(this, 104) + navBarH(); // 浮在 dock 之上（混合版 bottom:104px）；Q26 再加导航栏避让
        card.measure(View.MeasureSpec.makeMeasureSpec(cardW, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST));
        clp.height = card.getMeasuredHeight();
        // Q11：窗下垫冻结模糊快照（升起前抓的底层画面），与窗同位同尺寸
        FrameLayout.LayoutParams fglp = new FrameLayout.LayoutParams(clp.width, clp.height);
        fglp.gravity = clp.gravity; fglp.rightMargin = clp.rightMargin; fglp.bottomMargin = clp.bottomMargin;
        sheet.addView(glassLayer(card, 24, false), fglp);
        sheet.addView(card, clp);
        content.addView(sheet);
        filterSheet = sheet;
        // 开场：淡入+放大+上浮，减速曲线（P4-fix 统一手感方向，220–320ms 档）
        sheet.setAlpha(0f);
        sheet.animate().alpha(1f).setDuration(200)
            .setInterpolator(ANIM_ENTER).start();
        card.setAlpha(0f);
        card.setScaleX(0.94f); card.setScaleY(0.94f);
        card.setTranslationY(dp(this, 14));
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
            .setDuration(260).setInterpolator(ANIM_ENTER).start();
    }

    LinearLayout filterPanelRef = null;

    void closeFilterSheet() {
        final View sheet = filterSheet;
        if (sheet == null) return;
        filterSheet = null;
        if (sheet.getParent() == null) { syncSearchFab(); return; }
        View card = sheet instanceof ViewGroup && ((ViewGroup) sheet).getChildCount() > 0
            ? ((ViewGroup) sheet).getChildAt(((ViewGroup) sheet).getChildCount() - 1) : null; // Q11：玻璃层在窗下，窗体是最后一层
        if (card != null) {
            card.animate().alpha(0f).scaleX(0.96f).scaleY(0.96f).translationY(dp(this, 10))
                .setDuration(180).setInterpolator(ANIM_ENTER)
                .withEndAction(() -> { closeFilterSheetNow(sheet); syncSearchFab(); }).start();
            sheet.animate().alpha(0f).setDuration(180).start();
        } else {
            closeFilterSheetNow(sheet);
            syncSearchFab();
        }
    }

    void closeFilterSheetNow() { closeFilterSheetNow(filterSheet); }

    void closeFilterSheetNow(View sheet) {
        if (sheet != null && sheet.getParent() != null)
            ((ViewGroup) sheet.getParent()).removeView(sheet);
        if (sheet == filterSheet) filterSheet = null;
        if (sheet != null) { filterScroll = null; filterPanelRef = null; }
    }

    void rebuildFilterPanel(final LinearLayout panel) {
        final int keepY = filterScroll != null ? filterScroll.getScrollY() : 0;
        panel.removeAllViews();

        panel.addView(filterSectionTitle("\u5361\u7247\u7c7b\u578b"));
        List<View> typeChips = new ArrayList<>();
        typeChips.add(filterChip("\u501f\u8bb0\u5361", "debit".equals(filterType), () -> { filterType = "debit".equals(filterType) ? null : "debit"; rebuildFilterPanel(panel); refreshHome(); }));
        typeChips.add(filterChip("\u4fe1\u7528\u5361", "credit".equals(filterType), () -> { filterType = "credit".equals(filterType) ? null : "credit"; rebuildFilterPanel(panel); refreshHome(); }));
        addChipFlow(panel, typeChips);

        panel.addView(filterSectionTitle("\u5361\u7ec4\u7ec7"));
        String[][] orgs = {{"visa", "VISA"}, {"mastercard", "\u4e07\u4e8b\u8fbe"}, {"mastercard-nucc", "\u4e07\u4e8b\u8fbe-\u7f51\u8054"}, {"amex-cn", "\u8fd0\u901a-\u4eba\u6c11\u5e01"}, {"unionpay", "\u94f6\u8054"}, {"jcb", "JCB"}};
        List<View> orgChips = new ArrayList<>();
        for (final String[] o : orgs) {
            orgChips.add(filterChip(o[1], o[0].equals(filterOrg), () -> {
                filterOrg = o[0].equals(filterOrg) ? null : o[0];
                rebuildFilterPanel(panel); refreshHome();
            }));
        }
        addChipFlow(panel, orgChips);

        panel.addView(filterSectionTitle("\u72b6\u6001"));
        List<View> stChips = new ArrayList<>();
        stChips.add(filterChip("\u5728\u53d1", "\u5728\u53d1".equals(filterStatus), () -> { filterStatus = "\u5728\u53d1".equals(filterStatus) ? null : "\u5728\u53d1"; rebuildFilterPanel(panel); refreshHome(); }));
        stChips.add(filterChip("\u5df2\u505c\u53d1", "\u5df2\u505c\u53d1".equals(filterStatus), () -> { filterStatus = "\u5df2\u505c\u53d1".equals(filterStatus) ? null : "\u5df2\u505c\u53d1"; rebuildFilterPanel(panel); refreshHome(); }));
        addChipFlow(panel, stChips);

        panel.addView(filterSectionTitle("\u7279\u70b9\uff08\u53ef\u591a\u9009\uff0c\u987b\u540c\u65f6\u6ee1\u8db3\uff09"));
        List<View> featChips = new ArrayList<>();
        for (final String[] f : FEATS) {
            featChips.add(filterChip(f[1], filterFeats.contains(f[0]), () -> {
                if (filterFeats.contains(f[0])) {
                    filterFeats.remove(f[0]);
                } else {
                    java.util.Set<String> test = new java.util.LinkedHashSet<>(filterFeats);
                    test.add(f[0]);
                    boolean any = false;
                    for (Card c : Store.all) {
                        boolean ok = true;
                        for (String k : test) if (!featMatch(c, k)) { ok = false; break; }
                        if (ok) { any = true; break; }
                    }
                    if (!any) {
                        StringBuilder names = new StringBuilder();
                        for (String k : test) { if (names.length() > 0) names.append("\u300d+\u300c"); names.append(featLabel(k)); }
                        showFloatToast("\u300c" + names + "\u300d\u6ca1\u6709\u5361\u540c\u65f6\u6ee1\u8db3\uff0c\u4e0d\u80fd\u4e00\u8d77\u9009");
                        return;
                    }
                    filterFeats.add(f[0]);
                }
                rebuildFilterPanel(panel); refreshHome();
            }));
        }
        addChipFlow(panel, featChips);

        panel.addView(filterSectionTitle("\u53d1\u5361\u884c"));
        addBankGrid(panel, distinctBanks());

        panel.addView(filterSectionTitle("\u6392\u5e8f"));
        String[][] sorts = {{"score-desc", "\u8bc4\u5206\u7531\u9ad8\u5230\u4f4e"}, {"score-asc", "\u8bc4\u5206\u7531\u4f4e\u5230\u9ad8"}, {"name", "\u540d\u79f0"}, {"bank", "\u94f6\u884c"}};
        List<View> sortChips = new ArrayList<>();
        for (final String[] so : sorts) {
            sortChips.add(filterChip(so[1], so[0].equals(sortMode), () -> {
                sortMode = so[0].equals(sortMode) ? null : so[0];
                persistViewPrefs();
                rebuildFilterPanel(panel); refreshHome();
            }));
        }
        addChipFlow(panel, sortChips);

        panel.addView(filterSectionTitle("\u663e\u793a\u65b9\u5f0f"));
        List<View> dispChips = new ArrayList<>();
        dispChips.add(filterChip("\u663e\u793a\u5168\u90e8", !groupBank, () -> {
            groupBank = false; persistViewPrefs();
            rebuildFilterPanel(panel); refreshHome();
        }));
        dispChips.add(filterChip("\u6309\u94f6\u884c\u6298\u53e3", groupBank, () -> {
            groupBank = true; persistViewPrefs();
            rebuildFilterPanel(panel); refreshHome();
        }));
        addChipFlow(panel, dispChips);

        panel.addView(filterSectionTitle("\u5217\u6570"));
        String[][] colOpts = {{"1", "\u5355\u5217"}, {"2", "\u53cc\u5217"}, {"3", "\u4e09\u5217"}};
        List<View> colChips = new ArrayList<>();
        for (final String[] co : colOpts) {
            final int nCols = Integer.parseInt(co[0]);
            colChips.add(filterChip(co[1], cols == nCols, () -> {
                cols = nCols; persistViewPrefs();
                rebuildFilterPanel(panel); refreshHome();
            }));
        }
        addChipFlow(panel, colChips);

        if (filterScroll != null) filterScroll.post(() -> filterScroll.scrollTo(0, keepY));
    }

    // P2e chips：紧凑胶囊流式排列（多枚一行），选中蓝渐变+勾；发卡行三列等宽（混合版 #chipsBank 口径）
    TextView filterChip(String label, boolean on, final Runnable act) {
        TextView t = tv(this, (on ? "\u2713 " : "") + label, 13, on ? Color.WHITE : Color.rgb(0x1C, 0x1C, 0x1E), on);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setGravity(Gravity.CENTER);
        if (on) {
            GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.rgb(0x0A, 0x84, 0xFF), Color.rgb(0x00, 0x66, 0xE6)});
            g.setCornerRadius(dp(this, 999));
            t.setBackground(g);
            if (Build.VERSION.SDK_INT >= 21) t.setElevation(dp(this, 2));
        } else {
            GradientDrawable g = new GradientDrawable();
            g.setColor(Color.rgb(0xF2, 0xF3, 0xF7));
            g.setCornerRadius(dp(this, 999));
            g.setStroke(dp(this, 1), Color.argb(13, 20, 30, 60));
            t.setBackground(g);
        }
        t.setPadding(dp(this, 11), dp(this, 6), dp(this, 11), dp(this, 6));
        t.setOnClickListener(v -> { haptic(); act.run(); });
        t.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) pressBounce(v, true);
            else if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL)
                pressBounce(v, false);
            return false;
        });
        return t;
    }

    void addChipFlow(LinearLayout panel, List<View> chips) {
        int avail = Math.min(getResources().getDisplayMetrics().widthPixels - dp(this, 28), dp(this, 368)) - dp(this, 28);
        Paint mp = new Paint();
        mp.setTextSize(13f * uiScale * getResources().getDisplayMetrics().scaledDensity);
        LinearLayout row = null;
        int rowW = 0;
        for (View chip : chips) {
            String txt = ((TextView) chip).getText().toString();
            int w = (int) mp.measureText(txt) + dp(this, 24);
            if (row == null || (rowW > 0 && rowW + dp(this, 6) + w > avail)) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rlp.topMargin = dp(this, 6);
                row.setLayoutParams(rlp);
                panel.addView(row);
                rowW = 0;
            }
            if (rowW > 0) rowW += dp(this, 6);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (rowW > 0) clp.leftMargin = dp(this, 6);
            chip.setLayoutParams(clp);
            row.addView(chip);
            rowW += w;
        }
    }

    void addBankGrid(LinearLayout panel, List<String> banks) {
        for (int i = 0; i < banks.size(); i += 3) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.topMargin = dp(this, 6);
            row.setLayoutParams(rlp);
            for (int j = 0; j < 3; j++) {
                int idx = i + j;
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                if (j > 0) clp.leftMargin = dp(this, 6);
                if (idx < banks.size()) {
                    final String bankName = banks.get(idx);
                    View chip = filterChip(bankName, bankName.equals(filterBank), () -> {
                        filterBank = bankName.equals(filterBank) ? null : bankName;
                        rebuildFilterPanel(panel); refreshHome();
                    });
                    row.addView(chip, clp);
                } else {
                    row.addView(new View(this), clp);
                }
            }
            panel.addView(row);
        }
    }

    TextView filterSectionTitle(String s) {
        TextView t = tv(this, s, 12, Color.rgb(0x8E, 0x8E, 0x93), true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(this, 10);
        t.setLayoutParams(lp);
        return t;
    }

    View wizardBanner() {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.HORIZONTAL);
        b.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
            new int[]{Color.rgb(0x0B, 0x3D, 0x91), Color.rgb(0x0A, 0x6E, 0xD6), Color.rgb(0x00, 0xA3, 0xC8)});
        bg.setCornerRadius(dp(this, 16));
        b.setBackground(bg);
        b.setPadding(dp(this, 14), dp(this, 12), dp(this, 14), dp(this, 12));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(this, 2);
        b.setLayoutParams(blp);
        LinearLayout tx = new LinearLayout(this);
        tx.setOrientation(LinearLayout.VERTICAL);
        b.addView(tx, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        tx.addView(tv(this, "情景选卡", 15, Color.WHITE, true));
        tx.addView(tv(this, "按场景答几题，从在发卡里挑适合你的", 11.5f, Color.argb(215, 255, 255, 255), false));
        b.addView(tv(this, "开始 ›", 13, Color.WHITE, true));
        b.setOnClickListener(v -> { haptic(); openWizard(); });
        return b;
    }

    // ---------- 情景选卡（Phase 3a，对照 app.js WIZ_SCENARIOS/wizQs/wizScore/renderWiz 完整移植） ----------
    static class WizQ {
        String k, q; String[][] opts;
        WizQ(String k, String q, String[][] opts) { this.k = k; this.q = q; this.opts = opts; }
    }
    static class WizSc {
        String id, name, desc; WizQ[] qs;
        WizSc(String id, String name, String desc, WizQ[] qs) { this.id = id; this.name = name; this.desc = desc; this.qs = qs; }
    }

    static final WizSc[] WIZ_SCENARIOS = {
        new WizSc("study", "出国留学", "交学费、生活费、境外刷卡", new WizQ[]{
            new WizQ("region", "去哪个国家 / 地区留学？", new String[][]{{"uk", "英国"}, {"us", "美国 / 加拿大"}, {"eu", "欧洲"}, {"jp", "日本"}, {"au", "澳洲 / 新西兰"}, {"other", "其他地区"}}),
            new WizQ("use", "主要拿这张卡干什么？", new String[][]{{"tuition", "交学费、房租等大额支出"}, {"daily", "日常吃饭购物"}, {"both", "都用，它是主力卡"}}),
        }),
        new WizSc("travel", "出境旅游", "境外刷卡、取现、安全", new WizQ[]{
            new WizQ("region", "主要去哪儿玩？", new String[][]{{"jp", "日本"}, {"eu", "欧洲"}, {"us", "美洲"}, {"sea", "东南亚"}, {"hk", "港澳台"}, {"other", "其他"}}),
            new WizQ("care", "最在意哪一点？", new String[][]{{"fee", "刷卡别被收货币转换费"}, {"atm", "境外取现方便便宜"}, {"safe", "用卡安全、防盗刷"}}),
        }),
        new WizSc("shop", "海淘网购", "外网下单、绑卡支付", new WizQ[]{
            new WizQ("region", "常买哪个地区的店？", new String[][]{{"us", "美国"}, {"jp", "日本"}, {"eu", "欧洲"}, {"other", "哪儿的都有"}}),
            new WizQ("pay", "习惯怎么付？", new String[][]{{"direct", "直接刷卡付"}, {"wallet", "绑 Apple Pay / 钱包付"}}),
        }),
        new WizSc("daily", "日常使用", "学生、上班族的主力卡", new WizQ[]{
            new WizQ("who", "你目前是？", new String[][]{{"student", "学生"}, {"worker", "上班族"}}),
            new WizQ("use", "主要用途是？", new String[][]{{"online", "网购、外卖、线上支付"}, {"offline", "线下吃饭购物"}, {"save", "能省则省，免年费优先"}}),
        }),
    };
    static final WizQ WIZ_TIER_Q = new WizQ("tier", "想要什么档次的卡？", new String[][]{{"any", "都行，合适最重要"}, {"basic", "入门就行，好办好用"}, {"mid", "有点档次的，金卡 / 白金级"}, {"high", "高端有实力的，白金、钻石、无限级"}});
    static final WizQ WIZ_TYPE_Q = new WizQ("type", "想要借记卡还是信用卡？", new String[][]{{"debit", "借记卡"}, {"credit", "信用卡"}, {"any", "都行，好用优先"}});

    static String wizQShort(String k) {
        switch (k == null ? "" : k) {
            case "region": return "地区";
            case "use": return "用途";
            case "care": return "在意";
            case "pay": return "支付";
            case "who": return "身份";
            case "tier": return "档次";
            case "type": return "卡种";
            default: return "已选";
        }
    }

    static String wizRegionName(String r) {
        switch (r == null ? "" : r) {
            case "jp": return "日本";
            case "eu": return "欧洲";
            case "uk": return "英国";
            case "us": return "美洲";
            case "au": return "澳新";
            case "sea": return "东南亚";
            case "hk": return "港澳台";
            default: return "当地";
        }
    }

    static String wizCur(String r) {
        switch (r == null ? "" : r) {
            case "uk": return "英镑";
            case "us": return "美元";
            case "eu": return "欧元";
            case "jp": return "日元";
            case "au": return "澳大利亚元";
            case "hk": return "港币";
            default: return null;
        }
    }

    static Map<String, Double> orgWMap(Object... kv) {
        Map<String, Double> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], (Double) kv[i + 1]);
        return m;
    }

    static final Map<String, Map<String, Double>> WIZ_ORG_REGION = new HashMap<>();
    static {
        WIZ_ORG_REGION.put("jp", orgWMap("unionpay", 2.0, "jcb", 2.0, "visa", 1.0));
        WIZ_ORG_REGION.put("eu", orgWMap("mastercard", 2.0, "mastercard-nucc", 1.2, "visa", 1.5));
        WIZ_ORG_REGION.put("uk", orgWMap("mastercard", 1.5, "visa", 1.5));
        WIZ_ORG_REGION.put("us", orgWMap("visa", 2.0, "mastercard", 1.5));
        WIZ_ORG_REGION.put("au", orgWMap("visa", 1.5, "mastercard", 1.5));
        WIZ_ORG_REGION.put("sea", orgWMap("unionpay", 2.0, "visa", 1.0));
        WIZ_ORG_REGION.put("hk", orgWMap("unionpay", 2.5));
        WIZ_ORG_REGION.put("other", orgWMap("visa", 1.0, "mastercard", 1.0));
    }

    // 按卡名粗分档次（同 app.js cardTier）：3 钻石/无限，2 白金/世界，1 金卡，0 普卡
    static final java.util.regex.Pattern TIER3 = java.util.regex.Pattern.compile("无限|世界之极|钻石|黑金|百夫长|私人银行|私行");
    static final java.util.regex.Pattern TIER2 = java.util.regex.Pattern.compile("白金|世界|签名|Signature|钛金|御玺|尊尚|卓越");
    static final java.util.regex.Pattern TIER1 = java.util.regex.Pattern.compile("金卡|精英|金葵花");
    static int cardTier(Card c) {
        String n = c.name == null ? "" : c.name;
        if (TIER3.matcher(n).find()) return 3;
        if (TIER2.matcher(n).find()) return 2;
        if (TIER1.matcher(n).find()) return 1;
        return 0;
    }

    static class WizRW { double w; String l; WizRW(double w, String l) { this.w = w; this.l = l; } }
    static class WizResult { Card c; double s; List<String> reasons; }

    static void wizAdd(List<WizRW> R, double[] sb, double w, String label) {
        sb[0] += w;
        if (w >= 1 && label != null && !label.isEmpty()) R.add(new WizRW(w, label));
    }

    // 同 app.js wizScore：停发卡排除（返回 null）；理由只收权重 >= 1 的，最多 3 条
    static WizResult wizScore(Card c, String scId, Map<String, String> a) {
        if ("已停发".equals(c.status)) return null;
        double[] sb = {c.hasScore ? c.score : 5};
        if (c.spec("发行情况").contains("仅限")) sb[0] -= 4; // 资格受限的卡别霸榜，结果行会标明
        List<WizRW> R = new ArrayList<>();
        String region = a.get("region");
        Map<String, Double> rm = region == null ? null : WIZ_ORG_REGION.get(region);
        double orgW = (rm != null && c.org != null && rm.containsKey(c.org)) ? rm.get(c.org) : 0;
        String orgWhy = orgW != 0 ? orgLabel(c.org) + "在" + wizRegionName(region) + "更通用" : "";
        String cur = wizCur(region);
        boolean curHit = cur != null && c.spec("币种支持").contains(cur);

        if ("study".equals(scId)) {
            if (c.name != null && c.name.contains("留学")) wizAdd(R, sb, 3, "留学专属卡");
            if (featMatch(c, "noftf")) wizAdd(R, sb, "daily".equals(a.get("use")) ? 2.5 : 3, "无货币转换费");
            if (featMatch(c, "online")) wizAdd(R, sb, 1.5, "可网付");
            if (featMatch(c, "3ds")) wizAdd(R, sb, 1.5, "支持 3DS");
            if (featMatch(c, "autofx")) wizAdd(R, sb, 1, "自动购汇");
            if (orgW != 0) wizAdd(R, sb, orgW, orgWhy);
            if (curHit) wizAdd(R, sb, 2, "支持" + cur);
            if ("无".equals(c.spec("年费"))) wizAdd(R, sb, 1, "免年费");
        } else if ("travel".equals(scId)) {
            String care = a.get("care");
            if (featMatch(c, "noftf")) wizAdd(R, sb, "fee".equals(care) ? 3.5 : 2.5, "无货币转换费");
            String atm = c.spec("境外ATM取现手续费").trim();
            if (atm.startsWith("免发卡行")) wizAdd(R, sb, "atm".equals(care) ? 2.5 : 1, "境外取现免发卡行手续费");
            else if (java.util.regex.Pattern.compile("前\\d+笔免费").matcher(atm).find()) wizAdd(R, sb, "atm".equals(care) ? 2.5 : 1, "境外取现前几笔免费");
            if (featMatch(c, "autofx")) wizAdd(R, sb, "atm".equals(care) ? 1.5 : 0.5, "自动购汇");
            if (featMatch(c, "3ds")) wizAdd(R, sb, "safe".equals(care) ? 3 : 1, "支持 3DS");
            if (featMatch(c, "online")) wizAdd(R, sb, 1, "可网付");
            if (orgW != 0) wizAdd(R, sb, orgW, orgWhy);
            if (curHit) wizAdd(R, sb, 1.5, "支持" + cur);
        } else if ("shop".equals(scId)) {
            if (featMatch(c, "online")) wizAdd(R, sb, 3, "可网付");
            if (featMatch(c, "3ds")) wizAdd(R, sb, 3, "支持 3DS");
            if (featMatch(c, "noftf")) wizAdd(R, sb, 2, "无货币转换费");
            if ("wallet".equals(a.get("pay")) && featMatch(c, "applepay")) wizAdd(R, sb, 3, "支持 Apple Pay");
            if (orgW != 0) wizAdd(R, sb, orgW, orgWhy);
        } else {
            String use = a.get("use");
            if ("无".equals(c.spec("年费"))) wizAdd(R, sb, ("student".equals(a.get("who")) || "save".equals(use)) ? 3 : 2, "免年费");
            if ("无".equals(c.spec("小额账户管理费"))) wizAdd(R, sb, 1, "无小额账户管理费");
            if ("online".equals(use)) {
                if (featMatch(c, "online")) wizAdd(R, sb, 2.5, "可网付");
                if (featMatch(c, "applepay")) wizAdd(R, sb, 1.5, "支持 Apple Pay");
            }
        }
        // 档次偏好（同 app.js）：想办高端卡的别老推普卡，想省事的别推门槛高的
        int tier = cardTier(c);
        String ta = a.get("tier");
        if ("high".equals(ta)) {
            if (tier >= 3) wizAdd(R, sb, 4, "钻石 / 无限级");
            else if (tier == 2) wizAdd(R, sb, 2.5, "白金级");
            else if (tier == 1) wizAdd(R, sb, 0.5, "");
            else sb[0] -= 1.5;
        } else if ("mid".equals(ta)) {
            if (tier == 2) wizAdd(R, sb, 3, "白金级");
            else if (tier == 1) wizAdd(R, sb, 2, "金卡级");
            else if (tier >= 3) wizAdd(R, sb, 1, "钻石 / 无限级");
            else sb[0] -= 1;
        } else if ("basic".equals(ta)) {
            if (tier == 0) wizAdd(R, sb, 2, "好办理");
            else if (tier == 1) wizAdd(R, sb, 1.5, "金卡级");
            else if (tier >= 2) sb[0] -= 1;
        }
        R.sort((x, y) -> Double.compare(y.w, x.w));
        WizResult r = new WizResult();
        r.c = c; r.s = sb[0];
        r.reasons = new ArrayList<>();
        for (int i = 0; i < Math.min(3, R.size()); i++) r.reasons.add(R.get(i).l);
        return r;
    }

    void openWizard() {
        captureCurrentPageScroll(); // P-keepscroll：关选卡后回到打开前的页面位置
        wizSc = null; wizStep = 0; wizA.clear();
        wizardOpen = true; detailFromWiz = false;
        showWizardPage();
    }

    void closeWizard() {
        wizardOpen = false;
        final View sheet = wizardSheet;
        wizardSheet = null;
        if (sheet != null && sheet.getParent() != null) {
            // 关闭：窗下沉淡出（180ms 减速，与筛选窗同一套手感），落位后摘窗、底栏与悬浮钮再回来
            View card = sheet instanceof ViewGroup && ((ViewGroup) sheet).getChildCount() > 1
                ? ((ViewGroup) sheet).getChildAt(((ViewGroup) sheet).getChildCount() - 1) : null; // Q11：玻璃层垫在窗下，窗体是最后一层
            if (card != null) {
                card.animate().translationY(dp(this, 42)).alpha(0f)
                    .setDuration(180).setInterpolator(ANIM_ENTER)
                    .withEndAction(() -> {
                        if (sheet.getParent() != null) ((ViewGroup) sheet.getParent()).removeView(sheet);
                        navBar.setVisibility(View.VISIBLE);
                        syncSearchFab();
                    }).start();
                sheet.animate().alpha(0f).setDuration(180).start();
                return;
            }
            ((ViewGroup) sheet.getParent()).removeView(sheet);
        }
        navBar.setVisibility(View.VISIBLE);
        syncSearchFab();
    }

    // 同 app.js wizBack：已在第一题（含未选场景由关闭键处理）就回到选场景，否则上一步
    void wizGoBack() {
        if (wizStep <= 1) { wizSc = null; wizStep = 0; wizA.clear(); showWizardPage(); }
        else { wizStep--; showWizardPage(); }
    }

    // P2b：选卡改悬浮窗——对照混合版 .wizard/.wiz-shade/.wiz-sheet：全屏轻遮罩（rgba(15,20,40,.46)）
    // +贴底大圆角窗（顶圆角 26、max-height 88vh、柔影），底层页面留在后面，关窗回到原页原位。
    void showWizardPage() {
        navBar.setVisibility(View.GONE);
        // P4：窗已在场时是步骤切换（新内容横向滑入），否则是首次打开（整窗升起）
        boolean stepSwitch = wizardSheet != null && wizardSheet.getParent() != null;
        if (wizardSheet != null && wizardSheet.getParent() != null)
            ((ViewGroup) wizardSheet.getParent()).removeView(wizardSheet);
        final FrameLayout sheet = new FrameLayout(this);
        View shade = new View(this);
        shade.setBackgroundColor(Color.argb(117, 15, 20, 40));
        shade.setOnClickListener(v -> closeWizard());
        sheet.addView(shade, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable cg = new GradientDrawable();
        cg.setColor(Color.argb(198, 0xF2, 0xF3, 0xF7)); // Q11：半透染色盖在冻结模糊层上
        float rTop = dp(this, 26);
        cg.setCornerRadii(new float[]{rTop, rTop, rTop, rTop, 0, 0, 0, 0});
        card.setBackground(cg);
        if (Build.VERSION.SDK_INT >= 21) {
            card.setElevation(dp(this, 24));
            card.setClipToOutline(true);
        }
        card.setOnClickListener(v -> {}); // 窗体本体吃掉点击，防穿透到遮罩误关（同筛选窗）
        card.addView(buildWizardPage(), new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        int sw = getResources().getDisplayMetrics().widthPixels;
        int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.88);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.gravity = Gravity.BOTTOM;
        card.measure(View.MeasureSpec.makeMeasureSpec(sw, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST));
        clp.height = card.getMeasuredHeight();
        // Q11：贴底窗下垫冻结模糊层——玻璃层向下多延 26dp，让顶圆角对齐窗体、底圆角沉到屏外
        FrameLayout wizGlassWrap = new FrameLayout(this);
        wizGlassWrap.addView(glassLayer(card, 26, false), new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, clp.height + dp(this, 26)));
        wizGlassWrap.addView(card, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        sheet.addView(wizGlassWrap, clp);
        content.addView(sheet);
        wizardSheet = sheet;
        // P4：首次打开走遮罩淡入+窗从下方 42dp 上浮（260ms，混合版 wizUp 口径）；步骤切换只让
        // 新内容横向轻滑淡入——前进从右侧、后退从左侧，220ms 减速，窗体高度与遮罩保持不动。
        if (stepSwitch) {
            sheet.setAlpha(1f);
            float dx = dp(this, wizStep >= lastWizStepShown ? 28 : -28);
            card.setAlpha(0f);
            card.setTranslationX(dx);
            card.animate().alpha(1f).translationX(0f)
                .setDuration(220).setInterpolator(ANIM_ENTER).start();
        } else {
            sheet.setAlpha(0f);
            sheet.animate().alpha(1f).setDuration(200)
                .setInterpolator(ANIM_ENTER).start();
            card.setTranslationY(dp(this, 42));
            card.animate().translationY(0f)
                .setDuration(260).setInterpolator(ANIM_ENTER).start();
        }
        lastWizStepShown = wizStep;
    }

    WizSc wizScenario() {
        for (WizSc s : WIZ_SCENARIOS) if (s.id.equals(wizSc)) return s;
        return null;
    }

    List<WizQ> wizQs() {
        List<WizQ> out = new ArrayList<>();
        WizSc sc = wizScenario();
        if (sc != null) {
            java.util.Collections.addAll(out, sc.qs);
            out.add(WIZ_TIER_Q);
            out.add(WIZ_TYPE_Q);
        }
        return out;
    }

    static String wizOptLabel(WizQ q, String v) {
        for (String[] o : q.opts) if (o[0].equals(v)) return o[1];
        return v;
    }

    void addWizardTrail(LinearLayout page, List<WizQ> qs) {
        WizSc sc = wizScenario();
        if (sc == null) return;
        boolean results = wizStep > qs.size();
        // 轨迹项：{step, label, val}；答题中只显示当前题之前的，结果页全显示（同 renderWiz 的过滤）
        List<Object[]> items = new ArrayList<>();
        items.add(new Object[]{0, "场景", sc.name});
        for (int i = 0; i < qs.size(); i++) {
            WizQ q = qs.get(i);
            String v = wizA.get(q.k);
            if (v == null) continue;
            items.add(new Object[]{i + 1, wizQShort(q.k), wizOptLabel(q, v)});
        }
        for (Object[] it : items) {
            final int step = (Integer) it[0];
            if (!results && step >= wizStep) continue;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackground(roundRect(Color.rgb(0xE9, 0xF7, 0xEE), 10, this));
            row.setPadding(dp(this, 10), dp(this, 7), dp(this, 10), dp(this, 7));
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.topMargin = dp(this, 6);
            row.setLayoutParams(rlp);
            TextView kv = tv(this, (String) it[1] + "  ", 11, Color.rgb(0x8E, 0x8E, 0x93), true);
            row.addView(kv);
            TextView val = tv(this, (String) it[2], 12.5f, Color.rgb(0x1C, 0x1C, 0x1E), true);
            row.addView(val, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(tv(this, "修改", 11.5f, Color.rgb(0x0A, 0x5C, 0xD6), true));
            row.setOnClickListener(v -> {
                haptic();
                if (step == 0) { wizSc = null; wizStep = 0; wizA.clear(); }
                else wizStep = step;
                showWizardPage();
            });
            page.addView(row);
        }
    }

    View buildWizardPage() {
        // P2b：窗内结构——抓手 + 固定头部（返回/标题/关闭不随内容滚）+ 内容滚动区，对照混合版 .wiz-sheet/.wiz-head/.wiz-body
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        View grab = new View(this);
        grab.setBackground(roundRect(Color.rgb(0xD9, 0xD9, 0xDE), 3, this));
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(dp(this, 38), dp(this, 5));
        glp.gravity = Gravity.CENTER_HORIZONTAL;
        glp.topMargin = dp(this, 8);
        glp.bottomMargin = dp(this, 4);
        col.addView(grab, glp);
        LinearLayout headWrap = new LinearLayout(this);
        headWrap.setOrientation(LinearLayout.VERTICAL);
        headWrap.setPadding(dp(this, 18), dp(this, 2), dp(this, 18), 0);
        col.addView(headWrap);
        ScrollView sv = new ScrollView(this);
        thinScrollbar(sv);
        sv.setBackgroundColor(Color.TRANSPARENT);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(this, 18), dp(this, 4), dp(this, 18), dp(this, 24) + navBarH()); // Q26：贴底窗内容避开手势条
        sv.addView(page);
        col.addView(sv, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        List<WizQ> qs = wizQs();
        WizSc sc = wizScenario();
        String title = wizSc == null ? "情景选卡" : (wizStep <= qs.size() ? sc.name : "为你挑的卡");

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        headWrap.addView(top);
        Button back = new Button(this);
        back.setText("‹ 返回"); back.setTextSize(14); back.setAllCaps(false);
        back.setBackground(roundRect(Color.WHITE, 12, this));
        back.setVisibility(wizSc == null ? View.INVISIBLE : View.VISIBLE);
        back.setOnClickListener(v -> { haptic(); wizGoBack(); });
        top.addView(back, new LinearLayout.LayoutParams(dp(this, 84), dp(this, 38)));
        TextView ttl = tv(this, title, 16, Color.rgb(0x1C, 0x1C, 0x1E), true);
        LinearLayout.LayoutParams ttlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        ttlp.leftMargin = dp(this, 10);
        top.addView(ttl, ttlp);
        Button close = new Button(this);
        close.setText("✕"); close.setTextSize(14); close.setAllCaps(false);
        close.setBackground(roundRect(Color.WHITE, 12, this));
        close.setOnClickListener(v -> { haptic(); closeWizard(); });
        top.addView(close, new LinearLayout.LayoutParams(dp(this, 44), dp(this, 38)));

        if (wizSc == null) {
            TextView sub = tv(this, "打算拿卡做什么？选个场景往下答，每答完一题上面都会留一条，随时看清走到哪一步。", 12.5f, Color.rgb(0x8E, 0x8E, 0x93), false);
            LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            subLp.topMargin = dp(this, 12);
            page.addView(sub, subLp);
            for (final WizSc s : WIZ_SCENARIOS) {
                LinearLayout tile = new LinearLayout(this);
                tile.setOrientation(LinearLayout.HORIZONTAL);
                tile.setGravity(Gravity.CENTER_VERTICAL);
                tile.setBackground(rippleBg(Color.WHITE, 14));
                tile.setClipToOutline(true);
                tile.setPadding(dp(this, 14), dp(this, 12), dp(this, 14), dp(this, 12));
                LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                tlp.topMargin = dp(this, 10);
                tile.setLayoutParams(tlp);
                LinearLayout tx = new LinearLayout(this);
                tx.setOrientation(LinearLayout.VERTICAL);
                tile.addView(tx, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                tx.addView(tv(this, s.name, 16, Color.rgb(0x1C, 0x1C, 0x1E), true));
                tx.addView(tv(this, s.desc, 12, Color.rgb(0x8E, 0x8E, 0x93), false));
                tile.addView(tv(this, "›", 18, Color.rgb(0x8E, 0x8E, 0x93), false));
                tile.setOnClickListener(v -> { haptic(); wizSc = s.id; wizStep = 1; showWizardPage(); });
                page.addView(tile);
            }
            return col;
        }

        addWizardTrail(page, qs);

        if (wizStep <= qs.size()) {
            final WizQ q = qs.get(wizStep - 1);
            LinearLayout qHead = new LinearLayout(this);
            qHead.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams qhLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            qhLp.topMargin = dp(this, 14);
            page.addView(qHead, qhLp);
            TextView tag = tv(this, "第 " + wizStep + " 题 · 共 " + qs.size() + " 题", 11, Color.rgb(0x0A, 0x5C, 0xD6), true);
            tag.setBackground(roundRect(Color.rgb(0xE8, 0xF1, 0xFD), 999, this));
            tag.setPadding(dp(this, 8), dp(this, 3), dp(this, 8), dp(this, 3));
            LinearLayout tagWrap = new LinearLayout(this);
            tagWrap.setOrientation(LinearLayout.HORIZONTAL);
            tagWrap.addView(tag);
            qHead.addView(tagWrap);
            TextView qt = tv(this, q.q, 16, Color.rgb(0x1C, 0x1C, 0x1E), true);
            LinearLayout.LayoutParams qtLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            qtLp.topMargin = dp(this, 8);
            qHead.addView(qt, qtLp);
            for (final String[] o : q.opts) {
                TextView opt = tv(this, o[1], 14, Color.rgb(0x1C, 0x1C, 0x1E), false);
                opt.setBackground(rippleBg(Color.WHITE, 12));
                opt.setClipToOutline(true);
                opt.setPadding(dp(this, 14), dp(this, 12), dp(this, 14), dp(this, 12));
                LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                olp.topMargin = dp(this, 8);
                page.addView(opt, olp);
                opt.setOnClickListener(v -> { haptic(); wizA.put(q.k, o[0]); wizStep++; showWizardPage(); });
            }
            return col;
        }

        // 结果页：先按场景打分排序，再按「卡种」答复过滤（候选不足 4 张则不滤，同 renderWiz）
        List<WizResult> pool = new ArrayList<>();
        for (Card c : Store.all) {
            WizResult r = wizScore(c, wizSc, wizA);
            if (r != null) pool.add(r);
        }
        pool.sort((x, y) -> Double.compare(y.s, x.s));
        List<WizResult> list = pool;
        String wantType = wizA.get("type");
        if (wantType != null && !"any".equals(wantType)) {
            List<WizResult> f = new ArrayList<>();
            for (WizResult r : pool) {
                String ct = (r.c.type == null || r.c.type.isEmpty()) ? "debit" : r.c.type;
                if (wantType.equals(ct)) f.add(r);
            }
            if (f.size() >= 4) list = f;
        }
        if (list.size() > 6) list = new ArrayList<>(list.subList(0, 6));

        TextView sub = tv(this, "从 " + pool.size() + " 张在发卡里按「" + sc.name + "」排的，点卡看详情，＋ 是加入我的卡片。", 12.5f, Color.rgb(0x8E, 0x8E, 0x93), false);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(this, 12);
        page.addView(sub, subLp);

        for (final WizResult r : list) page.addView(wizResultRow(r));

        Button redo = new Button(this);
        redo.setText("换个场景重新选"); redo.setTextSize(13.5f); redo.setAllCaps(false);
        redo.setBackground(roundRect(Color.WHITE, 12, this));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(this, 44));
        rlp.topMargin = dp(this, 16);
        page.addView(redo, rlp);
        redo.setOnClickListener(v -> { haptic(); wizSc = null; wizStep = 0; wizA.clear(); showWizardPage(); });
        return col;
    }

    View wizResultRow(final WizResult r) {
        final Card c = r.c;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBackground(roundRect(Color.WHITE, 14, this));
        row.setBackground(rippleBg(Color.WHITE, 14));
        row.setClipToOutline(true);
        row.setPadding(dp(this, 12), dp(this, 10), dp(this, 12), dp(this, 10));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(this, 10);
        row.setLayoutParams(rlp);

        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setBackground(placeholderGrad(9, this));
        roundClip(iv, 9, this); // Q27 同机制：缩略图自身圆角裁切，不靠父行轮廓
        row.addView(iv, new LinearLayout.LayoutParams(dp(this, 76), dp(this, 48)));
        Bitmap b = Img.get(this, c.image);
        if (b != null) iv.setImageBitmap(b);

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        ilp.leftMargin = dp(this, 10);
        row.addView(info, ilp);
        TextView nm = tv(this, c.name, 14, Color.rgb(0x1C, 0x1C, 0x1E), true);
        nm.setMaxLines(2);
        info.addView(nm);
        String orgTxt = (c.org == null || c.org.isEmpty()) ? "—" : orgLabel(c.org);
        info.addView(tv(this, c.bank + " · " + orgTxt + " · " + (c.isCredit() ? "信用卡" : "借记卡"), 11, Color.rgb(0x8E, 0x8E, 0x93), false));
        if (!r.reasons.isEmpty()) {
            LinearLayout rr = new LinearLayout(this);
            rr.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rrLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rrLp.topMargin = dp(this, 6);
            info.addView(rr, rrLp);
            for (String reason : r.reasons) rr.addView(chip(reason, Color.rgb(0xE8, 0xF1, 0xFD), Color.rgb(0x0A, 0x5C, 0xD6)));
        }
        String limit = c.spec("发行情况");
        if (limit.contains("仅")) {
            TextView note = tv(this, "⚠ " + limit, 11, Color.rgb(0xB0, 0x23, 0x2B), false);
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            nlp.topMargin = dp(this, 5);
            info.addView(note, nlp);
        }

        LinearLayout side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);
        side.setGravity(Gravity.RIGHT);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.leftMargin = dp(this, 8);
        row.addView(side, slp);
        TextView score = tv(this, c.hasScore ? String.format(java.util.Locale.US, "%.1f分", c.score) : "待评分",
            11, Color.rgb(0x0A, 0x5C, 0xD6), true);
        score.setBackground(roundRect(Color.rgb(0xE8, 0xF1, 0xFD), 999, this));
        score.setPadding(dp(this, 8), dp(this, 3), dp(this, 8), dp(this, 3));
        side.addView(score);
        final boolean inMine = mine.contains(c.id);
        Button add = new Button(this);
        add.setText(inMine ? "✓" : "＋"); add.setTextSize(15); add.setAllCaps(false);
        add.setTextColor(inMine ? Color.rgb(0x1D, 0x8A, 0x49) : Color.WHITE);
        add.setBackground(roundRect(inMine ? Color.rgb(0xE6, 0xF6, 0xEC) : Color.rgb(0x0A, 0x5C, 0xD6), 999, this));
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(dp(this, 38), dp(this, 38));
        alp.topMargin = dp(this, 8);
        alp.gravity = Gravity.RIGHT;
        side.addView(add, alp);
        add.setOnClickListener(v -> {
            haptic();
            toggleMineWithToast(c, () -> { if (wizardOpen) showWizardPage(); });
        });
        row.setOnClickListener(v -> openDetail(c, true));
        attachCardMenuLongPress(row, c, true);
        return row;
    }

    // ---------- 详情页（Q6 贴底浮窗，对照混合版 .panel/.backdrop/.p-*） ----------
    // 底层页面不切走、不重绑：浮窗盖在现页之上，关窗回原页原滚动位；遮罩 rgba(0,0,0,.4)、
    // 窗体贴底全宽、顶圆 20dp、最高 88vh、内滚、底内边 20dp；关闭字形 48dp 触控框内嵌 34dp
    // 半透圆，右上 -4dp 微出窗外，升起时与窗同行、收窗时先冻结在原位、最后与遮罩一同淡出。
    void openDetail(Card c) { openDetail(c, false); }

    void openDetail(Card c, boolean fromWiz) {
        if (c == null) return;
        if (detailClosing) return; // 收窗途中再点既不重开也不入队（关窗意图已生效）
        if (detailCard != null) { detailQueue.add(c); return; } // 连点排队 FIFO，关一开一下一张
        dismissCardMenu();
        if (!fromWiz) captureCurrentPageScroll();
        detailFromWiz = fromWiz;
        detailCard = c;
        detailClosing = false;
        detailVariantIdx = 0;
        if (navWrap != null) navWrap.setVisibility(View.GONE);

        final FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(Color.TRANSPARENT);
        final View shade = new View(this);
        shade.setBackgroundColor(Color.argb(102, 0, 0, 0)); // rgba(0,0,0,.4)
        shade.setAlpha(0f);
        shade.setOnClickListener(v -> closeDetail());
        overlay.addView(shade, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        detailShade = shade;

        // 窗体容器（贴底）：玻璃垫 + 白窗；测高封顶 88vh
        final FrameLayout wrap = new FrameLayout(this);
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int screenH = getResources().getDisplayMetrics().heightPixels;
        final int maxH = (int) (screenH * 0.88);

        final LinearLayout sheetCard = new LinearLayout(this);
        sheetCard.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable sheetBg = new GradientDrawable();
        sheetBg.setColor(Color.rgb(0xFF, 0xFF, 0xFF));
        float rTop = dp(this, 20);
        sheetBg.setCornerRadii(new float[]{rTop, rTop, rTop, rTop, 0, 0, 0, 0});
        sheetCard.setBackground(sheetBg);
        if (Build.VERSION.SDK_INT >= 21) { sheetCard.setElevation(dp(this, 24)); sheetCard.setClipToOutline(true); }
        sheetCard.setOnClickListener(v -> {}); // 窗体吃点击防穿透遮罩

        // 内容滚动区 + 底部常驻收藏钮（窗内延续，不随内容滚走）
        ScrollView sc = new ScrollView(this);
        thinScrollbar(sc);
        sc.setBackgroundColor(Color.TRANSPARENT);
        sc.setFillViewport(false);
        detailScroll = sc;
        LinearLayout body = buildDetailSheetBody(c);
        sc.addView(body);
        sheetCard.addView(sc, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setPadding(dp(this, 16), dp(this, 10), dp(this, 16), dp(this, 20) + navBarH()); // Q26：贴底窗按钮避开系统手势条
        footer.setBackgroundColor(Color.WHITE);
        sheetCard.addView(footer);
        final Button mineBtn = new Button(this);
        mineBtn.setTextSize(15); mineBtn.setAllCaps(false);
        detailMineBtn = mineBtn;
        styleMineBtn(mineBtn, c);
        mineBtn.setOnClickListener(v -> {
            haptic();
            toggleMineWithToast(c, () -> styleMineBtn(mineBtn, c));
        });
        footer.addView(mineBtn, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(this, 50)));

        // 先量高再定版（内容可能短于封顶）
        sheetCard.measure(View.MeasureSpec.makeMeasureSpec(screenW, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST));
        int sheetH = Math.min(sheetCard.getMeasuredHeight(), maxH);
        FrameLayout.LayoutParams wlp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, sheetH);
        wlp.gravity = Gravity.BOTTOM;
        // Q11：窗下垫冻结玻璃层，与窗同位（顶圆 20 对齐，底边沉屏外由容器裁掉）
        wrap.addView(glassLayer(sheetCard, 20, false), new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        wrap.addView(sheetCard, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        overlay.addView(wrap, wlp);
        detailSheetWrap = wrap;

        // 关闭字形：48dp 对话框框 + 内嵌 34dp 半透圆 ✕，右上 -4dp 微出窗外（与窗同为 wrap 子层，升起同行）
        final FrameLayout glyphFrame = new FrameLayout(this);
        glyphFrame.setClipChildren(false); glyphFrame.setClipToPadding(false);
        View circle = new View(this);
        GradientDrawable cg = new GradientDrawable();
        cg.setShape(GradientDrawable.OVAL);
        cg.setColor(Color.argb(64, 120, 120, 128)); // rgba(120,120,128,.25)
        circle.setBackground(cg);
        FrameLayout.LayoutParams clp2 = new FrameLayout.LayoutParams(dp(this, 34), dp(this, 34));
        clp2.gravity = Gravity.CENTER;
        glyphFrame.addView(circle, clp2);
        TextView x = tv(this, "\u2715", 15, Color.WHITE, true);
        x.setGravity(Gravity.CENTER);
        glyphFrame.addView(x, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        glyphFrame.setOnClickListener(v -> { haptic(); closeDetail(); });
        FrameLayout.LayoutParams glp = new FrameLayout.LayoutParams(dp(this, 48), dp(this, 48));
        glp.gravity = Gravity.TOP | Gravity.END;
        glp.topMargin = -dp(this, 4);
        glp.rightMargin = dp(this, 12);
        wrap.addView(glyphFrame, glp);
        detailCloseGlyph = glyphFrame;

        // 拖拽关闭：抓手区下滑过 80dp 松手关窗（窗内滚动不受影响）
        attachDetailDrag(wrap, sheetCard);

        content.addView(overlay, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        overlay.bringToFront();
        detailView = overlay;
        syncSearchFab();
        syncTopFab();
        // 升起：遮罩 220ms 淡入 + 窗体（含字形）自下方滑入 240ms 同曲线家族
        shade.animate().alpha(1f).setDuration(220).setInterpolator(ANIM_ENTER).start();
        wrap.setTranslationY(sheetH);
        wrap.animate().translationY(0f).setDuration(240).setInterpolator(ANIM_ENTER).start();
    }

    void attachDetailDrag(final View wrap, final View sheetCard) {
        final float[] downY = {0f};
        final boolean[] dragging = {false};
        // 抓手条在滚动内容顶部，拖它下滑关窗；其余区域仍可正常滚动
        sheetCard.setOnTouchListener((v, e) -> false);
        if (detailScroll != null) {
            detailScroll.setOnTouchListener((v, e) -> {
                if (detailClosing) return false;
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downY[0] = e.getRawY(); dragging[0] = false; return false;
                    case MotionEvent.ACTION_MOVE:
                        if (detailScroll.getScrollY() <= 0 && e.getRawY() - downY[0] > dp(this, 12)) {
                            dragging[0] = true;
                            float dy = Math.max(0f, e.getRawY() - downY[0]);
                            if (wrap != null) wrap.setTranslationY(dy * 0.6f);
                            return false;
                        }
                        return false;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (dragging[0] && wrap != null) {
                            float dy = e.getRawY() - downY[0];
                            dragging[0] = false;
                            if (dy > dp(this, 80)) { closeDetail(); return true; }
                            wrap.animate().translationY(0f).setDuration(180)
                                .setInterpolator(ANIM_ENTER).start();
                        }
                        return false;
                    default: return false;
                }
            });
        }
    }

    void closeDetail() {
        if (detailCard == null || detailClosing) return;
        detailClosing = true;
        final View overlay = detailView;
        final View wrap = detailSheetWrap;
        final View shade = detailShade;
        final View glyph = detailCloseGlyph;
        final boolean wasWiz = detailFromWiz;
        Runnable finish = () -> {
            if (overlay != null && overlay.getParent() instanceof ViewGroup)
                ((ViewGroup) overlay.getParent()).removeView(overlay);
            detailView = null; detailSheetWrap = null; detailShade = null; detailCloseGlyph = null;
            detailScroll = null; detailBinView = null; detailVerInfoBox = null; detailMineBtn = null;
            detailDots = new java.util.ArrayList<>();
            detailCard = null; detailClosing = false; detailFromWiz = false;
            if (!wasWiz && navWrap != null) navWrap.setVisibility(View.VISIBLE);
            if (wasWiz && wizardOpen) {
                // 选卡窗仍在底下（未被切走），直接露回即可，进度天然保留
                if (navWrap != null) navWrap.setVisibility(View.GONE);
            }
            restoreCurrentTabScroll();
            syncSearchFab();
            syncTopFab();
            // FIFO：关窗落定才开下一次点选的那张，不叠窗
            Card next = detailQueue.poll();
            if (next != null) openDetail(next, wasWiz && wizardOpen);
        };
        if (overlay == null || wrap == null) { finish.run(); return; }
        // 收窗第一程：字形先冻结——摘到浮层根上原地不动（-4dp 出窗位保持），窗体单独下滑 240ms
        try {
            if (glyph != null && glyph.getParent() == wrap && overlay instanceof FrameLayout) {
                int[] gl = new int[2]; glyph.getLocationOnScreen(gl);
                int[] ol = new int[2]; overlay.getLocationOnScreen(ol);
                ((ViewGroup) wrap).removeView(glyph);
                FrameLayout.LayoutParams fp = new FrameLayout.LayoutParams(dp(this, 48), dp(this, 48));
                fp.gravity = Gravity.TOP | Gravity.START;
                fp.leftMargin = gl[0] - ol[0]; fp.topMargin = gl[1] - ol[1];
                ((FrameLayout) overlay).addView(glyph, fp);
                glyph.bringToFront();
            }
        } catch (Throwable ignored) { /* 冻结失败不挡关窗，字形随窗走 */ }
        int targetY = wrap.getHeight() > 0 ? wrap.getHeight() : dp(this, 420);
        wrap.animate().translationY(targetY).setDuration(240).setInterpolator(ANIM_ENTER)
            .withEndAction(() -> {
                // 第二程：字形与遮罩一同淡出，落定后才拆浮层
                if (glyph != null) glyph.animate().alpha(0f).setDuration(180)
                    .setInterpolator(ANIM_EXIT).start();
                if (shade != null) shade.animate().alpha(0f).setDuration(180)
                    .setInterpolator(ANIM_EXIT).withEndAction(finish).start();
                else finish.run();
            }).start();
    }

    String variantBinText(Card c, int idx) {
        if (c.variants == null || c.variants.length() == 0) return "\u2014";
        JSONObject v = c.variants.optJSONObject(Math.max(0, Math.min(idx, c.variants.length() - 1)));
        if (v == null) return "\u2014";
        String bin = v.optString("bin", "");
        String nm = v.optString("name", "");
        if (bin == null || bin.isEmpty()) return "\u2014";
        return (nm == null || nm.isEmpty()) ? bin : bin + "\uFF08" + nm + "\uFF09";
    }

    void updateDetailVariant(int idx) {
        detailVariantIdx = idx;
        Card c = detailCard;
        if (c == null) return;
        for (int i = 0; i < detailDots.size(); i++) {
            View d = detailDots.get(i);
            boolean on = i == idx;
            GradientDrawable g = new GradientDrawable();
            g.setColor(on ? Color.rgb(0x3A, 0x3A, 0x3C) : Color.rgb(0xD8, 0xD8, 0xDE));
            g.setCornerRadius(dp(this, 3));
            d.setBackground(g);
        }
        // LinearLayout 子项宽度切换（LinearLayout.LayoutParams）
        for (int i = 0; i < detailDots.size(); i++) {
            View d = detailDots.get(i);
            if (d.getLayoutParams() instanceof LinearLayout.LayoutParams) {
                LinearLayout.LayoutParams lp2 = (LinearLayout.LayoutParams) d.getLayoutParams();
                lp2.width = dp(this, i == idx ? 18 : 6);
                d.setLayoutParams(lp2);
            }
        }
        if (detailBinView != null) detailBinView.setText(variantBinText(c, idx));
        if (detailVerInfoBox != null) {
            detailVerInfoBox.removeAllViews();
            fillVerInfo(detailVerInfoBox, c, idx);
        }
    }

    void fillVerInfo(LinearLayout box, Card c, int idx) {
        if (c.variants == null || c.variants.length() == 0) return;
        JSONObject v = c.variants.optJSONObject(Math.max(0, Math.min(idx, c.variants.length() - 1)));
        if (v == null) return;
        String nm = v.optString("name", "");
        String bin = v.optString("bin", "");
        String note = v.optString("note", "");
        TextView t = tv(this, nm + (bin == null || bin.isEmpty() ? "" : " \u00B7 BIN " + bin),
            14, Color.rgb(0x1C, 0x1C, 0x1E), true);
        box.addView(t);
        if (note != null && !note.isEmpty()) {
            TextView n = tv(this, note, 12.5f, Color.rgb(0x3A, 0x3A, 0x3C), false);
            n.setLineSpacing(0, 1.4f);
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            nlp.topMargin = dp(this, 4);
            box.addView(n, nlp);
        }
    }

    LinearLayout buildDetailSheetBody(final Card c) {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(Color.WHITE);

        // ---- 图廊（.p-gal/.p-track/.p-slide）：整宽横滑，图原比例 contain、圆角 12、阴影，高封顶 260 ----
        final boolean hasVar = c.variants != null && c.variants.length() > 0;
        final int nSlides = hasVar ? c.variants.length() : 1;
        LinearLayout gal = new LinearLayout(this);
        gal.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable galBg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{Color.rgb(0xF1, 0xF1, 0xF4), Color.WHITE});
        gal.setBackground(galBg);
        page.addView(gal);

        final HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        hsv.setFillViewport(true);
        LinearLayout track = new LinearLayout(this);
        track.setOrientation(LinearLayout.HORIZONTAL);
        hsv.addView(track);
        gal.addView(hsv);
        final int screenW = getResources().getDisplayMetrics().widthPixels;
        detailDots = new java.util.ArrayList<>();
        for (int i = 0; i < nSlides; i++) {
            String imgPath = c.image;
            String slideName = "";
            if (hasVar) {
                JSONObject vv = c.variants.optJSONObject(i);
                if (vv != null) {
                    String vi = vv.optString("image", "");
                    if (vi != null && !vi.isEmpty()) imgPath = vi;
                    slideName = vv.optString("name", "");
                }
            }
            LinearLayout slide = new LinearLayout(this);
            slide.setOrientation(LinearLayout.VERTICAL);
            slide.setGravity(Gravity.CENTER_HORIZONTAL);
            slide.setPadding(dp(this, 22), dp(this, 16), dp(this, 22), dp(this, 4));
            track.addView(slide, new LinearLayout.LayoutParams(screenW, ViewGroup.LayoutParams.WRAP_CONTENT));
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setBackground(placeholderGrad(12, this));
            roundClip(iv, 12, this); // Q27：显式圆角轮廓，位图/占位渐变/阴影同半径，根除四角黑边
            if (Build.VERSION.SDK_INT >= 21) iv.setElevation(dp(this, 6));
            Bitmap b = Img.get(this, imgPath);
            int availW = screenW - dp(this, 44);
            int imgW = availW, imgH = dp(this, 168);
            if (b != null && b.getWidth() > 0 && b.getHeight() > 0) {
                float ratio = (float) b.getHeight() / (float) b.getWidth();
                imgH = Math.round(availW * ratio);
                int maxH = dp(this, 260);
                if (imgH > maxH) { imgH = maxH; imgW = Math.round(imgH / ratio); }
            }
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(imgW, imgH);
            ilp.gravity = Gravity.CENTER_HORIZONTAL;
            slide.addView(iv, ilp);
            if (b != null) {
                // Q27：有真图时撤掉占位底（免其颜色从圆角外透出），图本身按位图级圆角出（半径按位图/显示宽比换算）
                iv.setBackground(null);
                float rScale = imgW > 0 ? (float) b.getWidth() / (float) imgW : 1f;
                iv.setImageBitmap(roundBitmap(b, dp(this, 12) * rScale));
            }
            if (slideName != null && !slideName.isEmpty()) {
                TextView sn = tv(this, slideName, 12, Color.rgb(0x8E, 0x8E, 0x93), true);
                sn.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams snp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                snp.topMargin = dp(this, 10);
                slide.addView(sn, snp);
            }
        }
        if (nSlides > 1) {
            LinearLayout dots = new LinearLayout(this);
            dots.setOrientation(LinearLayout.HORIZONTAL);
            dots.setGravity(Gravity.CENTER);
            dots.setPadding(0, dp(this, 10), 0, dp(this, 6));
            gal.addView(dots);
            for (int i = 0; i < nSlides; i++) {
                View d = new View(this);
                GradientDrawable g = new GradientDrawable();
                g.setColor(i == 0 ? Color.rgb(0x3A, 0x3A, 0x3C) : Color.rgb(0xD8, 0xD8, 0xDE));
                g.setCornerRadius(dp(this, 3));
                d.setBackground(g);
                LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                    dp(this, i == 0 ? 18 : 6), dp(this, 6));
                dlp.leftMargin = dp(this, 3); dlp.rightMargin = dp(this, 3);
                dots.addView(d, dlp);
                detailDots.add(d);
            }
            hsv.setOnScrollChangeListener((v, sx, sy, ox, oy) -> {
                int idx = Math.max(0, Math.min(nSlides - 1, Math.round((float) sx / Math.max(1, screenW))));
                if (idx != detailVariantIdx) updateDetailVariant(idx);
            });
        }

        // ---- 正文（.p-body）：卡名 + 元信息行 ----
        LinearLayout bodyInner = new LinearLayout(this);
        bodyInner.setOrientation(LinearLayout.VERTICAL);
        bodyInner.setPadding(dp(this, 18), dp(this, 16), dp(this, 18), dp(this, 8));
        page.addView(bodyInner);

        TextView name = tv(this, c.name, 19, Color.rgb(0x1C, 0x1C, 0x1E), true);
        name.setLineSpacing(0, 1.15f);
        bodyInner.addView(name);
        // 状态直接取记录自身（与规格同源 specs 外的 status 字段），不二次加工
        String metaTxt = c.bank + " \u00B7 " + orgLabel(c.org) + " \u00B7 " + c.status
            + " \u00B7 " + (c.hasScore ? String.format(java.util.Locale.US, "%.1f\u5206", c.score) : "\u5F85\u8BC4\u5206");
        TextView meta = tv(this, metaTxt, 12.5f, Color.rgb(0x8E, 0x8E, 0x93), false);
        LinearLayout.LayoutParams mep = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mep.topMargin = dp(this, 2);
        bodyInner.addView(meta, mep);

        // 当前版本信息（首版，随横滑切换与 BIN 同步）
        if (hasVar) {
            LinearLayout vib = new LinearLayout(this);
            vib.setOrientation(LinearLayout.VERTICAL);
            vib.setBackground(roundRect(Color.rgb(0xF6, 0xF6, 0xF8), 10, this));
            vib.setPadding(dp(this, 12), dp(this, 10), dp(this, 12), dp(this, 10));
            LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            vlp.topMargin = dp(this, 10);
            bodyInner.addView(vib, vlp);
            detailVerInfoBox = vib;
            fillVerInfo(vib, c, 0);
        }

        // 学生推荐段（原样取记录里的 reason，不在详情侧改写）
        if (c.studentPick && c.studentReason != null && !c.studentReason.isEmpty()) {
            bodyInner.addView(detailSectionTitle("\u5B66\u751F\u63A8\u8350"));
            TextView st = tv(this, c.studentReason, 13.5f, Color.rgb(0x3A, 0x3A, 0x3C), false);
            st.setLineSpacing(0, 1.45f);
            bodyInner.addView(st);
        }

        // 点评（保留既有口径，规格之前展示）
        if (c.review != null && !c.review.isEmpty()) {
            bodyInner.addView(detailSectionTitle("\u70B9\u8BC4"));
            TextView rv = tv(this, c.review, 13.5f, Color.rgb(0x3A, 0x3A, 0x3C), false);
            rv.setLineSpacing(0, 1.45f);
            rv.setBackground(roundRect(Color.rgb(0xEE, 0xF4, 0xFB), 12, this));
            rv.setPadding(dp(this, 12), dp(this, 9), dp(this, 12), dp(this, 9));
            bodyInner.addView(rv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        // ---- 规格（.p-sec + .spec）：显式 12 键顺序、状态不重复入表、URL 值整行剔除 ----
        bodyInner.addView(detailSectionTitle("\u89C4\u683C"));
        if (c.specs != null) {
            LinearLayout specBox = new LinearLayout(this);
            specBox.setOrientation(LinearLayout.VERTICAL);
            specBox.setPadding(0, 0, 0, 0);
            bodyInner.addView(specBox);
            java.util.List<String[]> rows = detailSpecRows(c);
            for (int i = 0; i < rows.size(); i++) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.TOP);
                row.setPadding(0, dp(this, 8), 0, dp(this, 8));
                specBox.addView(row);
                TextView kt = tv(this, rows.get(i)[0], 12.5f, Color.rgb(0x8E, 0x8E, 0x93), false);
                row.addView(kt, new LinearLayout.LayoutParams(dp(this, 108), ViewGroup.LayoutParams.WRAP_CONTENT));
                TextView vt = tv(this, rows.get(i)[1], 12.5f, Color.rgb(0x1C, 0x1C, 0x1E), false);
                vt.setGravity(Gravity.END);
                if ("BIN".equals(rows.get(i)[0]) && hasVar) detailBinView = vt;
                row.addView(vt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                if (i < rows.size() - 1) {
                    View div = new View(this);
                    div.setBackgroundColor(Color.argb(18, 20, 30, 60));
                    specBox.addView(div, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(this, 1) / 2)));
                }
            }
        }
        return page;
    }

    TextView detailSectionTitle(String s) {
        TextView t = tv(this, s, 15, Color.rgb(0x1C, 0x1C, 0x1E), true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(this, 14); lp.bottomMargin = dp(this, 8);
        t.setLayoutParams(lp);
        return t;
    }

    // 规格行：与混合版 specRows 同一 12 键显式顺序；状态已在标题区展示故不入表；值为网址整行剔除
    java.util.List<String[]> detailSpecRows(Card c) {
        String[] order = {"\u5361\u7EC4\u7EC7", "\u53D1\u5361\u884C", "\u5361\u79CD", "BIN", "\u5E74\u8D39", "\u8D27\u5E01\u8F6C\u6362\u8D39\uFF08FTF\uFF09", "3DS", "\u7F51\u4ED8", "Apple Pay", "\u81EA\u52A8\u8D2D\u6C47", "\u5883\u5916ATM", "\u72B6\u6001"};
        java.util.Set<String> inOrder = new java.util.HashSet<>(java.util.Arrays.asList(order));
        java.util.List<String[]> rows = new java.util.ArrayList<>();
        if (c.specs == null) return rows;
        boolean hasVar = c.variants != null && c.variants.length() > 0;
        for (String k : order) {
            if ("\u72B6\u6001".equals(k)) continue; // 标题区已承载状态，规格表不重复
            String v;
            if ("BIN".equals(k) && hasVar) v = variantBinText(c, detailVariantIdx);
            else v = c.specs.optString(k, "");
            if (v == null || v.isEmpty()) continue;
            if (v.contains("http://") || v.contains("https://")) continue; // 网址行整行剔除，不留空标签
            rows.add(new String[]{k, v});
        }
        Iterator<String> keys = c.specs.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            if (inOrder.contains(k)) continue;
            if ("\u72B6\u6001".equals(k)) continue;
            String v = c.specs.optString(k, "");
            if (v == null || v.isEmpty()) continue;
            if (v.contains("http://") || v.contains("https://")) continue;
            rows.add(new String[]{k, v});
        }
        return rows;
    }

    void styleMineBtn(Button b, Card c) {
        // Q6 常驻收藏钮（对照混合版 .p-fab）：玻璃白底、未收藏蓝字、已收藏绿字带勾，圆角 16
        boolean in = mine.contains(c.id);
        b.setText(in ? "\u2713 已在我的卡片" : "+ 加入我的卡片");
        b.setTextColor(in ? Color.rgb(0x34, 0xC7, 0x59) : Color.rgb(0x00, 0x7A, 0xFF));
        GradientDrawable fb = new GradientDrawable();
        fb.setColor(Color.argb(199, 255, 255, 255));
        fb.setCornerRadius(dp(this, 16));
        fb.setStroke(dp(this, 1), Color.argb(90, 255, 255, 255));
        b.setBackground(fb);
        if (Build.VERSION.SDK_INT >= 21) b.setElevation(dp(this, 8));
    }

    // ---------- 学生推荐（Phase 2b，对照 app.js studentReason/studentFit/studentPageHtml） ----------
    static String cleanPromo(String t) {
        if (t == null) return "";
        String x = t.replaceAll("（[^）]*）", "").replaceAll("有效期.*$", "").replaceAll("[、；，,]+$", "").trim();
        return x;
    }

    static String studentReason(Card c) {
        java.util.List<String> parts = new ArrayList<>();
        String curTxt = c.spec("币种支持");
        java.util.regex.Matcher mCur = java.util.regex.Pattern.compile("共\\s*(\\d+)\\s*币种").matcher(curTxt);
        if (mCur.find()) parts.add(mCur.group(1) + " 个币种一卡走天下");
        else if (curTxt.contains("多币种")) parts.add("人民币 + 外币多币种");
        if (featMatch(c, "noftf")) parts.add("无货币转换费");
        String promo = c.spec("优惠政策");
        java.util.regex.Matcher mAtm = java.util.regex.Pattern.compile("[^，。；]*ATM[^，。；]*免[^，。；]*笔[^，。；]*").matcher(promo);
        if (mAtm.find() && parts.size() < 3) parts.add(cleanPromo(mAtm.group()));
        java.util.regex.Matcher mCash = java.util.regex.Pattern.compile("境外消费[^，。；]*返现[^，。；]*").matcher(promo);
        if (mCash.find() && java.util.regex.Pattern.compile("\\d").matcher(mCash.group()).find()
            && !parts.toString().contains("返现") && parts.size() < 3) parts.add(cleanPromo(mCash.group()));
        if (java.util.regex.Pattern.compile("AI|算力").matcher((c.name == null ? "" : c.name) + promo).find()) parts.add("开卡达标送 AI 算力套餐和积分权益");
        if (java.util.regex.Pattern.compile("哔哩哔哩|2233").matcher(c.name == null ? "" : c.name).find()) parts.add("联名卡面，免年费免管理费、境内 ATM 免费");
        boolean hasFree = false; for (String p : parts) if (p.contains("免年费")) hasFree = true;
        if ("无".equals(c.spec("年费").trim()) && !hasFree && parts.size() < 3) parts.add("免年费");
        String autofx = c.spec("自动购汇").trim();
        if ((autofx.startsWith("有") || autofx.startsWith("支持")) && parts.size() < 3) parts.add("自动购汇，刷完自动换汇");
        String issued = c.spec("发行情况");
        if (issued.contains("网申") && !issued.contains("仅限线下") && parts.size() < 3) parts.add("网申就能办不用跑网点");
        if (parts.isEmpty()) parts.add("门槛低、费用省，学生党友好");
        java.util.List<String> top = parts.subList(0, Math.min(3, parts.size()));
        return android.text.TextUtils.join("，", top) + "。";
    }

    static java.util.List<String> studentFit(Card c) {
        java.util.LinkedHashSet<String> fit = new java.util.LinkedHashSet<>();
        String curTxt = c.spec("币种支持");
        java.util.regex.Matcher mCur = java.util.regex.Pattern.compile("共\\s*(\\d+)\\s*币种").matcher(curTxt);
        boolean manyCur = false;
        if (mCur.find()) { try { manyCur = Integer.parseInt(mCur.group(1)) >= 5; } catch (Exception e) {} }
        if (manyCur || "visa".equals(c.org)) fit.add("留学生");
        if (featMatch(c, "noftf") && featMatch(c, "online")) fit.add("海淘党");
        String autofx = c.spec("自动购汇").trim();
        if (autofx.startsWith("有") || autofx.startsWith("支持")) fit.add("出境旅游");
        String issued = c.spec("发行情况");
        if (issued.contains("网申") && !issued.contains("仅限线下")) fit.add("第一次办卡");
        if (java.util.regex.Pattern.compile("AI|算力").matcher(c.name == null ? "" : c.name).find()) fit.add("AI 工具党");
        if (java.util.regex.Pattern.compile("哔哩哔哩|2233").matcher(c.name == null ? "" : c.name).find()) fit.add("二次元");
        if ("unionpay".equals(c.org) || featMatch(c, "online")) fit.add("日常党");
        java.util.List<String> out = new ArrayList<>(fit);
        return out.subList(0, Math.min(3, out.size()));
    }

    View buildStudentPage() {
        LinearLayout page = basePage("学生推荐");
        List<Card> stu = new ArrayList<>();
        for (Card c : Store.all) if (c.studentPick) stu.add(c);
        stu.sort((a, b2) -> {
            int r = Integer.compare(a.studentOrder, b2.studentOrder);
            return r != 0 ? r : Double.compare(b2.score, a.score);
        });
        java.util.Set<String> banks = new HashSet<>();
        int nFree = 0, nFtf = 0, n3ds = 0;
        for (Card c : stu) {
            if (c.bank != null && !c.bank.isEmpty()) banks.add(c.bank);
            if ("无".equals(c.spec("年费").trim())) nFree++;
            if (featMatch(c, "noftf")) nFtf++;
            if (featMatch(c, "3ds")) n3ds++;
        }

        // 顶部白底明亮统计卡（对照 .stu-hero：左大数字 + 右三色点统计）
        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.HORIZONTAL);
        hero.setBackground(roundRect(Color.WHITE, 18, this));
        hero.setPadding(dp(this, 16), dp(this, 14), dp(this, 16), dp(this, 14));
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = dp(this, 12);
        page.addView(hero, hlp);
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        hero.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        left.addView(tv(this, stu.size() + " 张精选卡", 26, Color.rgb(0x1C, 0x1C, 0x1E), true));
        TextView sub = tv(this, "学生精选 · 覆盖 " + banks.size() + " 家银行", 12, Color.rgb(0x8E, 0x8E, 0x93), false);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(this, 4);
        left.addView(sub, subLp);
        LinearLayout stats = new LinearLayout(this);
        stats.setOrientation(LinearLayout.VERTICAL);
        hero.addView(stats, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        stats.addView(stuStatLine(Color.rgb(0x34, 0xC7, 0x59), "免年费", nFree));
        stats.addView(stuStatLine(Color.rgb(0x0A, 0x84, 0xFF), "无转换费", nFtf));
        stats.addView(stuStatLine(Color.rgb(0xFF, 0x9F, 0x0A), "支持 3DS", n3ds));

        // 「挑卡只看三件事」条（对照 .stu-quote，数字按当前精选与全库动态生成）
        TextView quote = tv(this, "挑卡只看三件事：别交年费、境外别被收转换费、网购能过 3DS。这 "
            + stu.size() + " 张就是按这个标准从 " + Store.all.size() + " 张里筛出来的。",
            12.5f, Color.rgb(0x3A, 0x3A, 0x3C), false);
        quote.setBackground(roundRect(Color.rgb(0xE8, 0xF1, 0xFD), 12, this));
        quote.setPadding(dp(this, 12), dp(this, 10), dp(this, 12), dp(this, 10));
        LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        qlp.topMargin = dp(this, 10);
        page.addView(quote, qlp);

        TextView sect = tv(this, "为什么推荐这些卡", 15, Color.rgb(0x1C, 0x1C, 0x1E), true);
        LinearLayout.LayoutParams sectLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sectLp.topMargin = dp(this, 14);
        page.addView(sect, sectLp);

        ScrollView sv = new ScrollView(this);
        thinScrollbar(sv);
        sv.setClipToPadding(false);
        studentScroll = sv;
        if (Build.VERSION.SDK_INT >= 23) sv.setOnScrollChangeListener((v, sx, sy, ox, oy) -> { pageScrollSaveY.put("student", sy); updateTopFabVisibility(sy); });
        LinearLayout listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        listBox.setPadding(0, dp(this, 10), 0, dockPad());
        sv.addView(listBox);
        page.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        for (final Card c : stu) {
            LinearLayout cardBox = new LinearLayout(this);
            cardBox.setOrientation(LinearLayout.VERTICAL);
            cardBox.setBackground(rippleBg(Color.WHITE, 14));
            cardBox.setClipToOutline(true);
            cardBox.setPadding(dp(this, 12), dp(this, 10), dp(this, 12), dp(this, 12));
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.topMargin = dp(this, 10);
            listBox.addView(cardBox, clp);
            cardBox.setOnClickListener(v -> openDetail(c));
            attachCardMenuLongPress(cardBox, c, false);

            LinearLayout top = new LinearLayout(this);
            top.setOrientation(LinearLayout.HORIZONTAL);
            top.setGravity(Gravity.CENTER_VERTICAL);
            cardBox.addView(top);
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setBackground(placeholderGrad(9, this));
            roundClip(iv, 9, this); // Q27 同机制：缩略图自身圆角裁切，不靠父卡轮廓
            top.addView(iv, new LinearLayout.LayoutParams(dp(this, 72), dp(this, 44)));
            Bitmap b = Img.get(this, c.image);
            if (b != null) iv.setImageBitmap(b);
            LinearLayout tx = new LinearLayout(this);
            tx.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams txLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            txLp.leftMargin = dp(this, 10);
            top.addView(tx, txLp);
            TextView nm = tv(this, c.name, 14, Color.rgb(0x1C, 0x1C, 0x1E), true);
            nm.setMaxLines(2);
            tx.addView(nm);
            tx.addView(tv(this, c.bank + " · " + (c.isCredit() ? "信用卡" : "借记卡"), 11, Color.rgb(0x8E, 0x8E, 0x93), false));
            TextView sc = tv(this, c.score > 0 ? String.format(java.util.Locale.US, "%.1f分", c.score) : "新卡",
                11, Color.rgb(0x0A, 0x5C, 0xD6), true);
            sc.setBackground(roundRect(Color.rgb(0xE8, 0xF1, 0xFD), 999, this));
            sc.setPadding(dp(this, 8), dp(this, 3), dp(this, 8), dp(this, 3));
            top.addView(sc);

            TextView why = tv(this, "推荐理由：" + studentReason(c), 12.5f, Color.rgb(0x3A, 0x3A, 0x3C), false);
            LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            wlp.topMargin = dp(this, 8);
            cardBox.addView(why, wlp);

            java.util.List<String> fit = studentFit(c);
            if (!fit.isEmpty()) {
                LinearLayout fitRow = new LinearLayout(this);
                fitRow.setOrientation(LinearLayout.HORIZONTAL);
                fitRow.setGravity(Gravity.CENTER_VERTICAL);
                LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                flp.topMargin = dp(this, 8);
                cardBox.addView(fitRow, flp);
                TextView fl = tv(this, "适合 ", 11.5f, Color.rgb(0x8E, 0x8E, 0x93), true);
                fitRow.addView(fl);
                for (String f : fit) fitRow.addView(chip(f, Color.rgb(0xF5, 0xF6, 0xF8), Color.rgb(0x3A, 0x3A, 0x3C)));
            }
        }

        TextView note = tv(this, "推荐理由按卡库资料整理，仅供参考", 11, Color.rgb(0x8E, 0x8E, 0x93), false);
        note.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = dp(this, 12);
        listBox.addView(note, nlp);
        restorePageScroll("student", sv);
        return page;
    }

    LinearLayout stuStatLine(int dotColor, String label, int n) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(this, 2);
        row.setLayoutParams(lp);
        row.addView(tv(this, "●", 11, dotColor, true));
        row.addView(tv(this, " " + label + "  " + n + " 张", 12, Color.rgb(0x3A, 0x3A, 0x3C), false));
        return row;
    }

    // ---------- 我的卡片（Phase 3c：卡包分析补全 + 长按拖动排序） ----------

    // 卡档次用 3a 已移植的 cardTier（按卡名粗分 3 钻石/无限级、2 白金/世界级、1 金卡级、0 普卡）
    static final String[] TIER_NAMES = {"普卡", "金卡级", "白金级", "钻石 / 无限级"};
    // 组织清单（顺序与标签同 app.js mineAnalysisHtml 的 orgs）
    static final String[][] ORG_LIST = {
        {"unionpay", "银联"}, {"mastercard", "万事达"}, {"visa", "Visa"},
        {"mastercard-nucc", "万事网联"}, {"jcb", "JCB"}, {"amex-cn", "美国运通"}
    };

    void loadMineOrder() {
        mineOrder = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs.getString("mine_order", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                String id = arr.optString(i, "");
                if (!id.isEmpty()) mineOrder.add(id);
            }
        } catch (Exception e) { mineOrder = new ArrayList<>(); }
    }

    void saveMineOrder() {
        try {
            JSONArray arr = new JSONArray();
            for (String id : mineOrder) arr.put(id);
            // Q21：顺序同为用户资产，走 commit 同步落盘（理由同 saveCustomCards）。
            prefs.edit().putString("mine_order", arr.toString()).commit();
        } catch (Throwable e) { /* 存不下就保持内存顺序 */ }
    }

    // 同 app.js applyMineOrder：已保存顺序的在前（按保存序），其余保持原相对序（List.sort 稳定）
    void applyMineOrder(List<Card> list) {
        if (mineOrder == null || mineOrder.isEmpty() || list.size() < 2) return;
        final Map<String, Integer> pos = new HashMap<>();
        for (int i = 0; i < mineOrder.size(); i++) pos.put(mineOrder.get(i), i);
        list.sort((a, b) -> Integer.compare(
            pos.containsKey(a.id) ? pos.get(a.id) : Integer.MAX_VALUE,
            pos.containsKey(b.id) ? pos.get(b.id) : Integer.MAX_VALUE));
    }

    // 卡包分析（对照 app.js mineAnalysisHtml：最高档次/组织覆盖清单/最通用一张/短板/境外能力四条进度）
    View buildMineAnalysis(final List<Card> owned) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        int[] tiers = new int[4];
        for (Card c : owned) tiers[cardTier(c)]++;
        int topTier = 0;
        for (int i = 0; i < 4; i++) if (tiers[i] > 0) topTier = i;
        List<String> hasOrgs = new ArrayList<>(), missOrgs = new ArrayList<>();
        for (String[] o : ORG_LIST) {
            boolean has = false;
            for (Card c : owned) if (o[0].equals(c.org)) { has = true; break; }
            (has ? hasOrgs : missOrgs).add(o[1]);
        }
        int nNoFtf = 0, n3ds = 0, nFx = 0, nAtm = 0;
        for (Card c : owned) {
            if (featMatch(c, "noftf")) nNoFtf++;
            if (featMatch(c, "3ds")) n3ds++;
            if (featMatch(c, "autofx")) nFx++;
            if (c.spec("境外ATM取现手续费").trim().startsWith("免发卡行")) nAtm++;
        }
        // 最通用：币种多 + 无转换费 + 有 3DS + 能网付 + 评分加权（同混合版口径）
        Card best = null; double bestS = -1;
        for (Card c : owned) {
            String cur = c.spec("币种支持");
            int curN = 1; // 同混合版：分隔符数 + 1（空值也计 1）
            for (int i = 0; i < cur.length(); i++) {
                char ch = cur.charAt(i);
                if (ch == '、' || ch == ',' || ch == '，') curN++;
            }
            double s = curN + (featMatch(c, "noftf") ? 3 : 0) + (featMatch(c, "3ds") ? 2 : 0)
                + (featMatch(c, "online") ? 2 : 0) + (c.hasScore ? c.score : 0) * 0.3;
            if (s > bestS) { bestS = s; best = c; }
        }
        List<String> gaps = new ArrayList<>();
        if (nNoFtf == 0) gaps.add("还没有无货币转换费的卡，出境刷卡每笔会被收 1%~1.5% 转换费");
        boolean hasJcb = false;
        for (Card c : owned) if ("jcb".equals(c.org)) { hasJcb = true; break; }
        if (!hasJcb) gaps.add("没有 JCB，去日本线下会弱一点");
        if (n3ds == 0) gaps.add("没有支持 3DS 的卡，部分境外网站付款可能过不了验证");
        String verdict = owned.size() >= 8 ? "卡包比较齐整了"
            : owned.size() >= 4 ? "主力框架有了，再补短板就行" : "还在起步阶段，先把主力卡配齐";

        // 深蓝英雄卡：2×2 指标格
        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable hg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
            new int[]{Color.rgb(0x14, 0x1C, 0x2C), Color.rgb(0x1D, 0x2F, 0x4D)});
        hg.setCornerRadius(dp(this, 20)); // P5：对照混合版 .mine-bento 圆角 20
        hero.setBackground(hg);
        hero.setPadding(dp(this, 14), dp(this, 14), dp(this, 14), dp(this, 14));
        wrap.addView(hero, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout row1 = new LinearLayout(this); row1.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout row2 = new LinearLayout(this); row2.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams r1lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        LinearLayout.LayoutParams r2lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        r2lp.topMargin = dp(this, 10);
        hero.addView(row1, r1lp);
        hero.addView(row2, r2lp);
        row1.addView(mineHeroTile(owned.size() + " 张卡", "我的卡包 · " + verdict, null), mineTileLp(true));
        row1.addView(mineHeroTile(TIER_NAMES[topTier], "最高档次", null), mineTileLp(false));
        row2.addView(mineHeroTile(hasOrgs.size() + " / " + ORG_LIST.length, "组织覆盖",
            hasOrgs.isEmpty() ? null : joinCn(hasOrgs)), mineTileLp(true));
        row2.addView(mineHeroTile(nNoFtf + " 张", "无转换费",
            missOrgs.isEmpty() ? "组织全覆盖了" : "还差 " + joinCn(missOrgs)), mineTileLp(false));

        // 境外能力白卡：四条进度 + 最通用 + 短板
        TextView sect = tv(this, "境外能力", 15, Color.rgb(0x1C, 0x1C, 0x1E), true);
        LinearLayout.LayoutParams sectLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sectLp.topMargin = dp(this, 16);
        wrap.addView(sect, sectLp);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(roundRect(Color.WHITE, 16, this));
        card.setPadding(dp(this, 14), dp(this, 12), dp(this, 14), dp(this, 12));
        LinearLayout.LayoutParams clp2 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp2.topMargin = dp(this, 8);
        wrap.addView(card, clp2);
        card.addView(mineProgRow("无货币转换费", nNoFtf, owned.size()));
        card.addView(mineProgRow("3DS 验证", n3ds, owned.size()));
        card.addView(mineProgRow("自动购汇", nFx, owned.size()));
        card.addView(mineProgRow("境外 ATM 免发卡行费", nAtm, owned.size()));
        if (best != null) {
            final Card bestF = best;
            LinearLayout brow = new LinearLayout(this);
            brow.setOrientation(LinearLayout.HORIZONTAL);
            brow.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams blp2 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            blp2.topMargin = dp(this, 10);
            card.addView(brow, blp2);
            brow.addView(tv(this, "最通用", 12.5f, Color.rgb(0x0A, 0x5C, 0xD6), true));
            TextView bn = tv(this, best.name, 12.5f, Color.rgb(0x1C, 0x1C, 0x1E), false);
            LinearLayout.LayoutParams bnlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            bnlp.leftMargin = dp(this, 10);
            brow.addView(bn, bnlp);
            brow.addView(tv(this, "›", 16, Color.rgb(0x8E, 0x8E, 0x93), false));
            brow.setOnClickListener(v -> openDetail(bestF));
        }
        if (!gaps.isEmpty()) {
            TextView g = tv(this, "短板：" + gaps.get(0), 12, Color.rgb(0xB0, 0x5A, 0x1B), false);
            LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            glp.topMargin = dp(this, 10);
            card.addView(g, glp);
        }
        TextView note = tv(this, "按卡库资料粗算，仅供参考", 10.5f, Color.rgb(0x8E, 0x8E, 0x93), false);
        LinearLayout.LayoutParams nlp2 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp2.topMargin = dp(this, 8);
        card.addView(note, nlp2);
        return wrap;
    }

    static String joinCn(List<String> xs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < xs.size(); i++) { if (i > 0) sb.append("、"); sb.append(xs.get(i)); }
        return sb.toString();
    }

    LinearLayout.LayoutParams mineTileLp(boolean left) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        if (left) lp.rightMargin = dp(this, 5); else lp.leftMargin = dp(this, 5);
        return lp;
    }

    View mineHeroTile(String value, String label, String sub) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        // 对照混合版 .bn-tile：玻璃底+圆角 14+内边距 13，四格同高同底，内容绝不裸贴深蓝底
        t.setBackground(roundRect(Color.argb(38, 255, 255, 255), 14, this));
        t.setPadding(dp(this, 13), dp(this, 13), dp(this, 13), dp(this, 13));
        t.setMinimumHeight(dp(this, 64));
        t.addView(tv(this, value, 23, Color.WHITE, true)); // .bn-v 1.55rem
        TextView l = tv(this, label, 10.5f, Color.argb(205, 255, 255, 255), false);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.topMargin = dp(this, 3);
        t.addView(l, llp);
        if (sub != null && !sub.isEmpty()) {
            TextView s = tv(this, sub, 10.5f, Color.argb(170, 255, 255, 255), false);
            LinearLayout.LayoutParams slp3 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            slp3.topMargin = dp(this, 2);
            t.addView(s, slp3);
        }
        return t;
    }

    View mineProgRow(String label, int n, int total) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(this, 7);
        row.setLayoutParams(rlp);
        TextView k = tv(this, label, 12.5f, Color.rgb(0x3A, 0x3A, 0x3C), false);
        row.addView(k, new LinearLayout.LayoutParams(dp(this, 118), ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackground(roundRect(Color.rgb(0xE9, 0xEE, 0xF5), 999, this));
        row.addView(bar, new LinearLayout.LayoutParams(0, dp(this, 6), 1f));
        View fill = new View(this);
        fill.setBackground(roundRect(Color.rgb(0x0A, 0x5C, 0xD6), 999, this));
        bar.addView(fill, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, (float) Math.max(0, n)));
        View rest = new View(this);
        bar.addView(rest, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, (float) Math.max(0, total - n) + 0.0001f));
        TextView v = tv(this, n + " / " + total, 11.5f, Color.rgb(0x8E, 0x8E, 0x93), true);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(dp(this, 44), ViewGroup.LayoutParams.WRAP_CONTENT);
        vlp.leftMargin = dp(this, 8);
        row.addView(v, vlp);
        return row;
    }

    View buildMinePage() {
        LinearLayout page = basePage("我的卡片");
        List<Card> mineCards = new ArrayList<>();
        for (Card c : Store.all) if (mine.contains(c.id)) mineCards.add(c);
        applyMineOrder(mineCards);

        // 整页可滚：自定义卡展开后不会把卡库收藏网格挤没（色带多时纵向滚动看）
        ScrollView sv = new ScrollView(this);
        thinScrollbar(sv);
        sv.setFillViewport(true);
        sv.setClipToPadding(false);
        mineScrollView = sv;
        if (Build.VERSION.SDK_INT >= 23) {
            sv.setOnScrollChangeListener((v, sx, sy, ox, oy) -> { mineScrollSaveY = sy; pageScrollSaveY.put("mine", sy); updateTopFabVisibility(sy); });
        }
        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.setPadding(0, dp(this, 10), 0, dockPad());
        sv.addView(inner, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        page.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        // P-deck ③：重建后恢复上次滚动位置（换序/展开收起不跳顶）
        if (mineScrollSaveY > 0) sv.post(() -> sv.scrollTo(0, mineScrollSaveY));
        if (!mineCards.isEmpty()) inner.addView(buildMineAnalysis(mineCards));
        inner.addView(buildCustomSection());

        if (mineCards.isEmpty()) {
            LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            elp.topMargin = dp(this, 12);
            inner.addView(emptyState("还没有从卡库收藏的卡\n去「全部卡片」点开任意一张，加入我的卡片"), elp);
            return page;
        }

        TextView sect = tv(this, "卡库收藏", 15, Color.rgb(0x1C, 0x1C, 0x1E), true);
        LinearLayout.LayoutParams sectLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sectLp.topMargin = dp(this, 16);
        inner.addView(sect, sectLp);
        TextView hint = tv(this, "长按任意一张卡拖动即可调整顺序，松手自动保存。", 11.5f, Color.rgb(0x8E, 0x8E, 0x93), false);
        LinearLayout.LayoutParams hlp3 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp3.topMargin = dp(this, 3);
        inner.addView(hint, hlp3);
        addMineCardRows(inner, mineCards, sv);
        return page;
    }

    // 我的卡片网格：双列；长按拖动排序（对照 app.js startMineDrag/endMineDrag 的落位换序与 450ms 点击锁）
    void addMineCardRows(LinearLayout container, final List<Card> list, final ScrollView sv) {
        final int mineCols = 2;
        container.setClipChildren(false);
        for (int i = 0; i < list.size(); i += mineCols) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setClipChildren(false);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.topMargin = dp(this, 10);
            row.setLayoutParams(rlp);
            container.addView(row);
            for (int j = 0; j < mineCols; j++) {
                if (i + j < list.size()) {
                    final Card c = list.get(i + j);
                    final int idx = i + j;
                    final View tile = cardTile(c, row, mineCols);
                    tile.setOnTouchListener(null); // Q1：我的卡片页长按拖动优先，清掉 cardTile 默认贴卡菜单触摸
                    LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
                    if (j > 0) tlp.leftMargin = dp(this, 10);
                    tile.setLayoutParams(tlp);
                    tile.setOnClickListener(v -> {
                        if (System.currentTimeMillis() - lastDragEndAt < 450) return; // 拖后点击锁，同混合版
                        openDetail(c);
                    });
                    tile.setOnLongClickListener(v -> { startMineTileDrag(tile, list, idx, sv); return true; });
                    row.addView(tile);
                } else {
                    View spacer = new View(this);
                    LinearLayout.LayoutParams slp2 = new LinearLayout.LayoutParams(0, 1, 1f);
                    if (j > 0) slp2.leftMargin = dp(this, 10);
                    spacer.setLayoutParams(slp2);
                    row.addView(spacer);
                }
            }
        }
    }

    void startMineTileDrag(final View tile, final List<Card> list, final int fromIdx, final ScrollView sv) {
        tile.setScaleX(1.04f); tile.setScaleY(1.04f); tile.setAlpha(0.92f);
        tile.setElevation(dp(this, 8));
        if (tile.getParent() instanceof ViewGroup) ((ViewGroup) tile.getParent()).bringChildToFront(tile);
        if (sv != null) sv.requestDisallowInterceptTouchEvent(true);
        tile.setOnTouchListener(new View.OnTouchListener() {
            float downX = -1, downY = -1;
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX(); downY = e.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        if (downX < 0) { downX = e.getRawX(); downY = e.getRawY(); }
                        v.setTranslationX(e.getRawX() - downX);
                        v.setTranslationY(e.getRawY() - downY);
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL: {
                        float dx = downX < 0 ? 0 : e.getRawX() - downX;
                        float dy = downY < 0 ? 0 : e.getRawY() - downY;
                        finishMineTileDrag(tile, list, fromIdx, dx, dy, sv);
                        return true;
                    }
                }
                return true;
            }
        });
    }

    void finishMineTileDrag(View tile, List<Card> list, int fromIdx, float dx, float dy, ScrollView sv) {
        tile.setOnTouchListener(null);
        tile.setTranslationX(0); tile.setTranslationY(0);
        tile.setScaleX(1f); tile.setScaleY(1f); tile.setAlpha(1f);
        tile.setElevation(0);
        if (sv != null) sv.requestDisallowInterceptTouchEvent(false);
        lastDragEndAt = System.currentTimeMillis();
        final int mineCols = 2;
        int tw = tile.getWidth(), th = tile.getHeight();
        int rowH = th + dp(this, 10), colW = tw + dp(this, 10);
        int dRow = rowH > 0 ? Math.round(dy / (float) rowH) : 0;
        int dCol = colW > 0 ? Math.round(dx / (float) colW) : 0;
        int rows = (list.size() + mineCols - 1) / mineCols;
        int toRow = Math.max(0, Math.min(fromIdx / mineCols + dRow, rows - 1));
        int toCol = Math.max(0, Math.min(fromIdx % mineCols + dCol, mineCols - 1));
        int toIdx = Math.max(0, Math.min(toRow * mineCols + toCol, list.size() - 1));
        if (toIdx != fromIdx) {
            Card moved = list.remove(fromIdx);
            list.add(toIdx, moved);
            mineOrder = new ArrayList<>();
            for (Card c : list) mineOrder.add(c.id);
            saveMineOrder();
            showFloatToast("顺序已保存");
        }
        showTab("mine");
    }

    // ---------- 自定义卡片（Phase 3b） ----------
    View buildCustomSection() {
        LinearLayout sec = new LinearLayout(this);
        sec.setOrientation(LinearLayout.VERTICAL);
        sec.setBackground(roundRect(Color.WHITE, 16, this));
        sec.setPadding(dp(this, 14), dp(this, 12), dp(this, 14), dp(this, 12));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(this, 12);
        sec.setLayoutParams(slp);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        sec.addView(head);
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        head.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        info.addView(tv(this, "自定义卡片", 15, Color.rgb(0x1C, 0x1C, 0x1E), true));
        String subTxt = customCards.isEmpty()
            ? "还没有自定义卡片"
            : customCards.size() + " 张 · " + (customOpen ? "点开收起" : "点开展开");
        TextView sub = tv(this, subTxt, 11.5f, Color.rgb(0x8E, 0x8E, 0x93), false);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(this, 2);
        info.addView(sub, subLp);
        Button addBtn = new Button(this);
        addBtn.setText("＋ 添加"); addBtn.setTextSize(12.5f); addBtn.setAllCaps(false);
        addBtn.setTextColor(Color.WHITE);
        addBtn.setBackground(roundRect(Color.rgb(0x0A, 0x5C, 0xD6), 999, this));
        addBtn.setOnClickListener(v -> { haptic(); openCustomForm(null); });
        head.addView(addBtn, new LinearLayout.LayoutParams(dp(this, 76), dp(this, 36)));
        TextView arrow = tv(this, customCards.isEmpty() ? "" : (customOpen ? "收起 ‹" : "展开 ›"), 12, Color.rgb(0x0A, 0x5C, 0xD6), true);
        LinearLayout.LayoutParams alp2 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp2.leftMargin = dp(this, 8);
        head.addView(arrow, alp2);
        if (!customCards.isEmpty()) {
            head.setOnClickListener(v -> { haptic(); customOpen = !customOpen; refreshMineKeepScroll(); });
        }

        if (customCards.isEmpty()) {
            TextView hint = tv(this, "卡库里没有的卡可以自己记一张：填名字、银行、挑个颜色、写备注。", 12, Color.rgb(0x8E, 0x8E, 0x93), false);
            LinearLayout.LayoutParams hlp2 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            hlp2.topMargin = dp(this, 8);
            sec.addView(hint, hlp2);
            return sec;
        }

        if (!customOpen) {
            // P-deck ⑤ 收起态：最新一张完整压在最上（名/行/组织可见），其余只在下方露一道边
            int show = Math.min(3, customCards.size());
            FrameLayout deckFrame = new FrameLayout(this);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(this, 70));
            dlp.topMargin = dp(this, 10);
            sec.addView(deckFrame, dlp);
            // 先加旧卡垫底（只露边），最后加最新卡上盖
            for (int depth = show - 1; depth >= 0; depth--) {
                CustomCard c = customCards.get(customCards.size() - 1 - depth);
                boolean isTop = depth == 0;
                LinearLayout layer = new LinearLayout(this);
                layer.setOrientation(LinearLayout.HORIZONTAL);
                layer.setGravity(Gravity.CENTER_VERTICAL);
                GradientDrawable lg = customGradient(c.style);
                layer.setBackground(lg);
                layer.setPadding(dp(this, 12), dp(this, 8), dp(this, 12), dp(this, 8));
                if (Build.VERSION.SDK_INT >= 21) layer.setElevation(dp(this, isTop ? 3 : 1));
                FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(this, 52));
                flp.topMargin = dp(this, depth * 7);
                flp.leftMargin = dp(this, depth * 8);
                flp.rightMargin = dp(this, depth * 8);
                deckFrame.addView(layer, flp);
                if (isTop) {
                    TextView nm = tv(this, c.name, 13.5f, Color.WHITE, true);
                    nm.setMaxLines(1);
                    nm.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    layer.addView(nm, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                    String meta = (c.bank == null ? "" : c.bank) + (c.org != null && !c.org.isEmpty() ? " · " + c.org : "");
                    TextView mt = tv(this, meta, 11, Color.argb(215, 255, 255, 255), false);
                    mt.setMaxLines(1);
                    mt.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    mlp.leftMargin = dp(this, 8);
                    layer.addView(mt, mlp);
                }
            }
            return sec;
        }

        // P-deck 展开态：紧凑色带整叠（无缝衔接）+ 长按拖动换序，小圆钮弱化
        TextView dragHint = tv(this, "长按色带可拖动排序 · 点色带看详情", 11, Color.rgb(0x8E, 0x8E, 0x93), false);
        LinearLayout.LayoutParams dhLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dhLp.topMargin = dp(this, 8);
        sec.addView(dragHint, dhLp);
        LinearLayout tilesBox = new LinearLayout(this);
        tilesBox.setOrientation(LinearLayout.VERTICAL);
        tilesBox.setBackground(roundRect(Color.WHITE, 14, this));
        if (Build.VERSION.SDK_INT >= 21) tilesBox.setElevation(dp(this, 2));
        // 不裁子视图：拖动时色带要能滑出整叠边界跟手（首尾圆角由色带自身渐变给出）
        tilesBox.setClipChildren(false);
        tilesBox.setClipToPadding(false);
        sec.setClipChildren(false);
        LinearLayout.LayoutParams boxLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        boxLp.topMargin = dp(this, 6);
        sec.addView(tilesBox, boxLp);
        for (int i = 0; i < customCards.size(); i++) {
            final CustomCard c = customCards.get(i);
            final int idx = i;
            final int nextStyle = (i + 1 < customCards.size()) ? customCards.get(i + 1).style : -1;
            LinearLayout tile = new LinearLayout(this);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setBackground(customBlendedGradient(c.style, nextStyle, i == 0, i == customCards.size() - 1));
            tile.setPadding(dp(this, 12), dp(this, 8), dp(this, 12), dp(this, 8));
            tilesBox.addView(tile, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            LinearLayout topRow = new LinearLayout(this);
            topRow.setOrientation(LinearLayout.HORIZONTAL);
            topRow.setGravity(Gravity.CENTER_VERTICAL);
            tile.addView(topRow);
            TextView idxTv = tv(this, String.valueOf(i + 1), 11, Color.WHITE, true);
            idxTv.setBackground(roundRect(Color.argb(70, 255, 255, 255), 999, this));
            idxTv.setGravity(Gravity.CENTER);
            topRow.addView(idxTv, new LinearLayout.LayoutParams(dp(this, 22), dp(this, 22)));
            LinearLayout tx = new LinearLayout(this);
            tx.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams txLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            txLp.leftMargin = dp(this, 8);
            topRow.addView(tx, txLp);
            TextView nm = tv(this, c.name, 14, Color.WHITE, true);
            nm.setMaxLines(1);
            nm.setEllipsize(android.text.TextUtils.TruncateAt.END);
            tx.addView(nm);
            String meta = (c.bank == null || c.bank.isEmpty() ? "未填发卡行" : c.bank)
                + (c.org != null && !c.org.isEmpty() ? " · " + c.org : "");
            TextView mtv = tv(this, meta, 11, Color.argb(215, 255, 255, 255), false);
            mtv.setMaxLines(1);
            mtv.setEllipsize(android.text.TextUtils.TruncateAt.END);
            tx.addView(mtv);
            // 小圆钮（弱化）：上/下移，拖动为主、点按为辅
            topRow.addView(customMoveBtn("↑", i == 0, v -> moveCustom(idx, -1)));
            topRow.addView(customMoveBtn("↓", i == customCards.size() - 1, v -> moveCustom(idx, 1)));
            if (c.note != null && !c.note.isEmpty()) {
                TextView nt = tv(this, c.note, 11.5f, Color.argb(225, 255, 255, 255), false);
                nt.setMaxLines(2);
                nt.setEllipsize(android.text.TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams nlp2 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                nlp2.topMargin = dp(this, 4);
                nlp2.leftMargin = dp(this, 30);
                tile.addView(nt, nlp2);
            }
            LinearLayout acts = new LinearLayout(this);
            acts.setOrientation(LinearLayout.HORIZONTAL);
            acts.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams actLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            actLp.topMargin = dp(this, 6);
            actLp.leftMargin = dp(this, 30);
            tile.addView(acts, actLp);
            acts.addView(customActBtn("编辑", false, v -> openCustomForm(c)));
            acts.addView(customActBtn("删除", false, v -> confirmDeleteCustom(c)));
            View spacer = new View(this);
            acts.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
            TextView detailHint = tv(this, "详情 ›", 11, Color.argb(220, 255, 255, 255), false);
            acts.addView(detailHint);
            tile.setOnClickListener(v -> {
                if (System.currentTimeMillis() - lastDragEndAt < 450) return;
                openCustomDetail(c);
            });
            tile.setOnLongClickListener(v -> { startCustomDrag(tile, idx); return true; });
        }
        return sec;
    }

    // P-deck 小圆钮：28dp 圆，半透白底，禁用态更淡
    TextView customMoveBtn(String label, boolean disabled, View.OnClickListener onClick) {
        TextView b = tv(this, label, 13, disabled ? Color.argb(130, 255, 255, 255) : Color.WHITE, true);
        b.setGravity(Gravity.CENTER);
        b.setBackground(roundRect(Color.argb(disabled ? 28 : 52, 255, 255, 255), 999, this));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(this, 28), dp(this, 28));
        lp.leftMargin = dp(this, 6);
        b.setLayoutParams(lp);
        if (!disabled) b.setOnClickListener(onClick);
        return b;
    }

    // P-deck ① 长按拖动：跟手平移+放大，松手按位移换序并保存（对照混合版 startTileDrag）
    void startCustomDrag(final View tile, final int fromIdx) {
        haptic();
        tile.setScaleX(1.02f); tile.setScaleY(1.02f); tile.setAlpha(0.95f);
        if (Build.VERSION.SDK_INT >= 21) tile.setElevation(dp(this, 10));
        if (tile.getParent() instanceof ViewGroup) ((ViewGroup) tile.getParent()).bringChildToFront(tile);
        if (mineScrollView != null) mineScrollView.requestDisallowInterceptTouchEvent(true);
        tile.setOnTouchListener(new View.OnTouchListener() {
            float downY = -1;
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downY = e.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (downY < 0) downY = e.getRawY();
                        v.setTranslationY(e.getRawY() - downY);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL: {
                        float dy = downY < 0 ? 0 : e.getRawY() - downY;
                        finishCustomDrag(tile, fromIdx, dy);
                        return true;
                    }
                }
                return true;
            }
        });
    }

    void finishCustomDrag(View tile, int fromIdx, float dy) {
        tile.setOnTouchListener(null);
        tile.setTranslationY(0);
        tile.setScaleX(1f); tile.setScaleY(1f); tile.setAlpha(1f);
        if (Build.VERSION.SDK_INT >= 21) tile.setElevation(0);
        if (mineScrollView != null) mineScrollView.requestDisallowInterceptTouchEvent(false);
        lastDragEndAt = System.currentTimeMillis();
        int th = tile.getHeight();
        int dRow = th > 0 ? Math.round(dy / (float) th) : 0;
        int toIdx = Math.max(0, Math.min(fromIdx + dRow, customCards.size() - 1));
        if (toIdx != fromIdx) {
            CustomCard moved = customCards.remove(fromIdx);
            customCards.add(toIdx, moved);
            saveCustomCards();
            showFloatToast("顺序已保存");
        }
        refreshMineKeepScroll();
    }

    Button customActBtn(String label, boolean disabled, View.OnClickListener onClick) {
        Button b = new Button(this);
        b.setText(label); b.setTextSize(11f); b.setAllCaps(false);
        b.setMinWidth(0); b.setMinHeight(0);
        b.setPadding(dp(this, 8), dp(this, 3), dp(this, 8), dp(this, 3));
        b.setTextColor(disabled ? Color.argb(140, 255, 255, 255) : Color.WHITE);
        b.setBackground(roundRect(Color.argb(disabled ? 30 : 48, 255, 255, 255), 999, this));
        b.setEnabled(!disabled);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(this, 6);
        b.setLayoutParams(lp);
        if (!disabled) b.setOnClickListener(onClick);
        return b;
    }

    void moveCustom(int idx, int dir) {
        int j = idx + dir;
        if (idx < 0 || j < 0 || j >= customCards.size()) return;
        CustomCard t = customCards.get(idx);
        customCards.set(idx, customCards.get(j));
        customCards.set(j, t);
        saveCustomCards();
        refreshMineKeepScroll();
    }

    void confirmDeleteCustom(final CustomCard c) {
        new AlertDialog.Builder(this)
            .setTitle("删除这张自定义卡？")
            .setMessage("「" + c.name + "」删了就没了，备注也会一起清掉。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除", (d, w) -> {
                final int idx = customCards.indexOf(c);
                customCards.remove(c);
                saveCustomCards();
                refreshMineKeepScroll();
                showFloatToast("已删除这张自定义卡", "撤销", () -> {
                    int at = idx < 0 ? customCards.size() : Math.min(idx, customCards.size());
                    customCards.add(at, c);
                    saveCustomCards();
                    refreshMineKeepScroll();
                    showFloatToast("已恢复「" + c.name + "」");
                });
            })
            .show();
    }

    void openCustomDetail(final CustomCard c) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(this, 18), dp(this, 6), dp(this, 18), dp(this, 4));
        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setBackground(customGradient(c.style));
        hero.setPadding(dp(this, 14), dp(this, 12), dp(this, 14), dp(this, 12));
        box.addView(hero);
        hero.addView(tv(this, c.name, 16, Color.WHITE, true));
        hero.addView(tv(this, (c.bank == null || c.bank.isEmpty() ? "未填发卡行" : c.bank)
            + (c.org != null && !c.org.isEmpty() ? " · " + c.org : ""), 12, Color.argb(220, 255, 255, 255), false));
        box.addView(customDetailRow("卡片名称", c.name));
        box.addView(customDetailRow("发卡银行", c.bank == null || c.bank.isEmpty() ? "—" : c.bank));
        box.addView(customDetailRow("卡组织", c.org == null || c.org.isEmpty() ? "—" : c.org));
        if (c.note != null && !c.note.isEmpty()) box.addView(customDetailRow("备注", c.note));

        final AlertDialog[] holder = new AlertDialog[1];
        if (c.bank != null && !c.bank.isEmpty()) {
            TextView find = tv(this, "在卡库里搜「" + c.bank + "」 ›", 13, Color.rgb(0x0A, 0x5C, 0xD6), true);
            find.setPadding(0, dp(this, 10), 0, dp(this, 8));
            find.setOnClickListener(v -> {
                if (holder[0] != null) holder[0].dismiss();
                query = c.bank;
                resetPageScroll("home", homeScroll); // 这是去看搜索结果，明确回顶（P-keepscroll 的例外）
                showTab("home");
                if (searchBox != null) searchBox.setText(c.bank);
            });
            box.addView(find);
        }
        AlertDialog dlg = new AlertDialog.Builder(this)
            .setView(box)
            .setNegativeButton("关闭", null)
            .setNeutralButton("删除", (d, w) -> confirmDeleteCustom(c))
            .setPositiveButton("编辑", (d, w) -> openCustomForm(c))
            .create();
        holder[0] = dlg;
        dlg.show();
    }

    View customDetailRow(String k, String v) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(this, 7), 0, dp(this, 7));
        row.addView(tv(this, k, 12.5f, Color.rgb(0x8E, 0x8E, 0x93), false), new LinearLayout.LayoutParams(dp(this, 76), ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(tv(this, v == null ? "" : v, 13, Color.rgb(0x1C, 0x1C, 0x1E), false), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    EditText customInput(String hint, String value, boolean multi) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value == null ? "" : value);
        e.setTextSize(14);
        e.setSingleLine(!multi);
        if (multi) { e.setMinLines(2); e.setGravity(Gravity.TOP); }
        e.setBackground(roundRect(Color.rgb(0xF5, 0xF6, 0xF8), 10, this));
        e.setPadding(dp(this, 12), dp(this, 10), dp(this, 12), dp(this, 10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(this, 8);
        e.setLayoutParams(lp);
        return e;
    }

    TextView customFormLabel(String s) {
        TextView t = tv(this, s, 12, Color.rgb(0x8E, 0x8E, 0x93), true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(this, 12);
        t.setLayoutParams(lp);
        return t;
    }

    void openCustomForm(final CustomCard edit) {
        final boolean isNew = edit == null;
        final CustomCard draft = new CustomCard();
        if (!isNew) {
            draft.id = edit.id; draft.name = edit.name; draft.bank = edit.bank;
            draft.org = edit.org; draft.note = edit.note; draft.style = edit.style;
        } else {
            draft.id = null; draft.name = ""; draft.bank = ""; draft.org = ""; draft.note = ""; draft.style = 0;
        }
        final String[] orgSel = {draft.org == null ? "" : draft.org};
        final int[] styleSel = {draft.style};

        ScrollView sv = new ScrollView(this);
        thinScrollbar(sv);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(this, 18), dp(this, 14), dp(this, 18), dp(this, 18));
        sv.addView(form);
        form.addView(tv(this, isNew ? "添加自定义卡片" : "编辑自定义卡片", 17, Color.rgb(0x1C, 0x1C, 0x1E), true));
        form.addView(customFormLabel("卡片名称 *"));
        final EditText inName = customInput("例如：我的旅行卡", draft.name, false);
        form.addView(inName);
        form.addView(customFormLabel("发卡银行"));
        final EditText inBank = customInput("例如：招商银行（可不填）", draft.bank, false);
        form.addView(inBank);
        form.addView(customFormLabel("卡组织"));
        final LinearLayout orgRow = new LinearLayout(this);
        orgRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams orgLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        orgLp.topMargin = dp(this, 8);
        form.addView(orgRow, orgLp);
        final java.util.List<Button> orgBtns = new ArrayList<>();
        Runnable paintOrgs = () -> {
            for (Button b : orgBtns) {
                boolean on = b.getText().toString().equals(orgSel[0]);
                b.setTextColor(on ? Color.WHITE : Color.rgb(0x3A, 0x3A, 0x3C));
                b.setBackground(roundRect(on ? Color.rgb(0x0A, 0x5C, 0xD6) : Color.rgb(0xF5, 0xF6, 0xF8), 999, MainActivity.this));
            }
        };
        for (final String o : CUSTOM_ORGS) {
            Button b = new Button(this);
            b.setText(o); b.setTextSize(11.5f); b.setAllCaps(false);
            b.setMinWidth(0); b.setMinHeight(0);
            b.setPadding(dp(this, 10), dp(this, 6), dp(this, 10), dp(this, 6));
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            blp.rightMargin = dp(this, 6);
            b.setLayoutParams(blp);
            b.setOnClickListener(v -> { haptic(); orgSel[0] = o.equals(orgSel[0]) ? "" : o; paintOrgs.run(); });
            orgBtns.add(b);
            orgRow.addView(b);
        }
        paintOrgs.run();
        form.addView(customFormLabel("卡面颜色"));
        final LinearLayout styleRow = new LinearLayout(this);
        styleRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams styleLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        styleLp.topMargin = dp(this, 8);
        form.addView(styleRow, styleLp);
        final java.util.List<Button> styleBtns = new ArrayList<>();
        Runnable paintStyles = () -> {
            for (int i = 0; i < styleBtns.size(); i++) {
                Button b = styleBtns.get(i);
                boolean on = i == styleSel[0];
                b.setText(on ? "✓" : "");
                b.setTextColor(Color.WHITE);
                GradientDrawable g = customGradient(i);
                if (on) g.setStroke(dp(MainActivity.this, 2), Color.rgb(0x1C, 0x1C, 0x1E));
                b.setBackground(g);
            }
        };
        for (int i = 0; i < CUSTOM_STYLES.length; i++) {
            final int si = i;
            Button b = new Button(this);
            b.setTextSize(13); b.setAllCaps(false);
            b.setMinWidth(0); b.setMinHeight(0);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(this, 44), dp(this, 32));
            blp.rightMargin = dp(this, 7);
            b.setLayoutParams(blp);
            b.setOnClickListener(v -> { haptic(); styleSel[0] = si; paintStyles.run(); });
            styleBtns.add(b);
            styleRow.addView(b);
        }
        paintStyles.run();
        form.addView(customFormLabel("备注"));
        final EditText inNote = customInput("例如：额度、到期日、主要用途（可不填）", draft.note, true);
        form.addView(inNote);

        final Dialog dlg = new Dialog(this);
        dlg.setContentView(sv);
        if (dlg.getWindow() != null) {
            dlg.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            dlg.getWindow().setBackgroundDrawable(roundRect(Color.WHITE, 18, this));
        }
        customDialog = dlg;

        LinearLayout acts = new LinearLayout(this);
        acts.setOrientation(LinearLayout.HORIZONTAL);
        acts.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams actLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actLp.topMargin = dp(this, 16);
        form.addView(acts, actLp);
        Button cancel = new Button(this);
        cancel.setText("取消"); cancel.setTextSize(14); cancel.setAllCaps(false);
        cancel.setBackground(roundRect(Color.rgb(0xF5, 0xF6, 0xF8), 12, this));
        cancel.setOnClickListener(v -> { haptic(); customDialog = null; dlg.dismiss(); });
        acts.addView(cancel, new LinearLayout.LayoutParams(0, dp(this, 46), 1f));
        Button save = new Button(this);
        save.setText("保存"); save.setTextSize(14); save.setAllCaps(false);
        save.setTextColor(Color.WHITE);
        save.setBackground(roundRect(Color.rgb(0x0A, 0x5C, 0xD6), 12, this));
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(0, dp(this, 46), 1f);
        saveLp.leftMargin = dp(this, 10);
        acts.addView(save, saveLp);
        save.setOnClickListener(v -> {
            haptic();
            String name = inName.getText().toString().trim();
            if (name.isEmpty()) { showFloatToast("请填写卡片名称"); inName.requestFocus(); return; }
            if (isNew) {
                CustomCard c = new CustomCard();
                c.id = "custom-" + System.currentTimeMillis();
                c.name = name;
                c.bank = inBank.getText().toString().trim();
                c.org = orgSel[0];
                c.note = inNote.getText().toString().trim();
                c.style = styleSel[0];
                customCards.add(c);
                customOpen = true;
                showFloatToast("已添加「" + name + "」");
            } else {
                edit.name = name;
                edit.bank = inBank.getText().toString().trim();
                edit.org = orgSel[0];
                edit.note = inNote.getText().toString().trim();
                edit.style = styleSel[0];
                showFloatToast("已保存「" + name + "」");
            }
            saveCustomCards();
            customDialog = null;
            dlg.dismiss();
            refreshMineKeepScroll();
        });
        dlg.setOnDismissListener(d -> { if (customDialog == dlg) customDialog = null; });
        dlg.show();
    }

    // ---------- 资讯 / 设置 ----------
    // ---------- 数据 OTA（Phase 4c，对照 app.js checkDataUpdate/DATA_URLS） ----------
    boolean otaFetchStarted = false;

    // 启动自动查一次；设置页手动查 manual=true 给 toast 反馈。双线：jsDelivr 优先，失败回落 raw。
    void checkDataUpdate(final boolean manual) {
        if (!manual && otaFetchStarted) return;
        otaFetchStarted = true;
        final String[] urls = {
            "https://cdn.jsdelivr.net/gh/dimlogue/cardbox-data@main/cards.json",
            "https://raw.githubusercontent.com/dimlogue/cardbox-data/main/cards.json"
        };
        new Thread(() -> {
            for (String u : urls) {
                try {
                    HttpURLConnection conn = (HttpURLConnection) new URL(u + "?t=" + System.currentTimeMillis()).openConnection();
                    conn.setConnectTimeout(8000); conn.setReadTimeout(8000);
                    conn.setRequestProperty("Cache-Control", "no-cache");
                    if (conn.getResponseCode() != 200) { conn.disconnect(); continue; }
                    String json = Store.readAll(conn.getInputStream());
                    conn.disconnect();
                    int remoteVer = Store.versionOf(json);
                    if (remoteVer <= Store.dataVersion) {
                        if (manual) runOnUiThread(() -> showFloatToast("已是最新数据（v" + Store.dataVersion + "）"));
                        return;
                    }
                    // 先在临时解析校验卡数>0 再落盘，避免把坏数据写进 filesDir
                    try {
                        JSONObject probe = new JSONObject(json);
                        JSONArray pa = probe.getJSONArray("cards");
                        if (pa == null || pa.length() == 0) continue;
                    } catch (Exception e) { continue; }
                    try {
                        FileOutputStream fos = new FileOutputStream(new File(getFilesDir(), "cards-ota.json"));
                        fos.write(json.getBytes("UTF-8")); fos.close();
                    } catch (Exception e) { /* 落盘失败也继续用本次拉到的数据刷新界面 */ }
                    final boolean ok = Store.parseInto(json);
                    if (!ok) continue;
                    runOnUiThread(() -> {
                        showFloatToast("卡片数据已更新到 v" + Store.dataVersion + "（" + Store.all.size() + " 张）");
                        pages.clear(); // 页面缓存一律作废，下次进页用新数据重建
                        if (detailCard == null) rebuildPages(); // 正看详情时不打断，关掉详情自然用新数据
                    });
                    return;
                } catch (Exception e) { /* 换下一条线路 */ }
            }
            if (manual) runOnUiThread(() -> showFloatToast("检查更新失败，请检查网络"));
        }).start();
    }

    // ---------- 资讯（Phase 2c，对照 app.js renderNews/loadNews/checkNewsUpdate） ----------
    static class NewsItem {
        String id, title, tag, date, source, summary, url;
    }
    List<NewsItem> newsItems = null; // null = 还没读；空 = 读过但确实没有
    java.util.Set<String> newsOpen = new java.util.HashSet<>(); // 展开的资讯 id（点开看详情）
    boolean newsFetchStarted = false;
    LinearLayout newsListBox = null;
    TextView newsMeta = null;

    List<NewsItem> parseNews(String json) {
        try {
            JSONObject root = new JSONObject(json);
            JSONArray arr = root.getJSONArray("items");
            List<NewsItem> out = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                NewsItem n = new NewsItem();
                n.id = o.optString("id"); n.title = o.optString("title");
                n.tag = o.optString("tag"); n.date = o.optString("date");
                n.source = o.optString("source"); n.summary = o.optString("summary");
                n.url = o.optString("url");
                out.add(n);
            }
            return out;
        } catch (Exception e) { return null; }
    }

    String readAssetText(String path) {
        try {
            InputStream in = getAssets().open(path);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Exception e) { return null; }
    }

    void ensureNews() {
        if (newsItems != null) return;
        // 先缓存（上次联网拿到的）没有才用内置种子，保证断网/首次都能看
        String cached = prefs == null ? null : prefs.getString("news_cache", null);
        List<NewsItem> c = cached == null ? null : parseNews(cached);
        if (c != null && !c.isEmpty()) { newsItems = c; return; }
        String seed = readAssetText("data/news.json");
        List<NewsItem> s = seed == null ? null : parseNews(seed);
        newsItems = s == null ? new ArrayList<NewsItem>() : s;
    }

    void fetchNewsUpdate() {
        if (newsFetchStarted) return;
        newsFetchStarted = true;
        final String[] urls = {
            "https://cdn.jsdelivr.net/gh/dimlogue/cardbox-data@main/news.json",
            "https://raw.githubusercontent.com/dimlogue/cardbox-data/main/news.json"
        };
        new Thread(() -> {
            for (String u : urls) {
                try {
                    HttpURLConnection conn = (HttpURLConnection) new URL(u + "?t=" + System.currentTimeMillis()).openConnection();
                    conn.setConnectTimeout(6000); conn.setReadTimeout(6000);
                    conn.setRequestProperty("Cache-Control", "no-cache");
                    if (conn.getResponseCode() != 200) { conn.disconnect(); continue; }
                    InputStream in = conn.getInputStream();
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192]; int n;
                    while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                    in.close(); conn.disconnect();
                    String json = new String(bos.toByteArray(), "UTF-8");
                    List<NewsItem> fresh = parseNews(json);
                    if (fresh == null || fresh.isEmpty()) continue;
                    boolean changed = newsItems == null || newsItems.size() != fresh.size()
                        || (fresh.size() > 0 && newsItems.size() > 0 && !fresh.get(0).id.equals(newsItems.get(0).id));
                    if (prefs != null) prefs.edit().putString("news_cache", json).apply();
                    newsItems = fresh;
                    if (changed) runOnUiThread(() -> { if ("news".equals(tab) && newsListBox != null) renderNews(); });
                    return;
                } catch (Exception e) { /* 换下一条线路，失败就保持内置/缓存 */ }
            }
        }).start();
    }

    void renderNews() {
        if (newsListBox == null) return;
        // P-keepscroll：展开/收起一条资讯会整表重绘，先记位置、重绘后恢复，不跳顶
        final int keepY = savedPageScrollY("news", newsScroll);
        newsListBox.removeAllViews();
        if (newsItems == null || newsItems.isEmpty()) {
            newsListBox.addView(emptyState("暂时还没有资讯\n过段时间再来看看"));
            return;
        }
        if (newsMeta != null) newsMeta.setText("共 " + newsItems.size() + " 条 · 公开信息整理，仅供参考");
        for (final NewsItem n : newsItems) {
            final boolean open = newsOpen.contains(n.id);
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackground(rippleBg(Color.WHITE, 16));
            card.setClipToOutline(true);
            card.setPadding(dp(this, 14), dp(this, 12), dp(this, 14), dp(this, 12));
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.topMargin = dp(this, 10);
            newsListBox.addView(card, clp);

            LinearLayout meta = new LinearLayout(this);
            meta.setOrientation(LinearLayout.HORIZONTAL);
            meta.setGravity(Gravity.CENTER_VERTICAL);
            card.addView(meta);
            String tag = n.tag == null || n.tag.isEmpty() ? "资讯" : n.tag;
            TextView tg = tv(this, tag, 10.5f, Color.rgb(0x0A, 0x5C, 0xD6), true);
            tg.setBackground(roundRect(Color.rgb(0xE8, 0xF1, 0xFD), 999, this));
            tg.setPadding(dp(this, 8), dp(this, 3), dp(this, 8), dp(this, 3));
            meta.addView(tg);
            TextView dt = tv(this, n.date == null ? "" : n.date, 11, Color.rgb(0x8E, 0x8E, 0x93), false);
            LinearLayout.LayoutParams dtlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            dtlp.leftMargin = dp(this, 8);
            meta.addView(dt, dtlp);
            TextView sr = tv(this, n.source == null ? "" : n.source, 11, Color.rgb(0x8E, 0x8E, 0x93), false);
            LinearLayout.LayoutParams srlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            srlp.leftMargin = dp(this, 6);
            meta.addView(sr, srlp);
            TextView arrow = tv(this, open ? "收起 ‹" : "展开 ›", 11, Color.rgb(0x0A, 0x5C, 0xD6), true);
            arrow.setGravity(Gravity.RIGHT);
            meta.addView(arrow, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView ttl = tv(this, n.title == null ? "" : n.title, 15, Color.rgb(0x1C, 0x1C, 0x1E), true);
            LinearLayout.LayoutParams ttlp2 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            ttlp2.topMargin = dp(this, 7);
            card.addView(ttl, ttlp2);
            if (!open) ttl.setMaxLines(2);

            if (n.summary != null && !n.summary.isEmpty()) {
                TextView sm = tv(this, n.summary, 13, Color.rgb(0x3A, 0x3A, 0x3C), false);
                sm.setLineSpacing(dp(this, 2), 1f);
                LinearLayout.LayoutParams smlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                smlp.topMargin = dp(this, 5);
                card.addView(sm, smlp);
                if (!open) { sm.setMaxLines(2); sm.setEllipsize(android.text.TextUtils.TruncateAt.END); }
            }

            if (open) {
                LinearLayout det = new LinearLayout(this);
                det.setOrientation(LinearLayout.VERTICAL);
                det.setBackground(roundRect(Color.rgb(0xF5, 0xF6, 0xF8), 10, this));
                det.setPadding(dp(this, 10), dp(this, 8), dp(this, 10), dp(this, 8));
                LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                dlp.topMargin = dp(this, 9);
                card.addView(det, dlp);
                det.addView(tv(this, "来源：" + (n.source == null || n.source.isEmpty() ? "—" : n.source)
                    + " · 日期：" + (n.date == null || n.date.isEmpty() ? "—" : n.date), 11.5f, Color.rgb(0x3A, 0x3A, 0x3C), false));
                if (n.url != null && !n.url.isEmpty()) {
                    TextView link = tv(this, "查看原文 ›", 13, Color.rgb(0x0A, 0x5C, 0xD6), true);
                    LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    llp.topMargin = dp(this, 6);
                    det.addView(link, llp);
                    link.setOnClickListener(v -> {
                        haptic();
                        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(n.url))); }
                        catch (Exception e) { showFloatToast("打不开这个链接"); }
                    });
                } else {
                    det.addView(tv(this, "暂无原文链接", 11.5f, Color.rgb(0x8E, 0x8E, 0x93), false));
                }
            }

            card.setOnClickListener(v -> {
                haptic();
                if (newsOpen.contains(n.id)) newsOpen.remove(n.id); else newsOpen.add(n.id);
                renderNews();
            });
        }
        if (keepY > 0 && newsScroll != null) newsScroll.post(() -> newsScroll.scrollTo(0, keepY));
    }

    View buildNewsPage() {
        ensureNews();
        fetchNewsUpdate();
        LinearLayout page = basePage("卡片资讯");
        TextView sub = tv(this, "新卡发布、权益调整、停发换卡——公开信息整理，仅供参考", 12.5f, Color.rgb(0x8E, 0x8E, 0x93), false);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(this, 4);
        page.addView(sub, subLp);
        newsMeta = tv(this, "", 11.5f, Color.rgb(0x8E, 0x8E, 0x93), false);
        LinearLayout.LayoutParams mLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mLp.topMargin = dp(this, 6);
        page.addView(newsMeta, mLp);

        ScrollView sv = new ScrollView(this);
        thinScrollbar(sv);
        sv.setClipToPadding(false);
        newsScroll = sv;
        if (Build.VERSION.SDK_INT >= 23) sv.setOnScrollChangeListener((v, sx, sy, ox, oy) -> { pageScrollSaveY.put("news", sy); updateTopFabVisibility(sy); });
        newsListBox = new LinearLayout(this);
        newsListBox.setOrientation(LinearLayout.VERTICAL);
        newsListBox.setPadding(0, dp(this, 2), 0, dockPad());
        sv.addView(newsListBox);
        page.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        renderNews();
        restorePageScroll("news", sv);
        return page;
    }

    // ---------- P-about 关于卡盒（对照混合版 aboutDlg：图标+名称+版本/简介/数据来源/赞助展开） ----------
    // 细线咖啡杯图标（Canvas 线条，对照混合版 sponsor SVG，禁用 emoji）
    // Q26：关于窗卡盒标记——双卡叠放的细线自绘（替代带黑边的裁切位图），24 网格与全 App 图标同语言
    class CardMarkIconView extends View {
        CardMarkIconView(Context c) { super(c); }
        @Override protected void onDraw(Canvas cv) {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            p.setStrokeWidth(dp(getContext(), 1.7f));
            float sx = getWidth() / 24f, sy = getHeight() / 24f;
            // 后卡：左上探出的一张，只描上/左边（照原图叠放关系）
            p.setColor(Color.argb(150, 0x0A, 0x5C, 0xD6));
            Path back = new Path();
            back.moveTo(7.5f * sx, 8.5f * sy);
            back.lineTo(7.5f * sx, 5.2f * sy);
            back.quadTo(7.5f * sx, 3.4f * sy, 9.3f * sx, 3.4f * sy);
            back.lineTo(17.6f * sx, 3.4f * sy);
            back.quadTo(19.4f * sx, 3.4f * sy, 19.4f * sx, 5.2f * sy);
            back.lineTo(19.4f * sx, 7.5f * sy);
            cv.drawPath(back, p);
            // 前卡：整卡描边 + 磁条线 + 芯片 + 两道短线（照原图蓝卡元素）
            p.setColor(Color.rgb(0x0A, 0x5C, 0xD6));
            RectF card = new RectF(4.6f * sx, 8f * sy, 21f * sx, 20.6f * sy);
            cv.drawRoundRect(card, 2.4f * sx, 2.4f * sy, p);
            cv.drawLine(4.6f * sx, 11.6f * sy, 21f * sx, 11.6f * sy, p);
            RectF chip = new RectF(7f * sx, 14.2f * sy, 10.4f * sx, 16.8f * sy);
            cv.drawRoundRect(chip, 0.9f * sx, 0.9f * sy, p);
            cv.drawLine(14.6f * sx, 14.9f * sy, 18.6f * sx, 14.9f * sy, p);
            cv.drawLine(14.6f * sx, 17.3f * sy, 17.2f * sx, 17.3f * sy, p);
        }
    }

    class CoffeeIconView extends View {
        CoffeeIconView(Context c) { super(c); }
        @Override protected void onDraw(Canvas cv) {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            p.setStrokeWidth(dp(getContext(), 1.7f));
            p.setColor(Color.rgb(0x0A, 0x5C, 0xD6));
            float sx = getWidth() / 24f, sy = getHeight() / 24f;
            RectF cup = new RectF(4f * sx, 10f * sy, 17f * sx, 15.5f * sy);
            cv.drawRoundRect(cup, 2.2f * sx, 2.2f * sy, p);
            cv.drawLine(4f * sx, 15.5f * sy, 4f * sx, 16f * sy, p);
            cv.drawArc(new RectF(16.2f * sx, 10.8f * sy, 21.4f * sx, 16.4f * sy), -80, 180, false, p);
            cv.drawLine(8f * sx, 7f * sy, 8.8f * sx, 4.8f * sy, p);
            cv.drawLine(12f * sx, 7f * sy, 12.8f * sx, 4.8f * sy, p);
        }
    }

    // 赞助二维码虚线占位框（对照混合版 .sponsor-qr 的 dashed 边）
    class DashedQrView extends View {
        DashedQrView(Context c) { super(c); }
        @Override protected void onDraw(Canvas cv) {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(getContext(), 1.5f));
            p.setColor(Color.rgb(0xD1, 0xD1, 0xD6));
            p.setPathEffect(new android.graphics.DashPathEffect(new float[]{dp(getContext(), 6), dp(getContext(), 4)}, 0));
            float inset = dp(getContext(), 1);
            cv.drawRoundRect(new RectF(inset, inset, getWidth() - inset, getHeight() - inset), dp(getContext(), 12), dp(getContext(), 12), p);
        }
    }

    Bitmap loadAssetBitmap(String path) {
        try {
            InputStream in = getAssets().open(path);
            Bitmap b = BitmapFactory.decodeStream(in);
            try { in.close(); } catch (Exception e) {}
            return b;
        } catch (Exception e) { return null; }
    }

    void openAbout() {
        captureCurrentPageScroll();
        aboutOpen = true;
        aboutSponsorOpen = false;
        navBar.setVisibility(View.GONE);
        if (aboutSheet != null && aboutSheet.getParent() != null)
            ((ViewGroup) aboutSheet.getParent()).removeView(aboutSheet);
        final FrameLayout sheet = new FrameLayout(this);
        View shade = new View(this);
        shade.setBackgroundColor(Color.argb(102, 0, 0, 0));
        shade.setOnClickListener(v -> closeAbout());
        sheet.addView(shade, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable cg = new GradientDrawable();
        cg.setColor(Color.argb(190, 255, 255, 255)); // Q16: about sheet tint thinned, glass underneath shows through
        cg.setStroke(dp(this, 1), Color.argb(140, 255, 255, 255));
        cg.setCornerRadius(dp(this, 22));
        card.setBackground(cg);
        if (Build.VERSION.SDK_INT >= 21) {
            card.setElevation(dp(this, 24));
            card.setClipToOutline(true);
        }
        card.setOnClickListener(v -> {}); // 窗体吃掉点击，防穿透遮罩误关
        ScrollView sv = new ScrollView(this);
        thinScrollbar(sv);
        sv.setFillViewport(false);
        LinearLayout body = buildAboutBody();
        sv.addView(body);
        card.addView(sv, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        int sw = getResources().getDisplayMetrics().widthPixels;
        int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.88);
        card.measure(View.MeasureSpec.makeMeasureSpec(sw - dp(this, 24), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST));
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Math.min(card.getMeasuredHeight(), maxH));
        clp.gravity = Gravity.BOTTOM;
        clp.leftMargin = dp(this, 12); clp.rightMargin = dp(this, 12);
        clp.bottomMargin = dp(this, 12) + navBarH(); // Q26：浮窗底边抬到手势条之上，不拖白带
        // Q11：关于窗下垫冻结模糊快照，与窗同位同尺寸
        FrameLayout.LayoutParams aglp = new FrameLayout.LayoutParams(clp.width, clp.height);
        aglp.gravity = clp.gravity; aglp.leftMargin = clp.leftMargin; aglp.rightMargin = clp.rightMargin; aglp.bottomMargin = clp.bottomMargin;
        sheet.addView(glassLayer(card, 22, false), aglp);
        sheet.addView(card, clp);
        content.addView(sheet);
        aboutSheet = sheet;
        sheet.setAlpha(0f);
        sheet.animate().alpha(1f).setDuration(200).setInterpolator(ANIM_ENTER).start();
        card.setTranslationY(dp(this, 42));
        card.animate().translationY(0f).setDuration(260)
            .setInterpolator(ANIM_ENTER).start();
    }

    void closeAbout() {
        aboutOpen = false;
        final View sheet = aboutSheet;
        aboutSheet = null; aboutSponsorBody = null; aboutSponsorArrow = null;
        if (sheet != null && sheet.getParent() != null) {
            View card = sheet instanceof ViewGroup && ((ViewGroup) sheet).getChildCount() > 1
                ? ((ViewGroup) sheet).getChildAt(((ViewGroup) sheet).getChildCount() - 1) : null; // Q11：玻璃层垫在窗下，窗体是最后一层
            if (card != null) {
                card.animate().translationY(dp(this, 42)).alpha(0f)
                    .setDuration(180).setInterpolator(ANIM_ENTER)
                    .withEndAction(() -> {
                        if (sheet.getParent() != null) ((ViewGroup) sheet.getParent()).removeView(sheet);
                        navBar.setVisibility(View.VISIBLE);
                        syncSearchFab();
                    }).start();
                sheet.animate().alpha(0f).setDuration(180).start();
                return;
            }
            ((ViewGroup) sheet.getParent()).removeView(sheet);
        }
        navBar.setVisibility(View.VISIBLE);
        syncSearchFab();
    }

    LinearLayout buildAboutBody() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(this, 18), dp(this, 18), dp(this, 18), dp(this, 18));

        // hero：图标 + 卡盒 + 版本（对照 .about-hero）
        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.HORIZONTAL);
        hero.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable hg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{Color.rgb(0xF7, 0xFA, 0xFD), Color.rgb(0xEE, 0xF4, 0xFA)});
        hg.setCornerRadius(dp(this, 16));
        hg.setStroke(dp(this, 1), Color.rgb(0xDB, 0xE7, 0xF3));
        hero.setBackground(hg);
        hero.setPadding(dp(this, 16), dp(this, 14), dp(this, 16), dp(this, 14));
        page.addView(hero, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // Q26：图标改细线自绘——原 about-icon.png 是浅底彩图位图，在窗里被白底圆角裁出
        // 一圈没裁净的边（用户指认的黑边），禁用位图，照原图「双卡叠放」形态用 Canvas
        // 细线重绘，与全 App 图标体系同一语言，从根上无边可黑
        hero.addView(new CardMarkIconView(this), new LinearLayout.LayoutParams(dp(this, 56), dp(this, 56)));
        LinearLayout heroTx = new LinearLayout(this);
        heroTx.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams htlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        htlp.leftMargin = dp(this, 14);
        hero.addView(heroTx, htlp);
        heroTx.addView(tv(this, "卡盒", 19, Color.rgb(0x1C, 0x1C, 0x1E), true));
        TextView ver = tv(this, "版本 " + appVersion(), 12.5f, Color.rgb(0x8E, 0x8E, 0x93), false);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vlp.topMargin = dp(this, 2);
        heroTx.addView(ver, vlp);

        TextView desc = tv(this, "银行借记卡资料库：收录国内主要银行发行的借记卡，支持按卡组织、发卡行、特点筛选，数据内置、离线可用。你也可以收藏「我的卡片」，或添加自定义卡片。", 14.5f, Color.rgb(0x1C, 0x1C, 0x1E), false);
        desc.setLineSpacing(0, 1.45f);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = dp(this, 10);
        page.addView(desc, dlp);

        TextView src = tv(this, "数据来源为各银行公开资料整理，部分字段标注「待核实」，仅供参考，不构成办卡建议。", 12, Color.rgb(0x8E, 0x8E, 0x93), false);
        src.setLineSpacing(0, 1.45f);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(this, 8);
        page.addView(src, slp);

        // 赞助展开行（对照 #sponsorToggle）
        LinearLayout toggle = new LinearLayout(this);
        toggle.setOrientation(LinearLayout.HORIZONTAL);
        toggle.setGravity(Gravity.CENTER_VERTICAL);
        toggle.setClipToOutline(true);
        GradientDrawable tg = new GradientDrawable();
        tg.setColor(Color.rgb(0xF7, 0xF8, 0xFA)); tg.setCornerRadius(dp(this, 14));
        tg.setStroke(dp(this, 1), Color.argb(18, 20, 30, 60));
        toggle.setBackground(new RippleDrawable(ColorStateList.valueOf(Color.argb(38, 10, 92, 214)), tg, null));
        toggle.setPadding(dp(this, 16), dp(this, 13), dp(this, 16), dp(this, 13));
        LinearLayout.LayoutParams tolp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tolp.topMargin = dp(this, 14);
        page.addView(toggle, tolp);
        toggle.addView(new CoffeeIconView(this), new LinearLayout.LayoutParams(dp(this, 19), dp(this, 19)));
        TextView sponsorTx = tv(this, "请作者喝杯咖啡", 13.5f, Color.rgb(0x1C, 0x1C, 0x1E), true);
        LinearLayout.LayoutParams stlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        stlp.leftMargin = dp(this, 8);
        toggle.addView(sponsorTx, stlp);
        aboutSponsorArrow = tv(this, "›", 18, Color.rgb(0x8E, 0x8E, 0x93), false);
        aboutSponsorArrow.setGravity(Gravity.CENTER);
        toggle.addView(aboutSponsorArrow, new LinearLayout.LayoutParams(dp(this, 20), dp(this, 20)));

        // 赞助内容（对照 #sponsorBody，初始收起）
        final LinearLayout sponsor = new LinearLayout(this);
        sponsor.setOrientation(LinearLayout.VERTICAL);
        sponsor.setGravity(Gravity.CENTER_HORIZONTAL);
        sponsor.setBackground(roundRect(Color.rgb(0xF7, 0xF8, 0xFA), 14, this));
        sponsor.setPadding(dp(this, 14), dp(this, 14), dp(this, 14), dp(this, 14));
        LinearLayout.LayoutParams splp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        splp.topMargin = dp(this, 10);
        sponsor.setLayoutParams(splp);
        sponsor.setVisibility(View.GONE);
        page.addView(sponsor);
        aboutSponsorBody = sponsor;
        TextView spTx = tv(this, "如果卡盒对你有用，欢迎赞助支持开发～", 12, Color.rgb(0x8E, 0x8E, 0x93), false);
        spTx.setGravity(Gravity.CENTER);
        sponsor.addView(spTx, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Bitmap qr = loadAssetBitmap("data/images/sponsor-qr.png");
        if (qr != null) {
            ImageView qrIv = new ImageView(this);
            qrIv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            qrIv.setClipToOutline(true);
            qrIv.setImageBitmap(qr);
            LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(dp(this, 180), dp(this, 180));
            qlp.topMargin = dp(this, 10); qlp.bottomMargin = dp(this, 12);
            qlp.gravity = Gravity.CENTER_HORIZONTAL;
            sponsor.addView(qrIv, qlp);
        } else {
            FrameLayout qrBox = new FrameLayout(this);
            qrBox.setBackground(roundRect(Color.WHITE, 12, this));
            qrBox.addView(new DashedQrView(this), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            TextView ph = tv(this, "赞助二维码位\n（把收款码发我，即刻放上）", 12, Color.rgb(0x8E, 0x8E, 0x93), false);
            ph.setGravity(Gravity.CENTER);
            ph.setLineSpacing(0, 1.4f);
            qrBox.addView(ph, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(dp(this, 150), dp(this, 150));
            qlp.topMargin = dp(this, 10); qlp.bottomMargin = dp(this, 12);
            qlp.gravity = Gravity.CENTER_HORIZONTAL;
            sponsor.addView(qrBox, qlp);
        }

        Button save = new Button(this);
        save.setText("保存二维码到相册"); save.setTextSize(14); save.setAllCaps(false);
        save.setTextColor(Color.WHITE);
        GradientDrawable sbg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{Color.argb(235, 0x0A, 0x84, 0xFF), Color.argb(235, 0x00, 0x66, 0xE6)});
        sbg.setCornerRadius(dp(this, 14));
        sbg.setStroke(dp(this, 1), Color.argb(90, 255, 255, 255));
        save.setBackground(sbg);
        if (Build.VERSION.SDK_INT >= 21) save.setElevation(dp(this, 4));
        save.setOnClickListener(v -> { haptic(); saveSponsorQr(); });
        sponsor.addView(save, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(this, 46)));

        toggle.setOnClickListener(v -> {
            haptic();
            aboutSponsorOpen = !aboutSponsorOpen;
            sponsor.setVisibility(aboutSponsorOpen ? View.VISIBLE : View.GONE);
            if (aboutSponsorArrow != null) aboutSponsorArrow.setRotation(aboutSponsorOpen ? 90f : 0f);
            if (aboutSponsorOpen) {
                sponsor.setAlpha(0f); sponsor.setTranslationY(dp(this, -6));
                sponsor.animate().alpha(1f).translationY(0f).setDuration(220)
                    .setInterpolator(ANIM_ENTER).start();
            }
        });

        Button close = new Button(this);
        close.setText("关闭"); close.setTextSize(15); close.setAllCaps(false);
        close.setTextColor(Color.rgb(0x00, 0x7A, 0xFF));
        try { close.setTypeface(close.getTypeface(), android.graphics.Typeface.BOLD); } catch (Exception e) {}
        GradientDrawable cbg = new GradientDrawable();
        cbg.setColor(Color.argb(140, 255, 255, 255)); cbg.setCornerRadius(dp(this, 14));
        cbg.setStroke(dp(this, 1), Color.argb(140, 255, 255, 255));
        close.setBackground(cbg);
        close.setOnClickListener(v -> { haptic(); closeAbout(); });
        LinearLayout.LayoutParams clp2 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(this, 50));
        clp2.topMargin = dp(this, 14);
        page.addView(close, clp2);
        return page;
    }

    // 保存赞助二维码到相册（对照混合版 Bridge saveSponsorQr；API29+ 走 MediaStore 无需权限，低版本先申请写权限）
    void saveSponsorQr() {
        byte[] data;
        try {
            InputStream in = getAssets().open("data/images/sponsor-qr.png");
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            data = bos.toByteArray();
            if (data.length == 0) throw new Exception("empty");
        } catch (Exception e) {
            showFloatToast("收款码还没放上，放上后就能保存了");
            return;
        }
        if (Build.VERSION.SDK_INT < 29
            && checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            sponsorSavePending = true;
            requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 4201);
            return;
        }
        writeSponsorQrToAlbum(data);
    }

    void writeSponsorQrToAlbum(byte[] data) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                android.content.ContentValues cv = new android.content.ContentValues();
                cv.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "cardbox-sponsor-qr.png");
                cv.put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png");
                cv.put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/卡盒");
                Uri uri = getContentResolver().insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) throw new Exception("insert failed");
                java.io.OutputStream out = getContentResolver().openOutputStream(uri);
                if (out == null) throw new Exception("open failed");
                out.write(data); out.close();
            } else {
                File dir = new File(android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_PICTURES), "卡盒");
                if (!dir.exists()) dir.mkdirs();
                File f = new File(dir, "cardbox-sponsor-qr.png");
                FileOutputStream out = new FileOutputStream(f);
                out.write(data); out.close();
                android.media.MediaScannerConnection.scanFile(this,
                    new String[]{f.getAbsolutePath()}, new String[]{"image/png"}, null);
            }
            showFloatToast("赞助二维码已保存到相册");
        } catch (Exception e) {
            showFloatToast("保存失败，请稍后再试");
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 4201) {
            boolean granted = grantResults.length > 0
                && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED;
            if (granted && sponsorSavePending) { sponsorSavePending = false; saveSponsorQr(); }
            else {
                sponsorSavePending = false;
                showFloatToast("没有相册写入权限，二维码未保存");
            }
        }
    }

    // ---------- 欢迎页 / 更新日志（Phase 4b） ----------
    void showWelcome() {
        captureCurrentPageScroll();
        welcomeOpen = true;
        navBar.setVisibility(View.GONE);
        content.removeAllViews();
        content.addView(buildWelcomePage());
    }

    void closeWelcome() {
        prefs.edit().putBoolean("welcomed", true).apply();
        welcomeOpen = false;
        navBar.setVisibility(View.VISIBLE);
        showTab(tab);
    }

    View buildWelcomePage() {
        ScrollView sc = new ScrollView(this);
        thinScrollbar(sc);
        sc.setBackgroundColor(Color.WHITE);
        sc.setFillViewport(true);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(this, 26), statusBarH() + dp(this, 22), dp(this, 26), dp(this, 18) + navBarH()); // Q26：欢迎页底部按钮避开手势条
        sc.addView(page);

        TextView logo = tv(this, "卡", 30, Color.WHITE, true);
        logo.setGravity(Gravity.CENTER);
        GradientDrawable lg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
            new int[]{Color.rgb(0x0A, 0x84, 0xFF), Color.rgb(0x5E, 0x5C, 0xE6)});
        lg.setCornerRadius(dp(this, 18));
        logo.setBackground(lg);
        page.addView(logo, new LinearLayout.LayoutParams(dp(this, 64), dp(this, 64)));

        TextView title = tv(this, "欢迎使用卡盒", 28, Color.rgb(0x1C, 0x1C, 0x1E), true);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(this, 16);
        page.addView(title, tlp);
        TextView sub = tv(this, "把银行卡装进一个盒子，出门刷卡不再纠结。", 14, Color.rgb(0x8E, 0x8E, 0x93), false);
        sub.setLineSpacing(0, 1.35f);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(this, 8);
        page.addView(sub, slp);

        String n = String.valueOf(Store.all.size());
        String[][] feats = {
            {"▤", "银行卡图鉴", n + " 张借记卡与信用卡，费率、币种、权益一次看清"},
            {"◎", "情景选卡", "留学、旅游、海淘、日常，答几道题给你推荐合适的卡"},
            {"★", "我的卡片", "收藏自己的卡，能看卡包实力，还能拖动排序"},
            {"⊘", "断网可用", "数据存在手机里，没网也能查，更新不用重装"},
        };
        for (String[] f : feats) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.TOP);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.topMargin = dp(this, 15);
            page.addView(row, rlp);
            TextView ic = tv(this, f[0], 19, Color.rgb(0x0A, 0x5C, 0xD6), true);
            ic.setGravity(Gravity.CENTER);
            ic.setBackground(roundRect(Color.rgb(0xEE, 0xF4, 0xFF), 13, this));
            row.addView(ic, new LinearLayout.LayoutParams(dp(this, 44), dp(this, 44)));
            LinearLayout tx = new LinearLayout(this);
            tx.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams txlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            txlp.leftMargin = dp(this, 13);
            row.addView(tx, txlp);
            tx.addView(tv(this, f[1], 14.5f, Color.rgb(0x1C, 0x1C, 0x1E), true));
            TextView d = tv(this, f[2], 12.5f, Color.rgb(0x8E, 0x8E, 0x93), false);
            d.setLineSpacing(0, 1.3f);
            tx.addView(d);
        }

        Button go = new Button(this);
        go.setText("开始使用");
        go.setTextSize(15.5f);
        go.setAllCaps(false);
        go.setTextColor(Color.WHITE);
        GradientDrawable gb = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
            new int[]{Color.rgb(0x0A, 0x84, 0xFF), Color.rgb(0x5E, 0x5C, 0xE6)});
        gb.setCornerRadius(dp(this, 16));
        go.setBackground(gb);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(this, 52));
        glp.topMargin = dp(this, 26);
        page.addView(go, glp);
        go.setOnClickListener(v -> { haptic(); closeWelcome(); });

        TextView note = tv(this, "卡片数据仅供参考，办卡以银行最新公告为准", 11, Color.rgb(0xC7, 0xC7, 0xCC), false);
        note.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = dp(this, 12);
        page.addView(note, nlp);
        return sc;
    }

    static class LogEntry { String v; List<String> notes = new ArrayList<>(); }

    List<LogEntry> loadChangelog() {
        List<LogEntry> out = new ArrayList<>();
        try {
            InputStream in = getAssets().open("data/changelog.json");
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192]; int r;
            while ((r = in.read(buf)) > 0) bos.write(buf, 0, r);
            in.close();
            JSONArray arr = new JSONArray(new String(bos.toByteArray(), "UTF-8"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                LogEntry e = new LogEntry();
                e.v = o.optString("v");
                JSONArray ns = o.optJSONArray("notes");
                if (ns != null) for (int j = 0; j < ns.length(); j++) e.notes.add(ns.optString(j));
                out.add(e);
            }
        } catch (Exception e) { /* 读不到就空列表，页面会提示 */ }
        return out;
    }

    void showChangelog() {
        captureCurrentPageScroll();
        changelogOpen = true;
        navBar.setVisibility(View.GONE);
        content.removeAllViews();
        content.addView(buildChangelogPage());
        syncTopFab();
    }

    void closeChangelog() {
        changelogOpen = false;
        navBar.setVisibility(View.VISIBLE);
        showTab(tab);
    }

    View buildChangelogPage() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(0xF2, 0xF3, 0xF7));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(this, 14), pageTopPad(), dp(this, 14), dp(this, 6));
        root.addView(head);
        Button back = new Button(this);
        back.setText("‹ 返回"); back.setTextSize(14); back.setAllCaps(false);
        back.setBackground(roundRect(Color.WHITE, 12, this));
        back.setOnClickListener(v -> { haptic(); closeChangelog(); });
        head.addView(back, new LinearLayout.LayoutParams(dp(this, 84), dp(this, 38)));
        TextView ht = tv(this, "更新日志", 17, Color.rgb(0x1C, 0x1C, 0x1E), true);
        LinearLayout.LayoutParams htlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        htlp.leftMargin = dp(this, 10);
        head.addView(ht, htlp);

        changelogScroll = new ScrollView(this);
        thinScrollbarPersistent(changelogScroll);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(this, 16), dp(this, 6), dp(this, 16), dp(this, 16));
        changelogScroll.addView(page);
        if (Build.VERSION.SDK_INT >= 23) changelogScroll.setOnScrollChangeListener((v, sx, sy, ox, oy) -> updateTopFabVisibility(sy));
        root.addView(changelogScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        List<LogEntry> logs = loadChangelog();
        if (logs.isEmpty()) {
            page.addView(tv(this, "更新日志读取失败", 13.5f, Color.rgb(0x8E, 0x8E, 0x93), false));
        }
        for (LogEntry e : logs) {
            TextView ver = tv(this, "v" + e.v, 14.5f, Color.rgb(0x1C, 0x1C, 0x1E), true);
            LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            vlp.topMargin = dp(this, 14);
            page.addView(ver, vlp);
            for (String note : e.notes) {
                TextView nt = tv(this, "•  " + note, 12.5f, Color.rgb(0x3A, 0x3A, 0x3C), false);
                nt.setLineSpacing(0, 1.45f);
                LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                nlp.topMargin = dp(this, 3); nlp.leftMargin = dp(this, 4);
                page.addView(nt, nlp);
            }
        }

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER);
        actions.setBackgroundColor(Color.WHITE);
        actions.setPadding(dp(this, 14), dp(this, 10), dp(this, 14), dp(this, 12));
        root.addView(actions);
        Button top = new Button(this);
        top.setText("↑ 回到顶部"); top.setTextSize(13); top.setAllCaps(false);
        top.setBackground(roundRect(Color.rgb(0xEE, 0xF1, 0xF6), 999, this));
        top.setOnClickListener(v -> { haptic(); if (changelogScroll != null) changelogScroll.smoothScrollTo(0, 0); });
        actions.addView(top, new LinearLayout.LayoutParams(0, dp(this, 40), 1f));
        Button fold = new Button(this);
        fold.setText("收起日志"); fold.setTextSize(13); fold.setAllCaps(false);
        fold.setBackground(roundRect(Color.rgb(0xEE, 0xF1, 0xF6), 999, this));
        fold.setOnClickListener(v -> { haptic(); closeChangelog(); });
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(0, dp(this, 40), 1f);
        flp.leftMargin = dp(this, 10);
        actions.addView(fold, flp);
        return root;
    }

    // P-log：设置页内就地展开的更新日志框（对照混合版 .changelog：限高 52vh、右侧滑杆、底部收起/回顶常驻）
    View buildInlineLogBox() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(rippleBg(Color.WHITE, 14));
        box.setClipToOutline(true);
        box.setPadding(dp(this, 4), dp(this, 2), dp(this, 4), dp(this, 2));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(this, 8);
        box.setLayoutParams(blp);

        settingsLogScroll = new ScrollView(this);
        thinScrollbarPersistent(settingsLogScroll);
        settingsLogScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        int h = (int) (getResources().getDisplayMetrics().heightPixels * 0.52f);
        h = Math.max(dp(this, 240), Math.min(h, dp(this, 520)));
        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.setPadding(dp(this, 10), dp(this, 2), dp(this, 10), dp(this, 6));
        List<LogEntry> logs = loadChangelog();
        if (logs.isEmpty()) {
            inner.addView(tv(this, "更新日志读取失败", 13.5f, Color.rgb(0x8E, 0x8E, 0x93), false));
        }
        for (LogEntry e : logs) {
            TextView ver = tv(this, "v" + e.v, 14.5f, Color.rgb(0x1C, 0x1C, 0x1E), true);
            LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            vlp.topMargin = dp(this, 12);
            inner.addView(ver, vlp);
            for (String note : e.notes) {
                TextView nt = tv(this, "•  " + note, 12.5f, Color.rgb(0x3A, 0x3A, 0x3C), false);
                nt.setLineSpacing(0, 1.45f);
                LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                nlp.topMargin = dp(this, 3); nlp.leftMargin = dp(this, 4);
                inner.addView(nt, nlp);
            }
        }
        settingsLogScroll.addView(inner);
        settingsLogScroll.setOnTouchListener((v, ev) -> {
            v.getParent().requestDisallowInterceptTouchEvent(true);
            if (ev.getAction() == android.view.MotionEvent.ACTION_UP || ev.getAction() == android.view.MotionEvent.ACTION_CANCEL)
                v.getParent().requestDisallowInterceptTouchEvent(false);
            return false;
        });
        box.addView(settingsLogScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER);
        actions.setPadding(dp(this, 8), dp(this, 8), dp(this, 8), dp(this, 8));
        box.addView(actions);
        Button top = new Button(this);
        top.setText("↑ 回到顶部"); top.setTextSize(13); top.setAllCaps(false);
        top.setBackground(roundRect(Color.rgb(0xEE, 0xF1, 0xF6), 999, this));
        top.setOnClickListener(v -> { haptic(); if (settingsLogScroll != null) settingsLogScroll.smoothScrollTo(0, 0); });
        actions.addView(top, new LinearLayout.LayoutParams(0, dp(this, 38), 1f));
        Button fold = new Button(this);
        fold.setText("收起日志"); fold.setTextSize(13); fold.setAllCaps(false);
        fold.setBackground(roundRect(Color.rgb(0xEE, 0xF1, 0xF6), 999, this));
        fold.setOnClickListener(v -> { haptic(); settingsLogOpen = false; rebuildPages(); });
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(0, dp(this, 38), 1f);
        flp.leftMargin = dp(this, 10);
        actions.addView(fold, flp);
        return box;
    }

    View buildSettingsPage() {
        LinearLayout page = basePage("设置");
        View wizEntry = settingRow("情景选卡", "出国留学 / 出境旅游 / 海淘网购 / 日常使用，按场景挑卡 ›");
        wizEntry.setOnClickListener(v -> { haptic(); openWizard(); });
        page.addView(wizEntry);

        sectionHead(page, "显示");
        segRow(page, "字体", new String[][]{{"default","软件默认"},{"system","本机字体"},{"serif","内置宋体"}}, fontMode, v -> {
            fontMode = v; prefs.edit().putString("font_mode", v).apply(); haptic(); rebuildPages();
        });
        segRow(page, "界面大小", new String[][]{{"0.9","紧凑"},{"1","标准"},{"1.12","大号"}}, String.valueOf(uiScale), v -> {
            uiScale = Float.parseFloat(v); prefs.edit().putFloat("ui_scale", uiScale).apply(); haptic(); rebuildPages();
        });

        sectionHead(page, "使用体验");
        switchRow(page, "高刷新率", "把刷新率拉到屏幕最高档（耗电略增）", prefs.getBoolean("high_refresh", false), on -> {
            prefs.edit().putBoolean("high_refresh", on).apply(); haptic(); applyHighRefresh(); rebuildPages();
        });
        segRow(page, "触感反馈", new String[][]{{"0","关"},{"1","轻"},{"2","中"},{"3","强"}}, String.valueOf(hapticLevel), v -> {
            hapticLevel = Integer.parseInt(v); prefs.edit().putInt("haptic_level", hapticLevel).apply(); haptic(); rebuildPages();
        });

        sectionHead(page, "数据");
        page.addView(settingRow("数据版本", "v" + Store.dataVersion + " · " + Store.all.size() + " 张卡（启动自动检查，更新后无需重装）"));
        View updRow = settingRow("检查数据更新", "从数据仓拉最新卡库 ›");
        updRow.setOnClickListener(v -> { haptic(); showFloatToast("正在检查数据更新…"); checkDataUpdate(true); });
        page.addView(updRow);

        sectionHead(page, "关于");
        // Q18: last-crash trace at top of About (copyable / clearable); empty when no crash recorded
        if (crashLogText != null && !crashLogText.trim().isEmpty()) {
            LinearLayout crashBox = new LinearLayout(this);
            crashBox.setOrientation(LinearLayout.VERTICAL);
            crashBox.setBackground(roundRect(Color.rgb(0xFF, 0xF1, 0xF0), 12, this));
            crashBox.setPadding(dp(this, 14), dp(this, 10), dp(this, 14), dp(this, 12));
            LinearLayout.LayoutParams cbp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cbp.topMargin = dp(this, 8);
            crashBox.setLayoutParams(cbp);
            crashBox.addView(tv(this, "最近一次崩溃记录", 14, Color.rgb(0xB0, 0x2A, 0x20), true));
            TextView crashTv = tv(this, crashLogText, 11, Color.rgb(0x5A, 0x2A, 0x24), false);
            crashTv.setTextIsSelectable(true);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.topMargin = dp(this, 6);
            crashTv.setLayoutParams(clp);
            crashBox.addView(crashTv);
            LinearLayout crashBtns = new LinearLayout(this);
            crashBtns.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams bpl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            bpl.topMargin = dp(this, 10);
            crashBtns.setLayoutParams(bpl);
            Button copyBtn = new Button(this);
            copyBtn.setText("复制记录"); copyBtn.setTextSize(12.5f); copyBtn.setAllCaps(false);
            copyBtn.setBackground(roundRect(Color.rgb(0x0A, 0x5C, 0xD6), 9, this));
            copyBtn.setTextColor(Color.WHITE);
            copyBtn.setOnClickListener(v -> {
                haptic();
                try {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("crash", crashLogText));
                    showFloatToast("崩溃记录已复制");
                } catch (Throwable ignored) { showFloatToast("复制失败"); }
            });
            crashBtns.addView(copyBtn, new LinearLayout.LayoutParams(0, dp(this, 38), 1f));
            Button clearBtn = new Button(this);
            clearBtn.setText("清除记录"); clearBtn.setTextSize(12.5f); clearBtn.setAllCaps(false);
            clearBtn.setBackground(roundRect(Color.rgb(0xEE, 0xF1, 0xF6), 9, this));
            clearBtn.setOnClickListener(v -> { haptic(); clearCrashLog(); rebuildPages(); showFloatToast("崩溃记录已清除"); });
            LinearLayout.LayoutParams clrLp = new LinearLayout.LayoutParams(0, dp(this, 38), 1f);
            clrLp.leftMargin = dp(this, 10);
            crashBtns.addView(clearBtn, clrLp);
            crashBox.addView(crashBtns);
            // glass status line (Q18 auto-disable visibility)
            if (glassDisabled) {
                TextView gs = tv(this, "玻璃效果已自动停用（连续失败后回落半透，不影响使用）", 11, Color.rgb(0x8E, 0x8E, 0x93), false);
                LinearLayout.LayoutParams gsl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                gsl.topMargin = dp(this, 8);
                gs.setLayoutParams(gsl);
                crashBox.addView(gs);
            }
            page.addView(crashBox);
        } else if (glassDisabled) {
            page.addView(settingRow("玻璃效果", "已自动停用（连续失败后回落半透，不影响使用）"));
        }
        page.addView(settingRow("版本", appVersion() + "（原生版）"));
        View logRow = settingRow("更新日志", settingsLogOpen ? "收起更新日志" : "每个版本改了什么 ›");
        logRow.setOnClickListener(v -> { haptic(); settingsLogOpen = !settingsLogOpen; rebuildPages(); });
        page.addView(logRow);
        if (settingsLogOpen) page.addView(buildInlineLogBox());
        View welRow = settingRow("欢迎页", "重新看一遍首次打开的介绍 ›");
        welRow.setOnClickListener(v -> { haptic(); showWelcome(); });
        page.addView(welRow);
        View aboutRow = settingRow("关于卡盒", "介绍与赞助 ›");
        aboutRow.setOnClickListener(v -> { haptic(); openAbout(); });
        page.addView(aboutRow);
        page.addView(settingRow("迁移进度", "全部卡片 / 详情 / 我的卡片 / 学生推荐 / 筛选 / 资讯 / 情景选卡 / 自定义卡 / 拖动 / 字体与界面大小 / 高刷 / 触感 / 欢迎页 / 更新日志 / 数据 OTA 已迁移"));
        ScrollView sv = new ScrollView(this);
        thinScrollbar(sv);
        sv.setClipToPadding(false);
        settingsScroll = sv;
        if (Build.VERSION.SDK_INT >= 23) sv.setOnScrollChangeListener((v, sx, sy, ox, oy) -> { pageScrollSaveY.put("settings", sy); updateTopFabVisibility(sy); });
        // P2d-fix：此前这里把 basePage 的顶部留白覆盖成 12dp，标题被压进状态栏；改用 pageTopPad()/dockPad()
        page.setPadding(dp(this, 14), pageTopPad(), dp(this, 14), dockPad());
        sv.addView(page);
        restorePageScroll("settings", sv);
        return sv;
    }

    void sectionHead(LinearLayout page, String s) {
        TextView t = tv(this, s, 12.5f, Color.rgb(0x8E, 0x8E, 0x93), true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(this, 16); lp.leftMargin = dp(this, 2);
        t.setLayoutParams(lp);
        page.addView(t);
    }

    interface SegPick { void onPick(String v); }

    static boolean segOn(String optionKey, String cur) {
        // 数值类选项（界面大小 0.9/1/1.12）必须按浮点比较：
        // String.valueOf(1.0f) 得 "1.0"，与键 "1" 直等永假，「标准」永不显蓝。
        try {
            float a = Float.parseFloat(optionKey.trim());
            float b = Float.parseFloat(cur.trim());
            return Math.abs(a - b) < 0.0001f;
        } catch (Exception e) {
            return optionKey.equals(cur);
        }
    }

    // 三档单选行：白卡里横排，选中蓝底（与筛选面板 chipRow 同风格）
    void segRow(LinearLayout page, String label, String[][] opts, String cur, final SegPick pick) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(roundRect(Color.WHITE, 12, this));
        box.setPadding(dp(this, 14), dp(this, 10), dp(this, 14), dp(this, 12));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(this, 8);
        box.setLayoutParams(blp);
        box.addView(tv(this, label, 14, Color.rgb(0x1C, 0x1C, 0x1E), true));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(this, 9);
        box.addView(row, rlp);
        for (final String[] o : opts) {
            final boolean on = segOn(o[0], cur);
            TextView t = tv(this, o[1], 12.5f, on ? Color.WHITE : Color.rgb(0x1C, 0x1C, 0x1E), on);
            t.setGravity(Gravity.CENTER);
            t.setBackground(roundRect(on ? Color.rgb(0x0A, 0x5C, 0xD6) : Color.rgb(0xEE, 0xF1, 0xF6), 9, this));
            t.setPadding(dp(this, 4), dp(this, 8), dp(this, 4), dp(this, 8));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.rightMargin = dp(this, 8);
            t.setLayoutParams(lp);
            t.setOnClickListener(v -> pick.onPick(o[0]));
            row.addView(t);
        }
        page.addView(box);
    }

    interface SwitchSet { void onSet(boolean on); }

    void switchRow(LinearLayout page, String label, String desc, final boolean on, final SwitchSet set) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(rippleBg(Color.WHITE, 12));
        row.setClipToOutline(true);
        row.setPadding(dp(this, 14), dp(this, 10), dp(this, 12), dp(this, 10));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(this, 8);
        row.setLayoutParams(rlp);
        LinearLayout txt = new LinearLayout(this);
        txt.setOrientation(LinearLayout.VERTICAL);
        txt.addView(tv(this, label, 14, Color.rgb(0x1C, 0x1C, 0x1E), true));
        txt.addView(tv(this, desc, 11.5f, Color.rgb(0x8E, 0x8E, 0x93), false));
        row.addView(txt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView sw = tv(this, on ? "开" : "关", 12.5f, on ? Color.WHITE : Color.rgb(0x8E, 0x8E, 0x93), true);
        sw.setGravity(Gravity.CENTER);
        sw.setBackground(roundRect(on ? Color.rgb(0x0A, 0x5C, 0xD6) : Color.rgb(0xEE, 0xF1, 0xF6), 999, this));
        sw.setPadding(dp(this, 16), dp(this, 7), dp(this, 16), dp(this, 7));
        row.addView(sw);
        row.setOnClickListener(v -> set.onSet(!on));
        page.addView(row);
    }

    View settingRow(String k, String v) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setBackground(rippleBg(Color.WHITE, 12));
        row.setClipToOutline(true);
        row.setPadding(dp(this, 14), dp(this, 10), dp(this, 14), dp(this, 10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(this, 8);
        row.setLayoutParams(lp);
        row.addView(tv(this, k, 14, Color.rgb(0x1C, 0x1C, 0x1E), true));
        row.addView(tv(this, v, 12, Color.rgb(0x8E, 0x8E, 0x93), false));
        return row;
    }

    LinearLayout basePage(String title) {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(this, 14), pageTopPad(), dp(this, 14), 0);
        page.addView(tv(this, title, 24, Color.rgb(0x1C, 0x1C, 0x1E), true));
        return page;
    }

    @Override
    public void onBackPressed() {
        if (floatSearchOpen) { closeFloatSearch(); return; }
        if (cardMenuPop != null) { closeCardMenu(); return; }
        if (aboutOpen) { closeAbout(); return; }
        if (welcomeOpen) { closeWelcome(); return; }
        if (changelogOpen) { closeChangelog(); return; }
        if (settingsLogOpen && "settings".equals(tab)) { settingsLogOpen = false; rebuildPages(); return; }
        if (detailCard != null) { closeDetail(); return; }
        if (filterSheet != null) { closeFilterSheet(); return; }
        if (wizardOpen) {
            if (wizSc == null) closeWizard(); else wizGoBack();
            return;
        }
        super.onBackPressed();
    }
}
