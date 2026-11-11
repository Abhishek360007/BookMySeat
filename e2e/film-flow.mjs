// End-to-end browser test of films (payments off): an admin creates a film on the Films page and two showtimes for
// it with the New show form (an existing title hides the runtime field), a customer finds the film on the home page
// (with its next showtime and the cheapest seat), narrows it to a city, picks a showtime on the film's page and
// reaches the seat map, which links back to the film. Renaming the film on the Films page renames its shows.
// Also: the phone layout and no Content-Security-Policy violations.
// Builds its own data, so it needs an admin account (ADMIN_EMAIL / ADMIN_PASSWORD). Logs in 2 times. Run with:
//   docker run --rm -v "${PWD}/e2e:/e2e" -e BASE_URL=http://host.docker.internal:8080 \
//     -e ADMIN_EMAIL=... -e ADMIN_PASSWORD=... \
//     mcr.microsoft.com/playwright:v1.64.1-noble sh -c "npm i --no-save --prefix /tmp/pw playwright@1.64.1 \
//       && cp /e2e/film-flow.mjs /tmp/pw/ && cd /tmp/pw && node film-flow.mjs"
import { chromium } from 'playwright';

const BASE = process.env.BASE_URL || 'http://localhost:8080';
const OUT = process.env.OUT || './screenshots';
const { ADMIN_EMAIL, ADMIN_PASSWORD } = process.env;
const RUN = Date.now();
const TITLE = `E2E Film ${RUN}`;
const CITY_A = `Filmtown ${RUN % 100000}`;
const CITY_B = `Reeltown ${RUN % 100000}`;
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
	page.on('console', (m) => { if (m.type() === 'error' && !/status of (40[49]|415)/.test(m.text()) /* 415: the fake poster, on purpose */) problems.push(`${who}: console error: ${m.text()}`); });
	return page;
}

const browser = await chromium.launch();
const shows = [];
let adminToken = null;
try {
	adminToken = (await rest('/api/auth/login', { method: 'POST', body: { email: ADMIN_EMAIL, password: ADMIN_PASSWORD } })).accessToken;
	const hallA = await rest('/api/venues', { method: 'POST', token: adminToken, body: { name: `E2E Film Hall A ${RUN}`, city: CITY_A } });
	const hallB = await rest('/api/venues', { method: 'POST', token: adminToken, body: { name: `E2E Film Hall B ${RUN}`, city: CITY_B } });

	const csp = [];
	const adminContext = await browser.newContext({ viewport: { width: 1280, height: 900 } });
	const admin = await newPage(adminContext, 'admin', csp);
	admin.on('dialog', (d) => d.accept()); // "Remove the poster?"
	await admin.goto(`${BASE}/`);
	await admin.getByRole('button', { name: 'Log in' }).click();
	await admin.locator('#auth-form').getByLabel('Email').fill(ADMIN_EMAIL);
	await admin.locator('#auth-form').getByLabel('Password').fill(ADMIN_PASSWORD);
	await admin.locator('#auth-submit').click();
	await admin.locator('#logout-btn:not([hidden])').waitFor();

	// The Films page: a new film
	await admin.goto(`${BASE}/#/admin`);
	await admin.locator('#app').getByRole('link', { name: 'Films', exact: true }).click(); // not the top bar's Films
	await admin.getByRole('heading', { name: 'Films', exact: true }).waitFor();
	const filmForm = admin.locator('#film-form');
	await filmForm.getByLabel('Title').fill(TITLE);
	await filmForm.getByLabel('Runtime (minutes)').fill('116');
	await filmForm.getByLabel('Language').fill('English');
	await filmForm.getByLabel('Certificate').selectOption('UA');
	await filmForm.getByLabel('Synopsis').fill('A linguist is asked to talk to visitors.\nNobody knows why they came.');
	await filmForm.getByRole('button', { name: 'Create film' }).click();
	await admin.locator(`[data-film]`, { hasText: TITLE }).waitFor();
	check(await admin.evaluate(() => document.activeElement?.dataset?.film !== undefined), 'after creating, focus goes to the new film in the list');
	await admin.screenshot({ path: `${OUT}/f1-admin-films.png`, fullPage: true });

	// Its poster: Edit, then upload. A text file named .png is refused by what it is, not by its name
	await admin.getByRole('button', { name: `Edit ${TITLE}` }).click();
	await admin.locator('#poster-box').waitFor();
	check(await admin.locator('#poster-current .poster.placeholder').isVisible(), 'a film without a poster shows its initials');
	await admin.locator('#poster-file').setInputFiles({ name: 'fake.png', mimeType: 'image/png', buffer: Buffer.from('not an image at all') });
	await admin.getByRole('button', { name: 'Upload poster' }).click();
	await admin.locator('#poster-error', { hasText: 'JPEG or PNG' }).waitFor();
	check(true, 'a file that is not a JPEG or PNG is refused with the reason');
	const png = Buffer.from((await admin.evaluate(() => {
		const canvas = document.createElement('canvas');
		canvas.width = 600;
		canvas.height = 900;
		const g = canvas.getContext('2d');
		g.fillStyle = '#7c3aed';
		g.fillRect(0, 0, 600, 900);
		g.fillStyle = '#fde68a';
		g.fillRect(100, 300, 400, 300);
		return canvas.toDataURL('image/png');
	})).split(',')[1], 'base64');
	await admin.locator('#poster-file').setInputFiles({ name: 'poster.png', mimeType: 'image/png', buffer: png });
	await admin.getByRole('button', { name: 'Upload poster' }).click();
	await admin.locator('#poster-current img.poster').waitFor();
	check(await admin.locator('#poster-error').isHidden(), 'a PNG uploads (the error from before is gone)');
	check(await admin.locator('tbody tr', { hasText: TITLE }).locator('img.poster').count() === 1, 'the film list shows the poster');
	await admin.getByRole('button', { name: 'Cancel editing' }).click();

	// Two showtimes through the New show form: the existing title hides the runtime field
	for (const [hall, day] of [[hallA, 2], [hallB, 3]]) {
		await admin.goto(`${BASE}/#/admin/new`);
		const form = admin.locator('#new-show');
		await form.getByLabel('Film title').fill(TITLE.toLowerCase()); // any case finds the film
		check(await admin.locator('#runtime-field').isHidden(), 'the runtime field hides for a film that exists');
		await form.getByLabel('Venue', { exact: true }).selectOption(String(hall.id));
		const start = new Date(Date.now() + day * 24 * 3600_000);
		const local = new Date(start.getTime() + 330 * 60_000).toISOString().slice(0, 10); // India date
		await form.getByLabel('Starts (India time)').fill(`${local}T18:30`);
		await form.getByLabel('Regular price (₹)').fill(day === 2 ? '220' : '180');
		await form.getByLabel('Rows (A–Z)').fill('2');
		await form.getByLabel('Seats per row').fill('6');
		await form.getByRole('button', { name: 'Create show' }).click();
		await admin.waitForURL(/#\/admin\/show\/\d+$/);
		// The URL changes before the show's page replaces the form: wait for it, or the next round fills the old form
		await admin.locator('.seat').first().waitFor();
		shows.push(Number(admin.url().match(/show\/(\d+)$/)[1]));
	}
	const created = await rest(`/api/shows/${shows[0]}`);
	check(created.title === TITLE && created.runtimeMinutes === 116, 'the shows joined the film (its title in its case, its runtime)');

	// A customer (no login needed to browse)
	const visitorContext = await browser.newContext({ viewport: { width: 1100, height: 900 } });
	const visitor = await newPage(visitorContext, 'visitor', csp);
	await visitor.goto(`${BASE}/#/?q=${encodeURIComponent(TITLE)}`);
	const card = visitor.locator('.show-card', { hasText: TITLE });
	await card.waitFor();
	const cardText = await card.innerText();
	check(cardText.includes('1 h 56 min') && cardText.includes('English') && cardText.includes('UA'), 'the home card shows runtime, language and certificate');
	check(cardText.includes('2 showtimes') && cardText.includes('₹180'), 'the home card counts the showtimes and gives the cheapest seat');
	check(cardText.includes(`E2E Film Hall A ${RUN}`), 'the home card names the next showtime\'s venue');
	const cardPoster = card.locator('img.poster');
	await cardPoster.evaluate((img) => img.decode());
	check(await cardPoster.evaluate((img) => img.naturalWidth === 480 && img.naturalHeight === 720 && img.alt === ''),
		'the home card shows the poster, 480 x 720 (cropped and resized by the server), as decoration');

	// Narrowed to a city: only that city's showtime, on the card and on the film's page
	await visitor.goto(`${BASE}/#/?q=${encodeURIComponent(TITLE)}&city=${encodeURIComponent(CITY_B)}`);
	await visitor.locator('.show-card', { hasText: '1 showtime' }).click();
	await visitor.getByRole('heading', { name: `Showtimes in ${CITY_B}` }).waitFor();
	check(await visitor.locator('.slot').count() === 1, 'the film page keeps the city: one showtime');
	await visitor.goto(`${BASE}/#/film/${created.filmId}`);
	await visitor.getByRole('heading', { name: 'Showtimes', exact: true }).waitFor();
	check(await visitor.locator('.slot').count() === 2, 'without a city, the film page lists both showtimes');
	check(await visitor.locator('h3.day').count() === 2, '...under their days');
	check((await visitor.locator('.synopsis').innerText()).includes('Nobody knows why they came.'), 'the synopsis keeps its line break');
	await visitor.screenshot({ path: `${OUT}/f2-film.png`, fullPage: true });

	await visitor.locator('.slot').first().click();
	await visitor.locator('.seat').first().waitFor();
	check(await visitor.getByRole('link', { name: 'About the film and other showtimes' }).isVisible(), 'the seat map links back to the film');
	check((await visitor.locator('h1').innerText()) === TITLE, 'the seat map is the film\'s showtime');

	// Renamed on the Films page: its shows follow
	await admin.goto(`${BASE}/#/admin/films`);
	await admin.getByRole('button', { name: `Edit ${TITLE}` }).click();
	check(await admin.evaluate(() => document.activeElement?.name) === 'title', 'Edit puts focus in the title field');
	await admin.locator('#film-form').getByLabel('Title').fill(`${TITLE} (Director's Cut)`);
	await admin.getByRole('button', { name: 'Save film' }).click();
	await admin.locator('[data-film]', { hasText: `${TITLE} (Director's Cut)` }).waitFor();
	await visitor.reload();
	await visitor.locator('h1', { hasText: "(Director's Cut)" }).waitFor();
	check(true, 'a renamed film renames its shows');

	// Removing the poster: the film page falls back to the initials
	await admin.getByRole('button', { name: `Edit ${TITLE} (Director's Cut)` }).click();
	await admin.getByRole('button', { name: 'Remove poster' }).click();
	await admin.locator('#poster-current .poster.placeholder').waitFor();
	await visitor.goto(`${BASE}/#/film/${created.filmId}`);
	await visitor.locator('.film-head .poster.placeholder').waitFor();
	check(true, 'without its poster, the film page shows the initials');

	await visitor.goto(`${BASE}/#/film/${created.filmId}`);
	await visitor.setViewportSize({ width: 360, height: 800 });
	await visitor.locator('.slot').first().waitFor();
	check(await visitor.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth), 'the film page fits a phone without sideways scrolling');
	await visitor.screenshot({ path: `${OUT}/f3-film-phone.png`, fullPage: true });

	check(csp.length === 0, `no Content-Security-Policy violations (${csp.join(', ')})`);
} catch (error) {
	for (const p of browser.contexts().flatMap((c) => c.pages())) {
		await p.screenshot({ path: `${OUT}/zz-film-failure-${Math.random().toString(36).slice(2, 6)}.png` }).catch(() => {});
	}
	problems.push(`step failed: ${error.message.split('\n')[0]}`);
} finally {
	await browser.close();
	for (const id of shows) await rest(`/api/shows/${id}/cancel`, { method: 'POST', token: adminToken }).catch(() => {});
}

if (problems.length) {
	console.log(`\n${problems.length} problem(s):\n- ${problems.join('\n- ')}`);
	process.exit(1);
}
console.log('\nAll film browser checks passed');
