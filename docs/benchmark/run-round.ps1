param(
    [int]$concurrency = 20,
    [string]$label = 'round',
    [int]$duration = 45
)

# Collect MySQL select count and Redis command count before/after the benchmark.
$script:mysqlExe = 'E:\mysql-8.0.34-winx64\bin\mysql.exe'
$script:redisExe = 'E:\Redis\redis-cli.exe'
$benchScript = Join-Path $PSScriptRoot 'bench.js'

function Get-DbSelect {
    $row = & $script:mysqlExe --host=127.0.0.1 --user=root --password=123456 --skip-column-names yu_picture --execute "show global status like 'Com_select'" 2>$null
    [int](($row -split "`t")[-1])
}

function Get-RedisCommands {
    $line = & $script:redisExe info stats 2>$null | Select-String 'total_commands_processed'
    [int](($line -split ':')[-1].Trim())
}

$dbBefore = Get-DbSelect
$redisBefore = Get-RedisCommands

$benchOut = node $benchScript $concurrency $duration $label

$dbAfter = Get-DbSelect
$redisAfter = Get-RedisCommands

$bench = $benchOut | Out-String | ConvertFrom-Json
$bench | Add-Member -NotePropertyName dbSelectDelta -NotePropertyValue ($dbAfter - $dbBefore) -Force
$bench | Add-Member -NotePropertyName redisCmdDelta -NotePropertyValue ($redisAfter - $redisBefore) -Force
$bench | ConvertTo-Json -Depth 6
