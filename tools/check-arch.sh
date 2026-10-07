#!/bin/bash
# 卡盒改版门禁（Step0 建，Step1 补 catch 基线）：行数上限 + catch 空吞基线/白名单 + 静态可变状态清单。
# 默认警告模式（退出码 0）；--strict 时行数超基线或空吞超基线即失败。见 tools/check-arch.py。
exec python3 "$(dirname "$0")/check-arch.py" "$@"
