#!/usr/bin/env python3
"""卡盒改版门禁检查（Step0，警告模式）。

三项检查（对应改版方案 Step0/Step9 的 grep 门禁，先警告、后硬化）：
  1. 行数上限：MainActivity.java 只许减不许增。基线在 tools/line-baseline.txt
     （首跑若缺失则以当前行数落基线并注明）。当前 > 基线 => 报警；
     加 --strict 时退出码为 1（构建失败模式，Step9 再默认开启）。
  2. 异常门禁：统计全部 catch 块——空吞（空体且无注释）/仅注释/有体无日志/有日志。
     纪律：新增 catch 必须带 logErr/Log 调用或注释说明。明细写 tools/catch-audit.txt。
  3. 静态可变状态清单：static 非 final 字段逐条导出 tools/static-state-inventory.txt
     （Step3 AppState 收口的对照清单）。

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
    cats = {"with_log": [], "comment_only": [], "nolog_body": [], "empty": []}
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
        entry = f"L{line_no} catch ({m.group(1)} {m.group(2)})"
        if has_log:
            cats["with_log"].append(entry)
        elif body_clean == "" and has_comment:
            cats["comment_only"].append(entry)
        elif body_clean == "":
            cats["empty"].append(entry)
        else:
            cats["nolog_body"].append(entry)
        pos = j + 1 if j > b else m.end()
    total = sum(len(v) for v in cats.values())
    print(f"[异常] catch 共 {total}：有日志 {len(cats['with_log'])} · 仅注释 {len(cats['comment_only'])}"
          f" · 有体无日志 {len(cats['nolog_body'])} · 空吞 {len(cats['empty'])}")
    print("       纪律：新增 catch 必须带 logErr/Log 或注释说明；关键路径（数据/OTA/解码/玻璃/展柜）必须带日志。")
    with open(CATCH_AUDIT, "w", encoding="utf-8") as f:
        f.write(f"# catch 审计明细（check-arch 生成）total={total}\n")
        for k in ("empty", "nolog_body", "comment_only", "with_log"):
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
        fields.append(f"L{i + 1}: static {decl};")
    with open(STATIC_INV, "w", encoding="utf-8") as f:
        f.write("# MainActivity 静态可变字段清单（check-arch 生成；Step3 收口 AppState 对照）\n")
        for x in fields:
            f.write(x + "\n")
    print(f"[静态] static 非 final 字段 {len(fields)} 条 -> tools/static-state-inventory.txt")

    # ---------- 结论 ----------
    if problems:
        for p in problems:
            print("⚠ " + p)
        if STRICT:
            print("STRICT: 构建失败")
            return 1
        print("（警告模式：未拦截；Step9 起 --strict 转构建失败）")
    else:
        print("门禁结论：通过（警告模式）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
