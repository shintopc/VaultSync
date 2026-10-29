// VaultSync PC Client JavaScript

(function () {
    'use strict';

    // Application State
    const state = {
        token: sessionStorage.getItem('vaultsync_token') || null,
        currentMode: 'media', // 'media' or 'vault'
        hasVaultAccess: false,
        currentFolderId: null,
        allFolders: [],
        currentFolder: null,
        rawItems: [],
        filteredItems: [],
        selectedIds: new Set(),
        selectedCategory: 'ALL',
        searchQuery: '',
        sortOption: 'date_desc',
        viewMode: 'grid', // 'grid' or 'list'
        serverInfo: null,
        isReconnecting: false,
        pendingDeleteAction: null,
    };

    // DOM Elements
    const elements = {
        connectionBanner: document.getElementById('connectionBanner'),
        serverPill: document.getElementById('serverPill'),
        serverUrlText: document.getElementById('serverUrlText'),
        navTabs: document.getElementById('navTabs'),
        tabMedia: document.getElementById('tabMedia'),
        tabVault: document.getElementById('tabVault'),
        vaultStatusBadge: document.getElementById('vaultStatusBadge'),
        lockVaultBtn: document.getElementById('lockVaultBtn'),
        logoutBtn: document.getElementById('logoutBtn'),
        loginView: document.getElementById('loginView'),
        loginForm: document.getElementById('loginForm'),
        passcodeInput: document.getElementById('passcodeInput'),
        loginError: document.getElementById('loginError'),
        loginSubmitBtn: document.getElementById('loginSubmitBtn'),
        explorerView: document.getElementById('explorerView'),
        breadcrumbs: document.getElementById('breadcrumbs'),
        searchInput: document.getElementById('searchInput'),
        clearSearchBtn: document.getElementById('clearSearchBtn'),
        sortSelect: document.getElementById('sortSelect'),
        gridToggleBtn: document.getElementById('gridToggleBtn'),
        listToggleBtn: document.getElementById('listToggleBtn'),
        uploadBtn: document.getElementById('uploadBtn'),
        fileUploadInput: document.getElementById('fileUploadInput'),
        newFolderBtn: document.getElementById('newFolderBtn'),
        selectAllBtn: document.getElementById('selectAllBtn'),
        downloadZipBtn: document.getElementById('downloadZipBtn'),
        selectedCountBadge: document.getElementById('selectedCountBadge'),
        deleteSelectedBtn: document.getElementById('deleteSelectedBtn'),
        deleteCountBadge: document.getElementById('deleteCountBadge'),
        refreshBtn: document.getElementById('refreshBtn'),
        categoryFilters: document.getElementById('categoryFilters'),
        foldersSection: document.getElementById('foldersSection'),
        foldersGrid: document.getElementById('foldersGrid'),
        itemsContainer: document.getElementById('itemsContainer'),
        emptyState: document.getElementById('emptyState'),
        emptyStateMsg: document.getElementById('emptyStateMsg'),
        loadingState: document.getElementById('loadingState'),
        filesSectionTitle: document.getElementById('filesSectionTitle'),
        vaultUnlockModal: document.getElementById('vaultUnlockModal'),
        vaultUnlockForm: document.getElementById('vaultUnlockForm'),
        vaultPinInput: document.getElementById('vaultPinInput'),
        vaultUnlockError: document.getElementById('vaultUnlockError'),
        vaultUnlockCancelBtn: document.getElementById('vaultUnlockCancelBtn'),
        newFolderModal: document.getElementById('newFolderModal'),
        newFolderForm: document.getElementById('newFolderForm'),
        newFolderNameInput: document.getElementById('newFolderNameInput'),
        newFolderCancelBtn: document.getElementById('newFolderCancelBtn'),
        confirmDeleteModal: document.getElementById('confirmDeleteModal'),
        confirmDeleteMsg: document.getElementById('confirmDeleteMsg'),
        confirmDeleteCancelBtn: document.getElementById('confirmDeleteCancelBtn'),
        confirmDeleteSubmitBtn: document.getElementById('confirmDeleteSubmitBtn'),
        uploadProgressCard: document.getElementById('uploadProgressCard'),
        uploadStatusText: document.getElementById('uploadStatusText'),
        uploadPercent: document.getElementById('uploadPercent'),
        uploadProgressBar: document.getElementById('uploadProgressBar'),
        uploadFileName: document.getElementById('uploadFileName'),
        dragDropOverlay: document.getElementById('dragDropOverlay'),
        dragDropDestination: document.getElementById('dragDropDestination'),
        toastContainer: document.getElementById('toastContainer'),
        previewModal: document.getElementById('previewModal'),
        previewTitle: document.getElementById('previewTitle'),
        previewMeta: document.getElementById('previewMeta'),
        previewDownloadBtn: document.getElementById('previewDownloadBtn'),
        previewCloseBtn: document.getElementById('previewCloseBtn'),
        previewBody: document.getElementById('previewBody'),
    };

    // API Helpers
    async function apiRequest(endpoint, options = {}) {
        const headers = options.headers || {};
        headers['X-VaultSync-Client'] = 'web';
        if (state.token) {
            headers['Authorization'] = `Bearer ${state.token}`;
        }
        if (options.body && typeof options.body === 'object' && !(options.body instanceof FormData)) {
            headers['Content-Type'] = 'application/json';
            options.body = JSON.stringify(options.body);
        }
        options.headers = headers;

        try {
            const response = await fetch(endpoint, options);
            const data = await response.json().catch(() => null);

            if (response.status === 401) {
                // If this is a vault unlock attempt, 401 indicates an incorrect master credential,
                // NOT an expired PC session token. Do not wipe the user's session!
                const isUnlockAttempt = endpoint.includes('/api/vault/unlock');
                const isTokenError = data?.error?.message?.toLowerCase().includes('token');
                if (!isUnlockAttempt || isTokenError) {
                    handleLogout('Your session has expired. Please sign in again.');
                    return null;
                }
            }

            if (response.status === 403 && data?.error?.code === 'VAULT_LOCKED') {
                // Vault requires elevation
                showVaultUnlockModal();
                return null;
            }

            return { status: response.status, ok: response.ok, data };
        } catch (err) {
            console.error('API Error:', err);
            return { status: 0, ok: false, error: err };
        }
    }

    // Initialization
    async function init() {
        bindEvents();
        setupDragAndDrop();
        await checkServerStatus();
        startWatchdog();

        if (state.token) {
            const sessionRes = await apiRequest('/api/auth/session');
            if (sessionRes?.ok && sessionRes.data?.data) {
                state.hasVaultAccess = sessionRes.data.data.hasVaultAccess;
                updateVaultBadge();
                showExplorerView();
                loadContent();
            } else {
                handleLogout();
            }
        } else {
            showLoginView();
            // Check for QR code auto-pairing via URL hash
            const hash = window.location.hash || '';
            const match = hash.match(/(?:passcode=)?([0-9]{6})/);
            if (match && match[1]) {
                elements.passcodeInput.value = match[1];
                try {
                    history.replaceState(null, '', window.location.pathname + window.location.search);
                } catch (_) {}
                await handleLogin(match[1]);
            }
        }
    }

    function bindEvents() {
        // Login form
        elements.loginForm.addEventListener('submit', async (e) => {
            e.preventDefault();
            const passcode = elements.passcodeInput.value.trim();
            if (passcode.length < 6) return;
            await handleLogin(passcode);
        });

        // Lock Vault
        elements.lockVaultBtn.addEventListener('click', async () => {
            await apiRequest('/api/vault/lock', { method: 'POST' });
            state.hasVaultAccess = false;
            updateVaultBadge();
            switchMode('media');
            showToast('Vault locked successfully');
        });

        // Logout
        elements.logoutBtn.addEventListener('click', () => {
            handleLogout();
        });

        // Nav tabs
        elements.tabMedia.addEventListener('click', () => {
            switchMode('media');
        });

        elements.tabVault.addEventListener('click', () => {
            switchMode('vault');
        });

        // Vault Unlock modal
        elements.vaultUnlockForm.addEventListener('submit', async (e) => {
            e.preventDefault();
            const pin = elements.vaultPinInput.value.trim();
            if (!pin) return;
            await handleVaultUnlock(pin);
        });

        elements.vaultUnlockCancelBtn.addEventListener('click', () => {
            hideVaultUnlockModal();
            if (!state.hasVaultAccess) {
                switchMode('media');
            }
        });

        // Search
        elements.searchInput.addEventListener('input', (e) => {
            state.searchQuery = e.target.value;
            elements.clearSearchBtn.classList.toggle('hidden', !state.searchQuery);
            clearSelection();
            applyFilterAndRender();
        });

        elements.clearSearchBtn.addEventListener('click', () => {
            elements.searchInput.value = '';
            state.searchQuery = '';
            elements.clearSearchBtn.classList.add('hidden');
            clearSelection();
            applyFilterAndRender();
        });

        // Sort
        elements.sortSelect.addEventListener('change', (e) => {
            state.sortOption = e.target.value;
            applyFilterAndRender();
        });

        // View Mode Toggles
        elements.gridToggleBtn.addEventListener('click', () => {
            setViewMode('grid');
        });

        elements.listToggleBtn.addEventListener('click', () => {
            setViewMode('list');
        });

        // Refresh
        elements.refreshBtn.addEventListener('click', () => {
            loadContent();
        });

        // Category Filters
        elements.categoryFilters.querySelectorAll('.filter-pill').forEach((btn) => {
            btn.addEventListener('click', () => {
                elements.categoryFilters.querySelectorAll('.filter-pill').forEach((b) => b.classList.remove('active'));
                btn.classList.add('active');
                state.selectedCategory = btn.dataset.category;
                clearSelection();
                applyFilterAndRender();
            });
        });

        // Upload Button & File Input
        elements.uploadBtn.addEventListener('click', () => {
            elements.fileUploadInput.click();
        });

        elements.fileUploadInput.addEventListener('change', (e) => {
            if (e.target.files && e.target.files.length > 0) {
                handleUploadFiles(Array.from(e.target.files));
                elements.fileUploadInput.value = '';
            }
        });

        // Select All / Deselect All
        elements.selectAllBtn.addEventListener('click', () => {
            const allSelected = state.filteredItems.length > 0 &&
                state.filteredItems.every((item) => state.selectedIds.has(item.id));

            if (allSelected) {
                clearSelection();
            } else {
                state.filteredItems.forEach((item) => state.selectedIds.add(item.id));
                updateSelectionUI();
                renderItems(state.filteredItems);
            }
        });

        // Batch Download Selected ZIP
        elements.downloadZipBtn.addEventListener('click', () => {
            if (state.selectedIds.size === 0) return;
            const idsList = Array.from(state.selectedIds).join(',');
            const endpoint = state.currentMode === 'vault'
                ? `/api/vault/download/zip?ids=${encodeURIComponent(idsList)}&token=${state.token}`
                : `/api/media/download/zip?ids=${encodeURIComponent(idsList)}&token=${state.token}`;

            const link = document.createElement('a');
            link.href = endpoint;
            link.download = `VaultSync_${state.currentMode}_${Date.now()}.zip`;
            document.body.appendChild(link);
            link.click();
            document.body.removeChild(link);
            showToast(`Downloading ${state.selectedIds.size} files as ZIP...`);
        });

        // Batch Delete Selected
        elements.deleteSelectedBtn.addEventListener('click', () => {
            if (state.selectedIds.size === 0) return;
            const count = state.selectedIds.size;
            openConfirmDeleteModal(
                `Are you sure you want to permanently delete ${count} selected item(s)? This cannot be undone.`,
                async () => {
                    await executeBatchDelete();
                }
            );
        });

        // New Folder
        elements.newFolderBtn.addEventListener('click', () => {
            elements.newFolderNameInput.value = '';
            elements.newFolderModal.classList.remove('hidden');
            elements.newFolderNameInput.focus();
        });

        elements.newFolderCancelBtn.addEventListener('click', () => {
            elements.newFolderModal.classList.add('hidden');
        });

        elements.newFolderForm.addEventListener('submit', async (e) => {
            e.preventDefault();
            const name = elements.newFolderNameInput.value.trim();
            if (!name) return;

            elements.newFolderModal.classList.add('hidden');
            const res = await apiRequest('/api/vault/folders/create', {
                method: 'POST',
                body: { name, parentId: state.currentFolderId },
            });

            if (res?.ok) {
                showToast(`Folder "${name}" created.`);
                loadContent();
            } else {
                showToast('Failed to create folder: ' + (res?.data?.error?.message || 'Error'), true);
            }
        });

        // Confirm Delete Modal Actions
        elements.confirmDeleteCancelBtn.addEventListener('click', () => {
            elements.confirmDeleteModal.classList.add('hidden');
            state.pendingDeleteAction = null;
        });

        elements.confirmDeleteSubmitBtn.addEventListener('click', async () => {
            elements.confirmDeleteModal.classList.add('hidden');
            if (typeof state.pendingDeleteAction === 'function') {
                await state.pendingDeleteAction();
            }
            state.pendingDeleteAction = null;
        });

        // Preview Modal Close
        elements.previewCloseBtn.addEventListener('click', closePreviewModal);
        elements.previewModal.addEventListener('click', (e) => {
            if (e.target === elements.previewModal) closePreviewModal();
        });
    }

    // Drag and Drop File Upload
    function setupDragAndDrop() {
        let dragCounter = 0;

        window.addEventListener('dragenter', (e) => {
            e.preventDefault();
            dragCounter++;
            const dest = state.currentMode === 'vault'
                ? (state.currentFolder ? `Vault: ${state.currentFolder.name}` : 'Vault (Root)')
                : 'Normal Device Media';
            elements.dragDropDestination.textContent = `Uploading to ${dest}`;
            elements.dragDropOverlay.classList.remove('hidden');
        });

        window.addEventListener('dragleave', (e) => {
            e.preventDefault();
            dragCounter--;
            if (dragCounter <= 0) {
                dragCounter = 0;
                elements.dragDropOverlay.classList.add('hidden');
            }
        });

        window.addEventListener('dragover', (e) => {
            e.preventDefault();
        });

        window.addEventListener('drop', (e) => {
            e.preventDefault();
            dragCounter = 0;
            elements.dragDropOverlay.classList.add('hidden');
            if (e.dataTransfer && e.dataTransfer.files && e.dataTransfer.files.length > 0) {
                handleUploadFiles(Array.from(e.dataTransfer.files));
            }
        });
    }

    // Upload Processing
    async function handleUploadFiles(files) {
        if (!files || files.length === 0) return;
        if (!state.token) {
            showToast('Please connect to your phone first.', true);
            return;
        }

        elements.uploadProgressCard.classList.remove('hidden');
        const total = files.length;
        let successCount = 0;

        for (let i = 0; i < total; i++) {
            const file = files[i];
            elements.uploadStatusText.textContent = `Uploading ${i + 1} of ${total}...`;
            elements.uploadFileName.textContent = file.name;
            elements.uploadPercent.textContent = '0%';
            elements.uploadProgressBar.style.width = '0%';

            try {
                await uploadSingleFile(file, (percent) => {
                    elements.uploadPercent.textContent = `${percent}%`;
                    elements.uploadProgressBar.style.width = `${percent}%`;
                });
                successCount++;
            } catch (err) {
                console.error('File upload error:', err);
                showToast(`Failed to upload ${file.name}: ${err.message}`, true);
            }
        }

        setTimeout(() => {
            elements.uploadProgressCard.classList.add('hidden');
        }, 1500);

        if (successCount > 0) {
            showToast(`Uploaded ${successCount} file(s) successfully!`);
            loadContent();
        }
    }

    function uploadSingleFile(file, onProgress) {
        return new Promise((resolve, reject) => {
            const xhr = new XMLHttpRequest();
            const endpoint = state.currentMode === 'vault'
                ? `/api/upload/vault?filename=${encodeURIComponent(file.name)}${state.currentFolderId ? '&folderId=' + encodeURIComponent(state.currentFolderId) : ''}`
                : `/api/upload/media?filename=${encodeURIComponent(file.name)}`;

            xhr.open('POST', endpoint, true);
            xhr.setRequestHeader('X-VaultSync-Client', 'web');
            if (state.token) {
                xhr.setRequestHeader('Authorization', `Bearer ${state.token}`);
            }

            xhr.upload.onprogress = (e) => {
                if (e.lengthComputable && typeof onProgress === 'function') {
                    const percent = Math.round((e.loaded / e.total) * 100);
                    onProgress(percent);
                }
            };

            xhr.onload = () => {
                if (xhr.status >= 200 && xhr.status < 300) {
                    resolve(xhr.responseText);
                } else {
                    reject(new Error(`Server returned HTTP ${xhr.status}`));
                }
            };

            xhr.onerror = () => {
                reject(new Error('Network connection error'));
            };

            xhr.send(file);
        });
    }

    // View Switching
    function showLoginView(errorMessage = null) {
        elements.loginView.classList.remove('hidden');
        elements.explorerView.classList.add('hidden');
        elements.logoutBtn.classList.add('hidden');
        elements.lockVaultBtn.classList.add('hidden');
        elements.passcodeInput.value = '';
        if (errorMessage) {
            elements.loginError.textContent = errorMessage;
            elements.loginError.classList.remove('hidden');
        } else {
            elements.loginError.classList.add('hidden');
        }
    }

    function showExplorerView() {
        elements.loginView.classList.add('hidden');
        elements.explorerView.classList.remove('hidden');
        elements.logoutBtn.classList.remove('hidden');
    }

    function switchMode(mode) {
        state.currentMode = mode;
        elements.tabMedia.classList.toggle('active', mode === 'media');
        elements.tabVault.classList.toggle('active', mode === 'vault');

        if (mode === 'vault') {
            if (!state.hasVaultAccess) {
                showVaultUnlockModal();
                return;
            }
            elements.newFolderBtn.classList.remove('hidden');
        } else {
            elements.newFolderBtn.classList.add('hidden');
        }

        state.currentFolderId = null;
        clearSelection();
        loadContent();
    }

    function setViewMode(mode) {
        state.viewMode = mode;
        elements.gridToggleBtn.classList.toggle('active', mode === 'grid');
        elements.listToggleBtn.classList.toggle('active', mode === 'list');
        elements.itemsContainer.className = mode === 'grid' ? 'items-grid' : 'items-list';
    }

    // Authentication Handlers
    async function handleLogin(passcode) {
        elements.loginSubmitBtn.disabled = true;
        elements.loginSubmitBtn.textContent = 'Authenticating...';
        elements.loginError.classList.add('hidden');

        const res = await apiRequest('/api/auth/login', {
            method: 'POST',
            body: { passcode },
        });

        elements.loginSubmitBtn.disabled = false;
        elements.loginSubmitBtn.textContent = 'Connect to Phone';

        if (res?.ok && res.data?.data?.token) {
            state.token = res.data.data.token;
            state.hasVaultAccess = res.data.data.hasVaultAccess || false;
            sessionStorage.setItem('vaultsync_token', state.token);
            updateVaultBadge();
            showExplorerView();
            loadContent();
            showToast('Connected to VaultSync phone');
        } else {
            const err = res?.data?.error?.message || 'Invalid passcode or connection error.';
            elements.loginError.textContent = err;
            elements.loginError.classList.remove('hidden');
        }
    }

    async function handleLogout(message = null) {
        if (state.token) {
            await apiRequest('/api/auth/logout', { method: 'POST' }).catch(() => {});
        }
        state.token = null;
        state.hasVaultAccess = false;
        state.rawItems = [];
        state.allFolders = [];
        state.currentFolderId = null;
        clearSelection();
        sessionStorage.removeItem('vaultsync_token');
        updateVaultBadge();
        showLoginView(message);
    }

    function showVaultUnlockModal() {
        elements.vaultPinInput.value = '';
        elements.vaultUnlockError.classList.add('hidden');
        const descEl = document.getElementById('vaultUnlockDesc');
        if (descEl) {
            if (state.serverInfo?.isVaultUnlockedOnPhone) {
                descEl.textContent = 'Vault is already unlocked on your phone. You can enter your Vault Master PIN/Password or the 6-digit PC Sync passcode to access.';
            } else {
                descEl.textContent = 'Enter your Vault Master PIN or Password to access protected folders and stream private videos.';
            }
        }
        elements.vaultUnlockModal.classList.remove('hidden');
        elements.vaultPinInput.focus();
    }

    function hideVaultUnlockModal() {
        elements.vaultUnlockModal.classList.add('hidden');
    }

    async function handleVaultUnlock(pin) {
        elements.vaultUnlockError.classList.add('hidden');
        const submitBtn = document.getElementById('vaultUnlockSubmitBtn');
        if (submitBtn) {
            submitBtn.disabled = true;
            submitBtn.textContent = 'Verifying...';
        }

        const res = await apiRequest('/api/vault/unlock', {
            method: 'POST',
            body: { pin },
        });

        if (submitBtn) {
            submitBtn.disabled = false;
            submitBtn.textContent = 'Unlock Vault';
        }

        if (res?.ok) {
            state.hasVaultAccess = true;
            updateVaultBadge();
            hideVaultUnlockModal();
            elements.newFolderBtn.classList.remove('hidden');
            loadContent();
            showToast('Encrypted Vault Unlocked');
        } else {
            const err = res?.data?.error?.message || 'Incorrect Vault Master PIN or Password.';
            elements.vaultUnlockError.textContent = err;
            elements.vaultUnlockError.classList.remove('hidden');
        }
    }

    function updateVaultBadge() {
        if (state.hasVaultAccess) {
            elements.vaultStatusBadge.textContent = 'Unlocked';
            elements.vaultStatusBadge.className = 'vault-badge unlocked';
            elements.lockVaultBtn.classList.remove('hidden');
        } else {
            elements.vaultStatusBadge.textContent = 'Locked';
            elements.vaultStatusBadge.className = 'vault-badge locked';
            elements.lockVaultBtn.classList.add('hidden');
        }
    }

    // Content Loading
    async function loadContent() {
        showLoading(true);
        clearSelection();

        if (state.currentMode === 'media') {
            elements.foldersSection.classList.add('hidden');
            elements.newFolderBtn.classList.add('hidden');
            elements.filesSectionTitle.textContent = 'Device Media';
            renderBreadcrumbs([{ name: 'Normal Media', id: null }]);

            const res = await apiRequest('/api/media/list');
            showLoading(false);

            if (res?.ok && Array.isArray(res.data?.data)) {
                state.rawItems = res.data.data;
            } else {
                state.rawItems = [];
            }
            applyFilterAndRender();
        } else {
            // Vault Mode
            elements.foldersSection.classList.remove('hidden');
            elements.newFolderBtn.classList.remove('hidden');
            elements.filesSectionTitle.textContent = 'Vault Files';

            const [foldersRes, entriesRes] = await Promise.all([
                apiRequest('/api/vault/folders'),
                apiRequest('/api/vault/entries'),
            ]);

            showLoading(false);

            if (foldersRes?.ok && Array.isArray(foldersRes.data?.data)) {
                state.allFolders = foldersRes.data.data;
            } else {
                state.allFolders = [];
            }

            if (entriesRes?.ok && Array.isArray(entriesRes.data?.data)) {
                state.rawItems = entriesRes.data.data;
            } else {
                state.rawItems = [];
            }

            updateBreadcrumbsHierarchy();
            renderFolders();
            applyFilterAndRender();
        }
    }

    function showLoading(isLoading) {
        elements.loadingState.classList.toggle('hidden', !isLoading);
        if (isLoading) {
            elements.emptyState.classList.add('hidden');
            elements.itemsContainer.innerHTML = '';
            elements.foldersGrid.innerHTML = '';
        }
    }

    // Selection UI
    function clearSelection() {
        state.selectedIds.clear();
        updateSelectionUI();
    }

    function updateSelectionUI() {
        const count = state.selectedIds.size;
        elements.selectedCountBadge.textContent = count;
        elements.deleteCountBadge.textContent = count;

        elements.downloadZipBtn.classList.toggle('hidden', count === 0);
        // Delete button visible if items are selected and in vault mode
        elements.deleteSelectedBtn.classList.toggle('hidden', count === 0 || state.currentMode !== 'vault');

        const allSelected = state.filteredItems.length > 0 &&
            state.filteredItems.every((item) => state.selectedIds.has(item.id));
        elements.selectAllBtn.innerHTML = allSelected
            ? '<span>◻️</span> Clear Selection'
            : '<span>☑️</span> Select All';
    }

    // Filtering & Rendering
    function applyFilterAndRender() {
        let items = [...state.rawItems];

        // 1. Folder filter
        if (state.currentMode === 'vault' && state.selectedCategory === 'ALL') {
            items = items.filter((item) => item.folderId === state.currentFolderId);
        }

        // 2. Category filter
        if (state.selectedCategory !== 'ALL') {
            items = items.filter((item) => {
                const cat = (item.category || '').toLowerCase();
                return cat === state.selectedCategory.toLowerCase();
            });
        }

        // 3. Search query
        if (state.searchQuery.trim()) {
            const q = state.searchQuery.toLowerCase();
            items = items.filter((item) => {
                const name = (item.fileName || item.displayName || '').toLowerCase();
                return name.includes(q);
            });
        }

        // 4. Sorting
        items.sort((a, b) => {
            const nameA = (a.fileName || a.displayName || '').toLowerCase();
            const nameB = (b.fileName || b.displayName || '').toLowerCase();
            const dateA = a.dateModifiedMillis || 0;
            const dateB = b.dateModifiedMillis || 0;
            const sizeA = a.sizeBytes || 0;
            const sizeB = b.sizeBytes || 0;

            switch (state.sortOption) {
                case 'name_asc': return nameA.localeCompare(nameB);
                case 'name_desc': return nameB.localeCompare(nameA);
                case 'date_asc': return dateA - dateB;
                case 'date_desc': return dateB - dateA;
                case 'size_asc': return sizeA - sizeB;
                case 'size_desc': return sizeB - sizeA;
                default: return dateB - dateA;
            }
        });

        state.filteredItems = items;
        updateSelectionUI();
        renderItems(items);
    }

    function renderFolders() {
        if (state.currentMode !== 'vault' || state.selectedCategory !== 'ALL') {
            elements.foldersSection.classList.add('hidden');
            return;
        }

        const currentFolders = state.allFolders.filter((f) => f.parentId === state.currentFolderId);
        if (currentFolders.length === 0) {
            elements.foldersSection.classList.add('hidden');
            return;
        }

        elements.foldersSection.classList.remove('hidden');
        elements.foldersGrid.innerHTML = '';

        currentFolders.forEach((folder) => {
            const card = document.createElement('div');
            card.className = 'folder-card';
            card.innerHTML = `
                <span class="folder-icon">📁</span>
                <span class="folder-name">${escapeHtml(folder.name)}</span>
                <button class="folder-delete-btn" title="Delete folder">🗑️</button>
            `;

            // Open Folder
            card.addEventListener('click', (e) => {
                if (e.target.closest('.folder-delete-btn')) return;
                state.currentFolderId = folder.id;
                clearSelection();
                updateBreadcrumbsHierarchy();
                renderFolders();
                applyFilterAndRender();
            });

            // Delete Folder Button
            const deleteBtn = card.querySelector('.folder-delete-btn');
            deleteBtn.addEventListener('click', (e) => {
                e.stopPropagation();
                openConfirmDeleteModal(`Delete folder "${folder.name}" and remove all its contents?`, async () => {
                    const res = await apiRequest('/api/vault/folders/delete', {
                        method: 'POST',
                        body: { folderId: folder.id },
                    });
                    if (res?.ok) {
                        showToast(`Folder "${folder.name}" deleted.`);
                        loadContent();
                    } else {
                        showToast('Failed to delete folder', true);
                    }
                });
            });

            elements.foldersGrid.appendChild(card);
        });
    }

    function updateBreadcrumbsHierarchy() {
        if (state.currentMode === 'media') {
            renderBreadcrumbs([{ name: 'Normal Media', id: null }]);
            return;
        }

        const chain = [{ name: 'Vault Root', id: null }];
        if (state.currentFolderId) {
            const ancestry = [];
            let curr = state.allFolders.find((f) => f.id === state.currentFolderId);
            state.currentFolder = curr || null;
            while (curr) {
                ancestry.unshift({ name: curr.name, id: curr.id });
                curr = curr.parentId ? state.allFolders.find((f) => f.id === curr.parentId) : null;
            }
            chain.push(...ancestry);
        } else {
            state.currentFolder = null;
        }
        renderBreadcrumbs(chain);
    }

    function renderBreadcrumbs(chain) {
        elements.breadcrumbs.innerHTML = '';
        chain.forEach((crumb, index) => {
            if (index > 0) {
                const sep = document.createElement('span');
                sep.className = 'breadcrumb-sep';
                sep.textContent = '›';
                elements.breadcrumbs.appendChild(sep);
            }

            const item = document.createElement('span');
            item.className = 'breadcrumb-item' + (index === chain.length - 1 ? ' active' : '');
            item.textContent = crumb.name;

            if (index < chain.length - 1) {
                item.addEventListener('click', () => {
                    state.currentFolderId = crumb.id;
                    clearSelection();
                    updateBreadcrumbsHierarchy();
                    renderFolders();
                    applyFilterAndRender();
                });
            }
            elements.breadcrumbs.appendChild(item);
        });
    }

    function renderItems(items) {
        elements.itemsContainer.innerHTML = '';

        if (items.length === 0) {
            elements.emptyState.classList.remove('hidden');
            elements.emptyStateMsg.textContent = state.searchQuery
                ? `No files matching "${state.searchQuery}"`
                : 'No files in this folder or category.';
            return;
        }

        elements.emptyState.classList.add('hidden');

        items.forEach((item) => {
            const name = item.fileName || item.displayName || 'Untitled';
            const size = formatSize(item.sizeBytes || 0);
            const category = item.category || 'Other';
            const isVault = state.currentMode === 'vault';
            const isSelected = state.selectedIds.has(item.id);
            const mediaUrl = isVault
                ? `/api/vault/file/${item.id}?token=${state.token}`
                : `/api/media/file/${item.id}?token=${state.token}`;
            const thumbUrl = isVault
                ? `/api/vault/thumbnail/${item.id}?token=${state.token}`
                : `/api/media/thumbnail/${item.id}?token=${state.token}`;

            const card = document.createElement('div');
            card.className = `file-card ${isSelected ? 'selected' : ''}`;

            let thumbHtml = '';
            if (category === 'Images') {
                thumbHtml = `
                    <img src="${thumbUrl}" alt="${escapeHtml(name)}" loading="lazy"
                         onerror="if(this.dataset.triedMedia!=='1'){this.dataset.triedMedia='1';this.src='${mediaUrl}';}else{this.style.display='none';this.nextElementSibling.style.display='flex';}">
                    <span class="file-icon-large fallback-img-icon" style="display:none;">🖼️</span>`;
            } else if (category === 'Videos') {
                thumbHtml = `
                    <div class="video-thumb-wrapper">
                        <img src="${thumbUrl}" alt="${escapeHtml(name)}" loading="lazy"
                             onerror="window.handleVideoThumbFallback(this, '${mediaUrl}')">
                        <div class="video-play-badge" title="Play Video"><span class="play-icon">▶</span></div>
                        <span class="file-icon-large fallback-video-icon" style="display:none;">🎬</span>
                    </div>`;
            } else if (category === 'Audio') {
                thumbHtml = `<span class="file-icon-large">🎵</span>`;
            } else if (category === 'Documents') {
                thumbHtml = `<span class="file-icon-large">📄</span>`;
            } else {
                thumbHtml = `<span class="file-icon-large">📦</span>`;
            }

            card.innerHTML = `
                <div class="file-checkbox" title="Select file">${isSelected ? '✓' : ''}</div>
                <div class="card-actions-hover">
                    <a href="${mediaUrl}" download="${escapeHtml(name)}" class="action-pill-btn" title="Download">⬇️</a>
                    ${isVault ? `<button class="action-pill-btn btn-delete" title="Delete">🗑️</button>` : ''}
                </div>
                <div class="file-thumb">${thumbHtml}</div>
                <div class="file-card-body">
                    <span class="file-name" title="${escapeHtml(name)}">${escapeHtml(name)}</span>
                    <div class="file-meta">
                        <span>${category}</span>
                        <span>${size}</span>
                    </div>
                </div>
            `;

            // Checkbox selection
            const checkbox = card.querySelector('.file-checkbox');
            checkbox.addEventListener('click', (e) => {
                e.stopPropagation();
                toggleItemSelection(item.id, card);
            });

            // Delete pill
            if (isVault) {
                const deletePill = card.querySelector('.btn-delete');
                if (deletePill) {
                    deletePill.addEventListener('click', (e) => {
                        e.stopPropagation();
                        openConfirmDeleteModal(`Permanently delete "${name}" from encrypted vault?`, async () => {
                            const res = await apiRequest('/api/vault/entries/delete', {
                                method: 'POST',
                                body: { entryId: item.id },
                            });
                            if (res?.ok) {
                                showToast(`Deleted "${name}"`);
                                loadContent();
                            } else {
                                showToast('Delete failed: ' + (res?.data?.error?.message || 'Error'), true);
                            }
                        });
                    });
                }
            }

            // Preview modal open
            card.addEventListener('click', (e) => {
                if (e.target.closest('.card-actions-hover') || e.target.closest('.file-checkbox')) return;
                openPreviewModal(item);
            });

            elements.itemsContainer.appendChild(card);
        });
    }

    function toggleItemSelection(id, cardElement) {
        if (state.selectedIds.has(id)) {
            state.selectedIds.delete(id);
            cardElement.classList.remove('selected');
            const box = cardElement.querySelector('.file-checkbox');
            if (box) box.textContent = '';
        } else {
            state.selectedIds.add(id);
            cardElement.classList.add('selected');
            const box = cardElement.querySelector('.file-checkbox');
            if (box) box.textContent = '✓';
        }
        updateSelectionUI();
    }

    async function executeBatchDelete() {
        const ids = Array.from(state.selectedIds);
        let count = 0;
        for (const entryId of ids) {
            const res = await apiRequest('/api/vault/entries/delete', {
                method: 'POST',
                body: { entryId },
            });
            if (res?.ok) count++;
        }
        showToast(`Permanently deleted ${count} item(s).`);
        clearSelection();
        loadContent();
    }

    function openConfirmDeleteModal(message, onConfirm) {
        elements.confirmDeleteMsg.textContent = message;
        state.pendingDeleteAction = onConfirm;
        elements.confirmDeleteModal.classList.remove('hidden');
    }

    // Preview Modal & Streaming
    function openPreviewModal(item) {
        const name = item.fileName || item.displayName || 'Untitled';
        const size = formatSize(item.sizeBytes || 0);
        const category = item.category || 'Other';
        const isVault = state.currentMode === 'vault';
        const mediaUrl = isVault
            ? `/api/vault/file/${item.id}?token=${state.token}`
            : `/api/media/file/${item.id}?token=${state.token}`;
        const thumbUrl = isVault
            ? `/api/vault/thumbnail/${item.id}?token=${state.token}`
            : `/api/media/thumbnail/${item.id}?token=${state.token}`;

        elements.previewTitle.textContent = name;
        elements.previewMeta.textContent = `${category} • ${size} • ${item.mimeType || 'binary'}`;
        elements.previewDownloadBtn.href = mediaUrl;
        elements.previewDownloadBtn.setAttribute('download', name);
        elements.previewBody.innerHTML = '';

        if (category === 'Images') {
            const img = document.createElement('img');
            img.src = mediaUrl;
            img.alt = name;
            elements.previewBody.appendChild(img);
        } else if (category === 'Videos') {
            const video = document.createElement('video');
            video.controls = true;
            video.autoplay = true;
            video.preload = 'metadata';
            video.playsInline = true;
            video.poster = thumbUrl;
            video.src = mediaUrl;
            elements.previewBody.appendChild(video);
        } else if (category === 'Audio') {
            const card = document.createElement('div');
            card.className = 'audio-preview-card';
            card.innerHTML = `
                <div style="font-size: 48px; margin-bottom: 16px;">🎵</div>
                <h3 style="margin-bottom: 8px;">${escapeHtml(name)}</h3>
                <p style="color: var(--text-muted); font-size: 13px;">${size}</p>
                <audio controls autoplay src="${mediaUrl}"></audio>
            `;
            elements.previewBody.appendChild(card);
        } else {
            const isText = isTextMimeOrExtension(name, item.mimeType);
            if (isText) {
                const textContainer = document.createElement('pre');
                textContainer.className = 'document-preview-card';
                textContainer.textContent = 'Loading text preview...';
                elements.previewBody.appendChild(textContainer);

                fetch(mediaUrl, { headers: { 'X-VaultSync-Client': 'web' } })
                    .then((r) => r.text())
                    .then((txt) => {
                        textContainer.textContent = txt;
                    })
                    .catch((err) => {
                        textContainer.textContent = 'Error loading text: ' + err.message;
                    });
            } else {
                const card = document.createElement('div');
                card.className = 'audio-preview-card';
                card.innerHTML = `
                    <div style="font-size: 48px; margin-bottom: 16px;">📄</div>
                    <h3 style="margin-bottom: 8px;">${escapeHtml(name)}</h3>
                    <p style="color: var(--text-muted); font-size: 13px; margin-bottom: 24px;">Direct preview is not supported for this file type. Click download to open with your local PC applications.</p>
                    <a href="${mediaUrl}" download="${escapeHtml(name)}" class="btn btn-primary">⬇️ Download File</a>
                `;
                elements.previewBody.appendChild(card);
            }
        }

        elements.previewModal.classList.remove('hidden');
    }

    function closePreviewModal() {
        const video = elements.previewBody.querySelector('video');
        if (video) video.pause();
        const audio = elements.previewBody.querySelector('audio');
        if (audio) audio.pause();

        elements.previewBody.innerHTML = '';
        elements.previewModal.classList.add('hidden');
    }

    // Connection Watchdog
    async function checkServerStatus() {
        const res = await apiRequest('/api/status');
        if (res?.ok && res.data?.data && res.data.data.isRunning) {
            state.serverInfo = res.data.data;
            elements.serverUrlText.textContent = res.data.data.url
                ? res.data.data.url.replace(/^http:\/\//, '')
                : 'Connected';
            elements.connectionBanner.classList.add('hidden');
            state.isReconnecting = false;
        } else {
            elements.serverUrlText.textContent = 'Disconnected';
            elements.connectionBanner.classList.remove('hidden');
            const span = elements.connectionBanner.querySelector('span');
            if (span) {
                span.textContent = '⚠️ Server has stopped or connection to phone was lost. Please check VaultSync app.';
            }
            state.isReconnecting = true;
        }
    }

    function startWatchdog() {
        setInterval(checkServerStatus, 5000);
    }

    // Toast Notification Helper
    function showToast(message, isError = false) {
        if (!elements.toastContainer) return;
        const toast = document.createElement('div');
        toast.className = 'toast' + (isError ? ' toast-error' : '');
        toast.textContent = message;
        elements.toastContainer.appendChild(toast);
        setTimeout(() => {
            toast.remove();
        }, 4000);
    }

    // Utilities
    function formatSize(bytes) {
        if (!bytes || bytes <= 0) return '0 B';
        const units = ['B', 'KB', 'MB', 'GB', 'TB'];
        const i = Math.floor(Math.log(bytes) / Math.log(1024));
        return (bytes / Math.pow(1024, i)).toFixed(1) + ' ' + units[i];
    }

    function formatDate(millis) {
        if (!millis) return '';
        const d = new Date(millis);
        return d.toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' });
    }

    function escapeHtml(str) {
        const div = document.createElement('div');
        div.textContent = str;
        return div.innerHTML;
    }

    function isTextMimeOrExtension(filename, mime) {
        const m = (mime || '').toLowerCase();
        if (m.startsWith('text/') || m === 'application/json' || m === 'application/xml') return true;
        const ext = filename.split('.').pop().toLowerCase();
        return ['txt', 'log', 'json', 'xml', 'md', 'csv', 'kt', 'java', 'py', 'js', 'html', 'css', 'yml', 'yaml', 'sh'].includes(ext);
    }

    // Video Thumbnail Fallback via client-side Canvas frame capture
    function handleVideoThumbFallback(imgElement, mediaUrl) {
        if (!imgElement || imgElement.dataset.fallbackTriggered) return;
        imgElement.dataset.fallbackTriggered = 'true';

        const wrapper = imgElement.closest('.video-thumb-wrapper');
        const badge = wrapper?.querySelector('.video-play-badge');
        const fallbackIcon = wrapper?.querySelector('.fallback-video-icon');

        const fail = () => {
            imgElement.style.display = 'none';
            if (badge) badge.style.display = 'none';
            if (fallbackIcon) fallbackIcon.style.display = 'flex';
        };

        try {
            const v = document.createElement('video');
            v.crossOrigin = 'anonymous';
            v.preload = 'metadata';
            v.muted = true;
            v.playsInline = true;
            let finished = false;

            const cleanup = () => {
                v.removeAttribute('src');
                v.load();
            };

            const timer = setTimeout(() => {
                if (!finished) {
                    finished = true;
                    cleanup();
                    fail();
                }
            }, 3500);

            const capture = () => {
                if (finished) return;
                finished = true;
                clearTimeout(timer);
                try {
                    const canvas = document.createElement('canvas');
                    canvas.width = v.videoWidth || 320;
                    canvas.height = v.videoHeight || 240;
                    const ctx = canvas.getContext('2d');
                    ctx.drawImage(v, 0, 0, canvas.width, canvas.height);
                    const dataUrl = canvas.toDataURL('image/jpeg', 0.8);
                    cleanup();
                    imgElement.src = dataUrl;
                    imgElement.style.display = '';
                    if (badge) badge.style.display = 'flex';
                    if (fallbackIcon) fallbackIcon.style.display = 'none';
                } catch (e) {
                    cleanup();
                    fail();
                }
            };

            v.onseeked = capture;
            v.onloadeddata = () => {
                try {
                    v.currentTime = Math.min(1.0, (v.duration || 1) / 2);
                } catch (e) {
                    capture();
                }
            };
            v.onerror = () => {
                if (!finished) {
                    finished = true;
                    clearTimeout(timer);
                    cleanup();
                    fail();
                }
            };

            v.src = mediaUrl;
        } catch (e) {
            fail();
        }
    }
    window.handleVideoThumbFallback = handleVideoThumbFallback;

    // Run on page load
    window.addEventListener('DOMContentLoaded', init);
})();
