import { tryRefresh } from './api.js';

export async function logout() {
    await fetch('/api/v1/auth/logout', {
        method: 'POST',
        credentials: 'include',
    });
    window.location.href = '/login';
}

export async function requireAuth() {
    let response = await fetch('/api/v1/auth/me', {
        credentials: 'include',
    });
    if (!response.ok) {
        if (await tryRefresh()) {
            response = await fetch('/api/v1/auth/me', {
                credentials: 'include',
            });
        }
        if (!response.ok) {
            window.location.href = '/login';
            return null;
        }
    }
    return response.json();
}