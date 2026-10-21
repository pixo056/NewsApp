# Select the physical Wi-Fi adapter, never a VPN/default tunnel.
$wifiCandidates=@(Get-NetAdapter | Where-Object { $_.Status -eq 'Up' -and ($_.InterfaceType -eq 71 -or $_.NdisPhysicalMedium -in @(1,9)) })
$wifiNetwork=$null
foreach ($adapter in $wifiCandidates) {
    $configuration=Get-NetIPConfiguration -InterfaceIndex $adapter.InterfaceIndex
    $address=@($configuration.IPv4Address | Where-Object { $_.IPAddress -notlike '169.254.*' }) | Select-Object -First 1
    if ($address -and $configuration.IPv4DefaultGateway) {
        $bytes=[Net.IPAddress]::Parse($address.IPAddress).GetAddressBytes()
        $prefix=[int]$address.PrefixLength
        $networkBytes=New-Object byte[] 4
        $maskBytes=New-Object byte[] 4
        for ($index=0;$index -lt 4;$index++) {
            $bits=[Math]::Min(8,[Math]::Max(0,$prefix-8*$index))
            $mask=if($bits -eq 0){0}else{256-[Math]::Pow(2,8-$bits)}
            $networkBytes[$index]=$bytes[$index] -band [int]$mask
            $maskBytes[$index]=[byte]$mask
        }
        $wifiNetwork=[pscustomobject]@{ Ip=$address.IPAddress; Prefix=$prefix; Subnet=(($networkBytes -join '.')+'/'+$prefix); FilterSubnet=(($networkBytes -join '.')+'/'+($maskBytes -join '.')); Alias=$adapter.Name }
        break
    }
}
if (!$wifiNetwork) { throw 'No connected Wi-Fi IPv4 network found. Connect the laptop to Wi-Fi, then retry start-wifi.cmd.' }
