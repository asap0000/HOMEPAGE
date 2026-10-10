$ErrorActionPreference = 'Stop'
$toolDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$gui = Join-Path $toolDir 'buscourse_archive_gui.py'

$pythonw = $null
try {
    $python = (& py -3 -c 'import sys; print(sys.executable)' 2>$null | Select-Object -First 1)
    if ($LASTEXITCODE -eq 0 -and $python) {
        $candidate = Join-Path (Split-Path -Parent $python.Trim()) 'pythonw.exe'
        if (Test-Path -LiteralPath $candidate) { $pythonw = $candidate }
    }
} catch { }
if (-not $pythonw) {
    $candidate = (& where.exe pythonw 2>$null | Select-Object -First 1)
    if ($candidate -and (Test-Path -LiteralPath $candidate.Trim())) { $pythonw = $candidate.Trim() }
}
if (-not $pythonw) { throw 'pythonw.exe が見つかりません。Python 3 をインストールしてください。' }

$desktop = [Environment]::GetFolderPath('Desktop')
$link = (New-Object -ComObject WScript.Shell).CreateShortcut((Join-Path $desktop '保管庫へ取り込む.lnk'))
$link.TargetPath = $pythonw
$link.Arguments = '"' + $gui + '"'
$link.WorkingDirectory = $toolDir
$link.Description = 'BusCourse の書き出しを保管庫へ取り込む'
$link.Save()
Write-Output (Join-Path $desktop '保管庫へ取り込む.lnk')
