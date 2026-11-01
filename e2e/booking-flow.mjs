// End-to-end browser test of the web app (payments off): browse -> pick seats -> log in -> hold -> confirm
// -> my bookings -> cancel, plus a second visitor seeing the hold live and a phone-sized layout.
// Needs the demo data from scripts/seed-demo.ps1. Run with Playwright's Docker image:
//   docker run --rm --network host -v "${PWD}/e2e:/e2e" -w /e2e -e BASE_URL=http://localhost:8080 \
//     mcr.microsoft.com/playwright:v1.64.1-noble sh -c "npm i --no-save playwright@1.64.1 && node booking-flow.mjs"
import { chromium, devices } from 'playwright';

const BASE = process.env.BASE_URL || 'http://localhost:8080';
const OUT = process.env.OUT || './screenshots';
const SHOW = 'Dune: Part Three';
const problems = [];

const check = (ok, message) => {
	console.log(`${ok ? 'PASS' : 'FAIL'}  ${message}`);
	if (!ok) problems.push(message);
};

function watchForErrors(page, who) {
	page.on('pageerror', (e) => problems.push(`${who}: page error: ${e.message}`));
	page.on('console', (m) => { if (m.type() === 'error') problems.push(`${who}: console error: ${m.text()}`); });
}

const browser = await chromium.launch();
try {
	const page = await browser.newPage({ viewport: { width: 1280, height: 860 } });
	watchForErrors(page, 'user');

	await page.goto(`${BASE}/`);
	await page.getByText(SHOW).first().waitFor();
	check(await page.getByRole('heading', { name: 'Now booking' }).isVisible(), 'home page lists upcoming shows');
	await page.screenshot({ path: `${OUT}/01-home.png` });

	await page.getByText(SHOW).first().click();
	await page.locator('.seat').first().waitFor();
	check((await page.locator('.seat').count()) === 50, 'seat map shows all 50 seats');
	await page.getByRole('button', { name: 'Seat C5, available' }).click();
	await page.getByRole('button', { name: 'Seat C6, available' }).click();
	check((await page.locator('#summary').innerText()).includes('2 seats'), 'summary counts the 2 selected seats');
	await page.screenshot({ path: `${OUT}/02-seats-selected.png` });

	await page.getByRole('button', { name: 'Hold seats' }).click();
	await page.locator('#auth-dialog[open]').waitFor();
	check(true, 'holding while logged out opens the login dialog');
	await page.screenshot({ path: `${OUT}/03-login.png` });
	await page.getByRole('button', { name: 'Use the demo account' }).click();

	await page.locator('#timer').waitFor();
	const timer = await page.locator('#timer').innerText();
	check(/^[45]:\d\d left$/.test(timer), `hold countdown is running (${timer})`);
	check(await page.getByRole('button', { name: 'Seat C5, held by you' }).isVisible(), 'held seats are marked as yours');
	await page.screenshot({ path: `${OUT}/04-held.png` });

	// A second, logged-out visitor sees those seats as held within one refresh
	const visitor = await browser.newPage({ viewport: { width: 1280, height: 860 } });
	watchForErrors(visitor, 'visitor');
	await visitor.goto(page.url());
	await visitor.locator('.seat').first().waitFor();
	const c5 = visitor.getByRole('button', { name: /^Seat C5, / });
	check((await c5.getAttribute('aria-label')).endsWith('held') && await c5.isDisabled(), 'another visitor sees the seats as held');
	await visitor.close();

	await page.getByRole('button', { name: /Confirm booking/ }).click();
	await page.getByRole('heading', { name: "You're booked!" }).waitFor();
	const success = await page.locator('.success').innerText();
	check(success.includes('C5, C6') && success.includes('₹500.00'), 'success page shows seats C5, C6 and ₹500.00');
	await page.screenshot({ path: `${OUT}/05-booked.png` });

	await page.getByRole('link', { name: 'My bookings' }).first().click();
	await page.getByRole('heading', { name: 'My bookings' }).waitFor();
	check((await page.locator('.status').first().innerText()) === 'CONFIRMED', 'my bookings lists the confirmed booking');
	await page.screenshot({ path: `${OUT}/06-my-bookings.png` });

	page.once('dialog', (dialog) => dialog.accept());
	await page.getByRole('button', { name: 'Cancel' }).first().click();
	await page.locator('.status', { hasText: 'CANCELLED' }).waitFor();
	check(true, 'cancelling marks the booking CANCELLED');
	await page.screenshot({ path: `${OUT}/07-cancelled.png` });

	const phone = await browser.newPage({ ...devices['iPhone 13'] });
	watchForErrors(phone, 'phone');
	await phone.goto(page.url().replace('#/bookings', '#/show/1'));
	await phone.locator('.seat').first().waitFor();
	const overflow = await phone.evaluate(() => document.documentElement.scrollWidth > window.innerWidth);
	check(!overflow, 'phone layout has no horizontal page scroll');
	const seatMapFits = await phone.evaluate(() => {
		const map = document.getElementById('seatmap');
		return map.scrollWidth <= map.clientWidth;
	});
	check(seatMapFits, 'the whole seat map (row labels to seat 10) fits the phone screen');
	await phone.screenshot({ path: `${OUT}/08-phone.png`, fullPage: true });
} catch (error) {
	// Leave evidence: what the page showed and which messages it gave
	for (const p of browser.contexts().flatMap((c) => c.pages())) {
		await p.screenshot({ path: `${OUT}/zz-failure-${Math.random().toString(36).slice(2, 6)}.png` }).catch(() => {});
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
console.log('\nAll browser checks passed');
