$ErrorActionPreference = 'Stop'

function Assert-Contains([string]$Path, [string]$Text, [string]$Message) {
  $content = Get-Content -Raw $Path
  if (-not $content.Contains($Text)) { throw $Message }
}

$root = Split-Path -Parent $PSScriptRoot
Assert-Contains "$root\components\AppShell.vue" 'to="/sessions"' 'AppShell must expose a top-level sessions tab.'
Assert-Contains "$root\main.ts" "path: 'sessions'" 'Router must define the sessions list route.'
Assert-Contains "$root\main.ts" "path: 'sessions/:id'" 'Router must define the sessions detail route.'
Assert-Contains "$root\main.ts" 'redirect: to => ({ path: `/sessions/' 'Legacy chat detail route must redirect to sessions detail.'
Assert-Contains "$root\main.ts" 'query: to.query' 'Legacy chat detail redirect must preserve query filters.'
Assert-Contains "$root\pages\ChatWorkspacePage.vue" '<ChatPage />' 'Chat workspace must render the AI chat page.'
if ((Get-Content -Raw "$root\pages\ChatWorkspacePage.vue").Contains('SessionHistoryPage')) { throw 'Chat workspace must not render session history.' }
Assert-Contains "$root\pages\SessionHistoryPage.vue" 'path: `/sessions/' 'Session list must navigate to sessions detail.'
Assert-Contains "$root\pages\SessionDetailPage.vue" "path: '/sessions'" 'Session detail must return to sessions list.'
Write-Output 'Session history navigation assertions passed.'
