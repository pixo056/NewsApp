param([string]$NodePath)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'wifi-network.ps1')
$ruleName='NewsApp-WiFi-5173'
$identity=[Security.Principal.WindowsIdentity]::GetCurrent()
$principal=New-Object Security.Principal.WindowsPrincipal($identity)
if (!$principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    $rule=Get-NetFirewallRule -Name $ruleName -ErrorAction SilentlyContinue
    if ($rule -and $rule.Enabled -eq 'True') {
        $address=$rule | Get-NetFirewallAddressFilter
        $application=$rule | Get-NetFirewallApplicationFilter
        $interface=$rule | Get-NetFirewallInterfaceFilter
        $sameSubnet=($address.RemoteAddress -contains $wifiNetwork.Subnet) -or ($address.RemoteAddress -contains $wifiNetwork.FilterSubnet)
        if ($address.LocalAddress -contains $wifiNetwork.Ip -and $sameSubnet -and $application.Program -eq $NodePath -and $interface.InterfaceAlias -contains $wifiNetwork.Alias) { return }
    }
    Write-Host 'Windows needs administrator approval for one Wi-Fi-only firewall rule (TCP 5173).'
    $script=Join-Path $PSScriptRoot 'enable-wifi.ps1'
    $arguments='-NoProfile -ExecutionPolicy Bypass -File "'+$script+'" -NodePath "'+$NodePath+'"'
    $elevated=Start-Process -FilePath 'powershell.exe' -ArgumentList $arguments -Verb RunAs -WindowStyle Hidden -PassThru -Wait
    if ($elevated.ExitCode -ne 0) { throw 'Wi-Fi firewall setup failed. Retry start-wifi.cmd and approve the Windows administrator prompt.' }
    return
}
if (!(Test-Path -LiteralPath $NodePath -PathType Leaf) -or [IO.Path]::GetFileName($NodePath) -ne 'node.exe') { throw 'A valid Node.js executable is required.' }
# Replace only this project's named rule; no changes to network profiles or other rules.
Get-NetFirewallRule -Name $ruleName -ErrorAction SilentlyContinue | Remove-NetFirewallRule
New-NetFirewallRule -Name $ruleName -DisplayName 'NewsApp - same Wi-Fi (TCP 5173)' -Direction Inbound -Action Allow -Protocol TCP -LocalPort 5173 -Program $NodePath -LocalAddress $wifiNetwork.Ip -RemoteAddress $wifiNetwork.Subnet -InterfaceAlias $wifiNetwork.Alias -Profile Any -EdgeTraversalPolicy Block | Out-Null
