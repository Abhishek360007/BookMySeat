// End-to-end browser test of the web app (payments off): browse -> pick seats -> log in -> hold -> confirm
// -> my bookings -> cancel, plus a second visitor seeing the hold live and a phone-sized layout.
// With MAILPIT_URL set (app running with EMAIL_PROVIDER=smtp and Mailpit from compose.yaml) it also registers
// a new account and confirms its email through the link in the real email.
// Needs the demo data from scripts/seed-demo.ps1. Run with Playwright's Docker image:
//   docker run --rm --network host -v "${PWD}/e2e:/e2e" -w /e2e -e BASE_URL=http://localhost:8080 \
//     -e MAILPIT_URL=http://localhost:8025 \
//     mcr.microsoft.com/playwright:v1.64.1-noble sh -c "npm i --no-save playwright@1.64.1 && node booking-flow.mjs"
import { chromium, devices } from 'playwright';

const BASE = process.env.BASE_URL || 'http://localhost:8080';
const MAILPIT = process.env.MAILPIT_URL;
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
	// The newest booking (first card): the demo account may have older cancelled ones
	await page.locator('.bookings .card').first().locator('.status', { hasText: 'CANCELLED' }).waitFor();
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

	if (MAILPIT) {
		// A new visitor registers, sees the reminder, and confirms through the link in the email
		const email = `e2e-${Date.now()}@example.com`;
		const fresh = await browser.newPage({ viewport: { width: 1280, height: 860 } });
		watchForErrors(fresh, 'new user');
		await fresh.goto(`${BASE}/`);
		await fresh.getByRole('button', { name: 'Log in' }).click();
		await fresh.getByRole('button', { name: 'No account? Register' }).click();
		await fresh.getByLabel('Name').fill('E2E Tester');
		await fresh.getByLabel('Email').fill(email);
		await fresh.getByLabel('Password').fill('E2e-Password-123');
		await fresh.getByRole('button', { name: 'Register' }).click();
		await fresh.locator('#verify-banner:not([hidden])').waitFor();
		check(true, 'a new account sees the "confirm your email" reminder');
		await fresh.screenshot({ path: `${OUT}/09-verify-banner.png` });

		let link = null;
		for (let i = 0; i < 40 && !link; i++) {
			const list = await (await fetch(`${MAILPIT}/api/v1/search?query=${encodeURIComponent(`to:${email}`)}`)).json();
			if (list.messages?.length) {
				const message = await (await fetch(`${MAILPIT}/api/v1/message/${list.messages[0].ID}`)).json();
				link = message.Text.match(/https?:\/\/\S+#\/verify\/[A-Za-z0-9_-]+/)?.[0];
			}
			if (!link) await new Promise((r) => setTimeout(r, 250));
		}
		check(Boolean(link), 'the verification email arrived with a link');
		const token = link.split('#/verify/')[1];
		await fresh.goto(link.replace(/^https?:\/\/[^/#]+/, BASE));
		await fresh.getByRole('heading', { name: 'Email confirmed' }).waitFor();
		check(await fresh.locator('#verify-banner').isHidden(), 'opening the link confirms the email and hides the reminder');
		check(!fresh.url().includes(token), 'the one-time token is removed from the address bar');
		await fresh.screenshot({ path: `${OUT}/10-verified.png` });
	}
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
