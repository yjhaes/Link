param(
    [Parameter(Mandatory)][string]$ManagementUrl,
    [Parameter(Mandatory)][string]$VirtualHost,
    [Parameter(Mandatory)][pscredential]$Credential
)
$ErrorActionPreference = 'Stop'
$encodedHost = [uri]::EscapeDataString($VirtualHost)
$policies = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'visit-consumer-policies.json') -Raw | ConvertFrom-Json
foreach ($policy in $policies) {
    $encodedName = [uri]::EscapeDataString($policy.name)
    $body = $policy | Select-Object pattern, 'apply-to', priority, definition | ConvertTo-Json -Depth 5
    try {
        Invoke-RestMethod -Method Put -Uri "$($ManagementUrl.TrimEnd('/'))/api/policies/$encodedHost/$encodedName" -Authentication Basic -Credential $Credential -AllowUnencryptedAuthentication -ContentType 'application/json' -Body $body | Out-Null
    } catch {
        throw 'Visit consumer policy update failed. Check management access without exposing response or credentials.'
    }
}
Write-Output 'Visit consumer policies applied. No queues or messages were deleted.'
