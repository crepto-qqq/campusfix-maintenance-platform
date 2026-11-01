[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$script:StageIndex = 0
$totalStages = 3
$repositoryRoot = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $repositoryRoot 'backend\.env.local'
$utf8WithoutBom = New-Object System.Text.UTF8Encoding($false)

function Show-Stage {
    param([Parameter(Mandatory = $true)][string]$Name)

    $script:StageIndex++
    Clear-Host
    Write-Host "CampusFix Brevo email setup" -ForegroundColor Cyan
    Write-Host "Stage $script:StageIndex/$totalStages - $Name" -ForegroundColor Cyan
    Write-Host
}

function Open-SetupPage {
    param([Parameter(Mandatory = $true)][string]$Url)

    Write-Host "Opening $Url" -ForegroundColor DarkGray
    try {
        Start-Process $Url
    } catch {
        Write-Warning "The browser could not be opened. Visit this URL manually: $Url"
    }
}

function Wait-ForUser {
    param([Parameter(Mandatory = $true)][string]$Message)

    Read-Host $Message | Out-Null
}

function Get-LocalValue {
    param([Parameter(Mandatory = $true)][string]$Key)

    if (-not (Test-Path -LiteralPath $envFile)) {
        return ''
    }

    $prefix = "$Key="
    foreach ($line in Get-Content -LiteralPath $envFile) {
        if ($line.StartsWith($prefix, [System.StringComparison]::Ordinal)) {
            return $line.Substring($prefix.Length)
        }
    }
    return ''
}

function Set-LocalValue {
    param(
        [Parameter(Mandatory = $true)][string]$Key,
        [Parameter(Mandatory = $true)][AllowEmptyString()][string]$Value
    )

    $lines = @()
    if (Test-Path -LiteralPath $envFile) {
        $prefix = "$Key="
        $lines = @(Get-Content -LiteralPath $envFile | Where-Object {
            -not $_.StartsWith($prefix, [System.StringComparison]::Ordinal)
        })
    }
    $lines += "$Key=$Value"
    [System.IO.File]::WriteAllLines($envFile, [string[]]$lines, $utf8WithoutBom)
    Write-Host "Saved $Key to backend/.env.local" -ForegroundColor Green
}

function Read-VisibleValue {
    param(
        [Parameter(Mandatory = $true)][string]$Key,
        [Parameter(Mandatory = $true)][string]$Prompt,
        [string]$DefaultValue = ''
    )

    $current = Get-LocalValue -Key $Key
    $suffix = if ($current) { ' [Enter keeps the current value]' } elseif ($DefaultValue) {
        " [default: $DefaultValue]"
    } else { '' }
    $value = Read-Host "$Prompt$suffix"
    if (-not $value) {
        if ($current) { return $current }
        return $DefaultValue
    }
    return $value.Trim()
}

function Read-SecretValue {
    param(
        [Parameter(Mandatory = $true)][string]$Key,
        [Parameter(Mandatory = $true)][string]$Prompt
    )

    $current = Get-LocalValue -Key $Key
    $suffix = if ($current) { ' [Enter keeps the current value]' } else { '' }
    $secureValue = Read-Host "$Prompt$suffix" -AsSecureString
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureValue)
    try {
        $value = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    } finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
    }

    if (-not $value -and $current) {
        return $current
    }
    return $value
}

Clear-Host
Write-Host 'CampusFix Brevo email setup' -ForegroundColor Cyan
Write-Host 'This wizard has three stages and never displays your API key.'
Wait-ForUser -Message 'Press Enter to start'

Show-Stage -Name 'Verify the CampusFix sender'
Write-Host 'Brevo must verify the mailbox used as the From address.'
Open-SetupPage -Url 'https://app.brevo.com/senders/list'
Write-Host '1. Sign in or create the shared Brevo account.'
Write-Host '2. Open Settings > Senders & IP > Senders and add a sender.'
Write-Host '3. Use the dedicated CampusFix mailbox and sender name CampusFix.'
Write-Host '4. Enter the verification code Brevo sends to that mailbox.'
Wait-ForUser -Message 'Press Enter after the sender is shown as verified'

do {
    $senderEmail = Read-VisibleValue -Key 'CAMPUSFIX_EMAIL_FROM_ADDRESS' -Prompt 'Verified sender email address'
    if ($senderEmail -notmatch '^[^\s@]+@[^\s@]+\.[^\s@]+$') {
        Write-Warning 'Enter a complete email address.'
    }
} while ($senderEmail -notmatch '^[^\s@]+@[^\s@]+\.[^\s@]+$')
$senderName = Read-VisibleValue -Key 'CAMPUSFIX_EMAIL_FROM_NAME' -Prompt 'Sender display name' -DefaultValue 'CampusFix'
Set-LocalValue -Key 'CAMPUSFIX_EMAIL_FROM_ADDRESS' -Value $senderEmail
Set-LocalValue -Key 'CAMPUSFIX_EMAIL_FROM_NAME' -Value $senderName

Show-Stage -Name 'Create the shared Brevo API key'
Write-Host "This key lets each member's local back-end call the Brevo REST API."
Open-SetupPage -Url 'https://app.brevo.com/settings/keys/api'
Write-Host '1. Open Settings > SMTP & API > API Keys & MCP.'
Write-Host "2. Generate a normal API key named 'CampusFix shared demo'."
Write-Host '3. Do not select the MCP key option.'
Write-Host '4. Choose an expiry no later than 7 November 2026.'
Write-Host '5. Copy the key now; Brevo displays the full value only once.'
Write-Warning 'Share this key only through the agreed private team channel.'
do {
    $apiKey = Read-SecretValue -Key 'CAMPUSFIX_BREVO_API_KEY' -Prompt 'Paste the Brevo API key'
    if (-not $apiKey) { Write-Warning 'The API key cannot be empty.' }
} while (-not $apiKey)
Set-LocalValue -Key 'CAMPUSFIX_BREVO_API_KEY' -Value $apiKey

Show-Stage -Name 'Finish the local CampusFix configuration'
Write-Host 'The administrator password is local to this computer and should not be shared.'
do {
    $adminPassword = Read-SecretValue -Key 'CAMPUSFIX_ADMIN_PASSWORD' -Prompt 'Local administrator password (at least 12 characters)'
    if ($adminPassword.Length -lt 12) { Write-Warning 'Use at least 12 characters.' }
} while ($adminPassword.Length -lt 12)
Set-LocalValue -Key 'CAMPUSFIX_ADMIN_PASSWORD' -Value $adminPassword
Set-LocalValue -Key 'CAMPUSFIX_EMAIL_VERIFICATION_REQUIRED' -Value 'true'

Write-Host 'Checking the API key without displaying it...'
try {
    Invoke-RestMethod `
        -Uri 'https://api.brevo.com/v3/account' `
        -Method Get `
        -Headers @{ 'api-key' = $apiKey } `
        -TimeoutSec 20 | Out-Null
    Write-Host 'Brevo accepted the API key.' -ForegroundColor Green
} catch {
    $status = if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { 'no response' }
    Write-Warning "Brevo returned $status. Recheck the key before starting CampusFix."
}

Write-Host
Write-Host 'Setup complete.' -ForegroundColor Green
Write-Host 'Local secrets were saved to backend/.env.local, which Git ignores.'
Write-Host 'Deactivate the shared key in Brevo no later than 7 November 2026.'
Write-Host
Write-Host 'Start the back-end:'
Write-Host '  cd backend'
Write-Host '  .\gradlew.bat bootRun'
