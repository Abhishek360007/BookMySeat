// BookMySeat web app: a plain ES module (no build step) talking to the REST API on the same server.

const app = document.getElementById('app');
const REFRESH_KEY = 'bms.refreshToken';
const DEMO = { email: 'demo@bookmyseat.dev', password: 'DemoPass123' };
const POLL_MS = 10_000;

const state = {
	config: { paymentsEnabled: false, currency: 'INR', holdMinutes: 5 },
	accessToken: null, // memory only: never written to storage
	user: null,
	timers: [],
};

const money = new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR' });
const when = new Intl.DateTimeFormat('en-IN', { dateStyle: 'medium', timeStyle: 'short', timeZone: 'Asia/Kolkata' });
const esc = (value) => String(value ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

const CATEGORIES = ['REGULAR', 'PREMIUM', 'RECLINER'];
const CATEGORY_NAMES = { REGULAR: 'regular', PREMIUM: 'premium', RECLINER: 'recliner' };

/** "₹250" or, when seat categories cost different amounts, "from ₹250" */
const day = new Intl.DateTimeFormat('en-IN', { dateStyle: 'medium', timeZone: 'Asia/Kolkata' });
const RATING_WORDS = ['', 'Poor', 'Fair', 'Good', 'Very good', 'Excellent'];

/** "★★★★☆" for the eyes; screen readers get the words from the text next to it instead. */
function starsText(rating) {
	return '★'.repeat(rating) + '☆'.repeat(5 - rating);
}

/** "★ 4.3 · 12 reviews", read as "Rated 4.3 out of 5, 12 reviews"; nothing while a show has no reviews. */
function ratingSummary(show) {
	if (!show.ratingCount) return '';
	const average = Number(show.ratingAverage).toFixed(1);
	return `<span class="rating"><span class="star" aria-hidden="true">★</span> <span aria-hidden="true">${esc(average)}</span><span class="sr-only">Rated ${esc(average)} out of 5,</span> · ${show.ratingCount} review${show.ratingCount > 1 ? 's' : ''}</span>`;
}

function priceLabel(show) {
	const prices = Object.values(show.prices ?? {}).map(Number);
	const lowest = prices.length ? Math.min(...prices) : Number(show.price);
	return prices.some((p) => p !== lowest) ? `from ${money.format(lowest)}` : money.format(show.price);
}

// crypto.randomUUID() only exists on secure origins (HTTPS or localhost); getRandomValues works everywhere
function uuid() {
	if (crypto.randomUUID) return crypto.randomUUID();
	const b = crypto.getRandomValues(new Uint8Array(16));
	b[6] = (b[6] & 0x0f) | 0x40; // version 4
	b[8] = (b[8] & 0x3f) | 0x80; // RFC 4122 variant
	const h = [...b].map((x) => x.toString(16).padStart(2, '0')).join('');
	return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
}

// ---------- API ----------

class ApiError extends Error {
	constructor(status, message) {
		super(message);
		this.status = status;
	}
}

let refreshing = null;

/** fetch wrapper: JSON in/out, problem+json errors as ApiError, one silent token refresh on 401. */
async function api(path, { method = 'GET', body, auth = false, headers = {} } = {}, retried = false) {
	const h = { ...headers };
	if (body !== undefined) h['Content-Type'] = 'application/json';
	if (auth && state.accessToken) h.Authorization = `Bearer ${state.accessToken}`;

	const res = await fetch(path, { method, headers: h, body: body === undefined ? undefined : JSON.stringify(body) });
	if (res.status === 401 && auth && !retried && (await refreshSession())) {
		return api(path, { method, body, auth, headers }, true);
	}
	const text = await res.text();
	let data = null;
	try { data = text ? JSON.parse(text) : null; } catch { data = null; }
	if (!res.ok) {
		throw new ApiError(res.status, data?.detail || data?.title || `Request failed (${res.status})`);
	}
	return data;
}

/** A file the login token unlocks (the QR image, the calendar event), with the same one silent refresh on 401. */
async function apiBlob(path, retried = false) {
	const res = await fetch(path, { headers: state.accessToken ? { Authorization: `Bearer ${state.accessToken}` } : {} });
	if (res.status === 401 && !retried && (await refreshSession())) return apiBlob(path, true);
	if (!res.ok) {
		let data = null;
		try { data = await res.json(); } catch { data = null; }
		throw new ApiError(res.status, data?.detail || data?.title || `Request failed (${res.status})`);
	}
	return res.blob();
}

/** Swaps the stored refresh token for a new pair; concurrent callers share one request. */
function refreshSession() {
	const refreshToken = sessionStorage.getItem(REFRESH_KEY);
	if (!refreshToken) return Promise.resolve(false);
	refreshing ??= api('/api/auth/refresh', { method: 'POST', body: { refreshToken } })
		.then((tokens) => { setSession(tokens); return true; })
		.catch(() => { clearSession(); return false; })
		.finally(() => { refreshing = null; });
	return refreshing;
}

function setSession(tokens) {
	state.accessToken = tokens.accessToken;
	sessionStorage.setItem(REFRESH_KEY, tokens.refreshToken);
}

function clearSession() {
	state.accessToken = null;
	state.user = null;
	sessionStorage.removeItem(REFRESH_KEY);
}

async function login(email, password) {
	setSession(await api('/api/auth/login', { method: 'POST', body: { email, password } }));
	state.user = await api('/api/users/me', { auth: true });
	updateNav();
}

async function logout() {
	const refreshToken = sessionStorage.getItem(REFRESH_KEY);
	if (refreshToken) await api('/api/auth/logout', { method: 'POST', body: { refreshToken } }).catch(() => {});
	clearSession();
	updateNav();
	location.hash = '#/';
	route();
}

// ---------- UI helpers ----------

/** Tells screen reader users about a change they can't see where they are: politely, or (errors) right away. */
function announce(message, kind = 'info') {
	const region = document.getElementById(kind === 'error' ? 'announce-alert' : 'announce');
	region.textContent = '';
	// A new text node a moment later: the same message twice in a row is announced twice
	setTimeout(() => { region.textContent = message; }, 50);
}

function toast(message, kind = 'info') {
	announce(message, kind);
	const el = document.createElement('div');
	el.setAttribute('aria-hidden', 'true'); // announced above; the toast itself is for the eyes
	el.className = `toast ${kind === 'error' ? 'error' : ''}`;
	el.textContent = message;
	document.getElementById('toasts').append(el);
	setTimeout(() => el.remove(), 5000);
}

function updateNav() {
	const loggedIn = Boolean(state.user);
	document.querySelectorAll('[data-auth-only]').forEach((el) => { el.hidden = !loggedIn; });
	document.getElementById('login-btn').hidden = loggedIn;
	document.querySelector('.who').textContent = state.user ? state.user.email : '';
	document.querySelectorAll('[data-admin-only]').forEach((el) => { el.hidden = state.user?.role !== 'ADMIN'; });
	// Verification is optional: unverified users can still book, they just see this reminder
	document.getElementById('verify-banner').hidden = !loggedIn || state.user.emailVerified !== false;
	document.getElementById('verify-email').textContent = state.user ? state.user.email : '';
}

async function resendVerification() {
	try {
		await api('/api/users/me/verification-email', { method: 'POST', auth: true });
		toast(`Sent. Check ${state.user.email} (and the spam folder).`);
	} catch (e) {
		if (e.status === 409) { // verified meanwhile, e.g. in another tab
			state.user = await api('/api/users/me', { auth: true }).catch(() => state.user);
			updateNav();
		}
		toast(e.message, 'error');
	}
}

function every(ms, fn) {
	state.timers.push(setInterval(fn, ms));
}

function stopTimers() {
	state.timers.forEach(clearInterval);
	state.timers = [];
}

/** Opens the login/register dialog; resolves true once logged in. */
function requireLogin() {
	if (state.user) return Promise.resolve(true);
	const dialog = document.getElementById('auth-dialog');
	const form = document.getElementById('auth-form');
	const error = document.getElementById('auth-error');
	let register = false;

	const setMode = (isRegister) => {
		register = isRegister;
		document.getElementById('auth-title').textContent = isRegister ? 'Create an account' : 'Log in';
		document.getElementById('auth-submit').textContent = isRegister ? 'Register' : 'Log in';
		document.getElementById('auth-toggle').textContent = isRegister ? 'Have an account? Log in' : 'No account? Register';
		form.querySelector('[data-register-only]').hidden = !isRegister;
		form.password.autocomplete = isRegister ? 'new-password' : 'current-password';
		error.hidden = true;
	};

	return new Promise((resolve) => {
		const submit = async (event) => {
			event.preventDefault();
			error.hidden = true;
			const { name, email, password } = Object.fromEntries(new FormData(form));
			try {
				if (register) {
					await api('/api/auth/register', { method: 'POST', body: { name: name || email.split('@')[0], email, password } });
				}
				await login(email, password);
				finish(true);
				toast(register ? `Welcome, ${state.user.name}! We emailed you a link to confirm your address.` : `Welcome, ${state.user.name}`);
			} catch (e) {
				error.textContent = e.message;
				error.hidden = false;
			}
		};
		let finished = false;
		const finish = (ok) => {
			if (finished) return;
			finished = true;
			form.removeEventListener('submit', submit);
			if (dialog.open) dialog.close();
			form.reset();
			resolve(ok);
		};
		form.addEventListener('submit', submit);
		document.getElementById('auth-toggle').onclick = () => setMode(!register);
		document.getElementById('auth-cancel').onclick = () => finish(false);
		document.getElementById('auth-forgot').onclick = () => finish(false); // the link itself goes to #/forgot
		document.getElementById('auth-demo').onclick = () => {
			setMode(false);
			form.email.value = DEMO.email;
			form.password.value = DEMO.password;
			form.requestSubmit();
		};
		dialog.onclose = () => finish(false); // Esc or any other way of closing
		setMode(false);
		dialog.showModal();
	});
}

// ---------- Pages ----------

async function renderHome(params) {
	const q = params.get('q') || '';
	const city = params.get('city') || '';
	const page = Number(params.get('page') || 0);
	app.innerHTML = `
		<h1>Now booking</h1>
		<form class="search" id="search">
			<input name="q" placeholder="Search movies" value="${esc(q)}" aria-label="Search movies">
			<input name="city" placeholder="City" value="${esc(city)}" aria-label="City">
			<button class="btn btn-primary">Search</button>
		</form>
		<div id="results"><p class="muted">Loading shows…</p></div>`;

	document.getElementById('search').onsubmit = (event) => {
		event.preventDefault();
		const form = new URLSearchParams(new FormData(event.target));
		[...form.keys()].forEach((key) => { if (!form.get(key)) form.delete(key); });
		location.hash = `#/?${form}`;
	};

	const query = new URLSearchParams({ size: '12', page: String(page) });
	if (q) query.set('q', q);
	if (city) query.set('city', city);
	const results = await api(`/api/shows/search?${query}`);
	const box = document.getElementById('results');
	if (results.content.length === 0) {
		box.innerHTML = `<div class="card empty"><p>No upcoming shows match.</p></div>`;
		return;
	}
	box.innerHTML = `
		<div class="shows">
			${results.content.map((show) => `
				<a class="card show-card" href="#/show/${show.id}">
					<span class="title">${esc(show.title)}</span>
					<span class="muted">${esc(show.venueName)}</span>
					<span>${esc(when.format(new Date(show.startTime)))}</span>
					<span class="price">${esc(priceLabel(show))}</span>
				</a>`).join('')}
		</div>
		<div class="row spread pager">
			<button class="btn btn-ghost" id="prev" ${page === 0 ? 'disabled' : ''}>Previous</button>
			<span class="muted small">Page ${results.page + 1} of ${Math.max(results.totalPages, 1)}</span>
			<button class="btn btn-ghost" id="next" ${page + 1 >= results.totalPages ? 'disabled' : ''}>Next</button>
		</div>`;
	const go = (p) => { const next = new URLSearchParams(params); next.set('page', p); location.hash = `#/?${next}`; };
	document.getElementById('prev').onclick = () => go(page - 1);
	document.getElementById('next').onclick = () => go(page + 1);
}

async function renderShow(showId) {
	const show = await api(`/api/shows/${showId}`);
	if (show.status === 'CANCELLED') {
		app.innerHTML = `<a href="#/" class="small">← All shows</a>
			<div class="card empty"><h1>${esc(show.title)}</h1><p><span class="status CANCELLED">CANCELLED</span></p>
			<p class="muted">This show has been cancelled. Anyone who had booked it has been refunded and emailed.</p>
			<a class="btn btn-primary" href="#/">Find another show</a></div>`;
		return;
	}
	let seats = await api(`/api/shows/${showId}/seats`);
	const selected = new Set();
	let hold = null; // { seatIds, seatNumbers, expiresAt, idempotencyKey, offer, promo }
	let busy = false;
	// The promo code field: what is typed survives the redraws (every 10 s), and why a code was refused
	let promoDraft = '';
	let promoError = '';
	// "Pick for me": how many seats and which category; kept across the redraws
	let pickCount = 2;
	let pickCategory = '';
	let wait = null; // your place on this show's waitlist: { status, position, seats, offeredSeatIds, ... }

	// A waitlist offer means the seats are already held for you: the page goes on as if you had just held them
	const loadWait = async (whileHere = false) => {
		if (!state.user) { wait = null; return; }
		const mine = await api('/api/waitlist/me', { auth: true }).catch(() => []);
		wait = mine.find((e) => e.showId === Number(showId)) ?? null;
		if (wait?.status === 'OFFERED' && !hold) {
			hold = { seatIds: wait.offeredSeatIds, seatNumbers: wait.offeredSeatNumbers, expiresAt: wait.offerExpiresAt,
				idempotencyKey: uuid(), offer: true };
			// An offer that arrives while you're on the page changes the summary below the seat map: say so
			if (whileHere) announce(`From the waitlist: seats ${hold.seatNumbers.join(', ')} are held for you. ${minutesLeft()} to book them.`);
		}
	};
	const minutesLeft = () => {
		const minutes = Math.max(1, Math.round((new Date(hold.expiresAt) - Date.now()) / 60_000));
		return `${minutes} minute${minutes > 1 ? 's' : ''}`;
	};
	const soldOut = () => !seats.some((s) => s.status === 'AVAILABLE');
	await loadWait();

	app.innerHTML = `
		<a href="#/" class="small">← All shows</a>
		<h1>${esc(show.title)}</h1>
		<p class="muted">${esc(show.venueName)} · ${esc(when.format(new Date(show.startTime)))} · <span id="seat-prices"></span></p>
		${show.ratingCount ? `<p>${ratingSummary(show)}</p>` : ''}
		<div class="card">
			<div class="screen">SCREEN</div>
			<p class="sr-only" id="seatmap-help">Arrow keys move between seats, Space picks one.</p>
			<div class="seatmap" id="seatmap" role="group" aria-label="Seat map" aria-describedby="seatmap-help"></div>
			<div class="legend">
				<span><i class="l-free"></i>Available</span><span><i class="l-sel"></i>Your selection</span>
				<span><i class="l-held"></i>Held</span><span><i class="l-booked"></i>Booked</span>
			</div>
			<div class="legend" id="category-legend"></div>
		</div>
		<div class="card summary" id="summary"></div>
		${testModeNote()}
		<section class="reviews" aria-labelledby="reviews-h">
			<div class="row spread"><h2 id="reviews-h">Reviews</h2>
				${new Date(show.startTime) <= new Date() ? `<a class="btn btn-ghost" href="#/review/${esc(show.id)}">Rate this show</a>` : ''}</div>
			<div id="reviews"><p class="muted">Loading reviews…</p></div>
		</section>`;
	renderShowReviews(show);

	const rows = () => {
		const byRow = new Map();
		for (const seat of seats) {
			const [, row, num] = seat.seatNumber.match(/^([A-Z]+)(\d+)$/) || [null, '?', '0'];
			if (!byRow.has(row)) byRow.set(row, []);
			byRow.get(row).push({ ...seat, num: Number(num) });
		}
		return [...byRow.entries()].sort(([a], [b]) => a.localeCompare(b))
			.map(([row, list]) => [row, list.sort((a, b) => a.num - b.num)]);
	};

	// Each seat at its own category's price (the server computes the real total the same way)
	const priceOf = (id) => Number(seats.find((s) => s.id === id)?.price ?? show.price);
	const total = (ids) => money.format([...ids].reduce((sum, id) => sum + priceOf(id), 0));
	const aisles = new Set(show.aisleAfter ?? []);
	const usedCategories = () => CATEGORIES.filter((c) => seats.some((s) => (s.category ?? 'REGULAR') === c));
	const seatLabel = (seat, state) => `Seat ${seat.seatNumber}, ${state}, ${seat.category && seat.category !== 'REGULAR'
		? `${CATEGORY_NAMES[seat.category]} ` : ''}${money.format(seat.price ?? show.price)}`;

	const drawPrices = () => {
		const used = usedCategories();
		document.getElementById('seat-prices').textContent = used.length > 1
			? used.map((c) => `${CATEGORY_NAMES[c]} ${money.format(show.prices?.[c] ?? show.price)}`).join(' · ')
			: `${money.format(show.price)} per seat`;
		document.getElementById('category-legend').innerHTML = used.length > 1
			? used.map((c) => `<span><i class="l-cat cat-${c.toLowerCase()}"></i>${esc(CATEGORY_NAMES[c][0].toUpperCase() + CATEGORY_NAMES[c].slice(1))} ${esc(money.format(show.prices?.[c] ?? show.price))}</span>`).join('')
			: '';
	};

	// Redraws replace the seat map and summary, so whatever had keyboard focus there gets it back afterwards
	let focusKey = null;
	let tabStop = null; // the seat that Tab lands on: the one last focused
	let focusNext = null; // set by an action that moves focus on purpose (e.g. to Remove after applying a code)
	const rememberFocus = () => {
		if (focusNext) { // wins over whatever had focus while the action ran
			focusKey = { summary: focusNext };
			focusNext = null;
			return;
		}
		const el = document.activeElement;
		if (el?.closest('#seatmap')) focusKey = { seat: el.dataset.id };
		else if (el?.closest('#summary')) focusKey = { summary: el.id, caret: el.selectionStart ?? null };
		else if (el && el !== document.body && !el.closest('dialog')) focusKey = null; // focus went elsewhere on purpose
	};
	const restoreFocus = () => {
		if (!focusKey || document.querySelector('dialog[open]')) return;
		const summary = document.getElementById('summary');
		const el = focusKey.seat ? document.querySelector(`#seatmap [data-id="${focusKey.seat}"]`)
			: document.getElementById(focusKey.summary)
				?? summary.querySelector('.btn-primary:not([disabled])') ?? summary.querySelector('button:not([disabled]), select');
		if (el && !el.disabled) { // a button disabled while busy: try again after the next redraw
			el.focus();
			if (focusKey.caret != null && el.setSelectionRange) el.setSelectionRange(focusKey.caret, focusKey.caret);
			focusKey = null;
		}
	};

	const draw = () => {
		const mine = new Set(hold?.seatIds ?? []);
		const seatmap = document.getElementById('seatmap');
		if (!seatmap) return; // a refresh that finished after leaving the page
		rememberFocus();
		if (!seats.some((s) => s.id === tabStop)) tabStop = (seats.find((s) => s.status === 'AVAILABLE') ?? seats[0])?.id;
		seatmap.innerHTML = rows().map(([row, list]) => `
			<div class="seat-row" role="group" aria-label="Row ${esc(row)}">
				<span class="row-label" aria-hidden="true">${esc(row)}</span>
				${list.map((seat) => {
					const isMine = mine.has(seat.id);
					const cls = isMine ? 'mine' : selected.has(seat.id) ? 'selected' : seat.status.toLowerCase();
					const disabled = hold || (seat.status !== 'AVAILABLE' && !isMine);
					return `<button class="seat ${cls} cat-${(seat.category ?? 'REGULAR').toLowerCase()}" data-id="${seat.id}" ${disabled ? 'aria-disabled="true"' : ''}
						tabindex="${seat.id === tabStop ? 0 : -1}" aria-pressed="${selected.has(seat.id)}"
						aria-label="${esc(seatLabel(seat, isMine ? 'held by you' : seat.status.toLowerCase()))}">${seat.num}</button>${aisles.has(seat.num) ? '<span class="aisle"></span>' : ''}`;
				}).join('')}
			</div>`).join('');

		drawPrices();
		const summary = document.getElementById('summary');
		if (hold) {
			const sum = hold.promo ? money.format(hold.promo.total) : total(hold.seatIds);
			const action = state.config.paymentsEnabled ? `Pay ${sum}` : `Confirm booking · ${sum}`;
			summary.innerHTML = `
				<div>${hold.offer ? 'From the waitlist: ' : ''}<strong>${esc(hold.seatNumbers.join(', '))}</strong> held for you · <span class="timer" id="timer"></span></div>
				${promoHtml()}
				<div class="row">
					<button class="btn btn-ghost" id="release" ${busy ? 'disabled' : ''}>${hold.offer ? 'Decline' : 'Release'}</button>
					<button class="btn btn-primary" id="confirm" ${busy ? 'disabled' : ''}>${esc(action)}</button>
				</div>`;
			document.getElementById('release').onclick = release;
			document.getElementById('confirm').onclick = state.config.paymentsEnabled ? pay : confirm;
			bindPromo();
			tick();
		} else if (soldOut() && wait?.status === 'WAITING') {
			summary.innerHTML = `
				<div id="waitlist-status"><strong>You're #${esc(wait.position)} on the waitlist</strong> for ${esc(wait.seats)} seat${wait.seats > 1 ? 's' : ''}.
					<span class="muted">When seats come free they're held for you, and we email you.</span></div>
				<button class="btn btn-ghost" id="leave-waitlist" ${busy ? 'disabled' : ''}>Leave waitlist</button>`;
			document.getElementById('leave-waitlist').onclick = leaveWaitlist;
		} else if (soldOut()) {
			summary.innerHTML = `
				<div><strong>Sold out.</strong> <span class="muted">Join the waitlist: when seats come free, they're held for you.</span></div>
				<div class="row">
					<label for="waitlist-seats" class="small">Seats</label>
					<select id="waitlist-seats" class="waitlist-seats">${[1, 2, 3, 4, 5, 6].map((n) => `<option value="${n}">${n}</option>`).join('')}</select>
					<button class="btn btn-primary" id="join-waitlist" ${busy ? 'disabled' : ''}>Join waitlist</button>
				</div>`;
			document.getElementById('join-waitlist').onclick = joinWaitlist;
		} else {
			const count = selected.size;
			const categories = usedCategories();
			summary.innerHTML = `
				<div>${count ? `<strong>${count} seat${count > 1 ? 's' : ''}</strong> · ${total(selected)}` : '<span class="muted">Pick up to 10 seats</span>'}</div>
				<button class="btn btn-primary" id="hold" ${count === 0 || busy ? 'disabled' : ''}>Hold seats</button>
				<form class="pick" id="pick-form" novalidate>
					<span class="small muted">Or let us choose:</span>
					<span class="pick-field"><label for="pick-count" class="small">Seats</label>
					<select id="pick-count">${[1, 2, 3, 4, 5, 6, 7, 8, 9, 10].map((n) => `<option value="${n}" ${n === pickCount ? 'selected' : ''}>${n}</option>`).join('')}</select></span>
					${categories.length > 1 ? `<span class="pick-field"><label for="pick-category" class="small">Type</label>
					<select id="pick-category"><option value="">Any</option>${categories.map((c) => `<option value="${c}" ${c === pickCategory ? 'selected' : ''}>${esc(CATEGORY_NAMES[c][0].toUpperCase() + CATEGORY_NAMES[c].slice(1))}</option>`).join('')}</select></span>` : ''}
					<button class="btn btn-ghost btn-small" id="pick-best" ${busy ? 'disabled' : ''}>Pick best seats</button>
				</form>`;
			document.getElementById('hold').onclick = holdSeats;
			const pickForm = document.getElementById('pick-form');
			document.getElementById('pick-count').onchange = (e) => { pickCount = Number(e.target.value); };
			const categorySelect = document.getElementById('pick-category');
			if (categorySelect) categorySelect.onchange = (e) => { pickCategory = e.target.value; };
			else pickCategory = '';
			pickForm.onsubmit = pickBest;
		}
		restoreFocus();
	};

	// A code field under the held seats; once applied, the discount with a way to remove it
	const promoHtml = () => (hold.promo
		? `<div class="promo" id="promo-applied"><span><strong>${esc(hold.promo.code)}</strong> applied:
				${esc(money.format(hold.promo.discount))} off${hold.promo.description ? ` (${esc(hold.promo.description)})` : ''}
				<span class="muted small">· was ${esc(money.format(hold.promo.subtotal))}</span></span>
				<button type="button" class="btn btn-ghost btn-small" id="promo-remove" ${busy ? 'disabled' : ''}
					aria-label="Remove promo code ${esc(hold.promo.code)}">Remove</button></div>`
		: `<form class="promo" id="promo-form" novalidate>
				<label for="promo-code" class="small">Promo code</label>
				<input id="promo-code" name="code" maxlength="20" autocomplete="off" autocapitalize="characters" spellcheck="false"
					value="${esc(promoDraft)}" ${promoError ? 'aria-invalid="true" aria-describedby="promo-error"' : ''}>
				<button class="btn btn-ghost btn-small" id="promo-apply" ${busy ? 'disabled' : ''}>Apply</button>
				${promoError ? `<p class="form-error" id="promo-error">${esc(promoError)}</p>` : ''}
			</form>`);

	const bindPromo = () => {
		const form = document.getElementById('promo-form');
		if (form) {
			form.code.oninput = () => { promoDraft = form.code.value; };
			form.onsubmit = applyPromo;
		}
		const remove = document.getElementById('promo-remove');
		if (remove) {
			remove.onclick = () => {
				hold.promo = null;
				hold.idempotencyKey = uuid(); // another price: not a retry of the earlier request
				focusNext = 'promo-code';
				announce(`Promo code removed. Total ${total(hold.seatIds)}.`);
				draw();
			};
		}
	};

	// A code refused when booking or paying (used up meanwhile, say): back to the field, with the reason
	const promoRefused = (error) => {
		hold.promo = null;
		hold.idempotencyKey = uuid();
		promoError = error.message;
		focusNext = 'promo-code';
		announce(`Promo code not applied: ${error.message}`, 'error');
	};

	const reload = async () => {
		seats = await api(`/api/shows/${showId}/seats`);
		// Drop selections someone else grabbed meanwhile
		for (const id of selected) {
			if (seats.find((s) => s.id === id)?.status !== 'AVAILABLE') selected.delete(id);
		}
		// While you wait, an offer shows up here within one poll
		if (!hold && state.user && (wait || soldOut())) await loadWait(true);
		draw();
	};

	const tick = () => {
		const el = document.getElementById('timer');
		if (!hold || !el) return;
		const left = Math.max(0, Math.round((new Date(hold.expiresAt) - Date.now()) / 1000));
		el.textContent = `${Math.floor(left / 60)}:${String(left % 60).padStart(2, '0')} left`;
		el.classList.toggle('low', left < 60);
		// The countdown itself is silent (it changes every second); one warning near the end
		if (left <= 60 && left > 0 && !hold.warned) {
			hold.warned = true;
			announce(`One minute left to ${hold.offer ? 'book the seats offered to you' : 'confirm your seats'}.`);
		}
		if (left === 0) {
			toast(hold.offer ? 'Your waitlist offer expired; the seats went to the next person in line.'
				: 'Your hold expired; the seats were released.', 'error');
			if (hold.offer) wait = null;
			hold = null;
			reload();
		}
	};

	const run = async (action) => {
		busy = true;
		draw();
		try { await action(); } catch (e) { toast(e.message, 'error'); } finally { busy = false; if (document.getElementById('seatmap')) draw(); }
	};

	async function holdSeats() {
		if (!(await requireLogin())) return;
		await run(async () => {
			const seatIds = [...selected];
			const response = await api('/api/bookings/hold', { method: 'POST', auth: true, body: { showId: Number(showId), seatIds } });
			hold = { ...response, idempotencyKey: uuid() };
			promoError = '';
			selected.clear();
			await reload();
			announce(`Seats ${hold.seatNumbers.join(', ')} are held for you for ${minutesLeft()}.`);
		});
	}

	// The best seats together, chosen and held by the server in one step (someone faster: it takes the next best)
	async function pickBest(event) {
		event.preventDefault();
		if (!(await requireLogin())) return;
		await run(async () => {
			const response = await api('/api/bookings/hold/best', {
				method: 'POST', auth: true, body: { showId: Number(showId), count: pickCount, category: pickCategory || null },
			});
			hold = { ...response, idempotencyKey: uuid() };
			promoError = '';
			selected.clear();
			tabStop = response.seatIds[0];
			focusNext = 'confirm';
			await reload();
			announce(`We picked seats ${hold.seatNumbers.join(', ')}. They are held for you for ${minutesLeft()}.`);
		});
	}

	async function release() {
		await run(async () => {
			if (hold.offer) {
				// Declining leaves the waitlist; the seats go to the next person in line right away
				await api(`/api/waitlist/${showId}`, { method: 'DELETE', auth: true });
				wait = null;
				toast('Offer declined: the seats went to the next person in line.');
			} else {
				await api('/api/bookings/hold/release', { method: 'POST', auth: true, body: { showId: Number(showId), seatIds: hold.seatIds } });
			}
			hold = null;
			await reload();
		});
	}

	async function joinWaitlist() {
		const seatCount = Number(document.getElementById('waitlist-seats').value);
		if (!(await requireLogin())) return;
		await run(async () => {
			wait = await api('/api/waitlist', { method: 'POST', auth: true, body: { showId: Number(showId), seats: seatCount } });
			toast(`You're #${wait.position} on the waitlist.`);
		});
	}

	async function leaveWaitlist() {
		await run(async () => {
			await api(`/api/waitlist/${showId}`, { method: 'DELETE', auth: true });
			wait = null;
			toast('You left the waitlist.');
		});
	}

	// Checks the code and shows the new total; nothing is used up until booking (or paying)
	async function applyPromo(event) {
		event.preventDefault();
		const code = promoDraft.trim();
		if (!code) {
			promoError = 'Type a code first.';
			focusNext = 'promo-code';
			announce(promoError, 'error');
			draw();
			return;
		}
		await run(async () => {
			try {
				hold.promo = await api('/api/promo-codes/preview', {
					method: 'POST', auth: true, body: { code, showId: Number(showId), seatIds: hold.seatIds },
				});
				hold.idempotencyKey = uuid();
				promoDraft = '';
				promoError = '';
				focusNext = 'promo-remove';
				announce(`Code ${hold.promo.code} applied: ${money.format(hold.promo.discount)} off. New total ${money.format(hold.promo.total)}.`);
			} catch (e) {
				if (e.status !== 422 && e.status !== 429) throw e;
				promoError = e.message;
				focusNext = 'promo-code';
				announce(`Promo code not applied: ${e.message}`, 'error');
			}
		});
	}

	// Payments off: confirm directly. The same Idempotency-Key for this hold makes a retry or double click safe.
	async function confirm() {
		await run(async () => {
			let booking;
			try {
				booking = await api('/api/bookings', {
					method: 'POST', auth: true, headers: { 'Idempotency-Key': hold.idempotencyKey },
					body: { showId: Number(showId), seatIds: hold.seatIds, promoCode: hold.promo?.code },
				});
			} catch (e) {
				if (e.status !== 422 || !hold.promo) throw e;
				promoRefused(e);
				return;
			}
			stopTimers();
			showSuccess(booking.seats, booking.totalAmount, booking.id);
		});
	}

	// Payments on: checkout -> Razorpay Checkout -> verify. The webhook confirms too, even if this page is closed.
	async function pay() {
		await run(async () => {
			let order;
			try {
				order = await api('/api/bookings/checkout', {
					method: 'POST', auth: true, body: { showId: Number(showId), seatIds: hold.seatIds, promoCode: hold.promo?.code },
				});
			} catch (e) {
				if (e.status !== 422 || !hold.promo) throw e;
				promoRefused(e);
				return;
			}
			hold.expiresAt = order.expiresAt;
			await loadScript('https://checkout.razorpay.com/v1/checkout.js');
			await new Promise((resolve) => {
				const checkout = new window.Razorpay({
					key: order.razorpayKeyId,
					order_id: order.orderId,
					amount: order.amount,
					currency: order.currency,
					name: 'BookMySeat',
					description: `${show.title} · ${hold.seatNumbers.join(', ')}${order.promoCode ? ` · ${order.promoCode}` : ''}`,
					prefill: { email: state.user.email, name: state.user.name },
					theme: { color: getComputedStyle(document.documentElement).getPropertyValue('--accent').trim() },
					handler: async (result) => {
						try {
							const payment = await api('/api/payments/verify', {
								method: 'POST', auth: true,
								body: { orderId: result.razorpay_order_id, paymentId: result.razorpay_payment_id, signature: result.razorpay_signature },
							});
							finishPayment(payment);
						} catch (e) {
							toast(e.message, 'error');
						}
						resolve();
					},
					modal: { ondismiss: () => { toast('Payment not completed. Your seats stay held until the timer runs out.'); resolve(); } },
				});
				checkout.on('payment.failed', (r) => toast(r.error?.description || 'Payment failed; you can try again.', 'error'));
				checkout.open();
			});
		});
	}

	function finishPayment(payment) {
		if (payment.status === 'CONFIRMED') {
			stopTimers();
			showSuccess(hold.seatNumbers, payment.amount, payment.bookingId);
		} else if (payment.status.startsWith('REFUND')) {
			stopTimers();
			app.innerHTML = `<div class="card success"><div class="big" aria-hidden="true">↩</div><h1>Seats no longer available</h1>
				<p>Your payment arrived too late to book these seats (they were taken, or the promo code ran out), so it is being refunded in full.</p>
				<a class="btn btn-primary" href="#/">Back to shows</a></div>`;
			focusStart(app.querySelector('h1'));
		}
	}

	const seatmapEl = app.querySelector('#seatmap');
	seatmapEl.addEventListener('click', (event) => {
		const button = event.target.closest('.seat');
		if (!button || button.getAttribute('aria-disabled') === 'true') return;
		const id = Number(button.dataset.id);
		if (selected.has(id)) selected.delete(id);
		else if (selected.size < 10) selected.add(id);
		else toast('You can pick up to 10 seats.', 'error');
		draw();
	});

	// Arrow keys move between seats (up and down: the nearest seat number in the next row), Home and End along a row
	seatmapEl.addEventListener('focusin', (event) => { tabStop = Number(event.target.dataset.id) || tabStop; });
	seatmapEl.addEventListener('keydown', (event) => {
		const button = event.target.closest('.seat');
		if (!button) return;
		const grid = [...seatmapEl.querySelectorAll('.seat-row')].map((row) => [...row.querySelectorAll('.seat')]);
		const r = grid.findIndex((row) => row.includes(button));
		const c = grid[r].indexOf(button);
		const nearest = (row) => row?.reduce((best, b) => (Math.abs(b.textContent - button.textContent)
			< Math.abs(best.textContent - button.textContent) ? b : best));
		const target = {
			ArrowLeft: grid[r][c - 1], ArrowRight: grid[r][c + 1], ArrowUp: nearest(grid[r - 1]), ArrowDown: nearest(grid[r + 1]),
			Home: grid[r][0], End: grid[r][grid[r].length - 1],
		}[event.key];
		if (!(event.key in { ArrowLeft: 1, ArrowRight: 1, ArrowUp: 1, ArrowDown: 1, Home: 1, End: 1 })) return;
		event.preventDefault();
		if (!target) return;
		button.tabIndex = -1;
		target.tabIndex = 0;
		target.focus();
	});

	draw();
	every(1000, tick);
	every(POLL_MS, () => { if (!busy) reload().catch(() => {}); }); // other people's holds show up live
}

// Shown only with Razorpay *test* keys, so reviewers can pay without hunting for test details
function testModeNote() {
	if (!state.config.paymentsEnabled || !state.config.razorpayKeyId?.startsWith('rzp_test_')) return '';
	return `<p class="test-note"><strong>Test mode, no real money.</strong> In the Razorpay window choose <strong>UPI</strong>
		and enter <code>success@razorpay</code> to pay, or <code>failure@razorpay</code> to see a failed payment.</p>`;
}

function showSuccess(seatNumbers, amount, bookingId) {
	app.innerHTML = `
		<div class="card success">
			<div class="big" aria-hidden="true">🎟️</div>
			<h1>You're booked!</h1>
			<p>Seats <strong>${esc(seatNumbers.join(', '))}</strong> · ${esc(money.format(amount))} · booking #${esc(bookingId)}</p>
			<div class="row center">
				<a class="btn btn-primary" href="#/bookings">My bookings</a>
				<a class="btn btn-ghost" href="#/">More shows</a>
			</div>
		</div>`;
	focusStart(app.querySelector('h1'));
}

async function renderBookings() {
	if (!(await requireLogin())) {
		location.hash = '#/';
		return;
	}
	const [bookings, waitlist, reviews] = await Promise.all([
		api('/api/bookings/me', { auth: true }),
		api('/api/waitlist/me', { auth: true }).catch(() => []),
		api('/api/reviews/me', { auth: true }).catch(() => []),
	]);
	const reviewOf = new Map(reviews.map((r) => [r.showId, r]));
	// After the show: no cancelling any more, but a rating (or the one you gave, to change)
	const afterShow = (b) => {
		const mine = reviewOf.get(b.showId);
		return mine
			? `<a class="btn btn-ghost" href="#/review/${esc(b.showId)}" aria-label="Your rating of ${esc(b.showTitle)}: ${mine.rating} out of 5. Edit it"><span aria-hidden="true">${starsText(mine.rating)} · Edit</span></a>`
			: `<a class="btn btn-primary" href="#/review/${esc(b.showId)}" aria-label="Rate ${esc(b.showTitle)}">Rate this show</a>`;
	};
	const waitlistHtml = waitlist.length === 0 ? '' : `
		<h2>Waitlist</h2>
		<div class="bookings" id="waitlist">
			${waitlist.map((w) => `
				<div class="card row spread">
					<div>
						<div class="row"><strong>${esc(w.showTitle)}</strong><span class="status ${esc(w.status)}">${w.status === 'OFFERED'
							? 'SEATS HELD FOR YOU' : `#${esc(w.position)} IN LINE`}</span></div>
						<div class="muted small">${esc(w.venueName)} · ${esc(when.format(new Date(w.showStartTime)))}</div>
						<div>${w.status === 'OFFERED'
							? `Seats ${esc(w.offeredSeatNumbers.join(', '))} until ${esc(when.format(new Date(w.offerExpiresAt)))}`
							: `${esc(w.seats)} seat${w.seats > 1 ? 's' : ''} wanted`}</div>
					</div>
					<div class="row">${w.status === 'OFFERED'
						? `<a class="btn btn-primary" href="#/show/${esc(w.showId)}">Book now</a>`
						: `<button class="btn btn-ghost" data-leave="${esc(w.showId)}">Leave</button>`}</div>
				</div>`).join('')}
		</div>`;
	if (bookings.length === 0) {
		app.innerHTML = `<h1>My bookings</h1>${waitlistHtml}<div class="card empty"><p>No bookings yet.</p><a class="btn btn-primary" href="#/">Find a show</a></div>`;
		bindLeave();
		return;
	}
	app.innerHTML = `
		<h1>My bookings</h1>
		${waitlistHtml}
		${waitlist.length ? '<h2>Bookings</h2>' : ''}
		<div class="bookings">
			${bookings.map((b) => `
				<div class="card row spread">
					<div>
						<div class="row"><strong>${esc(b.showTitle)}</strong><span class="status ${esc(b.status)}">${esc(b.status)}</span></div>
						<div class="muted small">${esc(b.venueName)} · ${esc(when.format(new Date(b.showStartTime)))}</div>
						<div>Seats ${esc(b.seats.join(', '))} · <span class="price">${esc(money.format(b.totalAmount))}</span>${Number(b.discountAmount) > 0
						? ` <span class="muted small">(${esc(money.format(b.discountAmount))} off with ${esc(b.promoCode)})</span>` : ''}</div>
					</div>
					${b.status === 'CONFIRMED' ? `<div class="row">
						<a class="btn btn-primary" href="#/ticket/${b.id}">Ticket</a>
						${calendarButton(b.id, b.showTitle)}
						${new Date(b.showStartTime) <= new Date() ? afterShow(b) : `<button class="btn btn-danger" data-cancel="${b.id}">Cancel</button>`}</div>` : ''}
				</div>`).join('')}
		</div>`;
	bindLeave();
	bindCalendar();
	app.querySelectorAll('[data-cancel]').forEach((button) => {
		button.onclick = async () => {
			if (!confirm('Cancel this booking? The seats will be released.')) return;
			try {
				await api(`/api/bookings/${button.dataset.cancel}/cancel`, { method: 'POST', auth: true });
				toast(state.config.paymentsEnabled ? 'Booking cancelled. Your refund is on its way.' : 'Booking cancelled.');
				renderBookings();
			} catch (e) {
				toast(e.message, 'error');
			}
		};
	});
}

function bindLeave() {
	app.querySelectorAll('[data-leave]').forEach((button) => {
		button.onclick = async () => {
			try {
				await api(`/api/waitlist/${button.dataset.leave}`, { method: 'DELETE', auth: true });
				toast('You left the waitlist.');
				renderBookings();
			} catch (e) {
				toast(e.message, 'error');
			}
		};
	});
}

/** The show page's reviews: newest or highest rated first, 10 at a time with "Show more". */
function renderShowReviews(show) {
	const box = document.getElementById('reviews');
	let sort = 'newest';
	let page = 0;
	const item = (r) => `
		<li class="review">
			<div class="row"><span class="stars-read" aria-hidden="true">${starsText(r.rating)}</span>
				<span class="sr-only">${r.rating} out of 5:</span>
				<strong>${esc(r.author)}</strong>
				<span class="muted small">${esc(day.format(new Date(r.createdAt)))}${r.edited ? ' · edited' : ''}</span></div>
			${r.text ? `<p class="review-text">${esc(r.text)}</p>` : ''}
		</li>`;
	const load = async (more) => {
		let result;
		try {
			result = await api(`/api/shows/${show.id}/reviews?sort=${sort}&page=${page}&size=10`);
		} catch (e) {
			box.innerHTML = `<p class="muted">Reviews could not be loaded: ${esc(e.message)}</p>`;
			return;
		}
		if (!more) {
			if (result.totalElements === 0) {
				box.innerHTML = `<p class="muted">No reviews yet.${new Date(show.startTime) <= new Date() ? ' Been to it? Be the first to rate it.' : ' People who go can rate it after the show.'}</p>`;
				return;
			}
			box.innerHTML = `
				<div class="row"><label class="small" for="review-sort">Sort</label>
					<select id="review-sort"><option value="newest">Newest first</option><option value="highest">Highest rated first</option></select></div>
				<ul class="review-list" id="review-list"></ul>
				<button class="btn btn-ghost" id="more-reviews" hidden>Show more reviews</button>`;
			const select = document.getElementById('review-sort');
			select.value = sort;
			select.onchange = () => { sort = select.value; page = 0; load(false); };
			document.getElementById('more-reviews').onclick = () => { page += 1; load(true); };
		}
		const list = document.getElementById('review-list');
		const first = list.children.length;
		list.insertAdjacentHTML('beforeend', result.content.map(item).join(''));
		document.getElementById('more-reviews').hidden = page + 1 >= result.totalPages;
		if (more) {
			// Keyboard users continue where the new reviews start
			const next = list.children[first];
			if (next) { next.tabIndex = -1; next.focus(); }
			announce(`${result.content.length} more reviews`);
		}
	};
	load(false);
}

/** #/review/<showId>: rate a show you went to (1 to 5 stars and an optional text), or change or delete your review. */
async function renderReview(showId) {
	if (!(await requireLogin())) {
		location.hash = `#/show/${showId}`;
		return;
	}
	const [show, bookings, reviews] = await Promise.all([
		api(`/api/shows/${showId}`),
		api('/api/bookings/me', { auth: true }),
		api('/api/reviews/me', { auth: true }),
	]);
	const back = `<a href="#/show/${esc(show.id)}" class="small">← ${esc(show.title)}</a>`;
	const booked = bookings.some((b) => b.showId === show.id && b.status === 'CONFIRMED');
	if (!booked) {
		app.innerHTML = `${back}<div class="card empty"><h1>Rate ${esc(show.title)}</h1>
			<p class="muted">Only people with a booking for this show can review it.</p><a class="btn btn-primary" href="#/show/${esc(show.id)}">See the show</a></div>`;
		return;
	}
	if (new Date(show.startTime) > new Date()) {
		app.innerHTML = `${back}<div class="card empty"><h1>Rate ${esc(show.title)}</h1>
			<p class="muted">You can rate this show once it has started, from ${esc(when.format(new Date(show.startTime)))}.</p>
			<a class="btn btn-primary" href="#/bookings">My bookings</a></div>`;
		return;
	}
	let mine = reviews.find((r) => r.showId === show.id) ?? null;
	app.innerHTML = `
		${back}
		<h1 id="review-heading">${mine ? 'Your review of' : 'Rate'} ${esc(show.title)}</h1>
		<p class="muted">${esc(show.venueName)} · ${esc(when.format(new Date(show.startTime)))}</p>
		<form class="card review-form" id="review-form" novalidate>
			<fieldset class="star-input">
				<legend>Your rating</legend>
				<div class="row">${[1, 2, 3, 4, 5].map((n) => `
					<input type="radio" name="rating" id="star-${n}" value="${n}">
					<label for="star-${n}"><span aria-hidden="true">★</span><span class="sr-only">${n} star${n > 1 ? 's' : ''}, ${RATING_WORDS[n].toLowerCase()}</span></label>`).join('')}
					<span class="muted" id="rating-words" aria-hidden="true"></span></div>
			</fieldset>
			<label class="field" for="review-text">Your review <span class="muted">(optional)</span></label>
			<textarea id="review-text" name="text" rows="5" maxlength="1000" aria-describedby="review-help"></textarea>
			<p class="muted small" id="review-help">What should the next people deciding know? Shown with your first name and initial. <span id="review-count"></span></p>
			<p class="muted small" id="review-hidden" hidden>An admin hid this review: others don't see it and it doesn't count in the rating. You can still change it.</p>
			<p class="form-error" id="review-error" role="alert" hidden></p>
			<div class="row"><button class="btn btn-primary" id="review-save"></button>
				<button type="button" class="btn btn-ghost" id="review-delete" hidden>Delete my review</button></div>
		</form>`;

	const form = document.getElementById('review-form');
	const error = document.getElementById('review-error');
	const text = document.getElementById('review-text');
	const paint = () => {
		const value = Number(form.rating.value || 0);
		// Lit stars are filled (★), the others outlined (☆): told apart by shape, not only by colour
		form.querySelectorAll('.star-input label').forEach((label, i) => {
			label.classList.toggle('on', i < value);
			label.querySelector('[aria-hidden]').textContent = i < value ? '★' : '☆';
		});
		if (value) error.hidden = true; // "Pick a rating" no longer applies
		document.getElementById('rating-words').textContent = value ? RATING_WORDS[value] : 'Pick 1 to 5 stars';
		document.getElementById('review-count').textContent = `${text.value.length} of 1000 characters.`;
	};
	const fill = () => {
		if (mine) {
			form.querySelector(`#star-${mine.rating}`).checked = true;
			text.value = mine.text ?? '';
		}
		document.getElementById('review-heading').textContent = `${mine ? 'Your review of' : 'Rate'} ${show.title}`;
		document.getElementById('review-save').textContent = mine ? 'Save changes' : 'Post review';
		document.getElementById('review-delete').hidden = !mine;
		document.getElementById('review-hidden').hidden = !mine?.hidden;
		paint();
	};
	form.onchange = paint;
	text.oninput = paint;
	fill();

	form.onsubmit = async (event) => {
		event.preventDefault();
		error.hidden = true;
		const rating = Number(form.rating.value || 0);
		if (!rating) {
			error.textContent = 'Pick a rating from 1 to 5 stars.';
			error.hidden = false;
			form.querySelector('#star-1').focus();
			return;
		}
		const save = document.getElementById('review-save');
		save.disabled = true;
		try {
			const first = !mine;
			mine = await api(`/api/shows/${show.id}/reviews/me`, { method: 'PUT', auth: true, body: { rating, text: text.value } });
			fill();
			toast(first ? 'Thanks! Your review is up.' : 'Your review is updated.');
		} catch (e) {
			error.textContent = e.message;
			error.hidden = false;
		} finally {
			save.disabled = false;
		}
	};
	document.getElementById('review-delete').onclick = async () => {
		if (!confirm('Delete your review of this show?')) return;
		try {
			await api(`/api/shows/${show.id}/reviews/me`, { method: 'DELETE', auth: true });
			toast('Your review is deleted.');
			location.hash = `#/show/${show.id}`;
		} catch (e) {
			toast(e.message, 'error');
		}
	};
}

/** "Add to calendar": the booking as an .ics file, which calendar apps open and import. */
function calendarButton(bookingId, showTitle) {
	// The visible text starts the accessible name, which says which booking it is (there is one per booking)
	return `<button class="btn btn-ghost" data-calendar="${esc(bookingId)}" aria-label="Add to calendar: ${esc(showTitle)}">Add to calendar</button>`;
}

function bindCalendar() {
	app.querySelectorAll('[data-calendar]').forEach((button) => {
		button.onclick = async () => {
			button.disabled = true;
			try {
				const id = button.dataset.calendar;
				// Needs the login token, so it's downloaded from memory (a blob: URL) instead of a plain link
				const url = URL.createObjectURL(await apiBlob(`/api/bookings/${id}/ticket.ics`));
				const link = document.createElement('a');
				link.href = url;
				link.download = `booking-${id}.ics`;
				document.body.append(link);
				link.click();
				link.remove();
				setTimeout(() => URL.revokeObjectURL(url), 10000);
				toast('Calendar file downloaded: open it to add the show to your calendar.');
			} catch (e) {
				toast(e.message, 'error');
			} finally {
				button.disabled = false;
			}
		};
	});
}

/** #/ticket/<bookingId>: the e-ticket to show at the entrance. */
async function renderTicket(bookingId) {
	if (!(await requireLogin())) {
		location.hash = '#/';
		return;
	}
	const ticket = await api(`/api/bookings/${bookingId}/ticket`, { auth: true });
	// The QR image needs the login token, so it's fetched and shown from memory (a blob: URL)
	const qr = URL.createObjectURL(await apiBlob(`/api/bookings/${bookingId}/ticket.png`));
	app.innerHTML = `
		<a href="#/bookings" class="small">← My bookings</a>
		<div class="card ticket">
			<p class="muted small">BookMySeat e-ticket · booking #${ticket.bookingId}</p>
			<h1>${esc(ticket.showTitle)}</h1>
			<p>${esc(ticket.venueName)} · ${esc(when.format(new Date(ticket.showStartTime)))}</p>
			<img class="qr" src="${qr}" alt="Ticket QR code for booking ${ticket.bookingId}" width="260" height="260">
			<p class="seats">Seats <strong>${esc(ticket.seats.join(', '))}</strong></p>
			<p class="muted small">${esc(ticket.holderName)} · <code>${esc(ticket.code)}</code></p>
			${ticket.checkedInAt
				? `<p class="status CONFIRMED">CHECKED IN ${esc(when.format(new Date(ticket.checkedInAt)))}</p>`
				: '<p class="muted">Show this QR code at the entrance. Turn your screen brightness up.</p>'}
			<div class="row ticket-actions">${calendarButton(ticket.bookingId, ticket.showTitle)}</div>
		</div>`;
	bindCalendar();
}

/** Landing page of the link in the verification email: #/verify/<token> */
async function renderVerify(token) {
	// Take the one-time token out of the address bar and history (replaceState doesn't trigger a new route)
	history.replaceState(null, '', '#/verify');
	app.innerHTML = '<p class="muted">Confirming your email…</p>';
	try {
		const result = await api('/api/auth/verify-email', { method: 'POST', body: { token } });
		if (state.user && state.user.email === result.email) {
			state.user.emailVerified = true;
			updateNav();
		}
		app.innerHTML = `<div class="card success"><div class="big" aria-hidden="true">✓</div>
			<h1>Email confirmed</h1><p class="muted">${esc(result.email)} is verified. Thanks!</p>
			<a class="btn btn-primary" href="#/">Find a show</a></div>`;
	} catch (e) {
		app.innerHTML = `<div class="card empty"><h1>Link not valid</h1><p class="muted">${esc(e.message)}</p>
			${state.user && !state.user.emailVerified ? '<button class="btn btn-primary" type="button" id="verify-again">Send a new link</button>' : ''}
			<a class="btn btn-ghost" href="#/">Back to shows</a></div>`;
		document.getElementById('verify-again')?.addEventListener('click', resendVerification);
	}
}

// ---------- Account and passwords ----------

/** #/forgot: ask for a reset link. The answer is the same whether or not the account exists. */
function renderForgot() {
	app.innerHTML = `
		<div class="card narrow">
			<h1>Forgot your password?</h1>
			<p class="muted">Enter your email and we'll send you a link to choose a new one.</p>
			<form class="auth-form" id="forgot-form" novalidate>
				<label>Email <input name="email" type="email" autocomplete="email" required value="${esc(state.user?.email ?? '')}"></label>
				<p class="form-error" id="forgot-error" role="alert" hidden></p>
				<button class="btn btn-primary">Send reset link</button>
			</form>
		</div>`;
	const form = document.getElementById('forgot-form');
	form.onsubmit = async (event) => {
		event.preventDefault();
		const email = form.email.value.trim();
		const error = document.getElementById('forgot-error');
		try {
			await api('/api/auth/forgot-password', { method: 'POST', body: { email } });
			form.parentElement.innerHTML = `<h1>Check your inbox</h1>
				<p>If there's an account for <strong>${esc(email)}</strong>, we've emailed it a link to reset the password.
				The link works once and expires in 1 hour. Check the spam folder too.</p>
				<a class="btn btn-ghost" href="#/">Back to shows</a>`;
		} catch (e) {
			error.textContent = e.message;
			error.hidden = false;
		}
	};
}

/** #/reset/<token>: the link from the reset email. */
function renderReset(token) {
	history.replaceState(null, '', '#/reset'); // the one-time token leaves the address bar and history
	app.innerHTML = `
		<div class="card narrow">
			<h1>Choose a new password</h1>
			<form class="auth-form" id="reset-form" novalidate>
				<label>New password <input name="password" type="password" autocomplete="new-password" required minlength="8"></label>
				<label>Repeat it <input name="repeat" type="password" autocomplete="new-password" required minlength="8"></label>
				<p class="form-error" id="reset-error" role="alert" hidden></p>
				<button class="btn btn-primary">Set new password</button>
			</form>
		</div>`;
	const form = document.getElementById('reset-form');
	const error = document.getElementById('reset-error');
	form.onsubmit = async (event) => {
		event.preventDefault();
		error.hidden = true;
		const { password, repeat } = Object.fromEntries(new FormData(form));
		if (password !== repeat) {
			error.textContent = "The two passwords don't match";
			error.hidden = false;
			return;
		}
		try {
			await api('/api/auth/reset-password', { method: 'POST', body: { token, newPassword: password } });
			clearSession(); // every session was logged out, this one included
			updateNav();
			form.parentElement.innerHTML = `<h1>Password changed</h1>
				<p class="muted">You've been logged out everywhere. Log in with your new password.</p>
				<button class="btn btn-primary" type="button" id="reset-login">Log in</button>`;
			document.getElementById('reset-login').onclick = () => requireLogin().then((ok) => { if (ok) location.hash = '#/'; });
		} catch (e) {
			error.innerHTML = `${esc(e.message)} <a href="#/forgot">Send a new link</a>`;
			error.hidden = false;
		}
	};
}

/** #/account: who you are, and changing the password. */
async function renderAccount() {
	if (!(await requireLogin())) {
		location.hash = '#/';
		return;
	}
	const user = state.user;
	app.innerHTML = `
		<h1>Your account</h1>
		<div class="card narrow">
			<p><strong>${esc(user.name)}</strong><br><span class="muted">${esc(user.email)}</span>
				· ${user.emailVerified ? 'email confirmed' : 'email not confirmed yet'}</p>
		</div>
		<div class="card narrow mt">
			<h2>Change password</h2>
			<form class="auth-form" id="password-form" novalidate>
				<label>Current password <input name="current" type="password" autocomplete="current-password" required></label>
				<label>New password <input name="password" type="password" autocomplete="new-password" required minlength="8"></label>
				<label>Repeat new password <input name="repeat" type="password" autocomplete="new-password" required minlength="8"></label>
				<p class="form-error" id="password-error" role="alert" hidden></p>
				<button class="btn btn-primary">Change password</button>
				<p class="muted small">This logs you out on every other device.</p>
			</form>
		</div>`;
	const form = document.getElementById('password-form');
	const error = document.getElementById('password-error');
	form.onsubmit = async (event) => {
		event.preventDefault();
		error.hidden = true;
		const { current, password, repeat } = Object.fromEntries(new FormData(form));
		if (password !== repeat) {
			error.textContent = "The new passwords don't match";
			error.hidden = false;
			return;
		}
		try {
			setSession(await api('/api/users/me/password', { method: 'POST', auth: true, body: { currentPassword: current, newPassword: password } }));
			form.reset();
			toast('Password changed. Other devices have been logged out.');
		} catch (e) {
			error.textContent = e.message;
			error.hidden = false;
		}
	};
}

// ---------- Router and startup ----------

// Pages render one after another. Otherwise a slow page that started earlier (e.g. My bookings still loading)
// could finish after a quick second click and draw over the page that was asked for last.
let routing = Promise.resolve();
let firstRoute = true;
function route() {
	routing = routing.then(renderRoute, renderRoute);
	return routing;
}

async function renderRoute() {
	stopTimers();
	const hash = location.hash || '#/';
	const [path, query = ''] = hash.slice(1).split('?');
	try {
		const show = path.match(/^\/show\/(\d+)$/);
		const verify = path.match(/^\/verify\/([A-Za-z0-9_-]+)$/);
		const ticket = path.match(/^\/ticket\/(\d+)$/);
		const reset = path.match(/^\/reset\/([A-Za-z0-9_-]+)$/);
		const adminShow = path.match(/^\/admin\/show\/(\d+)$/);
		const review = path.match(/^\/review\/(\d+)$/);
		if (path === '/admin' || path.startsWith('/admin/')) {
			// Loaded only when an admin page is opened, so customers never download it
			const admin = await import('./admin.js');
			if (adminShow) await admin.renderAdminShow(adminShow[1]);
			else if (path === '/admin/new') await admin.renderNewShow();
			else if (path === '/admin/check-in') await admin.renderCheckIn(new URLSearchParams(query));
			else if (path === '/admin/promo-codes') await admin.renderPromoCodes();
			else if (path === '/admin/reviews') await admin.renderReviews(new URLSearchParams(query));
			else await admin.renderAdmin(new URLSearchParams(query));
		} else if (show) await renderShow(show[1]);
		else if (verify) await renderVerify(verify[1]);
		else if (path === '/verify') app.innerHTML = '<div class="card empty"><p>Done here.</p><a class="btn btn-primary" href="#/">Find a show</a></div>';
		else if (path === '/bookings') await renderBookings();
		else if (path === '/account') await renderAccount();
		else if (ticket) await renderTicket(ticket[1]);
		else if (review) await renderReview(review[1]);
		else if (path === '/forgot') renderForgot();
		else if (reset) renderReset(reset[1]);
		else if (path === '/reset') app.innerHTML = '<div class="card empty"><p>Done here.</p><a class="btn btn-primary" href="#/">Find a show</a></div>';
		else await renderHome(new URLSearchParams(query));
	} catch (e) {
		app.innerHTML = `<div class="card empty"><h1>Something went wrong</h1><p class="muted">${esc(e.message)}</p><a href="#/">Back to shows</a></div>`;
	}
	window.scrollTo(0, 0);
	const heading = app.querySelector('h1');
	document.title = heading ? `${heading.textContent.trim()} · BookMySeat` : 'BookMySeat';
	if (!firstRoute && !document.querySelector('dialog[open]')) focusStart(heading);
	firstRoute = false;
}

/** Puts keyboard focus at the start of the page (its heading, else the main area) without showing a focus ring. */
function focusStart(heading) {
	const target = heading ?? app;
	if (target !== app) target.tabIndex = -1;
	target.focus({ preventScroll: true });
}

const scripts = new Map();
function loadScript(src) {
	if (!scripts.has(src)) {
		scripts.set(src, new Promise((resolve, reject) => {
			const el = document.createElement('script');
			el.src = src;
			el.onload = resolve;
			el.onerror = () => { scripts.delete(src); reject(new Error('Could not load the payment window. Check your connection.')); };
			document.head.append(el);
		}));
	}
	return scripts.get(src);
}

async function start() {
	document.getElementById('login-btn').onclick = () => requireLogin().then((ok) => ok && route());
	document.getElementById('logout-btn').onclick = logout;
	document.getElementById('verify-resend').onclick = resendVerification;
	// The skip link must not change the hash (that's the router's): it moves focus itself
	document.getElementById('skip-link').onclick = (event) => {
		event.preventDefault();
		focusStart(app.querySelector('h1'));
	};
	try {
		state.config = await api('/api/config');
	} catch {
		// Older server or temporary error: assume direct booking
	}
	if (await refreshSession()) {
		state.user = await api('/api/users/me', { auth: true }).catch(() => null);
	}
	updateNav();
	window.addEventListener('hashchange', route);
	route();
}

start();

// Shared with the admin pages (assets/admin.js)
export { api, app, esc, money, when, toast, state, every, ApiError, starsText, day };
