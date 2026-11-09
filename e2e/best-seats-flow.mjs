// End-to-end browser test of "Pick best seats" (payments off): on a show with an aisle and a recliner row, a customer
// lets the app choose 2 seats (the middle of the row two thirds back), sees them held and hears which they are, releases
// them, asks for recliners, then for more seats together than any row has (told why, nothing held). With someone else
// holding the middle, the next best pair is picked instead. Also: focus goes to the confirm button, the choices survive
// the 10-second refresh, keyboard only, and the phone layout.
// Builds its own show through the API, so it needs an admin account (ADMIN_EMAIL / ADMIN_PASSWORD).
// Logs in 3 times: clear Redis rate:* keys first after other runs (rate limits). Run with:
//   docker run --rm -v "${PWD}/e2e:/e2e" -e BASE_URL=http://host.docker.internal:8080 \
//     -e ADMIN_EMAIL=... -e ADMIN_PASSWORD=... \
//     mcr.microsoft.com/playwright:v1.64.1-noble sh -c "npm i --no-save --prefix /tmp/pw playwright@1.64.1 \
//       && cp /e2e/best-seats-flow.mjs /tmp/pw/ && cd /tmp/pw && node best-seats-flow.mjs"
import { chromium, devices } from 'playwright';

const BASE = process.env.BASE_URL || 'http://localhost:8080';
const OUT = process.env.OUT || './screenshots';
const { ADMIN_EMAIL, ADMIN_PASSWORD } = process.env;
const RUN = Date.now();
const TITLE = `E2E Best Seats Show ${RUN}`;
const PASSWORD = 'Best-Seats-E2e-1';
const people = { customer: `claude-best-${RUN}@example.com`, other: `claude-best-other-${RUN}@example.com` };
const problems = [];

const check = (ok, message) => {
	console.log(`${ok ? 'PASS' : 'FAIL'}  ${message}`);
	if (!ok) problems.push(message);
};

function watchForErrors(page, who) {
	page.on('pageerror', (e) => problems.push(`${who}: page error: ${e.message}`));
	// "No 6 seats together" is an expected 409 response; anything else in the console is a problem
	page.on('console', (m) => { if (m.type() === 'error' && !m.text().includes('409')) problems.push(`${who}: console error: ${m.text()}`); });
}

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

const tokenOf = async (email, password) => (await rest('/api/auth/login', { method: 'POST', body: { email, password } })).accessToken;
const heldByMe = async (page) => (await page.locator('#seatmap .seat.mine').allInnerTexts()).join(',');
const announced = (page) => page.locator('#announce').textContent();

if (!ADMIN_EMAIL || !ADMIN_PASSWORD) {
	console.log('Set ADMIN_EMAIL and ADMIN_PASSWORD');
	process.exit(2);
}

const browser = await chromium.launch();
let admin;
let show;
try {
	// 3 rows of 8, an aisle after seat 5, the back row (C) recliners at ₹500
	admin = await tokenOf(ADMIN_EMAIL, ADMIN_PASSWORD);
	const venue = await rest('/api/venues', { method: 'POST', token: admin, body: { name: `E2E Best Seats Venue ${RUN}`, city: 'Pune' } });
	const startTime = new Date(Date.now() + 2 * 24 * 3600 * 1000).toISOString().replace(/\.\d+Z$/, 'Z');
	show = await rest('/api/shows', { method: 'POST', token: admin,
		body: { title: TITLE, startTime, price: 200, venueId: venue.id, categoryPrices: { RECLINER: 500 }, aisleAfter: [5] } });
	await rest(`/api/shows/${show.id}/seats`, { method: 'POST', token: admin, body: { rows: 3, seatsPerRow: 8, reclinerRows: 1 } });
	for (const email of Object.values(people)) {
		await rest('/api/auth/register', { method: 'POST', body: { name: 'Best Seats E2E', email, password: PASSWORD } });
	}

	const page = await browser.newPage({ viewport: { width: 1280, height: 860 } });
	watchForErrors(page, 'customer');
	await page.goto(`${BASE}/#/`);
	await page.getByRole('button', { name: 'Log in' }).click();
	await page.locator('#auth-form').getByLabel('Email').fill(people.customer);
	await page.locator('#auth-form').getByLabel('Password').fill(PASSWORD);
	await page.locator('#auth-submit').click();
	await page.locator('#logout-btn:not([hidden])').waitFor();
	await page.goto(`${BASE}/#/show/${show.id}`);
	await page.getByRole('button', { name: 'Pick best seats' }).waitFor();
	check(await page.getByLabel('Seats', { exact: true }).inputValue() === '2', 'two seats are picked by default');
	check((await page.getByLabel('Type').locator('option').allInnerTexts()).join(',') === 'Any,Regular,Recliner',
		'the type list offers only the categories this show has');
	await page.screenshot({ path: `${OUT}/best-01-controls.png` });

	// The choice survives the 10-second refresh
	await page.getByLabel('Seats', { exact: true }).selectOption('3');
	await page.waitForTimeout(11_000);
	check(await page.getByLabel('Seats', { exact: true }).inputValue() === '3', 'the chosen count survives the refresh');
	await page.getByLabel('Seats', { exact: true }).selectOption('2');

	// Pick: B4, B5 (row B is two thirds back, 4 and 5 the middle of the left block), held, announced, focus on confirm
	await page.getByRole('button', { name: 'Pick best seats' }).click();
	await page.locator('#timer').waitFor();
	check(await heldByMe(page) === '4,5' && (await page.locator('#summary').innerText()).includes('B4, B5'),
		`the best pair is picked and held: B4, B5 (${(await page.locator('#summary').innerText()).replace(/\s+/g, ' ')})`);
	check((await page.getByRole('button', { name: /Confirm booking/ }).innerText()).includes('₹400.00'), 'their price is on the button');
	await page.waitForFunction(() => document.getElementById('announce').textContent.startsWith('We picked'));
	check((await announced(page)).includes('We picked seats B4, B5'), 'screen readers hear which seats were picked');
	check(await page.evaluate(() => document.activeElement?.id === 'confirm'), 'focus moves to the confirm button');
	await page.screenshot({ path: `${OUT}/best-02-picked.png` });
	await page.getByRole('button', { name: 'Release' }).click();
	await page.getByRole('button', { name: 'Pick best seats' }).waitFor();

	// Recliners only: C4, C5 at ₹500 each
	await page.getByLabel('Type').selectOption('RECLINER');
	await page.getByRole('button', { name: 'Pick best seats' }).click();
	await page.locator('#timer').waitFor();
	check((await page.locator('#summary').innerText()).includes('C4, C5')
		&& (await page.getByRole('button', { name: /Confirm booking/ }).innerText()).includes('₹1,000.00'), 'recliners only: C4, C5 for ₹1,000');
	await page.getByRole('button', { name: 'Release' }).click();
	await page.getByRole('button', { name: 'Pick best seats' }).waitFor();
	check(await page.getByLabel('Type').inputValue() === 'RECLINER', 'the type stays chosen after releasing');

	// More seats together than any row has: the reason, nothing held
	await page.getByLabel('Type').selectOption('');
	await page.getByLabel('Seats', { exact: true }).selectOption('6');
	await page.getByRole('button', { name: 'Pick best seats' }).click();
	await page.locator('.toast', { hasText: 'No 6 seats together are free right now' }).waitFor();
	check(await page.locator('#timer').count() === 0 && await heldByMe(page) === '', '6 together is refused with the reason, and nothing is held');

	// Someone else holds the middle of row B: the next best pair goes to the customer instead
	const other = await tokenOf(people.other, PASSWORD);
	const seats = await rest(`/api/shows/${show.id}/seats`);
	const b4 = seats.find((s) => s.seatNumber === 'B4').id;
	await rest('/api/bookings/hold', { method: 'POST', token: other, body: { showId: show.id, seatIds: [b4] } });
	await page.reload();
	await page.getByRole('button', { name: 'Pick best seats' }).waitFor();
	await page.getByLabel('Seats', { exact: true }).selectOption('2');
	await page.getByRole('button', { name: 'Pick best seats' }).click();
	await page.locator('#timer').waitFor();
	const picked = (await page.locator('#summary').innerText()).replace(/\s+/g, ' ');
	check(picked.includes('C4, C5'), `with B4 held by someone else, the next best pair is picked (${picked})`);

	// Keyboard only: confirm the picked seats with Enter (focus is already on the button after picking)
	await page.getByRole('button', { name: 'Release' }).click();
	await page.getByRole('button', { name: 'Pick best seats' }).focus();
	await page.keyboard.press('Enter');
	await page.locator('#timer').waitFor();
	await page.waitForFunction(() => document.activeElement?.id === 'confirm');
	await page.keyboard.press('Enter');
	await page.getByRole('heading', { name: "You're booked!" }).waitFor();
	check((await page.locator('.success').innerText()).includes('C4, C5'), 'picked and booked with the keyboard alone');

	// Phone: the controls fit
	const phone = await browser.newPage({ ...devices['iPhone 13'] });
	watchForErrors(phone, 'phone');
	await phone.goto(`${BASE}/#/show/${show.id}`);
	await phone.getByRole('button', { name: 'Pick best seats' }).waitFor();
	check(!(await phone.evaluate(() => document.documentElement.scrollWidth > window.innerWidth)), 'phone: no horizontal page scroll');
	await phone.screenshot({ path: `${OUT}/best-03-phone.png`, fullPage: true });
} catch (error) {
	for (const p of browser.contexts().flatMap((c) => c.pages())) {
		await p.screenshot({ path: `${OUT}/zz-failure-${Math.random().toString(36).slice(2, 6)}.png` }).catch(() => {});
		const toasts = await p.locator('.toast').allInnerTexts().catch(() => []);
		if (toasts.length) problems.push(`toasts on ${p.url()}: ${toasts.join(' | ')}`);
	}
	problems.push(`step failed: ${error.message.split('\n')[0]}`);
} finally {
	await browser.close();
	if (show) await rest(`/api/shows/${show.id}/cancel`, { method: 'POST', token: admin }).catch(() => {});
}

console.log(`\nTest data: show "${TITLE}", accounts ${Object.values(people).join(', ')}`);
if (problems.length) {
	console.log(`\n${problems.length} problem(s):\n- ${problems.join('\n- ')}`);
	process.exit(1);
}
console.log('\nAll best-seat browser checks passed');
