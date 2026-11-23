param(
    [Parameter(Mandatory=$true)][string]$ClasspathFile,
    [Parameter(Mandatory=$true)][string]$OutputDirectory,
    [ValidateRange(1,350000)][long]$TokenBudget = 350000,
    [ValidateRange(1,2)][int]$Workers = 2,
    [string]$Cases = '',
    [string]$Model = '',
    [switch]$StopOnProviderError,
    [switch]$Run
)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$runDirectory = [IO.Path]::GetFullPath($OutputDirectory)
if (-not $runDirectory.StartsWith($projectRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'OutputDirectory must be inside the project.'
}
if ($Run -and (Test-Path -LiteralPath (Join-Path $runDirectory 'results.json'))) {
    throw 'Existing evidence must not be overwritten. Use a new output directory.'
}
$dependencyClasspath = [IO.File]::ReadAllText((Resolve-Path -LiteralPath $ClasspathFile)).Trim()
$classesDirectory = Join-Path $runDirectory 'classes'
New-Item -ItemType Directory -Force -Path $classesDirectory | Out-Null
$sourceFiles = @(Get-ChildItem (Join-Path $projectRoot 'backend/src/main/java') -Recurse -Filter '*.java')
$sourceFiles += Get-Item (Join-Path $PSScriptRoot 'FullMealDatasetRegression.java')
$sourceArguments = @($sourceFiles | ForEach-Object { '"' + $_.FullName.Replace('\','/') + '"' })
$argumentsFile = Join-Path $runDirectory 'sources.args'
[IO.File]::WriteAllLines($argumentsFile, $sourceArguments, [Text.UTF8Encoding]::new($false))
& javac '-J-Duser.language=en' -encoding UTF-8 -parameters -cp $dependencyClasspath -d $classesDirectory ('@' + $argumentsFile)
if ($LASTEXITCODE -ne 0) { throw 'Compilation failed; no provider calls were made.' }
$manifestFiles = $sourceFiles + @(Get-Item (Join-Path $projectRoot 'backend/src/main/resources/knowledge/diet_guides.md'),(Join-Path $projectRoot 'tools/datasets/synthetic-meals-100/dataset.json'))
@($manifestFiles | ForEach-Object { [pscustomobject]@{Path=$_.FullName;SHA256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash} }) |
    ConvertTo-Json -Depth 4 | Set-Content -Encoding utf8 (Join-Path $runDirectory 'source-manifest.json')
$javaArguments = @('-Xmx768m','-cp',($classesDirectory + ';' + $dependencyClasspath),
    'com.example.mealcheck.service.FullMealDatasetRegression','--root',$projectRoot,'--out',$runDirectory,
    '--budget',$TokenBudget.ToString(),'--workers',$Workers.ToString())
if ($Run) { $javaArguments += '--run' }
if ($Cases) { $javaArguments += @('--cases',$Cases) }
if ($Model) { $javaArguments += @('--model',$Model) }
if ($StopOnProviderError) { $javaArguments += '--stop-on-provider-error' }
& java @javaArguments
if ($LASTEXITCODE -ne 0) { throw 'Regression process exited unsuccessfully; inspect saved evidence before rerunning.' }
