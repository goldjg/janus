#requires -Version 7.0
[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$scriptPath = Join-Path $PSScriptRoot 'bootstrap.ps1'
$tokens = $null
$parseErrors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile(
    $scriptPath,
    [ref]$tokens,
    [ref]$parseErrors
)
if ($parseErrors.Count -gt 0) {
    throw "bootstrap.ps1 has parser errors: $($parseErrors -join '; ')"
}

$source = Get-Content -LiteralPath $scriptPath -Raw
$tokenText = @($tokens | ForEach-Object Text)
$assertions = [ordered]@{
    supportsShouldProcess = $source -match 'SupportsShouldProcess'
    graphWritesAreGuarded = $source -match 'Invoke-GraphMutation' -and $source -match '\$PSCmdlet\.ShouldProcess'
    azureWritesAreGuarded = $source -match 'Invoke-AzMutation' -and $source -match '\$PSCmdlet\.ShouldProcess'
    noRuntimeApplicationReadWriteAll = $tokenText -notcontains "'Application.ReadWrite.All'"
    usesOwnedByPermission = $source -match 'Application\.ReadWrite\.OwnedBy'
    usesEnvironmentFederation = $source -match 'environment:\$GitHubEnvironment'
    emitsStructuredOutput = $source -match 'schemaVersion = 1'
}

$failed = @($assertions.GetEnumerator() | Where-Object { -not $_.Value })
if ($failed.Count -gt 0) {
    throw "Bootstrap static assertions failed: $($failed.Name -join ', ')"
}

[pscustomobject]$assertions
