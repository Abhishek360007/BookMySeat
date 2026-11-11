<#
.SYNOPSIS
    Seeds the BookMySeat demo data: the demo show "Dune: Part Three" with 50 seats, a public demo user, and a small
    catalogue of films (runtime, language, certificate, synopsis, a poster for the made-up ones) with showtimes in
    Mumbai and Pune.

.DESCRIPTION
    Idempotent: anything that already exists is skipped (a film's details are brought up to date), so it is safe
    to run more than once. It never cancels or changes an existing show. Showtimes are far in the future (December
    2027 to January 2028), so the demo never runs out of bookable shows.
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

# A poster as multipart/form-data, built by hand: Windows PowerShell 5.1 has no -Form
function Send-Poster([long] $FilmId, [string] $Path, [string] $Token) {
	$boundary = [Guid]::NewGuid().ToString('N')
	$file = [IO.File]::ReadAllBytes($Path)
	$head = [Text.Encoding]::ASCII.GetBytes("--$boundary`r`nContent-Disposition: form-data; name=`"file`"; filename=`"$([IO.Path]::GetFileName($Path))`"`r`nContent-Type: image/jpeg`r`n`r`n")
	$tail = [Text.Encoding]::ASCII.GetBytes("`r`n--$boundary--`r`n")
	$body = New-Object byte[] ($head.Length + $file.Length + $tail.Length)
	[Array]::Copy($head, 0, $body, 0, $head.Length)
	[Array]::Copy($file, 0, $body, $head.Length, $file.Length)
	[Array]::Copy($tail, 0, $body, $head.Length + $file.Length, $tail.Length)
	Invoke-RestMethod -Method Put -Uri "$BaseUrl/api/admin/films/$FilmId/poster" -Headers @{ Authorization = "Bearer $Token" } `
		-ContentType "multipart/form-data; boundary=$boundary" -Body $body -TimeoutSec 180
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
	# Regular seats 250, premium 350, recliners 500; an aisle down the middle (after seat 5)
	$show = Invoke-Api POST '/api/shows' @{ title = $ShowTitle; startTime = $ShowStart; price = 250.00; venueId = $venue.id
		categoryPrices = @{ PREMIUM = 350.00; RECLINER = 500.00 }; aisleAfter = @(5) } $token
	Write-Host "  show               created (id $($show.id))"
}

# 4. Seats: 5 rows x 10 = A1-E10; row D premium, row E (the back) recliners
$seats = @(Invoke-Api GET "/api/shows/$($show.id)/seats")
if ($seats.Count -gt 0) {
	Write-Host "  seats              $($seats.Count) exist, skipped"
} else {
	$seats = @(Invoke-Api POST "/api/shows/$($show.id)/seats" @{ rows = 5; seatsPerRow = 10; premiumRows = 1; reclinerRows = 1 } $token)
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

# 6. The catalogue: films with their details, each with a few showtimes. The films are made up, except the demo show's.
$pune = @(Invoke-Api GET '/api/venues') | Where-Object { $_.name -eq 'Starlight Cinema' } | Select-Object -First 1
if ($pune) {
	Write-Host "  venue (Pune)       exists (id $($pune.id)), skipped"
} else {
	$pune = Invoke-Api POST '/api/venues' @{ name = 'Starlight Cinema'; city = 'Pune'; address = 'Koregaon Park' } $token
	Write-Host "  venue (Pune)       created (id $($pune.id))"
}

$catalogue = @(
	@{ title = $ShowTitle; runtimeMinutes = 165; language = 'English'; certificate = 'UA'
		synopsis = 'The next chapter of the desert saga, on the biggest screen in town.'; shows = @() },
	@{ title = 'The Monsoon Letters'; runtimeMinutes = 132; language = 'Hindi'; certificate = 'U'; poster = 'the-monsoon-letters.jpg'
		synopsis = "Two strangers in Mumbai trade letters through one rainy season.`nNeither knows the other lives three floors down."
		shows = @(@{ venue = $venue; start = '2027-12-18T13:30:00Z' }, @{ venue = $pune; start = '2027-12-19T14:00:00Z' }) },
	@{ title = 'Orbit of Ashes'; runtimeMinutes = 148; language = 'English'; certificate = 'UA'; poster = 'orbit-of-ashes.jpg'
		synopsis = 'A salvage crew answers a distress call from a ship that should not exist.'
		shows = @(@{ venue = $venue; start = '2027-12-20T15:30:00Z' }, @{ venue = $pune; start = '2027-12-21T16:00:00Z' }, @{ venue = $venue; start = '2028-01-08T15:30:00Z' }) },
	@{ title = 'Chai at Midnight'; runtimeMinutes = 104; language = 'Hindi'; certificate = 'U'; poster = 'chai-at-midnight.jpg'
		synopsis = 'One night-shift tea stall, eight customers, and the longest night of the year.'
		shows = @(@{ venue = $pune; start = '2027-12-22T16:30:00Z' }) },
	@{ title = 'Kaveri Express'; runtimeMinutes = 141; language = 'Tamil'; certificate = 'UA'; poster = 'kaveri-express.jpg'
		synopsis = 'A train from Chennai to Mysuru, a missing suitcase, and nobody who is quite who they say.'
		shows = @(@{ venue = $venue; start = '2027-12-27T12:30:00Z' }, @{ venue = $pune; start = '2028-01-03T13:00:00Z' }) }
)

foreach ($entry in $catalogue) {
	$details = @{ title = $entry.title; runtimeMinutes = $entry.runtimeMinutes; language = $entry.language
		certificate = $entry.certificate; synopsis = $entry.synopsis }
	$film = @((Invoke-Api GET "/api/admin/films?size=100&q=$([Uri]::EscapeDataString($entry.title))" $null $token).content) |
		Where-Object { $_.title -eq $entry.title } | Select-Object -First 1
	if ($film) {
		$film = Invoke-Api PUT "/api/admin/films/$($film.id)" $details $token
		Write-Host ("  film               {0}: details up to date" -f $entry.title)
	} else {
		$film = Invoke-Api POST '/api/admin/films' $details $token
		Write-Host ("  film               {0}: created (id {1})" -f $entry.title, $film.id)
	}
	# The made-up films' posters (scripts/posters, made by make-posters.mjs); a poster already there is kept
	if ($entry.poster) {
		if ($film.posterUrl) {
			Write-Host "    poster           exists, skipped"
		} else {
			Send-Poster $film.id (Join-Path $PSScriptRoot "posters\$($entry.poster)") $token | Out-Null
			Write-Host "    poster           uploaded ($($entry.poster))"
		}
	}
	foreach ($slot in $entry.shows) {
		$existing = @(Invoke-Api GET "/api/shows?venueId=$($slot.venue.id)") |
			Where-Object { $_.filmId -eq $film.id -and ([DateTime] $_.startTime).ToUniversalTime() -eq ([DateTime] $slot.start).ToUniversalTime() } |
			Select-Object -First 1
		if ($existing) {
			Write-Host ("    showtime         {0}, {1}: exists, skipped" -f $slot.start, $slot.venue.city)
			continue
		}
		$new = Invoke-Api POST '/api/shows' @{ filmId = $film.id; startTime = $slot.start; price = 220.00; venueId = $slot.venue.id
			categoryPrices = @{ PREMIUM = 320.00; RECLINER = 450.00 }; aisleAfter = @(5) } $token
		Invoke-Api POST "/api/shows/$($new.id)/seats" @{ rows = 5; seatsPerRow = 10; premiumRows = 1; reclinerRows = 1 } $token | Out-Null
		Write-Host ("    showtime         {0}, {1}: created (id {2}, 50 seats)" -f $slot.start, $slot.venue.city, $new.id)
	}
}

Write-Host "Done. Demo show id $($show.id): $BaseUrl/api/shows/$($show.id)/seats"
