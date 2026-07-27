"""Test Skill management frontend - capture screenshots and verify UI behavior."""
from playwright.sync_api import sync_playwright
import os

OUT = os.path.join(os.path.dirname(__file__), "screenshots")
os.makedirs(OUT, exist_ok=True)

BASE = "http://localhost:5173"

def shot(page, name):
    path = os.path.join(OUT, f"{name}.png")
    page.screenshot(path=path, full_page=True)
    print(f"  Screenshot: {path}")

with sync_playwright() as p:
    browser = p.chromium.launch(headless=True, executable_path=r"C:\Program Files\Google\Chrome\Application\chrome.exe")
    ctx = browser.new_context(viewport={"width": 1280, "height": 900})
    page = ctx.new_page()

    # Capture console errors
    console_errors = []
    page.on("console", lambda msg: console_errors.append(f"[{msg.type}] {msg.text}") if msg.type == "error" else None)

    print("=== Test 1: All Skills view (demo-user) ===")
    page.goto(f"{BASE}/skills")
    page.wait_for_load_state("networkidle")
    page.wait_for_timeout(1500)
    shot(page, "01-all-skills-demo")

    # Check user switcher exists
    user_select = page.locator(".user-select")
    print(f"  User switcher present: {user_select.count() > 0}")

    # Check skill cards
    cards = page.locator(".grid .skill-card, .grid > div")
    print(f"  Skill cards: {cards.count()}")

    # Check filter bar - tag filter missing?
    selects = page.locator("select")
    select_count = selects.count()
    print(f"  Select dropdowns: {select_count} (should have category, availability, dimension, sort - but NO tag filter)")

    print("\n=== Test 2: Switch to user_001 ===")
    user_select.select_option("user_001")
    page.wait_for_timeout(2000)
    page.wait_for_load_state("networkidle")
    shot(page, "02-all-skills-user001")

    # Check availability badges - user_001 should see some available=true
    page_content = page.content()
    has_available = "可用" in page_content
    print(f"  Has '可用' text: {has_available}")

    print("\n=== Test 3: Popular view (should show rank) ===")
    page.goto(f"{BASE}/skills/popular")
    page.wait_for_load_state("networkidle")
    page.wait_for_timeout(1500)
    shot(page, "03-popular")
    # Check if rank numbers show
    rank_text = page.locator(".rank, [class*='rank']")
    print(f"  Rank elements: {rank_text.count()}")

    print("\n=== Test 4: Detail page (skill 18 - published) ===")
    page.goto(f"{BASE}/skills/18")
    page.wait_for_load_state("networkidle")
    page.wait_for_timeout(1500)
    shot(page, "04-detail-18")

    # Check owner hint (should show for published skill, but bug: checks status==='PUBLISHED')
    owner_hint = page.locator(".owner-hint")
    print(f"  Owner hint visible: {owner_hint.count() > 0} (should be >0 for published skill, but bug checks status==='PUBLISHED')")

    # Check version history
    versions_title = page.locator(".versions-title")
    if versions_title.count() > 0:
        versions_title.click()
        page.wait_for_timeout(1000)
        shot(page, "04b-detail-versions")

    print("\n=== Test 5: Created view (user_001) ===")
    # Switch to user_001 first
    page.goto(f"{BASE}/skills")
    page.wait_for_load_state("networkidle")
    page.locator(".user-select").select_option("user_001")
    page.wait_for_timeout(1000)
    page.goto(f"{BASE}/skills/created")
    page.wait_for_load_state("networkidle")
    page.wait_for_timeout(1500)
    shot(page, "05-created-user001")

    print("\n=== Test 6: Approvals page (approver_001) ===")
    page.goto(f"{BASE}/skills")
    page.wait_for_load_state("networkidle")
    page.locator(".user-select").select_option("approver_001")
    page.wait_for_timeout(1000)
    page.goto(f"{BASE}/skills/approvals")
    page.wait_for_load_state("networkidle")
    page.wait_for_timeout(1500)
    shot(page, "06-approvals-approver001")

    print("\n=== Test 7: Liked view (user_002) ===")
    page.goto(f"{BASE}/skills")
    page.wait_for_load_state("networkidle")
    page.locator(".user-select").select_option("user_002")
    page.wait_for_timeout(1000)
    page.goto(f"{BASE}/skills/liked")
    page.wait_for_load_state("networkidle")
    page.wait_for_timeout(1500)
    shot(page, "07-liked-user002")

    print("\n=== Test 8: Used view (user_002) ===")
    page.goto(f"{BASE}/skills/used")
    page.wait_for_load_state("networkidle")
    page.wait_for_timeout(1500)
    shot(page, "08-used-user002")

    print("\n=== Test 9: Category browse ===")
    page.goto(f"{BASE}/skills/category")
    page.wait_for_load_state("networkidle")
    page.wait_for_timeout(1500)
    shot(page, "09-category")

    print("\n=== Test 10: Create skill form ===")
    page.goto(f"{BASE}/skills/new")
    page.wait_for_load_state("networkidle")
    page.wait_for_timeout(1000)
    shot(page, "10-create-form")

    print("\n=== Test 11: List view toggle (list density) ===")
    page.goto(f"{BASE}/skills")
    page.wait_for_load_state("networkidle")
    page.locator(".user-select").select_option("demo-user")
    page.wait_for_timeout(1000)
    # Click list toggle
    list_btn = page.locator("button:has-text('列表')")
    if list_btn.count() > 0:
        list_btn.click()
        page.wait_for_timeout(1000)
        shot(page, "11-list-density")

    print("\n=== Console Errors ===")
    if console_errors:
        for e in console_errors[:10]:
            print(f"  {e}")
    else:
        print("  No console errors")

    browser.close()
    print("\nDone! Screenshots saved to:", OUT)
