// THE PAGE, END TO END: headless Chrome loads it (URL: GitHub Pages, or target/site served locally), presses through
// the places, takes a picture of each (/tmp/okr-*.png).
const cdpOf = require("./cdp.js");
(async () => {
  const c = await cdpOf(9361, Number(process.env.WIDTH || 1280), 900, process.env.WIDTH === "390");
  let failed = 0;
  const ok = (what, cond) => { console.log(`${cond ? "✓" : "✗"} ${what}`); if (!cond) failed++; };
  await c.js(`(()=>{})()`);
  const url = process.env.URL || "http://127.0.0.1:8798/";
  // a browser with nothing kept: the page as a first visit sees it
  await c.go(url); await c.js(`(()=>{localStorage.clear(); return true})()`); await c.go(url);
  ok("the page", (await c.text()).includes("okay-reliability"));
  ok("the system's field empty, a file chooser beside it", await c.js(`(()=>{const a=document.querySelector("textarea");
    return !!a && a.value === "" && !!document.querySelector("input[type=file]")})()`));
  await c.shot("/tmp/okr-system.png");
  await c.click("Examples"); await c.sleep(800); await c.shot("/tmp/okr-examples.png");
  await c.click("Load the shop"); await c.sleep(1500);
  ok("the architecture", (await c.text()).toLowerCase().includes("single points of failure"));
  await c.shot("/tmp/okr-architecture.png");
  await c.click("Reliability"); await c.sleep(1500);
  ok("the numbers", (await c.text()).includes("MISSED"));
  await c.go(url);
  ok("kept: the system there again after a reload", (await c.text()).includes("Architecture"));
  await c.shot("/tmp/okr-reliability.png");
  await c.click("What if"); await c.sleep(800);
  await c.js(`(()=>{const s=document.querySelector("select"); s.value=[...s.options].find(o=>o.text==="db down").value;
    s.dispatchEvent(new Event("change",{bubbles:true})); return true})()`);
  await c.click("Add the fault"); await c.sleep(1500);
  ok("what if db is down", (await c.text()).includes("restart db"));
  await c.shot("/tmp/okr-whatif.png");
  await c.click("Training"); await c.sleep(800);
  await c.click("Make an incident"); await c.sleep(1500);
  await c.click("Read it into the incident"); await c.sleep(2500);
  ok("an incident diagnosed", (await c.text()).toLowerCase().includes("the causes"));
  await c.shot("/tmp/okr-incident.png");
  await c.click("System"); await c.sleep(800); await c.click("Examples"); await c.sleep(800);
  await c.click("Load and diagnose: Facebook 2021"); await c.sleep(2500);
  ok("a real incident: Facebook 2021, the backbone first", await c.js(`(()=>{
    const h=[...document.querySelectorAll("*")].find(e=>e.textContent.trim()==="Probable causes");
    const t=h && h.parentElement.querySelector("td");
    return !!t && t.textContent.includes("backbone")})()`));
  await c.shot("/tmp/okr-real.png");
  ok("the foot: built on okay-effects, Need more?", await c.js(`(()=>{const f=document.querySelector("body > footer");
    return !!f && f.textContent.includes("okay-effects") && !!f.querySelector("a.more[href*='#author']")})()`));
  c.close();
  process.exit(failed ? 1 : 0);
})();
