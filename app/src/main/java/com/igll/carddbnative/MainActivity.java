package com.igll.carddbnative;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.LruCache;
import android.view.Gravity;
import android.view.animation.DecelerateInterpolator;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
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

    // ---------- 小工具 ----------
    static int dp(Context c, float v) { return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f); }

    // 沉浸式状态栏高度（各页顶部留白与首页悬浮栏定位用）
    int statusBarH() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : dp(this, 24);
    }
    // P2d-fix：带大标题页面的顶部安全留白 = 状态栏高度 + 舒适间距，标题文字绝不进状态栏
    int pageTopPad() { return statusBarH() + dp(this, 16); }
    // P2d-fix：长页面底部安全留白，确保末行能完整滚出悬浮 dock 之外（dock 高约 67dp+底边距 12dp）
    int dockPad() { return dp(this, 112); }
    static GradientDrawable roundRect(int color, float radiusDp, Context c) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color); g.setCornerRadius(dp(c, radiusDp));
        return g;
    }
    // ---------- 显示偏好（Phase 4a：字体三档/界面大小/高刷/触感） ----------
    static String fontMode = "default"; // default=软件默认栈 / system=本机 / serif=内置宋体
    static float uiScale = 1f;          // 界面大小：0.9 紧凑 / 1 标准 / 1.12 大号（作用于 sp）
    static boolean hapticOn = true;
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
        } catch (Exception e) { customCards = new ArrayList<>(); }
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
            prefs.edit().putString("custom_cards", arr.toString()).apply();
        } catch (Exception e) { /* 存不下就保持内存中的列表 */ }
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

    // 情景选卡状态（Phase 3a，对照 app.js 的 wiz 全局状态）
    boolean wizardOpen = false;
    boolean detailFromWiz = false;
    String wizSc = null;
    int wizStep = 0;
    Map<String, String> wizA = new HashMap<>();

    // 欢迎页 / 更新日志（Phase 4b，对照 app.js showWelcome/renderChangelog）
    boolean welcomeOpen = false;
    boolean changelogOpen = false;
    ScrollView changelogScroll = null;

    FrameLayout content;
    LinearLayout navBar;
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
    // P2 悬浮搜索圆钮（首页右下，点了回顶聚焦顶部搜索框）
    View searchFab = null;
    // P-searchfix：首页悬浮搜索栏本体与显隐状态（滚动时收起/失焦，不再赖在视角上）
    View homeSearchBar = null;
    boolean homeSearchBarShown = true;
    int lastHomeScrollY = 0;
    // P-press 长按放大预览：快照浮层与源视图（松手即收起）
    View pressPreview = null;
    View pressPreviewSrc = null;

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
        w.setStatusBarColor(Color.TRANSPARENT);
        w.getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        prefs = getSharedPreferences("cardbox_native", MODE_PRIVATE);
        fontMode = prefs.getString("font_mode", "default");
        uiScale = prefs.getFloat("ui_scale", 1f);
        if (uiScale != 0.9f && uiScale != 1f && uiScale != 1.12f) uiScale = 1f;
        hapticOn = prefs.getBoolean("haptic", true);
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
        root.setBackgroundColor(Color.rgb(0xF2, 0xF3, 0xF7));

        content = new FrameLayout(this);
        // P1b 浮感修正：内容区不再留底部硬白边，各页滚动视图全高延伸到悬浮条底下，
        // 滚动时内容从半透明条下隐约滑过；最后一项靠各页内衬的底部留白滚出条外。
        content.setPadding(0, 0, 0, 0);
        content.setClipToPadding(false);
        root.addView(content, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        buildNav(root);
        setContentView(root);

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

    void haptic() {
        if (!hapticOn) return;
        try {
            Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (v == null) return;
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(15, VibrationEffect.DEFAULT_AMPLITUDE));
            else v.vibrate(15);
        } catch (Exception e) { /* 无振动器静默 */ }
    }

    // 字体/界面大小变化后整页重建（各页都是缓存 View，必须重造才生效）
    void rebuildPages() {
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
            p.setColor(on ? Color.rgb(0x1C, 0x1C, 0x1E) : Color.rgb(0x63, 0x63, 0x66));
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
        // P1c：贴混合版 .dock-glass——带极淡蓝灰的半透白（rgba .58 量级，内容滚过能透出）、
        // 顶部高光（三段渐变首段提亮模拟 inset 0 1px 高光）、白色半透描边
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{Color.argb(172, 255, 255, 255), Color.argb(150, 247, 250, 255),
                Color.argb(138, 228, 236, 246)});
        g.setCornerRadius(dp(this, 26));
        g.setStroke(dp(this, 1), Color.argb(140, 255, 255, 255));
        return g;
    }

    Drawable navPillBg() {
        // P1c：选中胶囊贴混合版 .dock-pill——半透白（.68 量级）+ 顶部高光渐变 + 白色高光描边
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{Color.argb(198, 255, 255, 255), Color.argb(173, 244, 248, 253),
                Color.argb(164, 234, 241, 250)});
        g.setCornerRadius(dp(this, 18));
        g.setStroke(dp(this, 1), Color.argb(160, 255, 255, 255));
        return g;
    }

    void buildNav(FrameLayout root) {
        navWrap = new FrameLayout(this);
        FrameLayout.LayoutParams wrapLp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wrapLp.gravity = Gravity.BOTTOM;
        wrapLp.leftMargin = dp(this, 12);
        wrapLp.rightMargin = dp(this, 12);
        wrapLp.bottomMargin = dp(this, 12);
        navWrap.setLayoutParams(wrapLp);

        navBar = new LinearLayout(this);
        navBar.setOrientation(LinearLayout.HORIZONTAL);
        navBar.setBackground(floatingBarBg());
        navBar.setPadding(dp(this, 8), dp(this, 8), dp(this, 8), dp(this, 8));
        if (Build.VERSION.SDK_INT >= 21) navBar.setElevation(dp(this, 24));
        if (Build.VERSION.SDK_INT >= 28) {
            // 大扩散柔影：阴影色压到混合版 rgba(20,30,60,.14) 量级，不让默认黑影发死
            navBar.setOutlineAmbientShadowColor(Color.argb(36, 20, 30, 60));
            navBar.setOutlineSpotShadowColor(Color.argb(36, 20, 30, 60));
        }
        navBar.setClipToOutline(false);
        String[][] tabs = {
            {"home", "全部卡片"}, {"student", "学生推荐"}, {"mine", "我的卡片"}, {"news", "资讯"}, {"settings", "设置"}
        };
        for (String[] t : tabs) {
            final String key = t[0];
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER_HORIZONTAL);
            item.setPadding(dp(this, 4), dp(this, 6), dp(this, 4), dp(this, 6));
            NavIconView icon = new NavIconView(this, key);
            item.addView(icon, new LinearLayout.LayoutParams(dp(this, 23), dp(this, 23)));
            TextView label = tv(this, t[1], 10f, Color.rgb(0x8E, 0x8E, 0x93), false);
            label.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            llp.topMargin = dp(this, 2);
            item.addView(label, llp);
            item.setOnClickListener(v -> { haptic(); showTab(key); });
            navItems.put(key, item);
            navIcons.put(key, icon);
            navLabels.put(key, label);
            navBar.addView(item, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        navWrap.addView(navBar, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(navWrap);
    }

    void showTab(String key) {
        dismissPressPreview();
        tab = key;
        content.removeAllViews();
        View page = pages.get(key);
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
        content.addView(page);
        if ("home".equals(key) && homeList != null) refreshHome();
        syncSearchFab();
        for (Map.Entry<String, LinearLayout> e : navItems.entrySet()) {
            boolean on = e.getKey().equals(key);
            e.getValue().setBackground(on ? navPillBg() : null);
            if (Build.VERSION.SDK_INT >= 21) e.getValue().setElevation(on ? dp(this, 3) : 0);
            if (Build.VERSION.SDK_INT >= 28) {
                e.getValue().setOutlineAmbientShadowColor(Color.argb(26, 20, 30, 60));
                e.getValue().setOutlineSpotShadowColor(Color.argb(26, 20, 30, 60));
            }
            NavIconView ic = navIcons.get(e.getKey());
            if (ic != null) ic.setOn(on);
            TextView lb = navLabels.get(e.getKey());
            if (lb != null) {
                lb.setTextColor(on ? Color.rgb(0x1C, 0x1C, 0x1E) : Color.rgb(0x8E, 0x8E, 0x93));
                android.graphics.Typeface cur = lb.getTypeface();
                if (cur != null) lb.setTypeface(cur, on ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            }
        }
    }

    // ---------- P2 悬浮搜索圆钮 ----------
    // 细线放大镜（Canvas 线条，与导航图标同语言，禁用 emoji）
    class SearchIconView extends View {
        int iconColor = Color.WHITE;
        SearchIconView(Context c) { super(c); }
        @Override protected void onDraw(Canvas cv) {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeWidth(dp(getContext(), 2.1f));
            p.setColor(iconColor);
            float w = getWidth(), h = getHeight();
            float sx = w / 24f, sy = h / 24f;
            cv.drawCircle(10.8f * sx, 10.8f * sy, 5.6f * sx, p);
            cv.drawLine(15.2f * sx, 15.2f * sy, 20.5f * sx, 20.5f * sy, p);
        }
    }

    // 只在首页、且没有整屏覆盖层时出现；覆盖层（详情/向导/欢迎/日志）都会
    // content.removeAllViews()，天然把它清掉，回到首页时 showTab 会再挂回来。
    void syncSearchFab() {
        boolean want = "home".equals(tab) && detailCard == null && !wizardOpen && !welcomeOpen && !changelogOpen && filterSheet == null; // P2e：筛选窗开着时搜索钮退场
        if (!want) {
            if (searchFab != null && searchFab.getParent() != null)
                ((ViewGroup) searchFab.getParent()).removeView(searchFab);
            searchFab = null;
            return;
        }
        if (searchFab != null && searchFab.getParent() == content) return;
        searchFab = buildSearchFab();
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(this, 54), dp(this, 54));
        lp.gravity = Gravity.END | Gravity.BOTTOM;
        lp.rightMargin = dp(this, 16);
        lp.bottomMargin = dp(this, 96); // 悬在底栏 dock 之上
        content.addView(searchFab, lp);
        searchFab.setAlpha(0f);
        searchFab.setScaleX(0.8f);
        searchFab.setScaleY(0.8f);
        searchFab.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(160).start();
    }

    View buildSearchFab() {
        FrameLayout fab = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
            new int[]{Color.rgb(0x0A, 0x6E, 0xD6), Color.rgb(0x0A, 0x5C, 0xD6)});
        bg.setShape(GradientDrawable.OVAL);
        bg.setStroke(dp(this, 1), Color.argb(60, 255, 255, 255));
        fab.setBackground(bg);
        if (Build.VERSION.SDK_INT >= 21) fab.setElevation(dp(this, 12));
        SearchIconView icon = new SearchIconView(this);
        int pad = dp(this, 13);
        icon.setPadding(pad, pad, pad, pad);
        fab.addView(icon, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        fab.setOnClickListener(v -> {
            haptic();
            focusSearch();
        });
        fab.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(90).start();
            else if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL)
                v.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
            return false;
        });
        return fab;
    }

    // P-searchfix：收起搜索——清焦点 + 收键盘（点框外、滚动列表时调用）
    void dismissSearch() {
        if (searchBox == null) return;
        if (searchBox.hasFocus()) searchBox.clearFocus();
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(searchBox.getWindowToken(), 0);
        } catch (Exception e) { /* 静默 */ }
    }

    // P-searchfix：悬浮搜索栏显隐（减速曲线，上滑隐藏、回顶/上滑显现，不再跟着视角赖住）
    void setHomeSearchBarShown(boolean show, boolean animate) {
        if (homeSearchBar == null || homeSearchBarShown == show) return;
        homeSearchBarShown = show;
        float ty = show ? 0f : -(statusBarH() + dp(this, 76));
        if (animate) {
            homeSearchBar.animate().translationY(ty).alpha(show ? 1f : 0f)
                .setDuration(show ? 240 : 200)
                .setInterpolator(new DecelerateInterpolator()).start();
        } else {
            homeSearchBar.setTranslationY(ty);
            homeSearchBar.setAlpha(show ? 1f : 0f);
        }
        homeSearchBar.setClickable(show);
        homeSearchBar.setFocusable(show);
    }

    // P-searchfix ③：点搜索栏以外任意区域收起搜索（全局分发，不依赖某个子视图是否消费触摸）
    @Override public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev != null && ev.getAction() == MotionEvent.ACTION_DOWN
            && searchBox != null && searchBox.hasFocus() && homeSearchBar != null && homeSearchBarShown) {
            int[] loc = new int[2];
            homeSearchBar.getLocationOnScreen(loc);
            float x = ev.getRawX(), y = ev.getRawY();
            boolean inside = x >= loc[0] && x <= loc[0] + homeSearchBar.getWidth()
                && y >= loc[1] && y <= loc[1] + homeSearchBar.getHeight();
            if (!inside) dismissSearch();
        }
        return super.dispatchTouchEvent(ev);
    }

    void focusSearch() {
        // P-searchfix：先让搜索栏显现，再平滑滚回顶，等滚动落稳后才聚焦弹键盘，避免瞬间弹飞的硬切
        setHomeSearchBarShown(true, true);
        long scrollDur = 0;
        if (homeScroll != null && homeScroll.getScrollY() > 0) {
            int from = homeScroll.getScrollY();
            scrollDur = Math.min(420, 200 + from / 6);
            ValueAnimator va = ValueAnimator.ofInt(from, 0);
            va.setDuration(scrollDur);
            va.setInterpolator(new DecelerateInterpolator());
            va.addUpdateListener(a -> { if (homeScroll != null) homeScroll.scrollTo(0, (int) a.getAnimatedValue()); });
            va.start();
        }
        if (searchBox == null) return;
        searchBox.postDelayed(() -> {
            if (searchBox == null) return;
            searchBox.requestFocus();
            try {
                InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(searchBox, InputMethodManager.SHOW_IMPLICIT);
            } catch (Exception e) { /* 无输入法静默 */ }
        }, scrollDur + 60);
    }

    // ---------- 通用：卡片瓷砖 ----------
    View cardTile(final Card c, ViewGroup parent) {
        return cardTile(c, parent, cols);
    }

    // P-grid：瓷砖规格统一——图区按 1.586 卡面比例定高（同列同宽同高）、卡名预留两行、行内等高拉伸，底边齐平
    View cardTile(final Card c, ViewGroup parent, int nCols) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(roundRect(Color.WHITE, 14, this));
        box.setPadding(dp(this, 8), dp(this, 8), dp(this, 8), dp(this, 10));
        AbsListView.LayoutParams lp = new AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        box.setLayoutParams(lp);

        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setBackground(roundRect(Color.rgb(0xE9, 0xEE, 0xF5), 9, this));
        int nc = (nCols == 1 || nCols == 3) ? nCols : 2;
        int availW = getResources().getDisplayMetrics().widthPixels - dp(this, 28) - (nc - 1) * dp(this, 10);
        int innerW = availW / nc - dp(this, 16);
        int imgH = Math.max(dp(this, 40), Math.round(innerW / 1.586f));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, imgH);
        box.addView(iv, ilp);
        Bitmap b = Img.get(this, c.image);
        if (b != null) iv.setImageBitmap(b); else iv.setImageBitmap(null);

        TextView name = tv(this, c.name, 13, Color.rgb(0x1C, 0x1C, 0x1E), true);
        name.setMaxLines(2);
        name.setMinLines(2);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = dp(this, 7);
        box.addView(name, nlp);

        TextView sub = tv(this, c.bank + " · " + orgLabel(c.org), 10.5f, Color.rgb(0x8E, 0x8E, 0x93), false);
        sub.setMaxLines(1);
        sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        box.addView(sub);

        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(this, 6);
        box.addView(chips, clp);
        chips.addView(chip(String.format(java.util.Locale.US, "%.1f分", c.score), Color.rgb(0xE8, 0xF1, 0xFD), Color.rgb(0x0A, 0x5C, 0xD6)));
        chips.addView(chip("已停发".equals(c.status) ? "已停发" : "在发",
            "已停发".equals(c.status) ? Color.rgb(0xF3, 0xE8, 0xE8) : Color.rgb(0xE6, 0xF6, 0xEC),
            "已停发".equals(c.status) ? Color.rgb(0xB0, 0x23, 0x2B) : Color.rgb(0x1D, 0x8A, 0x49)));
        if (mine.contains(c.id)) chips.addView(chip("已添加", Color.rgb(0xE6, 0xF6, 0xEC), Color.rgb(0x1D, 0x8A, 0x49)));
        // P-press 长按放大预览（我的卡片页会覆盖此长按为拖动排序，语义不冲突）
        box.setOnLongClickListener(v -> { showPressPreview(box); return true; });
        return box;
    }

    // P-press：长按放大预览——拍源视图像素快照做浮层，按当场实测宽高等比放大并居中夹在屏内，
    // 1/2/3 列都取实测尺寸，不复制子视图，也就不会丢行高/字号（混合版克隆栽过的跟头）。
    // 松手（UP/CANCEL）即收；切页/开详情时也会先收，避免浮层残留。
    void showPressPreview(final View src) {
        if (src == null || pressPreview != null) return;
        final int w = src.getWidth(), h = src.getHeight();
        if (w <= 0 || h <= 0) return;
        Bitmap snap;
        try {
            snap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            src.draw(new Canvas(snap));
        } catch (Exception e) { return; }
        ViewGroup rootVg = (ViewGroup) findViewById(android.R.id.content);
        if (rootVg == null || rootVg.getWidth() <= 0 || rootVg.getHeight() <= 0) return;
        int[] rl = new int[2]; rootVg.getLocationOnScreen(rl);
        int[] sl = new int[2]; src.getLocationOnScreen(sl);
        float left = sl[0] - rl[0], top = sl[1] - rl[1];

        FrameLayout holder = new FrameLayout(this);
        holder.setBackground(roundRect(Color.WHITE, 14, this));
        holder.setClipToOutline(true);
        if (Build.VERSION.SDK_INT >= 21) holder.setElevation(dp(this, 18));
        ImageView iv = new ImageView(this);
        iv.setImageBitmap(snap);
        iv.setScaleType(ImageView.ScaleType.FIT_XY);
        holder.addView(iv, new FrameLayout.LayoutParams(w, h));
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(w, h);
        hlp.leftMargin = (int) left; hlp.topMargin = (int) top;
        rootVg.addView(holder, hlp);

        float maxW = rootVg.getWidth() - dp(this, 32);
        float maxH = rootVg.getHeight() - dp(this, 48);
        float mul = w < dp(this, 150) ? 1.9f : (w < dp(this, 230) ? 1.45f : 1.18f);
        float scale = Math.min(mul, Math.min(maxW / w, maxH / h));
        if (scale < 1f) scale = 1f; // 单列大瓷砖已近屏宽：不再硬撑放大，靠浮起阴影与淡入做预览感，防溢出屏外
        float cx = left + w / 2f, cy = top + h / 2f;
        float tw = w * scale, th = h * scale;
        float wantCx = Math.max(tw / 2f + dp(this, 10), Math.min(cx, rootVg.getWidth() - tw / 2f - dp(this, 10)));
        float wantCy = Math.max(th / 2f + dp(this, 10), Math.min(cy, rootVg.getHeight() - th / 2f - dp(this, 10)));
        holder.setPivotX(w / 2f); holder.setPivotY(h / 2f);
        holder.setAlpha(0.92f);
        holder.animate().scaleX(scale).scaleY(scale)
            .translationX(wantCx - cx).translationY(wantCy - cy).alpha(1f)
            .setDuration(220).setInterpolator(new DecelerateInterpolator()).start();

        pressPreview = holder; pressPreviewSrc = src;
        haptic();
        android.view.ViewParent p = src.getParent();
        while (p != null) { p.requestDisallowInterceptTouchEvent(true); p = p.getParent(); }
        src.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_UP || e.getActionMasked() == MotionEvent.ACTION_CANCEL) dismissPressPreview();
            return false;
        });
    }

    void dismissPressPreview() {
        if (pressPreview == null) return;
        final View holder = pressPreview; pressPreview = null;
        if (pressPreviewSrc != null) {
            pressPreviewSrc.setOnTouchListener(null);
            android.view.ViewParent p = pressPreviewSrc.getParent();
            while (p != null) { p.requestDisallowInterceptTouchEvent(false); p = p.getParent(); }
            pressPreviewSrc = null;
        }
        holder.animate().cancel();
        holder.animate().scaleX(1f).scaleY(1f).translationX(0).translationY(0).alpha(0f)
            .setDuration(140).setInterpolator(new DecelerateInterpolator())
            .withEndAction(() -> { if (holder.getParent() instanceof ViewGroup) ((ViewGroup) holder.getParent()).removeView(holder); })
            .start();
    }

    TextView chip(String s, int bg, int fg) {
        TextView t = tv(this, s, 10, fg, true);
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
    // P2d：毛玻璃白悬浮搜索栏（圆角 + 淡描边 + 投影，半透近似混合版 backdrop blur）
    GradientDrawable glassPillBg() {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{Color.argb(238, 255, 255, 255), Color.argb(218, 246, 247, 250)});
        g.setCornerRadius(dp(this, 999));
        g.setStroke(dp(this, 1), Color.argb(70, 20, 30, 60));
        return g;
    }

    // P2d：整页改单 ScrollView 流，内容从悬浮栏底下滚过；英雄卡与网格同流 10dp 间隔，不再压首排
    View buildHomePage() {
        FrameLayout page = new FrameLayout(this);

        homeScroll = new ScrollView(this);
        homeScroll.setFillViewport(true);
        homeScroll.setClipToPadding(false);
        // P-searchfix：列表滚动时搜索自动收起/失焦；点列表区域（框外）收键盘
        homeScroll.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) dismissSearch();
            return false;
        });
        homeScroll.setOnScrollChangeListener((v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
            if (scrollY > oldScrollY + dp(this, 6) && scrollY > statusBarH() + dp(this, 72)) {
                dismissSearch();
                setHomeSearchBarShown(false, true);
            } else if (scrollY < oldScrollY - dp(this, 6) || scrollY <= dp(this, 10)) {
                setHomeSearchBarShown(true, true);
            }
            lastHomeScrollY = scrollY;
        });
        page.addView(homeScroll, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        // 顶部让出悬浮栏（状态栏 + 栏高约 50 + 间距），底部留白让最后一项滚出悬浮底栏
        col.setPadding(dp(this, 14), statusBarH() + dp(this, 70), dp(this, 14), dockPad());
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
        filterBtn.setOnClickListener(v -> openFilterSheet());
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

        // 悬浮搜索栏：左右留 12dp、浮在列表之上，内容从栏下滚过（对照混合版 .float-search / #search 玻璃胶囊）
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackground(glassPillBg());
        bar.setPadding(dp(this, 16), dp(this, 5), dp(this, 12), dp(this, 5));
        if (Build.VERSION.SDK_INT >= 21) bar.setElevation(dp(this, 10));
        SearchIconView sicon = new SearchIconView(this);
        sicon.iconColor = Color.rgb(0x63, 0x63, 0x66);
        bar.addView(sicon, new LinearLayout.LayoutParams(dp(this, 20), dp(this, 20)));
        searchBox = new EditText(this);
        searchBox.setHint("搜索卡名 / 银行 / BIN…");
        searchBox.setTextSize(15);
        searchBox.setSingleLine(true);
        searchBox.setBackground(null);
        searchBox.setPadding(dp(this, 8), dp(this, 7), dp(this, 4), dp(this, 7));
        bar.addView(searchBox, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        searchBox.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) { query = s.toString().trim(); refreshHome(); }
            public void afterTextChanged(Editable s) {}
        });
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.gravity = Gravity.TOP;
        blp.leftMargin = dp(this, 12);
        blp.rightMargin = dp(this, 12);
        blp.topMargin = statusBarH() + dp(this, 8);
        page.addView(bar, blp);
        homeSearchBar = bar;
        homeSearchBarShown = true;

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

    void refreshHome() {
        if (homeList == null) return;
        List<Card> list = filteredHome();
        applySort(list);
        if (homeCount != null) homeCount.setText("共 " + list.size() + " 张");
        if (filterBtn != null) {
            int n = activeFilterCount();
            filterBtn.setText(n == 0 ? "筛选" : "筛选 · " + n);
        }
        renderActiveFilters();
        renderHomeList(list);
    }

    void renderHomeList(List<Card> list) {
        homeList.removeAllViews();
        if (list.isEmpty()) {
            homeList.addView(tv(this, "没有符合条件的卡", 13.5f, Color.rgb(0x8E, 0x8E, 0x93), false));
            return;
        }
        if (query.isEmpty() && activeFilterCount() == 0) homeList.addView(wizardBanner());
        if (!groupBank) {
            addCardRows(homeList, list);
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
            if (open) addCardRows(homeList, cs);
        }
    }

    void addCardRows(LinearLayout container, List<Card> list) {
        for (int i = 0; i < list.size(); i += cols) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.topMargin = dp(this, 10);
            row.setLayoutParams(rlp);
            container.addView(row);
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
    }

    View bankHead(final String bank, List<Card> cs, boolean open, int maxN, final Runnable onToggle) {
        int nd = 0;
        for (Card c : cs) if (!c.isCredit()) nd++;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(roundRect(Color.WHITE, 14, this));
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
        // P2e：右下搜索钮退场，不与筛选窗叠压
        if (searchFab != null && searchFab.getParent() != null)
            ((ViewGroup) searchFab.getParent()).removeView(searchFab);
        searchFab = null;
        final FrameLayout sheet = new FrameLayout(this);
        sheet.setBackgroundColor(Color.argb(38, 18, 22, 36)); // 轻遮罩（混合版 .dlg-backdrop.light）
        sheet.setOnClickListener(v -> closeFilterSheet());
        // 窗框：真正浮起的卡片——固定不滚，四角完整圆角+描边，滚动只在窗内
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable cg = new GradientDrawable();
        cg.setColor(Color.WHITE);
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
            filterType = null; filterOrg = null; filterStatus = null;
            filterFeats.clear(); filterBank = null;
            sortMode = null; groupBank = false; persistViewPrefs();
            rebuildFilterPanel(filterPanelRef); refreshHome();
        });
        chead.addView(clearT);
        TextView doneT = tv(this, "\u5b8c\u6210", 13, Color.rgb(0x0A, 0x5C, 0xD6), true);
        doneT.setPadding(dp(this, 8), dp(this, 6), dp(this, 10), dp(this, 6));
        doneT.setOnClickListener(v -> closeFilterSheet());
        chead.addView(doneT);
        card.addView(chead);
        ScrollView sc = new ScrollView(this);
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
        clp.bottomMargin = dp(this, 104); // 浮在 dock 之上（混合版 bottom:104px）
        card.measure(View.MeasureSpec.makeMeasureSpec(cardW, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST));
        clp.height = card.getMeasuredHeight();
        sheet.addView(card, clp);
        content.addView(sheet);
        filterSheet = sheet;
        // 开场：淡入+放大+上浮，减速曲线（P4-fix 统一手感方向，220–320ms 档）
        sheet.setAlpha(0f);
        sheet.animate().alpha(1f).setDuration(200)
            .setInterpolator(new DecelerateInterpolator()).start();
        card.setAlpha(0f);
        card.setScaleX(0.94f); card.setScaleY(0.94f);
        card.setTranslationY(dp(this, 14));
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
            .setDuration(260).setInterpolator(new DecelerateInterpolator(2.2f)).start();
    }

    LinearLayout filterPanelRef = null;

    void closeFilterSheet() {
        final View sheet = filterSheet;
        if (sheet == null) return;
        filterSheet = null;
        if (sheet.getParent() == null) { syncSearchFab(); return; }
        View card = sheet instanceof ViewGroup && ((ViewGroup) sheet).getChildCount() > 0
            ? ((ViewGroup) sheet).getChildAt(0) : null;
        if (card != null) {
            card.animate().alpha(0f).scaleX(0.96f).scaleY(0.96f).translationY(dp(this, 10))
                .setDuration(180).setInterpolator(new DecelerateInterpolator())
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
                        Toast.makeText(this, "\u300c" + names + "\u300d\u6ca1\u6709\u5361\u540c\u65f6\u6ee1\u8db3\uff0c\u4e0d\u80fd\u4e00\u8d77\u9009", Toast.LENGTH_SHORT).show();
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
            if (e.getAction() == MotionEvent.ACTION_DOWN) v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(80).start();
            else if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL)
                v.animate().scaleX(1f).scaleY(1f).setDuration(140).setInterpolator(new DecelerateInterpolator()).start();
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
        b.setOnClickListener(v -> openWizard());
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
        wizSc = null; wizStep = 0; wizA.clear();
        wizardOpen = true; detailFromWiz = false;
        showWizardPage();
    }

    void closeWizard() {
        wizardOpen = false;
        navBar.setVisibility(View.VISIBLE);
        showTab(tab);
    }

    // 同 app.js wizBack：已在第一题（含未选场景由关闭键处理）就回到选场景，否则上一步
    void wizGoBack() {
        if (wizStep <= 1) { wizSc = null; wizStep = 0; wizA.clear(); showWizardPage(); }
        else { wizStep--; showWizardPage(); }
    }

    void showWizardPage() {
        navBar.setVisibility(View.GONE);
        content.removeAllViews();
        content.addView(buildWizardPage());
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
                if (step == 0) { wizSc = null; wizStep = 0; wizA.clear(); }
                else wizStep = step;
                showWizardPage();
            });
            page.addView(row);
        }
    }

    View buildWizardPage() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Color.rgb(0xF2, 0xF3, 0xF7));
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(this, 16), pageTopPad(), dp(this, 16), dp(this, 28));
        sv.addView(page);

        List<WizQ> qs = wizQs();
        WizSc sc = wizScenario();
        String title = wizSc == null ? "情景选卡" : (wizStep <= qs.size() ? sc.name : "为你挑的卡");

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        page.addView(top);
        Button back = new Button(this);
        back.setText("‹ 返回"); back.setTextSize(14); back.setAllCaps(false);
        back.setBackground(roundRect(Color.WHITE, 12, this));
        back.setVisibility(wizSc == null ? View.INVISIBLE : View.VISIBLE);
        back.setOnClickListener(v -> wizGoBack());
        top.addView(back, new LinearLayout.LayoutParams(dp(this, 84), dp(this, 38)));
        TextView ttl = tv(this, title, 16, Color.rgb(0x1C, 0x1C, 0x1E), true);
        LinearLayout.LayoutParams ttlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        ttlp.leftMargin = dp(this, 10);
        top.addView(ttl, ttlp);
        Button close = new Button(this);
        close.setText("✕"); close.setTextSize(14); close.setAllCaps(false);
        close.setBackground(roundRect(Color.WHITE, 12, this));
        close.setOnClickListener(v -> closeWizard());
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
                tile.setBackground(roundRect(Color.WHITE, 14, this));
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
                tile.setOnClickListener(v -> { wizSc = s.id; wizStep = 1; showWizardPage(); });
                page.addView(tile);
            }
            return sv;
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
                opt.setBackground(roundRect(Color.WHITE, 12, this));
                opt.setPadding(dp(this, 14), dp(this, 12), dp(this, 14), dp(this, 12));
                LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                olp.topMargin = dp(this, 8);
                page.addView(opt, olp);
                opt.setOnClickListener(v -> { wizA.put(q.k, o[0]); wizStep++; showWizardPage(); });
            }
            return sv;
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
        redo.setOnClickListener(v -> { wizSc = null; wizStep = 0; wizA.clear(); showWizardPage(); });
        return sv;
    }

    View wizResultRow(final WizResult r) {
        final Card c = r.c;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBackground(roundRect(Color.WHITE, 14, this));
        row.setPadding(dp(this, 12), dp(this, 10), dp(this, 12), dp(this, 10));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(this, 10);
        row.setLayoutParams(rlp);

        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setBackground(roundRect(Color.rgb(0xE9, 0xEE, 0xF5), 9, this));
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
            if (mine.contains(c.id)) { mine.remove(c.id); Toast.makeText(this, "已从我的卡片移除", Toast.LENGTH_SHORT).show(); }
            else { mine.add(c.id); Toast.makeText(this, "已加入我的卡片", Toast.LENGTH_SHORT).show(); }
            prefs.edit().putStringSet("mine_ids", new HashSet<>(mine)).apply();
            pages.remove("mine");
            showWizardPage();
        });
        row.setOnClickListener(v -> openDetail(c, true));
        row.setOnLongClickListener(v -> { showPressPreview(row); return true; });
        return row;
    }

    // ---------- 详情页 ----------
    void openDetail(Card c) { openDetail(c, false); }

    void openDetail(Card c, boolean fromWiz) {
        dismissPressPreview();
        detailFromWiz = fromWiz;
        detailCard = c;
        content.removeAllViews();
        navBar.setVisibility(View.GONE);
        content.addView(buildDetailPage(c));
    }

    void closeDetail() {
        detailCard = null;
        if (detailFromWiz && wizardOpen) {
            // 从选卡结果点进来的详情：关掉必回选卡且进度还在（同混合版 detailFromWiz）
            detailFromWiz = false;
            content.removeAllViews();
            content.addView(buildWizardPage());
            return;
        }
        detailFromWiz = false;
        navBar.setVisibility(View.VISIBLE);
        showTab(tab);
    }

    View buildDetailPage(final Card c) {
        ScrollView sc = new ScrollView(this);
        sc.setBackgroundColor(Color.rgb(0xF2, 0xF3, 0xF7));
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(this, 16), pageTopPad(), dp(this, 16), dp(this, 28));
        sc.addView(page);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        Button back = new Button(this);
        back.setText("‹ 返回"); back.setTextSize(14); back.setAllCaps(false);
        back.setBackground(roundRect(Color.WHITE, 12, this));
        back.setOnClickListener(v -> closeDetail());
        top.addView(back, new LinearLayout.LayoutParams(dp(this, 84), dp(this, 38)));
        TextView title = tv(this, c.bank, 15, Color.rgb(0x1C, 0x1C, 0x1E), true);
        LinearLayout.LayoutParams ttlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        ttlp.leftMargin = dp(this, 10);
        top.addView(title, ttlp);
        page.addView(top);

        ImageView iv = new ImageView(this);
        // 卡面图按原比例完整显示不裁剪（对照混合版 .p-slide img：object-fit:contain、圆角 12、最大高 260）
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setBackground(roundRect(Color.rgb(0xF1, 0xF1, 0xF4), 12, this));
        iv.setClipToOutline(true);
        Bitmap b = Img.get(this, c.image);
        int availW = getResources().getDisplayMetrics().widthPixels - dp(this, 32);
        int imgW = availW, imgH = dp(this, 168);
        if (b != null && b.getWidth() > 0 && b.getHeight() > 0) {
            float ratio = (float) b.getHeight() / (float) b.getWidth();
            imgH = Math.round(availW * ratio);
            int maxH = dp(this, 260);
            if (imgH > maxH) {
                imgH = maxH;
                imgW = Math.round(imgH / ratio);
            }
        }
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(imgW, imgH);
        ilp.topMargin = dp(this, 12);
        ilp.gravity = Gravity.CENTER_HORIZONTAL;
        page.addView(iv, ilp);
        if (b != null) iv.setImageBitmap(b);

        TextView name = tv(this, c.name, 19, Color.rgb(0x1C, 0x1C, 0x1E), true);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = dp(this, 12);
        page.addView(name, nlp);
        TextView meta = tv(this, c.bank + " · " + orgLabel(c.org) + " · " + (c.isCredit() ? "信用卡" : "借记卡") + " · " + c.status
            + " · " + (c.hasScore ? String.format(java.util.Locale.US, "%.1f分", c.score) : "待评分"),
            12.5f, Color.rgb(0x8E, 0x8E, 0x93), false);
        LinearLayout.LayoutParams mep = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mep.topMargin = dp(this, 2);
        page.addView(meta, mep);

        if (c.variants != null && c.variants.length() > 1) {
            TextView varTitle = tv(this, "子版本", 14, Color.rgb(0x1C, 0x1C, 0x1E), true);
            LinearLayout.LayoutParams vtp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            vtp.topMargin = dp(this, 14);
            page.addView(varTitle, vtp);
            LinearLayout varBox = new LinearLayout(this);
            varBox.setOrientation(LinearLayout.VERTICAL);
            varBox.setBackground(roundRect(Color.WHITE, 12, this));
            varBox.setPadding(dp(this, 12), dp(this, 4), dp(this, 12), dp(this, 4));
            LinearLayout.LayoutParams vbp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            vbp.topMargin = dp(this, 8);
            page.addView(varBox, vbp);
            for (int i = 0; i < c.variants.length(); i++) {
                JSONObject v = c.variants.optJSONObject(i);
                if (v == null) continue;
                String line = v.optString("name") + "（BIN " + v.optString("bin") + "）";
                if (!v.optString("note").isEmpty()) line += "：" + v.optString("note");
                TextView vt = tv(this, line, 12.5f, Color.rgb(0x3A, 0x3A, 0x3C), false);
                vt.setPadding(0, dp(this, 7), 0, dp(this, 7));
                varBox.addView(vt);
                if (i < c.variants.length() - 1) {
                    View div = new View(this);
                    div.setBackgroundColor(Color.rgb(0xF0, 0xF0, 0xF5));
                    varBox.addView(div, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(this, 1) / 2)));
                }
            }
        }

        if (c.review != null && !c.review.isEmpty()) {
            TextView rv = tv(this, c.review, 13.5f, Color.rgb(0x3A, 0x3A, 0x3C), false);
            rv.setBackground(roundRect(Color.rgb(0xEE, 0xF4, 0xFB), 12, this));
            rv.setPadding(dp(this, 12), dp(this, 9), dp(this, 12), dp(this, 9));
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.topMargin = dp(this, 10);
            page.addView(rv, rlp);
        }

        final Button mineBtn = new Button(this);
        mineBtn.setTextSize(15); mineBtn.setAllCaps(false);
        styleMineBtn(mineBtn, c);
        mineBtn.setOnClickListener(v -> {
            if (mine.contains(c.id)) { mine.remove(c.id); Toast.makeText(this, "已从我的卡片移除", Toast.LENGTH_SHORT).show(); }
            else { mine.add(c.id); Toast.makeText(this, "已加入我的卡片", Toast.LENGTH_SHORT).show(); }
            prefs.edit().putStringSet("mine_ids", new HashSet<>(mine)).apply();
            styleMineBtn(mineBtn, c);
            pages.remove("mine");
        });
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(this, 46));
        mlp.topMargin = dp(this, 12);
        page.addView(mineBtn, mlp);

        TextView specTitle = tv(this, "卡片参数", 14, Color.rgb(0x1C, 0x1C, 0x1E), true);
        LinearLayout.LayoutParams splp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        splp.topMargin = dp(this, 16);
        page.addView(specTitle, splp);

        if (c.specs != null) {
            // 参数整卡：白底圆角一整张，行间 1px 细线分隔、紧凑行高（对照混合版 .spec）
            LinearLayout specBox = new LinearLayout(this);
            specBox.setOrientation(LinearLayout.VERTICAL);
            specBox.setBackground(roundRect(Color.WHITE, 14, this));
            specBox.setPadding(dp(this, 14), dp(this, 4), dp(this, 14), dp(this, 4));
            LinearLayout.LayoutParams sbp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            sbp.topMargin = dp(this, 8);
            page.addView(specBox, sbp);
            java.util.List<String[]> rows = new ArrayList<>();
            // 固定优先键序（对照混合版 specRows 与 PROGRESS 已知注意），其余键随后，避免 org.json 无序打乱主参数
            String[] prefKeys = {"卡片名称", "BIN", "币种支持", "货币转换费（FTF）", "货币转换费", "3DS", "自动购汇", "网付", "年费", "发行情况"};
            java.util.Set<String> usedKeys = new HashSet<>();
            for (String k : prefKeys) {
                String v = c.specs.optString(k, "");
                if (v == null || v.isEmpty() || usedKeys.contains(k)) continue;
                rows.add(new String[]{k, v});
                usedKeys.add(k);
            }
            Iterator<String> keys = c.specs.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                if (usedKeys.contains(k)) continue;
                String v = c.specs.optString(k, "");
                if (v == null || v.isEmpty()) continue;
                rows.add(new String[]{k, v});
                usedKeys.add(k);
            }
            for (int i = 0; i < rows.size(); i++) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.TOP);
                row.setPadding(0, dp(this, 8), 0, dp(this, 8));
                specBox.addView(row);
                TextView kt = tv(this, rows.get(i)[0], 12.5f, Color.rgb(0x8E, 0x8E, 0x93), false);
                row.addView(kt, new LinearLayout.LayoutParams(dp(this, 108), ViewGroup.LayoutParams.WRAP_CONTENT));
                TextView vt = tv(this, rows.get(i)[1], 12.5f, Color.rgb(0x1C, 0x1C, 0x1E), false);
                row.addView(vt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                if (i < rows.size() - 1) {
                    View div = new View(this);
                    div.setBackgroundColor(Color.rgb(0xF0, 0xF0, 0xF5));
                    specBox.addView(div, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(this, 1) / 2)));
                }
            }
        }
        return sc;
    }

    void styleMineBtn(Button b, Card c) {
        // 对照混合版 .mine-toggle：未收藏蓝底白字、已收藏绿底白字，利落主按钮（圆角 12）
        boolean in = mine.contains(c.id);
        b.setText(in ? "✓ 已在我的卡片（点此移除）" : "＋ 加入我的卡片");
        b.setTextColor(Color.WHITE);
        b.setBackground(roundRect(in ? Color.rgb(0x34, 0xC7, 0x59) : Color.rgb(0x0A, 0x5C, 0xD6), 12, this));
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
        sv.setClipToPadding(false);
        LinearLayout listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        listBox.setPadding(0, dp(this, 10), 0, dockPad());
        sv.addView(listBox);
        page.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        for (final Card c : stu) {
            LinearLayout cardBox = new LinearLayout(this);
            cardBox.setOrientation(LinearLayout.VERTICAL);
            cardBox.setBackground(roundRect(Color.WHITE, 14, this));
            cardBox.setPadding(dp(this, 12), dp(this, 10), dp(this, 12), dp(this, 12));
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.topMargin = dp(this, 10);
            listBox.addView(cardBox, clp);
            cardBox.setOnClickListener(v -> openDetail(c));
            cardBox.setOnLongClickListener(v -> { showPressPreview(cardBox); return true; });

            LinearLayout top = new LinearLayout(this);
            top.setOrientation(LinearLayout.HORIZONTAL);
            top.setGravity(Gravity.CENTER_VERTICAL);
            cardBox.addView(top);
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setBackground(roundRect(Color.rgb(0xE9, 0xEE, 0xF5), 9, this));
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
            prefs.edit().putString("mine_order", arr.toString()).apply();
        } catch (Exception e) { /* 存不下就保持内存顺序 */ }
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
        hg.setCornerRadius(dp(this, 18));
        hero.setBackground(hg);
        hero.setPadding(dp(this, 16), dp(this, 14), dp(this, 16), dp(this, 14));
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
        // 对照混合版 .bn-tile：玻璃底+内边距，四格同高同底，内容绝不裸贴深蓝底
        t.setBackground(roundRect(Color.argb(38, 255, 255, 255), 12, this));
        t.setPadding(dp(this, 10), dp(this, 9), dp(this, 10), dp(this, 9));
        t.setMinimumHeight(dp(this, 64));
        t.addView(tv(this, value, 17, Color.WHITE, true));
        TextView l = tv(this, label, 11, Color.argb(205, 255, 255, 255), false);
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
        sv.setFillViewport(true);
        sv.setClipToPadding(false);
        mineScrollView = sv;
        if (Build.VERSION.SDK_INT >= 23) {
            sv.setOnScrollChangeListener((v, sx, sy, ox, oy) -> { mineScrollSaveY = sy; });
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
            TextView empty = tv(this, "还没有从卡库收藏的卡。去「全部卡片」点开任意一张，加入我的卡片。", 13.5f, Color.rgb(0x8E, 0x8E, 0x93), false);
            LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            elp.topMargin = dp(this, 12);
            inner.addView(empty, elp);
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
            Toast.makeText(this, "顺序已保存", Toast.LENGTH_SHORT).show();
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
        addBtn.setOnClickListener(v -> openCustomForm(null));
        head.addView(addBtn, new LinearLayout.LayoutParams(dp(this, 76), dp(this, 36)));
        TextView arrow = tv(this, customCards.isEmpty() ? "" : (customOpen ? "收起 ‹" : "展开 ›"), 12, Color.rgb(0x0A, 0x5C, 0xD6), true);
        LinearLayout.LayoutParams alp2 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp2.leftMargin = dp(this, 8);
        head.addView(arrow, alp2);
        if (!customCards.isEmpty()) {
            head.setOnClickListener(v -> { customOpen = !customOpen; refreshMineKeepScroll(); });
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
            Toast.makeText(this, "顺序已保存", Toast.LENGTH_SHORT).show();
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
                customCards.remove(c);
                saveCustomCards();
                Toast.makeText(this, "已删除这张自定义卡", Toast.LENGTH_SHORT).show();
                refreshMineKeepScroll();
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
            b.setOnClickListener(v -> { orgSel[0] = o.equals(orgSel[0]) ? "" : o; paintOrgs.run(); });
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
            b.setOnClickListener(v -> { styleSel[0] = si; paintStyles.run(); });
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
        cancel.setOnClickListener(v -> { customDialog = null; dlg.dismiss(); });
        acts.addView(cancel, new LinearLayout.LayoutParams(0, dp(this, 46), 1f));
        Button save = new Button(this);
        save.setText("保存"); save.setTextSize(14); save.setAllCaps(false);
        save.setTextColor(Color.WHITE);
        save.setBackground(roundRect(Color.rgb(0x0A, 0x5C, 0xD6), 12, this));
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(0, dp(this, 46), 1f);
        saveLp.leftMargin = dp(this, 10);
        acts.addView(save, saveLp);
        save.setOnClickListener(v -> {
            String name = inName.getText().toString().trim();
            if (name.isEmpty()) { Toast.makeText(this, "请填写卡片名称", Toast.LENGTH_SHORT).show(); inName.requestFocus(); return; }
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
                Toast.makeText(this, "已添加「" + name + "」", Toast.LENGTH_SHORT).show();
            } else {
                edit.name = name;
                edit.bank = inBank.getText().toString().trim();
                edit.org = orgSel[0];
                edit.note = inNote.getText().toString().trim();
                edit.style = styleSel[0];
                Toast.makeText(this, "已保存「" + name + "」", Toast.LENGTH_SHORT).show();
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
                        if (manual) runOnUiThread(() -> Toast.makeText(this, "已是最新数据（v" + Store.dataVersion + "）", Toast.LENGTH_SHORT).show());
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
                        Toast.makeText(this, "卡片数据已更新到 v" + Store.dataVersion + "（" + Store.all.size() + " 张）", Toast.LENGTH_SHORT).show();
                        pages.clear(); // 页面缓存一律作废，下次进页用新数据重建
                        if (detailCard == null) rebuildPages(); // 正看详情时不打断，关掉详情自然用新数据
                    });
                    return;
                } catch (Exception e) { /* 换下一条线路 */ }
            }
            if (manual) runOnUiThread(() -> Toast.makeText(this, "检查更新失败，请检查网络", Toast.LENGTH_SHORT).show());
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
        newsListBox.removeAllViews();
        if (newsItems == null || newsItems.isEmpty()) {
            newsListBox.addView(tv(this, "暂时还没有资讯，过段时间再来看看。", 13.5f, Color.rgb(0x8E, 0x8E, 0x93), false));
            return;
        }
        if (newsMeta != null) newsMeta.setText("共 " + newsItems.size() + " 条 · 公开信息整理，仅供参考");
        for (final NewsItem n : newsItems) {
            final boolean open = newsOpen.contains(n.id);
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackground(roundRect(Color.WHITE, 16, this));
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
                        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(n.url))); }
                        catch (Exception e) { Toast.makeText(this, "打不开这个链接", Toast.LENGTH_SHORT).show(); }
                    });
                } else {
                    det.addView(tv(this, "暂无原文链接", 11.5f, Color.rgb(0x8E, 0x8E, 0x93), false));
                }
            }

            card.setOnClickListener(v -> {
                if (newsOpen.contains(n.id)) newsOpen.remove(n.id); else newsOpen.add(n.id);
                renderNews();
            });
        }
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
        sv.setClipToPadding(false);
        newsListBox = new LinearLayout(this);
        newsListBox.setOrientation(LinearLayout.VERTICAL);
        newsListBox.setPadding(0, dp(this, 2), 0, dockPad());
        sv.addView(newsListBox);
        page.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        renderNews();
        return page;
    }

    // ---------- 欢迎页 / 更新日志（Phase 4b） ----------
    void showWelcome() {
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
        sc.setBackgroundColor(Color.WHITE);
        sc.setFillViewport(true);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(this, 26), statusBarH() + dp(this, 22), dp(this, 26), dp(this, 18));
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
        changelogOpen = true;
        navBar.setVisibility(View.GONE);
        content.removeAllViews();
        content.addView(buildChangelogPage());
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
        back.setOnClickListener(v -> closeChangelog());
        head.addView(back, new LinearLayout.LayoutParams(dp(this, 84), dp(this, 38)));
        TextView ht = tv(this, "更新日志", 17, Color.rgb(0x1C, 0x1C, 0x1E), true);
        LinearLayout.LayoutParams htlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        htlp.leftMargin = dp(this, 10);
        head.addView(ht, htlp);

        changelogScroll = new ScrollView(this);
        changelogScroll.setVerticalScrollBarEnabled(true);
        changelogScroll.setScrollbarFadingEnabled(false);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(this, 16), dp(this, 6), dp(this, 16), dp(this, 16));
        changelogScroll.addView(page);
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
        top.setOnClickListener(v -> { if (changelogScroll != null) changelogScroll.smoothScrollTo(0, 0); });
        actions.addView(top, new LinearLayout.LayoutParams(0, dp(this, 40), 1f));
        Button fold = new Button(this);
        fold.setText("收起日志"); fold.setTextSize(13); fold.setAllCaps(false);
        fold.setBackground(roundRect(Color.rgb(0xEE, 0xF1, 0xF6), 999, this));
        fold.setOnClickListener(v -> closeChangelog());
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(0, dp(this, 40), 1f);
        flp.leftMargin = dp(this, 10);
        actions.addView(fold, flp);
        return root;
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
        switchRow(page, "触感反馈", "点按时轻震一下", hapticOn, on -> {
            hapticOn = on; prefs.edit().putBoolean("haptic", on).apply(); haptic(); rebuildPages();
        });

        sectionHead(page, "数据");
        page.addView(settingRow("数据版本", "v" + Store.dataVersion + " · " + Store.all.size() + " 张卡（启动自动检查，更新后无需重装）"));
        View updRow = settingRow("检查数据更新", "从数据仓拉最新卡库 ›");
        updRow.setOnClickListener(v -> { haptic(); Toast.makeText(this, "正在检查数据更新…", Toast.LENGTH_SHORT).show(); checkDataUpdate(true); });
        page.addView(updRow);

        sectionHead(page, "关于");
        page.addView(settingRow("版本", appVersion() + "（原生版）"));
        View logRow = settingRow("更新日志", "每个版本改了什么 ›");
        logRow.setOnClickListener(v -> { haptic(); showChangelog(); });
        page.addView(logRow);
        View welRow = settingRow("欢迎页", "重新看一遍首次打开的介绍 ›");
        welRow.setOnClickListener(v -> { haptic(); showWelcome(); });
        page.addView(welRow);
        page.addView(settingRow("关于卡盒", "原生版：纯 Java 手写界面，数据与现行版共用同一份卡库"));
        page.addView(settingRow("迁移进度", "全部卡片 / 详情 / 我的卡片 / 学生推荐 / 筛选 / 资讯 / 情景选卡 / 自定义卡 / 拖动 / 字体与界面大小 / 高刷 / 触感 / 欢迎页 / 更新日志 / 数据 OTA 已迁移"));
        ScrollView sv = new ScrollView(this);
        sv.setClipToPadding(false);
        // P2d-fix：此前这里把 basePage 的顶部留白覆盖成 12dp，标题被压进状态栏；改用 pageTopPad()/dockPad()
        page.setPadding(dp(this, 14), pageTopPad(), dp(this, 14), dockPad());
        sv.addView(page);
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
        row.setBackground(roundRect(Color.WHITE, 12, this));
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
        row.setBackground(roundRect(Color.WHITE, 12, this));
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
        if (pressPreview != null) { dismissPressPreview(); return; }
        if (welcomeOpen) { closeWelcome(); return; }
        if (changelogOpen) { closeChangelog(); return; }
        if (detailCard != null) { closeDetail(); return; }
        if (filterSheet != null) { closeFilterSheet(); return; }
        if (wizardOpen) {
            if (wizSc == null) closeWizard(); else wizGoBack();
            return;
        }
        super.onBackPressed();
    }
}
