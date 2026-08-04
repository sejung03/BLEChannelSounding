[CmdletBinding(DefaultParameterSetName = 'Serial')]
param(
    [Parameter(Mandatory = $true, ParameterSetName = 'Serial')]
    [ValidatePattern('^COM\d+$')]
    [string]$Port,

    [Parameter(Mandatory = $true, ParameterSetName = 'Replay')]
    [ValidateScript({ Test-Path -LiteralPath $_ -PathType Leaf })]
    [string]$ReplayInputPath,

    [ValidateRange(1, 4000000)]
    [int]$BaudRate = 115200,

    [string]$OutputDirectory = $PWD,

    [string]$OutputPath
)

$ErrorActionPreference = 'Stop'
$culture = [Globalization.CultureInfo]::InvariantCulture
$captureStartedAt = [DateTimeOffset]::Now
$fileTimestamp = $captureStartedAt.ToString('yyyyMMdd_HHmmss_fff', $culture)

if ([string]::IsNullOrWhiteSpace($OutputPath)) {
    $OutputPath = Join-Path $OutputDirectory "${fileTimestamp}_CS_INITIATOR.csv"
}

$absoluteOutputPath = [System.IO.Path]::GetFullPath($OutputPath)
$absoluteOutputDirectory = [System.IO.Path]::GetDirectoryName($absoluteOutputPath)
[System.IO.Directory]::CreateDirectory($absoluteOutputDirectory) | Out-Null

$script:writer = $null
$script:serial = $null
$script:sessionInitialized = $false
$script:firstDeviceUptimeMs = $null
$script:lastElapsedMs = 0L
$script:sampleCount = 0
$script:peerAddress = ''
$script:boardStopAcknowledged = $false
$script:movingWindow = [Collections.Generic.Queue[double]]::new()
$script:movingSum = 0.0
$marker = 'PHONECS_DISTANCE,'
$stopMarker = 'PHONECS_STOPPED,'
$stopwatch = [Diagnostics.Stopwatch]::StartNew()

function ConvertTo-QuotedCsvField {
    param([AllowNull()][string]$Value)

    if ($null -eq $Value) {
        $Value = ''
    }

    return '"' + $Value.Replace('"', '""') + '"'
}

function Write-PhoneCsRow {
    param([string[]]$Fields)

    $quotedFields = @($Fields | ForEach-Object { ConvertTo-QuotedCsvField $_ })
    $script:writer.WriteLine($quotedFields -join ',')
    $script:writer.Flush()
}

function Get-PhoneCsTimestamp {
    param([long]$ElapsedMs)

    return $captureStartedAt.AddMilliseconds($ElapsedMs).ToString(
        "yyyy-MM-dd'T'HH:mm:ss.fffzzz",
        $culture
    )
}

function Write-PhoneCsEvent {
    param(
        [long]$ElapsedMs,
        [string]$Source,
        [string]$Event
    )

    Write-PhoneCsRow @(
        (Get-PhoneCsTimestamp $ElapsedMs),
        $ElapsedMs.ToString($culture),
        'INITIATOR',
        'EVENT',
        $Source,
        $Event,
        '',
        '',
        '',
        $script:peerAddress
    )
}

function Process-PhoneCsLine {
    param([string]$Line)

    $markerIndex = $Line.IndexOf($marker, [StringComparison]::Ordinal)
    if ($markerIndex -lt 0) {
        return
    }

    $record = $Line.Substring($markerIndex + $marker.Length).Trim()
    $fields = $record.Split(',')
    if ($fields.Count -ne 5) {
        Write-Warning "Ignored malformed PhoneCS record: $record"
        return
    }

    $deviceUptimeMs = 0L
    $rawDistance = 0.0
    if (-not [long]::TryParse($fields[0], [Globalization.NumberStyles]::Integer, $culture, [ref]$deviceUptimeMs)) {
        Write-Warning "Ignored record with invalid uptime: $record"
        return
    }
    if (-not [double]::TryParse($fields[3], [Globalization.NumberStyles]::Float, $culture, [ref]$rawDistance) -or
        [double]::IsNaN($rawDistance) -or [double]::IsInfinity($rawDistance)) {
        Write-Warning "Ignored record with invalid distance: $record"
        return
    }

    $script:peerAddress = $fields[4].Trim().ToUpperInvariant()

    if ($PSCmdlet.ParameterSetName -eq 'Replay') {
        if ($null -eq $script:firstDeviceUptimeMs) {
            $script:firstDeviceUptimeMs = $deviceUptimeMs
        }
        $elapsedMs = $deviceUptimeMs - $script:firstDeviceUptimeMs
    }
    else {
        $elapsedMs = $stopwatch.ElapsedMilliseconds
    }

    if (-not $script:sessionInitialized) {
        $script:sessionInitialized = $true
        Write-PhoneCsEvent 0 'STORAGE' 'CSV 저장 시작: PC/PhoneCS'
    }

    $script:movingWindow.Enqueue($rawDistance)
    $script:movingSum += $rawDistance
    if ($script:movingWindow.Count -gt 5) {
        $script:movingSum -= $script:movingWindow.Dequeue()
    }

    $script:sampleCount++
    $script:lastElapsedMs = $elapsedMs
    $smoothedDistance = $script:movingSum / $script:movingWindow.Count

    Write-PhoneCsRow @(
        (Get-PhoneCsTimestamp $elapsedMs),
        $elapsedMs.ToString($culture),
        'INITIATOR',
        'DISTANCE',
        'LOCAL',
        'distance measured',
        $rawDistance.ToString('0.000000', $culture),
        $smoothedDistance.ToString('0.000000', $culture),
        $script:sampleCount.ToString($culture),
        $script:peerAddress
    )

    Write-Host ("[{0}] raw={1:F6} m, smoothed={2:F6} m, peer={3}" -f
        $script:sampleCount, $rawDistance, $smoothedDistance, $script:peerAddress)
}

function Test-PhoneCsStoppedLine {
    param([string]$Line)

    $markerIndex = $Line.IndexOf($stopMarker, [StringComparison]::Ordinal)
    if ($markerIndex -lt 0) {
        return $false
    }

    $record = $Line.Substring($markerIndex + $stopMarker.Length).Trim()
    $fields = $record.Split(',')
    if ($fields.Count -ne 2) {
        Write-Warning "Ignored malformed PhoneCS stop record: $record"
        return $false
    }

    $deviceUptimeMs = 0L
    if (-not [long]::TryParse($fields[0], [Globalization.NumberStyles]::Integer, $culture, [ref]$deviceUptimeMs)) {
        Write-Warning "Ignored stop record with invalid uptime: $record"
        return $false
    }

    $script:peerAddress = $fields[1].Trim().ToUpperInvariant()
    if ($PSCmdlet.ParameterSetName -eq 'Replay' -and $null -ne $script:firstDeviceUptimeMs) {
        $elapsedMs = $deviceUptimeMs - $script:firstDeviceUptimeMs
    }
    else {
        $elapsedMs = $stopwatch.ElapsedMilliseconds
    }

    $script:lastElapsedMs = $elapsedMs
    $script:boardStopAcknowledged = $true
    if ($script:sessionInitialized) {
        Write-PhoneCsEvent $elapsedMs 'CS' 'CS stopped: INITIATOR'
    }
    Write-Host 'Board acknowledged CS stop.'

    return $true
}

try {
    if ($PSCmdlet.ParameterSetName -eq 'Serial') {
        $script:serial = [IO.Ports.SerialPort]::new(
            $Port,
            $BaudRate,
            [IO.Ports.Parity]::None,
            8,
            [IO.Ports.StopBits]::One
        )
        $script:serial.NewLine = "`n"
        $script:serial.ReadTimeout = 1000
        $script:serial.Open()
    }

    $script:writer = [IO.StreamWriter]::new(
        $absoluteOutputPath,
        $false,
        [Text.UTF8Encoding]::new($false)
    )
    $script:writer.WriteLine(
        'timestamp,elapsed_ms,role,record_type,source,event,raw_distance_m,' +
        'smoothed_distance_m,sample_count,peer_address'
    )
    $script:writer.Flush()

    if ($PSCmdlet.ParameterSetName -eq 'Replay') {
        Write-Host "Replaying UART log: $ReplayInputPath"
        foreach ($line in [IO.File]::ReadLines([IO.Path]::GetFullPath($ReplayInputPath))) {
            if (Test-PhoneCsStoppedLine $line) {
                break
            }
            Process-PhoneCsLine $line
        }
    }
    else {
        $stopRequested = $false
        $stopRequestTimer = [Diagnostics.Stopwatch]::new()

        Write-Host "Capturing $Port at $BaudRate baud"
        Write-Host 'Press Enter to stop the board measurement and save the CSV.'

        while ($true) {
            $keyAvailable = $false
            try {
                $keyAvailable = [Console]::KeyAvailable
            }
            catch [InvalidOperationException] {
                $keyAvailable = $false
            }

            if (-not $stopRequested -and $keyAvailable) {
                $key = [Console]::ReadKey($true)
                if ($key.Key -eq [ConsoleKey]::Enter) {
                    $script:serial.Write("STOP`n")
                    $stopRequested = $true
                    $stopRequestTimer.Restart()
                    Write-Host 'STOP sent; waiting for board acknowledgement...'
                }
            }

            try {
                $line = $script:serial.ReadLine()
            }
            catch [TimeoutException] {
                if ($stopRequested -and $stopRequestTimer.ElapsedMilliseconds -ge 10000) {
                    throw 'The board did not acknowledge STOP within 10 seconds.'
                }
                continue
            }

            if (Test-PhoneCsStoppedLine $line) {
                break
            }
            Process-PhoneCsLine $line
        }
    }
}
finally {
    if ($script:sessionInitialized -and $null -ne $script:writer) {
        if ($PSCmdlet.ParameterSetName -eq 'Replay') {
            $stopElapsedMs = $script:lastElapsedMs + 1
        }
        else {
            $stopElapsedMs = $stopwatch.ElapsedMilliseconds
        }
        Write-PhoneCsEvent $stopElapsedMs 'APP' '전체 세션 중지'
    }

    if ($null -ne $script:serial) {
        if ($script:serial.IsOpen) {
            $script:serial.Close()
        }
        $script:serial.Dispose()
    }

    if ($null -ne $script:writer) {
        $script:writer.Dispose()
        Write-Host "Saved $($script:sampleCount) distance rows to $absoluteOutputPath"
    }
}
