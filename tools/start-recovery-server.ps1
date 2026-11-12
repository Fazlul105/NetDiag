# Run in a foreground PowerShell window. Stop the listener with Ctrl+C.
param(
    [Parameter(Mandatory)]
    [System.Net.IPAddress] $Address,
    [ValidateRange(1, 65535)]
    [int] $Port = 7777
)

$ErrorActionPreference = 'Stop'
$listener = [System.Net.Sockets.TcpListener]::new($Address, $Port)
try {
    $listener.Start()
    Write-Output "Ready for NetDiag TCP connect on $Address port $Port"
    while ($true) {
        if ($listener.Pending()) {
            $client = $listener.AcceptTcpClient()
            $client.Dispose()
            Write-Output ('{0:o} accepted TCP connection' -f [DateTimeOffset]::UtcNow)
        } else {
            Start-Sleep -Milliseconds 50
        }
    }
} finally {
    $listener.Stop()
}
