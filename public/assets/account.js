(() => {
    'use strict';

    const API = '/api/mobile/v1';
    const state = {
        access: sessionStorage.getItem('cloud_access') || '',
        refresh: sessionStorage.getItem('cloud_refresh') || '',
        user: null,
        bootstrap: false,
        serverInfo: null,
    };
    const $ = id => document.getElementById(id);
    const elements = {
        authView: $('auth-view'),
        accountView: $('account-view'),
        session: $('session-actions'),
        authForm: $('auth-form'),
        authUsername: $('auth-username'),
        authPassword: $('auth-password'),
        authError: $('auth-error'),
        authTitle: $('auth-title'),
        authKicker: $('auth-kicker'),
        authSubmit: $('auth-submit'),
        bootstrap: $('bootstrap-toggle'),
        rootLabel: $('root-label'),
        logout: $('logout'),
        ownerAvatar: $('owner-avatar'),
        ownerName: $('owner-name'),
        ownerUsername: $('owner-username'),
        ownerLastLogin: $('owner-last-login'),
        webAddress: $('web-address'),
        driveAddressHttps: $('drive-address-https'),
        driveAddressHttp: $('drive-address-http'),
        certificateAddress: $('certificate-address'),
        accountForm: $('account-form'),
        accountDisplay: $('account-display'),
        currentPassword: $('account-current-password'),
        newPassword: $('account-new-password'),
        accountMessage: $('account-message'),
    };

    document.documentElement.setAttribute(
        'data-theme',
        localStorage.getItem('cd_theme') || (matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'),
    );

    async function jsonRequest(path, options = {}, retry = true) {
        const headers = { Accept: 'application/json', ...(options.headers || {}) };
        if (state.access) headers.Authorization = `Bearer ${state.access}`;
        if (options.body) headers['Content-Type'] = 'application/json';
        const response = await fetch(`${API}${path}`, { credentials: 'same-origin', ...options, headers });
        if (response.status === 401 && retry && state.refresh && !path.includes('/auth/refresh')) {
            if (await refreshSession()) return jsonRequest(path, options, false);
        }
        const payload = await response.json().catch(() => ({ success: false, error: `Invalid server response (${response.status})` }));
        if (!response.ok || !payload.success) throw new Error(payload.error || `Request failed (${response.status})`);
        return payload.data;
    }

    async function refreshSession() {
        try {
            const response = await fetch(`${API}/auth/refresh/`, {
                method: 'POST',
                credentials: 'same-origin',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ refresh_token: state.refresh }),
            });
            const payload = await response.json();
            if (!response.ok || !payload.success) throw new Error();
            saveSession(payload.data);
            return true;
        } catch (_) {
            clearLocalSession();
            return false;
        }
    }

    function saveSession(session) {
        state.access = session.access_token;
        state.refresh = session.refresh_token;
        state.user = session.user;
        sessionStorage.setItem('cloud_access', state.access);
        sessionStorage.setItem('cloud_refresh', state.refresh);
    }

    function clearLocalSession() {
        state.access = '';
        state.refresh = '';
        state.user = null;
        sessionStorage.removeItem('cloud_access');
        sessionStorage.removeItem('cloud_refresh');
    }

    function setMessage(element, message = '', error = false) {
        element.textContent = message;
        element.classList.toggle('hidden', !message);
        element.classList.toggle('is-error', error && !!message);
    }

    function showAuth() {
        elements.authView.classList.remove('hidden');
        elements.accountView.classList.add('hidden');
        elements.session.classList.add('hidden');
    }

    function renderRoot(root) {
        state.user = root;
        const displayName = root.display_name || root.username;
        elements.rootLabel.textContent = `${displayName} · root`;
        elements.ownerAvatar.textContent = displayName.slice(0, 1).toUpperCase();
        elements.ownerName.textContent = displayName;
        elements.ownerUsername.textContent = `@${root.username}`;
        elements.ownerLastLogin.textContent = root.last_login_at
            ? `Last signed in ${new Date(root.last_login_at * 1000).toLocaleString()}`
            : 'Root account ready';
        elements.accountDisplay.value = displayName;
        elements.webAddress.textContent = `${location.origin}/`;
        const info = state.serverInfo || {};
        const hostname = location.hostname;
        const httpPort = Number(info.http_port || 8080);
        const httpsPort = Number(info.https_port || 8443);
        elements.driveAddressHttp.textContent = `http://${hostname}:${httpPort}/network-drive/`;
        elements.driveAddressHttps.textContent = info.https_enabled === false
            ? 'Disabled'
            : `https://${hostname}:${httpsPort}/network-drive/`;
        elements.certificateAddress.href = info.certificate_url || '/clouddrive.crt';
    }

    async function showAccount() {
        const root = await jsonRequest('/auth/me/');
        if (root.role !== 'root') throw new Error('Root account required');
        renderRoot(root);
        elements.authView.classList.add('hidden');
        elements.accountView.classList.remove('hidden');
        elements.session.classList.remove('hidden');
    }

    function requestedDestination() {
        const next = new URLSearchParams(location.search).get('next') || '';
        return next.startsWith('/') && !next.startsWith('//') && !next.startsWith('/account') ? next : '';
    }

    elements.authForm.addEventListener('submit', async event => {
        event.preventDefault();
        elements.authSubmit.disabled = true;
        setMessage(elements.authError);
        try {
            const endpoint = state.bootstrap ? '/auth/register/' : '/auth/login/';
            const session = await jsonRequest(endpoint, {
                method: 'POST',
                body: JSON.stringify({
                    username: elements.authUsername.value,
                    password: elements.authPassword.value,
                    display_name: elements.authUsername.value,
                    device_name: 'CloudDrive Web',
                }),
            }, false);
            saveSession(session);
            const destination = requestedDestination();
            if (destination) location.assign(destination);
            else await showAccount();
        } catch (error) {
            setMessage(elements.authError, error.message, true);
        } finally {
            elements.authSubmit.disabled = false;
        }
    });

    elements.bootstrap.addEventListener('click', () => {
        state.bootstrap = !state.bootstrap;
        elements.authKicker.textContent = state.bootstrap ? 'FIRST-TIME SETUP' : 'ROOT SIGN IN';
        elements.authTitle.textContent = state.bootstrap ? 'Create the root owner' : 'Unlock this CloudDrive';
        elements.authSubmit.textContent = state.bootstrap ? 'Create root account' : 'Sign in as root';
        elements.bootstrap.textContent = state.bootstrap ? 'Already configured? Sign in as root' : 'First-time setup: create root';
        elements.authPassword.autocomplete = state.bootstrap ? 'new-password' : 'current-password';
        setMessage(elements.authError);
    });

    elements.logout.addEventListener('click', async () => {
        try { await jsonRequest('/auth/logout/', { method: 'POST' }, false); } catch (_) {}
        clearLocalSession();
        showAuth();
    });

    elements.accountForm.addEventListener('submit', async event => {
        event.preventDefault();
        const submit = elements.accountForm.querySelector('button[type="submit"]');
        submit.disabled = true;
        setMessage(elements.accountMessage);
        try {
            const root = await jsonRequest('/auth/me/', {
                method: 'PATCH',
                body: JSON.stringify({
                    display_name: elements.accountDisplay.value.trim(),
                    current_password: elements.currentPassword.value,
                    new_password: elements.newPassword.value,
                }),
            });
            renderRoot(root);
            elements.currentPassword.value = '';
            elements.newPassword.value = '';
            setMessage(elements.accountMessage, 'Root account updated.');
        } catch (error) {
            setMessage(elements.accountMessage, error.message, true);
        } finally {
            submit.disabled = false;
        }
    });

    (async () => {
        try {
            const response = await fetch('/api/server-info', { headers: { Accept: 'application/json' } });
            if (response.ok) state.serverInfo = await response.json();
        } catch (_) {}
        showAccount().catch(() => {
            clearLocalSession();
            showAuth();
        });
    })();
})();
