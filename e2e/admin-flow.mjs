// End-to-end browser test of the admin pages (payments off): a customer can't open them; an admin creates a
// venue + show + seats in one form, sees a customer's booking arrive, reschedules the show and cancels it.
// With MAILPIT_URL set it also checks the customer's "new time" and "show cancelled" emails.
// Needs an admin account (ADMIN_EMAIL / ADMIN_PASSWORD, as given to the app) and the demo user. Run with:
//   docker run --rm -v "${PWD}/e2e:/e2e" -e BASE_URL=http://host.docker.internal:8080 \
//     -e ADMIN_EMAIL=... -e ADMIN_PASSWORD=... [-e MAILPIT_URL=http://host.docker.internal:18025] \
//     mcr.microsoft.com/playwright:v1.64.1-noble sh -c "npm i --no-save --prefix /tmp/pw playwright@1.64.1 \
//       && cp /e2e/admin-flow.mjs /tmp/pw/ && cd /tmp/pw && node admin-flow.mjs"
import { chromium, devices } from 'playwright';

const BASE = process.env.BASE_URL || 'http://localhost:8080';
const OUT = process.env.OUT || './screenshots';
const MAILPIT = process.env.MAILPIT_URL;
const { ADMIN_EMAIL, ADMIN_PASSWORD } = process.env;
const RUN = Date.now();
const TITLE = `E2E Admin Show ${RUN}`;
const problems = [];

const check = (ok, message) => {
	console.log(`${ok ? 'PASS' : 'FAIL'}  ${message}`);
	if (!ok) problems.push(message);
};

function watchForErrors(page, who) {
	page.on('pageerror', (e) => problems.push(`${who}: page error: ${e.message}`));
	page.on('console', (m) => { if (m.type() === 'error' && !m.text().includes('403')) problems.push(`${who}: console error: ${m.text()}`); });
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

async function logIn(page, email, password) {
	await page.getByRole('button', { name: 'Log in' }).click();
	await page.getByLabel('Email').fill(email);
	await page.getByLabel('Password').fill(password);
	await page.locator('#auth-submit').click();
	await page.locator('#logout-btn:not([hidden])').waitFor();
}

async function mailTo(email, subjectPart) {
	for (let i = 0; i < 40; i++) {
		const list = await (await fetch(`${MAILPIT}/api/v1/search?query=${encodeURIComponent(`to:${email}`)}`)).json();
		const hit = list.messages?.find((m) => m.Subject.includes(subjectPart));
		if (hit) return hit;
		await new Promise((r) => setTimeout(r, 250));
	}
	return null;
}

if (!ADMIN_EMAIL || !ADMIN_PASSWORD) {
	console.log('Set ADMIN_EMAIL and ADMIN_PASSWORD');
	process.exit(2);
}

const browser = await chromium.launch();
try {
	// A customer sees no Admin link and can't use the pages
	const customer = await browser.newPage({ viewport: { width: 1280, height: 860 } });
	watchForErrors(customer, 'customer');
	await customer.goto(`${BASE}/`);
	await customer.getByRole('button', { name: 'Log in' }).click();
	await customer.getByRole('button', { name: 'Use the demo account' }).click();
	await customer.locator('#logout-btn:not([hidden])').waitFor();
	check(await customer.getByRole('link', { name: 'Admin' }).isHidden(), 'customers get no Admin link');
	await customer.goto(`${BASE}/#/admin`);
	await customer.getByRole('heading', { name: 'Admins only' }).waitFor();
	check(true, 'a customer opening #/admin is turned away');
	await customer.close();

	// Admin: dashboard
	const admin = await browser.newPage({ viewport: { width: 1280, height: 900 } });
	watchForErrors(admin, 'admin');
	await admin.goto(`${BASE}/`);
	await logIn(admin, ADMIN_EMAIL, ADMIN_PASSWORD);
	await admin.getByRole('link', { name: 'Admin' }).click();
	await admin.getByRole('heading', { name: 'Admin', exact: true }).waitFor();
	await admin.locator('.kpi').first().waitFor();
	check((await admin.locator('.kpi').count()) === 4, 'dashboard shows the 4 headline numbers');
	await admin.screenshot({ path: `${OUT}/a1-dashboard.png` });

	// New venue + show + seats in one form
	await admin.getByRole('link', { name: 'New show' }).click();
	const form = admin.locator('#new-show'); // scoped: the (closed) login dialog has a "Name" field too
	await form.getByLabel('Title').fill(TITLE);
	await form.getByLabel('Venue', { exact: true }).selectOption('new');
	await form.getByLabel('Name').fill(`E2E Hall ${RUN}`);
	await form.getByLabel('City').fill('Pune');
	await form.getByLabel('Rows (A–Z)').fill('3');
	await form.getByLabel('Seats per row').fill('8');
	check((await admin.locator('#seat-preview').innerText()).startsWith('24 seats: A1 to C8'), 'the form previews the seat map size');
	await admin.screenshot({ path: `${OUT}/a2-new-show.png` });
	await admin.getByRole('button', { name: 'Create show' }).click();
	await admin.waitForURL(/#\/admin\/show\/\d+$/);
	const showId = Number(admin.url().match(/show\/(\d+)$/)[1]);
	await admin.locator('#admin-seatmap .seat').first().waitFor();
	check((await admin.locator('#admin-seatmap .seat').count()) === 24, 'the new show has 24 seats');

	// A customer books 2 seats (through the API, like the web app does)
	const email = `e2e-admin-${RUN}@example.com`;
	await rest('/api/auth/register', { method: 'POST', body: { name: 'E2E Customer', email, password: 'E2e-Password-123' } });
	const token = (await rest('/api/auth/login', { method: 'POST', body: { email, password: 'E2e-Password-123' } })).accessToken;
	const seats = (await rest(`/api/shows/${showId}/seats`)).slice(0, 2).map((s) => s.id);
	await rest('/api/bookings/hold', { method: 'POST', token, body: { showId, seatIds: seats } });
	await rest('/api/bookings', { method: 'POST', token, body: { showId, seatIds: seats } });

	await admin.reload();
	await admin.locator('#admin-bookings table').waitFor();
	check((await admin.locator('#admin-bookings').innerText()).includes(email), 'the booking appears in the show\'s bookings table');
	check((await admin.locator('#show-kpis').innerText()).includes('2/24'), 'seats sold shows 2/24');
	check(await admin.locator('#edit-venue').isDisabled(), 'the venue is locked once the show has bookings');
	await admin.screenshot({ path: `${OUT}/a3-show.png`, fullPage: true });

	// Reschedule: one hour later and a new price
	const startInput = admin.locator('#edit-show').getByLabel('Starts (India time)');
	const current = await startInput.inputValue(); // e.g. 2026-11-03T19:00, India time
	check(current.endsWith('T19:00'), `the form shows the default 7 pm India time (${current})`);
	const later = new Date(new Date(`${current}:00Z`).getTime() + 3600_000).toISOString().slice(0, 16); // same wall-clock arithmetic
	await startInput.fill(later);
	await admin.locator('#edit-show').getByLabel('Price per seat (₹)').fill('300');
	await admin.getByRole('button', { name: 'Save changes' }).click();
	await admin.locator('.toast', { hasText: 'customer(s) were emailed the new time' }).waitFor();
	check(true, 'saving a new time tells the admin the customers were emailed');
	if (MAILPIT) check(Boolean(await mailTo(email, 'New time for')), 'the customer got the "new time" email');

	// Back on the dashboard: the show's row has the sale
	await admin.goto(`${BASE}/#/admin?when=ALL&q=${encodeURIComponent(String(RUN))}`);
	const row = admin.locator('tr', { hasText: TITLE });
	await row.waitFor();
	const rowText = await row.innerText();
	check(rowText.includes('2/24') && rowText.includes('₹500.00'), 'the dashboard row shows 2/24 sold and ₹500.00 (bookings keep their price)');

	// Cancel the show
	await row.getByRole('link', { name: TITLE }).click();
	await admin.getByRole('button', { name: 'Cancel this show' }).waitFor();
	admin.once('dialog', (dialog) => dialog.accept());
	await admin.getByRole('button', { name: 'Cancel this show' }).click();
	await admin.locator('h1 .status.CANCELLED').waitFor();
	check(await admin.getByRole('button', { name: 'Cancel this show' }).isHidden(), 'a cancelled show can no longer be edited or cancelled');
	await admin.screenshot({ path: `${OUT}/a4-cancelled.png`, fullPage: true });

	const mine = await rest('/api/bookings/me', { token });
	check(mine.every((b) => b.status === 'CANCELLED'), 'the customer\'s booking is cancelled');
	if (MAILPIT) check(Boolean(await mailTo(email, 'Show cancelled')), 'the customer got the "show cancelled" email');
	const listed = await rest('/api/shows');
	check(!listed.some((s) => s.id === showId), 'the cancelled show is gone from the public listing');

	const visitor = await browser.newPage();
	watchForErrors(visitor, 'visitor');
	await visitor.goto(`${BASE}/#/show/${showId}`);
	await visitor.getByText('This show has been cancelled').waitFor();
	check(true, 'a customer opening the cancelled show sees a notice instead of a seat map');
	await visitor.close();

	// Phone-sized dashboard
	const phone = await browser.newPage({ ...devices['iPhone 13'] });
	watchForErrors(phone, 'phone');
	await phone.goto(`${BASE}/`);
	await logIn(phone, ADMIN_EMAIL, ADMIN_PASSWORD);
	await phone.goto(`${BASE}/#/admin`);
	await phone.locator('.kpi').first().waitFor();
	check(!(await phone.evaluate(() => document.documentElement.scrollWidth > window.innerWidth)), 'admin dashboard has no horizontal page scroll on a phone');
	await phone.screenshot({ path: `${OUT}/a5-phone.png`, fullPage: true });
} catch (error) {
	for (const p of browser.contexts().flatMap((c) => c.pages())) {
		await p.screenshot({ path: `${OUT}/zz-admin-failure-${Math.random().toString(36).slice(2, 6)}.png` }).catch(() => {});
		const toasts = await p.locator('.toast').allInnerTexts().catch(() => []);
		if (toasts.length) problems.push(`toasts on ${p.url()}: ${toasts.join(' | ')}`);
	}
	problems.push(`step failed: ${error.message.split('\n')[0]}`);
} finally {
	await browser.close();
}

if (problems.length) {
	console.log(`\n${problems.length} problem(s):\n- ${problems.join('\n- ')}`);
	process.exit(1);
}
console.log(`\nAll admin browser checks passed (show "${TITLE}")`);
