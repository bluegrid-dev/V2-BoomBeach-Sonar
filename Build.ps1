$ErrorActionPreference = 'Stop'
$localPath = Join-Path $PSScriptRoot 'local.json'
if(-not(Test-Path -LiteralPath $localPath)){throw '请按 README 创建 local.json 并填写本机工具路径。'}
$local = Get-Content -LiteralPath $localPath -Raw -Encoding UTF8 | ConvertFrom-Json
$jdk = $local.jdk
$sdk = $local.androidSdk
$env:JAVA_HOME = $jdk
$env:PATH = "$jdk\bin;" + $env:PATH
$out = Join-Path $PSScriptRoot 'work\classes'
$resolvedOutput=[IO.Path]::GetFullPath($out)
$workspaceRoot=[IO.Path]::GetFullPath($PSScriptRoot)+[IO.Path]::DirectorySeparatorChar
if(-not $resolvedOutput.StartsWith($workspaceRoot,[StringComparison]::OrdinalIgnoreCase)){throw 'Build output escaped project directory'}
if(Test-Path -LiteralPath $resolvedOutput){
    if((Get-Item -LiteralPath $resolvedOutput).Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Build output must not be a link'}
    Remove-Item -LiteralPath $resolvedOutput -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $out,(Join-Path $PSScriptRoot 'dist') | Out-Null
$sources = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'src') -Recurse -Filter '*.java' | ForEach-Object FullName)
& "$jdk\bin\javac.exe" -encoding UTF-8 -source 8 -target 8 -Xlint:-options -cp "$sdk\platforms\android-36\android.jar" -d $out @sources
if ($LASTEXITCODE -ne 0) { throw 'Java compilation failed' }
& "$jdk\bin\jar.exe" cf (Join-Path $PSScriptRoot 'work\classes.jar') -C $out .
& "$sdk\build-tools\35.0.0\d8.bat" --min-api 28 --lib "$sdk\platforms\android-36\android.jar" --output (Join-Path $PSScriptRoot 'dist\sonar.jar') (Join-Path $PSScriptRoot 'work\classes.jar')
if ($LASTEXITCODE -ne 0) { throw 'DEX compilation failed' }
& "$jdk\bin\jar.exe" uf (Join-Path $PSScriptRoot 'dist\sonar.jar') -C (Join-Path $PSScriptRoot 'models') cells.bin -C (Join-Path $PSScriptRoot 'models') hud.bin
if ($LASTEXITCODE -ne 0) { throw 'Model packaging failed' }
