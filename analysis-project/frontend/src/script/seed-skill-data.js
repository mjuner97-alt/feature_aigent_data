// Seed multi-dimensional test data for Skill management platform
// Run: node seed-skill-data.js
const BASE = "http://localhost:8081/api";

async function api(method, path, userId, body) {
  const headers = { "X-User-Id": userId };
  const opts = { method, headers };
  if (body) {
    headers["Content-Type"] = "application/json";
    opts.body = JSON.stringify(body);
  }
  const res = await fetch(`${BASE}${path}`, opts);
  if (!res.ok) {
    const text = await res.text().catch(() => "");
    throw new Error(`HTTP ${res.status}: ${text}`);
  }
  const ct = res.headers.get("content-type") || "";
  if (ct.includes("application/json")) return res.json();
  return null;
}

async function main() {
  console.log("=== Seeding Skill test data ===\n");

  // 1. Create Skills by different users
  console.log("[1] Creating skills by different users...");
  const skillsToCreate = [
    { owner: "user_001", name: "[测试]SQL性能诊断", desc: "自动诊断慢SQL并给出索引优化建议", cat: "数据", tags: "SQL,性能,诊断", content: "诊断慢SQL的prompt..." },
    { owner: "user_001", name: "[测试]数据质量校验", desc: "校验数据完整性、一致性和准确性", cat: "数据", tags: "数据质量,校验", content: "数据质量校验规则..." },
    { owner: "user_002", name: "[测试]自动化测试生成", desc: "根据API文档自动生成测试用例", cat: "研发", tags: "测试,自动化,API", content: "测试用例生成模板..." },
    { owner: "user_002", name: "[测试]Git提交规范检查", desc: "检查commit message是否符合规范", cat: "研发", tags: "Git,规范", content: "规范检查规则..." },
    { owner: "user_003", name: "[测试]统计报表生成", desc: "根据数据自动生成统计报表", cat: "数据", tags: "统计,报表", content: "报表生成模板..." },
    { owner: "user_003", name: "[测试]数据脱敏工具", desc: "对敏感数据进行脱敏处理", cat: "数据", tags: "脱敏,安全", content: "脱敏规则..." },
    { owner: "approver_001", name: "[测试]代码规范巡检", desc: "全量代码规范扫描与报告", cat: "研发", tags: "代码规范,扫描", content: "规范巡检prompt..." },
  ];

  const created = {};
  for (const s of skillsToCreate) {
    try {
      const body = { name: s.name, description: s.desc, category: s.cat, tags: s.tags, content: s.content };
      const resp = await api("POST", "/skills", s.owner, body);
      created[s.name] = resp.id;
      console.log(`  Created: ${s.name} (id=${resp.id}, owner=${s.owner})`);
    } catch (e) {
      console.log(`  SKIP (exists?): ${s.name} - ${e.message}`);
    }
  }

  // 2. Publish skills to different orgs
  console.log("\n[2] Publishing skills to organizations...");
  try {
    const targets = await api("GET", "/skills/publish-targets", "user_001");
    console.log("  Available targets for user_001:");
    for (const t of targets) console.log(`    ${t.orgType}: ${t.orgId} = ${t.displayName}`);
  } catch (e) {
    console.log(`  Could not fetch publish targets: ${e.message}`);
  }

  const publishes = [
    { skillName: "[测试]SQL性能诊断", owner: "user_001", targetType: "GROUP", targetId: "group_001", targetName: "开发一组" },
    { skillName: "[测试]数据质量校验", owner: "user_001", targetType: "GROUP", targetId: "group_002", targetName: "开发二组" },
    { skillName: "[测试]自动化测试生成", owner: "user_002", targetType: "GROUP", targetId: "group_001", targetName: "开发一组" },
    { skillName: "[测试]统计报表生成", owner: "user_003", targetType: "GROUP", targetId: "group_003", targetName: "统计组" },
    { skillName: "[测试]代码规范巡检", owner: "approver_001", targetType: "DEPARTMENT", targetId: "dept_001", targetName: "研发部" },
  ];

  const publishIds = {};
  for (const p of publishes) {
    const skillId = created[p.skillName];
    if (!skillId) { console.log(`  SKIP publish (skill not found): ${p.skillName}`); continue; }
    try {
      const body = { targetType: p.targetType, targetId: p.targetId, targetName: p.targetName };
      const resp = await api("POST", `/skills/${skillId}/publish`, p.owner, body);
      publishIds[p.skillName] = resp.publishId;
      console.log(`  Published: ${p.skillName} -> ${p.targetType}:${p.targetId} (publishId=${resp.publishId})`);
    } catch (e) {
      console.log(`  SKIP publish (already?): ${p.skillName} - ${e.message}`);
    }
  }

  // 3. Approve some publishes
  console.log("\n[3] Approving publishes...");
  const approvals = [
    { skillName: "[测试]SQL性能诊断", approver: "approver_001", comment: "内容完善,同意发布到开发一组" },
    { skillName: "[测试]自动化测试生成", approver: "approver_001", comment: "测试用例覆盖全面,通过" },
    { skillName: "[测试]统计报表生成", approver: "approver_002", comment: "统计逻辑正确,通过" },
    { skillName: "[测试]代码规范巡检", approver: "approver_003", comment: "研发部统一使用,通过" },
  ];

  for (const a of approvals) {
    const pid = publishIds[a.skillName];
    if (!pid) { console.log(`  SKIP approve (publish not found): ${a.skillName}`); continue; }
    try {
      await api("POST", `/publish/${pid}/approve`, a.approver, { comment: a.comment });
      console.log(`  Approved: ${a.skillName} by ${a.approver}`);
    } catch (e) {
      console.log(`  SKIP approve (already?): ${a.skillName} - ${e.message}`);
    }
  }
  console.log("  [LEFT PENDING] [测试]数据质量校验 -> group_002 (waiting approver_001)");

  // 4. Likes (cross-user)
  console.log("\n[4] Liking skills across users...");
  const likes = [
    { skillName: "[测试]SQL性能诊断", users: ["user_002", "user_003", "approver_001", "demo-user"] },
    { skillName: "[测试]自动化测试生成", users: ["user_001", "user_003"] },
    { skillName: "[测试]统计报表生成", users: ["user_001", "user_002", "approver_002"] },
    { skillName: "[测试]数据脱敏工具", users: ["user_001", "user_002"] },
    { skillName: "[测试]代码规范巡检", users: ["user_001", "user_002", "user_003"] },
  ];

  for (const l of likes) {
    const skillId = created[l.skillName];
    if (!skillId) { console.log(`  SKIP like (skill not found): ${l.skillName}`); continue; }
    for (const u of l.users) {
      try {
        await api("POST", `/skills/${skillId}/like`, u);
        console.log(`  Liked: ${l.skillName} by ${u}`);
      } catch (e) {
        console.log(`  SKIP like (already?): ${l.skillName} by ${u} - ${e.message}`);
      }
    }
  }

  // 5. References (cross-user)
  console.log("\n[5] Referencing skills across users...");
  const refs = [
    { skillName: "[测试]SQL性能诊断", users: ["user_002", "user_003", "demo-user"] },
    { skillName: "[测试]自动化测试生成", users: ["user_001"] },
    { skillName: "[测试]统计报表生成", users: ["user_001", "user_002"] },
    { skillName: "[测试]代码规范巡检", users: ["user_002", "user_003"] },
  ];

  for (const r of refs) {
    const skillId = created[r.skillName];
    if (!skillId) { console.log(`  SKIP ref (skill not found): ${r.skillName}`); continue; }
    for (const u of r.users) {
      try {
        await api("POST", `/skills/${skillId}/reference`, u);
        console.log(`  Referenced: ${r.skillName} by ${u}`);
      } catch (e) {
        console.log(`  SKIP ref (already?): ${r.skillName} by ${u} - ${e.message}`);
      }
    }
  }

  // 6. User disable (test personal disable)
  console.log("\n[6] User disabling skills...");
  const disables = [
    { skillName: "[测试]SQL性能诊断", user: "demo-user" },
    { skillName: "[测试]统计报表生成", user: "user_003" },
  ];

  for (const d of disables) {
    const skillId = created[d.skillName];
    if (!skillId) { console.log(`  SKIP disable (skill not found): ${d.skillName}`); continue; }
    try {
      await api("POST", `/skills/${skillId}/disable`, d.user);
      console.log(`  Disabled: ${d.skillName} by ${d.user}`);
    } catch (e) {
      console.log(`  SKIP disable (already?): ${d.skillName} by ${d.user} - ${e.message}`);
    }
  }

  // 7. Draft approval flow (edit published skill -> draft -> approve)
  console.log("\n[7] Creating draft approval flow...");
  const draftSkillId = created["[测试]SQL性能诊断"];
  if (draftSkillId) {
    try {
      const body = { name: "[测试]SQL性能诊断", description: "自动诊断慢SQL并给出索引优化建议(v2增强版)", category: "数据", tags: "SQL,性能,诊断,索引", content: "增强版诊断prompt..." };
      await api("PUT", `/skills?id=${draftSkillId}`, "user_001", body);
      console.log("  Direct update succeeded (expected to throw SkillUpdateRequiresDraft) - BUG!");
    } catch (e) {
      console.log(`  Expected error on direct update: ${e.message}`);
      console.log("  -> This confirms the bug: update throws instead of creating draft (spec 12.3.3)");
    }
  }

  // 8. Summary
  console.log("\n=== Seed Summary ===");
  console.log("Created skills:");
  for (const [k, v] of Object.entries(created)) console.log(`  ${k} = id:${v}`);
  console.log("Publishes:");
  for (const [k, v] of Object.entries(publishIds)) console.log(`  ${k} = publishId:${v}`);
  console.log("\nDone! Test users available:");
  console.log("  user_001 (张三)     - 开发一组+二组, owns SQL诊断/数据质量");
  console.log("  user_002 (李四)     - 开发一组, owns 测试生成/Git规范");
  console.log("  user_003 (王五)     - 统计组, owns 统计报表/数据脱敏");
  console.log("  approver_001        - 一组/二组审批人, owns 代码规范巡检");
  console.log("  approver_002        - 统计组审批人");
  console.log("  approver_003        - 部门/产品线/杭研审批人");
  console.log("  demo-user           - 开发一组(访客)");
}

main().catch(e => { console.error("FATAL:", e); process.exit(1); });
