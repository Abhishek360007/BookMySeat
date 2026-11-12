// End-to-end test of the theme button: without a choice the page follows the system setting; the button (a toggle named
// "Dark theme") switches with the keyboard, says whether it is on, is remembered after a reload, and wins over the
// system setting both ways, and still works when storage is blocked. Also checks that the nav marks the section you're
// in. Needs no login. Run with:
//   docker run --rm -v "${PWD}/e2e:/e2e" -e BASE_URL=http://host.docker.internal:8080 \
//     mcr.microsoft.com/playwright:v1.64.1-noble sh -c "npm i --no-save --prefix /tmp/pw playwright@1.64.1 \
//       && cp /e2e/theme-flow.mjs /tmp/pw/ && cd /tmp/pw && node theme-flow.mjs"
import { chromium } from 'playwright';

const BASE = process.env.BASE_URL || 'http://localhost:8080';
const problems = [];
const check = (ok, message) => {
	console.log(`${ok ? 'PASS' : 'FAIL'}  ${message}`);
	if (!ok) problems.push(message);
};

const LIGHT_BG = 'rgb(248, 245, 241)';
const DARK_BG = 'rgb(12, 11, 16)';

const browser = await chromium.launch();
try {
	// A system in light mode
	const context = await browser.newContext({ colorScheme: 'light' });
	const page = await context.newPage();
	const errors = [];
	page.on('pageerror', (e) => errors.push(e.message));
	const background = () => page.evaluate(() => getComputedStyle(document.body).backgroundColor);
	const button = page.getByRole('button', { name: 'Dark theme' });

	await page.goto(`${BASE}/`);
	await page.locator('.show-card, .empty').first().waitFor();
	check(await background() === LIGHT_BG, 'without a choice, a light system gets the light theme');
	check(await button.getAttribute('aria-pressed') === 'false', 'the Dark theme toggle says it is off');
	check(await page.evaluate(() => !document.documentElement.dataset.theme), 'nothing is forced before a choice');

	// Keyboard: focus the button and press Enter
	await button.focus();
	await page.keyboard.press('Enter');
	check(await background() === DARK_BG, 'Enter on the toggle switches to the dark theme');
	check(await button.getAttribute('aria-pressed') === 'true', 'the toggle now says it is on');
	check(await button.evaluate((el) => el === document.activeElement), 'focus stays on the toggle');

	// Remembered, and set by the script in <head> (before the page is drawn, so no flash of the light theme)
	await page.reload();
	check(await page.evaluate(() => document.documentElement.dataset.theme) === 'dark', 'the choice is remembered after a reload');
	check(await background() === DARK_BG, 'the dark theme wins over the light system setting');

	// The other way: a dark system, light chosen
	await page.emulateMedia({ colorScheme: 'dark' });
	await button.click();
	check(await background() === LIGHT_BG, 'light can be chosen on a dark system');
	check(await button.getAttribute('aria-pressed') === 'false', 'and the toggle says it is off');
	await page.reload();
	check(await background() === LIGHT_BG, 'the light choice is remembered too');

	// The section you're in is marked in the nav
	const films = page.locator('.nav a[href="#/"]');
	check(await films.getAttribute('aria-current') === 'page', 'the Films link is marked as the current page on Home');
	await page.goto(`${BASE}/#/forgot`);
	await page.locator('#forgot-form').waitFor();
	check(await films.getAttribute('aria-current') === null, 'and no longer marked elsewhere');

	check(errors.length === 0, `no script errors${errors.length ? `: ${errors.join('; ')}` : ''}`);

	// Storage blocked (some private modes): the button still switches, the page doesn't break
	const blocked = await browser.newContext({ colorScheme: 'light' });
	await blocked.addInitScript(() => {
		Object.defineProperty(window, 'localStorage', { get() { throw new Error('blocked'); } });
	});
	const strict = await blocked.newPage();
	const strictErrors = [];
	strict.on('pageerror', (e) => strictErrors.push(e.message));
	await strict.goto(`${BASE}/`);
	await strict.getByRole('button', { name: 'Dark theme' }).click();
	check(await strict.evaluate(() => getComputedStyle(document.body).backgroundColor) === DARK_BG,
		'with storage blocked the toggle still switches the theme');
	check(!strictErrors.some((m) => m.includes('theme')), 'and the theme script throws nothing');
	await blocked.close();
} finally {
	await browser.close();
}

if (problems.length) {
	console.log(`\n${problems.length} check(s) failed`);
	process.exit(1);
}
console.log('\nAll theme checks passed');
