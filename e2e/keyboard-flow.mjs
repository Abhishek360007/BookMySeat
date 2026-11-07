// End-to-end keyboard test: books seats with the keyboard only (no mouse clicks) and checks where focus goes on the way.
// The skip link, focus on the new page's heading after moving to another page, the seat map as one Tab stop with arrow
// keys (a booked seat is reachable and announced as booked), focus that survives the 10-second refresh of the seat
// map, the login dialog, and focus on the summary and then on "You're booked!".
// Builds its own show through the API, so it needs an admin account (ADMIN_EMAIL / ADMIN_PASSWORD). Logs in 3 times.
// Run with:
//   docker run --rm -v "${PWD}/e2e:/e2e" -e BASE_URL=http://host.docker.internal:8080 -e ADMIN_EMAIL=... -e ADMIN_PASSWORD=... \
//     mcr.microsoft.com/playwright:v1.64.1-noble sh -c "npm i --no-save --prefix /tmp/pw playwright@1.64.1 \
//       && cp /e2e/keyboard-flow.mjs /tmp/pw/ && cd /tmp/pw && node keyboard-flow.mjs"
import { chromium } from 'playwright';

const BASE = process.env.BASE_URL || 'http://localhost:8080';
const OUT = process.env.OUT || './screenshots';
const { ADMIN_EMAIL, ADMIN_PASSWORD } = process.env;
const RUN = Date.now();
const TITLE = `E2E Keyboard Show ${RUN}`;
const PASSWORD = 'Keyboard-E2e-Pw-1';
const people = { other: `claude-kb-other-${RUN}@example.com`, me: `claude-kb-me-${RUN}@example.com` };
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

const tokenOf = async (email, password) => (await rest('/api/auth/login', { method: 'POST', body: { email, password } })).accessToken;
const register = (email) => rest('/api/auth/register', { method: 'POST', body: { name: 'Keyboard E2E', email, password: PASSWORD } });

if (!ADMIN_EMAIL || !ADMIN_PASSWORD) {
	console.log('Set ADMIN_EMAIL and ADMIN_PASSWORD');
	process.exit(2);
}

const browser = await chromium.launch();
let admin = null;
let show = null;
try {
	// Two rows of 6 seats; someone else has booked A2
	admin = await tokenOf(ADMIN_EMAIL, ADMIN_PASSWORD);
	const venue = await rest('/api/venues', { method: 'POST', token: admin, body: { name: `E2E Keyboard Venue ${RUN}`, city: 'Pune' } });
	const startTime = new Date(Date.now() + 2 * 24 * 3600 * 1000).toISOString().replace(/\.\d+Z$/, 'Z');
	show = await rest('/api/shows', { method: 'POST', token: admin, body: { title: TITLE, startTime, price: 200, venueId: venue.id } });
	await rest(`/api/shows/${show.id}/seats`, { method: 'POST', token: admin, body: { rows: 2, seatsPerRow: 6 } });
	const a2 = (await rest(`/api/shows/${show.id}/seats`)).find((s) => s.seatNumber === 'A2');
	await register(people.other);
	const other = await tokenOf(people.other, PASSWORD);
	await rest('/api/bookings/hold', { method: 'POST', token: other, body: { showId: show.id, seatIds: [a2.id] } });
	await rest('/api/bookings', { method: 'POST', token: other, body: { showId: show.id, seatIds: [a2.id] } });
	await register(people.me);

	const page = await browser.newPage({ viewport: { width: 1280, height: 860 } });
	page.on('pageerror', (e) => problems.push(`page error: ${e.message}`));
	const focused = () => page.evaluate(() => {
		const el = document.activeElement;
		return { tag: el.tagName, text: el.textContent.trim(), label: el.getAttribute('aria-label') ?? '' };
	});

	// The first Tab lands on the skip link; it moves focus to the page's heading
	await page.goto(`${BASE}/#/`);
	await page.locator('.show-card').first().waitFor();
	await page.keyboard.press('Tab');
	check((await focused()).text === 'Skip to content', 'the first Tab reaches "Skip to content"');
	await page.screenshot({ path: `${OUT}/kb-01-skip-link.png` });
	await page.keyboard.press('Enter');
	check((await focused()).text === 'Now booking', 'the skip link moves focus to the page heading');
	check(page.url().endsWith('#/'), 'the skip link leaves the address alone');

	// Moving to the show page puts focus on its heading and names the tab
	await page.evaluate((id) => { location.hash = `#/show/${id}`; }, show.id);
	await page.locator('.seat').first().waitFor();
	await page.waitForFunction((title) => document.activeElement.textContent.trim() === title, TITLE);
	check(true, 'after moving to the show page, focus is on its heading');
	check((await page.title()) === `${TITLE} · BookMySeat`, `the tab is named after the page (${await page.title()})`);

	// The seat map is one Tab stop; arrow keys move inside it, booked seats included
	for (let i = 0; i < 10 && !(await focused()).label.startsWith('Seat '); i++) await page.keyboard.press('Tab');
	check((await focused()).label.startsWith('Seat A1, available'), `Tab enters the seat map at the first free seat (${(await focused()).label})`);
	await page.keyboard.press('ArrowRight');
	check((await focused()).label.startsWith('Seat A2, booked'), 'the arrow key reaches a booked seat, announced as booked');
	await page.keyboard.press('Space');
	check(await page.locator('.seat.selected').count() === 0, 'a booked seat cannot be picked');
	await page.keyboard.press('ArrowRight');
	await page.keyboard.press('Space');
	check((await focused()).label.startsWith('Seat A3') && await page.locator('[aria-label^="Seat A3"]').getAttribute('aria-pressed') === 'true',
		'Space picks A3 and focus stays on it');
	await page.keyboard.press('ArrowDown');
	await page.keyboard.press('Space');
	check((await focused()).label.startsWith('Seat B3'), 'the down arrow goes to the seat below (B3)');
	await page.keyboard.press('End');
	check((await focused()).label.startsWith('Seat B6'), 'End goes to the end of the row');
	await page.keyboard.press('ArrowLeft');
	await page.keyboard.press('ArrowLeft');
	await page.keyboard.press('ArrowLeft');
	await page.waitForTimeout(11_000); // the seat map refreshes every 10 s
	check((await focused()).label.startsWith('Seat B3'), 'focus is still on B3 after the seat map refreshed');
	await page.screenshot({ path: `${OUT}/kb-02-seat-focus.png` });

	// Tab leaves the seat map for "Hold seats"; logging in in the dialog with the keyboard; focus comes back to the summary
	await page.keyboard.press('Tab');
	check((await focused()).text === 'Hold seats', 'one Tab from the seat map reaches "Hold seats"');
	await page.keyboard.press('Enter');
	await page.locator('#auth-dialog[open]').waitFor();
	check((await focused()).tag === 'INPUT', 'the login dialog puts focus in its form');
	await page.keyboard.type(people.me);
	await page.keyboard.press('Tab');
	await page.keyboard.type(PASSWORD);
	await page.keyboard.press('Enter');
	await page.locator('#timer').waitFor();
	await page.waitForFunction(() => document.activeElement.textContent.trim().startsWith('Confirm booking'));
	check(true, 'after logging in and holding, focus is on "Confirm booking"');
	check((await page.locator('#summary').innerText()).includes('A3, B3'), 'A3 and B3 are held');
	await page.screenshot({ path: `${OUT}/kb-03-held.png` });
	await page.keyboard.press('Enter');
	await page.getByRole('heading', { name: "You're booked!" }).waitFor();
	check((await focused()).text === "You're booked!", 'after booking, focus is on "You\'re booked!"');
} catch (error) {
	problems.push(`step failed: ${error.message.split('\n')[0]}`);
	for (const p of browser.contexts().flatMap((c) => c.pages())) {
		await p.screenshot({ path: `${OUT}/zz-kb-failure.png` }).catch(() => {});
	}
} finally {
	await browser.close();
	if (show) await rest(`/api/shows/${show.id}/cancel`, { method: 'POST', token: admin }).catch(() => {});
}

console.log(`\nTest data: show "${TITLE}", accounts ${Object.values(people).join(', ')}`);
if (problems.length) {
	console.log(`\n${problems.length} problem(s):\n- ${problems.join('\n- ')}`);
	process.exit(1);
}
console.log('\nAll keyboard checks passed');
