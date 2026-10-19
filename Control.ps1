param(
    [ValidateSet('Window','Start','Stop','Status','SetMode')][string]$Action='Window',
    [ValidateSet('International')][string]$Target='International',
    [ValidateSet('Red','Blue')][string]$ScoutMode='Red',
    [int]$StopAfterLevels=0
)
$ErrorActionPreference='Stop'
[Console]::OutputEncoding=New-Object System.Text.UTF8Encoding($false)
$localPath=Join-Path $PSScriptRoot 'local.json'
if(-not(Test-Path -LiteralPath $localPath)){throw '请按 README 创建 local.json 并填写本机工具路径。'}
$local=Get-Content -LiteralPath $localPath -Raw -Encoding UTF8 | ConvertFrom-Json
$adb=$local.adb
$serial=if($local.serial){$local.serial}else{'emulator-5554'}
$remote='/data/local/tmp/sonar-independent'
$utf8=New-Object System.Text.UTF8Encoding($false)
function Invoke-Adb([string[]]$Arguments) {
    $psi=New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName=$adb
    $escaped=@('-s',$serial)+$Arguments | ForEach-Object { '"'+($_ -replace '(\\*)"','$1$1\"' -replace '(\\+)$','$1$1')+'"' }
    $psi.Arguments=$escaped -join ' '
    $psi.UseShellExecute=$false;$psi.CreateNoWindow=$true;$psi.RedirectStandardOutput=$true;$psi.RedirectStandardError=$true
    $psi.StandardOutputEncoding=[Text.Encoding]::UTF8;$psi.StandardErrorEncoding=[Text.Encoding]::UTF8
    $proc=New-Object System.Diagnostics.Process;$proc.StartInfo=$psi
    [void]$proc.Start();$stdoutTask=$proc.StandardOutput.ReadToEndAsync();$stderrTask=$proc.StandardError.ReadToEndAsync()
    if(-not $proc.WaitForExit(15000)){ $proc.Kill();throw 'ADB 未在 15 秒内返回；设备侧状态未据此改写' }
    $stdout=$stdoutTask.Result;$stderr=$stderrTask.Result
    if($proc.ExitCode -ne 0){throw ($stdout+$stderr)}
    $proc.Dispose();return $stdout.Trim()
}
function Read-Status {
    $text=Invoke-Adb @('shell','cat',"$remote/status.json")
    return ($text | ConvertFrom-Json)
}
function Start-Runner([string]$Chosen,[int]$Levels){
    if(-not(Test-Path -LiteralPath "$PSScriptRoot\dist\sonar.jar")){& "$PSScriptRoot\Build.ps1"}
    $config=@{target=$Chosen;package='com.supercell.boombeach';scoutMode=$ScoutMode;stopAfterLevels=$Levels}
    New-Item -ItemType Directory -Force -Path "$PSScriptRoot\work" | Out-Null
    [IO.File]::WriteAllText("$PSScriptRoot\work\run.json",($config|ConvertTo-Json -Compress),$utf8)
    [void](Invoke-Adb @('shell','mkdir','-p',$remote))
    foreach($entry in @(@('dist\sonar.jar','sonar.jar'),@('assets\Montserrat-Bold.ttf','Montserrat-Bold.ttf'),@('start-device.sh','start.sh'),@('work\run.json','run.json'))){
        [void](Invoke-Adb @('push',(Join-Path $PSScriptRoot $entry[0]),"$remote/$($entry[1])"))
    }
    [void](Invoke-Adb @('shell','su','-c',"'sh $remote/start.sh'"))
}
function Stop-Runner { [void](Invoke-Adb @('shell','su','-c',"'touch $remote/stop'")) }
if($Action -eq 'Start'){Start-Runner $Target $StopAfterLevels;Write-Output '已提交设备侧启动请求。使用 -Action Status 查看实际状态。';return}
if($Action -eq 'Stop'){Stop-Runner;Write-Output '已请求停止；等待状态变为 stopped 或 error 后接管游戏。';return}
if($Action -eq 'Status'){Read-Status|ConvertTo-Json;return}
if($Action -eq 'SetMode'){
    $config=Invoke-Adb @('exec-out','cat',"$remote/run.json") | ConvertFrom-Json
    $config | Add-Member -NotePropertyName scoutMode -NotePropertyValue $ScoutMode -Force
    [IO.File]::WriteAllText("$PSScriptRoot\work\run.json",($config|ConvertTo-Json -Compress),$utf8)
    [void](Invoke-Adb @('push',"$PSScriptRoot\work\run.json","$remote/run.json.mode"))
    [void](Invoke-Adb @('shell','mv',"$remote/run.json.mode","$remote/run.json"))
    Write-Output '已提交侦察模式，下一次侦察生效。';return
}

# The window uses the same device-side Start/Stop/Status API.
$python=$local.python
if(-not(Test-Path -LiteralPath $python)){throw "未找到现有 Python 窗口运行环境：$python"}
$arguments=@('-B',('"'+(Join-Path $PSScriptRoot 'window.py')+'"'))
foreach($entry in @(@('Target','target'),@('ScoutMode','scout'),@('StopAfterLevels','levels'))){
    if($PSBoundParameters.ContainsKey($entry[0])){$arguments+=@('--'+$entry[1],('"'+[string]$PSBoundParameters[$entry[0]]+'"'))}
}
Start-Process -FilePath $python -ArgumentList $arguments -WorkingDirectory $PSScriptRoot -WindowStyle Hidden
