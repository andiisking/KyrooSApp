const CONFIG_PATH = "/storage/emulated/0/kikiros";

// Fallback jika tidak ada KSU
window.ksu = window.ksu || { exec: async () => ({ stdout: "" }) };

let allPackages = [];
let infoCache = {};
let currentDetailPkg = "";
let currentAngleList = [];
let currentGameList = [];
let currentDevList = [];
let isScanning = false;

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

// ================== EKSEKUSI PERINTAH (QUEUE SYSTEM - FINAL) ==================

window.adbCallbacks = {};
const adbQueue = [];           
let isAdbExecuting = false;    
let queueProcessing = false;  // Lock tambahan untuk processQueue itself
let queueTimer = null;
let queueWatchdog = null;

// Watchdog: Force reset kalau macet lebih dari 15 detik
function startWatchdog() {
    if (queueWatchdog) clearTimeout(queueWatchdog);
    queueWatchdog = setTimeout(() => {
        console.error("WATCHDOG: Queue stuck! Force resetting...");
        isAdbExecuting = false;
        queueProcessing = false;
        processQueue();
    }, 15000);
}

function stopWatchdog() {
    if (queueWatchdog) {
        clearTimeout(queueWatchdog);
        queueWatchdog = null;
    }
}

window.adbCallback = function(callbackId, base64Result, isError) {
    console.log("ADB Callback:", callbackId, "error:", isError);
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
            console.error("Callback decode error:", e);
            callback.resolve(base64Result || "");
        }
        delete window.adbCallbacks[callbackId];
    } else {
        console.warn("Callback not found:", callbackId);
    }

    // Reset dan proses berikutnya
    isAdbExecuting = false;
    
    // Delay minimal untuk mencegah stack overflow
    setTimeout(() => {
        queueProcessing = false;
        processQueue();
    }, 10);
};

function processQueue() {
    // Double-lock pattern: cek isAdbExecuting DAN queueProcessing
    if (isAdbExecuting || queueProcessing) {
        return;
    }
    
    if (adbQueue.length === 0) {
        return;
    }
    
    // Lock segera untuk mencegah race condition
    queueProcessing = true;
    isAdbExecuting = true;
    
    const task = adbQueue.shift();
    const callbackId = 'cb_' + Date.now() + '_' + Math.random().toString(36).substr(2, 9);
    
    console.log("Queue exec [" + adbQueue.length + " left]:", task.cmd.substring(0, 50));
    
    window.adbCallbacks[callbackId] = { 
        resolve: task.resolve, 
        reject: task.reject 
    };
    
    // Cek KyroosApp
    if (typeof KyroosApp === 'undefined' || !KyroosApp.executeShell) {
        console.error("KyroosApp missing!");
        isAdbExecuting = false;
        queueProcessing = false;
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
        queueProcessing = false;
        task.reject("Error: " + e.message);
        delete window.adbCallbacks[callbackId];
        setTimeout(processQueue, 100);
    }
}

async function execShell(cmd) {
    // Cek dulu apakah KyroosApp tersedia
    if (typeof KyroosApp !== 'undefined' && KyroosApp.executeShell) {
        return new Promise((resolve, reject) => {
            adbQueue.push({ cmd, resolve, reject });
            // Trigger queue
            setTimeout(processQueue, 0);
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

    console.warn("No execution method!");
    return "Error: No ADB or KSU available";
}

// ================== NAVIGASI ==================
function switchTab(tabId) {
    document.querySelectorAll('.tab-section').forEach(el => el.classList.remove('active'));
    document.querySelectorAll('.nav-item').forEach(el => el.classList.remove('active'));
    const target = document.getElementById(tabId);
    const nav = document.getElementById('nav-' + tabId);
    if (target) target.classList.add('active');
    if (nav) nav.classList.add('active');
    if (tabId === 'home') updateHomeData();
}

function toggleAppConfig(el) {
    const on = el.checked;
    localStorage.setItem('advAppConfig', on);
    const nav = document.getElementById('mainNav');
    const btn = document.getElementById('nav-apps');
    if (btn) {
        if (on) { 
            btn.classList.remove('hidden-tab'); 
            if (nav) nav.classList.add('wide'); 
        } else { 
            btn.classList.add('hidden-tab'); 
            if (nav) nav.classList.remove('wide'); 
        }
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
    const whitelistSw = document.getElementById('whitelistSwitch');
    
    if (angleSw) angleSw.checked = currentAngleList.includes(pkg);
    if (gameSw) gameSw.checked = currentGameList.includes(pkg);
    if (devSw) devSw.checked = currentDevList.includes(pkg);

    execShell(`sh -c "cmd deviceidle whitelist | grep ${pkg}"`)
        .then(res => {
            if (whitelistSw) whitelistSw.checked = res.includes(pkg);
        })
        .catch(err => {
            console.error("Whitelist check failed:", err);
            if (whitelistSw) whitelistSw.checked = false;
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
    
    const chipsetEl = document.getElementById('chipsetInfo');
    const ramEl = document.getElementById('ramInfo');
    const kernelEl = document.getElementById('kernelInfo');
    
    try {
        // Chipset dengan multiple fallback
        let chip = "";
        const props = [
            "ro.board.platform",
            "ro.product.board", 
            "ro.chipname",
            "ro.hardware",
            "ro.system.chipname"
        ];
        
        for (const prop of props) {
            if (chip) break;
            try {
                const result = await execShell(`/system/bin/getprop ${prop}`);
                const trimmed = result.trim();
                if (trimmed && !trimmed.startsWith("Error") && trimmed !== "") {
                    chip = trimmed;
                }
            } catch (e) {}
        }
        
        if (chipsetEl) chipsetEl.innerText = chip || "Unknown";
        console.log("Chipset:", chip || "Unknown");

        // RAM
        try {
            const memRaw = await execShell("cat /proc/meminfo | grep MemTotal");
            const memMatch = memRaw.match(/MemTotal:\s+(\d+)/);
            if (memMatch && ramEl) {
                const gb = (parseInt(memMatch[1]) / 1024 / 1024).toFixed(1);
                ramEl.innerText = gb + " GB";
            } else if (ramEl) {
                ramEl.innerText = "Unknown";
            }
        } catch (e) {
            if (ramEl) ramEl.innerText = "Error";
        }

        // Kernel
        let kernel = "";
        try {
            const uname = await execShell("/system/bin/uname -r");
            kernel = uname.trim();
        } catch (e) {}
        
        if (!kernel) {
            try {
                const ver = await execShell("cat /proc/version");
                const match = ver.match(/version\s+([^\s]+)/);
                if (match) kernel = match[1];
            } catch (e) {}
        }
        
        if (kernelEl) kernelEl.innerText = kernel || "Unknown";
        console.log("Kernel:", kernel || "Unknown");

        await checkStatus();
    } catch (e) {
        console.error("updateHomeData error:", e);
    }
}

async function checkStatus() {
    try {
        const pid = await execShell("pgrep -f sigma");
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
            await execShell("pkill -f sigma || true");
        }
        setTimeout(checkStatus, 1000);
    } catch (e) {
        console.error("toggleSigma error:", e);
        // Revert switch kalau gagal
        setTimeout(() => {
            el.checked = !el.checked;
        }, 100);
    }
}

// ================== APPS LIST ==================
async function startAppScan() {
    if (isScanning) return;
    isScanning = true;

    const initState = document.getElementById('appInitState');
    const search = document.getElementById('appSearch');
    const container = document.getElementById('app-list-target');
    
    if (initState) initState.style.display = 'none';
    if (search) search.classList.add('show');
    if (container) container.innerHTML = '<div style="text-align:center; padding:20px;">Loading...</div>';

    try {
        let raw = "";
        
        // Coba cmd package dulu
        try {
            raw = await execShell("cmd package list packages -u");
        } catch (e) {}
        
        // Fallback ke pm
        if (!raw || raw.trim() === "") {
            raw = await execShell("/system/bin/pm list packages -u");
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
            container.innerHTML = '<div style="text-align:center; padding:20px; color: red;">Failed to load</div>';
        }
    } finally {
        isScanning = false;
    }
}

async function loadLabels(pkgs) {
    if (window.ksu && window.ksu.getPackagesInfo) {
        try {
            const chunk = pkgs.slice(0, 500);
            const infoJson = JSON.stringify(chunk);
            const result = await window.ksu.getPackagesInfo(infoJson);
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

    // Mutual exclusion game/dev
    if (type === 'game' && gameEl.checked) devEl.checked = false;
    if (type === 'dev' && devEl.checked) gameEl.checked = false;

    // Update lists
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

        // Apply ANGLE
        if (angleVal !== "none") {
            await execShell(`settings put global angle_gl_driver_selection_pkgs "${angleVal}"`);
            await execShell(`settings put global angle_gl_driver_selection_values "${angleVal.split(',').map(() => 'angle').join(',')}"`);
        } else {
            await execShell(`settings delete global angle_gl_driver_selection_pkgs 2>/dev/null || true`);
            await execShell(`settings delete global angle_gl_driver_selection_values 2>/dev/null || true`);
        }

        // Apply Game Driver
        if (gameVal !== "none") {
            await execShell(`settings put global game_driver_opt_in_apps "${gameVal}"`);
        } else {
            await execShell(`settings delete global game_driver_opt_in_apps 2>/dev/null || true`);
        }

        // Apply Dev Driver
        if (devVal !== "none") {
            await execShell(`settings put global game_driver_prerelease_opt_in_apps "${devVal}"`);
        } else {
            await execShell(`settings delete global game_driver_prerelease_opt_in_apps 2>/dev/null || true`);
        }
        
        console.log("Config saved successfully");
    } catch (e) {
        console.error("saveKyroosConfig error:", e);
        throw e;
    }
}

async function loadConfig() {
    console.log("Loading config...");
    
    try {
        const result = await execShell(`cat "${CONFIG_PATH}" 2>/dev/null || echo "FILE_NOT_FOUND"`);
        
        // Handle kalau result bukan string
        const c = String(result || "");
        
        if (c === "FILE_NOT_FOUND" || c.includes("No such file") || c.trim() === "") {
            console.log("No config file, using defaults");
            return;
        }
        
        if (c.startsWith("Error")) {
            console.error("Error reading config:", c);
            return;
        }
        
        console.log("Config loaded");
        
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
            raw = await execShell("cmd package list packages -3 -u");
        } catch (e) {}
        
        if (!raw || raw.trim() === "") {
            raw = await execShell("/system/bin/pm list packages -3 -u");
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
    await new Promise(r => setTimeout(r, 300));
    
    // Cek KyroosApp
    if (typeof KyroosApp === 'undefined') {
        console.error("KyroosApp not found! Waiting...");
        // Coba lagi nanti
        setTimeout(window.onload, 500);
        return;
    }
    
    console.log("KyroosApp found:", typeof KyroosApp.executeShell);
    
    const appConfig = localStorage.getItem('advAppConfig') === 'true';
    const appConfigSw = document.getElementById('appConfigSwitch');
    const navApps = document.getElementById('nav-apps');
    const mainNav = document.getElementById('mainNav');
    
    if (appConfigSw) appConfigSw.checked = appConfig;
    
    if (appConfig) {
        navApps?.classList.remove('hidden-tab');
        mainNav?.classList.add('wide');
    }
    
    // Jalankan fungsi secara berurutan dengan delay
    const runSequential = async () => {
        try {
            await updateHomeData();
            console.log("✓ Home data");
        } catch(e) {
            console.error("✗ Home data:", e);
        }
        
        await new Promise(r => setTimeout(r, 200));
        
        try {
            await loadConfig();
            console.log("✓ Config loaded");
        } catch(e) {
            console.error("✗ Config:", e);
        }
        
        await new Promise(r => setTimeout(r, 200));
        
        try {
            await fetchCurrentRes();
            console.log("✓ Resolution");
        } catch(e) {
            console.error("✗ Resolution:", e);
        }
        
        console.log("=== Init Complete ===");
    };
    
    runSequential();
};
