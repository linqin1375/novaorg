$ErrorActionPreference='Stop'
$taskPath=Join-Path (Split-Path -Parent $PSScriptRoot) '.local\accounts.dpapi.json'
$taskAccounts=Get-Content -LiteralPath $taskPath -Raw | ConvertFrom-Json
foreach($taskProperty in $taskAccounts.PSObject.Properties) {
    $taskSecure=ConvertTo-SecureString $taskProperty.Value
    [pscustomobject]@{Username=$taskProperty.Name;Password=([Net.NetworkCredential]::new('',$taskSecure)).Password}
}
