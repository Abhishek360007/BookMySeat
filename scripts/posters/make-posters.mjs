// Makes the demo posters (scripts/posters/*.jpg) from the HTML designs below: gradients and type only, no photos,
// so they are original. Only the made-up films of the demo get one; "Dune: Part Three" is a real film and keeps
// its initials. Run with Playwright in Docker (from the repo root):
//   docker run --rm -v "${PWD}/scripts/posters:/out" mcr.microsoft.com/playwright:v1.64.1-noble sh -c \
//     "npm i --no-save --prefix /tmp/pw playwright@1.64.1 && cp /out/make-posters.mjs /tmp/pw/ && cd /tmp/pw && node make-posters.mjs"
import { chromium } from 'playwright';

const base = `
	* { margin: 0; box-sizing: border-box; }
	body { width: 600px; height: 900px; overflow: hidden; font-family: 'DejaVu Sans', sans-serif; color: #fff; }
	.poster { position: relative; width: 600px; height: 900px; padding: 56px 48px; display: flex; flex-direction: column; }
	.title { font-weight: 800; line-height: .95; letter-spacing: -.01em; }
	.tag { font-size: 22px; line-height: 1.35; opacity: .9; }
	.foot { margin-top: auto; font-size: 15px; letter-spacing: .25em; text-transform: uppercase; opacity: .75; }
	.cert { position: absolute; top: 40px; right: 40px; border: 2px solid currentColor; border-radius: 6px; padding: 2px 10px; font-weight: 700; font-size: 18px; }
`;

const posters = [
	{ file: 'the-monsoon-letters.jpg', html: `
		<style>
			.poster { background: linear-gradient(180deg, #0f3d3e 0%, #164e63 55%, #1e293b 100%); }
			.rain { position: absolute; inset: 0; background: repeating-linear-gradient(105deg, rgba(255,255,255,.10) 0 2px, transparent 2px 26px); }
			.letter { position: absolute; left: 150px; top: 150px; width: 300px; height: 200px; background: #f5efe0; border-radius: 6px; transform: rotate(-6deg); box-shadow: 0 20px 40px rgba(0,0,0,.35); }
			.letter::after { content: ''; position: absolute; inset: 0; background: linear-gradient(155deg, transparent 49.5%, #d6cdb6 50%, transparent 50.5%), linear-gradient(25deg, transparent 49.5%, #d6cdb6 50%, transparent 50.5%); }
			.title { font-family: 'DejaVu Serif', serif; font-size: 66px; font-weight: 700; margin-top: 380px; position: relative; }
		</style>
		<div class="poster"><div class="rain"></div><span class="cert">U</span><div class="letter"></div>
			<div class="title">The Monsoon Letters</div>
			<p class="tag" style="margin-top:18px">One rainy season. Two strangers.<br>Three floors apart.</p>
			<div class="foot">Hindi · In cinemas</div></div>` },
	{ file: 'orbit-of-ashes.jpg', html: `
		<style>
			.poster { background: radial-gradient(circle at 50% 38%, #f97316 0 0, #000 0) , #050505; background: radial-gradient(circle at 50% 36%, #fb923c 0%, #c2410c 16%, #431407 34%, #050505 60%); }
			.ring { position: absolute; left: 90px; top: 180px; width: 420px; height: 420px; border-radius: 50%; border: 2px solid rgba(253, 186, 116, .55); box-shadow: 0 0 80px rgba(249,115,22,.45); }
			.ring2 { position: absolute; left: 30px; top: 330px; width: 540px; height: 120px; border-radius: 50%; border: 3px solid rgba(254, 215, 170, .6); transform: rotate(-12deg); }
			.title { font-size: 70px; text-transform: uppercase; letter-spacing: .12em; margin-top: auto; text-align: center; }
			.tag { text-align: center; margin: 16px 0 24px; }
			.foot { margin-top: 0; text-align: center; }
		</style>
		<div class="poster"><span class="cert">UA</span><div class="ring"></div><div class="ring2"></div>
			<div class="title">Orbit<br>of Ashes</div>
			<p class="tag">The distress call came from a ship<br>that should not exist.</p>
			<div class="foot">English · In cinemas</div></div>` },
	{ file: 'chai-at-midnight.jpg', html: `
		<style>
			.poster { background: radial-gradient(circle at 70% 30%, rgba(253,224,71,.85) 0%, rgba(251,146,60,.35) 14%, transparent 34%), linear-gradient(180deg, #0b1026 0%, #1e1b4b 70%, #312e81 100%); }
			.cup { position: absolute; left: 190px; top: 520px; width: 220px; height: 150px; background: #fef3c7; border-radius: 0 0 90px 90px; box-shadow: inset 0 -20px 0 #d97706; }
			.cup::after { content: ''; position: absolute; right: -46px; top: 30px; width: 60px; height: 60px; border: 12px solid #fef3c7; border-radius: 50%; }
			.steam { position: absolute; left: 260px; top: 420px; width: 80px; height: 90px; border-left: 6px solid rgba(255,255,255,.5); border-radius: 50%; }
			.title { font-family: 'DejaVu Serif', serif; font-size: 72px; font-style: italic; font-weight: 700; color: #fde68a; }
			.tag { margin-top: 18px; }
		</style>
		<div class="poster"><span class="cert">U</span><div class="steam"></div><div class="cup"></div>
			<div class="title">Chai at<br>Midnight</div>
			<p class="tag">One tea stall. Eight customers.<br>The longest night of the year.</p>
			<div class="foot">Hindi · In cinemas</div></div>` },
	{ file: 'kaveri-express.jpg', html: `
		<style>
			.poster { background: linear-gradient(180deg, #fef3c7 0%, #fcd34d 38%, #15803d 38.2%, #14532d 100%); color: #1c1917; }
			.track { position: absolute; left: 50%; bottom: 0; width: 520px; height: 560px; transform: translateX(-50%); clip-path: polygon(46% 0, 54% 0, 100% 100%, 0 100%);
				background: repeating-linear-gradient(180deg, #78350f 0 10px, #44403c 10px 46px); }
			.rails { position: absolute; left: 50%; bottom: 0; width: 420px; height: 560px; transform: translateX(-50%); clip-path: polygon(47% 0, 48% 0, 12% 100%, 8% 100%, 47% 0, 52% 0, 53% 0, 92% 100%, 88% 100%, 52% 0); background: #e7e5e4; }
			.title { font-size: 80px; text-transform: uppercase; letter-spacing: .02em; }
			.tag { color: #292524; margin-top: 12px; }
			.foot { color: #292524; opacity: .8; margin-top: 18px; }
		</style>
		<div class="poster"><span class="cert">UA</span><div class="track"></div><div class="rails"></div>
			<div class="title">Kaveri<br>Express</div>
			<p class="tag">Chennai to Mysuru. One missing suitcase.<br>Nobody is quite who they say.</p>
			<div class="foot">Tamil · In cinemas</div></div>` },
];

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 600, height: 900 } });
for (const poster of posters) {
	await page.setContent(`<!doctype html><html><head><style>${base}</style></head><body>${poster.html}</body></html>`);
	await page.screenshot({ path: `/out/${poster.file}`, type: 'jpeg', quality: 88 });
	console.log(`made ${poster.file}`);
}
await browser.close();
