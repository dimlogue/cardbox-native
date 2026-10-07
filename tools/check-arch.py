#!/usr/bin/env python3
"""卡盒改版门禁检查（Step0 建，Step1 补 catch 基线）。

五项检查（第 5 项为 2.79 批2/Q164 新增的渲染出口与弹层册门禁）：
  1. 行数上限：MainActivity.java 只许减不许增。基线在 tools/line-baseline.txt
     （首跑若缺失则以当前行数落基线并注明）。当前 > 基线 => 报警；
     加 --strict 时退出码为 1（构建失败模式，Step9 再默认开启）。
  2. 异常门禁：统计全部 catch 块——空吞（空体且无注释无日志）/白名单豁免
     （空体但带 `// swallow-ok: 理由` 标记）/仅注释/有体无日志/有日志。
     空吞基线 CATCH_EMPTY_BASELINE（2026-10-08 实测 212 写死）只许减不许增：
     超基线即记 problem，--strict 退出 1；确需空吞须在 catch 体内加白名单
     标记放行（不计入空吞）。空吞数下降时人工同步调低基线值。
     纪律：新增 catch 必须带 logErr/Log 调用、注释说明或白名单标记。
     明细写 tools/catch-audit.txt。
  3. 静态可变状态清单：static 非 final 字段逐条导出
     tools/static-state-inventory.txt（同行多声明逐字段拆条；
     Step3 AppState 收口的对照清单）。

用法：bash tools/check-arch.sh [--strict]
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MAIN = os.path.join(ROOT, "app/src/main/java/com/igll/carddbnative/MainActivity.java")
BASELINE_FILE = os.path.join(ROOT, "tools", "line-baseline.txt")
CATCH_AUDIT = os.path.join(ROOT, "tools", "catch-audit.txt")
STATIC_INV = os.path.join(ROOT, "tools", "static-state-inventory.txt")
STRICT = "--strict" in sys.argv

# Step1（2026-10-08）空吞 catch 基线：当日实测写死，只许减不许增。
# 空吞数下降时人工把本值同步调低；上升即 problem（--strict 失败）。
# 确需空吞的 catch 在体内写 `// swallow-ok: 理由` 即归白名单豁免，不计空吞。
CATCH_EMPTY_BASELINE = 212
SWALLOW_OK_MARKER = "swallow-ok"

# i18n 批1（2026-10-08）基线：字符串三套机制冻结 + 未收编字面量盘点。
# 基线值落 tools/i18n-baseline.txt（实测登记，只许减不许增，降了要同步调低）。
# 口径与批1盘点扫描一致：L( 调用按 `(?<![A-Za-z])L\("` 计数（36 真调用+定义/注释，
# 批1实测 42 写死基线）；EN_TXT 条数按 `EN_TXT\.put\(` 计数（272 冻结，
# 含多 put 同行逐个计）；未收编候选 = 字面量含 CJK 或 [A-Za-z]{2,} 且非纯标识符/
# 纯格式串、且不在 STR.put / EN_TXT.put 声明行、不作 S("key") 键名（批1实测 1668）。
I18N_BASELINE_FILE = os.path.join(ROOT, "tools", "i18n-baseline.txt")

# i18n 批2（Q164/2026-10-08）第 5 段：渲染出口枚举 + 弹层册计数。
# 出口 = 文案最终落到屏幕的调用点（setText/setHint/setTitle/Toast/drawText/
# setContentDescription/setError）。口径：出口点只许减不许增——新增出口
# 必须随附 STR 落串或在本册登记豁免，并在 tools/i18n-outlet-baseline.txt
# 里人工调高登记（登记即声明已知）。弹层册 tools/popup-registry.txt 的
# POPUP_POINTS 与手工条目合计为弹层点基线，新弹层先入册再调基线。
# 注意（门禁注释，用户裁量原文）：计数只防回归、不证全覆盖——出口数
# 不涨只说明没有新增未登记的渲染面，单个出口背后的文案是否真走 STR
# 仍靠逐页销项与真机验收，本段不作覆盖证明。
OUTLET_BASELINE_FILE = os.path.join(ROOT, "tools", "i18n-outlet-baseline.txt")
POPUP_REGISTRY_FILE = os.path.join(ROOT, "tools", "popup-registry.txt")
OUTLET_PATTERNS = [
    ("setText", re.compile(r"\.setText\(")),
    ("setHint", re.compile(r"\.setHint\(")),
    ("setTitle", re.compile(r"\.setTitle\(")),
    ("Toast", re.compile(r"Toast\.makeText")),
    ("drawText", re.compile(r"\.drawText\(")),
    ("setContentDescription", re.compile(r"\.setContentDescription\(")),
    ("setError", re.compile(r"\.setError\(")),
]


def strip_code(text):
    """去掉字符串/字符字面量与注释，保留行结构（换行与列位近似），供大括号配对。"""
    out = []
    i, n = 0, len(text)
    state = None  # None | 'line' | 'block' | 'str' | 'chr'
    while i < n:
        ch = text[i]
        nxt = text[i + 1] if i + 1 < n else ""
        if state is None:
            if ch == "/" and nxt == "/":
                state = "line"; out.append("  "); i += 2; continue
            if ch == "/" and nxt == "*":
                state = "block"; out.append("  "); i += 2; continue
            if ch == '"':
                state = "str"; out.append(" "); i += 1; continue
            if ch == "'":
                state = "chr"; out.append(" "); i += 1; continue
            out.append(ch); i += 1; continue
        if state == "line":
            if ch == "\n":
                state = None; out.append("\n")
            else:
                out.append(" ")
            i += 1; continue
        if state == "block":
            if ch == "*" and nxt == "/":
                state = None; out.append("  "); i += 2; continue
            out.append("\n" if ch == "\n" else " "); i += 1; continue
        if state in ("str", "chr"):
            if ch == "\\":
                out.append("  "); i += 2; continue
            if (state == "str" and ch == '"') or (state == "chr" and ch == "'"):
                state = None
            out.append("\n" if ch == "\n" else " "); i += 1; continue
    return "".join(out)


def main():
    src = open(MAIN, encoding="utf-8").read()
    lines = src.split("\n")
    n_lines = len(lines) - (1 if lines and lines[-1] == "" else 0)
    problems = []

    # ---------- 1. 行数上限 ----------
    baseline = None
    if os.path.exists(BASELINE_FILE):
        for ln in open(BASELINE_FILE, encoding="utf-8"):
            m = re.search(r"\d+", ln)
            if m:
                baseline = int(m.group(0)); break
    if baseline is None:
        with open(BASELINE_FILE, "w", encoding="utf-8") as f:
            f.write(f"{n_lines}\n# 首跑落基线（check-arch 自动生成）；此后 MainActivity 只许减不许增。\n")
        baseline = n_lines
        print(f"[行数] 基线文件缺失，已以当前行数落基线：{baseline}")
    over = n_lines - baseline
    if over > 0:
        problems.append(f"MainActivity 行数 {n_lines} 超过基线 {baseline}（+{over}）——只许减不许增")
        print(f"[行数] ⚠ {n_lines} 行，超基线 {baseline}（+{over}）")
    else:
        print(f"[行数] OK：{n_lines} 行（基线 {baseline}，余量 {-over}）")

    # ---------- 2. catch 审计 ----------
    clean = strip_code(src)
    catch_re = re.compile(r"catch\s*\(\s*([\w.$]+(?:\s*\|\s*[\w.$]+)*)\s+(\w+)\s*\)")
    cats = {"with_log": [], "whitelisted": [], "comment_only": [], "nolog_body": [], "empty": []}
    pos = 0
    while True:
        m = catch_re.search(clean, pos)
        if not m:
            break
        line_no = clean.count("\n", 0, m.start()) + 1
        # 找 body 的 '{'
        b = clean.find("{", m.end())
        if b < 0:
            pos = m.end(); continue
        depth, j = 0, b
        while j < len(clean):
            if clean[j] == "{":
                depth += 1
            elif clean[j] == "}":
                depth -= 1
                if depth == 0:
                    break
            j += 1
        body_clean = clean[b + 1:j].strip()
        body_raw = src[b + 1:j]
        has_log = bool(re.search(r"Log\.|logErr\s*\(|printStackTrace", body_raw))
        has_comment = ("//" in body_raw) or ("/*" in body_raw)
        has_whitelist = SWALLOW_OK_MARKER in body_raw
        entry = f"L{line_no} catch ({m.group(1)} {m.group(2)})"
        if has_log:
            cats["with_log"].append(entry)
        elif body_clean == "" and has_whitelist:
            cats["whitelisted"].append(entry)
        elif body_clean == "" and has_comment:
            cats["comment_only"].append(entry)
        elif body_clean == "":
            cats["empty"].append(entry)
        else:
            cats["nolog_body"].append(entry)
        pos = j + 1 if j > b else m.end()
    total = sum(len(v) for v in cats.values())
    print(f"[异常] catch 共 {total}：有日志 {len(cats['with_log'])} · 白名单豁免 {len(cats['whitelisted'])}"
          f" · 仅注释 {len(cats['comment_only'])} · 有体无日志 {len(cats['nolog_body'])} · 空吞 {len(cats['empty'])}")
    print("       纪律：新增 catch 必须带 logErr/Log、注释说明或 `// swallow-ok: 理由` 白名单标记；关键路径（数据/OTA/解码/玻璃/展柜）必须带日志。")
    empty_n = len(cats["empty"])
    if empty_n > CATCH_EMPTY_BASELINE:
        problems.append(f"空吞 catch {empty_n} 超过基线 {CATCH_EMPTY_BASELINE}（+{empty_n - CATCH_EMPTY_BASELINE}）"
                        f"——基线只许减不许增；确需空吞须在 catch 体内加 `// {SWALLOW_OK_MARKER}: 理由` 白名单标记")
        print(f"[异常] ⚠ 空吞 {empty_n} 超基线 {CATCH_EMPTY_BASELINE}（+{empty_n - CATCH_EMPTY_BASELINE}）：")
        for e in cats["empty"][:20]:
            print("       " + e)
        if empty_n > 20:
            print(f"       ……余 {empty_n - 20} 条见 tools/catch-audit.txt")
    else:
        tip = "；空吞已低于基线，请人工调低 CATCH_EMPTY_BASELINE" if empty_n < CATCH_EMPTY_BASELINE else ""
        print(f"[异常] 空吞基线 OK：{empty_n}（基线 {CATCH_EMPTY_BASELINE}，余量 {CATCH_EMPTY_BASELINE - empty_n}）{tip}")
    with open(CATCH_AUDIT, "w", encoding="utf-8") as f:
        f.write(f"# catch 审计明细（check-arch 生成）total={total}\n")
        for k in ("empty", "whitelisted", "nolog_body", "comment_only", "with_log"):
            f.write(f"\n## {k} ({len(cats[k])})\n")
            for e in cats[k]:
                f.write(e + "\n")

    # ---------- 3. 静态可变状态清单 ----------
    field_re = re.compile(
        r"^\s*(?:public\s+|private\s+|protected\s+)?static\s+"
        r"(?!final\b)(?!class\b)(?!interface\b)(?!enum\b)(?!\{)"
        r"([\w.$]+(?:<[^;(){}]*>)?(?:\[\])?)\s+(.+?)(;|\{)\s*$")
    fields = []
    for i, raw in enumerate(lines):
        ln = re.sub(r"//.*$", "", raw)
        ln = re.sub(r'"(?:[^"\\]|\\.)*"', '""', ln)
        m = field_re.match(ln)
        if not m:
            continue
        decl = (m.group(1) + " " + m.group(2)).strip()
        ender = m.group(3)
        if "(" in decl.split("=")[0]:
            continue  # 方法声明（名后跟括号）
        if ender == "{" and "=" not in decl:
            continue  # 方法/静态块的 '{' 收尾，无 '=' 不是字段
        # 同行多声明拆条（如 `Typeface a=null, b=null;` 逐字段一条；
        # 逗号只在括号/泛型/数组字面量之外才是分隔符）
        parts, depth, cur = [], 0, ""
        for ch in m.group(2):
            if ch in "(<[{":
                depth += 1
            elif ch in ")>]}":
                depth -= 1
            if ch == "," and depth == 0:
                parts.append(cur); cur = ""
            else:
                cur += ch
        parts.append(cur)
        for part in parts:
            part = part.strip()
            if part:
                fields.append(f"L{i + 1}: static {m.group(1)} {part};")
    with open(STATIC_INV, "w", encoding="utf-8") as f:
        f.write("# MainActivity 静态可变字段清单（check-arch 生成；Step3 收口 AppState 对照）\n")
        for x in fields:
            f.write(x + "\n")
    print(f"[静态] static 非 final 字段 {len(fields)} 条 -> tools/static-state-inventory.txt")

    # ---------- 4. i18n 基线（三套机制冻结 + 未收编字面量只减不增） ----------
    l_calls = len(re.findall(r'(?<![A-Za-z])L\("', src))
    entxt_entries = len(re.findall(r'EN_TXT\.put\(', src))
    str_decl_lines, entxt_decl_lines = set(), set()
    for i, ln in enumerate(lines, 1):
        if re.search(r'STR\.put\("', ln):
            str_decl_lines.add(i)
        if re.search(r'EN_TXT\.put\(', ln):
            entxt_decl_lines.add(i)
    keylike_re = re.compile(r'^[A-Za-z0-9_.\-/:]+$')
    cand_n = 0
    for i, ln in enumerate(lines, 1):
        if i in str_decl_lines or i in entxt_decl_lines:
            continue
        for m in re.finditer(r'"((?:[^"\\]|\\.)*)"', ln):
            t = m.group(1)
            if len(t) < 2:
                continue
            pre = ln[:m.start()].rstrip()
            if pre.endswith("S("):
                continue
            has_cjk = bool(re.search(r'[\u4e00-\u9fff]', t))
            has_enw = bool(re.search(r'[A-Za-z]{2,}', t))
            if not has_cjk and not has_enw:
                continue
            if keylike_re.match(t) and not has_cjk and ' ' not in t:
                continue  # 纯标识符/键名/路径
            if not has_cjk and re.search(r'%[-+0-9.]*[a-zA-Z%]', t) \
                    and t.strip('%0123456789. -+dfsx') == '':
                continue  # 纯格式串（口径同批1盘点扫描）
            cand_n += 1
    i18n_base = {}
    if os.path.exists(I18N_BASELINE_FILE):
        for ln in open(I18N_BASELINE_FILE, encoding="utf-8"):
            m = re.match(r"\s*(L_CALLS|EN_TXT|CANDIDATES)\s*=\s*(\d+)", ln)
            if m:
                i18n_base[m.group(1)] = int(m.group(2))
    if not i18n_base:
        problems.append("i18n 基线文件 tools/i18n-baseline.txt 缺失或无有效键值")
        print("[i18n] ⚠ 基线文件缺失")
    else:
        cur = {"L_CALLS": l_calls, "EN_TXT": entxt_entries, "CANDIDATES": cand_n}
        labels = {"L_CALLS": "L() 调用（冻结）", "EN_TXT": "EN_TXT 条数（冻结）",
                  "CANDIDATES": "未收编候选字面量（只减不增）"}
        bad = []
        for k in ("L_CALLS", "EN_TXT", "CANDIDATES"):
            if k in i18n_base and cur[k] > i18n_base[k]:
                bad.append(f"{labels[k]} {cur[k]} 超基线 {i18n_base[k]}（+{cur[k] - i18n_base[k]}）")
        print(f"[i18n] L() {l_calls} / EN_TXT {entxt_entries} / 候选 {cand_n}（基线 "
              f"L={i18n_base.get('L_CALLS')} EN={i18n_base.get('EN_TXT')} 候选={i18n_base.get('CANDIDATES')}）")
        if bad:
            for b in bad:
                problems.append("i18n 基线：" + b + "——新串只许进 STR（S(key)）；L()/EN_TXT 冻结新增；候选降了请同步调低基线")
            print("[i18n] ⚠ " + "；".join(bad))
        else:
            low = [k for k in cur if k in i18n_base and cur[k] < i18n_base[k]]
            tip = "；已低于基线，请人工调低 i18n-baseline.txt：" + ",".join(low) if low else ""
            print(f"[i18n] 基线 OK（只减不增）{tip}")

    # ---------- 5. 渲染出口枚举 + 弹层册（批2 Q164） ----------
    # 计数只防回归、不证全覆盖：出口总数与弹层登记数双基线，只涨即报警；
    # 出口背后的文案是否真走 STR 仍靠逐页销项与真机验收（见段首注释）。
    outlet_counts = {name: len(pat.findall(src)) for name, pat in OUTLET_PATTERNS}
    outlet_total = sum(outlet_counts.values())
    outlet_base = {}
    if os.path.exists(OUTLET_BASELINE_FILE):
        for ln in open(OUTLET_BASELINE_FILE, encoding="utf-8"):
            m = re.match(r"\s*(OUTLETS|POPUPS)\s*=\s*(\d+)", ln)
            if m:
                outlet_base[m.group(1)] = int(m.group(2))
    popup_points = None
    if os.path.exists(POPUP_REGISTRY_FILE):
        for ln in open(POPUP_REGISTRY_FILE, encoding="utf-8"):
            m = re.match(r"POPUP_POINTS\s*=\s*(\d+)", ln)
            if m:
                popup_points = int(m.group(1))
    detail = " ".join(f"{k}={v}" for k, v in outlet_counts.items())
    print(f"[出口] 渲染出口共 {outlet_total}（{detail}）；弹层册登记 {popup_points} 点"
          f"（基线 OUTLETS={outlet_base.get('OUTLETS')} POPUPS={outlet_base.get('POPUPS')}）")
    if not outlet_base or popup_points is None:
        problems.append("i18n 出口/弹层册基线缺失（tools/i18n-outlet-baseline.txt 或 tools/popup-registry.txt）")
        print("[出口] ⚠ 基线或弹层册缺失")
    else:
        obad = []
        if outlet_total > outlet_base.get("OUTLETS", 0):
            obad.append(f"渲染出口 {outlet_total} 超基线 {outlet_base.get('OUTLETS')}（+{outlet_total - outlet_base.get('OUTLETS', 0)}）"
                        "——新出口须随 STR 落串或在弹层册登记豁免，登记后人工调高基线；计数只防回归不证覆盖")
        if popup_points < outlet_base.get("POPUPS", 0):
            obad.append(f"弹层册登记 {popup_points} 低于基线 {outlet_base.get('POPUPS')}——弹层条目只许增不许删")
        if obad:
            for b in obad:
                problems.append("i18n 出口门禁：" + b)
            print("[出口] ⚠ " + "；".join(obad))
        else:
            low = outlet_total < outlet_base.get("OUTLETS", 0)
            tip = "；出口已低于基线，请人工调低 i18n-outlet-baseline.txt" if low else ""
            print(f"[出口] 基线 OK（只涨报警）{tip}")

    # ---------- 结论 ----------
    if problems:
        for p in problems:
            print("⚠ " + p)
        if STRICT:
            print("STRICT: 构建失败")
            return 1
        print("（警告模式：未拦截；Step9 起 --strict 转构建失败）")
    else:
        print("门禁结论：通过（STRICT 模式）" if STRICT else "门禁结论：通过（警告模式）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
