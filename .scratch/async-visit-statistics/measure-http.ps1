param(
    [int]$Port = 18080,
    [int]$Requests = 300,
    [string]$Output = '.tools/async-baseline/measurement.json'
)
$ErrorActionPreference = 'Stop'
$handler = [System.Net.Http.HttpClientHandler]::new()
$handler.AllowAutoRedirect = $false
$client = [System.Net.Http.HttpClient]::new($handler)
$client.Timeout = [TimeSpan]::FromSeconds(10)
$base = "http://127.0.0.1:$Port"
try {
    $body = [System.Net.Http.StringContent]::new('{"originalUrl":"https://example.com/benchmark"}', [Text.Encoding]::UTF8, 'application/json')
    $created = $client.PostAsync("$base/api/links", $body).GetAwaiter().GetResult()
    if ([int]$created.StatusCode -ne 201) { throw "Creation failed: $([int]$created.StatusCode)" }
    $code = ($created.Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json).shortCode
    $created.Dispose()
    $body.Dispose()
    for ($i = 0; $i -lt 30; $i++) {
        $response = $client.GetAsync("$base/s/$code").GetAwaiter().GetResult()
        if ([int]$response.StatusCode -ne 302) { throw 'Warm-up redirect failed' }
        $response.Dispose()
    }
    $latencies = [System.Collections.Generic.List[double]]::new()
    $total = [Diagnostics.Stopwatch]::StartNew()
    for ($i = 0; $i -lt $Requests; $i++) {
        $timer = [Diagnostics.Stopwatch]::StartNew()
        $response = $client.GetAsync("$base/s/$code").GetAwaiter().GetResult()
        $timer.Stop()
        if ([int]$response.StatusCode -ne 302 -or $response.Headers.Location.AbsoluteUri -ne 'https://example.com/benchmark') { throw 'Redirect contract failed' }
        $latencies.Add($timer.Elapsed.TotalMilliseconds)
        $response.Dispose()
    }
    $total.Stop()
    $sorted = @($latencies | Sort-Object)
    $client.DefaultRequestHeaders.Add('X-Internal-Token', '0123456789abcdef0123456789abcdef')
    $statsJson = $client.GetStringAsync("$base/api/internal/links/$code/stats").GetAwaiter().GetResult() | ConvertFrom-Json
    $result = [ordered]@{
        requests = $Requests; warmup = 30; concurrency = 1; shortCode = $code
        elapsedMs = $total.Elapsed.TotalMilliseconds
        meanMs = ($latencies | Measure-Object -Average).Average
        p50Ms = $sorted[[Math]::Ceiling($Requests * 0.50) - 1]
        p95Ms = $sorted[[Math]::Ceiling($Requests * 0.95) - 1]
        p99Ms = $sorted[[Math]::Ceiling($Requests * 0.99) - 1]
        initiallyVisiblePv = $statsJson.pv; initiallyVisibleUv = $statsJson.uv
        sampledAt = [DateTimeOffset]::UtcNow.ToString('O')
        latenciesMs = $latencies.ToArray()
    }
    $result | ConvertTo-Json -Depth 3 | Set-Content -LiteralPath $Output -Encoding utf8
    $result.Remove('latenciesMs')
    $result | ConvertTo-Json
} finally {
    $client.Dispose()
    $handler.Dispose()
}
