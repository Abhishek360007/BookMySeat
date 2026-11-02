// Admin pages of the web app. Loaded on demand (import()) only when an admin opens #/admin, so customers never
// download it. Every action goes through the same ADMIN-only REST API that Swagger documents.
import { api, app, esc, money, when, toast, state } from './app.js';

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
		<div class="bar"><span style="width:${pct(booked, total)}%"></span></div>
		<span class="small">${booked}/${total}</span></div>`;
}

// ---------- #/admin ----------

export async function renderAdmin(params) {
	if (!requireAdmin()) return;
	const view = ['UPCOMING', 'PAST', 'ALL'].includes(params.get('when')) ? params.get('when') : 'UPCOMING';
	const q = params.get('q') || '';
	const page = Number(params.get('page') || 0);

	app.innerHTML = `
		<div class="row spread"><h1>Admin</h1><a class="btn btn-primary" href="#/admin/new">New show</a></div>
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
		<div class="card table-wrap">
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
				<label>Price per seat (₹) <input name="price" type="number" min="0" step="0.01" required value="250"></label>
			</div>
			<div class="form-row">
				<label>Rows (A–Z) <input name="rows" type="number" min="1" max="26" required value="5"></label>
				<label>Seats per row <input name="seatsPerRow" type="number" min="1" max="30" required value="10"></label>
			</div>
			<p class="muted small" id="seat-preview"></p>
			<p class="form-error" id="new-show-error" role="alert" hidden></p>
			<div class="row"><button class="btn btn-primary" id="create-show">Create show</button><a class="btn btn-ghost" href="#/admin">Cancel</a></div>
		</form>`;

	const form = document.getElementById('new-show');
	const error = document.getElementById('new-show-error');
	const preview = () => {
		const rows = Number(form.rows.value), per = Number(form.seatsPerRow.value);
		const last = rows >= 1 && rows <= 26 ? String.fromCharCode(64 + rows) : '?';
		document.getElementById('seat-preview').textContent =
			`${rows * per || 0} seats: A1 to ${last}${per || '?'} · ${money.format((rows * per || 0) * Number(form.price.value || 0))} if every seat sells`;
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
				body: { title: f.title.trim(), startTime: fromLocalInput(f.startTime), price: Number(f.price), venueId } });
			showId = show.id;
			await api(`/api/shows/${showId}/seats`, { method: 'POST', auth: true,
				body: { rows: Number(f.rows), seatsPerRow: Number(f.seatsPerRow) } });
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
	app.innerHTML = `<a href="#/admin" class="small">← Admin</a><p class="muted">Show ${esc(showId)}: details coming next.</p>`;
}
