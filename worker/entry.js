// okay-reliability's Worker: its logic Scala (okay-serverless's Worker, dist/okay-reliability-worker.js); here only
// what JavaScript must say — the entry point, and the page's files as text
import { worker, setFiles } from "./dist/okay-reliability-worker.js";
import files from "./dist/assets.js";
setFiles(files);

export default { fetch: (request, env, ctx) => worker.fetch(request, env, ctx) };
