$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent $PSScriptRoot
$taskPidFile=Join-Path $taskRoot '.local\server.pid'
if(!(Test-Path -LiteralPath $taskPidFile)) { Write-Output 'No managed server PID.';exit }
$taskServerId=[int](Get-Content -LiteralPath $taskPidFile -Raw)
$taskProcess=Get-CimInstance Win32_Process -Filter "ProcessId=$taskServerId"
if(!$taskProcess) { Write-Output 'Server already stopped.';exit }
$taskJar=Join-Path $taskRoot '.local\runtime\novaorg.jar'
$taskLegacyJar=Join-Path $taskRoot 'target\novaorg-1.0.0.jar'
if(!$taskProcess.CommandLine -or ($taskProcess.CommandLine.IndexOf($taskJar,[StringComparison]::OrdinalIgnoreCase) -lt 0 -and $taskProcess.CommandLine.IndexOf($taskLegacyJar,[StringComparison]::OrdinalIgnoreCase) -lt 0)) {
    throw 'PID no longer belongs to this project; refusing to stop it.'
}
Stop-Process -Id $taskServerId
Write-Output 'NovaOrg stopped.'
