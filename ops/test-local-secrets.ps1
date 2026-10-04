#Requires -Version 7.0
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$testDirectory = Join-Path $repo ('target/secret-init-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $testDirectory -Force | Out-Null
try {
    $path = Join-Path $testDirectory 'local secrets.env'
    $firstOutput = & (Join-Path $PSScriptRoot 'init-local-secrets.ps1') -Path $path
    $first = [IO.File]::ReadAllText($path)
    $values = @{}
    foreach ($line in ($first -split "`n")) {
        if ($line.Contains('=')) { $name, $value = $line -split '=', 2; $values[$name] = $value }
    }
    $names = @('SHORT_LINK_INTERNAL_TOKEN', 'SHORT_LINK_VISITOR_HMAC_KEY', 'DB_PASSWORD', 'DB_ROOT_PASSWORD', 'RABBITMQ_PASSWORD')
    $seen = [Collections.Generic.HashSet[string]]::new()
    foreach ($name in $names) {
        if ([Convert]::FromBase64String($values[$name]).Length -ne 32 -or !$seen.Add($values[$name])) {
            throw 'Generated secrets must be independent 32-byte values.'
        }
        if (($firstOutput -join "`n").Contains($values[$name])) { throw 'Initializer exposed a secret.' }
    }
    $secondOutput = & (Join-Path $PSScriptRoot 'init-local-secrets.ps1') -Path $path
    if ([IO.File]::ReadAllText($path) -cne $first) { throw 'Repeated initialization changed existing secrets.' }
    foreach ($name in $names) {
        if (($secondOutput -join "`n").Contains($values[$name])) { throw 'Repeated initialization exposed a secret.' }
    }
    Write-Output 'Secret initialization acceptance passed: independent values, private output, stable repeat.'
} finally {
    # The checked target is a freshly generated directory under this repository's target/.
    $targetRoot = [IO.Path]::GetFullPath((Join-Path $repo 'target')) + [IO.Path]::DirectorySeparatorChar
    if (!$testDirectory.StartsWith($targetRoot, [StringComparison]::OrdinalIgnoreCase)) { throw 'Unsafe test cleanup path.' }
    Remove-Item -LiteralPath $testDirectory -Recurse -Force
}
