const CONFIG_PATH = "/storage/emulated/0/kikiros";

// Fallback KSU
window.ksu = window.ksu || { exec: async () => ({ stdout: "" }) };

let allPackages = [];
let infoCache = {};
let currentDetailPkg = "";
let currentAngleList = [];
let currentGameList = [];
let currentDevList = [];
let isScanning = false;
let isAdbReady = false;

function safeJSONParse(str) { 
    try { return JSON.parse(str); } catch { return []; } 
}

function debounce(func, delay) {
    let timeout;
    return (...args) => { 
        clearTimeout(timeout); 
        timeout = setTimeout(() => func.apply(this, args), delay); 
    };
}

function cleanList(arr) {
    return [...new Set(arr)].filter(p => p && p.trim().length > 0);
}

// ================== ADB QUEUE SYSTEM (ROBUST) ==================

window.adbCallbacks = {};
const adbQueue = [];           
let isAdbExecuting = false;    
let queueLock = false;         
let watchdogTimer = null;

function startWatchdog() {
    if (watchdogTimer) clearTimeout(watchdogTimer);
    watchdogTimer = setTimeout(() => {
        console.error("WATCHDOG: Force reset queue");
        isAdbExecuting = false;
        queueLock = false;
        processQueue();
    }, 15000);
}

function stopWatchdog() {
    if (watchdogTimer) {
        clearTimeout(watchdogTimer);
        watchdogTimer = null;
    }
}

window.adbCallback = function(callbackId, base64Result, isError) {
    console.log("ADB Callback:", callbackId.substring(0, 20), "error:", isError);
    stopWatchdog();
    
    const callback = window.adbCallbacks[callbackId];
    
    if (callback) {
        try {
            let result = "";
            if (base64Result) {
                const binaryString = atob(base64Result);
                const bytes = new Uint8Array(binaryString.length);
                for (let i = 0; i < binaryString.length; i++) {
                    bytes[i] = binaryString.charCodeAt(i);
                }
                result = new TextDecoder('utf-8').decode(bytes);
            }
            
            if (isError) callback.reject(result);
            else callback.resolve(result);
        } catch (e) {
            console.warn("Decode fallback:", e);
            callback.resolve(base64Result || "");
        }
        delete window.adbCallbacks[callbackId];
    } else {
        console.warn("Callback not found:", callbackId);
    }

    // Release locks
    isAdbExecuting = false;
    queueLock = false;
    
    // Process next
    setTimeout(processQueue, 50);
};

function processQueue() {
    // Double-lock prevention
    if (queueLock || isAdbExecuting) {
        return;
    }
    
    if (adbQueue.length === 0) {
        return;
    }
    
    // Acquire locks
    queueLock = true;
    isAdbExecuting = true;
    
    const task = adbQueue.shift();
    const callbackId = 'cb_' + Date.now() + '_' + Math.random().toString(36).substr(2, 9);
    
    console.log("Queue exec [" + adbQueue.length + " left]:", task.cmd.substring(0, 40));
    
    window.adbCallbacks[callbackId] = { 
        resolve: (r) => { task.resolve(r); }, 
        reject: (e) => { task.reject(e); } 
    };
    
    // Check KyroosApp
    if (typeof KyroosApp === 'undefined' || !KyroosApp.executeShell) {
        console.error("KyroosApp missing!");
        isAdbExecuting = false;
        queueLock = false;
        task.reject("Error: KyroosApp not available");
        delete window.adbCallbacks[callbackId];
        setTimeout(processQueue, 100);
        return;
    }
    
    startWatchdog();
    
    try {
        KyroosApp.executeShell(task.cmd, callbackId);
    } catch (e) {
        console.error("ExecuteShell exception:", e);
        stopWatchdog();
        isAdbExecuting = false;
        queueLock = false;
        task.reject("Error: " + e.message);
        delete window.adbCallbacks[callbackId];
        setTimeout(processQueue, 100);
    }
}

async function execShell(cmd) {
    if (!cmd || typeof cmd !== 'string') {
        return "Error: Invalid command";
    }

    // Tunggu ADB ready
    if (!isAdbReady && typeof KyroosApp !== 'undefined') {
        // Cek manual
        if (KyroosApp.isPaired && KyroosApp.isPaired()) {
            isAdbReady = true;
        }
    }

    if (typeof KyroosApp !== 'undefined' && KyroosApp.executeShell) {
        return new Promise((resolve, reject) => {
            adbQueue.push({ cmd, resolve, reject });
            setTimeout(processQueue, 10);
        });
    }

    // Fallback KSU
    if (window.ksu && window.ksu.exec) {
        try {
            const res = await window.ksu.exec(cmd);
            return res.stdout || res || "";
        } catch (e) {
            return "Error: " + e.message;
        }
    }

    return "Error: No ADB/KSU available";
}

// Callback dari Kotlin saat ADB ready
window.onAdbReady = function(port) {
    console.log("ADB Ready on port:", port);
    isAdbReady = true;
};

// ================== NAVIGASI ==================
function switchTab(tabId) {
    document.querySelectorAll('.tab-section').forEach(el => el.classList.remove('active'));
    document.querySelectorAll('.nav-item').forEach(el => el.classList.remove('active'));
    document.getElementById(tabId)?.classList.add('active');
    document.getElementById('nav-' + tabId)?.classList.add('active');
    if (tabId === 'home') updateHomeData();
}

function toggleAppConfig(el) {
    const on = el.checked;
    localStorage.setItem('advAppConfig', on);
    const nav = document.getElementById('mainNav');
    const btn = document.getElementById('nav-apps');
    if (on) { 
        btn?.classList.remove('hidden-tab'); 
        nav?.classList.add('wide'); 
    } else { 
        btn?.classList.add('hidden-tab'); 
        nav?.classList.remove('wide'); 
    }
    if (!on && document.getElementById('apps')?.classList.contains('active')) {
        switchTab('home');
    }
}

function openConfigPage() { 
    document.getElementById('configPage')?.classList.add('open'); 
    document.getElementById('mainNav')?.classList.add('hidden'); 
}

function closeConfigPage() { 
    document.getElementById('configPage')?.classList.remove('open'); 
    document.getElementById('mainNav')?.classList.remove('hidden'); 
}

function openTweakPage() { 
    document.getElementById('tweakPage')?.classList.add('open'); 
    document.getElementById('mainNav')?.classList.add('hidden'); 
}

function closeTweakPage() { 
    document.getElementById('tweakPage')?.classList.remove('open'); 
    document.getElementById('mainNav')?.classList.remove('hidden'); 
}

function openDevModal() { 
    document.getElementById('devModal')?.classList.add('show'); 
}

function closeDevModal() { 
    document.getElementById('devModal')?.classList.remove('show'); 
}

async function openTgLink(url) {
    await execShell(`am start -a android.intent.action.VIEW -d "${url}"`);
}

// ================== DETAIL APLIKASI ==================
function openAppDetail(pkg) {
    currentDetailPkg = pkg;
    const cache = infoCache[pkg] || {};
    
    const labelEl = document.getElementById('detailAppLabel');
    const pkgEl = document.getElementById('detailAppPkg');
    
    if (labelEl) labelEl.innerText = cache.label || pkg;
    if (pkgEl) pkgEl.innerText = pkg;

    const angleSw = document.getElementById('angleSwitch');
    const gameSw = document.getElementById('gameSwitch');
    const devSw = document.getElementById('devSwitch');
    
    if (angleSw) angleSw.checked = currentAngleList.includes(pkg);
    if (gameSw) gameSw.checked = currentGameList.includes(pkg);
    if (devSw) devSw.checked = currentDevList.includes(pkg);

    execShell(`cmd deviceidle whitelist | grep ${pkg} || echo "NOT_FOUND"`)
        .then(res => {
            const whitelistSw = document.getElementById('whitelistSwitch');
            if (whitelistSw) whitelistSw.checked = res.includes(pkg) && !res.includes("NOT_FOUND");
        })
        .catch(err => {
            console.error("Whitelist check failed:", err);
        });

    document.getElementById('appDetailPage')?.classList.add('open');
    document.getElementById('mainNav')?.classList.add('hidden');
}

function closeAppDetail() {
    document.getElementById('appDetailPage')?.classList.remove('open');
    document.getElementById('mainNav')?.classList.remove('hidden');
}

// ================== HOME ==================
async function updateHomeData() {
    console.log("Updating home...");
    
    try {
        // Chipset
        let chip = "";
        const props = ["ro.board.platform", "ro.product.board", "ro.chipname", "ro.hardware"];
        
        for (const prop of props) {
            if (chip) break;
            try {
                const result = await execShell(`getprop ${prop}`);
                const trimmed = result.trim();
                if (trimmed && !trimmed.startsWith("Error") && trimmed !== "") {
                    chip = trimmed;
                }
            } catch (e) {}
        }
        
        const chipsetEl = document.getElementById('chipsetInfo');
        if (chipsetEl) chipsetEl.innerText = chip || "Unknown";

        // RAM
        try {
            const memRaw = await execShell("cat /proc/meminfo | grep MemTotal");
            const memMatch = memRaw.match(/MemTotal:\s+(\d+)/);
            const ramEl = document.getElementById('ramInfo');
            if (memMatch && ramEl) {
                ramEl.innerText = (parseInt(memMatch[1]) / 1024 / 1024).toFixed(1) + " GB";
            } else if (ramEl) {
                ramEl.innerText = "Unknown";
            }
        } catch (e) {
            const ramEl = document.getElementById('ramInfo');
            if (ramEl) ramEl.innerText = "Error";
        }

        // Kernel
        let kernel = "";
        try {
            kernel = (await execShell("uname -r")).trim();
        } catch (e) {}
        
        if (!kernel) {
            try {
                const ver = await execShell("cat /proc/version");
                const match = ver.match(/version\s+([^\s]+)/);
                if (match) kernel = match[1];
            } catch (e) {}
        }
        
        const kernelEl = document.getElementById('kernelInfo');
        if (kernelEl) kernelEl.innerText = kernel || "Unknown";

        await checkStatus();
    } catch (e) {
        console.error("updateHomeData error:", e);
    }
}

async function checkStatus() {
    try {
        const pid = await execShell("pgrep -f sigma 2>/dev/null || echo ''");
        const isRun = pid && pid.trim().length > 0 && !pid.includes("Error");
        
        const sigmaSw = document.getElementById('sigmaSwitch');
        if (sigmaSw) sigmaSw.checked = isRun;

        const card = document.getElementById('statusCard');
        const icon = document.getElementById('statusIcon');
        const title = document.getElementById('statusTitle');
        const desc = document.getElementById('statusDesc');

        if (isRun) {
            card?.classList.add('active-mode');
            if (icon) icon.innerText = 'verified';
            if (title) title.innerText = 'Kyroos Active';
            if (desc) desc.innerText = 'Sigma binary running';
        } else {
            card?.classList.remove('active-mode');
            if (icon) icon.innerText = 'hourglass_empty';
            if (title) title.innerText = 'Service Idle';
            if (desc) desc.innerText = 'Enable in settings';
        }
    } catch (e) {
        console.error("checkStatus error:", e);
    }
}

async function toggleSigma(el) {
    try {
        if (el.checked) {
            await execShell("nohup sh /storage/emulated/0/sigma.sh > /dev/null 2>&1 &");
        } else {
            await execShell("pkill -f sigma 2>/dev/null || true");
        }
        setTimeout(checkStatus, 800);
    } catch (e) {
        console.error("toggleSigma error:", e);
        setTimeout(() => { el.checked = !el.checked; }, 100);
    }
}

// ================== APPS LIST ==================
async function startAppScan() {
    if (isScanning) return;
    isScanning = true;

    document.getElementById('appInitState')?.style.setProperty('display', 'none');
    document.getElementById('appSearch')?.classList.add('show');
    
    const container = document.getElementById('app-list-target');
    if (container) container.innerHTML = '<div style="text-align:center; padding:20px;">Loading...</div>';

    try {
        let raw = "";
        
        try {
            raw = await execShell("cmd package list packages -u 2>/dev/null");
        } catch (e) {}
        
        if (!raw || raw.trim() === "") {
            raw = await execShell("pm list packages -u");
        }
        
        const regex = /package:([^\s]+)/g;
        let match;
        allPackages = [];
        
        while ((match = regex.exec(raw)) !== null) {
            const pkg = match[1];
            if (!pkg.includes("overlay")) {
                allPackages.push(pkg);
            }
        }

        allPackages.sort();
        await loadLabels(allPackages);
        renderAppList();
    } catch (e) {
        console.error("startAppScan error:", e);
        if (container) {
            container.innerHTML = '<div style="text-align:center; padding:20px; color: red;">Failed</div>';
        }
    } finally {
        isScanning = false;
    }
}

async function loadLabels(pkgs) {
    if (window.ksu && window.ksu.getPackagesInfo) {
        try {
            const chunk = pkgs.slice(0, 500);
            const result = await window.ksu.getPackagesInfo(JSON.stringify(chunk));
            const infos = safeJSONParse(result);
            infos.forEach(item => {
                if (!infoCache[item.packageName]) infoCache[item.packageName] = {};
                infoCache[item.packageName].label = item.appLabel;
            });
        } catch (e) {
            console.error("loadLabels error:", e);
        }
    } else {
        pkgs.forEach(pkg => {
            if (!infoCache[pkg]) infoCache[pkg] = {};
            const parts = pkg.split('.');
            infoCache[pkg].label = parts[parts.length - 1] || pkg;
        });
    }
}

function renderAppList(filter = '') {
    const container = document.getElementById('app-list-target');
    const countInfo = document.getElementById('app-count-info');
    
    if (!container) return;
    
    const filterLower = filter.toLowerCase();
    const filtered = allPackages.filter(pkg => {
        const label = (infoCache[pkg]?.label || "").toLowerCase();
        return pkg.toLowerCase().includes(filterLower) || label.includes(filterLower);
    });

    if (countInfo) {
        countInfo.textContent = `${filtered.length} apps found`;
        countInfo.style.display = 'block';
    }

    if (filtered.length === 0) {
        container.innerHTML = '<div style="text-align:center; opacity:0.5; padding:20px;">No apps</div>';
        return;
    }

    const limit = filter === '' ? 100 : filtered.length;
    const fragment = document.createDocumentFragment();

    filtered.slice(0, limit).forEach(pkg => {
        const label = infoCache[pkg]?.label || pkg;
        const div = document.createElement('div');
        div.className = 'app-card-item';
        div.onclick = () => openAppDetail(pkg);

        div.innerHTML = `
            <div class="app-icon-placeholder">
                <span class="material-symbols-rounded">android</span>
            </div>
            <div class="app-card-info">
                <div class="app-card-title">${escapeHtml(label)}</div>
                <div class="app-card-pkg">${escapeHtml(pkg)}</div>
            </div>`;
        fragment.appendChild(div);
    });
    
    container.innerHTML = '';
    container.appendChild(fragment);
}

function escapeHtml(text) {
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}

const debouncedFilter = debounce((val) => { 
    if (allPackages.length > 0) renderAppList(val); 
}, 300);

// ================== KONFIGURASI ==================
function checkInterlock() {
    const auto = document.getElementById('powerSwitch')?.checked || false;
    const custom = document.getElementById('customSaveSwitch')?.checked || false;

    const autoCard = document.getElementById('autoPowerCard');
    const customCard = document.getElementById('customPowerCard');
    const autoSw = document.getElementById('powerSwitch');
    const customSw = document.getElementById('customSaveSwitch');

    if (autoCard && customCard && autoSw && customSw) {
        if (auto) {
            customCard.classList.add('disabled-state');
            customSw.disabled = true;
        } else {
            customCard.classList.remove('disabled-state');
            customSw.disabled = false;
        }

        if (custom) {
            autoCard.classList.add('disabled-state');
            autoSw.disabled = true;
        } else {
            autoCard.classList.remove('disabled-state');
            autoSw.disabled = false;
        }
    }
}

function handleAutoToggle(el) {
    if (el.checked) {
        const customSw = document.getElementById('customSaveSwitch');
        if (customSw) customSw.checked = false;
        toggleCustomSaveUI({ checked: false });
    }
    checkInterlock();
    saveKyroosConfig();
}

function handleCustomToggle(el) {
    if (el.checked) {
        const powerSw = document.getElementById('powerSwitch');
        if (powerSw) powerSw.checked = false;
    }
    toggleCustomSaveUI(el);
    checkInterlock();
    saveKyroosConfig();
}

function toggleCustomSaveUI(el) {
    const container = document.getElementById('customSaveContainer');
    if (container) {
        if (el.checked) container.classList.add('show'); 
        else container.classList.remove('show');
    }
}

function handleBrutalToggle(el) {
    if (el.checked) {
        document.getElementById('confirmModal')?.classList.add('show');
    }
}

async function confirmBrutalAction() {
    document.getElementById('confirmModal')?.classList.remove('show');
    openOpapsPage();
}

function cancelBrutalAction() {
    document.getElementById('confirmModal')?.classList.remove('show');
    const brutalSw = document.getElementById('brutalSwitch');
    if (brutalSw) brutalSw.checked = false;
}

async function handleDriverToggle(type) {
    const pkg = currentDetailPkg;
    const angleEl = document.getElementById('angleSwitch');
    const gameEl = document.getElementById('gameSwitch');
    const devEl = document.getElementById('devSwitch');
    
    if (!angleEl || !gameEl || !devEl) return;

    if (type === 'game' && gameEl.checked) devEl.checked = false;
    if (type === 'dev' && devEl.checked) gameEl.checked = false;

    if (angleEl.checked) { 
        if (!currentAngleList.includes(pkg)) currentAngleList.push(pkg); 
    } else { 
        currentAngleList = currentAngleList.filter(p => p !== pkg); 
    }

    if (gameEl.checked) { 
        if (!currentGameList.includes(pkg)) currentGameList.push(pkg); 
    } else { 
        currentGameList = currentGameList.filter(p => p !== pkg); 
    }

    if (devEl.checked) { 
        if (!currentDevList.includes(pkg)) currentDevList.push(pkg); 
    } else { 
        currentDevList = currentDevList.filter(p => p !== pkg); 
    }

    await saveKyroosConfig();
}

async function handleWhitelistToggle(el) {
    const pkg = currentDetailPkg;
    const cmd = el.checked ? 
        `cmd deviceidle whitelist +${pkg}` : 
        `cmd deviceidle whitelist -${pkg}`;
    try {
        await execShell(cmd);
    } catch (e) {
        console.error("Whitelist toggle error:", e);
        el.checked = !el.checked;
    }
}

async function fetchCurrentRes() {
    try {
        const sizeRaw = await execShell("wm size");
        const sizeMatch = sizeRaw.match(/(\d+)x(\d+)/);
        const resW = document.getElementById('resW');
        const resH = document.getElementById('resH');
        
        if (sizeMatch && resW && resH) {
            resW.value = sizeMatch[1];
            resH.value = sizeMatch[2];
        }
    } catch (e) { 
        console.error("fetchCurrentRes error:", e);
    }
}

function toggleResUI(el) {
    const container = document.getElementById('resContainer');
    if (!container) return;
    
    if (el.checked) { 
        container.classList.add('show'); 
        fetchCurrentRes(); 
    } else { 
        container.classList.remove('show'); 
    }
}

async function applyResolution() {
    const resW = document.getElementById('resW');
    const resH = document.getElementById('resH');
    
    if (!resW || !resH) return;
    
    const w = resW.value;
    const h = resH.value;
    
    if (w && h) {
        try {
            await execShell(`wm size ${w}x${h}`);
            const btn = document.querySelector('.btn-apply');
            if (btn) {
                const original = btn.innerHTML;
                btn.innerHTML = `<span class="material-symbols-rounded">check</span> Applied!`;
                setTimeout(() => btn.innerHTML = original, 1500);
            }
        } catch (e) {
            console.error("applyResolution error:", e);
            alert("Failed to apply resolution");
        }
    }
}

async function resetResolution() {
    try {
        await execShell("wm size reset");
        fetchCurrentRes();
    } catch (e) {
        console.error("resetResolution error:", e);
    }
}

async function applyCustomSave() {
    const btn = document.querySelector('.btn-icon-apply');
    if (!btn) return;
    
    const oldColor = btn.style.backgroundColor;
    btn.style.backgroundColor = "var(--md-secondary-container)";
    btn.style.color = "var(--md-on-secondary-container)";
    
    try {
        await saveKyroosConfig();
    } catch (e) {
        console.error("applyCustomSave error:", e);
    }
    
    setTimeout(() => { 
        btn.style.backgroundColor = oldColor; 
        btn.style.color = ""; 
    }, 500);
}

async function saveKyroosConfig() {
    console.log("Saving config...");
    
    const deepSw = document.getElementById('deepSwitch');
    const powerSw = document.getElementById('powerSwitch');
    const cacheSw = document.getElementById('cacheSwitch');
    const brutalSw = document.getElementById('brutalSwitch');
    const customSaveSw = document.getElementById('customSaveSwitch');
    const customSaveVal = document.getElementById('customSaveValue');
    
    const d = deepSw?.checked ? "on" : "off";
    const p = powerSw?.checked ? "on" : "off";
    const c = cacheSw?.checked ? "on" : "off";
    const b = brutalSw?.checked ? "on" : "off";
    
    const isCustomOn = customSaveSw?.checked || false;
    const customVal = customSaveVal?.value || "";
    const finalCustom = (isCustomOn && customVal) ? customVal : "off";

    const angleVal = cleanList(currentAngleList).join(',') || "none";
    const gameVal = cleanList(currentGameList).join(',') || "none";
    const devVal = cleanList(currentDevList).join(',') || "none";

    try {
        // Buat direktori
        await execShell(`mkdir -p "$(dirname '${CONFIG_PATH}')" 2>/dev/null || true`);
        
        // Simpan config
        const configContent = [
            `echo "deep=${d}" > "${CONFIG_PATH}"`,
            `echo "power=${p}" >> "${CONFIG_PATH}"`,
            `echo "chace=${c}" >> "${CONFIG_PATH}"`,
            `echo "brutal=${b}" >> "${CONFIG_PATH}"`,
            `echo "cossave=${finalCustom}" >> "${CONFIG_PATH}"`,
            `echo "angle=${angleVal}" >> "${CONFIG_PATH}"`,
            `echo "game=${gameVal}" >> "${CONFIG_PATH}"`,
            `echo "dev=${devVal}" >> "${CONFIG_PATH}"`
        ].join(' && ');

        await execShell(configContent);

        // Apply settings
        if (angleVal !== "none") {
            await execShell(`settings put global angle_gl_driver_selection_pkgs "${angleVal}"`);
            await execShell(`settings put global angle_gl_driver_selection_values "${angleVal.split(',').map(() => 'angle').join(',')}"`);
        } else {
            await execShell(`settings delete global angle_gl_driver_selection_pkgs 2>/dev/null || true`);
            await execShell(`settings delete global angle_gl_driver_selection_values 2>/dev/null || true`);
        }

        if (gameVal !== "none") {
            await execShell(`settings put global game_driver_opt_in_apps "${gameVal}"`);
        } else {
            await execShell(`settings delete global game_driver_opt_in_apps 2>/dev/null || true`);
        }

        if (devVal !== "none") {
            await execShell(`settings put global game_driver_prerelease_opt_in_apps "${devVal}"`);
        } else {
            await execShell(`settings delete global game_driver_prerelease_opt_in_apps 2>/dev/null || true`);
        }
        
        console.log("Config saved");
    } catch (e) {
        console.error("saveKyroosConfig error:", e);
        throw e;
    }
}

async function loadConfig() {
    console.log("Loading config...");
    
    try {
        const result = await execShell(`cat "${CONFIG_PATH}" 2>/dev/null || echo "FILE_NOT_FOUND"`);
        const c = String(result || "");
        
        if (c === "FILE_NOT_FOUND" || c.includes("No such file") || c.trim() === "") {
            console.log("No config file, using defaults");
            return;
        }
        
        if (c.startsWith("Error")) {
            console.error("Error reading config:", c);
            return;
        }
        
        const deepSw = document.getElementById('deepSwitch');
        const powerSw = document.getElementById('powerSwitch');
        const cacheSw = document.getElementById('cacheSwitch');
        const brutalSw = document.getElementById('brutalSwitch');
        const customSaveSw = document.getElementById('customSaveSwitch');
        const customSaveVal = document.getElementById('customSaveValue');
        const customContainer = document.getElementById('customSaveContainer');
        
        if (deepSw) deepSw.checked = c.includes('deep=on');
        if (powerSw) powerSw.checked = c.includes('power=on');
        if (cacheSw) cacheSw.checked = c.includes('chace=on');
        if (brutalSw) brutalSw.checked = c.includes('brutal=on');

        const matchS = c.match(/cossave=(\S+)/);
        if (matchS && matchS[1] !== 'off' && customSaveSw && customSaveVal && customContainer) {
            customSaveSw.checked = true;
            customContainer.classList.add('show');
            customSaveVal.value = matchS[1];
        } else if (customSaveSw) {
            customSaveSw.checked = false;
        }

        const matchA = c.match(/angle=([^\n]+)/);
        currentAngleList = (matchA && matchA[1] !== 'none') ? matchA[1].split(',') : [];

        const matchG = c.match(/game=([^\n]+)/);
        currentGameList = (matchG && matchG[1] !== 'none') ? matchG[1].split(',') : [];

        const matchD = c.match(/dev=([^\n]+)/);
        currentDevList = (matchD && matchD[1] !== 'none') ? matchD[1].split(',') : [];

        checkInterlock();
        console.log("Config loaded");
    } catch (e) {
        console.error("loadConfig error:", e);
    }
}

// ================== OPAPS ==================
let opapsPackages = [];
let selectedOpapsPkg = "";

function openOpapsPage() {
    document.getElementById('opapsPage')?.classList.add('open');
    document.getElementById('mainNav')?.classList.add('hidden');
}

function closeOpapsPage() {
    document.getElementById('opapsPage')?.classList.remove('open');
    document.getElementById('mainNav')?.classList.remove('hidden');
    const brutalSw = document.getElementById('brutalSwitch');
    if (brutalSw) brutalSw.checked = false;
}

async function startOpapsScan() {
    if (isScanning) return;
    isScanning = true;

    const btn = document.querySelector('#opapsInitState .btn-scan');
    const originalText = btn?.innerHTML;
    
    if (btn) {
        btn.innerHTML = '<span class="material-symbols-rounded" style="animation: spin 1s linear infinite;">refresh</span> Scanning...';
        btn.disabled = true;
    }

    try {
        let raw = "";
        
        try {
            raw = await execShell("cmd package list packages -3 -u 2>/dev/null");
        } catch (e) {}
        
        if (!raw || raw.trim() === "") {
            raw = await execShell("pm list packages -3 -u");
        }
        
        const regex = /package:([^\s]+)/g;
        let rawList = [];
        let match;
        
        while ((match = regex.exec(raw)) !== null) {
            rawList.push(match[1]);
        }

        opapsPackages = cleanList(rawList).filter(p => !p.includes("overlay"));
        opapsPackages.sort();

        await loadLabels(opapsPackages);

        document.getElementById('opapsInitState')?.style.setProperty('display', 'none');
        document.getElementById('opapsSearch')?.classList.add('show');
        renderOpapsList();
    } catch (e) {
        console.error("startOpapsScan error:", e);
        alert("Failed to scan apps: " + e);
    } finally {
        if (btn) {
            btn.innerHTML = originalText || 'Scan';
            btn.disabled = false;
        }
        isScanning = false;
    }
}

function renderOpapsList(filter = '') {
    const container = document.getElementById('opaps-list-target');
    const countInfo = document.getElementById('opaps-count-info');
    
    if (!container) return;
    
    const filterLower = filter.toLowerCase();
    const filtered = opapsPackages.filter(pkg => {
        const label = (infoCache[pkg]?.label || "").toLowerCase();
        return pkg.toLowerCase().includes(filterLower) || label.includes(filterLower);
    });

    if (countInfo) {
        countInfo.textContent = `${filtered.length} user apps found`;
        countInfo.style.display = 'block';
    }

    if (filtered.length === 0) {
        container.innerHTML = '<div style="text-align:center; opacity:0.5; padding:20px;">No apps</div>';
        return;
    }

    const limit = filter === '' ? 100 : filtered.length;
    const fragment = document.createDocumentFragment();

    filtered.slice(0, limit).forEach(pkg => {
        const label = infoCache[pkg]?.label || pkg;
        const div = document.createElement('div');
        div.className = 'app-card-item';
        div.onclick = () => executeOpapsForApp(pkg);

        div.innerHTML = `
            <div class="app-icon-placeholder">
                <span class="material-symbols-rounded">android</span>
            </div>
            <div class="app-card-info">
                <div class="app-card-title">${escapeHtml(label)}</div>
                <div class="app-card-pkg">${escapeHtml(pkg)}</div>
            </div>
            <span class="material-symbols-rounded" style="color: var(--md-outline); font-size: 20px;">play_circle</span>`;
        fragment.appendChild(div);
    });
    
    container.innerHTML = '';
    container.appendChild(fragment);
}

const debouncedOpapsFilter = debounce((val) => { 
    if (opapsPackages.length > 0) renderOpapsList(val); 
}, 300);

function executeOpapsForApp(pkg) {
    selectedOpapsPkg = pkg;
    const targetEl = document.getElementById('opapsTargetPkg');
    if (targetEl) targetEl.innerText = pkg;
    document.getElementById('opapsConfirmModal')?.classList.add('show');
}

function closeOpapsConfirm() {
    document.getElementById('opapsConfirmModal')?.classList.remove('show');
    selectedOpapsPkg = "";
}

async function runOpapsConfirmed() {
    if (!selectedOpapsPkg) return;
    const pkg = selectedOpapsPkg;

    closeOpapsConfirm();

    try {
        await execShell(`nohup opaps ${pkg} > /dev/null 2>&1 &`);
        alert(`Success! Opaps applied to ${pkg}.`);
    } catch (e) {
        console.error("runOpapsConfirmed error:", e);
        alert(`Failed: ${e}`);
    }

    closeOpapsPage();
}

// ================== INIT ==================
window.onload = async function () {
    console.log("=== KyrooS Initializing ===");
    
    // Tunggu WebView interface siap
    await new Promise(r => setTimeout(r, 500));
    
    // Cek KyroosApp
    let retries = 0;
    while (typeof KyroosApp === 'undefined' && retries < 10) {
        console.log("Waiting for KyroosApp...", retries);
        await new Promise(r => setTimeout(r, 200));
        retries++;
    }
    
    if (typeof KyroosApp === 'undefined') {
        console.error("KyroosApp not found after retries!");
        alert("Error: ADB interface not available");
        return;
    }
    
    console.log("KyroosApp ready");
    
    const appConfig = localStorage.getItem('advAppConfig') === 'true';
    const appConfigSw = document.getElementById('appConfigSwitch');
    const navApps = document.getElementById('nav-apps');
    const mainNav = document.getElementById('mainNav');
    
    if (appConfigSw) appConfigSw.checked = appConfig;
    
    if (appConfig) {
        navApps?.classList.remove('hidden-tab');
        mainNav?.classList.add('wide');
    }
    
    // Jalankan fungsi secara berurutan
    try {
        await updateHomeData();
        console.log("✓ Home data");
    } catch(e) {
        console.error("✗ Home data:", e);
    }
    
    await new Promise(r => setTimeout(r, 300));
    
    try {
        await loadConfig();
        console.log("✓ Config loaded");
    } catch(e) {
        console.error("✗ Config:", e);
    }
    
    await new Promise(r => setTimeout(r, 300));
    
    try {
        await fetchCurrentRes();
        console.log("✓ Resolution");
    } catch(e) {
        console.error("✗ Resolution:", e);
    }
    
    console.log("=== Init Complete ===");
};
