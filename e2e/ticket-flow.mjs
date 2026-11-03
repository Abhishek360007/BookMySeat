// End-to-end browser test of e-tickets: a customer books and opens the ticket (QR + code); at the door an admin
// checks it in by its code; a second scan is refused; the ticket then shows "checked in". Fails on any
// Content-Security-Policy violation. Needs ADMIN_EMAIL / ADMIN_PASSWORD (payments off). Run with:
//   docker run --rm -v "${PWD}/e2e:/e2e" -e BASE_URL=http://host.docker.internal:8080 -e ADMIN_EMAIL=... -e ADMIN_PASSWORD=... \
//     mcr.microsoft.com/playwright:v1.64.1-noble sh -c "npm i --no-save --prefix /tmp/pw playwright@1.64.1 \
//       && cp /e2e/ticket-flow.mjs /tmp/pw/ && cd /tmp/pw && node ticket-flow.mjs"
import { chromium } from 'playwright';

const BASE = process.env.BASE_URL || 'http://localhost:8080';
const OUT = process.env.OUT || './screenshots';
const { ADMIN_EMAIL, ADMIN_PASSWORD } = process.env;
const RUN = Date.now();
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

async function newPage(context, who) {
	const page = await context.newPage();
	page.on('pageerror', (e) => problems.push(`${who}: page error: ${e.message}`));
	page.on('console', (m) => { if (m.type() === 'error' && !/status of 40[09]/.test(m.text())) problems.push(`${who}: console error: ${m.text()}`); });
	return page;
}

async function logIn(page, email, password) {
	await page.goto(`${BASE}/`);
	await page.getByRole('button', { name: 'Log in' }).click();
	await page.locator('#auth-form').getByLabel('Email').fill(email);
	await page.locator('#auth-form').getByLabel('Password').fill(password);
	await page.locator('#auth-submit').click();
	await page.locator('#logout-btn:not([hidden])').waitFor();
}

const browser = await chromium.launch();
try {
	// A show starting in 2 hours (doors are open), set up through the API
	const adminToken = (await rest('/api/auth/login', { method: 'POST', body: { email: ADMIN_EMAIL, password: ADMIN_PASSWORD } })).accessToken;
	const venue = await rest('/api/venues', { method: 'POST', token: adminToken, body: { name: `E2E Door ${RUN}`, city: 'Pune' } });
	const show = await rest('/api/shows', { method: 'POST', token: adminToken, body: {
		title: `E2E Ticket Show ${RUN}`, startTime: new Date(Date.now() + 2 * 3600_000).toISOString(), price: 200, venueId: venue.id } });
	await rest(`/api/shows/${show.id}/seats`, { method: 'POST', token: adminToken, body: { rows: 2, seatsPerRow: 6 } });

	const email = `e2e-ticket-${RUN}@example.com`;
	await rest('/api/auth/register', { method: 'POST', body: { name: 'Tina Ticket', email, password: 'E2e-Password-123' } });
	const token = (await rest('/api/auth/login', { method: 'POST', body: { email, password: 'E2e-Password-123' } })).accessToken;
	const seatIds = (await rest(`/api/shows/${show.id}/seats`)).slice(0, 2).map((s) => s.id);
	await rest('/api/bookings/hold', { method: 'POST', token, body: { showId: show.id, seatIds } });
	const booking = await rest('/api/bookings', { method: 'POST', token, body: { showId: show.id, seatIds } });

	// Customer: My bookings -> Ticket
	const csp = [];
	const customerContext = await browser.newContext({ viewport: { width: 420, height: 860 } });
	await customerContext.exposeFunction('reportCsp', (v) => csp.push(v));
	await customerContext.addInitScript(() => document.addEventListener('securitypolicyviolation', (e) => window.reportCsp(`${e.violatedDirective} ${e.blockedURI}`)));
	const customer = await newPage(customerContext, 'customer');
	await logIn(customer, email, 'E2e-Password-123');
	await customer.goto(`${BASE}/#/bookings`);
	await customer.getByRole('heading', { name: 'My bookings' }).waitFor();
	await customer.getByRole('link', { name: 'Ticket', exact: true }).first().click();
	const qr = customer.getByRole('img', { name: `Ticket QR code for booking ${booking.id}` });
	await qr.waitFor();
	check(await qr.evaluate((img) => img.complete && img.naturalWidth > 100), 'the ticket shows its QR code image');
	const code = (await customer.locator('.ticket code').innerText()).trim();
	check(new RegExp(`^T1\\.${booking.id}\\.[A-Za-z0-9_-]{22}$`).test(code), `the ticket shows its signed code (${code})`);
	await customer.screenshot({ path: `${OUT}/t1-ticket.png`, fullPage: true });

	// Door: admin check-in page, code typed in
	const adminContext = await browser.newContext({ viewport: { width: 1100, height: 860 } });
	await adminContext.exposeFunction('reportCsp', (v) => csp.push(v));
	await adminContext.addInitScript(() => document.addEventListener('securitypolicyviolation', (e) => window.reportCsp(`${e.violatedDirective} ${e.blockedURI}`)));
	const door = await newPage(adminContext, 'door');
	await logIn(door, ADMIN_EMAIL, ADMIN_PASSWORD);
	await door.goto(`${BASE}/#/admin/show/${show.id}`);
	await door.getByRole('link', { name: 'Check-in' }).click();
	await door.getByRole('heading', { name: 'Check-in' }).waitFor();
	check((await door.locator('#checkin-show').inputValue()) === String(show.id), 'the check-in page opens on the right show');
	await door.getByLabel('Ticket code').fill(code);
	await door.getByRole('button', { name: 'Check in' }).click();
	await door.locator('.checkin-result.ok').waitFor();
	check((await door.locator('.checkin-result').innerText()).includes('Welcome, Tina Ticket'), 'a valid ticket is let in with the holder\'s name and seats');
	await door.locator('#checkin-count', { hasText: '1 of 1' }).waitFor();
	check(true, 'the counter shows 1 of 1 checked in');
	await door.screenshot({ path: `${OUT}/t2-door-ok.png` });

	await door.waitForTimeout(1600); // the page ignores repeats for 1.5 s
	await door.getByLabel('Ticket code').fill(code);
	await door.getByRole('button', { name: 'Check in' }).click();
	await door.locator('.checkin-result.bad').waitFor();
	check((await door.locator('.checkin-result').innerText()).includes('Already checked in'), 'the same ticket a second time is refused');
	await door.screenshot({ path: `${OUT}/t3-door-again.png` });

	await door.waitForTimeout(1600);
	await door.getByLabel('Ticket code').fill(`T1.${booking.id}.AAAAAAAAAAAAAAAAAAAAAA`);
	await door.getByRole('button', { name: 'Check in' }).click();
	await door.locator('.checkin-result.bad', { hasText: 'not a valid BookMySeat ticket' }).waitFor();
	check(true, 'a forged code is refused');

	// The customer's ticket now says so
	await customer.reload();
	await customer.locator('.ticket .status', { hasText: 'CHECKED IN' }).waitFor();
	check(true, 'the customer\'s ticket shows CHECKED IN');
	check(csp.length === 0, `no Content-Security-Policy violations (${csp.join(', ')})`);
} catch (error) {
	for (const p of browser.contexts().flatMap((c) => c.pages())) {
		await p.screenshot({ path: `${OUT}/zz-ticket-failure-${Math.random().toString(36).slice(2, 6)}.png` }).catch(() => {});
	}
	problems.push(`step failed: ${error.message.split('\n')[0]}`);
} finally {
	await browser.close();
}

if (problems.length) {
	console.log(`\n${problems.length} problem(s):\n- ${problems.join('\n- ')}`);
	process.exit(1);
}
console.log('\nAll ticket browser checks passed');
