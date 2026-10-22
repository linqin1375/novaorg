[CmdletBinding()]
param(
    [string]$LlmBaseUrl='https://api.deepseek.com/v1',
    [string]$LlmModel='deepseek-flash',
    [string]$EmbeddingBaseUrl,
    [string]$EmbeddingModel,
    [int]$EmbeddingDimensions=1024,
    [switch]$StrictTools,
    [switch]$ChatOnly
)
$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent $PSScriptRoot
$taskLocal=Join-Path $taskRoot '.local'
New-Item -ItemType Directory -Path $taskLocal -Force | Out-Null
$taskChatKey=Read-Host 'Chat API key (hidden)' -AsSecureString
if($taskChatKey.Length -eq 0) { throw 'Chat API key cannot be empty.' }
$taskEmbeddingKey=$null
if(!$ChatOnly) {
    if(!$EmbeddingBaseUrl -or !$EmbeddingModel) { throw 'Provide embedding URL/model, or choose -ChatOnly.' }
    $taskEmbeddingKey=Read-Host 'Embedding API key (hidden; blank only for a local server)' -AsSecureString
}
$taskValues=@{
    NOVAORG_LLM_BASE_URL=$LlmBaseUrl;NOVAORG_LLM_MODEL=$LlmModel
    NOVAORG_LLM_STRICT_TOOLS=$StrictTools.IsPresent.ToString().ToLowerInvariant()
    NOVAORG_EMBEDDING_MODE=$(if($ChatOnly){'local'}else{'api'});NOVAORG_EMBEDDING_BASE_URL=$EmbeddingBaseUrl
    NOVAORG_EMBEDDING_MODEL=$EmbeddingModel;NOVAORG_EMBEDDING_DIMENSIONS=$EmbeddingDimensions
}
$taskSecrets=@{NOVAORG_LLM_API_KEY=(ConvertFrom-SecureString $taskChatKey)}
if($taskEmbeddingKey -and $taskEmbeddingKey.Length -gt 0) { $taskSecrets.NOVAORG_EMBEDDING_API_KEY=ConvertFrom-SecureString $taskEmbeddingKey }
@{Values=$taskValues;Secrets=$taskSecrets} | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $taskLocal 'api-config.json') -Encoding UTF8
Write-Output 'API configuration saved with Windows DPAPI encryption. Keys were not printed.'
