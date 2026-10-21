Import-Module -Name (Join-Path $PSHOME 'Modules\Microsoft.PowerShell.Utility\Microsoft.PowerShell.Utility.psd1') -ErrorAction Stop

function Wait-NewsAppFrontend {
    param(
        [Parameter(Mandatory=$true)]$Process,
        [ValidateSet('local','wifi')][string]$Mode='local',
        [string]$WifiIp,
        [ValidateRange(1,60)][int]$TimeoutSeconds=20,
        [ValidateRange(0,2000)][int]$PollMilliseconds=250,
        [scriptblock]$Probe={ Invoke-WebRequest -Uri 'http://127.0.0.1:5173/' -UseBasicParsing -TimeoutSec 2 }
    )
    $timer=[Diagnostics.Stopwatch]::StartNew()
    while ($timer.Elapsed.TotalSeconds -lt $TimeoutSeconds) {
        if ($Process.HasExited) { throw 'Frontend stopped before it was ready. See .local/logs/frontend.log and frontend-error.log; check whether port 5173 is already in use.' }
        try {
            $response=& $Probe
            if ($response.StatusCode -eq 200 -and $response.Headers['X-NewsApp-Web'] -eq '1' -and $response.Headers['X-NewsApp-Mode'] -eq $Mode -and ($Mode -ne 'wifi' -or $response.Headers['X-NewsApp-Wifi-Ip'] -eq $WifiIp)) { return }
        } catch {
            # A refused connection is expected while Vite is starting.
        }
        Start-Sleep -Milliseconds $PollMilliseconds
    }
    throw 'Frontend did not become ready on port 5173. See .local/logs/frontend.log and frontend-error.log. Stop another server on this port before restarting NewsApp.'
}
