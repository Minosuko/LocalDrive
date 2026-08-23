/**
 * CloudDrive — Frontend SPA (Premium Modern)
 */

const API = '/api';
let CHUNK_SIZE = 5 * 1024 * 1024; // Default 5MB chunks
let MAX_UPLOADS = 3;
let UPLOAD_CONCURRENCY = 2;
let accountRedirecting = false;

const nativeFetch = window.fetch.bind(window);
window.fetch = async (...args) => {
    const response = await nativeFetch(...args);
    if (response.status === 401) redirectToAccount();
    return response;
};

function redirectToAccount() {
    if (accountRedirecting) return;
    accountRedirecting = true;
    const next = `${location.pathname}${location.search}${location.hash}`;
    location.replace(`/account.html?next=${encodeURIComponent(next)}`);
}

const state = {
    path: '/',
    files: [],
    crumbs: [],
    selected: new Set(),
    view: localStorage.getItem('cd_view') || 'grid',
    theme: localStorage.getItem('cd_theme') || (matchMedia('(prefers-color-scheme:dark)').matches ? 'dark' : 'light'),
    sort: localStorage.getItem('cd_sort') || 'name',
    sortDir: localStorage.getItem('cd_sortDir') || 'asc',
    group: localStorage.getItem('cd_group') || 'none',
    uploads: new Map(),
    ctxFile: null,
    viewerFile: null,
    viewerGallery: [],
    viewerIndex: -1
};
const queue = [];
let active = 0;

const $ = id => document.getElementById(id);
const dom = {};

document.addEventListener('DOMContentLoaded', () => {
    [
        'file-list','file-container','breadcrumbs','loading-state','empty-state',
        'upload-panel','upload-list','upload-panel-title','drop-overlay',
        'context-menu','bg-context-menu','modal-overlay','modal-title','modal-body',
        'btn-modal-confirm','btn-modal-cancel','selection-bar','selection-count',
        'file-input','folder-input','storage-text','storage-bar-fg',
        'view-icon-grid','view-icon-list','icon-moon','icon-sun',
        'btn-new','new-menu','btn-upload-file','btn-upload-folder','btn-new-folder',
        'sidebar','mobile-overlay','btn-menu','btn-close-sidebar','sidebar-tree',
        'viewer-overlay','viewer-title','viewer-body','btn-viewer-close','btn-viewer-download','viewer-prev','viewer-next',
        'move-modal', 'move-breadcrumbs', 'move-body', 'btn-move-close', 'btn-move-cancel', 'btn-move-confirm',
        'settings-overlay','btn-settings-close','btn-settings-cancel','btn-settings-save',
        'btn-clean-cache','btn-clean-chunk','btn-clean-trash','btn-clear-trash','btn-settings',
        'btn-zip-selected', 'btn-minimize-uploads', 'btn-close-uploads', 'btn-clear-uploads'
    ].forEach(id => { dom[id.replace(/-([a-z])/g, (_, c) => c.toUpperCase())] = $(id); });

    init();
});

async function init() {
    applyTheme();
    applyView();
    if (localStorage.getItem('cd_sidebar') === '1' && window.innerWidth > 768) {
        dom.sidebar.classList.add('collapsed');
    }
    try {
        const r = await fetch(`${API}/settings/?config_only=1`);
        const j = await r.json();
        const c = j.config || {};
        if (c.chunk_size) CHUNK_SIZE = c.chunk_size * 1024 * 1024;
        if (c.max_uploads) UPLOAD_CONCURRENCY = c.max_uploads;
    } catch (e) {}

    const savedPath = localStorage.getItem('cd_path') || '/';
    loadFiles(savedPath);
    refreshSidebarTree();
    loadStorageInfo();
    bind();
}

function applyTheme() {
    document.documentElement.setAttribute('data-theme', state.theme);
    if (dom.iconMoon) dom.iconMoon.style.display = state.theme === 'light' ? '' : 'none';
    if (dom.iconSun)  dom.iconSun.style.display  = state.theme === 'dark'  ? '' : 'none';
}
function toggleTheme() {
    state.theme = state.theme === 'light' ? 'dark' : 'light';
    localStorage.setItem('cd_theme', state.theme);
    applyTheme();
}

function applyView() {
    if (!dom.fileList) return;
    if (state.group !== 'none') {
        dom.fileList.className = 'file-list grouped';
        dom.fileList.querySelectorAll('.file-group-items').forEach(el => {
            el.className = 'file-list ' + state.view + ' file-group-items';
        });
    } else {
        dom.fileList.className = 'file-list ' + state.view;
    }
    if (dom.viewIconGrid) dom.viewIconGrid.style.display = state.view === 'list' ? '' : 'none';
    if (dom.viewIconList) dom.viewIconList.style.display  = state.view === 'grid' ? '' : 'none';
}
function toggleView() {
    state.view = state.view === 'list' ? 'grid' : 'list';
    localStorage.setItem('cd_view', state.view);
    applyView();
    renderFiles();
}

let _loadTimer = null;
let _loadAbort = null;
let _loadGen = 0;

function requestLoadFiles(path) {
    // Only auto-refresh if user is still viewing this folder
    if (state.path !== path) return;
    if (_loadTimer) clearTimeout(_loadTimer);
    _loadTimer = setTimeout(() => {
        if (state.path === path) loadFiles(path, true);
    }, 200);
}

async function loadFiles(path, silent = false) {
    // Cancel any pending debounced refresh
    if (_loadTimer) { clearTimeout(_loadTimer); _loadTimer = null; }
    // Abort previous in-flight request
    if (_loadAbort) _loadAbort.abort();
    _loadAbort = new AbortController();

    const gen = ++_loadGen;
    state.path = path || '/';

    if (!silent) {
        state.selected.clear();
        updateSelectionBar();
        dom.loadingState.classList.remove('hidden');
        dom.emptyState.classList.add('hidden');
        dom.fileList.innerHTML = '';
    }

    try {
        let endpoint = `${API}/files/?path=${encodeURIComponent(state.path)}`;
        if (state.path === '/Trash') endpoint = `${API}/trash/`;
        endpoint += (endpoint.includes('?') ? '&' : '?') + `t=${Date.now()}`;
        
        const r = await fetch(endpoint, { signal: _loadAbort.signal });
        const j = await r.json();

        // Discard if a newer loadFiles was called while we were waiting
        if (gen !== _loadGen) return;

        if (!j.success) throw new Error(j.error);

        state.files  = j.data.files;
        state.crumbs = j.data.breadcrumbs;
        state.path   = j.data.path;
        localStorage.setItem('cd_path', state.path);

        state.sort = localStorage.getItem('cd_sort_' + state.path) || localStorage.getItem('cd_sort') || 'name';
        state.sortDir = localStorage.getItem('cd_sortDir_' + state.path) || localStorage.getItem('cd_sortDir') || 'asc';
        state.group = localStorage.getItem('cd_group_' + state.path) || localStorage.getItem('cd_group') || 'none';

        renderCrumbs();
        renderFiles();
        
        refreshSidebarTree();
        
        if (state.path === '/Trash') {
            if ($('nav-myfiles')) $('nav-myfiles').classList.remove('active');
            if ($('nav-trash')) $('nav-trash').classList.add('active');
            if (dom.btnClearTrash) dom.btnClearTrash.classList.remove('hidden');
        } else {
            if ($('nav-myfiles')) $('nav-myfiles').classList.add('active');
            if ($('nav-trash')) $('nav-trash').classList.remove('active');
            if (dom.btnClearTrash) dom.btnClearTrash.classList.add('hidden');
        }
        
        const searchInput = $('search-input');
        if (searchInput && searchInput.value) searchInput.value = '';
    } catch (e) {
        if (e.name === 'AbortError') return; // Silently ignore aborted requests
        if (gen !== _loadGen) return;
        if (!silent) dom.fileList.innerHTML = `<div class="state-msg"><p class="state-title">Error</p><p class="state-sub">${esc(e.message)}</p></div>`;
    } finally {
        if (gen === _loadGen && !silent) dom.loadingState.classList.add('hidden');
    }
}

// ── Search Logic ──
async function performSearch(q) {
    dom.loadingState.classList.remove('hidden');
    dom.fileList.innerHTML = '';
    dom.emptyState.classList.add('hidden');
    
    try {
        const r = await fetch(`${API}/files/search/?q=${encodeURIComponent(q)}`);
        const j = await r.json();
        if (!j.success) throw new Error(j.error);
        
        state.files = j.data.files || [];
        state.selected.clear();
        updateSelectionBar();
        
        dom.breadcrumbs.innerHTML = `<a href="#" onclick="event.preventDefault(); document.getElementById('search-input').value=''; loadFiles(state.path);">Home</a><span class="sep">/</span><span class="cur">Search: "${esc(q)}"</span>`;
        
        renderFiles(true);
    } catch (e) {
        dom.fileList.innerHTML = `<div class="state-msg"><p class="state-title">Error</p><p class="state-sub">${esc(e.message)}</p></div>`;
    } finally {
        dom.loadingState.classList.add('hidden');
    }
}

// ── Sidebar Tree Logic ──
let sidebarTreeState = new Set(['/']);
let isTreeLoaded = false;

async function refreshSidebarTree(force = false) {
    if (!force && isTreeLoaded) {
        updateTreeActive(state.path);
        return;
    }
    if (!dom.sidebarTree) return;
    await loadSidebarTree(state.path, dom.sidebarTree);
}

function updateTreeActive(activePath) {
    if (!dom.sidebarTree) return;
    dom.sidebarTree.querySelectorAll('.tree-item').forEach(el => el.classList.remove('active'));
    
    // Auto-expand parents in state
    let cur = activePath;
    while (cur && cur !== '/' && cur !== '/Trash') {
        sidebarTreeState.add(cur);
        cur = cur.substring(0, cur.lastIndexOf('/')) || '/';
    }
    sidebarTreeState.add('/');

    const node = dom.sidebarTree.querySelector(`.tree-node[data-path="${escA(activePath)}"]`);
    if (node) {
        const item = node.querySelector('.tree-item');
        if (item) item.classList.add('active');
    }
    
    // Apply expanded states
    dom.sidebarTree.querySelectorAll('.tree-node').forEach(el => {
        const path = el.dataset.path;
        const toggle = el.querySelector('.tree-item-toggle');
        const children = el.querySelector('.tree-children');
        if (toggle && !toggle.classList.contains('empty')) {
            if (sidebarTreeState.has(path)) {
                toggle.classList.add('open');
                if (children) children.style.display = 'block';
            } else {
                toggle.classList.remove('open');
                if (children) children.style.display = 'none';
            }
        }
    });
}

async function loadSidebarTree(activePath, container) {
    container.innerHTML = '<div style="padding:12px; color:var(--text-3); font-size:12px;">Loading tree...</div>';
    try {
        const r = await fetch(`${API}/tree/?t=${Date.now()}`);
        const j = await r.json();
        if (!j.success) return;
        
        isTreeLoaded = true;
        
        // Build nested tree structure from flat list
        const treeMap = { '/': { path: '/', name: 'My Files', children: [], expanded: sidebarTreeState.has('/') } };
        j.data.tree.forEach(f => {
            treeMap[f.path] = { ...f, children: [], expanded: sidebarTreeState.has(f.path) };
        });
        
        j.data.tree.forEach(f => {
            if (treeMap[f.parent]) treeMap[f.parent].children.push(treeMap[f.path]);
        });
        
        // Auto-expand parents of active path
        let cur = activePath;
        while (cur && cur !== '/' && cur !== '/Trash') {
            if (treeMap[cur]) {
                treeMap[cur].expanded = true;
                sidebarTreeState.add(cur);
            }
            cur = cur.substring(0, cur.lastIndexOf('/')) || '/';
        }
        if (treeMap['/']) treeMap['/'].expanded = true;

        const buildHTML = (node, depth = 0) => {
            let h = '';
            const hasChildren = node.children.length > 0;
            const active = activePath === node.path ? ' active' : '';
            const expanded = node.expanded ? ' open' : '';
            const chStyle = node.expanded ? ' style="display:block;"' : '';
            
            const toggle = hasChildren ? `<div class="tree-item-toggle${expanded}"><svg viewBox="0 0 24 24" width="14" height="14" fill="currentColor"><path d="M8.59 16.59L13.17 12 8.59 7.41 10 6l6 6-6 6-1.41-1.41z"/></svg></div>` : `<div class="tree-item-toggle empty"></div>`;
            
            h += `<div class="tree-node" data-path="${escA(node.path)}">
                    <div class="tree-item${active}" style="padding-left: ${depth * 12 + 16}px; --indent: ${depth * 12 + 16}px;">
                        ${toggle}
                        <div class="tree-item-icon">
                            <svg viewBox="0 0 24 24" width="18" height="18" fill="#f59e0b"><path d="M10 4H4c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2h-8l-2-2z"/></svg>
                        </div>
                        <div class="tree-item-label">${esc(node.name)}</div>
                    </div>`;
                    
            if (hasChildren) {
                h += `<div class="tree-children"${chStyle}>`;
                node.children.forEach(c => h += buildHTML(c, depth + 1));
                h += `</div>`;
            }
            h += `</div>`;
            return h;
        };

        let html = '';
        treeMap['/'].children.forEach(c => html += buildHTML(c, 0));
        container.innerHTML = html || '<div style="padding:12px 16px; color:var(--text-3); font-size:12px; font-style:italic;">No folders</div>';
        
        // Bind toggle clicks
        container.querySelectorAll('.tree-item-toggle').forEach(el => {
            if (el.classList.contains('empty')) return;
            el.addEventListener('click', e => {
                e.stopPropagation();
                const isOpen = el.classList.toggle('open');
                const path = el.closest('.tree-node').dataset.path;
                if (isOpen) sidebarTreeState.add(path);
                else sidebarTreeState.delete(path);
                const children = el.closest('.tree-node').querySelector('.tree-children');
                if (children) children.style.display = el.classList.contains('open') ? 'block' : 'none';
            });
        });
        
        // Bind folder clicks
        container.querySelectorAll('.tree-item').forEach(el => {
            el.addEventListener('click', e => {
                if (e.target.closest('.tree-item-toggle')) return;
                loadFiles(el.closest('.tree-node').dataset.path);
            });
        });

    } catch (e) {
        container.innerHTML = '<div style="padding:12px; color:var(--text-3); font-size:12px;">Failed to load tree</div>';
    }
}

function reloadTreeFolder(path) {
    refreshSidebarTree(true);
}

async function loadStorageInfo() {
    try {
        const r = await fetch(`${API}/storage/`);
        const j = await r.json();
        if (j.success && dom.storageText) {
            dom.storageText.textContent = `${fmtSize(j.data.used_space)} / ${fmtSize(j.data.total_space)}`;
            if (dom.storageBarFg) {
                const pct = j.data.total_space > 0 ? (j.data.used_space / j.data.total_space) * 100 : 0;
                dom.storageBarFg.style.width = Math.min(100, Math.max(0, pct)) + '%';
            }
        }
    } catch (_) {}
}

function renderCrumbs() {
    let h = '';
    state.crumbs.forEach((c, i) => {
        if (i > 0) h += '<span class="sep">/</span>';
        if (i === state.crumbs.length - 1) {
            h += `<span class="cur">${esc(c.name)}</span>`;
        } else {
            h += `<a href="#" data-path="${escA(c.path)}">${esc(c.name)}</a>`;
        }
    });
    dom.breadcrumbs.innerHTML = h;
    dom.breadcrumbs.querySelectorAll('a').forEach(a =>
        a.addEventListener('click', e => { e.preventDefault(); loadFiles(a.dataset.path); })
    );
}

function naturalCompare(a, b) {
    const split = (s) => (s.match(/(\d+)|(\D+)/g) || []).map(p => isNaN(p) ? p.toLowerCase() : parseInt(p, 10));
    const aP = split(a), bP = split(b);
    for (let i = 0; i < Math.min(aP.length, bP.length); i++) {
        if (aP[i] !== bP[i]) return typeof aP[i] === 'number' ? aP[i] - bP[i] : aP[i] < bP[i] ? -1 : 1;
    }
    return aP.length - bP.length;
}

function sortFiles(files) {
    const sorted = [...files];
    const dir = state.sortDir === 'asc' ? 1 : -1;
    sorted.sort((a, b) => {
        // Folders always first
        if (a.type !== b.type) return a.type === 'folder' ? -1 : 1;
        let cmp = 0;
        switch (state.sort) {
            case 'name':
                cmp = naturalCompare(a.name, b.name);
                break;
            case 'date':
                cmp = (a.modified || 0) - (b.modified || 0);
                break;
            case 'size':
                cmp = (a.size || 0) - (b.size || 0);
                break;
        }
        return cmp * dir;
    });
    return sorted;
}

function getGroupKey(f) {
    switch (state.group) {
        case 'name': {
            const ch = (f.name || '')[0];
            if (!ch) return '#';
            return /[a-zA-Z]/.test(ch) ? ch.toUpperCase() : '#';
        }
        case 'date': {
            if (!f.modified) return 'Unknown';
            const d = new Date(f.modified * 1000);
            const now = new Date();
            const today = new Date(now.getFullYear(), now.getMonth(), now.getDate());
            const fileDay = new Date(d.getFullYear(), d.getMonth(), d.getDate());
            const diff = (today - fileDay) / 86400000;
            if (diff < 1) return 'Today';
            if (diff < 2) return 'Yesterday';
            if (diff < 7) return 'This Week';
            if (diff < 30) return 'This Month';
            return 'Older';
        }
        case 'size': {
            const s = f.size || 0;
            if (f.type === 'folder') return 'Folders';
            if (s < 1024) return 'Tiny (< 1 KB)';
            if (s < 1024 * 1024) return 'Small (< 1 MB)';
            if (s < 100 * 1024 * 1024) return 'Medium (< 100 MB)';
            if (s < 1024 * 1024 * 1024) return 'Large (< 1 GB)';
            return 'Huge (> 1 GB)';
        }
        default: return null;
    }
}

function applySortGroup() {
    document.querySelectorAll('[data-sort]').forEach(el => {
        el.classList.toggle('active', el.dataset.sort === state.sort);
    });
    document.querySelectorAll('[data-dir]').forEach(el => {
        el.classList.toggle('active', el.dataset.dir === state.sortDir);
    });
    document.querySelectorAll('[data-group]').forEach(el => {
        el.classList.toggle('active', el.dataset.group === state.group);
    });
}

function renderFileItem(f, isGrid) {
    const fPath = f.path || (state.path === '/' ? '/' + f.name : state.path + '/' + f.name);
    const sel   = state.selected.has(fPath) ? ' selected' : '';
    const hasThumb = f.type === 'file' && (f.icon === 'image' || f.icon === 'video') && state.path !== '/Trash';
    const isFolder = f.type === 'folder';
    const version = hasThumb && (f.modified || f.size) ? `&v=${f.modified || 0}-${f.size || 0}` : '';
    const thumbUrl = hasThumb ? `${API}/thumbnails/?path=${encodeURIComponent(fPath)}${version}` :
                     isFolder ? `${API}/thumbnails/?path=${encodeURIComponent(fPath)}&type=folder` : '';

    let thumbHtml;
    if (hasThumb || isFolder) {
        thumbHtml = `<div class="skeleton-thumb"></div><img data-src="${thumbUrl}" style="opacity:0; transition: opacity 0.3s;" data-isfolder="${isFolder ? '1' : '0'}">
                     <div class="thumb-fallback" style="display:none;">${fileIcon(f.icon, f.extension, isGrid ? 48 : 20)}</div>`;
    } else {
        thumbHtml = `<div class="thumb-icon">${fileIcon(f.icon, f.extension, isGrid ? 48 : 20)}</div>`;
    }

    const ctxBtnHtml = `<button class="btn-ctx" title="Options"><svg viewBox="0 0 24 24" width="20" height="20" fill="currentColor"><path d="M12 8c1.1 0 2-.9 2-2s-.9-2-2-2-2 .9-2 2 .9 2 2 2zm0 2c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2zm0 6c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2z"/></svg></button>`;

    if (isGrid) {
        return `<div class="file-item${sel}" data-name="${escA(fPath)}" data-id="${f.id || ''}" data-type="${f.type}" data-icon="${f.icon}" data-ext="${f.extension}">
                    <div class="file-thumb">${thumbHtml}</div>
                    <div class="file-info">
                        <div class="file-name" title="${escA(f.name)}">${esc(f.name)}</div>
                        <div class="file-meta-row">
                            ${f.type === 'file' ? `<span class="file-meta">${fmtSize(f.size)}</span>` : '<span></span>'}
                            ${ctxBtnHtml}
                        </div>
                    </div>
                  </div>`;
    } else {
        return `<div class="file-item${sel}" data-name="${escA(fPath)}" data-id="${f.id || ''}" data-type="${f.type}" data-icon="${f.icon}" data-ext="${f.extension}">
                    <div class="file-thumb">${thumbHtml}</div>
                    <div class="file-name" title="${escA(f.name)}">${esc(f.name)}</div>
                    <div class="file-size">${f.type === 'file' ? fmtSize(f.size) : '—'}</div>
                    <div class="file-date">${fmtDate(f.modified)}</div>
                    <div class="file-actions">${ctxBtnHtml}</div>
                  </div>`;
    }
}

function renderFiles(isSearch = false) {
    if (!state.files.length) {
        dom.emptyState.classList.remove('hidden');
        dom.fileList.innerHTML = '';
        if (isSearch) dom.emptyState.querySelector('.state-title').textContent = 'No results found';
        else dom.emptyState.querySelector('.state-title').textContent = 'No files yet';
        return;
    }
    dom.emptyState.classList.add('hidden');
    applyView();

    const isGrid = state.view === 'grid';
    const sorted = sortFiles(state.files);
    let h = '';

    if (state.group !== 'none') {
        const groups = [];
        const groupMap = new Map();
        sorted.forEach(f => {
            const key = getGroupKey(f);
            if (!groupMap.has(key)) { groupMap.set(key, []); groups.push(key); }
            groupMap.get(key).push(f);
        });
        groups.forEach(key => {
            h += `<div class="file-group-header">${esc(key)}</div>`;
            h += `<div class="file-list ${state.view} file-group-items">`;
            groupMap.get(key).forEach(f => h += renderFileItem(f, isGrid));
            h += `</div>`;
        });
        dom.fileList.className = 'file-list grouped';
    } else {
        sorted.forEach(f => h += renderFileItem(f, isGrid));
    }

    dom.fileList.innerHTML = h;
    bindFileItems();
}

function updateSelectionDOM() {
    dom.fileList.querySelectorAll('.file-item').forEach(el => {
        if (state.selected.has(el.dataset.name)) el.classList.add('selected');
        else el.classList.remove('selected');
    });
}

function bindFileItems() {
    if (!window.thumbObserver) {
        window.thumbQueue = [];
        window.activeThumbs = 0;
        window.processThumbQueue = () => {
            while (window.activeThumbs < 4 && window.thumbQueue.length > 0) {
                const img = window.thumbQueue.shift();
                if (!img.dataset.src) continue;
                window.activeThumbs++;
                img.onload = function() {
                    this.style.opacity = '1';
                    const skel = this.parentElement.querySelector('.skeleton-thumb');
                    if (skel) skel.style.display = 'none';
                    const fb = this.parentElement.querySelector('.thumb-fallback');
                    if (fb) fb.style.display = 'none';
                    window.activeThumbs--;
                    window.processThumbQueue();
                };
                img.onerror = function() {
                    const skel = this.parentElement.querySelector('.skeleton-thumb');
                    if (skel) skel.style.display = 'none';
                    const fPath = this.closest('.file-item').dataset.name;
                    const fIcon = this.closest('.file-item').dataset.icon;
                    handleThumbError(this, fPath, fIcon);
                    window.activeThumbs--;
                    window.processThumbQueue();
                };
                img.src = img.dataset.src;
                delete img.dataset.src;
            }
        };
        window.thumbObserver = new IntersectionObserver((entries) => {
            entries.forEach(entry => {
                if (entry.isIntersecting) {
                    const img = entry.target;
                    window.thumbObserver.unobserve(img);
                    window.thumbQueue.push(img);
                    window.processThumbQueue();
                }
            });
        }, { rootMargin: '200px' });
    }

    dom.fileList.querySelectorAll('.file-thumb img[data-src]').forEach(img => {
        window.thumbObserver.observe(img);
    });

    dom.fileList.querySelectorAll('.file-item').forEach(el => {
        el.addEventListener('click', e => onFileClick(el, e));
        el.addEventListener('contextmenu', e => onFileCtx(el, e));
        
        let touchTimer = null;
        const cancelTouch = () => { if (touchTimer) clearTimeout(touchTimer); touchTimer = null; };
        el.addEventListener('touchstart', e => {
            if (e.touches.length > 1 || e.target.closest('.btn-ctx')) return;
            touchTimer = setTimeout(() => {
                touchTimer = null;
                const fPath = el.dataset.name;
                if (!state.selected.has(fPath)) {
                    state.selected.add(fPath);
                    updateSelectionDOM();
                    updateSelectionBar();
                }
                el.dataset.longpressed = 'true';
                setTimeout(() => { el.dataset.longpressed = 'false'; }, 500);
            }, 500);
        }, { passive: true });
        el.addEventListener('touchmove', cancelTouch, { passive: true });
        el.addEventListener('touchend', cancelTouch, { passive: true });
        el.addEventListener('touchcancel', cancelTouch, { passive: true });

        const name = el.dataset.name;
        el.setAttribute('draggable', 'true');
        el.addEventListener('dragstart', e => {
            el.dataset.wasDragged = 'true';
            
            e.dataTransfer.effectAllowed = 'move';
            
            if (state.selected.has(name)) {
                e.dataTransfer.setData('text/plain', JSON.stringify(selectedPaths()));
            } else {
                const fPath = state.path === '/' ? '/' + name : state.path + '/' + name;
                e.dataTransfer.setData('text/plain', JSON.stringify([fPath]));
            }
            
            setTimeout(() => el.classList.add('dragging'), 0);
        });
        el.addEventListener('dragend', () => {
            el.classList.remove('dragging');
            setTimeout(() => el.dataset.wasDragged = 'false', 50);
        });

        if (el.dataset.type === 'folder') {
            el.addEventListener('dragover', e => { e.preventDefault(); e.dataTransfer.dropEffect = 'move'; el.classList.add('drag-over'); });
            el.addEventListener('dragleave', () => el.classList.remove('drag-over'));
            el.addEventListener('drop', e => {
                e.preventDefault();
                el.classList.remove('drag-over');
                const p = e.dataTransfer.getData('text/plain');
                if (p) {
                    try {
                        const paths = JSON.parse(p);
                        const dest = state.path === '/' ? '/' + name : state.path + '/' + name;
                        if (!paths.some(path => dest.startsWith(path))) moveItems(paths, dest);
                    } catch(err) {}
                }
            });
        }
    });
}

// ── File Interaction ──────────────────────────────────────────
function onFileClick(el, e) {
    if (el.dataset.wasDragged === 'true') return;
    e.preventDefault();
    const fPath = el.dataset.name;
    const type = el.dataset.type;
    const ext  = el.dataset.ext;
    const icon = el.dataset.icon;

    if (e.target.closest('.btn-ctx')) {
        e.stopPropagation();
        
        const isCtxOpen = !dom.contextMenu.classList.contains('hidden');
        if (isCtxOpen && state.ctxFile) {
            const currentPath = state.ctxFile.path || (state.path === '/' ? '/' + state.ctxFile.name : state.path + '/' + state.ctxFile.name);
            if (currentPath === fPath) {
                hideCtx();
                return;
            }
        }
        
        onFileCtx(el, e);
        return;
    }

    if (el.dataset.longpressed === 'true') {
        el.dataset.longpressed = 'false';
        return;
    }

    const isMobile = matchMedia('(max-width: 768px)').matches;
    if (e.ctrlKey || e.metaKey || state.path === '/Trash' || (isMobile && state.selected.size > 0)) {
        state.selected.has(fPath) ? state.selected.delete(fPath) : state.selected.add(fPath);
        updateSelectionDOM();
        updateSelectionBar();
    } else {
        if (type === 'folder') {
            loadFiles(fPath);
        } else {
            openViewer(fPath, ext, icon);
        }
    }
}

function onFileCtx(el, e) {
    e.preventDefault();
    e.stopPropagation();
    const fPath = el.dataset.name;
    const file = state.files.find(f => {
        const p = f.path || (state.path === '/' ? '/' + f.name : state.path + '/' + f.name);
        return p === fPath;
    });
    if (!file) return;

    const isBtnClick = e.type === 'click' && e.target.closest('.btn-ctx');
    
    let x, y;
    if (isBtnClick) {
        const rect = e.target.closest('.btn-ctx').getBoundingClientRect();
        x = rect.left;
        y = rect.bottom + 4;
    } else {
        x = e.clientX;
        y = e.clientY;
        
        if (!state.selected.has(fPath)) {
            state.selected.clear();
            state.selected.add(fPath);
            updateSelectionDOM();
            updateSelectionBar();
        }
    }

    state.ctxFile = file;

    const openBtn = dom.contextMenu.querySelector('[data-action="open"]');
    const dlBtn = dom.contextMenu.querySelector('[data-action="download"]');
    const renBtn = dom.contextMenu.querySelector('[data-action="rename"]');
    const mvBtn = dom.contextMenu.querySelector('[data-action="move"]');
    const delBtn = dom.contextMenu.querySelector('[data-action="delete"]');
    const restBtn = dom.contextMenu.querySelector('[data-action="restore"]');
    const delPermBtn = dom.contextMenu.querySelector('[data-action="delete_permanent"]');
    const zipBtn = dom.contextMenu.querySelector('[data-action="zip"]');
    const unzipHereBtn = dom.contextMenu.querySelector('[data-action="unzip_here"]');
    const unzipToBtn = dom.contextMenu.querySelector('[data-action="unzip_to"]');
    const convertBtn = dom.contextMenu.querySelector('[data-action="convert"]');

    const isZip = file.name.toLowerCase().endsWith('.zip');
    const isConvertible = file.type === 'file' && ['image','psd','psb','sai','sai2'].includes(file.icon) || ['psd','psb','sai','sai2'].includes(file.extension.toLowerCase());

    if (state.path === '/Trash') {
        if(openBtn) openBtn.style.display = 'none';
        if(dlBtn) dlBtn.style.display = 'none';
        if(renBtn) renBtn.style.display = 'none';
        if(mvBtn) mvBtn.style.display = 'none';
        if(delBtn) delBtn.style.display = 'none';
        if(restBtn) restBtn.style.display = '';
        if(delPermBtn) delPermBtn.style.display = '';
        if(zipBtn) zipBtn.style.display = 'none';
        if(unzipHereBtn) unzipHereBtn.style.display = 'none';
        if(unzipToBtn) unzipToBtn.style.display = 'none';
    } else {
        if(openBtn) openBtn.style.display = file.type === 'folder' ? '' : 'none';
        if(dlBtn) dlBtn.style.display = '';
        if(renBtn) renBtn.style.display = '';
        if(mvBtn) mvBtn.style.display = '';
        if(delBtn) delBtn.style.display = '';
        if(restBtn) restBtn.style.display = 'none';
        if(delPermBtn) delPermBtn.style.display = 'none';
        if(zipBtn) zipBtn.style.display = '';
        if(unzipHereBtn) unzipHereBtn.style.display = isZip ? '' : 'none';
        if(unzipToBtn) unzipToBtn.style.display = isZip ? '' : 'none';
        if(convertBtn) convertBtn.style.display = isConvertible ? '' : 'none';
    }

    showCtx(x, y);
}

function onContainerCtx(e) {
    if (e.target.closest('.file-item') || e.target.closest('.btn-ctx') || state.path === '/Trash') return;
    e.preventDefault();
    e.stopPropagation();
    state.selected.clear();
    updateSelectionDOM();
    updateSelectionBar();
    applySortGroup();
    showCtx(e.clientX, e.clientY, true);
}

function updateSelectionBar() {
    if (state.selected.size > 0) {
        dom.selectionBar.classList.remove('hidden');
        dom.selectionCount.textContent = `${state.selected.size} selected`;
        
        // Update selection bar buttons based on Trash state
        const dlBtn = $('btn-download-selected');
        const renBtn = $('btn-rename-selected'); // Not actually in HTML, but safe to query
        const mvBtn = $('btn-move-selected'); // Same
        const delBtn = $('btn-delete-selected');
        const restBtn = $('btn-restore-selected');
        const delPermBtn = $('btn-delete-permanent-selected');
        const convertBtn = $('btn-convert-selected');
        
        let hasConvertibles = false;
        if (state.selected.size > 0 && state.path !== '/Trash') {
            const selArray = selectedPaths();
            hasConvertibles = selArray.every(p => {
                const f = state.files.find(fi => {
                    const fp = fi.path || (state.path === '/' ? '/' + fi.name : state.path + '/' + fi.name);
                    return fp === p;
                });
                return f && f.type === 'file' && (['image','psd','psb','sai','sai2'].includes(f.icon) || ['psd','psb','sai','sai2'].includes(f.extension.toLowerCase()));
            });
        }
        
        if (state.path === '/Trash') {
            if(dlBtn) dlBtn.classList.add('hidden');
            if(dom.btnZipSelected) dom.btnZipSelected.classList.add('hidden');
            if(renBtn) renBtn.classList.add('hidden');
            if(mvBtn) mvBtn.classList.add('hidden');
            if(delBtn) delBtn.classList.add('hidden');
            if(restBtn) restBtn.classList.remove('hidden');
            if(delPermBtn) delPermBtn.classList.remove('hidden');
            if(convertBtn) convertBtn.style.display = 'none';
        } else {
            if(dlBtn) dlBtn.classList.remove('hidden');
            if(dom.btnZipSelected) dom.btnZipSelected.classList.remove('hidden');
            if(renBtn) {
                if(state.selected.size === 1) renBtn.classList.remove('hidden');
                else renBtn.classList.add('hidden');
            }
            if(mvBtn) mvBtn.classList.remove('hidden');
            if(delBtn) delBtn.classList.remove('hidden');
            if(restBtn) restBtn.classList.add('hidden');
            if(delPermBtn) delPermBtn.classList.add('hidden');
            if(convertBtn) convertBtn.style.display = hasConvertibles ? '' : 'none';
        }
    } else {
        if (dom.selectionBar) dom.selectionBar.classList.add('hidden');
    }
}

// ── Image Converter Modal ──────────────────────────────────────
const converterOverlay = $('converter-overlay');
const converterCancel = $('converter-cancel');
const converterSubmit = $('converter-submit');
const converterFormat = $('converter-format');
const converterQuality = $('converter-quality');
const converterQualityVal = $('converter-quality-val');
const converterQualityWrap = $('converter-quality-wrap');
const converterSource = $('converter-source');
const converterStatus = $('converter-status');

let converterTargetPaths = [];

if (converterFormat) {
    converterFormat.addEventListener('change', () => {
        const fmt = converterFormat.value;
        if (fmt === 'jpg' || fmt === 'jpeg' || fmt === 'webp' || fmt === 'avif') {
            converterQualityWrap.style.display = '';
        } else {
            converterQualityWrap.style.display = 'none';
        }
    });
}
if (converterQuality) {
    converterQuality.addEventListener('input', () => {
        converterQualityVal.textContent = converterQuality.value;
    });
}
if (converterCancel) {
    converterCancel.addEventListener('click', () => {
        converterOverlay.classList.add('hidden');
    });
}
if (converterSubmit) {
    converterSubmit.addEventListener('click', async () => {
        converterStatus.style.display = 'block';
        converterStatus.style.color = 'var(--text-1)';
        converterStatus.textContent = 'Converting...';
        converterSubmit.disabled = true;
        
        let successCount = 0;
        for (const p of converterTargetPaths) {
            try {
                const r = await fetch(`${API}/convert/`, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({
                        path: p,
                        format: converterFormat.value,
                        quality: parseInt(converterQuality.value)
                    })
                });
                const j = await r.json();
                if (j.success) successCount++;
                else console.error('Convert failed:', j.error);
            } catch (e) {
                console.error(e);
            }
        }
        
        converterSubmit.disabled = false;
        converterOverlay.classList.add('hidden');
        loadFiles(state.path);
    });
}

window.openConverterModal = function(paths) {
    if (!Array.isArray(paths)) paths = [paths];
    converterTargetPaths = paths;
    
    if (paths.length === 1) {
        converterSource.textContent = 'Converting: ' + paths[0].split('/').pop();
    } else {
        converterSource.textContent = `Converting ${paths.length} images...`;
    }
    
    converterStatus.style.display = 'none';
    converterFormat.dispatchEvent(new Event('change'));
    converterOverlay.classList.remove('hidden');
}

// ── Viewer Modal ──────────────────────────────────────────────
async function openViewer(filePath, ext, icon) {
    state.viewerFile = filePath;
    state.viewerGallery = [];
    state.viewerIndex = -1;
    if (dom.viewerPrev) dom.viewerPrev.classList.add('hidden');
    if (dom.viewerNext) dom.viewerNext.classList.add('hidden');
    
    dom.viewerTitle.textContent = filePath.split('/').pop();
    dom.viewerOverlay.classList.remove('hidden');
    dom.viewerBody.innerHTML = '<div class="spinner" style="border-color: rgba(255,255,255,0.2); border-top-color: #fff;"></div>';
    
    let url = `${API}/files/download/?path=${encodeURIComponent(filePath)}&view=1`;
    const extLower = (ext || '').toLowerCase();
    const currentFile = state.files.find(file =>
        (file.path || (state.path === '/' ? '/' + file.name : state.path + '/' + file.name)) === filePath
    );
    const version = currentFile && (currentFile.modified || currentFile.size)
        ? `&v=${currentFile.modified || 0}-${currentFile.size || 0}`
        : '';
    url += version;
    
    if (['psd', 'psb', 'sai', 'sai2', 'tiff', 'raw', 'cr2', 'nef', 'arw'].includes(extLower)) {
        url = `${API}/view/?path=${encodeURIComponent(filePath)}${version}`;
    }

    if (icon === 'image') {
        // Reuse the grid thumbnail as the placeholder instead of generating a second HQ proxy.
        const thumbUrl = `${API}/thumbnails/?path=${encodeURIComponent(filePath)}${version}`;
        dom.viewerBody.innerHTML = `<img src="${thumbUrl}" class="viewer-content viewer-preview" style="filter:blur(12px);opacity:0.6;position:absolute;inset:0;object-fit:contain;z-index:1;">
            <img src="${url}" class="viewer-content" style="position:relative;z-index:2;" onload="this.previousElementSibling.style.opacity='0'">`;
        
        // Setup Gallery state
        const imgFiles = state.files.filter(f => f.icon === 'image').sort((a,b) => naturalCompare(a.name, b.name));
        if (imgFiles.length > 1) {
            state.viewerGallery = imgFiles;
            state.viewerIndex = imgFiles.findIndex(f => (f.path || (state.path === '/' ? '/' + f.name : state.path + '/' + f.name)) === filePath);
            if (dom.viewerPrev) dom.viewerPrev.classList.remove('hidden');
            if (dom.viewerNext) dom.viewerNext.classList.remove('hidden');
        } else {
            state.viewerGallery = [];
            state.viewerIndex = -1;
            if (dom.viewerPrev) dom.viewerPrev.classList.add('hidden');
            if (dom.viewerNext) dom.viewerNext.classList.add('hidden');
        }
    } 
    else if (icon === 'video') {
        dom.viewerBody.innerHTML = `
            <div class="custom-video-wrapper" id="custom-video-wrapper">
                <video src="${url}" class="viewer-content custom-video" id="custom-video" autoplay></video>
                <div class="video-controls" id="video-controls">
                    <div class="video-progress-container" id="video-progress-container">
                        <div class="video-progress-fg" id="video-progress"></div>
                    </div>
                    <div class="video-controls-row">
                        <div class="video-controls-left">
                            <button class="btn-icon-sm" id="btn-play-pause">
                                <svg id="icon-play" viewBox="0 0 24 24" width="20" height="20" fill="currentColor" style="display:none"><path d="M8 5v14l11-7z"/></svg>
                                <svg id="icon-pause" viewBox="0 0 24 24" width="20" height="20" fill="currentColor"><path d="M6 19h4V5H6v14zm8-14v14h4V5h-4z"/></svg>
                            </button>
                            <div class="video-time">
                                <span id="time-current">0:00</span> / <span id="time-total">0:00</span>
                            </div>
                        </div>
                        <div class="video-controls-right">
                            <button class="btn-icon-sm" id="btn-mute">
                                <svg id="icon-vol" viewBox="0 0 24 24" width="18" height="18" fill="currentColor"><path d="M3 9v6h4l5 5V4L7 9H3zm13.5 3c0-1.77-1.02-3.29-2.5-4.03v8.05c1.48-.73 2.5-2.25 2.5-4.02zM14 3.23v2.06c2.89.86 5 3.54 5 6.71s-2.11 5.85-5 6.71v2.06c4.01-.91 7-4.49 7-8.77s-2.99-7.86-7-8.77z"/></svg>
                                <svg id="icon-mute" viewBox="0 0 24 24" width="18" height="18" fill="currentColor" style="display:none"><path d="M16.5 12c0-1.77-1.02-3.29-2.5-4.03v2.21l2.45 2.45c.03-.2.05-.41.05-.63zm2.5 0c0 .94-.2 1.82-.54 2.64l1.51 1.51C20.63 14.91 21 13.5 21 12c0-4.28-2.99-7.86-7-8.77v2.06c2.89.86 5 3.54 5 6.71zM4.27 3L3 4.27 7.73 9H3v6h4l5 5v-6.73l4.25 4.25c-.67.52-1.42.93-2.25 1.18v2.06c1.11-.31 2.15-.81 3.09-1.45l2.64 2.64 1.27-1.27L4.27 3zM12 4L9.91 6.09 12 8.18V4z"/></svg>
                            </button>
                            <input type="range" id="volume-slider" min="0" max="1" step="0.05" value="1" class="volume-slider">
                            <button class="btn-icon-sm" id="btn-fullscreen">
                                <svg viewBox="0 0 24 24" width="18" height="18" fill="currentColor"><path d="M7 14H5v5h5v-2H7v-3zm-2-4h2V7h3V5H5v5zm12 7h-3v2h5v-5h-2v3zM14 5v2h3v3h2V5h-5z"/></svg>
                            </button>
                        </div>
                    </div>
                </div>
            </div>`;
        initVideoPlayer();
    }
    else if (icon === 'audio') {
        dom.viewerBody.innerHTML = `<audio src="${url}" controls autoplay></audio>`;
    }
    else if (extLower === 'zip') {
        try {
            const r = await fetch(`${API}/files/zip_info/?path=${encodeURIComponent(filePath)}`);
            const j = await r.json();
            if (j.success) {
                // Build Tree
                const root = { name: '/', children: {}, type: 'folder', size: 0, expanded: true };
                j.data.files.forEach(f => {
                    const parts = f.name.split('/');
                    if (parts[parts.length - 1] === '') parts.pop();
                    let cur = root;
                    parts.forEach((p, i) => {
                        if (!cur.children[p]) {
                            cur.children[p] = { 
                                name: p, children: {}, 
                                type: (i === parts.length - 1 && !f.name.endsWith('/')) ? 'file' : 'folder',
                                size: 0
                            };
                        }
                        if (i === parts.length - 1 && !f.name.endsWith('/')) {
                            cur.children[p].size = f.size;
                        }
                        cur = cur.children[p];
                    });
                });
                
                const buildNode = (node) => {
                    const isFolder = node.type === 'folder';
                    const hasChildren = isFolder && Object.keys(node.children).length > 0;
                    const toggleClass = hasChildren ? (node.expanded ? ' open' : '') : ' empty';
                    const childStyle = node.expanded ? ' style="display:block;"' : '';
                    
                    const icon = isFolder 
                        ? `<svg viewBox="0 0 24 24" width="18" height="18" fill="#f59e0b"><path d="M10 4H4c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2h-8l-2-2z"/></svg>`
                        : `<svg viewBox="0 0 24 24" width="16" height="16" fill="currentColor"><path d="M14 2H6c-1.1 0-2 .9-2 2v16c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V8l-6-6zM6 20V4h7v5h5v11H6z"/></svg>`;
                    
                    let html = `<div class="viewer-tree-node">
                        <div class="viewer-tree-item">
                            <div class="viewer-tree-toggle${toggleClass}"><svg viewBox="0 0 24 24" width="16" height="16" fill="currentColor"><path d="M8.59 16.59L13.17 12 8.59 7.41 10 6l6 6-6 6-1.41-1.41z"/></svg></div>
                            <div class="viewer-tree-icon">${icon}</div>
                            <div class="viewer-tree-label" title="${esc(node.name)}">${esc(node.name)}</div>
                            ${!isFolder ? `<div class="viewer-tree-size">${fmtSize(node.size)}</div>` : ''}
                        </div>`;
                        
                    if (hasChildren) {
                        html += `<div class="viewer-tree-children"${childStyle}>`;
                        Object.values(node.children).sort((a,b) => {
                            if (a.type !== b.type) return a.type === 'folder' ? -1 : 1;
                            return naturalCompare(a.name, b.name);
                        }).forEach(c => html += buildNode(c));
                        html += `</div>`;
                    }
                    html += `</div>`;
                    return html;
                };

                let treeHtml = '';
                Object.values(root.children).sort((a,b) => {
                    if (a.type !== b.type) return a.type === 'folder' ? -1 : 1;
                    return naturalCompare(a.name, b.name);
                }).forEach(c => treeHtml += buildNode(c));
                
                dom.viewerBody.innerHTML = `<div class="viewer-zip-list">
                    <div class="viewer-zip-header">ZIP Contents (${j.data.files.length} items)</div>
                    <div class="viewer-zip-items">${treeHtml || '<div style="color:var(--text-3); font-size:13px; font-style:italic; padding:12px;">Empty Archive</div>'}</div>
                </div>`;
                
                // Bind interactions
                dom.viewerBody.querySelectorAll('.viewer-tree-item').forEach(el => {
                    el.addEventListener('click', (e) => {
                        const toggle = el.querySelector('.viewer-tree-toggle');
                        if (toggle.classList.contains('empty')) return;
                        toggle.classList.toggle('open');
                        const children = el.closest('.viewer-tree-node').querySelector('.viewer-tree-children');
                        if (children) children.style.display = toggle.classList.contains('open') ? 'block' : 'none';
                    });
                });
                
            } else throw new Error();
        } catch (e) {
            dom.viewerBody.innerHTML = `<div style="color:#fff">Failed to read ZIP contents.</div>`;
        }
    }
    else if (['txt','md','json','js','css','html','php','log','csv','xml'].includes(extLower)) {
        try {
            const r = await fetch(url);
            const text = await r.text();
            dom.viewerBody.innerHTML = `<div class="viewer-text-content">${esc(text)}</div>`;
        } catch (e) {
            dom.viewerBody.innerHTML = `<div style="color:#fff">Preview not available.</div>`;
        }
    }
    else {
        dom.viewerBody.innerHTML = `
            <div style="text-align:center; color:#fff">
                <svg viewBox="0 0 24 24" width="64" height="64" fill="currentColor" style="opacity:0.5; margin-bottom:16px;">
                    <path d="M14 2H6c-1.1 0-2 .9-2 2v16c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V8l-6-6zM6 20V4h7v5h5v11H6z"/>
                </svg>
                <h3>Preview not available</h3>
                <p style="opacity:0.7; margin-top:8px;">This file type cannot be previewed.</p>
            </div>`;
    }
}

let videoHideTimer;
function initVideoPlayer() {
    const video = $('custom-video');
    const wrap = $('custom-video-wrapper');
    const ctrls = $('video-controls');
    const btnPlay = $('btn-play-pause');
    const iconPlay = $('icon-play');
    const iconPause = $('icon-pause');
    const progCont = $('video-progress-container');
    const progFg = $('video-progress');
    const tCur = $('time-current');
    const tTot = $('time-total');
    const btnMute = $('btn-mute');
    const iconVol = $('icon-vol');
    const iconMute = $('icon-mute');
    const volSlider = $('volume-slider');
    const btnFs = $('btn-fullscreen');

    const fmtT = (s) => {
        if(isNaN(s) || s === Infinity) return '0:00';
        const m = Math.floor(s/60);
        const secs = Math.floor(s%60);
        return m + ':' + (secs<10?'0':'') + secs;
    };

    video.addEventListener('loadedmetadata', () => { tTot.textContent = fmtT(video.duration); });
    video.addEventListener('timeupdate', () => {
        tCur.textContent = fmtT(video.currentTime);
        progFg.style.width = video.duration ? (video.currentTime / video.duration * 100) + '%' : '0%';
    });

    const togglePlay = () => { if(video.paused) video.play(); else video.pause(); };
    btnPlay.addEventListener('click', togglePlay);
    video.addEventListener('click', togglePlay);
    video.addEventListener('play', () => { iconPlay.style.display = 'none'; iconPause.style.display = ''; });
    video.addEventListener('pause', () => { iconPlay.style.display = ''; iconPause.style.display = 'none'; });

    progCont.addEventListener('click', e => {
        if (!video.duration) return;
        const r = progCont.getBoundingClientRect();
        video.currentTime = ((e.clientX - r.left) / r.width) * video.duration;
    });

    const toggleMute = () => { video.muted = !video.muted; };
    btnMute.addEventListener('click', toggleMute);
    video.addEventListener('volumechange', () => {
        if(video.muted || video.volume === 0) {
            iconVol.style.display = 'none'; iconMute.style.display = ''; volSlider.value = 0;
        } else {
            iconVol.style.display = ''; iconMute.style.display = 'none'; volSlider.value = video.volume;
        }
    });

    volSlider.addEventListener('input', e => { video.muted = false; video.volume = e.target.value; });

    btnFs.addEventListener('click', () => {
        if (!document.fullscreenElement) {
            if(wrap.requestFullscreen) wrap.requestFullscreen();
        } else {
            if(document.exitFullscreen) document.exitFullscreen();
        }
    });

    const showCtrls = () => {
        ctrls.classList.add('active');
        clearTimeout(videoHideTimer);
        videoHideTimer = setTimeout(() => { if(!video.paused) ctrls.classList.remove('active'); }, 2000);
    };
    wrap.addEventListener('mousemove', showCtrls);
    wrap.addEventListener('click', showCtrls);
    wrap.addEventListener('mouseleave', () => { if(!video.paused) ctrls.classList.remove('active'); });
}

function navigateViewer(dir) {
    if (state.viewerGallery.length <= 1 || state.viewerIndex < 0) return;
    state.viewerIndex += dir;
    if (state.viewerIndex < 0) state.viewerIndex = state.viewerGallery.length - 1;
    if (state.viewerIndex >= state.viewerGallery.length) state.viewerIndex = 0;
    
    const file = state.viewerGallery[state.viewerIndex];
    const filePath = file.path || (state.path === '/' ? '/' + file.name : state.path + '/' + file.name);
    state.viewerFile = filePath;
    dom.viewerTitle.textContent = file.name;
    
    const extLower = (file.extension || '').toLowerCase();
    let url = `${API}/files/download/?path=${encodeURIComponent(filePath)}&view=1`;
    if (['psd', 'psb', 'sai', 'sai2', 'tiff', 'raw', 'cr2', 'nef', 'arw'].includes(extLower)) {
        url = `${API}/view/?path=${encodeURIComponent(filePath)}`;
    }
    
    dom.viewerBody.innerHTML = `<img src="${url}" class="viewer-content">`;
}

function closeViewer() {
    dom.viewerOverlay.classList.add('hidden');
    dom.viewerBody.innerHTML = '';
    state.viewerFile = null;
    if (typeof videoHideTimer !== 'undefined') clearTimeout(videoHideTimer);
}

// ── Context Menu ──────────────────────────────────────────────
function showCtx(x, y, bg = false) {
    const m = bg ? dom.bgContextMenu : dom.contextMenu;
    if (!m) return;
    m.classList.remove('hidden');
    const r = m.getBoundingClientRect();
    if (x + r.width > innerWidth) x = innerWidth - r.width - 8;
    if (y + r.height > innerHeight) y = innerHeight - r.height - 8;
    m.style.left = x + 'px';
    m.style.top  = y + 'px';
}
function hideCtx() { 
    if (dom.contextMenu) dom.contextMenu.classList.add('hidden'); 
    if (dom.bgContextMenu) dom.bgContextMenu.classList.add('hidden'); 
    state.ctxFile = null; 
}

// ── File Operations ───────────────────────────────────────────
function downloadFile(path) {
    const a = document.createElement('a');
    a.href = `${API}/files/download/?path=${encodeURIComponent(path)}`;
    a.download = '';
    document.body.appendChild(a);
    a.click();
    a.remove();
}

function downloadSelected() {
    const paths = Array.from(state.selected).map(n => n.startsWith('/') ? n : (state.path === '/' ? '/' + n : state.path + '/' + n));
    if (paths.length === 0) return;
    if (paths.length === 1) {
        // If single folder, zip it
        const f = state.files.find(x => {
            const p = x.path || (state.path === '/' ? '/' + x.name : state.path + '/' + x.name);
            return p === paths[0];
        });
        if (f && f.type === 'folder') {
            triggerZipDownload([paths[0]]);
        } else {
            downloadFile(paths[0]);
        }
        return;
    }
    
    triggerZipDownload(paths);
}

function triggerZipDownload(paths) {
    const form = document.createElement('form');
    form.method = 'POST';
    form.action = `${API}/files/zip_download/`;
    
    paths.forEach(p => {
        const input = document.createElement('input');
        input.type = 'hidden';
        input.name = 'paths[]';
        input.value = p;
        form.appendChild(input);
    });
    
    document.body.appendChild(form);
    form.submit();
    setTimeout(() => form.remove(), 1000);
}

function createFolderPrompt() {
    showModal('New Folder', '<input type="text" id="m-input" placeholder="Folder name" autofocus>', () => {
        const v = $('m-input').value.trim();
        createFolder(v || 'New Folder');
    });
    setTimeout(() => { const i = $('m-input'); if (i) i.focus(); }, 80);
}

async function createFolder(name) {
    try {
        const r = await fetch(`${API}/folders/`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ path: state.path, name })
        });
        const j = await r.json();
        if (!j.success) throw new Error(j.error);
        loadFiles(state.path);
        reloadTreeFolder(state.path);
    } catch (e) { alert('Error: ' + e.message); }
}

function renamePrompt() {
    const f = state.ctxFile;
    if (!f) return;
    showModal('Rename', `<input type="text" id="m-input" value="${escA(f.name)}" autofocus>`, () => {
        const v = $('m-input').value.trim();
        if (v && v !== f.name) renameItem(state.path === '/' ? '/' + f.name : state.path + '/' + f.name, v);
    });
    setTimeout(() => {
        const i = $('m-input');
        if (i) { i.focus(); const d = f.name.lastIndexOf('.'); i.setSelectionRange(0, d > 0 && f.type === 'file' ? d : f.name.length); }
    }, 80);
}

async function renameItem(path, newName) {
    try {
        const r = await fetch(`${API}/files/`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json', 'X-HTTP-Method-Override': 'PATCH' },
            body: JSON.stringify({ path, new_name: newName })
        });
        const j = await r.json();
        if (!j.success) throw new Error(j.error);
        loadFiles(state.path);
        refreshSidebarTree(true);
    } catch (e) { alert('Error: ' + e.message); }
}

let moveDestPath = '/';
let moveTargetPaths = [];
let moveConfirmCallback = null;

async function movePrompt(targetPaths = null) {
    moveTargetPaths = targetPaths || selectedPaths();
    if (!moveTargetPaths.length) return;
    
    moveConfirmCallback = null;
    $('move-modal-title').textContent = 'Move Items';
    dom.btnMoveConfirm.textContent = 'Move Here';
    dom.moveModal.classList.remove('hidden');
    await loadMoveFolders('/');
}



async function loadMoveFolders(path) {
    moveDestPath = path;
    dom.moveBody.innerHTML = '<div class="spinner"></div>';
    try {
        const r = await fetch(`${API}/files/?path=${encodeURIComponent(path)}&only_folders=1&t=${Date.now()}`);
        const j = await r.json();
        if (!j.success) throw new Error(j.error);
        
        let bHtml = '';
        j.data.breadcrumbs.forEach((c, i) => {
            if (i > 0) bHtml += '<span class="sep">/</span>';
            if (i === j.data.breadcrumbs.length - 1) bHtml += `<span class="cur">${esc(c.name)}</span>`;
            else bHtml += `<a href="#" data-path="${escA(c.path)}">${esc(c.name)}</a>`;
        });
        dom.moveBreadcrumbs.innerHTML = bHtml;
        dom.moveBreadcrumbs.querySelectorAll('a').forEach(a => 
            a.addEventListener('click', e => { e.preventDefault(); loadMoveFolders(a.dataset.path); })
        );

        let fHtml = '';
        if (j.data.files.length === 0) {
            fHtml = '<div style="color:var(--text-3); text-align:center; padding: 20px;">No folders here</div>';
        } else {
            j.data.files.forEach(f => {
                const nextPath = path === '/' ? '/' + f.name : path + '/' + f.name;
                const isSelfOrChild = moveTargetPaths.some(p => nextPath.startsWith(p));
                if (!isSelfOrChild) {
                    fHtml += `<div class="move-folder-item" data-path="${escA(nextPath)}">
                        <svg viewBox="0 0 24 24" width="24" height="24" fill="#f59e0b"><path d="M10 4H4c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2h-8l-2-2z"/></svg>
                        ${esc(f.name)}
                    </div>`;
                }
            });
        }
        dom.moveBody.innerHTML = fHtml;
        dom.moveBody.querySelectorAll('.move-folder-item').forEach(el => 
            el.addEventListener('click', () => loadMoveFolders(el.dataset.path))
        );
    } catch(e) { dom.moveBody.innerHTML = 'Error loading folders'; }
}

async function moveItems(paths, dest) {
    try {
        const r = await fetch(`${API}/files/move/`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ paths, destination: dest })
        });
        const j = await r.json();
        if (j.errors?.length) alert('Errors:\n' + j.errors.join('\n'));
        loadFiles(state.path);
        refreshSidebarTree(true);
    } catch (e) { alert('Error: ' + e.message); }
}

function deletePrompt(targetPaths = null) {
    const paths = targetPaths || selectedPaths();
    if (!paths.length) return;
    
    // Extract names for the prompt
    const names = paths.map(p => p.split('/').pop());
    
    const msg = names.length === 1
        ? `<p>Delete <strong>${esc(names[0])}</strong>?</p>`
        : `<p>Delete ${names.length} items?</p>`;
    showModal('Confirm Delete', msg, () => deleteItems(paths), true);
}

async function deleteItems(paths) {
    try {
        const r = await fetch(`${API}/files/`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json', 'X-HTTP-Method-Override': 'DELETE' },
            body: JSON.stringify({ paths })
        });
        const j = await r.json();
        if (!j.success) throw new Error(j.error || 'Failed to delete');
        state.selected.clear();
        updateSelectionBar();
        const deletedNames = paths.map(p => p.split('/').pop());
        state.files = state.files.filter(f => !deletedNames.includes(f.name));
        
        const scrollY = dom.fileContainer ? dom.fileContainer.scrollTop : 0;
        renderFiles();
        if (dom.fileContainer) dom.fileContainer.scrollTop = scrollY;
        reloadTreeFolder(state.path);
        loadStorageInfo();
    } catch (e) { alert('Error: ' + e.message); }
}

async function restoreItems(ids) {
    if (!ids || !ids.length) return;
    try {
        const r = await fetch(`${API}/trash/restore/`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ ids })
        });
        const j = await r.json();
        if (!j.success) throw new Error(j.error || 'Failed to restore');
        state.selected.clear();
        updateSelectionBar();
        loadFiles(state.path);
        refreshSidebarTree(true);
    } catch (e) { alert('Error: ' + e.message); }
}

async function deletePermanent(ids = []) {
    try {
        const r = await fetch(`${API}/trash/empty/`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json', 'X-HTTP-Method-Override': 'DELETE' },
            body: JSON.stringify({ ids })
        });
        const j = await r.json();
        if (!j.success) throw new Error(j.error || 'Failed to delete permanently');
        state.selected.clear();
        updateSelectionBar();
        loadFiles(state.path);
        loadStorageInfo();
    } catch (e) { alert('Error: ' + e.message); }
}

function zipPrompt(paths) {
    if (!paths || paths.length === 0) return;
    let defaultName = 'archive.zip';
    if (paths.length === 1) {
        const base = paths[0].split('/').pop();
        if (base) {
            defaultName = base.includes('.') && !base.endsWith('/') ? base.split('.').slice(0, -1).join('.') + '.zip' : base + '.zip';
        }
    }
    showModal('Compress to ZIP', `<input type="text" id="m-input" value="${escA(defaultName)}" placeholder="archive.zip" autofocus>`, () => {
        const v = $('m-input').value.trim();
        if (v) zipItems(paths, v);
    });
    setTimeout(() => {
        const i = $('m-input');
        if (i) { i.focus(); const d = defaultName.lastIndexOf('.'); i.setSelectionRange(0, d > 0 ? d : defaultName.length); }
    }, 80);
}

async function zipItems(paths, zipName) {
    if (dom.loadingState) dom.loadingState.classList.remove('hidden');
    try {
        const r = await fetch(`${API}/files/zip/`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ paths, zip_name: zipName, destination: state.path })
        });
        const j = await r.json();
        if (!j.success) throw new Error(j.error);
        state.selected.clear();
        updateSelectionBar();
        loadFiles(state.path);
        refreshSidebarTree(true);
    } catch (e) { alert('Error: ' + e.message); }
    finally { if (dom.loadingState) dom.loadingState.classList.add('hidden'); }
}

function unzipToPrompt(path) {
    if (!path) return;
    moveTargetPaths = [];
    moveConfirmCallback = () => unzipItem(path, moveDestPath);
    $('move-modal-title').textContent = 'Extract To…';
    dom.btnMoveConfirm.textContent = 'Extract Here';
    dom.moveModal.classList.remove('hidden');
    loadMoveFolders(state.path);
}

async function unzipItem(path, destination) {
    if (dom.loadingState) dom.loadingState.classList.remove('hidden');
    try {
        const r = await fetch(`${API}/files/unzip/`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ path, destination })
        });
        const j = await r.json();
        if (!j.success) throw new Error(j.error);
        state.selected.clear();
        updateSelectionBar();
        loadFiles(state.path);
        refreshSidebarTree(true);
    } catch (e) { alert('Error: ' + e.message); }
    finally { if (dom.loadingState) dom.loadingState.classList.add('hidden'); }
}

function selectedPaths() {
    return Array.from(state.selected).map(n => n.startsWith('/') ? n : (state.path === '/' ? '/' + n : state.path + '/' + n));
}

function selectedIds() {
    const ids = [];
    state.selected.forEach(path => {
        const file = state.files.find(f => {
            const p = f.path || (state.path === '/' ? '/' + f.name : state.path + '/' + f.name);
            return p === path;
        });
        if (file && file.id) ids.push(file.id);
    });
    return ids;
}

// ── Chunked Upload ────────────────────────────────────────────
function startUpload(fileList, basePath) {
    if (!fileList?.length) return;
    for (const file of fileList) {
        let folder = basePath || state.path;
        if (folder === '/Trash' || folder.startsWith('/Trash/')) {
            alert('Cannot upload directly to the Trash folder.');
            return;
        }
        const relPath = file.customPath || file.webkitRelativePath;
        if (relPath) {
            const parts = relPath.split('/');
            parts.pop();
            if (parts.length) {
                const sub = '/' + parts.join('/');
                folder = state.path === '/' ? sub : state.path + sub;
            }
        }

        const id = genId();
        state.uploads.set(id, {
            id, file, folder, progress: 0, speed: 0, status: 'queued',
            startTime: null, uploaded: 0, error: null,
        });
        queue.push(id);
    }
    renderUploads();
    showUploads();
    drainQueue();
}

function drainQueue() {
    while (queue.length && active < MAX_UPLOADS) {
        const id = queue.shift();
        active++;
        processUpload(id).finally(() => { active--; drainQueue(); });
    }
}

async function processUpload(id) {
    const u = state.uploads.get(id);
    if (!u) return;
    
    const total = Math.max(1, Math.ceil(u.file.size / CHUNK_SIZE));
    if (!u.chunks) u.chunks = new Array(total).fill(0);
    if (!u.xhrs) u.xhrs = new Set();
    
    u.status = 'uploading';
    u.startTime = Date.now();
    u.speed = 0;
    renderUploads(true);

    try {
        const concurrency = UPLOAD_CONCURRENCY;
        let activeChunks = 0;
        let currentIndex = 0;
        
        await new Promise((resolve, reject) => {
            function next() {
                if (u.status !== 'uploading') return resolve();
                if (currentIndex >= total && activeChunks === 0) return resolve();
                
                while (activeChunks < concurrency && currentIndex < total && u.status === 'uploading') {
                    const i = currentIndex++;
                    if (u.chunks[i] === true) continue;
                    
                    activeChunks++;
                    const start = i * CHUNK_SIZE;
                    const end   = Math.min(start + CHUNK_SIZE, u.file.size);
                    const chunk = u.file.slice(start, end);
                    
                    const xhr = new XMLHttpRequest();
                    u.xhrs.add(xhr);
                    
                    xhr.open('POST', `${API}/files/upload/`);
                    xhr.setRequestHeader('X-File-Id', id);
                    xhr.setRequestHeader('X-Chunk-Index', String(i));
                    xhr.setRequestHeader('X-Total-Chunks', String(total));
                    xhr.setRequestHeader('X-Filename', encodeURIComponent(u.file.name));
                    xhr.setRequestHeader('X-Folder', encodeURIComponent(u.folder));
                    xhr.setRequestHeader('X-File-Size', String(u.file.size));
                    xhr.setRequestHeader('X-Chunk-Size', String(CHUNK_SIZE));
                    
                    xhr.upload.onprogress = (e) => {
                        if (e.lengthComputable && u.status === 'uploading') {
                            u.chunks[i] = e.loaded;
                            calcProgress(u);
                        }
                    };
                    
                    xhr.onload = () => {
                        u.xhrs.delete(xhr);
                        if (u.status !== 'uploading') { activeChunks--; next(); return; }
                        if (xhr.status === 401) {
                            redirectToAccount();
                            reject(new Error('Root sign-in required'));
                            return;
                        }
                        
                        if (xhr.status >= 200 && xhr.status < 300) {
                            u.chunks[i] = true;
                            calcProgress(u);
                            activeChunks--;
                            next();
                        } else {
                            let err = `HTTP ${xhr.status}`;
                            try { err = JSON.parse(xhr.responseText).error || err; } catch(e){}
                            reject(new Error(err));
                        }
                    };
                    xhr.onerror = () => {
                        u.xhrs.delete(xhr);
                        if (u.status === 'uploading') reject(new Error('Network Error'));
                        else { activeChunks--; next(); }
                    };
                    xhr.send(chunk);
                }
                if (currentIndex >= total && activeChunks === 0) resolve();
            }
            next();
        });

        if (u.status === 'canceled') return;
        if (u.status === 'paused') { renderUploads(true); return; }

        u.status = 'assembling';
        renderUploads(true);

        const r = await fetch(`${API}/files/upload/complete/`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ file_id: id, filename: u.file.name, total_chunks: total, folder: u.folder }),
        });
        const resJson = await r.json().catch(() => ({}));
        if (!r.ok) { throw new Error(resJson.error || 'Assembly failed'); }

        u.status = 'done';
        u.progress = 1;
        renderUploads(true);
        
        if (resJson.file) {
            let reRender = false;
            if (u.folder === state.path) {
                const idx = state.files.findIndex(f => f.name === resJson.file.name);
                if (idx >= 0) state.files[idx] = resJson.file;
                else state.files.push(resJson.file);
                reRender = true;
            } else if (u.folder.startsWith(state.path === '/' ? '/' : state.path + '/')) {
                let rel = u.folder.substring(state.path === '/' ? 1 : state.path.length + 1);
                let topFolder = rel.split('/')[0];
                if (topFolder && !state.files.find(f => f.name === topFolder)) {
                    state.files.push({
                        name: topFolder, type: 'folder', size: 0,
                        modified: Math.floor(Date.now() / 1000), extension: '',
                        icon: 'folder',
                        path: state.path === '/' ? '/' + topFolder : state.path + '/' + topFolder
                    });
                    reRender = true;
                }
            }
            if (reRender) renderFiles();
        }
        
        const hasActiveUploads = Array.from(state.uploads.values()).some(up => ['queued', 'uploading', 'assembling'].includes(up.status));
        if (!hasActiveUploads) {
            refreshSidebarTree(true);
            loadStorageInfo();
        }
    } catch (e) {
        if (u.status !== 'canceled') {
            u.status = 'error';
            u.error = e.message;
            renderUploads(true);
        }
    }
}

function calcProgress(u) {
    let loaded = 0;
    for (let i = 0; i < u.chunks.length; i++) {
        if (u.chunks[i] === true) {
            const start = i * CHUNK_SIZE;
            const end   = Math.min(start + CHUNK_SIZE, u.file.size);
            loaded += (end - start);
        } else if (typeof u.chunks[i] === 'number') {
            loaded += u.chunks[i];
        }
    }
    u.uploaded = loaded;
    u.progress = u.uploaded / u.file.size;
    
    if (!u.speedHistory) u.speedHistory = [];
    const now = Date.now();
    u.speedHistory.push({ time: now, loaded });
    while (u.speedHistory.length > 0 && u.speedHistory[0].time < now - 2000) {
        u.speedHistory.shift();
    }
    
    if (u.speedHistory.length > 1) {
        const first = u.speedHistory[0];
        const last = u.speedHistory[u.speedHistory.length - 1];
        const dt = (last.time - first.time) / 1000;
        if (dt > 0) u.speed = (last.loaded - first.loaded) / dt;
    } else {
        u.speed = 0;
    }
    
    renderUploads();
}

let _lastRender = 0;
let _renderRAF = 0;
function renderUploads(force = false) {
    const now = Date.now();
    if (!force && now - _lastRender < 300) return;
    if (_renderRAF) cancelAnimationFrame(_renderRAF);
    _renderRAF = requestAnimationFrame(() => {
        _renderRAF = 0;
        _lastRender = Date.now();
        _doRenderUploads();
    });
}

function _doRenderUploads() {

    let h = '', done = 0, tot = 0;
    state.uploads.forEach(u => {
        tot++;
        if (u.status === 'done') done++;
        const pct = Math.round(u.progress * 100);
        let st = '', cls = '';
        switch (u.status) {
            case 'queued':     st = 'Queued'; break;
            case 'uploading':  st = `${pct}% · ${fmtSize(u.speed)}/s`; cls = ' uploading'; break;
            case 'paused':     st = 'Paused'; cls = ' paused'; break;
            case 'assembling': st = 'Assembling…'; break;
            case 'done':       st = 'Done'; cls = ' done'; break;
            case 'error':      st = u.error || 'Error'; cls = ' error'; break;
        }

        let actionsHtml = '';
        if (['queued', 'uploading', 'paused', 'error'].includes(u.status)) {
            const isPaused = u.status === 'paused' || u.status === 'error';
            const icon = isPaused ? `<svg viewBox="0 0 24 24" width="16" height="16" fill="currentColor"><path d="M8 5v14l11-7z"/></svg>` : `<svg viewBox="0 0 24 24" width="16" height="16" fill="currentColor"><path d="M6 19h4V5H6v14zm8-14v14h4V5h-4z"/></svg>`;
            actionsHtml = `<div class="upload-item-actions" style="display:flex; gap:4px; margin-left:12px;">
                <button class="btn-icon-sm" onclick="toggleUpload('${u.id}')" title="${isPaused ? 'Resume' : 'Pause'}">${icon}</button>
                <button class="btn-icon-sm" onclick="cancelUpload('${u.id}')" title="Cancel"><svg viewBox="0 0 24 24" width="16" height="16" fill="currentColor"><path d="M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z"/></svg></button>
            </div>`;
        }

        h += `<div class="upload-item">
            <div class="upload-item-row">
                <span class="upload-item-name" title="${escA(u.file.name)}">${esc(u.file.name)}</span>
                <div style="display:flex; align-items:center;">
                    <span class="upload-item-status${cls}">${st}</span>
                    ${actionsHtml}
                </div>
            </div>
            <div class="progress-bar"><div class="progress-fill${cls}" style="width:${pct}%"></div></div>
            <div class="upload-item-info"><span>${fmtSize(u.uploaded)} / ${fmtSize(u.file.size)}</span></div>
        </div>`;
    });
    dom.uploadList.innerHTML = h;
    dom.uploadPanelTitle.textContent = `Uploads (${done}/${tot})`;
}

window.toggleUpload = function(id) {
    const u = state.uploads.get(id);
    if (!u) return;
    if (u.status === 'uploading' || u.status === 'queued') {
        u.status = 'paused';
        u.speedHistory = []; // Reset speed history
        if (u.xhrs) {
            for (const xhr of u.xhrs) xhr.abort();
            u.xhrs.clear();
        }
        renderUploads(true);
    } else if (u.status === 'paused' || u.status === 'error') {
        u.status = 'queued';
        u.startTime = Date.now(); // Reset start time if needed
        queue.push(id);
        renderUploads(true);
        drainQueue();
    }
}

window.cancelUpload = function(id) {
    const u = state.uploads.get(id);
    if (!u) return;
    u.status = 'canceled';
    if (u.xhrs) {
        for (const xhr of u.xhrs) xhr.abort();
        u.xhrs.clear();
    }
    state.uploads.delete(id);
    fetch(`${API}/files/upload/cancel/`, { method: 'POST', body: JSON.stringify({ file_id: id }) }).catch(()=>{});
    renderUploads(true);
}

function showUploads() { dom.uploadPanel.classList.remove('hidden', 'minimized'); }
function toggleUploads() { dom.uploadPanel.classList.toggle('minimized'); }
function closeUploads() {
    const busy = [...state.uploads.values()].some(u => ['uploading','assembling','queued'].includes(u.status));
    if (busy) { dom.uploadPanel.classList.add('minimized'); }
    else { dom.uploadPanel.classList.add('hidden'); state.uploads.clear(); }
}

window.clearDoneUploads = function() {
    for (const [id, u] of state.uploads.entries()) {
        if (u.status === 'done' || u.status === 'error' || u.status === 'canceled') {
            state.uploads.delete(id);
        }
    }
    if (state.uploads.size === 0) {
        closeUploads();
    } else {
        renderUploads(true);
    }
};

// ── Modal ─────────────────────────────────────────────────────
let _modalCb = null;
function showModal(title, body, cb, danger) {
    dom.modalTitle.textContent = title;
    dom.modalBody.innerHTML    = body;
    dom.modalOverlay.classList.remove('hidden');
    dom.btnModalConfirm.className = danger ? 'btn btn-danger' : 'btn btn-primary';
    dom.btnModalConfirm.textContent = danger ? 'Delete' : 'Confirm';
    _modalCb = cb;
    const inp = dom.modalBody.querySelector('input');
    if (inp) inp.addEventListener('keydown', e => { if (e.key === 'Enter') { e.preventDefault(); confirmModal(); } });
}
function hideModal() { dom.modalOverlay.classList.add('hidden'); _modalCb = null; }
function confirmModal() { if (_modalCb) _modalCb(); hideModal(); }

// ── Drag & Drop ───────────────────────────────────────────────
let dragN = 0;
let isInternalDrag = false;

function setupDnD() {
    const b = document.body;
    
    document.addEventListener('dragstart', () => { isInternalDrag = true; });
    document.addEventListener('dragend', () => { isInternalDrag = false; });
    
    const isExternalFile = e => !isInternalDrag && e.dataTransfer.types && Array.from(e.dataTransfer.types).includes('Files');
    
    b.addEventListener('dragenter', e => { 
        if (!isExternalFile(e)) return;
        e.preventDefault(); 
        if (++dragN === 1) dom.dropOverlay.classList.remove('hidden'); 
    });
    b.addEventListener('dragleave', e => { 
        if (!isExternalFile(e)) return;
        e.preventDefault(); 
        if (--dragN <= 0) { dragN = 0; dom.dropOverlay.classList.add('hidden'); } 
    });
    b.addEventListener('dragover',  e => { 
        if (!isExternalFile(e)) return;
        e.preventDefault(); 
        e.dataTransfer.dropEffect = 'copy'; 
    });
    b.addEventListener('drop', async e => {
        if (!isExternalFile(e)) return;
        e.preventDefault(); dragN = 0; dom.dropOverlay.classList.add('hidden');
        
        const items = e.dataTransfer.items;
        if (!items) {
            if (e.dataTransfer.files.length) startUpload(e.dataTransfer.files);
            return;
        }

        const entries = [];
        for (let i = 0; i < items.length; i++) {
            const item = items[i];
            if (item.kind === 'file') {
                const entry = item.webkitGetAsEntry();
                if (entry) entries.push(entry);
            }
        }
        
        if (!entries.length) {
            if (e.dataTransfer.files.length) startUpload(e.dataTransfer.files);
            return;
        }

        const filesToUpload = [];
        async function readEntry(entry, path = '') {
            if (entry.isFile) {
                return new Promise(resolve => {
                    entry.file(file => {
                        file.customPath = path ? path + '/' + file.name : file.name;
                        filesToUpload.push(file);
                        resolve();
                    });
                });
            } else if (entry.isDirectory) {
                const dirReader = entry.createReader();
                const newPath = path ? path + '/' + entry.name : entry.name;
                
                return new Promise(resolve => {
                    const readAll = () => {
                        dirReader.readEntries(async dirEntries => {
                            if (dirEntries.length === 0) {
                                resolve();
                            } else {
                                for (const child of dirEntries) {
                                    await readEntry(child, newPath);
                                }
                                readAll();
                            }
                        });
                    };
                    readAll();
                });
            }
        }
        
        for (const entry of entries) {
            await readEntry(entry);
        }
        
        if (filesToUpload.length) startUpload(filesToUpload);
    });
}

// ── Events ────────────────────────────────────────────────────
function bind() {
    if (dom.btnSettings) dom.btnSettings.addEventListener('click', loadSettings);
    if (dom.btnSettingsClose) dom.btnSettingsClose.addEventListener('click', () => { if (dom.settingsOverlay) dom.settingsOverlay.classList.add('hidden'); });
    if (dom.btnSettingsCancel) dom.btnSettingsCancel.addEventListener('click', () => { if (dom.settingsOverlay) dom.settingsOverlay.classList.add('hidden'); });
    if (dom.btnSettingsSave) dom.btnSettingsSave.addEventListener('click', saveSettings);
    
    const btnCleanSyscache = $('btn-clean-syscache');
    if (btnCleanSyscache) btnCleanSyscache.addEventListener('click', () => triggerCleanup('clean_syscache'));
    
    const btnCleanImgcache = $('btn-clean-imgcache');
    if (btnCleanImgcache) btnCleanImgcache.addEventListener('click', () => triggerCleanup('clean_imgcache'));

    if (dom.btnCleanChunk) dom.btnCleanChunk.addEventListener('click', () => triggerCleanup('clean_chunk'));
    if (dom.btnCleanTrash) dom.btnCleanTrash.addEventListener('click', () => triggerCleanup('clean_trash'));
    if (dom.btnClearTrash) dom.btnClearTrash.addEventListener('click', () => triggerCleanup('clean_trash'));
    
    if (dom.btnMinimizeUploads) dom.btnMinimizeUploads.addEventListener('click', toggleUploads);
    if (dom.btnCloseUploads) dom.btnCloseUploads.addEventListener('click', closeUploads);
    if (dom.btnClearUploads) dom.btnClearUploads.addEventListener('click', window.clearDoneUploads);

    if (dom.btnNew && dom.newMenu) {
        dom.btnNew.addEventListener('click', e => {
            e.stopPropagation();
            dom.newMenu.classList.toggle('hidden');
        });
    }
    
    if (dom.btnUploadFile) dom.btnUploadFile.addEventListener('click', () => { dom.fileInput.click(); dom.newMenu.classList.add('hidden'); });
    if (dom.btnUploadFolder) dom.btnUploadFolder.addEventListener('click', () => { dom.folderInput.click(); dom.newMenu.classList.add('hidden'); });
    if (dom.btnNewFolder) dom.btnNewFolder.addEventListener('click', () => { createFolderPrompt(); dom.newMenu.classList.add('hidden'); });
    
    const searchInput = $('search-input');
    let searchTimer = null;
    if (searchInput) {
        searchInput.addEventListener('input', e => {
            clearTimeout(searchTimer);
            searchTimer = setTimeout(() => {
                const q = e.target.value.trim();
                if (q) performSearch(q);
                else loadFiles(state.path);
            }, 300);
        });
        searchInput.setAttribute('placeholder', 'Search files... (*? wildcard, type:image)');
    }

    // ── Sort/Group Menu ──
    const btnSortGroup = $('btn-sort-group');
    const sortGroupMenu = $('sort-group-menu');
    if (btnSortGroup && sortGroupMenu) {
        btnSortGroup.addEventListener('click', e => {
            e.stopPropagation();
            sortGroupMenu.classList.toggle('hidden');
            if (!sortGroupMenu.classList.contains('hidden')) applySortGroup();
        });
    }

    document.querySelectorAll('[data-sort]').forEach(btn => {
        btn.addEventListener('click', () => {
            state.sort = btn.dataset.sort;
            localStorage.setItem('cd_sort', state.sort);
            localStorage.setItem('cd_sort_' + state.path, state.sort);
            applySortGroup();
            renderFiles();
            hideCtx();
        });
    });
    document.querySelectorAll('[data-dir]').forEach(btn => {
        btn.addEventListener('click', () => {
            state.sortDir = btn.dataset.dir;
            localStorage.setItem('cd_sortDir', state.sortDir);
            localStorage.setItem('cd_sortDir_' + state.path, state.sortDir);
            applySortGroup();
            renderFiles();
            hideCtx();
        });
    });
    document.querySelectorAll('[data-group]').forEach(btn => {
        btn.addEventListener('click', () => {
            state.group = btn.dataset.group;
            localStorage.setItem('cd_group', state.group);
            localStorage.setItem('cd_group_' + state.path, state.group);
            applySortGroup();
            renderFiles();
            hideCtx();
        });
    });

    const _viewToggle = $('btn-view-toggle');
    if (_viewToggle) _viewToggle.addEventListener('click', toggleView);
    const _themeToggle = $('btn-theme');
    if (_themeToggle) _themeToggle.addEventListener('click', toggleTheme);
    const _delSel = $('btn-delete-selected');
    if (_delSel) _delSel.addEventListener('click', () => deletePrompt());
    const _dlSel = $('btn-download-selected');
    if (_dlSel) _dlSel.addEventListener('click', downloadSelected);
    
    const btnRestore = $('btn-restore-selected');
    if (btnRestore) btnRestore.addEventListener('click', () => restoreItems(selectedIds()));
    
    const btnDeletePerm = $('btn-delete-permanent-selected');
    if (btnDeletePerm) btnDeletePerm.addEventListener('click', () => showModal('Confirm Delete', '<p>Permanently delete these items?</p>', () => deletePermanent(selectedIds()), true));
    if (dom.btnZipSelected) dom.btnZipSelected.addEventListener('click', () => zipPrompt(selectedPaths()));
    
    const _clrSel = $('btn-clear-selection');
    if (_clrSel) _clrSel.addEventListener('click', () => { state.selected.clear(); updateSelectionBar(); updateSelectionDOM(); });
    const _btnModalClose = $('btn-modal-close');
    if (_btnModalClose) _btnModalClose.addEventListener('click', hideModal);
    if (dom.btnModalCancel) dom.btnModalCancel.addEventListener('click', hideModal);
    if (dom.btnModalConfirm) dom.btnModalConfirm.addEventListener('click', confirmModal);

    if (dom.btnViewerClose) dom.btnViewerClose.addEventListener('click', closeViewer);
    if (dom.viewerPrev) dom.viewerPrev.addEventListener('click', () => navigateViewer(-1));
    if (dom.viewerNext) dom.viewerNext.addEventListener('click', () => navigateViewer(1));
    
    // Keyboard navigation
    document.addEventListener('keydown', e => {
        if (!dom.viewerOverlay || dom.viewerOverlay.classList.contains('hidden')) return;
        if (e.key === 'ArrowLeft') navigateViewer(-1);
        if (e.key === 'ArrowRight') navigateViewer(1);
        if (e.key === 'Escape') closeViewer();
    });
    
    // Touch swipe navigation
    if (dom.viewerBody) {
        let touchStartX = 0;
        dom.viewerBody.addEventListener('touchstart', e => {
            touchStartX = e.changedTouches[0].screenX;
        }, {passive: true});
        dom.viewerBody.addEventListener('touchend', e => {
            const touchEndX = e.changedTouches[0].screenX;
            if (touchEndX - touchStartX > 50) navigateViewer(-1);
            else if (touchStartX - touchEndX > 50) navigateViewer(1);
        }, {passive: true});
    }
    const _btnConvertSelected = $('btn-convert-selected');
    if (_btnConvertSelected) _btnConvertSelected.addEventListener('click', () => {
        openConverterModal(selectedPaths());
    });
    
    if (dom.btnViewerDownload) dom.btnViewerDownload.addEventListener('click', () => {
        if (state.viewerFile) downloadFile(state.viewerFile);
    });

    if (dom.btnMoveClose) dom.btnMoveClose.addEventListener('click', () => { if (dom.moveModal) dom.moveModal.classList.add('hidden'); });
    if (dom.btnMoveCancel) dom.btnMoveCancel.addEventListener('click', () => { if (dom.moveModal) dom.moveModal.classList.add('hidden'); });
    if (dom.btnMoveConfirm) dom.btnMoveConfirm.addEventListener('click', () => {
        if (dom.moveModal) dom.moveModal.classList.add('hidden');
        if (moveConfirmCallback) {
            moveConfirmCallback();
            moveConfirmCallback = null;
        } else {
            moveItems(moveTargetPaths, moveDestPath);
        }
    });

    if (dom.btnMenu) {
        dom.btnMenu.addEventListener('click', () => {
            if (window.innerWidth <= 768) {
                if (dom.sidebar) dom.sidebar.classList.add('open');
                if (dom.mobileOverlay) dom.mobileOverlay.classList.remove('hidden');
            } else {
                if (dom.sidebar) {
                    dom.sidebar.classList.toggle('collapsed');
                    localStorage.setItem('cd_sidebar', dom.sidebar.classList.contains('collapsed') ? '1' : '0');
                }
            }
        });
    }
    if (dom.btnCloseSidebar) {
        dom.btnCloseSidebar.addEventListener('click', () => {
            if (dom.sidebar) dom.sidebar.classList.remove('open');
            if (dom.mobileOverlay) dom.mobileOverlay.classList.add('hidden');
        });
    }

    if (dom.fileInput) dom.fileInput.addEventListener('change', e => { if (e.target.files.length) { startUpload(e.target.files); e.target.value = ''; } });
    if (dom.folderInput) dom.folderInput.addEventListener('change', e => { if (e.target.files.length) { startUpload(e.target.files); e.target.value = ''; } });

    dom.contextMenu.querySelectorAll('[data-action]').forEach(btn => {
        btn.addEventListener('click', () => {
            const a = btn.dataset.action;
            const p = state.ctxFile.path || (state.path === '/' ? '/' + state.ctxFile.name : state.path + '/' + state.ctxFile.name);
            if (a === 'open' && state.ctxFile?.type === 'folder') { loadFiles(p); }
            else if (a === 'download' && state.ctxFile) { 
                if (state.ctxFile.type === 'folder') triggerZipDownload([p]);
                else downloadFile(p);
            }
            else if (a === 'rename') { renamePrompt(); }
            else if (a === 'move')   { movePrompt([p]); }
            else if (a === 'delete') { deletePrompt([p]); }
            else if (a === 'restore') { 
                const id = state.ctxFile.id;
                restoreItems([id]); 
            }
            else if (a === 'delete_permanent') { 
                const id = state.ctxFile.id;
                showModal('Confirm Delete', '<p>Permanently delete this item?</p>', () => deletePermanent([id]), true);
            }
            else if (a === 'zip') {
                const paths = state.selected.has(p) ? selectedPaths() : [p];
                zipPrompt(paths);
            }
            else if (a === 'unzip_here') {
                unzipItem(p, state.path);
            }
            else if (a === 'unzip_to') {
                unzipToPrompt(p);
            }
            else if (a === 'convert') {
                openConverterModal(p);
            }
            hideCtx();
        });
    });

    if (dom.bgContextMenu) {
        dom.bgContextMenu.querySelectorAll('[data-action]').forEach(btn => {
            btn.addEventListener('click', () => {
                const a = btn.dataset.action;
                if (a === 'refresh') loadFiles(state.path);
                else if (a === 'new_folder') createFolderPrompt();
                else if (a === 'new_file') startUpload([new File([''], 'New Text Document.txt', { type: 'text/plain' })]);
                else if (a === 'upload_file') dom.fileInput.click();
                else if (a === 'upload_folder') dom.folderInput.click();
                hideCtx();
            });
        });
    }

    if (dom.fileContainer) dom.fileContainer.addEventListener('contextmenu', onContainerCtx);

    document.addEventListener('click', e => {
        if (dom.contextMenu && !dom.contextMenu.contains(e.target) && dom.bgContextMenu && !dom.bgContextMenu.contains(e.target)) hideCtx(); 
        if (dom.newMenu && !dom.btnNew.contains(e.target) && !dom.newMenu.contains(e.target)) dom.newMenu.classList.add('hidden');
        const sortGroupMenu = $('sort-group-menu');
        const btnSortGroup = $('btn-sort-group');
        if (sortGroupMenu && btnSortGroup && !btnSortGroup.contains(e.target) && !sortGroupMenu.contains(e.target)) sortGroupMenu.classList.add('hidden');
        if (dom.mobileOverlay && e.target === dom.mobileOverlay) {
            dom.sidebar.classList.remove('open');
            dom.mobileOverlay.classList.add('hidden');
        }
        if (dom.viewerOverlay && e.target === dom.viewerOverlay) {
            closeViewer();
        }
    });

    if (dom.modalOverlay) dom.modalOverlay.addEventListener('click', e => { if (e.target === dom.modalOverlay) hideModal(); });
    if (dom.settingsOverlay) dom.settingsOverlay.addEventListener('click', e => { if (e.target === dom.settingsOverlay) dom.settingsOverlay.classList.add('hidden'); });
    bindDragSelect();

    document.addEventListener('keydown', e => {
        if (e.key === 'Escape') {
            hideCtx();
            if (dom.modalOverlay && !dom.modalOverlay.classList.contains('hidden')) hideModal();
            else if (dom.settingsOverlay && !dom.settingsOverlay.classList.contains('hidden')) dom.settingsOverlay.classList.add('hidden');
            else if (dom.viewerOverlay && !dom.viewerOverlay.classList.contains('hidden')) closeViewer();
            else if (state.selected.size) { state.selected.clear(); updateSelectionBar(); updateSelectionDOM(); }
        }
        const modalHidden = !dom.modalOverlay || dom.modalOverlay.classList.contains('hidden');
        const viewerHidden = !dom.viewerOverlay || dom.viewerOverlay.classList.contains('hidden');
        const settingsHidden = !dom.settingsOverlay || dom.settingsOverlay.classList.contains('hidden');
        if (e.key === 'Delete' && state.selected.size && modalHidden && viewerHidden && settingsHidden) {
            deletePrompt();
        }
        if ((e.ctrlKey || e.metaKey) && e.key === 'a' && document.activeElement.tagName !== 'INPUT') {
            e.preventDefault(); 
            state.files.forEach(f => {
                const fPath = f.path || (state.path === '/' ? '/' + f.name : state.path + '/' + f.name);
                state.selected.add(fPath);
            }); 
            updateSelectionBar(); updateSelectionDOM();
        }
    });

    setupDnD();
}

// ── Icons & Utils ─────────────────────────────────────────────
window.handleThumbError = function(img, path, icon) {
    img.style.display = 'none';
    const fallback = img.nextElementSibling;
    if (fallback) fallback.style.display = '';
};

function fileIcon(type, ext, size) {
    const C = {
        folder:'#f59e0b', image:'#8b5cf6', video:'#ef4444', audio:'#ec4899',
        document:'#3b82f6', text:'#6b7280', code:'#10b981', archive:'#f97316', file:'#6b7280',
    };
    const c = C[type] || C.file;
    if (type === 'folder') {
        return `<svg viewBox="0 0 24 24" width="${size}" height="${size}"><path d="M10 4H4c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2h-8l-2-2z" fill="${c}"/></svg>`;
    }
    const label = (ext || '').toUpperCase().substring(0, 4);
    const fs = size > 24 ? 5.5 : 0;
    return `<svg viewBox="0 0 24 24" width="${size}" height="${size}">
        <path d="M14 2H6c-1.1 0-2 .9-2 2v16c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V8l-6-6z" fill="${c}" opacity=".15"/>
        <path d="M14 2H6c-1.1 0-2 .9-2 2v16c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V8l-6-6zM6 20V4h7v5h5v11H6z" fill="${c}"/>
        ${fs ? `<text x="12" y="17" text-anchor="middle" fill="${c}" font-size="${fs}" font-weight="700" font-family="sans-serif">${label}</text>` : ''}
    </svg>`;
}

function genId() { return Date.now().toString(36) + Math.random().toString(36).substr(2, 6); }
function fmtSize(b) {
    if (b == null || isNaN(b) || b === 0) return '0 B';
    const u = ['B','KB','MB','GB','TB'];
    let i = 0, v = b;
    while (v >= 1024 && i < 4) { v /= 1024; i++; }
    return v.toFixed(i ? 1 : 0) + ' ' + u[i];
}
function fmtDate(ts) {
    if (!ts) return '—';
    const d = new Date(ts * 1000), n = new Date();
    if (d.toDateString() === n.toDateString()) return 'Today ' + d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
    return d.toLocaleDateString([], { year: 'numeric', month: 'short', day: 'numeric' });
}
function esc(s) { const d = document.createElement('div'); d.textContent = s; return d.innerHTML; }
function escA(s) { return s.replace(/&/g,'&amp;').replace(/"/g,'&quot;').replace(/'/g,'&#39;').replace(/</g,'&lt;').replace(/>/g,'&gt;'); }

function bindDragSelect() {
    if (!dom.fileContainer) return;
    
    let isDragging = false;
    let startX = 0, startY = 0;
    let box = null;
    let hasDragged = false;
    const fileContainer = dom.fileContainer;

    fileContainer.addEventListener('mousedown', e => {
        if (window.innerWidth <= 768) return;
        if (e.button !== 0) return;
        
        if (e.target.closest('.file-item') || e.target.closest('button') || e.target.closest('.tree-item')) return;
        if (e.offsetX > fileContainer.clientWidth) return;

        isDragging = true;
        hasDragged = false;
        startX = e.pageX;
        startY = e.pageY;
        
        if (!e.ctrlKey && !e.metaKey) {
            state.selected.clear();
            updateSelectionDOM();
            updateSelectionBar();
        }
    });

    document.addEventListener('mousemove', e => {
        if (!isDragging) return;

        const currentX = e.pageX;
        const currentY = e.pageY;
        
        if (!hasDragged && Math.abs(currentX - startX) < 5 && Math.abs(currentY - startY) < 5) return;
        hasDragged = true;

        if (!box) {
            box = document.createElement('div');
            box.className = 'selection-box';
            document.body.appendChild(box);
        }
        
        const left = Math.min(startX, currentX);
        const top = Math.min(startY, currentY);
        const width = Math.abs(currentX - startX);
        const height = Math.abs(currentY - startY);

        box.style.left = left + 'px';
        box.style.top = top + 'px';
        box.style.width = width + 'px';
        box.style.height = height + 'px';

        const boxRect = box.getBoundingClientRect();
        let changed = false;

        dom.fileList.querySelectorAll('.file-item').forEach(el => {
            const rect = el.getBoundingClientRect();
            const intersect = !(rect.right < boxRect.left || 
                                rect.left > boxRect.right || 
                                rect.bottom < boxRect.top || 
                                rect.top > boxRect.bottom);
            
            const fPath = el.dataset.name;
            if (intersect) {
                if (!state.selected.has(fPath)) {
                    state.selected.add(fPath);
                    changed = true;
                }
            } else if (!e.ctrlKey && !e.metaKey) {
                if (state.selected.has(fPath)) {
                    state.selected.delete(fPath);
                    changed = true;
                }
            }
        });

        if (changed) {
            updateSelectionDOM();
            updateSelectionBar();
        }
    });

    document.addEventListener('mouseup', () => {
        if (!isDragging) return;
        isDragging = false;
        if (box) {
            box.remove();
            box = null;
        }
    });
}

// ── Settings & Cleanup Logic ─────────────────────────────────
async function loadSettings() {
    try {
        dom.settingsOverlay.classList.remove('hidden');
        const r = await fetch(`${API}/settings/`);
        const j = await r.json();
        
        const m = j.metrics || (j.data && j.data.metrics);
        const c = j.config || (j.data && j.data.config);

        $('metric-disk-val').textContent = fmtSize(m.disk_used) + ' / ' + fmtSize(m.disk_total);
        const diskPct = (m.disk_total > 0) ? (m.disk_used / m.disk_total) * 100 : 0;
        $('metric-disk-bar').style.width = diskPct + '%';
        
        $('metric-cache-val').textContent = fmtSize(m.cache_size);
        $('metric-chunk-val').textContent = fmtSize(m.chunk_size);
        $('metric-trash-val').textContent = fmtSize(m.trash_size);
        
        $('cfg-memory-limit').value = c.memory_limit || 128;
        $('cfg-buffer-size').value = c.buffer_size || 2048;
        if ($('cfg-chunk-size')) $('cfg-chunk-size').value = c.chunk_size || 5;
        if ($('cfg-max-uploads')) $('cfg-max-uploads').value = c.max_uploads || 3;
        if ($('cfg-thumb-quality')) $('cfg-thumb-quality').value = c.thumbnail_quality || 80;
    } catch (e) {
        alert('Failed to load settings: ' + e.message);
    }
}

async function saveSettings() {
    try {
        const payload = {
            memory_limit: parseInt($('cfg-memory-limit').value, 10) || 128,
            buffer_size: parseInt($('cfg-buffer-size').value, 10) || 2048
        };
        if ($('cfg-chunk-size')) payload.chunk_size = parseInt($('cfg-chunk-size').value, 10) || 500;
        if ($('cfg-max-uploads')) payload.max_uploads = parseInt($('cfg-max-uploads').value, 10) || 3;
        if ($('cfg-thumb-quality')) payload.thumbnail_quality = parseInt($('cfg-thumb-quality').value, 10) || 80;

        const r = await fetch(`${API}/settings/`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload)
        });
        const j = await r.json();
        if (!j.success) throw new Error(j.error);
        
        // Update JS globals with new config
        if (j.config) {
            if (j.config.chunk_size) CHUNK_SIZE = j.config.chunk_size * 1024 * 1024;
            if (j.config.max_uploads) UPLOAD_CONCURRENCY = j.config.max_uploads;
        }

        dom.settingsOverlay.classList.add('hidden');
    } catch (e) {
        alert('Failed to save settings: ' + e.message);
    }
}

async function triggerCleanup(action) {
    let title, msg;
    if (action === 'clean_trash') {
        title = 'Empty Trash';
        msg = '<p>Are you sure you want to permanently delete all items in the trash?</p>';
    } else if (action === 'clean_syscache') {
        title = 'Clean System Cache';
        msg = '<p>Are you sure you want to clear system cache files? This includes folder directory structures and the file tree list.</p>';
    } else if (action === 'clean_imgcache') {
        title = 'Clean Thumbnails';
        msg = '<p>Are you sure you want to clear generated image thumbnails? They will be re-generated on demand.</p>';
    } else if (action === 'clean_chunk') {
        title = 'Clean Chunks';
        msg = '<p>Are you sure you want to clear incomplete upload chunks?</p>';
    }
    
    showModal(title, msg, async () => {
        try {
            const r = await fetch(`${API}/settings/cleanup/`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ action })
            });
            const j = await r.json();
            if (!j.success) throw new Error(j.error);
            if (dom.settingsOverlay && !dom.settingsOverlay.classList.contains('hidden')) {
                loadSettings(); // Reload metrics
            }
            if (action === 'clean_trash' && state.path === '/Trash') loadFiles('/Trash');
            loadStorageInfo(); // Also update the storage widget in sidebar
        } catch (e) {
            alert('Cleanup failed: ' + e.message);
        }
    }, true);
}
