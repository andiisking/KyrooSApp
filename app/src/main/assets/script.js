let CONFIG_PATH = "";
let SCRIPTS_DIR = "";
let allPackages = [];
let infoCache = {};
let currentDetailPkg = "";
let currentAngleList = [];
let currentGameList = [];
let currentDevList = [];
let opapsPackages = [];
let selectedOpapsPkg = "";
let isScanning = false;

function initializePaths() {
    if (typeof KyroosApp !== 'undefined' && KyroosApp.getScriptDir) {
        SCRIPTS_DIR = KyroosApp.getScriptDir();
        CONFIG_PATH = SCRIPTS_DIR + "/kyroos.conf";
    }
}

function safeJSONParse(str) { 
    try { return JSON.parse(str); } 
    catch { return []; } 
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

function escapeHtml(text) {
    if (!text) return '';
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}

async function runScriptFile(filename, args = "") {
    return new Promise((resolve, reject) => {
        const callbackId = 'script_' + Date.now() + '_' + Math.random().toString(36).substr(2, 9);
        window.shellCallbacks = window.shellCallbacks || {};
        window.shellCallbacks[callbackId] = { resolve, reject };
        if (typeof KyroosApp !== 'undefined' && KyroosApp.runScript) {
            KyroosApp.runScript(filename, args, callbackId);
        } else {
            delete window.shellCallbacks[callbackId];
            reject(new Error("KyroosApp interface unavailable"));
        }
        setTimeout(() => {
            if (window.shellCallbacks[callbackId]) {
                delete window.shellCallbacks[callbackId];
                reject(new Error("Timeout"));
            }
        }, 30000);
    });
}

async function execShell(command) {
    return new Promise((resolve, reject) => {
        if (typeof KyroosApp === 'undefined') {
            reject(new Error("Interface unavailable"));
            return;
        }
        const callbackId = 'cmd_' + Date.now() + '_' + Math.random().toString(36).substr(2, 9);
        window.shellCallbacks = window.shellCallbacks || {};
        window.shellCallbacks[callbackId] = { resolve, reject };
        KyroosApp.executeShell(command, callbackId);
        setTimeout(() => {
            if (window.shellCallbacks[callbackId]) {
                delete window.shellCallbacks[callbackId];
                reject(new Error("Timeout"));
            }
        }, 30000);
    });
}

window.shellCallback = function(callbackId, result, isError) {
    if (window.shellCallbacks && window.shellCallbacks[callbackId]) {
        if (isError === true || isError === 'true' || isError === 1) {
            window.shellCallbacks[callbackId].reject(new Error(result));
        } else {
            window.shellCallbacks[callbackId].resolve(result || '');
        }
        delete window.shellCallbacks[callbackId];
    }
};

function switchTab(tabId) {
    document.querySelectorAll('.tab-section').forEach(el => el.classList.remove('active'));
    document.querySelectorAll('.nav-item').forEach(el => el.classList.remove('active'));
    document.getElementById(tabId).classList.add('active');
    document.getElementById('nav-' + tabId).classList.add('active');
    if (tabId === 'home') updateHomeData();
}

function toggleAppConfig(el) {
    const isEnabled = el.checked;
    localStorage.setItem('advAppConfig', isEnabled);
    const nav = document.getElementById('mainNav');
    const btn = document.getElementById('nav-apps');
    if (isEnabled) { 
        btn.classList.remove('hidden-tab'); 
        nav.classList.add('wide'); 
    } else { 
        btn.classList.add('hidden-tab'); 
        nav.classList.remove('wide'); 
    }
}

function openConfigPage() { document.getElementById('configPage').classList.add('open'); document.getElementById('mainNav').classList.add('hidden'); }
function closeConfigPage() { document.getElementById('configPage').classList.remove('open'); document.getElementById('mainNav').classList.remove('hidden'); }
function openTweakPage() { document.getElementById('tweakPage').classList.add('open'); document.getElementById('mainNav').classList.add('hidden'); }
function closeTweakPage() { document.getElementById('tweakPage').classList.remove('open'); document.getElementById('mainNav').classList.remove('hidden'); }
function openDevModal() { document.getElementById('devModal').classList.add('show'); }
function closeDevModal() { document.getElementById('devModal').classList.remove('show'); }

async function updateHomeData() {
    try {
        let chip = await execShell("getprop ro.board.platform").catch(() => "");
        if (!chip) chip = await execShell("getprop ro.product.board").catch(() => "");
        document.getElementById('chipsetInfo').innerText = chip.trim() || "Unknown";
        let mem = await execShell("cat /proc/meminfo | grep MemTotal").catch(() => "");
        const memMatch = mem.match(/MemTotal:\s+(\d+)/i);
        if (memMatch) document.getElementById('ramInfo').innerText = (parseInt(memMatch[1]) / 1024 / 1024).toFixed(1) + " GB";
        let kernel = await execShell("uname -r").catch(() => "");
        document.getElementById('kernelInfo').innerText = kernel.trim() || "Unknown";
        checkStatus();
    } catch (e) {}
}

async function checkStatus() {
    try {
        const pid = await execShell("pgrep -f sigma").catch(() => "");
        const isRunning = pid && pid.trim().length > 0;
        document.getElementById('sigmaSwitch').checked = isRunning;
        const card = document.getElementById('statusCard');
        const title = document.getElementById('statusTitle');
        if (isRunning) {
            card.classList.add('active-mode');
            title.innerText = 'Kyroos Active';
        } else {
            card.classList.remove('active-mode');
            title.innerText = 'Service Idle';
        }
    } catch (e) {}
}

async function toggleSigma(el) {
    try {
        if (el.checked) {
            await runScriptFile("sigma.sh", "enable"); 
        } else {
            await execShell("pkill -f sigma");
        }
        setTimeout(checkStatus, 800);
    } catch (e) { el.checked = !el.checked; }
}

async function startAppScan() {
    if (isScanning) return;
    isScanning = true;
    document.getElementById('appInitState').style.display = 'none';
    document.getElementById('appSearch').classList.add('show');
    const container = document.getElementById('app-list-target');
    container.innerHTML = 'Loading...';
    try {
        const raw = await execShell("pm list packages -u").catch(() => "");
        allPackages = raw.split('\n').map(p => p.replace('package:', '').trim()).filter(p => p && !p.includes('overlay'));
        allPackages.sort();
        loadLabels(allPackages);
        renderAppList();
    } catch (e) { container.innerHTML = 'Failed'; }
    finally { isScanning = false; }
}

function loadLabels(pkgs) {
    pkgs.forEach(pkg => {
        if (!infoCache[pkg]) infoCache[pkg] = {};
        const parts = pkg.split('.');
        infoCache[pkg].label = parts[parts.length - 1];
    });
}

function renderAppList(filter = '') {
    const container = document.getElementById('app-list-target');
    const filtered = allPackages.filter(pkg => pkg.toLowerCase().includes(filter.toLowerCase()));
    const fragment = document.createDocumentFragment();
    filtered.slice(0, 50).forEach(pkg => {
        const div = document.createElement('div');
        div.className = 'app-card-item';
        div.onclick = () => openAppDetail(pkg);
        div.innerHTML = `<div class="app-card-info"><div class="app-card-title">${escapeHtml(infoCache[pkg]?.label || pkg)}</div><div class="app-card-pkg">${pkg}</div></div>`;
        fragment.appendChild(div);
    });
    container.innerHTML = '';
    container.appendChild(fragment);
}

function openAppDetail(pkg) {
    currentDetailPkg = pkg;
    document.getElementById('detailAppLabel').innerText = infoCache[pkg]?.label || pkg;
    document.getElementById('detailAppPkg').innerText = pkg;
    document.getElementById('angleSwitch').checked = currentAngleList.includes(pkg);
    document.getElementById('gameSwitch').checked = currentGameList.includes(pkg);
    document.getElementById('devSwitch').checked = currentDevList.includes(pkg);
    execShell(`cmd deviceidle whitelist | grep ${pkg}`).then(res => {
        document.getElementById('whitelistSwitch').checked = res.includes(pkg);
    });
    document.getElementById('appDetailPage').classList.add('open');
}

function closeAppDetail() { document.getElementById('appDetailPage').classList.remove('open'); }

async function handleDriverToggle(type) {
    const pkg = currentDetailPkg;
    if (type === 'angle') {
        if (document.getElementById('angleSwitch').checked) currentAngleList.push(pkg);
        else currentAngleList = currentAngleList.filter(p => p !== pkg);
    }
    if (type === 'game') {
        if (document.getElementById('gameSwitch').checked) currentGameList.push(pkg);
        else currentGameList = currentGameList.filter(p => p !== pkg);
    }
    await saveKyroosConfig();
}

async function fetchCurrentRes() {
    const sizeRaw = await execShell("wm size");
    const match = sizeRaw.match(/(\d+)x(\d+)/);
    if (match) {
        document.getElementById('resW').value = match[1];
        document.getElementById('resH').value = match[2];
    }
}

async function applyResolution() {
    const w = document.getElementById('resW').value;
    const h = document.getElementById('resH').value;
    if (w && h) await execShell(`wm size ${w}x${h}`);
}

async function resetResolution() {
    await execShell("wm size reset");
    fetchCurrentRes();
}

async function saveKyroosConfig() {
    initializePaths();
    const config = [
        `deep=${document.getElementById('deepSwitch').checked ? "on" : "off"}`,
        `power=${document.getElementById('powerSwitch').checked ? "on" : "off"}`,
        `chace=${document.getElementById('cacheSwitch').checked ? "on" : "off"}`,
        `brutal=${document.getElementById('brutalSwitch').checked ? "on" : "off"}`,
        `angle=${cleanList(currentAngleList).join(',') || "none"}`,
        `game=${cleanList(currentGameList).join(',') || "none"}`,
        `dev=${cleanList(currentDevList).join(',') || "none"}`
    ].join('\n');
    await execShell(`echo "${config}" > ${CONFIG_PATH}`);
}

async function loadConfig() {
    initializePaths();
    const content = await execShell(`cat ${CONFIG_PATH}`).catch(() => "");
    if (content && !content.includes("No such file")) {
        document.getElementById('deepSwitch').checked = content.includes('deep=on');
        document.getElementById('powerSwitch').checked = content.includes('power=on');
        document.getElementById('cacheSwitch').checked = content.includes('chace=on');
        document.getElementById('brutalSwitch').checked = content.includes('brutal=on');
        const angleMatch = content.match(/angle=([^\n]+)/);
        currentAngleList = angleMatch && angleMatch[1] !== 'none' ? angleMatch[1].split(',') : [];
    }
}

async function startOpapsScan() {
    const raw = await execShell("pm list packages -3").catch(() => "");
    opapsPackages = raw.split('\n').map(p => p.replace('package:', '').trim()).filter(p => p);
    renderOpapsList();
}

function renderOpapsList(filter = '') {
    const container = document.getElementById('opaps-list-target');
    const filtered = opapsPackages.filter(p => p.toLowerCase().includes(filter.toLowerCase()));
    container.innerHTML = '';
    filtered.forEach(pkg => {
        const div = document.createElement('div');
        div.className = 'app-card-item';
        div.onclick = () => { selectedOpapsPkg = pkg; document.getElementById('opapsConfirmModal').classList.add('show'); };
        div.innerHTML = `<span>${pkg}</span>`;
        container.appendChild(div);
    });
}

async function runOpapsConfirmed() {
    if (!selectedOpapsPkg) return;
    document.getElementById('opapsConfirmModal').classList.remove('show');
    try {
        await runScriptFile("opaps.sh", selectedOpapsPkg);
        alert("Success");
    } catch (e) { alert("Error: " + e); }
}

window.onload = async function () {
    initializePaths();
    const appConfigEnabled = localStorage.getItem('advAppConfig') === 'true';
    if(document.getElementById('appConfigSwitch')) document.getElementById('appConfigSwitch').checked = appConfigEnabled;
    updateHomeData();
    await loadConfig();
    fetchCurrentRes();
};

setInterval(checkStatus, 5000);
