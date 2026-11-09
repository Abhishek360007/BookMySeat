// Admin pages of the web app. Loaded on demand (import()) only when an admin opens #/admin, so customers never
// download it. Every action goes through the same ADMIN-only REST API that Swagger documents.
import { api, app, esc, money, when, toast, state, every } from './app.js';

const pct = (part, whole) => (whole ? Math.round((100 * part) / whole) : 0);

function requireAdmin() {
	if (state.user?.role === 'ADMIN') return true;
	app.innerHTML = `<div class="card empty"><h1>Admins only</h1><p class="muted">Log in with an admin account to manage shows.</p>
		<a class="btn btn-primary" href="#/">Back to shows</a></div>`;
	return false;
}

function statusBadge(status) {
	return status === 'CANCELLED' ? '<span class="status CANCELLED">CANCELLED</span>' : '';
}

function soldBar(booked, total) {
	return `<div class="sold" title="${booked} of ${total} seats sold">
		<div class="bar"><span data-pct="${pct(booked, total)}"></span></div>
		<span class="small">${booked}/${total}</span></div>`;
}

// ---------- #/admin ----------

export async function renderAdmin(params) {
	if (!requireAdmin()) return;
	const view = ['UPCOMING', 'PAST', 'ALL'].includes(params.get('when')) ? params.get('when') : 'UPCOMING';
	const q = params.get('q') || '';
	const page = Number(params.get('page') || 0);

	app.innerHTML = `
		<div class="row spread"><h1>Admin</h1><div class="row"><a class="btn btn-ghost" href="#/admin/promo-codes">Promo codes</a>
			<a class="btn btn-primary" href="#/admin/new">New show</a></div></div>
		<div class="kpis" id="kpis"><p class="muted">Loading…</p></div>
		<div class="row spread admin-filters">
			<div class="tabs" role="tablist">
				${['UPCOMING', 'PAST', 'ALL'].map((w) => `<a role="tab" aria-selected="${w === view}" class="tab ${w === view ? 'active' : ''}"
					href="#/admin?when=${w}">${w[0] + w.slice(1).toLowerCase()}</a>`).join('')}
			</div>
			<form id="admin-search" class="row"><input name="q" placeholder="Filter by title" value="${esc(q)}" aria-label="Filter by title"></form>
		</div>
		<div id="admin-shows"><p class="muted">Loading shows…</p></div>`;

	document.getElementById('admin-search').onsubmit = (event) => {
		event.preventDefault();
		const next = new URLSearchParams({ when: view });
		const value = new FormData(event.target).get('q').trim();
		if (value) next.set('q', value);
		location.hash = `#/admin?${next}`;
	};

	const query = new URLSearchParams({ when: view, page: String(page), size: '15', sort: view === 'PAST' ? 'startTime,desc' : 'startTime,asc' });
	if (q) query.set('q', q);
	const [overview, shows] = await Promise.all([
		api('/api/admin/overview', { auth: true }),
		api(`/api/admin/shows?${query}`, { auth: true }),
	]);

	document.getElementById('kpis').innerHTML = `
		<div class="card kpi"><span class="muted small">Revenue</span><strong>${esc(money.format(overview.revenue))}</strong>
			<span class="small muted">${esc(money.format(overview.revenueLast7Days))} in the last 7 days</span></div>
		<div class="card kpi"><span class="muted small">Confirmed bookings</span><strong>${overview.confirmedBookings}</strong>
			<span class="small muted">${overview.bookingsLast7Days} in the last 7 days</span></div>
		<div class="card kpi"><span class="muted small">Upcoming shows</span><strong>${overview.upcomingShows}</strong>
			<span class="small muted">${overview.upcomingSeats} seats on sale</span></div>
		<div class="card kpi"><span class="muted small">Upcoming seats sold</span><strong>${pct(overview.upcomingSeatsBooked, overview.upcomingSeats)}%</strong>
			<span class="small muted">${overview.upcomingSeatsBooked} of ${overview.upcomingSeats}</span></div>`;

	const box = document.getElementById('admin-shows');
	if (shows.content.length === 0) {
		box.innerHTML = `<div class="card empty"><p>No ${view === 'ALL' ? '' : view.toLowerCase() + ' '}shows${q ? ' match' : ''}.</p></div>`;
		return;
	}
	box.innerHTML = `
		<div class="card table-wrap" tabindex="0" role="region" aria-label="Shows">
			<table class="table">
				<thead><tr><th>Show</th><th>When</th><th>Price</th><th>Sold</th><th class="num">Revenue</th></tr></thead>
				<tbody>
					${shows.content.map((s) => `
						<tr>
							<td><a href="#/admin/show/${s.id}"><strong>${esc(s.title)}</strong></a> ${statusBadge(s.status)}
								<div class="muted small">${esc(s.venueName)}${s.city ? ` · ${esc(s.city)}` : ''}</div></td>
							<td class="small">${esc(when.format(new Date(s.startTime)))}</td>
							<td>${esc(money.format(s.price))}</td>
							<td>${s.totalSeats ? soldBar(s.bookedSeats, s.totalSeats) : '<span class="muted small">No seats yet</span>'}</td>
							<td class="num">${esc(money.format(s.revenue))}<div class="muted small">${s.confirmedBookings} booking${s.confirmedBookings === 1 ? '' : 's'}</div></td>
						</tr>`).join('')}
				</tbody>
			</table>
		</div>
		<div class="row spread pager">
			<button class="btn btn-ghost" id="prev" ${page === 0 ? 'disabled' : ''}>Previous</button>
			<span class="muted small">Page ${shows.page + 1} of ${Math.max(shows.totalPages, 1)}</span>
			<button class="btn btn-ghost" id="next" ${page + 1 >= shows.totalPages ? 'disabled' : ''}>Next</button>
		</div>`;
	// Widths are set through the DOM, not style="" attributes, which the Content-Security-Policy blocks
	box.querySelectorAll('.bar [data-pct]').forEach((bar) => { bar.style.width = `${bar.dataset.pct}%`; });
	const go = (p) => { const next = new URLSearchParams(params); next.set('page', p); location.hash = `#/admin?${next}`; };
	document.getElementById('prev').onclick = () => go(page - 1);
	document.getElementById('next').onclick = () => go(page + 1);
}

// ---------- #/admin/new ----------

// Show times are entered and displayed in India time (as everywhere on the site), whatever the admin's computer
// is set to. India has no daylight saving, so the offset is fixed.
const IST_OFFSET_MS = 330 * 60_000;

/** datetime-local value in India time for an Instant. */
export function toLocalInput(instant) {
	return new Date(new Date(instant).getTime() + IST_OFFSET_MS).toISOString().slice(0, 16);
}

/** ISO Instant for a datetime-local value typed in India time. */
export function fromLocalInput(value) {
	return new Date(`${value}:00+05:30`).toISOString();
}

/** "4, 8" -> [4, 8]; empty -> [] */
function parseAisles(text) {
	return String(text || '').split(/[\s,]+/).filter(Boolean).map(Number);
}

/** The optional premium / recliner prices of a form; a blank one means "same as regular". */
function categoryPricesOf(f) {
	const prices = {};
	if (f.premiumPrice !== '') prices.PREMIUM = Number(f.premiumPrice);
	if (f.reclinerPrice !== '') prices.RECLINER = Number(f.reclinerPrice);
	return prices;
}

const CATEGORY_NAMES = { REGULAR: 'regular', PREMIUM: 'premium', RECLINER: 'recliner' };

/** "₹250 regular · ₹350 premium" for the categories this show's seats use */
function pricesText(show, categories) {
	return Object.entries(show.prices ?? { REGULAR: show.price })
		.filter(([c]) => !categories || categories.has(c))
		.map(([c, p]) => `${money.format(p)} ${CATEGORY_NAMES[c]}`).join(' · ');
}

export function venueOptions(venues, selectedId) {
	return venues.map((v) => `<option value="${v.id}" ${v.id === selectedId ? 'selected' : ''}>${esc(v.name)}${v.city ? ` (${esc(v.city)})` : ''}</option>`).join('');
}

export async function renderNewShow() {
	if (!requireAdmin()) return;
	const venues = await api('/api/venues');
	// Tomorrow, 7 pm India time
	const tomorrow = fromLocalInput(`${toLocalInput(Date.now() + 24 * 3600_000).slice(0, 10)}T19:00`);

	app.innerHTML = `
		<a href="#/admin" class="small">← Admin</a>
		<h1>New show</h1>
		<form class="card admin-form" id="new-show" novalidate>
			<label>Title <input name="title" required maxlength="150" placeholder="e.g. Dune: Part Three"></label>
			<div class="field"><label for="new-show-venue">Venue</label>
				<select name="venueId" id="new-show-venue">${venueOptions(venues, venues[0]?.id)}<option value="new" ${venues.length ? '' : 'selected'}>+ New venue…</option></select>
			</div>
			<fieldset id="new-venue" ${venues.length ? 'hidden' : ''}>
				<legend>New venue</legend>
				<label>Name <input name="venueName" maxlength="100"></label>
				<label>City <input name="venueCity" maxlength="50"></label>
				<label>Address <input name="venueAddress" maxlength="255"></label>
			</fieldset>
			<div class="form-row">
				<label>Starts (India time) <input name="startTime" type="datetime-local" required value="${toLocalInput(tomorrow)}"></label>
				<label>Regular price (₹) <input name="price" type="number" min="0" step="0.01" required value="250"></label>
			</div>
			<div class="form-row">
				<label>Rows (A–Z) <input name="rows" type="number" min="1" max="26" required value="5"></label>
				<label>Seats per row <input name="seatsPerRow" type="number" min="1" max="30" required value="10"></label>
			</div>
			<fieldset>
				<legend>Seat categories (rows at the back; optional)</legend>
				<div class="form-row">
					<label>Premium rows <input name="premiumRows" type="number" min="0" max="26" value="0"></label>
					<label>Premium price (₹) <input name="premiumPrice" type="number" min="0" step="0.01" placeholder="same as regular"></label>
				</div>
				<div class="form-row">
					<label>Recliner rows <input name="reclinerRows" type="number" min="0" max="26" value="0"></label>
					<label>Recliner price (₹) <input name="reclinerPrice" type="number" min="0" step="0.01" placeholder="same as regular"></label>
				</div>
			</fieldset>
			<label>Aisles after seat <input name="aisles" placeholder="e.g. 4, 8 (optional)" inputmode="numeric"></label>
			<p class="muted small" id="seat-preview"></p>
			<p class="form-error" id="new-show-error" role="alert" hidden></p>
			<div class="row"><button class="btn btn-primary" id="create-show">Create show</button><a class="btn btn-ghost" href="#/admin">Cancel</a></div>
		</form>`;

	const form = document.getElementById('new-show');
	const error = document.getElementById('new-show-error');
	const preview = () => {
		const rows = Number(form.rows.value), per = Number(form.seatsPerRow.value) || 0;
		const recliner = Number(form.reclinerRows.value) || 0, premium = Number(form.premiumRows.value) || 0;
		const regular = Math.max(rows - premium - recliner, 0);
		const price = Number(form.price.value || 0);
		const priceOf = (input) => (input.value === '' ? price : Number(input.value));
		const full = per * (regular * price + premium * priceOf(form.premiumPrice) + recliner * priceOf(form.reclinerPrice));
		const last = rows >= 1 && rows <= 26 ? String.fromCharCode(64 + rows) : '?';
		const parts = [`${regular * per} regular`, premium && `${premium * per} premium`, recliner && `${recliner * per} recliner`].filter(Boolean);
		document.getElementById('seat-preview').textContent = premium + recliner > rows
			? 'More premium and recliner rows than rows'
			: `${rows * per || 0} seats: A1 to ${last}${per || '?'} (${parts.join(', ')}) · ${money.format(full)} if every seat sells`;
	};
	form.venueId.onchange = () => { document.getElementById('new-venue').hidden = form.venueId.value !== 'new'; };
	form.oninput = preview;
	preview();

	form.onsubmit = async (event) => {
		event.preventDefault();
		error.hidden = true;
		const f = Object.fromEntries(new FormData(form));
		const button = document.getElementById('create-show');
		button.disabled = true;
		let showId = null;
		try {
			let venueId = Number(f.venueId);
			if (f.venueId === 'new') {
				if (!f.venueName.trim() || !f.venueCity.trim()) throw new Error('Give the new venue a name and a city');
				venueId = (await api('/api/venues', { method: 'POST', auth: true,
					body: { name: f.venueName.trim(), city: f.venueCity.trim(), address: f.venueAddress.trim() || null } })).id;
			}
			if (!f.startTime) throw new Error('Pick a start time');
			const show = await api('/api/shows', { method: 'POST', auth: true,
				body: { title: f.title.trim(), startTime: fromLocalInput(f.startTime), price: Number(f.price), venueId,
					categoryPrices: categoryPricesOf(f), aisleAfter: parseAisles(f.aisles) } });
			showId = show.id;
			await api(`/api/shows/${showId}/seats`, { method: 'POST', auth: true,
				body: { rows: Number(f.rows), seatsPerRow: Number(f.seatsPerRow),
					premiumRows: Number(f.premiumRows) || 0, reclinerRows: Number(f.reclinerRows) || 0 } });
			toast(`Created "${show.title}" with ${Number(f.rows) * Number(f.seatsPerRow)} seats`);
			location.hash = `#/admin/show/${showId}`;
		} catch (e) {
			// The show may exist without seats (e.g. invalid row count): its page offers to generate them
			error.textContent = showId ? `Show created, but its seats weren't: ${e.message}` : e.message;
			error.hidden = false;
			button.disabled = false;
			if (showId) location.hash = `#/admin/show/${showId}`;
		}
	};
}

// ---------- #/admin/show/:id ----------

export async function renderAdminShow(showId) {
	if (!requireAdmin()) return;
	const [show, venues] = await Promise.all([api(`/api/shows/${showId}`), api('/api/venues')]);
	const cancelled = show.status === 'CANCELLED';
	const started = new Date(show.startTime) <= new Date();
	let bookingStatus = 'CONFIRMED';
	let bookingPage = 0;

	app.innerHTML = `
		<a href="#/admin" class="small">← Admin</a>
		<div class="row spread">
			<div><h1>${esc(show.title)} ${statusBadge(show.status)}</h1>
				<p class="muted">${esc(show.venueName)} · ${esc(when.format(new Date(show.startTime)))} · <span id="show-prices">${esc(pricesText(show))}</span>
				· <a href="#/show/${show.id}">customer view</a></p></div>
			${cancelled ? '' : `<a class="btn btn-primary" href="#/admin/check-in?show=${show.id}">Check-in</a>`}
		</div>
		<div class="kpis" id="show-kpis"></div>
		<div class="card">
			<div class="row spread"><h2>Seat map</h2><span class="muted small">Refreshes every 10 s</span></div>
			<div class="screen">SCREEN</div>
			<div class="seatmap" id="admin-seatmap"></div>
			<div class="legend"><span><i class="l-free"></i>Available</span><span><i class="l-held"></i>Held</span><span><i class="l-booked"></i>Booked</span></div>
		</div>
		<div class="card mt">
			<div class="row spread"><h2>Bookings</h2>
				<div class="tabs" role="tablist" id="booking-tabs">
					${['CONFIRMED', 'CANCELLED'].map((s) => `<button type="button" role="tab" class="tab link-tab" data-status="${s}">${s[0] + s.slice(1).toLowerCase()}</button>`).join('')}
				</div></div>
			<div id="admin-bookings"></div>
		</div>
		${cancelled || started ? '' : `
		<div class="card mt">
			<h2>Edit show</h2>
			<form class="admin-form" id="edit-show" novalidate>
				<label>Title <input name="title" required maxlength="150" value="${esc(show.title)}"></label>
				<div class="form-row">
					<label>Starts (India time) <input name="startTime" type="datetime-local" required value="${toLocalInput(show.startTime)}"></label>
					<label>Regular price (₹) <input name="price" type="number" min="0" step="0.01" required value="${show.price}"></label>
				</div>
				<div class="form-row">
					<label>Premium price (₹) <input name="premiumPrice" type="number" min="0" step="0.01" placeholder="same as regular"
						value="${show.prices && show.prices.PREMIUM !== show.price ? show.prices.PREMIUM : ''}"></label>
					<label>Recliner price (₹) <input name="reclinerPrice" type="number" min="0" step="0.01" placeholder="same as regular"
						value="${show.prices && show.prices.RECLINER !== show.price ? show.prices.RECLINER : ''}"></label>
				</div>
				<label>Aisles after seat <input name="aisles" placeholder="e.g. 4, 8 (optional)" value="${esc((show.aisleAfter ?? []).join(', '))}"></label>
				<div class="field"><label for="edit-venue">Venue</label><select name="venueId" id="edit-venue">${venueOptions(venues, show.venueId)}</select></div>
				<p class="muted small" id="edit-hint"></p>
				<p class="form-error" id="edit-error" role="alert" hidden></p>
				<div class="row"><button class="btn btn-primary" id="save-show">Save changes</button></div>
			</form>
		</div>
		<div class="card mt" id="layout-card" hidden>
				<h2>Seat categories</h2>
				<form class="row" id="layout-form">
					<label>Premium rows <input name="premiumRows" type="number" min="0" max="26" class="w-6"></label>
					<label>Recliner rows <input name="reclinerRows" type="number" min="0" max="26" class="w-6"></label>
					<button class="btn btn-ghost">Apply</button>
				</form>
				<p class="muted small">Counted from the back row. Existing bookings keep what they paid; new bookings use the new layout.</p>
		</div>
		<div class="card danger-zone mt">
			<h2>Cancel show</h2>
			<p class="muted">Cancels every booking, refunds online payments and emails every customer. The show disappears from the site. This can't be undone.</p>
			<button class="btn btn-danger" id="cancel-show">Cancel this show</button>
		</div>`}`;

	let summary = null;
	const loadSummary = async () => {
		summary = await api(`/api/admin/shows/${showId}/summary`, { auth: true });
		const kpis = document.getElementById('show-kpis');
		if (!kpis) return; // left the page while this was loading
		kpis.innerHTML = `
			<div class="card kpi"><span class="muted small">Revenue</span><strong>${esc(money.format(summary.revenue))}</strong>
				<span class="small muted">${summary.confirmedBookings} confirmed booking${summary.confirmedBookings === 1 ? '' : 's'}</span></div>
			<div class="card kpi"><span class="muted small">Seats sold</span><strong>${summary.booked}/${summary.totalSeats}</strong>
				<span class="small muted">${pct(summary.booked, summary.totalSeats)}% of the house</span></div>
			<div class="card kpi"><span class="muted small">Held right now</span><strong>${summary.held}</strong>
				<span class="small muted">${summary.available} available</span></div>
			<div class="card kpi"><span class="muted small">Checked in</span><strong>${summary.checkedInBookings}/${summary.confirmedBookings}</strong>
				<span class="small muted">bookings at the door</span></div>`;
		const hint = document.getElementById('edit-hint');
		if (hint) {
			const booked = summary.confirmedBookings > 0;
			document.getElementById('edit-venue').disabled = booked;
			hint.textContent = booked
				? `${summary.confirmedBookings} booking(s): the venue is locked, a new time is emailed to every customer, and a new price applies only to new bookings.`
				: 'No bookings yet: anything can change.';
		}
	};

	const loadSeats = async () => {
		const seats = await api(`/api/shows/${showId}/seats`);
		const map = document.getElementById('admin-seatmap');
		if (!map) return;
		if (seats.length === 0) {
			map.innerHTML = cancelled ? '<p class="muted">No seats.</p>' : `
				<form class="row" id="gen-seats"><span>No seats yet.</span>
					<input name="rows" type="number" min="1" max="26" value="5" aria-label="Rows" class="w-6">
					<span>rows ×</span><input name="seatsPerRow" type="number" min="1" max="30" value="10" aria-label="Seats per row" class="w-6">
					<button class="btn btn-primary">Generate seats</button></form>`;
			const gen = document.getElementById('gen-seats');
			if (gen) gen.onsubmit = async (event) => {
				event.preventDefault();
				const f = Object.fromEntries(new FormData(gen));
				try {
					await api(`/api/shows/${showId}/seats`, { method: 'POST', auth: true, body: { rows: Number(f.rows), seatsPerRow: Number(f.seatsPerRow) } });
					toast('Seats generated');
					await Promise.all([loadSeats(), loadSummary()]);
				} catch (e) { toast(e.message, 'error'); }
			};
			return;
		}
		const byRow = new Map();
		for (const seat of seats) {
			const [, row, num] = seat.seatNumber.match(/^([A-Z]+)(\d+)$/) || [null, '?', '0'];
			if (!byRow.has(row)) byRow.set(row, []);
			byRow.get(row).push({ ...seat, num: Number(num) });
		}
		const aisles = new Set(show.aisleAfter ?? []);
		const rows = [...byRow.entries()].sort(([a], [b]) => a.localeCompare(b));
		map.innerHTML = rows.map(([row, list]) => `
			<div class="seat-row"><span class="row-label">${esc(row)}</span>
				${list.sort((a, b) => a.num - b.num).map((s) => `<span class="seat ${s.status.toLowerCase()} cat-${s.category.toLowerCase()} static"
					role="img" aria-label="Seat ${esc(s.seatNumber)}, ${CATEGORY_NAMES[s.category]}, ${s.status.toLowerCase()}">${s.num}</span>${aisles.has(s.num) ? '<span class="aisle"></span>' : ''}`).join('')}
			</div>`).join('');
		document.getElementById('show-prices').textContent = pricesText(show, new Set(seats.map((s) => s.category)));
		const layout = document.getElementById('layout-form');
		if (layout && !layout.dataset.ready) {
			const rowsOf = (category) => rows.filter(([, list]) => list[0].category === category).length;
			layout.premiumRows.value = rowsOf('PREMIUM');
			layout.reclinerRows.value = rowsOf('RECLINER');
			layout.dataset.ready = 'yes';
			document.getElementById('layout-card').hidden = false;
			layout.onsubmit = async (event) => {
				event.preventDefault();
				try {
					await api(`/api/shows/${showId}/seats/layout`, { method: 'PUT', auth: true,
						body: { premiumRows: Number(layout.premiumRows.value) || 0, reclinerRows: Number(layout.reclinerRows.value) || 0 } });
					toast('Seat categories updated');
					await loadSeats();
				} catch (e) { toast(e.message, 'error'); }
			};
		}
	};

	const loadBookings = async () => {
		document.querySelectorAll('#booking-tabs [data-status]').forEach((b) => {
			b.classList.toggle('active', b.dataset.status === bookingStatus);
			b.setAttribute('aria-selected', String(b.dataset.status === bookingStatus));
		});
		const page = await api(`/api/admin/shows/${showId}/bookings?status=${bookingStatus}&page=${bookingPage}&size=10`, { auth: true });
		const box = document.getElementById('admin-bookings');
		if (!box) return;
		if (page.content.length === 0) {
			box.innerHTML = `<p class="muted">No ${bookingStatus.toLowerCase()} bookings.</p>`;
			return;
		}
		box.innerHTML = `
			<div class="table-wrap" tabindex="0" role="region" aria-label="Bookings"><table class="table">
				<thead><tr><th>#</th><th>Customer</th><th>Seats</th><th>Booked</th><th>Checked in</th><th class="num">Amount</th></tr></thead>
				<tbody>${page.content.map((b) => `<tr><td>${b.id}</td><td>${esc(b.userEmail)}</td><td>${esc(b.seats.join(', '))}</td>
					<td class="small">${esc(when.format(new Date(b.createdAt)))}</td>
					<td class="small">${b.checkedInAt ? esc(when.format(new Date(b.checkedInAt))) : '<span class="muted">–</span>'}</td>
					<td class="num">${esc(money.format(b.totalAmount))}${Number(b.discountAmount) > 0
						? `<div class="small promo-off">${esc(money.format(b.discountAmount))} off · ${esc(b.promoCode)}</div>` : ''}</td></tr>`).join('')}
				</tbody></table></div>
			<div class="row spread pager">
				<button class="btn btn-ghost" id="b-prev" ${bookingPage === 0 ? 'disabled' : ''}>Previous</button>
				<span class="muted small">Page ${page.page + 1} of ${Math.max(page.totalPages, 1)} · ${page.totalElements} total</span>
				<button class="btn btn-ghost" id="b-next" ${bookingPage + 1 >= page.totalPages ? 'disabled' : ''}>Next</button>
			</div>`;
		document.getElementById('b-prev').onclick = () => { bookingPage--; loadBookings(); };
		document.getElementById('b-next').onclick = () => { bookingPage++; loadBookings(); };
	};
	document.querySelectorAll('#booking-tabs [data-status]').forEach((b) => {
		b.onclick = () => { bookingStatus = b.dataset.status; bookingPage = 0; loadBookings(); };
	});

	await Promise.all([loadSummary(), loadSeats(), loadBookings()]);
	if (!cancelled) every(10_000, () => Promise.all([loadSeats(), loadSummary()]).catch(() => {}));

	const form = document.getElementById('edit-show');
	if (form) {
		form.onsubmit = async (event) => {
			event.preventDefault();
			const error = document.getElementById('edit-error');
			error.hidden = true;
			const f = Object.fromEntries(new FormData(form));
			const startTime = fromLocalInput(f.startTime);
			const moved = new Date(startTime).getTime() !== new Date(show.startTime).getTime();
			try {
				await api(`/api/shows/${showId}`, { method: 'PUT', auth: true, body: {
					title: f.title.trim(), startTime, price: Number(f.price), venueId: Number(f.venueId ?? show.venueId),
					categoryPrices: categoryPricesOf(f), aisleAfter: parseAisles(f.aisles) } });
				toast(moved && summary?.confirmedBookings ? `Saved. ${summary.confirmedBookings} customer(s) were emailed the new time.` : 'Saved');
				renderAdminShow(showId);
			} catch (e) {
				error.textContent = e.message;
				error.hidden = false;
			}
		};
		// A disabled select isn't submitted: keep the current venue
		form.addEventListener('formdata', (event) => { if (!event.formData.has('venueId')) event.formData.set('venueId', show.venueId); });
	}

	const cancelButton = document.getElementById('cancel-show');
	if (cancelButton) {
		cancelButton.onclick = async () => {
			const n = summary?.confirmedBookings ?? 0;
			const message = n
				? `Cancel "${show.title}"? ${n} booking(s) will be cancelled, online payments refunded and every customer emailed. This can't be undone.`
				: `Cancel "${show.title}"? It will disappear from the site. This can't be undone.`;
			if (!window.confirm(message)) return;
			cancelButton.disabled = true;
			try {
				const result = await api(`/api/shows/${showId}/cancel`, { method: 'POST', auth: true });
				toast(`Show cancelled. ${result.cancelledBookings} booking(s) cancelled and refunded.`);
				renderAdminShow(showId);
			} catch (e) {
				toast(e.message, 'error');
				cancelButton.disabled = false;
			}
		};
	}
}

// ---------- #/admin/check-in?show=:id ----------

/**
 * The door: scans ticket QR codes with the phone camera (BarcodeDetector, in Chrome and Edge) or takes a typed
 * code. The server decides; this page only shows the answer big enough to read at arm's length.
 */
export async function renderCheckIn(params) {
	if (!requireAdmin()) return;
	const now = Date.now();
	// Shows whose doors could be open: from 6 hours before the start to 6 hours after
	const recent = (await api('/api/admin/shows?when=ALL&size=100&sort=startTime,desc', { auth: true })).content
		.filter((s) => s.status !== 'CANCELLED' && Math.abs(new Date(s.startTime).getTime() - now) <= 12 * 3600_000);
	let showId = Number(params.get('show')) || recent[0]?.id;

	app.innerHTML = `
		<a href="#/admin" class="small">← Admin</a>
		<h1>Check-in</h1>
		<div class="card admin-form narrow">
			<div class="field"><label for="checkin-show">Show</label>
				<select id="checkin-show">${recent.map((s) => `<option value="${s.id}" ${s.id === showId ? 'selected' : ''}>${esc(s.title)} · ${esc(when.format(new Date(s.startTime)))}</option>`).join('')
					|| '<option value="">No show starts within 12 hours</option>'}</select></div>
			<p class="muted small" id="checkin-count"></p>
		</div>
		<div class="checkin-result" id="checkin-result" role="status" aria-live="assertive"></div>
		<div class="card narrow mt">
			<div id="scanner"></div>
			<form class="row" id="checkin-form">
				<input name="code" placeholder="Or type the ticket code (T1.…)" aria-label="Ticket code" autocomplete="off">
				<button class="btn btn-primary">Check in</button>
			</form>
		</div>`;

	const select = document.getElementById('checkin-show');
	const result = document.getElementById('checkin-result');
	const count = async () => {
		if (!showId) return;
		const s = await api(`/api/admin/shows/${showId}/summary`, { auth: true });
		document.getElementById('checkin-count').textContent = `${s.checkedInBookings} of ${s.confirmedBookings} bookings checked in`;
	};
	select.onchange = () => { showId = Number(select.value); history.replaceState(null, '', `#/admin/check-in?show=${showId}`); count(); };

	let busy = false;
	const check = async (code) => {
		if (busy || !showId || !code.trim()) return;
		busy = true;
		try {
			const ok = await api('/api/admin/check-in', { method: 'POST', auth: true, body: { showId, code: code.trim() } });
			result.className = 'checkin-result ok';
			result.innerHTML = `<strong>✓ Welcome, ${esc(ok.holderName)}</strong><span>Seats ${esc(ok.seats.join(', '))}</span>`;
			count();
		} catch (e) {
			result.className = 'checkin-result bad';
			result.innerHTML = `<strong>✗ Not checked in</strong><span>${esc(e.message)}</span>`;
		} finally {
			setTimeout(() => { busy = false; }, 1500); // one scan of one ticket, not five
		}
	};
	const form = document.getElementById('checkin-form');
	form.onsubmit = (event) => { event.preventDefault(); check(form.code.value); form.reset(); };
	await count();
	every(10_000, () => count().catch(() => {}));

	const scanner = document.getElementById('scanner');
	if (!('BarcodeDetector' in window) || !navigator.mediaDevices?.getUserMedia) {
		scanner.innerHTML = '<p class="muted small">This browser can\'t scan QR codes; type the code instead (Chrome or Edge on Android can scan).</p>';
		return;
	}
	scanner.innerHTML = '<button class="btn btn-ghost" type="button" id="start-camera">Scan with camera</button>';
	document.getElementById('start-camera').onclick = async () => {
		try {
			const stream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: 'environment' } });
			scanner.innerHTML = '<video id="camera" class="camera" playsinline muted></video>';
			const video = document.getElementById('camera');
			video.srcObject = stream;
			await video.play();
			const detector = new BarcodeDetector({ formats: ['qr_code'] });
			const timer = setInterval(async () => {
				if (!document.body.contains(video)) { // left the page
					clearInterval(timer);
					stream.getTracks().forEach((t) => t.stop());
					return;
				}
				const codes = await detector.detect(video).catch(() => []);
				if (codes.length) check(codes[0].rawValue);
			}, 300);
		} catch (e) {
			scanner.innerHTML = `<p class="form-error">Camera unavailable: ${esc(e.message)}. Type the code instead.</p>`;
		}
	};
}

// ---------- #/admin/promo-codes ----------

/** "20% off (at most ₹150)" or "₹50 off". */
function discountText(c) {
	if (c.kind === 'FLAT') return `${money.format(c.amount)} off`;
	return `${Number(c.amount)}% off${c.maxDiscount ? ` (at most ${money.format(c.maxDiscount)})` : ''}`;
}

function limitsText(c) {
	const parts = [];
	if (c.minTotal) parts.push(`from ${money.format(c.minTotal)}`);
	if (c.showTitle) parts.push(`only ${c.showTitle}`);
	parts.push(c.maxUsesPerUser === 1 ? 'once per person' : `${c.maxUsesPerUser}× per person`);
	return parts.join(' · ');
}

function validText(c) {
	const until = c.validUntil ? `until ${when.format(new Date(c.validUntil))}` : 'no end';
	return new Date(c.validFrom) > new Date() ? `from ${when.format(new Date(c.validFrom))}, ${until}` : until;
}

export async function renderPromoCodes() {
	if (!requireAdmin()) return;
	const shows = (await api('/api/admin/shows?when=UPCOMING&size=100&sort=startTime,asc', { auth: true })).content;

	app.innerHTML = `
		<a href="#/admin" class="small">← Admin</a>
		<h1>Promo codes</h1>
		<div id="promo-list"><p class="muted">Loading…</p></div>
		<h2>New code</h2>
		<form class="card admin-form" id="new-code" novalidate>
			<div class="form-row">
				<label>Code <input name="code" required maxlength="20" placeholder="e.g. DIWALI25" autocapitalize="characters" spellcheck="false"></label>
				<label>Description <input name="description" maxlength="100" placeholder="optional, shown to customers"></label>
			</div>
			<div class="form-row">
				<div class="field"><label for="code-kind">Discount</label>
					<select name="kind" id="code-kind"><option value="PERCENT">Percentage</option><option value="FLAT">Fixed amount (₹)</option></select></div>
				<label><span id="amount-label">Percent off</span> <input name="amount" type="number" min="0.01" step="0.01" required value="10"></label>
			</div>
			<div class="form-row">
				<label id="cap-field">At most (₹) <input name="maxDiscount" type="number" min="0.01" step="0.01" placeholder="no cap"></label>
				<label>Minimum total (₹) <input name="minTotal" type="number" min="0" step="0.01" placeholder="any total"></label>
			</div>
			<div class="field"><label for="code-show">Show</label>
				<select name="showId" id="code-show"><option value="">Any show</option>${shows.map((s) => `<option value="${s.id}">${esc(s.title)} · ${esc(when.format(new Date(s.startTime)))}</option>`).join('')}</select></div>
			<div class="form-row">
				<label>Uses in total <input name="maxUses" type="number" min="1" step="1" placeholder="no limit"></label>
				<label>Uses per person <input name="maxUsesPerUser" type="number" min="1" max="100" step="1" value="1"></label>
			</div>
			<label>Valid until (India time) <input name="validUntil" type="datetime-local"></label>
			<p class="muted small" id="code-preview"></p>
			<p class="form-error" id="new-code-error" role="alert" hidden></p>
			<div class="row"><button class="btn btn-primary" id="create-code">Create code</button></div>
		</form>`;

	const form = document.getElementById('new-code');
	const error = document.getElementById('new-code-error');
	const preview = () => {
		const percent = form.kind.value === 'PERCENT';
		document.getElementById('amount-label').textContent = percent ? 'Percent off' : 'Rupees off';
		document.getElementById('cap-field').hidden = !percent;
		const c = { kind: form.kind.value, amount: form.amount.value || 0, maxDiscount: percent ? form.maxDiscount.value : null };
		const example = 500;
		let off = c.kind === 'PERCENT' ? Math.round(example * c.amount) / 100 : Number(c.amount);
		if (c.maxDiscount) off = Math.min(off, Number(c.maxDiscount));
		off = Math.max(Math.min(off, example - 1), 0);
		document.getElementById('code-preview').textContent =
			`${discountText(c)}: a ${money.format(example)} booking would cost ${money.format(example - off)}. A discount always leaves at least ₹1 to pay.`;
	};
	form.oninput = preview;
	preview();

	form.onsubmit = async (event) => {
		event.preventDefault();
		error.hidden = true;
		const f = Object.fromEntries(new FormData(form));
		const number = (v) => (v === '' || v == null ? null : Number(v));
		const button = document.getElementById('create-code');
		button.disabled = true;
		try {
			if (!f.code.trim()) throw new Error('Give the code a name, e.g. DIWALI25');
			const created = await api('/api/admin/promo-codes', { method: 'POST', auth: true, body: {
				code: f.code.trim(), description: f.description.trim() || null, kind: f.kind, amount: Number(f.amount),
				maxDiscount: f.kind === 'PERCENT' ? number(f.maxDiscount) : null, minTotal: number(f.minTotal),
				showId: number(f.showId), maxUses: number(f.maxUses), maxUsesPerUser: number(f.maxUsesPerUser) ?? 1,
				validUntil: f.validUntil ? fromLocalInput(f.validUntil) : null,
			} });
			toast(`Created ${created.code}`);
			form.reset();
			preview();
			await loadCodes();
			document.querySelector(`#promo-list [data-code="${created.id}"]`)?.focus();
		} catch (e) {
			error.textContent = e.message;
			error.hidden = false;
		} finally {
			button.disabled = false;
		}
	};

	async function loadCodes() {
		const page = await api('/api/admin/promo-codes?size=100', { auth: true });
		const box = document.getElementById('promo-list');
		if (!box) return;
		if (page.content.length === 0) {
			box.innerHTML = '<div class="card empty"><p class="muted">No promo codes yet. Create one below.</p></div>';
			return;
		}
		box.innerHTML = `
			<div class="card table-wrap" tabindex="0" role="region" aria-label="Promo codes"><table class="table">
				<thead><tr><th>Code</th><th>Discount</th><th>Limits</th><th>Valid</th><th class="num">Used</th><th>Status</th><th><span class="sr-only">Action</span></th></tr></thead>
				<tbody>${page.content.map((c) => `<tr>
					<td><strong tabindex="-1" data-code="${c.id}">${esc(c.code)}</strong>${c.description ? `<div class="small muted">${esc(c.description)}</div>` : ''}</td>
					<td>${esc(discountText(c))}</td>
					<td class="small">${esc(limitsText(c))}</td>
					<td class="small">${esc(validText(c))}</td>
					<td class="num">${c.uses}${c.maxUses ? ` / ${c.maxUses}` : ''}</td>
					<td><span class="status ${c.active ? 'CONFIRMED' : 'CANCELLED'}">${c.active ? 'ACTIVE' : 'OFF'}</span></td>
					<td><button class="btn btn-ghost btn-small" data-toggle="${c.id}" data-active="${c.active}"
						aria-label="${c.active ? 'Switch off' : 'Switch on'} ${esc(c.code)}">${c.active ? 'Switch off' : 'Switch on'}</button></td>
				</tr>`).join('')}</tbody></table></div>
			<p class="muted small">Switching a code off stops new bookings using it; bookings made with it keep their discount.
				A cancelled booking, or an order left unpaid, gives its use back.</p>`;
		box.querySelectorAll('[data-toggle]').forEach((b) => {
			b.onclick = async () => {
				const action = b.dataset.active === 'true' ? 'disable' : 'enable';
				try {
					const updated = await api(`/api/admin/promo-codes/${b.dataset.toggle}/${action}`, { method: 'POST', auth: true });
					toast(`${updated.code} is ${updated.active ? 'on' : 'off'}`);
					await loadCodes();
					box.querySelector(`[data-toggle="${updated.id}"]`)?.focus();
				} catch (e) {
					toast(e.message, 'error');
				}
			};
		});
	}
	await loadCodes();
}
