// Light or dark theme. Loaded as a plain script in <head> so the chosen theme is set before the page is drawn
// (no flash of the other one). Without a choice the page follows the system setting; the choice stays in this browser.
(function () {
	const KEY = 'bms.theme';
	const root = document.documentElement;
	const systemDark = window.matchMedia('(prefers-color-scheme: dark)');
	let chosen = null;
	try { chosen = localStorage.getItem(KEY); } catch (e) { /* storage blocked: follow the system */ }
	if (chosen === 'light' || chosen === 'dark') root.dataset.theme = chosen;

	const isDark = () => (root.dataset.theme ? root.dataset.theme === 'dark' : systemDark.matches);

	// A toggle: its name stays "Dark theme", aria-pressed says whether it is on
	function sync(button) {
		button.setAttribute('aria-pressed', String(isDark()));
		button.title = isDark() ? 'Switch to the light theme' : 'Switch to the dark theme';
	}

	document.addEventListener('DOMContentLoaded', function () {
		const button = document.getElementById('theme-btn');
		if (!button) return;
		sync(button);
		button.addEventListener('click', function () {
			const next = isDark() ? 'light' : 'dark';
			root.dataset.theme = next;
			try { localStorage.setItem(KEY, next); } catch (e) { /* still switches, just not remembered */ }
			sync(button);
		});
		systemDark.addEventListener('change', function () { sync(button); });
	});
})();
