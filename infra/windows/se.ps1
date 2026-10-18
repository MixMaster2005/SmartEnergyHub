# SmartEnergy Hub - outil de cycle de vie (Windows PowerShell 5.1+ / PowerShell 7)
# Utilisation : infra\windows\se.cmd <commande> ou powershell -File infra\windows\se.ps1 <commande>
$ErrorActionPreference = 'Continue'
$Root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
Set-Location $Root

$Project = 'smartenergy-hub'
$AppServices = @('sensor-service', 'collector-service', 'storage-service', 'analytics-service')
$AllReady = @('redis', 'mongo', 'cassandra', 'cassandra-init') + $AppServices
$KnownServices = $AllReady + @('simulator')
$DefaultTimeout = 420
$Volumes = @('redis_data', 'mongo_data', 'cassandra_data')
$Ports = @{ 'collector-service' = 8081; 'storage-service' = 8082; 'analytics-service' = 8083; 'sensor-service' = 8084 }
$script:RC = 0
$argv = @($args | ForEach-Object { [string]$_ })
$Command = if ($argv.Count -gt 0) { $argv[0] } else { 'help' }
$Rest = if ($argv.Count -gt 1) { @($argv | Select-Object -Skip 1) } else { @() }

function Info([string]$Message) { Write-Host "==> $Message" -ForegroundColor Cyan }
function Ok([string]$Message) { Write-Host " OK  $Message" -ForegroundColor Green }
function Warn([string]$Message) { Write-Host " !!  $Message" -ForegroundColor Yellow }
function Err([string]$Message) { Write-Host "ERR  $Message" -ForegroundColor Red }
function Die([string]$Message) { Err $Message; exit 1 }
function Have([string]$Name) { return [bool](Get-Command $Name -ErrorAction SilentlyContinue) }
function Test-Known([string]$Service) { return $KnownServices -contains $Service }
function Invoke-Compose([string[]]$ComposeArgs) { & docker compose @ComposeArgs }

function Get-Cid([string]$Service) {
    $result = @(Invoke-Compose -ComposeArgs @('ps', '-aq', $Service) 2>$null)
    if ($result.Count -gt 0 -and [string]$result[0]) { return [string]$result[0] }
    return ''
}
function Get-SvcState([string]$Service) {
    $cid = Get-Cid $Service
    if (-not $cid) { return 'absent' }
    $state = & docker inspect -f '{{.State.Status}}' $cid 2>$null
    $health = & docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{end}}' $cid 2>$null
    if ($health) { return [string]$health }
    return [string]$state
}
function Get-ExitCode([string]$Service) {
    $cid = Get-Cid $Service
    if (-not $cid) { return '' }
    return [string](& docker inspect -f '{{.State.ExitCode}}' $cid 2>$null)
}
function Test-Ready([string]$Service) {
    $state = Get-SvcState $Service
    if ($Service -eq 'cassandra-init') { return (($state -eq 'exited') -and ((Get-ExitCode $Service) -eq '0')) }
    return $state -eq 'healthy'
}
function Wait-Ready([string[]]$Services, [int]$Timeout) {
    Info "Attente des services (timeout ${Timeout}s) ..."
    $timer = [Diagnostics.Stopwatch]::StartNew()
    $lastPrinted = -10
    while ($true) {
        $pending = @()
        foreach ($service in $Services) {
            if (-not (Test-Ready $service)) {
                $state = Get-SvcState $service
                if (($service -ne 'cassandra-init') -and @('unhealthy', 'exited', 'dead').Contains($state)) {
                    Err "$service est en etat '$state'. Voir : infra\windows\se.cmd logs $service"
                    return $false
                }
                if (($service -eq 'cassandra-init') -and ($state -eq 'exited') -and ((Get-ExitCode $service) -ne '0')) {
                    Err 'cassandra-init a echoue. Voir : infra\windows\se.cmd logs cassandra-init'
                    return $false
                }
                $pending += "$service($state)"
            }
        }
        $elapsed = [int]$timer.Elapsed.TotalSeconds
        if ($pending.Count -eq 0) { Ok "Tous les services sont prets (${elapsed}s)"; return $true }
        if ($elapsed -ge $Timeout) { Err "Timeout apres ${Timeout}s. En attente : $($pending -join ' ')"; return $false }
        if (($elapsed - $lastPrinted) -ge 10) { Info "  en attente : $($pending -join ' ')"; $lastPrinted = $elapsed }
        Start-Sleep -Seconds 3
    }
}
function Show-Urls {
    Write-Host ''
    Write-Host '  Services              API                      Swagger UI'
    foreach ($service in @('collector-service', 'storage-service', 'analytics-service', 'sensor-service')) {
        $port = $Ports[$service]
        Write-Host ('  {0,-21} http://localhost:{1}    http://localhost:{1}/swagger-ui.html' -f $service, $port)
    }
    Write-Host ''
    Write-Host '  Sante : /api/health  |  Detail : /actuator/health/readiness  |  Metriques : /actuator/prometheus'
}
function Get-EnvValue([string]$Key) {
    $envPath = Join-Path $Root '.env'
    if (-not (Test-Path $envPath)) { return '' }
    foreach ($line in Get-Content $envPath) {
        if ($line -match ('^' + [regex]::Escape($Key) + '=(.*)$')) { return $Matches[1].Trim() }
    }
    return ''
}
function Set-EnvVar([string]$Key, [string]$Value) {
    $envPath = Join-Path $Root '.env'
    $found = $false
    $out = @()
    foreach ($line in Get-Content $envPath) {
        if ($line -match ('^' + [regex]::Escape($Key) + '=')) { $out += "$Key=$Value"; $found = $true }
        else { $out += $line }
    }
    if (-not $found) { $out += "$Key=$Value" }
    [IO.File]::WriteAllText($envPath, (($out -join "`n") + "`n"))
}
function New-Secret {
    $bytes = New-Object byte[] 12
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    return (($bytes | ForEach-Object { $_.ToString('x2') }) -join '')
}
function Ensure-Env([bool]$RandomSecrets = $false) {
    $envPath = Join-Path $Root '.env'
    if (-not (Test-Path $envPath)) {
        $example = Join-Path $Root '.env.example'
        if (-not (Test-Path $example)) { Die '.env absent et .env.example introuvable' }
        Copy-Item $example $envPath
        Ok '.env cree depuis .env.example'
        if ($RandomSecrets) {
            $existingVolumes = @(& docker volume ls -q)
            if ($existingVolumes -contains "${Project}_mongo_data") { Die 'Des volumes existent deja : les secrets ne seraient pas appliques. Faites d''abord infra\windows\se.cmd reset' }
            foreach ($key in @('REDIS_PASSWORD', 'MONGO_ROOT_PASSWORD', 'MONGO_SENSOR_PASSWORD', 'MONGO_ANALYTICS_PASSWORD')) { Set-EnvVar $key (New-Secret) }
            Ok 'Mots de passe aleatoires generes dans .env (ne pas commiter .env)'
        }
    } elseif ($RandomSecrets) { Warn '.env existe deja : --random-secrets ignore' }
}
function Test-PortInUse([int]$Port) {
    $client = New-Object Net.Sockets.TcpClient
    try { $async = $client.BeginConnect('127.0.0.1', $Port, $null, $null); return ($async.AsyncWaitHandle.WaitOne(250) -and $client.Connected) }
    catch { return $false } finally { $client.Close() }
}
function Cmd-Doctor {
    $errors = 0; $warnings = 0
    Info 'Verification de l''environnement'
    if (-not (Have 'docker')) { Err 'docker introuvable (installez Docker Desktop)'; $errors++ }
    else {
        Ok (& docker --version)
        & docker info *> $null
        if ($LASTEXITCODE -eq 0) { Ok 'le daemon Docker repond' } else { Err 'le daemon Docker ne repond pas'; $errors++ }
        & docker compose version *> $null
        if ($LASTEXITCODE -eq 0) { Ok ('docker compose : ' + (& docker compose version --short 2>$null)) } else { Err 'docker compose v2 introuvable'; $errors++ }
        $memory = & docker info --format '{{.MemTotal}}' 2>$null
        if ([string]$memory -match '^\d+$' -and [int64]$memory -lt 6000000000) { Warn 'Docker dispose de moins de 6 Go de RAM : Cassandra et les services Java peuvent manquer de memoire.'; $warnings++ }
    }
    if (Test-Path (Join-Path $Root 'docker-compose.yml')) { Ok 'docker-compose.yml present' } else { Err 'docker-compose.yml introuvable (lancez depuis la racine du depot)'; $errors++ }
    if (Test-Path (Join-Path $Root '.env')) { Ok '.env present' } else { Warn '.env absent : il sera cree par first-start'; $warnings++ }
    $runningContainers = @()
    if (Have 'docker') { $runningContainers = @(Invoke-Compose -ComposeArgs @('ps', '-q') 2>$null) }
    if ($runningContainers.Count -eq 0) {
        foreach ($port in @(6379, 27017, 9042, 8081, 8082, 8083, 8084)) { if (Test-PortInUse $port) { Warn "le port $port est deja utilise"; $warnings++ } }
    }
    if (-not (Have 'mvn')) { Info 'Maven local absent : build/test utiliseront un conteneur Maven' }
    if (-not (Have 'curl')) { Info 'curl absent : health utilise Invoke-WebRequest' }
    if ($errors -gt 0) { Err "$errors erreur(s), $warnings avertissement(s)"; return $false }
    Ok "Environnement pret ($warnings avertissement(s))"
    return $true
}
function Parse-Timeout([string[]]$Items, [ref]$Index) {
    $Index.Value++
    if ($Index.Value -ge $Items.Count) { return $DefaultTimeout }
    try { return [int]$Items[$Index.Value] } catch { Die 'timeout invalide' }
}
function Cmd-FirstStart([string[]]$Items) {
    $timeout = $DefaultTimeout; $random = $false
    for ($i = 0; $i -lt $Items.Count; $i++) {
        switch ($Items[$i]) {
            '--random-secrets' { $random = $true }
            '--timeout' { $timeout = Parse-Timeout $Items ([ref]$i) }
            default { Die "option inconnue : $($Items[$i])" }
        }
    }
    if (-not (Cmd-Doctor)) { Die 'Corrigez les erreurs ci-dessus puis relancez.' }
    Ensure-Env $random
    Info 'Construction des images (premier build : Maven telecharge les dependances) ...'
    Invoke-Compose -ComposeArgs @('build'); if ($LASTEXITCODE -ne 0) { Die 'Echec du build' }
    Invoke-Compose -ComposeArgs @('up', '-d'); if ($LASTEXITCODE -ne 0) { Die 'Echec du demarrage' }
    if (-not (Wait-Ready $AllReady $timeout)) { exit 1 }
    Show-Urls; Ok 'Premier demarrage termine.'
}
function Cmd-Start([string[]]$Items) {
    $timeout = $DefaultTimeout; $wait = $true; $services = @()
    for ($i = 0; $i -lt $Items.Count; $i++) {
        switch -Regex ($Items[$i]) {
            '^--no-wait$' { $wait = $false }
            '^--timeout$' { $timeout = Parse-Timeout $Items ([ref]$i) }
            '^-' { Die "option inconnue : $($Items[$i])" }
            default { if (-not (Test-Known $Items[$i])) { Die "service inconnu : $($Items[$i])" }; $services += $Items[$i] }
        }
    }
    Ensure-Env
    Invoke-Compose -ComposeArgs (@('up', '-d') + $services); if ($LASTEXITCODE -ne 0) { Die 'Echec du demarrage' }
    if ($wait) { $targets = if ($services.Count) { $services } else { $AllReady }; if (-not (Wait-Ready $targets $timeout)) { exit 1 }; if (-not $services.Count) { Show-Urls } }
}
function Cmd-Stop([string[]]$Items) { Invoke-Compose -ComposeArgs (@('stop') + $Items); if ($LASTEXITCODE -eq 0) { Ok 'Arret effectue (conteneurs et donnees conserves)' } }
function Cmd-Down { Invoke-Compose -ComposeArgs @('--profile', 'tools', 'down', '--remove-orphans'); if ($LASTEXITCODE -eq 0) { Ok 'Conteneurs supprimes (volumes conserves)' } }
function Cmd-Restart([string[]]$Items) { Invoke-Compose -ComposeArgs (@('restart') + $Items) }
function Cmd-Rebuild([string[]]$Items) {
    $services = @(); $flags = @(); $timeout = $DefaultTimeout
    for ($i = 0; $i -lt $Items.Count; $i++) {
        switch ($Items[$i]) {
            '--no-cache' { $flags += '--no-cache' }
            '--timeout' { $timeout = Parse-Timeout $Items ([ref]$i) }
            default { if ($Items[$i].StartsWith('-')) { Die "option inconnue : $($Items[$i])" }; if (-not (Test-Known $Items[$i])) { Die "service inconnu : $($Items[$i])" }; $services += $Items[$i] }
        }
    }
    if (-not $services.Count) { $services = $AppServices }
    Info "Reconstruction : $($services -join ' ')"
    Invoke-Compose -ComposeArgs (@('build') + $flags + $services); if ($LASTEXITCODE -ne 0) { Die 'Echec du build' }
    Invoke-Compose -ComposeArgs (@('up', '-d', '--no-deps') + $services); if ($LASTEXITCODE -ne 0) { Die 'Echec du redemarrage' }
    [void](Wait-Ready $services $timeout)
}
function Cmd-Logs([string[]]$Items) {
    $services = @(); $tail = '100'; $follow = $true; $since = ''; $pretty = $false
    for ($i = 0; $i -lt $Items.Count; $i++) {
        switch ($Items[$i]) {
            { $_ -in '-n', '--tail' } { $i++; if ($i -lt $Items.Count) { $tail = $Items[$i] } }
            '--no-follow' { $follow = $false }
            '--since' { $i++; if ($i -lt $Items.Count) { $since = $Items[$i] } }
            '--pretty' { $pretty = $true }
            '--raw' { $pretty = $false }
            default { if ($Items[$i].StartsWith('-')) { Die "option inconnue : $($Items[$i])" }; if (-not (Test-Known $Items[$i])) { Die "service inconnu : $($Items[$i])" }; $services += $Items[$i] }
        }
    }
    $composeArgs = @('logs', '--tail', $tail)
    if ($follow) { $composeArgs += '-f' }; if ($since) { $composeArgs += @('--since', $since) }; if ($pretty) { $composeArgs += '--no-log-prefix' }
    if ($pretty -and $services.Count -ne 1) { Die '--pretty necessite exactement un service' }
    if ($pretty) { Invoke-Compose -ComposeArgs ($composeArgs + $services) 2>&1 | ForEach-Object { $line = [string]$_; try { $json = $line | ConvertFrom-Json -ErrorAction Stop; '{0} {1} {2}' -f $json.'@timestamp', $json.'log.level', $json.message } catch { $line } } }
    else { Invoke-Compose -ComposeArgs ($composeArgs + $services) }
}
function Get-HttpCode([string]$Url) {
    try { return [int](Invoke-WebRequest -UseBasicParsing -TimeoutSec 3 -Uri $Url).StatusCode }
    catch { if ($_.Exception.Response) { return [int]$_.Exception.Response.StatusCode }; return 0 }
}
function Show-Health {
    $bad = $false
    foreach ($service in $AppServices) {
        $code = Get-HttpCode "http://localhost:$($Ports[$service])/actuator/health/readiness"
        if ($code -eq 200) { Write-Host ('  {0,-18} UP    http://localhost:{1}' -f $service, $Ports[$service]) -ForegroundColor Green }
        else { Write-Host ('  {0,-18} DOWN  (HTTP {1})' -f $service, $code) -ForegroundColor Red; $bad = $true }
    }
    return (-not $bad)
}
function Cmd-Status { Invoke-Compose -ComposeArgs @('--profile', 'tools', 'ps', '-a'); Write-Host ''; [void](Show-Health) }
function Cmd-Health { Info 'Sante des services (readiness)'; if (Show-Health) { Ok 'Tous les services repondent' } else { Warn 'Au moins un service est indisponible : infra\windows\se.cmd logs <service>'; $script:RC = 1 } }
function Cmd-Open { foreach ($port in @(8081, 8082, 8083, 8084)) { Start-Process "http://localhost:$port/swagger-ui.html" } }
function Confirm-Danger([string]$Message, [bool]$Yes) { Warn $Message; if ($Yes) { return $true }; return ((Read-Host "Tapez 'oui' pour confirmer") -eq 'oui') }
function Cmd-Reset([string[]]$Items) {
    $yes = ($Items -contains '--yes') -or ($Items -contains '-y')
    if (-not (Confirm-Danger 'RESET : supprime les conteneurs ET les volumes (Redis, MongoDB, Cassandra). Toutes les donnees seront perdues.' $yes)) { Info 'Annule.'; return }
    Invoke-Compose -ComposeArgs @('--profile', 'tools', 'down', '-v', '--remove-orphans'); if ($LASTEXITCODE -eq 0) { Ok 'Reset effectue. Les scripts d''initialisation seront rejoues au prochain demarrage.' }
}
function Cmd-Clean([string[]]$Items) {
    $yes = ($Items -contains '--yes') -or ($Items -contains '-y')
    if (-not (Confirm-Danger 'CLEAN : reset complet + suppression des images construites localement.' $yes)) { Info 'Annule.'; return }
    Invoke-Compose -ComposeArgs @('--profile', 'tools', 'down', '-v', '--rmi', 'local', '--remove-orphans'); Ok 'Images locales et volumes supprimes.'
}
function Cmd-Stats { $ids = @(Invoke-Compose -ComposeArgs @('ps', '-q')); if (-not $ids.Count) { Info 'Aucun conteneur en cours'; return }; & docker stats --no-stream $ids }
function Invoke-Mvn([string[]]$MvnArgs) {
    if ((Have 'mvn') -and ($env:SE_DOCKER_MAVEN -ne '1')) { & mvn -B @MvnArgs }
    else { if (-not (Have 'docker')) { Die 'Ni Maven ni Docker disponibles' }; Info 'Maven local absent : execution dans un conteneur'; & docker run --rm -v "${Root}:/workspace" -v se-m2:/root/.m2 -w /workspace maven:3.9-eclipse-temurin-17 mvn -B @MvnArgs }
}
function Cmd-Build([string[]]$Items) {
    if ($Items.Count) {
        if (-not ((Test-Known $Items[0]) -or $Items[0] -eq 'simulator')) { Die "module inconnu : $($Items[0])" }
        Invoke-Mvn @('clean', 'package', '-DskipTests', '-pl', $Items[0], '-am')
    } else { Invoke-Mvn @('clean', 'package', '-DskipTests') }
}
function Cmd-Test([string[]]$Items) {
    if ($Items.Count) {
        if (-not ((Test-Known $Items[0]) -or $Items[0] -eq 'simulator')) { Die "module inconnu : $($Items[0])" }
        Invoke-Mvn @('test', '-pl', $Items[0], '-am')
    } else { Invoke-Mvn @('test') }
}
function Cmd-Sim([string[]]$Items) {
    $simArgs = @()
    for ($i = 0; $i -lt $Items.Count; $i++) {
        switch -Regex ($Items[$i]) {
            '^--anomaly$' { $simArgs += '--smartenergy.simulator.anomaly.sensor=SENSOR-003' }
            '^--anomaly=.+' { $simArgs += ('--smartenergy.simulator.anomaly.sensor=' + $Items[$i].Substring(10)) }
            '^--chaos$' { $simArgs += @('--smartenergy.simulator.invalid-rate=0.05', '--smartenergy.simulator.duplicate-rate=0.02') }
            '^--long$' { $simArgs += '--smartenergy.simulator.duration=PT0S' }
            '^--sensors$' { $i++; if ($i -lt $Items.Count) { $simArgs += "--smartenergy.simulator.sensors=$($Items[$i])" } }
            '^--duration$' { $i++; if ($i -lt $Items.Count) { $simArgs += "--smartenergy.simulator.duration=$($Items[$i])" } }
            '^--interval$' { $i++; if ($i -lt $Items.Count) { $simArgs += "--smartenergy.simulator.interval=$($Items[$i])" } }
            default { $simArgs += $Items[$i] }
        }
    }
    Ensure-Env; Info 'Simulateur (Ctrl+C pour interrompre)'; Invoke-Compose -ComposeArgs (@('run', '--rm', 'simulator') + $simArgs)
}
function Invoke-RedisCli([string[]]$Items, [bool]$Interactive = $false) {
    $password = Get-EnvValue 'REDIS_PASSWORD'
    $prefix = if ($Interactive) { @('exec', '-e', "REDISCLI_AUTH=$password", 'redis', 'redis-cli', '--no-auth-warning') } else { @('exec', '-T', '-e', "REDISCLI_AUTH=$password", 'redis', 'redis-cli', '--no-auth-warning') }
    Invoke-Compose -ComposeArgs ($prefix + $Items)
}
function Cmd-Shell([string[]]$Items) {
    if (-not $Items.Count) { Die 'usage : shell <redis|mongo|cassandra|service>' }
    switch ($Items[0]) {
        'redis' { Invoke-RedisCli @() $true }
        { $_ -in 'mongo', 'mongosh' } { Invoke-Compose -ComposeArgs @('exec', 'mongo', 'sh', '-c', 'mongosh -u $MONGO_INITDB_ROOT_USERNAME -p $MONGO_INITDB_ROOT_PASSWORD --authenticationDatabase admin') }
        { $_ -in 'cassandra', 'cql', 'cqlsh' } { Invoke-Compose -ComposeArgs @('exec', 'cassandra', 'cqlsh') }
        default { if (-not (Test-Known $Items[0])) { Die "service inconnu : $($Items[0])" }; Invoke-Compose -ComposeArgs @('exec', $Items[0], 'bash') }
    }
}
function Cmd-Redis([string[]]$Items) { if ($Items.Count) { Invoke-RedisCli $Items } else { Cmd-Shell @('redis') } }
function Cmd-Cql([string[]]$Items) { if (-not $Items.Count) { Invoke-Compose -ComposeArgs @('exec', 'cassandra', 'cqlsh') } else { Invoke-Compose -ComposeArgs (@('exec', '-T', 'cassandra', 'cqlsh', '-e', ($Items -join ' '))) } }
function Cmd-Mongo([string[]]$Items) {
    if (-not $Items.Count) { Cmd-Shell @('mongo') }
    else { Invoke-Compose -ComposeArgs @('exec', '-T', 'mongo', 'sh', '-c', 'mongosh -u "$MONGO_INITDB_ROOT_USERNAME" -p "$MONGO_INITDB_ROOT_PASSWORD" --authenticationDatabase admin --quiet --eval "$1"', '_', ($Items -join ' ')) }
}
function Cmd-Stream { Info 'Stream energy-measurements'; Write-Host -NoNewline 'XLEN          : '; Invoke-RedisCli @('XLEN', 'energy-measurements'); Write-Host -NoNewline 'DLQ (XLEN)    : '; Invoke-RedisCli @('XLEN', 'energy-measurements-dlq'); Write-Host '--- groupes (pending = livres non acquittes, lag = restant a lire) ---'; Invoke-RedisCli @('XINFO', 'GROUPS', 'energy-measurements') }
function Cmd-Count([string[]]$Items) {
    if (-not $Items.Count) { Die 'usage : count <SENSOR-ID> [YYYY-MM-DD]' }
    $sensor = $Items[0]; $day = if ($Items.Count -gt 1) { $Items[1] } else { [DateTime]::UtcNow.ToString('yyyy-MM-dd') }
    if ($sensor -notmatch '^[A-Za-z0-9_-]+$' -or $day -notmatch '^\d{4}-\d{2}-\d{2}$') { Die 'identifiant ou date invalide' }
    Cmd-Cql @("SELECT count(*) FROM smartenergy.measurements_by_sensor_day WHERE sensor_id='$sensor' AND day='$day';")
}
function Cmd-E2E { $path = Join-Path $Root 'scripts/e2e.sh'; if (-not (Test-Path $path)) { Die 'scripts/e2e.sh introuvable; executez la recette depuis Git Bash ou installez bash' }; if (Have 'bash') { & bash $path } else { Die 'bash introuvable : utilisez Git Bash pour e2e' } }
function Cmd-Todo { Get-ChildItem -Path $Root -Recurse -Filter '*.java' -ErrorAction SilentlyContinue | Select-String -Pattern '^// TODO\(' | ForEach-Object { '{0}:{1}:{2}' -f $_.Path.Substring($Root.Length + 1), $_.LineNumber, $_.Line } }
function Cmd-Pull { Invoke-Compose -ComposeArgs @('pull', 'redis', 'mongo', 'cassandra', 'cassandra-init') }
function Cmd-Backup {
    $timestamp = Get-Date -Format 'yyyyMMdd-HHmmss'; $dir = Join-Path $Root "backups\$timestamp"
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $running = @(Invoke-Compose -ComposeArgs @('ps', '-q')).Count
    Warn 'Arret temporaire de la plateforme pour une sauvegarde coherente (Cassandra).'; Invoke-Compose -ComposeArgs @('stop') | Out-Null
    foreach ($volume in $Volumes) {
        & docker volume inspect "${Project}_$volume" *> $null
        if ($LASTEXITCODE -eq 0) { Info "Sauvegarde du volume $volume ..."; & docker run --rm -v "${Project}_${volume}:/data:ro" -v "${dir}:/backup" alpine tar czf "/backup/$volume.tar.gz" -C /data .; if ($LASTEXITCODE -ne 0) { Die "Echec de la sauvegarde de $volume" } }
        else { Warn "Volume $volume absent : ignore" }
    }
    if ($running -gt 0) { Invoke-Compose -ComposeArgs @('start') | Out-Null; Info 'Plateforme redemarree.' }
    Ok "Sauvegarde dans backups\$timestamp"
}
function Cmd-Restore([string[]]$Items) {
    $backups = Join-Path $Root 'backups'; if (-not (Test-Path $backups)) { Die 'Aucun dossier backups\' }
    $names = @(Get-ChildItem $backups -Directory | Sort-Object Name | ForEach-Object Name)
    $yes = ($Items -contains '--yes') -or ($Items -contains '-y')
    $requested = @($Items | Where-Object { $_ -notin @('--yes', '-y') } | Select-Object -First 1)
    $selected = if ($requested.Count -gt 0 -and $requested[0] -ne 'latest') { $requested[0] } else { if ($names.Count) { $names[-1] } else { '' } }
    if (-not $selected) { Die 'Aucune sauvegarde disponible' }
    if (-not (Confirm-Danger "RESTAURATION de backups\$selected : les donnees actuelles seront REMPLACEES." $yes)) { Info 'Annule.'; return }
    $dir = Join-Path $backups $selected; if (-not (Test-Path $dir)) { Die "Sauvegarde introuvable : backups\$selected" }
    Invoke-Compose -ComposeArgs @('--profile', 'tools', 'down', '--remove-orphans') | Out-Null
    foreach ($volume in $Volumes) {
        $archive = Join-Path $dir "$volume.tar.gz"
        if (Test-Path $archive) { & docker volume create "${Project}_$volume" | Out-Null; & docker run --rm -v "${Project}_${volume}:/data" -v "${dir}:/backup" alpine sh -c "rm -rf /data/* /data/.[!.]* 2>/dev/null; tar xzf /backup/$volume.tar.gz -C /data"; if ($LASTEXITCODE -ne 0) { Die "Echec de la restauration de $volume" } }
        else { Warn "$volume.tar.gz absent de la sauvegarde : ignore" }
    }
    Invoke-Compose -ComposeArgs @('up', '-d'); [void](Wait-Ready $AllReady $DefaultTimeout)
}
function Cmd-Help {
@'
SmartEnergy Hub - outil de cycle de vie (Linux/macOS/Git Bash : infra/linux/se.sh <commande>)
DEMARRER / ARRETER
  first-start [--random-secrets] [--timeout N]  Verifie, cree .env, construit et demarre
  start [service...] [--no-wait]                Demarre sans rebuild
  stop [service...]                             Arrete, conserve les donnees
  restart [service...]                          Redemarre les conteneurs
  down                                          Supprime les conteneurs, garde les volumes
  rebuild [service...] [--no-cache]             Reconstruit et redemarre les services
  reset [--yes] | clean [--yes]                 DANGER : efface les volumes (et les images pour clean)
OBSERVER
  status | health | logs [service...] [-n N] [--no-follow] [--since 10m] [--pretty]
  stream | count <SENSOR> [YYYY-MM-DD] | stats | urls | open | doctor
DONNEES DE TEST
  simulate [--anomaly[=SENSOR-003]] [--chaos] [--long] [--sensors N] [--duration PT5M] [--interval PT1S]
  e2e
BASES DE DONNEES
  shell <redis|mongo|cassandra|service> | redis [commande...] | cql [requete] | mongo [js]
  backup | restore [dossier|latest] [--yes]
DEVELOPPEMENT
  build [module] | test [module] | pull | todo
Services : redis mongo cassandra sensor-service collector-service storage-service analytics-service simulator
'@ | Write-Host
}

switch -Regex ($Command) {
    '^(help|-h|--help)$' { Cmd-Help }
    '^doctor$' { if (-not (Cmd-Doctor)) { $script:RC = 1 } }
    '^(first-start|init|bootstrap)$' { Cmd-FirstStart $Rest }
    '^(start|up)$' { Cmd-Start $Rest }
    '^stop$' { Cmd-Stop $Rest }
    '^down$' { Cmd-Down }
    '^restart$' { Cmd-Restart $Rest }
    '^rebuild$' { Cmd-Rebuild $Rest }
    '^reset$' { Cmd-Reset $Rest }
    '^clean$' { Cmd-Clean $Rest }
    '^(logs|log)$' { Cmd-Logs $Rest }
    '^(status|ps)$' { Cmd-Status }
    '^health$' { Cmd-Health }
    '^urls$' { Show-Urls }
    '^open$' { Cmd-Open }
    '^stats$' { Cmd-Stats }
    '^stream$' { Cmd-Stream }
    '^count$' { Cmd-Count $Rest }
    '^(sim|simulate|simulator)$' { Cmd-Sim $Rest }
    '^e2e$' { Cmd-E2E }
    '^(shell|sh)$' { Cmd-Shell $Rest }
    '^(redis|redis-cli)$' { Cmd-Redis $Rest }
    '^(cql|cqlsh)$' { Cmd-Cql $Rest }
    '^(mongo|mongosh)$' { Cmd-Mongo $Rest }
    '^backup$' { Cmd-Backup }
    '^restore$' { Cmd-Restore $Rest }
    '^build$' { Cmd-Build $Rest }
    '^test$' { Cmd-Test $Rest }
    '^pull$' { Cmd-Pull }
    '^todo$' { Cmd-Todo }
    default { Err "commande inconnue : $Command"; Cmd-Help; $script:RC = 1 }
}
exit $script:RC
