# One-click: extract the SayIt portable build to the Desktop and create a shortcut.
# Usage: 右键"使用 PowerShell 运行"，或由 一键安装到桌面.bat 调用。
$zip = "$env:USERPROFILE\Downloads\SayIt-Watch-Portable.zip"
$desk = [Environment]::GetFolderPath('Desktop')
$dir = Join-Path $desk 'SayIt'
if (-not (Test-Path $zip)) { Write-Host "ZIP 不存在: $zip"; exit 1 }
if (-not (Test-Path $dir)) { Expand-Archive -LiteralPath $zip -DestinationPath $dir -Force }
$exe = Join-Path $dir 'sayit.exe'
$ws = New-Object -ComObject WScript.Shell
$lnk = $ws.CreateShortcut((Join-Path $desk 'SayIt.lnk'))
$lnk.TargetPath = $exe
$lnk.WorkingDirectory = $dir
$lnk.Save()
Write-Host "完成！桌面已建 SayIt 快捷方式 -> $dir"
