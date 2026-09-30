param(
    [string]$PostgresHome = 'C:\Program Files\PostgreSQL\18',
    [switch]$SkipDatabase
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'local-environment.ps1')
Import-LocalEnvironment -ProjectRoot $projectRoot
$issues = [Collections.Generic.List[string]]::new()
Write-Output "Project: $projectRoot"

$java = Get-Command java.exe -ErrorAction SilentlyContinue
if ($null -eq $java) {
    $issues.Add('Java 25 was not found in PATH.')
} else {
    # java -version uses stderr; avoid treating that output as a PowerShell error.
    $javaInfo = & $java.Source --version
    if ($LASTEXITCODE -ne 0 -or ($javaInfo | Select-Object -First 1) -notmatch '^(openjdk|java) 25(?:[. +]|$)') {
        $issues.Add('The active Java runtime must be Java 25.')
    }
    $javaInfo | Select-Object -First 1 | Write-Output
}
Write-Output ('Gradle wrapper: ' + (Test-Path -LiteralPath (Join-Path $projectRoot 'gradlew.bat')))
Write-Output ('Existing project Gradle cache: ' + (Test-Path -LiteralPath (Join-Path $projectRoot '.gradle-home')))
$service = Get-Service -Name postgresql-x64-18 -ErrorAction SilentlyContinue
if ($null -ne $service) { Write-Output "PostgreSQL service: $($service.Status)" }

if (-not $SkipDatabase) {
    $psql = Join-Path $PostgresHome 'bin\psql.exe'
    if (-not (Test-Path -LiteralPath $psql)) {
        $fromPath = Get-Command psql.exe -ErrorAction SilentlyContinue
        if ($null -ne $fromPath) { $psql = $fromPath.Source }
    }
    if (-not (Test-Path -LiteralPath $psql)) {
        $issues.Add('psql was not found. Pass -PostgresHome with the existing installation directory.')
    } else {
        & $psql --version
        $dbHost = if ($env:DB_HOST) { $env:DB_HOST } else { 'localhost' }
        $dbPort = if ($env:DB_PORT) { $env:DB_PORT } else { '5432' }
        $dbName = if ($env:DB_NAME) { $env:DB_NAME } else { 'memory' }
        $dbUser = if ($env:DB_USER) { $env:DB_USER } else { 'memory' }
        if ($dbHost -in @('localhost', '127.0.0.1', '::1')) {
            $vectorControl = Join-Path $PostgresHome 'share\extension\vector.control'
            if (-not (Test-Path -LiteralPath $vectorControl)) {
                $issues.Add('pgvector files were not found in the selected PostgreSQL installation. Install only the matching extension; do not reinstall PostgreSQL.')
            }
        }
        $savedPassword = $env:PGPASSWORD
        $savedTimeout = $env:PGCONNECT_TIMEOUT
        $savedEncoding = $env:PGCLIENTENCODING
        try {
            if ($env:DB_PASSWORD) { $env:PGPASSWORD = $env:DB_PASSWORD }
            $env:PGCONNECT_TIMEOUT = '5'
            $env:PGCLIENTENCODING = 'UTF8'
            # Never prompt, execute migrations, print credentials, or change the service.
            $query = "SELECT current_setting('server_version'); SELECT 'vector_available=' || default_version FROM pg_available_extensions WHERE name='vector'; SELECT 'vector_enabled=' || extversion FROM pg_extension WHERE extname='vector';"
            $savedPreference = $ErrorActionPreference
            $ErrorActionPreference = 'Continue'
            $result = & $psql -X -w -A -t -v ON_ERROR_STOP=1 -h $dbHost -p $dbPort -U $dbUser -d $dbName -c $query 2>$null
            $queryExit = $LASTEXITCODE
            $ErrorActionPreference = $savedPreference
            if ($queryExit -ne 0) {
                $issues.Add('Database connection failed. Check DB_* in .env or the existing pgpass file. No password was printed.')
            } else {
                Write-Output 'Database connection: OK'
                $result | Write-Output
                if (-not ($result -match '^vector_available=')) {
                    $issues.Add('The connected PostgreSQL server has no available vector extension.')
                } elseif (-not ($result -match '^vector_enabled=')) {
                    $issues.Add('Enable pgvector in this database as an administrator: CREATE EXTENSION IF NOT EXISTS vector;')
                }
            }
        } finally {
            $env:PGPASSWORD = $savedPassword
            $env:PGCONNECT_TIMEOUT = $savedTimeout
            $env:PGCLIENTENCODING = $savedEncoding
        }
    }
}

if ($issues.Count -gt 0) {
    $issues | ForEach-Object { Write-Output "NEEDS ATTENTION: $_" }
    exit 1
}
Write-Output 'Environment checks passed. No software was installed or service settings changed.'
