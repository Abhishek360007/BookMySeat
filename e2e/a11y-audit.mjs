// Accessibility audit of the web app with axe-core (WCAG 2.2 A and AA rules plus axe's best practices): every page,
// on a desktop and a phone screen, in the light and the dark theme. Fails on any violation and prints each one with
// the elements it found it on.
// Builds its own data through the API (a show, a sold-out show, one that starts 45 s into the run, bookings and a
// review), so it needs an admin account
// (ADMIN_EMAIL / ADMIN_PASSWORD). Logs in 3 times. Run with:
//   docker run --rm -v "${PWD}/e2e:/e2e" -e BASE_URL=http://host.docker.internal:8080 -e ADMIN_EMAIL=... -e ADMIN_PASSWORD=... \
//     mcr.microsoft.com/playwright:v1.64.1-noble sh -c "npm i --no-save --prefix /tmp/pw playwright@1.64.1 \
//       @axe-core/playwright@4.10.2 && cp /e2e/a11y-audit.mjs /tmp/pw/ && cd /tmp/pw && node a11y-audit.mjs"
import { chromium, devices } from 'playwright';
import { AxeBuilder } from '@axe-core/playwright';

const BASE = process.env.BASE_URL || 'http://localhost:8080';
const OUT = process.env.OUT || './screenshots';
const { ADMIN_EMAIL, ADMIN_PASSWORD } = process.env;
const RUN = Date.now();
const PASSWORD = 'A11y-Audit-Pw-1';
const customer = `claude-a11y-${RUN}@example.com`;
const TAGS = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22a', 'wcag22aa', 'best-practice'];
const SCREENS = {
	desktop: { viewport: { width: 1280, height: 860 } },
	phone: { viewport: devices['iPhone 13'].viewport },
};
const found = new Map(); // rule id -> { impact, help, url, where: Set }
let checked = 0;

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

async function newShow(admin, venueId, title, rows, seatsPerRow, days) {
	const startTime = new Date(Date.now() + days * 24 * 3600 * 1000).toISOString().replace(/\.\d+Z$/, 'Z');
	const show = await rest('/api/shows', { method: 'POST', token: admin, body: { title, startTime, price: 250, venueId } });
	await rest(`/api/shows/${show.id}/seats`, { method: 'POST', token: admin, body: { rows, seatsPerRow } });
	return show;
}

/** A show starting `seconds` from now: booked now, reviewable once it has started. */
async function showStartingIn(admin, venueId, title, seconds) {
	const startTime = new Date(Date.now() + seconds * 1000).toISOString().replace(/\.\d+Z$/, 'Z');
	const show = await rest('/api/shows', { method: 'POST', token: admin, body: { title, startTime, price: 250, venueId } });
	await rest(`/api/shows/${show.id}/seats`, { method: 'POST', token: admin, body: { rows: 1, seatsPerRow: 4 } });
	return show;
}

async function untilStarted(show) {
	const wait = new Date(show.startTime) - Date.now() + 1500;
	if (wait > 0) await new Promise((resolve) => setTimeout(resolve, wait));
}

async function book(token, showId, seatIds) {
	const body = { showId, seatIds };
	await rest('/api/bookings/hold', { method: 'POST', token, body });
	return rest('/api/bookings', { method: 'POST', token, body });
}

/** Runs axe on the page as it is now, in every screen size and theme. `ready` waits for what the page should show. */
async function audit(page, name, ready) {
	for (const [screen, { viewport }] of Object.entries(SCREENS)) {
		await page.setViewportSize(viewport);
		for (const colorScheme of ['light', 'dark']) {
			await page.emulateMedia({ colorScheme, reducedMotion: 'reduce' });
			if (ready) await ready();
			await page.waitForTimeout(300); // let colour transitions finish: axe measures the colours of this moment
			const result = await new AxeBuilder({ page }).withTags(TAGS).analyze();
			checked++;
			const where = `${name} (${screen}, ${colorScheme})`;
			for (const v of result.violations) {
				const entry = found.get(v.id) ?? { impact: v.impact, help: v.help, url: v.helpUrl, where: new Map() };
				entry.where.set(where, v.nodes.map((n) => n.target.join(' ')).slice(0, 4));
				found.set(v.id, entry);
			}
			console.log(`${result.violations.length ? 'FAIL' : 'PASS'}  ${where}${result.violations.length
				? `: ${result.violations.map((v) => v.id).join(', ')}` : ''}`);
		}
	}
	// Reflow (WCAG 1.4.10): at 320 px wide nothing makes the page scroll sideways (the seat map may scroll on its own)
	await page.setViewportSize({ width: 320, height: 640 });
	await page.waitForTimeout(300);
	const wide = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
	checked++;
	console.log(`${wide > 0 ? 'FAIL' : 'PASS'}  ${name} (320 px wide)${wide > 0 ? `: ${wide}px too wide` : ''}`);
	if (wide > 0) {
		const entry = found.get('reflow-320px') ?? { impact: 'serious', help: 'Content must fit 320 CSS px without scrolling sideways',
			url: 'https://www.w3.org/WAI/WCAG22/Understanding/reflow.html', where: new Map() };
		entry.where.set(name, [`${wide}px too wide`]);
		found.set('reflow-320px', entry);
	}
	await page.setViewportSize(SCREENS.desktop.viewport);
	await page.emulateMedia({ colorScheme: 'light' });
	await page.screenshot({ path: `${OUT}/a11y-${name.replace(/[^a-z0-9]+/gi, '-').toLowerCase()}.png` });
}

async function open(page, hash, waitFor) {
	await page.goto(`${BASE}/${hash}`);
	await waitFor();
}

if (!ADMIN_EMAIL || !ADMIN_PASSWORD) {
	console.log('Set ADMIN_EMAIL and ADMIN_PASSWORD');
	process.exit(2);
}

const browser = await chromium.launch();
let failed = null;
let adminToken = null;
const shows = [];
try {
	adminToken = await tokenOf(ADMIN_EMAIL, ADMIN_PASSWORD);
	const venue = await rest('/api/venues', { method: 'POST', token: adminToken, body: { name: `A11y Audit Venue ${RUN}`, city: 'Pune' } });
	const show = await newShow(adminToken, venue.id, `A11y Audit Show ${RUN}`, 3, 8, 3);
	const soldOut = await newShow(adminToken, venue.id, `A11y Audit Sold Out ${RUN}`, 1, 2, 3);
	shows.push(show, soldOut);
	await rest('/api/auth/register', { method: 'POST', body: { name: 'A11y Audit', email: customer, password: PASSWORD } });
	const user = await tokenOf(customer, PASSWORD);
	const seats = await rest(`/api/shows/${show.id}/seats`);
	const booking = await book(user, show.id, [seats[0].id, seats[1].id]);
	await book(user, soldOut.id, (await rest(`/api/shows/${soldOut.id}/seats`)).map((s) => s.id));
	const started = await showStartingIn(adminToken, venue.id, `A11y Audit Reviewed ${RUN}`, 45);
	shows.push(started);
	await book(user, started.id, [(await rest(`/api/shows/${started.id}/seats`))[0].id]);

	const context = await browser.newContext();
	const page = await context.newPage();
	page.on('pageerror', (e) => { failed ??= `page error: ${e.message}`; });

	// Logged out
	await open(page, '#/', () => page.locator('.show-card').first().waitFor());
	await audit(page, 'shows');
	await open(page, `#/show/${show.id}`, () => page.locator('.seat').first().waitFor());
	await page.locator('.seat:not([aria-disabled])').nth(3).click(); // a seat selected, to see that state too
	await audit(page, 'seat map');
	await open(page, `#/show/${soldOut.id}`, () => page.getByRole('button', { name: 'Join waitlist' }).waitFor());
	await audit(page, 'sold-out show');
	await page.getByRole('button', { name: 'Join waitlist' }).click();
	await audit(page, 'log-in dialog', () => page.locator('#auth-dialog[open]').waitFor());
	await page.locator('#auth-toggle').click();
	await audit(page, 'register dialog');
	await page.locator('#auth-cancel').click();
	await open(page, '#/forgot', () => page.locator('#forgot-form').waitFor());
	await audit(page, 'forgot password');
	await open(page, '#/reset/not-a-real-token', () => page.locator('#reset-form').waitFor());
	await audit(page, 'reset password');

	// Logged in as the customer
	await open(page, '#/', () => page.locator('.show-card').first().waitFor());
	await page.getByRole('button', { name: 'Log in' }).click();
	await page.locator('#auth-form').getByLabel('Email').fill(customer);
	await page.locator('#auth-form').getByLabel('Password').fill(PASSWORD);
	await page.locator('#auth-submit').click();
	await page.locator('#logout-btn:not([hidden])').waitFor();
	await audit(page, 'shows, logged in (email not confirmed)');
	await page.goto(`${BASE}/#/show/${show.id}`);
	await page.locator('.seat').first().waitFor();
	await page.locator('.seat:not([aria-disabled])').first().click();
	await page.getByRole('button', { name: 'Hold seats' }).click();
	await audit(page, 'held seats', () => page.locator('#timer').waitFor());
	await page.getByLabel('Promo code').fill('NOSUCHCODE');
	await page.getByRole('button', { name: 'Apply' }).click();
	await audit(page, 'held seats, promo code refused', () => page.locator('#promo-error').waitFor());
	await page.getByRole('button', { name: /Confirm booking/ }).click();
	await audit(page, 'booked', () => page.getByRole('heading', { name: "You're booked!" }).waitFor());
	await page.goto(`${BASE}/#/show/999999`);
	await audit(page, 'error page', () => page.getByRole('heading', { name: 'Something went wrong' }).waitFor());
	await page.goto(`${BASE}/#/show/${soldOut.id}`);
	await page.getByRole('button', { name: 'Join waitlist' }).click();
	await audit(page, 'on the waitlist', () => page.locator('#waitlist-status').waitFor());
	await page.goto(`${BASE}/#/review/${show.id}`);
	await audit(page, 'review before the show', () => page.getByText('once your screening has started').waitFor());
	await untilStarted(started);
	await rest(`/api/shows/${started.id}/reviews/me`, { method: 'PUT', token: user, body: { rating: 4, text: 'Great sound.\nComfy seats.' } });
	await page.goto(`${BASE}/#/review/${started.id}`);
	await audit(page, 'review form', () => page.locator('#review-delete:not([hidden])').waitFor());
	await page.goto(`${BASE}/#/show/${started.id}`);
	await audit(page, 'show with reviews', () => page.locator('.review').first().waitFor());
	await page.goto(`${BASE}/#/film/${show.filmId}`);
	await audit(page, 'film with showtimes', () => page.locator('.slot').first().waitFor());
	// Followed: the toggle pressed, and the film listed in My bookings (audited below)
	await page.getByRole('button', { name: 'Follow this film' }).click();
	await audit(page, 'film, followed', () => page.locator('#follow-btn[aria-pressed="true"]').waitFor());
	await page.goto(`${BASE}/#/film/${started.filmId}`);
	await audit(page, 'film with reviews', () => page.locator('.review').first().waitFor());
	await page.goto(`${BASE}/#/bookings`);
	await audit(page, 'my bookings', () => page.locator('#following').waitFor());
	await page.goto(`${BASE}/#/ticket/${booking.id}`);
	await audit(page, 'ticket', () => page.locator('img.qr').waitFor());
	await page.goto(`${BASE}/#/account`);
	await audit(page, 'account', () => page.locator('#password-form').waitFor());
	await page.goto(`${BASE}/#/admin`);
	await audit(page, 'admin page for a customer', () => page.getByRole('heading', { name: 'Admins only' }).waitFor());
	await context.close();

	// Logged in as the admin
	const adminContext = await browser.newContext();
	const adminPage = await adminContext.newPage();
	adminPage.on('pageerror', (e) => { failed ??= `page error: ${e.message}`; });
	await adminPage.goto(`${BASE}/#/`);
	await adminPage.getByRole('button', { name: 'Log in' }).click();
	await adminPage.locator('#auth-form').getByLabel('Email').fill(ADMIN_EMAIL);
	await adminPage.locator('#auth-form').getByLabel('Password').fill(ADMIN_PASSWORD);
	await adminPage.locator('#auth-submit').click();
	await adminPage.locator('#logout-btn:not([hidden])').waitFor();
	await adminPage.goto(`${BASE}/#/admin`);
	await audit(adminPage, 'admin dashboard', () => adminPage.locator('#kpis strong').first().waitFor());
	await adminPage.goto(`${BASE}/#/admin/show/${show.id}`);
	await audit(adminPage, 'admin show', () => adminPage.locator('.seat').first().waitFor());
	await adminPage.goto(`${BASE}/#/admin/new`);
	await audit(adminPage, 'admin new show', () => adminPage.locator('form').first().waitFor());
	await adminPage.goto(`${BASE}/#/admin/check-in`);
	await audit(adminPage, 'admin check-in', () => adminPage.locator('form').first().waitFor());
	await adminPage.goto(`${BASE}/#/admin/promo-codes`);
	await audit(adminPage, 'admin promo codes', () => adminPage.locator('#new-code').waitFor());
	await adminPage.goto(`${BASE}/#/admin/reviews`);
	await audit(adminPage, 'admin reviews', () => adminPage.locator('[data-review]').first().waitFor());
	await adminPage.goto(`${BASE}/#/admin/films`);
	await audit(adminPage, 'admin films', () => adminPage.locator('[data-edit]').first().waitFor());
	await adminPage.locator('[data-edit]').first().click();
	await audit(adminPage, 'admin film with its poster', () => adminPage.locator('#poster-box:not([hidden])').waitFor());
	await adminContext.close();
} catch (error) {
	failed = `step failed: ${error.message.split('\n')[0]}`;
} finally {
	await browser.close();
	for (const s of shows) await rest(`/api/shows/${s.id}/cancel`, { method: 'POST', token: adminToken }).catch(() => {});
}

console.log(`\n${checked} page checks. Test data: venue "A11y Audit Venue ${RUN}", account ${customer}`);
for (const [id, v] of [...found].sort(([, a], [, b]) => ['critical', 'serious', 'moderate', 'minor'].indexOf(a.impact)
	- ['critical', 'serious', 'moderate', 'minor'].indexOf(b.impact))) {
	console.log(`\n[${v.impact}] ${id}: ${v.help}\n  ${v.url}`);
	for (const [where, targets] of v.where) console.log(`  - ${where}: ${targets.join(' | ')}`);
}
if (failed) console.log(`\n${failed}`);
if (failed || found.size) process.exit(1);
console.log('\nNo accessibility violations found');
