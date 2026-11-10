// End-to-end browser test of reviews (payments off): a customer books a show that starts a minute later, is told
// to wait until it has started, then rates it from My bookings (a missing rating is explained, the stars work with
// the keyboard), sees the review and the average on the show's page, and edits it. A second customer's spam drags
// the average down until an admin hides it on the admin Reviews page; deleting the review empties the list. Also:
// the phone layout and no Content-Security-Policy violations.
// Builds its own shows through the API, so it needs an admin account (ADMIN_EMAIL / ADMIN_PASSWORD).
// Logs in 5 times (the limit per minute): clear Redis rate:* keys first after other runs. Waits about a minute for
// the show to start. Run with:
//   docker run --rm -v "${PWD}/e2e:/e2e" -e BASE_URL=http://host.docker.internal:8080 \
//     -e ADMIN_EMAIL=... -e ADMIN_PASSWORD=... \
//     mcr.microsoft.com/playwright:v1.64.1-noble sh -c "npm i --no-save --prefix /tmp/pw playwright@1.64.1 \
//       && cp /e2e/review-flow.mjs /tmp/pw/ && cd /tmp/pw && node review-flow.mjs"
import { chromium } from 'playwright';

const BASE = process.env.BASE_URL || 'http://localhost:8080';
const OUT = process.env.OUT || './screenshots';
const { ADMIN_EMAIL, ADMIN_PASSWORD } = process.env;
const RUN = Date.now();
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
	page.on('dialog', (d) => d.accept()); // "Delete your review?"
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
	// A show starting in 50 seconds (shows must start in the future to be created, and bookings close at the start)
	const adminToken = (await rest('/api/auth/login', { method: 'POST', body: { email: ADMIN_EMAIL, password: ADMIN_PASSWORD } })).accessToken;
	const venue = await rest('/api/venues', { method: 'POST', token: adminToken, body: { name: `E2E Review Venue ${RUN}`, city: 'Pune' } });
	const title = `E2E Review Show ${RUN}`;
	const show = await rest('/api/shows', { method: 'POST', token: adminToken, body: {
		title, startTime: new Date(Date.now() + 50_000).toISOString(), price: 200, venueId: venue.id } });
	await rest(`/api/shows/${show.id}/seats`, { method: 'POST', token: adminToken, body: { rows: 1, seatsPerRow: 6 } });
	const seatIds = (await rest(`/api/shows/${show.id}/seats`)).map((s) => s.id);

	const customer = async (name, email, seatId) => {
		await rest('/api/auth/register', { method: 'POST', body: { name, email, password: PASSWORD } });
		const token = (await rest('/api/auth/login', { method: 'POST', body: { email, password: PASSWORD } })).accessToken;
		await rest('/api/bookings/hold', { method: 'POST', token, body: { showId: show.id, seatIds: [seatId] } });
		await rest('/api/bookings', { method: 'POST', token, body: { showId: show.id, seatIds: [seatId] } });
		return token;
	};
	const tinaEmail = `e2e-review-tina-${RUN}@example.com`;
	await customer('Tina Reviewer', tinaEmail, seatIds[0]);
	const spammer = await customer('Sam Spam', `e2e-review-sam-${RUN}@example.com`, seatIds[1]);

	const csp = [];
	const tinaContext = await browser.newContext({ viewport: { width: 1100, height: 900 } });
	const tina = await newPage(tinaContext, 'customer', csp);
	await logIn(tina, tinaEmail, PASSWORD);

	// Before the show: told to wait
	await tina.goto(`${BASE}/#/review/${show.id}`);
	await tina.getByText('You can rate this show once it has started').waitFor();
	check(true, 'before the show, the review page says to wait until it has started');

	const wait = new Date(show.startTime) - Date.now() + 2000;
	if (wait > 0) await tina.waitForTimeout(wait);

	// After the show: My bookings offers a rating instead of a cancel button
	await tina.goto(`${BASE}/#/bookings`);
	await tina.getByRole('heading', { name: 'My bookings' }).waitFor();
	check(await tina.getByRole('button', { name: 'Cancel' }).count() === 0, 'a show that has started has no Cancel button any more');
	await tina.getByRole('link', { name: `Rate ${title}` }).click();
	await tina.getByRole('heading', { name: `Rate ${title}`, exact: true }).waitFor();

	await tina.getByRole('button', { name: 'Post review' }).click();
	await tina.locator('#review-error', { hasText: 'Pick a rating from 1 to 5 stars' }).waitFor();
	check(await tina.evaluate(() => document.activeElement?.id) === 'star-1', 'posting without a rating is explained and focus goes to the stars');

	// Keyboard: arrow keys move through the stars
	await tina.keyboard.press('ArrowRight');
	await tina.keyboard.press('ArrowRight');
	await tina.keyboard.press('ArrowRight');
	check((await tina.locator('#rating-words').innerText()) === 'Very good', 'arrow keys pick 4 stars ("Very good")');
	check(await tina.locator('.star-input label.on').count() === 4, 'four stars are lit');
	await tina.getByLabel('Your review').fill('Great sound.\nComfy seats, though the popcorn ran out.');
	await tina.screenshot({ path: `${OUT}/r1-review-form.png`, fullPage: true });
	await tina.getByRole('button', { name: 'Post review' }).click();
	await tina.getByRole('button', { name: 'Save changes' }).waitFor();
	check(await tina.getByRole('button', { name: 'Delete my review' }).isVisible(), 'after posting, the review can be changed or deleted');
	await tina.locator('#announce', { hasText: 'Thanks! Your review is up.' }).waitFor();
	check(true, 'posting is announced');

	// The show's page: the review and the average
	await tina.goto(`${BASE}/#/show/${show.id}`);
	const review = tina.locator('.review').first();
	await review.waitFor();
	check((await review.innerText()).includes('Tina R.'), 'the review is shown with the first name and an initial');
	check((await review.locator('.review-text').innerText()).includes('Comfy seats, though the popcorn ran out.'), 'the text keeps its line break and words');
	check((await tina.locator('p .rating').innerText()).includes('4.0'), 'the show shows its average, 4.0');
	check((await tina.locator('p .rating').innerText()).includes('1 review'), '...from 1 review');

	// A second customer's 1-star spam drags it down
	await rest(`/api/shows/${show.id}/reviews/me`, { method: 'PUT', token: spammer, body: { rating: 1, text: 'Cheap tickets at example.com' } });
	await tina.reload();
	await tina.locator('.review').nth(1).waitFor();
	check((await tina.locator('p .rating').innerText()).includes('2.5'), 'with a 1-star review the average is 2.5');
	await tina.locator('#review-sort').selectOption('highest');
	await tina.locator('.review').first().filter({ hasText: 'Tina R.' }).waitFor();
	check(true, '"Highest rated first" puts the 4-star review first');

	// The admin hides it
	const adminContext = await browser.newContext({ viewport: { width: 1280, height: 900 } });
	const admin = await newPage(adminContext, 'admin', csp);
	await logIn(admin, ADMIN_EMAIL, ADMIN_PASSWORD);
	await admin.goto(`${BASE}/#/admin`);
	await admin.getByRole('link', { name: 'Reviews' }).click();
	await admin.getByRole('heading', { name: 'Reviews' }).waitFor();
	const hide = admin.getByRole('button', { name: `Hide: review by Sam Spam of ${title}` });
	await hide.click();
	await admin.getByRole('button', { name: `Show again: review by Sam Spam of ${title}` }).waitFor();
	check(await admin.evaluate(() => document.activeElement?.textContent?.trim()) === 'Show again', 'after hiding, focus stays on that review\'s button');
	await admin.screenshot({ path: `${OUT}/r2-admin-reviews.png`, fullPage: true });
	await admin.getByRole('tab', { name: 'Hidden' }).click();
	// Wait for the Hidden list itself: no visible review left on the page
	await admin.waitForFunction(() => location.hash.includes('show=HIDDEN')
		&& document.querySelector('#review-admin [data-review]') && !document.querySelector('#review-admin .status.CONFIRMED'));
	// (other runs may have left hidden reviews of their own shows)
	const hiddenRows = admin.locator('tbody tr', { hasText: title });
	check(await hiddenRows.count() === 1 && (await hiddenRows.innerText()).includes('Sam Spam'), 'the Hidden tab lists the hidden review');

	await tina.reload();
	await tina.locator('.review').first().waitFor();
	check(await tina.locator('.review').count() === 1 && (await tina.locator('p .rating').innerText()).includes('4.0'),
		'once hidden, the spam is gone from the list and the average is back to 4.0');

	// Editing, then deleting
	await tina.getByRole('link', { name: 'Rate this show' }).click();
	await tina.getByRole('heading', { name: `Your review of ${title}` }).waitFor();
	check(await tina.locator('#star-4').isChecked(), 'the review page opens with the rating given');
	await tina.locator('label[for="star-5"]').click();
	await tina.getByRole('button', { name: 'Save changes' }).click();
	await tina.locator('#announce', { hasText: 'Your review is updated.' }).waitFor();
	const mine = await rest('/api/shows/' + show.id);
	check(mine.ratingAverage === 5 && mine.ratingCount === 1, 'the edit changes the average to 5.0');

	await tina.setViewportSize({ width: 360, height: 800 });
	check(await tina.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth), 'the review page fits a phone without sideways scrolling');
	await tina.screenshot({ path: `${OUT}/r3-review-phone.png`, fullPage: true });

	await tina.getByRole('button', { name: 'Delete my review' }).click();
	await tina.getByText('No reviews yet.').waitFor();
	check(true, 'deleting the review leaves "No reviews yet." on the show\'s page');
	await tina.goto(`${BASE}/#/bookings`);
	await tina.getByRole('link', { name: `Rate ${title}` }).waitFor();
	check(true, 'My bookings offers "Rate this show" again');

	check(csp.length === 0, `no Content-Security-Policy violations (${csp.join(', ')})`);
} catch (error) {
	for (const p of browser.contexts().flatMap((c) => c.pages())) {
		await p.screenshot({ path: `${OUT}/zz-review-failure-${Math.random().toString(36).slice(2, 6)}.png` }).catch(() => {});
	}
	problems.push(`step failed: ${error.message.split('\n')[0]}`);
} finally {
	await browser.close();
}

if (problems.length) {
	console.log(`\n${problems.length} problem(s):\n- ${problems.join('\n- ')}`);
	process.exit(1);
}
console.log('\nAll review browser checks passed');
