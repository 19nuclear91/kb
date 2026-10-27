## 命令行执行原则

本环境为 Windows，主要终端是 PowerShell 7+（pwsh）。执行任何终端命令时，必须使用 PowerShell 语法，不得使用 Bash、sh、zsh、cmd.exe
或类 Unix shell 的专用语法，除非用户明确要求。

编写命令时应遵守以下规则：

- 使用 PowerShell 原生命令、语法和管道，例如 `Get-ChildItem`、`Select-String`、`Where-Object`、`ForEach-Object`、`Join-Path`、
  `Test-Path`。
- 路径使用 Windows/PowerShell 兼容写法，优先使用 `-LiteralPath` 处理可能包含空格或特殊字符的路径。
- 不使用 Bash 风格语法，例如 `VAR=value command`、`export VAR=value`、`grep`、`sed`、`awk`、`cat <<EOF`、`2>/dev/null`、
  `rm -rf`、`&&` 链接复杂命令等。
- 不提交或执行空命令。命令字符串必须包含实际可执行内容，不能是空字符串、仅空白、仅注释，或由条件拼接导致的空片段。
- 如果需要组合多步操作，优先写成清晰的 PowerShell 语句；复杂逻辑应使用可读的多行 PowerShell，而不是强行压缩成一行。
- 删除、移动、覆盖文件前，应确认目标路径明确，递归删除尤其要先校验解析后的绝对路径位于预期工作区内。
- 若某个工具本身跨平台，例如 `git`、`node`、`python`、`uv`、`pnpm`，可以直接调用该工具，但外围的变量、路径、重定向、循环、条件判断仍必须使用
  PowerShell 语法。
- 除非用户明确要求，否则所有 `shell_command` 都必须假定运行在 PowerShell 7+ 中，并严格使用 pwsh 语法。任何 Bash/cmd
  风格命令都视为不合规。

## 语言与沟通协议

- **母语优先**：除非用户明确要求英文，否则所有回复、解释、注释均使用 **简体中文**。
- **技术保留**：代码标识符、系统命令、日志条目、原始报错信息必须保持其 **原始语言**（通常为英文）。
- **简洁直击**：解释应言简意赅，避免冗长的开场白，直接针对问题核心。

## Java代码风格

- Import：禁止通配符，始终导入具体类
- 大括号：所有控制流语句必须带 {}，禁止单行体
- 禁止将控制流、方法体压缩为单行