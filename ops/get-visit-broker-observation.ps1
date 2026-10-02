param(
    [Parameter(Mandatory)][string]$ManagementUrl,
    [Parameter(Mandatory)][string]$VirtualHost,
    [Parameter(Mandatory)][pscredential]$Credential
)
$ErrorActionPreference = 'Stop'
$encodedHost = [uri]::EscapeDataString($VirtualHost)
$api = "$($ManagementUrl.TrimEnd('/'))/api"
try {
    $queues = foreach ($name in @('shortlink.visit.stats.q', 'shortlink.visit.stats.dlq')) {
        $encodedName = [uri]::EscapeDataString($name)
        $queue = Invoke-RestMethod -Uri "$api/queues/$encodedHost/$encodedName" -Authentication Basic -Credential $Credential -AllowUnencryptedAuthentication
        [pscustomobject]@{
            queue = $name
            ready = $queue.messages_ready
            unacked = $queue.messages_unacknowledged
            consumers = $queue.consumers
            brokerAckPerSecond = $queue.message_stats.ack_details.rate
        }
    }
    $nodes = Invoke-RestMethod -Uri "$api/nodes" -Authentication Basic -Credential $Credential -AllowUnencryptedAuthentication
    [pscustomobject]@{
        queues = @($queues)
        brokerNodesObserved = @($nodes).Count
        memoryAlarm = @($nodes | Where-Object mem_alarm).Count -gt 0
        diskAlarm = @($nodes | Where-Object disk_free_alarm).Count -gt 0
    }
} catch {
    throw 'Visit broker observation unavailable. Check management access without exposing response or credentials.'
}
