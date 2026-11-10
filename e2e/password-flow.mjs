// End-to-end browser test of passwords: "Forgot password?" -> email (from Mailpit) -> reset page -> log in with the
// new password -> change it again on the account page. Also checks that the web app runs under its
// Content-Security-Policy without violations. Needs the app with EMAIL_PROVIDER=smtp and Mailpit. Run with:
//   docker run --rm -v "${PWD}/e2e:/e2e" -e BASE_URL=http://host.docker.internal:8080 \
//     -e MAILPIT_URL=http://host.docker.internal:18025 mcr.microsoft.com/playwright:v1.64.1-noble \
//     sh -c "npm i --no-save --prefix /tmp/pw playwright@1.64.1 && cp /e2e/password-flow.mjs /tmp/pw/ && cd /tmp/pw && node password-flow.mjs"
import { chromium } from 'playwright';

const BASE = process.env.BASE_URL || 'http://localhost:8080';
const OUT = process.env.OUT || './screenshots';
const MAILPIT = process.env.MAILPIT_URL || 'http://localhost:8025';
const RUN = Date.now();
const EMAIL = `e2e-pw-${RUN}@example.com`;
const problems = [];

const check = (ok, message) => {
	console.log(`${ok ? 'PASS' : 'FAIL'}  ${message}`);
	if (!ok) problems.push(message);
};

function watch(page, who) {
	page.on('pageerror', (e) => problems.push(`${who}: page error: ${e.message}`));
	page.on('console', (m) => {
		// 400s from deliberately wrong passwords are expected
		if (m.type() === 'error' && !/status of 40[01]/.test(m.text())) problems.push(`${who}: console error: ${m.text()}`);
	});
}

async function cspViolations(page) {
	return page.evaluate(() => window.__csp ?? []);
}

async function mailLink(subject, pattern) {
	for (let i = 0; i < 40; i++) {
		const list = await (await fetch(`${MAILPIT}/api/v1/search?query=${encodeURIComponent(`to:${EMAIL}`)}`)).json();
		const hit = list.messages?.find((m) => m.Subject === subject);
		if (hit) {
			const message = await (await fetch(`${MAILPIT}/api/v1/message/${hit.ID}`)).json();
			const link = message.Text.match(pattern)?.[0];
			if (link) return link;
		}
		await new Promise((r) => setTimeout(r, 250));
	}
	return null;
}

const browser = await chromium.launch();
try {
	const context = await browser.newContext({ viewport: { width: 1280, height: 860 } });
	// Record any Content-Security-Policy violation the page hits
	await context.addInitScript(() => {
		window.__csp = [];
		document.addEventListener('securitypolicyviolation', (e) => window.__csp.push(`${e.violatedDirective} ${e.blockedURI}`));
	});
	const page = await context.newPage();
	watch(page, 'user');

	await fetch(`${BASE}/api/auth/register`, { method: 'POST', headers: { 'Content-Type': 'application/json' },
		body: JSON.stringify({ name: 'Password Tester', email: EMAIL, password: 'First-Password-1' }) });

	// Forgot password, from the login dialog
	await page.goto(`${BASE}/`);
	await page.getByRole('button', { name: 'Log in' }).click();
	await page.getByRole('link', { name: 'Forgot password?' }).click();
	await page.getByRole('heading', { name: 'Forgot your password?' }).waitFor();
	await page.locator('#forgot-form').getByLabel('Email').fill(EMAIL);
	await page.getByRole('button', { name: 'Send reset link' }).click();
	await page.getByRole('heading', { name: 'Check your inbox' }).waitFor();
	check(true, '"Forgot password?" sends a link and says to check the inbox');

	const link = await mailLink('Reset your BookMySeat password', /https?:\/\/\S+#\/reset\/[A-Za-z0-9_-]+/);
	check(Boolean(link), 'the reset email arrived with a link');
	const token = link.split('#/reset/')[1];
	await page.goto(link.replace(/^https?:\/\/[^/#]+/, BASE));
	await page.getByRole('heading', { name: 'Choose a new password' }).waitFor();
	check(!page.url().includes(token), 'the one-time token is removed from the address bar');
	await page.getByLabel('New password').fill('Second-Password-2');
	await page.getByLabel('Repeat it').fill('Second-Password-2');
	await page.getByRole('button', { name: 'Set new password' }).click();
	await page.getByRole('heading', { name: 'Password changed' }).waitFor();
	await page.screenshot({ path: `${OUT}/p1-reset-done.png` });

	// Log in with the new password
	await page.getByRole('button', { name: 'Log in' }).last().click();
	await page.locator('#auth-form').getByLabel('Email').fill(EMAIL);
	await page.locator('#auth-form').getByLabel('Password').fill('Second-Password-2');
	await page.locator('#auth-submit').click();
	await page.locator('#logout-btn:not([hidden])').waitFor();
	check(true, 'the new password works');
	check(Boolean(await mailLink('Your BookMySeat password was changed', /forgot/)), 'a "password changed" email was sent');

	// The same link can't be used twice
	await page.goto(link.replace(/^https?:\/\/[^/#]+/, BASE));
	await page.getByLabel('New password').fill('Third-Password-3');
	await page.getByLabel('Repeat it').fill('Third-Password-3');
	await page.getByRole('button', { name: 'Set new password' }).click();
	await page.getByText('This password reset link is invalid').waitFor();
	check(true, 'a used reset link is refused');

	// Change it again from the account page
	await page.goto(`${BASE}/#/`);
	await page.locator('#logout-btn:not([hidden])').waitFor();
	await page.locator('a.who').click();
	await page.getByRole('heading', { name: 'Your account' }).waitFor();
	const form = page.locator('#password-form');
	await form.getByLabel('Current password').fill('Wrong-Password-0');
	await form.getByLabel('New password', { exact: true }).fill('Fourth-Password-4');
	await form.getByLabel('Repeat new password').fill('Fourth-Password-4');
	await form.getByRole('button', { name: 'Change password' }).click();
	await page.getByText('Your current password is incorrect').waitFor();
	check(true, 'a wrong current password is refused');
	await form.getByLabel('Current password').fill('Second-Password-2');
	await form.getByRole('button', { name: 'Change password' }).click();
	await page.locator('.toast', { hasText: 'Password changed' }).waitFor();
	await page.reload(); // the new session survives a reload
	await page.locator('#logout-btn:not([hidden])').waitFor();
	check(true, 'changing the password keeps this device logged in');
	await page.screenshot({ path: `${OUT}/p2-account.png` });

	// Browse a show too, so the seat map runs under the CSP
	await page.goto(`${BASE}/`);
	await page.locator('.show-card').first().click();
	await page.locator('.slot').first().click();
	await page.locator('.seat').first().waitFor();
	check((await cspViolations(page)).length === 0, `no Content-Security-Policy violations (${(await cspViolations(page)).join(', ')})`);
} catch (error) {
	for (const p of browser.contexts().flatMap((c) => c.pages())) {
		await p.screenshot({ path: `${OUT}/zz-pw-failure-${Math.random().toString(36).slice(2, 6)}.png` }).catch(() => {});
	}
	problems.push(`step failed: ${error.message.split('\n')[0]}`);
} finally {
	await browser.close();
}

if (problems.length) {
	console.log(`\n${problems.length} problem(s):\n- ${problems.join('\n- ')}`);
	process.exit(1);
}
console.log('\nAll password browser checks passed');
