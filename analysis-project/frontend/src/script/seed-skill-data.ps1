# Seed multi-dimensional test data for Skill management platform
# Run: powershell -ExecutionPolicy Bypass -File seed-skill-data.ps1
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$ErrorActionPreference = 'Stop'
$BASE = "http://localhost:8081/api"

function Api($method, $path, $userId, $body = $null) {
    $headers = @{ "X-User-Id" = $userId }
    if ($body) {
        $headers["Content-Type"] = "application/json"
        $json = $body | ConvertTo-Json -Depth 5 -Compress
        $resp = Invoke-RestMethod -Uri "$BASE$path" -Method $method -Headers $headers -Body $json
    } else {
        $resp = Invoke-RestMethod -Uri "$BASE$path" -Method $method -Headers $headers
    }
    return $resp
}

Write-Host "=== Seeding Skill test data ===" -ForegroundColor Cyan

# ---- 1. Create Skills by different users ----
Write-Host "`n[1] Creating skills by different users..." -ForegroundColor Yellow

$skillsToCreate = @(
    # user_001 (开发一组+二组)
    @{ owner="user_001"; name="[测试]SQL性能诊断"; desc="自动诊断慢SQL并给出索引优化建议"; cat="数据"; tags="SQL,性能,诊断"; content="诊断慢SQL的prompt..." }
    @{ owner="user_001"; name="[测试]数据质量校验"; desc="校验数据完整性、一致性和准确性"; cat="数据"; tags="数据质量,校验"; content="数据质量校验规则..." }
    # user_002 (开发一组)
    @{ owner="user_002"; name="[测试]自动化测试生成"; desc="根据API文档自动生成测试用例"; cat="研发"; tags="测试,自动化,API"; content="测试用例生成模板..." }
    @{ owner="user_002"; name="[测试]Git提交规范检查"; desc="检查commit message是否符合规范"; cat="研发"; tags="Git,规范"; content="规范检查规则..." }
    # user_003 (统计组)
    @{ owner="user_003"; name="[测试]统计报表生成"; desc="根据数据自动生成统计报表"; cat="数据"; tags="统计,报表"; content="报表生成模板..." }
    @{ owner="user_003"; name="[测试]数据脱敏工具"; desc="对敏感数据进行脱敏处理"; cat="数据"; tags="脱敏,安全"; content="脱敏规则..." }
    # approver_001
    @{ owner="approver_001"; name="[测试]代码规范巡检"; desc="全量代码规范扫描与报告"; cat="研发"; tags="代码规范,扫描"; content="规范巡检prompt..." }
)

$createdSkills = @{}
foreach ($s in $skillsToCreate) {
    try {
        $body = @{ name=$s.name; description=$s.desc; category=$s.cat; tags=$s.tags; content=$s.content }
        $resp = Api "POST" "/skills" $s.owner $body
        $createdSkills[$s.name] = $resp.id
        Write-Host "  Created: $($s.name) (id=$($resp.id), owner=$($s.owner))"
    } catch {
        Write-Host "  SKIP (exists?): $($s.name) - $($_.Exception.Message)" -ForegroundColor DarkYellow
    }
}

# ---- 2. Publish skills to different orgs ----
Write-Host "`n[2] Publishing skills to organizations..." -ForegroundColor Yellow

# publish-targets to see what's available
try {
    $targets = Api "GET" "/skills/publish-targets" "user_001"
    Write-Host "  Available targets for user_001:"
    $targets | ForEach-Object { Write-Host "    $($_.orgType): $($_.orgId) = $($_.displayName)" }
} catch {
    Write-Host "  Could not fetch publish targets: $($_.Exception.Message)" -ForegroundColor Red
}

# Publish: user_001's SQL诊断 -> GROUP:group_001 (开发一组)
$publishes = @(
    @{ skillName="[测试]SQL性能诊断"; owner="user_001"; targetType="GROUP"; targetId="group_001"; targetName="开发一组" }
    @{ skillName="[测试]数据质量校验"; owner="user_001"; targetType="GROUP"; targetId="group_002"; targetName="开发二组" }
    @{ skillName="[测试]自动化测试生成"; owner="user_002"; targetType="GROUP"; targetId="group_001"; targetName="开发一组" }
    @{ skillName="[测试]统计报表生成"; owner="user_003"; targetType="GROUP"; targetId="group_003"; targetName="统计组" }
    @{ skillName="[测试]代码规范巡检"; owner="approver_001"; targetType="DEPARTMENT"; targetId="dept_001"; targetName="研发部" }
)

$publishIds = @{}
foreach ($p in $publishes) {
    $skillId = $createdSkills[$p.skillName]
    if (-not $skillId) { Write-Host "  SKIP publish (skill not found): $($p.skillName)"; continue }
    try {
        $body = @{ targetType=$p.targetType; targetId=$p.targetId; targetName=$p.targetName }
        $resp = Api "POST" "/skills/$skillId/publish" $p.owner $body
        $publishIds[$p.skillName] = $resp.publishId
        Write-Host "  Published: $($p.skillName) -> $($p.targetType):$($p.targetId) (publishId=$($resp.publishId))"
    } catch {
        Write-Host "  SKIP publish (already?): $($p.skillName) - $($_.Exception.Message)" -ForegroundColor DarkYellow
    }
}

# ---- 3. Approve some publishes (as approver_001 / approver_003) ----
Write-Host "`n[3] Approving publishes..." -ForegroundColor Yellow

# group_001 -> approver_001, group_002 -> approver_001, group_003 -> approver_002, dept_001 -> approver_003
$approvals = @(
    @{ skillName="[测试]SQL性能诊断"; approver="approver_001"; comment="内容完善,同意发布到开发一组" }
    @{ skillName="[测试]自动化测试生成"; approver="approver_001"; comment="测试用例覆盖全面,通过" }
    @{ skillName="[测试]统计报表生成"; approver="approver_002"; comment="统计逻辑正确,通过" }
    @{ skillName="[测试]代码规范巡检"; approver="approver_003"; comment="研发部统一使用,通过" }
)

foreach ($a in $approvals) {
    $pid = $publishIds[$a.skillName]
    if (-not $pid) { Write-Host "  SKIP approve (publish not found): $($a.skillName)"; continue }
    try {
        $body = @{ comment=$a.comment }
        Api "POST" "/publish/$pid/approve" $a.approver $body
        Write-Host "  Approved: $($a.skillName) by $($a.approver)"
    } catch {
        Write-Host "  SKIP approve (already?): $($a.skillName) - $($_.Exception.Message)" -ForegroundColor DarkYellow
    }
}

# Leave one publish PENDING: [测试]数据质量校验 -> group_002 (approver_001 not yet approved)
Write-Host "  [LEFT PENDING] [测试]数据质量校验 -> group_002 (waiting approver_001)" -ForegroundColor DarkGray

# ---- 4. Likes (cross-user) ----
Write-Host "`n[4] Liking skills across users..." -ForegroundColor Yellow

$likes = @(
    @{ skillName="[测试]SQL性能诊断"; users=@("user_002","user_003","approver_001","demo-user") }
    @{ skillName="[测试]自动化测试生成"; users=@("user_001","user_003") }
    @{ skillName="[测试]统计报表生成"; users=@("user_001","user_002","approver_002") }
    @{ skillName="[测试]数据脱敏工具"; users=@("user_001","user_002") }
    @{ skillName="[测试]代码规范巡检"; users=@("user_001","user_002","user_003") }
)

foreach ($l in $likes) {
    $skillId = $createdSkills[$l.skillName]
    if (-not $skillId) { Write-Host "  SKIP like (skill not found): $($l.skillName)"; continue }
    foreach ($u in $l.users) {
        try {
            Api "POST" "/skills/$skillId/like" $u
            Write-Host "  Liked: $($l.skillName) by $u"
        } catch {
            Write-Host "  SKIP like (already?): $($l.skillName) by $u - $($_.Exception.Message)" -ForegroundColor DarkYellow
        }
    }
}

# ---- 5. References (cross-user) ----
Write-Host "`n[5] Referencing skills across users..." -ForegroundColor Yellow

$refs = @(
    @{ skillName="[测试]SQL性能诊断"; users=@("user_002","user_003","demo-user") }
    @{ skillName="[测试]自动化测试生成"; users=@("user_001") }
    @{ skillName="[测试]统计报表生成"; users=@("user_001","user_002") }
    @{ skillName="[测试]代码规范巡检"; users=@("user_002","user_003") }
)

foreach ($r in $refs) {
    $skillId = $createdSkills[$r.skillName]
    if (-not $skillId) { Write-Host "  SKIP ref (skill not found): $($r.skillName)"; continue }
    foreach ($u in $r.users) {
        try {
            Api "POST" "/skills/$skillId/reference" $u
            Write-Host "  Referenced: $($r.skillName) by $u"
        } catch {
            Write-Host "  SKIP ref (already?): $($r.skillName) by $u - $($_.Exception.Message)" -ForegroundColor DarkYellow
        }
    }
}

# ---- 6. User disable (test personal disable) ----
Write-Host "`n[6] User disabling skills..." -ForegroundColor Yellow

# user_002 disables [测试]统计报表生成 (which is published to group_003 - user_002 is not in group_003 anyway)
# demo-user disables [测试]SQL性能诊断 (published to group_001, demo-user is in group_001)
$disables = @(
    @{ skillName="[测试]SQL性能诊断"; user="demo-user" }
    @{ skillName="[测试]统计报表生成"; user="user_003" }  # owner disables own published skill
)

foreach ($d in $disables) {
    $skillId = $createdSkills[$d.skillName]
    if (-not $skillId) { Write-Host "  SKIP disable (skill not found): $($d.skillName)"; continue }
    try {
        Api "POST" "/skills/$skillId/disable" $d.user
        Write-Host "  Disabled: $($d.skillName) by $($d.user)"
    } catch {
        Write-Host "  SKIP disable (already?): $($d.skillName) by $($d.user) - $($_.Exception.Message)" -ForegroundColor DarkYellow
    }
}

# ---- 7. Draft approval flow (edit published skill -> draft -> approve) ----
Write-Host "`n[7] Creating draft approval flow..." -ForegroundColor Yellow

# user_001 edits [测试]SQL性能诊断 (already published to group_001) -> should create draft
$draftSkillId = $createdSkills["[测试]SQL性能诊断"]
if ($draftSkillId) {
    try {
        $body = @{ name="[测试]SQL性能诊断"; description="自动诊断慢SQL并给出索引优化建议(v2增强版)"; category="数据"; tags="SQL,性能,诊断,索引"; content="增强版诊断prompt..." }
        Api "PUT" "/skills?id=$draftSkillId" "user_001" $body
        Write-Host "  Direct update succeeded (expected to throw SkillUpdateRequiresDraft)" -ForegroundColor Red
    } catch {
        Write-Host "  Expected error on direct update: $($_.Exception.Message)" -ForegroundColor Green
        Write-Host "  -> This confirms the bug: update throws instead of creating draft (spec §12.3.3)"
    }
}

# ---- 8. Summary ----
Write-Host "`n=== Seed Summary ===" -ForegroundColor Cyan
Write-Host "Created skills:"
$createdSkills.GetEnumerator() | ForEach-Object { Write-Host "  $($_.Key) = id:$($_.Value)" }
Write-Host "Publishes:"
$publishIds.GetEnumerator() | ForEach-Object { Write-Host "  $($_.Key) = publishId:$($_.Value)" }
Write-Host "`nDone! Test users available:" -ForegroundColor Green
Write-Host "  user_001 (张三)     - 开发一组+二组, owns SQL诊断/数据质量"
Write-Host "  user_002 (李四)     - 开发一组, owns 测试生成/Git规范"
Write-Host "  user_003 (王五)     - 统计组, owns 统计报表/数据脱敏"
Write-Host "  approver_001        - 一组/二组审批人, owns 代码规范巡检"
Write-Host "  approver_002        - 统计组审批人"
Write-Host "  approver_003        - 部门/产品线/杭研审批人"
Write-Host "  demo-user           - 开发一组(访客)"
