// Opens the demo, waking its free server first if it is asleep. A sleeping Render instance holds the first request
// until the app has started (about 2 minutes), so the page keeps asking the liveness probe and forwards to the app
// once it answers UP. The app's address comes only from data-app on <body>: no parameter can send visitors elsewhere.
'use strict';

const APP = document.body.dataset.app;
const LIVENESS = new URL('actuator/health/liveness', APP).href;
const EXPECTED_SECONDS = 140; // measured cold starts on Render: 125 to 140 s
const GIVE_UP_SECONDS = Number(document.body.dataset.giveUp || 300); // shortened only by the browser test
const QUICK_CHECK_MS = 2500;  // an awake app answers well within this
const ATTEMPT_MS = 60000;     // one held request; if it times out, ask again

const $ = (id) => document.getElementById(id);
let startedAt = 0;
let ticker = 0;
let phase = '';

async function isUp(timeoutMs) {
	const abort = new AbortController();
	const timer = setTimeout(() => abort.abort(), timeoutMs);
	try {
		const response = await fetch(LIVENESS, { cache: 'no-store', credentials: 'omit', signal: abort.signal });
		// A Render error page (during a deploy, say) is not the app: only the app's own "UP" counts
		return response.ok && (await response.json()).status === 'UP';
	} catch {
		return false; // timed out, offline, or a page without the CORS header
	} finally {
		clearTimeout(timer);
	}
}

function say(text) {
	if (text !== phase) { // the live region speaks on changes only, not every second
		phase = text;
		$('status').textContent = text;
	}
}

function progressFor(seconds) {
	// Steady to 90% at the usual start time, then creeps on so the bar never looks stuck or finished early
	return seconds < EXPECTED_SECONDS ? 90 * seconds / EXPECTED_SECONDS
		: 90 + 8 * (1 - Math.exp(-(seconds - EXPECTED_SECONDS) / 60));
}

function tick() {
	const seconds = (performance.now() - startedAt) / 1000;
	$('progress').value = progressFor(seconds);
	$('elapsed').textContent = `${Math.floor(seconds / 60)}:${String(Math.floor(seconds % 60)).padStart(2, '0')}`;
	say(seconds < 20 ? 'Waking up the server.'
		: seconds < 100 ? 'Starting the app and connecting to the database.'
			: seconds < 170 ? 'Almost there.'
				: 'This is taking longer than usual, still trying.');
}

function openDemo() {
	clearInterval(ticker);
	$('progress').value = 100;
	$('title').textContent = 'Ready';
	say('The demo is ready, opening it.');
	location.replace(APP);
}

async function wait() {
	$('failed').hidden = true;
	$('waiting').hidden = false;
	$('title').textContent = 'Waking up the demo…';
	startedAt = performance.now();
	tick();
	ticker = setInterval(tick, 1000);
	while ((performance.now() - startedAt) / 1000 < GIVE_UP_SECONDS) {
		if (await isUp(ATTEMPT_MS)) {
			openDemo();
			return;
		}
		await new Promise((resolve) => setTimeout(resolve, 3000)); // refused fast: don't hammer it
	}
	clearInterval(ticker);
	$('waiting').hidden = true;
	$('failed').hidden = false;
	$('title').textContent = 'The demo didn’t wake up';
	say('The server has not answered after 5 minutes.');
	$('retry').focus();
}

$('retry').addEventListener('click', () => { phase = ''; wait(); });

(async () => {
	if (await isUp(QUICK_CHECK_MS)) {
		openDemo(); // already awake: straight through
	} else {
		wait();
	}
})();
