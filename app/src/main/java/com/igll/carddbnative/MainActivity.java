package com.igll.carddbnative;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;
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
    static final OvershootInterpolator ANIM_MENU_SPRING = new OvershootInterpolator(1.15f);
    static final int ANIM_DUR_PAGE = 260;    // 切页 淡入+上移
    static final int ANIM_DUR_SHEET_IN = 280; // 悬浮窗升起（Q74 统一）
    static final int ANIM_DUR_SHEET_OUT = 190; // 悬浮窗收起（Q74 统一）
    static final int ANIM_DUR_FADE = 220;    // 普通淡入/步骤切换
    // Q74 弹窗动画精加工统一：同类窗同参数，遮罩与窗体同曲线同起止，不许一段一个节奏。
    static final int ANIM_DUR_SHADE_IN = ANIM_DUR_SHEET_IN;
    static final int ANIM_DUR_SHADE_OUT = ANIM_DUR_SHEET_OUT;
    static final int ANIM_DUR_MENU_IN = 260;
    static final int ANIM_DUR_MENU_OUT = 160;
    static final int ANIM_DUR_TOAST_IN = 250;
    static final int ANIM_DUR_TOAST_OUT = 180;
    static final int ANIM_DUR_CARDMENU_IN = 160;
    static final int ANIM_DUR_CARDMENU_OUT = 140;
    static final float SHEET_RISE_DP = 42f;
    /** Q74：遮罩淡入与窗体升起同曲线同步启动（ANIM_ENTER + SHEET_IN）。 */
    void animShadeIn(View shade) {
        if (shade == null) return;
        shade.setAlpha(0f);
        shade.animate().cancel();
        shade.animate().alpha(1f).setDuration(ANIM_DUR_SHADE_IN).setInterpolator(ANIM_ENTER).start();
    }
    /** Q74：遮罩淡出与窗体收起同曲线同步（ANIM_EXIT + SHEET_OUT）。 */
    void animShadeOut(View shade) {
        if (shade == null) return;
        shade.animate().cancel();
        shade.animate().alpha(0f).setDuration(ANIM_DUR_SHADE_OUT).setInterpolator(ANIM_EXIT).start();
    }
    /** Q74：贴底窗升起——轻微上浮 42dp + 淡入，ANIM_ENTER/SHEET_IN。 */
    void animSheetIn(View wrap) {
        if (wrap == null) return;
        wrap.setAlpha(0f);
        wrap.setTranslationY(dp(this, SHEET_RISE_DP));
        wrap.animate().cancel();
        wrap.animate().alpha(1f).translationY(0f).setDuration(ANIM_DUR_SHEET_IN).setInterpolator(ANIM_ENTER).start();
    }
    /** Q74：悬浮卡升起——.94 放大 + 上浮 14dp + 淡入，同 SHEET_IN 曲线。 */
    void animCardIn(View card) {
        if (card == null) return;
        card.setAlpha(0f);
        card.setScaleX(0.94f); card.setScaleY(0.94f);
        card.setTranslationY(dp(this, 14));
        card.animate().cancel();
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
            .setDuration(ANIM_DUR_SHEET_IN).setInterpolator(ANIM_ENTER).start();
    }
    /** Q74：窗体收起——顺势下沉 42dp + 淡出，ANIM_EXIT/SHEET_OUT，落定回调。 */
    void animSheetOut(View wrap, Runnable end) {
        if (wrap == null) { if (end != null) end.run(); return; }
        wrap.animate().cancel();
        wrap.animate().alpha(0f).translationY(dp(this, SHEET_RISE_DP))
            .setDuration(ANIM_DUR_SHEET_OUT).setInterpolator(ANIM_EXIT)
            .withEndAction(end).start();
    }
    void animCardOut(View card, Runnable end) {
        if (card == null) { if (end != null) end.run(); return; }
        card.animate().cancel();
        card.animate().alpha(0f).scaleX(0.96f).scaleY(0.96f).translationY(dp(this, 10))
            .setDuration(ANIM_DUR_SHEET_OUT).setInterpolator(ANIM_EXIT)
            .withEndAction(end).start();
    }
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
    // Q12 浮窗升起时悬浮件全体退场（对照混合版 styles.css body.dock-hidden：
    // .dock-wrap/.fab/.quick-fab/.qf-top/.sbar 一并 display:none，app.js 各弹窗 open* 均 setDockVisible(false)）。
    // 原生等价：navWrap（dock）+searchFab/filterFab/addFab/topFab 同退同回；关窗恢复走 sync* 的
    // ANIM_ENTER 淡入缩放（P4-fix 曲线），dock 本身 180ms 淡入，不再各处散写 navWrap VISIBLE。
    boolean isChromeCovered() {
        return welcomeOpen || changelogOpen || wizardOpen || aboutOpen
            || filterSheet != null || detailCard != null || cardMenuPop != null
            || customFormSheet != null || customDetailSheet != null || binSheet != null || addSheetView != null
            || extSheet != null || showcaseView != null || simkeepView != null || simkeepFormSheet != null
            || placeholderPickerView != null
            || delConfirmSheet != null || updateTipSheet != null || updateConfirmSheet != null;
    }
    void hideFabsNow() {
        cancelTopFabShow();
        if (searchFab != null && searchFab.getParent() != null) ((ViewGroup) searchFab.getParent()).removeView(searchFab);
        searchFab = null;
        if (filterFab != null && filterFab.getParent() != null) ((ViewGroup) filterFab.getParent()).removeView(filterFab);
        filterFab = null; filterFabBadge = null;
        if (addFab != null && addFab.getParent() != null) ((ViewGroup) addFab.getParent()).removeView(addFab);
        addFab = null;
        if (topFab != null && topFab.getParent() != null) ((ViewGroup) topFab.getParent()).removeView(topFab);
        topFab = null; topFabShown = false;
    }
    void hideChrome() {
        // Q74：窗体升起时悬浮件退场与窗体同曲线同步——dock 淡出下沉、悬浮钮缩放淡出，
        // 不许硬切 GONE 与窗体升起打架。落定后才 GONE/摘除，避免白杠与残影。
        if (navWrap != null && navWrap.getVisibility() == View.VISIBLE) {
            navWrap.animate().cancel();
            final View nw = navWrap;
            nw.animate().alpha(0f).translationY(dp(this, 8))
                .setDuration(ANIM_DUR_SHEET_OUT).setInterpolator(ANIM_EXIT)
                .withEndAction(() -> { nw.setVisibility(View.GONE); nw.setAlpha(1f); nw.setTranslationY(0f); })
                .start();
        } else if (navWrap != null) {
            navWrap.animate().cancel();
            navWrap.setVisibility(View.GONE);
            navWrap.setAlpha(1f); navWrap.setTranslationY(0f);
        }
        // 悬浮钮同步缩放淡出后摘除（与 hideTopFab 既有 170ms 口径收敛到 SHEET_OUT）
        animateFabOut(searchFab); animateFabOut(filterFab); animateFabOut(addFab); animateFabOut(topFab);
        // 动画进行中先清引用，防 sync* 在窗在场时把钮又挂回来；实际摘除由动画结束或 200ms 兜底完成
        searchFab = null; filterFab = null; filterFabBadge = null; addFab = null; topFab = null; topFabShown = false;
        cancelTopFabShow();
    }
    void animateFabOut(final View fab) {
        if (fab == null || fab.getParent() == null) return;
        fab.animate().cancel();
        fab.animate().alpha(0f).scaleX(0.85f).scaleY(0.85f)
            .setDuration(ANIM_DUR_SHEET_OUT).setInterpolator(ANIM_EXIT)
            .withEndAction(() -> { if (fab.getParent() instanceof ViewGroup) ((ViewGroup) fab.getParent()).removeView(fab); })
            .start();
        // 兜底：动画被打断时 220ms 后强制摘除，防残留挡窗
        fab.postDelayed(() -> { if (fab.getParent() instanceof ViewGroup) ((ViewGroup) fab.getParent()).removeView(fab); }, ANIM_DUR_SHEET_OUT + 30);
    }
    void restoreChrome() {
        if (suppressNextChromeRestore) { suppressNextChromeRestore = false; hideChrome(); return; }
        if (isChromeCovered()) { hideChrome(); return; }
        if (navWrap != null && navWrap.getVisibility() != View.VISIBLE) {
            navWrap.animate().cancel();
            navWrap.setVisibility(View.VISIBLE);
            navWrap.setAlpha(0f);
            navWrap.setTranslationY(dp(this, 8));
            navWrap.animate().alpha(1f).translationY(0f).setDuration(ANIM_DUR_FADE).setInterpolator(ANIM_ENTER).start();
        }
        syncSearchFab(); syncAddFab(); syncTopFab();
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
                thumb.setSize(dp(this, 3), dp(this, 34)); // Q49：thumb 长度能缩则缩（原 48 过长）
                sv.setVerticalScrollbarThumbDrawable(thumb);
                GradientDrawable track = new GradientDrawable();
                track.setColor(Color.TRANSPARENT);
                sv.setVerticalScrollbarTrackDrawable(track);
            } catch (Exception e) { /* 低版本/个别机型回落系统细条 */ }
        }
    }
    // Q49：短内容列表（资讯 6 条/我的卡片）指示从简到近乎无——直接关掉系统滚动条，不挂长条
    void noScrollbar(ScrollView sv) {
        if (sv == null) return;
        sv.setVerticalScrollBarEnabled(false);
        sv.setHorizontalScrollBarEnabled(false);
    }
    // Q49：可拖拽滚动条（对照混合版 .sbar：right 3px、thumb 5px 拖时 7px、min 34px、
    // 滚动显 1100ms 淡出、拖时百分比气泡；轨道只落在宿主可视区内，上下边距由 attach 处避开 dock/手势条）
    class DragBarView extends View {
        ScrollView target;
        boolean persistent;
        boolean dragging;
        boolean shown;
        Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Paint bubblePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Paint bubbleText = new Paint(Paint.ANTI_ALIAS_FLAG);
        Runnable hideTask = () -> { if (!dragging && !persistent) { shown = false; animate().alpha(0f).setDuration(300).start(); } };
        DragBarView(Context c, ScrollView sv, boolean pers) {
            super(c);
            target = sv; persistent = pers;
            setAlpha(pers ? 1f : 0f);
            shown = pers;
            thumbPaint.setStyle(Paint.Style.FILL);
            bubblePaint.setStyle(Paint.Style.FILL);
            bubblePaint.setColor(Color.argb(224, 28, 32, 44));
            bubbleText.setColor(Color.WHITE);
            bubbleText.setTextSize(dp(c, 12.5f));
            bubbleText.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            bubbleText.setTextAlign(Paint.Align.CENTER);
            sv.getViewTreeObserver().addOnScrollChangedListener(() -> {
                if (!persistent) {
                    if (!shown) { shown = true; animate().cancel(); animate().alpha(1f).setDuration(150).start(); }
                    mainHandler.removeCallbacks(hideTask);
                    mainHandler.postDelayed(hideTask, 1100);
                }
                invalidate();
            });
        }
        // Q49：computeVerticalScroll* 为 protected，改用子视图高度/自身高度/ scrollY 公开量自算
        int contentH() { return target.getChildCount() > 0 ? target.getChildAt(0).getHeight() : 0; }
        int maxScroll() {
            return Math.max(0, contentH() - target.getHeight());
        }
        float thumbH() {
            int h = getHeight(); if (h <= 0) return dp(getContext(), 34);
            int range = contentH();
            int extent = target.getHeight();
            if (range <= 0) return h;
            float th = h * ((float) extent / (float) range);
            return Math.max(dp(getContext(), 34), Math.min(h, th));
        }
        boolean scrollableEnough() {
            // 混合版 syncSbar：maxScroll 不足半屏不显；日志框 persistent 例外（有滚动即显）
            int extent = target.getHeight();
            return maxScroll() > (persistent ? 1 : extent * 0.5f);
        }
        @Override protected void onDraw(Canvas cv) {
            super.onDraw(cv);
            if (target == null || getHeight() <= 0 || !scrollableEnough()) return;
            float th = thumbH();
            int max = maxScroll();
            float p = max > 0 ? (float) target.getScrollY() / (float) max : 0f;
            float top = p * (getHeight() - th);
            // Q76：玻璃胶囊条——8–10dp 可抓宽度、清晰明亮，拖时 10dp、滚动显形期 9dp、常显 8dp
            float w = dp(getContext(), dragging ? 10 : 8.5f);
            float right = getWidth() - dp(getContext(), 4);
            float left = right - w;
            float rad = w / 2f;
            Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
            bg.setStyle(Paint.Style.FILL);
            bg.setColor(Color.argb(dragging ? 235 : 210, 255, 255, 255));
            cv.drawRoundRect(new RectF(left, top, right, top + th), rad, rad, bg);
            Paint bd = new Paint(Paint.ANTI_ALIAS_FLAG);
            bd.setStyle(Paint.Style.STROKE);
            bd.setStrokeWidth(dp(getContext(), 1));
            bd.setColor(Color.argb(150, 255, 255, 255));
            cv.drawRoundRect(new RectF(left + dp(getContext(), .5f), top + dp(getContext(), .5f), right - dp(getContext(), .5f), top + th - dp(getContext(), .5f)), rad, rad, bd);
            Paint core = new Paint(Paint.ANTI_ALIAS_FLAG);
            core.setStyle(Paint.Style.FILL);
            core.setColor(Color.argb(dragging ? 120 : 80, 10, 92, 214));
            cv.drawRoundRect(new RectF(left + dp(getContext(), 2), top + dp(getContext(), 2), right - dp(getContext(), 2), top + th - dp(getContext(), 2)), Math.max(1, rad - dp(getContext(), 2)), Math.max(1, rad - dp(getContext(), 2)), core);
            if (dragging) {
                String txt = Math.round(p * 100) + "%";
                float bw = dp(getContext(), 46), bh = dp(getContext(), 26);
                float bx = right - w - dp(getContext(), 10) - bw;
                float by = top + th / 2f - bh / 2f;
                cv.drawRoundRect(new RectF(bx, by, bx + bw, by + bh), dp(getContext(), 10), dp(getContext(), 10), bubblePaint);
                cv.drawText(txt, bx + bw / 2f, by + bh / 2f + dp(getContext(), 4.5f), bubbleText);
            }
        }
        void jumpTo(float y) {
            int max = maxScroll(); if (max <= 0) return;
            float th = thumbH();
            float r = (y - th / 2f) / Math.max(1f, getHeight() - th);
            r = Math.max(0f, Math.min(1f, r));
            target.scrollTo(0, Math.round(r * max));
            invalidate();
        }
        @Override public boolean onTouchEvent(MotionEvent e) {
            if (target == null || !scrollableEnough()) return false;
            // Q49：非拖动态且已淡出时不拦截右缘点击（瓷砖/＋钮优先），条显形期内才可抓
            if (!persistent && !shown && !dragging) return false;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    dragging = true;
                    getParent().requestDisallowInterceptTouchEvent(true);
                    mainHandler.removeCallbacks(hideTask);
                    if (!shown) { shown = true; animate().cancel(); animate().alpha(1f).setDuration(120).start(); }
                    jumpTo(e.getY());
                    invalidate();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (dragging) { jumpTo(e.getY()); return true; }
                    return false;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    dragging = false;
                    getParent().requestDisallowInterceptTouchEvent(false);
                    if (!persistent) { mainHandler.removeCallbacks(hideTask); mainHandler.postDelayed(hideTask, 1100); }
                    invalidate();
                    return true;
                default: return super.onTouchEvent(e);
            }
        }
    }
    // Q49：把可拖拽滚动条挂到宿主 FrameLayout 右侧；top/bottom 边距即轨道范围，绝不探进 dock/手势条
    DragBarView attachDragBar(FrameLayout host, ScrollView sv, boolean persistent, int topDp, int bottomDp) {
        sv.setVerticalScrollBarEnabled(false);
        DragBarView bar = new DragBarView(this, sv, persistent);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(this, 32), ViewGroup.LayoutParams.MATCH_PARENT);
        lp.gravity = Gravity.RIGHT | Gravity.TOP;
        lp.topMargin = dp(this, topDp);
        lp.bottomMargin = dp(this, bottomDp) + navBarH(); // Q49/Q26：轨道下止于 dock 上沿，绝不探进手势小白条区
        lp.rightMargin = dp(this, 2);
        host.addView(bar, lp);
        return bar;
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
        topFab.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(ANIM_DUR_FADE)
            .setInterpolator(ANIM_ENTER).start();
    }
    void hideTopFab() {
        if (topFab == null || !topFabShown) return;
        topFabShown = false;
        final View fab = topFab;
        fab.animate().cancel();
        fab.animate().alpha(0f).scaleX(0.85f).scaleY(0.85f).setDuration(ANIM_DUR_SHEET_OUT)
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
        boolean covered = isChromeCovered();
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
        // Q53：改用与搜索/筛选钮同一 fabFrostWash() 提亮层，三钮黑底白底同一口径。
        fab.addView(fabFrostWash(), new FrameLayout.LayoutParams(
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
    // Q70：无图占位面按卡 id 哈希固定取色（同卡恒定、不随机乱跳，全局一套色板）
    // Q71：自动配色两套风格——深色沉稳系（Q70 原 10 色）与浅色柔光系（默认），设置二选一全局生效
    static final int[][] PLACEHOLDER_PALETTE = {
        {0x2B4C7E, 0x4A7BB5}, {0x1F6B6B, 0x3A9A8C}, {0x4A3F78, 0x7A6BA5},
        {0x6B2A3A, 0x9E4A5E}, {0x2E5A3C, 0x4E8A5F}, {0x2F3A4A, 0x55677F},
        {0x6B4A2F, 0x9A7350}, {0x343A7A, 0x5A62B5}, {0x1E4A5F, 0x2F7A9A},
        {0x5A2A4E, 0x8A4A78}
    };
    static final int[][] PLACEHOLDER_PALETTE_LIGHT = {
        {0xEAF2FD, 0xD3E4FA}, {0xE7F5F0, 0xC9EADF}, {0xF0EBFA, 0xDCD0F2},
        {0xFCEBF0, 0xF4CBD6}, {0xEBF6ED, 0xCDE7D2}, {0xEEF1F6, 0xD3DCEA},
        {0xFBF2E4, 0xF1DDBE}, {0xEAECFB, 0xCCD2F2}, {0xE8F3F9, 0xC6E1F0},
        {0xF9EBF4, 0xEFCCE1}
    };
    static int placeholderIdx(String id) {
        if (id == null || id.isEmpty()) return 0;
        return Math.abs(id.hashCode()) % PLACEHOLDER_PALETTE.length;
    }
    // Q71 状态：placeholder_style=light(默认)/dark；placeholder_custom_enabled 开关；单卡自选存色板下标
    String placeholderStyle = "light";
    boolean placeholderCustomEnabled = false;
    java.util.Map<String, Integer> placeholderCustom = new java.util.HashMap<>();
    View placeholderPickerView = null;
    boolean placeholderDirty = false;
    // Q72 外观：主题色与深色模式与卡面配色（Q71）严格分开、独立保存独立生效。
    // dark_mode_pref: system(默认)/light/dark；theme_color_key: blue(默认)/teal/violet/green/orange。
    // 只染界面强调元素（选中态/开关/链接/评分强调/底栏选中），绝不改卡面图与占位底色。
    String darkModePref = "system";
    String themeColorKey = "blue";
    static final String[][] THEME_OPTS = {{"blue","蓝"},{"teal","青绿"},{"violet","紫"},{"green","翠绿"},{"orange","橙"}};
    void loadAppearancePrefs() {
        try {
            darkModePref = prefs == null ? "system" : prefs.getString("dark_mode", "system");
            if (!"light".equals(darkModePref) && !"dark".equals(darkModePref)) darkModePref = "system";
            themeColorKey = prefs == null ? "blue" : prefs.getString("theme_color", "blue");
            boolean ok = false;
            for (String[] o : THEME_OPTS) if (o[0].equals(themeColorKey)) ok = true;
            if (!ok) themeColorKey = "blue";
        } catch (Throwable ignored) { darkModePref = "system"; themeColorKey = "blue"; }
    }
    boolean darkEff() {
        if ("dark".equals(darkModePref)) return true;
        if ("light".equals(darkModePref)) return false;
        try {
            int m = getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
            return m == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        } catch (Throwable ignored) { return false; }
    }
    // 强调色：深色档同色相降饱和、提明度保对比（Q72 参数口径）
    int accentColor() {
        boolean d = darkEff();
        switch (themeColorKey) {
            case "teal": return d ? Color.rgb(0x5E,0xC8,0xB4) : Color.rgb(0x0E,0x7C,0x7B);
            case "violet": return d ? Color.rgb(0xB3,0x9D,0xDB) : Color.rgb(0x6C,0x4B,0xD8);
            case "green": return d ? Color.rgb(0x7E,0xD6,0x9B) : Color.rgb(0x1D,0x8A,0x49);
            case "orange": return d ? Color.rgb(0xFF,0xB2,0x6B) : Color.rgb(0xC7,0x5A,0x00);
            default: return d ? Color.rgb(0x6E,0xB3,0xFF) : Color.rgb(0x0A,0x5C,0xD6);
        }
    }
    int accentSoftBg() {
        return darkEff() ? Color.argb(56, Color.red(accentColor()), Color.green(accentColor()), Color.blue(accentColor()))
                         : Color.rgb(0xE8,0xF1,0xFD);
    }
    // 语义色板（Q72）：底/面/浮层/主字/次字/禁用/边线，深浅两套，禁止各页再硬编码白底黑字时优先走这里
    int colBg() { return darkEff() ? Color.rgb(0x12,0x12,0x12) : Color.rgb(0xF2,0xF3,0xF7); }
    int colSurface() { return darkEff() ? Color.rgb(0x1E,0x1E,0x1E) : Color.WHITE; }
    int colSheet() { return darkEff() ? Color.rgb(0x23,0x23,0x23) : Color.WHITE; }
    int colText() { return darkEff() ? Color.argb(222,255,255,255) : Color.rgb(0x1C,0x1C,0x1E); }
    int colText2() { return darkEff() ? Color.argb(153,255,255,255) : Color.rgb(0x8E,0x8E,0x93); }
    int colText3() { return darkEff() ? Color.argb(97,255,255,255) : Color.rgb(0xAE,0xAE,0xB2); }
    int colDivider() { return darkEff() ? Color.argb(26,255,255,255) : Color.argb(13,20,30,60); }
    int colChipOff() { return darkEff() ? Color.rgb(0x2A,0x2A,0x2E) : Color.rgb(0xEE,0xF1,0xF6); }
    // Q73 unified true-glass spec (all floating pieces share this): frozen/live snapshot blur
    // (applyGlass blur13 + saturate1.65, aligned to the tile +/check frost the user approved in Q47)
    // + ONLY a thin tint over it + a fixed light wash + a soft 1dp edge. Tint must stay thin and
    // see-through (never a thick milky block, never Q34 solid white plastic); dark mode swaps to a
    // dark thin tint. Pieces: float toast/undo bar, more-menu, detail close, card +/check (Q47),
    // search/filter fabs, top fab, dock, search capsules. Sampling stays Q29/Q41 static-band:
    // refresh on settle/tab-switch only, zero capture while scrolling, no Bitmap.recycle (Q21).
    GradientDrawable glassTintDrawable(float radiusDp, boolean oval) {
        GradientDrawable g;
        if (darkEff()) {
            g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.argb(96, 52, 52, 58), Color.argb(84, 40, 40, 46), Color.argb(76, 34, 34, 40)});
        } else {
            g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.argb(84, 255, 255, 255), Color.argb(72, 246, 249, 253), Color.argb(64, 238, 244, 250)});
        }
        if (oval) g.setShape(GradientDrawable.OVAL); else g.setCornerRadius(dp(this, radiusDp));
        g.setStroke(dp(this, 1), darkEff() ? Color.argb(44, 255, 255, 255) : Color.argb(110, 255, 255, 255));
        return g;
    }
    GradientDrawable glassWashDrawable(float radiusDp, boolean oval) {
        GradientDrawable g;
        if (darkEff()) {
            g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.argb(34, 255, 255, 255), Color.argb(22, 255, 255, 255)});
        } else {
            g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.argb(96, 255, 255, 255), Color.argb(78, 248, 250, 253)});
        }
        if (oval) g.setShape(GradientDrawable.OVAL); else g.setCornerRadius(dp(this, radiusDp));
        g.setStroke(dp(this, 1), darkEff() ? Color.argb(36, 255, 255, 255) : Color.argb(120, 255, 255, 255));
        return g;
    }
    View glassWashView(float radiusDp, boolean oval) {
        View wash = new View(this);
        wash.setClickable(false); wash.setFocusable(false);
        wash.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        wash.setBackground(glassWashDrawable(radiusDp, oval));
        return wash;
    }
    void glassClip(View v, final float radiusDp, final boolean oval) {
        try {
            v.setClipToOutline(true);
            v.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override public void getOutline(View vv, android.graphics.Outline o) {
                    if (oval) o.setOval(0, 0, Math.max(1, vv.getWidth()), Math.max(1, vv.getHeight()));
                    else o.setRoundRect(0, 0, Math.max(1, vv.getWidth()), Math.max(1, vv.getHeight()), dp(vv.getContext(), radiusDp));
                }
            });
        } catch (Throwable ignored) {}
    }
    // 切深色先套色再显页：根底色与状态/导航栏图标明暗在建页前就定，不许闪白
    void applyAppearanceChrome() {
        try {
            if (rootView != null) rootView.setBackgroundColor(colBg());
            Window w = getWindow();
            int flags = w.getDecorView().getSystemUiVisibility();
            if (darkEff()) flags &= ~(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
            else flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            w.getDecorView().setSystemUiVisibility(flags);
        } catch (Throwable ignored) {}
    }
    void loadPlaceholderPrefs() {
        try {
            placeholderStyle = prefs == null ? "light" : prefs.getString("placeholder_style", "light");
            if (!"dark".equals(placeholderStyle)) placeholderStyle = "light";
            placeholderCustomEnabled = prefs != null && prefs.getBoolean("placeholder_custom_enabled", false);
            placeholderCustom = new java.util.HashMap<>();
            String raw = prefs == null ? null : prefs.getString("placeholder_custom_json", "{}");
            org.json.JSONObject o = new org.json.JSONObject(raw == null || raw.isEmpty() ? "{}" : raw);
            java.util.Iterator<String> ks = o.keys();
            while (ks.hasNext()) { String k = ks.next(); int v = o.optInt(k, -1); if (v >= 0 && v < PLACEHOLDER_PALETTE.length) placeholderCustom.put(k, v); }
        } catch (Throwable ignored) { placeholderCustom = new java.util.HashMap<>(); }
    }
    void savePlaceholderCustom() {
        try {
            org.json.JSONObject o = new org.json.JSONObject();
            for (java.util.Map.Entry<String, Integer> e : placeholderCustom.entrySet()) o.put(e.getKey(), e.getValue());
            if (prefs != null) prefs.edit().putString("placeholder_custom_json", o.toString()).apply();
        } catch (Throwable ignored) {}
    }
    int placeholderIdxFor(String id) {
        if (placeholderCustomEnabled && id != null && placeholderCustom.containsKey(id)) return placeholderCustom.get(id);
        return placeholderIdx(id);
    }
    GradientDrawable placeholderGradFor(String id, float radiusDp, Context c) {
        int idx = placeholderIdxFor(id);
        int[] pair;
        if (placeholderCustomEnabled && id != null && placeholderCustom.containsKey(id)) pair = PLACEHOLDER_PALETTE[idx];
        else pair = ("dark".equals(placeholderStyle) ? PLACEHOLDER_PALETTE : PLACEHOLDER_PALETTE_LIGHT)[idx];
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
            new int[]{ Color.rgb(Color.red(pair[0]), Color.green(pair[0]), Color.blue(pair[0])),
                       Color.rgb(Color.red(pair[1]), Color.green(pair[1]), Color.blue(pair[1])) });
        g.setCornerRadius(dp(c, radiusDp));
        return g;
    }
    static boolean hasOrgBadge(String org) {
        if (org == null) return false;
        String o = org.trim();
        return o.equals("unionpay") || o.equals("mastercard") || o.equals("mastercard-nucc")
            || o.equals("visa") || o.equals("jcb") || o.equals("amex-cn");
    }
    static int orgBadgeWidthDp(String org) {
        if (org == null) return 36;
        String o = org.trim();
        if (o.equals("mastercard-nucc")) return 54;
        if (o.equals("unionpay") || o.equals("visa") || o.equals("amex-cn")) return 44;
        if (o.equals("jcb")) return 40;
        return 36; // mastercard 双圆
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
    // Q45：贴底窗专用裁切——窗体背景虽已仅顶部圆角，但默认 outline 取自 GradientDrawable 时
    // 只认统一半径、会把底部两角也裁圆，角下露出玻璃/遮罩即用户截图里的黑三角。API 29+ 用
    // 「顶圆底直」的 Path 轮廓让裁切与阴影都只圆顶部；低版本回落统一圆角（旧行为，不冒新崩点）。
    static void topSheetClip(final View v, final float radiusDp, final Context c) {
        try {
            final float r = dp(c, radiusDp);
            v.setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View view, Outline outline) {
                    int w = Math.max(1, view.getWidth()), h = Math.max(1, view.getHeight());
                    if (Build.VERSION.SDK_INT >= 29) {
                        Path p = new Path();
                        p.moveTo(0, h);
                        p.lineTo(0, r);
                        p.quadTo(0, 0, r, 0);
                        p.lineTo(w - r, 0);
                        p.quadTo(w, 0, w, r);
                        p.lineTo(w, h);
                        p.close();
                        outline.setPath(p);
                    } else {
                        outline.setRoundRect(0, 0, w, h, r);
                    }
                }
            });
            v.setClipToOutline(true);
        } catch (Throwable ignored) { /* 个别机型 outline 异常时保留原裁切，不为修角冒崩点 */ }
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
    // Q33：卡面圆角照实体银行卡模具——半径按显示图宽约 4% 取（Q33 钉的 3.5–4% 量级取上沿，
    // 对照混合版 styles.css .p-slide img 的 12px 固定值在全宽图上约合 3.5%，原生旧值 12dp 恰卡下沿显尖）；
    // 随图宽缩放、夹在 8–24dp，详情大图/瓷砖/无图占位共用同一口径，肉眼明确圆润。
    static float cardRadiusDp(float widthDp) {
        float r = widthDp * 0.04f;
        if (r < 8f) r = 8f;
        if (r > 24f) r = 24f;
        return r;
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
            glassSnapStale = false; // Q63：新帧已落地，live 层可重新垫图
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
            // Q63：切页后新帧未落地前，live 层不许把旧页快照再垫回来（那正是旧页文字在新页胶囊/钮位置糊出残影的来源）；
            // 保持已清空的染色兜底，等 refreshLiveGlass 抓到新页定格帧再垫。frozen 浮窗升起时已自抓当前帧，不受此限。
            if (glassSnapStale && "live".equals(iv.getTag())) return;
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
            cm.setSaturation(1.65f); // Q29：1.4→1.65 往混合版 saturate(2) 靠，让身后颜色透进来（彩色毛玻璃不死白）
            pt.setColorFilter(new ColorMatrixColorFilter(cm));
            android.graphics.Matrix m = new android.graphics.Matrix();
            m.setTranslate(-left * s, -top * s);
            cv.drawBitmap(full, m, pt);
            // Q37：live 面换帧交叉淡入 220ms（治用户 20:03 批的停稳硬跳色），不许 setImageBitmap 硬切；
            // frozen 浮窗升起只 apply 一次、沿用直切。Q21 纪律：旧图只解引用不 recycle。
            // Q41：live 面优先走条带画布——停稳这一帧先把条带按当前 scrollY 绘进复用画布，
            // 再交叉淡入到画布帧；滚动期 followBandScroll 只平移重绘同一画布、底色实时跟随。
            Bitmap frameBmp = out;
            if ("live".equals(iv.getTag()) && glassBand != null && bandSv == bandScroll()) {
                if (drawBandFrame(iv)) {
                    Bitmap cb = bandCanvases.get(iv);
                    if (cb != null && !cb.isRecycled()) frameBmp = cb;
                }
            }
            android.graphics.drawable.Drawable prevD = iv.getDrawable();
            boolean prevIsFrame = prevD instanceof android.graphics.drawable.BitmapDrawable
                && ((android.graphics.drawable.BitmapDrawable) prevD).getBitmap() == frameBmp;
            if ("live".equals(iv.getTag()) && prevD != null && !prevIsFrame) {
                android.graphics.drawable.BitmapDrawable nd = new android.graphics.drawable.BitmapDrawable(iv.getResources(), frameBmp);
                android.graphics.drawable.TransitionDrawable td = new android.graphics.drawable.TransitionDrawable(
                    new android.graphics.drawable.Drawable[]{ prevD, nd });
                td.setCrossFadeEnabled(true);
                iv.setImageDrawable(td);
                td.startTransition(220);
            } else if (!prevIsFrame) {
                iv.setImageBitmap(frameBmp);
            }
            glassCrops.put(iv, out); // Q21：旧裁片只解引用不 recycle（黑匣子定案：显示列表在用时 recycle 必崩，见 noteGlassFailure）
            if (Build.VERSION.SDK_INT >= 31) {
                // Q29：模糊 11→13、配合降采样快照呈彩色高斯柔糊；快照本身已 0.2 降采样，半径不许再堆到乳白糊墙，
                // 隔着玻璃要能认出身后卡片的颜色与大致形状（Q16/Q18 的通透红线继续有效）。
                try { iv.setRenderEffect(RenderEffect.createBlurEffect(13f, 13f, Shader.TileMode.CLAMP)); }
                catch (Throwable t) { noteGlassFailure(); }
            }
            noteGlassSuccess();
        } catch (Throwable t) {
            noteGlassFailure();
            try { iv.setImageBitmap(null); if (Build.VERSION.SDK_INT >= 31) iv.setRenderEffect(null); } catch (Throwable ignored) {}
        }
    }

    /**
     * Q63 切页清旧帧：showTab 一进来先执行——live 玻璃层（底栏/悬浮钮/回顶/搜索胶囊）全部
     * 清空图像退回各自的半透染色兜底，旧快照打 stale，条带（Q41）一并作废防 followBandScroll
     * 把旧页条带平移绘回新页；待决的停稳刷新任务摘除，改由 showTab 末尾代次守卫的一次
     * 延迟刷新在新页淡入落定（旧页已摘除）后抓干净新帧。Q21 纪律：只解引用不 recycle。
     */
    void clearLiveGlassForTabSwitch() {
        glassTabGen++;
        glassSnapStale = true;
        glassTabSwitchMs = android.os.SystemClock.uptimeMillis();
        try { mainHandler.removeCallbacks(glassRefreshTask); } catch (Throwable ignored) {}
        glassBand = null; bandSv = null; bandRecalibPending = false; // 旧页条带作废，滚动跟随自然停摆
        for (ImageView iv : new java.util.ArrayList<>(glassViews)) {
            if (!"live".equals(iv.getTag())) continue;
            try { iv.setImageDrawable(null); } catch (Throwable ignored) {}
        }
    }

    /** Q29：静态帧刷新——只在停稳/切页这两个时刻调用（底栏/悬浮钮/回顶/搜索胶囊共用一帧）；
     *  有浮窗在场时不刷，浮窗用的是各自升起时的冻结快照。 */
    void refreshLiveGlass() {
        // Q37（推翻 Q34 纯白实底，用户 20:24–20:27 拍板回老图毛玻璃）：dock/悬浮钮/回顶/搜索胶囊改静态定格毛玻璃——
        // 糊一帧定住、滚动中绝不调用本函数换帧；只在停稳（scheduleGlassRefresh 650ms 防抖跑完）与切页两个时刻更新，
        // 换帧经 applyGlass 的 220ms 交叉淡入，不许硬跳色。浮窗在场不刷（浮窗用各自冻结帧）。
        if (glassDisabled || glassCapturing || rootView == null || rootView.getWidth() <= 0) return;
        // Q63：切页淡入窗（280ms）内让路——此刻抓图必混入正在淡出的旧页，等 showTab 的代次守卫延迟刷新抓定格帧。
        if (glassSnapStale && android.os.SystemClock.uptimeMillis() - glassTabSwitchMs < 280) return;
        // Q21 ③：底栏拖动/弹簧进行中不做整屏抓图——capture 是全树 draw，正是滑动发卡与 MOVE 被饿死的主因之一；落稳后防抖任务会补上最终帧。
        if (navDragging || navSpringRunning) return;
        if (cardMenuPop != null || filterSheet != null || wizardOpen || aboutOpen
            || detailCard != null || welcomeOpen || changelogOpen) return;
        captureGlassSnapshot();
        rebuildBand(); // Q41：停稳/切页帧顺带生成条带，滚动期靠它平移跟随（失败自动回落静态帧）
        for (ImageView iv : new java.util.ArrayList<>(glassViews)) {
            if ("live".equals(iv.getTag()) && iv.isAttachedToWindow()) applyGlass(iv);
        }
    }

    void scheduleGlassRefresh() {
        // Q37：滚动事件里绝不抓图——每次事件只重置防抖计时，滚动态势下计时永不跑完即滚动零采样；
        // 静默停稳满 650ms 才补一帧静态毛玻璃（与 Q29 同路线、Q34 的空转已废）。
        // Q21：底栏手势优先——拖动/弹簧期间连排队都免了，避免手势一结束就被积压的抓图任务堵住切页。
        if (navDragging || navSpringRunning) return;
        mainHandler.removeCallbacks(glassRefreshTask);
        mainHandler.postDelayed(glassRefreshTask, 650);
    }

    // ---------- Q41 条带跟随（底栏玻璃底色滑动实时跟随） ----------
    /** 跟随源：当前页长列表；详情/更新日志覆盖层在场时 chrome 已退场，不跟随。 */
    ScrollView bandScroll() {
        if (detailCard != null || changelogOpen) return null;
        switch (tab) {
            case "mine": return mineScrollView;
            case "student": return studentScroll;
            case "news": return newsScroll;
            case "settings": return settingsScroll;
            default: return homeScroll;
        }
    }

    /**
     * 生成条带快照：把当前页滚动内容在「chrome 带身后文档区 ± 行程裕量」范围内按 0.2 降采样
     * 渲染成一条低清带（高约 420dp，覆盖约 3 倍 dock 高加上下裕量）。只画滚动内容本体，
     * 不含 dock/悬浮钮等 chrome。只在停稳/切页（refreshLiveGlass）与覆盖将尽校准时调用，
     * 滚动跟随期绝不调用。失败即 glassBand=null 回落 Q37。
     */
    void rebuildBand() {
        try {
            if (glassDisabled || rootView == null || rootView.getWidth() <= 0 || rootView.getHeight() <= 0) { glassBand = null; return; }
            ScrollView sv = bandScroll();
            if (sv == null || sv.getChildCount() == 0) { glassBand = null; bandSv = null; return; }
            View inner = sv.getChildAt(0);
            int contentH = inner.getHeight();
            if (contentH <= 0) { glassBand = null; bandSv = null; return; }
            int bandH = Math.min(dp(this, 420), contentH);
            // chrome 带（dock+悬浮钮列）身后的文档行：以屏幕底部上方约 260dp 为带心，上下留行程裕量
            int zoneDocY = sv.getScrollY() + rootView.getHeight() - dp(this, 260);
            int top = Math.max(0, Math.min(zoneDocY - dp(this, 90), Math.max(0, contentH - bandH)));
            int bw = Math.max(1, Math.round(rootView.getWidth() * bandScale));
            int bh = Math.max(1, Math.round(bandH * bandScale));
            Bitmap b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(b);
            cv.scale(bandScale, bandScale);
            // Q57：画布必须与 drawBandFrame 同一文档坐标系——child 在文档中的原点是
            // (inner.getLeft(), inner.getTop())（含 sv padding 与 child margin 的 layout 结果），
            // inner.draw 自带一层同值 translate，故外层再补 -原点，让文档行 Y 落在画布 (Y-top) 处；
            // 现五页原点均为 0，此校正不改现有行为、只根治换算口径。
            cv.translate(-inner.getLeft(), -top - inner.getTop());
            inner.draw(cv); // 位图设备边界天然裁剪，只画带内行
            glassBand = b; // Q21：旧带只解引用不 recycle
            bandDocTopPx = top;
            bandHeightPx = bandH;
            bandChildLeftPx = inner.getLeft();
            bandInnerH = contentH; // Q57：内容指纹，高度变了（分析卡展开/增删卡）旧带即作废
            bandSv = sv;
            bandLastBuildMs = android.os.SystemClock.uptimeMillis();
        } catch (Throwable t) {
            glassBand = null; bandSv = null; // 回落 Q37 静态帧
        }
    }

    /**
     * 把条带按当前 scrollY 平移绘制进某层 live 玻璃的复用画布（只 Canvas 平移、不触发排版）。
     * 返回 false = 条带已覆盖不到本层（覆盖将尽），调用方决定是否校准。
     */
    boolean drawBandFrame(ImageView iv) {
        try {
            if (iv == null || !iv.isAttachedToWindow() || glassBand == null || glassBand.isRecycled()
                || rootView == null || rootView.getWidth() <= 0) return false;
            ScrollView sv = bandScroll();
            if (sv == null || sv != bandSv) return false;
            int wpx = iv.getWidth(), hpx = iv.getHeight();
            if (wpx <= 0 || hpx <= 0) return false;
            int cw = Math.max(1, Math.round(wpx * bandScale));
            int ch = Math.max(1, Math.round(hpx * bandScale));
            Bitmap cvs = bandCanvases.get(iv);
            if (cvs == null || cvs.isRecycled() || cvs.getWidth() != cw || cvs.getHeight() != ch) {
                cvs = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888);
                bandCanvases.put(iv, cvs); // Q21：旧画布只解引用不 recycle
            }
            int[] il = new int[2]; iv.getLocationOnScreen(il);
            int[] sl = new int[2]; sv.getLocationOnScreen(sl);
            // Q57 纵向对位根治：层身后的文档行 = scrollY +（层顶 − ScrollView 顶），旧式少减 svTop——
            // 学生/我的卡片/资讯三页的 sv 不贴根顶（标题区在其上），docTop 系统性偏大 svTop（约 90–300dp），
            // 在 0.2 降采样带内 srcTop 偏大 18–60px 而层高仅约 17px，频繁越界判 exhausted →
            // 滚动中不换帧（冻色）、停稳走 applyGlass 截帧才跳变，与用户「静止才改变+取样错位」双定性吻合。
            // 改以 sv 实时屏幕位为基准逐像素对位，任意页通用；横向同理减 bandChildLeftPx 回到建带坐标系。
            int docTop = sv.getScrollY() + (il[1] - sl[1]);
            int srcTop = Math.round((docTop - bandDocTopPx) * bandScale);
            if (srcTop < 0 || srcTop + ch > Math.round(bandHeightPx * bandScale)) return false; // 覆盖将尽
            int srcLeft = Math.round(((il[0] - sl[0]) - bandChildLeftPx) * bandScale);
            Canvas cv = new Canvas(cvs);
            cv.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR);
            Paint pt = new Paint(Paint.FILTER_BITMAP_FLAG);
            ColorMatrix cm = new ColorMatrix();
            cm.setSaturation(1.65f); // 与 applyGlass 同口径（往 saturate(2) 靠的彩色毛玻璃）
            pt.setColorFilter(new ColorMatrixColorFilter(cm));
            cv.drawBitmap(glassBand, -srcLeft, -srcTop, pt);
            android.graphics.drawable.Drawable d = iv.getDrawable();
            if (d instanceof android.graphics.drawable.BitmapDrawable
                && ((android.graphics.drawable.BitmapDrawable) d).getBitmap() == cvs) {
                iv.invalidate(); // 同一画布已在显示：只重绘，零 drawable 切换
            } else if (d instanceof android.graphics.drawable.TransitionDrawable) {
                iv.invalidate(); // 停稳交叉淡入进行中：其终帧即本画布，内容已更新，不抢
            } else {
                iv.setImageBitmap(cvs);
            }
            return true;
        } catch (Throwable t) {
            return false; // 跟随坏一帧不许拖死页面；停稳帧仍走 applyGlass
        }
    }

    /** 滚动事件入口：只平移条带绘制，绝不重采样；覆盖将尽时节流排一次校准。 */
    void followBandScroll() {
        if (glassDisabled || glassBand == null || bandSv == null) return;
        if (navDragging || navSpringRunning) return; // Q21：底栏手势优先
        if (isChromeCovered()) return;               // 浮窗在场 chrome 退场，不跟随
        // Q57：内容指纹校验——建带后文档总高变了（我的卡片分析卡展开/收起、自定义卡增删）
        // 旧带内容已过期，继续平移就是「冻旧色」；作废走下方 exhausted 校准（180ms 节流）重建。
        try {
            ScrollView csv = bandScroll();
            if (csv == null || csv != bandSv || csv.getChildCount() == 0
                || csv.getChildAt(0).getHeight() != bandInnerH) {
                triggerBandRecalib();
                return;
            }
        } catch (Throwable ignored) {}
        boolean exhausted = false;
        for (ImageView iv : new java.util.ArrayList<>(glassViews)) {
            if (!"live".equals(iv.getTag())) continue;
            if (!iv.isAttachedToWindow() || iv.getVisibility() != View.VISIBLE) continue;
            View host = glassHosts.get(iv);
            if (host != null && host.getVisibility() != View.VISIBLE) continue;
            if (!drawBandFrame(iv)) exhausted = true;
        }
        if (exhausted) triggerBandRecalib();
    }

    /** Q57：覆盖将尽/内容过期共用一条校准路径（180ms 节流、主线程 post 一次），滚动期绝不逐帧重采样。 */
    void triggerBandRecalib() {
        if (bandRecalibPending
            || android.os.SystemClock.uptimeMillis() - bandLastBuildMs <= 180) return;
        bandRecalibPending = true;
        mainHandler.post(() -> {
            bandRecalibPending = false;
            if (glassDisabled || isChromeCovered() || navDragging || navSpringRunning) return;
            rebuildBand(); // 以当前 scrollY 重新定带
            for (ImageView iv2 : new java.util.ArrayList<>(glassViews)) {
                if ("live".equals(iv2.getTag())) drawBandFrame(iv2);
            }
        });
    }

    // P5 空状态：对照混合版 .empty（居中、灰字、上下 36px 留白），包进白卡（圆角 14）不裸贴页面底
    View emptyState(String s) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(roundRect(colSurface(), 14, this));
        box.setPadding(dp(this, 20), dp(this, 32), dp(this, 20), dp(this, 32));
        TextView t = tv(this, s, 13.5f, colText2(), false);
        t.setGravity(android.view.Gravity.CENTER);
        t.setLineSpacing(dp(this, 3), 1f);
        box.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return box;
    }
    // ---------- 显示偏好（字体/界面大小/高刷/触感） ----------
    // Q42 内置清爽黑体：苹方/PingFang 为苹果专有字体不可打包，改内置免费可商用（SIL OFL）
    // Noto Sans SC 可变字体实例化三档（400/500/700）并子集化（GB2312 一级字+卡库/资讯/
    // 更新日志/界面实际用字+常用标点，共 3940 码位，每档约 1.1MB，合计约 3.4MB）。
    // 缺字由安卓字体回落链自动落系统无衬线，不出豆腐块；资产加载失败整段回落系统无衬线。
    // fontMode：builtin=软件字体（默认，内置 Noto Sans SC）/ system=系统字体。
    // 旧值 default/serif 一律迁移为 builtin——UI 全面禁用衬线（serif），杜绝整窗落宋体。
    static String fontMode = "builtin";
    static float uiScale = 1f;          // 界面大小：0.9 紧凑 / 1 标准 / 1.12 大号（作用于 sp）
    static int hapticLevel = 2; // P3 触感分档：0 关 / 1 轻(10ms) / 2 中(20ms) / 3 强(40ms)，存 prefs haptic_level（旧 boolean haptic 自动迁移）
    static android.graphics.Typeface sansRegularTf = null, sansMediumTf = null, sansBoldTf = null;
    static boolean sansLoadTried = false;
    // Q55 自定义字体：用户导入的单文件 .ttf/.otf 存私有目录，字重由系统合成；异常回退链 自定义→软件字体→系统无衬线
    static android.graphics.Typeface customTf = null;
    static boolean customLoadTried = false;
    static String customFontName = "";
    static final int REQ_PICK_FONT = 7711;
    static final long MAX_CUSTOM_FONT_BYTES = 20L * 1024L * 1024L;

    static void ensureSansLoaded(Context c) {
        if (sansLoadTried) return;
        sansLoadTried = true;
        try { sansRegularTf = android.graphics.Typeface.createFromAsset(c.getAssets(), "fonts/sans-regular.ttf"); } catch (Throwable e) { sansRegularTf = null; }
        try { sansMediumTf = android.graphics.Typeface.createFromAsset(c.getAssets(), "fonts/sans-medium.ttf"); } catch (Throwable e) { sansMediumTf = null; }
        try { sansBoldTf = android.graphics.Typeface.createFromAsset(c.getAssets(), "fonts/sans-bold.ttf"); } catch (Throwable e) { sansBoldTf = null; }
    }

    static android.graphics.Typeface builtinSansTypeface(Context c, int weight) {
        ensureSansLoaded(c);
        android.graphics.Typeface base = weight >= 600 ? sansBoldTf : (weight >= 450 ? sansMediumTf : sansRegularTf);
        if (base == null) base = sansRegularTf != null ? sansRegularTf : android.graphics.Typeface.SANS_SERIF;
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            int w = Math.max(100, Math.min(1000, weight));
            try { return android.graphics.Typeface.create(base, w, false); } catch (Throwable e) { return base; }
        }
        return weight >= 600 ? android.graphics.Typeface.create(base, android.graphics.Typeface.BOLD) : base;
    }

    static File customFontFile(Context c) {
        File d = new File(c.getFilesDir(), "fonts");
        if (!d.exists()) d.mkdirs();
        return new File(d, "custom-font.dat");
    }

    static File customFontTmpFile(Context c) {
        File d = new File(c.getFilesDir(), "fonts");
        if (!d.exists()) d.mkdirs();
        return new File(d, "custom-font.tmp");
    }

    static boolean hasCustomFont(Context c) {
        try { File f = customFontFile(c); return f.exists() && f.length() > 0; } catch (Throwable e) { return false; }
    }

    static android.graphics.Typeface ensureCustomLoaded(Context c) {
        if (customTf != null) return customTf;
        if (customLoadTried) return null;
        customLoadTried = true;
        try {
            File f = customFontFile(c);
            if (f.exists() && f.length() > 0) customTf = android.graphics.Typeface.createFromFile(f);
        } catch (Throwable e) { customTf = null; }
        return customTf;
    }

    static void resetCustomFontCache() { customTf = null; customLoadTried = false; }

    static android.graphics.Typeface customSansTypeface(Context c, int weight) {
        android.graphics.Typeface base = null;
        try { base = ensureCustomLoaded(c); } catch (Throwable ignored) { base = null; }
        if (base == null) return builtinSansTypeface(c, weight); // 回退链：自定义→软件字体（其内部再回落系统无衬线）
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            int w = Math.max(100, Math.min(1000, weight));
            try { return android.graphics.Typeface.create(base, w, false); } catch (Throwable e) { return base; }
        }
        return weight >= 600 ? android.graphics.Typeface.create(base, android.graphics.Typeface.BOLD) : base;
    }

    // Q39 排字字距/行高口径不变；字形来源按 Q42：软件字体=内置 Noto Sans SC 三档，系统字体=系统无衬线。
    static android.graphics.Typeface weightTypeface(Context c, int weight) {
        if ("custom".equals(fontMode)) return customSansTypeface(c, weight);
        if (!"system".equals(fontMode)) return builtinSansTypeface(c, weight);
        android.graphics.Typeface base = android.graphics.Typeface.SANS_SERIF;
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            int w = Math.max(100, Math.min(1000, weight));
            return android.graphics.Typeface.create(base, w, false);
        }
        return weight >= 600 ? android.graphics.Typeface.create(base, android.graphics.Typeface.BOLD)
            : android.graphics.Typeface.create(base, android.graphics.Typeface.NORMAL);
    }

    static TextView tv(Context c, String s, float sp, int color, boolean bold) {
        return tvW(c, s, sp, color, bold ? 700 : 400);
    }
    // Q42：输入框/系统按钮等不走 tv() 的文字控件统一挂当前无衬线，禁衬线落点
    void applyUiFont(TextView t, int weight) { try { t.setTypeface(weightTypeface(t.getContext(), weight)); } catch (Throwable ignored) {} }
    static TextView tvW(Context c, String s, float sp, int color, int weight) {
        TextView t = new TextView(c);
        t.setText(s); t.setTextSize(sp * uiScale); t.setTextColor(color);
        t.setTypeface(weightTypeface(c, weight));
        t.setIncludeFontPadding(false);
        return t;
    }
    // 正文行高：混合版 body line-height 1.5；原生多行正文统一走此助手，不在单行标签上套
    static void bodyLH(TextView t) { t.setLineSpacing(0, 1.45f); }
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
        // Q67：数据线下发的逐维度分项分（score_dims）。只读展示，不在端上按权重重算。
        java.util.LinkedHashMap<String, Double> scoreDims = new java.util.LinkedHashMap<>();
        Double scoreDim(String dim) { return scoreDims.get(dim); }
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

        static java.util.LinkedHashMap<String, Double> parseScoreDims(JSONObject o) {
            java.util.LinkedHashMap<String, Double> out = new java.util.LinkedHashMap<>();
            if (o == null) return out;
            try {
                JSONArray names = o.names();
                if (names == null) return out;
                for (int i = 0; i < names.length(); i++) {
                    String k = names.optString(i, "");
                    if (k.isEmpty() || o.isNull(k)) continue;
                    Object v = o.opt(k);
                    if (v instanceof Number) out.put(k, ((Number) v).doubleValue());
                }
            } catch (Exception ignored) { }
            return out;
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
                    cd.scoreDims = parseScoreDims(o.optJSONObject("score_dims"));
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
        String acctClass = ""; // Q65：用户自有账户标记（""/"一类"/"二类"），纯手填，未设不显示
        String kind = "bank"; // Q82：卡种扩展位（bank 银行卡/phone 电话卡/other 其他），旧卡默认 bank，字段按卡种收窄
        int style;
    }
    static final String[] CARD_KIND_VALS = {"bank", "phone", "other"};
    static final String[] CARD_KIND_LABELS = {"银行卡", "电话卡", "其他卡"};
    static String normCardKind(String s) {
        if ("phone".equals(s) || "other".equals(s)) return s;
        return "bank";
    }
    static String cardKindLabel(String k) {
        String nk = normCardKind(k);
        if ("phone".equals(nk)) return "电话卡";
        if ("other".equals(nk)) return "其他卡";
        return "银行卡";
    }
    static boolean isBankKind(String k) { return "bank".equals(normCardKind(k)); }
    static final int[][] CUSTOM_STYLES = {
        {0x3B82C4, 0x1E3A5F}, {0x8E44AD, 0x2C1A4D}, {0x16A085, 0x0A3D2E},
        {0xE67E22, 0x7E2F0E}, {0x2C3E50, 0x0D1520}, {0xC0392B, 0x4D0F0A}
    };
    static final String[] CUSTOM_ORGS = {"Visa", "万事达", "美国运通", "银联", "JCB"};
    java.util.List<CustomCard> customCards = new ArrayList<>();
    // Q65 我的卡片条目（一张库卡可有多条，分别标一类/二类）：标记只存本机 mine_entries，不写回卡库/OTA
    static class MineEntry {
        String key, cardId, acctClass;
        MineEntry(String k, String id, String cls) { key = k; cardId = id; acctClass = cls == null ? "" : cls; }
    }
    static class MineRow {
        Card card; MineEntry entry;
        MineRow(Card c, MineEntry e) { card = c; entry = e; }
    }
    java.util.List<MineEntry> mineEntries = new ArrayList<>();
    View acctPickerView = null;
    String detailEntryKey = null;
    static final String ACCT_CLASS_HINT = "一类是全功能账户，存款取现转账消费不限额；二类功能受限，日累计转出等限额以发卡行现行规则为准。不标就不显示标签。";
    boolean customOpen = false;
    boolean mineOpen = true; // Q22：混合版 mineOpen（cardbox_mine_open）对应原生 prefs mine_open，默认展开
    LinearLayout customTilesBox = null; // Q56：展开态色带容器，就地重排/重衔接用，不整页重建
    View customFormSheet = null; // Q8：表单改为根层浮卡（原 Dialog 全宽平纸已废）
    View delConfirmSheet = null; // Q52：删卡确认贴底小窗（废系统 AlertDialog）
    boolean delConfirmClosing = false;
    View updateTipSheet = null; boolean updateTipClosing = false;
    View updateConfirmSheet = null; boolean updateConfirmClosing = false;
    String pendingUpdateJson = null; int pendingUpdateVer = -1; boolean updateApplying = false;
    // Q19：自定义卡详情改为与 Q6 数据库详情同规范的贴底浮窗（原居中 AlertDialog 白框已废）
    View customDetailSheet = null;
    View customDetailWrap = null;
    ScrollView customDetailScroll = null;
    boolean customDetailClosing = false;
    CustomCard customDetailCard = null;
    // Q9 NFC 贴卡识别（对照混合版 NativeApp.startNfcRead 与 app.js __onNfcCard 回填）：
    // 只读 EMV 目录取组织/应用名、再读 PAN 取 BIN8+尾号，完整卡号只在内存过一遍不落盘。
    NfcAdapter nfcAdapter = null;
    boolean nfcWaiting = false;
    static class NfcFillTarget {
        EditText name, bank, note;
        String[] orgSel;
        Runnable paintOrgs;
    }
    NfcFillTarget pendingNfcTarget = null;
    Runnable nfcTimeoutTask = null;
    final NfcAdapter.ReaderCallback nfcCallback = new NfcAdapter.ReaderCallback() {
        @Override public void onTagDiscovered(Tag tag) { handleNfcTag(tag); }
    };
    // Q9 在线 BIN 查询（对照混合版 lookupBin/openBinQuery/addBinToMine）：binlist 在线认行，可一键入卡
    View binSheet = null;
    // Q68 在线搜卡·扩展卡库：轻索引（发卡行+卡名+组织+卡种+官网）走 OTA extended.json，不进安装包、不进核心库；
    // 搜索命中可一键转为本机 CustomCard（自有条目），断网仅看缓存并明示。规格空缺如实标注，不编造。
    static class ExtCard {
        String id, name, bank, org, type, url, image;
    }
    View extSheet = null;
    boolean extClosing = false;
    java.util.List<ExtCard> extItems = null;
    boolean extFetchStarted = false;
    boolean extLoading = false;
    String extQuery = "";
    LinearLayout extResultBox = null;
    TextView extMeta = null;
    EditText extInput = null;
    // Q78 展柜：我的卡片纯卡面展示（堆叠/平放自由画布），只用自有卡（收藏条目+自定义卡），
    // 模块自成一块：数据键统一 showcase_ 前缀，入口登记在设置「功能启用」与我的卡片页，关掉不占位。
    View showcaseView = null;
    boolean showcaseClosing = false;
    FrameLayout showcaseBody = null;
    FrameLayout showcaseWorld = null;
    TextView showcaseTitleTv = null;
    org.json.JSONObject showcasePosJson = null;
    Runnable showcaseDriftTask = null;
    float showcaseDriftVx = 0.35f, showcaseDriftVy = 0.22f;
    long showcaseLastTouchMs = 0;
    boolean showcaseDragging = false;
    TextView showcaseStackChip = null, showcaseCanvasChip = null, showcaseGroupChip = null;
    View showcaseDensityRow = null;
    // Q84 电话卡保号管家：模块自成一块（simkeep_ 前缀），设置「功能启用」可关，关掉入口与界面彻底不出现、不占位。
    // 数据模板参考：GitHub 开源卡包类应用的电话卡保号模型（号码/运营商/到期日/动作/周期/提醒提前量），代码自写。
    static class SimKeepItem {
        String id, cardId, number, operator, country, nextDue, action, fee;
        int cycleDays;
    }
    View simkeepView = null;
    boolean simkeepClosing = false;
    FrameLayout simkeepBody = null;
    View simkeepFormSheet = null;
    boolean simkeepFormClosing = false;
    boolean suppressNextChromeRestore = false; // Q12: chain open (menu->detail, addSheet->form) skips one restore to avoid dock flicker
    View addSheetView = null; // Q12: 添加卡片底表，浮窗退场名单内
    String lastBin = null, lastBinScheme = null, lastBinType = null, lastBinBrand = null, lastBinBank = null, lastBinCountry = null;
    // Q43：binlist 剩余字段 + 国家代码（有才存，无不假装）与本地命中卡（点开走 openDetail）
    String lastBinPrepaid = null, lastBinBankUrl = null, lastBinBankPhone = null, lastBinBankCity = null, lastBinCountryAlpha2 = null;
    Card lastBinLocalCard = null;
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
                c.acctClass = normAcctClass(o.optString("cls", ""));
                c.kind = normCardKind(o.optString("kind", "bank"));
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
                        c.acctClass = normAcctClass(o.optString("cls", ""));
                        c.kind = normCardKind(o.optString("kind", "bank"));
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
                o.put("cls", c.acctClass == null ? "" : c.acctClass);
                o.put("kind", c.kind == null ? "bank" : c.kind);
                o.put("style", c.style);
                arr.put(o);
            }
            // Q21：自定义卡是用户资产，apply() 异步落盘在紧接着的崩溃/强杀下可能来不及刷盘；JSON 很小，直接 commit 同步落盘。
            prefs.edit().putString("custom_cards", arr.toString()).commit();
        } catch (Throwable e) { /* 存不下就保持内存中的列表 */ }
    }

    static String normAcctClass(String s) {
        if ("一类".equals(s) || "二类".equals(s)) return s;
        return "";
    }

    // Q65 条目存储：mine_entries 为真相（key/cardId/cls），mine Set 与 mineOrder 仅作旧口径投影同步
    java.util.List<MineEntry> entriesForCard(String cardId) {
        java.util.List<MineEntry> out = new ArrayList<>();
        if (cardId == null || mineEntries == null) return out;
        for (MineEntry e : mineEntries) if (cardId.equals(e.cardId)) out.add(e);
        return out;
    }

    MineEntry findEntryByKey(String key) {
        if (key == null || mineEntries == null) return null;
        for (MineEntry e : mineEntries) if (key.equals(e.key)) return e;
        return null;
    }

    void syncMineProjection() {
        java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();
        java.util.List<String> order = new ArrayList<>();
        if (mineEntries != null) for (MineEntry e : mineEntries) {
            if (e.cardId == null || e.cardId.isEmpty()) continue;
            ids.add(e.cardId); order.add(e.cardId);
        }
        mine = new HashSet<>(ids);
        mineOrder = order;
    }

    void saveMineEntries() {
        try {
            JSONArray arr = new JSONArray();
            if (mineEntries != null) for (MineEntry e : mineEntries) {
                JSONObject o = new JSONObject();
                o.put("key", e.key == null ? "" : e.key);
                o.put("id", e.cardId == null ? "" : e.cardId);
                o.put("cls", e.acctClass == null ? "" : e.acctClass);
                arr.put(o);
            }
            syncMineProjection();
            prefs.edit().putString("mine_entries", arr.toString())
                .putStringSet("mine_ids", new HashSet<>(mine))
                .putString("mine_order", new JSONArray(mineOrder).toString()).commit();
        } catch (Throwable ignored) {}
    }

    void loadMineEntries() {
        mineEntries = new ArrayList<>();
        try {
            if (prefs != null && prefs.contains("mine_entries")) {
                JSONArray arr = new JSONArray(prefs.getString("mine_entries", "[]"));
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    String id = o.optString("id", "");
                    if (id.isEmpty()) continue;
                    String key = o.optString("key", "");
                    if (key.isEmpty()) key = id + "#m" + i;
                    mineEntries.add(new MineEntry(key, id, normAcctClass(o.optString("cls", ""))));
                }
                syncMineProjection();
                return;
            }
        } catch (Throwable ignored) { mineEntries = new ArrayList<>(); }
        // 迁移：旧 mine_ids + mine_order -> 每卡一条未标条目（不推断类别）
        try {
            java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();
            if (mineOrder != null) for (String id : mineOrder) if (id != null && !id.isEmpty() && mine.contains(id)) ids.add(id);
            if (mine != null) for (String id : mine) if (id != null && !id.isEmpty()) ids.add(id);
            int i = 0;
            for (String id : ids) mineEntries.add(new MineEntry(id + "#m" + (i++), id, ""));
            syncMineProjection();
            if (prefs != null) prefs.edit().putString("mine_entries", new JSONArray().toString()).commit();
            saveMineEntries();
        } catch (Throwable ignored) {}
    }

    String newMineEntryKey(String cardId) {
        String base = cardId + "#" + System.currentTimeMillis();
        String k = base; int n = 1;
        while (findEntryByKey(k) != null) k = base + "-" + (n++);
        return k;
    }

    void paintChoiceChip(TextView t, boolean on) {
        if (on) {
            GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.rgb(0x0A, 0x84, 0xFF), Color.rgb(0x00, 0x66, 0xE6)});
            g.setCornerRadius(dp(this, 999));
            t.setBackground(g);
            t.setTextColor(Color.WHITE);
            try { t.setTypeface(weightTypeface(this, 700)); } catch (Throwable ignored) {}
        } else {
            GradientDrawable g = new GradientDrawable();
            g.setColor(Color.rgb(0xF2, 0xF3, 0xF7));
            g.setCornerRadius(dp(this, 999));
            g.setStroke(dp(this, 1), Color.argb(13, 20, 30, 60));
            t.setBackground(g);
            t.setTextColor(Color.rgb(0x1C, 0x1C, 0x1E));
            try { t.setTypeface(weightTypeface(this, 400)); } catch (Throwable ignored) {}
        }
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

    // Q40 对照混合版 restyleTiles/.csk-tile::after 重做衔接：卡带本体只用自身双色 135° 渐变，
    // 底部 48dp 由 customNextFade 竖向淡接下一张顶色（透明→下一张顶色），不用三段对角硬接
    GradientDrawable customBandGradient(int style, boolean first, boolean last) {
        int s = Math.max(0, Math.min(style, CUSTOM_STYLES.length - 1));
        int[] pair = CUSTOM_STYLES[s];
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
            new int[]{styleRgb(pair[0]), styleRgb(pair[1])});
        float r = dp(this, 14);
        if (first && last) g.setCornerRadius(r);
        else if (first) g.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        else if (last) g.setCornerRadii(new float[]{0, 0, 0, 0, r, r, r, r});
        else g.setCornerRadius(0);
        return g;
    }

    // Q40：带底 48dp 衔接层（对照 .csk-tile::after：to bottom, transparent → var(--next)=下一张顶色）
    GradientDrawable customNextFade(int nextStyle) {
        int ns = Math.max(0, Math.min(nextStyle, CUSTOM_STYLES.length - 1));
        int next = styleRgb(CUSTOM_STYLES[ns][0]);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{Color.argb(0, Color.red(next), Color.green(next), Color.blue(next)), next});
        g.setCornerRadius(0);
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

    // Q29 静态毛玻璃（用户 19:26 拍板，推翻 Q16 的实时追色）：全 App 玻璃面统一为静态毛玻璃——
    // 身后颜色透进来的彩色高斯模糊（对照混合版 .dock-glass blur(28px) saturate(2)+rgba(.58) 的彩色透色，
    // 不是固定死白奶糊）。实现纪律：滚动中零截图、不实时重采样；只在页面停稳（滚动事件静默
    // 650ms 后）或切页时更新一帧，保住彩色透色即可，以流畅为先。
    // Q11 地基保留：自研零三方，抓底层快照（0.2 降采样+饱和）垫在玻璃面之下，API 31+ 叠 RenderEffect；
    // frozen 面（各浮窗）升起时抓一次冻结，live 面（底栏/悬浮钮/回顶/搜索胶囊）用停稳/切页帧。
    // Q21 纪律不破：快照与裁片只解引用交系统回收，绝不主动 Bitmap.recycle()。
    final java.util.List<ImageView> glassViews = new java.util.ArrayList<>();
    final java.util.Map<ImageView, View> glassHosts = new java.util.HashMap<>();
    final java.util.Map<ImageView, Bitmap> glassCrops = new java.util.HashMap<>();
    Bitmap glassSnap = null;
    boolean glassCapturing = false;
    // Q63：切页瞬间旧页帧作废标记 + 切页代次。切页时 live 玻璃层先清空退回染色兜底，
    // 旧快照打 stale——applyGlass 对 live 层见 stale 不许再垫旧帧；新页淡入落定后由
    // 代次守卫的一次 refreshLiveGlass 抓干净新帧（抓图时旧页已摘除，不再混帧烤出残影）。
    boolean glassSnapStale = false;
    int glassTabGen = 0;
    long glassTabSwitchMs = 0; // Q63：切页时刻，280ms 淡入窗内其他路径的 refresh 一律让路给代次守卫的那一次
    final Runnable glassRefreshTask = new Runnable() { public void run() { refreshLiveGlass(); } };
    // Q29：旧 Q16 的滚动中 140ms 节流实时重采样（liveGlassTask/lastLiveGlassMs）整套删除——滚动零截图。
    final java.util.Map<ImageView, Integer> glassRetry = new java.util.HashMap<>(); // Q16: layout retry cap per glass layer
    // Q41 底栏玻璃底色滑动实时跟随：条带快照（文档空间）+滚动期 Canvas 平移绘制。
    // 纪律：滚动中零重采样（不 capture/不全屏抓图）、禁止 Bitmap.recycle()（Q21）、只动绘制层不触发排版；
    // 任一环节失败 glassBand=null，行为完整回落 Q37 静态帧，绝不拖死页面。
    Bitmap glassBand = null;              // 当前条带（0.2 降采样，文档空间渲染）
    int bandDocTopPx = 0;                 // 条带顶在文档中的 y（屏幕 px，未降采样）
    int bandHeightPx = 0;                 // 条带实际高度（屏幕 px，未降采样）
    int bandChildLeftPx = 0;              // Q57：child 在文档中的横原点（建带坐标系基准）
    int bandInnerH = -1;                  // Q57：建带时文档总高指纹，变了即旧带过期
    float bandScale = 0.20f;              // 条带降采样比（与 glassSnap 同口径）
    ScrollView bandSv = null;             // 条带所属滚动视图（实例变了即作废）
    final java.util.Map<ImageView, Bitmap> bandCanvases = new java.util.HashMap<>(); // 每层复用画布位图
    long bandLastBuildMs = 0;             // 上次条带校准时刻（覆盖将尽校准节流）
    boolean bandRecalibPending = false;
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
    int navTintIdx = 0;             // Q38: tab index currently tinted as "under the lens" (icon+label follow the lens, not only the settled page)
    View currentPageView;           // Q38: currently displayed page view - only pages crossfade on switch; FABs/sheets in content keep removeAll semantics
    int lastTabIdx = 0;             // Q64: last settled tab index for directional slide
    int tabAnimGen = 0;             // Q64: generation token to cancel stale page animators on rapid taps
    boolean navDragging = false;
    int navDragIdx = -1;
    float navSpringV = 0f;
    boolean navSpringRunning = false;
    // Q21 ①：弹簧只许一条回路——代次令牌 + 任务句柄。新弹簧/取消/按下接管都自增代次并摘除旧任务，
    // 旧 Runnable 即使已被系统派发也会因代次不符立即退出，杜绝两条回路同驱 navPos 互殴空转。
    int navSpringGen = 0;
    Runnable navSpringTask = null;
    float navDownRawX = 0f;
    float navDownRawY = 0f; // Q38: lift-off-dock abort guard for the press-first-move path
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
    View filterSheet = null;
    ScrollView filterScroll = null; // P2e：筛选窗内滚动区（窗框固定不滚，四角不被内容切掉）
    // Q3 悬浮搜索圆钮（首页右侧竖列上钮，玻璃底深色放大镜，点了回顶聚焦顶部搜索框）
    View searchFab = null;
    View addFab = null; // Q7: 我的卡片页右下圆形 ＋（与悬浮钮同层，照混合版 .fab 56dp 蓝圆）
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
    View moreMenuOverlay = null; // Q58：设置页 ⋯ 菜单（从按钮角长出、点外部收回）

    // 筛选状态（Phase 2a-1：与混合版 chipRow 相同的单选切换语义，点已选项再点一次取消）
    String filterType = null;   // "debit" / "credit" / null
    String filterOrg = null;    // org 代码 / null
    String filterStatus = null; // "在发" / "已停发" / null
    String filterScoreStatus = null; // Q69: "rated" / "unrated" / null（与总分 filterScoreStatus 同级过滤）

    // 特点与发卡行（Phase 2a-2，对照 app.js 的 FEATS/featMatch 与 state.bank）
    java.util.Set<String> filterFeats = new java.util.LinkedHashSet<>(); // feat key，多选且 AND（每项都要满足）；保序与标签栏一致
    String filterBank = null; // 发卡行，单选切换（同 chipRow 对 bank 的语义）
    LinearLayout activeFilterBar = null;

    // 排序 / 列数 / 显示方式（Phase 2a-3，对照 app.js applySort/colsNowVal/groupBank+bankOpen）
    String sortMode = null; // null=默认 / score-desc / score-asc / name / bank / dim:<维度名>
    int cols = 2; // 1/2/3/4（Q75 起加四列档）
    boolean groupBank = false;
    java.util.Set<String> bankOpen = new java.util.HashSet<>();
    // Q67：只看这些评分（维度名用数据线 score_dims 的全名；空=维持现行总分展示）
    java.util.Set<String> scoreDimsSel = new java.util.LinkedHashSet<>();
    LinearLayout homeList = null;
    ScrollView homeScroll = null;
    LinearLayout homeHero = null; // Q15：卡库总览英雄卡（仅无搜索/无筛选时显示，同混合版 lib-hero 口径）
    // Q61 下拉刷新（仅首页列表在顶部时接管下拉，松手触发双线检查更新；带轻量指示，不跳顶、不丢位置）
    TextView homePullText = null;
    FrameLayout homePullBar = null;
    boolean homePullTracking = false;
    boolean homePullRefreshing = false;
    float homePullDownY = -1f;
    float homePullDy = 0f;

    static String sortLabel(String v) {
        if ("score-desc".equals(v)) return "评分由高到低";
        if ("score-asc".equals(v)) return "评分由低到高";
        if ("name".equals(v)) return "名称";
        if ("bank".equals(v)) return "银行";
        if (v != null && v.startsWith("dim:")) return "按" + scoreDimShort(v.substring(4)) + "评分";
        return v;
    }

    // Q67 维度顺序：先按《评分表》核心维度，再接备选池；数据里出现的新维度按中文序补在后面。
    static final String[] SCORE_DIM_ORDER = {
        "3DS 支持", "网付支持", "年费与免年费条件", "境外与线上支付能力", "积分与返现价值",
        "货币转换费", "自动购汇", "优惠政策", "权益", "冻结比例", "免息期与取现成本",
        "Apple Pay", "收费情况", "收费与其他持有成本", "办理难度",
        "境外 ATM 取现费", "境外消费返现", "多币种账户/原币支付覆盖", "卡组织等级自带权益",
        "卡面等级", "挂失补卡费", "机场贵宾厅", "高额旅行/航空保险", "酒店与接送机权益",
        "取现额度比例", "分期费率", "年费积分抵扣率", "附属卡政策"
    };
    static int scoreDimOrderIdx(String dim) {
        for (int i = 0; i < SCORE_DIM_ORDER.length; i++) if (SCORE_DIM_ORDER[i].equals(dim)) return i;
        return 1000;
    }
    static String scoreDimShort(String dim) {
        if (dim == null) return "";
        switch (dim) {
            case "3DS 支持": return "3DS";
            case "网付支持": return "网付";
            case "年费与免年费条件": return "年费";
            case "境外与线上支付能力": return "支付能力";
            case "积分与返现价值": return "积分返现";
            case "货币转换费": return "转换费";
            case "自动购汇": return "自动购汇";
            case "优惠政策": return "优惠";
            case "冻结比例": return "冻结";
            case "免息期与取现成本": return "免息取现";
            case "收费情况": return "收费";
            case "收费与其他持有成本": return "持有成本";
            case "境外 ATM 取现费": return "境外取现费";
            case "多币种账户/原币支付覆盖": return "多币种";
            case "卡组织等级自带权益": return "组织权益";
            default: return dim;
        }
    }
    static List<String> scoreDimsAvailCache = null;
    static int scoreDimsAvailStamp = Integer.MIN_VALUE;
    List<String> availableScoreDims() {
        int stamp = Store.dataVersion * 1000003 + Store.all.size();
        if (scoreDimsAvailCache != null && stamp == scoreDimsAvailStamp) return new ArrayList<>(scoreDimsAvailCache);
        java.util.LinkedHashSet<String> found = new java.util.LinkedHashSet<>();
        for (Card c : Store.all) found.addAll(c.scoreDims.keySet());
        List<String> out = new ArrayList<>(found);
        final java.text.Collator zh = java.text.Collator.getInstance(java.util.Locale.CHINA);
        out.sort((a, b) -> {
            int r = Integer.compare(scoreDimOrderIdx(a), scoreDimOrderIdx(b));
            return r != 0 ? r : zh.compare(a, b);
        });
        scoreDimsAvailCache = new ArrayList<>(out);
        scoreDimsAvailStamp = stamp;
        return out;
    }
    List<String> selectedScoreDimsOrdered() {
        List<String> out = new ArrayList<>();
        for (String d : availableScoreDims()) if (scoreDimsSel.contains(d)) out.add(d);
        return out;
    }
    static String formatDimScore(double v) {
        String s = String.format(java.util.Locale.US, "%.2f", v);
        if (s.contains(".")) s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        return s;
    }
    boolean isDimSort() { return sortMode != null && sortMode.startsWith("dim:"); }
    String dimSortName() { return isDimSort() ? sortMode.substring(4) : null; }

    void applySort(List<Card> list) {
        final java.text.Collator zh = java.text.Collator.getInstance(java.util.Locale.CHINA);
        if (isDimSort()) {
            final String dim = dimSortName();
            java.util.Comparator<Card> byDim = (a, b) -> {
                Double av = a.scoreDim(dim), bv = b.scoreDim(dim);
                if (av == null && bv == null) return Double.compare(b.score, a.score);
                if (av == null) return 1; // 未下发该维度分项分的卡排后面，不冒充 0 分
                if (bv == null) return -1;
                int r = Double.compare(bv, av);
                return r != 0 ? r : Double.compare(b.score, a.score);
            };
            if (groupBank) {
                list.sort((a, b) -> {
                    int r = zh.compare(a.bank == null ? "" : a.bank, b.bank == null ? "" : b.bank);
                    return r != 0 ? r : byDim.compare(a, b);
                });
            } else {
                list.sort(byDim);
            }
            return;
        }
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
        if (scoreDimsSel.isEmpty()) e.remove("score_dims_sel"); else e.putStringSet("score_dims_sel", new HashSet<>(scoreDimsSel));
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
        if (filterScoreStatus != null) n++; // Q69
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
        fontMode = prefs.getString("font_mode", "builtin");
        customFontName = prefs.getString("custom_font_name", "");
        if ("custom".equals(fontMode)) {
            // Q55：自定义字体已删/损坏时自动回退软件字体，不带病启动
            if (ensureCustomLoaded(this) == null) {
                fontMode = "builtin";
                try { prefs.edit().putString("font_mode", "builtin").apply(); } catch (Throwable ignored) {}
            }
        } else if (!"system".equals(fontMode) && !"builtin".equals(fontMode)) fontMode = "builtin"; // Q42 迁移：旧 default/serif 统一落软件字体（无衬线）
        uiScale = prefs.getFloat("ui_scale", 1f);
        if (uiScale != 0.9f && uiScale != 1f && uiScale != 1.12f) uiScale = 1f;
        if (prefs.contains("haptic_level")) hapticLevel = prefs.getInt("haptic_level", 2);
        else hapticLevel = prefs.getBoolean("haptic", true) ? 2 : 0; // 旧开关迁移：开→中档
        if (hapticLevel < 0 || hapticLevel > 3) hapticLevel = 2;
        loadPlaceholderPrefs(); // Q71
        loadAppearancePrefs(); // Q72
        applyHighRefresh();
        try { mine = new HashSet<>(prefs.getStringSet("mine_ids", new HashSet<String>())); } catch (Exception e) { mine = new HashSet<>(); }
        sortMode = prefs.getString("sort_mode", null);
        try { scoreDimsSel = new java.util.LinkedHashSet<>(prefs.getStringSet("score_dims_sel", new HashSet<String>())); } catch (Exception e) { scoreDimsSel = new java.util.LinkedHashSet<>(); }
        cols = prefs.getInt("cols", 2); if (cols < 1 || cols > 4) cols = 2;
        groupBank = prefs.getBoolean("group_bank", false);
        try { bankOpen = new HashSet<>(prefs.getStringSet("bank_open", new HashSet<String>())); } catch (Exception e) { bankOpen = new HashSet<>(); }
        loadCustomCards();
        loadMineOrder();
        loadMineEntries();
        Store.load(this);
        scoreDimsSel.retainAll(availableScoreDims()); // Q67：旧 OTA 已下线维度不残留成幽灵选择
        try { nfcAdapter = NfcAdapter.getDefaultAdapter(this); } catch (Throwable ignored) { nfcAdapter = null; }

        FrameLayout root = new FrameLayout(this);
        rootView = root;
        root.setBackgroundColor(colBg()); // Q72

        content = new FrameLayout(this);
        // P1b 浮感修正：内容区不再留底部硬白边，各页滚动视图全高延伸到悬浮条底下，
        // 滚动时内容从半透明条下隐约滑过；最后一项靠各页内衬的底部留白滚出条外。
        content.setPadding(0, 0, 0, 0);
        content.setClipToPadding(false);
        root.addView(content, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        buildNav(root);
        setContentView(root);
        applyAppearanceChrome(); // Q72：先套色再显页，切深色不闪白
        // Q29：滚动只重置停稳计时、滚动中零截图（scheduleGlassRefresh 内 650ms 防抖，见其注释）
        // Q41：滚动期条带平移跟随（只 Canvas 绘制、不重采样，与停稳计时互不干扰）
        root.getViewTreeObserver().addOnScrollChangedListener(() -> { scheduleGlassRefresh(); followBandScroll(); });

        showTab("home");
        if (prefs == null || prefs.getBoolean("auto_check_update", true)) checkDataUpdate(false); // Q62: auto only detects
        if (!prefs.getBoolean("welcomed", false)) showWelcome();
    }

    @Override protected void onResume() {
        super.onResume();
        if (nfcWaiting) enableNfcReader();
    }

    @Override protected void onPause() {
        try { if (nfcAdapter != null) nfcAdapter.disableReaderMode(this); } catch (Throwable ignored) {}
        super.onPause();
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
        // Q73: thin unified tint over the frozen true-blur layer below (was argb170/160 milky block).
        bar.setBackground(glassTintDrawable(16, false));
        if (Build.VERSION.SDK_INT >= 21) bar.setElevation(dp(this, 10));
        bar.setPadding(dp(this, 15), dp(this, 12), dp(this, 15), dp(this, 12));
        TextView txt = tv(this, msg, 14, colText(), false);
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
        // Q73: frozen true-blur of the content directly behind the bar + thin tint + light wash,
        // one smooth rounded clip for body+edge together (no hard bright line). The snapshot is
        // taken now, at the bar's own position, so the blur always matches what is behind it.
        FrameLayout toastWrap = new FrameLayout(this);
        glassClip(toastWrap, 16, false);
        toastWrap.addView(glassLayer(toastWrap, 16, false), new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        toastWrap.addView(glassWashView(16, false), new FrameLayout.LayoutParams(
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
            .setDuration(ANIM_DUR_TOAST_IN).setInterpolator(ANIM_ENTER).start();
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
            .setDuration(ANIM_DUR_TOAST_OUT).setInterpolator(ANIM_EXIT)
            .withEndAction(() -> { if (bar.getParent() instanceof ViewGroup) ((ViewGroup) bar.getParent()).removeView(bar); })
            .start();
    }

    // 收藏切换统一入口：移除出「撤销」（按原顺序恢复，因 mineOrder 未动、重新加入即回原位）、加入给普通提示
    // Q21：收藏集合同走 commit 同步落盘——用户资产不赌 apply() 的异步刷盘窗口（崩溃/强杀紧跟保存时不丢）。
    void persistMineSet() {
        // Q65 后真相在 mineEntries；保留此入口给旧调用，统一转条目落盘
        if (mineEntries != null) saveMineEntries();
        else try { prefs.edit().putStringSet("mine_ids", new HashSet<>(mine)).commit(); } catch (Throwable ignored) {}
    }

    // Q65 加入：弹一类/二类选择（可不标），选完才落条目；同一库卡可再加第二条分别标记
    void addMineEntry(final Card c, final String cls, final Runnable uiRefresh) {
        if (c == null) return;
        mineEntries.add(new MineEntry(newMineEntryKey(c.id), c.id, normAcctClass(cls)));
        saveMineEntries();
        pages.remove("mine");
        if (uiRefresh != null) uiRefresh.run();
        showFloatToast("已加入我的卡片：" + c.name);
    }

    void removeMineEntriesForCard(final Card c, final Runnable uiRefresh) {
        if (c == null) return;
        final java.util.List<MineEntry> snap = new ArrayList<>();
        final java.util.List<Integer> snapIdx = new ArrayList<>();
        for (int i = 0; i < mineEntries.size(); i++) if (c.id.equals(mineEntries.get(i).cardId)) {
            snap.add(mineEntries.get(i)); snapIdx.add(i);
        }
        if (snap.isEmpty()) return;
        mineEntries.removeAll(snap);
        saveMineEntries();
        pages.remove("mine");
        if (uiRefresh != null) uiRefresh.run();
        showFloatToast("已从我的卡片移除：" + c.name, "撤销", () -> {
            for (int i = 0; i < snap.size(); i++) {
                int at = Math.min(snapIdx.get(i), mineEntries.size());
                mineEntries.add(at, snap.get(i));
            }
            saveMineEntries();
            pages.remove("mine");
            if (uiRefresh != null) uiRefresh.run();
            showFloatToast("已恢复：" + c.name);
        });
    }

    void removeSingleMineEntry(final MineEntry e, final Runnable uiRefresh) {
        if (e == null) return;
        final int at = mineEntries.indexOf(e);
        if (at < 0) return;
        mineEntries.remove(at);
        saveMineEntries();
        pages.remove("mine");
        if (uiRefresh != null) uiRefresh.run();
        showFloatToast("已移除这张", "撤销", () -> {
            mineEntries.add(Math.min(at, mineEntries.size()), e);
            saveMineEntries();
            pages.remove("mine");
            if (uiRefresh != null) uiRefresh.run();
            showFloatToast("已恢复");
        });
    }

    void setMineEntryClass(final MineEntry e, final String cls) {
        if (e == null) return;
        e.acctClass = normAcctClass(cls);
        saveMineEntries();
        pages.remove("mine");
    }

    void toggleMineWithToast(final Card c, final Runnable uiRefresh) {
        if (c == null) return;
        if (!entriesForCard(c.id).isEmpty()) removeMineEntriesForCard(c, uiRefresh);
        else openAcctClassPicker(c, "加入我的卡片", false, uiRefresh);
    }

    // 首页加卡钮：未加走选择窗；已加再点出「再加一张 / 移除」窗，不直接整卡移除
    void handleMineAddButton(final Card c, final Runnable uiRefresh) {
        if (c == null) return;
        if (entriesForCard(c.id).isEmpty()) openAcctClassPicker(c, "加入我的卡片", false, uiRefresh);
        else openAcctClassPicker(c, "再加一张", true, uiRefresh);
    }

    TextView acctOptionRow(String label, String desc) {
        TextView t = tv(this, label, 15, Color.rgb(0x1C, 0x1C, 0x1E), true);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(dp(this, 14), dp(this, 11), dp(this, 14), dp(this, 11));
        t.setBackground(rippleBg(Color.rgb(0xF2, 0xF3, 0xF7), 12));
        if (desc != null && !desc.isEmpty()) t.setText(label + "  ·  " + desc);
        return t;
    }

    void closeAcctClassPicker() {
        // Q74：贴底选择窗收起同走 SHEET_OUT 下沉淡出，不许硬消失；连点由 acctPickerView 先置空防重入。
        final View v = acctPickerView;
        acctPickerView = null;
        if (v == null) return;
        if (!(v instanceof ViewGroup) || ((ViewGroup) v).getChildCount() < 2) {
            if (v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v);
            return;
        }
        View wrap = ((ViewGroup) v).getChildAt(((ViewGroup) v).getChildCount() - 1);
        View shade = ((ViewGroup) v).getChildAt(0);
        if (shade != null) animShadeOut(shade);
        if (wrap != null) {
            wrap.animate().cancel();
            wrap.animate().alpha(0f).translationY(dp(this, SHEET_RISE_DP))
                .setDuration(ANIM_DUR_SHEET_OUT).setInterpolator(ANIM_EXIT)
                .withEndAction(() -> { if (v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v); }).start();
            v.postDelayed(() -> { if (v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v); }, ANIM_DUR_SHEET_OUT + 40);
        } else if (v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v);
    }

    // Q65 选择窗：不标/一类/二类三选 + 一句说明；manage=true 时多一条移除已有（全部）
    void openAcctClassPicker(final Card c, final String title, final boolean manage, final Runnable uiRefresh) {
        if (c == null) return;
        closeAcctClassPicker();
        if (rootView == null) { addMineEntry(c, "", uiRefresh); return; }
        final FrameLayout overlay = new FrameLayout(this);
        View shade = new View(this);
        shade.setBackgroundColor(Color.argb(102, 0, 0, 0));
        shade.setAlpha(0f);
        shade.setOnClickListener(v -> closeAcctClassPicker());
        overlay.addView(shade, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout cardBox = new LinearLayout(this);
        cardBox.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable cg = new GradientDrawable();
        cg.setColor(Color.WHITE);
        float rTop = dp(this, 22);
        cg.setCornerRadii(new float[]{rTop, rTop, rTop, rTop, 0, 0, 0, 0});
        cardBox.setBackground(cg);
        if (Build.VERSION.SDK_INT >= 21) { cardBox.setElevation(dp(this, 24)); topSheetClip(cardBox, 22, this); }
        cardBox.setOnClickListener(v -> {});
        cardBox.setPadding(dp(this, 18), dp(this, 18), dp(this, 18), dp(this, 14) + navBarH());
        cardBox.addView(tv(this, title, 17, Color.rgb(0x1C, 0x1C, 0x1E), true));
        TextView sub = tv(this, c.name, 12.5f, Color.rgb(0x8E, 0x8E, 0x93), false);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(this, 4);
        cardBox.addView(sub, subLp);
        final String[] opts = {"", "一类", "二类"};
        final String[] labs = {"不标", "一类", "二类"};
        final String[] descs = {"加入后不显示类别标签", "全功能账户", "功能受限，限额以银行规则为准"};
        for (int i = 0; i < opts.length; i++) {
            final String cls = opts[i];
            TextView row = acctOptionRow(labs[i], descs[i]);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.topMargin = dp(this, 8);
            cardBox.addView(row, rlp);
            row.setOnClickListener(v -> { haptic(); closeAcctClassPicker(); addMineEntry(c, cls, uiRefresh); });
        }
        TextView hint = tv(this, ACCT_CLASS_HINT, 12, Color.rgb(0x8E, 0x8E, 0x93), false);
        hint.setLineSpacing(0, 1.45f);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = dp(this, 12);
        cardBox.addView(hint, hlp);
        TextView glossLink = tv(this, "查看卡片常识 ›", 12.5f, Color.rgb(0x0A, 0x5C, 0xD6), true);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        glp.topMargin = dp(this, 8);
        glossLink.setLayoutParams(glp);
        glossLink.setPadding(0, dp(this, 4), dp(this, 8), dp(this, 4));
        cardBox.addView(glossLink);
        glossLink.setOnClickListener(v -> { haptic(); closeAcctClassPicker(); openGlossaryTerm("acct1"); });
        if (manage) {
            TextView rm = tv(this, "移除已有（全部 " + entriesForCard(c.id).size() + " 张）", 14, Color.rgb(0xE0, 0x31, 0x31), true);
            rm.setGravity(Gravity.CENTER);
            rm.setPadding(0, dp(this, 11), 0, dp(this, 11));
            LinearLayout.LayoutParams rmlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rmlp.topMargin = dp(this, 8);
            cardBox.addView(rm, rmlp);
            rm.setOnClickListener(v -> { haptic(); closeAcctClassPicker(); removeMineEntriesForCard(c, uiRefresh); });
        }
        TextView cancel = tv(this, manage ? "取消" : "先不加", 14, Color.rgb(0x8E, 0x8E, 0x93), false);
        cancel.setGravity(Gravity.CENTER);
        cancel.setPadding(0, dp(this, 11), 0, dp(this, 4));
        LinearLayout.LayoutParams canLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        canLp.topMargin = dp(this, 4);
        cardBox.addView(cancel, canLp);
        cancel.setOnClickListener(v -> { haptic(); closeAcctClassPicker(); });
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.gravity = Gravity.BOTTOM;
        clp.leftMargin = dp(this, 12); clp.rightMargin = dp(this, 12);
        FrameLayout wrap = new FrameLayout(this);
        View glass = glassLayer(cardBox, 22, false);
        topSheetClip(glass, 22, this);
        wrap.addView(glass, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        wrap.addView(cardBox, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        overlay.addView(wrap, clp);
        rootView.addView(overlay, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        acctPickerView = overlay;
        overlay.setAlpha(0f);
        overlay.animate().alpha(1f).setDuration(ANIM_DUR_SHADE_IN).setInterpolator(ANIM_ENTER).start();
        shade.animate().alpha(1f).setDuration(ANIM_DUR_SHADE_IN).setInterpolator(ANIM_ENTER).start();
        wrap.setTranslationY(dp(this, 42));
        wrap.animate().translationY(0f).setDuration(ANIM_DUR_SHEET_IN).setInterpolator(ANIM_ENTER).start();
    }

    // Q71 占位卡面自选配色：10 色色板贴底窗，按卡 id 存本机偏好，OTA 不冲掉；真图卡不走此路
    void closePlaceholderPicker() {
        // Q74：色板窗收起同走 SHEET_OUT 下沉淡出，落定再 restoreChrome，不许硬消失。
        final View v = placeholderPickerView;
        placeholderPickerView = null;
        if (v == null) { restoreChrome(); return; }
        if (!(v instanceof ViewGroup) || ((ViewGroup) v).getChildCount() < 2) {
            if (v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v);
            restoreChrome();
            return;
        }
        View wrap = ((ViewGroup) v).getChildAt(((ViewGroup) v).getChildCount() - 1);
        View shade = ((ViewGroup) v).getChildAt(0);
        if (shade != null) animShadeOut(shade);
        if (wrap != null) {
            wrap.animate().cancel();
            wrap.animate().alpha(0f).translationY(dp(this, SHEET_RISE_DP))
                .setDuration(ANIM_DUR_SHEET_OUT).setInterpolator(ANIM_EXIT)
                .withEndAction(() -> {
                    if (v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v);
                    restoreChrome();
                }).start();
            v.postDelayed(() -> {
                if (v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v);
                restoreChrome();
            }, ANIM_DUR_SHEET_OUT + 40);
        } else {
            if (v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v);
            restoreChrome();
        }
    }
    void refreshDetailPlaceholderBody(Card c) {
        try {
            if (detailScroll != null && detailCard != null && c != null && c.id != null && c.id.equals(detailCard.id)) {
                detailScroll.removeAllViews();
                LinearLayout nb = buildDetailSheetBody(c);
                nb.setPadding(0, 0, 0, dp(this, 10) + navBarH());
                detailScroll.addView(nb);
                detailScroll.scrollTo(0, 0);
            }
        } catch (Throwable ignored) {}
    }
    void openPlaceholderColorPicker(final Card c) {
        if (c == null || rootView == null) return;
        closePlaceholderPicker();
        final FrameLayout overlay = new FrameLayout(this);
        View shade = new View(this);
        shade.setBackgroundColor(Color.argb(102, 0, 0, 0));
        shade.setAlpha(0f);
        shade.setOnClickListener(v -> closePlaceholderPicker());
        overlay.addView(shade, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout cardBox = new LinearLayout(this);
        cardBox.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable cg = new GradientDrawable();
        cg.setColor(Color.WHITE);
        float rTop = dp(this, 22);
        cg.setCornerRadii(new float[]{rTop, rTop, rTop, rTop, 0, 0, 0, 0});
        cardBox.setBackground(cg);
        if (Build.VERSION.SDK_INT >= 21) { cardBox.setElevation(dp(this, 24)); topSheetClip(cardBox, 22, this); }
        cardBox.setOnClickListener(v -> {});
        cardBox.setPadding(dp(this, 18), dp(this, 18), dp(this, 18), dp(this, 14) + navBarH());
        cardBox.addView(tv(this, "卡面颜色", 17, Color.rgb(0x1C, 0x1C, 0x1E), true));
        TextView sub = tv(this, c.name + " · 只改无图占位面，真卡图不受影响", 12.5f, Color.rgb(0x8E, 0x8E, 0x93), false);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(this, 4);
        cardBox.addView(sub, subLp);
        Integer curCustom = placeholderCustom.get(c.id);
        int autoIdx = placeholderIdx(c.id);
        for (int rowI = 0; rowI < 2; rowI++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.topMargin = dp(this, 12);
            cardBox.addView(row, rlp);
            for (int colI = 0; colI < 5; colI++) {
                final int idx = rowI * 5 + colI;
                int[] pair = PLACEHOLDER_PALETTE[idx];
                GradientDrawable sw = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{Color.rgb(Color.red(pair[0]), Color.green(pair[0]), Color.blue(pair[0])), Color.rgb(Color.red(pair[1]), Color.green(pair[1]), Color.blue(pair[1]))});
                sw.setCornerRadius(dp(this, 12));
                boolean sel = curCustom != null && curCustom == idx;
                boolean isAuto = curCustom == null && autoIdx == idx;
                if (sel) sw.setStroke(dp(this, 3), Color.rgb(0x0A, 0x5C, 0xD6));
                else if (isAuto) sw.setStroke(dp(this, 2), Color.argb(160, 10, 92, 214));
                View cell = new View(this);
                cell.setBackground(sw);
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, dp(this, 48), 1f);
                if (colI > 0) clp.leftMargin = dp(this, 8);
                row.addView(cell, clp);
                cell.setOnClickListener(v -> {
                    haptic();
                    placeholderCustom.put(c.id, idx);
                    savePlaceholderCustom();
                    placeholderDirty = true;
                    closePlaceholderPicker();
                    refreshDetailPlaceholderBody(c);
                    showFloatToast("卡面颜色已保存");
                });
            }
        }
        TextView follow = tv(this, curCustom == null ? "当前跟随系统自动配色" : "跟随系统（清掉自选）", 14, Color.rgb(0x0A, 0x5C, 0xD6), true);
        follow.setGravity(Gravity.CENTER);
        follow.setPadding(0, dp(this, 12), 0, dp(this, 6));
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        flp.topMargin = dp(this, 8);
        cardBox.addView(follow, flp);
        follow.setOnClickListener(v -> {
            haptic();
            placeholderCustom.remove(c.id);
            savePlaceholderCustom();
            placeholderDirty = true;
            closePlaceholderPicker();
            refreshDetailPlaceholderBody(c);
            showFloatToast("已回到系统配色");
        });
        TextView cancel = tv(this, "取消", 14, Color.rgb(0x8E, 0x8E, 0x93), false);
        cancel.setGravity(Gravity.CENTER);
        cancel.setPadding(0, dp(this, 8), 0, dp(this, 4));
        cardBox.addView(cancel);
        cancel.setOnClickListener(v -> { haptic(); closePlaceholderPicker(); });
        FrameLayout.LayoutParams clp2 = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp2.gravity = Gravity.BOTTOM;
        clp2.leftMargin = dp(this, 12); clp2.rightMargin = dp(this, 12);
        FrameLayout wrap = new FrameLayout(this);
        View glass = glassLayer(cardBox, 22, false);
        topSheetClip(glass, 22, this);
        wrap.addView(glass, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        wrap.addView(cardBox, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        overlay.addView(wrap, clp2);
        rootView.addView(overlay, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        placeholderPickerView = overlay;
        hideChrome();
        overlay.setAlpha(0f);
        overlay.animate().alpha(1f).setDuration(ANIM_DUR_SHADE_IN).setInterpolator(ANIM_ENTER).start();
        shade.animate().alpha(1f).setDuration(ANIM_DUR_SHADE_IN).setInterpolator(ANIM_ENTER).start();
        wrap.setTranslationY(dp(this, 42));
        wrap.animate().translationY(0f).setDuration(ANIM_DUR_SHEET_IN).setInterpolator(ANIM_ENTER).start();
    }

    // 字体/界面大小变化后整页重建（各页都是缓存 View，必须重造才生效）
    void rebuildPages() {
        captureCurrentPageScroll(); // P-keepscroll：整页重建（字体/界面大小等）前先记下各页滚动位置
        pages.clear();
        if (content != null) content.removeAllViews();
        showTab(tab);
    }

    // Q55 自定义字体导入：系统文件选择 .ttf/.otf，先下到临时文件校验（大小+文件头+试加载），成功才替换正式文件并即时启用
    void openFontPicker() {
        try {
            Intent it = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            it.addCategory(Intent.CATEGORY_OPENABLE);
            it.setType("*/*");
            it.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"font/ttf", "font/otf", "application/x-font-ttf", "application/x-font-otf", "application/octet-stream"});
            startActivityForResult(it, REQ_PICK_FONT);
        } catch (Throwable e) { showFloatToast("打不开文件选择器"); }
    }

    String queryFontDisplayName(Uri uri) {
        String name = "";
        android.database.Cursor cur = null;
        try {
            cur = getContentResolver().query(uri, null, null, null, null);
            if (cur != null && cur.moveToFirst()) {
                int idx = cur.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) { String v = cur.getString(idx); if (v != null) name = v; }
            }
        } catch (Throwable ignored) {} finally { if (cur != null) try { cur.close(); } catch (Throwable ignored) {} }
        return name;
    }

    void importCustomFont(final Uri uri, final String displayName) {
        showFloatToast("正在导入字体…");
        new Thread(() -> {
            String failMsg = null;
            String okName = displayName == null ? "" : displayName;
            File tmp = null;
            try {
                if (okName != null && okName.length() > 0) {
                    String low = okName.toLowerCase(java.util.Locale.ROOT);
                    if (!low.endsWith(".ttf") && !low.endsWith(".otf")) failMsg = "只支持 .ttf / .otf 字体文件，已取消导入";
                }
                tmp = customFontTmpFile(MainActivity.this);
                if (failMsg == null) {
                    try { if (tmp.exists()) tmp.delete(); } catch (Throwable ignored) {}
                    java.io.InputStream in = null;
                    java.io.FileOutputStream out = null;
                    long total = 0;
                    try {
                        in = getContentResolver().openInputStream(uri);
                        if (in == null) failMsg = "读不到这个文件，已回退软件字体";
                        else {
                            out = new java.io.FileOutputStream(tmp);
                            byte[] buf = new byte[32768];
                            int n;
                            while ((n = in.read(buf)) > 0) {
                                total += n;
                                if (total > MAX_CUSTOM_FONT_BYTES) { failMsg = "字体文件太大了（上限 20MB），没有导入"; break; }
                                out.write(buf, 0, n);
                            }
                            out.flush();
                        }
                    } finally {
                        if (out != null) try { out.close(); } catch (Throwable ignored) {}
                        if (in != null) try { in.close(); } catch (Throwable ignored) {}
                    }
                    if (failMsg == null && total == 0) failMsg = "这个文件是空的，已回退软件字体";
                }
                if (failMsg == null) {
                    // 文件头校验：TrueType 0x00010000 / OpenType OTTO / Collection ttcf / true
                    java.io.FileInputStream fis = null;
                    try {
                        fis = new java.io.FileInputStream(tmp);
                        byte[] head = new byte[4];
                        int got = fis.read(head);
                        boolean magicOk = got == 4 && ((head[0] == 0 && head[1] == 1 && head[2] == 0 && head[3] == 0)
                            || (head[0] == 'O' && head[1] == 'T' && head[2] == 'T' && head[3] == 'O')
                            || (head[0] == 't' && head[1] == 't' && head[2] == 'c' && head[3] == 'f')
                            || (head[0] == 't' && head[1] == 'r' && head[2] == 'u' && head[3] == 'e'));
                        if (!magicOk) failMsg = "这个字体文件读不了，已回退软件字体";
                    } finally { if (fis != null) try { fis.close(); } catch (Throwable ignored) {} }
                }
                android.graphics.Typeface trial = null;
                if (failMsg == null) {
                    try { trial = android.graphics.Typeface.createFromFile(tmp); } catch (Throwable e) { trial = null; }
                    // createFromFile 对坏文件在部分机型回落 DEFAULT 而非 null，一并判坏拒绝
                    if (trial == null || trial == android.graphics.Typeface.DEFAULT) failMsg = "这个字体文件读不了，已回退软件字体";
                }
                if (failMsg == null) {
                    File dst = customFontFile(MainActivity.this);
                    try { if (dst.exists()) dst.delete(); } catch (Throwable ignored) {}
                    if (!tmp.renameTo(dst)) {
                        // 跨卷兜底：流式拷贝
                        java.io.FileInputStream cin = null; java.io.FileOutputStream cout = null;
                        try {
                            cin = new java.io.FileInputStream(tmp); cout = new java.io.FileOutputStream(dst);
                            byte[] buf = new byte[32768]; int n;
                            while ((n = cin.read(buf)) > 0) cout.write(buf, 0, n);
                            cout.flush();
                        } finally {
                            if (cout != null) try { cout.close(); } catch (Throwable ignored) {}
                            if (cin != null) try { cin.close(); } catch (Throwable ignored) {}
                        }
                        try { tmp.delete(); } catch (Throwable ignored) {}
                    }
                    if (okName == null || okName.length() == 0) okName = "自定义字体";
                    final String finalName = okName;
                    runOnUiThread(() -> {
                        resetCustomFontCache();
                        customFontName = finalName;
                        fontMode = "custom";
                        try { prefs.edit().putString("font_mode", "custom").putString("custom_font_name", finalName).apply(); } catch (Throwable ignored) {}
                        // 启用后再验一次，加载失败立即回退软件字体，不许带半截状态
                        if (ensureCustomLoaded(MainActivity.this) == null) {
                            fontMode = "builtin";
                            try { prefs.edit().putString("font_mode", "builtin").apply(); } catch (Throwable ignored) {}
                            rebuildPages();
                            showFloatToast("这个字体文件读不了，已回退软件字体");
                            return;
                        }
                        haptic();
                        rebuildPages();
                        showFloatToast("自定义字体已启用：" + finalName);
                    });
                    return;
                }
            } catch (Throwable e) {
                failMsg = "导入失败，已回退软件字体";
            }
            // 失败路径：清临时文件、不动已生效字体文件，但模式回退软件字体（Q55 口径），全程不闪退
            try { if (tmp != null && tmp.exists()) tmp.delete(); } catch (Throwable ignored) {}
            final String msg = failMsg == null ? "导入失败，已回退软件字体" : failMsg;
            runOnUiThread(() -> {
                fontMode = "builtin";
                try { prefs.edit().putString("font_mode", "builtin").apply(); } catch (Throwable ignored) {}
                rebuildPages();
                showFloatToast(msg);
            });
        }).start();
    }

    void deleteCustomFont() {
        try { customFontFile(this).delete(); } catch (Throwable ignored) {}
        try { customFontTmpFile(this).delete(); } catch (Throwable ignored) {}
        resetCustomFontCache();
        customFontName = "";
        fontMode = "builtin";
        try { prefs.edit().putString("font_mode", "builtin").remove("custom_font_name").apply(); } catch (Throwable ignored) {}
        haptic();
        rebuildPages();
        showFloatToast("自定义字体已删除，已回退软件字体");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_FONT) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) { showFloatToast("已取消导入字体"); return; }
        Uri uri = data.getData();
        importCustomFont(uri, queryFontDisplayName(uri));
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
            p.setColor(on ? ((MainActivity) getContext()).navOnColor() : ((MainActivity) getContext()).navOffColor());
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
        // Q73: unified thin tint over the live glass layer (was argb148-168, too milky).
        return glassTintDrawable(26, false);
    }

    Drawable navPillBg() {
        // Q73: lens tint slightly stronger than dock tint so the lens still reads, same family.
        GradientDrawable g = glassTintDrawable(18, false);
        try {
            int[] cs = darkEff()
                ? new int[]{Color.argb(120, 255, 255, 255), Color.argb(108, 245, 248, 252), Color.argb(100, 238, 243, 250)}
                : new int[]{Color.argb(112, 255, 255, 255), Color.argb(102, 247, 250, 254), Color.argb(96, 240, 245, 251)};
            g.setColors(cs);
        } catch (Throwable ignored) {}
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
        // Q38/Q35: the lens must stay UNDER the item row (mixed version: .dock-pill sits below the buttons,
        // which carry z-index 1 and only change text color/weight when .on). The old elevation 2dp raised the
        // pill above the row and painted over the selected cell's icon+label - the "empty light block" bug.
        if (Build.VERSION.SDK_INT >= 21) navIndicator.setElevation(0f);
        FrameLayout.LayoutParams indLp = new FrameLayout.LayoutParams(dp(this, 60), dp(this, 52));
        navBar.addView(navIndicator, indLp);

        navRow = new LinearLayout(this);
        navRow.setOrientation(LinearLayout.HORIZONTAL);
        // Q38/Q35: row rides above the lens so every cell's icon+label stays visible; the lens shows through
        // only in the gaps (items have no background), exactly like the mixed version's buttons over .dock-pill.
        if (Build.VERSION.SDK_INT >= 21) navRow.setElevation(dp(this, 3));
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
            TextView label = tv(this, t[1], 10f, navOffColor(), false);
            label.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            llp.topMargin = dp(this, 2);
            item.addView(label, llp);
            // Q38 (FClash-style lens, mechanism re-implemented by hand): press starts the lens gliding,
            // lifting the finger commits the page switch on spring settle - never mid-flight.
            item.setOnClickListener(v -> { if (!navDragging) { haptic(); springNavTo(idx, true); } });
            // Q17/Q21: drag on the dock itself - finger drags the drop, passing a tab ticks haptic, release springs to nearest and only then switches page.
            // Q21 ③ 触摸竞争治理：按下即向父级声明不许拦截（底栏整条手势归条目独占），坐标统一用 rawX 换算到 navRow，
            // 手指滑出起始条目后仍由按下条目独占 MOVE 流，不再出现滑到一半被别的视图抢走而「滑不动」。
            item.setOnTouchListener((v, e) -> {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        navDownRawX = e.getRawX(); navDownRawY = e.getRawY(); navDownMs = android.os.SystemClock.uptimeMillis();
                        try { v.getParent().requestDisallowInterceptTouchEvent(true); } catch (Throwable ignored) {}
                        // Q38: press moves first - the lens starts gliding to the pressed tab right away
                        // (retarget keeps spring velocity); the page itself switches only on lift.
                        // A following drag still cancels the spring and takes the lens over (MOVE branch).
                        springNavTo(idx, false);
                        return false;
                    case MotionEvent.ACTION_MOVE:
                        if (!navDragging && Math.abs(e.getRawX() - navDownRawX) > dp(this, 9)) {
                            navDragging = true; navDragIdx = Math.round(navPos); cancelNavSpring();
                        }
                        if (navDragging) { navDragTo(e.getRawX()); return true; }
                        return false;
                    case MotionEvent.ACTION_UP:
                        try { v.getParent().requestDisallowInterceptTouchEvent(false); } catch (Throwable ignored) {}
                        if (navDragging) { navDragging = false; settleNav(); return true; }
                        // Q38: lift commits - spring to the pressed tab, page switches on settle.
                        // Guard: a press that slid far off the dock vertically is an abort, not a tap.
                        if (Math.abs(e.getRawY() - navDownRawY) > dp(this, 48)) {
                            springNavTo(Math.max(0, navOrder.indexOf(tab)), false); return true;
                        }
                        haptic(); springNavTo(idx, true); return true;
                    case MotionEvent.ACTION_CANCEL:
                        try { v.getParent().requestDisallowInterceptTouchEvent(false); } catch (Throwable ignored) {}
                        if (navDragging) { navDragging = false; settleNav(); return true; }
                        // press aborted without lift-commit: glide the lens back to the current page's tab.
                        springNavTo(Math.max(0, navOrder.indexOf(tab)), false); return true;
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

    // Q38/Q35: tint follows the lens - the cell under the moving lens gets the dark icon + bold label,
    // like the mixed version's button.on (color #1C1C1E + font-weight 700; its svg strokes use currentColor,
    // so icon and label tint together). Fires only on discrete tab crossings; lens motion itself stays
    // pure drawing-layer (translation/scale/alpha), no layout passes per frame.
    // Q72：底栏未选/选中字色随深浅走语义色（深色下未选为 60% 白、选中为强调色前景深字面上的主字）
    int navOffColor() { return darkEff() ? Color.argb(153,255,255,255) : Color.rgb(0x3A,0x3A,0x3C); }
    int navOnColor() { return darkEff() ? Color.argb(235,255,255,255) : Color.rgb(0x1C,0x1C,0x1E); }
    void tintNavTo(int idx) {
        if (idx < 0 || idx >= navOrder.size()) return;
        navTintIdx = idx;
        String key = navOrder.get(idx);
        for (Map.Entry<String, LinearLayout> e : navItems.entrySet()) {
            boolean on = e.getKey().equals(key);
            NavIconView ic = navIcons.get(e.getKey());
            if (ic != null) ic.setOn(on);
            TextView lb = navLabels.get(e.getKey());
            if (lb != null) {
                lb.setTextColor(on ? navOnColor() : navOffColor());
                android.graphics.Typeface cur = lb.getTypeface();
                if (cur != null) lb.setTypeface(cur, on ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            }
        }
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
        // Q38: jelly stretch follows speed, capped at about +25% wide (squash inversely on Y).
        float stretch = Math.min(1.25f, 1f + Math.abs(vel) * 0.012f);
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
        navSpringV = vel; // Q38: hand the finger's velocity to the spring on release (no dead stop)
        placeNavIndicator(vel);
        int idx = Math.max(0, Math.min(4, Math.round(navPos)));
        if (idx != navDragIdx) { navDragIdx = idx; haptic(); tintNavTo(idx); } // one tick per tab passed
    }

    void cancelNavSpring() {
        navSpringRunning = false;
        navSpringGen++;
        if (navSpringTask != null) { mainHandler.removeCallbacks(navSpringTask); navSpringTask = null; }
    }

    void settleNav() { springNavTo(Math.max(0, Math.min(4, Math.round(navPos))), true); }

    // hand-written damped spring (no libs), Q38 tuning: stiffness 210, damping ratio ~0.68 -> about 500ms
    // settle with a slight overshoot; retargeting mid-flight KEEPS the current velocity (FClash-style lens).
    // Q21 ①：全程只许一条回路——启动时摘除旧任务并自增代次，帧内先验代次再推进；取消/新弹簧/手指接管任一发生，旧回路当帧自尽。
    // 落位才切页的防闪烁语义不变（switchPage 时弹簧落稳那一帧才 showTab）。
    void springNavTo(final int target, final boolean switchPage) {
        final int gen = ++navSpringGen;
        if (navSpringTask != null) mainHandler.removeCallbacks(navSpringTask);
        navSpringRunning = true;
        // velocity intentionally NOT zeroed: a mid-flight retarget continues with its momentum.
        final long[] last = { android.os.SystemClock.uptimeMillis() };
        navSpringTask = new Runnable() {
            public void run() {
                if (gen != navSpringGen || !navSpringRunning) return; // 已被更新的回路/取消取代
                long now = android.os.SystemClock.uptimeMillis();
                float dt = Math.min(0.032f, Math.max(0.001f, (now - last[0]) / 1000f)); last[0] = now;
                float k = 210f, c = 2f * 0.68f * (float) Math.sqrt(k);
                float a = -k * (navPos - target) - c * navSpringV;
                navSpringV += a * dt;
                navPos += navSpringV * dt;
                placeNavIndicator(navSpringV);
                int tintIdx = Math.max(0, Math.min(4, Math.round(navPos)));
                if (tintIdx != navTintIdx) tintNavTo(tintIdx); // Q38: icon+label tint rides with the lens
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
        closeShowcaseNow(); // Q78：切页先摘展柜浮层，引用与漂移任务不许残留到新页
        dismissMoreMenuNow(); // Q58：切页前菜单即刻退场，不许残留到新页
        clearLiveGlassForTabSwitch(); // Q63：切页瞬间清 live 玻璃旧帧，不许旧页文字在新页玻璃面糊出残影
        tab = key;
        sCrashTab = key;
        // Q64：页间切换过渡（FClash 节奏学机制自写）——旧页直接退场不叠在新页底下透出，
        // 新页按标签方向轻横移+淡入 220ms 一条 ANIM_ENTER 曲线走完；重活（refreshHome 的
        // 签名校验与分帧续搭）让一帧再跑，不堵点击瞬间。快速连点以 tabAnimGen 代次作废旧动画。
        final int newIdx64 = navOrder.indexOf(key);
        final int dir64 = (newIdx64 >= 0) ? Integer.signum(newIdx64 - lastTabIdx) : 0;
        if (newIdx64 >= 0) lastTabIdx = newIdx64;
        final int gen64 = ++tabAnimGen;
        if (currentPageView != null) currentPageView.animate().cancel();
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
        currentPageView = page;
        // Q64：旧页已退场（不再垫底淡出），与 Q63 清旧帧合起来达成「切换瞬间不见上一页」。
        // Q21：弹簧/拖动未落稳时不抓玻璃全图（整屏 draw 会抢主线程、拖动随之发卡）；落稳后由滚动停稳防抖补刷。
        // Q63：不再切页即刻抓图——旧版 post 立即抓，抓到的是旧页淡出+新页淡入的混帧，旧页文字被烤进
        // 玻璃帧直到下一次停稳刷新才消失（残影 1–2 秒的定案来源）。改延迟到 280ms（220ms 交叉淡入已落定、
        // 旧页已摘除）再抓干净新帧，且代次守卫防连切时过期任务抓到半路画面；延迟窗内 live 层走染色兜底。
        if (rootView != null && !navSpringRunning && !navDragging) {
            final int gen63 = glassTabGen;
            mainHandler.postDelayed(() -> { if (gen63 == glassTabGen) refreshLiveGlass(); }, 280);
        }
        // Q64：新页方向轻移+淡入——从目标标签方向滑入（右移页自右轻入、左移页自左轻入），
        // 220ms ANIM_ENTER 与底栏液滴「落位才切页」时序对齐；只动绘制层（alpha/translation），不抓图不采样。
        page.animate().cancel();
        page.setAlpha(0f);
        page.setTranslationX(dir64 * dp(this, 18));
        page.setTranslationY(dp(this, 4));
        page.animate().alpha(1f).translationX(0f).translationY(0f)
            .setDuration(ANIM_DUR_FADE).setInterpolator(ANIM_ENTER).start();
        // Q64：首页重活让一帧——先让过渡首帧出去，再做签名校验/分帧续搭，避免 213 张校验堵在点击瞬间掉帧。
        if ("home".equals(key) && homeList != null) {
            final View pg64 = page;
            pg64.post(() -> { if (gen64 == tabAnimGen && "home".equals(tab)) refreshHome(); });
        }
        restoreCurrentTabScroll();
        syncSearchFab(); syncAddFab();
        for (Map.Entry<String, LinearLayout> e : navItems.entrySet()) {
            boolean on = e.getKey().equals(key);
            NavIconView ic = navIcons.get(e.getKey());
            if (ic != null) ic.setOn(on);
            TextView lb = navLabels.get(e.getKey());
            if (lb != null) {
                lb.setTextColor(on ? navOnColor() : navOffColor());
                android.graphics.Typeface cur = lb.getTypeface();
                if (cur != null) lb.setTypeface(cur, on ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            }
        }
        navTintIdx = navOrder.indexOf(key); // Q38: settled tint matches the page; lens tint rides during flight
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
        // Q73: unified oval thin tint; when glass is disabled this is the fallback face, so keep
        // it light enough to stand on pure-black cards (Q53) without turning into solid plastic.
        GradientDrawable g = glassTintDrawable(-1, true);
        if (glassDisabled) {
            try { g.setColors(darkEff()
                ? new int[]{Color.argb(210, 52, 52, 58), Color.argb(200, 40, 40, 46)}
                : new int[]{Color.argb(214, 255, 255, 255), Color.argb(206, 248, 250, 255), Color.argb(198, 232, 238, 246)}); }
            catch (Throwable ignored) {}
        }
        return g;
    }
    // Q53：悬浮钮磨砂提亮层——固定浅白半透盖在 live 模糊层之上、图标之下。玻璃位图是不透明快照，
    // 压纯黑卡面时模糊层整片发黑、原 argb118 染色被它盖住，钮即全黑隐身；此层与身后颜色无关，
    // argb 168→152 白提亮让黑底上钮面仍为浅灰白、深色细线图标可辨，白底上也不过曝（仍半透留糊感）。
    View fabFrostWash() {
        // Q73: same wash as every other glass piece (was a one-off argb168/152).
        return glassWashView(-1, true);
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
    // Q30：细线 ✕（搜索胶囊同语言）；shadow=true 时给白字形加暗晕，保证浅图上也可辨
    class CloseIconView extends View {
        int iconColor = Color.rgb(0x1C, 0x1C, 0x1E);
        float lineDp = 1.6f;
        boolean shadow = false;
        CloseIconView(Context c) { super(c); }
        @Override protected void onDraw(Canvas cv) {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setColor(iconColor);
            if (shadow) p.setShadowLayer(dp(getContext(), 3), 0, dp(getContext(), 1), Color.argb(170, 0, 0, 0));
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

    // Q14：选卡场景细线图标（SF 风自绘禁用 emoji，语义对混合版 🎓✈️🛒☕；
    // 24 网格、stroke 1.7、#1C1C1E，与 SearchIconView/FilterIconView 同语言）
    class SceneIconView extends View {
        String kind = "study";
        SceneIconView(Context c) { super(c); }
        @Override protected void onDraw(Canvas cv) {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            p.setColor(Color.rgb(0x1C, 0x1C, 0x1E));
            float ox = getPaddingLeft(), oy = getPaddingTop();
            float sx = (getWidth() - getPaddingLeft() - getPaddingRight()) / 24f;
            float sy = (getHeight() - getPaddingTop() - getPaddingBottom()) / 24f;
            p.setStrokeWidth(1.7f * sx);
            android.graphics.Path path = new android.graphics.Path();
            if ("study".equals(kind)) { // 学士帽：帽板菱形+帽身+流苏
                path.moveTo(ox + 12f * sx, oy + 5f * sy);
                path.lineTo(ox + 20.5f * sx, oy + 9.5f * sy);
                path.lineTo(ox + 12f * sx, oy + 14f * sy);
                path.lineTo(ox + 3.5f * sx, oy + 9.5f * sy);
                path.close();
                cv.drawPath(path, p);
                path.reset();
                path.moveTo(ox + 7.5f * sx, oy + 11.8f * sy);
                path.lineTo(ox + 7.5f * sx, oy + 15.5f * sy);
                path.cubicTo(ox + 7.5f * sx, oy + 18f * sy, ox + 16.5f * sx, oy + 18f * sy, ox + 16.5f * sx, oy + 15.5f * sy);
                path.lineTo(ox + 16.5f * sx, oy + 11.8f * sy);
                cv.drawPath(path, p);
                cv.drawLine(ox + 20.5f * sx, oy + 9.5f * sy, ox + 20.5f * sx, oy + 15f * sy, p);
            } else if ("travel".equals(kind)) { // 纸飞机（SF paperplane）
                path.moveTo(ox + 21f * sx, oy + 3.5f * sy);
                path.lineTo(ox + 10.5f * sx, oy + 13.5f * sy);
                path.lineTo(ox + 21f * sx, oy + 3.5f * sy);
                path.lineTo(ox + 14f * sx, oy + 20.5f * sy);
                path.lineTo(ox + 10.5f * sx, oy + 13.5f * sy);
                path.lineTo(ox + 3.5f * sx, oy + 10f * sy);
                path.close();
                cv.drawPath(path, p);
            } else if ("shop".equals(kind)) { // 购物袋：袋身+提手弧
                android.graphics.RectF bag = new android.graphics.RectF(ox + 5.5f * sx, oy + 8f * sy, ox + 18.5f * sx, oy + 20f * sy);
                cv.drawRoundRect(bag, 2f * sx, 2f * sy, p);
                path.moveTo(ox + 9f * sx, oy + 11f * sy);
                path.cubicTo(ox + 9f * sx, oy + 5.5f * sy, ox + 15f * sx, oy + 5.5f * sy, ox + 15f * sx, oy + 11f * sy);
                cv.drawPath(path, p);
            } else { // daily 咖啡杯：杯身+耳+碟
                path.moveTo(ox + 5.5f * sx, oy + 9f * sy);
                path.lineTo(ox + 17f * sx, oy + 9f * sy);
                path.lineTo(ox + 16f * sx, oy + 18.5f * sy);
                path.lineTo(ox + 6.5f * sx, oy + 18.5f * sy);
                path.close();
                cv.drawPath(path, p);
                path.reset();
                path.moveTo(ox + 17.2f * sx, oy + 10.5f * sy);
                path.cubicTo(ox + 20.5f * sx, oy + 10.5f * sy, ox + 20.5f * sx, oy + 15.5f * sy, ox + 16.8f * sx, oy + 15.5f * sy);
                cv.drawPath(path, p);
                cv.drawLine(ox + 4.5f * sx, oy + 21f * sy, ox + 18f * sx, oy + 21f * sy, p);
            }
        }
    }

    // 只在首页、且没有整屏覆盖层时出现；覆盖层（详情/向导/欢迎/日志）都会
    // content.removeAllViews()，天然把它清掉，回到首页时 showTab 会再挂回来。
    void syncSearchFab() {
        boolean want = "home".equals(tab) && !isChromeCovered(); // Q12：任一浮窗在场双钮退场
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

    // Q7 ④：我的卡片页右下圆形 ＋（照混合版 .fab：56dp、右 20dp、底 108dp+inset、蓝底白 ＋、与 dock 同层浮空）；
    // 仅在 mine 页且浮窗（筛选/选卡/详情/向导/关于/长按菜单）不在场时出现，浮窗退场归 Q12 同步。
    void syncAddFab() {
        boolean want = "mine".equals(tab) && !isChromeCovered(); // Q12
        if (!want) {
            if (addFab != null && addFab.getParent() != null) ((ViewGroup) addFab.getParent()).removeView(addFab);
            addFab = null;
            return;
        }
        if (addFab == null || addFab.getParent() != content) {
            if (addFab != null && addFab.getParent() != null) ((ViewGroup) addFab.getParent()).removeView(addFab);
            addFab = buildAddFab();
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(this, 56), dp(this, 56));
            lp.gravity = Gravity.END | Gravity.BOTTOM;
            lp.rightMargin = dp(this, 20);
            lp.bottomMargin = dp(this, 108) + navBarH();
            content.addView(addFab, lp);
            addFab.setAlpha(0f); addFab.setScaleX(0.82f); addFab.setScaleY(0.82f);
            addFab.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(ANIM_DUR_FADE).setInterpolator(ANIM_ENTER).start();
        }
    }
    View buildAddFab() {
        FrameLayout fab = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{Color.rgb(0x2F, 0x7D, 0xFF), Color.rgb(0x0A, 0x5C, 0xD6)});
        bg.setShape(GradientDrawable.OVAL);
        fab.setBackground(bg);
        applyGlassFabShadow(fab);
        TextView plus = tv(this, "\uFF0B", 28, Color.WHITE, false);
        plus.setGravity(Gravity.CENTER);
        fab.addView(plus, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        fab.setOnClickListener(v -> { haptic(); openAddSheet(); });
        return fab;
    }
    // Q7 ④ + Q9：点 ＋ 先升「添加卡片」底表（自定义卡片 / 在线查询卡信息 / 取消），再进表单；
    // 在线查询走真 BIN 在线认行（openBinQuery），不再是空壳跳转。
    void openAddSheet() {
        hideChrome(); // Q12
        final FrameLayout sheet = new FrameLayout(this);
        addSheetView = sheet;
        sheet.setBackgroundColor(Color.argb(117, 15, 20, 40));
        sheet.setOnClickListener(v -> closeAddSheet(sheet));
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        // Q45：添加底表改为贴底形态——仅顶部圆角、底部直角直达屏底，消灭底部两角透出遮罩的黑三角
        GradientDrawable addBg = new GradientDrawable();
        addBg.setColor(colSheet()); // Q54 实底口径 + Q72 深色浮层，窗身一整块同色到底
        float addR = dp(this, 22);
        addBg.setCornerRadii(new float[]{addR, addR, addR, addR, 0, 0, 0, 0});
        card.setBackground(addBg);
        if (Build.VERSION.SDK_INT >= 21) { card.setElevation(dp(this, 24)); topSheetClip(card, 22, this); } // Q45 顶圆底直轮廓
        card.setPadding(dp(this, 18), dp(this, 16), dp(this, 18), dp(this, 12) + navBarH());
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.gravity = Gravity.BOTTOM;
        clp.leftMargin = dp(this, 12); clp.rightMargin = dp(this, 12); clp.bottomMargin = 0;
        sheet.addView(card, clp);
        card.setOnClickListener(v -> {});
        card.addView(tv(this, "\u6DFB\u52A0\u5361\u7247", 17, Color.rgb(0x1C, 0x1C, 0x1E), true));
        TextView r1 = addSheetRow("\u81EA\u5B9A\u4E49\u5361\u7247", () -> { closeAddSheet(sheet); openCustomForm(null); });
        LinearLayout.LayoutParams r1lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(this, 52));
        r1lp.topMargin = dp(this, 12);
        card.addView(r1, r1lp);
        TextView rExt = addSheetRow("\u5728\u7EBF\u641C\u5361\uFF08\u6269\u5C55\u5361\u5E93\uFF09", () -> {
            suppressNextChromeRestore = true; closeAddSheet(sheet);
            openExtendedSearch();
        });
        LinearLayout.LayoutParams rExtLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(this, 52));
        rExtLp.topMargin = dp(this, 8);
        card.addView(rExt, rExtLp);
        TextView r2 = addSheetRow("\u5728\u7EBF\u67E5\u8BE2\u5361\u4FE1\u606F", () -> {
            suppressNextChromeRestore = true; closeAddSheet(sheet);
            openBinQuery();
        });
        LinearLayout.LayoutParams r2lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(this, 52));
        r2lp.topMargin = dp(this, 8);
        card.addView(r2, r2lp);
        TextView cancel = tv(this, "\u53D6\u6D88", 15, Color.rgb(0x8E, 0x8E, 0x93), false);
        cancel.setGravity(Gravity.CENTER);
        cancel.setPadding(0, dp(this, 10), 0, dp(this, 6));
        cancel.setOnClickListener(v -> closeAddSheet(sheet));
        card.addView(cancel);
        content.addView(sheet);
        // Q74：遮罩与窗体同曲线同步升起（此前只有卡动、遮罩硬现）
        sheet.setAlpha(0f);
        sheet.animate().alpha(1f).setDuration(ANIM_DUR_SHADE_IN).setInterpolator(ANIM_ENTER).start();
        card.setAlpha(0f); card.setTranslationY(dp(this, 28));
        card.animate().alpha(1f).translationY(0f).setDuration(ANIM_DUR_SHEET_IN).setInterpolator(ANIM_ENTER).start();
        sheet.setTag("addSheet");
    }
    TextView addSheetRow(String label, final Runnable act) {
        TextView r = tv(this, label, 16, Color.rgb(0x1C, 0x1C, 0x1E), false);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(this, 14), 0, dp(this, 14), 0);
        r.setBackground(roundRect(Color.rgb(0xF2, 0xF3, 0xF7), 14, this));
        r.setOnClickListener(v -> { haptic(); act.run(); });
        return r;
    }
    void closeAddSheet(final View sheet) {
        if (sheet == addSheetView) addSheetView = null;
        if (sheet == null || sheet.getParent() == null) { restoreChrome(); return; }
        // Q74：链式打开（行点击已置 suppressNextChromeRestore 并立刻开下一窗）走即时摘除，
        // 由下一窗的升起动画承接过渡，不叠两段收起动画拖慢链路；取消/点遮罩走精加工收起。
        if (suppressNextChromeRestore) {
            ((ViewGroup) sheet.getParent()).removeView(sheet);
            restoreChrome();
            return;
        }
        View card = sheet instanceof ViewGroup && ((ViewGroup) sheet).getChildCount() > 0
            ? ((ViewGroup) sheet).getChildAt(((ViewGroup) sheet).getChildCount() - 1) : null;
        if (card != null) {
            card.animate().cancel();
            card.animate().alpha(0f).translationY(dp(this, SHEET_RISE_DP))
                .setDuration(ANIM_DUR_SHEET_OUT).setInterpolator(ANIM_EXIT)
                .withEndAction(() -> {
                    if (sheet.getParent() != null) ((ViewGroup) sheet.getParent()).removeView(sheet);
                    restoreChrome();
                }).start();
            sheet.animate().cancel();
            sheet.animate().alpha(0f).setDuration(ANIM_DUR_SHADE_OUT).setInterpolator(ANIM_EXIT).start();
            sheet.postDelayed(() -> {
                if (sheet.getParent() != null) ((ViewGroup) sheet.getParent()).removeView(sheet);
                restoreChrome();
            }, ANIM_DUR_SHEET_OUT + 40);
        } else {
            ((ViewGroup) sheet.getParent()).removeView(sheet);
            restoreChrome();
        }
    }

    void updateFilterFabBadge() {
        if (filterFabBadge == null || filterFab == null) return;
        int n = activeFilterCount() + scoreDimsSel.size(); // Q67：维度选择也让筛选钮角标有反馈（不计入 activeFilterCount，不影响英雄卡显隐）
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
        fab.addView(fabFrostWash(), new FrameLayout.LayoutParams(
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
        fab.addView(fabFrostWash(), new FrameLayout.LayoutParams(
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
        bar.animate().alpha(0f).translationY(-dp(this, 8)).setDuration(ANIM_DUR_SHEET_OUT)
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
        int ac = accentColor();
        return new RippleDrawable(ColorStateList.valueOf(Color.argb(38, Color.red(ac), Color.green(ac), Color.blue(ac))), base, null); // Q72 波纹随主题色
    }

    View cardTile(final Card c, ViewGroup parent) {
        return cardTile(c, parent, cols);
    }

    // Q24 首页瓷砖加卡钮（对照混合版 .mine-btn：32dp 半透圆钮，3 列 26dp）：未加入灰半透底＋白色加号、
    // 已加入蓝半透底＋白色勾（rgba(0,122,255,.38)），细线 Canvas 绘制禁用 emoji；按下 .9 回弹照 .mine-btn:active。
    // Q47 磨砂玻璃化（用户 22:24 点名，现行平色「塑料感」）：混合版 .mine-btn 本就有 backdrop-filter:blur(8px)，
    // 原生改取钮身下卡图一次性预渲染低清高斯片（降采样 14px 再放大回落柔糊+饱和 1.5，随卡图路径缓存、
    // 滚动零采样、禁 Bitmap.recycle 同 Q21），盖半透明白提亮/蓝调染色与 1dp 细描边；字形按底片明暗取白或深。
    static class MineFrost {
        Bitmap bmp; int lum = 255;
    }
    static final LruCache<String, MineFrost> mineFrostCache = new LruCache<String, MineFrost>(4 * 1024) {
        protected int sizeOf(String k, MineFrost f) { return f == null || f.bmp == null ? 1 : Math.max(1, f.bmp.getByteCount() / 1024); }
    };
    static MineFrost makeMineFrost(Bitmap src, String key) {
        if (src == null || src.isRecycled() || src.getWidth() <= 0 || src.getHeight() <= 0) return null;
        if (key != null) {
            MineFrost hit = mineFrostCache.get(key);
            if (hit != null) return hit;
        }
        try {
            int sw = src.getWidth(), sh = src.getHeight();
            int cw = Math.max(2, Math.round(sw * 0.30f));
            int ch = Math.max(2, Math.round(sh * 0.42f));
            Bitmap crop = Bitmap.createBitmap(src, Math.max(0, sw - cw), 0, Math.min(cw, sw), Math.min(ch, sh));
            Bitmap small = Bitmap.createScaledBitmap(crop, 14, 14, true);
            // 饱和 1.5 把卡面颜色透进来（同 applyGlass 口径），小图放大绘制即高斯柔糊感
            Bitmap out = Bitmap.createBitmap(28, 28, Bitmap.Config.ARGB_8888);
            Canvas oc = new Canvas(out);
            Paint sp = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
            ColorMatrix cm = new ColorMatrix();
            cm.setSaturation(1.5f);
            sp.setColorFilter(new ColorMatrixColorFilter(cm));
            oc.drawBitmap(small, null, new RectF(0, 0, 28, 28), sp);
            long sum = 0;
            for (int y = 0; y < small.getHeight(); y++) {
                for (int x = 0; x < small.getWidth(); x++) {
                    int px = small.getPixel(x, y);
                    sum += Math.round(0.2126f * Color.red(px) + 0.7152f * Color.green(px) + 0.0722f * Color.blue(px));
                }
            }
            MineFrost f = new MineFrost();
            f.bmp = out;
            f.lum = (int) (sum / Math.max(1, small.getWidth() * small.getHeight()));
            if (key != null) mineFrostCache.put(key, f);
            return f;
        } catch (Throwable t) {
            return null; // 生成失败回落浅白玻璃兜底，不为钮底冒崩点
        }
    }

    // Q70：占位卡面右下角卡组织小标——按 org 自绘，禁用 emoji、绝不用万事达双圆充一切；org 空/未知不画
    class OrgBadgeView extends View {
        final String org;
        OrgBadgeView(Context ctx, String o) { super(ctx); org = o == null ? "" : o.trim(); setClickable(false); setFocusable(false); }
        @Override protected void onDraw(Canvas cv) {
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0 || org.isEmpty()) return;
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            float pad = dp(getContext(), 1);
            RectF box = new RectF(pad, pad, w - pad, h - pad);
            try {
                if (org.equals("mastercard") || org.equals("mastercard-nucc")) {
                    float r = h * (org.equals("mastercard-nucc") ? 0.30f : 0.38f);
                    float cy = org.equals("mastercard-nucc") ? h * 0.36f : h / 2f;
                    float cx = w / 2f;
                    p.setStyle(Paint.Style.FILL);
                    p.setColor(Color.rgb(0xEB, 0x00, 0x1B));
                    cv.drawCircle(cx - r * 0.55f, cy, r, p);
                    p.setColor(Color.argb(215, 0xF7, 0x9E, 0x1B));
                    cv.drawCircle(cx + r * 0.55f, cy, r, p);
                    if (org.equals("mastercard-nucc")) {
                        p.setColor(Color.WHITE);
                        p.setTextSize(h * 0.26f);
                        p.setTypeface(weightTypeface(getContext(), 600));
                        p.setTextAlign(Paint.Align.CENTER);
                        p.setShadowLayer(dp(getContext(), 1), 0, dp(getContext(), 0.5f), Color.argb(120, 0, 0, 0));
                        cv.drawText("万事达·网联", cx, h * 0.92f, p);
                        p.clearShadowLayer();
                    }
                } else if (org.equals("unionpay")) {
                    // 银联：白底圆角 + 红/蓝/绿三色斜纹 + 「银联」
                    p.setStyle(Paint.Style.FILL);
                    p.setColor(Color.WHITE);
                    cv.drawRoundRect(box, dp(getContext(), 4), dp(getContext(), 4), p);
                    cv.save();
                    android.graphics.Path clip = new android.graphics.Path();
                    clip.addRoundRect(box, dp(getContext(), 4), dp(getContext(), 4), android.graphics.Path.Direction.CW);
                    cv.clipPath(clip);
                    int[] cols = { Color.rgb(0xE2, 0x18, 0x36), Color.rgb(0x00, 0x44, 0x7C), Color.rgb(0x00, 0x9B, 0x77) };
                    float stripeW = w * 0.30f;
                    for (int i = 0; i < 3; i++) {
                        p.setColor(cols[i]);
                        android.graphics.Path poly = new android.graphics.Path();
                        float x0 = i * stripeW;
                        poly.moveTo(x0, h);
                        poly.lineTo(x0 + stripeW * 0.55f, 0);
                        poly.lineTo(x0 + stripeW * 1.35f, 0);
                        poly.lineTo(x0 + stripeW * 0.80f, h);
                        poly.close();
                        cv.drawPath(poly, p);
                    }
                    cv.restore();
                    p.setColor(Color.WHITE);
                    p.setTextSize(h * 0.40f);
                    p.setTypeface(weightTypeface(getContext(), 700));
                    p.setTextAlign(Paint.Align.CENTER);
                    p.setShadowLayer(dp(getContext(), 1.2f), 0, dp(getContext(), 0.5f), Color.argb(140, 0, 0, 0));
                    cv.drawText("银联", w / 2f, h * 0.68f, p);
                    p.clearShadowLayer();
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(dp(getContext(), 0.7f));
                    p.setColor(Color.argb(70, 20, 30, 60));
                    cv.drawRoundRect(box, dp(getContext(), 4), dp(getContext(), 4), p);
                } else if (org.equals("visa")) {
                    p.setStyle(Paint.Style.FILL);
                    p.setColor(Color.WHITE);
                    cv.drawRoundRect(box, dp(getContext(), 4), dp(getContext(), 4), p);
                    p.setColor(Color.rgb(0x1A, 0x1F, 0x71));
                    p.setTextSize(h * 0.46f);
                    p.setTypeface(weightTypeface(getContext(), 800));
                    p.setTextSkewX(-0.25f);
                    p.setTextAlign(Paint.Align.CENTER);
                    cv.drawText("VISA", w / 2f, h * 0.70f, p);
                    p.setTextSkewX(0);
                } else if (org.equals("jcb")) {
                    int[] cols = { Color.rgb(0x0B, 0x4E, 0xA2), Color.rgb(0xCC, 0x00, 0x2E), Color.rgb(0x00, 0x8A, 0x3C) };
                    float bw = box.width() / 3f;
                    p.setStyle(Paint.Style.FILL);
                    for (int i = 0; i < 3; i++) {
                        p.setColor(cols[i]);
                        cv.drawRect(box.left + i * bw, box.top, box.left + (i + 1) * bw, box.bottom, p);
                    }
                    p.setColor(Color.WHITE);
                    p.setTextSize(h * 0.42f);
                    p.setTypeface(weightTypeface(getContext(), 800));
                    p.setTextAlign(Paint.Align.CENTER);
                    p.setShadowLayer(dp(getContext(), 1), 0, dp(getContext(), 0.5f), Color.argb(110, 0, 0, 0));
                    cv.drawText("JCB", w / 2f, h * 0.70f, p);
                    p.clearShadowLayer();
                } else if (org.equals("amex-cn")) {
                    p.setStyle(Paint.Style.FILL);
                    p.setColor(Color.rgb(0x2E, 0x77, 0xBC));
                    cv.drawRoundRect(box, dp(getContext(), 4), dp(getContext(), 4), p);
                    p.setColor(Color.WHITE);
                    p.setTextSize(h * 0.38f);
                    p.setTypeface(weightTypeface(getContext(), 800));
                    p.setTextAlign(Paint.Align.CENTER);
                    cv.drawText("AMEX", w / 2f, h * 0.68f, p);
                }
            } catch (Throwable ignored) { /* 小标绘制失败只不显示，不为占位冒崩点 */ }
        }
    }
    void addOrgBadge(FrameLayout parent, String org, float scale) {
        if (parent == null || !hasOrgBadge(org)) return;
        try {
            OrgBadgeView badge = new OrgBadgeView(this, org);
            int bw = Math.round(dp(this, orgBadgeWidthDp(org) * scale));
            int bh = Math.round(dp(this, 24 * scale));
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(bw, bh);
            lp.gravity = Gravity.BOTTOM | Gravity.RIGHT;
            lp.rightMargin = dp(this, 6);
            lp.bottomMargin = dp(this, 6);
            parent.addView(badge, lp);
        } catch (Throwable ignored) { }
    }

    class MineAddBtn extends View {
        boolean on = false;
        MineFrost frost = null;
        MineAddBtn(Context ctx) { super(ctx); setClickable(true); setFocusable(false); }
        void setOn(boolean v) { on = v; invalidate(); }
        void setCardImage(Bitmap src, String key) { frost = makeMineFrost(src, key == null ? null : "minefrost:" + key); }
        @Override protected void onDraw(Canvas cv) {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            float r = Math.min(getWidth(), getHeight()) / 2f;
            if (r <= 0) return;
            // 圆形裁切内铺磨砂片（钮身下卡图的低清高斯），无卡图时浅白提亮兜底
            cv.save();
            android.graphics.Path circle = new android.graphics.Path();
            circle.addCircle(cx, cy, r, android.graphics.Path.Direction.CW);
            cv.clipPath(circle);
            if (frost != null && frost.bmp != null && !frost.bmp.isRecycled()) {
                cv.drawBitmap(frost.bmp, null, new RectF(cx - r, cy - r, cx + r, cy + r), p);
            } else {
                p.setStyle(Paint.Style.FILL);
                p.setColor(Color.argb(205, 246, 247, 250));
                cv.drawCircle(cx, cy, r, p);
            }
            // 半透明白提亮（未加）/ 蓝调染色（已加），与 dock 玻璃一脉，不再平色塑料面
            p.setStyle(Paint.Style.FILL);
            p.setColor(on ? Color.argb(92, 0, 122, 255) : Color.argb(88, 255, 255, 255));
            cv.drawCircle(cx, cy, r, p);
            cv.restore();
            // 1dp 细描边（白色半透，玻璃边缘口径）
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(getContext(), 1f));
            p.setColor(Color.argb(150, 255, 255, 255));
            cv.drawCircle(cx, cy, r - dp(getContext(), 0.5f), p);
            // 字形按底色明暗取白/深保可辨：亮底深字、暗底或已加蓝态白字（白字带轻影压浅图）
            boolean lightBg = frost != null && frost.lum > 168;
            int glyph = (on || !lightBg) ? Color.WHITE : Color.rgb(0x1C, 0x1C, 0x1E);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            p.setStrokeWidth(dp(getContext(), 1.8f));
            p.setColor(glyph);
            if (glyph == Color.WHITE) p.setShadowLayer(dp(getContext(), 1.5f), 0, dp(getContext(), 0.5f), Color.argb(110, 0, 0, 0));
            else p.clearShadowLayer();
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
            p.clearShadowLayer();
        }
    }

    // P-grid：瓷砖规格统一——图区按 1.586 卡面比例定高（同列同宽同高）、卡名预留两行、行内等高拉伸，底边齐平
    // Q24：卡图改全幅 cover 铺满图区（对照混合版 .art/.art-img object-fit:cover，图区贴瓷砖顶边满宽、不留白、
    // 不拉伸；圆角靠瓷砖外框 clipToOutline 平滑裁切，冲突时保铺满）+ 图右上半透圆加卡钮（.mine-btn）。
    View cardTile(final Card c, ViewGroup parent, int nCols) {
        return cardTile(c, parent, nCols, "", false, true);
    }

    View cardTile(final Card c, ViewGroup parent, int nCols, final String acctClass) {
        return cardTile(c, parent, nCols, acctClass, true, false);
    }

    View cardTile(final Card c, ViewGroup parent, int nCols, final String acctClass, final boolean mineTile) {
        return cardTile(c, parent, nCols, acctClass, mineTile, false);
    }

    View cardTile(final Card c, ViewGroup parent, int nCols, final String acctClass, final boolean mineTile, final boolean showScoreDims) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setClipToOutline(true);
        AbsListView.LayoutParams lp = new AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        box.setLayoutParams(lp);

        // Q75：列数放行 1–4；四列列间距收至 8dp 给瓷砖让宽（行构建处同口径），图宽按真实列宽算
        int nc = (nCols >= 1 && nCols <= 4) ? nCols : 2;
        int gapDp = nc >= 4 ? 8 : 10;
        int availW = getResources().getDisplayMetrics().widthPixels - dp(this, 28) - (nc - 1) * dp(this, gapDp);
        int tileW = availW / nc;
        // Q33：瓷砖圆角随图宽缩放（图宽 4% 量级），且不低于原固定 14dp——只许更圆润不许回退变尖；
        // 顶图 cover 铺满不变（Q24 优先级：铺满第一、圆角第二），四角靠外框同半径裁切、无图占位同半径。
        float tileR = Math.max(14f, cardRadiusDp(tileW / getResources().getDisplayMetrics().density));
        box.setBackground(rippleBg(colSurface(), tileR));
        roundClip(box, tileR, this);
        int imgH = Math.max(dp(this, 40), Math.round(tileW / 1.586f));
        FrameLayout art = new FrameLayout(this);
        topSheetClip(art, tileR, this); // Q48 排查补强：图区容器同半径顶圆底直裁切，顶图四角不靠瓷砖外框单层 outline
        box.addView(art, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, imgH));
        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP); // cover：铺满不留白、等比不拉伸
        iv.setBackground(placeholderGradFor(c.id, 0, this));
        art.addView(iv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        Bitmap b = Img.get(this, c.image);
        if (b != null) { iv.setImageBitmap(b); if (darkEff()) iv.setAlpha(0.90f); } else { iv.setImageBitmap(null); addOrgBadge(art, c.org, 1f); }

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        // Q75：正文内边距按列数分档重排（四档外推混合版 .grid.c3 的 body 收紧口径再进一档），
        // 间距宁匀勿乱，不把双列的 8/7/8/10 硬挤进四列窄瓷砖
        int padH = nc >= 4 ? 6 : 8;
        int padT = nc >= 3 ? 6 : 7;
        int padB = nc >= 4 ? 8 : 10;
        body.setPadding(dp(this, padH), dp(this, padT), dp(this, padH), dp(this, padB));
        box.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Q75：四列卡名字号收至 11.5sp 仍保两行多显字（v3.77 定版），不许回退一行截断
        TextView name = tv(this, c.name, nc >= 4 ? 11.5f : 13, colText(), true);
        name.setMaxLines(2);
        name.setMinLines(2);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        body.addView(name, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView sub = tv(this, c.bank + " · " + orgLabel(c.org) + (c.isCredit() ? " · 信用卡" : ""), nc >= 4 ? 9f : 10.5f, colText2(), false);
        sub.setMaxLines(1);
        sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        body.addView(sub);

        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(this, nc >= 4 ? 5 : 6);
        body.addView(chips, clp);
        float chipSp = nc >= 4 ? 8f : nc == 3 ? 8.5f : 10f;
        List<String> selDims = showScoreDims ? selectedScoreDimsOrdered() : new ArrayList<>();
        if (showScoreDims && !selDims.isEmpty()) {
            // Q67：选了维度后总分退居其次（灰胶囊），分项分在下方单独成流展示。
            chips.addView(chip(c.hasScore ? String.format(java.util.Locale.US, "总分 %.1f", c.score) : "总分待评分",
                Color.rgb(0xEE, 0xF0, 0xF3), Color.rgb(0x63, 0x63, 0x66), chipSp));
        } else {
            chips.addView(chip(String.format(java.util.Locale.US, "%.1f分", c.score), accentSoftBg(), accentColor(), chipSp));
        }
        chips.addView(chip("已停发".equals(c.status) ? "已停发" : "在发",
            "已停发".equals(c.status) ? Color.rgb(0xF3, 0xE8, 0xE8) : Color.rgb(0xE6, 0xF6, 0xEC),
            "已停发".equals(c.status) ? Color.rgb(0xB0, 0x23, 0x2B) : Color.rgb(0x1D, 0x8A, 0x49), chipSp));
        final TextView addedChip = chip("已添加", Color.rgb(0xE6, 0xF6, 0xEC), Color.rgb(0x1D, 0x8A, 0x49), chipSp);
        // 我的卡片瓷砖不重复「已添加」（页面本身即自有条目），把同一格位留给类别标签，未标则不占位
        if (!mineTile && mine.contains(c.id)) chips.addView(addedChip);
        // Q65：类别标签只在用户自有条目瓷砖出现（我的卡片传入 acctClass），未标完全不占位；样式同现行 chips
        if (acctClass != null && !acctClass.isEmpty())
            chips.addView(chip(acctClass, Color.rgb(0xF0, 0xF7, 0xFF), Color.rgb(0x2F, 0x6F, 0xD0), chipSp));

        // Q75：四列下评分/状态/已添加三胶囊同行易溢出，统一收紧内边距与间隔防裁断到读不出
        if (nc >= 4) {
            for (int ci = 0; ci < chips.getChildCount(); ci++) {
                View cv = chips.getChildAt(ci);
                cv.setPadding(dp(this, 4), dp(this, 2), dp(this, 4), dp(this, 2));
                LinearLayout.LayoutParams cp = (LinearLayout.LayoutParams) cv.getLayoutParams();
                cp.rightMargin = dp(this, 4);
            }
        }

        if (showScoreDims && !selDims.isEmpty()) {
            View dimFlow = buildScoreDimFlow(c, tileW - dp(this, padH * 2), nc, selDims);
            if (dimFlow != null) {
                LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                dlp.topMargin = dp(this, 5);
                body.addView(dimFlow, dlp);
            }
        }

        // Q51：特点标签行（对照混合版 featChips——FEATS 顺序逐卡渲染命中的标签，
        // .feats 流式换行、最多两行溢出截断；此前原生瓷砖只出评分/状态行，标签全缺）
        View featFlow = buildFeatFlow(c, tileW - dp(this, padH * 2), nc);
        if (featFlow != null) {
            LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            flp.topMargin = dp(this, 5);
            body.addView(featFlow, flp);
        }

        // Q24 加卡钮：点它直接切换我的卡片不进详情（对照混合版 [data-mine] 点击 stopPropagation + toggleMine，
        // 反馈走既有悬浮提示条/撤销）；钮态与「已添加」chip 在切换/撤销后就地同步，不整页重绘。
        final MineAddBtn mineBtn = new MineAddBtn(this);
        mineBtn.setOn(mine.contains(c.id));
        mineBtn.setCardImage(b, c.image); // Q47：钮下卡图磨砂片一次性生成并缓存，无图回落浅白玻璃
        int btnSize = nc >= 4 ? dp(this, 22) : nc == 3 ? dp(this, 26) : dp(this, 32);
        int btnEdge = nc >= 4 ? dp(this, 5) : nc == 3 ? dp(this, 6) : dp(this, 8);
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
            handleMineAddButton(c, () -> {
                boolean in = mine.contains(c.id);
                mineBtn.setOn(in);
                if (mineTile) return; // 我的卡片瓷砖无「已添加」chip，只同步钮态
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
        // Q36：旧实现快照为方角位图、克隆层无圆角裁切，蓝框圆角内侧四角被方角白像素顶出（用户截图头顶两角外凸）。
        // 对照混合版 styles.css .card（border-radius:14px;overflow:hidden，克隆 pop-clone 的 box-shadow 随圆角走）
        // 与原生 Q27/Q33 同一套——位图级切圆（roundBitmap）+ 显式 RoundRect outline（roundClip 塑 elevation 阴影）。
        // 半径与锚卡同源：首页瓷砖 tileR=max(14,cardRadiusDp(图宽))，学生/选卡行为 14dp，本式在行宽下≈14.4dp 同量级。
        int w = Math.max(1, anchor.getWidth()), h = Math.max(1, anchor.getHeight());
        final float cloneR = Math.max(14f, cardRadiusDp(w / getResources().getDisplayMetrics().density));
        int[] rl = new int[2]; rootView.getLocationOnScreen(rl);
        int[] al = new int[2]; anchor.getLocationOnScreen(al);
        int left = al[0] - rl[0], top = al[1] - rl[1];
        FrameLayout clone = new FrameLayout(this);
        roundClip(clone, cloneR, this); // 克隆容器同半径裁切 + elevation 阴影按圆角轮廓，不再方角打影
        try {
            Bitmap snap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            anchor.draw(new Canvas(snap));
            ImageView iv = new ImageView(this);
            // 位图级四角切透明（半径按位图 px 换算即 dp(this,cloneR)），不靠视图硬剪贴，从根上无外凸无毛刺
            iv.setImageBitmap(roundBitmap(snap, dp(this, cloneR)));
            iv.setScaleType(ImageView.ScaleType.FIT_XY);
            roundClip(iv, cloneR, this);
            clone.addView(iv, new FrameLayout.LayoutParams(w, h));
        } catch (Exception e) { /* 快照失败仍保留蓝框定位 */ }
        GradientDrawable border = new GradientDrawable();
        border.setColor(Color.TRANSPARENT);
        border.setCornerRadius(dp(this, cloneR));
        border.setStroke(dp(this, 2.5f), Color.argb(179, 0, 122, 255));
        View borderV = new View(this);
        borderV.setBackground(border);
        borderV.setClickable(false);
        roundClip(borderV, cloneR, this);
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
        pbg.setColor(Color.argb(172, 255, 255, 255)); // Q29：198→172 减薄，冻结模糊的彩色透进来
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
            suppressNextChromeRestore = true;
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
            .setDuration(ANIM_DUR_CARDMENU_IN).setInterpolator(ANIM_ENTER).start();

        // 菜单出现时底栏让开，不挡靠近底部的卡（对照 setDockVisible(false)）
        hideChrome(); // Q12
    }

    static void removeViewNow(View v) {
        if (v != null && v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v);
    }

    // 关闭走 140ms 缩小淡出（对照 closeCardMenu），遮罩与高亮立即撤，底栏立即恢复
    void closeCardMenu() {
        final View pop = cardMenuPop, bd = cardMenuBackdrop, cl = cardMenuClone, mg = cardMenuGlass;
        cardMenuPop = null; cardMenuBackdrop = null; cardMenuClone = null; cardMenuGlass = null;
        removeViewNow(mg);
        restoreChrome(); // Q12
        removeViewNow(bd);
        removeViewNow(cl);
        if (pop == null) return;
        pop.animate().cancel();
        pop.animate().alpha(0f).scaleX(0.94f).scaleY(0.94f)
            .setDuration(ANIM_DUR_CARDMENU_OUT).setInterpolator(ANIM_EXIT)
            .withEndAction(() -> removeViewNow(pop)).start();
    }

    // 立即关闭（切页/开详情/返回拦截）：不走动画，避免浮层残留
    void dismissCardMenu() {
        View pop = cardMenuPop, bd = cardMenuBackdrop, cl = cardMenuClone, mg = cardMenuGlass;
        boolean wasOpen = pop != null;
        cardMenuPop = null; cardMenuBackdrop = null; cardMenuClone = null; cardMenuGlass = null;
        removeViewNow(mg);
        if (wasOpen) restoreChrome(); // Q12: no menu was open -> nothing to restore (avoids flicker on openDetail/showTab paths)
        removeViewNow(pop);
        removeViewNow(bd);
        removeViewNow(cl);
    }

    TextView chip(String s, int bg, int fg) {
        return chip(s, bg, fg, 10f);
    }

    // Q51：瓷砖特点标签流（对照混合版 .feats/featChips：FEATS 固定顺序、命中才出、
    // gap 4dp 流式换行、容器最高两行——.feats max-height 38px 口径，溢出截断不撑高瓷砖）
    View buildFeatFlow(Card c, int availPx, int nc) {
        List<String> labels = new ArrayList<>();
        for (String[] f : FEATS) if (featMatch(c, f[0])) labels.add(f[1]);
        if (labels.isEmpty()) return null;
        float sp = nc >= 4 ? 7.5f : nc == 3 ? 8f : 9.5f;
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        Paint mp = new Paint();
        mp.setTextSize(sp * uiScale * getResources().getDisplayMetrics().scaledDensity);
        int gap = dp(this, 4);
        LinearLayout row = null;
        int rowW = 0, rows = 0;
        for (String label : labels) {
            int w = (int) Math.ceil(mp.measureText(label)) + dp(this, nc >= 4 ? 9 : 13);
            if (row == null || (rowW > 0 && rowW + gap + w > availPx)) {
                if (rows >= 2) break; // 两行封顶，同混合版 overflow:hidden
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                if (rows > 0) rlp.topMargin = gap;
                row.setLayoutParams(rlp);
                wrap.addView(row);
                rowW = 0; rows++;
            }
            TextView t = chip(label, Color.rgb(0xF0, 0xF7, 0xFF), Color.rgb(0x2F, 0x6F, 0xD0), sp);
            if (nc >= 4) t.setPadding(dp(this, 4), dp(this, 2), dp(this, 4), dp(this, 2)); // Q75：与宽度测算同档收紧
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (rowW > 0) clp.leftMargin = gap;
            t.setLayoutParams(clp);
            row.addView(t);
            rowW += (rowW > 0 ? gap : 0) + w;
        }
        return wrap.getChildCount() > 0 ? wrap : null;
    }

    // Q67：所选评分维度流。已下发分项分用蓝 chip，未下发用灰 chip 标「—」，不拿 0 分冒充。
    View buildScoreDimFlow(Card c, int availPx, int nc, List<String> dims) {
        if (dims == null || dims.isEmpty()) return null;
        float sp = nc >= 4 ? 7.5f : nc == 3 ? 8f : 9.5f;
        int maxRows = 99; // Q67 是用户显式多选，所选维度不截断；选得多时瓷砖自然变高，不拿两行封顶吞掉后选维度
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        Paint mp = new Paint();
        mp.setTextSize(sp * uiScale * getResources().getDisplayMetrics().scaledDensity);
        int gap = dp(this, 4);
        LinearLayout row = null;
        int rowW = 0, rows = 0;
        for (String dim : dims) {
            Double v = c.scoreDim(dim);
            String label = scoreDimShort(dim) + " " + (v == null ? "—" : formatDimScore(v));
            int w = (int) Math.ceil(mp.measureText(label)) + dp(this, nc >= 4 ? 9 : 13);
            if (row == null || (rowW > 0 && rowW + gap + w > availPx)) {
                if (rows >= maxRows) break;
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                if (rows > 0) rlp.topMargin = gap;
                row.setLayoutParams(rlp);
                wrap.addView(row);
                rowW = 0; rows++;
            }
            TextView t = v == null
                ? chip(label, Color.rgb(0xEE, 0xF0, 0xF3), Color.rgb(0x8E, 0x8E, 0x93), sp)
                : chip(label, Color.rgb(0xE8, 0xF1, 0xFD), Color.rgb(0x0A, 0x5C, 0xD6), sp);
            if (nc >= 4) t.setPadding(dp(this, 4), dp(this, 2), dp(this, 4), dp(this, 2)); // Q75 同上
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (rowW > 0) clp.leftMargin = gap;
            t.setLayoutParams(clp);
            row.addView(t);
            rowW += (rowW > 0 ? gap : 0) + w;
        }
        return wrap.getChildCount() > 0 ? wrap : null;
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
        // Q73: unified thin tint (radius 999 capsule), true blur from the live glass layer.
        GradientDrawable g = glassTintDrawable(999, false);
        return g;
    }

    // Q37: float search capsule back to shallow glass (mixed .float-search rgba(255,255,255,.85)+blur24 saturate1.7; Q29 thinned 176/166)
    GradientDrawable glassFloatBg() {
        // Q73: unified thin tint (was argb176/166, the milky block the user rejected).
        return glassTintDrawable(999, false);
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
            int act = e.getActionMasked();
            if (homePullRefreshing) return false;
            if (act == MotionEvent.ACTION_DOWN) {
                if (homeScroll.getScrollY() == 0) {
                    homePullTracking = true; homePullDownY = e.getRawY(); homePullDy = 0f;
                } else { homePullTracking = false; homePullDy = 0f; }
                return false;
            }
            if (act == MotionEvent.ACTION_MOVE && homePullTracking) {
                if (homeScroll.getScrollY() != 0) { homePullTracking = false; homePullDy = 0f; updateHomePullUi(); return false; }
                float dy = e.getRawY() - homePullDownY;
                if (dy <= 0) { homePullDy = 0f; updateHomePullUi(); return false; }
                homePullDy = dy;
                updateHomePullUi();
                // Pull past the slop: take over so the list itself does not jitter; still let Q49 bar & tiles see UP via our return.
                return dy > dp(v.getContext(), 36);
            }
            if ((act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) && homePullTracking) {
                float dy = homePullDy;
                homePullTracking = false; homePullDownY = -1f;
                if (dy >= dp(v.getContext(), 72)) { startHomePullRefresh(); return true; }
                homePullDy = 0f; updateHomePullUi();
                return dy > dp(v.getContext(), 36);
            }
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
        // Q61：顶部下拉刷新指示（素净细线胶囊，默认隐藏；拉动渐显、松手触发后转「正在检查…」）
        homePullBar = new FrameLayout(this);
        homePullBar.setBackground(roundRect(Color.argb(238, 255, 255, 255), 999, this));
        homePullBar.setVisibility(View.GONE);
        homePullText = tv(this, "下拉检查更新", 12.5f, Color.rgb(0x3A, 0x3A, 0x3C), false);
        homePullText.setGravity(Gravity.CENTER);
        homePullText.setPadding(dp(this, 14), dp(this, 7), dp(this, 14), dp(this, 7));
        homePullBar.addView(homePullText, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        FrameLayout.LayoutParams pullLp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pullLp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        pullLp.topMargin = statusBarH() + dp(this, 10);
        page.addView(homePullBar, pullLp);
        // Q49：全部卡片长列表必备可拖拽滚动条（轨道 top 120dp 起、bottom 100dp 止于 dock 上沿）
        attachDragBar(page, homeScroll, false, 120, 100);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        // Q10: title + inline search flow together under the status bar; nothing floats over them.
        col.setPadding(dp(this, 14), statusBarH() + dp(this, 16), dp(this, 14), dockPad());
        homeScroll.addView(col, new ScrollView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Q39: .title-row h1 1.45rem/800/.02em
        TextView homeTitle = tvW(this, "卡盒", 24, Color.rgb(0x1C, 0x1C, 0x1E), 800);
        homeTitle.setLetterSpacing(0.02f); homeTitle.setLineSpacing(0, 1.15f);
        col.addView(homeTitle);

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
        // Q31：顶部「筛选」药丸下架——对照混合版 app.js 现行（顶部入口已去掉、只留右下悬浮筛选钮），
        // 已选计数走悬浮钮蓝色角标（updateFilterFabBadge），已选标签栏仍在下方可点删。

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

        // Q15 卡库总览英雄卡：对照 cardapp app.js lib-hero 与 styles.css .dash-hero/.dash-tiles
        // 渐变 135deg #16283F→#0B5FA5(55%)→#00A3C8、圆角 22、内边距 20、三格 gap 10 均匀间隙
        LinearLayout hero = new LinearLayout(this);
        homeHero = hero;
        hero.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable hg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
            new int[]{Color.rgb(0x16, 0x28, 0x3F), Color.rgb(0x0B, 0x5F, 0xA5), Color.rgb(0x00, 0xA3, 0xC8)});
        hg.setCornerRadius(dp(this, 22));
        hero.setBackground(hg);
        if (Build.VERSION.SDK_INT >= 21) hero.setElevation(dp(this, 10));
        hero.setPadding(dp(this, 20), dp(this, 20), dp(this, 20), dp(this, 20));
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = dp(this, 10);
        col.addView(hero, hlp);
        // .dash-kick .78rem/opacity .75/letter-spacing .14em；.dash-big 2.9rem/800/1.08；.dash-sub .85rem/opacity .88
        TextView kick = tvW(this, "卡库总览", 12.5f, Color.argb(191, 255, 255, 255), 600);
        kick.setLetterSpacing(0.14f);
        hero.addView(kick);
        LinearLayout bigRow = new LinearLayout(this);
        bigRow.setOrientation(LinearLayout.HORIZONTAL);
        bigRow.setGravity(Gravity.BOTTOM);
        TextView bigNum = tvW(this, String.valueOf(Store.all.size()), 44, Color.WHITE, 800);
        bigNum.setLineSpacing(0, 1.08f);
        bigRow.addView(bigNum);
        TextView bigUnit = tvW(this, "张卡", 16, Color.argb(217, 255, 255, 255), 600);
        LinearLayout.LayoutParams bulp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bulp.leftMargin = dp(this, 7);
        bulp.bottomMargin = dp(this, 5);
        bigRow.addView(bigUnit, bulp);
        hero.addView(bigRow);
        java.util.Set<String> orgs = new java.util.HashSet<>();
        for (Card c : Store.all) if (c.org != null && !c.org.isEmpty()) orgs.add(c.org);
        hero.addView(tv(this, banks.size() + " 家银行 · " + orgs.size() + " 大卡组织", 13, Color.argb(224, 255, 255, 255), false));
        LinearLayout tiles = new LinearLayout(this);
        tiles.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(this, 16);
        hero.addView(tiles, tlp);
        tiles.addView(heroTile("card", debit + " 张", "借记卡"), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams tile2Lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tile2Lp.leftMargin = dp(this, 10);
        tiles.addView(heroTile("card", (Store.all.size() - debit) + " 张", "信用卡"), tile2Lp);
        LinearLayout.LayoutParams tile3Lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tile3Lp.leftMargin = dp(this, 10);
        tiles.addView(heroTile("clock", stopped + " 张", "已停发"), tile3Lp);

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
        applyUiFont(searchBox, 400);
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
        applyUiFont(floatSearchBox, 400);
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

    // Q15 .dash-tile：玻璃格 rgba(255,255,255,.13)+1px rgba(255,255,255,.16) 边、圆角 14、内边距 10/12
    // 顶部 20dp 细线图标（ICO.card/ICO.clock 白色细线，禁用 emoji）+ .dt-v 1.05rem/700 + .dt-k .72rem
    View heroTile(String icon, String v, String k) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dp(this, 14));
        bg.setColor(Color.argb(33, 255, 255, 255));
        bg.setStroke(dp(this, 1), Color.argb(41, 255, 255, 255));
        t.setBackground(bg);
        t.setPadding(dp(this, 12), dp(this, 10), dp(this, 12), dp(this, 10));
        DashTileIconView ic = new DashTileIconView(this);
        ic.kind = icon;
        t.addView(ic, new LinearLayout.LayoutParams(dp(this, 20), dp(this, 20)));
        t.addView(tvW(this, v, 17, Color.WHITE, 700));
        t.addView(tv(this, k, 11, Color.argb(199, 255, 255, 255), false));
        return t;
    }

    // Q15 英雄卡三格细线图标：card=卡面矩形+磁条+芯片短线，clock=圆+时分针（24 网格、1.7dp 白色细线）
    class DashTileIconView extends View {
        String kind = "card";
        DashTileIconView(Context c) { super(c); }
        @Override protected void onDraw(Canvas cv) {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            p.setColor(Color.argb(235, 255, 255, 255));
            float sx = getWidth() / 24f, sy = getHeight() / 24f;
            p.setStrokeWidth(1.7f * sx);
            if ("clock".equals(kind)) {
                cv.drawCircle(12f * sx, 12f * sy, 8.5f * sx, p);
                cv.drawLine(12f * sx, 12f * sy, 12f * sx, 7.5f * sy, p);
                cv.drawLine(12f * sx, 12f * sy, 15.5f * sx, 14f * sy, p);
            } else {
                RectF r = new RectF(3f * sx, 6f * sy, 21f * sx, 18f * sy);
                cv.drawRoundRect(r, 2.5f * sx, 2.5f * sy, p);
                cv.drawLine(3f * sx, 10f * sy, 21f * sx, 10f * sy, p);
                cv.drawLine(6.5f * sx, 14.5f * sy, 11f * sx, 14.5f * sy, p);
            }
        }
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
            // Q69：评分状态以 Card.hasScore（条目是否带数值 score）为准，待评分/无 score 计未评分。
            if ("rated".equals(filterScoreStatus) && !c.hasScore) continue;
            if ("unrated".equals(filterScoreStatus) && c.hasScore) continue;
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

    // Q61 下拉刷新指示与触发（仅首页顶部；刷新沿 Q59 双线口径，完成后原地重绘保住滚动位）
    void updateHomePullUi() {
        if (homePullBar == null) return;
        if (homePullRefreshing) {
            if (homePullText != null) homePullText.setText("正在检查数据更新…");
            homePullBar.setVisibility(View.VISIBLE);
            homePullBar.setAlpha(1f);
            homePullBar.setTranslationY(0f);
            return;
        }
        if (homePullDy < dp(this, 8)) { homePullBar.setVisibility(View.GONE); return; }
        float prog = Math.min(1f, homePullDy / dp(this, 72));
        homePullBar.setVisibility(View.VISIBLE);
        homePullBar.setAlpha(0.35f + 0.65f * prog);
        homePullBar.setTranslationY(-dp(this, 10) + dp(this, 10) * prog);
        if (homePullText != null) {
            homePullText.setText(homePullDy >= dp(this, 72) ? "↑ 松开检查更新" : "↓ 下拉检查更新");
        }
    }
    void startHomePullRefresh() {
        if (homePullRefreshing) return;
        homePullRefreshing = true;
        homePullDy = 0f;
        updateHomePullUi();
        haptic();
        checkDataUpdate(true, true, () -> {
            homePullRefreshing = false;
            if (homePullBar != null) homePullBar.setVisibility(View.GONE);
        });
    }

    // Q21 ② 首页渲染签名：把决定网格内容的全部输入拼成一把钥匙——切页回来/关弹层这类「什么都没变」的 refresh 直接跳过整表重搭。
    String homeSig() {
        StringBuilder sb = new StringBuilder();
        sb.append(query).append('|').append(filterType).append('|').append(filterOrg).append('|')
          .append(filterStatus).append('|').append(filterBank).append('|').append(filterScoreStatus).append('|').append(filterFeats).append('|')
          .append(selectedScoreDimsOrdered()).append('|')
          .append(sortMode).append('|').append(cols).append('|').append(groupBank).append('|')
          .append(new java.util.TreeSet<>(bankOpen)).append('|').append(Store.dataVersion).append('|')
          .append(Store.all.size()).append('|').append(mine.size()).append(':');
        for (String id : new java.util.TreeSet<>(mine)) sb.append(id).append(',');
        return sb.toString();
    }

    void refreshHome() {
        if (homeList == null) return;
        // Q15：英雄卡仅在无搜索/无筛选时显示（同混合版 lib-hero 条件），有条件时整卡收起不占位
        if (homeHero != null) {
            boolean heroOn = query.isEmpty() && activeFilterCount() == 0;
            homeHero.setVisibility(heroOn ? View.VISIBLE : View.GONE);
        }
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
            // Q68：本地搜不到时给扩展卡库入口，冷门卡走在线索引，不在本地硬编
            if (query != null && !query.trim().isEmpty()) {
                TextView goExt = tv(this, "去扩展卡库搜「" + query.trim() + "」 ›", 13.5f, Color.rgb(0x0A, 0x5C, 0xD6), true);
                goExt.setGravity(Gravity.CENTER);
                goExt.setPadding(dp(this, 14), dp(this, 10), dp(this, 14), dp(this, 10));
                goExt.setBackground(rippleBg(Color.rgb(0xE8, 0xF1, 0xFD), 999));
                LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                glp.gravity = Gravity.CENTER_HORIZONTAL;
                glp.topMargin = dp(this, 10);
                final String fq = query.trim();
                goExt.setOnClickListener(v -> { haptic(); openExtendedSearch(); if (extInput != null) { extInput.setText(fq); try { extInput.setSelection(fq.length()); } catch (Throwable ignored) {} } });
                homeList.addView(goExt, glp);
            }
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
        boolean forceOpen = !query.isEmpty() || filterOrg != null || filterStatus != null || filterType != null || filterScoreStatus != null || !filterFeats.isEmpty();
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
        int rowGap = cols >= 4 ? dp(this, 8) : dp(this, 10); // Q75：与 cardTile 图宽测算同口径
        for (int j = 0; j < cols; j++) {
            if (i + j < list.size()) {
                final Card c = list.get(i + j);
                View tile = cardTile(c, row);
                LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
                if (j > 0) tlp.leftMargin = rowGap;
                tile.setLayoutParams(tlp);
                tile.setOnClickListener(v -> openDetail(c));
                row.addView(tile);
            } else {
                View spacer = new View(this);
                LinearLayout.LayoutParams slp2 = new LinearLayout.LayoutParams(0, 1, 1f);
                if (j > 0) slp2.leftMargin = rowGap;
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
        if (filterScoreStatus != null) activeFilterBar.addView(afPill("rated".equals(filterScoreStatus) ? "已评分" : "未评分", () -> { filterScoreStatus = null; refreshHome(); }));
        for (final String f : new ArrayList<>(filterFeats))
            activeFilterBar.addView(afPill(featLabel(f), () -> { filterFeats.remove(f); refreshHome(); }));
        if (filterType != null) activeFilterBar.addView(afPill("credit".equals(filterType) ? "信用卡" : "借记卡", () -> { filterType = null; refreshHome(); }));
        for (final String dim : selectedScoreDimsOrdered())
            activeFilterBar.addView(afPill("评分·" + scoreDimShort(dim), () -> {
                scoreDimsSel.remove(dim);
                if (("dim:" + dim).equals(sortMode)) sortMode = null;
                persistViewPrefs(); refreshHome();
            }));
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
        hideChrome(); // Q12
        final FrameLayout sheet = new FrameLayout(this);
        sheet.setBackgroundColor(Color.argb(102, 15, 20, 40)); // Q77b：窗外再压暗一档（对齐混合版 .dlg-backdrop rgba(0,0,0,.4)），与玻璃窗体明暗分明
        sheet.setOnClickListener(v -> closeFilterSheet());
        // Q77 定版：B 真玻璃悬浮窗（与卡面 +/✓ 钮、底栏同一套 Q73 玻璃语言）——冻结模糊垫底 +
        // 薄染色 tint + 提亮 wash，禁止实白板；窗放大、排版放呼吸，chips 点选仍即时 refreshHome。
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(glassTintDrawable(24, false));
        glassClip(card, 24, false);
        card.setOnClickListener(v -> {});
        // 头部固定（标题+清空/完成），不随内容滚动；Q77：标题 17sp、动作改有分量的药丸钮
        LinearLayout chead = new LinearLayout(this);
        chead.setOrientation(LinearLayout.HORIZONTAL);
        chead.setGravity(Gravity.CENTER_VERTICAL);
        chead.setPadding(dp(this, 18), dp(this, 14), dp(this, 14), dp(this, 10));
        // Q77b header lift: near-solid wash over the frozen glass at the title band only, so a
        // strong card colour behind the sheet cannot soak the title; body stays Q73 thin glass.
        try {
            GradientDrawable headLift = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                darkEff() ? new int[]{Color.argb(232, 52, 52, 58), Color.argb(214, 40, 40, 46)}
                          : new int[]{Color.argb(238, 255, 255, 255), Color.argb(220, 248, 250, 253)});
            float hr = dp(this, 24);
            headLift.setCornerRadii(new float[]{hr, hr, hr, hr, 0, 0, 0, 0});
            chead.setBackground(headLift);
        } catch (Throwable ignored) {}
        TextView cttl = tv(this, "\u7b5b\u9009", 17, colText(), true);
        chead.addView(cttl, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView clearT = tv(this, "\u6e05\u7a7a", 13, colText(), false);
        clearT.setGravity(Gravity.CENTER);
        GradientDrawable clearBg = new GradientDrawable();
        clearBg.setColor(colChipOff()); clearBg.setCornerRadius(dp(this, 999));
        clearBg.setStroke(dp(this, 1), colDivider());
        clearT.setBackground(clearBg);
        clearT.setMinWidth(dp(this, 62)); clearT.setMinHeight(dp(this, 34));
        clearT.setPadding(dp(this, 14), dp(this, 7), dp(this, 14), dp(this, 7));
        clearT.setOnClickListener(v -> {
            haptic();
            final String sType = filterType, sOrg = filterOrg, sStatus = filterStatus, sBank = filterBank, sSort = sortMode, sScoreStatus = filterScoreStatus;
            final boolean sGroup = groupBank;
            final java.util.Set<String> sFeats = new java.util.LinkedHashSet<>(filterFeats);
            final java.util.Set<String> sDims = new java.util.LinkedHashSet<>(scoreDimsSel);
            filterType = null; filterOrg = null; filterStatus = null; filterScoreStatus = null;
            filterFeats.clear(); filterBank = null; scoreDimsSel.clear();
            sortMode = null; groupBank = false; persistViewPrefs();
            rebuildFilterPanel(filterPanelRef); refreshHome();
            showFloatToast("已清空筛选", "撤销", () -> {
                filterType = sType; filterOrg = sOrg; filterStatus = sStatus; filterBank = sBank; sortMode = sSort; filterScoreStatus = sScoreStatus;
                groupBank = sGroup;
                filterFeats.clear(); filterFeats.addAll(sFeats);
                scoreDimsSel.clear(); scoreDimsSel.addAll(sDims);
                persistViewPrefs();
                if (filterPanelRef != null) rebuildFilterPanel(filterPanelRef);
                refreshHome();
                showFloatToast("已恢复筛选");
            });
        });
        chead.addView(clearT);
        TextView doneT = tv(this, "\u5b8c\u6210", 13.5f, Color.WHITE, true);
        doneT.setGravity(Gravity.CENTER);
        GradientDrawable doneBg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
            new int[]{Color.rgb(0x0A, 0x84, 0xFF), Color.rgb(0x00, 0x66, 0xE6)});
        doneBg.setCornerRadius(dp(this, 999));
        doneT.setBackground(doneBg);
        doneT.setMinWidth(dp(this, 68)); doneT.setMinHeight(dp(this, 34));
        doneT.setPadding(dp(this, 16), dp(this, 7), dp(this, 16), dp(this, 7));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.leftMargin = dp(this, 8);
        doneT.setLayoutParams(dlp);
        doneT.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) pressBounce(v, true);
            else if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL) pressBounce(v, false);
            return false;
        });
        clearT.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) pressBounce(v, true);
            else if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL) pressBounce(v, false);
            return false;
        });
        doneT.setOnClickListener(v -> { haptic(); closeFilterSheet(); }); // Q77：「完成」只关窗，刷新已在点选时即时发生
        chead.addView(doneT);
        View headDiv = new View(this);
        headDiv.setBackgroundColor(colDivider());
        card.addView(chead);
        card.addView(headDiv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(this, 1))));
        ScrollView sc = new ScrollView(this);
        thinScrollbar(sc);
        sc.setBackgroundColor(Color.TRANSPARENT);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(this, 18), dp(this, 6), dp(this, 18), dp(this, 18));
        sc.addView(panel, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(sc, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        filterScroll = sc;
        filterPanelRef = panel;
        rebuildFilterPanel(panel);
        int sw = getResources().getDisplayMetrics().widthPixels;
        int cardW = Math.min(sw - dp(this, 24), dp(this, 480)); // Q77：窗放大，不再 368dp 挤成一团
        filterCardW = cardW;
        int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.78);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(cardW, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.gravity = Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM;
        clp.leftMargin = dp(this, 12); clp.rightMargin = dp(this, 12);
        clp.bottomMargin = dp(this, 100) + navBarH(); // 浮在 dock 之上；Q26 再加导航栏避让
        card.measure(View.MeasureSpec.makeMeasureSpec(cardW, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST));
        clp.height = card.getMeasuredHeight();
        // Q77b: glass + wash + card share one clipped wrap so the three layers move/scale as one
        // body (Q74 entry/exit) and the 24dp rim stays aligned; wash is the Q73 light lift.
        FrameLayout wrap = new FrameLayout(this);
        glassClip(wrap, 24, false);
        if (Build.VERSION.SDK_INT >= 21) wrap.setElevation(dp(this, 24));
        wrap.addView(glassLayer(card, 24, false), new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        View wash = glassWashView(24, false);
        glassClip(wash, 24, false);
        wrap.addView(wash, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        wrap.addView(card, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        sheet.addView(wrap, clp);
        content.addView(sheet);
        filterSheet = sheet;
        sheet.setAlpha(0f);
        sheet.animate().alpha(1f).setDuration(ANIM_DUR_SHADE_IN).setInterpolator(ANIM_ENTER).start();
        wrap.setAlpha(0f);
        wrap.setScaleX(0.94f); wrap.setScaleY(0.94f);
        wrap.setTranslationY(dp(this, 14));
        wrap.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
            .setDuration(ANIM_DUR_SHEET_IN).setInterpolator(ANIM_ENTER).start();
    }

    LinearLayout filterPanelRef = null;
    int filterCardW = 0; // Q77：筛选窗实际宽，chips 流式排版按此算可用宽

    void closeFilterSheet() {
        final View sheet = filterSheet;
        if (sheet == null) return;
        filterSheet = null;
        if (sheet.getParent() == null) { restoreChrome(); return; }
        View card = sheet instanceof ViewGroup && ((ViewGroup) sheet).getChildCount() > 0
            ? ((ViewGroup) sheet).getChildAt(((ViewGroup) sheet).getChildCount() - 1) : null; // Q77b: last child is the unified glass wrap (glass+wash+card)
        if (card != null) {
            card.animate().alpha(0f).scaleX(0.96f).scaleY(0.96f).translationY(dp(this, 10))
                .setDuration(ANIM_DUR_SHEET_OUT).setInterpolator(ANIM_EXIT)
                .withEndAction(() -> { closeFilterSheetNow(sheet); restoreChrome(); }).start();
            sheet.animate().alpha(0f).setDuration(ANIM_DUR_SHADE_OUT).setInterpolator(ANIM_EXIT).start();
        } else {
            closeFilterSheetNow(sheet);
            restoreChrome();
        }
    }

    void closeFilterSheetNow() { closeFilterSheetNow(filterSheet); }

    void closeFilterSheetNow(View sheet) {
        if (sheet != null && sheet.getParent() != null)
            ((ViewGroup) sheet.getParent()).removeView(sheet);
        if (sheet == filterSheet) filterSheet = null;
        if (sheet != null) { filterScroll = null; filterPanelRef = null; filterCardW = 0; }
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
        // Q13: 在发/已停发互斥——已点亮一项时另一项置灰不可点（再点已选项取消后恢复），不许两项同亮自相矛盾
        stChips.add(filterChip("\u5728\u53d1", "\u5728\u53d1".equals(filterStatus), () -> { filterStatus = "\u5728\u53d1".equals(filterStatus) ? null : "\u5728\u53d1"; rebuildFilterPanel(panel); refreshHome(); }, filterStatus != null && !"\u5728\u53d1".equals(filterStatus)));
        stChips.add(filterChip("\u5df2\u505c\u53d1", "\u5df2\u505c\u53d1".equals(filterStatus), () -> { filterStatus = "\u5df2\u505c\u53d1".equals(filterStatus) ? null : "\u5df2\u505c\u53d1"; rebuildFilterPanel(panel); refreshHome(); }, filterStatus != null && !"\u5df2\u505c\u53d1".equals(filterStatus)));
        addChipFlow(panel, stChips);

        // Q69：评分状态 —— 全部 / 已评分 / 未评分 三选，与现行筛选条件同级并存；以 Card.hasScore 为准（带数值 score 才算已评分）
        panel.addView(filterSectionTitle("评分状态"));
        List<View> scoreStatusChips = new ArrayList<>();
        scoreStatusChips.add(filterChip("全部", filterScoreStatus == null, () -> { filterScoreStatus = null; rebuildFilterPanel(panel); refreshHome(); }));
        scoreStatusChips.add(filterChip("已评分", "rated".equals(filterScoreStatus), () -> { filterScoreStatus = "rated".equals(filterScoreStatus) ? null : "rated"; rebuildFilterPanel(panel); refreshHome(); }));
        scoreStatusChips.add(filterChip("未评分", "unrated".equals(filterScoreStatus), () -> { filterScoreStatus = "unrated".equals(filterScoreStatus) ? null : "unrated"; rebuildFilterPanel(panel); refreshHome(); }));
        addChipFlow(panel, scoreStatusChips);

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

        panel.addView(filterSectionTitle("只看这些评分（可多选）"));
        List<String> dims = availableScoreDims();
        if (dims.isEmpty()) {
            TextView noDims = tv(this, "分项分随数据更新下发，当前卡库暂无可选维度", 11.5f, Color.rgb(0x8E, 0x8E, 0x93), false);
            panel.addView(noDims);
        } else {
            List<View> dimChips = new ArrayList<>();
            for (final String dim : dims) {
                dimChips.add(filterChip(scoreDimShort(dim), scoreDimsSel.contains(dim), () -> {
                    if (scoreDimsSel.contains(dim)) {
                        scoreDimsSel.remove(dim);
                        if (("dim:" + dim).equals(sortMode)) sortMode = null; // 维度已不展示时，不再按它隐形排序
                    } else {
                        scoreDimsSel.add(dim);
                    }
                    persistViewPrefs();
                    rebuildFilterPanel(panel); refreshHome();
                }));
            }
            addChipFlow(panel, dimChips);
        }

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
        List<String> selDimsForSort = selectedScoreDimsOrdered();
        if (!selDimsForSort.isEmpty()) {
            List<View> dimSortChips = new ArrayList<>();
            for (final String dim : selDimsForSort) {
                final String mode = "dim:" + dim;
                dimSortChips.add(filterChip("按" + scoreDimShort(dim) + "评分", mode.equals(sortMode), () -> {
                    sortMode = mode.equals(sortMode) ? null : mode;
                    persistViewPrefs();
                    rebuildFilterPanel(panel); refreshHome();
                }));
            }
            addChipFlow(panel, dimSortChips);
        }

        panel.addView(filterSectionTitle("\u663e\u793a\u65b9\u5f0f"));
        List<View> dispChips = new ArrayList<>();
        dispChips.add(filterChip("\u663e\u793a\u5168\u90e8", !groupBank, () -> {
            groupBank = false; persistViewPrefs();
            rebuildFilterPanel(panel); refreshHome();
        }));
        dispChips.add(filterChip("\u6309\u94f6\u884c\u6298\u53e0", groupBank, () -> {
            groupBank = true; persistViewPrefs();
            rebuildFilterPanel(panel); refreshHome();
        }));
        addChipFlow(panel, dispChips);

        panel.addView(filterSectionTitle("\u5217\u6570"));
        String[][] colOpts = {{"1", "\u5355\u5217"}, {"2", "\u53cc\u5217"}, {"3", "\u4e09\u5217"}, {"4", "\u56db\u5217"}};
        List<View> colChips = new ArrayList<>();
        for (final String[] co : colOpts) {
            final int nCols = Integer.parseInt(co[0]);
            // Q13 A 案：按银行折叠时列数整组置灰禁用（当前所选以哑光蓝灰保留可见），切回显示全部即恢复可点
            colChips.add(filterChip(co[1], cols == nCols, () -> {
                cols = nCols; persistViewPrefs();
                rebuildFilterPanel(panel); refreshHome();
            }, groupBank));
        }
        addChipFlow(panel, colChips);
        if (groupBank) {
            TextView colsHint = tv(this, "\u6309\u94f6\u884c\u6298\u53e0\u65f6\u5217\u6570\u6682\u4e0d\u53ef\u8c03\uff0c\u5c55\u5f00\u94f6\u884c\u540e\u4ecd\u6309\u5f53\u524d\u5217\u6570\u663e\u793a", 11.5f, Color.rgb(0x8E, 0x8E, 0x93), false);
            LinearLayout.LayoutParams chp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            chp.topMargin = dp(this, 6);
            colsHint.setLayoutParams(chp);
            panel.addView(colsHint);
        }

        if (filterScroll != null) filterScroll.post(() -> filterScroll.scrollTo(0, keepY));
    }

    // P2e chips：紧凑胶囊流式排列（多枚一行），选中蓝渐变+勾；发卡行三列等宽（混合版 #chipsBank 口径）
    TextView filterChip(String label, boolean on, final Runnable act) {
        return filterChip(label, on, act, false);
    }

    // Q13 置灰禁用态：哑光灰底灰字、无点击无按压无震动；若为当前所选则以哑光蓝灰保留勾与选中可辨
    TextView filterChip(String label, boolean on, final Runnable act, boolean disabled) {
        TextView t = tv(this, (on ? "\u2713 " : "") + label, 13, on ? Color.WHITE : Color.rgb(0x1C, 0x1C, 0x1E), on);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setGravity(Gravity.CENTER);
        if (disabled) {
            t.setTextColor(on ? Color.rgb(0x6B, 0x7D, 0x94) : Color.rgb(0x9A, 0x9A, 0xA0));
            GradientDrawable dg = new GradientDrawable();
            dg.setColor(on ? Color.rgb(0xD9, 0xE4, 0xF2) : Color.rgb(0xE8, 0xEA, 0xEF));
            dg.setCornerRadius(dp(this, 999));
            dg.setStroke(dp(this, 1), Color.argb(10, 20, 30, 60));
            t.setBackground(dg);
            t.setAlpha(0.75f);
            t.setEnabled(false);
            t.setClickable(false);
            t.setPadding(dp(this, 13), dp(this, 8), dp(this, 13), dp(this, 8));
            return t;
        }
        if (on) {
            GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.rgb(0x0A, 0x84, 0xFF), Color.rgb(0x00, 0x66, 0xE6)});
            g.setCornerRadius(dp(this, 999));
            t.setBackground(g);
            if (Build.VERSION.SDK_INT >= 21) t.setElevation(dp(this, 2));
        } else {
            GradientDrawable g = new GradientDrawable();
            g.setColor(colChipOff());
            g.setCornerRadius(dp(this, 999));
            g.setStroke(dp(this, 1), colDivider());
            t.setBackground(g);
        }
        t.setPadding(dp(this, 13), dp(this, 8), dp(this, 13), dp(this, 8));
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
        int avail = (filterCardW > 0 ? filterCardW
            : Math.min(getResources().getDisplayMetrics().widthPixels - dp(this, 24), dp(this, 480))) - dp(this, 36);
        Paint mp = new Paint();
        mp.setTextSize(13f * uiScale * getResources().getDisplayMetrics().scaledDensity);
        LinearLayout row = null;
        int rowW = 0;
        for (View chip : chips) {
            String txt = ((TextView) chip).getText().toString();
            int w = (int) mp.measureText(txt) + dp(this, 28);
            if (row == null || (rowW > 0 && rowW + dp(this, 8) + w > avail)) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rlp.topMargin = dp(this, 8);
                row.setLayoutParams(rlp);
                panel.addView(row);
                rowW = 0;
            }
            if (rowW > 0) rowW += dp(this, 8);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (rowW > 0) clp.leftMargin = dp(this, 8);
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
            rlp.topMargin = dp(this, 8);
            row.setLayoutParams(rlp);
            for (int j = 0; j < 3; j++) {
                int idx = i + j;
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                if (j > 0) clp.leftMargin = dp(this, 8);
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
        TextView t = tv(this, s, 12.5f, colText2(), true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(this, 16);
        t.setLayoutParams(lp);
        return t;
    }

    // Q15 情景横幅：对照 cardapp index.html #wizBanner 与 styles.css .wiz-banner/.wiz-go
    // 渐变 120deg #0A84FF→#5E5CE6(62%)→#BF5AF2、圆角 18、内边距 13/14、白字主行+副行、白药丸「去选卡」蓝字
    View wizardBanner() {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.HORIZONTAL);
        b.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
            new int[]{Color.rgb(0x0A, 0x84, 0xFF), Color.rgb(0x5E, 0x5C, 0xE6), Color.rgb(0xBF, 0x5A, 0xF2)});
        bg.setCornerRadius(dp(this, 18));
        b.setBackground(bg);
        if (Build.VERSION.SDK_INT >= 21) b.setElevation(dp(this, 6));
        b.setPadding(dp(this, 14), dp(this, 13), dp(this, 14), dp(this, 13));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(this, 12);
        blp.bottomMargin = dp(this, 12);
        b.setLayoutParams(blp);
        LinearLayout tx = new LinearLayout(this);
        tx.setOrientation(LinearLayout.VERTICAL);
        b.addView(tx, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        tx.addView(tvW(this, "不知道选哪张？", 15, Color.WHITE, 700));
        tx.addView(tv(this, "情景选卡：留学 · 旅游 · 海淘 · 日常，答几题就给你排好", 12, Color.argb(224, 255, 255, 255), false));
        TextView go = tvW(this, "去选卡", 13, Color.rgb(0x0A, 0x5C, 0xD6), 700);
        go.setGravity(Gravity.CENTER);
        go.setBackground(roundRect(Color.WHITE, 999, this));
        go.setPadding(dp(this, 16), dp(this, 9), dp(this, 16), dp(this, 9));
        b.addView(go);
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
                    .setDuration(ANIM_DUR_SHEET_OUT).setInterpolator(ANIM_EXIT)
                    .withEndAction(() -> {
                        if (sheet.getParent() != null) ((ViewGroup) sheet.getParent()).removeView(sheet);
                        restoreChrome(); // Q12
                    }).start();
                sheet.animate().alpha(0f).setDuration(ANIM_DUR_SHADE_OUT).setInterpolator(ANIM_EXIT).start();
                return;
            }
            ((ViewGroup) sheet.getParent()).removeView(sheet);
        }
        restoreChrome(); // Q12
    }

    // 同 app.js wizBack：已在第一题（含未选场景由关闭键处理）就回到选场景，否则上一步
    void wizGoBack() {
        if (wizStep <= 1) { wizSc = null; wizStep = 0; wizA.clear(); showWizardPage(); }
        else { wizStep--; showWizardPage(); }
    }

    // P2b：选卡改悬浮窗——对照混合版 .wizard/.wiz-shade/.wiz-sheet：全屏轻遮罩（rgba(15,20,40,.46)）
    // +贴底大圆角窗（顶圆角 26、max-height 88vh、柔影），底层页面留在后面，关窗回到原页原位。
    void showWizardPage() {
        hideChrome(); // Q12
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
        cg.setColor(colSheet()); // Q14 近白不透口径 + Q72 深色浮层，窗身一整块同色到底
        float rTop = dp(this, 26);
        cg.setCornerRadii(new float[]{rTop, rTop, rTop, rTop, 0, 0, 0, 0});
        card.setBackground(cg);
        if (Build.VERSION.SDK_INT >= 21) {
            card.setElevation(dp(this, 24));
            topSheetClip(card, 26, this); // Q45 顶圆底直轮廓
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
        View wizGlass = glassLayer(card, 26, false);
        topSheetClip(wizGlass, 26, this); // Q54：玻璃轮廓与窗体同（顶圆底直），不得在窗外露面发雾
        wizGlassWrap.addView(wizGlass, new FrameLayout.LayoutParams(
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
                .setDuration(ANIM_DUR_FADE).setInterpolator(ANIM_ENTER).start();
        } else {
            sheet.setAlpha(0f);
            sheet.animate().alpha(1f).setDuration(ANIM_DUR_SHADE_IN).setInterpolator(ANIM_ENTER).start();
            card.setTranslationY(dp(this, 42));
            card.animate().translationY(0f)
                .setDuration(ANIM_DUR_SHEET_IN).setInterpolator(ANIM_ENTER).start();
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
            // Q14：轨迹行照混合版 .wiz-done——#F2FBF4 浅底+rgba(46,140,60,.16) 描边、圆角 13、内边距 12/10，
            // 废旧整行厚绿 #E9F7EE（改浅底细边，层级标签改绿 chip、值 600、「修改」改灰字）
            GradientDrawable trailBg = new GradientDrawable();
            trailBg.setColor(Color.rgb(0xF2, 0xFB, 0xF4));
            trailBg.setCornerRadius(dp(this, 13));
            trailBg.setStroke(Math.max(1, dp(this, 1)), Color.argb(41, 46, 140, 60));
            row.setBackground(trailBg);
            row.setPadding(dp(this, 12), dp(this, 10), dp(this, 12), dp(this, 10));
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.topMargin = dp(this, 7);
            row.setLayoutParams(rlp);
            TextView kv = tv(this, (String) it[1], 11, Color.rgb(0x22, 0x86, 0x3A), true);
            kv.setBackground(roundRect(Color.rgb(0xE0, 0xF5, 0xE4), 6, this));
            kv.setPadding(dp(this, 7), dp(this, 3), dp(this, 7), dp(this, 3));
            row.addView(kv);
            TextView val = tv(this, (String) it[2], 14, Color.rgb(0x1C, 0x1C, 0x1E), true);
            val.setSingleLine(true);
            val.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams valLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            valLp.leftMargin = dp(this, 8);
            row.addView(val, valLp);
            TextView ed = tv(this, "修改", 11.5f, Color.rgb(0x9A, 0x9A, 0xA0), false);
            LinearLayout.LayoutParams edLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            edLp.leftMargin = dp(this, 8);
            row.addView(ed, edLp);
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
        glp.topMargin = dp(this, 10);
        glp.bottomMargin = dp(this, 8);
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
        // Q14 头部照混合版 .wiz-head/.wiz-nav/.wiz-title：返回/关闭同为 34dp 圆钮 #F0F0F4、
        // 返回仅细线 ‹（废 84dp 大药丸）、标题 flex 居中 1.02rem/700、✕ 走 CloseIconView 细线自绘
        FrameLayout back = new FrameLayout(this);
        back.setBackground(roundRect(Color.rgb(0xF0, 0xF0, 0xF4), 999, this));
        TextView backGlyph = tv(this, "‹", 24, Color.rgb(0x1C, 0x1C, 0x1E), false);
        backGlyph.setGravity(Gravity.CENTER);
        back.addView(backGlyph, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        back.setVisibility(wizSc == null ? View.GONE : View.VISIBLE);
        back.setOnClickListener(v -> { haptic(); wizGoBack(); });
        top.addView(back, new LinearLayout.LayoutParams(dp(this, 34), dp(this, 34)));
        TextView ttl = tv(this, title, 16, Color.rgb(0x1C, 0x1C, 0x1E), true);
        ttl.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams ttlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        ttlp.leftMargin = dp(this, 8);
        ttlp.rightMargin = dp(this, 8);
        top.addView(ttl, ttlp);
        FrameLayout close = new FrameLayout(this);
        close.setBackground(roundRect(Color.rgb(0xF0, 0xF0, 0xF4), 999, this));
        CloseIconView closeIc = new CloseIconView(this);
        closeIc.iconColor = Color.rgb(0x1C, 0x1C, 0x1E);
        closeIc.lineDp = 1.6f;
        closeIc.setPadding(dp(this, 10), dp(this, 10), dp(this, 10), dp(this, 10));
        close.addView(closeIc, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        close.setOnClickListener(v -> { haptic(); closeWizard(); });
        top.addView(close, new LinearLayout.LayoutParams(dp(this, 34), dp(this, 34)));

        if (wizSc == null) {
            TextView sub = tv(this, "打算拿卡做什么？选个场景往下答，每答完一题上面都会留一条，随时看清走到哪一步。", 12.5f, Color.rgb(0x8E, 0x8E, 0x93), false);
            LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            subLp.topMargin = dp(this, 12);
            page.addView(sub, subLp);
            // Q14：场景改混合版 .sc-grid 2×2 网格卡（.wz-scene：纵列、SF 细线图标禁用 emoji、
            // 名称 1rem+描述 .74rem、内边距 15/14、圆角 18、1dp 淡描边、按压回弹），废横排整行+› 箭头
            for (int i = 0; i < WIZ_SCENARIOS.length; i += 2) {
                LinearLayout gridRow = new LinearLayout(this);
                gridRow.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams grLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                grLp.topMargin = dp(this, 10);
                gridRow.setLayoutParams(grLp);
                page.addView(gridRow);
                for (int j = i; j < Math.min(i + 2, WIZ_SCENARIOS.length); j++) {
                    final WizSc s = WIZ_SCENARIOS[j];
                    LinearLayout tile = new LinearLayout(this);
                    tile.setOrientation(LinearLayout.VERTICAL);
                    tile.setBackground(rippleBg(Color.WHITE, 18));
                    tile.setClipToOutline(true);
                    if (Build.VERSION.SDK_INT >= 21) tile.setElevation(dp(this, 3));
                    tile.setPadding(dp(this, 14), dp(this, 15), dp(this, 14), dp(this, 15));
                    LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                    if (j > i) tlp.leftMargin = dp(this, 10);
                    tile.setLayoutParams(tlp);
                    SceneIconView ic = new SceneIconView(this);
                    ic.kind = s.id;
                    LinearLayout.LayoutParams icLp = new LinearLayout.LayoutParams(dp(this, 26), dp(this, 26));
                    icLp.bottomMargin = dp(this, 6);
                    tile.addView(ic, icLp);
                    tile.addView(tv(this, s.name, 16, Color.rgb(0x1C, 0x1C, 0x1E), true));
                    TextView dsc = tv(this, s.desc, 12, Color.rgb(0x8E, 0x8E, 0x93), false);
                    dsc.setLineSpacing(0, 1.45f);
                    LinearLayout.LayoutParams dsLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    dsLp.topMargin = dp(this, 4);
                    tile.addView(dsc, dsLp);
                    tile.setOnClickListener(v -> { haptic(); wizSc = s.id; wizStep = 1; showWizardPage(); });
                    gridRow.addView(tile);
                }
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
                // Q14：选项照混合版 .wiz-opt——#F4F5F9 浅底+rgba(20,30,60,.06) 描边、圆角 14、内边距 14/13
                GradientDrawable optBgBase = new GradientDrawable();
                optBgBase.setColor(Color.rgb(0xF4, 0xF5, 0xF9));
                optBgBase.setCornerRadius(dp(this, 14));
                optBgBase.setStroke(Math.max(1, dp(this, 1)), Color.argb(15, 20, 30, 60));
                opt.setBackground(new RippleDrawable(
                    android.content.res.ColorStateList.valueOf(Color.argb(38, 10, 92, 214)), optBgBase, null));
                opt.setClipToOutline(true);
                opt.setPadding(dp(this, 14), dp(this, 13), dp(this, 14), dp(this, 13));
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

        TextView redo = tv(this, "换个场景重新选", 14, Color.rgb(0x0A, 0x5C, 0xD6), true);
        redo.setGravity(Gravity.CENTER);
        redo.setPadding(dp(this, 8), dp(this, 8), dp(this, 8), dp(this, 8));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(this, 14);
        page.addView(redo, rlp);
        redo.setOnClickListener(v -> { haptic(); wizSc = null; wizStep = 0; wizA.clear(); showWizardPage(); });
        return col;
    }

    View wizResultRow(final WizResult r) {
        final Card c = r.c;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        // Q14：结果行照混合版 .wiz-row——白卡圆角 16+rgba(20,30,60,.06) 描边+柔影、内边距 10、gap 11
        row.setBackground(rippleBg(Color.WHITE, 16));
        row.setClipToOutline(true);
        if (Build.VERSION.SDK_INT >= 21) row.setElevation(dp(this, 3));
        row.setPadding(dp(this, 10), dp(this, 10), dp(this, 10), dp(this, 10));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(this, 9);
        row.setLayoutParams(rlp);

        FrameLayout wizThumb = new FrameLayout(this);
        row.addView(wizThumb, new LinearLayout.LayoutParams(dp(this, 76), dp(this, 48)));
        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setBackground(placeholderGradFor(c.id, 9, this));
        roundClip(iv, 9, this); // Q27 同机制：缩略图自身圆角裁切，不靠父行轮廓
        wizThumb.addView(iv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        roundClip(wizThumb, 9, this);
        Bitmap b = Img.get(this, c.image);
        if (b != null) iv.setImageBitmap(b); else addOrgBadge(wizThumb, c.org, 0.75f);

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        ilp.leftMargin = dp(this, 11);
        row.addView(info, ilp);
        TextView nm = tv(this, c.name, 14.5f, Color.rgb(0x1C, 0x1C, 0x1E), true);
        nm.setMaxLines(2);
        nm.setEllipsize(android.text.TextUtils.TruncateAt.END);
        info.addView(nm);
        String orgTxt = (c.org == null || c.org.isEmpty()) ? "—" : orgLabel(c.org);
        TextView meta = tv(this, c.bank + " · " + orgTxt + " · " + (c.isCredit() ? "信用卡" : "借记卡"), 11.5f, Color.rgb(0x8E, 0x8E, 0x93), false);
        meta.setSingleLine(true);
        meta.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams metaLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        metaLp.topMargin = dp(this, 2);
        info.addView(meta, metaLp);
        if (!r.reasons.isEmpty()) {
            // Q14：理由 chips 照 .wiz-reasons 流式换行（#F0F7FF/#2F6FD0、圆角 6、gap 4），
            // 废旧单行横排——长标签曾被右列挤成竖条
            int availPx = getResources().getDisplayMetrics().widthPixels
                - dp(this, 36) - dp(this, 20) - dp(this, 76) - dp(this, 11) - dp(this, 52);
            LinearLayout wrap = new LinearLayout(this);
            wrap.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams wrapLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            wrapLp.topMargin = dp(this, 6);
            info.addView(wrap, wrapLp);
            Paint mp = new Paint();
            mp.setTextSize(10.5f * uiScale * getResources().getDisplayMetrics().scaledDensity);
            int gap = dp(this, 4);
            LinearLayout crow = null;
            int crowW = 0;
            for (String reason : r.reasons) {
                int w = (int) Math.ceil(mp.measureText(reason)) + dp(this, 12);
                if (crow == null || (crowW > 0 && crowW + gap + w > availPx)) {
                    crow = new LinearLayout(this);
                    crow.setOrientation(LinearLayout.HORIZONTAL);
                    LinearLayout.LayoutParams crLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    if (wrap.getChildCount() > 0) crLp.topMargin = gap;
                    crow.setLayoutParams(crLp);
                    wrap.addView(crow);
                    crowW = 0;
                }
                TextView t = chip(reason, Color.rgb(0xF0, 0xF7, 0xFF), Color.rgb(0x2F, 0x6F, 0xD0), 10.5f);
                LinearLayout.LayoutParams clp2 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                if (crowW > 0) clp2.leftMargin = gap;
                clp2.rightMargin = 0;
                t.setLayoutParams(clp2);
                crow.addView(t);
                crowW += (crowW > 0 ? gap : 0) + w;
            }
        }
        String limit = c.spec("发行情况");
        if (limit.contains("仅")) {
            TextView note = tv(this, "⚠ " + limit, 11, Color.rgb(0xB2, 0x50, 0x00), false);
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
        // Q14：评分胶囊照混合版 .score+scoreCls（≥8 hi/≥6 mid/余 lo 三色），文案 %.1f分/待评分（scoreTxt 口径）
        int scoreBg, scoreFg;
        if (!c.hasScore) { scoreBg = Color.rgb(0xEE, 0xF0, 0xF3); scoreFg = Color.rgb(0x8E, 0x8E, 0x93); }
        else if (c.score >= 8) { scoreBg = Color.rgb(0xE7, 0xF0, 0xFE); scoreFg = Color.rgb(0x0A, 0x5C, 0xD6); }
        else if (c.score >= 6) { scoreBg = Color.rgb(0xFD, 0xF1, 0xE0); scoreFg = Color.rgb(0xB2, 0x5B, 0x09); }
        else { scoreBg = Color.rgb(0xEE, 0xF0, 0xF3); scoreFg = Color.rgb(0x8E, 0x8E, 0x93); }
        TextView score = tv(this, c.hasScore ? String.format(java.util.Locale.US, "%.1f分", c.score) : "待评分",
            11, scoreFg, true);
        score.setBackground(roundRect(scoreBg, 999, this));
        score.setPadding(dp(this, 9), dp(this, 4), dp(this, 9), dp(this, 4));
        side.addView(score);
        // Q14：加卡钮照 .wiz-add——30dp 浅色圆（未加 #EEF4FF 蓝字＋、已加 #0A84FF 白字 ✓），废 38dp 大实心蓝圆
        final boolean inMine = mine.contains(c.id);
        TextView add = tv(this, inMine ? "✓" : "＋", 15, inMine ? Color.WHITE : Color.rgb(0x0A, 0x5C, 0xD6), true);
        add.setGravity(Gravity.CENTER);
        add.setBackground(roundRect(inMine ? Color.rgb(0x0A, 0x84, 0xFF) : Color.rgb(0xEE, 0xF4, 0xFF), 999, this));
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(dp(this, 30), dp(this, 30));
        alp.topMargin = dp(this, 7);
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
    // 窗体贴底全宽、顶圆 20dp、最高 88vh、内滚、底内边 20dp；关闭钮 Q30 改为长在窗体内部：
    // 对照混合版 .p-close（34dp 半透圆、top/right 12dp、.panel 子层随窗升降），禁止根层独立字形冻结/淡出。
    void openDetail(Card c) { detailEntryKey = null; openDetail(c, false); }

    void openDetailEntry(Card c, MineEntry e) {
        detailEntryKey = e == null ? null : e.key;
        openDetail(c, false);
    }

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
        hideChrome(); // Q12

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
        sheetBg.setColor(colSheet()); // Q72
        float rTop = dp(this, 20);
        sheetBg.setCornerRadii(new float[]{rTop, rTop, rTop, rTop, 0, 0, 0, 0});
        sheetCard.setBackground(sheetBg);
        if (Build.VERSION.SDK_INT >= 21) { sheetCard.setElevation(dp(this, 24)); topSheetClip(sheetCard, 20, this); } // Q45 顶圆底直轮廓
        sheetCard.setOnClickListener(v -> {}); // 窗体吃点击防穿透遮罩

        // 内容滚动区 + 底部常驻收藏钮（窗内延续，不随内容滚走）
        ScrollView sc = new ScrollView(this);
        thinScrollbar(sc);
        sc.setBackgroundColor(Color.TRANSPARENT);
        sc.setFillViewport(false);
        detailScroll = sc;
        LinearLayout body = buildDetailSheetBody(c);
        body.setPadding(0, 0, 0, dp(this, 10) + navBarH()); // Q28：内容落窗底，末行让开系统手势条
        sc.addView(body);
        sheetCard.addView(sc, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // Q28：详情只看不加入，底部收藏行整行移除，滚动区直落窗底

        // 先量高再定版（内容可能短于封顶）
        sheetCard.measure(View.MeasureSpec.makeMeasureSpec(screenW, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST));
        int sheetH = Math.min(sheetCard.getMeasuredHeight(), maxH);
        FrameLayout.LayoutParams wlp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, sheetH);
        wlp.gravity = Gravity.BOTTOM;
        // Q11：窗下垫冻结玻璃层，与窗同位（顶圆 20 对齐）；Q45：玻璃层高出窗体 20dp、底圆角沉到
        // 窗外由 wrap 裁掉，窗底两角只剩直角白窗贴齐屏底，不露玻璃/遮罩黑三角
        FrameLayout.LayoutParams detailGlassLp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, sheetH + dp(this, 20));
        View detailGlass = glassLayer(sheetCard, 20, false);
        topSheetClip(detailGlass, 20, this); // Q54：玻璃轮廓与窗体同（顶圆底直），不得在窗外露面发雾
        wrap.addView(detailGlass, detailGlassLp);
        wrap.addView(sheetCard, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        overlay.addView(wrap, wlp);
        detailSheetWrap = wrap;

        // Q30 关闭钮：长在窗体内部，随窗同升同降同销毁。对照混合版 .p-close 数值——
        // 34dp 半透圆、top/right 12dp 浮在图廊右上；48dp 只是触控框，圆心对齐靠框边距 5dp+居中 7dp=12dp。
        // 对比修正：混合版原底 rgba(120,120,128,.25) 在浅卡图/浅占位上会让白 ✕ 隐身，原生把圆底
        // 加深为半透炭灰 argb(142,54,56,64)+白半透描边+elevation 柔影，白细线 ✕ 再加暗晕，
        // 浅图/深图/占位渐变上都可辨，仍是半透若隐若现而非实心硬键（真机观感待验收）。
        final FrameLayout glyphFrame = new FrameLayout(this);
        glyphFrame.setClipChildren(false); glyphFrame.setClipToPadding(false);
        glyphFrame.setAlpha(0f);
        View circle = new View(this);
        GradientDrawable cg = new GradientDrawable();
        cg.setShape(GradientDrawable.OVAL);
        cg.setColor(Color.argb(142, 54, 56, 64));
        cg.setStroke(dp(this, 1), Color.argb(110, 255, 255, 255));
        circle.setBackground(cg);
        if (Build.VERSION.SDK_INT >= 21) circle.setElevation(dp(this, 5));
        FrameLayout.LayoutParams clp2 = new FrameLayout.LayoutParams(dp(this, 34), dp(this, 34));
        clp2.gravity = Gravity.CENTER;
        glyphFrame.addView(circle, clp2);
        CloseIconView x = new CloseIconView(this);
        x.iconColor = Color.WHITE;
        x.lineDp = 1.9f;
        x.shadow = true;
        x.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        int xPad = dp(this, 4); // 内衬 26dp → ✕ 约 13.5dp，对照混合版 .p-close 的 1rem 字形量级
        x.setPadding(xPad, xPad, xPad, xPad);
        FrameLayout.LayoutParams xlp = new FrameLayout.LayoutParams(dp(this, 34), dp(this, 34));
        xlp.gravity = Gravity.CENTER;
        glyphFrame.addView(x, xlp);
        glyphFrame.setOnClickListener(v -> { haptic(); closeDetail(); });
        FrameLayout.LayoutParams glp = new FrameLayout.LayoutParams(dp(this, 48), dp(this, 48));
        glp.gravity = Gravity.TOP | Gravity.END;
        glp.topMargin = dp(this, 5);
        glp.rightMargin = dp(this, 5);
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
        // 升起：遮罩 220ms 淡入 + 窗体自下方滑入 240ms 同曲线家族；✕ 长在窗内随窗同行，再略延迟淡入呈半透浮现
        shade.animate().alpha(1f).setDuration(ANIM_DUR_SHADE_IN).setInterpolator(ANIM_ENTER).start();
        wrap.setTranslationY(sheetH);
        wrap.animate().translationY(0f).setDuration(ANIM_DUR_SHEET_IN).setInterpolator(ANIM_ENTER).start();
        glyphFrame.animate().alpha(1f).setDuration(ANIM_DUR_FADE).setStartDelay(70).setInterpolator(ANIM_ENTER).start();
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
                            wrap.animate().translationY(0f).setDuration(ANIM_DUR_FADE)
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
        final boolean wasWiz = detailFromWiz;
        Runnable finish = () -> {
            if (overlay != null && overlay.getParent() instanceof ViewGroup)
                ((ViewGroup) overlay.getParent()).removeView(overlay);
            detailView = null; detailSheetWrap = null; detailShade = null; detailCloseGlyph = null;
            detailScroll = null; detailBinView = null; detailVerInfoBox = null;
            detailDots = new java.util.ArrayList<>();
            detailCard = null; detailClosing = false; detailFromWiz = false; detailEntryKey = null;
            restoreCurrentTabScroll();
            restoreChrome(); // Q12
            if (placeholderDirty) { placeholderDirty = false; rebuildPages(); } // Q71：自选色落盘后刷新底层瓷砖
            // FIFO：关窗落定才开下一次点选的那张，不叠窗
            Card next = detailQueue.poll();
            if (next != null) openDetail(next, wasWiz && wizardOpen);
        };
        if (overlay == null || wrap == null) { finish.run(); return; }
        // Q30：✕ 是 wrap 子层，不摘出、不冻结、不单独淡出——随窗同降，overlay 销毁时一并销毁，主页不留残影。
        // 关窗与混合版同口径并行：窗体 240ms 下滑、遮罩 180ms 淡出同时进行，落定才拆浮层。
        int targetY = wrap.getHeight() > 0 ? wrap.getHeight() : dp(this, 420);
        if (shade != null) shade.animate().alpha(0f).setDuration(ANIM_DUR_SHADE_OUT).setInterpolator(ANIM_EXIT).start();
        wrap.animate().translationY(targetY).setDuration(ANIM_DUR_SHEET_OUT).setInterpolator(ANIM_EXIT)
            .withEndAction(finish).start();
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

    // Q65 详情内「我的标记」：只对用户自有条目出现；可直接改 不标/一类/二类，并可再加一张或移除本张
    View buildDetailMineTagSection(final Card c) {
        MineEntry target = detailEntryKey == null ? null : findEntryByKey(detailEntryKey);
        if (target != null && !c.id.equals(target.cardId)) target = null;
        java.util.List<MineEntry> mineOfCard = entriesForCard(c.id);
        if (target == null) {
            if (mineOfCard.size() == 1) target = mineOfCard.get(0);
            else if (mineOfCard.size() > 1) {
                LinearLayout box = new LinearLayout(this);
                box.setOrientation(LinearLayout.VERTICAL);
                box.setBackground(roundRect(Color.rgb(0xF6, 0xF6, 0xF8), 12, this));
                box.setPadding(dp(this, 12), dp(this, 10), dp(this, 12), dp(this, 10));
                box.addView(tv(this, "我的标记", 13, Color.rgb(0x1C, 0x1C, 0x1E), true));
                StringBuilder sb = new StringBuilder("这张卡在我的卡片里有 " + mineOfCard.size() + " 张：");
                for (int i = 0; i < mineOfCard.size(); i++) {
                    if (i > 0) sb.append("、");
                    String cl = mineOfCard.get(i).acctClass;
                    sb.append(cl == null || cl.isEmpty() ? "未标" : cl);
                }
                sb.append("。去「我的卡片」点开对应那张改标记。");
                TextView tx = tv(this, sb.toString(), 12, Color.rgb(0x8E, 0x8E, 0x93), false);
                tx.setLineSpacing(0, 1.45f);
                LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                tlp.topMargin = dp(this, 4);
                box.addView(tx, tlp);
                return box;
            } else return null;
        }
        final MineEntry tgt = target;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(roundRect(Color.rgb(0xF6, 0xF6, 0xF8), 12, this));
        box.setPadding(dp(this, 12), dp(this, 10), dp(this, 12), dp(this, 10));
        box.addView(tv(this, "我的标记（这张）", 13, Color.rgb(0x1C, 0x1C, 0x1E), true));
        LinearLayout chipsRow = new LinearLayout(this);
        chipsRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams crlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        crlp.topMargin = dp(this, 8);
        box.addView(chipsRow, crlp);
        final String[] vals = {"", "一类", "二类"};
        final String[] labs = {"不标", "一类", "二类"};
        final java.util.List<TextView> chipViews = new ArrayList<>();
        final Runnable[] paint = new Runnable[1];
        paint[0] = () -> { for (int i = 0; i < chipViews.size(); i++) paintChoiceChip(chipViews.get(i), vals[i].equals(tgt.acctClass == null ? "" : tgt.acctClass)); };
        for (int i = 0; i < vals.length; i++) {
            final String v = vals[i];
            TextView b = tv(this, labs[i], 12.5f, Color.rgb(0x1C, 0x1C, 0x1E), false);
            b.setSingleLine(true);
            b.setGravity(Gravity.CENTER);
            b.setPadding(dp(this, 13), dp(this, 7), dp(this, 13), dp(this, 7));
            b.setOnTouchListener((vv, e) -> {
                if (e.getAction() == MotionEvent.ACTION_DOWN) pressBounce(vv, true);
                else if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL) pressBounce(vv, false);
                return false;
            });
            b.setOnClickListener(vv -> {
                haptic();
                setMineEntryClass(tgt, v);
                paint[0].run();
                showFloatToast(v.isEmpty() ? "已取消标记" : "已标为" + v);
            });
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) blp.leftMargin = dp(this, 6);
            b.setLayoutParams(blp);
            chipViews.add(b);
            chipsRow.addView(b);
        }
        paint[0].run();
        TextView hint = tv(this, ACCT_CLASS_HINT, 11.5f, Color.rgb(0x8E, 0x8E, 0x93), false);
        hint.setLineSpacing(0, 1.45f);
        LinearLayout.LayoutParams hlp2 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp2.topMargin = dp(this, 8);
        box.addView(hint, hlp2);
        LinearLayout acts = new LinearLayout(this);
        acts.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.topMargin = dp(this, 10);
        box.addView(acts, alp);
        TextView addMore = tv(this, "再加一张", 12.5f, Color.rgb(0x0A, 0x5C, 0xD6), true);
        addMore.setGravity(Gravity.CENTER);
        addMore.setPadding(dp(this, 12), dp(this, 7), dp(this, 12), dp(this, 7));
        addMore.setBackground(rippleBg(Color.rgb(0xE8, 0xF1, 0xFD), 999));
        acts.addView(addMore, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addMore.setOnClickListener(vv -> { haptic(); openAcctClassPicker(c, "再加一张", false, null); });
        TextView rmOne = tv(this, "移除这张", 12.5f, Color.rgb(0xE0, 0x31, 0x31), true);
        rmOne.setGravity(Gravity.CENTER);
        rmOne.setPadding(dp(this, 12), dp(this, 7), dp(this, 12), dp(this, 7));
        LinearLayout.LayoutParams rlp2 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp2.leftMargin = dp(this, 8);
        acts.addView(rmOne, rlp2);
        rmOne.setOnClickListener(vv -> {
            haptic();
            final MineEntry rm = tgt;
            closeDetail();
            // 等关窗落定再移除，避免详情重建与移除竞态；撤销可恢复本张
            mainHandler.postDelayed(() -> removeSingleMineEntry(rm, null), 260);
        });
        return box;
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
            slide.setPadding(dp(this, 26), dp(this, 16), dp(this, 26), dp(this, 4)); // Q33：大图略内缩留呼吸边（对照 .p-slide padding 16/22 再放宽 4dp），不死贴窗边
            track.addView(slide, new LinearLayout.LayoutParams(screenW, ViewGroup.LayoutParams.WRAP_CONTENT));
            Bitmap b = Img.get(this, imgPath);
            int availW = screenW - dp(this, 52);
            int imgW = availW, imgH = dp(this, 168);
            if (b != null && b.getWidth() > 0 && b.getHeight() > 0) {
                float ratio = (float) b.getHeight() / (float) b.getWidth();
                imgH = Math.round(availW * ratio);
                int maxH = dp(this, 260);
                if (imgH > maxH) { imgH = maxH; imgW = Math.round(imgH / ratio); }
            }
            // Q48：控件级圆角裁切为主——用户定性「控件是正方形、图要裁成圆弧边框」；旧实现仅靠 roundBitmap
            // 副本切角，副本分配失败或个别 OEM 的 outline 退化时方图四角（源图深色角）直接露黑。改：外框
            // FrameLayout 与 ImageView 双双装同半径 outline 裁切（四角透出窗体/画廊底，不垫任何黑底），
            // elevation 阴影移到外框按圆角轮廓走，位图级切角保留为第二层；源图文件一张不动。
            float cardR = cardRadiusDp(imgW / getResources().getDisplayMetrics().density);
            FrameLayout imgFrame = new FrameLayout(this);
            imgFrame.setBackgroundColor(Color.TRANSPARENT);
            if (Build.VERSION.SDK_INT >= 21) imgFrame.setElevation(dp(this, 6));
            roundClip(imgFrame, cardR, this);
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setBackgroundColor(Color.TRANSPARENT);
            roundClip(iv, cardR, this);
            imgFrame.addView(iv, new FrameLayout.LayoutParams(imgW, imgH));
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(imgW, imgH);
            ilp.gravity = Gravity.CENTER_HORIZONTAL;
            slide.addView(imgFrame, ilp);
            if (b == null) { iv.setBackground(placeholderGradFor(c.id, cardR, this)); addOrgBadge(imgFrame, c.org, 1.5f);
                if (placeholderCustomEnabled) imgFrame.setOnLongClickListener(v -> { haptic(); openPlaceholderColorPicker(c); return true; });
            }
            else {
                // 第二层：位图级同半径切角（半径按位图/显示宽比换算）；副本失败回落源图时控件裁切仍保四角
                float rScale = imgW > 0 ? (float) b.getWidth() / (float) imgW : 1f;
                iv.setImageBitmap(roundBitmap(b, dp(this, cardR) * rScale));
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

        // Q39: .p-title 1.2rem/800
        TextView name = tvW(this, c.name, 19, Color.rgb(0x1C, 0x1C, 0x1E), 800);
        name.setLineSpacing(0, 1.15f);
        bodyInner.addView(name);
        // 状态直接取记录自身（与规格同源 specs 外的 status 字段），不二次加工
        String metaTxt = c.bank + " \u00B7 " + orgLabel(c.org) + " \u00B7 " + c.status
            + " \u00B7 " + (c.hasScore ? String.format(java.util.Locale.US, "%.1f\u5206", c.score) : "\u5F85\u8BC4\u5206");
        // Q39: .p-sub .88rem 次级行
        TextView meta = tv(this, metaTxt, 13.5f, Color.rgb(0x8E, 0x8E, 0x93), false); bodyLH(meta);
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

        // Q65：用户自有条目的类别标记（仅在我的卡片条目/已收藏单条时出现，未标不占位）
        View mineTagSec = buildDetailMineTagSection(c);
        if (mineTagSec != null) {
            LinearLayout.LayoutParams mtlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            mtlp.topMargin = dp(this, 10);
            bodyInner.addView(mineTagSec, mtlp);
        }

        // Q71：无真实卡面图时可自选占位底色（仅本机保存，真图卡不出现）
        if (Img.get(this, c.image) == null) {
            TextView colorBtn = tv(this, placeholderCustomEnabled ? "换卡面颜色 ›" : "换卡面颜色（先在设置开启自选） ›", 13.5f, Color.rgb(0x0A, 0x5C, 0xD6), true);
            colorBtn.setBackground(rippleBg(Color.rgb(0xEE, 0xF4, 0xFB), 10));
            colorBtn.setPadding(dp(this, 12), dp(this, 9), dp(this, 12), dp(this, 9));
            colorBtn.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams cbLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cbLp.topMargin = dp(this, 10);
            bodyInner.addView(colorBtn, cbLp);
            colorBtn.setOnClickListener(v -> {
                haptic();
                if (!placeholderCustomEnabled) { showFloatToast("先在设置打开自选卡面配色"); return; }
                openPlaceholderColorPicker(c);
            });
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
        // Q60：整页连贯滚动——标题/统计卡/说明条与卡片同在一根 ScrollView 里随滚走，
        // 不再把上半块钉死在列表上方（对照首页沉浸滚动口径：标题也在流内）。
        FrameLayout page = new FrameLayout(this);
        ScrollView sv = new ScrollView(this);
        thinScrollbar(sv);
        sv.setClipToPadding(false);
        studentScroll = sv;
        if (Build.VERSION.SDK_INT >= 23) sv.setOnScrollChangeListener((v, sx, sy, ox, oy) -> { pageScrollSaveY.put("student", sy); updateTopFabVisibility(sy); });
        page.addView(sv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // Q49：学生页同为长列表（混合版 syncSbar onList 含 student），挂可拖拽滚动条
        attachDragBar(page, sv, false, 8, 100);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(this, 14), pageTopPad(), dp(this, 14), dockPad());
        sv.addView(col, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView stuTitle = tvW(this, "学生推荐", 24, Color.rgb(0x1C, 0x1C, 0x1E), 800);
        stuTitle.setLetterSpacing(0.02f); stuTitle.setLineSpacing(0, 1.15f);
        col.addView(stuTitle);
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
        col.addView(hero, hlp);
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
        col.addView(quote, qlp);

        TextView sect = tv(this, "为什么推荐这些卡", 15, Color.rgb(0x1C, 0x1C, 0x1E), true);
        LinearLayout.LayoutParams sectLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sectLp.topMargin = dp(this, 14);
        col.addView(sect, sectLp);

        for (final Card c : stu) {
            LinearLayout cardBox = new LinearLayout(this);
            cardBox.setOrientation(LinearLayout.VERTICAL);
            cardBox.setBackground(rippleBg(Color.WHITE, 14));
            cardBox.setClipToOutline(true);
            cardBox.setPadding(dp(this, 12), dp(this, 10), dp(this, 12), dp(this, 12));
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.topMargin = dp(this, 10);
            col.addView(cardBox, clp);
            cardBox.setOnClickListener(v -> openDetail(c));
            attachCardMenuLongPress(cardBox, c, false);

            LinearLayout top = new LinearLayout(this);
            top.setOrientation(LinearLayout.HORIZONTAL);
            top.setGravity(Gravity.CENTER_VERTICAL);
            cardBox.addView(top);
            FrameLayout stuThumb = new FrameLayout(this);
            top.addView(stuThumb, new LinearLayout.LayoutParams(dp(this, 72), dp(this, 44)));
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setBackground(placeholderGradFor(c.id, 9, this));
            roundClip(iv, 9, this); // Q27 同机制：缩略图自身圆角裁切，不靠父卡轮廓
            stuThumb.addView(iv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            roundClip(stuThumb, 9, this);
            Bitmap b = Img.get(this, c.image);
            if (b != null) iv.setImageBitmap(b); else addOrgBadge(stuThumb, c.org, 0.7f);
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
        col.addView(note, nlp);
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
        row1.addView(mineHeroTile(String.valueOf(owned.size()), " 张卡", "我的卡包 · " + verdict, null, false), mineTileLp(1.25f, true));
        row1.addView(mineHeroTile(TIER_NAMES[topTier], null, "最高档次", null, true), mineTileLp(1f, false));
        row2.addView(mineHeroTile(hasOrgs.size() + " / " + ORG_LIST.length, null, "组织覆盖",
            hasOrgs.isEmpty() ? null : joinCn(hasOrgs), false), mineTileLp(1.25f, true));
        row2.addView(mineHeroTile(nNoFtf + " 张", null, "无转换费",
            missOrgs.isEmpty() ? "组织全覆盖了" : "还差 " + joinCn(missOrgs), false), mineTileLp(1f, false));

        // 境外能力白卡（Q22 对照 .dash-sect/.dash-card/.drow：分节 1.02rem/700、白卡圆角 16 内边距 4/16/12、行内图标+不截断标签+渐变条）
        TextView sect = tvW(this, "境外能力", 16, Color.rgb(0x1C, 0x1C, 0x1E), 700);
        LinearLayout.LayoutParams sectLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sectLp.topMargin = dp(this, 22);
        sectLp.leftMargin = dp(this, 2);
        wrap.addView(sect, sectLp);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(roundRect(Color.WHITE, 16, this));
        card.setPadding(dp(this, 16), dp(this, 4), dp(this, 16), dp(this, 12));
        LinearLayout.LayoutParams clp2 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp2.topMargin = dp(this, 10);
        wrap.addView(card, clp2);
        card.addView(mineProgRow("percent", "无货币转换费", nNoFtf, owned.size(), true));
        card.addView(mineProgRow("shield", "3DS 验证", n3ds, owned.size(), false));
        card.addView(mineProgRow("swap", "自动购汇", nFx, owned.size(), false));
        card.addView(mineProgRow("atm", "境外 ATM 免发卡行费", nAtm, owned.size(), false));
        if (best != null) {
            final Card bestF = best;
            card.addView(mineDivider());
            LinearLayout brow = new LinearLayout(this);
            brow.setOrientation(LinearLayout.HORIZONTAL);
            brow.setGravity(Gravity.CENTER_VERTICAL);
            brow.setPadding(0, dp(this, 11), 0, dp(this, 11));
            card.addView(brow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            DrowIconView bic = new DrowIconView(this); bic.kind = "gem";
            brow.addView(bic, new LinearLayout.LayoutParams(dp(this, 22), dp(this, 22)));
            TextView bk = tv(this, "最通用", 13.5f, Color.rgb(0x1C, 0x1C, 0x1E), false);
            bk.setSingleLine(true);
            LinearLayout.LayoutParams bklp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            bklp.leftMargin = dp(this, 10);
            brow.addView(bk, bklp);
            TextView bn = tvW(this, best.name, 13.5f, Color.rgb(0x3A, 0x3A, 0x3C), 600);
            bn.setSingleLine(true);
            bn.setEllipsize(android.text.TextUtils.TruncateAt.END);
            bn.setGravity(Gravity.RIGHT);
            LinearLayout.LayoutParams bnlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            bnlp.leftMargin = dp(this, 10);
            brow.addView(bn, bnlp);
            TextView bch = tv(this, "›", 16, Color.rgb(0xC7, 0xC7, 0xCC), true);
            LinearLayout.LayoutParams bchlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            bchlp.leftMargin = dp(this, 4);
            brow.addView(bch, bchlp);
            brow.setOnClickListener(v -> openDetail(bestF));
        }
        if (!gaps.isEmpty()) {
            TextView g = tv(this, "短板：" + gaps.get(0), 13, Color.rgb(0xB2, 0x6A, 0x00), false);
            g.setBackground(roundRect(Color.rgb(0xFF, 0xF8, 0xEC), 10, this));
            g.setPadding(dp(this, 12), dp(this, 10), dp(this, 12), dp(this, 10));
            g.setLineSpacing(0, 1.5f);
            LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            glp.topMargin = dp(this, 10);
            card.addView(g, glp);
        }
        TextView note = tv(this, "按卡库资料粗算，仅供参考", 11, Color.rgb(0xAE, 0xAE, 0xB2), false);
        LinearLayout.LayoutParams nlp2 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp2.topMargin = dp(this, 10);
        card.addView(note, nlp2);
        return wrap;
    }

    static String joinCn(List<String> xs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < xs.size(); i++) { if (i > 0) sb.append("、"); sb.append(xs.get(i)); }
        return sb.toString();
    }

    LinearLayout.LayoutParams mineTileLp(float w, boolean left) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w);
        if (left) lp.rightMargin = dp(this, 5); else lp.leftMargin = dp(this, 5);
        return lp;
    }

    // Q22 对照混合版 .bn-tile/.bn-v/.bn-k/.bn-s：玻璃底 rgba .08+描边 .1、值 1.55rem/800（首格带 .78rem 小字）、最高档次金字 #ffd60a 1.12rem
    View mineHeroTile(String value, String small, String label, String sub, boolean gold) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable tg = new GradientDrawable();
        tg.setColor(Color.argb(20, 255, 255, 255));
        tg.setCornerRadius(dp(this, 14));
        tg.setStroke(Math.max(1, dp(this, 1)), Color.argb(26, 255, 255, 255));
        t.setBackground(tg);
        t.setPadding(dp(this, 13), dp(this, 13), dp(this, 13), dp(this, 13));
        t.setMinimumHeight(dp(this, 64));
        TextView bv;
        if (gold) {
            bv = tvW(this, value, 18, Color.rgb(0xFF, 0xD6, 0x0A), 800);
            bv.setPadding(0, dp(this, 5), 0, 0);
        } else if (small != null) {
            bv = tvW(this, "", 25, Color.WHITE, 800);
            android.text.SpannableString ss = new android.text.SpannableString(value + small);
            ss.setSpan(new android.text.style.RelativeSizeSpan(0.52f), value.length(), ss.length(), 0);
            ss.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, ss.length(), 0);
            bv.setText(ss);
        } else {
            bv = tvW(this, value, 25, Color.WHITE, 800);
        }
        bv.setLineSpacing(0, 1.1f);
        t.addView(bv);
        TextView l = tv(this, label, 11, Color.argb(184, 255, 255, 255), false);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.topMargin = dp(this, 4);
        t.addView(l, llp);
        if (sub != null && !sub.isEmpty()) {
            TextView s = tv(this, sub, 11, Color.argb(166, 255, 255, 255), false);
            s.setLineSpacing(0, 1.5f);
            LinearLayout.LayoutParams slp3 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            slp3.topMargin = dp(this, 4);
            t.addView(s, slp3);
        }
        return t;
    }

    // Q22 .drow 分隔线：左缩 32dp（图标 22+间隙 10）、1px #f1f1f4
    View mineDivider() {
        View d = new View(this);
        d.setBackgroundColor(Color.rgb(0xF1, 0xF1, 0xF4));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(this, 1)));
        lp.leftMargin = dp(this, 32);
        d.setLayoutParams(lp);
        return d;
    }

    // Q22 对照 .drow：22dp 细线图标 + .9rem 标签（nowrap 绝不截断）+ 6dp 渐变进度条（flex 可缩）+ n/total
    View mineProgRow(String icon, String label, int n, int total, boolean first) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        if (!first) col.addView(mineDivider());
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(this, 11), 0, dp(this, 11));
        col.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        DrowIconView ic = new DrowIconView(this); ic.kind = icon;
        row.addView(ic, new LinearLayout.LayoutParams(dp(this, 22), dp(this, 22)));
        TextView k = tv(this, label, 13.5f, Color.rgb(0x1C, 0x1C, 0x1E), false);
        k.setSingleLine(true);
        LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        klp.leftMargin = dp(this, 10);
        row.addView(k, klp);
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setMinimumWidth(dp(this, 24));
        bar.setBackground(roundRect(Color.rgb(0xEE, 0xF0, 0xF3), 999, this));
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(0, dp(this, 6), 1f);
        barLp.leftMargin = dp(this, 10);
        row.addView(bar, barLp);
        View fill = new View(this);
        GradientDrawable fg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
            new int[]{Color.rgb(0x0A, 0x84, 0xFF), Color.rgb(0x00, 0xA3, 0xC8)});
        fg.setCornerRadius(dp(this, 999));
        fill.setBackground(fg);
        bar.addView(fill, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, (float) Math.max(0, n)));
        View rest = new View(this);
        bar.addView(rest, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, (float) Math.max(0, total - n) + 0.0001f));
        TextView v = tv(this, n + " / " + total, 12.5f, Color.rgb(0x8E, 0x8E, 0x93), false);
        v.setSingleLine(true);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vlp.leftMargin = dp(this, 10);
        row.addView(v, vlp);
        return col;
    }

    // Q22 境外能力细线图标（24 网格、1.7dp 蓝线 #0a5cff，对照 .drow-ic；percent/shield/swap/atm/gem，禁用 emoji）
    class DrowIconView extends View {
        String kind = "percent";
        DrowIconView(Context c) { super(c); }
        @Override protected void onDraw(Canvas cv) {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            p.setColor(Color.rgb(0x0A, 0x5C, 0xFF));
            float sx = getWidth() / 24f, sy = getHeight() / 24f;
            p.setStrokeWidth(1.7f * sx);
            if ("shield".equals(kind)) {
                android.graphics.Path ph = new android.graphics.Path();
                ph.moveTo(12f * sx, 3f * sy);
                ph.lineTo(19f * sx, 5.5f * sy);
                ph.lineTo(19f * sx, 11f * sy);
                ph.cubicTo(19f * sx, 16f * sy, 16f * sx, 19.5f * sy, 12f * sx, 21f * sy);
                ph.cubicTo(8f * sx, 19.5f * sy, 5f * sx, 16f * sy, 5f * sx, 11f * sy);
                ph.lineTo(5f * sx, 5.5f * sy);
                ph.close();
                cv.drawPath(ph, p);
                cv.drawLine(9f * sx, 11.5f * sy, 11.2f * sx, 13.8f * sy, p);
                cv.drawLine(11.2f * sx, 13.8f * sy, 15.2f * sx, 9.2f * sy, p);
            } else if ("swap".equals(kind)) {
                cv.drawLine(4f * sx, 8f * sy, 19f * sx, 8f * sy, p);
                cv.drawLine(15.5f * sx, 4.5f * sy, 19f * sx, 8f * sy, p);
                cv.drawLine(19f * sx, 8f * sy, 15.5f * sx, 11.5f * sy, p);
                cv.drawLine(20f * sx, 16f * sy, 5f * sx, 16f * sy, p);
                cv.drawLine(8.5f * sx, 12.5f * sy, 5f * sx, 16f * sy, p);
                cv.drawLine(5f * sx, 16f * sy, 8.5f * sx, 19.5f * sy, p);
            } else if ("atm".equals(kind)) {
                RectF r = new RectF(3.5f * sx, 5f * sy, 20.5f * sx, 19f * sy);
                cv.drawRoundRect(r, 2.5f * sx, 2.5f * sy, p);
                cv.drawLine(3.5f * sx, 9.5f * sy, 20.5f * sx, 9.5f * sy, p);
                cv.drawLine(6.5f * sx, 15f * sy, 11f * sx, 15f * sy, p);
            } else if ("gem".equals(kind)) {
                android.graphics.Path ph = new android.graphics.Path();
                ph.moveTo(7f * sx, 4f * sy);
                ph.lineTo(17f * sx, 4f * sy);
                ph.lineTo(21f * sx, 9f * sy);
                ph.lineTo(12f * sx, 20f * sy);
                ph.lineTo(3f * sx, 9f * sy);
                ph.close();
                cv.drawPath(ph, p);
                cv.drawLine(3f * sx, 9f * sy, 21f * sx, 9f * sy, p);
                cv.drawLine(8.5f * sx, 9f * sy, 12f * sx, 20f * sy, p);
                cv.drawLine(15.5f * sx, 9f * sy, 12f * sx, 20f * sy, p);
            } else { // percent
                cv.drawCircle(7f * sx, 7f * sy, 2.6f * sx, p);
                cv.drawCircle(17f * sx, 17f * sy, 2.6f * sx, p);
                cv.drawLine(18f * sx, 6f * sy, 6f * sx, 18f * sy, p);
            }
        }
    }

    View buildMinePage() {
        LinearLayout page = basePage("我的卡片");
        // Q39: .mine-pagetitle 1.7rem/800/-.01em，与通用 .page-title 区分
        if (page.getChildCount() > 0 && page.getChildAt(0) instanceof TextView) {
            TextView mt = (TextView) page.getChildAt(0);
            mt.setTextSize(27 * uiScale); mt.setLetterSpacing(-0.01f);
        }
        // Q65：我的卡片按条目渲染（同卡可一类/二类两条并存）；分析仍按去重后的产品算覆盖，不重复计同一张产品
        java.util.List<MineRow> mineRows = new ArrayList<>();
        java.util.List<Card> mineCards = new ArrayList<>();
        java.util.Set<String> seenOwned = new java.util.HashSet<>();
        for (MineEntry e : mineEntries) {
            Card mc = Store.byId.get(e.cardId);
            if (mc == null) continue;
            mineRows.add(new MineRow(mc, e));
            if (seenOwned.add(mc.id)) mineCards.add(mc);
        }

        // 整页可滚：自定义卡展开后不会把卡库收藏网格挤没（色带多时纵向滚动看）
        ScrollView sv = new ScrollView(this);
        noScrollbar(sv); // Q49：我的卡片内容不多，滚动指示从简到近乎无，不挂长条
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
        // Q78 展柜入口（设置「功能启用」关掉时整行不出现、不占位）
        if (prefs == null || prefs.getBoolean("showcase_enabled", true)) {
            final int ownedN = mineRows.size() + customCards.size();
            View scEntry = settingRow("展柜", ownedN > 0
                ? ("只看卡面 · " + ownedN + " 张自有卡，堆叠或平放展示 ›")
                : "只看卡面展示，先添加几张自己的卡 ›");
            scEntry.setOnClickListener(v -> { haptic(); openShowcase(); });
            inner.addView(scEntry);
        }
        // Q84 保号管家入口（设置关掉不占位）：显示待保号数与最近到期
        if (simkeepEnabled()) {
            java.util.List<SimKeepItem> sk = simkeepSorted();
            String sub;
            if (sk.isEmpty()) sub = "电话卡 / eSIM 保号到期管理 ›";
            else {
                int left = simkeepDaysLeft(sk.get(0).nextDue);
                String dl = left < 0 ? ("已逾期 " + (-left) + " 天") : (left == 0 ? "今天到期" : (left + " 天后到期"));
                sub = sk.size() + " 张待保号 · 最近 " + dl + " ›";
            }
            View skEntry = settingRow("保号管家", sub);
            skEntry.setOnClickListener(v -> { haptic(); openSimKeep(); });
            inner.addView(skEntry);
        }
        // Q22 页级构成对照混合版：自定义区在前（index.html #customSec 先于 #grid），其后卡包分析，再「我的卡片」折叠条+瓷砖
        inner.addView(buildCustomSection());
        if (!mineRows.isEmpty()) inner.addView(buildMineAnalysis(mineCards));

        if (mineRows.isEmpty()) {
            // 混合版 #empty 口径：居中灰字、上下 36dp 留白，不包白卡
            TextView em = tv(this, "没有符合条件的卡，换个筛选试试。", 13.5f, Color.rgb(0x8E, 0x8E, 0x93), false);
            em.setGravity(Gravity.CENTER);
            em.setPadding(dp(this, 20), dp(this, 36), dp(this, 20), dp(this, 36));
            inner.addView(em, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return page;
        }

        // Q22 折叠条对照 #mineBar/.ios-sect.dash-sectbtn：「我的卡片」+「N 张」+›（展开旋转 90°），状态存 prefs；废常驻拖动教学行（Q7 口径）
        mineOpen = prefs.getBoolean("mine_open", true);
        LinearLayout barRow = new LinearLayout(this);
        barRow.setOrientation(LinearLayout.HORIZONTAL);
        barRow.setGravity(Gravity.CENTER_VERTICAL);
        barRow.setPadding(dp(this, 4), dp(this, 4), dp(this, 4), dp(this, 4));
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        barLp.topMargin = dp(this, 22);
        barLp.bottomMargin = dp(this, 10);
        inner.addView(barRow, barLp);
        barRow.addView(tvW(this, "我的卡片", 14.5f, Color.rgb(0x3A, 0x3A, 0x3C), 600));
        TextView cnt = tv(this, mineRows.size() + " 张", 13, Color.rgb(0x8E, 0x8E, 0x93), false);
        LinearLayout.LayoutParams cntLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cntLp.leftMargin = dp(this, 8);
        barRow.addView(cnt, cntLp);
        View sp = new View(this);
        barRow.addView(sp, new LinearLayout.LayoutParams(0, 1, 1f));
        TextView chev = tv(this, "›", 19, Color.rgb(0xC7, 0xC7, 0xCC), true);
        chev.setGravity(Gravity.CENTER);
        chev.setRotation(mineOpen ? 90 : 0);
        barRow.addView(chev, new LinearLayout.LayoutParams(dp(this, 24), dp(this, 24)));
        barRow.setOnClickListener(v -> {
            haptic();
            mineOpen = !mineOpen;
            try { prefs.edit().putBoolean("mine_open", mineOpen).commit(); } catch (Throwable ignored) {}
            refreshMineKeepScroll();
        });
        if (mineOpen) addMineCardRows(inner, mineRows, sv);
        return page;
    }

    // 我的卡片网格：列数随全局 cols（Q75）；长按拖动排序（对照 app.js startMineDrag/endMineDrag 的落位换序与 450ms 点击锁）
    void addMineCardRows(LinearLayout container, final List<MineRow> list, final ScrollView sv) {
        // Q75：我的卡片网格同步走全局列数体系（1/2/3/4），列间距与 cardTile 测算一致；拖动换算下方同取此值
        final int mineCols = (cols >= 1 && cols <= 4) ? cols : 2;
        final int mineGap = mineCols >= 4 ? dp(this, 8) : dp(this, 10);
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
                    final MineRow mr = list.get(i + j);
                    final Card c = mr.card;
                    final int idx = i + j;
                    final View tile = cardTile(c, row, mineCols, mr.entry.acctClass);
                    tile.setOnTouchListener(null); // Q1：我的卡片页长按拖动优先，清掉 cardTile 默认贴卡菜单触摸
                    LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
                    if (j > 0) tlp.leftMargin = mineGap;
                    tile.setLayoutParams(tlp);
                    tile.setOnClickListener(v -> {
                        if (System.currentTimeMillis() - lastDragEndAt < 450) return; // 拖后点击锁，同混合版
                        openDetailEntry(c, mr.entry);
                    });
                    tile.setOnLongClickListener(v -> { startMineTileDrag(tile, list, idx, sv); return true; });
                    row.addView(tile);
                } else {
                    View spacer = new View(this);
                    LinearLayout.LayoutParams slp2 = new LinearLayout.LayoutParams(0, 1, 1f);
                    if (j > 0) slp2.leftMargin = mineGap;
                    spacer.setLayoutParams(slp2);
                    row.addView(spacer);
                }
            }
        }
    }

    void startMineTileDrag(final View tile, final List<MineRow> list, final int fromIdx, final ScrollView sv) {
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

    void finishMineTileDrag(View tile, List<MineRow> list, int fromIdx, float dx, float dy, ScrollView sv) {
        tile.setOnTouchListener(null);
        tile.setTranslationX(0); tile.setTranslationY(0);
        tile.setScaleX(1f); tile.setScaleY(1f); tile.setAlpha(1f);
        tile.setElevation(0);
        if (sv != null) sv.requestDisallowInterceptTouchEvent(false);
        lastDragEndAt = System.currentTimeMillis();
        // Q75：拖动落位换算与网格同列数/同列间距，四列下按 4 列网格换位
        final int mineCols = (cols >= 1 && cols <= 4) ? cols : 2;
        int tw = tile.getWidth(), th = tile.getHeight();
        int rowH = th + dp(this, 10), colW = tw + (mineCols >= 4 ? dp(this, 8) : dp(this, 10));
        int dRow = rowH > 0 ? Math.round(dy / (float) rowH) : 0;
        int dCol = colW > 0 ? Math.round(dx / (float) colW) : 0;
        int rows = (list.size() + mineCols - 1) / mineCols;
        int toRow = Math.max(0, Math.min(fromIdx / mineCols + dRow, rows - 1));
        int toCol = Math.max(0, Math.min(fromIdx % mineCols + dCol, mineCols - 1));
        int toIdx = Math.max(0, Math.min(toRow * mineCols + toCol, list.size() - 1));
        if (toIdx != fromIdx) {
            MineRow moved = list.remove(fromIdx);
            list.add(toIdx, moved);
            // 条目顺序即 mineEntries 中这些条目的相对顺序：抽出后按新序插回原段首位
            java.util.List<MineEntry> ordered = new ArrayList<>();
            for (MineRow r : list) ordered.add(r.entry);
            int insertAt = ordered.isEmpty() ? 0 : Math.max(0, mineEntries.indexOf(ordered.get(0)));
            mineEntries.removeAll(ordered);
            mineEntries.addAll(Math.min(insertAt, mineEntries.size()), ordered);
            saveMineEntries();
            showFloatToast("顺序已保存");
        }
        showTab("mine");
    }

    // ---------- 自定义卡片（Phase 3b） ----------
    // Q7 对照返工：施工前核对混合版 app.js renderCustom / restyleTiles / startTileDrag
    // 与 styles.css .csk-head/.csk-deck/.csk-layer/.csk-body/.csk-tiles/.csk-tile/.csk-idx/.csk-del/.csk-mv：
    // 头部为一行式白卡（62×56 迷你叠卡 + 自定义卡片 + N 张 · 点开展开/点开收起 + ▸ 箭头），
    // 展开为整叠色带河（每带底 48dp 向下一带顶色渐变衔接、首尾由 16dp 外框裁圆），
    // 带面只留编号/卡名/副行与三颗 30dp 半透小圆钮（↑ ↓ ✕）；无常驻教学字、无编辑大药丸行。
    View buildCustomSection() {
        LinearLayout sec = new LinearLayout(this);
        sec.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(this, 12);
        sec.setLayoutParams(slp);
        if (customCards.isEmpty()) {
            TextView empty = tv(this, "还没有自定义卡片，点右下角 ＋ 添加。", 12, Color.rgb(0x8E, 0x8E, 0x93), false);
            empty.setPadding(dp(this, 2), dp(this, 4), dp(this, 2), dp(this, 4));
            sec.addView(empty);
            return sec;
        }
        // 头部（一行式）：迷你叠卡预览 + 标题/计数 + 箭头
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setBackground(roundRect(Color.WHITE, 16, this));
        if (Build.VERSION.SDK_INT >= 21) head.setElevation(dp(this, 2));
        head.setPadding(dp(this, 14), dp(this, 12), dp(this, 14), dp(this, 12));
        sec.addView(head);
        // 迷你叠卡：取最近 3 张，最新一张在最上（app.js deck 口径，层 top i*9、左右内缩 i*5%）
        int show = Math.min(3, customCards.size());
        FrameLayout deck = new FrameLayout(this);
        head.addView(deck, new LinearLayout.LayoutParams(dp(this, 62), dp(this, 56)));
        for (int k = show - 1; k >= 0; k--) {
            int depth = k; // 0 = 最新在最上
            CustomCard c = customCards.get(customCards.size() - 1 - k);
            View layer = new View(this);
            GradientDrawable lg = customGradient(c.style);
            lg.setCornerRadius(dp(this, 9));
            layer.setBackground(lg);
            if (Build.VERSION.SDK_INT >= 21) layer.setElevation(dp(this, 1));
            FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(this, 42));
            flp.topMargin = dp(this, depth * 9);
            flp.leftMargin = dp(this, depth * 3);
            flp.rightMargin = dp(this, depth * 3);
            deck.addView(layer, flp);
        }
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        infoLp.leftMargin = dp(this, 12);
        head.addView(info, infoLp);
        info.addView(tv(this, "自定义卡片", 15, Color.rgb(0x1C, 0x1C, 0x1E), true));
        TextView sub = tv(this, customCards.size() + " 张 · " + (customOpen ? "点开收起" : "点开展开"), 11.5f, Color.rgb(0x8E, 0x8E, 0x93), false);
        info.addView(sub);
        TextView arrow = tv(this, "▸", 16, Color.rgb(0x8E, 0x8E, 0x93), false);
        arrow.setGravity(Gravity.CENTER);
        arrow.setRotation(customOpen ? 90 : 0);
        head.addView(arrow, new LinearLayout.LayoutParams(dp(this, 24), dp(this, 24)));
        head.setOnClickListener(v -> { haptic(); customOpen = !customOpen; refreshMineKeepScroll(); });
        if (!customOpen) return sec;
        // 展开教学只弹一次首次 toast（混合版 __ccDragHint 口径），不留常驻行
        try {
            if (!prefs.getBoolean("cc_drag_hint_shown", false)) {
                prefs.edit().putBoolean("cc_drag_hint_shown", true).commit();
                mainHandler.postDelayed(() -> showFloatToast("长按色带可拖动排序"), 600);
            }
        } catch (Throwable ignored) {}
        // 展开态：整叠色带河——外框 16dp 裁圆+柔影，每带 18/14/30 内边距，带底 48dp 竖向淡接至下一带顶色（Q40 照 .csk-tile::after）
        LinearLayout tilesBox = new LinearLayout(this);
        customTilesBox = tilesBox;
        tilesBox.setOrientation(LinearLayout.VERTICAL);
        tilesBox.setBackground(roundRect(Color.WHITE, 16, this));
        if (Build.VERSION.SDK_INT >= 21) tilesBox.setElevation(dp(this, 2));
        tilesBox.setClipToOutline(true);
        LinearLayout.LayoutParams boxLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        boxLp.topMargin = dp(this, 10);
        sec.addView(tilesBox, boxLp);
        for (int i = 0; i < customCards.size(); i++) {
            final CustomCard c = customCards.get(i);
            final int idx = i;
            final int nextStyle = (i + 1 < customCards.size()) ? customCards.get(i + 1).style : -1;
            // Q40：带体改 FrameLayout——本体双色渐变 + 底部 48dp 淡接层（下一条色带顶色），对照 .csk-tile::after
            FrameLayout tile = new FrameLayout(this);
            tile.setBackground(customBandGradient(c.style, i == 0, i == customCards.size() - 1));
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(this, 14), dp(this, 18), dp(this, 14), dp(this, 30));
            tile.addView(row, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            if (nextStyle >= 0) {
                View fade = new View(this);
                fade.setBackground(customNextFade(nextStyle));
                fade.setClickable(false);
                fade.setFocusable(false);
                FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(this, 48));
                flp.gravity = Gravity.BOTTOM;
                tile.addView(fade, flp);
            }
            LinearLayout.LayoutParams tileLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            // Q56 ②：相邻色带上叠 1dp，压掉交界处露出的容器白发丝线，整叠只在首尾见圆角
            if (i > 0) tileLp.topMargin = -dp(this, 1);
            tilesBox.addView(tile, tileLp);
            TextView idxTv = tv(this, String.valueOf(i + 1), 11, Color.WHITE, true);
            idxTv.setBackground(roundRect(Color.argb(71, 255, 255, 255), 999, this));
            idxTv.setGravity(Gravity.CENTER);
            row.addView(idxTv, new LinearLayout.LayoutParams(dp(this, 24), dp(this, 24)));
            LinearLayout tx = new LinearLayout(this);
            tx.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams txLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            txLp.leftMargin = dp(this, 10);
            row.addView(tx, txLp);
            TextView nm = tv(this, c.name, 15.5f, Color.WHITE, true);
            nm.setMaxLines(1);
            nm.setEllipsize(android.text.TextUtils.TruncateAt.END);
            tx.addView(nm);
            String meta = (c.bank == null ? "" : c.bank) + (c.org != null && !c.org.isEmpty() ? " · " + c.org : "");
            TextView mtv = tv(this, meta, 11.5f, Color.argb(224, 255, 255, 255), false);
            mtv.setMaxLines(1);
            mtv.setEllipsize(android.text.TextUtils.TruncateAt.END);
            tx.addView(mtv);
            // Q82：非银行卡种 chip（电话卡/其他卡）与类别 chip 同语言；Q65 类别仅银行卡
            if (!isBankKind(c.kind)) {
                TextView kChip = chip(cardKindLabel(c.kind), Color.rgb(0xF0, 0xF7, 0xFF), Color.rgb(0x2F, 0x6F, 0xD0), 9.5f);
                LinearLayout.LayoutParams kclp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                kclp.topMargin = dp(this, 5);
                kChip.setLayoutParams(kclp);
                tx.addView(kChip);
            }
            // Q65：自建卡类别 chip 与瓷砖同语言（浅底小字），未标不占位
            if (isBankKind(c.kind) && c.acctClass != null && !c.acctClass.isEmpty()) {
                TextView clsChip = chip(c.acctClass, Color.rgb(0xF0, 0xF7, 0xFF), Color.rgb(0x2F, 0x6F, 0xD0), 9.5f);
                LinearLayout.LayoutParams cclp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                cclp.topMargin = dp(this, 5);
                clsChip.setLayoutParams(cclp);
                tx.addView(clsChip);
            }
            // 三颗 30dp 半透玻璃小圆钮：上移/下移/删除（细字形，禁用态半透明；删除走二次确认+撤销）
            final FrameLayout tileF = tile;
            row.addView(customMoveBtn("↑", i == 0, v -> moveCustom(customTileIndex(tileF), -1)));
            row.addView(customMoveBtn("↓", i == customCards.size() - 1, v -> moveCustom(customTileIndex(tileF), 1)));
            row.addView(customMoveBtn("✕", false, v -> { int ci = customTileIndex(tileF); if (ci >= 0 && ci < customCards.size()) confirmDeleteCustom(customCards.get(ci)); }));
            tile.setOnClickListener(v -> {
                if (System.currentTimeMillis() - lastDragEndAt < 450) return;
                int ci = customTileIndex(tileF);
                if (ci >= 0 && ci < customCards.size()) openCustomDetail(customCards.get(ci));
            });
            tile.setOnLongClickListener(v -> { startCustomDrag(tile, customTileIndex(tileF)); return true; });
        }
        return sec;
    }

    // Q56：色带在其容器中的当前位置（换位/拖动落位后以此为准，监听里不吃旧下标）
    int customTileIndex(View tile) {
        try {
            if (tile != null && tile.getParent() instanceof ViewGroup)
                return ((ViewGroup) tile.getParent()).indexOfChild(tile);
        } catch (Throwable ignored) {}
        return -1;
    }

    void setCustomMoveBtnState(TextView b, boolean disabled, final View tile, final int dir) {
        if (b == null) return;
        b.setTextColor(disabled ? Color.argb(120, 255, 255, 255) : Color.WHITE);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(Color.argb(disabled ? 30 : 64, 255, 255, 255));
        g.setStroke(dp(this, 1), Color.argb(60, 255, 255, 255));
        b.setBackground(g);
        if (disabled) b.setOnClickListener(null);
        else b.setOnClickListener(v -> { haptic(); moveCustom(customTileIndex(tile), dir); });
    }

    // Q56 对照混合版 endTileDrag→restyleTiles(wrap)：只就地重算编号/渐变衔接/首尾圆角/钮态，不整页重建、不闪
    void restyleCustomTilesInPlace() {
        ViewGroup box = customTilesBox;
        if (box == null) return;
        int n = Math.min(box.getChildCount(), customCards.size());
        for (int i = 0; i < n; i++) {
            View child = box.getChildAt(i);
            if (!(child instanceof FrameLayout)) continue;
            FrameLayout tile = (FrameLayout) child;
            CustomCard c = customCards.get(i);
            boolean last = i == customCards.size() - 1;
            // Q56 ②：重排后交界上叠量按新位置归位（首带不叠、其余 -1dp），重衔接后仍无缝
            if (tile.getLayoutParams() instanceof LinearLayout.LayoutParams) {
                LinearLayout.LayoutParams tlp = (LinearLayout.LayoutParams) tile.getLayoutParams();
                int want = i == 0 ? 0 : -dp(this, 1);
                if (tlp.topMargin != want) { tlp.topMargin = want; tile.setLayoutParams(tlp); }
            }
            tile.setBackground(customBandGradient(c.style, i == 0, last));
            // 衔接层：非末带底部 48dp 淡接下一张顶色，末带移除
            if (!last) {
                int nextStyle = customCards.get(i + 1).style;
                if (tile.getChildCount() > 1) {
                    View fade = tile.getChildAt(1);
                    fade.setBackground(customNextFade(nextStyle));
                    fade.setVisibility(View.VISIBLE);
                } else {
                    View fade = new View(this);
                    fade.setBackground(customNextFade(nextStyle));
                    fade.setClickable(false);
                    fade.setFocusable(false);
                    FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(this, 48));
                    flp.gravity = Gravity.BOTTOM;
                    tile.addView(fade, flp);
                }
            } else if (tile.getChildCount() > 1) {
                tile.removeViewAt(1);
            }
            if (tile.getChildCount() == 0 || !(tile.getChildAt(0) instanceof LinearLayout)) continue;
            LinearLayout row = (LinearLayout) tile.getChildAt(0);
            if (row.getChildCount() > 0 && row.getChildAt(0) instanceof TextView)
                ((TextView) row.getChildAt(0)).setText(String.valueOf(i + 1));
            if (row.getChildCount() > 3) {
                if (row.getChildAt(2) instanceof TextView) setCustomMoveBtnState((TextView) row.getChildAt(2), i == 0, tile, -1);
                if (row.getChildAt(3) instanceof TextView) setCustomMoveBtnState((TextView) row.getChildAt(3), last, tile, 1);
            }
        }
    }

    // Q7：三颗半透玻璃小圆钮（照混合版 .csk-mv/.csk-del 30dp、rgba(255,255,255,.25)，按下 .4）
    TextView customMoveBtn(String label, boolean disabled, View.OnClickListener onClick) {
        TextView b = tv(this, label, 13, disabled ? Color.argb(120, 255, 255, 255) : Color.WHITE, true);
        b.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(Color.argb(disabled ? 30 : 64, 255, 255, 255));
        g.setStroke(dp(this, 1), Color.argb(60, 255, 255, 255));
        b.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(this, 30), dp(this, 30));
        lp.leftMargin = dp(this, 6);
        b.setLayoutParams(lp);
        if (!disabled) b.setOnClickListener(v -> { haptic(); onClick.onClick(v); });
        return b;
    }
    // Q7 ③：拖动中的蓝框（与 Q1 长按菜单同语言 rgba(0,122,255,.7)）；带体圆角由 outline 塑形，禁止黑边毛刺
    void setCustomTileDragFrame(View tile, boolean on, Drawable orig) {
        if (tile instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) tile;
            if (on) {
                Drawable cur = tile.getBackground();
                if (cur instanceof GradientDrawable) ((GradientDrawable) cur).setStroke(dp(this, 2), Color.argb(178, 0, 122, 255));
                roundClip(tile, 16, this);
                if (Build.VERSION.SDK_INT >= 21) tile.setElevation(dp(this, 18));
            } else {
                if (orig instanceof GradientDrawable) ((GradientDrawable) orig).setStroke(0, 0);
                tile.setBackground(orig);
                tile.setClipToOutline(false);
                if (Build.VERSION.SDK_INT >= 21) tile.setElevation(0);
            }
        }
    }

    // P-deck ① 长按拖动：跟手平移+放大，松手按位移换序并保存（对照混合版 startTileDrag）
    void startCustomDrag(final View tile, final int fromIdx) {
        haptic();
        final Drawable origBg = tile.getBackground();
        tile.setTag(origBg);
        setCustomTileDragFrame(tile, true, origBg);
        tile.setScaleX(1.02f); tile.setScaleY(1.02f); tile.setAlpha(0.98f);
        // Q56：不再 bringChildToFront 改子序（那一下正是起拖闪烁源）；靠 setCustomTileDragFrame 的 elevation 18 浮在兄弟之上
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
                        // Q7 ③：过中线即让位——兄弟色带就地换位，编号随之实时改写（混合版 startTileDrag 的 insertBefore 语义）
                        try {
                            ViewGroup parent = (ViewGroup) v.getParent();
                            if (parent != null && v.getHeight() > 0) {
                                float center = v.getTop() + v.getTranslationY() + v.getHeight() / 2f;
                                int cur = parent.indexOfChild(v);
                                int target = cur;
                                for (int i = 0; i < parent.getChildCount(); i++) {
                                    View sib = parent.getChildAt(i);
                                    if (sib == v) continue;
                                    float sc = sib.getTop() + sib.getHeight() / 2f;
                                    if (i < cur && center < sc) target = Math.min(target, i);
                                    if (i > cur && center > sc) target = Math.max(target, i);
                                }
                                if (target != cur) {
                                    float topBefore = v.getTop();
                                    parent.removeView(v);
                                    parent.addView(v, target);
                                    v.setTranslationY(v.getTranslationY() + (topBefore - v.getTop()));
                                    for (int i = 0; i < parent.getChildCount(); i++) {
                                        View band = parent.getChildAt(i);
                                        if (band instanceof ViewGroup && ((ViewGroup) band).getChildCount() > 0) {
                                            View idxV = ((ViewGroup) band).getChildAt(0);
                                            // Q40：色带改 FrameLayout（本体+淡接层）后编号在行容器首位，下探一层取编号
                                            if (idxV instanceof ViewGroup && ((ViewGroup) idxV).getChildCount() > 0) idxV = ((ViewGroup) idxV).getChildAt(0);
                                            if (idxV instanceof TextView) ((TextView) idxV).setText(String.valueOf(i + 1));
                                        }
                                    }
                                }
                            }
                        } catch (Throwable ignored) {}
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
        // Q56：落位不瞬跳——保留当前平移量，待物理子序定稿后 140ms 滑入槽位（见下）
        tile.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(140).setInterpolator(ANIM_EXIT).start();
        Object tag = tile.getTag();
        if (tag instanceof Drawable) setCustomTileDragFrame(tile, false, (Drawable) tag);
        tile.setTag(null);
        if (mineScrollView != null) mineScrollView.requestDisallowInterceptTouchEvent(false);
        lastDragEndAt = System.currentTimeMillis();
        // Q7：以拖动手势中就地换位后的实际子序为准（dy 仅作兜底），避免实时换位与按位移换算双重作用
        int liveIdx = -1;
        try { if (tile.getParent() instanceof ViewGroup) liveIdx = ((ViewGroup) tile.getParent()).indexOfChild(tile); } catch (Throwable ignored) {}
        int toIdx;
        if (liveIdx >= 0) toIdx = Math.max(0, Math.min(liveIdx, customCards.size() - 1));
        else {
            int th = tile.getHeight();
            int dRow = th > 0 ? Math.round(dy / (float) th) : 0;
            toIdx = Math.max(0, Math.min(fromIdx + dRow, customCards.size() - 1));
        }
        if (toIdx != fromIdx) {
            CustomCard moved = customCards.remove(fromIdx);
            customCards.add(toIdx, moved);
            saveCustomCards();
            showFloatToast("顺序已保存");
        }
        // Q56：落位只就地重衔接（对照 restyleTiles），不再 refreshMineKeepScroll 整页重建闪一下；
        // live 换位已使物理子序与新顺序一致，兜底路径（未实时换位）先把带体挪到目标位再重算
        try {
            ViewGroup box = (tile.getParent() instanceof ViewGroup) ? (ViewGroup) tile.getParent() : null;
            if (box != null && customTilesBox == box) {
                int cur = box.indexOfChild(tile);
                boolean liveSettled = cur == toIdx;
                if (cur >= 0 && cur != toIdx) { box.removeView(tile); box.addView(tile, Math.min(toIdx, box.getChildCount())); }
                restyleCustomTilesInPlace();
                if (liveSettled) tile.animate().translationY(0f).setDuration(140).setInterpolator(ANIM_EXIT).start();
                else tile.setTranslationY(0);
            } else {
                tile.setTranslationY(0);
                refreshMineKeepScroll();
            }
        } catch (Throwable ignored) { tile.setTranslationY(0); refreshMineKeepScroll(); }
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
        // Q56 ①：↑/↓ 换位与拖动同法——物理交换两带后就地重衔接，不整页重建
        try {
            ViewGroup box = customTilesBox;
            if (box != null && j < box.getChildCount()) {
                View a = box.getChildAt(idx);
                View b = box.getChildAt(j);
                int lo = Math.min(idx, j), hi = Math.max(idx, j);
                box.removeViewAt(hi);
                box.removeViewAt(lo);
                box.addView(idx == lo ? b : a, lo);
                box.addView(idx == lo ? a : b, hi);
                restyleCustomTilesInPlace();
                return;
            }
        } catch (Throwable ignored) {}
        refreshMineKeepScroll();
    }

    // Q52：废全 App 唯一系统 AlertDialog（居中白框+系统钮），改与 Q6/Q19 同基建的贴底确认小窗——
    // 遮罩 rgba(0,0,0,.4)、窗体实白仅顶圆 22dp 贴屏底、标题 17sp/800+一句说明、取消/删除双钮
    // （删除 #E03131 红字），开窗 hideChrome（Q12）、关窗 restoreChrome，删后撤销条沿现行。
    void confirmDeleteCustom(final CustomCard c) {
        if (c == null || delConfirmSheet != null || delConfirmClosing) return;
        delConfirmClosing = false;
        hideChrome(); // Q12
        final FrameLayout sheet = new FrameLayout(this);
        View shade = new View(this);
        shade.setBackgroundColor(Color.argb(102, 0, 0, 0)); // .dlg-backdrop rgba(0,0,0,.4)
        shade.setAlpha(0f);
        shade.setOnClickListener(v -> closeDelConfirm());
        sheet.addView(shade, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable cg = new GradientDrawable();
        cg.setColor(Color.rgb(0xFF, 0xFF, 0xFF));
        float rTop = dp(this, 22);
        cg.setCornerRadii(new float[]{rTop, rTop, rTop, rTop, 0, 0, 0, 0});
        card.setBackground(cg);
        if (Build.VERSION.SDK_INT >= 21) { card.setElevation(dp(this, 24)); topSheetClip(card, 22, this); }
        card.setOnClickListener(v -> {}); // 窗体吃点击防穿透遮罩
        card.setPadding(dp(this, 18), dp(this, 18), dp(this, 18), dp(this, 14) + navBarH());
        card.addView(tv(this, "删除这张自定义卡？", 17, Color.rgb(0x1C, 0x1C, 0x1E), true));
        TextView msg = tv(this, "「" + c.name + "」删了就没了，备注也会一起清掉。", 13.5f, Color.rgb(0x8E, 0x8E, 0x93), false);
        LinearLayout.LayoutParams msgLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        msgLp.topMargin = dp(this, 8);
        card.addView(msg, msgLp);
        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams btnsLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnsLp.topMargin = dp(this, 18);
        card.addView(btns, btnsLp);
        TextView cancelB = tv(this, "取消", 15, Color.rgb(0x1C, 0x1C, 0x1E), false);
        cancelB.setGravity(Gravity.CENTER);
        cancelB.setBackground(rippleBg(Color.rgb(0xF2, 0xF3, 0xF7), 14));
        cancelB.setOnClickListener(v -> { haptic(); closeDelConfirm(); });
        LinearLayout.LayoutParams cbLp = new LinearLayout.LayoutParams(0, dp(this, 48), 1f);
        btns.addView(cancelB, cbLp);
        TextView delB = tv(this, "删除", 15, Color.rgb(0xE0, 0x31, 0x31), true);
        delB.setGravity(Gravity.CENTER);
        delB.setBackground(rippleBg(Color.rgb(0xFD, 0xEE, 0xEE), 14));
        delB.setOnClickListener(v -> {
            haptic();
            final int idx = customCards.indexOf(c);
            customCards.remove(c);
            saveCustomCards();
            refreshMineKeepScroll();
            closeDelConfirm();
            showFloatToast("已删除这张自定义卡", "撤销", () -> {
                int at = idx < 0 ? customCards.size() : Math.min(idx, customCards.size());
                customCards.add(at, c);
                saveCustomCards();
                refreshMineKeepScroll();
                showFloatToast("已恢复「" + c.name + "」");
            });
        });
        LinearLayout.LayoutParams dbLp = new LinearLayout.LayoutParams(0, dp(this, 48), 1f);
        dbLp.leftMargin = dp(this, 10);
        btns.addView(delB, dbLp);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.gravity = Gravity.BOTTOM;
        clp.leftMargin = dp(this, 12); clp.rightMargin = dp(this, 12); clp.bottomMargin = 0;
        FrameLayout wrap = new FrameLayout(this);
        View glass = glassLayer(card, 22, false);
        topSheetClip(glass, 22, this); // Q54：玻璃轮廓与窗体同，不得在窗外露面
        wrap.addView(glass, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        wrap.addView(card, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        sheet.addView(wrap, clp);
        content.addView(sheet);
        delConfirmSheet = sheet;
        sheet.setAlpha(0f);
        sheet.animate().alpha(1f).setDuration(ANIM_DUR_SHADE_IN).setInterpolator(ANIM_ENTER).start();
        shade.animate().alpha(1f).setDuration(ANIM_DUR_SHADE_IN).setInterpolator(ANIM_ENTER).start();
        wrap.setTranslationY(dp(this, 42));
        wrap.animate().translationY(0f).setDuration(ANIM_DUR_SHEET_IN).setInterpolator(ANIM_ENTER).start();
    }

    void closeDelConfirm() {
        final View sheet = delConfirmSheet;
        if (sheet == null || delConfirmClosing) return;
        delConfirmClosing = true;
        if (sheet.getParent() == null) { delConfirmSheet = null; delConfirmClosing = false; restoreChrome(); return; }
        View wrap = sheet instanceof ViewGroup && ((ViewGroup) sheet).getChildCount() > 1
            ? ((ViewGroup) sheet).getChildAt(1) : null;
        if (wrap != null) {
            wrap.animate().translationY(dp(this, 42)).alpha(0f)
                .setDuration(ANIM_DUR_SHEET_OUT).setInterpolator(ANIM_EXIT)
                .withEndAction(() -> {
                    if (sheet.getParent() != null) ((ViewGroup) sheet.getParent()).removeView(sheet);
                    if (delConfirmSheet == sheet) delConfirmSheet = null;
                    delConfirmClosing = false;
                    restoreChrome(); // Q12
                }).start();
            sheet.animate().alpha(0f).setDuration(ANIM_DUR_SHADE_OUT).setInterpolator(ANIM_EXIT).start();
        } else {
            ((ViewGroup) sheet.getParent()).removeView(sheet);
            delConfirmSheet = null; delConfirmClosing = false;
            restoreChrome(); // Q12
        }
    }

    // Q19 对照混合版 app.js openCustomDetail + styles.css .panel/.cc-hero/.p-close/.p-sec/.spec/.dlg-actions：
    // 废居中 AlertDialog 白框，改与 Q6 同规范的贴底浮窗——底层页保留、遮罩 rgba(0,0,0,.4)、窗体全宽贴底
    // 顶圆 20dp/最高 88vh/白底 elevation 24、垫冻结玻璃层； hero 为 .cc-hero（渐变 135°、内边距 28/20/22、
    // 卡名 1.3rem/800 白字 + 发卡行·组织副行 .88rem/88% 白），右上 .p-close 式 34dp 半透圆 ✕ 长在窗内
    // 随窗同升同降；「卡片信息」走 .spec 细线分行（标签左灰、值右对齐 500、缺项 —）；底部动作行照
    // .dlg-actions 双 ghost 钮（左「在卡库里搜这家银行」蓝、右「删除这张卡」#e03131 红），编辑入口
    // 以 hero 内半透描边小钮保留在窗内。开窗 hideChrome（Q12），关窗 restoreCurrentTabScroll 回原位。
    void openCustomDetail(final CustomCard c) {
        if (c == null || customDetailSheet != null || customDetailClosing) return;
        closeCustomDetailNow();
        captureCurrentPageScroll();
        customDetailCard = c;
        customDetailClosing = false;
        hideChrome(); // Q12

        final FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(Color.TRANSPARENT);
        final View shade = new View(this);
        shade.setBackgroundColor(Color.argb(102, 0, 0, 0)); // .backdrop rgba(0,0,0,.4)
        shade.setAlpha(0f);
        shade.setOnClickListener(v -> closeCustomDetail());
        overlay.addView(shade, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        final FrameLayout wrap = new FrameLayout(this);
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int screenH = getResources().getDisplayMetrics().heightPixels;
        final int maxH = (int) (screenH * 0.88); // .panel max-height:88vh

        final LinearLayout sheetCard = new LinearLayout(this);
        sheetCard.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable sheetBg = new GradientDrawable();
        sheetBg.setColor(Color.WHITE);
        float rTop = dp(this, 20);
        sheetBg.setCornerRadii(new float[]{rTop, rTop, rTop, rTop, 0, 0, 0, 0});
        sheetCard.setBackground(sheetBg);
        if (Build.VERSION.SDK_INT >= 21) { sheetCard.setElevation(dp(this, 24)); topSheetClip(sheetCard, 20, this); } // Q45 顶圆底直轮廓
        sheetCard.setOnClickListener(v -> {}); // 窗体吃点击防穿透遮罩

        ScrollView sc = new ScrollView(this);
        thinScrollbar(sc);
        sc.setBackgroundColor(Color.TRANSPARENT);
        sc.setFillViewport(false);
        customDetailScroll = sc;
        LinearLayout body = buildCustomDetailBody(c);
        body.setPadding(0, 0, 0, dp(this, 20) + navBarH()); // .panel padding-bottom 20+safe
        sc.addView(body);
        sheetCard.addView(sc, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        sheetCard.measure(View.MeasureSpec.makeMeasureSpec(screenW, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST));
        int sheetH = Math.min(sheetCard.getMeasuredHeight(), maxH);
        FrameLayout.LayoutParams wlp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, sheetH);
        wlp.gravity = Gravity.BOTTOM;
        // Q45：同详情窗——冻结玻璃层高出窗体 20dp，底圆角沉到窗外由 wrap 裁掉，窗底直角贴屏底
        FrameLayout.LayoutParams customDetailGlassLp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, sheetH + dp(this, 20));
        View customDetailGlass = glassLayer(sheetCard, 20, false);
        topSheetClip(customDetailGlass, 20, this); // Q54：玻璃轮廓与窗体同（顶圆底直），不得在窗外露面发雾
        wrap.addView(customDetailGlass, customDetailGlassLp);
        wrap.addView(sheetCard, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        overlay.addView(wrap, wlp);
        customDetailWrap = wrap;

        // 右上 ✕：长在窗内随窗同升同降（同 Q30 纪律，禁根层独立字形）；.p-close 34dp 半透圆，
        // 圆底加深为半透炭灰保证在任意卡色渐变上可辨，仍半透非实心
        final FrameLayout glyphFrame = new FrameLayout(this);
        glyphFrame.setClipChildren(false); glyphFrame.setClipToPadding(false);
        glyphFrame.setAlpha(0f);
        View circle = new View(this);
        GradientDrawable cg = new GradientDrawable();
        cg.setShape(GradientDrawable.OVAL);
        cg.setColor(Color.argb(110, 30, 32, 40));
        cg.setStroke(dp(this, 1), Color.argb(110, 255, 255, 255));
        circle.setBackground(cg);
        if (Build.VERSION.SDK_INT >= 21) circle.setElevation(dp(this, 5));
        FrameLayout.LayoutParams clp2 = new FrameLayout.LayoutParams(dp(this, 34), dp(this, 34));
        clp2.gravity = Gravity.CENTER;
        glyphFrame.addView(circle, clp2);
        CloseIconView x = new CloseIconView(this);
        x.iconColor = Color.WHITE;
        x.lineDp = 1.9f;
        x.shadow = true;
        x.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        int xPad = dp(this, 4);
        x.setPadding(xPad, xPad, xPad, xPad);
        FrameLayout.LayoutParams xlp = new FrameLayout.LayoutParams(dp(this, 34), dp(this, 34));
        xlp.gravity = Gravity.CENTER;
        glyphFrame.addView(x, xlp);
        glyphFrame.setOnClickListener(v -> { haptic(); closeCustomDetail(); });
        FrameLayout.LayoutParams glp = new FrameLayout.LayoutParams(dp(this, 48), dp(this, 48));
        glp.gravity = Gravity.TOP | Gravity.END;
        glp.topMargin = dp(this, 5);
        glp.rightMargin = dp(this, 5);
        wrap.addView(glyphFrame, glp);

        attachCustomDetailDrag(wrap);
        content.addView(overlay, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        overlay.bringToFront();
        customDetailSheet = overlay;
        shade.animate().alpha(1f).setDuration(ANIM_DUR_SHADE_IN).setInterpolator(ANIM_ENTER).start();
        wrap.setTranslationY(sheetH);
        wrap.animate().translationY(0f).setDuration(ANIM_DUR_SHEET_IN).setInterpolator(ANIM_ENTER).start();
        glyphFrame.animate().alpha(1f).setDuration(ANIM_DUR_FADE).setStartDelay(70).setInterpolator(ANIM_ENTER).start();
    }

    LinearLayout buildCustomDetailBody(final CustomCard c) {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(Color.WHITE);

        // Q48：hero 改独立圆角卡——四角同半径 16dp、四边内缩留白，不再顶着窗体顶圆在两上角露白边
        // （与 Q46 收款码卡片口径一致）；编辑钮移出色块，入下方信息区（见 buildCustomDetailBody 信息头行）。
        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable hg = customGradient(c.style);
        float hr = dp(this, 16);
        hg.setCornerRadii(new float[]{hr, hr, hr, hr, hr, hr, hr, hr});
        hero.setBackground(hg);
        hero.setPadding(dp(this, 20), dp(this, 22), dp(this, 20), dp(this, 20));
        LinearLayout.LayoutParams hlp2 = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp2.leftMargin = dp(this, 12); hlp2.rightMargin = dp(this, 12); hlp2.topMargin = dp(this, 12);
        page.addView(hero, hlp2);
        TextView nm = tvW(this, c.name == null ? "" : c.name, 20, Color.WHITE, 800);
        nm.setLineSpacing(0, 1.15f);
        hero.addView(nm);
        String sub = (c.bank == null || c.bank.isEmpty()
            ? (isBankKind(c.kind) ? "未填发卡行" : ("phone".equals(normCardKind(c.kind)) ? "未填运营商" : "未填发行方")) : c.bank)
            + (isBankKind(c.kind) && c.org != null && !c.org.isEmpty() ? " · " + c.org : "");
        TextView sb = tv(this, sub, 13.5f, Color.argb(224, 255, 255, 255), false);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(this, 4);
        hero.addView(sb, slp);
        // .p-body（padding 16/18）+ .p-sec「卡片信息」+ .spec 细线分行；Q48：编辑钮移出 hero 色块，
        // 作信息区头行右上轻钮（白底浅描边蓝字），点击行为沿旧 hero 钮（链式开表单、防 dock 闪烁）
        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.setPadding(dp(this, 18), dp(this, 16), dp(this, 18), 0);
        page.addView(inner);
        LinearLayout infoHead = new LinearLayout(this);
        infoHead.setOrientation(LinearLayout.HORIZONTAL);
        infoHead.setGravity(Gravity.CENTER_VERTICAL);
        infoHead.addView(detailSectionTitle("卡片信息"), new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView editBtn = tv(this, "编辑", 12.5f, Color.rgb(0x0A, 0x5C, 0xD6), true);
        editBtn.setGravity(Gravity.CENTER);
        GradientDrawable eg = new GradientDrawable();
        eg.setColor(Color.WHITE);
        eg.setCornerRadius(dp(this, 999));
        eg.setStroke(dp(this, 1), Color.rgb(0xD8, 0xD8, 0xDE));
        editBtn.setBackground(eg);
        editBtn.setPadding(dp(this, 14), dp(this, 6), dp(this, 14), dp(this, 6));
        infoHead.addView(editBtn);
        inner.addView(infoHead);
        editBtn.setOnClickListener(v -> {
            haptic();
            suppressNextChromeRestore = true; // 链式开表单，跳过一次恢复防 dock 闪烁（Q12）
            closeCustomDetailNow();
            openCustomForm(c);
        });
        editBtn.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) pressBounce(v, true);
            else if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL)
                pressBounce(v, false);
            return false;
        });
        LinearLayout specBox = new LinearLayout(this);
        specBox.setOrientation(LinearLayout.VERTICAL);
        inner.addView(specBox);
        java.util.List<String[]> rowList = new ArrayList<>();
        rowList.add(new String[]{"卡片名称", dash(c.name)});
        rowList.add(new String[]{"卡种", cardKindLabel(c.kind)});
        rowList.add(new String[]{isBankKind(c.kind) ? "发卡银行" : ("phone".equals(normCardKind(c.kind)) ? "运营商" : "发行方"), dash(c.bank)});
        if (isBankKind(c.kind)) rowList.add(new String[]{"卡组织", dash(c.org)});
        if (isBankKind(c.kind) && c.acctClass != null && !c.acctClass.isEmpty()) rowList.add(new String[]{"账户类别", c.acctClass});
        rowList.add(new String[]{"备注", dash(c.note)});
        String[][] rows = rowList.toArray(new String[0][]);
        for (int i = 0; i < rows.length; i++) {
            specBox.addView(customDetailRow(rows[i][0], rows[i][1]));
            if (i < rows.length - 1) {
                View div = new View(this);
                div.setBackgroundColor(Color.rgb(0xF0, 0xF0, 0xF5)); // .spec border-bottom #f0f0f5
                specBox.addView(div, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(this, 1) / 2)));
            }
        }
        // Q65：详情内直接改类别（不标/一类/二类），改完重开本窗使规格行与色带 chip 同步
        LinearLayout tagBox = new LinearLayout(this);
        tagBox.setVisibility(isBankKind(c.kind) ? View.VISIBLE : View.GONE); // Q82：电话卡/其他卡无一类二类
        tagBox.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tagLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tagLp.topMargin = dp(this, 14);
        inner.addView(tagBox, tagLp);
        tagBox.addView(tv(this, "我的标记", 13, Color.rgb(0x1C, 0x1C, 0x1E), true));
        LinearLayout tagRow = new LinearLayout(this);
        tagRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams trLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        trLp.topMargin = dp(this, 8);
        tagBox.addView(tagRow, trLp);
        final String[] tVals = {"", "一类", "二类"};
        final String[] tLabs = {"不标", "一类", "二类"};
        final java.util.List<TextView> tViews = new ArrayList<>();
        final Runnable[] tPaint = new Runnable[1];
        tPaint[0] = () -> { for (int i = 0; i < tViews.size(); i++) paintChoiceChip(tViews.get(i), tVals[i].equals(c.acctClass == null ? "" : c.acctClass)); };
        for (int i = 0; i < tVals.length; i++) {
            final String v = tVals[i];
            TextView b = tv(this, tLabs[i], 12.5f, Color.rgb(0x1C, 0x1C, 0x1E), false);
            b.setSingleLine(true);
            b.setGravity(Gravity.CENTER);
            b.setPadding(dp(this, 13), dp(this, 7), dp(this, 13), dp(this, 7));
            b.setOnTouchListener((vv, e) -> {
                if (e.getAction() == MotionEvent.ACTION_DOWN) pressBounce(vv, true);
                else if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL) pressBounce(vv, false);
                return false;
            });
            b.setOnClickListener(vv -> {
                haptic();
                c.acctClass = v;
                saveCustomCards();
                tPaint[0].run();
                showFloatToast(v.isEmpty() ? "已取消标记" : "已标为" + v);
                closeCustomDetailNow();
                openCustomDetail(c);
            });
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) blp.leftMargin = dp(this, 6);
            b.setLayoutParams(blp);
            tViews.add(b);
            tagRow.addView(b);
        }
        tPaint[0].run();
        TextView thint = tv(this, ACCT_CLASS_HINT, 11.5f, Color.rgb(0x8E, 0x8E, 0x93), false);
        thint.setLineSpacing(0, 1.45f);
        LinearLayout.LayoutParams thLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        thLp.topMargin = dp(this, 8);
        tagBox.addView(thint, thLp);

        // .dlg-actions 底部动作行：左搜银行（蓝）/右删除（红），无发卡行时只留删除占满
        LinearLayout acts = new LinearLayout(this);
        acts.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.topMargin = dp(this, 16);
        inner.addView(acts, alp);
        if (c.bank != null && !c.bank.isEmpty()) {
            TextView find = customDetailAction("在卡库里搜这家银行", Color.rgb(0x00, 0x7A, 0xFF));
            find.setOnClickListener(v -> {
                haptic();
                suppressNextChromeRestore = true; // 随即切首页，跳过一次恢复防闪烁
                closeCustomDetailNow();
                query = c.bank;
                resetPageScroll("home", homeScroll); // 去看搜索结果，明确回顶（P-keepscroll 例外）
                showTab("home");
                if (searchBox != null) searchBox.setText(c.bank);
            });
            LinearLayout.LayoutParams flp2 = new LinearLayout.LayoutParams(0, dp(this, 46), 1f);
            acts.addView(find, flp2);
        }
        TextView del = customDetailAction("删除这张卡", Color.rgb(0xE0, 0x31, 0x31));
        del.setOnClickListener(v -> {
            haptic();
            closeCustomDetailNow();
            confirmDeleteCustom(c);
        });
        LinearLayout.LayoutParams dlp2 = new LinearLayout.LayoutParams(0, dp(this, 46), 1f);
        if (c.bank != null && !c.bank.isEmpty()) dlp2.leftMargin = dp(this, 10);
        acts.addView(del, dlp2);
        return page;
    }

    static String dash(String s) { return s == null || s.isEmpty() ? "—" : s; }

    // .dlg-actions .ghost-btn：flex1、圆角 14、浅底+细描边、按下 .98（以 pressBounce 近似）
    TextView customDetailAction(String label, int color) {
        TextView t = tv(this, label, 14, color, true);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.rgb(0xF7, 0xF8, 0xFA));
        g.setCornerRadius(dp(this, 14));
        g.setStroke(dp(this, 1), Color.rgb(0xE8, 0xE8, 0xEE));
        t.setBackground(g);
        t.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) pressBounce(v, true);
            else if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL)
                pressBounce(v, false);
            return false;
        });
        return t;
    }

    void attachCustomDetailDrag(final View wrap) {
        final float[] downY = {0f};
        final boolean[] dragging = {false};
        if (customDetailScroll == null) return;
        customDetailScroll.setOnTouchListener((v, e) -> {
            if (customDetailClosing) return false;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downY[0] = e.getRawY(); dragging[0] = false; return false;
                case MotionEvent.ACTION_MOVE:
                    if (customDetailScroll.getScrollY() <= 0 && e.getRawY() - downY[0] > dp(this, 12)) {
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
                        if (dy > dp(this, 80)) { closeCustomDetail(); return true; }
                        wrap.animate().translationY(0f).setDuration(ANIM_DUR_FADE)
                            .setInterpolator(ANIM_ENTER).start();
                    }
                    return false;
                default: return false;
            }
        });
    }

    void closeCustomDetail() {
        if (customDetailSheet == null || customDetailClosing) return;
        customDetailClosing = true;
        final View overlay = customDetailSheet;
        final View wrap = customDetailWrap;
        Runnable finish = () -> {
            if (overlay.getParent() instanceof ViewGroup)
                ((ViewGroup) overlay.getParent()).removeView(overlay);
            if (customDetailSheet == overlay) {
                customDetailSheet = null; customDetailWrap = null;
                customDetailScroll = null; customDetailCard = null;
            }
            customDetailClosing = false;
            restoreCurrentTabScroll();
            restoreChrome(); // Q12
        };
        if (wrap == null) { finish.run(); return; }
        View shade = overlay instanceof ViewGroup && ((ViewGroup) overlay).getChildCount() > 0
            ? ((ViewGroup) overlay).getChildAt(0) : null;
        if (shade != null) shade.animate().alpha(0f).setDuration(ANIM_DUR_SHADE_OUT).setInterpolator(ANIM_EXIT).start();
        int targetY = wrap.getHeight() > 0 ? wrap.getHeight() : dp(this, 420);
        wrap.animate().translationY(targetY).setDuration(ANIM_DUR_SHEET_OUT).setInterpolator(ANIM_EXIT)
            .withEndAction(finish).start();
    }

    void closeCustomDetailNow() {
        View sheet = customDetailSheet;
        if (sheet == null) return;
        customDetailSheet = null; customDetailWrap = null;
        customDetailScroll = null; customDetailCard = null;
        customDetailClosing = false;
        if (sheet.getParent() != null) ((ViewGroup) sheet.getParent()).removeView(sheet);
    }

    // .spec 分行：标签左灰 flex-none、值右对齐（对照混合版 .spec dt/dd）
    View customDetailRow(String k, String v) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.TOP);
        row.setPadding(0, dp(this, 8), 0, dp(this, 8));
        row.addView(tv(this, k, 13, Color.rgb(0x8E, 0x8E, 0x93), false),
            new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView vt = tvW(this, v == null ? "" : v, 13, Color.rgb(0x1C, 0x1C, 0x1E), 500);
        vt.setGravity(Gravity.END);
        row.addView(vt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    // Q8 对照混合版 styles.css .dlg input：#f8f8fa 近白底+1px #e2e2e6 边+圆角 10dp、内边距 12/11、
    // 聚焦边转 #007AFF 且底转纯白（废原 F5F6F8 无边灰石板面）。
    EditText customInput(String hint, String value, int maxLen) {
        final EditText e = new EditText(this);
        applyUiFont(e, 400);
        e.setHint(hint);
        e.setText(value == null ? "" : value);
        e.setTextSize(15);
        e.setSingleLine(true);
        if (maxLen > 0) e.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(maxLen)});
        e.setPadding(dp(this, 12), dp(this, 11), dp(this, 12), dp(this, 11));
        Runnable paint = () -> {
            boolean foc = e.hasFocus();
            GradientDrawable g = new GradientDrawable();
            g.setColor(foc ? Color.WHITE : Color.rgb(0xF8, 0xF8, 0xFA));
            g.setCornerRadius(dp(this, 10));
            g.setStroke(dp(this, 1), foc ? Color.rgb(0x00, 0x7A, 0xFF) : Color.rgb(0xE2, 0xE2, 0xE6));
            e.setBackground(g);
        };
        paint.run();
        e.setOnFocusChangeListener((v, has) -> paint.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(this, 6);
        e.setLayoutParams(lp);
        return e;
    }

    // Q8 对照混合版 .dlg label/.dlg-label：.85rem #555 非粗，组标签上距 12 下距 6（废原 12sp 粗灰）
    TextView customFormLabel(String s) {
        TextView t = tv(this, s, 13, Color.rgb(0x55, 0x55, 0x55), false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(this, 12);
        lp.bottomMargin = dp(this, 6);
        t.setLayoutParams(lp);
        return t;
    }

    // Q8：表单收键盘——先抓令牌再清焦点（Q25 纪律，清焦后取不到令牌键盘会残留）
    void hideKeyboardNow() {
        android.os.IBinder token = null;
        View foc = null;
        try { foc = getCurrentFocus(); if (foc != null) token = foc.getWindowToken(); } catch (Throwable ignored) {}
        if (token == null) {
            try {
                if (getWindow() != null && getWindow().getDecorView() != null)
                    token = getWindow().getDecorView().getWindowToken();
            } catch (Throwable ignored) {}
        }
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null && token != null) imm.hideSoftInputFromWindow(token, 0);
        } catch (Throwable ignored) {}
        try { if (foc != null) foc.clearFocus(); } catch (Throwable ignored) {}
    }

    // Q8 表单浮卡关闭：与 openAbout 同手感（窗下沉 42dp+遮罩淡出 180ms），落定后恢复底栏与悬浮钮
    void closeCustomForm() {
        final View sheet = customFormSheet;
        if (sheet == null) return;
        customFormSheet = null;
        hideKeyboardNow();
        if (sheet.getParent() != null) {
            View card = sheet instanceof ViewGroup && ((ViewGroup) sheet).getChildCount() > 1
                ? ((ViewGroup) sheet).getChildAt(((ViewGroup) sheet).getChildCount() - 1) : null;
            if (card != null) {
                card.animate().translationY(dp(this, 42)).alpha(0f)
                    .setDuration(ANIM_DUR_SHEET_OUT).setInterpolator(ANIM_EXIT)
                    .withEndAction(() -> {
                        if (sheet.getParent() != null) ((ViewGroup) sheet.getParent()).removeView(sheet);
                        restoreChrome(); // Q12
                    }).start();
                sheet.animate().alpha(0f).setDuration(ANIM_DUR_SHADE_OUT).setInterpolator(ANIM_EXIT).start();
                return;
            }
            ((ViewGroup) sheet.getParent()).removeView(sheet);
        }
        restoreChrome(); // Q12
    }

    void closeCustomFormNow() {
        View sheet = customFormSheet;
        if (sheet == null) return;
        customFormSheet = null;
        if (sheet.getParent() != null) ((ViewGroup) sheet.getParent()).removeView(sheet);
    }

    // ---------- Q9 NFC + 在线 BIN（对照混合版 app.js bankFromBinLocal/__onNfcCard/lookupBin 与混合版 MainActivity EMV 读卡） ----------
    // 本地认行：用 BIN 前缀在卡库 specs.BIN 里找最长匹配（同 app.js bankFromBinLocal，bestLen 起点 5、最长 8）
    String bankFromBinLocal(String bin) {
        if (bin == null) return "";
        String digits = bin.replaceAll("[^0-9]", "");
        String bank = "";
        int bestLen = 5;
        for (Card c : Store.all) {
            String b = c.spec("BIN").replaceAll("[^0-9]", "");
            if (b.length() < 6) continue;
            int n = Math.min(Math.min(digits.length(), b.length()), 8);
            if (n > bestLen && digits.startsWith(b.substring(0, n))) { bestLen = n; bank = c.bank == null ? "" : c.bank; }
        }
        return bank;
    }

    void enableNfcReader() {
        if (nfcAdapter == null) return;
        try {
            Bundle opts = new Bundle();
            opts.putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 250);
            nfcAdapter.enableReaderMode(this, nfcCallback,
                NfcAdapter.FLAG_READER_NFC_A | NfcAdapter.FLAG_READER_NFC_B
                | NfcAdapter.FLAG_READER_NFC_F | NfcAdapter.FLAG_READER_NFC_V
                | NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS, opts);
        } catch (Throwable ignored) {}
    }

    void stopNfcReader() {
        nfcWaiting = false;
        pendingNfcTarget = null;
        if (nfcTimeoutTask != null) { mainHandler.removeCallbacks(nfcTimeoutTask); nfcTimeoutTask = null; }
        try { if (nfcAdapter != null) nfcAdapter.disableReaderMode(this); } catch (Throwable ignored) {}
    }

    void startNfcRead(NfcFillTarget target) {
        if (nfcAdapter == null) { showFloatToast("这台手机没有 NFC 功能"); return; }
        try {
            if (!nfcAdapter.isEnabled()) { showFloatToast("NFC 还没打开，去系统设置打开后再试"); return; }
        } catch (Throwable ignored) { showFloatToast("NFC 启动失败"); return; }
        pendingNfcTarget = target;
        nfcWaiting = true;
        enableNfcReader();
        showFloatToast("把银行卡贴到手机背面 NFC 区域…");
        if (nfcTimeoutTask != null) mainHandler.removeCallbacks(nfcTimeoutTask);
        nfcTimeoutTask = () -> {
            if (nfcWaiting) { stopNfcReader(); showFloatToast("超时没等到卡，识别已取消"); }
        };
        mainHandler.postDelayed(nfcTimeoutTask, 30000);
    }

    void handleNfcTag(Tag tag) {
        // ReaderCallback 在 binder 线程回调，IsoDep 收发不能上主线程；结果统一 runOnUiThread 回填。
        try {
            IsoDep iso = IsoDep.get(tag);
            if (iso == null) {
                runOnUiThread(() -> { stopNfcReader(); showFloatToast("没读到芯片卡，把卡贴紧手机背面再试"); });
                return;
            }
            iso.connect();
            iso.setTimeout(5000);
            String aid = null, label = null;
            byte[] resp = iso.transceive(hexToBytes("00A404000E325041592E5359532E444446303100"));
            if (swOk(resp)) {
                String[] f = parseEmvDir(resp);
                aid = f[0]; label = f[1];
            }
            if (aid == null) {
                String[] aids = {"A0000000031010", "A0000000041010", "A000000333010101", "A0000000250101", "A0000000651010"};
                for (int i = 0; i < aids.length && aid == null; i++) {
                    byte[] r2 = iso.transceive(selectAid(aids[i]));
                    if (swOk(r2)) {
                        String[] f = parseEmvDir(r2);
                        aid = aids[i];
                        if (f[1] != null) label = f[1];
                    }
                }
            }
            String bin = null, last4 = null;
            if (aid != null) {
                try {
                    byte[] sel = iso.transceive(selectAid(aid));
                    if (swOk(sel)) {
                        String[] f2 = parseEmvDir(sel);
                        if (f2[1] != null && f2[1].length() > 0) label = f2[1];
                        String pan = readPan(iso, findValue(sel, 0x9F38));
                        if (pan != null && pan.length() >= 4) {
                            bin = pan.substring(0, Math.min(8, pan.length()));
                            last4 = pan.substring(pan.length() - 4);
                        }
                        // 完整卡号只在内存里过一遍取 BIN/尾号，立即丢弃，绝不落盘/日志（混合版铁律）
                        pan = null;
                    }
                } catch (Throwable ignored) {}
            }
            try { iso.close(); } catch (Throwable ignored) {}
            final String fAid = aid, fLabel = label, fBin = bin, fLast4 = last4;
            runOnUiThread(() -> onNfcTagResult(fAid, fLabel, fBin, fLast4));
        } catch (Throwable e) {
            runOnUiThread(() -> { stopNfcReader(); showFloatToast("读卡失败，把卡贴紧 NFC 区域再试"); });
        }
    }

    void onNfcTagResult(String aid, String label, final String bin, final String last4) {
        final NfcFillTarget t = pendingNfcTarget;
        stopNfcReader();
        if (aid == null) { showFloatToast("读到了卡，但没找到银行卡应用"); return; }
        if (t == null) return; // 表单已关，识别结果无处回填，静默结束（已给过贴卡提示）
        haptic();
        final String org = schemeOf(aid);
        final String type = typeOf(label);
        final String cleanLabel = sanitizeLabel(label);
        if (org != null && org.length() > 0 && t.orgSel != null) {
            t.orgSel[0] = org;
            if (t.paintOrgs != null) t.paintOrgs.run();
        }
        if (bin != null && bin.length() > 0) {
            String local = bankFromBinLocal(bin);
            if (local != null && local.length() > 0) {
                finishNfcFill(t, org, type, cleanLabel, bin, last4, local);
            } else {
                // 本地没认出再在线认行（同混合版 fetch binlist 的回落），失败也不挡回填
                new Thread(() -> {
                    String onlineBank = "";
                    try {
                        HttpURLConnection conn = (HttpURLConnection) new URL("https://lookup.binlist.net/" + bin).openConnection();
                        conn.setConnectTimeout(8000); conn.setReadTimeout(8000);
                        conn.setRequestProperty("Accept", "application/json");
                        if (conn.getResponseCode() == 200) {
                            JSONObject d = new JSONObject(Store.readAll(conn.getInputStream()));
                            JSONObject bk = d.optJSONObject("bank");
                            if (bk != null) onlineBank = bk.optString("name", "");
                        }
                        conn.disconnect();
                    } catch (Throwable ignored) {}
                    final String ob = onlineBank;
                    runOnUiThread(() -> finishNfcFill(t, org, type, cleanLabel, bin, last4, ob));
                }).start();
            }
        } else {
            finishNfcFill(t, org, type, cleanLabel, bin, last4, "");
        }
    }

    void finishNfcFill(NfcFillTarget t, String org, String type, String label, String bin, String last4, String bank) {
        if (t == null) return;
        try {
            if (bank != null && bank.length() > 0 && t.bank != null && t.bank.getText().toString().trim().isEmpty())
                t.bank.setText(bank);
            if (t.note != null && t.note.getText().toString().trim().isEmpty() && (type != null || bin != null || (bank != null && bank.length() > 0))) {
                StringBuilder sb = new StringBuilder("NFC 识别：");
                sb.append(type != null && !"类型未知".equals(type) ? type : "银行卡");
                if (label != null && label.length() > 0) sb.append("（").append(label).append("）");
                if (bank != null && bank.length() > 0) sb.append(" · 发卡行 ").append(bank);
                if (bin != null && bin.length() > 0) sb.append(" · BIN ").append(bin);
                if (last4 != null && last4.length() > 0) sb.append(" · 尾号 ").append(last4);
                t.note.setText(sb.toString());
            }
            if (t.name != null && t.name.getText().toString().trim().isEmpty()
                && org != null && org.length() > 0 && type != null && !"类型未知".equals(type))
                t.name.setText(org + type);
        } catch (Throwable ignored) {}
        StringBuilder toast = new StringBuilder("识别到：");
        toast.append(org != null && org.length() > 0 ? org : "未知组织");
        if (type != null && type.length() > 0) toast.append(" · ").append(type);
        if (bank != null && bank.length() > 0) toast.append(" · ").append(bank);
        showFloatToast(toast.toString());
    }

    boolean swOk(byte[] r) {
        return r != null && r.length >= 2 && (r[r.length - 2] & 0xFF) == 0x90 && (r[r.length - 1] & 0xFF) == 0x00;
    }
    byte[] selectAid(String aidHex) {
        byte[] aid = hexToBytes(aidHex);
        byte[] cmd = new byte[5 + aid.length + 1];
        cmd[1] = (byte) 0xA4; cmd[2] = 0x04;
        cmd[4] = (byte) aid.length;
        System.arraycopy(aid, 0, cmd, 5, aid.length);
        return cmd;
    }
    byte[] hexToBytes(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        return b;
    }
    String bytesToHex(byte[] d, int off, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            String h = Integer.toHexString(d[off + i] & 0xFF).toUpperCase();
            if (h.length() == 1) sb.append('0');
            sb.append(h);
        }
        return sb.toString();
    }
    String[] parseEmvDir(byte[] d) {
        String aid = null, label = null;
        int i = 0;
        while (i + 1 < d.length) {
            int first = d[i] & 0xFF;
            int tag = first, tlen = 1;
            if ((first & 0x1F) == 0x1F && i + 2 < d.length) { tag = (first << 8) | (d[i + 1] & 0xFF); tlen = 2; }
            int p = i + tlen;
            if (p >= d.length) break;
            int len = d[p] & 0xFF; p++;
            if (len == 0x81 && p < d.length) { len = d[p] & 0xFF; p++; }
            else if (len > 0x80) break;
            if (p + len > d.length) break;
            if (tag == 0x4F && aid == null) aid = bytesToHex(d, p, len);
            if ((tag == 0x50 || tag == 0x9F12) && label == null) label = asciiOf(d, p, len);
            if ((first & 0x20) != 0) { i = p; continue; }
            i = p + len;
        }
        return new String[]{aid, label};
    }
    String asciiOf(byte[] d, int off, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            int c = d[off + i] & 0xFF;
            if (c >= 32 && c < 127) sb.append((char) c);
        }
        return sb.toString().trim();
    }
    String sanitizeLabel(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 32 && c < 127 && c != '"' && c != '\\') || (c >= 0x4E00 && c <= 0x9FA5)) sb.append(c);
        }
        return sb.toString().trim();
    }
    String schemeOf(String aid) {
        if (aid == null) return "";
        if (aid.startsWith("A000000003")) return "Visa";
        if (aid.startsWith("A000000004")) return "万事达";
        if (aid.startsWith("A000000333")) return "银联";
        if (aid.startsWith("A000000025")) return "美国运通";
        if (aid.startsWith("A000000065")) return "JCB";
        return "";
    }
    String typeOf(String label) {
        if (label == null || label.length() == 0) return "类型未知";
        String s = label.toUpperCase();
        if (s.contains("DEBIT") || s.contains("MAESTRO") || s.contains("V PAY")) return "借记卡";
        if (s.contains("CREDIT")) return "信用卡";
        if (s.contains("PREPAID")) return "预付卡";
        return "类型未知";
    }
    byte[] findValue(byte[] d, int want) {
        int i = 0;
        while (i + 1 < d.length) {
            int first = d[i] & 0xFF;
            int tag = first, tlen = 1;
            if ((first & 0x1F) == 0x1F && i + 2 < d.length) { tag = (first << 8) | (d[i + 1] & 0xFF); tlen = 2; }
            int p = i + tlen;
            if (p >= d.length) break;
            int len = d[p] & 0xFF; p++;
            if (len == 0x81 && p < d.length) { len = d[p] & 0xFF; p++; }
            else if (len > 0x80) break;
            if (p + len > d.length) break;
            if (tag == want) {
                byte[] v = new byte[len];
                System.arraycopy(d, p, v, 0, len);
                return v;
            }
            if ((first & 0x20) != 0) { i = p; continue; }
            i = p + len;
        }
        return null;
    }
    byte bcdByte(int n) { return (byte) (((n / 10) << 4) | (n % 10)); }
    byte[] buildGpoData(byte[] pdol) {
        if (pdol == null || pdol.length == 0) return new byte[0];
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        java.util.Calendar cal = java.util.Calendar.getInstance();
        int i = 0;
        while (i < pdol.length) {
            int first = pdol[i] & 0xFF;
            int tag = first, tlen = 1;
            if ((first & 0x1F) == 0x1F && i + 1 < pdol.length) { tag = (first << 8) | (pdol[i + 1] & 0xFF); tlen = 2; }
            if (i + tlen >= pdol.length) break;
            int need = pdol[i + tlen] & 0xFF;
            byte[] v = new byte[need];
            if (tag == 0x9F1A || tag == 0x5F2A) {
                if (need >= 2) { v[need - 2] = 0x01; v[need - 1] = 0x56; }
            } else if (tag == 0x9A && need >= 3) {
                v[need - 3] = bcdByte(cal.get(java.util.Calendar.YEAR) % 100);
                v[need - 2] = bcdByte(cal.get(java.util.Calendar.MONTH) + 1);
                v[need - 1] = bcdByte(cal.get(java.util.Calendar.DAY_OF_MONTH));
            } else if (tag == 0x9F21 && need >= 3) {
                v[need - 3] = bcdByte(cal.get(java.util.Calendar.HOUR_OF_DAY));
                v[need - 2] = bcdByte(cal.get(java.util.Calendar.MINUTE));
                v[need - 1] = bcdByte(cal.get(java.util.Calendar.SECOND));
            } else if (tag == 0x9F37) {
                java.util.Random rnd = new java.util.Random();
                for (int k = 0; k < need; k++) v[k] = (byte) rnd.nextInt(256);
            } else if (tag == 0x9F66 && need >= 4) {
                v[need - 4] = 0x36; v[need - 3] = (byte) 0xA0; v[need - 2] = 0x40; v[need - 1] = 0x00;
            }
            out.write(v, 0, v.length);
            i += tlen + 1;
        }
        return out.toByteArray();
    }
    String readPan(IsoDep iso, byte[] pdol) throws Exception {
        byte[] gd = buildGpoData(pdol);
        byte[] cmd = new byte[8 + gd.length];
        cmd[0] = (byte) 0x80; cmd[1] = (byte) 0xA8;
        cmd[4] = (byte) (gd.length + 2);
        cmd[5] = (byte) 0x83; cmd[6] = (byte) gd.length;
        System.arraycopy(gd, 0, cmd, 7, gd.length);
        byte[] gpo = iso.transceive(cmd);
        if (!swOk(gpo)) {
            gpo = iso.transceive(new byte[]{(byte) 0x80, (byte) 0xA8, 0, 0, 2, (byte) 0x83, 0, 0});
            if (!swOk(gpo)) return null;
        }
        byte[] afl = findValue(gpo, 0x94);
        if (afl == null && gpo.length > 4 && (gpo[0] & 0xFF) == 0x80) {
            int l = gpo[1] & 0xFF;
            int end = Math.min(gpo.length - 2, 2 + l);
            if (end > 4) {
                afl = new byte[end - 4];
                System.arraycopy(gpo, 4, afl, 0, afl.length);
            }
        }
        if (afl == null) return null;
        for (int k = 0; k + 3 < afl.length; k += 4) {
            int sfi = (afl[k] & 0xFF) >> 3;
            int first = afl[k + 1] & 0xFF, last = afl[k + 2] & 0xFF;
            if (sfi < 1 || sfi > 30 || first < 1 || last < first || last > 10) continue;
            for (int rec = first; rec <= last; rec++) {
                byte[] rc = iso.transceive(new byte[]{0, (byte) 0xB2, (byte) rec, (byte) ((sfi << 3) | 4), 0});
                if (!swOk(rc)) continue;
                byte[] v5a = findValue(rc, 0x5A);
                String pan = v5a != null ? bcdDigits(v5a) : null;
                if (pan == null) {
                    byte[] t2 = findValue(rc, 0x57);
                    if (t2 != null) pan = track2Pan(t2);
                }
                if (pan != null && luhnOk(pan)) return pan;
            }
        }
        return null;
    }
    String bcdDigits(byte[] v) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < v.length; i++) {
            int hi = (v[i] >> 4) & 0xF, lo = v[i] & 0xF;
            if (hi <= 9) sb.append((char) ('0' + hi)); else if (hi != 0xF) return null;
            if (lo <= 9) sb.append((char) ('0' + lo)); else if (lo != 0xF) return null;
        }
        return sb.length() > 0 ? sb.toString() : null;
    }
    String track2Pan(byte[] v) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < v.length; i++) {
            for (int j = 0; j < 2; j++) {
                int n = j == 0 ? ((v[i] >> 4) & 0xF) : (v[i] & 0xF);
                if (n == 0xD || n == 0xF) return sb.length() >= 13 ? sb.toString() : null;
                if (n > 9) return null;
                sb.append((char) ('0' + n));
            }
        }
        return sb.length() >= 13 ? sb.toString() : null;
    }
    boolean luhnOk(String pan) {
        if (pan == null || pan.length() < 13 || pan.length() > 19) return false;
        int sum = 0;
        boolean alt = false;
        for (int i = pan.length() - 1; i >= 0; i--) {
            int dg = pan.charAt(i) - '0';
            if (dg < 0 || dg > 9) return false;
            if (alt) { dg *= 2; if (dg > 9) dg -= 9; }
            sum += dg;
            alt = !alt;
        }
        return sum % 10 == 0;
    }

    // Q43 对照混合版 styles.css .spec（display:flex;justify-content:space-between;gap:12px;
    // padding:8px 0;border-bottom:1px solid #f0f0f5;font-size:.9rem；dt 灰 flex:none、dd 右对齐
    // weight 500、overflow-wrap:anywhere）+ Q42 字体口径：全程 tvW 的内置无衬线（软件字体三档），
    // 键 WRAP_CONTENT 不定宽截断、值 weight 500 可换行不省略，行下 1px #F0F0F5 细线分行。
    LinearLayout binSpecRow(String k, String v) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.TOP);
        row.setPadding(0, dp(this, 8), 0, dp(this, 8));
        TextView dk = tvW(this, k, 13, Color.rgb(0x8E, 0x8E, 0x93), 400);
        dk.setSingleLine(false);
        row.addView(dk, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView dv = tvW(this, v == null ? "" : v, 13.5f, Color.rgb(0x1C, 0x1C, 0x1E), 500);
        dv.setGravity(Gravity.END);
        dv.setSingleLine(false);
        try { dv.setBreakStrategy(android.text.Layout.BREAK_STRATEGY_SIMPLE); } catch (Throwable ignored) {}
        LinearLayout.LayoutParams dvLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        dvLp.leftMargin = dp(this, 12);
        row.addView(dv, dvLp);
        wrap.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        View line = new View(this);
        line.setBackgroundColor(Color.rgb(0xF0, 0xF0, 0xF5));
        wrap.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(this, 1))));
        return wrap;
    }

    // Q43② 国家双语：英文原名（binlist country.name）+ 括号简体中文（alpha2 经 Locale 转出），
    // 如 Bangladesh（孟加拉国）；alpha2 缺失或中文与英文相同/为空时只显英文，不编造。
    static String binCountryDisplay(String name, String alpha2) {
        if (name == null || name.length() == 0) return "";
        if (alpha2 == null || alpha2.length() == 0) return name;
        try {
            String zh = new java.util.Locale("", alpha2).getDisplayCountry(java.util.Locale.SIMPLIFIED_CHINESE);
            if (zh != null && zh.length() > 0 && !zh.equalsIgnoreCase(name) && !zh.equalsIgnoreCase(alpha2)) return name + "（" + zh + "）";
        } catch (Throwable ignored) {}
        return name;
    }

    // Q43③ 英文发卡行名 → 本地中文行名（仅覆盖卡库 17 家，认不出返回空，不硬凑）
    static String binBankZh(String bankName) {
        if (bankName == null) return "";
        String s = bankName.toLowerCase(java.util.Locale.ROOT);
        if (s.contains("bank of china")) return "中国银行";
        if (s.contains("industrial and commercial") || s.contains("icbc")) return "工商银行";
        if (s.contains("china construction") || s.contains("ccb")) return "建设银行";
        if (s.contains("agricultural")) return "农业银行";
        if (s.contains("bank of communications") || s.contains("bocom")) return "交通银行";
        if (s.contains("china merchants") || s.contains("cmb")) return "招商银行";
        if (s.contains("citic")) return "中信银行";
        if (s.contains("ping an")) return "平安银行";
        if (s.contains("guangfa") || s.contains("cgb")) return "广发银行";
        if (s.contains("pudong") || s.contains("spd bank")) return "浦发银行";
        if (s.contains("postal savings") || s.contains("psbc")) return "邮储银行";
        if (s.contains("minsheng")) return "民生银行";
        if (s.contains("huaxia") || s.contains("hua xia")) return "华夏银行";
        if (s.contains("bank of beijing")) return "北京银行";
        if (s.contains("bank of shanghai")) return "上海银行";
        if (s.contains("bank of ningbo")) return "宁波银行";
        if (s.contains("industrial bank")) return "兴业银行";
        return "";
    }

    static java.util.Set<String> binSchemeOrgs(String scheme) {
        java.util.Set<String> out = new java.util.HashSet<>();
        if (scheme == null) return out;
        String s = scheme.toLowerCase(java.util.Locale.ROOT);
        if (s.contains("visa")) out.add("visa");
        if (s.contains("mastercard") || s.contains("maestro")) { out.add("mastercard"); out.add("mastercard-nucc"); }
        if (s.contains("unionpay") || s.contains("union pay")) out.add("unionpay");
        if (s.contains("amex") || s.contains("american express")) out.add("amex-cn");
        if (s.contains("jcb")) out.add("jcb");
        return out;
    }

    // Q43③ 本地命中：先 BIN 前缀最长匹配（卡库 specs.BIN），再发卡行（中英映射）+卡组织双中；
    // 都不中返回 null，结果区不许假装命中。
    Card findLocalCardForBin(String bin, String bankName, String scheme) {
        if (bin != null && bin.length() >= 6) {
            Card best = null; int bestK = 5;
            for (Card c : Store.all) {
                String digits = c.spec("BIN").replaceAll("[^0-9]", "");
                if (digits.length() < 6) continue;
                int k = Math.min(Math.min(bin.length(), digits.length()), 8);
                if (k > bestK && bin.substring(0, k).equals(digits.substring(0, k))) { bestK = k; best = c; }
            }
            if (best != null) return best;
        }
        String mapped = binBankZh(bankName);
        java.util.Set<String> orgs = binSchemeOrgs(scheme);
        if (orgs.isEmpty()) return null;
        Card best = null;
        for (Card c : Store.all) {
            boolean bankHit = (mapped.length() > 0 && mapped.equals(c.bank))
                || (bankName != null && bankName.length() > 0 && bankName.equals(c.bank));
            if (!bankHit || c.org == null || !orgs.contains(c.org)) continue;
            if (best == null || (c.hasScore ? c.score : -1) > (best.hasScore ? best.score : -1)) best = c;
        }
        return best;
    }

    // Q43③ 命中行：浅蓝卡可点开详情，带卡名与现成字段（评分/货币转换费），未命中不渲染此行
    View binLocalHitRow(final Card c) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(dp(this, 12), dp(this, 10), dp(this, 12), dp(this, 10));
        box.setBackground(roundRect(Color.rgb(0xF0, 0xF7, 0xFF), 12, this));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(tvW(this, "卡库里有这张卡", 13, Color.rgb(0x0A, 0x5C, 0xD6), 700));
        String ftf = c.spec("货币转换费（FTF）");
        if (ftf == null || ftf.length() == 0) ftf = c.spec("货币转换费");
        StringBuilder meta = new StringBuilder();
        meta.append(c.name == null ? "" : c.name);
        meta.append(" · ").append(c.bank == null ? "" : c.bank).append(" · ").append(orgLabel(c.org));
        if (c.hasScore) meta.append(" · ").append(String.format(java.util.Locale.US, "%.1f分", c.score));
        if (ftf != null && ftf.length() > 0) meta.append(" · 转换费 ").append(ftf);
        TextView mv = tv(this, meta.toString(), 12, Color.rgb(0x5A, 0x6B, 0x8A), false);
        mv.setSingleLine(false);
        LinearLayout.LayoutParams mvLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mvLp.topMargin = dp(this, 3);
        texts.addView(mv, mvLp);
        box.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView arrow = tv(this, "›", 20, Color.rgb(0x0A, 0x5C, 0xD6), false);
        arrow.setGravity(Gravity.CENTER);
        box.addView(arrow, new LinearLayout.LayoutParams(dp(this, 20), ViewGroup.LayoutParams.WRAP_CONTENT));
        box.setOnClickListener(v -> {
            haptic();
            final Card target = c;
            closeBinQueryNow();
            openDetail(target);
        });
        return box;
    }

    void openBinQuery() {
        closeBinQueryNow();
        lastBin = null; lastBinScheme = null; lastBinType = null; lastBinBrand = null; lastBinBank = null; lastBinCountry = null;
        lastBinPrepaid = null; lastBinBankUrl = null; lastBinBankPhone = null; lastBinBankCity = null; lastBinCountryAlpha2 = null; lastBinLocalCard = null; // Q43
        captureCurrentPageScroll();
        hideChrome(); // Q12
        final FrameLayout sheet = new FrameLayout(this);
        View shade = new View(this);
        shade.setBackgroundColor(Color.argb(102, 0, 0, 0));
        shade.setOnClickListener(v -> closeBinQuery());
        sheet.addView(shade, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable cg = new GradientDrawable();
        cg.setColor(colSheet()); // Q54 实底口径 + Q72 深色浮层，玻璃退为纯垫底
        float binR = dp(this, 22); // Q45：仅顶部圆角、底部直角贴屏底（原四角同圆时底部两角露遮罩成黑三角）
        cg.setCornerRadii(new float[]{binR, binR, binR, binR, 0, 0, 0, 0});
        card.setBackground(cg);
        if (Build.VERSION.SDK_INT >= 21) { card.setElevation(dp(this, 24)); topSheetClip(card, 22, this); } // Q45 顶圆底直轮廓
        card.setOnClickListener(v -> {});
        card.setPadding(dp(this, 18), dp(this, 18), dp(this, 18), dp(this, 14) + navBarH());
        final EditText inBin = customInput("输入卡号前 6–8 位", "", 8);
        inBin.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        final LinearLayout resultBox = new LinearLayout(this);
        resultBox.setOrientation(LinearLayout.VERTICAL);
        final Button addBtn = new Button(this);
        card.addView(tv(this, "在线查询卡信息", 17, Color.rgb(0x1C, 0x1C, 0x1E), true));
        TextView hint = tv(this, "输入银行卡号前 6–8 位，在线查询卡组织、发卡行等信息，可一键加入我的卡片。", 12.5f, Color.rgb(0x8E, 0x8E, 0x93), false);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintLp.topMargin = dp(this, 6);
        card.addView(hint, hintLp);
        LinearLayout formRow = new LinearLayout(this);
        formRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams formLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        formLp.topMargin = dp(this, 12);
        card.addView(formRow, formLp);
        formRow.addView(inBin, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button go = new Button(this);
        go.setText("查询"); go.setTextSize(15); go.setAllCaps(false); go.setTextColor(Color.WHITE);
        try { go.setTypeface(weightTypeface(this, 700)); } catch (Throwable ignored) {}
        GradientDrawable goBg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{Color.rgb(0x0A, 0x84, 0xFF), Color.rgb(0x00, 0x66, 0xE6)});
        goBg.setCornerRadius(dp(this, 12));
        go.setBackground(goBg);
        LinearLayout.LayoutParams goLp = new LinearLayout.LayoutParams(dp(this, 84), dp(this, 48));
        goLp.leftMargin = dp(this, 10);
        // customInput 自带 topMargin 6，与查询钮对齐需把输入框的边距在行内归零
        if (inBin.getLayoutParams() instanceof ViewGroup.MarginLayoutParams)
            ((ViewGroup.MarginLayoutParams) inBin.getLayoutParams()).topMargin = 0;
        formRow.addView(go, goLp);
        // Q43：结果区独立可滚（字段补全后可达 10 行，卡高封顶 82vh 时动作行不被挤出、长网址在行内换行）
        ScrollView binScroll = new ScrollView(this);
        thinScrollbar(binScroll);
        binScroll.addView(resultBox, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams resLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        resLp.topMargin = dp(this, 6);
        card.addView(binScroll, resLp);
        LinearLayout acts = new LinearLayout(this);
        acts.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams actLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actLp.topMargin = dp(this, 14);
        card.addView(acts, actLp);
        Button closeBtn = new Button(this);
        closeBtn.setText("关闭"); closeBtn.setTextSize(15); closeBtn.setAllCaps(false);
        try { closeBtn.setTypeface(weightTypeface(this, 600)); } catch (Throwable ignored) {}
        closeBtn.setBackground(roundRect(Color.rgb(0xF2, 0xF3, 0xF7), 14, this));
        closeBtn.setOnClickListener(v -> { haptic(); closeBinQuery(); });
        acts.addView(closeBtn, new LinearLayout.LayoutParams(0, dp(this, 48), 1f));
        addBtn.setText("加入我的卡片"); addBtn.setTextSize(15); addBtn.setAllCaps(false); addBtn.setTextColor(Color.WHITE);
        try { addBtn.setTypeface(weightTypeface(this, 700)); } catch (Throwable ignored) {}
        GradientDrawable addBg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{Color.rgb(0x0A, 0x84, 0xFF), Color.rgb(0x00, 0x66, 0xE6)});
        addBg.setCornerRadius(dp(this, 14));
        addBtn.setBackground(addBg);
        LinearLayout.LayoutParams addLp = new LinearLayout.LayoutParams(0, dp(this, 48), 1.4f);
        addLp.leftMargin = dp(this, 10);
        addBtn.setVisibility(View.GONE);
        acts.addView(addBtn, addLp);
        go.setOnClickListener(v -> { haptic(); lookupBinOnline(inBin.getText().toString().trim(), resultBox, addBtn); });
        addBtn.setOnClickListener(v -> { haptic(); addLastBinToMine(); });
        int sw = getResources().getDisplayMetrics().widthPixels;
        int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.82);
        card.measure(View.MeasureSpec.makeMeasureSpec(sw - dp(this, 24), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST));
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.min(card.getMeasuredHeight(), maxH));
        clp.gravity = Gravity.BOTTOM;
        clp.leftMargin = dp(this, 12); clp.rightMargin = dp(this, 12);
        clp.bottomMargin = 0; // Q45：窗底直达屏底，动作行靠卡内底部留白（含手势避让）抬起
        // Q45：玻璃与窗体同装贴底容器，玻璃高出 22dp、底圆角沉出容器被裁，与窗体同升同降；
        // closeBinQuery 取最后一层即此容器，动画口径不变；Q43 结果变高时由 resizeBinSheetToFit 跟随重定高
        FrameLayout binWrap = new FrameLayout(this);
        View binGlass = glassLayer(card, 22, false);
        topSheetClip(binGlass, 22, this); // Q54：玻璃轮廓与窗体同（顶圆底直），不得在窗外露面发雾
        binWrap.addView(binGlass, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, clp.height + dp(this, 22)));
        binWrap.addView(card, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        sheet.addView(binWrap, clp);
        content.addView(sheet);
        binSheet = sheet;
        sheet.setAlpha(0f);
        sheet.animate().alpha(1f).setDuration(ANIM_DUR_SHADE_IN).setInterpolator(ANIM_ENTER).start();
        binWrap.setTranslationY(dp(this, 40)); binWrap.setAlpha(0f);
        binWrap.animate().translationY(0f).alpha(1f).setDuration(ANIM_DUR_SHEET_IN).setInterpolator(ANIM_ENTER).start();
        inBin.postDelayed(() -> { try { inBin.requestFocus(); } catch (Throwable ignored) {} }, 120);
    }

    void closeBinQueryNow() {
        View sheet = binSheet;
        if (sheet == null) return;
        binSheet = null;
        if (sheet.getParent() != null) ((ViewGroup) sheet.getParent()).removeView(sheet);
    }

    // Q43：结果行变多后重定窗高——openBinQuery 开窗时按空结果测高冻结，补全字段（可达 10 行+命中行）
    // 会把动作行裁出窗外；查完按新内容重测（封顶 82vh），玻璃同步高出 22dp，binScroll 在窗内滚。
    void resizeBinSheetToFit() {
        try {
            if (binSheet == null || !(binSheet instanceof ViewGroup)) return;
            ViewGroup sheet = (ViewGroup) binSheet;
            if (sheet.getChildCount() < 2) return;
            View wrap = sheet.getChildAt(sheet.getChildCount() - 1);
            if (!(wrap instanceof ViewGroup) || ((ViewGroup) wrap).getChildCount() < 2) return;
            View card = ((ViewGroup) wrap).getChildAt(1);
            int sw = getResources().getDisplayMetrics().widthPixels;
            int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.82);
            card.measure(View.MeasureSpec.makeMeasureSpec(sw - dp(this, 24), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST));
            int h = Math.min(card.getMeasuredHeight(), maxH);
            ViewGroup.LayoutParams wlp = wrap.getLayoutParams();
            if (wlp != null && wlp.height != h) { wlp.height = h; wrap.setLayoutParams(wlp); }
            View glass = ((ViewGroup) wrap).getChildAt(0);
            ViewGroup.LayoutParams glp = glass.getLayoutParams();
            if (glp != null) { glp.height = h + dp(this, 22); glass.setLayoutParams(glp); }
        } catch (Throwable ignored) {}
    }

    void closeBinQuery() {
        final View sheet = binSheet;
        if (sheet == null) return;
        binSheet = null;
        hideKeyboardNow();
        if (sheet.getParent() != null) {
            View card = sheet instanceof ViewGroup && ((ViewGroup) sheet).getChildCount() > 1
                ? ((ViewGroup) sheet).getChildAt(((ViewGroup) sheet).getChildCount() - 1) : null;
            if (card != null) {
                card.animate().translationY(dp(this, 42)).alpha(0f)
                    .setDuration(ANIM_DUR_SHEET_OUT).setInterpolator(ANIM_EXIT)
                    .withEndAction(() -> {
                        if (sheet.getParent() != null) ((ViewGroup) sheet.getParent()).removeView(sheet);
                        restoreChrome(); // Q12
                    }).start();
                sheet.animate().alpha(0f).setDuration(ANIM_DUR_SHADE_OUT).setInterpolator(ANIM_EXIT).start();
                return;
            }
            ((ViewGroup) sheet.getParent()).removeView(sheet);
        }
        restoreChrome(); // Q12
    }

    // Q43 对照混合版 app.js lookupBin（fetch lookup.binlist.net、ok 门槛 scheme||bank.name、
    // 三态：查询中…／查不到这个 BIN 的信息，换个试试。／空窗提示句在窗体 hint）：
    // ①binlist 剩余字段全展示（prepaid 有才显、bank.city/url/phone 有才显，空跳行不编造）；
    // ②国家英文原名+括号简体中文（alpha2 经 Locale）；③本地命中（BIN 前缀→行名+组织）加可点行；
    // 失败只显查不到、不假装查到；一键加入沿 addBinToMine（name=银行+组织(BIN)、note=type·brand·国家英文名）。
    void lookupBinOnline(final String bin, final LinearLayout resultBox, final Button addBtn) {
        if (bin == null || !bin.matches("[0-9]{6,8}")) { showFloatToast("请输入 6–8 位数字 BIN"); return; }
        resultBox.removeAllViews();
        resultBox.addView(tv(this, "查询中…", 13.5f, Color.rgb(0x8E, 0x8E, 0x93), false));
        addBtn.setVisibility(View.GONE);
        lastBin = null; lastBinLocalCard = null;
        new Thread(() -> {
            String scheme = null, type = null, brand = null, bankName = null, country = null;
            String prepaid = null, bankUrl = null, bankPhone = null, bankCity = null, alpha2 = null;
            boolean ok = false;
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL("https://lookup.binlist.net/" + bin).openConnection();
                conn.setConnectTimeout(8000); conn.setReadTimeout(8000);
                conn.setRequestProperty("Accept", "application/json");
                int code = conn.getResponseCode();
                if (code == 200) {
                    JSONObject d = new JSONObject(Store.readAll(conn.getInputStream()));
                    scheme = d.optString("scheme", "");
                    type = d.optString("type", "");
                    brand = d.optString("brand", "");
                    if (d.has("prepaid") && !d.isNull("prepaid")) prepaid = d.optBoolean("prepaid", false) ? "是" : "否";
                    JSONObject bk = d.optJSONObject("bank");
                    if (bk != null) {
                        bankName = bk.optString("name", "");
                        bankUrl = bk.optString("url", "");
                        bankPhone = bk.optString("phone", "");
                        bankCity = bk.optString("city", "");
                    }
                    JSONObject co = d.optJSONObject("country");
                    if (co != null) { country = co.optString("name", ""); alpha2 = co.optString("alpha2", ""); }
                    ok = (scheme != null && scheme.length() > 0) || (bankName != null && bankName.length() > 0);
                }
                conn.disconnect();
            } catch (Throwable ignored) { ok = false; }
            final String fScheme = scheme, fType = type, fBrand = brand, fBank = bankName, fCountry = country;
            final String fPrepaid = prepaid, fUrl = bankUrl, fPhone = bankPhone, fCity = bankCity, fAlpha2 = alpha2;
            final boolean fOk = ok;
            runOnUiThread(() -> {
                resultBox.removeAllViews();
                if (!fOk) {
                    resultBox.addView(tv(MainActivity.this, "查不到这个 BIN 的信息，换个试试。", 13.5f, Color.rgb(0x8E, 0x8E, 0x93), false));
                    addBtn.setVisibility(View.GONE);
                    resultBox.post(() -> resizeBinSheetToFit());
                    return;
                }
                lastBin = bin; lastBinScheme = fScheme; lastBinType = fType; lastBinBrand = fBrand; lastBinBank = fBank; lastBinCountry = fCountry;
                lastBinPrepaid = fPrepaid; lastBinBankUrl = fUrl; lastBinBankPhone = fPhone; lastBinBankCity = fCity; lastBinCountryAlpha2 = fAlpha2;
                resultBox.addView(binSpecRow("BIN", bin));
                if (fScheme != null && fScheme.length() > 0) resultBox.addView(binSpecRow("卡组织", fScheme));
                if (fType != null && fType.length() > 0) resultBox.addView(binSpecRow("类型", fType));
                if (fBrand != null && fBrand.length() > 0) resultBox.addView(binSpecRow("品牌", fBrand));
                if (fPrepaid != null && fPrepaid.length() > 0) resultBox.addView(binSpecRow("是否预付", fPrepaid));
                if (fBank != null && fBank.length() > 0) resultBox.addView(binSpecRow("发卡行", fBank));
                if (fCity != null && fCity.length() > 0) resultBox.addView(binSpecRow("发卡行城市", fCity));
                if (fUrl != null && fUrl.length() > 0) resultBox.addView(binSpecRow("发卡行网址", fUrl));
                if (fPhone != null && fPhone.length() > 0) resultBox.addView(binSpecRow("发卡行电话", fPhone));
                String countryTxt = binCountryDisplay(fCountry, fAlpha2);
                if (countryTxt.length() > 0) resultBox.addView(binSpecRow("国家", countryTxt));
                Card local = null;
                try { local = findLocalCardForBin(bin, fBank, fScheme); } catch (Throwable ignored) { local = null; }
                lastBinLocalCard = local;
                if (local != null) {
                    View hit = binLocalHitRow(local);
                    LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    hLp.topMargin = dp(MainActivity.this, 10);
                    resultBox.addView(hit, hLp);
                }
                addBtn.setVisibility(View.VISIBLE);
                resultBox.post(() -> resizeBinSheetToFit());
            });
        }).start();
    }

    void addLastBinToMine() {
        if (lastBin == null) return;
        String bankName = lastBinBank == null ? "" : lastBinBank;
        String schemeUp = lastBinScheme == null ? "" : lastBinScheme.toUpperCase();
        String name = (bankName.length() > 0 ? bankName + " " : "") + (schemeUp.length() > 0 ? schemeUp : "银行卡") + " (" + lastBin + ")";
        java.util.List<String> parts = new ArrayList<>();
        if (lastBinType != null && lastBinType.length() > 0) parts.add(lastBinType);
        if (lastBinBrand != null && lastBinBrand.length() > 0) parts.add(lastBinBrand);
        if (lastBinCountry != null && lastBinCountry.length() > 0) parts.add(lastBinCountry);
        StringBuilder note = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) { if (i > 0) note.append(" · "); note.append(parts.get(i)); }
        CustomCard c = new CustomCard();
        c.id = "custom-" + System.currentTimeMillis();
        c.name = name; c.bank = bankName; c.org = schemeUp; c.note = note.toString(); c.style = 0;
        customCards.add(c);
        customOpen = true;
        saveCustomCards();
        closeBinQuery();
        showFloatToast("已加入我的卡片");
        refreshMineKeepScroll();
    }


    // Q8 组织 chips（对照混合版 openCustomForm 的 .chips button 与 .dlg .chips 数值）：
    // 胶囊圆角、内边距 13/7、选中蓝渐变白字（同 .chips button.on），未选走筛选 chips 的近白口径保可读
    TextView formOrgChip(String label) {
        TextView t = tv(this, label, 13, Color.rgb(0x1C, 0x1C, 0x1E), false);
        t.setSingleLine(true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(this, 13), dp(this, 7), dp(this, 13), dp(this, 7));
        t.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) pressBounce(v, true);
            else if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL)
                pressBounce(v, false);
            return false;
        });
        return t;
    }

    void paintFormOrgChip(TextView t, boolean on) {
        if (on) {
            GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.rgb(0x0A, 0x84, 0xFF), Color.rgb(0x00, 0x66, 0xE6)});
            g.setCornerRadius(dp(this, 999));
            t.setBackground(g);
            t.setTextColor(Color.WHITE);
            try { t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD); } catch (Throwable ignored) {}
        } else {
            GradientDrawable g = new GradientDrawable();
            g.setColor(Color.rgb(0xF2, 0xF3, 0xF7));
            g.setCornerRadius(dp(this, 999));
            g.setStroke(dp(this, 1), Color.argb(13, 20, 30, 60));
            t.setBackground(g);
            t.setTextColor(Color.rgb(0x1C, 0x1C, 0x1E));
            try { t.setTypeface(weightTypeface(this, 400)); } catch (Throwable ignored) {}
        }
    }

    // Q8 对照返工：施工前核对混合版 index.html #customDlg 与 styles.css .dlg/.dlg input/.chips/.swatches/
    // .dlg-actions 及 app.js openCustomForm——表单改根层贴底浮卡（左右/底部 12dp、圆角 22、最高 82vh、
    // 遮罩 rgba(0,0,0,.4)、升窗 280ms 上浮淡入，混合版开表单即藏 dock、原生藏整条 navWrap 同 Q32 口径）；
    // 输入框换 customInput 近白圆角带边；卡组织改 chips 流式换行（废原单行横排裁掉第四项）且选中走
    // .chips button.on 蓝渐变（废原整坨实心蓝）；卡面样式改 3×2 大渐变色块（.swatch 64×40 量级、
    // 圆角 8、选中 #007AFF 边+外圈 rgba(0,122,255,.25)，废原一排小药丸加勾）；标题/必填星/占位/
    // 字数上限照 index.html（名称*、如：我的工资卡/30、如：招商银行/20、备注 可空/60）；动作行
    // 取消 flex1/保存 flex2（.dlg-actions 口径），保存走 .primary-btn 蓝渐变。NFC 贴卡行已于 Q9 补齐（见表单内按钮）。
    void openCustomForm(final CustomCard edit) {
        closeCustomFormNow();
        final boolean isNew = edit == null;
        final CustomCard draft = new CustomCard();
        if (!isNew) {
            draft.id = edit.id; draft.name = edit.name; draft.bank = edit.bank;
            draft.org = edit.org; draft.note = edit.note; draft.style = edit.style;
            draft.acctClass = edit.acctClass == null ? "" : edit.acctClass;
            draft.kind = normCardKind(edit.kind);
        } else {
            draft.id = null; draft.name = ""; draft.bank = ""; draft.org = ""; draft.note = ""; draft.style = 0; draft.acctClass = ""; draft.kind = "bank";
        }
        final String[] kindSel = {normCardKind(draft.kind)};
        final Runnable[] applyKindVisibilityHolder = {null};
        final Runnable applyKindVisibility = () -> { if (applyKindVisibilityHolder[0] != null) applyKindVisibilityHolder[0].run(); };
        final String[] orgSel = {draft.org == null ? "" : draft.org};
        final String[] acctSel = {draft.acctClass == null ? "" : draft.acctClass};
        final int[] styleSel = {draft.style};

        captureCurrentPageScroll();
        hideChrome(); // Q12 混合版 openCustomForm 即 setDockVisible(false)
        final FrameLayout sheet = new FrameLayout(this);
        View shade = new View(this);
        shade.setBackgroundColor(Color.argb(102, 0, 0, 0)); // .dlg-backdrop rgba(0,0,0,.4)
        shade.setOnClickListener(v -> closeCustomForm());
        sheet.addView(shade, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable cg = new GradientDrawable();
        cg.setColor(colSheet()); // Q54 实底口径 + Q72 深色浮层，玻璃退为纯垫底
        cg.setStroke(dp(this, 1), Color.argb(140, 255, 255, 255));
        // Q45：仅顶部圆角、底部直角贴屏底（原四角同圆时底部两角露遮罩成黑三角）
        float formR = dp(this, 22);
        cg.setCornerRadii(new float[]{formR, formR, formR, formR, 0, 0, 0, 0});
        card.setBackground(cg);
        if (Build.VERSION.SDK_INT >= 21) {
            card.setElevation(dp(this, 24));
            topSheetClip(card, 22, this); // Q45 顶圆底直轮廓
        }
        card.setOnClickListener(v -> {}); // 窗体吃掉点击，防穿透遮罩误关

        ScrollView sv = new ScrollView(this);
        thinScrollbar(sv);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(this, 18), dp(this, 18), dp(this, 18), dp(this, 18) + navBarH()); // .dlg padding 18px；Q45 贴底后底部再加手势避让
        sv.addView(form);
        card.addView(sv, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        form.addView(tv(this, isNew ? "添加自定义卡片" : "编辑自定义卡片", 17, colText(), true));
        // Q82 卡种扩展位：银行卡/电话卡/其他，字段按卡种收窄（电话卡不套银行卡的组织/一类二类）
        form.addView(customFormLabel("卡种"));
        LinearLayout kindRow = new LinearLayout(this);
        kindRow.setOrientation(LinearLayout.HORIZONTAL);
        form.addView(kindRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        final java.util.List<TextView> kindChips = new ArrayList<>();
        final Runnable[] paintKind = new Runnable[1];
        paintKind[0] = () -> { for (int i = 0; i < kindChips.size(); i++) paintChoiceChip(kindChips.get(i), CARD_KIND_VALS[i].equals(kindSel[0])); };
        for (int i = 0; i < CARD_KIND_VALS.length; i++) {
            final String kv = CARD_KIND_VALS[i];
            TextView b = formOrgChip(CARD_KIND_LABELS[i]);
            b.setOnClickListener(v -> { haptic(); kindSel[0] = kv; paintKind[0].run(); applyKindVisibility.run(); });
            LinearLayout.LayoutParams kblp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) kblp.leftMargin = dp(this, 6);
            b.setLayoutParams(kblp);
            kindChips.add(b);
            kindRow.addView(b);
        }
        form.addView(customFormLabel("卡片名称*"));
        final EditText inName = customInput("如：我的工资卡", draft.name, 30);
        form.addView(inName);
        final TextView bankLabel = customFormLabel("发卡银行");
        form.addView(bankLabel);
        final EditText inBank = customInput("如：招商银行", draft.bank, 20);
        form.addView(inBank);
        final TextView orgLabel = customFormLabel("卡组织");
        form.addView(orgLabel);
        // 组织 chips 流式换行：按文字量宽逐行打包（行距/间距 6dp，同 .dlg .chips），任何一项不裁
        final LinearLayout orgFlow = new LinearLayout(this);
        orgFlow.setOrientation(LinearLayout.VERTICAL);
        form.addView(orgFlow, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        final java.util.List<TextView> orgChips = new ArrayList<>();
        Runnable paintOrgs = () -> {
            for (TextView b : orgChips) paintFormOrgChip(b, b.getText().toString().equals(orgSel[0]));
        };
        for (final String o : CUSTOM_ORGS) {
            TextView b = formOrgChip(o);
            b.setOnClickListener(v -> { haptic(); orgSel[0] = o.equals(orgSel[0]) ? "" : o; paintOrgs.run(); });
            orgChips.add(b);
        }
        {
            int avail = getResources().getDisplayMetrics().widthPixels - dp(this, 24) - dp(this, 36);
            Paint mp = new Paint();
            mp.setTextSize(13f * uiScale * getResources().getDisplayMetrics().scaledDensity);
            LinearLayout row = null;
            int rowW = 0;
            for (TextView chip : orgChips) {
                int w = (int) mp.measureText(chip.getText().toString()) + dp(this, 28);
                if (row == null || (rowW > 0 && rowW + dp(this, 6) + w > avail)) {
                    row = new LinearLayout(this);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    if (orgFlow.getChildCount() > 0) rlp.topMargin = dp(this, 6);
                    row.setLayoutParams(rlp);
                    orgFlow.addView(row);
                    rowW = 0;
                }
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                if (rowW > 0) clp.leftMargin = dp(this, 6);
                chip.setLayoutParams(clp);
                row.addView(chip);
                rowW += (rowW > 0 ? dp(this, 6) : 0) + w;
            }
        }
        paintOrgs.run();
        // Q65 账户类别（可不选）：不标/一类/二类，默认不标、不推断；下方一句说明，全文词条归 Q66
        final TextView acctLabel = customFormLabel("账户类别（可不选）");
        form.addView(acctLabel);
        LinearLayout acctRow = new LinearLayout(this);
        acctRow.setOrientation(LinearLayout.HORIZONTAL);
        form.addView(acctRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        final String[] aVals = {"", "一类", "二类"};
        final String[] aLabs = {"不标", "一类", "二类"};
        final java.util.List<TextView> acctChips = new ArrayList<>();
        final Runnable[] paintAcct = new Runnable[1];
        paintAcct[0] = () -> { for (int i = 0; i < acctChips.size(); i++) paintChoiceChip(acctChips.get(i), aVals[i].equals(acctSel[0])); };
        for (int i = 0; i < aVals.length; i++) {
            final String av = aVals[i];
            TextView b = formOrgChip(aLabs[i]);
            b.setOnClickListener(v -> { haptic(); acctSel[0] = av; paintAcct[0].run(); });
            LinearLayout.LayoutParams ablp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) ablp.leftMargin = dp(this, 6);
            b.setLayoutParams(ablp);
            acctChips.add(b);
            acctRow.addView(b);
        }
        paintAcct[0].run();
        TextView acctHint = tv(this, ACCT_CLASS_HINT, 11.5f, Color.rgb(0x8E, 0x8E, 0x93), false);
        acctHint.setLineSpacing(0, 1.45f);
        LinearLayout.LayoutParams ahLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ahLp.topMargin = dp(this, 6);
        form.addView(acctHint, ahLp);
        // Q82 字段收窄：仅银行卡显示卡组织/账户类别/NFC；电话卡银行行改运营商、其他卡改发行方
        final Runnable applyKindVisibilityReal = () -> {
            boolean bank = isBankKind(kindSel[0]);
            int vis = bank ? View.VISIBLE : View.GONE;
            orgLabel.setVisibility(vis);
            orgFlow.setVisibility(vis);
            acctLabel.setVisibility(vis);
            acctRow.setVisibility(vis);
            acctHint.setVisibility(vis);
            bankLabel.setText(bank ? "发卡银行" : ("phone".equals(kindSel[0]) ? "运营商" : "发行方"));
        };
        applyKindVisibilityHolder[0] = applyKindVisibilityReal;
        form.addView(customFormLabel("卡面样式"));
        // 卡面 3×2 大色块：每块高 40dp、间隔 10dp、圆角 8（.swatch 量级），选中蓝边+浅蓝外圈
        final java.util.List<View> swatchCells = new ArrayList<>();
        final java.util.List<View> swatchInners = new ArrayList<>();
        Runnable paintStyles = () -> {
            for (int i = 0; i < swatchInners.size(); i++) {
                boolean on = i == styleSel[0];
                GradientDrawable g = customGradient(i);
                g.setCornerRadius(dp(this, 8));
                g.setStroke(dp(this, 2), on ? Color.rgb(0x00, 0x7A, 0xFF) : Color.TRANSPARENT);
                swatchInners.get(i).setBackground(g);
                GradientDrawable ring = new GradientDrawable();
                ring.setColor(on ? Color.argb(64, 0, 122, 255) : Color.TRANSPARENT);
                ring.setCornerRadius(dp(this, 10));
                swatchCells.get(i).setBackground(ring);
            }
        };
        LinearLayout styleGrid = new LinearLayout(this);
        styleGrid.setOrientation(LinearLayout.VERTICAL);
        form.addView(styleGrid, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        for (int r = 0; r < 2; r++) {
            LinearLayout srow = new LinearLayout(this);
            srow.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams srlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (r > 0) srlp.topMargin = dp(this, 10);
            srow.setLayoutParams(srlp);
            styleGrid.addView(srow);
            for (int cix = 0; cix < 3; cix++) {
                final int si = r * 3 + cix;
                FrameLayout cell = new FrameLayout(this);
                cell.setPadding(dp(this, 2), dp(this, 2), dp(this, 2), dp(this, 2));
                LinearLayout.LayoutParams celp = new LinearLayout.LayoutParams(0, dp(this, 44), 1f);
                if (cix > 0) celp.leftMargin = dp(this, 6);
                cell.setLayoutParams(celp);
                View inner = new View(this);
                cell.addView(inner, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                cell.setOnClickListener(v -> { haptic(); styleSel[0] = si; paintStyles.run(); });
                swatchCells.add(cell);
                swatchInners.add(inner);
                srow.addView(cell);
            }
        }
        paintStyles.run();
        final EditText inNote = customInput("可空", draft.note, 60);
        // Q9 NFC 贴卡按钮（对照混合版 index.html #ccNfc：全宽、浅蓝底、蓝字，禁用 emoji 改纯文字细线语言）：
        // 点它起 NFC 读卡，回填组织/银行/备注；无 NFC 或未开启给明确提示，不静默失败。
        TextView nfcBtn = tv(this, "NFC 贴卡识别（自动填卡组织 / 类型）", 14, Color.rgb(0x00, 0x7A, 0xFF), true);
        nfcBtn.setGravity(Gravity.CENTER);
        nfcBtn.setPadding(0, dp(this, 12), 0, dp(this, 12));
        GradientDrawable nfcBg = new GradientDrawable();
        nfcBg.setColor(Color.rgb(0xF2, 0xF7, 0xFF));
        nfcBg.setCornerRadius(dp(this, 12));
        nfcBg.setStroke(dp(this, 1), Color.rgb(0xCF, 0xE4, 0xFF));
        nfcBtn.setBackground(nfcBg);
        LinearLayout.LayoutParams nfcLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nfcLp.topMargin = dp(this, 14);
        nfcBtn.setLayoutParams(nfcLp);
        nfcBtn.setOnClickListener(v -> {
            haptic();
            NfcFillTarget t = new NfcFillTarget();
            t.name = inName; t.bank = inBank; t.note = inNote; t.orgSel = orgSel; t.paintOrgs = paintOrgs;
            startNfcRead(t);
        });
        nfcBtn.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) pressBounce(v, true);
            else if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL) pressBounce(v, false);
            return false;
        });
        nfcBtn.setTag("kindBankOnly");
        form.addView(nfcBtn);
        // Q82：NFC 仅银行卡，电话卡/其他卡隐藏该行（applyKindVisibility 终版在此补 NFC）
        {
            final Runnable prev = applyKindVisibilityHolder[0];
            final TextView nfcRef = nfcBtn;
            applyKindVisibilityHolder[0] = () -> { if (prev != null) prev.run(); nfcRef.setVisibility(isBankKind(kindSel[0]) ? View.VISIBLE : View.GONE); };
            applyKindVisibilityHolder[0].run();
            paintKind[0].run();
        }
        form.addView(customFormLabel("备注"));
        form.addView(inNote);

        LinearLayout acts = new LinearLayout(this);
        acts.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams actLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actLp.topMargin = dp(this, 16);
        form.addView(acts, actLp);
        Button cancel = new Button(this);
        cancel.setText("取消"); cancel.setTextSize(15); cancel.setAllCaps(false);
        cancel.setBackground(roundRect(Color.rgb(0xF2, 0xF3, 0xF7), 14, this));
        cancel.setOnClickListener(v -> { haptic(); closeCustomForm(); });
        acts.addView(cancel, new LinearLayout.LayoutParams(0, dp(this, 48), 1f));
        Button save = new Button(this);
        save.setText("保存"); save.setTextSize(15); save.setAllCaps(false);
        save.setTextColor(Color.WHITE);
        try { save.setTypeface(save.getTypeface(), android.graphics.Typeface.BOLD); } catch (Throwable ignored) {}
        GradientDrawable saveBg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{Color.rgb(0x0A, 0x84, 0xFF), Color.rgb(0x00, 0x66, 0xE6)}); // .primary-btn 蓝渐变
        saveBg.setCornerRadius(dp(this, 14));
        save.setBackground(saveBg);
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(0, dp(this, 48), 2f);
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
                c.acctClass = isBankKind(kindSel[0]) ? normAcctClass(acctSel[0]) : "";
                c.kind = normCardKind(kindSel[0]);
                if (!isBankKind(kindSel[0])) c.org = "";
                customCards.add(c);
                customOpen = true;
                showFloatToast("已添加「" + name + "」");
            } else {
                edit.name = name;
                edit.bank = inBank.getText().toString().trim();
                edit.org = orgSel[0];
                edit.note = inNote.getText().toString().trim();
                edit.style = styleSel[0];
                edit.acctClass = isBankKind(kindSel[0]) ? normAcctClass(acctSel[0]) : "";
                edit.kind = normCardKind(kindSel[0]);
                if (!isBankKind(kindSel[0])) edit.org = "";
                showFloatToast("已保存「" + name + "」");
            }
            saveCustomCards();
            closeCustomForm();
            refreshMineKeepScroll();
        });

        int sw = getResources().getDisplayMetrics().widthPixels;
        int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.82); // .dlg max-height 82vh
        card.measure(View.MeasureSpec.makeMeasureSpec(sw - dp(this, 24), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST));
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Math.min(card.getMeasuredHeight(), maxH));
        clp.gravity = Gravity.BOTTOM;
        clp.leftMargin = dp(this, 12); clp.rightMargin = dp(this, 12);
        clp.bottomMargin = 0; // Q45：窗底直达屏底，表单底部留白（含手势避让）托起动作行
        // Q45：玻璃与窗体同装贴底容器，玻璃高出 22dp、底圆角沉出容器被裁，与窗体同升同降；
        // closeCustomForm 取最后一层即此容器，动画口径不变
        FrameLayout formWrap = new FrameLayout(this);
        View formGlass = glassLayer(card, 22, false);
        topSheetClip(formGlass, 22, this); // Q54：玻璃轮廓与窗体同（顶圆底直），不得在窗外露面发雾
        formWrap.addView(formGlass, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, clp.height + dp(this, 22))); // Q11 冻结玻璃垫底
        formWrap.addView(card, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        sheet.addView(formWrap, clp);
        content.addView(sheet);
        customFormSheet = sheet;
        sheet.setAlpha(0f);
        sheet.animate().alpha(1f).setDuration(ANIM_DUR_SHADE_IN).setInterpolator(ANIM_ENTER).start();
        formWrap.setTranslationY(dp(this, 40)); // dlgIn：40px 上浮淡入 .28s
        formWrap.setAlpha(0f);
        formWrap.animate().translationY(0f).alpha(1f).setDuration(ANIM_DUR_SHEET_IN)
            .setInterpolator(ANIM_ENTER).start();
    }

    // ---------- 资讯 / 设置 ----------
    // ---------- 数据 OTA（Phase 4c，对照 app.js checkDataUpdate/DATA_URLS） ----------
    boolean otaFetchStarted = false;

    // 启动自动查一次；设置页手动查 manual=true 给 toast 反馈。
    // Q59 修：双线都取，以 data_version 高者为准（jsDelivr 200 但回旧缓存时不被其骗成「已是最新」）。
    void checkDataUpdate(final boolean manual) { checkDataUpdate(manual, false, null); }
    // Q62: check = detect only (never applies). Apply happens in applyPendingUpdate after confirm.
    void checkDataUpdate(final boolean manual, final boolean fromPull, final Runnable onDone) {
        if (!manual && otaFetchStarted) { if (onDone != null) onDone.run(); return; }
        otaFetchStarted = true;
        final String[] urls = {
            "https://cdn.jsdelivr.net/gh/dimlogue/cardbox-data@main/cards.json",
            "https://raw.githubusercontent.com/dimlogue/cardbox-data/main/cards.json"
        };
        new Thread(() -> {
            String bestJson = null; int bestVer = -1;
            for (String u : urls) {
                try {
                    HttpURLConnection conn = (HttpURLConnection) new URL(u + "?t=" + System.currentTimeMillis()).openConnection();
                    conn.setConnectTimeout(8000); conn.setReadTimeout(8000);
                    conn.setRequestProperty("Cache-Control", "no-cache");
                    if (conn.getResponseCode() != 200) { conn.disconnect(); continue; }
                    String json = Store.readAll(conn.getInputStream()); conn.disconnect();
                    int remoteVer = Store.versionOf(json);
                    try { JSONObject probe = new JSONObject(json); JSONArray pa = probe.getJSONArray("cards"); if (pa == null || pa.length() == 0) continue; } catch (Exception e) { continue; }
                    if (remoteVer > bestVer) { bestVer = remoteVer; bestJson = json; }
                } catch (Exception e) {}
            }
            if (bestJson == null) {
                runOnUiThread(() -> { if (fromPull) showFloatToast("检查失败，请稍后再试"); else if (manual) showFloatToast("检查更新失败，请检查网络"); if (onDone != null) onDone.run(); });
                return;
            }
            if (bestVer <= Store.dataVersion) {
                pendingUpdateJson = null; pendingUpdateVer = -1;
                if (prefs != null) prefs.edit().remove("pending_update_version").apply();
                runOnUiThread(() -> { if (manual || fromPull) showFloatToast("已是最新数据（v" + Store.dataVersion + "）"); if (onDone != null) onDone.run(); });
                return;
            }
            pendingUpdateJson = bestJson; pendingUpdateVer = bestVer;
            if (prefs != null) prefs.edit().putInt("pending_update_version", bestVer).apply();
            final int ver = bestVer;
            runOnUiThread(() -> {
                if (onDone != null) onDone.run();
                if ("settings".equals(tab)) rebuildPages();
                if (manual || fromPull) { showUpdateConfirm(); }
                else {
                    int prompted = prefs == null ? -1 : prefs.getInt("update_prompted_version", -1);
                    if (prompted != ver && !isChromeCovered()) {
                        if (prefs != null) prefs.edit().putInt("update_prompted_version", ver).apply();
                        showUpdateTip(ver);
                    }
                }
            });
        }).start();
    }
    void applyPendingUpdate() {
        if (updateApplying) return;
        final String json = pendingUpdateJson; final int ver = pendingUpdateVer;
        if (json == null || ver <= Store.dataVersion) { showFloatToast("已是最新数据（v" + Store.dataVersion + "）"); return; }
        updateApplying = true; showFloatToast("正在更新数据…");
        new Thread(() -> {
            boolean ok = false;
            try { FileOutputStream fos = new FileOutputStream(new File(getFilesDir(), "cards-ota.json")); fos.write(json.getBytes("UTF-8")); fos.close(); ok = Store.parseInto(json); } catch (Exception e) { ok = false; }
            final boolean fok = ok;
            runOnUiThread(() -> {
                updateApplying = false;
                if (!fok) { showFloatToast("检查更新失败，请检查网络"); return; }
                pendingUpdateJson = null; pendingUpdateVer = -1;
                if (prefs != null) prefs.edit().remove("pending_update_version").apply();
                showFloatToast("卡片数据已更新到 v" + Store.dataVersion + "（" + Store.all.size() + " 张）");
                pages.clear(); if (detailCard == null) rebuildPages();
            });
        }).start();
    }
    FrameLayout buildUpdateSheet(String title, String msg, String cancelTxt, String okTxt, Runnable onOk) {
        hideChrome();
        final FrameLayout sheet = new FrameLayout(this);
        View shade = new View(this); shade.setBackgroundColor(Color.argb(102,0,0,0)); shade.setAlpha(0f);
        sheet.addView(shade, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable cg = new GradientDrawable(); cg.setColor(Color.rgb(0xFF,0xFF,0xFF));
        float rTop = dp(this,22); cg.setCornerRadii(new float[]{rTop,rTop,rTop,rTop,0,0,0,0}); card.setBackground(cg);
        if (Build.VERSION.SDK_INT >= 21) { card.setElevation(dp(this,24)); topSheetClip(card,22,this); }
        card.setOnClickListener(v->{}); card.setPadding(dp(this,18),dp(this,18),dp(this,18),dp(this,14)+navBarH());
        card.addView(tv(this,title,17,Color.rgb(0x1C,0x1C,0x1E),true));
        TextView m = tv(this,msg,13.5f,Color.rgb(0x3A,0x3A,0x3C),false); LinearLayout.LayoutParams mlp=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT); mlp.topMargin=dp(this,8); card.addView(m,mlp);
        LinearLayout btns=new LinearLayout(this); btns.setOrientation(LinearLayout.HORIZONTAL); LinearLayout.LayoutParams blp=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT); blp.topMargin=dp(this,18); card.addView(btns,blp);
        TextView cb=tv(this,cancelTxt,15,Color.rgb(0x1C,0x1C,0x1E),false); cb.setGravity(Gravity.CENTER); cb.setBackground(rippleBg(Color.rgb(0xF2,0xF3,0xF7),14));
        btns.addView(cb,new LinearLayout.LayoutParams(0,dp(this,48),1f));
        TextView ob=tv(this,okTxt,15,Color.WHITE,true); ob.setGravity(Gravity.CENTER); ob.setBackground(rippleBg(Color.rgb(0x0A,0x5C,0xD6),14));
        LinearLayout.LayoutParams olp=new LinearLayout.LayoutParams(0,dp(this,48),1f); olp.leftMargin=dp(this,10); btns.addView(ob,olp);
        FrameLayout.LayoutParams clp=new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT); clp.gravity=Gravity.BOTTOM; clp.leftMargin=dp(this,12); clp.rightMargin=dp(this,12);
        FrameLayout wrap=new FrameLayout(this); View glass=glassLayer(card,22,false); topSheetClip(glass,22,this);
        wrap.addView(glass,new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT));
        wrap.addView(card,new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT));
        sheet.addView(wrap,clp);
        sheet.setTag(new Object[]{wrap, cb, ob, shade});
        cb.setOnClickListener(v->{haptic(); closeUpdateSheet(sheet);});
        shade.setOnClickListener(v->closeUpdateSheet(sheet));
        ob.setOnClickListener(v->{haptic(); closeUpdateSheet(sheet); if(onOk!=null) mainHandlerPost(onOk);});
        return sheet;
    }
    void mainHandlerPost(Runnable r){ try{ new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(r,190);}catch(Throwable e){ r.run(); } }
    void showUpdateTip(final int ver){
        if(updateTipSheet!=null||updateConfirmSheet!=null) return; updateTipClosing=false;
        FrameLayout sheet=buildUpdateSheet("有新数据","检测到新数据 v"+ver+"，去更新？","知道了","去更新",()->showUpdateConfirm());
        updateTipSheet=sheet; content.addView(sheet); animateUpdateSheetIn(sheet);
    }
    void showUpdateConfirm(){
        if(updateConfirmSheet!=null) return; if(pendingUpdateVer<=Store.dataVersion){ if(pendingUpdateJson==null){ checkDataUpdate(true); return; } }
        updateConfirmClosing=false;
        FrameLayout sheet=buildUpdateSheet("更新数据","确定要更新数据吗？更新会覆盖当前卡库数据；你自己添加的卡片和收藏不会被改动，重复的卡会被合并删除。","取消","去更新",()->applyPendingUpdate());
        updateConfirmSheet=sheet; content.addView(sheet); animateUpdateSheetIn(sheet);
    }
    void animateUpdateSheetIn(FrameLayout sheet){
        Object[] t=(Object[])sheet.getTag(); View wrap=(View)t[0];
        sheet.setAlpha(0f); sheet.animate().alpha(1f).setDuration(ANIM_DUR_SHADE_IN).setInterpolator(ANIM_ENTER).start();
        wrap.setTranslationY(dp(this,42)); wrap.animate().translationY(0f).setDuration(ANIM_DUR_SHEET_IN).setInterpolator(ANIM_ENTER).start();
    }
    void closeUpdateSheet(final View sheet){
        if(sheet==null) return; boolean isTip=(sheet==updateTipSheet); boolean isConf=(sheet==updateConfirmSheet);
        if(isTip){ if(updateTipClosing) return; updateTipClosing=true; } if(isConf){ if(updateConfirmClosing) return; updateConfirmClosing=true; }
        Object[] t=sheet.getTag() instanceof Object[] ? (Object[])sheet.getTag() : null; View wrap=t!=null?(View)t[0]:null;
        Runnable done=()->{ if(sheet.getParent()!=null) ((ViewGroup)sheet.getParent()).removeView(sheet); if(isTip){updateTipSheet=null;updateTipClosing=false;} if(isConf){updateConfirmSheet=null;updateConfirmClosing=false;} restoreChrome(); };
        if(wrap!=null){ wrap.animate().translationY(dp(this,42)).alpha(0f).setDuration(ANIM_DUR_SHEET_OUT).setInterpolator(ANIM_EXIT).withEndAction(done).start(); sheet.animate().alpha(0f).setDuration(ANIM_DUR_SHADE_OUT).setInterpolator(ANIM_EXIT).start(); } else done.run();
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

    // Q66 卡片常识：资讯页下半组词条，数据源与资讯同走 assets 种子 + prefs 缓存 + OTA 双线（glossary.json），不写死在代码
    static class GlossaryItem {
        String id, term, aka, category, body;
    }
    List<GlossaryItem> glossaryItems = null;
    java.util.Set<String> glossaryOpen = new java.util.HashSet<>();
    boolean glossaryFetchStarted = false;
    LinearLayout glossaryBox = null;
    TextView glossaryMeta = null;
    String pendingGlossaryId = null;

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

    List<GlossaryItem> parseGlossary(String json) {
        try {
            JSONObject root = new JSONObject(json);
            JSONArray arr = root.getJSONArray("items");
            List<GlossaryItem> out = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                GlossaryItem g = new GlossaryItem();
                g.id = o.optString("id"); g.term = o.optString("term");
                g.aka = o.optString("aka"); g.category = o.optString("category");
                g.body = o.optString("body");
                if (g.id == null || g.id.isEmpty() || g.term == null || g.term.isEmpty()) continue;
                out.add(g);
            }
            return out;
        } catch (Exception e) { return null; }
    }

    void ensureGlossary() {
        if (glossaryItems != null) return;
        String cached = prefs == null ? null : prefs.getString("glossary_cache", null);
        List<GlossaryItem> c = cached == null ? null : parseGlossary(cached);
        if (c != null && !c.isEmpty()) { glossaryItems = c; return; }
        String seed = readAssetText("data/glossary.json");
        List<GlossaryItem> s = seed == null ? null : parseGlossary(seed);
        glossaryItems = s == null ? new ArrayList<GlossaryItem>() : s;
    }

    void fetchGlossaryUpdate() {
        if (glossaryFetchStarted) return;
        glossaryFetchStarted = true;
        final String[] urls = {
            "https://cdn.jsdelivr.net/gh/dimlogue/cardbox-data@main/glossary.json",
            "https://raw.githubusercontent.com/dimlogue/cardbox-data/main/glossary.json"
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
                    List<GlossaryItem> fresh = parseGlossary(json);
                    if (fresh == null || fresh.isEmpty()) continue;
                    boolean changed = glossaryItems == null || glossaryItems.size() != fresh.size()
                        || (!fresh.isEmpty() && !glossaryItems.isEmpty() && !fresh.get(0).id.equals(glossaryItems.get(0).id));
                    if (prefs != null) prefs.edit().putString("glossary_cache", json).apply();
                    glossaryItems = fresh;
                    if (changed) runOnUiThread(() -> { if ("news".equals(tab) && glossaryBox != null) renderGlossary(); });
                    return;
                } catch (Exception e) { /* 换下一条，失败保持内置/缓存 */ }
            }
        }).start();
    }

    void openGlossaryTerm(String id) {
        if (id == null) return;
        pendingGlossaryId = id;
        glossaryOpen.add(id);
        if (!"news".equals(tab)) showTab("news");
        else if (glossaryBox != null) renderGlossary();
    }

    void renderGlossary() {
        if (glossaryBox == null) return;
        glossaryBox.removeAllViews();
        if (glossaryItems == null || glossaryItems.isEmpty()) {
            glossaryBox.addView(emptyState("暂时还没有常识词条\n过段时间再来看看"));
            return;
        }
        if (glossaryMeta != null) glossaryMeta.setText("共 " + glossaryItems.size() + " 条 · 概念说明，仅供参考");
        String jumpId = pendingGlossaryId;
        View jumpView = null;
        for (final GlossaryItem g : glossaryItems) {
            final boolean open = glossaryOpen.contains(g.id);
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackground(rippleBg(Color.WHITE, 14));
            card.setClipToOutline(true);
            card.setPadding(dp(this, 14), dp(this, 11), dp(this, 14), dp(this, 11));
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.topMargin = dp(this, 8);
            glossaryBox.addView(card, clp);

            LinearLayout meta = new LinearLayout(this);
            meta.setOrientation(LinearLayout.HORIZONTAL);
            meta.setGravity(Gravity.CENTER_VERTICAL);
            card.addView(meta);
            String cat = g.category == null || g.category.isEmpty() ? "常识" : g.category;
            TextView cg = tv(this, cat, 10.5f, Color.rgb(0x0A, 0x5C, 0xD6), true);
            cg.setBackground(roundRect(Color.rgb(0xE8, 0xF1, 0xFD), 999, this));
            cg.setPadding(dp(this, 8), dp(this, 3), dp(this, 8), dp(this, 3));
            meta.addView(cg);
            TextView arrow = tv(this, open ? "收起 ‹" : "展开 ›", 11, Color.rgb(0x0A, 0x5C, 0xD6), true);
            arrow.setGravity(Gravity.RIGHT);
            meta.addView(arrow, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView ttl = tv(this, g.term == null ? "" : g.term, 15, Color.rgb(0x1C, 0x1C, 0x1E), true);
            LinearLayout.LayoutParams ttlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            ttlp.topMargin = dp(this, 7);
            card.addView(ttl, ttlp);
            if (g.aka != null && !g.aka.isEmpty()) {
                TextView aka = tv(this, g.aka, 12, Color.rgb(0x8E, 0x8E, 0x93), false);
                LinearLayout.LayoutParams akp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                akp.topMargin = dp(this, 2);
                card.addView(aka, akp);
            }
            if (g.body != null && !g.body.isEmpty()) {
                TextView bd = tv(this, g.body, 13, Color.rgb(0x3A, 0x3A, 0x3C), false);
                bd.setLineSpacing(dp(this, 2), 1f);
                LinearLayout.LayoutParams bdp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                bdp.topMargin = dp(this, 6);
                card.addView(bd, bdp);
                if (!open) { bd.setMaxLines(2); bd.setEllipsize(android.text.TextUtils.TruncateAt.END); }
                else {
                    TextView note = tv(this, "具体规则以发卡行与卡组织现行说明为准。", 11.5f, Color.rgb(0x8E, 0x8E, 0x93), false);
                    LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    nlp.topMargin = dp(this, 8);
                    card.addView(note, nlp);
                }
            }
            card.setOnClickListener(v -> {
                haptic();
                if (glossaryOpen.contains(g.id)) glossaryOpen.remove(g.id); else glossaryOpen.add(g.id);
                renderGlossary();
            });
            if (jumpId != null && jumpId.equals(g.id)) jumpView = card;
        }
        if (jumpView != null && newsScroll != null) {
            final View target = jumpView;
            newsScroll.post(() -> {
                try {
                    int y = 0;
                    View cur = target;
                    while (cur != null && cur != newsScroll.getChildAt(0)) { y += cur.getTop(); cur = (View) cur.getParent(); }
                    newsScroll.smoothScrollTo(0, Math.max(0, y - dp(MainActivity.this, 12)));
                } catch (Throwable ignored) {}
            });
            pendingGlossaryId = null;
        }
    }

    // ---------- Q68 在线搜卡·扩展卡库 ----------
    // 索引格式：{"cards":[{id,name,bank,org,type,url,image}]}，兼容 {"items":[...]} 与裸数组；
    // org 沿核心库代码（visa/mastercard/unionpay/...），type 为 debit/credit 或中文直写。
    java.util.List<ExtCard> parseExtended(String json) {
        if (json == null || json.trim().isEmpty()) return null;
        try {
            JSONArray arr = null;
            String t = json.trim();
            if (t.startsWith("[")) arr = new JSONArray(t);
            else {
                JSONObject root = new JSONObject(t);
                if (root.has("cards")) arr = root.optJSONArray("cards");
                else if (root.has("items")) arr = root.optJSONArray("items");
            }
            if (arr == null) return null;
            java.util.List<ExtCard> out = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                ExtCard e = new ExtCard();
                e.id = o.optString("id", "");
                e.name = o.optString("name", "");
                e.bank = o.optString("bank", "");
                e.org = o.optString("org", "");
                e.type = o.optString("type", "");
                e.url = o.optString("url", "");
                e.image = o.optString("image", "");
                if (e.name == null || e.name.trim().isEmpty()) continue;
                if (e.id == null || e.id.trim().isEmpty()) e.id = "ext-" + i + "-" + Math.abs((e.bank + "|" + e.name).hashCode());
                out.add(e);
            }
            return out;
        } catch (Throwable e) { return null; }
    }

    void ensureExtended() {
        if (extItems != null) return;
        try {
            String cached = prefs == null ? null : prefs.getString("extended_cache", null);
            java.util.List<ExtCard> c = cached == null ? null : parseExtended(cached);
            if (c != null) { extItems = c; return; }
        } catch (Throwable ignored) {}
        extItems = new ArrayList<>();
    }

    void fetchExtendedUpdate(final Runnable onDone) {
        if (extFetchStarted) { if (onDone != null) onDone.run(); return; }
        extFetchStarted = true;
        extLoading = true;
        final String[] urls = {
            "https://cdn.jsdelivr.net/gh/dimlogue/cardbox-data@main/extended.json",
            "https://raw.githubusercontent.com/dimlogue/cardbox-data/main/extended.json"
        };
        new Thread(() -> {
            String bestJson = null;
            java.util.List<ExtCard> best = null;
            for (String u : urls) {
                try {
                    HttpURLConnection conn = (HttpURLConnection) new URL(u + "?t=" + System.currentTimeMillis()).openConnection();
                    conn.setConnectTimeout(8000); conn.setReadTimeout(8000);
                    conn.setRequestProperty("Cache-Control", "no-cache");
                    if (conn.getResponseCode() != 200) { conn.disconnect(); continue; }
                    String json = Store.readAll(conn.getInputStream()); conn.disconnect();
                    java.util.List<ExtCard> parsed = parseExtended(json);
                    if (parsed == null) continue;
                    if (best == null || parsed.size() > best.size()) { best = parsed; bestJson = json; }
                    if (parsed.size() > 0) break;
                } catch (Throwable ignored) {}
            }
            final java.util.List<ExtCard> fBest = best;
            final String fJson = bestJson;
            runOnUiThread(() -> {
                extLoading = false;
                extFetchStarted = false;
                if (fBest != null) {
                    extItems = fBest;
                    if (fJson != null && prefs != null) {
                        try { prefs.edit().putString("extended_cache", fJson).apply(); } catch (Throwable ignored) {}
                    }
                }
                if (onDone != null) onDone.run();
                if (extSheet != null) renderExtResults();
            });
        }).start();
    }

    boolean isExtAdded(ExtCard e) {
        if (e == null || customCards == null) return false;
        String n = e.name == null ? "" : e.name.trim();
        String b = e.bank == null ? "" : e.bank.trim();
        for (CustomCard c : customCards) {
            String cn = c.name == null ? "" : c.name.trim();
            String cb = c.bank == null ? "" : c.bank.trim();
            if (n.equals(cn) && b.equals(cb)) return true;
        }
        return false;
    }

    void addExtToMine(final ExtCard e) {
        if (e == null) return;
        if (isExtAdded(e)) { showFloatToast("这张卡已经在我的卡片里了"); return; }
        CustomCard c = new CustomCard();
        c.id = "custom-ext-" + System.currentTimeMillis();
        c.name = e.name == null ? "" : e.name;
        c.bank = e.bank == null ? "" : e.bank;
        // CustomCard 的组织存显示名；扩展索引的 org 代码经 orgLabel 转显示名，空则留空
        String ol = (e.org == null || e.org.trim().isEmpty()) ? "" : orgLabel(e.org.trim());
        // CUSTOM_ORGS 用「Visa」而 orgLabel 返「VISA」，统一成表单口径，避免详情里大小写两套
        if ("VISA".equals(ol)) ol = "Visa";
        else if ("万事达-网联".equals(ol)) ol = "万事达";
        else if ("运通-人民币".equals(ol)) ol = "美国运通";
        c.org = ol;
        String typeTxt = "";
        if ("credit".equals(e.type)) typeTxt = "信用卡";
        else if ("debit".equals(e.type)) typeTxt = "借记卡";
        else if (e.type != null) typeTxt = e.type;
        StringBuilder note = new StringBuilder();
        if (!typeTxt.isEmpty()) note.append(typeTxt);
        note.append(note.length() > 0 ? " · 扩展卡库" : "扩展卡库");
        note.append(" · 规格待补，以发卡行官网为准");
        if (e.url != null && !e.url.trim().isEmpty()) note.append(" · ").append(e.url.trim());
        c.note = note.toString();
        c.acctClass = ""; c.kind = "bank";
        c.style = Math.abs((c.name + "|" + c.bank).hashCode()) % CUSTOM_STYLES.length;
        customCards.add(c);
        customOpen = true;
        saveCustomCards();
        showFloatToast("已加入我的卡片，可在详情里标一类/二类");
        renderExtResults();
    }

    void renderExtResults() {
        if (extResultBox == null) return;
        extResultBox.removeAllViews();
        ensureExtended();
        String q = extQuery == null ? "" : extQuery.trim().toLowerCase();
        // 本地核心库命中数（断网/扩展无结果时给出去向，不假装扩展有）
        int localHits = 0;
        if (!q.isEmpty()) {
            for (Card lc : Store.all) {
                String hay = ((lc.name == null ? "" : lc.name) + " " + (lc.bank == null ? "" : lc.bank) + " " + orgLabel(lc.org)).toLowerCase();
                if (hay.contains(q)) localHits++;
            }
        }
        if (extMeta != null) {
            int total = extItems == null ? 0 : extItems.size();
            if (extLoading) extMeta.setText("正在拉取扩展索引… 已缓存 " + total + " 条");
            else if (total == 0) extMeta.setText("扩展索引暂无缓存 · 本地核心库 " + Store.all.size() + " 张仍可搜");
            else extMeta.setText("扩展卡库 " + total + " 条 · 本地核心库 " + Store.all.size() + " 张");
        }
        if (extItems == null || extItems.isEmpty()) {
            TextView em = tv(this, extLoading ? "正在拉取扩展卡库…" : "暂时拉不到扩展卡库\n检查网络后再试；本地卡库在首页仍可搜索。", 13.5f, Color.rgb(0x8E, 0x8E, 0x93), false);
            em.setGravity(Gravity.CENTER);
            em.setPadding(dp(this, 16), dp(this, 28), dp(this, 16), dp(this, 28));
            extResultBox.addView(em);
            return;
        }
        java.util.List<ExtCard> hits = new ArrayList<>();
        for (ExtCard e : extItems) {
            if (q.isEmpty()) { hits.add(e); continue; }
            String hay = ((e.name == null ? "" : e.name) + " " + (e.bank == null ? "" : e.bank) + " " + (e.org == null ? "" : e.org) + " " + orgLabel(e.org == null ? "" : e.org) + " " + (e.type == null ? "" : e.type)).toLowerCase();
            if (hay.contains(q)) hits.add(e);
        }
        if (hits.isEmpty()) {
            String msg = "扩展卡库里没找到「" + (extQuery == null ? "" : extQuery.trim()) + "」";
            if (localHits > 0) msg += "\n本地卡库有 " + localHits + " 张相似卡，去首页搜索看看。";
            else msg += "\n换个卡名或银行试试，冷门卡会随扩展索引持续增补。";
            TextView em = tv(this, msg, 13.5f, Color.rgb(0x8E, 0x8E, 0x93), false);
            em.setGravity(Gravity.CENTER);
            em.setPadding(dp(this, 16), dp(this, 28), dp(this, 16), dp(this, 28));
            extResultBox.addView(em);
            return;
        }
        TextView cnt = tv(this, "找到 " + hits.size() + " 张" + (hits.size() > 60 ? "（只显示前 60 张，输入更准的关键词）" : ""), 12, Color.rgb(0x8E, 0x8E, 0x93), false);
        extResultBox.addView(cnt);
        int shown = 0;
        for (final ExtCard e : hits) {
            if (shown++ >= 60) break;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackground(rippleBg(Color.rgb(0xF8, 0xF8, 0xFA), 12));
            row.setPadding(dp(this, 10), dp(this, 10), dp(this, 10), dp(this, 10));
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.topMargin = dp(this, 8);
            extResultBox.addView(row, rlp);
            // 占位面：无图卡统一按 id 哈希配色 + Q70 组织小标，不预置扩展图进包（Q68 体积口径）
            FrameLayout thumb = new FrameLayout(this);
            thumb.setBackground(placeholderGradFor(e.id != null && !e.id.isEmpty() ? e.id : ((e.bank == null ? "" : e.bank) + (e.name == null ? "" : e.name)), 8, this));
            roundClip(thumb, 8, this);
            if (hasOrgBadge(e.org)) {
                addOrgBadge(thumb, e.org, 0.7f);
            } else {
                TextView orgShort = tv(this, (e.org == null || e.org.trim().isEmpty()) ? "卡" : orgLabel(e.org.trim()), 10, Color.WHITE, true);
                orgShort.setGravity(Gravity.CENTER);
                orgShort.setShadowLayer(dp(this, 1), 0, dp(this, 0.5f), Color.argb(120, 0, 0, 0));
                thumb.addView(orgShort, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }
            row.addView(thumb, new LinearLayout.LayoutParams(dp(this, 56), dp(this, 36)));
            LinearLayout mid = new LinearLayout(this);
            mid.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            mlp.leftMargin = dp(this, 10);
            row.addView(mid, mlp);
            mid.addView(tv(this, e.name == null ? "" : e.name, 15, Color.rgb(0x1C, 0x1C, 0x1E), true));
            String typeTxt = "credit".equals(e.type) ? "信用卡" : ("debit".equals(e.type) ? "借记卡" : (e.type == null ? "" : e.type));
            StringBuilder sub = new StringBuilder();
            if (e.bank != null && !e.bank.trim().isEmpty()) sub.append(e.bank.trim());
            String ol2 = (e.org == null || e.org.trim().isEmpty()) ? "" : orgLabel(e.org.trim());
            if (!ol2.isEmpty()) { if (sub.length() > 0) sub.append(" · "); sub.append(ol2); }
            if (!typeTxt.isEmpty()) { if (sub.length() > 0) sub.append(" · "); sub.append(typeTxt); }
            sub.append(" · 扩展卡库");
            mid.addView(tv(this, sub.toString(), 12, Color.rgb(0x8E, 0x8E, 0x93), false));
            if (e.url != null && !e.url.trim().isEmpty()) {
                TextView link = tv(this, "发卡行官网 ›", 12, Color.rgb(0x0A, 0x5C, 0xD6), true);
                LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                llp.topMargin = dp(this, 3);
                mid.addView(link, llp);
                final String fu = e.url.trim();
                link.setOnClickListener(v -> {
                    haptic();
                    try { startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(fu))); }
                    catch (Throwable ex) { showFloatToast("打不开这个链接"); }
                });
                row.setOnClickListener(v -> {
                    haptic();
                    try { startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(fu))); }
                    catch (Throwable ex) { showFloatToast("打不开这个链接"); }
                });
            }
            final boolean added = isExtAdded(e);
            TextView addBtn = tv(this, added ? "已加入" : "加入", 13, added ? Color.rgb(0x8E, 0x8E, 0x93) : Color.WHITE, true);
            addBtn.setGravity(Gravity.CENTER);
            addBtn.setPadding(dp(this, 12), dp(this, 7), dp(this, 12), dp(this, 7));
            if (added) addBtn.setBackground(roundRect(Color.rgb(0xE9, 0xE9, 0xED), 999, this));
            else {
                GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{Color.rgb(0x0A, 0x84, 0xFF), Color.rgb(0x00, 0x66, 0xE6)});
                g.setCornerRadius(dp(this, 999));
                addBtn.setBackground(g);
            }
            LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            alp.leftMargin = dp(this, 8);
            row.addView(addBtn, alp);
            if (!added) addBtn.setOnClickListener(v -> { haptic(); addExtToMine(e); });
        }
        if (!q.isEmpty() && localHits > 0) {
            TextView lh = tv(this, "本地卡库另有 " + localHits + " 张相似卡，在首页搜索即可查看。", 12, Color.rgb(0x8E, 0x8E, 0x93), false);
            LinearLayout.LayoutParams lhp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lhp.topMargin = dp(this, 12);
            extResultBox.addView(lh, lhp);
        }
    }

    // ---------- Q78 展柜（纯卡面展示：堆叠 / 平放自由画布） ----------
    // 数据源只用自有卡：收藏条目（mineEntries，同卡一类/二类各一条）+ 自定义卡；真实卡面图优先，
    // 无图走 Q70 占位面（placeholderGradFor + OrgBadgeView 按 org 画标），卡面本身不带任何文字信息层。
    // 构图参照 Apple Pay 卡包与 Mi Pay 集卡的叠压观感、卡链 canvas 的自由画布，只学构图、自写实现。
    static class ShowcaseItem {
        String key, bank;
        Card card; CustomCard custom;
        ShowcaseItem(String k, String b, Card c, CustomCard cc) { key = k; bank = b == null ? "" : b; card = c; custom = cc; }
    }
    static final int[] SHOWCASE_BGS = {
        Color.rgb(0xF2, 0xF3, 0xF7), Color.rgb(0xFF, 0xFF, 0xFF), Color.rgb(0xE8, 0xF1, 0xFD),
        Color.rgb(0xF6, 0xEF, 0xE6), Color.rgb(0x16, 0x28, 0x3F), Color.rgb(0x10, 0x10, 0x14)
    };
    java.util.List<ShowcaseItem> showcaseItems() {
        java.util.List<ShowcaseItem> out = new ArrayList<>();
        if (mineEntries != null) for (MineEntry e : mineEntries) {
            Card c = Store.byId.get(e.cardId);
            if (c != null) out.add(new ShowcaseItem("lib:" + e.key, c.bank, c, null));
        }
        if (customCards != null) for (CustomCard cc : customCards)
            out.add(new ShowcaseItem("cc:" + cc.id, cc.bank, null, cc));
        return out;
    }
    String showcaseOrgCode(ShowcaseItem it) {
        if (it.card != null) return it.card.org == null ? "" : it.card.org;
        if (it.custom == null || it.custom.org == null) return "";
        String o = it.custom.org.trim();
        if ("Visa".equals(o)) return "visa";
        if ("万事达".equals(o)) return "mastercard";
        if ("美国运通".equals(o)) return "amex-cn";
        if ("银联".equals(o)) return "unionpay";
        if ("JCB".equals(o)) return "jcb";
        return "";
    }
    int showcaseBgIdx() {
        int i = prefs == null ? 0 : prefs.getInt("showcase_bg", 0);
        return (i >= 0 && i < SHOWCASE_BGS.length) ? i : 0;
    }
    boolean showcaseDarkBg() { return showcaseBgIdx() >= 4; }
    int showcaseOnBg() { return showcaseDarkBg() ? Color.WHITE : Color.rgb(0x1C, 0x1C, 0x1E); }
    int showcaseOnBg2() { return showcaseDarkBg() ? Color.argb(170, 255, 255, 255) : Color.rgb(0x8E, 0x8E, 0x93); }

    View buildShowcaseFace(final ShowcaseItem it, final int wPx) {
        FrameLayout face = new FrameLayout(this);
        float wDp = wPx / getResources().getDisplayMetrics().density;
        float r = cardRadiusDp(wDp);
        roundClip(face, r, this);
        if (Build.VERSION.SDK_INT >= 21) face.setElevation(dp(this, 6));
        if (it.card != null) {
            face.setBackground(placeholderGradFor(it.card.id, r, this));
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            Bitmap b = Img.get(this, it.card.image);
            if (b != null) { iv.setImageBitmap(b); if (darkEff()) iv.setAlpha(0.92f); }
            face.addView(iv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            if (b == null) addOrgBadge(face, it.card.org, 1.2f);
        } else {
            face.setBackground(customGradient(it.custom.style));
            addOrgBadge(face, showcaseOrgCode(it), 1.2f);
        }
        face.setTag(it.key);
        return face;
    }

    void updateShowcaseChips() {
        String mode = prefs == null ? "stack" : prefs.getString("showcase_mode", "stack");
        boolean canvas = "canvas".equals(mode);
        if (showcaseStackChip != null) {
            showcaseStackChip.setBackground(roundRect(!canvas ? accentColor() : (showcaseDarkBg() ? Color.argb(70, 255, 255, 255) : Color.rgb(0xE9, 0xEC, 0xF2)), 999, this));
            showcaseStackChip.setTextColor(!canvas ? Color.WHITE : showcaseOnBg());
        }
        if (showcaseCanvasChip != null) {
            showcaseCanvasChip.setBackground(roundRect(canvas ? accentColor() : (showcaseDarkBg() ? Color.argb(70, 255, 255, 255) : Color.rgb(0xE9, 0xEC, 0xF2)), 999, this));
            showcaseCanvasChip.setTextColor(canvas ? Color.WHITE : showcaseOnBg());
        }
        boolean grp = prefs != null && prefs.getBoolean("showcase_bank_group", false);
        if (showcaseGroupChip != null) {
            showcaseGroupChip.setBackground(roundRect(grp ? accentColor() : (showcaseDarkBg() ? Color.argb(70, 255, 255, 255) : Color.rgb(0xE9, 0xEC, 0xF2)), 999, this));
            showcaseGroupChip.setTextColor(grp ? Color.WHITE : showcaseOnBg());
        }
        if (showcaseDensityRow != null) showcaseDensityRow.setVisibility(canvas ? View.VISIBLE : View.GONE);
    }

    void openShowcase() {
        if (showcaseView != null) return;
        captureCurrentPageScroll();
        hideChrome();
        showcaseClosing = false;
        try { showcasePosJson = new org.json.JSONObject(prefs == null ? "{}" : prefs.getString("showcase_positions", "{}")); }
        catch (Throwable t) { showcasePosJson = new org.json.JSONObject(); }
        final FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(SHOWCASE_BGS[showcaseBgIdx()]);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        overlay.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // 头部：标题 + 模式切换 + 关闭（细线自绘，禁用 emoji）
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(this, 16), pageTopPad(), dp(this, 12), dp(this, 8));
        col.addView(head, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        showcaseTitleTv = tvW(this, "展柜", 20, showcaseOnBg(), 800);
        head.addView(showcaseTitleTv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        showcaseStackChip = tv(this, "堆叠", 13, showcaseOnBg(), true);
        showcaseStackChip.setGravity(Gravity.CENTER);
        showcaseStackChip.setPadding(dp(this, 14), dp(this, 7), dp(this, 14), dp(this, 7));
        showcaseStackChip.setOnClickListener(v -> { haptic(); if (prefs != null) prefs.edit().putString("showcase_mode", "stack").apply(); updateShowcaseChips(); buildShowcaseBody(true); });
        head.addView(showcaseStackChip);
        showcaseCanvasChip = tv(this, "平放", 13, showcaseOnBg(), true);
        showcaseCanvasChip.setGravity(Gravity.CENTER);
        showcaseCanvasChip.setPadding(dp(this, 14), dp(this, 7), dp(this, 14), dp(this, 7));
        LinearLayout.LayoutParams ccLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ccLp.leftMargin = dp(this, 8);
        showcaseCanvasChip.setLayoutParams(ccLp);
        showcaseCanvasChip.setOnClickListener(v -> { haptic(); if (prefs != null) prefs.edit().putString("showcase_mode", "canvas").apply(); updateShowcaseChips(); buildShowcaseBody(true); });
        head.addView(showcaseCanvasChip);
        FrameLayout closeBtn = new FrameLayout(this);
        closeBtn.setBackground(roundRect(showcaseDarkBg() ? Color.argb(70, 255, 255, 255) : Color.argb(220, 255, 255, 255), 999, this));
        CloseIconView civ = new CloseIconView(this);
        civ.iconColor = showcaseOnBg();
        closeBtn.addView(civ, new FrameLayout.LayoutParams(dp(this, 18), dp(this, 18), Gravity.CENTER));
        LinearLayout.LayoutParams cbLp = new LinearLayout.LayoutParams(dp(this, 36), dp(this, 36));
        cbLp.leftMargin = dp(this, 10);
        closeBtn.setLayoutParams(cbLp);
        closeBtn.setOnClickListener(v -> { haptic(); closeShowcase(); });
        head.addView(closeBtn);
        // 主体
        showcaseBody = new FrameLayout(this);
        showcaseBody.setClipChildren(true);
        col.addView(showcaseBody, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        // 底部控制：背景色点 + 按银行分组 + 平放密度（只在平放显示）
        LinearLayout ctrl = new LinearLayout(this);
        ctrl.setOrientation(LinearLayout.VERTICAL);
        ctrl.setPadding(dp(this, 16), dp(this, 8), dp(this, 16), dp(this, 12) + navBarH());
        col.addView(ctrl, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout ctrlRow = new LinearLayout(this);
        ctrlRow.setOrientation(LinearLayout.HORIZONTAL);
        ctrlRow.setGravity(Gravity.CENTER_VERTICAL);
        ctrl.addView(ctrlRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        final java.util.List<View> bgDots = new ArrayList<>();
        for (int i = 0; i < SHOWCASE_BGS.length; i++) {
            final int bi = i;
            View dot = new View(this);
            GradientDrawable dg = new GradientDrawable();
            dg.setShape(GradientDrawable.OVAL);
            dg.setColor(SHOWCASE_BGS[i]);
            dg.setStroke(dp(this, bi == showcaseBgIdx() ? 2 : 1), bi == showcaseBgIdx() ? accentColor() : Color.argb(90, 128, 128, 140));
            dot.setBackground(dg);
            LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(dp(this, 26), dp(this, 26));
            if (i > 0) dLp.leftMargin = dp(this, 8);
            dot.setLayoutParams(dLp);
            dot.setOnClickListener(v -> {
                haptic();
                if (prefs != null) prefs.edit().putInt("showcase_bg", bi).apply();
                overlay.setBackgroundColor(SHOWCASE_BGS[bi]);
                if (showcaseTitleTv != null) showcaseTitleTv.setTextColor(showcaseOnBg());
                for (int k = 0; k < bgDots.size(); k++) {
                    GradientDrawable nd = new GradientDrawable();
                    nd.setShape(GradientDrawable.OVAL);
                    nd.setColor(SHOWCASE_BGS[k]);
                    nd.setStroke(dp(this, k == bi ? 2 : 1), k == bi ? accentColor() : Color.argb(90, 128, 128, 140));
                    bgDots.get(k).setBackground(nd);
                }
                updateShowcaseChips();
            });
            bgDots.add(dot);
            ctrlRow.addView(dot);
        }
        View sp = new View(this);
        ctrlRow.addView(sp, new LinearLayout.LayoutParams(0, 1, 1f));
        showcaseGroupChip = tv(this, "按银行分组", 12.5f, showcaseOnBg(), true);
        showcaseGroupChip.setGravity(Gravity.CENTER);
        showcaseGroupChip.setPadding(dp(this, 12), dp(this, 7), dp(this, 12), dp(this, 7));
        showcaseGroupChip.setOnClickListener(v -> {
            haptic();
            boolean g = prefs != null && prefs.getBoolean("showcase_bank_group", false);
            if (prefs != null) prefs.edit().putBoolean("showcase_bank_group", !g).apply();
            updateShowcaseChips(); buildShowcaseBody(true);
        });
        ctrlRow.addView(showcaseGroupChip);
        showcaseDensityRow = new LinearLayout(this);
        ((LinearLayout) showcaseDensityRow).setOrientation(LinearLayout.HORIZONTAL);
        ((LinearLayout) showcaseDensityRow).setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams drLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        drLp.topMargin = dp(this, 10);
        showcaseDensityRow.setLayoutParams(drLp);
        ctrl.addView(showcaseDensityRow);
        TextView dLab = tv(this, "密度", 12.5f, showcaseOnBg2(), true);
        ((LinearLayout) showcaseDensityRow).addView(dLab);
        android.widget.SeekBar seek = new android.widget.SeekBar(this);
        seek.setMax(70);
        float dens0 = prefs == null ? 1f : prefs.getFloat("showcase_density", 1f);
        seek.setProgress(Math.max(0, Math.min(70, Math.round((dens0 - 0.6f) * 100))));
        seek.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(android.widget.SeekBar sb, int pr, boolean fromUser) {}
            public void onStartTrackingTouch(android.widget.SeekBar sb) {}
            public void onStopTrackingTouch(android.widget.SeekBar sb) {
                float d = 0.6f + sb.getProgress() / 100f;
                if (prefs != null) prefs.edit().putFloat("showcase_density", d).apply();
                buildShowcaseBody(false);
            }
        });
        LinearLayout.LayoutParams skLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        skLp.leftMargin = dp(this, 10);
        ((LinearLayout) showcaseDensityRow).addView(seek, skLp);
        updateShowcaseChips();
        content.addView(overlay, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        showcaseView = overlay;
        buildShowcaseBody(false);
        overlay.setAlpha(0f);
        overlay.animate().alpha(1f).setDuration(ANIM_DUR_FADE).setInterpolator(ANIM_ENTER).start();
    }

    void closeShowcaseNow() {
        stopShowcaseDrift();
        View v = showcaseView;
        showcaseView = null; showcaseBody = null; showcaseWorld = null;
        showcaseStackChip = null; showcaseCanvasChip = null; showcaseGroupChip = null; showcaseDensityRow = null; showcaseTitleTv = null;
        showcaseClosing = false;
        if (v != null && v.getParent() != null) ((ViewGroup) v.getParent()).removeView(v);
    }

    void closeShowcase() {
        final View v = showcaseView;
        if (v == null || showcaseClosing) return;
        showcaseClosing = true;
        stopShowcaseDrift();
        v.animate().alpha(0f).setDuration(ANIM_DUR_SHEET_OUT).setInterpolator(ANIM_EXIT)
            .withEndAction(() -> { closeShowcaseNow(); restoreChrome(); }).start();
    }

    // ---------- Q84 电话卡保号管家（模块键 simkeep_，设置可关、关掉不占位） ----------
    static final String[] SIMKEEP_ACT_VALS = {"sms", "recharge", "call", "app", "other"};
    static final String[] SIMKEEP_ACT_LABELS = {"发短信", "充值", "拨打", "登录App", "其他"};
    static String simkeepActionLabel(String v) {
        if (v == null) return "发短信";
        for (int i = 0; i < SIMKEEP_ACT_VALS.length; i++) if (SIMKEEP_ACT_VALS[i].equals(v)) return SIMKEEP_ACT_LABELS[i];
        return v;
    }
    boolean simkeepEnabled() { return prefs == null || prefs.getBoolean("simkeep_enabled", true); }
    java.util.List<SimKeepItem> loadSimKeeps() {
        java.util.List<SimKeepItem> out = new ArrayList<>();
        if (prefs == null) return out;
        try {
            String raw = prefs.getString("simkeep_items", "[]");
            JSONArray arr = new JSONArray(raw == null || raw.isEmpty() ? "[]" : raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                SimKeepItem it = new SimKeepItem();
                it.id = o.optString("id"); it.cardId = o.optString("cardId");
                it.number = o.optString("number"); it.operator = o.optString("operator");
                it.country = o.optString("country"); it.nextDue = o.optString("nextDue");
                it.action = o.optString("action", "sms"); it.fee = o.optString("fee");
                it.cycleDays = o.optInt("cycleDays", 30);
                if (it.id == null || it.id.isEmpty()) it.id = "sim-" + i;
                if (it.cycleDays <= 0) it.cycleDays = 30;
                out.add(it);
            }
        } catch (Throwable ignored) {}
        return out;
    }
    void saveSimKeeps(java.util.List<SimKeepItem> items) {
        if (prefs == null) return;
        try {
            JSONArray arr = new JSONArray();
            for (SimKeepItem it : items) {
                JSONObject o = new JSONObject();
                o.put("id", it.id == null ? "" : it.id); o.put("cardId", it.cardId == null ? "" : it.cardId);
                o.put("number", it.number == null ? "" : it.number); o.put("operator", it.operator == null ? "" : it.operator);
                o.put("country", it.country == null ? "" : it.country); o.put("nextDue", it.nextDue == null ? "" : it.nextDue);
                o.put("action", it.action == null ? "sms" : it.action); o.put("fee", it.fee == null ? "" : it.fee);
                o.put("cycleDays", it.cycleDays);
                arr.put(o);
            }
            prefs.edit().putString("simkeep_items", arr.toString()).commit();
        } catch (Throwable ignored) {}
    }
    static String simkeepTodayStr() {
        try { return new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(new java.util.Date()); }
        catch (Throwable t) { return ""; }
    }
    static java.util.Date simkeepParseDate(String s) {
        if (s == null) return null;
        try { return new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(s.trim()); }
        catch (Throwable t) { return null; }
    }
    static int simkeepDaysLeft(String due) {
        java.util.Date d = simkeepParseDate(due);
        if (d == null) return 9999;
        long today = 0, target = 0;
        try {
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
            today = f.parse(f.format(new java.util.Date())).getTime();
            target = f.parse(due.trim()).getTime();
        } catch (Throwable t) { return 9999; }
        return (int) Math.round((target - today) / 86400000.0);
    }
    static String simkeepAddDays(String due, int days) {
        java.util.Date d = simkeepParseDate(due);
        if (d == null) d = new java.util.Date();
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTime(d); c.add(java.util.Calendar.DAY_OF_MONTH, days);
        try { return new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(c.getTime()); }
        catch (Throwable t) { return due; }
    }
    java.util.List<SimKeepItem> simkeepSorted() {
        java.util.List<SimKeepItem> items = loadSimKeeps();
        java.util.Collections.sort(items, (a, b) -> Integer.compare(simkeepDaysLeft(a.nextDue), simkeepDaysLeft(b.nextDue)));
        return items;
    }
    void openSimKeep() {
        if (simkeepView != null) return;
        captureCurrentPageScroll();
        hideChrome();
        simkeepClosing = false;
        final FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(colBg());
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        overlay.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL); head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(this, 16), pageTopPad(), dp(this, 12), dp(this, 8));
        col.addView(head, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        head.addView(tvW(this, "保号管家", 20, colText(), 800), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        FrameLayout closeBtn = new FrameLayout(this);
        closeBtn.setBackground(roundRect(colSurface(), 999, this)); closeBtn.setClipToOutline(true);
        CloseIconView civ = new CloseIconView(this); civ.iconColor = colText();
        closeBtn.addView(civ, new FrameLayout.LayoutParams(dp(this, 18), dp(this, 18), Gravity.CENTER));
        LinearLayout.LayoutParams cbLp = new LinearLayout.LayoutParams(dp(this, 36), dp(this, 36)); cbLp.leftMargin = dp(this, 8);
        closeBtn.setLayoutParams(cbLp);
        closeBtn.setOnClickListener(v -> { haptic(); closeSimKeep(); });
        head.addView(closeBtn);
        simkeepBody = new FrameLayout(this);
        col.addView(simkeepBody, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        content.addView(overlay, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        simkeepView = overlay;
        buildSimKeepBody();
        overlay.setAlpha(0f);
        overlay.animate().alpha(1f).setDuration(ANIM_DUR_FADE).setInterpolator(ANIM_ENTER).start();
    }
    void closeSimKeepNow() {
        View v = simkeepView; simkeepView = null; simkeepBody = null; simkeepClosing = false;
        if (v != null && v.getParent() != null) ((ViewGroup) v.getParent()).removeView(v);
    }
    void closeSimKeep() {
        final View v = simkeepView;
        if (v == null || simkeepClosing) return;
        if (simkeepFormSheet != null) { closeSimKeepForm(); return; }
        simkeepClosing = true;
        v.animate().alpha(0f).setDuration(ANIM_DUR_SHEET_OUT).setInterpolator(ANIM_EXIT)
            .withEndAction(() -> { closeSimKeepNow(); restoreChrome(); }).start();
    }
    void buildSimKeepBody() {
        if (simkeepBody == null) return;
        simkeepBody.removeAllViews();
        ScrollView sv = new ScrollView(this); thinScrollbar(sv); sv.setClipToPadding(false);
        LinearLayout inner = new LinearLayout(this); inner.setOrientation(LinearLayout.VERTICAL);
        inner.setPadding(dp(this, 14), dp(this, 6), dp(this, 14), dockPad());
        sv.addView(inner, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        simkeepBody.addView(sv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // 提醒设置（提前天数 + 时刻，本机保存；列表内以徽标呈现，系统通知后续再接）
        LinearLayout remindCard = new LinearLayout(this); remindCard.setOrientation(LinearLayout.VERTICAL);
        remindCard.setBackground(roundRect(colSurface(), 14, this)); remindCard.setClipToOutline(true);
        remindCard.setPadding(dp(this, 14), dp(this, 12), dp(this, 14), dp(this, 12));
        inner.addView(remindCard, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        remindCard.addView(tvW(this, "到期提醒", 14, colText(), 600));
        final int remindDays = prefs == null ? 3 : prefs.getInt("simkeep_remind_days", 3);
        final String remindTime = prefs == null ? "09:00" : prefs.getString("simkeep_remind_time", "09:00");
        remindCard.addView(tv(this, "提前 " + remindDays + " 天 · 每天 " + remindTime + " 提醒（先在本页标急展示，系统通知后续接）", 11.5f, colText2(), false));
        LinearLayout rdRow = new LinearLayout(this); rdRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rdLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); rdLp.topMargin = dp(this, 8);
        remindCard.addView(rdRow, rdLp);
        final int[] rdOpts = {0, 1, 3, 7};
        for (final int dv : rdOpts) {
            final boolean on = dv == remindDays;
            TextView chip = tv(this, dv == 0 ? "当天" : (dv + "天前"), 12.5f, on ? Color.WHITE : colText(), on);
            chip.setGravity(Gravity.CENTER); chip.setPadding(dp(this, 12), dp(this, 7), dp(this, 12), dp(this, 7));
            chip.setBackground(roundRect(on ? accentColor() : colChipOff(), 999, this));
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT); if (rdRow.getChildCount() > 0) clp.leftMargin = dp(this, 6);
            chip.setLayoutParams(clp);
            chip.setOnClickListener(v -> { haptic(); if (prefs != null) prefs.edit().putInt("simkeep_remind_days", dv).apply(); buildSimKeepBody(); });
            rdRow.addView(chip);
        }
        TextView addBtn = tv(this, "+ 添加保号卡", 14, Color.WHITE, true); addBtn.setGravity(Gravity.CENTER);
        addBtn.setPadding(0, dp(this, 12), 0, dp(this, 12)); addBtn.setBackground(roundRect(accentColor(), 12, this));
        LinearLayout.LayoutParams abLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); abLp.topMargin = dp(this, 12);
        inner.addView(addBtn, abLp);
        addBtn.setOnClickListener(v -> { haptic(); openSimKeepForm(null); });
        java.util.List<SimKeepItem> items = simkeepSorted();
        TextView sec = tvW(this, "待保号 · " + items.size() + " 张", 13, colText2(), 600);
        LinearLayout.LayoutParams secLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); secLp.topMargin = dp(this, 16); secLp.bottomMargin = dp(this, 4);
        inner.addView(sec, secLp);
        if (items.isEmpty()) {
            LinearLayout em = new LinearLayout(this); em.setOrientation(LinearLayout.VERTICAL);
            em.setBackground(roundRect(colSurface(), 14, this)); em.setPadding(dp(this, 16), dp(this, 22), dp(this, 16), dp(this, 22));
            em.addView(tv(this, "还没有保号卡。点上方添加，填号码、运营商和下次保号日期。", 13, colText2(), false));
            inner.addView(em, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return;
        }
        for (final SimKeepItem it : items) {
            final int left = simkeepDaysLeft(it.nextDue);
            final boolean urgent = left <= remindDays;
            LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL);
            // Q73/Q84：高斯玻璃面口径——冻结模糊垫底 + 薄染色 + 提亮层（glassTintDrawable/glassClip 同规范）
            card.setBackground(glassTintDrawable(16, false)); card.setClipToOutline(true); glassClip(card, 16, false);
            try { if (Build.VERSION.SDK_INT >= 21) card.setElevation(dp(this, 4)); } catch (Throwable ignored) {}
            card.setPadding(dp(this, 14), dp(this, 12), dp(this, 14), dp(this, 12));
            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); cardLp.topMargin = dp(this, 10);
            inner.addView(card, cardLp);
            LinearLayout top = new LinearLayout(this); top.setOrientation(LinearLayout.HORIZONTAL); top.setGravity(Gravity.CENTER_VERTICAL);
            card.addView(top);
            String title = (it.operator == null || it.operator.isEmpty() ? "电话卡" : it.operator) + (it.number == null || it.number.isEmpty() ? "" : (" · " + it.number));
            top.addView(tvW(this, title, 15, colText(), 700), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            String badge = left < 0 ? ("逾期 " + (-left) + " 天") : (left == 0 ? "今天到期" : (left + " 天后"));
            TextView bd = tv(this, (urgent ? "急 · " : "") + badge, 11.5f, urgent ? Color.WHITE : colText2(), true);
            bd.setGravity(Gravity.CENTER); bd.setPadding(dp(this, 9), dp(this, 5), dp(this, 9), dp(this, 5));
            bd.setBackground(roundRect(urgent ? Color.rgb(0xE0, 0x31, 0x31) : colChipOff(), 999, this));
            top.addView(bd);
            StringBuilder meta = new StringBuilder();
            if (it.country != null && !it.country.isEmpty()) meta.append(it.country).append(" · ");
            meta.append("下次 ").append(it.nextDue == null || it.nextDue.isEmpty() ? "未设日期" : it.nextDue);
            meta.append(" · ").append(simkeepActionLabel(it.action));
            if (it.fee != null && !it.fee.isEmpty()) meta.append(" · ").append(it.fee);
            meta.append(" · 每 ").append(it.cycleDays).append(" 天");
            TextView mv = tv(this, meta.toString(), 12, colText2(), false); bodyLH(mv);
            LinearLayout.LayoutParams mvLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); mvLp.topMargin = dp(this, 6);
            card.addView(mv, mvLp);
            LinearLayout acts = new LinearLayout(this); acts.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams actLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); actLp.topMargin = dp(this, 10);
            card.addView(acts, actLp);
            TextView done = tv(this, "已保号 · 顺延", 13, Color.WHITE, true); done.setGravity(Gravity.CENTER);
            done.setPadding(0, dp(this, 9), 0, dp(this, 9)); done.setBackground(roundRect(accentColor(), 10, this));
            acts.addView(done, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            done.setOnClickListener(v -> { haptic(); markSimKeepDone(it); });
            TextView edit = tv(this, "编辑", 13, colText(), true); edit.setGravity(Gravity.CENTER);
            edit.setPadding(0, dp(this, 9), 0, dp(this, 9)); edit.setBackground(roundRect(colChipOff(), 10, this));
            LinearLayout.LayoutParams edLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f); edLp.leftMargin = dp(this, 8);
            acts.addView(edit, edLp);
            edit.setOnClickListener(v -> { haptic(); openSimKeepForm(it); });
        }
        TextView hint = tv(this, "点「已保号」会按周期自动顺延下次日期；号码与费用只存本机。", 11, colText3(), false);
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); hLp.topMargin = dp(this, 12);
        inner.addView(hint, hLp);
    }
    void markSimKeepDone(final SimKeepItem it) {
        String newDue = simkeepAddDays(it.nextDue, it.cycleDays <= 0 ? 30 : it.cycleDays);
        java.util.List<SimKeepItem> items = loadSimKeeps();
        for (SimKeepItem x : items) if (x.id.equals(it.id)) { x.nextDue = newDue; break; }
        saveSimKeeps(items);
        showFloatToast("已顺延到 " + newDue);
        buildSimKeepBody();
    }
    void openSimKeepForm(final SimKeepItem edit) {
        closeSimKeepFormNow();
        final boolean isNew = edit == null;
        final SimKeepItem draft = new SimKeepItem();
        if (isNew) { draft.id = null; draft.cardId = ""; draft.number = ""; draft.operator = ""; draft.country = ""; draft.nextDue = simkeepTodayStr(); draft.action = "sms"; draft.fee = ""; draft.cycleDays = 30; }
        else { draft.id = edit.id; draft.cardId = edit.cardId; draft.number = edit.number; draft.operator = edit.operator; draft.country = edit.country; draft.nextDue = edit.nextDue; draft.action = edit.action; draft.fee = edit.fee; draft.cycleDays = edit.cycleDays; }
        final String[] actSel = {draft.action == null || draft.action.isEmpty() ? "sms" : draft.action};
        hideChrome();
        final FrameLayout sheet = new FrameLayout(this);
        View shade = new View(this); shade.setBackgroundColor(Color.argb(102, 0, 0, 0));
        shade.setOnClickListener(v -> closeSimKeepForm());
        sheet.addView(shade, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable cg = new GradientDrawable(); cg.setColor(colSheet()); cg.setStroke(dp(this, 1), Color.argb(140, 255, 255, 255));
        float formR = dp(this, 22); cg.setCornerRadii(new float[]{formR, formR, formR, formR, 0, 0, 0, 0});
        card.setBackground(cg);
        if (Build.VERSION.SDK_INT >= 21) { card.setElevation(dp(this, 24)); topSheetClip(card, 22, this); }
        card.setOnClickListener(v -> {});
        ScrollView sv = new ScrollView(this); thinScrollbar(sv);
        LinearLayout form = new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(this, 18), dp(this, 18), dp(this, 18), dp(this, 18) + navBarH());
        sv.addView(form); card.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        form.addView(tv(this, isNew ? "添加保号卡" : "编辑保号卡", 17, colText(), true));
        form.addView(customFormLabel("手机号码"));
        final EditText inNum = customInput("如：+86 138…", draft.number, 24); form.addView(inNum);
        form.addView(customFormLabel("运营商"));
        final EditText inOp = customInput("如：中国移动 / csl / Digi", draft.operator, 24); form.addView(inOp);
        form.addView(customFormLabel("国家 / 地区"));
        final EditText inCountry = customInput("如：中国 / 香港 / 马来西亚", draft.country, 20); form.addView(inCountry);
        form.addView(customFormLabel("下次保号日期（yyyy-MM-dd）"));
        final EditText inDue = customInput("2026-11-01", draft.nextDue, 10); form.addView(inDue);
        form.addView(customFormLabel("保号动作"));
        LinearLayout actRow = new LinearLayout(this); actRow.setOrientation(LinearLayout.HORIZONTAL); form.addView(actRow);
        final java.util.List<TextView> actChips = new ArrayList<>();
        final Runnable[] paintActs = new Runnable[1];
        paintActs[0] = () -> { for (int i = 0; i < actChips.size(); i++) paintChoiceChip(actChips.get(i), SIMKEEP_ACT_VALS[i].equals(actSel[0])); };
        for (int i = 0; i < SIMKEEP_ACT_VALS.length; i++) {
            final String av = SIMKEEP_ACT_VALS[i];
            TextView b = formOrgChip(SIMKEEP_ACT_LABELS[i]);
            b.setOnClickListener(v -> { haptic(); actSel[0] = av; paintActs[0].run(); });
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT); if (i > 0) blp.leftMargin = dp(this, 6);
            b.setLayoutParams(blp); actChips.add(b); actRow.addView(b);
        }
        paintActs[0].run();
        form.addView(customFormLabel("费用（可空）"));
        final EditText inFee = customInput("如：¥10 / 免费", draft.fee, 20); form.addView(inFee);
        form.addView(customFormLabel("周期（天）"));
        final EditText inCycle = customInput("30", String.valueOf(draft.cycleDays), 4); form.addView(inCycle);
        LinearLayout acts = new LinearLayout(this); acts.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams actLp2 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); actLp2.topMargin = dp(this, 16);
        form.addView(acts, actLp2);
        Button cancel = new Button(this); cancel.setText("取消"); cancel.setTextSize(15); cancel.setAllCaps(false);
        cancel.setBackground(roundRect(Color.rgb(0xF2, 0xF3, 0xF7), 14, this));
        cancel.setOnClickListener(v -> { haptic(); closeSimKeepForm(); });
        acts.addView(cancel, new LinearLayout.LayoutParams(0, dp(this, 48), 1f));
        Button save = new Button(this); save.setText("保存"); save.setTextSize(15); save.setAllCaps(false); save.setTextColor(Color.WHITE);
        GradientDrawable saveBg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{Color.rgb(0x0A, 0x84, 0xFF), Color.rgb(0x00, 0x66, 0xE6)}); saveBg.setCornerRadius(dp(this, 14)); save.setBackground(saveBg);
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(0, dp(this, 48), 2f); saveLp.leftMargin = dp(this, 10);
        acts.addView(save, saveLp);
        save.setOnClickListener(v -> {
            haptic();
            String num = inNum.getText().toString().trim(); String op = inOp.getText().toString().trim();
            String due = inDue.getText().toString().trim();
            if (num.isEmpty() && op.isEmpty()) { showFloatToast("请至少填号码或运营商"); return; }
            if (simkeepParseDate(due) == null) { showFloatToast("日期请用 yyyy-MM-dd"); inDue.requestFocus(); return; }
            int cyc = 30; try { cyc = Integer.parseInt(inCycle.getText().toString().trim()); } catch (Throwable ignored) {}
            if (cyc <= 0) cyc = 30;
            java.util.List<SimKeepItem> items = loadSimKeeps();
            if (isNew) {
                SimKeepItem ni = new SimKeepItem(); ni.id = "sim-" + System.currentTimeMillis();
                ni.cardId = ""; ni.number = num; ni.operator = op; ni.country = inCountry.getText().toString().trim();
                ni.nextDue = due; ni.action = actSel[0]; ni.fee = inFee.getText().toString().trim(); ni.cycleDays = cyc;
                items.add(ni); showFloatToast("已添加保号卡");
            } else {
                for (SimKeepItem x : items) if (x.id.equals(edit.id)) { x.number = num; x.operator = op; x.country = inCountry.getText().toString().trim(); x.nextDue = due; x.action = actSel[0]; x.fee = inFee.getText().toString().trim(); x.cycleDays = cyc; break; }
                showFloatToast("已保存");
            }
            saveSimKeeps(items); closeSimKeepForm(); buildSimKeepBody();
        });
        if (!isNew) {
            TextView del = tv(this, "删除这张保号卡", 13, Color.rgb(0xE0, 0x31, 0x31), true); del.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams delLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); delLp.topMargin = dp(this, 12);
            form.addView(del, delLp);
            del.setOnClickListener(v -> {
                haptic();
                java.util.List<SimKeepItem> items = loadSimKeeps();
                SimKeepItem rm = null; for (SimKeepItem x : items) if (x.id.equals(edit.id)) { rm = x; break; }
                if (rm != null) { items.remove(rm); saveSimKeeps(items); }
                closeSimKeepForm(); buildSimKeepBody(); showFloatToast("已删除");
            });
        }
        int sw = getResources().getDisplayMetrics().widthPixels;
        int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.82);
        card.measure(View.MeasureSpec.makeMeasureSpec(sw - dp(this, 24), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST));
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.min(card.getMeasuredHeight(), maxH));
        clp.gravity = Gravity.BOTTOM; clp.leftMargin = dp(this, 12); clp.rightMargin = dp(this, 12); clp.bottomMargin = 0;
        FrameLayout wrap = new FrameLayout(this);
        View glass = glassLayer(card, 22, false); topSheetClip(glass, 22, this);
        wrap.addView(glass, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, clp.height + dp(this, 22)));
        wrap.addView(card, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        sheet.addView(wrap, clp);
        content.addView(sheet, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        simkeepFormSheet = sheet;
        animShadeIn(shade); animSheetIn(wrap);
    }
    void closeSimKeepFormNow() {
        View s = simkeepFormSheet; simkeepFormSheet = null; simkeepFormClosing = false;
        if (s != null && s.getParent() != null) ((ViewGroup) s.getParent()).removeView(s);
    }
    void closeSimKeepForm() {
        final View sheet = simkeepFormSheet;
        if (sheet == null || simkeepFormClosing) return;
        simkeepFormClosing = true; hideKeyboardNow();
        if (sheet.getParent() != null) {
            View wrap = sheet instanceof ViewGroup && ((ViewGroup) sheet).getChildCount() > 1 ? ((ViewGroup) sheet).getChildAt(((ViewGroup) sheet).getChildCount() - 1) : null;
            if (wrap != null) {
                animSheetOut(wrap, () -> { closeSimKeepFormNow(); if (simkeepView == null) restoreChrome(); });
                if (sheet instanceof ViewGroup && ((ViewGroup) sheet).getChildCount() > 0) animShadeOut(((ViewGroup) sheet).getChildAt(0));
                return;
            }
            ((ViewGroup) sheet.getParent()).removeView(sheet);
        }
        simkeepFormSheet = null; simkeepFormClosing = false;
        if (simkeepView == null) restoreChrome();
    }

    void stopShowcaseDrift() {
        if (showcaseDriftTask != null && mainHandler != null) mainHandler.removeCallbacks(showcaseDriftTask);
        showcaseDriftTask = null;
    }

    void startShowcaseDrift() {
        stopShowcaseDrift();
        showcaseDriftTask = new Runnable() {
            public void run() {
                if (showcaseView == null || showcaseWorld == null || showcaseClosing) return;
                if (!showcaseDragging && System.currentTimeMillis() - showcaseLastTouchMs > 3000) {
                    float nx = showcaseWorld.getTranslationX() + showcaseDriftVx;
                    float ny = showcaseWorld.getTranslationY() + showcaseDriftVy;
                    if (nx > dp(MainActivity.this, 80)) showcaseDriftVx = -Math.abs(showcaseDriftVx);
                    if (nx < -dp(MainActivity.this, 80)) showcaseDriftVx = Math.abs(showcaseDriftVx);
                    if (ny > dp(MainActivity.this, 50)) showcaseDriftVy = -Math.abs(showcaseDriftVy);
                    if (ny < -dp(MainActivity.this, 50)) showcaseDriftVy = Math.abs(showcaseDriftVy);
                    showcaseWorld.setTranslationX(nx);
                    showcaseWorld.setTranslationY(ny);
                }
                if (mainHandler != null) mainHandler.postDelayed(this, 50);
            }
        };
        if (mainHandler != null) mainHandler.postDelayed(showcaseDriftTask, 3000);
    }

    void buildShowcaseBody(boolean animate) {
        if (showcaseBody == null) return;
        stopShowcaseDrift();
        showcaseWorld = null;
        showcaseBody.removeAllViews();
        java.util.List<ShowcaseItem> items = showcaseItems();
        if (items.isEmpty()) {
            TextView em = tv(this, "还没有自己的卡片。\n去全部卡片添加几张，或在我的卡片里加自定义卡，再回来开展柜。", 13.5f, showcaseOnBg2(), false);
            em.setGravity(Gravity.CENTER);
            em.setLineSpacing(dp(this, 3), 1f);
            showcaseBody.addView(em, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            return;
        }
        String mode = prefs == null ? "stack" : prefs.getString("showcase_mode", "stack");
        if ("canvas".equals(mode)) buildShowcaseCanvas(items); else buildShowcaseStack(items);
        if (animate) {
            showcaseBody.setAlpha(0f);
            showcaseBody.setTranslationX(dp(this, 14));
            showcaseBody.animate().alpha(1f).translationX(0f).setDuration(ANIM_DUR_FADE).setInterpolator(ANIM_ENTER).start();
        }
    }

    void buildShowcaseStack(java.util.List<ShowcaseItem> items) {
        ScrollView sv = new ScrollView(this);
        thinScrollbar(sv);
        sv.setClipToPadding(false);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(this, 16), dp(this, 6), dp(this, 16), dp(this, 28));
        sv.addView(col, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        showcaseBody.addView(sv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        boolean grp = prefs != null && prefs.getBoolean("showcase_bank_group", false);
        final boolean expanded = prefs != null && prefs.getBoolean("showcase_stack_open", false);
        // 展开/收起一颗小钮（堆叠只露顶带，展开近全卡）
        TextView tog = tv(this, expanded ? "收起堆叠" : "展开堆叠", 12.5f, showcaseOnBg(), true);
        tog.setGravity(Gravity.CENTER);
        tog.setBackground(roundRect(showcaseDarkBg() ? Color.argb(70, 255, 255, 255) : Color.rgb(0xE9, 0xEC, 0xF2), 999, this));
        tog.setPadding(dp(this, 12), dp(this, 7), dp(this, 12), dp(this, 7));
        LinearLayout togRow = new LinearLayout(this);
        togRow.setGravity(Gravity.RIGHT);
        togRow.addView(tog, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        col.addView(togRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        tog.setOnClickListener(v -> { haptic(); if (prefs != null) prefs.edit().putBoolean("showcase_stack_open", !expanded).apply(); buildShowcaseBody(true); });
        // 分组：按银行聚成多摞（银行名只作组标题，卡面本身仍零文字）；不分组则一摞到底
        java.util.LinkedHashMap<String, java.util.List<ShowcaseItem>> groups = new java.util.LinkedHashMap<>();
        for (ShowcaseItem it : items) {
            String k = grp ? (it.bank == null || it.bank.isEmpty() ? "其他" : it.bank) : "";
            java.util.List<ShowcaseItem> g = groups.get(k);
            if (g == null) { g = new ArrayList<>(); groups.put(k, g); }
            g.add(it);
        }
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int faceW = screenW - dp(this, 32);
        int faceH = Math.round(faceW / 1.586f);
        int strip = expanded ? Math.round(faceH * 0.88f) : dp(this, 52);
        for (java.util.Map.Entry<String, java.util.List<ShowcaseItem>> e : groups.entrySet()) {
            if (grp) {
                TextView gl = tv(this, e.getKey() + " · " + e.getValue().size() + " 张", 13, showcaseOnBg2(), true);
                LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                glp.topMargin = dp(this, 16); glp.bottomMargin = dp(this, 8);
                gl.setLayoutParams(glp);
                col.addView(gl);
            }
            final FrameLayout stack = new FrameLayout(this);
            stack.setClipChildren(false);
            int stackH = faceH + (e.getValue().size() - 1) * strip;
            LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, stackH);
            stLp.topMargin = dp(this, 8);
            stack.setLayoutParams(stLp);
            col.addView(stack);
            for (int i = 0; i < e.getValue().size(); i++) {
                final View face = buildShowcaseFace(e.getValue().get(i), faceW);
                FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(faceW, faceH);
                flp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
                flp.topMargin = i * strip;
                face.setLayoutParams(flp);
                stack.addView(face);
                face.setOnClickListener(v -> {
                    haptic();
                    stack.bringChildToFront(v);
                    v.animate().cancel();
                    v.setScaleX(1.03f); v.setScaleY(1.03f);
                    v.animate().scaleX(1f).scaleY(1f).setDuration(ANIM_DUR_FADE).setInterpolator(ANIM_ENTER).start();
                });
            }
        }
    }

    void buildShowcaseCanvas(java.util.List<ShowcaseItem> items) {
        final FrameLayout clip = new FrameLayout(this);
        clip.setClipChildren(true);
        showcaseBody.addView(clip, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        final FrameLayout world = new FrameLayout(this);
        world.setClipChildren(false);
        clip.addView(world, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        showcaseWorld = world;
        float dens = prefs == null ? 1f : prefs.getFloat("showcase_density", 1f);
        final int faceW = Math.round(dp(this, 190) * Math.max(0.6f, Math.min(1.3f, dens)));
        final int faceH = Math.round(faceW / 1.586f);
        final boolean grp = prefs != null && prefs.getBoolean("showcase_bank_group", false);
        final java.util.List<View> faces = new ArrayList<>();
        for (final ShowcaseItem it : items) {
            final View face = buildShowcaseFace(it, faceW);
            face.setLayoutParams(new FrameLayout.LayoutParams(faceW, faceH));
            world.addView(face);
            faces.add(face);
            face.setOnTouchListener(new View.OnTouchListener() {
                float downRawX, downRawY; int startL, startT; boolean moved;
                public boolean onTouch(View v, MotionEvent ev) {
                    showcaseLastTouchMs = System.currentTimeMillis();
                    FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) v.getLayoutParams();
                    switch (ev.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            moved = false; showcaseDragging = true;
                            downRawX = ev.getRawX(); downRawY = ev.getRawY();
                            startL = lp.leftMargin; startT = lp.topMargin;
                            if (v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).bringChildToFront(v);
                            return true;
                        case MotionEvent.ACTION_MOVE: {
                            float sc = Math.max(0.3f, world.getScaleX());
                            int nx = startL + Math.round((ev.getRawX() - downRawX) / sc);
                            int ny = startT + Math.round((ev.getRawY() - downRawY) / sc);
                            if (Math.abs(ev.getRawX() - downRawX) + Math.abs(ev.getRawY() - downRawY) > dp(MainActivity.this, 4)) moved = true;
                            int pw = world.getWidth() > 0 ? world.getWidth() : getResources().getDisplayMetrics().widthPixels;
                            int ph = world.getHeight() > 0 ? world.getHeight() : getResources().getDisplayMetrics().heightPixels;
                            lp.leftMargin = Math.max(-faceW / 2, Math.min(nx, pw - faceW / 2));
                            lp.topMargin = Math.max(-faceH / 2, Math.min(ny, ph - faceH / 2));
                            v.setLayoutParams(lp);
                            return true;
                        }
                        case MotionEvent.ACTION_UP:
                        case MotionEvent.ACTION_CANCEL:
                            showcaseDragging = false;
                            showcaseLastTouchMs = System.currentTimeMillis();
                            if (!moved) {
                                haptic();
                                v.animate().cancel();
                                v.setScaleX(1.04f); v.setScaleY(1.04f);
                                v.animate().scaleX(1f).scaleY(1f).setDuration(ANIM_DUR_FADE).setInterpolator(ANIM_ENTER).start();
                            } else if (showcasePosJson != null) {
                                try {
                                    showcasePosJson.put(it.key, lp.leftMargin + "," + lp.topMargin);
                                    if (prefs != null) prefs.edit().putString("showcase_positions", showcasePosJson.toString()).apply();
                                } catch (Throwable ignored) {}
                            }
                            return true;
                    }
                    return true;
                }
            });
        }
        // 空白处：拖动平移整画布 + 双指缩放（只动绘制层，不触发排版）
        final android.view.ScaleGestureDetector sgd = new android.view.ScaleGestureDetector(this,
            new android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override public boolean onScale(android.view.ScaleGestureDetector d) {
                    float s = Math.max(0.5f, Math.min(2.2f, world.getScaleX() * d.getScaleFactor()));
                    world.setPivotX(d.getFocusX()); world.setPivotY(d.getFocusY());
                    world.setScaleX(s); world.setScaleY(s);
                    return true;
                }
            });
        world.setOnTouchListener(new View.OnTouchListener() {
            float downRawX, downRawY, startTx, startTy;
            public boolean onTouch(View v, MotionEvent ev) {
                showcaseLastTouchMs = System.currentTimeMillis();
                sgd.onTouchEvent(ev);
                if (ev.getPointerCount() > 1) return true;
                switch (ev.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawX = ev.getRawX(); downRawY = ev.getRawY();
                        startTx = v.getTranslationX(); startTy = v.getTranslationY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        v.setTranslationX(startTx + (ev.getRawX() - downRawX));
                        v.setTranslationY(startTy + (ev.getRawY() - downRawY));
                        return true;
                }
                return true;
            }
        });
        // 首次排布：有本机记忆位置用记忆；分组时按银行成团簇排（不覆盖记忆，关掉分组回到记忆位）
        clip.post(() -> {
            int aw = clip.getWidth(), ah = clip.getHeight();
            if (aw <= 0 || ah <= 0) return;
            java.util.HashMap<String, Integer> bankIdx = new java.util.HashMap<>();
            java.util.HashMap<String, Integer> bankCnt = new java.util.HashMap<>();
            for (int i = 0; i < faces.size(); i++) {
                View f = faces.get(i);
                ShowcaseItem it = items.get(i);
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) f.getLayoutParams();
                int x, y;
                String saved = (showcasePosJson == null || grp) ? null : showcasePosJson.optString(it.key, null);
                if (saved != null && saved.contains(",")) {
                    try {
                        String[] parts = saved.split(",");
                        x = Integer.parseInt(parts[0].trim()); y = Integer.parseInt(parts[1].trim());
                    } catch (Throwable t) { x = -1; y = -1; }
                    if (x < -faceW / 2 || y < -faceH / 2) { x = -1; y = -1; }
                } else if (grp) {
                    Integer bi = bankIdx.get(it.bank);
                    if (bi == null) { bi = bankIdx.size(); bankIdx.put(it.bank, bi); }
                    Integer bc = bankCnt.get(it.bank);
                    int k = bc == null ? 0 : bc; bankCnt.put(it.bank, k + 1);
                    int colI = bi % 2, rowI = bi / 2;
                    x = colI * (aw / 2) + dp(MainActivity.this, 12) + (k % 2) * dp(MainActivity.this, 24);
                    y = rowI * dp(MainActivity.this, 190) + dp(MainActivity.this, 14) + k * dp(MainActivity.this, 34);
                } else {
                    x = Math.round(((i * 47) % 100) / 100f * Math.max(0, aw - faceW));
                    y = Math.round(((i * 29 + 13) % 100) / 100f * Math.max(0, ah - faceH));
                }
                lp.leftMargin = Math.max(-faceW / 2, Math.min(x, Math.max(-faceW / 2, aw - faceW / 2)));
                lp.topMargin = Math.max(-faceH / 2, Math.min(y, Math.max(-faceH / 2, ah - faceH / 2)));
                f.setLayoutParams(lp);
            }
        });
        showcaseLastTouchMs = System.currentTimeMillis();
        startShowcaseDrift();
    }

    void openExtendedSearch() {
        closeExtendedSearchNow();
        extQuery = "";
        ensureExtended();
        captureCurrentPageScroll();
        hideChrome();
        final FrameLayout sheet = new FrameLayout(this);
        View shade = new View(this);
        shade.setBackgroundColor(Color.argb(102, 0, 0, 0));
        shade.setOnClickListener(v -> closeExtendedSearch());
        sheet.addView(shade, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable cg = new GradientDrawable();
        cg.setColor(Color.rgb(0xFF, 0xFF, 0xFF));
        float rTop = dp(this, 22);
        cg.setCornerRadii(new float[]{rTop, rTop, rTop, rTop, 0, 0, 0, 0});
        card.setBackground(cg);
        if (Build.VERSION.SDK_INT >= 21) { card.setElevation(dp(this, 24)); topSheetClip(card, 22, this); }
        card.setOnClickListener(v -> {});
        card.setPadding(dp(this, 18), dp(this, 18), dp(this, 18), dp(this, 14) + navBarH());
        card.addView(tv(this, "在线搜卡 · 扩展卡库", 17, Color.rgb(0x1C, 0x1C, 0x1E), true));
        TextView hint = tv(this, "搜冷门卡、地方银行与合作社卡。扩展索引只存文字、随数据更新增补，加入后只存本机；无图卡先用占位面，规格空缺会如实标注。", 12.5f, Color.rgb(0x8E, 0x8E, 0x93), false);
        hint.setLineSpacing(dp(this, 2), 1f);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintLp.topMargin = dp(this, 6);
        card.addView(hint, hintLp);
        final EditText inQ = customInput("输入卡名 / 银行，如：村镇银行", "", 30);
        // customInput 自带 topMargin 6，在窗内再补一行距
        LinearLayout.LayoutParams inLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        inLp.topMargin = dp(this, 6);
        card.addView(inQ, inLp);
        extInput = inQ;
        extMeta = tv(this, "", 12, Color.rgb(0x8E, 0x8E, 0x93), false);
        LinearLayout.LayoutParams metaLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        metaLp.topMargin = dp(this, 8);
        card.addView(extMeta, metaLp);
        ScrollView resScroll = new ScrollView(this);
        thinScrollbar(resScroll);
        extResultBox = new LinearLayout(this);
        extResultBox.setOrientation(LinearLayout.VERTICAL);
        resScroll.addView(extResultBox, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.52);
        card.addView(resScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxH));
        LinearLayout acts = new LinearLayout(this);
        acts.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams actLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actLp.topMargin = dp(this, 14);
        card.addView(acts, actLp);
        Button refreshBtn = new Button(this);
        refreshBtn.setText("刷新索引"); refreshBtn.setTextSize(15); refreshBtn.setAllCaps(false);
        try { refreshBtn.setTypeface(weightTypeface(this, 600)); } catch (Throwable ignored) {}
        refreshBtn.setBackground(roundRect(Color.rgb(0xF2, 0xF3, 0xF7), 14, this));
        refreshBtn.setOnClickListener(v -> { haptic(); extFetchStarted = false; fetchExtendedUpdate(null); renderExtResults(); });
        acts.addView(refreshBtn, new LinearLayout.LayoutParams(0, dp(this, 48), 1f));
        Button closeBtn = new Button(this);
        closeBtn.setText("关闭"); closeBtn.setTextSize(15); closeBtn.setAllCaps(false); closeBtn.setTextColor(Color.WHITE);
        try { closeBtn.setTypeface(weightTypeface(this, 700)); } catch (Throwable ignored) {}
        GradientDrawable closeBg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{Color.rgb(0x0A, 0x84, 0xFF), Color.rgb(0x00, 0x66, 0xE6)});
        closeBg.setCornerRadius(dp(this, 14));
        closeBtn.setBackground(closeBg);
        closeBtn.setOnClickListener(v -> { haptic(); closeExtendedSearch(); });
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(0, dp(this, 48), 1.4f);
        closeLp.leftMargin = dp(this, 10);
        acts.addView(closeBtn, closeLp);
        inQ.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) { extQuery = s == null ? "" : s.toString(); renderExtResults(); }
            public void afterTextChanged(android.text.Editable s) {}
        });
        int sw = getResources().getDisplayMetrics().widthPixels;
        int maxCardH = (int) (getResources().getDisplayMetrics().heightPixels * 0.86);
        card.measure(View.MeasureSpec.makeMeasureSpec(sw - dp(this, 24), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(maxCardH, View.MeasureSpec.AT_MOST));
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.min(card.getMeasuredHeight(), maxCardH));
        clp.gravity = Gravity.BOTTOM;
        clp.leftMargin = dp(this, 12); clp.rightMargin = dp(this, 12); clp.bottomMargin = 0;
        FrameLayout wrap = new FrameLayout(this);
        View glass = glassLayer(card, 22, false);
        topSheetClip(glass, 22, this);
        wrap.addView(glass, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, clp.height + dp(this, 22)));
        wrap.addView(card, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        sheet.addView(wrap, clp);
        content.addView(sheet);
        extSheet = sheet;
        extClosing = false;
        sheet.setAlpha(0f);
        sheet.animate().alpha(1f).setDuration(ANIM_DUR_SHADE_IN).setInterpolator(ANIM_ENTER).start();
        wrap.setTranslationY(dp(this, 40)); wrap.setAlpha(0f);
        wrap.animate().translationY(0f).alpha(1f).setDuration(ANIM_DUR_SHEET_IN).setInterpolator(ANIM_ENTER).start();
        renderExtResults();
        // 进窗即拉一次新索引（有缓存先显缓存，拉到新版再刷新）；断网保持缓存并在空状态明示
        fetchExtendedUpdate(null);
        inQ.postDelayed(() -> { try { inQ.requestFocus(); } catch (Throwable ignored) {} }, 140);
    }

    void closeExtendedSearchNow() {
        View sheet = extSheet;
        if (sheet == null) return;
        extSheet = null; extResultBox = null; extMeta = null; extInput = null;
        if (sheet.getParent() != null) ((ViewGroup) sheet.getParent()).removeView(sheet);
    }

    void closeExtendedSearch() {
        final View sheet = extSheet;
        if (sheet == null || extClosing) return;
        extClosing = true;
        extSheet = null;
        hideKeyboardNow();
        if (sheet.getParent() != null) {
            View wrap = sheet instanceof ViewGroup && ((ViewGroup) sheet).getChildCount() > 1
                ? ((ViewGroup) sheet).getChildAt(((ViewGroup) sheet).getChildCount() - 1) : null;
            if (wrap != null) {
                wrap.animate().translationY(dp(this, 42)).alpha(0f).setDuration(ANIM_DUR_SHEET_OUT).setInterpolator(ANIM_EXIT)
                    .withEndAction(() -> {
                        if (sheet.getParent() != null) ((ViewGroup) sheet.getParent()).removeView(sheet);
                        extClosing = false; extResultBox = null; extMeta = null; extInput = null;
                        restoreChrome();
                    }).start();
                sheet.animate().alpha(0f).setDuration(ANIM_DUR_SHADE_OUT).setInterpolator(ANIM_EXIT).start();
                return;
            }
            ((ViewGroup) sheet.getParent()).removeView(sheet);
        }
        extClosing = false; extResultBox = null; extMeta = null; extInput = null;
        restoreChrome();
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
        ensureGlossary();
        fetchGlossaryUpdate();
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
        noScrollbar(sv); // Q49：资讯仅 6 条，滚动指示从简到近乎无，不挂长条
        sv.setClipToPadding(false);
        newsScroll = sv;
        if (Build.VERSION.SDK_INT >= 23) sv.setOnScrollChangeListener((v, sx, sy, ox, oy) -> { pageScrollSaveY.put("news", sy); updateTopFabVisibility(sy); });
        LinearLayout scrollContent = new LinearLayout(this);
        scrollContent.setOrientation(LinearLayout.VERTICAL);
        newsListBox = new LinearLayout(this);
        newsListBox.setOrientation(LinearLayout.VERTICAL);
        newsListBox.setPadding(0, dp(this, 2), 0, 0);
        scrollContent.addView(newsListBox);

        // Q66 卡片常识：与资讯同页，独立分区；结构对齐资讯白卡（分类 chip + 词条标题 + 可展开正文）
        LinearLayout gHead = new LinearLayout(this);
        gHead.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams ghp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ghp.topMargin = dp(this, 18);
        gHead.setLayoutParams(ghp);
        gHead.addView(tvW(this, "卡片常识", 18, Color.rgb(0x1C, 0x1C, 0x1E), 800));
        TextView gSub = tv(this, "账户分类、支付验证与费用等常见概念的简短说明", 12.5f, Color.rgb(0x8E, 0x8E, 0x93), false);
        LinearLayout.LayoutParams gSubLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gSubLp.topMargin = dp(this, 4);
        gHead.addView(gSub, gSubLp);
        glossaryMeta = tv(this, "", 11.5f, Color.rgb(0x8E, 0x8E, 0x93), false);
        LinearLayout.LayoutParams gmLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gmLp.topMargin = dp(this, 6);
        gHead.addView(glossaryMeta, gmLp);
        scrollContent.addView(gHead);
        glossaryBox = new LinearLayout(this);
        glossaryBox.setOrientation(LinearLayout.VERTICAL);
        glossaryBox.setPadding(0, 0, 0, dockPad());
        scrollContent.addView(glossaryBox);
        sv.addView(scrollContent);
        page.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        renderNews();
        renderGlossary();
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
        hideChrome(); // Q12
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
        cg.setColor(colSheet()); // Q54 实底口径 + Q72 深色浮层，玻璃退为纯垫底
        cg.setStroke(dp(this, 1), Color.argb(140, 255, 255, 255));
        // Q45：仅顶部圆角、底部直角——原四角同圆时底部两角把暗遮罩露成黑三角（用户 22:21 截图）
        float aboutR = dp(this, 22);
        cg.setCornerRadii(new float[]{aboutR, aboutR, aboutR, aboutR, 0, 0, 0, 0});
        card.setBackground(cg);
        if (Build.VERSION.SDK_INT >= 21) {
            card.setElevation(dp(this, 24));
            topSheetClip(card, 22, this); // Q45 顶圆底直轮廓
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
        clp.bottomMargin = 0; // Q45：窗底直达屏底（手势区靠窗内底部留白避让，沿 Q26 口径），不再悬空露角
        // Q45：玻璃与窗体装进同一贴底容器——玻璃高出窗体 22dp、底圆角沉到容器外被裁掉，
        // 与窗体同升同降；关窗时 closeAbout 取到的最后一层即此容器，动画口径不变
        FrameLayout aboutWrap = new FrameLayout(this);
        View aboutGlass = glassLayer(card, 22, false);
        topSheetClip(aboutGlass, 22, this); // Q54：玻璃轮廓与窗体同（顶圆底直），不得在窗外露面发雾
        aboutWrap.addView(aboutGlass, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, clp.height + dp(this, 22)));
        aboutWrap.addView(card, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        sheet.addView(aboutWrap, clp);
        content.addView(sheet);
        aboutSheet = sheet;
        sheet.setAlpha(0f);
        sheet.animate().alpha(1f).setDuration(ANIM_DUR_SHADE_IN).setInterpolator(ANIM_ENTER).start();
        aboutWrap.setTranslationY(dp(this, 42));
        aboutWrap.animate().translationY(0f).setDuration(ANIM_DUR_SHEET_IN)
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
                    .setDuration(ANIM_DUR_SHEET_OUT).setInterpolator(ANIM_EXIT)
                    .withEndAction(() -> {
                        if (sheet.getParent() != null) ((ViewGroup) sheet.getParent()).removeView(sheet);
                        restoreChrome(); // Q12
                    }).start();
                sheet.animate().alpha(0f).setDuration(ANIM_DUR_SHADE_OUT).setInterpolator(ANIM_EXIT).start();
                return;
            }
            ((ViewGroup) sheet.getParent()).removeView(sheet);
        }
        restoreChrome(); // Q12
    }

    LinearLayout buildAboutBody() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(this, 18), dp(this, 18), dp(this, 18), dp(this, 18) + navBarH()); // Q45：关于窗贴底后关闭钮靠底部留白避开手势条

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

        // Q46：赞助码换真码——整图圆角卡片（白底+柔影、四角同圆、等比完整不裁不抻），废虚线占位
        Bitmap qr = loadAssetBitmap("sponsor_alipay.jpg");
        if (qr != null) {
            FrameLayout qrCard = new FrameLayout(this);
            qrCard.setBackground(roundRect(Color.WHITE, 16, this));
            roundClip(qrCard, 16, this);
            if (Build.VERSION.SDK_INT >= 21) qrCard.setElevation(dp(this, 6));
            qrCard.setPadding(dp(this, 10), dp(this, 10), dp(this, 10), dp(this, 10));
            ImageView qrIv = new ImageView(this);
            qrIv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            qrIv.setAdjustViewBounds(true);
            float qrR = cardRadiusDp(240);
            qrIv.setImageBitmap(roundBitmap(qr, dp(this, qrR) * ((float) qr.getWidth() / dp(this, 240))));
            roundClip(qrIv, qrR, this);
            qrCard.addView(qrIv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(dp(this, 240), ViewGroup.LayoutParams.WRAP_CONTENT);
            qlp.topMargin = dp(this, 10); qlp.bottomMargin = dp(this, 12);
            qlp.gravity = Gravity.CENTER_HORIZONTAL;
            sponsor.addView(qrCard, qlp);
        } else {
            TextView ph = tv(this, "收款码加载失败", 12, Color.rgb(0x8E, 0x8E, 0x93), false);
            ph.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            qlp.topMargin = dp(this, 10); qlp.bottomMargin = dp(this, 12);
            sponsor.addView(ph, qlp);
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
                sponsor.animate().alpha(1f).translationY(0f).setDuration(ANIM_DUR_FADE)
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
            InputStream in = getAssets().open("sponsor_alipay.jpg");
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            data = bos.toByteArray();
            if (data.length == 0) throw new Exception("empty");
        } catch (Exception e) {
            showFloatToast("保存失败，请稍后再试");
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
                cv.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "cardbox-sponsor-qr.jpg");
                cv.put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
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
                File f = new File(dir, "cardbox-sponsor-qr.jpg");
                FileOutputStream out = new FileOutputStream(f);
                out.write(data); out.close();
                android.media.MediaScannerConnection.scanFile(this,
                    new String[]{f.getAbsolutePath()}, new String[]{"image/jpeg"}, null);
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
        if (navWrap != null) navWrap.setVisibility(View.GONE); // Q32: hide whole dock incl. glass layer - hiding navBar alone leaks a glass strip at screen bottom
        content.removeAllViews();
        content.addView(buildWelcomePage());
    }

    void closeWelcome() {
        prefs.edit().putBoolean("welcomed", true).apply();
        welcomeOpen = false;
        if (navWrap != null) navWrap.setVisibility(View.VISIBLE); // Q32
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
        if (navWrap != null) navWrap.setVisibility(View.GONE); // Q32: hide whole dock incl. glass layer - hiding navBar alone leaks a glass strip at screen bottom
        content.removeAllViews();
        content.addView(buildChangelogPage());
        syncTopFab();
    }

    void closeChangelog() {
        changelogOpen = false;
        if (navWrap != null) navWrap.setVisibility(View.VISIBLE); // Q32
        showTab(tab);
    }

    // Q50：更新日志底部动作行——对照混合版 styles.css .cl-actions（display:flex;gap:10px;justify-content:center）
    // 与 .cl-top（padding:9px 20px;border-radius:999px;background:#eef4fb;color:#0a5cd6;font-weight:700;font-size:.85rem）
    // 两钮居中并排药丸、紧贴日志框下沿成一组，不再整宽灰钮平摊；两处（独立页/设置页内嵌）共用此助手保证口径一致。
    LinearLayout changelogActions(final Runnable onTop, final Runnable onFold) {
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER);
        actions.setPadding(dp(this, 12), dp(this, 8), dp(this, 12), dp(this, 10));
        TextView top = tvW(this, "↑ 回到顶部", 13.5f, Color.rgb(0x0A, 0x5C, 0xD6), 700);
        top.setGravity(Gravity.CENTER);
        top.setBackground(rippleBg(Color.rgb(0xEE, 0xF4, 0xFB), 999));
        top.setPadding(dp(this, 20), dp(this, 9), dp(this, 20), dp(this, 9));
        top.setOnTouchListener((v, ev) -> {
            if (ev.getAction() == android.view.MotionEvent.ACTION_DOWN) pressBounce(v, true);
            else if (ev.getAction() == android.view.MotionEvent.ACTION_UP || ev.getAction() == android.view.MotionEvent.ACTION_CANCEL) pressBounce(v, false);
            return false;
        });
        top.setOnClickListener(v -> { haptic(); if (onTop != null) onTop.run(); });
        actions.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView fold = tvW(this, "收起日志", 13.5f, Color.rgb(0x0A, 0x5C, 0xD6), 700);
        fold.setGravity(Gravity.CENTER);
        fold.setBackground(rippleBg(Color.rgb(0xEE, 0xF4, 0xFB), 999));
        fold.setPadding(dp(this, 20), dp(this, 9), dp(this, 20), dp(this, 9));
        fold.setOnTouchListener((v, ev) -> {
            if (ev.getAction() == android.view.MotionEvent.ACTION_DOWN) pressBounce(v, true);
            else if (ev.getAction() == android.view.MotionEvent.ACTION_UP || ev.getAction() == android.view.MotionEvent.ACTION_CANCEL) pressBounce(v, false);
            return false;
        });
        fold.setOnClickListener(v -> { haptic(); if (onFold != null) onFold.run(); });
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        flp.leftMargin = dp(this, 10);
        actions.addView(fold, flp);
        return actions;
    }

    View buildChangelogPage() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(colBg()); // Q72

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
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(this, 16), dp(this, 6), dp(this, 16), dp(this, 16));
        changelogScroll.addView(page);
        if (Build.VERSION.SDK_INT >= 23) changelogScroll.setOnScrollChangeListener((v, sx, sy, ox, oy) -> updateTopFabVisibility(sy));
        // Q49：更新日志长列表可拖拽黑条（常显细条、按住拖快速拉动全文）
        FrameLayout logWrap = new FrameLayout(this);
        logWrap.addView(changelogScroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        attachDragBar(logWrap, changelogScroll, true, 8, 8);
        root.addView(logWrap, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

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

        LinearLayout actions = changelogActions(
            () -> { if (changelogScroll != null) changelogScroll.smoothScrollTo(0, 0); },
            () -> closeChangelog());
        // 独立页 dock 已藏，底部避让手势条一次算清，两钮完整可见不半埋
        actions.setPadding(actions.getPaddingLeft(), actions.getPaddingTop(), actions.getPaddingRight(), actions.getPaddingBottom() + navBarH());
        root.addView(actions);
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
        settingsLogScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        // Q50：框+钮一组——总高按 52vh 收，滚动区扣掉动作行高，两钮紧贴框底不被推出屏外半埋 dock
        int totalH = (int) (getResources().getDisplayMetrics().heightPixels * 0.52f);
        totalH = Math.max(dp(this, 280), Math.min(totalH, dp(this, 560)));
        int h = Math.max(dp(this, 200), totalH - dp(this, 56));
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
        // Q49：设置页内嵌日志框同备可拖拽黑条（常显、按住拖快速拉动）
        FrameLayout inlineLogWrap = new FrameLayout(this);
        inlineLogWrap.addView(settingsLogScroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        attachDragBar(inlineLogWrap, settingsLogScroll, true, 6, 6);
        box.addView(inlineLogWrap, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h));

        LinearLayout actions = changelogActions(
            () -> { if (settingsLogScroll != null) settingsLogScroll.smoothScrollTo(0, 0); },
            () -> { settingsLogOpen = false; rebuildPages(); });
        box.addView(actions);
        return box;
    }

    // ── Q58 ⋯ 菜单丝滑展开（只学机制自写：缩放原点贴按钮角、缩放+淡入同步、轻回弹、点外部反向收回）──
    class MoreDotsView extends View {
        MoreDotsView(Context c) { super(c); }
        @Override protected void onDraw(Canvas cv) {
            super.onDraw(cv);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.rgb(0x1C, 0x1C, 0x1E));
            float cx = getWidth() / 2f;
            float r = dp(getContext(), 2.1f);
            float gap = dp(getContext(), 6.2f);
            float cy = getHeight() / 2f;
            cv.drawCircle(cx, cy - gap, r, p);
            cv.drawCircle(cx, cy, r, p);
            cv.drawCircle(cx, cy + gap, r, p);
        }
    }

    View moreMenuRow(String label, final Runnable act) {
        TextView t = tv(this, label, 14.5f, Color.rgb(0x1C, 0x1C, 0x1E), false);
        t.setPadding(dp(this, 16), dp(this, 12), dp(this, 16), dp(this, 12));
        t.setBackground(rippleBg(Color.TRANSPARENT, 10)); // Q73: row sits on menu glass, no opaque white
        t.setClipToOutline(true);
        t.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) pressBounce(v, true);
            else if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL) pressBounce(v, false);
            return false;
        });
        t.setOnClickListener(v -> {
            haptic();
            closeMoreMenu();
            if (act != null) mainHandler.postDelayed(act, 150);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        t.setLayoutParams(lp);
        return t;
    }

    void openMoreMenu(View anchor) {
        if (moreMenuOverlay != null) { closeMoreMenu(); return; }
        if (anchor == null || content == null) return;
        haptic();
        final FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(Color.TRANSPARENT);
        overlay.setOnClickListener(v -> closeMoreMenu());
        // Q73: more-menu joins the same true-glass family (frozen blur + thin tint + wash),
        // was a solid white card. Rows keep their ripple over the glass.
        final FrameLayout cardWrap = new FrameLayout(this);
        glassClip(cardWrap, 16, false);
        cardWrap.setElevation(dp(this, 18));
        cardWrap.addView(glassLayer(cardWrap, 16, false), new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        cardWrap.addView(glassWashView(16, false), new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        final LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(glassTintDrawable(16, false));
        card.setPadding(dp(this, 6), dp(this, 6), dp(this, 6), dp(this, 6));
        card.addView(moreMenuRow("情景选卡", () -> openWizard()));
        card.addView(moreMenuRow("更新日志", () -> { settingsLogOpen = true; rebuildPages(); }));
        card.addView(moreMenuRow("欢迎页", () -> showWelcome()));
        card.addView(moreMenuRow("关于卡盒", () -> openAbout()));
        cardWrap.addView(card, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.setOnClickListener(v -> {});
        int menuW = dp(this, 196);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(menuW, ViewGroup.LayoutParams.WRAP_CONTENT);
        int[] al = new int[2]; anchor.getLocationOnScreen(al);
        int[] cl = new int[2]; content.getLocationOnScreen(cl);
        int anchorRight = al[0] - cl[0] + anchor.getWidth();
        int anchorTop = al[1] - cl[1];
        clp.leftMargin = Math.max(dp(this, 12), anchorRight - menuW);
        clp.topMargin = Math.max(pageTopPad(), anchorTop + anchor.getHeight() + dp(this, 6));
        overlay.addView(cardWrap, clp);
        content.addView(overlay, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        moreMenuOverlay = overlay;
        // 缩放原点贴按钮角（右上）：菜单像从 ⋯ 钮角上长出来；缩放与淡入同步、带轻回弹。
        cardWrap.setPivotX(menuW);
        cardWrap.setPivotY(0f);
        cardWrap.setScaleX(0.72f); cardWrap.setScaleY(0.72f); cardWrap.setAlpha(0f);
        cardWrap.animate().scaleX(1f).scaleY(1f).alpha(1f)
            .setDuration(ANIM_DUR_MENU_IN).setInterpolator(ANIM_MENU_SPRING).start();
    }

    void closeMoreMenu() {
        final View ov = moreMenuOverlay;
        if (ov == null) return;
        moreMenuOverlay = null;
        if (ov instanceof FrameLayout && ((FrameLayout) ov).getChildCount() > 0) {
            View card = ((FrameLayout) ov).getChildAt(0);
            card.animate().cancel();
            card.animate().scaleX(0.78f).scaleY(0.78f).alpha(0f)
                .setDuration(ANIM_DUR_MENU_OUT).setInterpolator(ANIM_EXIT)
                .withEndAction(() -> { if (ov.getParent() != null) ((ViewGroup) ov.getParent()).removeView(ov); })
                .start();
        } else if (ov.getParent() != null) {
            ((ViewGroup) ov.getParent()).removeView(ov);
        }
    }

    void dismissMoreMenuNow() {
        View ov = moreMenuOverlay;
        moreMenuOverlay = null;
        if (ov != null) {
            ov.animate().cancel();
            if (ov.getParent() != null) ((ViewGroup) ov.getParent()).removeView(ov);
        }
    }

    View buildSettingsPage() {
        LinearLayout page = basePage("设置");
        // Q58：标题行右上角 ⋯ 菜单钮（细线三点自绘，禁用 emoji；菜单从此钮角长出）
        try {
            if (page.getChildCount() > 0 && page.getChildAt(0) instanceof TextView) {
                TextView titleTv = (TextView) page.getChildAt(0);
                page.removeViewAt(0);
                LinearLayout titleRow = new LinearLayout(this);
                titleRow.setOrientation(LinearLayout.HORIZONTAL);
                titleRow.setGravity(Gravity.CENTER_VERTICAL);
                titleRow.addView(titleTv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                FrameLayout moreBtn = new FrameLayout(this);
                moreBtn.setBackground(rippleBg(Color.rgb(0xF2, 0xF3, 0xF7), 999));
                moreBtn.setClipToOutline(true);
                MoreDotsView dots = new MoreDotsView(this);
                moreBtn.addView(dots, new FrameLayout.LayoutParams(dp(this, 22), dp(this, 22), Gravity.CENTER));
                LinearLayout.LayoutParams mbLp = new LinearLayout.LayoutParams(dp(this, 40), dp(this, 40));
                titleRow.addView(moreBtn, mbLp);
                moreBtn.setOnTouchListener((v, e) -> {
                    if (e.getAction() == MotionEvent.ACTION_DOWN) pressBounce(v, true);
                    else if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL) pressBounce(v, false);
                    return false;
                });
                moreBtn.setOnClickListener(v -> openMoreMenu(v));
                page.addView(titleRow, 0);
            }
        } catch (Throwable ignored) {}
        View wizEntry = settingRow("情景选卡", "出国留学 / 出境旅游 / 海淘网购 / 日常使用，按场景挑卡 ›");
        wizEntry.setOnClickListener(v -> { haptic(); openWizard(); });
        page.addView(wizEntry);

        // 功能启用区（2026-10-04 06:33 钉版）：可选模块逐项登记在此，关掉入口与界面彻底不出现、不占位
        sectionHead(page, "功能启用");
        switchRow(page, "展柜", "我的卡片页的纯卡面展示（堆叠 / 平放自由画布）", prefs == null || prefs.getBoolean("showcase_enabled", true), on -> {
            if (prefs != null) prefs.edit().putBoolean("showcase_enabled", on).apply(); haptic(); rebuildPages();
        });
        switchRow(page, "保号管家", "电话卡 / eSIM 保号到期管理，关掉后入口不出现", prefs == null || prefs.getBoolean("simkeep_enabled", true), on -> {
            if (prefs != null) prefs.edit().putBoolean("simkeep_enabled", on).apply(); haptic(); rebuildPages();
        });

        // Q72 外观分区：深色模式/主题色/卡面配色三件事各管各、互不染指（卡面配色只管无图占位底色）
        sectionHead(page, "外观");
        segRow(page, "深色模式", new String[][]{{"system","跟随系统"},{"light","浅色"},{"dark","深色"}}, darkModePref, v -> {
            darkModePref = v; prefs.edit().putString("dark_mode", v).apply(); haptic(); applyAppearanceChrome(); rebuildPages();
        });
        themeColorRow(page);
        segRow(page, "卡面配色", new String[][]{{"light","浅色柔光"},{"dark","深色沉稳"}}, placeholderStyle, v -> {
            placeholderStyle = v; prefs.edit().putString("placeholder_style", v).apply(); haptic(); rebuildPages();
        });
        switchRow(page, "自选卡面配色", "开启后在无图卡详情里逐张换颜色；关闭用自动配色", placeholderCustomEnabled, on -> {
            placeholderCustomEnabled = on; prefs.edit().putBoolean("placeholder_custom_enabled", on).apply(); haptic(); rebuildPages();
        });

        sectionHead(page, "显示");
        segRow(page, "字体", new String[][]{{"builtin","软件字体"},{"system","系统字体"},{"custom","自定义"}}, fontMode, v -> {
            if ("custom".equals(v) && !hasCustomFont(this)) { haptic(); showFloatToast("先导入一个字体文件再用自定义"); openFontPicker(); return; }
            fontMode = v; prefs.edit().putString("font_mode", v).apply(); haptic(); rebuildPages();
        });
        View fontImpRow = settingRow("导入字体文件", hasCustomFont(this)
            ? ("已导入：" + (customFontName == null || customFontName.length() == 0 ? "自定义字体" : customFontName) + " · 点此更换 ›")
            : "选择 .ttf / .otf 字体文件 ›");
        fontImpRow.setOnClickListener(v -> { haptic(); openFontPicker(); });
        page.addView(fontImpRow);
        if (hasCustomFont(this)) {
            View fontDelRow = settingRow("删除自定义字体", "删掉后回到软件字体 ›");
            fontDelRow.setOnClickListener(v -> { deleteCustomFont(); });
            page.addView(fontDelRow);
        }
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
        int pendVer = prefs == null ? -1 : prefs.getInt("pending_update_version", -1); if (pendVer <= Store.dataVersion) pendVer = -1; else if (pendingUpdateVer > Store.dataVersion) pendVer = pendingUpdateVer;
        String updSub = pendVer > 0 ? ("v" + Store.dataVersion + " · " + Store.all.size() + " 张卡 · 有新版 v" + pendVer + " 可更新 ›") : ("v" + Store.dataVersion + " · " + Store.all.size() + " 张卡 · 点此直接检查更新 ›");
        View updRow = settingRow("数据更新", updSub);
        if (pendVer > 0) { try { TextView ut = (TextView)((ViewGroup)updRow).getChildAt(0); android.text.SpannableStringBuilder ssb = new android.text.SpannableStringBuilder("数据更新  ●"); ssb.setSpan(new android.text.style.ForegroundColorSpan(Color.rgb(0xE0,0x31,0x31)), 5, 6, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE); ssb.setSpan(new android.text.style.RelativeSizeSpan(0.7f), 5, 6, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE); ut.setText(ssb); } catch(Throwable ignored){} }
        final int pendFinal = pendVer;
        updRow.setOnClickListener(v -> { haptic(); if (pendFinal > 0 && pendingUpdateJson != null) showUpdateConfirm(); else { showFloatToast("正在检查数据更新…"); checkDataUpdate(true); } });
        page.addView(updRow);
        switchRow(page, "启动时自动检测更新", "开启只检测并提示，不自动应用；关闭则仅手动检查", prefs == null || prefs.getBoolean("auto_check_update", true), on -> { if(prefs!=null) prefs.edit().putBoolean("auto_check_update", on).apply(); haptic(); rebuildPages(); });

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
        // Q39: .set-cap .76rem/600/.02em；.set-t .98rem/600、.set-d .76rem 行高1.4
        TextView t = tvW(this, s, 12, colText2(), 600);
        t.setLetterSpacing(0.02f);
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

    // Q72 主题色：五枚预置强调色圆点，选中外环为当前强调色；只染界面强调，不碰卡面/占位
    void themeColorRow(LinearLayout page) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(roundRect(colSurface(), 12, this));
        box.setPadding(dp(this, 14), dp(this, 10), dp(this, 14), dp(this, 12));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(this, 8);
        box.setLayoutParams(blp);
        box.addView(tv(this, "主题色", 14, colText(), true));
        box.addView(tv(this, "只改选中态、开关、链接与强调色，不改卡面颜色", 11.5f, colText2(), false));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(this, 10);
        box.addView(row, rlp);
        // swatch colors shown are the light-mode accents (dark variants derive from accentColor())
        final int[] sw = {Color.rgb(0x0A,0x5C,0xD6), Color.rgb(0x0E,0x7C,0x7B), Color.rgb(0x6C,0x4B,0xD8), Color.rgb(0x1D,0x8A,0x49), Color.rgb(0xC7,0x5A,0x00)};
        for (int i = 0; i < THEME_OPTS.length; i++) {
            final String key = THEME_OPTS[i][0];
            final boolean on = key.equals(themeColorKey);
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            FrameLayout dotWrap = new FrameLayout(this);
            View dot = new View(this);
            GradientDrawable dg = new GradientDrawable();
            dg.setShape(GradientDrawable.OVAL);
            dg.setColor(sw[i]);
            if (on) dg.setStroke(dp(this, 2), accentColor());
            dot.setBackground(dg);
            dotWrap.addView(dot, new FrameLayout.LayoutParams(dp(this, 30), dp(this, 30), Gravity.CENTER));
            if (on) {
                GradientDrawable ring = new GradientDrawable();
                ring.setShape(GradientDrawable.OVAL);
                ring.setColor(Color.TRANSPARENT);
                ring.setStroke(dp(this, 2), accentColor());
                FrameLayout ringV = new FrameLayout(this);
                ringV.setBackground(ring);
                dotWrap.addView(ringV, new FrameLayout.LayoutParams(dp(this, 38), dp(this, 38), Gravity.CENTER));
            }
            cell.addView(dotWrap, new LinearLayout.LayoutParams(dp(this, 38), dp(this, 38)));
            TextView nm = tv(this, THEME_OPTS[i][1], 11, on ? accentColor() : colText2(), on);
            nm.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            nlp.topMargin = dp(this, 3);
            cell.addView(nm, nlp);
            LinearLayout.LayoutParams clp2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            row.addView(cell, clp2);
            cell.setOnClickListener(v -> {
                themeColorKey = key; prefs.edit().putString("theme_color", key).apply(); haptic(); rebuildPages();
            });
        }
        page.addView(box);
    }

    // 三档单选行：白卡里横排，选中蓝底（与筛选面板 chipRow 同风格）
    void segRow(LinearLayout page, String label, String[][] opts, String cur, final SegPick pick) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(roundRect(colSurface(), 12, this));
        box.setPadding(dp(this, 14), dp(this, 10), dp(this, 14), dp(this, 12));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(this, 8);
        box.setLayoutParams(blp);
        box.addView(tv(this, label, 14, colText(), true));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(this, 9);
        box.addView(row, rlp);
        for (final String[] o : opts) {
            final boolean on = segOn(o[0], cur);
            TextView t = tv(this, o[1], 12.5f, on ? Color.WHITE : colText(), on);
            t.setGravity(Gravity.CENTER);
            t.setBackground(roundRect(on ? accentColor() : colChipOff(), 9, this));
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
        row.setBackground(rippleBg(colSurface(), 12));
        row.setClipToOutline(true);
        row.setPadding(dp(this, 14), dp(this, 10), dp(this, 12), dp(this, 10));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(this, 8);
        row.setLayoutParams(rlp);
        LinearLayout txt = new LinearLayout(this);
        txt.setOrientation(LinearLayout.VERTICAL);
        txt.addView(tv(this, label, 14, colText(), true));
        txt.addView(tv(this, desc, 11.5f, colText2(), false));
        row.addView(txt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView sw = tv(this, on ? "开" : "关", 12.5f, on ? Color.WHITE : colText2(), true);
        sw.setGravity(Gravity.CENTER);
        sw.setBackground(roundRect(on ? accentColor() : colChipOff(), 999, this));
        sw.setPadding(dp(this, 16), dp(this, 7), dp(this, 16), dp(this, 7));
        row.addView(sw);
        row.setOnClickListener(v -> set.onSet(!on));
        page.addView(row);
    }

    View settingRow(String k, String v) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setBackground(rippleBg(colSurface(), 12));
        row.setClipToOutline(true);
        row.setPadding(dp(this, 14), dp(this, 10), dp(this, 14), dp(this, 10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(this, 8);
        row.setLayoutParams(lp);
        row.addView(tvW(this, k, 15, colText(), 600));
        TextView sv2 = tv(this, v, 12, colText2(), false); bodyLH(sv2);
        row.addView(sv2);
        return row;
    }

    LinearLayout basePage(String title) {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(this, 14), pageTopPad(), dp(this, 14), 0);
        // Q39: .page-title 1.4rem/800/.02em、行高收紧
        TextView pt = tvW(this, title, 24, colText(), 800);
        pt.setLetterSpacing(0.02f); pt.setLineSpacing(0, 1.15f);
        page.addView(pt);
        return page;
    }

    @Override
    public void onBackPressed() {
        if (acctPickerView != null) { closeAcctClassPicker(); return; }
        if (simkeepFormSheet != null) { closeSimKeepForm(); return; }
        if (simkeepView != null) { closeSimKeep(); return; }
        if (showcaseView != null) { closeShowcase(); return; }
        if (moreMenuOverlay != null) { closeMoreMenu(); return; }
        if (floatSearchOpen) { closeFloatSearch(); return; }
        if (cardMenuPop != null) { closeCardMenu(); return; }
        if (aboutOpen) { closeAbout(); return; }
        if (welcomeOpen) { closeWelcome(); return; }
        if (changelogOpen) { closeChangelog(); return; }
        if (settingsLogOpen && "settings".equals(tab)) { settingsLogOpen = false; rebuildPages(); return; }
        if (placeholderPickerView != null) { closePlaceholderPicker(); return; }
        if (customDetailSheet != null) { closeCustomDetail(); return; }
        if (detailCard != null) { closeDetail(); return; }
        if (filterSheet != null) { closeFilterSheet(); return; }
        if (extSheet != null) { closeExtendedSearch(); return; }
        if (binSheet != null) { closeBinQuery(); return; }
        if (delConfirmSheet != null) { closeDelConfirm(); return; }
        if (updateConfirmSheet != null) { closeUpdateSheet(updateConfirmSheet); return; }
        if (updateTipSheet != null) { closeUpdateSheet(updateTipSheet); return; }
        if (addSheetView != null) { closeAddSheet(addSheetView); return; }
        if (customFormSheet != null) { closeCustomForm(); return; }
        if (wizardOpen) {
            if (wizSc == null) closeWizard(); else wizGoBack();
            return;
        }
        super.onBackPressed();
    }
}
