[CmdletBinding()]
param([ValidateSet('demo','live')][string]$Mode='demo',[int]$Port=8093,[switch]$SkipBuild)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'runtime.ps1')
$taskRuntime=Get-NovaRuntime
Set-Location -LiteralPath $taskRuntime.Root
$taskLocal=Join-Path $taskRuntime.Root '.local'
New-Item -ItemType Directory -Path $taskLocal -Force | Out-Null
if(Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue) { throw "Port $Port is already in use; choose a different port." }
$taskAccountFile=Join-Path $taskLocal 'accounts.dpapi.json'
if(!(Test-Path -LiteralPath $taskAccountFile)) {
    $taskAccounts=@{}
    foreach($taskName in @('operator','reviewer','admin')) {
        $taskBytes=New-Object byte[] 24
        $taskRandom=[Security.Cryptography.RandomNumberGenerator]::Create();$taskRandom.GetBytes($taskBytes);$taskRandom.Dispose()
        $taskPassword=[Convert]::ToBase64String($taskBytes)
        $taskAccounts[$taskName]=ConvertFrom-SecureString (ConvertTo-SecureString $taskPassword -AsPlainText -Force)
    }
    $taskAccounts | ConvertTo-Json | Set-Content -LiteralPath $taskAccountFile -Encoding UTF8
}
$taskAccounts=Get-Content -LiteralPath $taskAccountFile -Raw | ConvertFrom-Json
foreach($taskProperty in $taskAccounts.PSObject.Properties) {
    $taskSecure=ConvertTo-SecureString $taskProperty.Value
    [Environment]::SetEnvironmentVariable('NOVAORG_'+$taskProperty.Name.ToUpperInvariant()+'_PASSWORD',
        ([Net.NetworkCredential]::new('',$taskSecure)).Password,'Process')
}
if($Mode -eq 'live') { Import-NovaApi }
else { $env:NOVAORG_EMBEDDING_MODE='local' }
$env:NOVAORG_LLM_MODE=$Mode
$env:BIND_ADDRESS='127.0.0.1'
if(!$SkipBuild) {
    & $taskRuntime.Maven -B -ntp -DskipTests package
    if($LASTEXITCODE -ne 0) { throw 'Build failed.' }
}
$taskBuiltJar=Join-Path $taskRuntime.Root 'target\novaorg-1.0.0.jar'
if(!(Test-Path -LiteralPath $taskBuiltJar)) { throw 'Packaged jar not found.' }
$taskRunDirectory=Join-Path $taskLocal 'runtime'
New-Item -ItemType Directory -Path $taskRunDirectory -Force | Out-Null
$taskJar=Join-Path $taskRunDirectory 'novaorg.jar'
Copy-Item -LiteralPath $taskBuiltJar -Destination $taskJar -Force
$taskProcess=Start-Process -FilePath $taskRuntime.Java -WorkingDirectory $taskRuntime.Root -ArgumentList @('-jar',$taskJar,"--server.port=$Port") -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $taskLocal 'server.out.log') -RedirectStandardError (Join-Path $taskLocal 'server.err.log')
Set-Content -LiteralPath (Join-Path $taskLocal 'server.pid') -Value $taskProcess.Id
$taskHealthy=$false
for($taskAttempt=0;$taskAttempt -lt 40;$taskAttempt++) {
    Start-Sleep -Milliseconds 500
    try {
        $taskHealth=Invoke-RestMethod "http://127.0.0.1:$Port/actuator/health" -TimeoutSec 1
        if($taskHealth.status -eq 'UP') { $taskHealthy=$true;break }
    } catch {}
    if($taskProcess.HasExited) { break }
}
if(!$taskHealthy) { throw 'Startup failed or timed out. See .local/server.out.log and server.err.log.' }
Write-Output "NovaOrg ready: http://127.0.0.1:$Port ; mode=$Mode ; PID=$($taskProcess.Id)"
Write-Output 'To view your local login credentials, run scripts/show-credentials.ps1.'
