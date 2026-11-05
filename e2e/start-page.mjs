// Browser test of the wake-up start page (docs/index.html, served by GitHub Pages). Needs no running app: it serves
// docs/ itself and plays the app with a fake server that is awake, asleep (holds the request like a sleeping Render
// instance), deploying (Render error pages without CORS) or down. Fails on any JavaScript error or CSP violation.
//   docker run --rm -v "${PWD}/docs:/docs" -v "${PWD}/e2e:/e2e" mcr.microsoft.com/playwright:v1.64.1-noble \
//     sh -c "npm i --no-save --prefix /tmp/pw playwright@1.64.1 && cp /e2e/start-page.mjs /tmp/pw/ && cd /tmp/pw && node start-page.mjs"
import { chromium } from 'playwright';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { extname, join, normalize } from 'node:path';

const DOCS = process.env.DOCS || '/docs';
const PAGE = 'http://localhost:4000';
const APP = 'http://localhost:4001';
const problems = [];
const check = (ok, message) => {
	console.log(`${ok ? 'PASS' : 'FAIL'}  ${message}`);
	if (!ok) problems.push(message);
};
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

// ---- docs/ as GitHub Pages would serve it, pointed at the fake app, giving up after 8 s instead of 5 minutes ----
const types = { '.html': 'text/html', '.css': 'text/css', '.js': 'text/javascript', '.svg': 'image/svg+xml' };
createServer(async (req, res) => {
	const path = normalize(new URL(req.url, PAGE).pathname).replace(/^(\.\.[/\\])+/, '');
	const file = join(DOCS, path.endsWith('/') ? `${path}index.html` : path);
	try {
		let body = await readFile(file);
		if (file.endsWith('index.html') && path === '/') {
			body = body.toString().replaceAll('https://bookmyseat-8j1e.onrender.com', APP)
				.replace('<body data-app=', '<body data-give-up="8" data-app=');
		}
		res.writeHead(200, { 'Content-Type': types[extname(file)] || 'application/octet-stream' }).end(body);
	} catch {
		res.writeHead(404).end('not found');
	}
}).listen(4000);

// ---- the fake app ----
let mode = 'awake';
let modeSince = Date.now();
const setMode = (m) => { mode = m; modeSince = Date.now(); };
createServer(async (req, res) => {
	if (req.url.startsWith('/actuator/health/liveness')) {
		const up = () => res.writeHead(200, { 'Content-Type': 'application/json', 'Access-Control-Allow-Origin': PAGE })
			.end('{"status":"UP"}');
		const renderError = () => res.writeHead(502, { 'Content-Type': 'text/html' }).end('<h1>Bad Gateway</h1>'); // no CORS header
		if (mode === 'awake') return up();
		if (mode === 'asleep') { await sleep(6000); return up(); } // Render holds the request while the app starts
		if (mode === 'deploying') return Date.now() - modeSince < 4000 ? renderError() : up(); // probes at 0, 3, 6 s
		return renderError(); // down
	}
	res.writeHead(200, { 'Content-Type': 'text/html' }).end('<!doctype html><title>Fake BookMySeat</title><h1>The app</h1>');
}).listen(4001);

const browser = await chromium.launch();

async function newPage(options = {}) {
	const context = await browser.newContext(options);
	const page = await context.newPage();
	page.on('pageerror', (e) => problems.push(`page error: ${e.message}`));
	page.on('console', (m) => {
		const text = m.text();
		if (/Content Security Policy/i.test(text)) problems.push(`CSP violation: ${text}`);
		// Failed probes (502, CORS) are expected while the fake app is deploying or down; anything else is not
		else if (m.type() === 'error' && !/CORS|Failed to load resource|ERR_FAILED/.test(text)) problems.push(`console error: ${text}`);
	});
	return page;
}

try {
	// Awake: straight through to the app
	setMode('awake');
	let page = await newPage();
	let started = Date.now();
	await page.goto(`${PAGE}/`);
	await page.waitForURL(`${APP}/`, { timeout: 5000 });
	check(Date.now() - started < 3000, `an awake app opens within 3 s (${Date.now() - started} ms)`);
	check(await page.title() === 'Fake BookMySeat', 'the visitor lands on the app itself');
	await page.context().close();

	// Asleep: the wait screen, with progress and a spoken status, then the app
	setMode('asleep');
	page = await newPage();
	await page.goto(`${PAGE}/`);
	await page.locator('#waiting:not([hidden])').waitFor({ timeout: 5000 });
	await sleep(1500);
	check(await page.getByRole('heading', { name: 'Waking up the demo…' }).isVisible(), 'a sleeping app shows the wake-up heading');
	check(await page.getByRole('status').textContent() === 'Waking up the server.', 'the status (a live region) says what is happening');
	const progress = page.getByRole('progressbar', { name: 'Waking up the demo…' });
	check(Number(await progress.getAttribute('value')) > 0, 'the progress bar moves and has an accessible name');
	check(/^0:0\d$/.test(await page.locator('#elapsed').textContent()), 'the elapsed time is shown');
	check(await page.getByRole('link', { name: 'watch the 2-minute video' }).getAttribute('href') === 'demo/',
		'the wait offers the demo video');
	check(await page.getByRole('link', { name: 'Open the demo directly' }).getAttribute('href') === `${APP}/`,
		'a direct link to the app is always there');
	await page.waitForURL(`${APP}/`, { timeout: 15000 });
	check(true, 'once the app answers UP the page opens it');
	await page.context().close();

	// Deploying: Render error pages (no CORS) are not the app; the page waits for the real "UP"
	setMode('deploying');
	page = await newPage();
	await page.goto(`${PAGE}/`);
	await sleep(3000);
	check(page.url() === `${PAGE}/`, 'a Render error page does not count as the app being up');
	await page.waitForURL(`${APP}/`, { timeout: 20000 });
	check(true, 'after the deploy the page opens the app');
	await page.context().close();

	// Down: give up with a clear message and a focused retry button; the retry works once the app is back
	setMode('down');
	page = await newPage();
	await page.goto(`${PAGE}/`);
	await page.locator('#failed:not([hidden])').waitFor({ timeout: 20000 });
	check(await page.getByRole('heading', { name: 'The demo didn’t wake up' }).isVisible(), 'a dead app ends in a clear failure message');
	check(await page.evaluate(() => document.activeElement?.id) === 'retry', 'the retry button gets the focus');
	setMode('awake');
	await page.getByRole('button', { name: 'Try again' }).click();
	await page.waitForURL(`${APP}/`, { timeout: 8000 });
	check(true, 'retrying after the app is back opens it');
	await page.context().close();

	// Phone, dark mode: no sideways scrolling, dark colours
	setMode('asleep');
	page = await newPage({ viewport: { width: 360, height: 740 }, colorScheme: 'dark', reducedMotion: 'reduce' });
	await page.goto(`${PAGE}/`);
	await page.locator('#waiting:not([hidden])').waitFor({ timeout: 5000 });
	check(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth),
		'no horizontal scroll on a 360 px phone');
	const background = await page.evaluate(() => getComputedStyle(document.body).backgroundColor);
	check(background === 'rgb(18, 19, 26)', `dark mode uses the app's dark background (${background})`);
	await page.context().close();
} finally {
	await browser.close();
}

check(problems.filter((p) => !p.startsWith('FAIL')).length === 0, `no JavaScript errors or CSP violations (${problems.join('; ')})`);
if (problems.length) {
	console.log(`\n${problems.length} problem(s)`);
	process.exit(1);
}
console.log('\nAll start page checks passed');
process.exit(0);
