<#
.SYNOPSIS
    华为码道（CodeArts Doer）派活 / 收活桥。

.DESCRIPTION
    码道是 VS Code 插件，没有开放 CLI，外部进程无法直接驱动它。
    所以这里不假装能"调用"它，只做两件纯机械的活：

      push  —— 把任务单内容放进剪贴板，你去 VS Code 里 Ctrl+V
      pull  —— 把剪贴板里的长回复存成 docs 下的批复文件，省得手动新建

    中间那句"发过去"仍然要你按一次粘贴；这是插件形态决定的，绕不过去。

.EXAMPLE
    .\scripts\codearts.ps1 push docs\给码道的任务单.md
    .\scripts\codearts.ps1 pull B1
    .\scripts\codearts.ps1 ls

.NOTES
    建立于 2026-11-23。本文件必须以 UTF-8 with BOM 保存，否则 PowerShell 5.1 会按 GBK 解析、中文全乱。
#>
[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidateSet('push', 'pull', 'ls', 'help')]
    [string]$Action = 'help',

    [Parameter(Position = 1)]
    [string]$Arg
)

$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$DocsDir = Join-Path $ProjectRoot 'docs'

function Write-Head([string]$s) { Write-Host ''; Write-Host "== $s" -ForegroundColor Cyan }

function Save-Utf8NoBom([string]$Path, [string]$Text) {
    $enc = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($Path, $Text, $enc)
}

switch ($Action) {

    'help' {
        Write-Host @'
华为码道派活桥  ——  只做剪贴板搬运

  push <文件>     把文件内容送进剪贴板（然后去 VS Code 粘贴）
  pull [名字]     把剪贴板内容存成 docs\码道-<名字>批复.md
                  不写名字则存成 docs\码道-回报-<时间戳>.md
  ls              列出 docs 下已有的任务单与批复

例：
  .\scripts\codearts.ps1 push docs\给码道的任务单.md
  .\scripts\codearts.ps1 pull B1
'@ -ForegroundColor Gray
    }

    'push' {
        if (-not $Arg) { throw "要传文件路径。例：.\scripts\codearts.ps1 push docs\给码道的任务单.md" }
        $path = if ([System.IO.Path]::IsPathRooted($Arg)) { $Arg } else { Join-Path $ProjectRoot $Arg }
        if (-not (Test-Path -LiteralPath $path)) { throw "文件不存在：$path" }

        $text = Get-Content -LiteralPath $path -Raw -Encoding UTF8
        if ([string]::IsNullOrWhiteSpace($text)) { throw "文件是空的：$path" }

        Set-Clipboard -Value $text

        Write-Head '已放进剪贴板'
        Write-Host ("  文件   : {0}" -f $path)
        Write-Host ("  长度   : {0} 字符 / {1} 行" -f $text.Length, ($text -split "`n").Count)
        Write-Host ''
        Write-Host '  接下来：' -ForegroundColor Yellow
        Write-Host '    1) VS Code 打开项目根目录：' -NoNewline
        Write-Host $ProjectRoot
        Write-Host '    2) 码道对话框里 Ctrl+V 粘贴，或直接说「按 docs\给码道的任务单.md 执行 B1」'
        Write-Host '    3) 它改完，把它的汇报文字选中复制，回来跑：.\scripts\codearts.ps1 pull B1'
    }

    'pull' {
        $text = Get-Clipboard -Raw
        if ([string]::IsNullOrWhiteSpace($text)) {
            throw "剪贴板是空的。先在 VS Code 里选中码道的汇报文字并 Ctrl+C。"
        }

        if (-not (Test-Path -LiteralPath $DocsDir)) { New-Item -ItemType Directory -Path $DocsDir | Out-Null }

        if ($Arg) {
            $name = $Arg
            # 去掉可能带进来的非法文件名字符
            $name = $name -replace '[\\/:*?"<>|]', '-'
            $target = Join-Path $DocsDir ("码道-{0}批复.md" -f $name)
        } else {
            $target = Join-Path $DocsDir ("码道-回报-{0}.md" -f (Get-Date -Format 'yyyyMMdd-HHmmss'))
        }

        # 已存在就退让，绝不覆盖上一份批复
        if (Test-Path -LiteralPath $target) {
            $stamp = Get-Date -Format 'HHmmss'
            $target = $target -replace '\.md$', "-$stamp.md"
            Write-Host ("  目标已存在，改存为：{0}" -f (Split-Path -Leaf $target)) -ForegroundColor Yellow
        }

        Save-Utf8NoBom -Path $target -Text $text

        $preview = ($text -split "`r?`n" | Select-Object -First 3) -join ' / '
        Write-Head '已存成文件'
        Write-Host ("  路径   : {0}" -f $target)
        Write-Host ("  长度   : {0} 字符" -f $text.Length)
        Write-Host ("  开头   : {0}" -f $preview)

        # 只提示，不阻塞——判断真伪留给后面人工核对
        $hasKey = $text -match '改动文件|build\.cmd|BUILD SUCCESSFUL|新增'
        if (-not $hasKey) {
            Write-Host ''
            Write-Host '  注意：内容里没看到「改动文件 / build.cmd / BUILD SUCCESSFUL」这类标记，' -ForegroundColor Yellow
            Write-Host '        可能复制错东西了，也可能它压根没跑构建。核对时留意。' -ForegroundColor Yellow
        }
    }

    'ls' {
        Write-Head 'docs 下的派活记录'
        if (-not (Test-Path -LiteralPath $DocsDir)) {
            Write-Host '  （docs 目录还不存在）'
            break
        }
        $files = Get-ChildItem -LiteralPath $DocsDir -Filter '*.md' -File | Sort-Object Name
        if (-not $files) { Write-Host '  （空）'; break }
        foreach ($f in $files) {
            $mark = if ($f.Name -match '批复|回报') { '  <- 码道回报' } elseif ($f.Name -match '任务单') { '  <- 派给它' } else { '' }
            Write-Host ("  {0,-34} {1,7:N0} 字节  {2}{3}" -f $f.Name, $f.Length, $f.LastWriteTime.ToString('MM-dd HH:mm'), $mark)
        }
    }
}
