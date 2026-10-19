param(
    [ValidateSet('Launch','Configure')][string]$Action='Launch',
    [string]$AdbPath='',
    [string]$Serial=''
)
$ErrorActionPreference='Stop'
[Console]::OutputEncoding=New-Object System.Text.UTF8Encoding($false)
$utf8=New-Object System.Text.UTF8Encoding($false)
$localPath=Join-Path $PSScriptRoot 'local.json'

function Find-WindowPython([string]$Candidate){
    if([string]::IsNullOrWhiteSpace($Candidate)){return}
    $directory=Split-Path -Parent $Candidate
    if(-not $directory){return}
    $console=Join-Path $directory 'python.exe'
    $windowed=Join-Path $directory 'pythonw.exe'
    if(-not(Test-Path -LiteralPath $console -PathType Leaf) -or -not(Test-Path -LiteralPath $windowed -PathType Leaf)){return}
    try{
        & $console -I -B -c 'import sys, tkinter; sys.exit(0 if sys.version_info >= (3, 10) and sys.maxsize > 2**32 else 1)' 2>$null
        if($LASTEXITCODE -eq 0){return [IO.Path]::GetFullPath($windowed)}
    }catch{}
}

function Prepare-Python([string]$Configured){
    $runtime=Join-Path $PSScriptRoot '.runtime\python-3.13.16'
    $candidates=@($Configured,(Join-Path $runtime 'pythonw.exe'))
    $command=Get-Command python.exe -ErrorAction SilentlyContinue
    if($command -and $command.Source -notlike '*\WindowsApps\*'){$candidates+=$command.Source}
    foreach($candidate in $candidates){
        $found=Find-WindowPython $candidate
        if($found){Write-Host 'Python/Tk 已就绪。';return $found}
    }
    Write-Host '首次准备 Python/Tk：从 Python 官网下载完整运行时，请稍候。'
    $cache=Join-Path $PSScriptRoot '.runtime'
    New-Item -ItemType Directory -Force -Path $cache | Out-Null
    $archive=Join-Path $cache 'python-3.13.16-amd64.zip'
    $expected='bbf675bb5e763c1efbb09a3a461b259d81598a63c30c4b0d7ea11b9f063df159'
    if(-not(Test-Path -LiteralPath $archive) -or (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ne $expected){
        [Net.ServicePointManager]::SecurityProtocol=[Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
        $ProgressPreference='SilentlyContinue'
        Invoke-WebRequest -UseBasicParsing -Uri 'https://www.python.org/ftp/python/3.13.16/python-3.13.16-amd64.zip' -OutFile $archive
    }
    if((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ne $expected){throw 'Python 下载校验失败，未运行下载内容；请重新启动后重试。'}
    Expand-Archive -LiteralPath $archive -DestinationPath $runtime -Force
    $found=Find-WindowPython (Join-Path $runtime 'pythonw.exe')
    if(-not $found){throw 'Python/Tk 未能运行，请检查 Windows 是否为受支持的 64 位系统。'}
    Write-Host 'Python/Tk 准备完成。'
    return $found
}

function Find-Adb([string]$Configured){
    if($AdbPath){
        if(-not(Test-Path -LiteralPath $AdbPath -PathType Leaf)){throw '指定的 ADB 文件不存在。'}
        return [IO.Path]::GetFullPath($AdbPath)
    }
    $candidates=@($Configured)
    foreach($process in @(Get-Process dnplayer -ErrorAction SilentlyContinue)){
        if($process.Path){$candidates+=Join-Path (Split-Path -Parent $process.Path) 'adb.exe'}
    }
    $uninstall=@('HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\*','HKLM:\Software\Microsoft\Windows\CurrentVersion\Uninstall\*','HKLM:\Software\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall\*')
    foreach($entry in @(Get-ItemProperty -Path $uninstall -ErrorAction SilentlyContinue | Where-Object {$_.DisplayName -match 'LDPlayer|雷电'})){
        if($entry.InstallLocation){$candidates+=Join-Path $entry.InstallLocation 'adb.exe'}
        if($entry.DisplayIcon){
            $icon=($entry.DisplayIcon -replace ',\s*\d+$','').Trim('"')
            $candidates+=Join-Path (Split-Path -Parent $icon) 'adb.exe'
        }
    }
    foreach($candidate in $candidates){
        if($candidate -and (Test-Path -LiteralPath $candidate -PathType Leaf)){return [IO.Path]::GetFullPath($candidate)}
    }
    throw '未找到雷电 ADB。请安装并打开雷电模拟器后重试；自定义位置可用 Setup.ps1 -AdbPath 指定 adb.exe。'
}

function Select-Device([string]$Adb,[string]$Configured){
    $previousPreference=$ErrorActionPreference
    try{
        # ADB reports a normal daemon startup on stderr in Windows PowerShell 5.
        $ErrorActionPreference='Continue'
        $output=@(& $Adb devices 2>&1)
        $exitCode=$LASTEXITCODE
    }finally{$ErrorActionPreference=$previousPreference}
    if($exitCode -ne 0){throw ('ADB 设备查询失败：'+($output -join ' '))}
    $online=@($output | ForEach-Object {if([string]$_ -match '^(\S+)\s+device(?:\s|$)'){$Matches[1]}})
    $chosen=if($Serial){$Serial}else{$Configured}
    if($chosen){
        if($online -notcontains $chosen){throw "已配置的设备 $chosen 不在线。请打开对应模拟器；更换设备可用 Setup.ps1 -Serial 指定序列号。"}
        return $chosen
    }
    if($online.Count -eq 0){throw '没有在线设备。请启动雷电模拟器、开启 ADB 后重新双击启动.cmd。'}
    if($online.Count -eq 1){return $online[0]}
    Write-Host '检测到多台设备，请选择运行正式国际服的设备：'
    for($i=0;$i -lt $online.Count;$i++){Write-Host ('{0}. {1}' -f ($i+1),$online[$i])}
    do{
        $answer=Read-Host '输入设备编号'
        $number=0
    }while(-not [int]::TryParse($answer,[ref]$number) -or $number -lt 1 -or $number -gt $online.Count)
    return $online[$number-1]
}

try{
    foreach($entry in @('Control.ps1','window.py','start-device.sh','dist\sonar.jar','assets\Montserrat-Bold.ttf')){
        if(-not(Test-Path -LiteralPath (Join-Path $PSScriptRoot $entry) -PathType Leaf)){throw "缺少 $entry。请完整解压下载的 ZIP 后再启动。"}
    }
    $config=[pscustomobject]@{}
    $hadBom=$false
    if(Test-Path -LiteralPath $localPath){
        try{$config=[IO.File]::ReadAllText($localPath) | ConvertFrom-Json}catch{throw 'local.json 无法解析，请修复配置文件后再启动；原文件未覆盖。'}
        if($null -eq $config -or $config -isnot [pscustomobject]){throw 'local.json 必须为 JSON 对象；原文件未覆盖。'}
        $bytes=[IO.File]::ReadAllBytes($localPath)
        $hadBom=$bytes.Length -ge 3 -and $bytes[0] -eq 239 -and $bytes[1] -eq 187 -and $bytes[2] -eq 191
    }
    Write-Host 'V2 声纳：检查本机启动环境。'
    $adb=Find-Adb $config.adb
    $device=Select-Device $adb $config.serial
    $python=Prepare-Python $config.python
    if($hadBom -or $config.adb -ne $adb -or $config.serial -ne $device -or $config.python -ne $python){
        $config | Add-Member -NotePropertyName adb -NotePropertyValue $adb -Force
        $config | Add-Member -NotePropertyName serial -NotePropertyValue $device -Force
        $config | Add-Member -NotePropertyName python -NotePropertyValue $python -Force
        [IO.File]::WriteAllText($localPath,($config | ConvertTo-Json -Depth 20),$utf8)
    }
    Write-Host "设备：$device；本机配置已就绪。"
    if($Action -eq 'Launch'){
        & (Join-Path $PSScriptRoot 'Control.ps1') -Action Window
        Write-Host '控制窗口已打开，请在窗口中点击“开始”。'
    }
}catch{
    Write-Host ('启动未完成：'+$_.Exception.Message) -ForegroundColor Red
    exit 1
}
