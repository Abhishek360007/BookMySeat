// End-to-end browser test of following a film (payments off): a visitor is asked to log in first; a customer follows
// a film that has no showtimes yet (the toggle and its explanation change, and are announced), sees it in My
// bookings, an admin adds two showtimes, and the customer gets ONE email listing both (read from Mailpit); My
// bookings then counts them, and Stop following removes the film, keeping the keyboard where it was.
// Builds its own film, so it needs an admin account (ADMIN_EMAIL / ADMIN_PASSWORD). Logs in 3 times.
// The email check needs MAILPIT_URL and an app that announces quickly (otherwise it waits up to 15 minutes):
//   --showtimes.announce.after=PT5S --showtimes.announce.interval=PT10S --showtimes.announce.initial-delay=PT5S
// Run with:
//   docker run --rm -v "${PWD}/e2e:/e2e" -e BASE_URL=http://host.docker.internal:8080 -e MAILPIT_URL=http://host.docker.internal:18025 \
//     -e ADMIN_EMAIL=... -e ADMIN_PASSWORD=... mcr.microsoft.com/playwright:v1.64.1-noble sh -c \
//     "npm i --no-save --prefix /tmp/pw playwright@1.64.1 && cp /e2e/follow-flow.mjs /tmp/pw/ && cd /tmp/pw && node follow-flow.mjs"
import { chromium } from 'playwright';

const BASE = process.env.BASE_URL || 'http://localhost:8080';
const MAILPIT = process.env.MAILPIT_URL;
const OUT = process.env.OUT || './screenshots';
const { ADMIN_EMAIL, ADMIN_PASSWORD } = process.env;
const RUN = Date.now();
const TITLE = `E2E Follow Film ${RUN}`;
const EMAIL = `claude-follow-${RUN}@example.com`;
const PASSWORD = 'E2e-Password-123';
const problems = [];

const check = (ok, message) => {
	console.log(`${ok ? 'PASS' : 'FAIL'}  ${message}`);
	if (!ok) problems.push(message);
};

async function rest(path, { method = 'GET', body, token } = {}) {
	const res = await fetch(`${BASE}${path}`, {
		method,
		headers: { ...(body ? { 'Content-Type': 'application/json' } : {}), ...(token ? { Authorization: `Bearer ${token}` } : {}) },
		body: body ? JSON.stringify(body) : undefined,
	});
	const text = await res.text();
	if (!res.ok) throw new Error(`${method} ${path} -> ${res.status} ${text}`);
	return text ? JSON.parse(text) : null;
}

async function newPage(context, who, csp) {
	await context.exposeFunction('reportCsp', (v) => csp.push(`${who}: ${v}`));
	await context.addInitScript(() => document.addEventListener('securitypolicyviolation', (e) => window.reportCsp(`${e.violatedDirective} ${e.blockedURI}`)));
	const page = await context.newPage();
	page.on('pageerror', (e) => problems.push(`${who}: page error: ${e.message}`));
	page.on('console', (m) => { if (m.type() === 'error' && !/status of 40[49]/.test(m.text())) problems.push(`${who}: console error: ${m.text()}`); });
	return page;
}

const browser = await chromium.launch();
const shows = [];
let adminToken = null;
try {
	adminToken = (await rest('/api/auth/login', { method: 'POST', body: { email: ADMIN_EMAIL, password: ADMIN_PASSWORD } })).accessToken;
	const venue = await rest('/api/venues', { method: 'POST', token: adminToken, body: { name: `E2E Follow Hall ${RUN}`, city: 'Pune' } });
	const film = await rest('/api/admin/films', { method: 'POST', token: adminToken, body: { title: TITLE, runtimeMinutes: 120 } });
	await rest('/api/auth/register', { method: 'POST', body: { name: 'Fatima Follower', email: EMAIL, password: PASSWORD } });
	const csp = [];

	// A visitor is asked to log in first
	const visitorContext = await browser.newContext({ viewport: { width: 1100, height: 900 } });
	const visitor = await newPage(visitorContext, 'visitor', csp);
	await visitor.goto(`${BASE}/#/film/${film.id}`);
	const visitorButton = visitor.getByRole('button', { name: 'Follow this film' });
	await visitorButton.waitFor();
	await visitorButton.click();
	await visitor.locator('#auth-dialog[open]').waitFor();
	check(true, 'following while logged out asks to log in first');
	await visitor.getByRole('button', { name: 'Cancel' }).click();
	check(await visitorButton.getAttribute('aria-pressed') === 'false', '...and cancelling follows nothing');

	// The customer follows it
	const context = await browser.newContext({ viewport: { width: 1100, height: 900 } });
	const page = await newPage(context, 'customer', csp);
	await page.goto(`${BASE}/`);
	await page.getByRole('button', { name: 'Log in' }).click();
	await page.locator('#auth-form').getByLabel('Email').fill(EMAIL);
	await page.locator('#auth-form').getByLabel('Password').fill(PASSWORD);
	await page.locator('#auth-submit').click();
	await page.locator('#logout-btn:not([hidden])').waitFor();
	await page.goto(`${BASE}/#/film/${film.id}`);
	const button = page.getByRole('button', { name: 'Follow this film' });
	await button.waitFor();
	check(await button.getAttribute('aria-pressed') === 'false', 'the follow button starts not pressed');
	check((await page.locator('#follow-status').innerText()).includes('Get an email when new showtimes are added'), '...and says what following does');
	await button.click();
	await page.waitForFunction(() => document.getElementById('follow-btn').getAttribute('aria-pressed') === 'true');
	check((await page.locator('#follow-status').innerText()).startsWith('Following'), 'pressed, it says the customer is following (a live region)');
	check(await page.evaluate(() => document.activeElement?.id) === 'follow-btn', 'focus stays on the button');
	check((await button.innerText()).trim().endsWith('Follow this film'), 'the button keeps its name (the tick is decoration)');
	await page.screenshot({ path: `${OUT}/fl1-following.png` });

	await page.goto(`${BASE}/#/bookings`);
	const followed = page.locator('#following .card', { hasText: TITLE });
	await followed.waitFor();
	check((await followed.innerText()).includes('No showtimes yet'), 'My bookings lists the film, with no showtimes yet');

	// The admin adds two showtimes: one email, listing both
	for (const days of [3, 4]) {
		const show = await rest('/api/shows', { method: 'POST', token: adminToken, body: {
			filmId: film.id, startTime: new Date(Date.now() + days * 24 * 3600_000).toISOString(), price: 200, venueId: venue.id } });
		shows.push(show.id);
	}
	if (MAILPIT) {
		let mail = null;
		for (let i = 0; i < 60 && !mail; i++) {
			const list = await (await fetch(`${MAILPIT}/api/v1/search?query=${encodeURIComponent(`to:${EMAIL}`)}`)).json();
			const hit = list.messages?.find((m) => m.Subject === `New showtimes for ${TITLE}`);
			if (hit) {
				mail = await (await fetch(`${MAILPIT}/api/v1/message/${hit.ID}`)).json();
				check(list.messages.filter((m) => m.Subject === `New showtimes for ${TITLE}`).length === 1, 'exactly one email for both showtimes');
			} else {
				await page.waitForTimeout(2000);
			}
		}
		check(mail !== null, 'the follower is emailed about the new showtimes');
		check(mail?.Text.includes('2 new showtimes') && mail?.Text.includes(`E2E Follow Hall ${RUN}, Pune`) && mail?.Text.includes(`#/film/${film.id}`),
			'the email lists both showtimes with their venue and links to the film');
	} else {
		console.log('SKIP  the email check (no MAILPIT_URL)');
	}

	await page.reload();
	await page.locator('#following .card', { hasText: '2 showtimes coming up' }).waitFor();
	check(true, 'My bookings now counts the 2 showtimes coming up');
	await page.getByRole('button', { name: `Stop following ${TITLE}` }).click();
	await page.locator('#following .card', { hasText: TITLE }).waitFor({ state: 'detached' });
	check(await page.evaluate(() => document.activeElement && document.activeElement !== document.body), 'after Stop following, the keyboard focus is kept on the page');
	await page.goto(`${BASE}/#/film/${film.id}`);
	await page.getByRole('button', { name: 'Follow this film' }).waitFor();
	check(await page.getByRole('button', { name: 'Follow this film' }).getAttribute('aria-pressed') === 'false', 'the film page shows it is not followed any more');

	check(csp.length === 0, `no Content-Security-Policy violations (${csp.join(', ')})`);
} catch (error) {
	for (const p of browser.contexts().flatMap((c) => c.pages())) {
		await p.screenshot({ path: `${OUT}/zz-follow-failure-${Math.random().toString(36).slice(2, 6)}.png` }).catch(() => {});
	}
	problems.push(`step failed: ${error.message.split('\n')[0]}`);
} finally {
	await browser.close();
	for (const id of shows) await rest(`/api/shows/${id}/cancel`, { method: 'POST', token: adminToken }).catch(() => {});
}

if (problems.length) {
	console.log(`\n${problems.length} problem(s):\n- ${problems.join('\n- ')}`);
	process.exit(1);
}
console.log('\nAll follow browser checks passed');
