const app = document.getElementById('app');
const nav = document.getElementById('nav');
const toasts = document.getElementById('toasts');
const state = { user: null, next: null };
let renderToken = 0;
let lastHash = null;

function h(tag, attrs, ...children) {
    const node = document.createElement(tag);
    if (attrs) {
        for (const [key, value] of Object.entries(attrs)) {
            if (value === false || value == null) {
                continue;
            }
            if (key === 'class') {
                node.className = value;
            } else if (key.startsWith('on')) {
                node.addEventListener(key.slice(2), value);
            } else {
                node.setAttribute(key, value === true ? '' : value);
            }
        }
    }
    for (const child of children.flat()) {
        if (child == null || child === false) {
            continue;
        }
        node.append(child instanceof Node ? child : document.createTextNode(String(child)));
    }
    return node;
}

function toast(text, kind) {
    const node = h('div', { class: 'toast' + (kind === 'bad' ? ' bad' : ''), role: 'status' }, text);
    toasts.append(node);
    setTimeout(() => node.remove(), 5000);
}

async function api(path, options) {
    const res = await fetch(path, options);
    let data = null;
    try {
        data = await res.json();
    } catch {
        data = null;
    }
    if (!res.ok) {
        const err = new Error((data && data.error) || 'Request failed');
        err.status = res.status;
        throw err;
    }
    return data;
}

function post(path, fields) {
    return api(path, {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: new URLSearchParams(fields).toString()
    });
}

const money = value => '\u20B9' + Number(value).toFixed(2);
const when = ts => new Date(ts).toLocaleString();
const badge = status => h('span', { class: 'badge ' + status.toLowerCase() }, status);
const link = (href, text, cls) => h('a', { href, class: cls }, text);
const mono = text => h('span', { class: 'mono' }, text);
const homeFor = user => (user.role === 'ADMIN' ? '#/admin' : '#/dashboard');

function go(hash) {
    if (location.hash === hash) {
        render();
    } else {
        location.hash = hash;
    }
}

function sessionExpired() {
    state.user = null;
    if (location.hash !== '#/login' && location.hash !== '#/register') {
        state.next = location.hash || null;
    }
    toast('Please log in to continue', 'bad');
    go('#/login');
}

async function act(fn) {
    try {
        await fn();
    } catch (err) {
        if (err.status === 401) {
            sessionExpired();
        } else {
            toast(err.message, 'bad');
        }
    }
}

async function busy(button, fn) {
    button.disabled = true;
    await act(fn);
    button.disabled = false;
}

function field(label, input) {
    return h('label', { class: 'field' }, h('span', null, label), input);
}

function kv(label, value) {
    return h('div', { class: 'kv' }, h('span', { class: 'k' }, label), h('code', null, value));
}

function pageHead(title, subtitle) {
    return h('div', { class: 'page-head' }, h('h2', null, title), subtitle ? h('p', { class: 'muted' }, subtitle) : null);
}

function stat(label, value) {
    return h('div', { class: 'stat' }, h('span', { class: 'stat-value' }, value), h('span', { class: 'stat-label' }, label));
}

function empty(text, action) {
    return h('div', { class: 'empty' }, h('p', null, text), action);
}

function table(headers, rows) {
    return h('div', { class: 'table-wrap' },
        h('table', null,
            h('thead', null, h('tr', null, headers.map(t => h('th', null, t)))),
            h('tbody', null, rows.map(cells => h('tr', null, cells.map(c => h('td', null, c)))))));
}

function askText(title, label, confirmText) {
    return new Promise(resolve => {
        const input = h('input', { type: 'text', maxlength: '20', required: true, autocomplete: 'off' });
        const dialog = h('dialog', { class: 'modal' });
        const form = h('form', {
            class: 'form',
            onsubmit: e => {
                e.preventDefault();
                dialog.close(input.value.trim());
            }
        },
            h('h3', null, title),
            field(label, input),
            h('div', { class: 'modal-actions' },
                h('button', { type: 'button', class: 'btn ghost', onclick: () => dialog.close('') }, 'Cancel'),
                h('button', { type: 'submit', class: 'btn' }, confirmText)));
        dialog.append(form);
        dialog.addEventListener('close', () => {
            resolve(dialog.returnValue || null);
            dialog.remove();
        });
        document.body.append(dialog);
        dialog.showModal();
        input.focus();
    });
}

function askSelect(title, label, confirmText, options) {
    return new Promise(resolve => {
        const select = h('select', { required: true },
            h('option', { value: '' }, 'Select a recipient...'),
            options.map(u => h('option', { value: u }, u)));
        const dialog = h('dialog', { class: 'modal' });
        const form = h('form', {
            class: 'form',
            onsubmit: e => {
                e.preventDefault();
                dialog.close(select.value);
            }
        },
            h('h3', null, title),
            field(label, select),
            h('div', { class: 'modal-actions' },
                h('button', { type: 'button', class: 'btn ghost', onclick: () => dialog.close('') }, 'Cancel'),
                h('button', { type: 'submit', class: 'btn' }, confirmText)));
        dialog.append(form);
        dialog.addEventListener('close', () => {
            resolve(dialog.returnValue || null);
            dialog.remove();
        });
        document.body.append(dialog);
        dialog.showModal();
        select.focus();
    });
}

function feature(icon, title, text) {
    return h('article', { class: 'card feature' }, h('span', { class: 'icon' }, icon), h('h3', null, title), h('p', { class: 'muted' }, text));
}

function step(title, text) {
    return h('li', null, h('div', null, h('strong', null, title), h('p', { class: 'muted' }, text)));
}

function landingView() {
    const primary = state.user
        ? link(homeFor(state.user), 'Open dashboard', 'btn')
        : link('#/register', 'Get started', 'btn');
    return h('div', null,
        h('section', { class: 'hero' },
            h('span', { class: 'pill' }, 'Tamper-evident event ticketing'),
            h('h1', null, 'Tickets you can prove are real.'),
            h('p', { class: 'lead' }, 'ChainTickets records every issue, transfer and entry on a hash-linked ledger, so an altered record is caught the moment anyone checks.'),
            h('div', { class: 'hero-actions' }, primary, link('#/events', 'Browse events', 'btn ghost'), link('#/verify', 'Verify a ticket', 'btn ghost'))),
        h('section', { class: 'grid three' },
            feature('01', 'Tamper-evident ledger', 'Every event, issue, transfer and entry is a hash-linked block. Edit one and the chain stops validating.'),
            feature('02', 'Request and approve', 'Fans request tickets and admins review them. Only approved requests reach the ledger.'),
            feature('03', 'Verify in seconds', 'Anyone can check a ticket ID and see whether it is valid, already used or unknown.')),
        h('section', { class: 'steps-section' },
            h('h2', null, 'How it works'),
            h('ol', { class: 'steps' },
                step('Request', 'Pick an event and request a ticket from your account.'),
                step('Approve', 'An admin reviews the request and issues the ticket onto the ledger.'),
                step('Use', 'Transfer it to another account, or present it at the door. Entry is recorded once and only once.'))),
        h('section', { class: 'note' },
            h('strong', null, 'Academic prototype. '),
            'The ledger is hash-linked and tamper-evident, but it runs on a single node. It is not a decentralised network.'));
}

function authView(mode) {
    const registering = mode === 'register';
    const error = h('p', { class: 'form-error', role: 'alert' });
    const username = h('input', { autocomplete: 'username', required: true, maxlength: '20' });
    const password = h('input', { type: 'password', autocomplete: registering ? 'new-password' : 'current-password', required: true, maxlength: '128' });
    const confirm = h('input', { type: 'password', autocomplete: 'new-password', required: true, maxlength: '128' });
    const submit = h('button', { type: 'submit', class: 'btn block' }, registering ? 'Create account' : 'Log in');
    const form = h('form', {
        class: 'form',
        onsubmit: async e => {
            e.preventDefault();
            error.textContent = '';
            if (registering && password.value !== confirm.value) {
                error.textContent = 'Passwords do not match';
                return;
            }
            submit.disabled = true;
            try {
                if (registering) {
                    await post('/api/auth/register', { username: username.value, password: password.value });
                }
                state.user = await post('/api/auth/login', { username: username.value, password: password.value });
                toast(registering ? 'Account created. Welcome aboard.' : 'Welcome back, ' + state.user.username);
                const target = state.next || homeFor(state.user);
                state.next = null;
                go(target);
            } catch (err) {
                error.textContent = err.message;
            } finally {
                submit.disabled = false;
            }
        }
    },
        field('Username', username),
        field('Password', password),
        registering ? field('Confirm password', confirm) : null,
        error,
        submit);
    const demoBox = !registering ? h('div', { class: 'demo-accounts' },
        h('span', { class: 'demo-label' }, 'Demo Logins:'),
        h('div', { class: 'demo-chips' },
            h('button', {
                type: 'button',
                class: 'btn ghost small demo-chip',
                onclick: () => {
                    username.value = 'user';
                    password.value = 'user12345';
                    username.focus();
                }
            }, 'user / user12345'),
            h('button', {
                type: 'button',
                class: 'btn ghost small demo-chip',
                onclick: () => {
                    username.value = 'alice';
                    password.value = 'correcthorse';
                    username.focus();
                }
            }, 'alice / correcthorse'),
            h('button', {
                type: 'button',
                class: 'btn ghost small demo-chip',
                onclick: () => {
                    username.value = 'admin';
                    password.value = '';
                    password.focus();
                }
            }, 'admin')
        )
    ) : null;
    return h('div', { class: 'auth-wrap' },
        h('div', { class: 'card auth-card' },
            h('h2', null, registering ? 'Create your account' : 'Welcome back'),
            h('p', { class: 'muted' }, registering ? 'Request tickets and manage them in one place.' : 'Log in to manage your tickets.'),
            demoBox,
            form,
            registering ? h('p', { class: 'hint muted small' }, 'Usernames use 3 to 20 letters, numbers or underscores. Passwords need at least 8 characters.') : null,
            h('p', { class: 'auth-alt muted' },
                registering ? 'Already have an account? ' : 'New here? ',
                registering ? link('#/login', 'Log in') : link('#/register', 'Create an account'))));
}

function eventAction(event, pending) {
    if (!state.user) {
        return link('#/login', 'Log in to request', 'btn ghost block');
    }
    if (state.user.role === 'ADMIN') {
        return link('#/admin/events', 'Manage events', 'btn ghost block');
    }
    if (pending.has(event.id)) {
        return h('button', { class: 'btn ghost block', disabled: true }, 'Request pending');
    }
    return h('button', {
        class: 'btn block',
        onclick: ev => busy(ev.currentTarget, async () => {
            await post('/api/requests/create', { eventId: event.id });
            toast('Request sent. An admin will review it.');
            await render();
        })
    }, 'Request ticket');
}

async function eventsView() {
    const events = await api('/api/events');
    const mine = state.user && state.user.role === 'USER' ? await api('/api/requests/list') : [];
    const pending = new Set(mine.filter(r => r.status === 'PENDING').map(r => r.eventId));
    const cards = events.map(e => h('article', { class: 'card event' },
        h('div', { class: 'event-top' }, h('span', { class: 'pill small' }, e.id), h('span', { class: 'price' }, money(e.price))),
        h('h3', null, e.name),
        h('p', { class: 'muted' }, e.date),
        h('p', { class: 'muted' }, e.venue),
        eventAction(e, pending)));
    const adminLink = state.user && state.user.role === 'ADMIN' ? link('#/admin/events', 'Create the first event', 'btn') : null;
    return h('div', null,
        pageHead('Events', 'Browse what is on and request your ticket.'),
        events.length ? h('div', { class: 'grid' }, cards) : empty('No events yet.', adminLink));
}

async function transfer(ticket) {
    const allUsers = await api('/api/users');
    const available = allUsers.filter(u => u !== state.user.username);
    if (!available.length) {
        toast('No other registered users available to transfer to', 'bad');
        return;
    }
    const to = await askSelect('Transfer ticket', 'Recipient', 'Transfer', available);
    if (!to) {
        return;
    }
    await post('/api/tickets/transfer', { ticketId: ticket.id, to });
    toast('Ticket ' + ticket.id + ' transferred to ' + to.toLowerCase());
    await render();
}

async function dashboardView() {
    const [tickets, requests, events] = await Promise.all([
        api('/api/tickets'), api('/api/requests/list'), api('/api/events')
    ]);
    const names = Object.fromEntries(events.map(e => [e.id, e.name]));
    const active = tickets.filter(t => t.status === 'ACTIVE').length;
    const pending = requests.filter(r => r.status === 'PENDING').length;
    const ticketRows = tickets.map(t => [
        mono(t.id),
        names[t.eventId] || t.eventId,
        badge(t.status),
        h('div', { class: 'row' },
            link('#/verify/' + encodeURIComponent(t.id), 'Verify', 'btn ghost sm'),
            t.status === 'ACTIVE'
                ? h('button', { class: 'btn sm', onclick: ev => busy(ev.currentTarget, () => transfer(t)) }, 'Transfer')
                : null)
    ]);
    const requestRows = [...requests].reverse().map(r => [
        mono(r.id),
        names[r.eventId] || r.eventId,
        when(r.createdAt),
        badge(r.status),
        r.ticketId ? link('#/verify/' + encodeURIComponent(r.ticketId), r.ticketId, 'mono') : '\u2014'
    ]);
    return h('div', null,
        pageHead('My tickets', 'Signed in as ' + state.user.username + '.'),
        h('div', { class: 'stats' }, stat('Active tickets', active), stat('Pending requests', pending), stat('Total tickets', tickets.length)),
        h('h3', null, 'Tickets'),
        tickets.length
            ? table(['Ticket', 'Event', 'Status', 'Actions'], ticketRows)
            : empty('No tickets yet.', link('#/events', 'Browse events', 'btn')),
        h('h3', null, 'Requests'),
        requests.length
            ? table(['Request', 'Event', 'Requested', 'Status', 'Ticket'], requestRows)
            : h('p', { class: 'muted' }, 'You have not requested any tickets.'));
}

function tabLink(href, label, active, count) {
    return h('a', { href, class: 'tab' + (active ? ' active' : ''), 'aria-current': active ? 'page' : false },
        label, count ? h('span', { class: 'count' }, count) : null);
}

function requestsPanel(requests, names) {
    const pending = requests.filter(r => r.status === 'PENDING');
    const decided = requests.filter(r => r.status !== 'PENDING').reverse();
    const cards = pending.map(r => h('div', { class: 'card row-card' },
        h('div', null,
            h('strong', null, r.username),
            h('span', { class: 'muted' }, ' requested ' + (names[r.eventId] || r.eventId)),
            h('div', { class: 'muted small' }, r.id + ' \u00B7 ' + when(r.createdAt))),
        h('div', { class: 'row' },
            h('button', {
                class: 'btn danger sm',
                onclick: ev => busy(ev.currentTarget, async () => {
                    if (!confirm('Reject ' + r.username + "'s request?")) {
                        return;
                    }
                    await post('/api/requests/reject', { requestId: r.id });
                    toast('Request ' + r.id + ' rejected');
                    await render();
                })
            }, 'Reject'),
            h('button', {
                class: 'btn success sm',
                onclick: ev => busy(ev.currentTarget, async () => {
                    const res = await post('/api/requests/approve', { requestId: r.id });
                    toast('Ticket ' + res.ticketId + ' issued to ' + r.username);
                    await render();
                })
            }, 'Approve and issue'))));
    const historyRows = decided.map(r => [
        mono(r.id), r.username, names[r.eventId] || r.eventId, badge(r.status), r.decidedBy || '\u2014',
        r.ticketId ? mono(r.ticketId) : '\u2014'
    ]);
    return h('div', null,
        h('h3', null, 'Pending'),
        pending.length ? h('div', { class: 'stack' }, cards) : empty('No pending requests.'),
        h('h3', null, 'History'),
        decided.length
            ? table(['Request', 'User', 'Event', 'Status', 'Decided by', 'Ticket'], historyRows)
            : h('p', { class: 'muted' }, 'Nothing decided yet.'));
}

function eventsPanel(events) {
    const name = h('input', { required: true, maxlength: '60' });
    const date = h('input', { type: 'date', required: true });
    const venue = h('input', { required: true, maxlength: '60' });
    const price = h('input', { type: 'number', min: '0', step: '0.01', required: true });
    const form = h('form', {
        class: 'form form-grid',
        onsubmit: e => {
            e.preventDefault();
            busy(e.submitter, async () => {
                const created = await post('/api/events/create', {
                    name: name.value, date: date.value, venue: venue.value, price: price.value
                });
                toast('Event ' + created.id + ' created');
                await render();
            });
        }
    },
        field('Event name', name),
        field('Date', date),
        field('Venue', venue),
        field('Ticket price (INR)', price),
        h('button', { type: 'submit', class: 'btn' }, 'Create event'));
    const rows = events.map(e => [mono(e.id), e.name, e.date, e.venue, money(e.price)]);
    return h('div', { class: 'split' },
        h('div', { class: 'card' }, h('h3', null, 'New event'), form),
        h('div', null,
            h('h3', { style: 'margin-top:0' }, 'All events'),
            events.length ? table(['ID', 'Name', 'Date', 'Venue', 'Price'], rows) : empty('No events yet.')));
}

function ticketsPanel(tickets, names) {
    const filter = h('input', { type: 'search', class: 'search', placeholder: 'Filter by ticket ID or owner' });
    const holder = h('div');
    const draw = () => {
        const q = filter.value.trim().toLowerCase();
        const rows = tickets
            .filter(t => t.id.toLowerCase().includes(q) || t.owner.toLowerCase().includes(q))
            .map(t => [
                mono(t.id),
                names[t.eventId] || t.eventId,
                t.owner,
                badge(t.status),
                h('div', { class: 'row' },
                    link('#/verify/' + encodeURIComponent(t.id), 'Verify', 'btn ghost sm'),
                    t.status === 'ACTIVE'
                        ? h('button', {
                            class: 'btn sm',
                            onclick: ev => busy(ev.currentTarget, async () => {
                                if (!confirm('Record entry for ' + t.id + '? This cannot be undone.')) {
                                    return;
                                }
                                await post('/api/tickets/use', { ticketId: t.id });
                                toast('Entry recorded for ' + t.id);
                                await render();
                            })
                        }, 'Mark used')
                        : null)
            ]);
        holder.replaceChildren(rows.length ? table(['Ticket', 'Event', 'Owner', 'Status', 'Actions'], rows) : empty('No tickets match.'));
    };
    filter.addEventListener('input', draw);
    draw();
    return h('div', null, filter, holder);
}

async function adminView(param) {
    const tab = param === 'events' || param === 'tickets' ? param : 'requests';
    const [requests, tickets, events] = await Promise.all([
        api('/api/requests/list'), api('/api/tickets'), api('/api/events')
    ]);
    const names = Object.fromEntries(events.map(e => [e.id, e.name]));
    const pending = requests.filter(r => r.status === 'PENDING').length;
    const used = tickets.filter(t => t.status === 'USED').length;
    let panel;
    if (tab === 'events') {
        panel = eventsPanel(events);
    } else if (tab === 'tickets') {
        panel = ticketsPanel(tickets, names);
    } else {
        panel = requestsPanel(requests, names);
    }
    return h('div', null,
        pageHead('Admin console', 'Review requests, issue tickets and manage events.'),
        h('div', { class: 'stats' },
            stat('Pending requests', pending), stat('Tickets issued', tickets.length),
            stat('Entries recorded', used), stat('Events', events.length)),
        h('nav', { class: 'tabs', 'aria-label': 'Admin sections' },
            tabLink('#/admin', 'Requests', tab === 'requests', pending),
            tabLink('#/admin/events', 'Events', tab === 'events'),
            tabLink('#/admin/tickets', 'Tickets', tab === 'tickets')),
        panel);
}

async function verifyView(param) {
    const id = param ? decodeURIComponent(param) : '';
    const input = h('input', { required: true, placeholder: 'T-XXXXXXXXXXXX', autocomplete: 'off' });
    input.value = id;
    const form = h('form', {
        class: 'form',
        onsubmit: e => {
            e.preventDefault();
            go('#/verify/' + encodeURIComponent(input.value.trim()));
        }
    }, field('Ticket ID', input), h('button', { type: 'submit', class: 'btn' }, 'Verify'));
    const result = h('div');
    if (id) {
        const verdict = await api('/api/verify?ticketId=' + encodeURIComponent(id));
        const labels = {
            ACTIVE: 'Valid. This ticket is active.',
            USED: 'Already used. Entry denied.',
            NOT_FOUND: 'Ticket not found.',
            CHAIN_INVALID: 'Ledger integrity check failed. This ticket cannot be trusted.'
        };
        result.append(h('div', { class: 'verdict ' + verdict.status.toLowerCase() }, labels[verdict.status] || verdict.status));
        if (state.user && (verdict.status === 'ACTIVE' || verdict.status === 'USED')) {
            try {
                const history = await api('/api/history?ticketId=' + encodeURIComponent(id));
                result.append(
                    h('h3', null, 'Ledger history'),
                    table(['Block', 'Time', 'Transaction'], history.map(b => ['#' + b.index, when(b.timestamp), mono(b.data)])));
            } catch (err) {
                if (err.status === 403) {
                    result.append(h('p', { class: 'muted' }, 'Ledger history is only shown to the ticket owner and admins.'));
                }
            }
        } else if (!state.user && (verdict.status === 'ACTIVE' || verdict.status === 'USED')) {
            result.append(h('p', { class: 'muted' }, 'Log in as the ticket owner or an admin to see its ledger history.'));
        }
    }
    return h('div', null,
        pageHead('Verify a ticket', 'Anyone can check a ticket ID. No account needed.'),
        h('div', { class: 'card', style: 'max-width:520px' }, form),
        result);
}

async function explorerView() {
    const chain = await api('/api/chain');
    const head = chain.blocks[chain.blocks.length - 1];
    const banner = h('div', { class: 'verdict ' + (chain.valid ? 'active' : 'chain_invalid') },
        chain.valid ? 'Chain valid. All hashes and links verified.' : 'Chain INVALID. First broken block: #' + chain.invalidIndex);
    const anchor = h('section', { class: 'card anchor' },
        h('div', { class: 'anchor-head' },
            h('h3', null, 'Ledger anchor'),
            h('button', {
                class: 'btn ghost sm',
                onclick: () => act(async () => {
                    await navigator.clipboard.writeText(head.hash);
                    toast('Head hash copied');
                })
            }, 'Copy head hash')),
        kv('Blocks', chain.blocks.length),
        kv('Head hash', head.hash),
        h('p', { class: 'muted small', style: 'margin-top:12px' },
            'Record these two values somewhere outside this system. If blocks are later removed from the end of the ledger, the chain still validates, but the count and head hash will no longer match what you recorded.'));
    const blocks = [...chain.blocks].reverse().map(b => {
        const broken = !chain.valid && b.index === chain.invalidIndex;
        return h('article', { class: 'block' + (broken ? ' broken' : '') },
            h('div', { class: 'block-top' },
                h('h3', null, 'Block #' + b.index),
                h('span', { class: 'badge type' }, b.data.split('|')[0]),
                h('span', { class: 'muted small' }, when(b.timestamp))),
            kv('Data', b.data),
            kv('Previous hash', b.previousHash),
            kv('Hash', b.hash));
    });
    return h('div', null,
        pageHead('Ledger explorer', 'Newest block first.'),
        banner, anchor, blocks);
}

function errorView(message) {
    return h('div', { class: 'card' }, h('h2', null, 'Something went wrong'), h('p', { class: 'muted' }, message), link('#/', 'Back to home', 'btn ghost'));
}

function notFoundView() {
    return h('div', { class: 'card' }, h('h2', null, 'Page not found'), link('#/', 'Back to home', 'btn ghost'));
}

async function resolve(section, param) {
    const user = state.user;
    if ((section === 'login' || section === 'register') && user) {
        go(homeFor(user));
        return null;
    }
    if (['dashboard', 'admin', 'explorer'].includes(section) && !user) {
        sessionExpired();
        return null;
    }
    if (section === 'dashboard' && user.role !== 'USER') {
        go(homeFor(user));
        return null;
    }
    if (section === 'admin' && user.role !== 'ADMIN') {
        go(homeFor(user));
        return null;
    }
    switch (section) {
        case '': return landingView();
        case 'login': return authView('login');
        case 'register': return authView('register');
        case 'events': return eventsView();
        case 'dashboard': return dashboardView();
        case 'admin': return adminView(param);
        case 'verify': return verifyView(param);
        case 'explorer': return explorerView();
        default: return notFoundView();
    }
}

function renderNav(section) {
    const items = [['#/events', 'Events', 'events'], ['#/verify', 'Verify', 'verify']];
    if (state.user) {
        items.push(state.user.role === 'ADMIN' ? ['#/admin', 'Admin', 'admin'] : ['#/dashboard', 'My tickets', 'dashboard']);
        items.push(['#/explorer', 'Explorer', 'explorer']);
    }
    const links = items.map(([href, label, key]) =>
        h('a', { href, class: 'nav-link' + (section === key ? ' active' : '') }, label));
    const account = state.user
        ? [
            h('span', { class: 'user-chip' },
                h('span', { class: 'avatar' }, state.user.username[0].toUpperCase()),
                state.user.username,
                h('span', { class: 'role' }, state.user.role)),
            h('button', { class: 'btn ghost sm', onclick: logout }, 'Log out')
        ]
        : [link('#/login', 'Log in', 'btn ghost sm'), link('#/register', 'Get started', 'btn sm')];
    nav.replaceChildren(...links, h('span', { class: 'spacer' }), ...account);
}

function logout() {
    return act(async () => {
        await post('/api/auth/logout', {});
        state.user = null;
        toast('Logged out');
        go('#/');
    });
}

async function render() {
    const token = ++renderToken;
    const [, section = '', param] = location.hash.slice(1).split('/');
    let view;
    try {
        view = await resolve(section, param);
    } catch (err) {
        if (token !== renderToken) {
            return;
        }
        if (err.status === 401) {
            sessionExpired();
            return;
        }
        view = errorView(err.message);
    }
    if (token !== renderToken || !view) {
        return;
    }
    app.replaceChildren(view);
    renderNav(section);
    if (location.hash !== lastHash) {
        window.scrollTo(0, 0);
    }
    lastHash = location.hash;
}

window.addEventListener('hashchange', render);

(async () => {
    try {
        state.user = await api('/api/auth/me');
    } catch {
        state.user = null;
    }
    render();
})();