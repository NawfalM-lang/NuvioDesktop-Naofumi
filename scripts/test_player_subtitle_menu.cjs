// Browser smoke checks against the actual native player HTML/CSS/JS.
// Requires Playwright on NODE_PATH; no new application dependency.
const { chromium } = require("playwright");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const { pathToFileURL } = require("node:url");
const playerDir = path.resolve(__dirname, "../composeApp/src/desktopMain/resources/player-ui");
const fixture={title:'Sample episode',episodeText:'Season 1 · Episode 4',isPlaying:true,isLoading:false,durationMs:2400000,positionMs:240000,controlsVisible:true,
 subtitleLanguageItems:[{key:'__off__',label:'None',count:0},{key:'ar',label:'Arabic',count:8,isSelected:true},{key:'en',label:'English',count:4},{key:'fr',label:'French',count:2}],
 subtitleOptionItems:[{id:'internal-ar',kind:'builtIn',languageKey:'ar',sourceLabel:'Built-in',title:'Arabic',index:1},...Array.from({length:7},(_,i)=>({id:'addon-ar-'+i,kind:'addon',languageKey:'ar',sourceLabel:i<4?'OpenSubtitles':'SubDL',title:'Arabic',metadata:`Sample.Series.S01E04.1080p.WEB-DL.DDP5.1.H.264.Release.${i+1}.ar.srt`,isSelected:i===0,index:i})),{id:'internal-en',kind:'builtIn',languageKey:'en',sourceLabel:'Built-in',title:'English',index:2}],
 selectedSubtitleLanguageKey:'ar',selectedSubtitleOptionId:'addon-ar-0',hasSelectedAddonSubtitle:true,subtitleDelayMs:0,
 subtitleStyle:{textColor:'#FFFFFFFF',outlineColor:'#FF000000',outlineEnabled:true,bold:false,fontSizeSp:24,bottomOffset:20},subtitleColorSwatches:['#FFFFFFFF','#FFFFE082','#FF81D4FA','#FFA5D6A7'],subtitleOutlineColorSwatches:['#FF000000','#FF333333','#FF0000FF']};

(async () => {
  const browser = await chromium.launch({
    headless: true,
    ...(process.env.NUVIO_UI_BROWSER_PATH ? { executablePath: process.env.NUVIO_UI_BROWSER_PATH } : {}),
  });
  let passed = 0;
  const test = async (name, run) => {
    const page = await browser.newPage({ viewport: { width: 1366, height: 768 }, deviceScaleFactor: 1 });
    const errors = [];
    page.on("pageerror", error => errors.push(error.message));
    try {
      await page.addInitScript(() => {
        window.testPlayerMessages = [];
        window.webkit = { messageHandlers: { player: { postMessage: message => window.testPlayerMessages.push(message) } } };
      });
      await page.goto(pathToFileURL(path.join(playerDir, "controls.html")).href);
      await page.evaluate(data => { document.body.style.background = "#0b1016"; window.playerControls(data); }, fixture);
      await page.locator('[data-command="subtitles"]').click();
      await page.locator('#subtitleModal.modal-visible').waitFor();
      await run(page);
      assert.deepEqual(errors, [], "Player JavaScript errors");
      console.log("PASS " + name);
      passed++;
    } finally { await page.close(); }
  };
  const emitted = async (page, type, value = 0) => {
    assert.ok(await page.evaluate(({ type, value }) => window.testPlayerMessages.some(item =>
      item.type === type && item.value === value), { type, value }), `Missing player command ${type}:${value}`);
  };
  try {
    await test("selection is the default; release details are optional", async page => {
      assert.ok(await page.locator("#subtitleTracksPanel").isVisible());
      assert.ok(await page.locator("#subtitleStyleRail").isHidden());
      assert.ok(await page.locator("#subtitleTimingPanel").isHidden());
      assert.equal(await page.locator(".subtitle-source-heading").count(), 2);
      assert.ok(await page.locator(".subtitle-option-metadata").first().isHidden());
      await page.locator("#subtitleDetailsToggle").click();
      assert.ok(await page.locator(".subtitle-option-metadata").first().isVisible());
      await page.locator("#subtitleDetailsToggle").click();
      assert.ok(await page.locator(".subtitle-option-metadata").first().isHidden());
    });
    await test("built-in and add-on selections keep their native indices", async page => {
      await page.locator('[data-subtitle-option="addon-ar-3"]').click();
      await emitted(page, "selectAddonSubtitle", 3);
      await page.locator('[data-subtitle-language="en"]').click();
      await page.locator('[data-subtitle-option="internal-en"]').click();
      await emitted(page, "selectBuiltInSubtitleTrack", 2);
      await page.locator('[data-subtitle-language="__off__"]').click();
      await emitted(page, "selectBuiltInSubtitleTrack", -1);
    });
    await test("appearance preview and controls follow native state", async page => {
      await page.locator("#subtitleAppearanceTab").click();
      assert.equal(await page.locator("#subtitlePreviewText").getAttribute("dir"), "auto");
      assert.match(await page.locator("#subtitlePreviewText").textContent(), /الترجمة/);
      await page.locator("#fontSizePlus").click();
      await emitted(page, "subtitleFontSizeDelta", 2);
      await page.locator("#subtitleColorSwatches button").nth(1).click();
      await emitted(page, "subtitleTextColor", 1);
      await page.evaluate(() => window.playerControls({ subtitleStyle: {
        fontSizeSp: 30, bold: true, outlineEnabled: false, textColor: "#FFFFE082", bottomOffset: 20,
      } }));
      assert.equal(await page.locator("#subtitlePreviewText").evaluate(el => getComputedStyle(el).fontSize), "30px");
      assert.equal(await page.locator("#subtitlePreviewText").evaluate(el => getComputedStyle(el).fontWeight), "700");
      await page.locator("#customSubtitleStyleToggle").click();
      await emitted(page, "subtitleCustomStyleToggle");
      await page.evaluate(() => window.playerControls({ customSubtitleStylingEnabled: false }));
      assert.ok(await page.locator("#subtitleColorSwatches button").first().isDisabled());
      assert.ok(await page.locator("#fontSizePlus").isEnabled());
    });
    await test("timing and manual matching keep existing player commands", async page => {
      await page.locator("#subtitleTimingTab").click();
      await page.locator("#subtitleDelayMinus").click(); await emitted(page, "subtitleDelayDelta", -100);
      await page.locator("#subtitleDelayPlus").click(); await emitted(page, "subtitleDelayDelta", 100);
      await page.locator("#subtitleDelayReset").click(); await emitted(page, "subtitleDelayReset");
      await page.locator("#hybridSyncButton").click(); await emitted(page, "subtitleHybridAutoSync");
      assert.equal(await page.locator("#subtitleManualSync").getAttribute("open"), null);
      await page.locator("#subtitleManualSync summary").click();
      await page.locator("#autoSyncCapture").click(); await emitted(page, "subtitleAutoSyncCapture");
      await page.locator("#autoSyncReload").click(); await emitted(page, "subtitleAutoSyncReload");
      await page.evaluate(() => window.playerControls({ subtitleAutoSyncCues: [{ index: 7, timeLabel: "04:00", text: "مرحبا" }], subtitleDelayMs: 1300 }));
      assert.equal(await page.locator("#subtitleDelayValue").textContent(), "+1.300s");
      await page.locator(".sync-cue").click(); await emitted(page, "subtitleAutoSyncCue", 7);
    });
    await test("sync needs an add-on; status stays readable", async page => {
      await page.locator("#subtitleTimingTab").click();
      await page.evaluate(() => window.playerControls({ hasSelectedAddonSubtitle: false }));
      assert.ok(await page.locator("#hybridSyncButton").isDisabled());
      assert.ok(await page.locator("#autoSyncCapture").isDisabled());
      assert.match(await page.locator("#autoSyncStatus").textContent(), /Select an addon/);
      await page.evaluate(() => window.playerControls({ hasSelectedAddonSubtitle: true, subtitleAutoSyncErrorMessage: "Sync failed: embedded English could not be read; original subtitle kept" }));
      assert.match(await page.locator("#autoSyncStatus").textContent(), /original subtitle kept/);
    });
    await test("native refresh preserves focused row and list position", async page => {
      const row = page.locator('[data-subtitle-option="addon-ar-3"]');
      await row.focus();
      const before = await page.locator("#addonSubtitleList").evaluate(el => el.scrollTop);
      await page.evaluate(() => window.playerControls({ positionMs: 241000 }));
      assert.equal(await page.evaluate(() => document.activeElement.dataset.subtitleOption), "addon-ar-3");
      assert.equal(await page.locator("#addonSubtitleList").evaluate(el => el.scrollTop), before);
    });
    await test("keyboard tabs, focus containment, dismissal, and reopening", async page => {
      await page.locator("#subtitleTracksTab").focus();
      await page.keyboard.press("ArrowRight");
      assert.equal(await page.locator("#subtitleAppearanceTab").getAttribute("aria-selected"), "true");
      await page.keyboard.press("End");
      assert.equal(await page.locator("#subtitleTimingTab").getAttribute("aria-selected"), "true");
      await page.keyboard.press("Home");
      const last = page.locator("#addonSubtitleList .track-row").last();
      await last.focus(); await page.keyboard.press("Tab");
      assert.equal(await page.evaluate(() => document.activeElement.id), "subtitleCloseButton");
      await page.keyboard.press("Escape");
      await page.locator("#subtitleModal").waitFor({ state: "hidden" });
      await page.locator('[data-command="subtitles"]').click();
      assert.equal(await page.locator("#subtitleTracksTab").getAttribute("aria-selected"), "true");
      await page.locator("#subtitleCloseButton").click();
      await page.locator("#subtitleModal").waitFor({ state: "hidden" });
    });
    await test("loading and fetching remain available for empty languages", async page => {
      await page.locator('[data-subtitle-language="fr"]').click();
      await page.locator(".subtitle-fetch-row").click(); await emitted(page, "fetchAddonSubtitles");
      await page.evaluate(() => window.playerControls({ isLoadingAddonSubtitles: true }));
      assert.match(await page.locator("#addonSubtitleList").textContent(), /Loading subtitles/);
    });
    await test("long Arabic and narrow/short windows stay within the panel", async page => {
      await page.evaluate(data => window.playerControls({ subtitleOptionItems: data.subtitleOptionItems.map(option => ({
        ...option, title: "الترجمة العربية — وصف طويل لاختبار التفاف النص وإمكانية قراءة القائمة", metadata: "release-".repeat(40),
      })) }), fixture);
      for (const viewport of [{ width: 480, height: 640 }, { width: 900, height: 500 }, { width: 1920, height: 1080 }]) {
        await page.setViewportSize(viewport);
        for (const tab of ["Tracks", "Appearance", "Timing"]) {
          await page.locator(`#subtitle${tab}Tab`).click();
          const dimensions = await page.locator(".subtitle-rail-panel").evaluate(el => ({
            x: el.getBoundingClientRect().x, right: el.getBoundingClientRect().right,
            top: el.getBoundingClientRect().top, bottom: el.getBoundingClientRect().bottom,
            width: el.clientWidth, contentWidth: el.scrollWidth,
          }));
          assert.ok(dimensions.x >= 0 && dimensions.right <= viewport.width, JSON.stringify(dimensions));
          assert.ok(dimensions.top >= 0 && dimensions.bottom <= viewport.height, JSON.stringify(dimensions));
          assert.ok(dimensions.contentWidth <= dimensions.width + 1, "Horizontal overflow");
        }
      }
    });
    if (process.env.NUVIO_UI_SCREENSHOTS) {
      const directory = path.resolve(process.env.NUVIO_UI_SCREENSHOTS); fs.mkdirSync(directory, { recursive: true });
      await test("capture UI review screenshots", async page => {
        await page.screenshot({ path: path.join(directory, "selection.png") });
        await page.locator(".subtitle-rail-panel").screenshot({ path: path.join(directory, "selection-panel.png") });
        await page.locator("#subtitleAppearanceTab").click(); await page.screenshot({ path: path.join(directory, "appearance.png") });
        await page.locator("#subtitleTimingTab").click(); await page.screenshot({ path: path.join(directory, "timing.png") });
        await page.setViewportSize({ width: 480, height: 640 });
        await page.locator("#subtitleTracksTab").click(); await page.screenshot({ path: path.join(directory, "compact.png") });
      });
    }
    console.log(`${passed} subtitle menu browser checks passed`);
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
