#Requires -Version 7.0
[CmdletBinding()]
param([string]$Path = (Join-Path $PSScriptRoot '../.env.local'))
$ErrorActionPreference = 'Stop'
$secretPath = [IO.Path]::GetFullPath($Path)
if (Test-Path -LiteralPath $secretPath) {
    Write-Output 'Existing local secrets preserved.'
    return
}
function New-Secret {
    [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
}
$lines = @(
    'SHORT_LINK_INTERNAL_TOKEN=' + (New-Secret)
    'SHORT_LINK_VISITOR_HMAC_KEY=' + (New-Secret)
    'SHORT_LINK_VISITOR_KEY_VERSION=1'
    'SHORT_LINK_STATS_ENABLED=true'
    'SHORT_LINK_STATS_CONSUMER_ENABLED=true'
    'DB_USERNAME=short_link'
    'DB_PASSWORD=' + (New-Secret)
    'DB_ROOT_PASSWORD=' + (New-Secret)
    'RABBITMQ_USERNAME=short_link'
    'RABBITMQ_PASSWORD=' + (New-Secret)
    'RABBITMQ_VIRTUAL_HOST=short_link'
)
# CreateNew prevents two initializers from replacing each other's file.
$stream = [IO.File]::Open($secretPath, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
try {
    $bytes = [Text.UTF8Encoding]::new($false).GetBytes(($lines -join "`n") + "`n")
    $stream.Write($bytes)
} finally {
    $stream.Dispose()
}
Write-Output 'Local secrets initialized. Keep the file private; values are not printed.'
