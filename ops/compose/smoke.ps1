#Requires -Version 7.0
$ErrorActionPreference = 'Stop'
python (Join-Path $PSScriptRoot 'smoke.py')
if ($LASTEXITCODE -ne 0) { throw 'Full-stack Compose smoke failed.' }
