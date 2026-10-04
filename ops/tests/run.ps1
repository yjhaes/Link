param([ValidateSet('unit','integration','all')][string]$Suite = 'unit')
$ErrorActionPreference = 'Stop'
python (Join-Path $PSScriptRoot 'run.py') $Suite
exit $LASTEXITCODE