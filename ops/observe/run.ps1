param([int]$Samples = 240, [double]$Rate = 8)
$ErrorActionPreference = 'Stop'
python "$PSScriptRoot/observe.py" --samples $Samples --rate $Rate
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
