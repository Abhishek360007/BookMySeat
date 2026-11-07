// End-to-end browser test of the waitlist (payments off): a sold-out show offers "Join waitlist"; a customer joins
// in the browser; when the booking is cancelled the freed seats appear on their page, held for them with a countdown,
// and they book them; a second person in line gets an offer and declines it. Also: the phone layout of a sold-out
// show, My bookings' waitlist section, and (with MAILPIT_URL) the offer email with its link.
// Builds its own sold-out show through the API, so it needs an admin account (ADMIN_EMAIL / ADMIN_PASSWORD).
// Registers 3 accounts and logs in 5 times: clear Redis rate:* keys first after other runs (rate limits). Run with:
//   docker run --rm -v "${PWD}/e2e:/e2e" -e BASE_URL=http://host.docker.internal:8080 \
//     -e ADMIN_EMAIL=... -e ADMIN_PASSWORD=... [-e MAILPIT_URL=http://host.docker.internal:18025] \
//     mcr.microsoft.com/playwright:v1.64.1-noble sh -c "npm i --no-save --prefix /tmp/pw playwright@1.64.1 \
//       && cp /e2e/waitlist-flow.mjs /tmp/pw/ && cd /tmp/pw && node waitlist-flow.mjs"
import { chromium, devices } from 'playwright';

const BASE = process.env.BASE_URL || 'http://localhost:8080';
const OUT = process.env.OUT || './screenshots';
const MAILPIT = process.env.MAILPIT_URL;
const { ADMIN_EMAIL, ADMIN_PASSWORD } = process.env;
const RUN = Date.now();
const TITLE = `E2E Waitlist Show ${RUN}`;
const PASSWORD = 'Waitlist-E2e-Pw-1';
const people = {
	buyer: `claude-wl-buyer-${RUN}@example.com`,
	first: `claude-wl-first-${RUN}@example.com`,
	second: `claude-wl-second-${RUN}@example.com`,
};
const problems = [];

const check = (ok, message) => {
	console.log(`${ok ? 'PASS' : 'FAIL'}  ${message}`);
	if (!ok) problems.push(message);
};

function watchForErrors(page, who) {
	page.on('pageerror', (e) => problems.push(`${who}: page error: ${e.message}`));
	page.on('console', (m) => { if (m.type() === 'error') problems.push(`${who}: console error: ${m.text()}`); });
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

// Logs in only when the test needs the token: 5 logins per minute per IP is the limit, and this test uses all 5
async function register(email, { logIn = true } = {}) {
	await rest('/api/auth/register', { method: 'POST', body: { name: 'Waitlist E2E', email, password: PASSWORD } });
	return logIn ? tokenOf(email, PASSWORD) : null;
}

async function logInInDialog(page, email) {
	await page.locator('#auth-dialog[open]').waitFor();
	await page.locator('#auth-form').getByLabel('Email').fill(email);
	await page.locator('#auth-form').getByLabel('Password').fill(PASSWORD);
	await page.locator('#auth-submit').click();
	await page.locator('#logout-btn:not([hidden])').waitFor();
}

async function mailTo(email, subjectPart) {
	for (let i = 0; i < 40; i++) {
		const list = await (await fetch(`${MAILPIT}/api/v1/search?query=${encodeURIComponent(`to:${email}`)}`)).json();
		const hit = list.messages?.find((m) => m.Subject.includes(subjectPart));
		if (hit) return (await fetch(`${MAILPIT}/api/v1/message/${hit.ID}`)).json();
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
	// A show with two seats, both booked by one customer
	const admin = await tokenOf(ADMIN_EMAIL, ADMIN_PASSWORD);
	const venue = await rest('/api/venues', { method: 'POST', token: admin, body: { name: `E2E Waitlist Venue ${RUN}`, city: 'Pune' } });
	const startTime = new Date(Date.now() + 2 * 24 * 3600 * 1000).toISOString().replace(/\.\d+Z$/, 'Z');
	const show = await rest('/api/shows', { method: 'POST', token: admin, body: { title: TITLE, startTime, price: 300, venueId: venue.id } });
	await rest(`/api/shows/${show.id}/seats`, { method: 'POST', token: admin, body: { rows: 1, seatsPerRow: 2 } });
	const seats = await rest(`/api/shows/${show.id}/seats`);
	const buyer = await register(people.buyer);
	const both = { showId: show.id, seatIds: seats.map((s) => s.id) };
	await rest('/api/bookings/hold', { method: 'POST', token: buyer, body: both });
	const booking = await rest('/api/bookings', { method: 'POST', token: buyer, body: both });
	const second = await register(people.second);
	await register(people.first, { logIn: false }); // logs in through the page

	// Phone first: the sold-out panel fits a small screen
	const phone = await browser.newPage({ ...devices['iPhone 13'] });
	watchForErrors(phone, 'phone');
	await phone.goto(`${BASE}/#/show/${show.id}`);
	await phone.getByRole('button', { name: 'Join waitlist' }).waitFor();
	check(!(await phone.evaluate(() => document.documentElement.scrollWidth > window.innerWidth)),
		'phone: a sold-out show has no horizontal page scroll');
	await phone.screenshot({ path: `${OUT}/wl-01-phone-sold-out.png`, fullPage: true });
	await phone.close();

	// The first customer joins in the browser for both seats (logging in on the way)
	const page = await browser.newPage({ viewport: { width: 1280, height: 860 } });
	watchForErrors(page, 'first');
	await page.goto(`${BASE}/#/show/${show.id}`);
	await page.getByRole('button', { name: 'Join waitlist' }).waitFor();
	check((await page.locator('#summary').innerText()).includes('Sold out'), 'a sold-out show says so and offers the waitlist');
	await page.screenshot({ path: `${OUT}/wl-02-sold-out.png` });
	await page.getByLabel('Seats', { exact: true }).selectOption('2');
	await page.getByRole('button', { name: 'Join waitlist' }).click();
	await logInInDialog(page, people.first);
	await page.locator('#waitlist-status').waitFor();
	check((await page.locator('#waitlist-status').innerText()).includes("You're #1 on the waitlist for 2 seats"),
		'after joining, the page shows the place in line');
	await page.screenshot({ path: `${OUT}/wl-03-waiting.png` });

	// The second person waits for 1 seat (through the API)
	const behind = await rest('/api/waitlist', { method: 'POST', token: second, body: { showId: show.id, seats: 1 } });
	check(behind.position === 2, 'a second person joins behind them (#2)');

	// The booking is cancelled: both seats appear on the first customer's page, held for them
	await rest(`/api/bookings/${booking.id}/cancel`, { method: 'POST', token: buyer });
	await page.getByText('From the waitlist:').waitFor({ timeout: 25_000 });
	const offerText = await page.locator('#summary').innerText();
	check(offerText.includes('A1, A2') && /1[45]:\d\d left/.test(offerText), `the offer shows up live with a ~15 minute countdown (${offerText.replace(/\s+/g, ' ')})`);
	check(await page.getByRole('button', { name: 'Seat A1, held by you' }).isVisible(), 'the offered seats are marked as theirs');
	await page.screenshot({ path: `${OUT}/wl-04-offer.png` });

	if (MAILPIT) {
		const mail = await mailTo(people.first, 'Seats for you');
		check(Boolean(mail), 'the offer email arrived');
		check(mail?.Text.includes(`#/show/${show.id}`) && mail?.Text.includes('A1, A2'), 'the email names the seats and links to the show');
	}

	await page.getByRole('button', { name: /Confirm booking/ }).click();
	await page.getByRole('heading', { name: "You're booked!" }).waitFor();
	check((await page.locator('.success').innerText()).includes('A1, A2'), 'they book the offered seats');

	// My bookings: the booking, and they are off the waitlist; cancelling it gives the next person an offer
	await page.getByRole('link', { name: 'My bookings' }).first().click();
	await page.getByRole('heading', { name: 'My bookings' }).waitFor();
	check(await page.locator('#waitlist').count() === 0, 'after booking they are no longer on the waitlist');

	const next = await browser.newPage({ viewport: { width: 1280, height: 860 } });
	watchForErrors(next, 'second');
	await next.goto(`${BASE}/`);
	await next.getByRole('button', { name: 'Log in' }).click();
	await logInInDialog(next, people.second);
	await next.goto(`${BASE}/#/bookings`);
	await next.locator('#waitlist').waitFor();
	check((await next.locator('#waitlist').innerText()).includes('#1 IN LINE'), "My bookings lists the second person's place in line");
	await next.screenshot({ path: `${OUT}/wl-05-my-waitlist.png` });

	page.once('dialog', (dialog) => dialog.accept());
	await page.getByRole('button', { name: 'Cancel' }).first().click();
	await page.locator('.bookings .card').first().locator('.status', { hasText: 'CANCELLED' }).waitFor();

	await next.reload();
	await next.locator('#waitlist .status', { hasText: 'SEATS HELD FOR YOU' }).waitFor();
	check((await next.locator('#waitlist').innerText()).includes('Seats A1'), 'the next person gets the seat they asked for');
	await next.getByRole('link', { name: 'Book now' }).click();
	await next.getByText('From the waitlist:').waitFor();
	await next.getByRole('button', { name: 'Decline' }).click();
	await next.getByText('Offer declined').waitFor();
	await next.getByRole('button', { name: 'Seat A1, available' }).waitFor();
	check(true, 'declining frees the seat for anyone (nobody else is waiting)');
	await next.screenshot({ path: `${OUT}/wl-06-declined.png` });

	await rest(`/api/shows/${show.id}/cancel`, { method: 'POST', token: admin }); // gone from listings
} catch (error) {
	for (const p of browser.contexts().flatMap((c) => c.pages())) {
		await p.screenshot({ path: `${OUT}/zz-failure-${Math.random().toString(36).slice(2, 6)}.png` }).catch(() => {});
		const toasts = await p.locator('.toast').allInnerTexts().catch(() => []);
		if (toasts.length) problems.push(`toasts on ${p.url()}: ${toasts.join(' | ')}`);
	}
	problems.push(`step failed: ${error.message.split('\n')[0]}`);
} finally {
	await browser.close();
}

console.log(`\nTest data: show "${TITLE}", accounts ${Object.values(people).join(', ')}`);
if (problems.length) {
	console.log(`\n${problems.length} problem(s):\n- ${problems.join('\n- ')}`);
	process.exit(1);
}
console.log('\nAll waitlist browser checks passed');
