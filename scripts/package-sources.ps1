# Package hand-written sources. Use -OutputPath to select the archive path; the default is under
# build.
[CmdletBinding()]
param(
    [string]$OutputPath = 'build/electromagnetism-sources.zip'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = Split-Path -Parent $PSScriptRoot
$sourceExtensions = @(
    '.java', '.rs', '.h', '.c', '.cpp', '.hpp', '.kt', '.kts',
    '.groovy', '.gradle', '.js', '.ts', '.py', '.ps1', '.sh', '.bat', '.cmd'
)
$excludedDirectories = @(
    '.git', '.gradle', '.idea', '.vscode', '.codex', '.agents', '.aws',
    'build', 'target', 'out', 'run', 'logs', 'node_modules',
    '__pycache__', '.venv', 'venv', 'generated'
)

function Get-SourceFiles {
    param([string]$Directory)

    foreach ($item in Get-ChildItem -LiteralPath $Directory -Force) {
        # Skip directory links to avoid cycles and traversal outside the repository.
        if ($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) {
            continue
        }
        if ($item.PSIsContainer) {
            if ($item.Name -notin $excludedDirectories) {
                Get-SourceFiles -Directory $item.FullName
            }
        } elseif ($item.Extension -in $sourceExtensions -or $item.Name -eq 'gradlew') {
            $item
        }
    }
}

if (-not [System.IO.Path]::IsPathRooted($OutputPath)) {
    $OutputPath = Join-Path $repositoryRoot $OutputPath
}
$OutputPath = [System.IO.Path]::GetFullPath($OutputPath)
if ([System.IO.Path]::GetExtension($OutputPath) -ne '.zip') {
    throw 'OutputPath must end in .zip.'
}
$files = @(Get-SourceFiles -Directory $repositoryRoot | Sort-Object FullName)
if ($files.Count -eq 0) {
    throw 'No source files found.'
}

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
[System.IO.Directory]::CreateDirectory([System.IO.Path]::GetDirectoryName($OutputPath)) | Out-Null
# Open with CreateNew to avoid overwriting an existing archive.
$stream = [System.IO.File]::Open($OutputPath, [System.IO.FileMode]::CreateNew)
try {
    $archive = [System.IO.Compression.ZipArchive]::new($stream, [System.IO.Compression.ZipArchiveMode]::Create)
    try {
        foreach ($file in $files) {
            $entryName = $file.FullName.Substring($repositoryRoot.Length + 1).Replace('\', '/')
            [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
                $archive, $file.FullName, $entryName, [System.IO.Compression.CompressionLevel]::Optimal
            ) | Out-Null
        }
    } finally {
        $archive.Dispose()
    }
} finally {
    $stream.Dispose()
}

Write-Host "Packaged $($files.Count) source files: $OutputPath"
