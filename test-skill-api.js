// Comprehensive API test for Skill management platform
// Tests all modules against spec and records bugs
const BASE = "http://localhost:8081/api";

const results = [];
function log(category, test, status, detail = "") {
  const icon = status === "PASS" ? "[OK]" : status === "FAIL" ? "[BUG]" : status === "WARN" ? "[WARN]" : "[INFO]";
  results.push({ category, test, status, detail });
  console.log(`${icon} ${category} / ${test}${detail ? " -> " + detail : ""}`);
}

async function api(method, path, userId, body) {
  const headers = { "X-User-Id": userId };
  const opts = { method, headers };
  if (body) {
    headers["Content-Type"] = "application/json";
    opts.body = JSON.stringify(body);
  }
  const res = await fetch(`${BASE}${path}`, opts);
  const ct = res.headers.get("content-type") || "";
  const text = await res.text().catch(() => "");
  let json = null;
  try { if (text) json = JSON.parse(text); } catch {}
  return { status: res.status, ok: res.ok, json, text };
}

async function main() {
  console.log("=== Skill Platform API Test Suite ===\n");

  // ===== §4.1 Skill CRUD =====
  console.log("--- §4.1 Skill CRUD ---");

  // List
  let r = await api("GET", "/skills?limit=5", "demo-user");
  log("CRUD", "list skills", r.ok && Array.isArray(r.json) ? "PASS" : "FAIL", `got ${r.json?.length} items`);

  // Get detail
  r = await api("GET", "/skills/get?id=18", "demo-user");
  log("CRUD", "get skill detail", r.ok && r.json?.id === 18 ? "PASS" : "FAIL", `id=${r.json?.id}`);

  // Create with name conflict
  r = await api("POST", "/skills", "user_002", { name: "[测试]SQL性能诊断", description: "dup", content: "x", category: "x", tags: "x" });
  log("CRUD", "name conflict on create", r.status === 500 ? "WARN" : "PASS", `HTTP ${r.status} (spec says 409, but handler maps IllegalStateException)`);

  // Create non-conflict then delete
  r = await api("POST", "/skills", "user_002", { name: "[TempDelete]Test", description: "temp", content: "temp", category: "test", tags: "test" });
  if (r.ok) {
    const tempId = r.json.id;
    log("CRUD", "create temp skill", "PASS", `id=${tempId}`);
    // Delete as non-owner
    r = await api("DELETE", `/skills?id=${tempId}`, "user_003");
    log("CRUD", "delete by non-owner", r.status === 500 ? "WARN" : "PASS", `HTTP ${r.status} (spec says 403, IllegalStateException mapped)`);
    // Delete as owner
    r = await api("DELETE", `/skills?id=${tempId}`, "user_002");
    log("CRUD", "delete by owner", r.ok ? "PASS" : "FAIL", `HTTP ${r.status}`);
  }

  // Update unpublished skill (should work directly, but should save version history - check)
  r = await api("GET", "/skills/get?id=21", "user_002"); // [测试]Git提交规范检查 (unpublished)
  if (r.ok) {
    const beforeUpdate = r.json;
    r = await api("PUT", "/skills?id=21", "user_002", { name: "[测试]Git提交规范检查", description: "检查commit message是否符合规范(更新)", category: "研发", tags: "Git,规范", content: "更新后规则" });
    log("CRUD", "update unpublished skill (owner)", r.ok ? "PASS" : "FAIL", `HTTP ${r.status}`);
    // Check version history was saved (spec §12.3.3 says it should)
    r = await api("GET", "/skills/21/versions", "demo-user");
    const versions = r.json || [];
    log("CRUD", "version history on direct update (§12.3.3)", versions.length > 0 ? "PASS" : "FAIL", `versions count=${versions.length} (should be >0 after update)`);

    // Check operation history (CREATE/UPDATE should be recorded per §4.7)
    // No direct API to get operation history by skill... skip
  }

  // ===== §6 Availability Calculation =====
  console.log("\n--- §6 Availability Calculation ---");

  // user_001 is in group_001 + group_002. [测试]SQL性能诊断 published to group_001 (APPROVED).
  // So user_001 should see it as available=true
  r = await api("GET", "/skills?limit=100", "user_001");
  const sqlSkill = (r.json || []).find(s => s.id === 18);
  log("AVAIL", "user_001 sees SQL诊断 as available (org match group_001)", sqlSkill?.available === true ? "PASS" : "FAIL", `available=${sqlSkill?.available} (should be true)`);

  // user_003 is in group_003. [测试]SQL性能诊断 published to group_001 only.
  // So user_003 should see it as available=false
  r = await api("GET", "/skills?limit=100", "user_003");
  const sqlSkillFor003 = (r.json || []).find(s => s.id === 18);
  log("AVAIL", "user_003 sees SQL诊断 as NOT available (no org match)", sqlSkillFor003?.available === false ? "PASS" : "FAIL", `available=${sqlSkillFor003?.available} (should be false)`);

  // demo-user disabled [测试]SQL性能诊断. demo-user is in group_001 (published APPROVED).
  // So demo-user should see available=false (disabled overrides org match)
  r = await api("GET", "/skills?limit=100", "demo-user");
  const sqlSkillForDemo = (r.json || []).find(s => s.id === 18);
  log("AVAIL", "demo-user sees SQL诊断 as NOT available (user-disabled)", sqlSkillForDemo?.available === false ? "PASS" : "FAIL", `available=${sqlSkillForDemo?.available} (should be false due to disable)`);

  // user_003 disabled [测试]统计报表生成 (published to group_003, user_003 is in group_003)
  // user_003 should see available=false
  const statSkillFor003 = (r.json || []).find(s => s.id === 22);
  log("AVAIL", "user_003 sees 统计报表 as NOT available (user-disabled)", statSkillFor003?.available === false ? "PASS" : "FAIL", `available=${statSkillFor003?.available} (should be false due to disable)`);

  // ===== §7.1 List Views =====
  console.log("\n--- §7.1 List Views ---");

  // View: created
  r = await api("GET", "/skills?view=created&limit=100", "user_001");
  const createdIds = (r.json || []).map(s => s.id);
  log("VIEW", "created view (user_001)", createdIds.includes(18) && createdIds.includes(19) ? "PASS" : "FAIL", `ids=${createdIds.join(",")}`);

  // View: liked
  r = await api("GET", "/skills?view=liked&limit=100", "user_002");
  const likedIds = (r.json || []).map(s => s.id);
  log("VIEW", "liked view (user_002)", likedIds.includes(18) ? "PASS" : "FAIL", `liked ids=${likedIds.join(",")}`);

  // View: used (referenced)
  r = await api("GET", "/skills?view=used&limit=100", "user_002");
  const usedIds = (r.json || []).map(s => s.id);
  log("VIEW", "used view (user_002)", usedIds.includes(18) ? "PASS" : "FAIL", `used ids=${usedIds.join(",")}`);

  // View: popular
  r = await api("GET", "/skills?view=popular&limit=100", "demo-user");
  const popularItems = r.json || [];
  const hasRank = popularItems.some(s => s.rank != null);
  log("VIEW", "popular view has rank", hasRank ? "PASS" : "FAIL", `items=${popularItems.length}, hasRank=${hasRank}`);

  // View: popular default limit (spec says 50)
  r = await api("GET", "/skills?view=popular", "demo-user");
  log("VIEW", "popular default limit=50 (§7.1)", (r.json || []).length <= 50 ? "WARN" : "FAIL", `returned ${(r.json || []).length} items (spec says default 50, impl uses 20)`);

  // Sort: likes (default)
  r = await api("GET", "/skills?sort=likes&limit=10", "demo-user");
  const likesSorted = r.json || [];
  const isLikesDesc = likesSorted.every((s, i) => i === 0 || s.likeCount <= likesSorted[i - 1].likeCount);
  log("SORT", "sort by likes DESC (default)", isLikesDesc ? "PASS" : "FAIL", `counts: ${likesSorted.map(s => s.likeCount).join(",")}`);

  // Sort: name
  r = await api("GET", "/skills?sort=name&limit=10", "demo-user");
  const nameSorted = r.json || [];
  const isNameAsc = nameSorted.every((s, i) => i === 0 || s.name >= nameSorted[i - 1].name);
  log("SORT", "sort by name ASC", isNameAsc ? "PASS" : "FAIL");

  // Filter: category
  r = await api("GET", "/skills?category=数据&limit=100", "demo-user");
  const allData = (r.json || []).every(s => s.category === "数据");
  log("FILTER", "filter by category=数据", allData ? "PASS" : "FAIL", `items=${(r.json || []).length}`);

  // Filter: tag (backend supports, frontend missing UI)
  r = await api("GET", "/skills?tag=SQL&limit=100", "demo-user");
  const allSqlTag = (r.json || []).every(s => (s.tags || "").includes("SQL"));
  log("FILTER", "filter by tag=SQL (backend)", allSqlTag ? "PASS" : "FAIL", `items=${(r.json || []).length}`);

  // Filter: availability=available
  r = await api("GET", "/skills?availability=available&limit=100", "user_001");
  const allAvailable = (r.json || []).every(s => s.available === true);
  log("FILTER", "filter by availability=available (user_001)", allAvailable ? "PASS" : "FAIL", `items=${(r.json || []).length}, allAvailable=${allAvailable}`);

  // ===== §4.2 Publish =====
  console.log("\n--- §4.2 Publish ---");

  // Pending list for approver
  r = await api("GET", "/publish/pending", "approver_001");
  const pendingFor001 = r.json || [];
  log("PUBLISH", "pending list for approver_001", r.ok ? "PASS" : "FAIL", `${pendingFor001.length} pending items`);
  const hasDataQuality = pendingFor001.some(p => p.skillId === 19);
  log("PUBLISH", "pending includes [测试]数据质量校验", hasDataQuality ? "PASS" : "FAIL", `found=${hasDataQuality}`);

  // Approvals history
  r = await api("GET", "/publish/2/approvals", "demo-user");
  log("PUBLISH", "approval history for publishId=2", r.ok && Array.isArray(r.json) ? "PASS" : "FAIL", `${(r.json || []).length} records`);

  // Publish targets
  r = await api("GET", "/skills/publish-targets", "user_001");
  const targets = r.json || [];
  const firstTarget = targets[0] || {};
  const hasValidFields = firstTarget.orgType && firstTarget.orgId;
  log("PUBLISH", "publish-targets returns valid fields", hasValidFields ? "PASS" : "FAIL", `firstTarget=${JSON.stringify(firstTarget)} (orgType/orgId should not be undefined)`);

  // ===== §4.3 Draft Approval =====
  console.log("\n--- §4.3 Draft Approval ---");

  // Try to get draft for skill 18 (should have none, since update threw instead of creating draft)
  r = await api("GET", "/skills/18/draft", "user_001");
  log("DRAFT", "get draft for skill 18 (should be null - update threw)", (!r.json || r.status === 204) ? "WARN" : "INFO", `status=${r.status}, json=${JSON.stringify(r.json)?.substring(0, 100)}`);

  // Pending drafts for approver
  r = await api("GET", "/draft/pending", "approver_001");
  log("DRAFT", "pending drafts for approver_001", r.ok ? "PASS" : "FAIL", `${(r.json || []).length} drafts (expected 0 because draft creation is broken)`);

  // ===== §4.4 User Disable =====
  console.log("\n--- §4.4 User Disable ---");

  // Check disable status
  r = await api("GET", "/skills/18/disable", "demo-user");
  log("DISABLE", "disable status for demo-user on skill 18", r.ok ? "PASS" : "FAIL", `disabled=${r.json?.disabled ?? r.json?.isDisabled}`);

  // Enable (cancel disable)
  r = await api("DELETE", "/skills/18/disable", "demo-user");
  log("DISABLE", "cancel disable (enable)", r.ok ? "PASS" : "FAIL", `HTTP ${r.status}`);

  // Verify enabled
  r = await api("GET", "/skills/18/disable", "demo-user");
  log("DISABLE", "verify enabled after cancel", !r.json?.disabled && !r.json?.isDisabled ? "PASS" : "FAIL");

  // Re-disable for testing
  await api("POST", "/skills/18/disable", "demo-user");

  // ===== §4.5 Reference =====
  console.log("\n--- §4.5 Reference ---");

  // My references
  r = await api("GET", "/skills/my-references", "user_002");
  const myRefs = r.json || [];
  log("REF", "my-references (user_002)", r.ok && Array.isArray(r.json) ? "PASS" : "FAIL", `${myRefs.length} refs`);

  // Referencers (owner only)
  r = await api("GET", "/skills/18/referencers", "user_001");
  log("REF", "referencers by owner (user_001 on skill 18)", r.ok ? "PASS" : "FAIL", `users=${JSON.stringify(r.json)?.substring(0, 80)}`);

  // Referencers by non-owner (should be denied?)
  r = await api("GET", "/skills/18/referencers", "user_003");
  log("REF", "referencers by non-owner (spec says owner only)", r.ok ? "WARN" : "PASS", `HTTP ${r.status} (spec says owner-only, but ${r.ok ? "allowed" : "denied"})`);

  // ===== §4.8 Like =====
  console.log("\n--- §4.8 Like ---");

  // Like status
  r = await api("GET", "/skills/18/like", "user_001");
  log("LIKE", "like status (user_001 on skill 18)", r.ok ? "PASS" : "FAIL", `liked=${r.json?.liked}, count=${r.json?.likeCount}`);

  // Like (idempotent - user_001 already liked)
  r = await api("POST", "/skills/18/like", "user_001");
  log("LIKE", "like idempotent (already liked)", r.ok && r.json?.liked === true ? "PASS" : "FAIL", `liked=${r.json?.liked}, count=${r.json?.likeCount} (should not increment)`);

  // Unlike then re-like
  r = await api("DELETE", "/skills/18/like", "user_001");
  log("LIKE", "unlike", r.ok ? "PASS" : "FAIL", `liked=${r.json?.liked}, count=${r.json?.likeCount}`);

  r = await api("POST", "/skills/18/like", "user_001");
  log("LIKE", "re-like after unlike", r.ok && r.json?.liked === true ? "PASS" : "FAIL", `liked=${r.json?.liked}, count=${r.json?.likeCount}`);

  // Like a non-existent skill (should 404)
  r = await api("POST", "/skills/99999/like", "demo-user");
  log("LIKE", "like non-existent skill (404)", r.status === 404 || r.status === 500 ? "WARN" : "FAIL", `HTTP ${r.status}`);

  // ===== §4.6 Version History =====
  console.log("\n--- §4.6 Version History ---");

  r = await api("GET", "/skills/18/versions", "demo-user");
  const versions18 = r.json || [];
  log("VERSION", "version history for skill 18", r.ok ? "PASS" : "FAIL", `${versions18.length} versions (draft approve should create versions)`);

  // ===== Summary =====
  console.log("\n=== Test Summary ===");
  const pass = results.filter(r => r.status === "PASS").length;
  const fail = results.filter(r => r.status === "FAIL").length;
  const warn = results.filter(r => r.status === "WARN").length;
  console.log(`PASS: ${pass}, FAIL: ${fail}, WARN: ${warn}`);
  console.log("\n--- BUGS / GAPS ---");
  results.filter(r => r.status === "FAIL" || r.status === "WARN").forEach(r => {
    console.log(`  [${r.status}] ${r.category}/${r.test}: ${r.detail}`);
  });
}

main().catch(e => { console.error("FATAL:", e); process.exit(1); });
