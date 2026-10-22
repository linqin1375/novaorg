[CmdletBinding()]
param([ValidateSet('offline','live','retrieval')][string]$Mode='offline',[int]$MaxCases=5,[int]$Trials=1,
      [int]$MaxRequests=30,[int]$MaxTokens=50000,[string]$CaseIds,[switch]$AllowNetwork,[switch]$AllowLocalFeatures,[switch]$Retrieval,[switch]$SkipBuild)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'runtime.ps1')
$taskRuntime=Get-NovaRuntime
Set-Location -LiteralPath $taskRuntime.Root
if($Mode -ne 'offline') {
    if(!$AllowNetwork) { throw 'Provider evaluation needs explicit -AllowNetwork.' }
    Import-NovaApi
} else { $env:NOVAORG_EMBEDDING_MODE='local' }
if(!$SkipBuild) {
    & $taskRuntime.Maven -B -ntp -DskipTests package
    if($LASTEXITCODE -ne 0) { throw 'Build failed.' }
}
$taskArgs=@("--mode=$Mode","--max-cases=$MaxCases","--trials=$Trials","--max-requests=$MaxRequests","--max-tokens=$MaxTokens")
if($AllowNetwork) { $taskArgs+='--allow-network' }
if($AllowLocalFeatures) { $taskArgs+='--allow-local-features' }
if($CaseIds) { $taskArgs+="--case-ids=$CaseIds" }
if($Retrieval) { $taskArgs+='--retrieval' }
& $taskRuntime.Java '-Dloader.main=io.github.tangbailin.novaorg.eval.VerificationCli' -cp (Join-Path $taskRuntime.Root 'target\novaorg-1.0.0.jar') org.springframework.boot.loader.launch.PropertiesLauncher @taskArgs
exit $LASTEXITCODE
