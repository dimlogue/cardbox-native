#!/usr/bin/env python3
"""弹层构造点机器枚举（初版册生成器，Q164/2.79 批2 立）。

口径：全 App 无系统 Dialog/PopupWindow（grep 实证 0 命中），弹层一律为
手写 FrameLayout 贴屏挂载。本册按两路机器枚举：①统一玻璃窗工厂
buildUpdateSheet( 的调用点（每点即一窗）；②content.addView(sheet|overlay|
ov|pop|menu|full…) 的挂载点（按所在方法归名）。行号仅供当日定位，
后续以方法名为准；销项结论（STR/EN 覆盖/豁免）人工维护在本册条目尾。

纪律：新弹层落地必须在本册增条目；check-arch.sh 第 5 段以本册登记数
与渲染出口计数双基线防回归——计数只防回归、不证全覆盖（见门禁注释）。
用法：python3 tools/gen-popup-registry.py [> tools/popup-registry.draft]
人工核对后并入 tools/popup-registry.txt（勿直接覆盖销项标注）。
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MAIN = os.path.join(ROOT, "app/src/main/java/com/igll/carddbnative/MainActivity.java")
lines = open(MAIN, encoding="utf-8").read().split("\n")

meth_re = re.compile(r'^\s{4,8}(?:static\s+|final\s+|private\s+|public\s+|protected\s+)*[\w<>\[\].]+\s+(\w+)\s*\(')


def enclosing(i):
    for j in range(i, -1, -1):
        s = lines[j].strip()
        m = meth_re.match(lines[j])
        if m and not s.startswith(("if", "for", "while", "switch", "catch", "return", "else")):
            return m.group(1)
    return "?"


entries = []
for i, ln in enumerate(lines):
    st = ln.strip()
    if st.startswith("//") or st.startswith("*"):
        continue
    if "buildUpdateSheet(" in ln and "FrameLayout buildUpdateSheet" not in ln:
        entries.append((i + 1, "update-sheet（统一玻璃窗工厂）", enclosing(i), st[:100]))
        continue
    m = re.search(r'(?:content|rootView)\.addView\((\w+)', ln)
    if m and m.group(1) in ("sheet", "overlay", "ov", "pop", "menu", "popWrap", "backdrop", "clone", "toastWrap"):
        entries.append((i + 1, "贴屏挂载 " + m.group(1), enclosing(i), st[:100]))
    elif m and m.group(1) in ("fullScreenPage", "helloView"):
        entries.append((i + 1, "整页挂载 " + m.group(1) + "（页面非弹层，册内备查）", enclosing(i), st[:100]))

sys.stdout.write(f"# 弹层册机器枚举：共 {len(entries)} 点（buildUpdateSheet 调用 + 贴屏挂载）\n")
for ln_no, kind, fn, txt in entries:
    sys.stdout.write(f"L{ln_no}\t{kind}\t{fn}\t{txt}\n")
