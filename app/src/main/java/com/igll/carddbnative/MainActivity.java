package com.igll.carddbnative;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
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
                    cd.score = o.optDouble("score", 0); cd.scoreLabel = o.optString("score_label");
                    cd.specs = o.optJSONObject("specs");
                    cd.variants = o.optJSONArray("variants");
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

    // ---------- 全局状态 ----------
    SharedPreferences prefs;
    Set<String> mine = new HashSet<>();
    String tab = "home";
    Card detailCard = null;

    FrameLayout content;
    LinearLayout navBar;
    Map<String, View> pages = new HashMap<>();
    Map<String, Button> navBtns = new HashMap<>();

    // 首页控件（切页回来保持搜索词）
    EditText searchBox;
    GridView homeGrid;
    CardAdapter homeAdapter;
    String query = "";
    TextView homeCount;
    Button filterBtn;
    View filterSheet = null;

    // 筛选状态（Phase 2a-1：与混合版 chipRow 相同的单选切换语义，点已选项再点一次取消）
    String filterType = null;   // "debit" / "credit" / null
    String filterOrg = null;    // org 代码 / null
    String filterStatus = null; // "在发" / "已停发" / null

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window w = getWindow();
        w.setStatusBarColor(Color.WHITE);
        w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        prefs = getSharedPreferences("cardbox_native", MODE_PRIVATE);
        try { mine = new HashSet<>(prefs.getStringSet("mine_ids", new HashSet<String>())); } catch (Exception e) { mine = new HashSet<>(); }
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

        homeGrid = new GridView(this);
        homeGrid.setNumColumns(2);
        homeGrid.setHorizontalSpacing(dp(this, 10));
        homeGrid.setVerticalSpacing(dp(this, 10));
        homeGrid.setPadding(0, dp(this, 10), 0, dp(this, 16));
        homeGrid.setClipToPadding(false);
        homeAdapter = new CardAdapter(filteredHome());
        homeGrid.setAdapter(homeAdapter);
        homeGrid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> p, View v, int i, long id) { openDetail(homeAdapter.data.get(i)); }
        });
        page.addView(homeGrid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
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
            if (q.isEmpty()) { out.add(c); continue; }
            String bin = c.spec("BIN");
            if (c.name.toLowerCase().contains(q) || c.bank.toLowerCase().contains(q)
                || bin.contains(q) || (c.keywords != null && c.keywords.toLowerCase().contains(q))) out.add(c);
        }
        return out;
    }

    void refreshHome() {
        if (homeAdapter == null) return;
        homeAdapter.data = filteredHome();
        homeAdapter.notifyDataSetChanged();
        if (homeCount != null) homeCount.setText("共 " + homeAdapter.data.size() + " 张");
        if (filterBtn != null) {
            int n = (filterType != null ? 1 : 0) + (filterOrg != null ? 1 : 0) + (filterStatus != null ? 1 : 0);
            filterBtn.setText(n == 0 ? "筛选" : "筛选 · " + n);
        }
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
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.gravity = Gravity.BOTTOM;
        sheet.addView(panel, plp);
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

    // ---------- 详情页 ----------
    void openDetail(Card c) {
        detailCard = c;
        content.removeAllViews();
        navBar.setVisibility(View.GONE);
        content.addView(buildDetailPage(c));
    }

    void closeDetail() {
        detailCard = null;
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

    // ---------- 学生推荐 ----------
    View buildStudentPage() {
        LinearLayout page = basePage("学生推荐");
        List<Card> stu = new ArrayList<>();
        for (Card c : Store.all) {
            if ("已停发".equals(c.status)) continue;
            String hay = (c.name + " " + (c.keywords == null ? "" : c.keywords));
            if (hay.contains("学生") || hay.contains("留学") || hay.contains("校园")) stu.add(c);
        }
        stu.sort((a, b2) -> Double.compare(b2.score, a.score));
        page.addView(tv(this, stu.size() + " 张适合学生的卡（按评分排）", 12.5f, Color.rgb(0x8E, 0x8E, 0x93), false));
        GridView g = new GridView(this);
        g.setNumColumns(1);
        g.setVerticalSpacing(dp(this, 10));
        g.setPadding(0, dp(this, 10), 0, dp(this, 16));
        final CardAdapter ad = new CardAdapter(stu);
        g.setAdapter(ad);
        g.setOnItemClickListener((p, v, i, id) -> openDetail(ad.data.get(i)));
        page.addView(g, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return page;
    }

    // ---------- 我的卡片 ----------
    View buildMinePage() {
        LinearLayout page = basePage("我的卡片");
        List<Card> mineCards = new ArrayList<>();
        for (Card c : Store.all) if (mine.contains(c.id)) mineCards.add(c);

        if (mineCards.isEmpty()) {
            page.addView(tv(this, "还没有收藏的卡。去「全部卡片」点开任意一张，加入我的卡片。", 13.5f, Color.rgb(0x8E, 0x8E, 0x93), false));
            return page;
        }
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
        page.addView(hero);
        hero.addView(tv(this, mineCards.size() + " 张卡 · 我的卡包", 18, Color.WHITE, true));
        hero.addView(tv(this, "组织覆盖 " + orgs.size() + " 家 · 无转换费 " + noFtf + " 张", 12.5f, Color.argb(205, 255, 255, 255), false));

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

    // ---------- 资讯 / 设置 ----------
    View buildNewsPage() {
        LinearLayout page = basePage("资讯");
        page.addView(tv(this, "新卡发布、权益调整、停发换卡——这一页正在从网页版迁移过来，下一阶段补上。", 13.5f, Color.rgb(0x8E, 0x8E, 0x93), false));
        return page;
    }

    View buildSettingsPage() {
        LinearLayout page = basePage("设置");
        page.addView(settingRow("版本", "0.2-native（Phase 2）"));
        page.addView(settingRow("关于卡盒", "原生版：纯 Java 手写界面，数据与现行版共用同一份卡库"));
        page.addView(settingRow("迁移进度", "全部卡片 / 详情 / 我的卡片 / 学生推荐 已迁移；筛选、情景选卡、资讯、字体与界面大小在后续阶段"));
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
        super.onBackPressed();
    }
}
