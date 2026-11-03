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

function toast(message, kind = 'info') {
	const el = document.createElement('div');
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
	let hold = null; // { seatIds, seatNumbers, expiresAt, idempotencyKey }
	let busy = false;

	app.innerHTML = `
		<a href="#/" class="small">← All shows</a>
		<h1>${esc(show.title)}</h1>
		<p class="muted">${esc(show.venueName)} · ${esc(when.format(new Date(show.startTime)))} · <span id="seat-prices"></span></p>
		<div class="card">
			<div class="screen">SCREEN</div>
			<div class="seatmap" id="seatmap"></div>
			<div class="legend">
				<span><i class="l-free"></i>Available</span><span><i class="l-sel"></i>Your selection</span>
				<span><i class="l-held"></i>Held</span><span><i class="l-booked"></i>Booked</span>
			</div>
			<div class="legend" id="category-legend"></div>
		</div>
		<div class="card summary" id="summary"></div>
		${testModeNote()}`;

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

	const draw = () => {
		const mine = new Set(hold?.seatIds ?? []);
		const seatmap = document.getElementById('seatmap');
		if (!seatmap) return; // a refresh that finished after leaving the page
		seatmap.innerHTML = rows().map(([row, list]) => `
			<div class="seat-row">
				<span class="row-label">${esc(row)}</span>
				${list.map((seat) => {
					const isMine = mine.has(seat.id);
					const cls = isMine ? 'mine' : selected.has(seat.id) ? 'selected' : seat.status.toLowerCase();
					const disabled = hold || (seat.status !== 'AVAILABLE' && !isMine);
					return `<button class="seat ${cls} cat-${(seat.category ?? 'REGULAR').toLowerCase()}" data-id="${seat.id}" ${disabled ? 'disabled' : ''}
						aria-pressed="${selected.has(seat.id)}" aria-label="${esc(seatLabel(seat, isMine ? 'held by you' : seat.status.toLowerCase()))}">${seat.num}</button>${aisles.has(seat.num) ? '<span class="aisle"></span>' : ''}`;
				}).join('')}
			</div>`).join('');

		drawPrices();
		const summary = document.getElementById('summary');
		if (hold) {
			const action = state.config.paymentsEnabled ? `Pay ${total(hold.seatIds)}` : `Confirm booking · ${total(hold.seatIds)}`;
			summary.innerHTML = `
				<div><strong>${hold.seatNumbers.join(', ')}</strong> held for you · <span class="timer" id="timer"></span></div>
				<div class="row">
					<button class="btn btn-ghost" id="release" ${busy ? 'disabled' : ''}>Release</button>
					<button class="btn btn-primary" id="confirm" ${busy ? 'disabled' : ''}>${esc(action)}</button>
				</div>`;
			document.getElementById('release').onclick = release;
			document.getElementById('confirm').onclick = state.config.paymentsEnabled ? pay : confirm;
			tick();
		} else {
			const count = selected.size;
			summary.innerHTML = `
				<div>${count ? `<strong>${count} seat${count > 1 ? 's' : ''}</strong> · ${total(selected)}` : '<span class="muted">Pick up to 10 seats</span>'}</div>
				<button class="btn btn-primary" id="hold" ${count === 0 || busy ? 'disabled' : ''}>Hold seats</button>`;
			document.getElementById('hold').onclick = holdSeats;
		}
	};

	const reload = async () => {
		seats = await api(`/api/shows/${showId}/seats`);
		// Drop selections someone else grabbed meanwhile
		for (const id of selected) {
			if (seats.find((s) => s.id === id)?.status !== 'AVAILABLE') selected.delete(id);
		}
		draw();
	};

	const tick = () => {
		const el = document.getElementById('timer');
		if (!hold || !el) return;
		const left = Math.max(0, Math.round((new Date(hold.expiresAt) - Date.now()) / 1000));
		el.textContent = `${Math.floor(left / 60)}:${String(left % 60).padStart(2, '0')} left`;
		el.classList.toggle('low', left < 60);
		if (left === 0) {
			hold = null;
			toast('Your hold expired; the seats were released.', 'error');
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
			selected.clear();
			await reload();
		});
	}

	async function release() {
		await run(async () => {
			await api('/api/bookings/hold/release', { method: 'POST', auth: true, body: { showId: Number(showId), seatIds: hold.seatIds } });
			hold = null;
			await reload();
		});
	}

	// Payments off: confirm directly. The same Idempotency-Key for this hold makes a retry or double click safe.
	async function confirm() {
		await run(async () => {
			const booking = await api('/api/bookings', {
				method: 'POST', auth: true, headers: { 'Idempotency-Key': hold.idempotencyKey },
				body: { showId: Number(showId), seatIds: hold.seatIds },
			});
			stopTimers();
			showSuccess(booking.seats, booking.totalAmount, booking.id);
		});
	}

	// Payments on: checkout -> Razorpay Checkout -> verify. The webhook confirms too, even if this page is closed.
	async function pay() {
		await run(async () => {
			const order = await api('/api/bookings/checkout', { method: 'POST', auth: true, body: { showId: Number(showId), seatIds: hold.seatIds } });
			hold.expiresAt = order.expiresAt;
			await loadScript('https://checkout.razorpay.com/v1/checkout.js');
			await new Promise((resolve) => {
				const checkout = new window.Razorpay({
					key: order.razorpayKeyId,
					order_id: order.orderId,
					amount: order.amount,
					currency: order.currency,
					name: 'BookMySeat',
					description: `${show.title} · ${hold.seatNumbers.join(', ')}`,
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
			app.innerHTML = `<div class="card success"><div class="big">↩</div><h2>Seats no longer available</h2>
				<p>Your payment arrived after the seats were taken, so it is being refunded in full.</p>
				<a class="btn btn-primary" href="#/">Back to shows</a></div>`;
		}
	}

	app.querySelector('#seatmap').addEventListener('click', (event) => {
		const button = event.target.closest('.seat');
		if (!button || button.disabled) return;
		const id = Number(button.dataset.id);
		if (selected.has(id)) selected.delete(id);
		else if (selected.size < 10) selected.add(id);
		else toast('You can pick up to 10 seats.', 'error');
		draw();
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
			<div class="big">🎟️</div>
			<h2>You're booked!</h2>
			<p>Seats <strong>${esc(seatNumbers.join(', '))}</strong> · ${esc(money.format(amount))} · booking #${esc(bookingId)}</p>
			<div class="row center">
				<a class="btn btn-primary" href="#/bookings">My bookings</a>
				<a class="btn btn-ghost" href="#/">More shows</a>
			</div>
		</div>`;
}

async function renderBookings() {
	if (!(await requireLogin())) {
		location.hash = '#/';
		return;
	}
	const bookings = await api('/api/bookings/me', { auth: true });
	if (bookings.length === 0) {
		app.innerHTML = `<h1>My bookings</h1><div class="card empty"><p>No bookings yet.</p><a class="btn btn-primary" href="#/">Find a show</a></div>`;
		return;
	}
	app.innerHTML = `
		<h1>My bookings</h1>
		<div class="bookings">
			${bookings.map((b) => `
				<div class="card row spread">
					<div>
						<div class="row"><strong>${esc(b.showTitle)}</strong><span class="status ${esc(b.status)}">${esc(b.status)}</span></div>
						<div class="muted small">${esc(b.venueName)} · ${esc(when.format(new Date(b.showStartTime)))}</div>
						<div>Seats ${esc(b.seats.join(', '))} · <span class="price">${esc(money.format(b.totalAmount))}</span></div>
					</div>
					${b.status === 'CONFIRMED' ? `<div class="row">
						<a class="btn btn-primary" href="#/ticket/${b.id}">Ticket</a>
						<button class="btn btn-danger" data-cancel="${b.id}">Cancel</button></div>` : ''}
				</div>`).join('')}
		</div>`;
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

/** #/ticket/<bookingId>: the e-ticket to show at the entrance. */
async function renderTicket(bookingId) {
	if (!(await requireLogin())) {
		location.hash = '#/';
		return;
	}
	const ticket = await api(`/api/bookings/${bookingId}/ticket`, { auth: true });
	// The QR image needs the login token, so it's fetched and shown from memory (a blob: URL)
	const res = await fetch(`/api/bookings/${bookingId}/ticket.png`, { headers: { Authorization: `Bearer ${state.accessToken}` } });
	if (!res.ok) throw new ApiError(res.status, 'Could not load the ticket');
	const qr = URL.createObjectURL(await res.blob());
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
		</div>`;
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
		if (path === '/admin' || path.startsWith('/admin/')) {
			// Loaded only when an admin page is opened, so customers never download it
			const admin = await import('./admin.js');
			if (adminShow) await admin.renderAdminShow(adminShow[1]);
			else if (path === '/admin/new') await admin.renderNewShow();
			else if (path === '/admin/check-in') await admin.renderCheckIn(new URLSearchParams(query));
			else await admin.renderAdmin(new URLSearchParams(query));
		} else if (show) await renderShow(show[1]);
		else if (verify) await renderVerify(verify[1]);
		else if (path === '/verify') app.innerHTML = '<div class="card empty"><p>Done here.</p><a class="btn btn-primary" href="#/">Find a show</a></div>';
		else if (path === '/bookings') await renderBookings();
		else if (path === '/account') await renderAccount();
		else if (ticket) await renderTicket(ticket[1]);
		else if (path === '/forgot') renderForgot();
		else if (reset) renderReset(reset[1]);
		else if (path === '/reset') app.innerHTML = '<div class="card empty"><p>Done here.</p><a class="btn btn-primary" href="#/">Find a show</a></div>';
		else await renderHome(new URLSearchParams(query));
	} catch (e) {
		app.innerHTML = `<div class="card empty"><h2>Something went wrong</h2><p class="muted">${esc(e.message)}</p><a href="#/">Back to shows</a></div>`;
	}
	window.scrollTo(0, 0);
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
export { api, app, esc, money, when, toast, state, every, ApiError };
