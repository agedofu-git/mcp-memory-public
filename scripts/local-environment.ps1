function Import-LocalEnvironment {
    param([Parameter(Mandatory = $true)][string]$ProjectRoot)
    $envFile = Join-Path $ProjectRoot '.env'
    if (Test-Path -LiteralPath $envFile) {
        foreach ($line in Get-Content -LiteralPath $envFile -Encoding UTF8) {
            if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)=(.*)$') {
                $name = $matches[1]
                $value = $matches[2].Trim()
                if ($value.Length -ge 2 -and (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'")))) {
                    $value = $value.Substring(1, $value.Length - 2)
                }
                # Read literal values only; never execute expressions from .env.
                [Environment]::SetEnvironmentVariable($name, $value, 'Process')
            }
        }
    }
}
