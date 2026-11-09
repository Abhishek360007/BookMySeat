// End-to-end browser test of promo codes (payments off): the admin creates a code on the Promo codes page; a customer
// holds seats, tries a code that doesn't exist (told why, next to the field), applies the real one in lower case,
// removes it and applies it again, and books at the discounted price; My bookings and the admin bookings show the
// discount. A second code that runs out while the customer is about to book is refused at confirm, the seats stay
// held and they book at full price. The admin then switches the first code off. Also: what is typed in the code field
// survives the 10-second refresh of the seat map, and the Promo codes page fits a phone.
// Builds its own show through the API, so it needs an admin account (ADMIN_EMAIL / ADMIN_PASSWORD).
// Logs in 5 times (the limit: 5 a minute): clear Redis rate:* keys first after other runs. Run with:
//   docker run --rm -v "${PWD}/e2e:/e2e" -e BASE_URL=http://host.docker.internal:8080 \
//     -e ADMIN_EMAIL=... -e ADMIN_PASSWORD=... \
//     mcr.microsoft.com/playwright:v1.64.1-noble sh -c "npm i --no-save --prefix /tmp/pw playwright@1.64.1 \
//       && cp /e2e/promo-flow.mjs /tmp/pw/ && cd /tmp/pw && node promo-flow.mjs"
import { chromium, devices } from 'playwright';

const BASE = process.env.BASE_URL || 'http://localhost:8080';
const OUT = process.env.OUT || './screenshots';
const { ADMIN_EMAIL, ADMIN_PASSWORD } = process.env;
const RUN = Date.now();
const TAG = String(RUN).slice(-6);
const TITLE = `E2E Promo Show ${RUN}`;
const CODE = `E2E20-${TAG}`; // 20 % off, once
const LAST = `E2ELAST-${TAG}`; // ₹50 off, one use in total
const PASSWORD = 'Promo-E2e-Pw-1';
const people = { customer: `claude-promo-${RUN}@example.com`, rival: `claude-promo-rival-${RUN}@example.com` };
const problems = [];

const check = (ok, message) => {
	console.log(`${ok ? 'PASS' : 'FAIL'}  ${message}`);
	if (!ok) problems.push(message);
};

function watchForErrors(page, who) {
	page.on('pageerror', (e) => problems.push(`${who}: page error: ${e.message}`));
	// A refused code is an expected 422 response; anything else in the console is a problem
	page.on('console', (m) => { if (m.type() === 'error' && !m.text().includes('422')) problems.push(`${who}: console error: ${m.text()}`); });
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

async function logIn(page, email, password) {
	await page.goto(`${BASE}/#/`);
	await page.getByRole('button', { name: 'Log in' }).click();
	await page.locator('#auth-form').getByLabel('Email').fill(email);
	await page.locator('#auth-form').getByLabel('Password').fill(password);
	await page.locator('#auth-submit').click();
	await page.locator('#logout-btn:not([hidden])').waitFor();
}

const announced = (page) => page.locator('#announce').textContent();
const announcedAlert = (page) => page.locator('#announce-alert').textContent();
const confirmButton = (page) => page.getByRole('button', { name: /Confirm booking/ });

if (!ADMIN_EMAIL || !ADMIN_PASSWORD) {
	console.log('Set ADMIN_EMAIL and ADMIN_PASSWORD');
	process.exit(2);
}

const browser = await chromium.launch();
let admin;
let show;
try {
	// A show with 4 seats at ₹300
	admin = await tokenOf(ADMIN_EMAIL, ADMIN_PASSWORD);
	const venue = await rest('/api/venues', { method: 'POST', token: admin, body: { name: `E2E Promo Venue ${RUN}`, city: 'Pune' } });
	const startTime = new Date(Date.now() + 2 * 24 * 3600 * 1000).toISOString().replace(/\.\d+Z$/, 'Z');
	show = await rest('/api/shows', { method: 'POST', token: admin, body: { title: TITLE, startTime, price: 300, venueId: venue.id } });
	await rest(`/api/shows/${show.id}/seats`, { method: 'POST', token: admin, body: { rows: 1, seatsPerRow: 4 } });
	const seats = await rest(`/api/shows/${show.id}/seats`);
	for (const email of Object.values(people)) {
		await rest('/api/auth/register', { method: 'POST', body: { name: 'Promo E2E', email, password: PASSWORD } });
	}

	// The admin creates the first code in the browser
	const adminPage = await browser.newPage({ viewport: { width: 1280, height: 900 } });
	watchForErrors(adminPage, 'admin');
	await logIn(adminPage, ADMIN_EMAIL, ADMIN_PASSWORD);
	await adminPage.goto(`${BASE}/#/admin`);
	await adminPage.getByRole('link', { name: 'Promo codes' }).click();
	await adminPage.getByRole('heading', { name: 'Promo codes', level: 1 }).waitFor();
	const form = adminPage.locator('#new-code');
	await form.getByLabel('Code').fill(CODE.toLowerCase());
	await form.getByLabel('Description').fill('E2E twenty off');
	await form.getByLabel('Percent off').fill('20');
	await form.getByLabel('Uses in total').fill('1');
	check((await adminPage.locator('#code-preview').innerText()).includes('would cost ₹400.00'),
		'the form previews what the code does to a ₹500 booking');
	await form.getByLabel('Discount').selectOption('FLAT');
	check(await form.getByLabel('At most (₹)').isHidden() && await form.getByLabel('Rupees off').isVisible(),
		'a fixed amount has no cap field');
	await form.getByLabel('Discount').selectOption('PERCENT');
	await adminPage.getByRole('button', { name: 'Create code' }).click();
	await adminPage.locator('#promo-list').getByText(CODE).waitFor();
	const row = adminPage.locator('#promo-list tr', { hasText: CODE });
	const rowText = (await row.innerText()).replace(/\s+/g, ' ');
	check(rowText.includes('20% off') && rowText.includes('0 / 1') && rowText.includes('ACTIVE'),
		`the new code is listed in capitals, unused and active (${rowText})`);
	check(await adminPage.evaluate(() => document.activeElement?.dataset.code !== undefined), 'focus moves to the new code');
	await adminPage.screenshot({ path: `${OUT}/promo-01-admin-codes.png`, fullPage: true });

	// The second code, made through the API: one use in total
	await rest('/api/admin/promo-codes', { method: 'POST', token: admin, body: { code: LAST, kind: 'FLAT', amount: 50, maxUses: 1 } });

	// The customer holds two seats and tries a code that doesn't exist
	const page = await browser.newPage({ viewport: { width: 1280, height: 860 } });
	watchForErrors(page, 'customer');
	await logIn(page, people.customer, PASSWORD);
	await page.goto(`${BASE}/#/show/${show.id}`);
	await page.getByRole('button', { name: 'Seat A1, available' }).click();
	await page.getByRole('button', { name: 'Seat A2, available' }).click();
	await page.getByRole('button', { name: 'Hold seats' }).click();
	await page.locator('#timer').waitFor();
	check((await confirmButton(page).innerText()).includes('₹600.00'), 'held: the button shows the full price');

	await page.getByLabel('Promo code').fill('NOSUCHCODE');
	await page.getByRole('button', { name: 'Apply' }).click();
	await page.locator('#promo-error').waitFor();
	check((await page.locator('#promo-error').innerText()) === "This code doesn't exist", 'a code that does not exist is explained next to the field');
	check(await page.getByLabel('Promo code').getAttribute('aria-invalid') === 'true'
		&& await page.getByLabel('Promo code').getAttribute('aria-describedby') === 'promo-error',
		'the field is marked invalid and points at the reason');
	check(await page.evaluate(() => document.activeElement?.id === 'promo-code'), 'focus goes back to the field');
	await page.waitForFunction(() => document.getElementById('announce-alert').textContent.includes("doesn't exist"));
	check((await announcedAlert(page)).includes('Promo code not applied'), 'screen readers hear the refusal at once');
	await page.screenshot({ path: `${OUT}/promo-02-refused.png` });

	// What is typed survives the seat map's refresh (every 10 s), with focus and caret in place
	await page.getByLabel('Promo code').fill('');
	await page.getByLabel('Promo code').pressSequentially(CODE.toLowerCase().slice(0, 5));
	await page.waitForTimeout(11_000);
	check(await page.getByLabel('Promo code').inputValue() === CODE.toLowerCase().slice(0, 5)
		&& await page.evaluate(() => document.activeElement?.id === 'promo-code'),
		'half-typed code and focus survive the 10-second refresh');
	await page.getByLabel('Promo code').pressSequentially(CODE.toLowerCase().slice(5));
	check(await page.getByLabel('Promo code').inputValue() === CODE.toLowerCase(), 'typing goes on at the end after the refresh');

	// Applied in lower case: the new total, announced; removing it brings the full price back
	await page.getByLabel('Promo code').press('Enter');
	await page.locator('#promo-applied').waitFor();
	const applied = (await page.locator('#promo-applied').innerText()).replace(/\s+/g, ' ');
	check(applied.includes(`${CODE} applied`) && applied.includes('₹120.00 off') && applied.includes('E2E twenty off') && applied.includes('was ₹600.00'),
		`the applied code shows its discount (${applied})`);
	check((await confirmButton(page).innerText()).includes('₹480.00'), 'the button shows the discounted price');
	await page.waitForFunction(() => document.getElementById('announce').textContent.includes('applied'));
	check((await announced(page)).includes('New total ₹480.00'), 'screen readers hear the new total');
	check(await page.evaluate(() => document.activeElement?.id === 'promo-remove'), 'focus moves to Remove');
	await page.screenshot({ path: `${OUT}/promo-03-applied.png` });

	await page.getByRole('button', { name: `Remove promo code ${CODE}` }).click();
	await page.getByLabel('Promo code').waitFor();
	check((await confirmButton(page).innerText()).includes('₹600.00'), 'removing the code brings the full price back');
	await page.getByLabel('Promo code').fill(CODE);
	await page.getByRole('button', { name: 'Apply' }).click();
	await page.locator('#promo-applied').waitFor();

	await confirmButton(page).click();
	await page.getByRole('heading', { name: "You're booked!" }).waitFor();
	check((await page.locator('.success').innerText()).includes('₹480.00'), 'booked at the discounted price');

	await page.goto(`${BASE}/#/bookings`);
	await page.getByRole('heading', { name: 'My bookings' }).waitFor();
	check((await page.locator('.bookings').innerText()).includes(`(₹120.00 off with ${CODE})`), 'My bookings shows the discount and the code');

	// The last use of the second code goes to someone else while the customer is about to book
	await page.goto(`${BASE}/#/show/${show.id}`);
	await page.getByRole('button', { name: 'Seat A3, available' }).click();
	await page.getByRole('button', { name: 'Hold seats' }).click();
	await page.getByLabel('Promo code').fill(LAST);
	await page.getByRole('button', { name: 'Apply' }).click();
	await page.locator('#promo-applied').waitFor();
	check((await confirmButton(page).innerText()).includes('₹250.00'), 'the second code applies: ₹250.00');

	const rival = await tokenOf(people.rival, PASSWORD);
	const a4 = { showId: show.id, seatIds: [seats[3].id] };
	await rest('/api/bookings/hold', { method: 'POST', token: rival, body: a4 });
	await rest('/api/bookings', { method: 'POST', token: rival, body: { ...a4, promoCode: LAST } });

	await confirmButton(page).click();
	await page.locator('#promo-error').waitFor();
	check((await page.locator('#promo-error').innerText()) === 'This code has been fully used',
		'a code used up meanwhile is refused at confirm, with the reason');
	check((await confirmButton(page).innerText()).includes('₹300.00') && await page.locator('#timer').isVisible(),
		'the seats stay held, at full price');
	await page.screenshot({ path: `${OUT}/promo-04-used-up.png` });
	await confirmButton(page).click();
	await page.getByRole('heading', { name: "You're booked!" }).waitFor();
	check((await page.locator('.success').innerText()).includes('₹300.00'), 'they book at full price instead');

	// The admin sees the uses and the discounts, and switches the first code off
	await adminPage.reload();
	await adminPage.locator('#promo-list').getByText(CODE).waitFor();
	check((await row.innerText()).includes('1 / 1'), 'the first code shows 1 of 1 used');
	await adminPage.getByRole('button', { name: `Switch off ${CODE}` }).click();
	await adminPage.getByRole('button', { name: `Switch on ${CODE}` }).waitFor();
	check((await row.innerText()).includes('OFF'), 'switched off');
	check(await adminPage.evaluate(() => document.activeElement?.getAttribute('aria-label')?.startsWith('Switch on')),
		'focus stays on the toggled button');
	await adminPage.goto(`${BASE}/#/admin/show/${show.id}`);
	await adminPage.locator('#admin-bookings table').waitFor();
	const bookingsText = await adminPage.locator('#admin-bookings').innerText();
	check(bookingsText.includes(`₹120.00 off · ${CODE}`) && bookingsText.includes(`₹50.00 off · ${LAST}`),
		'the admin bookings show each discount and code');

	// The Promo codes page on a phone
	const phoneContext = await browser.newContext({ ...devices['iPhone 13'] });
	const phone = await phoneContext.newPage();
	watchForErrors(phone, 'phone');
	await logIn(phone, ADMIN_EMAIL, ADMIN_PASSWORD);
	await phone.goto(`${BASE}/#/admin/promo-codes`);
	await phone.locator('#new-code').waitFor();
	check(!(await phone.evaluate(() => document.documentElement.scrollWidth > window.innerWidth)),
		'phone: the Promo codes page has no horizontal page scroll');
	await phone.screenshot({ path: `${OUT}/promo-05-phone.png`, fullPage: true });
	await phoneContext.close();
} catch (error) {
	for (const p of browser.contexts().flatMap((c) => c.pages())) {
		await p.screenshot({ path: `${OUT}/zz-failure-${Math.random().toString(36).slice(2, 6)}.png` }).catch(() => {});
		const toasts = await p.locator('.toast').allInnerTexts().catch(() => []);
		if (toasts.length) problems.push(`toasts on ${p.url()}: ${toasts.join(' | ')}`);
	}
	problems.push(`step failed: ${error.message.split('\n')[0]}`);
} finally {
	await browser.close();
	if (show) await rest(`/api/shows/${show.id}/cancel`, { method: 'POST', token: admin }).catch(() => {}); // gone from listings
}

console.log(`\nTest data: show "${TITLE}", codes ${CODE} and ${LAST}, accounts ${Object.values(people).join(', ')}`);
if (problems.length) {
	console.log(`\n${problems.length} problem(s):\n- ${problems.join('\n- ')}`);
	process.exit(1);
}
console.log('\nAll promo code browser checks passed');
