[CmdletBinding()]
param([int]$Port=8093)
$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent $PSScriptRoot
$taskBase="http://127.0.0.1:$Port"
$taskPreviousDb=[Environment]::GetEnvironmentVariable('NOVAORG_DB_URL','Process')
$taskAcceptanceDb='jdbc:h2:file:./data/acceptance-'+(Get-Date -Format 'yyyyMMdd-HHmmss')+';DB_CLOSE_ON_EXIT=FALSE;LOCK_TIMEOUT=10000;WRITE_DELAY=0'
function Login-TestAccount([string]$Name) {
    $taskEncrypted=Get-Content -LiteralPath (Join-Path $taskRoot '.local/accounts.dpapi.json') -Raw | ConvertFrom-Json
    $taskSecure=ConvertTo-SecureString $taskEncrypted.$Name
    $taskPassword=([Net.NetworkCredential]::new('',$taskSecure)).Password
    $taskSession=New-Object Microsoft.PowerShell.Commands.WebRequestSession
    $taskCsrf=Invoke-RestMethod "$taskBase/api/auth/csrf" -WebSession $taskSession
    Invoke-RestMethod "$taskBase/api/login" -Method Post -WebSession $taskSession -Headers @{$taskCsrf.headerName=$taskCsrf.token} -Body @{username=$Name;password=$taskPassword} | Out-Null
    $taskCsrf=Invoke-RestMethod "$taskBase/api/auth/csrf" -WebSession $taskSession
    [pscustomobject]@{Session=$taskSession;Headers=@{$taskCsrf.headerName=$taskCsrf.token}}
}
function Post-TestJson($Login,[string]$Path,$Body) {
    $taskJson=$Body | ConvertTo-Json -Depth 12 -Compress
    Invoke-RestMethod ($taskBase+$Path) -Method Post -WebSession $Login.Session -Headers $Login.Headers -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($taskJson))
}
try {
    & (Join-Path $PSScriptRoot 'stop.ps1') | Out-Null
    $env:NOVAORG_DB_URL=$taskAcceptanceDb
    & (Join-Path $PSScriptRoot 'start.ps1') -Mode demo -Port $Port -SkipBuild | Out-Null
    $taskOperator=Login-TestAccount 'operator'
    $taskReviewer=Login-TestAccount 'reviewer'
    $taskPending=Post-TestJson $taskOperator '/api/tasks' @{goal='请帮我办理全额退款，我还没有提供订单号'}
    if($taskPending.status -ne 'NEEDS_INPUT') { throw 'Expected NEEDS_INPUT.' }
    $taskPaused=Post-TestJson $taskOperator ("/api/tasks/"+$taskPending.taskId+'/clarify') @{answer='订单 A101';revision=$taskPending.revision}
    if($taskPaused.status -ne 'WAITING_APPROVAL' -or $taskPaused.pendingActions[0].call.arguments.orderId -ne 'A101') { throw 'Wrong resumed order or approval state.' }
    $taskDone=Post-TestJson $taskReviewer ("/api/tasks/"+$taskPending.taskId+'/approve') @{granted=$true;revision=$taskPaused.revision;reason='合成订单验收'}
    if($taskDone.status -ne 'DONE') { throw 'Approval did not complete the sandbox refund.' }
    try {
        Post-TestJson $taskReviewer ("/api/tasks/"+$taskPending.taskId+'/approve') @{granted=$true;revision=$taskPaused.revision} | Out-Null
        throw 'Duplicate approval was accepted.'
    } catch { if(!$_.Exception.Response -or [int]$_.Exception.Response.StatusCode -ne 409) { throw } }
    & (Join-Path $PSScriptRoot 'stop.ps1') | Out-Null
    & (Join-Path $PSScriptRoot 'start.ps1') -Mode demo -Port $Port -SkipBuild | Out-Null
    $taskOperator=Login-TestAccount 'operator'
    $taskReviewer=Login-TestAccount 'reviewer'
    $taskRestored=Invoke-RestMethod ($taskBase+'/api/tasks/'+$taskPending.taskId) -WebSession $taskOperator.Session
    if($taskRestored.status -ne 'DONE' -or !($taskRestored.steps | Where-Object { $_.trace.Count -gt 0 })) { throw 'State or trace did not survive restart.' }
    $taskRepeat=Post-TestJson $taskOperator '/api/tasks' @{goal='请全额退款订单 A101'}
    $taskRefused=Post-TestJson $taskReviewer ("/api/tasks/"+$taskRepeat.taskId+'/approve') @{granted=$true;revision=$taskRepeat.revision;reason='验证数据库余额持久化'}
    if($taskRefused.status -ne 'FAILED') { throw 'Persisted refunded balance did not prevent a second full refund.' }
    $taskReport=@{checkedAt=(Get-Date).ToString('o');mode='offline-synthetic';missingOrder='NEEDS_INPUT';clarifiedOrder='A101';approval='DONE';duplicateApprovalHttp=409;stateAndTraceSurviveRestart=$true;secondFullRefund='FAILED';database='isolated acceptance database'}
    $taskReport | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $taskRoot 'reports/local-http-acceptance.json') -Encoding UTF8
    Write-Output 'HTTP acceptance passed: login/CSRF, clarification, approval, duplicate rejection, durable trace and per-order balance.'
} finally {
    & (Join-Path $PSScriptRoot 'stop.ps1') | Out-Null
    [Environment]::SetEnvironmentVariable('NOVAORG_DB_URL',$taskPreviousDb,'Process')
    & (Join-Path $PSScriptRoot 'start.ps1') -Mode demo -Port $Port -SkipBuild
}
