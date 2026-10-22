$ErrorActionPreference='Stop'
function Get-NovaRuntime {
    $taskProject=Split-Path -Parent $PSScriptRoot
    $taskJavaCandidates=@()
    if($env:JAVA_HOME) { $taskJavaCandidates+=Join-Path $env:JAVA_HOME 'bin\java.exe' }
    $taskJavaCommand=Get-Command java.exe -ErrorAction SilentlyContinue
    if($taskJavaCommand) { $taskJavaCandidates+=$taskJavaCommand.Source }
    foreach($taskJavaRoot in @("$env:ProgramFiles\Java","$env:ProgramFiles\Eclipse Adoptium",'D:\VSCode\JAVA','D:\Java','D:\tools')) {
        if(Test-Path -LiteralPath $taskJavaRoot) {
            $taskJavaCandidates+=Get-ChildItem -LiteralPath $taskJavaRoot -Filter java.exe -Recurse -Depth 4 -ErrorAction SilentlyContinue | Select-Object -First 12 -ExpandProperty FullName
        }
    }
    $taskJava=$taskJavaCandidates | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
    if(!$taskJava) { throw 'JDK 17+ not found. Set JAVA_HOME to a JDK installation.' }
    $env:JAVA_HOME=Split-Path -Parent (Split-Path -Parent $taskJava)
    $taskMavenCandidates=@((Join-Path $taskProject '.tools\apache-maven-3.9.16\bin\mvn.cmd'),

        (Join-Path $taskProject 'mvnw.cmd'))
    $taskMaven=$taskMavenCandidates | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
    if(!$taskMaven) { throw 'Maven wrapper not found.' }
    [pscustomobject]@{Java=$taskJava;Maven=$taskMaven;Root=$taskProject}
}
function Import-NovaApi {
    $taskConfigPath=Join-Path (Split-Path -Parent $PSScriptRoot) '.local\api-config.json'
    if(!(Test-Path -LiteralPath $taskConfigPath)) { throw 'API configuration missing. Run scripts/configure-api.ps1 first.' }
    $taskConfig=Get-Content -LiteralPath $taskConfigPath -Raw | ConvertFrom-Json
    foreach($taskProperty in $taskConfig.Values.PSObject.Properties) {
        [Environment]::SetEnvironmentVariable($taskProperty.Name,[string]$taskProperty.Value,'Process')
    }
    foreach($taskProperty in $taskConfig.Secrets.PSObject.Properties) {
        $taskSecure=ConvertTo-SecureString $taskProperty.Value
        $taskPlain=([System.Net.NetworkCredential]::new('',$taskSecure)).Password
        [Environment]::SetEnvironmentVariable($taskProperty.Name,$taskPlain,'Process')
    }
}
