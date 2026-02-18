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

function safeJSONParse(str) { try { return JSON.parse(str); } catch { return []; } }

function debounce(func, delay) {
    let timeout;
    return (...args) => { clearTimeout(timeout); timeout = setTimeout(() => func.apply(this, args), delay); };
}

function cleanList(arr) {
    return [...new Set(arr)].filter(p => p && p.trim().length > 0);
}

// ================== EKSEKUSI PERINTAH (TIDAK MEMFILTER ERROR) ==================
async function execShell(cmd) {
    try {
        // 1. Prioritas Utama: Gunakan ADB KyrooS
        if (typeof KyroosApp !== 'undefined') {
            console.log("ADB exec: " + cmd);
            const result = KyroosApp.executeShell(cmd);
            console.log("ADB result:", result);
            return result; // kembalikan apa adanya (bisa string error atau output)
        }

        // 2. Prioritas Kedua: KernelSU
        if (window.ksu && window.ksu.exec) {
            console.log("KSU exec: " + cmd);
            const res = await window.ksu.exec(cmd);
            return res.stdout || res || "";
        }

        console.warn("No execution bridge found!");
        return "";
    } catch (e) {
        console.error("Exec exception: ", e);
        return "Error: " + e.message;
    }
}

// ================== NAVIGASI ==================
function switchTab(tabId) {
    document.querySelectorAll('.tab-section').forEach(el => el.classList.remove('active'));
    document.querySelectorAll('.nav-item').forEach(el => el.classList.remove('active'));
    document.getElementById(tabId).classList.add('active');
    document.getElementById('nav-' + tabId).classList.add('active');
    if (tabId === 'home') updateHomeData();
}

function toggleAppConfig(el) {
    const on = el.checked;
    localStorage.setItem('advAppConfig', on);
    const nav = document.getElementById('mainNav');
    const btn = document.getElementById('nav-apps');
    if (on) { btn.classList.remove('hidden-tab'); nav.classList.add('wide'); }
    else { btn.classList.add('hidden-tab'); nav.classList.remove('wide'); }
    if (!on && document.getElementById('apps').classList.contains('active')) switchTab('home');
}

function openConfigPage() { document.getElementById('configPage').classList.add('open'); document.getElementById('mainNav').classList.add('hidden'); }
function closeConfigPage() { document.getElementById('configPage').classList.remove('open'); document.getElementById('mainNav').classList.remove('hidden'); }

function openTweakPage() { document.getElementById('tweakPage').classList.add('open'); document.getElementById('mainNav').classList.add('hidden'); }
function closeTweakPage() { document.getElementById('tweakPage').classList.remove('open'); document.getElementById('mainNav').classList.remove('hidden'); }

function openDevModal() { document.getElementById('devModal').classList.add('show'); }
function closeDevModal() { document.getElementById('devModal').classList.remove('show'); }

async function openTgLink(url) {
    await execShell(`am start -a android.intent.action.VIEW -d "${url}"`);
}

// ================== DETAIL APLIKASI ==================
function openAppDetail(pkg) {
    currentDetailPkg = pkg;
    const cache = infoCache[pkg] || {};
    document.getElementById('detailAppLabel').innerText = cache.label || pkg;
    document.getElementById('detailAppPkg').innerText = pkg;

    document.getElementById('angleSwitch').checked = currentAngleList.includes(pkg);
    document.getElementById('gameSwitch').checked = currentGameList.includes(pkg);
    document.getElementById('devSwitch').checked = currentDevList.includes(pkg);

    execShell(`cmd deviceidle whitelist | grep ${pkg}`).then(res => {
        document.getElementById('whitelistSwitch').checked = res.includes(pkg);
    });

    document.getElementById('appDetailPage').classList.add('open');
    document.getElementById('mainNav').classList.add('hidden');
}
function closeAppDetail() {
    document.getElementById('appDetailPage').classList.remove('open');
    document.getElementById('mainNav').classList.remove('hidden');
}

// ================== HOME ==================
async function updateHomeData() {
    // Gunakan beberapa percobaan untuk chipset
    let chip = await execShell("/system/bin/getprop ro.board.platform");
    chip = chip.trim();
    if (!chip || chip.startsWith("Error")) {
        chip = (await execShell("/system/bin/getprop ro.product.board")).trim();
    }
    if (!chip || chip.startsWith("Error")) {
        chip = (await execShell("/system/bin/getprop ro.chipname")).trim();
    }
    if (!chip || chip.startsWith("Error")) {
        chip = (await execShell("/system/bin/getprop ro.hardware")).trim();
    }
    document.getElementById('chipsetInfo').innerText = chip || "Unknown";

    // RAM
    const memRaw = await execShell("cat /proc/meminfo | grep MemTotal");
    const memMatch = memRaw.match(/MemTotal:\s+(\d+)/);
    if (memMatch) {
        const kb = parseInt(memMatch[1]);
        document.getElementById('ramInfo').innerText = (kb / 1024 / 1024).toFixed(1) + " GB";
    } else {
        document.getElementById('ramInfo').innerText = "Error";
    }

    // Kernel
    let kernel = await execShell("/system/bin/uname -r");
    kernel = kernel.trim();
    if (!kernel || kernel.startsWith("Error")) {
        kernel = await execShell("cat /proc/version");
        // parse versi dari /proc/version
        const vMatch = kernel.match(/version (.*?) \(/);
        if (vMatch) kernel = vMatch[1];
        else kernel = "Unknown";
    }
    document.getElementById('kernelInfo').innerText = kernel || "Unknown";

    // Status Sigma
    checkStatus();
}

async function checkStatus() {
    const pid = await execShell("pgrep -f sigma");
    const isRun = pid.trim().length > 0 && !pid.startsWith("Error");
    document.getElementById('sigmaSwitch').checked = isRun;

    const card = document.getElementById('statusCard');
    const icon = document.getElementById('statusIcon');
    const title = document.getElementById('statusTitle');
    const desc = document.getElementById('statusDesc');

    if (isRun) {
        card.classList.add('active-mode');
        icon.innerText = 'verified';
        title.innerText = 'Kyroos Active';
        desc.innerText = 'Sigma binary running';
    } else {
        card.classList.remove('active-mode');
        icon.innerText = 'hourglass_empty';
        title.innerText = 'Service Idle';
        desc.innerText = 'Enable in settings';
    }
}

async function toggleSigma(el) {
    if (el.checked) await execShell("nohup sigma > /dev/null 2>&1 &");
    else await execShell("pkill -f sigma && pkill -f sigma");
    setTimeout(checkStatus, 500);
}

// ================== APPS LIST ==================
async function startAppScan() {
    if (isScanning) return;
    isScanning = true;

    document.getElementById('appInitState').style.display = 'none';
    document.getElementById('appSearch').classList.add('show');
    const container = document.getElementById('app-list-target');
    container.innerHTML = '<div style="text-align:center; padding:20px;">Loading list...</div>';

    let rawList = [];
    // Coba dengan cmd package (lebih modern)
    let raw = await execShell("cmd package list packages -u");
    if (!raw || raw.startsWith("Error")) {
        // Fallback ke pm
        raw = await execShell("/system/bin/pm list packages -u");
    }
    const regex = /package:([^\s]+)/g;
    let match;
    while ((match = regex.exec(raw)) !== null) {
        const pkg = match[1];
        if (!pkg.startsWith("com.android.overlay") && !pkg.startsWith("com.google.android.overlay")) {
            rawList.push(pkg);
        }
    }

    allPackages = rawList;
    allPackages.sort();
    await loadLabels(allPackages);
    renderAppList();
    isScanning = false;
}

async function loadLabels(pkgs) {
    // Jika ada KSU, gunakan untuk mendapatkan label
    if (window.ksu && window.ksu.getPackagesInfo) {
        try {
            const chunk = pkgs.slice(0, 500);
            const infoJson = JSON.stringify(chunk);
            const infos = safeJSONParse(await window.ksu.getPackagesInfo(infoJson));
            infos.forEach(item => {
                if (!infoCache[item.packageName]) infoCache[item.packageName] = {};
                infoCache[item.packageName].label = item.appLabel;
            });
        } catch (e) {
            console.error("loadLabels error:", e);
        }
    } else {
        // Fallback: gunakan bagian terakhir package name
        pkgs.forEach(pkg => {
            if (!infoCache[pkg]) infoCache[pkg] = {};
            infoCache[pkg].label = pkg.split('.').pop();
        });
    }
}

function renderAppList(filter = '') {
    const container = document.getElementById('app-list-target');
    const countInfo = document.getElementById('app-count-info');
    const filterLower = filter.toLowerCase();

    const filtered = allPackages.filter(pkg =>
        pkg.toLowerCase().includes(filterLower) ||
        (infoCache[pkg]?.label || '').toLowerCase().includes(filterLower)
    );

    countInfo.textContent = `${filtered.length} apps found`;
    countInfo.style.display = 'block';

    if (filtered.length === 0) {
        container.innerHTML = '<div style="text-align:center; opacity:0.5; padding:20px;">No matching apps</div>';
        return;
    }

    const renderLimit = filter === '' ? 100 : filtered.length;
    const fragment = document.createDocumentFragment();

    filtered.slice(0, renderLimit).forEach(pkg => {
        const cached = infoCache[pkg];
        const label = cached?.label || pkg;

        const div = document.createElement('div');
        div.className = 'app-card-item';
        div.onclick = () => openAppDetail(pkg);

        div.innerHTML = `
            <div class="app-icon-placeholder">
                <span class="material-symbols-rounded">android</span>
            </div>
            <div class="app-card-info">
                <div class="app-card-title">${label}</div>
                <div class="app-card-pkg">${pkg}</div>
            </div>`;
        fragment.appendChild(div);
    });
    container.innerHTML = '';
    container.appendChild(fragment);
}
const debouncedFilter = debounce((val) => { if (allPackages.length > 0) renderAppList(val); }, 300);

// ================== KONFIGURASI ==================
function checkInterlock() {
    const auto = document.getElementById('powerSwitch').checked;
    const custom = document.getElementById('customSaveSwitch').checked;

    const autoCard = document.getElementById('autoPowerCard');
    const customCard = document.getElementById('customPowerCard');
    const autoSw = document.getElementById('powerSwitch');
    const customSw = document.getElementById('customSaveSwitch');

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

function handleAutoToggle(el) {
    if (el.checked) {
        document.getElementById('customSaveSwitch').checked = false;
        toggleCustomSaveUI({ checked: false });
    }
    checkInterlock();
    saveKyroosConfig();
}

function handleCustomToggle(el) {
    if (el.checked) {
        document.getElementById('powerSwitch').checked = false;
    }
    toggleCustomSaveUI(el);
    checkInterlock();
    saveKyroosConfig();
}

function toggleCustomSaveUI(el) {
    const container = document.getElementById('customSaveContainer');
    if (el.checked) container.classList.add('show'); else container.classList.remove('show');
}

function handleBrutalToggle(el) {
    if (el.checked) {
        document.getElementById('confirmModal').classList.add('show');
    }
}

async function confirmBrutalAction() {
    document.getElementById('confirmModal').classList.remove('show');
    openOpapsPage();
}

function cancelBrutalAction() {
    document.getElementById('confirmModal').classList.remove('show');
    document.getElementById('brutalSwitch').checked = false;
}

async function handleDriverToggle(type) {
    const pkg = currentDetailPkg;
    const angleEl = document.getElementById('angleSwitch');
    const gameEl = document.getElementById('gameSwitch');
    const devEl = document.getElementById('devSwitch');

    if (type === 'game' && gameEl.checked) devEl.checked = false;
    if (type === 'dev' && devEl.checked) gameEl.checked = false;

    if (angleEl.checked) { if (!currentAngleList.includes(pkg)) currentAngleList.push(pkg); }
    else { currentAngleList = currentAngleList.filter(p => p !== pkg); }

    if (gameEl.checked) { if (!currentGameList.includes(pkg)) currentGameList.push(pkg); }
    else { currentGameList = currentGameList.filter(p => p !== pkg); }

    if (devEl.checked) { if (!currentDevList.includes(pkg)) currentDevList.push(pkg); }
    else { currentDevList = currentDevList.filter(p => p !== pkg); }

    await saveKyroosConfig();
}

async function handleWhitelistToggle(el) {
    const pkg = currentDetailPkg;
    const cmd = el.checked ? `cmd deviceidle whitelist +${pkg}` : `cmd deviceidle whitelist -${pkg}`;
    await execShell(cmd);
}

async function fetchCurrentRes() {
    try {
        const sizeRaw = await execShell("wm size");
        const sizeMatch = sizeRaw.match(/(\d+)x(\d+)/);
        if (sizeMatch) {
            document.getElementById('resW').value = sizeMatch[1];
            document.getElementById('resH').value = sizeMatch[2];
        }
    } catch (e) { }
}

function toggleResUI(el) {
    const container = document.getElementById('resContainer');
    if (el.checked) { container.classList.add('show'); fetchCurrentRes(); }
    else { container.classList.remove('show'); }
}

async function applyResolution() {
    const w = document.getElementById('resW').value;
    const h = document.getElementById('resH').value;
    if (w && h) {
        await execShell(`wm size ${w}x${h}`);
        const btn = document.querySelector('.btn-apply');
        const originalText = btn.innerHTML;
        btn.innerHTML = `<span class="material-symbols-rounded">check</span> Applied!`;
        setTimeout(() => { btn.innerHTML = originalText; }, 1500);
    }
}

async function resetResolution() {
    await execShell("wm size reset");
    fetchCurrentRes();
}

async function applyCustomSave() {
    const btn = document.querySelector('.btn-icon-apply');
    const oldColor = btn.style.backgroundColor;
    btn.style.backgroundColor = "var(--md-secondary-container)";
    btn.style.color = "var(--md-on-secondary-container)";
    await saveKyroosConfig();
    setTimeout(() => { btn.style.backgroundColor = oldColor; btn.style.color = ""; }, 500);
}

async function saveKyroosConfig() {
    const d = document.getElementById('deepSwitch').checked ? "on" : "off";
    const p = document.getElementById('powerSwitch').checked ? "on" : "off";
    const c = document.getElementById('cacheSwitch').checked ? "on" : "off";
    const b = document.getElementById('brutalSwitch').checked ? "on" : "off";
    const isCustomOn = document.getElementById('customSaveSwitch').checked;
    const customVal = document.getElementById('customSaveValue').value;
    const finalCustom = (isCustomOn && customVal) ? customVal : "off";

    const angleVal = cleanList(currentAngleList).join(',') || "none";
    const gameVal = cleanList(currentGameList).join(',') || "none";
    const devVal = cleanList(currentDevList).join(',') || "none";

    const commands = [
        `echo "deep=${d}" > "${CONFIG_PATH}"`,
        `echo "power=${p}" >> "${CONFIG_PATH}"`,
        `echo "chace=${c}" >> "${CONFIG_PATH}"`,
        `echo "brutal=${b}" >> "${CONFIG_PATH}"`,
        `echo "cossave=${finalCustom}" >> "${CONFIG_PATH}"`,
        `echo "angle=${angleVal}" >> "${CONFIG_PATH}"`,
        `echo "game=${gameVal}" >> "${CONFIG_PATH}"`,
        `echo "dev=${devVal}" >> "${CONFIG_PATH}"`
    ];

    await execShell(commands.join(' && '));

    if (angleVal !== "none") {
        await execShell(`settings put global angle_gl_driver_selection_pkgs "${angleVal}"`);
        await execShell(`settings put global angle_gl_driver_selection_values "${angleVal.split(',').map(() => 'angle').join(',')}"`);
    } else {
        await execShell(`settings delete global angle_gl_driver_selection_pkgs`);
        await execShell(`settings delete global angle_gl_driver_selection_values`);
    }

    if (gameVal !== "none") await execShell(`settings put global game_driver_opt_in_apps "${gameVal}"`);
    else await execShell(`settings delete global game_driver_opt_in_apps`);

    if (devVal !== "none") await execShell(`settings put global game_driver_prerelease_opt_in_apps "${devVal}"`);
    else await execShell(`settings delete global game_driver_prerelease_opt_in_apps`);
}

async function loadConfig() {
    const c = await execShell(`cat ${CONFIG_PATH}`);
    if (c && !c.startsWith("Error")) {
        document.getElementById('deepSwitch').checked = c.includes('deep=on');
        document.getElementById('powerSwitch').checked = c.includes('power=on');
        document.getElementById('cacheSwitch').checked = c.includes('chace=on');
        document.getElementById('brutalSwitch').checked = c.includes('brutal=on');

        const matchS = c.match(/cossave=(\w+)/);
        if (matchS && matchS[1] !== 'off') {
            document.getElementById('customSaveSwitch').checked = true;
            document.getElementById('customSaveContainer').classList.add('show');
            document.getElementById('customSaveValue').value = matchS[1];
        } else {
            document.getElementById('customSaveSwitch').checked = false;
        }

        const matchA = c.match(/angle=([^\n]+)/);
        currentAngleList = matchA && matchA[1] !== 'none' ? matchA[1].split(',') : [];

        const matchG = c.match(/game=([^\n]+)/);
        currentGameList = matchG && matchG[1] !== 'none' ? matchG[1].split(',') : [];

        const matchD = c.match(/dev=([^\n]+)/);
        currentDevList = matchD && matchD[1] !== 'none' ? matchD[1].split(',') : [];

        checkInterlock();
    }
}

// ================== OPAPS ==================
let opapsPackages = [];
let selectedOpapsPkg = "";

function openOpapsPage() {
    document.getElementById('opapsPage').classList.add('open');
    document.getElementById('mainNav').classList.add('hidden');
}

function closeOpapsPage() {
    document.getElementById('opapsPage').classList.remove('open');
    document.getElementById('mainNav').classList.remove('hidden');
    document.getElementById('brutalSwitch').checked = false;
}

async function startOpapsScan() {
    if (isScanning) return;
    isScanning = true;

    const btn = document.querySelector('#opapsInitState .btn-scan');
    const originalText = btn.innerHTML;
    btn.innerHTML = '<span class="material-symbols-rounded" style="animation: spin 1s linear infinite;">refresh</span> Scanning...';
    btn.disabled = true;

    try {
        let raw = await execShell("cmd package list packages -3 -u");
        if (!raw || raw.startsWith("Error")) {
            raw = await execShell("/system/bin/pm list packages -3 -u");
        }
        const regex = /package:([^\s]+)/g;
        let rawList = [];
        let match;
        while ((match = regex.exec(raw)) !== null) {
            rawList.push(match[1]);
        }

        opapsPackages = cleanList(rawList).filter(p => !p.startsWith("com.android.overlay") && !p.startsWith("com.google.android.overlay"));
        opapsPackages.sort();

        await loadLabels(opapsPackages);

        document.getElementById('opapsInitState').style.display = 'none';
        document.getElementById('opapsSearch').classList.add('show');
        renderOpapsList();
    } catch (e) {
        alert("Failed to scan apps: " + e);
    } finally {
        btn.innerHTML = originalText;
        btn.disabled = false;
        isScanning = false;
    }
}

function renderOpapsList(filter = '') {
    const container = document.getElementById('opaps-list-target');
    const countInfo = document.getElementById('opaps-count-info');
    const filterLower = filter.toLowerCase();

    const filtered = opapsPackages.filter(pkg =>
        pkg.toLowerCase().includes(filterLower) ||
        (infoCache[pkg]?.label || '').toLowerCase().includes(filterLower)
    );

    countInfo.textContent = `${filtered.length} user apps found`;
    countInfo.style.display = 'block';

    if (filtered.length === 0) {
        container.innerHTML = '<div style="text-align:center; opacity:0.5; padding:20px;">No matching apps</div>';
        return;
    }

    const renderLimit = filter === '' ? 100 : filtered.length;
    const fragment = document.createDocumentFragment();

    filtered.slice(0, renderLimit).forEach(pkg => {
        const cached = infoCache[pkg];
        const label = cached?.label || pkg;

        const div = document.createElement('div');
        div.className = 'app-card-item';
        div.onclick = () => executeOpapsForApp(pkg);

        div.innerHTML = `
            <div class="app-icon-placeholder">
                <span class="material-symbols-rounded">android</span>
            </div>
            <div class="app-card-info">
                <div class="app-card-title">${label}</div>
                <div class="app-card-pkg">${pkg}</div>
            </div>
            <span class="material-symbols-rounded" style="color: var(--md-outline); font-size: 20px;">play_circle</span>`;
        fragment.appendChild(div);
    });
    container.innerHTML = '';
    container.appendChild(fragment);
}

const debouncedOpapsFilter = debounce((val) => { if (opapsPackages.length > 0) renderOpapsList(val); }, 300);

function executeOpapsForApp(pkg) {
    selectedOpapsPkg = pkg;
    document.getElementById('opapsTargetPkg').innerText = pkg;
    document.getElementById('opapsConfirmModal').classList.add('show');
}

function closeOpapsConfirm() {
    document.getElementById('opapsConfirmModal').classList.remove('show');
    selectedOpapsPkg = "";
}

async function runOpapsConfirmed() {
    if (!selectedOpapsPkg) return;
    const pkg = selectedOpapsPkg;

    closeOpapsConfirm();

    try {
        await execShell(`nohup opaps ${pkg}`);
        alert(`Success! Opaps applied to ${pkg}.`);
    } catch (e) {
        alert(`Failed to execute Opaps: ${e}`);
    }

    closeOpapsPage();
}

// ================== INIT ==================
window.onload = function () {
    const appConfig = localStorage.getItem('advAppConfig') === 'true';
    document.getElementById('appConfigSwitch').checked = appConfig;
    if (appConfig) {
        document.getElementById('nav-apps').classList.remove('hidden-tab');
        document.getElementById('mainNav').classList.add('wide');
    }
    updateHomeData();
    loadConfig();
    fetchCurrentRes();
};