package com.igll.carddbnative;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
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
    static GradientDrawable roundRect(int color, float radiusDp, Context c) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color); g.setCornerRadius(dp(c, radiusDp));
        return g;
    }
    static TextView tv(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s); t.setTextSize(sp); t.setTextColor(color);
        if (bold) t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
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
        static void load(Context c) {
            if (!all.isEmpty()) return;
            try {
                InputStream in = c.getAssets().open("data/cards.json");
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192]; int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                in.close();
                JSONObject root = new JSONObject(new String(bos.toByteArray(), "UTF-8"));
                JSONArray arr = root.getJSONArray("cards");
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
                    all.add(cd); byId.put(cd.id, cd);
                }
            } catch (Exception e) { /* 数据读不到就空列表，界面有空状态 */ }
        }
    }

    static class Img {
        static LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(48 * 1024) {
            protected int sizeOf(String k, Bitmap b) { return b.getByteCount() / 1024; }
        };
        static Bitmap get(Context c, String path) {
            if (path == null || path.isEmpty()) return null;
            Bitmap hit = cache.get(path);
            if (hit != null) return hit;
            try {
                InputStream in = c.getAssets().open(path);
                BitmapFactory.Options op = new BitmapFactory.Options();
                op.inSampleSize = 2;
                Bitmap b = BitmapFactory.decodeStream(in, null, op);
                in.close();
                if (b != null) cache.put(path, b);
                return b;
            } catch (Exception e) { return null; }
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

    // ---------- 全局状态 ----------
    SharedPreferences prefs;
    Set<String> mine = new HashSet<>();
    String tab = "home";
    Card detailCard = null;

    // 情景选卡状态（Phase 3a，对照 app.js 的 wiz 全局状态）
    boolean wizardOpen = false;
    boolean detailFromWiz = false;
    String wizSc = null;
    int wizStep = 0;
    Map<String, String> wizA = new HashMap<>();

    FrameLayout content;
    LinearLayout navBar;
    Map<String, View> pages = new HashMap<>();
    Map<String, Button> navBtns = new HashMap<>();

    // 首页控件（切页回来保持搜索词）
    EditText searchBox;
    String query = "";
    TextView homeCount;
    Button filterBtn;
    View filterSheet = null;

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
        w.setStatusBarColor(Color.WHITE);
        w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        prefs = getSharedPreferences("cardbox_native", MODE_PRIVATE);
        try { mine = new HashSet<>(prefs.getStringSet("mine_ids", new HashSet<String>())); } catch (Exception e) { mine = new HashSet<>(); }
        sortMode = prefs.getString("sort_mode", null);
        cols = prefs.getInt("cols", 2); if (cols != 1 && cols != 2 && cols != 3) cols = 2;
        groupBank = prefs.getBoolean("group_bank", false);
        try { bankOpen = new HashSet<>(prefs.getStringSet("bank_open", new HashSet<String>())); } catch (Exception e) { bankOpen = new HashSet<>(); }
        loadCustomCards();
        Store.load(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(0xF2, 0xF3, 0xF7));

        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        buildNav(root);
        setContentView(root);

        showTab("home");
    }

    // ---------- 底部导航 ----------
    void buildNav(LinearLayout root) {
        navBar = new LinearLayout(this);
        navBar.setOrientation(LinearLayout.HORIZONTAL);
        navBar.setBackgroundColor(Color.WHITE);
        navBar.setPadding(dp(this, 8), dp(this, 6), dp(this, 8), dp(this, 10));
        String[][] tabs = {
            {"home", "全部卡片"}, {"student", "学生推荐"}, {"mine", "我的卡片"}, {"news", "资讯"}, {"settings", "设置"}
        };
        for (String[] t : tabs) {
            Button b = new Button(this);
            b.setText(t[1]); b.setTextSize(12);
            b.setAllCaps(false);
            b.setBackground(null);
            final String key = t[0];
            b.setOnClickListener(v -> showTab(key));
            navBtns.put(key, b);
            navBar.addView(b, new LinearLayout.LayoutParams(0, dp(this, 44), 1f));
        }
        root.addView(navBar);
    }

    void showTab(String key) {
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
        for (Map.Entry<String, Button> e : navBtns.entrySet()) {
            boolean on = e.getKey().equals(key);
            e.getValue().setTextColor(on ? Color.rgb(0x0A, 0x5C, 0xD6) : Color.rgb(0x8E, 0x8E, 0x93));
            e.getValue().setTypeface(on ? android.graphics.Typeface.DEFAULT_BOLD : android.graphics.Typeface.DEFAULT);
            e.getValue().setBackground(on ? roundRect(Color.rgb(0xE8, 0xF1, 0xFD), 12, this) : null);
        }
    }

    // ---------- 通用：卡片瓷砖 ----------
    View cardTile(final Card c, ViewGroup parent) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(roundRect(Color.WHITE, 14, this));
        box.setPadding(dp(this, 8), dp(this, 8), dp(this, 8), dp(this, 10));
        AbsListView.LayoutParams lp = new AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        box.setLayoutParams(lp);

        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setBackground(roundRect(Color.rgb(0xE9, 0xEE, 0xF5), 9, this));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(this, 74));
        box.addView(iv, ilp);
        Bitmap b = Img.get(this, c.image);
        if (b != null) iv.setImageBitmap(b); else iv.setImageBitmap(null);

        TextView name = tv(this, c.name, 13, Color.rgb(0x1C, 0x1C, 0x1E), true);
        name.setMaxLines(2);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = dp(this, 7);
        box.addView(name, nlp);

        TextView sub = tv(this, c.bank + " · " + orgLabel(c.org), 10.5f, Color.rgb(0x8E, 0x8E, 0x93), false);
        sub.setMaxLines(1);
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
        return box;
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
    View buildHomePage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(this, 14), dp(this, 12), dp(this, 14), 0);

        page.addView(tv(this, "卡盒", 24, Color.rgb(0x1C, 0x1C, 0x1E), true));

        searchBox = new EditText(this);
        searchBox.setHint("搜索卡名 / 银行 / BIN…");
        searchBox.setTextSize(14);
        searchBox.setSingleLine(true);
        searchBox.setBackground(roundRect(Color.WHITE, 14, this));
        searchBox.setPadding(dp(this, 14), dp(this, 11), dp(this, 14), dp(this, 11));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(this, 10);
        page.addView(searchBox, slp);
        searchBox.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) { query = s.toString().trim(); refreshHome(); }
            public void afterTextChanged(Editable s) {}
        });

        Set<String> banks = new HashSet<>();
        for (Card c : Store.all) banks.add(c.bank);
        int debit = 0, stopped = 0;
        for (Card c : Store.all) { if (!c.isCredit()) debit++; if ("已停发".equals(c.status)) stopped++; }

        TextView stats = tv(this, Store.all.size() + " 张卡 · " + banks.size() + " 家银行", 12, Color.rgb(0x8E, 0x8E, 0x93), false);
        LinearLayout.LayoutParams stlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stlp.topMargin = dp(this, 10);
        page.addView(stats, stlp);

        LinearLayout frow = new LinearLayout(this);
        frow.setOrientation(LinearLayout.HORIZONTAL);
        frow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams frowLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        frowLp.topMargin = dp(this, 8);
        page.addView(frow, frowLp);
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
        page.addView(afScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        activeFilterBar.setTag(afScroll);

        // 卡库总览（与混合版同款深蓝卡）
        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable hg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
            new int[]{Color.rgb(0x0B, 0x3D, 0x91), Color.rgb(0x0A, 0x6E, 0xD6), Color.rgb(0x00, 0xA3, 0xC8)});
        hg.setCornerRadius(dp(this, 20));
        hero.setBackground(hg);
        hero.setPadding(dp(this, 18), dp(this, 16), dp(this, 18), dp(this, 16));
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = dp(this, 10);
        page.addView(hero, hlp);
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

        homeScroll = new ScrollView(this);
        homeScroll.setFillViewport(true);
        homeList = new LinearLayout(this);
        homeList.setOrientation(LinearLayout.VERTICAL);
        homeList.setPadding(0, dp(this, 10), 0, dp(this, 16));
        homeScroll.addView(homeList, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        page.addView(homeScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
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
                    LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
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

    // ---------- 筛选面板（Phase 2a-1） ----------
    void openFilterSheet() {
        closeFilterSheet();
        final FrameLayout sheet = new FrameLayout(this);
        sheet.setBackgroundColor(Color.argb(90, 10, 16, 28));
        sheet.setOnClickListener(v -> closeFilterSheet());
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable pg = new GradientDrawable();
        pg.setColor(Color.WHITE);
        pg.setCornerRadii(new float[]{dp(this, 18), dp(this, 18), dp(this, 18), dp(this, 18), 0, 0, 0, 0});
        panel.setBackground(pg);
        panel.setPadding(dp(this, 16), dp(this, 14), dp(this, 16), dp(this, 18));
        panel.setOnClickListener(v -> {});
        ScrollView panelScroll = new ScrollView(this);
        panelScroll.setBackgroundColor(Color.TRANSPARENT);
        panelScroll.setFillViewport(true);
        int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.78);
        FrameLayout.LayoutParams splp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxH);
        splp.gravity = Gravity.BOTTOM;
        panelScroll.addView(panel, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        sheet.addView(panelScroll, splp);
        rebuildFilterPanel(panel);
        content.addView(sheet);
        filterSheet = sheet;
    }

    void closeFilterSheet() {
        if (filterSheet != null && filterSheet.getParent() != null)
            ((ViewGroup) filterSheet.getParent()).removeView(filterSheet);
        filterSheet = null;
    }

    void rebuildFilterPanel(final LinearLayout panel) {
        panel.removeAllViews();
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView ttl = tv(this, "筛选", 16, Color.rgb(0x1C, 0x1C, 0x1E), true);
        head.addView(ttl, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button clear = new Button(this);
        clear.setText("清空"); clear.setTextSize(12.5f); clear.setAllCaps(false); clear.setMinWidth(0);
        clear.setBackground(roundRect(Color.rgb(0xF5, 0xF6, 0xF8), 10, this));
        clear.setOnClickListener(v -> {
            filterType = null; filterOrg = null; filterStatus = null;
            filterFeats.clear(); filterBank = null;
            sortMode = null; groupBank = false; persistViewPrefs();
            rebuildFilterPanel(panel); refreshHome();
        });
        head.addView(clear, new LinearLayout.LayoutParams(dp(this, 64), dp(this, 32)));
        Button done = new Button(this);
        done.setText("完成"); done.setTextSize(12.5f); done.setAllCaps(false); done.setMinWidth(0);
        done.setTextColor(Color.WHITE);
        done.setBackground(roundRect(Color.rgb(0x0A, 0x5C, 0xD6), 10, this));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(this, 64), dp(this, 32));
        dlp.leftMargin = dp(this, 8);
        done.setOnClickListener(v -> closeFilterSheet());
        head.addView(done, dlp);
        panel.addView(head);

        panel.addView(filterSectionTitle("卡片类型"));
        panel.addView(filterOpt("借记卡", "debit".equals(filterType), () -> { filterType = "debit".equals(filterType) ? null : "debit"; rebuildFilterPanel(panel); refreshHome(); }));
        panel.addView(filterOpt("信用卡", "credit".equals(filterType), () -> { filterType = "credit".equals(filterType) ? null : "credit"; rebuildFilterPanel(panel); refreshHome(); }));

        panel.addView(filterSectionTitle("卡组织"));
        String[][] orgs = {{"visa", "VISA"}, {"mastercard", "万事达"}, {"mastercard-nucc", "万事达-网联"}, {"amex-cn", "运通-人民币"}, {"unionpay", "银联"}, {"jcb", "JCB"}};
        for (final String[] o : orgs) {
            panel.addView(filterOpt(o[1], o[0].equals(filterOrg), () -> {
                filterOrg = o[0].equals(filterOrg) ? null : o[0];
                rebuildFilterPanel(panel); refreshHome();
            }));
        }

        panel.addView(filterSectionTitle("状态"));
        panel.addView(filterOpt("在发", "在发".equals(filterStatus), () -> { filterStatus = "在发".equals(filterStatus) ? null : "在发"; rebuildFilterPanel(panel); refreshHome(); }));
        panel.addView(filterOpt("已停发", "已停发".equals(filterStatus), () -> { filterStatus = "已停发".equals(filterStatus) ? null : "已停发"; rebuildFilterPanel(panel); refreshHome(); }));

        panel.addView(filterSectionTitle("特点（可多选，须同时满足）"));
        for (final String[] f : FEATS) {
            panel.addView(filterOpt(f[1], filterFeats.contains(f[0]), () -> {
                if (filterFeats.contains(f[0])) {
                    filterFeats.remove(f[0]);
                } else {
                    // 同混合版 chipRow：加上后若没有任何卡能同时满足全部已选特点，拒绝并提示
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
                        for (String k : test) { if (names.length() > 0) names.append("」+「"); names.append(featLabel(k)); }
                        Toast.makeText(this, "「" + names + "」没有卡同时满足，不能一起选", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    filterFeats.add(f[0]);
                }
                rebuildFilterPanel(panel); refreshHome();
            }));
        }

        panel.addView(filterSectionTitle("发卡行"));
        for (final String bankName : distinctBanks()) {
            panel.addView(filterOpt(bankName, bankName.equals(filterBank), () -> {
                filterBank = bankName.equals(filterBank) ? null : bankName;
                rebuildFilterPanel(panel); refreshHome();
            }));
        }

        panel.addView(filterSectionTitle("排序"));
        String[][] sorts = {{"score-desc", "评分由高到低"}, {"score-asc", "评分由低到高"}, {"name", "名称"}, {"bank", "银行"}};
        for (final String[] so : sorts) {
            panel.addView(filterOpt(so[1], so[0].equals(sortMode), () -> {
                sortMode = so[0].equals(sortMode) ? null : so[0];
                persistViewPrefs();
                rebuildFilterPanel(panel); refreshHome();
            }));
        }

        panel.addView(filterSectionTitle("显示方式"));
        panel.addView(filterOpt("显示全部", !groupBank, () -> {
            groupBank = false; persistViewPrefs();
            rebuildFilterPanel(panel); refreshHome();
        }));
        panel.addView(filterOpt("按银行折叠", groupBank, () -> {
            groupBank = true; persistViewPrefs();
            rebuildFilterPanel(panel); refreshHome();
        }));

        panel.addView(filterSectionTitle("列数"));
        String[][] colOpts = {{"1", "单列"}, {"2", "双列"}, {"3", "三列"}};
        for (final String[] co : colOpts) {
            final int nCols = Integer.parseInt(co[0]);
            panel.addView(filterOpt(co[1], cols == nCols, () -> {
                cols = nCols; persistViewPrefs();
                rebuildFilterPanel(panel); refreshHome();
            }));
        }
    }

    TextView filterSectionTitle(String s) {
        TextView t = tv(this, s, 12, Color.rgb(0x8E, 0x8E, 0x93), true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(this, 14);
        t.setLayoutParams(lp);
        return t;
    }

    View filterOpt(String label, boolean on, final Runnable act) {
        TextView t = tv(this, (on ? "✓ " : "　 ") + label, 13.5f, on ? Color.rgb(0x0A, 0x5C, 0xD6) : Color.rgb(0x1C, 0x1C, 0x1E), on);
        t.setBackground(roundRect(on ? Color.rgb(0xE8, 0xF1, 0xFD) : Color.rgb(0xF5, 0xF6, 0xF8), 10, this));
        t.setPadding(dp(this, 12), dp(this, 9), dp(this, 12), dp(this, 9));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(this, 6);
        t.setLayoutParams(lp);
        t.setOnClickListener(v -> act.run());
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
        page.setPadding(dp(this, 16), dp(this, 12), dp(this, 16), dp(this, 28));
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
        return row;
    }

    // ---------- 详情页 ----------
    void openDetail(Card c) { openDetail(c, false); }

    void openDetail(Card c, boolean fromWiz) {
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
        page.setPadding(dp(this, 16), dp(this, 12), dp(this, 16), dp(this, 28));
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
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setBackground(roundRect(Color.rgb(0xE9, 0xEE, 0xF5), 14, this));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(this, 168));
        ilp.topMargin = dp(this, 14);
        page.addView(iv, ilp);
        Bitmap b = Img.get(this, c.image);
        if (b != null) iv.setImageBitmap(b);

        TextView name = tv(this, c.name, 20, Color.rgb(0x1C, 0x1C, 0x1E), true);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = dp(this, 14);
        page.addView(name, nlp);
        page.addView(tv(this, c.bank + " · " + orgLabel(c.org) + " · " + (c.isCredit() ? "信用卡" : "借记卡") + " · " + c.status,
            12.5f, Color.rgb(0x8E, 0x8E, 0x93), false));

        if (c.variants != null && c.variants.length() > 1) {
            page.addView(tv(this, "子版本", 13, Color.rgb(0x8E, 0x8E, 0x93), true));
            for (int i = 0; i < c.variants.length(); i++) {
                JSONObject v = c.variants.optJSONObject(i);
                if (v == null) continue;
                String line = v.optString("name") + "（BIN " + v.optString("bin") + "）";
                if (!v.optString("note").isEmpty()) line += "：" + v.optString("note");
                TextView vt = tv(this, "· " + line, 12, Color.rgb(0x3A, 0x3A, 0x3C), false);
                LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                vlp.topMargin = dp(this, 3);
                page.addView(vt, vlp);
            }
        }

        if (c.review != null && !c.review.isEmpty()) {
            TextView rv = tv(this, c.review, 13.5f, Color.rgb(0x3A, 0x3A, 0x3C), false);
            rv.setBackground(roundRect(Color.rgb(0xEE, 0xF4, 0xFB), 12, this));
            rv.setPadding(dp(this, 12), dp(this, 10), dp(this, 12), dp(this, 10));
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.topMargin = dp(this, 12);
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
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(this, 48));
        mlp.topMargin = dp(this, 14);
        page.addView(mineBtn, mlp);

        TextView specTitle = tv(this, "卡片参数", 15, Color.rgb(0x1C, 0x1C, 0x1E), true);
        LinearLayout.LayoutParams splp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        splp.topMargin = dp(this, 18);
        page.addView(specTitle, splp);

        if (c.specs != null) {
            Iterator<String> keys = c.specs.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                String v = c.specs.optString(k, "");
                if (v == null || v.isEmpty()) continue;
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setBackground(roundRect(Color.WHITE, 10, this));
                row.setPadding(dp(this, 12), dp(this, 9), dp(this, 12), dp(this, 9));
                LinearLayout.LayoutParams rlp2 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rlp2.topMargin = dp(this, 6);
                page.addView(row, rlp2);
                TextView kt = tv(this, k, 12.5f, Color.rgb(0x8E, 0x8E, 0x93), false);
                row.addView(kt, new LinearLayout.LayoutParams(dp(this, 108), ViewGroup.LayoutParams.WRAP_CONTENT));
                TextView vt = tv(this, v, 12.5f, Color.rgb(0x1C, 0x1C, 0x1E), false);
                row.addView(vt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            }
        }
        return sc;
    }

    void styleMineBtn(Button b, Card c) {
        boolean in = mine.contains(c.id);
        b.setText(in ? "✓ 已在我的卡片（点此移除）" : "＋ 加入我的卡片");
        b.setTextColor(in ? Color.rgb(0x1D, 0x8A, 0x49) : Color.WHITE);
        b.setBackground(roundRect(in ? Color.rgb(0xE6, 0xF6, 0xEC) : Color.rgb(0x0A, 0x5C, 0xD6), 14, this));
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
        LinearLayout listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        listBox.setPadding(0, dp(this, 10), 0, dp(this, 16));
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

    // ---------- 我的卡片 ----------
    View buildMinePage() {
        LinearLayout page = basePage("我的卡片");
        List<Card> mineCards = new ArrayList<>();
        for (Card c : Store.all) if (mine.contains(c.id)) mineCards.add(c);

        Set<String> orgs = new HashSet<>();
        int noFtf = 0;
        for (Card c : mineCards) {
            if (c.org != null && !c.org.isEmpty()) orgs.add(c.org);
            if ("无".equals(c.spec("货币转换费（FTF）"))) noFtf++;
        }
        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable hg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
            new int[]{Color.rgb(0x14, 0x1C, 0x2C), Color.rgb(0x1D, 0x2F, 0x4D)});
        hg.setCornerRadius(dp(this, 18));
        hero.setBackground(hg);
        hero.setPadding(dp(this, 16), dp(this, 14), dp(this, 16), dp(this, 14));
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = dp(this, 10);
        page.addView(hero, hlp);
        hero.addView(tv(this, mineCards.size() + " 张卡 · 我的卡包", 18, Color.WHITE, true));
        hero.addView(tv(this, "组织覆盖 " + orgs.size() + " 家 · 无转换费 " + noFtf + " 张", 12.5f, Color.argb(205, 255, 255, 255), false));

        page.addView(buildCustomSection());

        if (mineCards.isEmpty()) {
            TextView empty = tv(this, "还没有从卡库收藏的卡。去「全部卡片」点开任意一张，加入我的卡片。", 13.5f, Color.rgb(0x8E, 0x8E, 0x93), false);
            LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            elp.topMargin = dp(this, 12);
            page.addView(empty, elp);
            return page;
        }

        GridView g = new GridView(this);
        g.setNumColumns(2);
        g.setHorizontalSpacing(dp(this, 10));
        g.setVerticalSpacing(dp(this, 10));
        g.setPadding(0, dp(this, 10), 0, dp(this, 16));
        final CardAdapter ad = new CardAdapter(mineCards);
        g.setAdapter(ad);
        g.setOnItemClickListener((p, v, i, id) -> openDetail(ad.data.get(i)));
        page.addView(g, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return page;
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
            head.setOnClickListener(v -> { customOpen = !customOpen; showTab("mine"); });
        }

        if (customCards.isEmpty()) {
            TextView hint = tv(this, "卡库里没有的卡可以自己记一张：填名字、银行、挑个颜色、写备注。", 12, Color.rgb(0x8E, 0x8E, 0x93), false);
            LinearLayout.LayoutParams hlp2 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            hlp2.topMargin = dp(this, 8);
            sec.addView(hint, hlp2);
            return sec;
        }

        if (!customOpen) {
            // 收起态：最近 3 张叠成一叠（最新在最上），只露顶部一张的名字
            int show = Math.min(3, customCards.size());
            LinearLayout deck = new LinearLayout(this);
            deck.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            dlp.topMargin = dp(this, 10);
            sec.addView(deck, dlp);
            for (int k = show - 1; k >= 0; k--) {
                CustomCard c = customCards.get(customCards.size() - 1 - k);
                LinearLayout strip = new LinearLayout(this);
                strip.setOrientation(LinearLayout.HORIZONTAL);
                strip.setGravity(Gravity.CENTER_VERTICAL);
                strip.setBackground(customGradient(c.style));
                strip.setPadding(dp(this, 12), dp(this, 8), dp(this, 12), dp(this, 8));
                LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                stLp.leftMargin = dp(this, k * 10);
                stLp.rightMargin = dp(this, k * 10);
                if (k < show - 1) stLp.topMargin = dp(this, -6);
                deck.addView(strip, stLp);
                if (k == 0) {
                    strip.addView(tv(this, c.name, 13, Color.WHITE, true), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                    String meta = (c.bank == null ? "" : c.bank) + (c.org != null && !c.org.isEmpty() ? " · " + c.org : "");
                    strip.addView(tv(this, meta, 11, Color.argb(210, 255, 255, 255), false));
                } else {
                    strip.addView(tv(this, " ", 13, Color.WHITE, false));
                }
            }
            return sec;
        }

        // 展开态：每张一条渐变色带，底部向下一张颜色衔接
        for (int i = 0; i < customCards.size(); i++) {
            final CustomCard c = customCards.get(i);
            final int idx = i;
            LinearLayout tile = new LinearLayout(this);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setBackground(customGradient(c.style));
            tile.setPadding(dp(this, 12), dp(this, 10), dp(this, 12), dp(this, 10));
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tlp.topMargin = i == 0 ? dp(this, 10) : dp(this, 8);
            sec.addView(tile, tlp);

            LinearLayout topRow = new LinearLayout(this);
            topRow.setOrientation(LinearLayout.HORIZONTAL);
            topRow.setGravity(Gravity.CENTER_VERTICAL);
            tile.addView(topRow);
            TextView idxTv = tv(this, String.valueOf(i + 1), 11, Color.WHITE, true);
            idxTv.setBackground(roundRect(Color.argb(70, 255, 255, 255), 999, this));
            idxTv.setPadding(dp(this, 7), dp(this, 2), dp(this, 7), dp(this, 2));
            idxTv.setGravity(Gravity.CENTER);
            topRow.addView(idxTv);
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
            tx.addView(tv(this, meta, 11, Color.argb(215, 255, 255, 255), false));
            if (c.note != null && !c.note.isEmpty()) {
                TextView nt = tv(this, c.note, 11.5f, Color.argb(225, 255, 255, 255), false);
                nt.setMaxLines(2);
                nt.setEllipsize(android.text.TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams nlp2 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                nlp2.topMargin = dp(this, 5);
                tile.addView(nt, nlp2);
            }
            LinearLayout acts = new LinearLayout(this);
            acts.setOrientation(LinearLayout.HORIZONTAL);
            acts.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams actLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            actLp.topMargin = dp(this, 8);
            tile.addView(acts, actLp);
            acts.addView(customActBtn("↑", i == 0, v -> moveCustom(idx, -1)));
            acts.addView(customActBtn("↓", i == customCards.size() - 1, v -> moveCustom(idx, 1)));
            acts.addView(customActBtn("编辑", false, v -> openCustomForm(c)));
            acts.addView(customActBtn("删除", false, v -> confirmDeleteCustom(c)));
            View spacer = new View(this);
            acts.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
            TextView detailHint = tv(this, "点色带看详情 ›", 11, Color.argb(220, 255, 255, 255), false);
            acts.addView(detailHint);
            tile.setOnClickListener(v -> openCustomDetail(c));
        }
        return sec;
    }

    Button customActBtn(String label, boolean disabled, View.OnClickListener onClick) {
        Button b = new Button(this);
        b.setText(label); b.setTextSize(11.5f); b.setAllCaps(false);
        b.setMinWidth(0); b.setMinHeight(0);
        b.setPadding(dp(this, 9), dp(this, 4), dp(this, 9), dp(this, 4));
        b.setTextColor(disabled ? Color.argb(140, 255, 255, 255) : Color.WHITE);
        b.setBackground(roundRect(Color.argb(disabled ? 30 : 55, 255, 255, 255), 999, this));
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
        showTab("mine");
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
                showTab("mine");
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

        AlertDialog dlg = new AlertDialog.Builder(this)
            .setView(box)
            .setNegativeButton("关闭", null)
            .setNeutralButton("删除", (d, w) -> confirmDeleteCustom(c))
            .setPositiveButton("编辑", (d, w) -> openCustomForm(c))
            .create();
        if (c.bank != null && !c.bank.isEmpty()) {
            dlg.setButton(AlertDialog.BUTTON_NEUTRAL, "删除", (d, w) -> confirmDeleteCustom(c));
            // 搜银行放在关闭后执行，避免与编辑/删除挤同一个按钮位：用标题区下小字提示替代
        }
        dlg.show();
        // 在卡库里搜这家银行（详情底部附加一行可点文字，点完关详情跳首页搜索）
        if (c.bank != null && !c.bank.isEmpty()) {
            TextView find = tv(this, "在卡库里搜「" + c.bank + "」 ›", 13, Color.rgb(0x0A, 0x5C, 0xD6), true);
            find.setPadding(dp(this, 18), dp(this, 8), dp(this, 18), dp(this, 10));
            find.setOnClickListener(v -> {
                try { dlg.dismiss(); } catch (Exception e) {}
                query = c.bank;
                showTab("home");
                if (searchBox != null) searchBox.setText(c.bank);
            });
            // AlertDialog 内容区外无法直接追加，改为塞进 box（对话框已 show，追加后需请求重排）
            box.addView(find);
        }
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
            showTab("mine");
        });
        dlg.setOnDismissListener(d -> { if (customDialog == dlg) customDialog = null; });
        dlg.show();
    }

    // ---------- 资讯 / 设置 ----------
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
        newsListBox = new LinearLayout(this);
        newsListBox.setOrientation(LinearLayout.VERTICAL);
        newsListBox.setPadding(0, dp(this, 2), 0, dp(this, 16));
        sv.addView(newsListBox);
        page.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        renderNews();
        return page;
    }

    View buildSettingsPage() {
        LinearLayout page = basePage("设置");
        View wizEntry = settingRow("情景选卡", "出国留学 / 出境旅游 / 海淘网购 / 日常使用，按场景挑卡 ›");
        wizEntry.setOnClickListener(v -> openWizard());
        page.addView(wizEntry);
        page.addView(settingRow("版本", "0.7-native（Phase 3a）"));
        page.addView(settingRow("关于卡盒", "原生版：纯 Java 手写界面，数据与现行版共用同一份卡库"));
        page.addView(settingRow("迁移进度", "全部卡片 / 详情 / 我的卡片 / 学生推荐 / 筛选 / 资讯 / 情景选卡 已迁移；自定义卡、我的卡片拖动、字体与界面大小在后续阶段"));
        return page;
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
        page.setPadding(dp(this, 14), dp(this, 12), dp(this, 14), 0);
        page.addView(tv(this, title, 24, Color.rgb(0x1C, 0x1C, 0x1E), true));
        return page;
    }

    @Override
    public void onBackPressed() {
        if (detailCard != null) { closeDetail(); return; }
        if (filterSheet != null) { closeFilterSheet(); return; }
        if (wizardOpen) {
            if (wizSc == null) closeWizard(); else wizGoBack();
            return;
        }
        super.onBackPressed();
    }
}
