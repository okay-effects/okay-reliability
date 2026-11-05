// THE PAGE, END TO END: headless Chrome loads it (URL: GitHub Pages, or target/site served locally), presses through
// the places, takes a picture of each (/tmp/okr-*.png).
const cdpOf = require("./cdp.js");
const fs = require("fs");
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
  // a folder: the system and its logs, chosen as files (spec §38)
  const dir = "/tmp/okr-e2e-folder";
  fs.rmSync(dir, { recursive: true, force: true }); fs.mkdirSync(dir + "/logs", { recursive: true });
  fs.writeFileSync(dir + "/arch.txt",
    "web: service entry\ncheckout: service\ndb: database\nweb -> checkout hard\ncheckout -> db hard\n");
  fs.writeFileSync(dir + "/logs/checkout.log", "2026-11-04T14:01:02Z ERROR connection refused to db:5432\n");
  fs.writeFileSync(dir + "/logs/web.log", "2026-11-04T14:01:05Z ERROR upstream checkout returned 503\n");
  await c.click("System"); await c.sleep(800);
  ok("a folder: its files chosen", await c.files(
    "textarea#f-folder ~ label.okay-file:not(:has([webkitdirectory])) input",
    [dir + "/arch.txt", dir + "/logs/checkout.log", dir + "/logs/web.log"]));
  await c.sleep(800); await c.click("Read the files"); await c.sleep(2500);
  const folderText = await c.text();
  ok("a folder: the system and the logs read, the incident shown", folderText.includes("logs of checkout, web") &&
    folderText.toLowerCase().includes("probable causes"));
  await c.shot("/tmp/okr-folder.png");
  // Micro in the browser (specs/reader.md §8): a line only it reads, its fact used
  const dir2 = "/tmp/okr-e2e-micro"; fs.rmSync(dir2, { recursive: true, force: true }); fs.mkdirSync(dir2);
  fs.writeFileSync(dir2 + "/arch.txt", "web: service entry\ncheckout: service\npayments: external\n" +
    "web -> checkout hard\ncheckout -> payments hard\n");
  fs.writeFileSync(dir2 + "/checkout.log",
    "2026-11-05T10:00:18Z ERROR stripe error from payments: 500 Internal Server Error\n");
  await c.click("System"); await c.sleep(800);
  await c.files("textarea#f-folder ~ label.okay-file:not(:has([webkitdirectory])) input",
    [dir2 + "/arch.txt", dir2 + "/checkout.log"]);
  await c.sleep(800); await c.click("Read the files"); await c.sleep(3000);
  ok("Micro in the browser: a line only it reads, its fact used", (await c.text()).includes("micro:checkout:1"));
  ok("the foot: built on okay-effects, Need more?", await c.js(`(()=>{const f=document.querySelector("body > footer");
    return !!f && f.textContent.includes("okay-effects") && !!f.querySelector("a.more[href*='#author']")})()`));
  c.close();
  process.exit(failed ? 1 : 0);
})();
