#Requires -Version 7.0
$ErrorActionPreference = 'Stop'
python (Join-Path $PSScriptRoot 'run.py') @args
if ($LASTEXITCODE -ne 0) { throw 'CI correctness gate failed; inspect safe reports.' }
