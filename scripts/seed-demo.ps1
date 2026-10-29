<#
.SYNOPSIS
    Seeds the BookMySeat demo data: one venue, one far-future show with 50 seats, and a public demo user.

.DESCRIPTION
    Idempotent: anything that already exists is skipped, so it is safe to run more than once.
    The admin password is only ever passed as a parameter; it is never stored in this script.

.EXAMPLE
    .\scripts\seed-demo.ps1 -BaseUrl https://<name>.onrender.com -AdminEmail you@example.com -AdminPassword '<prod admin password>'
#>
param(
	[Parameter(Mandatory)] [string] $BaseUrl,
	[Parameter(Mandatory)] [string] $AdminEmail,
	[Parameter(Mandatory)] [string] $AdminPassword,
	[string] $DemoEmail = 'demo@bookmyseat.dev',
	[string] $DemoPassword = 'DemoPass123'
)

$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12   # Windows PowerShell 5.1 + https
$BaseUrl = $BaseUrl.TrimEnd('/')

$VenueName = 'PVR Phoenix'
$ShowTitle = 'Dune: Part Three'
$ShowStart = '2027-12-31T18:30:00Z'   # far future, so the demo show never becomes unbookable

# Free-tier hosts can take about a minute to wake up, hence the long timeout
function Invoke-Api([string] $Method, [string] $Path, $Body = $null, [string] $Token = $null) {
	$params = @{ Method = $Method; Uri = "$BaseUrl$Path"; TimeoutSec = 180 }
	if ($Token) { $params.Headers = @{ Authorization = "Bearer $Token" } }
	if ($null -ne $Body) {
		$params.ContentType = 'application/json; charset=utf-8'
		$params.Body = [Text.Encoding]::UTF8.GetBytes(($Body | ConvertTo-Json -Compress))
	}
	$result = Invoke-RestMethod @params
	# Windows PowerShell 5.1 returns a JSON array as ONE object; writing it out here unrolls it,
	# so @(Invoke-Api ...) is a real list in both 5.1 and 7 (and an empty [] gives Count 0)
	$result
}

function Get-StatusCode($ErrorRecord) {
	$response = $ErrorRecord.Exception.Response
	if ($response) { return [int] $response.StatusCode }
	return $null
}

Write-Host "Seeding $BaseUrl (the first request may take a minute if the app is asleep)..."

# 1. Admin login
$token = (Invoke-Api POST '/api/auth/login' @{ email = $AdminEmail; password = $AdminPassword }).accessToken
Write-Host '  admin login        ok'

# 2. Venue
$venue = @(Invoke-Api GET '/api/venues') | Where-Object { $_.name -eq $VenueName } | Select-Object -First 1
if ($venue) {
	Write-Host "  venue              exists (id $($venue.id)), skipped"
} else {
	$venue = Invoke-Api POST '/api/venues' @{ name = $VenueName; city = 'Mumbai'; address = 'Lower Parel' } $token
	Write-Host "  venue              created (id $($venue.id))"
}

# 3. Show
$show = @(Invoke-Api GET "/api/shows?venueId=$($venue.id)") | Where-Object { $_.title -eq $ShowTitle } | Select-Object -First 1
if ($show) {
	Write-Host "  show               exists (id $($show.id)), skipped"
} else {
	$show = Invoke-Api POST '/api/shows' @{ title = $ShowTitle; startTime = $ShowStart; price = 250.00; venueId = $venue.id } $token
	Write-Host "  show               created (id $($show.id))"
}

# 4. Seats: 5 rows x 10 = A1-E10
$seats = @(Invoke-Api GET "/api/shows/$($show.id)/seats")
if ($seats.Count -gt 0) {
	Write-Host "  seats              $($seats.Count) exist, skipped"
} else {
	$seats = @(Invoke-Api POST "/api/shows/$($show.id)/seats" @{ rows = 5; seatsPerRow = 10 } $token)
	Write-Host "  seats              created $($seats.Count)"
}

# 5. Demo user (public credentials, always role USER)
try {
	Invoke-Api POST '/api/auth/register' @{ name = 'Demo User'; email = $DemoEmail; password = $DemoPassword } | Out-Null
	Write-Host "  demo user          created ($DemoEmail)"
} catch {
	if ((Get-StatusCode $_) -eq 409) {
		Write-Host "  demo user          exists ($DemoEmail), skipped"
	} else {
		throw
	}
}

Write-Host "Done. Show id $($show.id): $BaseUrl/api/shows/$($show.id)/seats"
